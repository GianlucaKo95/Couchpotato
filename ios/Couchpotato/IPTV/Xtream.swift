import Foundation

struct XtreamLogin: Codable, Equatable {
    var server: String
    var user: String
    var password: String

    var isValid: Bool { !server.trimmed.isEmpty && !user.trimmed.isEmpty && !password.trimmed.isEmpty }
    var base: String { Self.normalize(server) }
    var host: String? { URL(string: base)?.host?.lowercased() }

    /// Accepts "host:port", "http://host:port/" or a pasted get.php / player_api.php link.
    static func normalize(_ input: String) -> String {
        var s = input.trimmed
        if s.isEmpty { return s }
        if !s.hasPrefix("http://") && !s.hasPrefix("https://") { s = "http://" + s }
        for marker in ["/player_api.php", "/get.php", "/xmltv.php"] {
            if let r = s.range(of: marker) { s = String(s[..<r.lowerBound]) }
        }
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }

    /// Reads server, user and password out of a provider link (get.php?username=…&password=…,
    /// /playlist/U/P/…, /live/U/P/123.ts …). nil when the link carries no login.
    static func fromLink(_ input: String) -> XtreamLogin? {
        let link = input.trimmed.trimmingCharacters(in: CharacterSet(charactersIn: "\"'<>"))
        guard !link.isEmpty else { return nil }
        let withScheme = link.hasPrefix("http://") || link.hasPrefix("https://") ? link : "http://" + link
        guard let c = URLComponents(string: withScheme), let host = c.host, !host.isEmpty else { return nil }
        let root = "\(c.scheme ?? "http")://\(host)\(c.port.map { ":\($0)" } ?? "")"
        let q = Dictionary((c.queryItems ?? []).map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { a, _ in a })
        if let u = q["username"]?.trimmed, let p = q["password"]?.trimmed, !u.isEmpty, !p.isEmpty {
            return XtreamLogin(server: normalize(withScheme.components(separatedBy: "?")[0]), user: u, password: p)
        }
        let seg = c.path.split(separator: "/").map(String.init).filter { !$0.isEmpty }
        if seg.count >= 3, ["playlist", "get", "live", "movie", "series", "timeshift"].contains(seg[0].lowercased()) {
            return XtreamLogin(server: root, user: seg[1], password: seg[2])
        }
        if seg.count >= 3, seg[2].split(separator: ".")[0].allSatisfy(\.isNumber) {
            return XtreamLogin(server: root, user: seg[0], password: seg[1])
        }
        return nil
    }

    static func load() -> XtreamLogin? {
        Keychain.data("iptv.login").flatMap { try? JSONDecoder().decode(XtreamLogin.self, from: $0) }
    }

    static func save(_ login: XtreamLogin?) {
        Keychain.set("iptv.login", login.flatMap { try? JSONEncoder().encode($0) })
    }
}

struct LiveCategory: Codable, Hashable, Identifiable {
    let id: String
    let name: String
}

struct LiveChannel: Codable, Hashable, Identifiable {
    let id: Int64
    let name: String
    let logo: String?
    let cat: String?
}

/// Minimal Xtream client. Every request goes through [RequestGate] – the provider locks the account
/// when it gets too many, so the phone is as careful as the TVs.
struct XtreamClient {
    let login: XtreamLogin
    var background = false

    private func api(_ action: String?, _ extra: [URLQueryItem] = []) -> URL? {
        var c = URLComponents(string: login.base + "/player_api.php")
        c?.queryItems = [URLQueryItem(name: "username", value: login.user), URLQueryItem(name: "password", value: login.password)]
            + (action.map { [URLQueryItem(name: "action", value: $0)] } ?? []) + extra
        return c?.url
    }

