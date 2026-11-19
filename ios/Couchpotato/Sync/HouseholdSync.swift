import Foundation

/// Joins the household of the TVs and keeps profiles, favourites and "Weiterschauen" in step –
/// end-to-end encrypted, same server function and data format as CloudSync.kt on the TVs.
/// The iPhone never creates a household: the main TV owns it (and decides whether to share the IPTV login).
@MainActor
final class HouseholdSync: ObservableObject {
    enum SyncError: LocalizedError {
        case message(String)
        var errorDescription: String? { if case .message(let m) = self { return m }; return nil }
    }

    struct IptvShare { let login: XtreamLogin }

    @Published private(set) var linked: Bool
    @Published private(set) var lastSync: Date?
    @Published private(set) var busy = false

    let data: HouseholdData
    private let baselineURL: URL
    private var version: Int64 { get { Int64(UserDefaults.standard.integer(forKey: "cp.sync.version")) } set { UserDefaults.standard.set(newValue, forKey: "cp.sync.version") } }

    init(data: HouseholdData, directory: URL) {
        self.data = data
        baselineURL = directory.appendingPathComponent("household-base.json")
        linked = Keychain.data("household.keys") != nil
        lastSync = UserDefaults.standard.object(forKey: "cp.sync.at") as? Date
    }

    static var configured: Bool { !Config.supabaseURL.isEmpty && !Config.supabaseKey.isEmpty }

    var code: String? { Keychain.string("household.code").map(HouseholdCode.display) }

    // MARK: join / sync / leave

    /// Returns the IPTV login when the main TV shares it – the caller asks before using it.
    func join(code raw: String) async throws -> IptvShare? {
        guard Self.configured else { throw SyncError.message("Der Abgleich ist in dieser Version noch nicht eingerichtet.") }
        let code = HouseholdCode.normalize(raw)
        guard code.count == HouseholdCode.length else { throw SyncError.message("Der Haushalts-Code hat \(HouseholdCode.length) Zeichen.") }
        busy = true
        defer { busy = false }
        let keys = await Task.detached(priority: .userInitiated) { HouseholdKeys.derive(code: code) }.value
        let res: [String: Any]
        do {
            res = try await Self.call("pull", ["auth": keys.authHex])
        } catch Remote.notFound {
            throw SyncError.message("Diesen Haushalts-Code gibt es nicht (mehr). Bitte am Fernseher unter Einstellungen → Haushalt nachsehen.")
        }
        guard let blob = res["blob"] as? String, let remote = Self.open(blob, keys) else {
            throw SyncError.message("Der Code passt nicht – bitte noch einmal eingeben.")
        }
        Keychain.set("household.keys", keys.encoded)
        Keychain.setString("household.code", code)
        version = Self.int(res["version"])
        data.replace(with: remote)
        saveBaseline(remote)
        linked = true
        return Self.iptv(remote)
    }

    @discardableResult
    func sync() async throws -> IptvShare? {
        guard Self.configured, let k = Keychain.data("household.keys"), let keys = HouseholdKeys(encoded: k) else { return nil }
        if busy { return nil }
        busy = true
        defer { busy = false }
        let baseline = loadBaseline()
        for _ in 0..<3 {
            let local = data.snapshot
            let res: [String: Any]
            do {
                res = try await Self.call("pull", ["auth": keys.authHex])
            } catch Remote.notFound {
                throw SyncError.message("Der Haushalts-Code wurde am Hauptgerät erneuert – bitte den neuen Code eingeben.")
            }
            let remoteVersion = Self.int(res["version"])
            guard let blob = res["blob"] as? String, let remote = Self.open(blob, keys) else {
                throw SyncError.message("Die Haushaltsdaten lassen sich nicht entschlüsseln.")
            }
            var merged = baseline.map { SnapshotMerge.merge(base: $0, local: local, remote: remote) } ?? remote
            // the IPTV login belongs to the main TV: pass it through exactly as it is
            if let iptv = remote["iptv"] { merged["iptv"] = iptv }
            if !SnapshotMerge.same(merged, remote) {
                let sealed = try keys.seal(JSONSerialization.data(withJSONObject: merged))
                do {
                    let pushed = try await Self.call("push", ["auth": keys.authHex, "blob": sealed, "base_version": remoteVersion])
                    version = Self.int(pushed["version"])
                } catch Remote.conflict {
                    continue   // a TV was faster – fetch its state and merge again
                }
            } else {
                version = remoteVersion
            }
            data.replace(with: merged)
            saveBaseline(merged)
            lastSync = Date()
            UserDefaults.standard.set(lastSync, forKey: "cp.sync.at")
            return Self.iptv(merged)
        }
        throw SyncError.message("Andere Geräte gleichen gerade ab – bitte gleich noch einmal versuchen.")
    }

