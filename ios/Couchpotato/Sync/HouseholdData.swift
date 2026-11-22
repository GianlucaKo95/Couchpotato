import Foundation

/// The household's data exactly as the TVs keep it (their SharedPreferences files):
/// file → key → {"type": "string"|"boolean"|"int"|"long"|"float"|"set", "value": …}.
/// The iPhone reads and writes only what it needs and passes everything else through untouched,
/// so a TV never loses anything because of the phone.
@MainActor
final class HouseholdData: ObservableObject {
    typealias Files = [String: [String: Any]]

    @Published private(set) var files: Files = [:]
    private let url: URL

    init(directory: URL) {
        url = directory.appendingPathComponent("household.json")
        if let d = try? Data(contentsOf: url),
           let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any] {
            files = Self.filesOf(o)
        }
    }

    // MARK: reading

    func value(_ file: String, _ key: String) -> Any? { (files[file]?[key] as? [String: Any])?["value"] }
    func string(_ file: String, _ key: String) -> String? { value(file, key) as? String }
    func bool(_ file: String, _ key: String) -> Bool? { value(file, key) as? Bool }
    func stringSet(_ file: String, _ key: String) -> Set<String> { Set(value(file, key) as? [String] ?? []) }
    func keys(_ file: String) -> [String] { files[file].map { Array($0.keys) } ?? [] }

    func json(_ file: String, _ key: String) -> Any? {
        guard let s = string(file, key), let d = s.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed])
    }

    // MARK: writing

    func setString(_ file: String, _ key: String, _ value: String?) {
        put(file, key, value.map { ["type": "string", "value": $0] })
    }

    func setJSON(_ file: String, _ key: String, _ value: Any) {
        guard let d = try? JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]),
              let s = String(data: d, encoding: .utf8) else { return }
        setString(file, key, s)
    }

    func setStringSet(_ file: String, _ key: String, _ value: Set<String>) {
        put(file, key, ["type": "set", "value": value.sorted()])
    }

    func setLong(_ file: String, _ key: String, _ value: Int64) {
        put(file, key, ["type": "long", "value": NSNumber(value: value)])
    }

    func remove(_ file: String, _ key: String) { put(file, key, nil) }

    /// The file the latest change was in; nil = several or all (a sync replaced everything).
    private(set) var lastChanged: String?

    private func put(_ file: String, _ key: String, _ typed: [String: Any]?) {
        var f = files[file] ?? [:]
        f[key] = typed
        lastChanged = file
        files[file] = f
        save()
    }

    // MARK: snapshots (the format of the encrypted household blob)

    var snapshot: [String: Any] { ["version": 1, "preferences": files] }

    func replace(with snapshot: [String: Any]) {
        lastChanged = nil
        files = Self.filesOf(snapshot)
        save()
    }

    func clear() {
        pendingSave?.cancel(); pendingSave = nil
        lastChanged = nil
        files = [:]
        let url = url
        Self.writer.async { try? FileManager.default.removeItem(at: url) }
    }

    nonisolated static func filesOf(_ snapshot: [String: Any]) -> Files {
        let prefs = (snapshot["preferences"] as? [String: Any]) ?? snapshot
        var out: Files = [:]
        for (name, value) in prefs {
            // device-local files of the TVs never belong to the household
            if ["cloudsync", "netguard", "tmdbgate"].contains(name) { continue }
            if let v = value as? [String: Any] { out[name] = v }
        }
        return out
    }

    // Saving: many small changes (progress every 10 s, history, favourites) are collected for a second and
    // written in the background – not the whole file on the main thread for every key.
    private var pendingSave: Task<Void, Never>?
    private static let writer = DispatchQueue(label: "household.save", qos: .utility)

    private func save() {
        pendingSave?.cancel()
        pendingSave = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled else { return }
            self?.flush()
        }
    }

    /// Writes now (also called when the app goes to the background).
    func flush() {
        pendingSave?.cancel(); pendingSave = nil
        let snap = snapshot, url = url
        Self.writer.async {
            guard let d = try? JSONSerialization.data(withJSONObject: snap) else { return }
            try? d.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }
}

/// Three-way merge as on the TVs: a key changed here wins, otherwise the household's value counts.
enum SnapshotMerge {
    static func merge(base: [String: Any], local: [String: Any], remote: [String: Any]) -> [String: Any] {
        let b = HouseholdData.filesOf(base), l = HouseholdData.filesOf(local), r = HouseholdData.filesOf(remote)
        var merged: [String: Any] = [:]
        for file in Set(b.keys).union(l.keys).union(r.keys) {
            let bf = b[file] ?? [:], lf = l[file] ?? [:], rf = r[file] ?? [:]
            var out: [String: Any] = [:]
            for key in Set(bf.keys).union(lf.keys).union(rf.keys) {
                let localChanged = (lf[key] == nil) != (bf[key] == nil) || canonical(lf[key]) != canonical(bf[key])
                if let v = localChanged ? lf[key] : rf[key] { out[key] = v }
            }
            merged[file] = out
        }
        return ["version": 1, "preferences": merged]
    }

    static func canonical(_ value: Any?) -> String {
        guard let value, !(value is NSNull) else { return "null" }
        guard let d = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .fragmentsAllowed]) else { return "\(value)" }
        return String(decoding: d, as: UTF8.self)
    }

    static func same(_ a: [String: Any], _ b: [String: Any]) -> Bool { canonical(a) == canonical(b) }
}