    private func get(_ url: URL?) async throws -> Any {
        guard let url else { throw XtreamError.badAnswer("Server-Adresse prüfen") }
        let data = try await RequestGate.shared.run(background: background) {
            var req = URLRequest(url: url, timeoutInterval: 60)
            req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
            let (d, r) = try await URLSession.shared.data(for: req)
            let code = (r as? HTTPURLResponse)?.statusCode ?? 0
            if [401, 403, 404, 429, 509].contains(code) {
                // the provider locks the account for a while after too many requests: stay away completely
                let until = await RequestGate.shared.blockNow()
                throw XtreamError.blocked(until: until, code: code)
            }
            guard (200..<300).contains(code) else { throw XtreamError.http(code) }
            return d
        }
        do {
            return try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        } catch {
            throw XtreamError.badAnswer("Keine gültige Antwort – Server-Adresse prüfen")
        }
    }

    /// One request: checks the login and returns a short status line.
    func checkLogin() async throws -> String {
        guard let root = try await get(api(nil)) as? [String: Any], let ui = root["user_info"] as? [String: Any] else {
            throw XtreamError.badAnswer("Keine Kontodaten erhalten")
        }
        guard Self.string(ui["auth"]) == "1" else { throw XtreamError.badAnswer("Login abgelehnt – Benutzername/Passwort prüfen") }
        var line = "Verbunden · Status: \(Self.string(ui["status"]) ?? "unbekannt")"
        if let exp = Self.string(ui["exp_date"]).flatMap(Double.init) {
            line += " · gültig bis " + Date(timeIntervalSince1970: exp).formatted(date: .numeric, time: .omitted)
        }
        return line
    }

    /// Two requests: categories and all channels.
    func liveCatalog() async throws -> LiveCatalog {
        guard let cats = try await get(api("get_live_categories")) as? [[String: Any]] else {
            throw XtreamError.badAnswer("Kategorien konnten nicht geladen werden")
        }
        guard let streams = try await get(api("get_live_streams")) as? [[String: Any]] else {
            throw XtreamError.badAnswer("Sender konnten nicht geladen werden")
        }
        let categories = cats.compactMap { o -> LiveCategory? in
            guard let id = Self.string(o["category_id"]) else { return nil }
            return LiveCategory(id: id, name: Self.string(o["category_name"]) ?? id)
        }
        var seen = Set<Int64>()
        let channels = streams.compactMap { o -> LiveChannel? in
            guard let id = Self.string(o["stream_id"]).flatMap({ Int64($0) }), seen.insert(id).inserted else { return nil }
            let logo = Self.string(o["stream_icon"]).flatMap { $0.hasPrefix("http") ? $0 : nil }
            return LiveChannel(id: id, name: Names.display(Self.string(o["name"]) ?? ""), logo: logo, cat: Self.string(o["category_id"]))
        }
        return LiveCatalog(categories: categories, channels: channels, fetchedAt: Date())
    }

    /// Live channels as HLS – the format the iPhone plays natively (with AirPlay and picture in picture).
    func liveURL(_ id: Int64) -> URL? { stream("live", id, "m3u8") }

    func movieURL(_ id: Int64, ext: String) -> URL? { stream("movie", id, ext) }
    func episodeURL(_ id: Int64, ext: String) -> URL? { stream("series", id, ext) }

    private func stream(_ segment: String, _ id: Int64, _ ext: String) -> URL? {
        let u = login.user.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? login.user
        let p = login.password.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? login.password
        return URL(string: "\(login.base)/\(segment)/\(u)/\(p)/\(id).\(ext)")
    }

    static let userAgent = "Couchpotato/0.1"

    static func string(_ v: Any?) -> String? {
        switch v {
        case let s as String: return s.trimmed.isEmpty ? nil : s.trimmed
        case let n as NSNumber: return n.stringValue
        default: return nil
        }
    }
}

struct LiveCatalog: Codable {
    var categories: [LiveCategory]
    var channels: [LiveChannel]
    var fetchedAt: Date
}

enum XtreamError: LocalizedError {
    case blocked(until: Date, code: Int?)
    case budget(background: Bool)
    case http(Int)
    case badAnswer(String)