    /// Leaves on this iPhone only; the household and the TVs stay as they are.
    func leave() {
        Keychain.set("household.keys", nil)
        Keychain.set("household.code", nil)
        try? FileManager.default.removeItem(at: baselineURL)
        UserDefaults.standard.removeObject(forKey: "cp.sync.at")
        data.clear()
        linked = false
        lastSync = nil
    }

    // MARK: helpers

    private func loadBaseline() -> [String: Any]? {
        guard let d = try? Data(contentsOf: baselineURL) else { return nil }
        return try? JSONSerialization.jsonObject(with: d) as? [String: Any]
    }

    private func saveBaseline(_ snap: [String: Any]) {
        var s = snap
        s.removeValue(forKey: "iptv")   // the login is never kept in plain files
        guard let d = try? JSONSerialization.data(withJSONObject: s) else { return }
        try? d.write(to: baselineURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private static func open(_ blob: String, _ keys: HouseholdKeys) -> [String: Any]? {
        keys.open(blob).flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
    }

    private static func iptv(_ snap: [String: Any]) -> IptvShare? {
        guard let o = snap["iptv"] as? [String: Any],
              let url = o["url"] as? String, let user = o["user"] as? String, let pass = o["pass"] as? String else { return nil }
        let login = XtreamLogin(server: url, user: user, password: pass)
        return login.isValid ? IptvShare(login: login) : nil
    }

    private static func int(_ v: Any?) -> Int64 { (v as? NSNumber)?.int64Value ?? 0 }

    private enum Remote: Error { case notFound, conflict }

    private static func call(_ action: String, _ extra: [String: Any]) async throws -> [String: Any] {
        guard let url = URL(string: Config.supabaseURL + "/functions/v1/couchpotato-sync") else { throw SyncError.message("Abgleich nicht eingerichtet") }
        var req = URLRequest(url: url, timeoutInterval: 30)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(Config.supabaseKey, forHTTPHeaderField: "apikey")
        req.setValue("Bearer \(Config.supabaseKey)", forHTTPHeaderField: "Authorization")
        var body = extra
        body["action"] = action
        body["v"] = 3
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (d, response) = try await URLSession.shared.data(for: req)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        switch code {
        case 200..<300: return (try? JSONSerialization.jsonObject(with: d) as? [String: Any]) ?? [:]
        case 404: throw Remote.notFound
        case 409: throw Remote.conflict
        case 426: throw SyncError.message("Bitte Couchpotato aktualisieren – der Haushalt nutzt ein neueres Format.")
        default: throw SyncError.message("Der Abgleich antwortet mit Fehler \(code).")
        }
    }
}

enum Config {
    static var supabaseURL: String { (Bundle.main.object(forInfoDictionaryKey: "CPSupabaseURL") as? String ?? "").trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/")) }
    static var supabaseKey: String { (Bundle.main.object(forInfoDictionaryKey: "CPSupabaseKey") as? String ?? "").trimmingCharacters(in: .whitespaces) }
}