    var errorDescription: String? {
        switch self {
        case .blocked(let until, let code):
            let t = until.formatted(date: .omitted, time: .shortened)
            return "Anbieter sperrt gerade\(code.map { " (HTTP \($0))" } ?? "") – vermutlich wegen zu vieler Anfragen. Couchpotato pausiert und versucht es ab \(t) Uhr wieder."
        case .budget(let background):
            return background
                ? "Hintergrund-Abfragen pausiert (Stundenlimit erreicht) – geht später automatisch weiter."
                : "Zu viele Anfragen in der letzten Stunde – Couchpotato bremst, um eine Sperre beim Anbieter zu vermeiden. Bitte etwas später erneut versuchen."
        case .http(let code): return "Server antwortet mit HTTP \(code)"
        case .badAnswer(let m): return m
        }
    }
}

/// The request brake, like on the TVs: one request at a time, at least 2.5 s apart, at most 40 per hour
/// (20 for work nobody waits for), and 15 minutes of complete silence after the provider answered with a lock code.
/// Kept across app starts.
actor RequestGate {
    static let shared = RequestGate()

    static let minGap: TimeInterval = 2.5
    static let maxPerHour = 40
    static let backgroundPerHour = 20
    static let cooldown: TimeInterval = 15 * 60
    static let imageGap: TimeInterval = 1.2
    static let imagesPerHour = 60

    private var requests: [Date]
    private var images: [Date]
    private var last: Date
    private(set) var blockedUntil: Date
    private var running = false
    private let defaults = UserDefaults.standard

    init() {
        let now = Date()
        func dates(_ key: String) -> [Date] {
            (UserDefaults.standard.array(forKey: key) as? [Double] ?? []).map(Date.init(timeIntervalSince1970:))
                .filter { now.timeIntervalSince($0) < 3600 && $0.timeIntervalSince(now) < 300 }
        }
        requests = dates("cp.gate.requests")
        images = dates("cp.gate.images")
        last = Date(timeIntervalSince1970: UserDefaults.standard.double(forKey: "cp.gate.last"))
        let until = Date(timeIntervalSince1970: UserDefaults.standard.double(forKey: "cp.gate.blocked"))
        blockedUntil = until.timeIntervalSince(now) <= Self.cooldown + 300 ? until : .distantPast
    }

    func run<T>(background: Bool, _ op: () async throws -> T) async throws -> T {
        try await pass(image: false, background: background, op)
    }

    func runImage<T>(_ op: () async throws -> T) async throws -> T {
        try await pass(image: true, background: true, op)
    }

    private func pass<T>(image: Bool, background: Bool, _ op: () async throws -> T) async throws -> T {
        while running { try await Task.sleep(nanoseconds: 150_000_000) }
        running = true
        defer { running = false }
        let now = Date()
        if now < blockedUntil { throw XtreamError.blocked(until: blockedUntil, code: nil) }
        prune(now)
        if image {
            if images.count >= Self.imagesPerHour { throw XtreamError.budget(background: true) }
        } else if requests.count >= (background ? Self.backgroundPerHour : Self.maxPerHour) {
            throw XtreamError.budget(background: background)
        }
        let wait = last.addingTimeInterval(image ? Self.imageGap : Self.minGap).timeIntervalSince(now)
        if wait > 0 { try await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000)) }
        defer {
            last = Date()
            if image { images.append(last) } else { requests.append(last) }
            persist()
        }
        return try await op()
    }

    func blockNow() -> Date {
        blockedUntil = Date().addingTimeInterval(Self.cooldown)
        persist()
        return blockedUntil
    }

    /// Only when the login really changed – a new account isn't locked.
    func clearBlock() {
        blockedUntil = .distantPast
        persist()
    }

    func requestsLastHour() -> Int {
        prune(Date())
        return requests.count
    }

    private func prune(_ now: Date) {
        requests.removeAll { now.timeIntervalSince($0) >= 3600 || $0.timeIntervalSince(now) > 300 }
        images.removeAll { now.timeIntervalSince($0) >= 3600 || $0.timeIntervalSince(now) > 300 }
    }

    private func persist() {
        defaults.set(requests.map(\.timeIntervalSince1970), forKey: "cp.gate.requests")
        defaults.set(images.map(\.timeIntervalSince1970), forKey: "cp.gate.images")
        defaults.set(last.timeIntervalSince1970, forKey: "cp.gate.last")
        defaults.set(blockedUntil.timeIntervalSince1970, forKey: "cp.gate.blocked")
    }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
