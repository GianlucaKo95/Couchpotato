import CryptoKit
import Foundation
import Network

/// A Couchpotato TV of the household in the same Wi-Fi (see TvLink.kt on the TV).
struct TvDevice: Identifiable, Hashable {
    let name: String
    let host: String
    let port: Int
    let key: Data
    var id: String { name }

    static func == (a: TvDevice, b: TvDevice) -> Bool { a.name == b.name && a.host == b.host && a.port == b.port }
    func hash(into h: inout Hasher) { h.combine(name); h.combine(host); h.combine(port) }

    func get(_ target: String, timeout: TimeInterval = 20) async throws -> Data {
        try await request("GET", target, body: Data(), timeout: timeout)
    }

    @discardableResult
    func post(_ target: String, _ json: [String: Any]) async throws -> [String: Any] {
        let body = try JSONSerialization.data(withJSONObject: json)
        let d = try await request("POST", target, body: body, timeout: 25)
        let o = (try? JSONSerialization.jsonObject(with: d) as? [String: Any]) ?? [:]
        if let e = o["error"] as? String { throw XtreamError.badAnswer(e) }
        return o
    }

    func getJSON(_ target: String) async throws -> [String: Any] {
        (try JSONSerialization.jsonObject(with: try await get(target, timeout: 6)) as? [String: Any]) ?? [:]
    }

    /// Signed like the TV expects: X-CP-Link: <unix seconds>.<HMAC-SHA256(seconds \n METHOD \n target \n sha256(body))>.
    private func request(_ method: String, _ target: String, body: Data, timeout: TimeInterval) async throws -> Data {
        guard let url = URL(string: "http://\(host):\(port)\(target)") else { throw URLError(.badURL) }
        var req = URLRequest(url: url, timeoutInterval: timeout)
        req.httpMethod = method
        let ts = String(Int(Date().timeIntervalSince1970))
        let bodyHash = SHA256.hash(data: body).map { String(format: "%02x", $0) }.joined()
        let mac = HMAC<SHA256>.authenticationCode(for: Data("\(ts)\n\(method)\n\(target)\n\(bodyHash)".utf8), using: SymmetricKey(data: key))
        req.setValue("\(ts).\(Data(mac).map { String(format: "%02x", $0) }.joined())", forHTTPHeaderField: "X-CP-Link")
        if method == "POST" {
            req.httpBody = body
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (d, r) = try await URLSession.shared.data(for: req)
        let code = (r as? HTTPURLResponse)?.statusCode ?? 0
        if code == 403 { throw XtreamError.badAnswer("Der Fernseher gehört zu einem anderen Haushalt") }
        guard code == 200 else { throw XtreamError.http(code) }
        return d
    }

}

/// Finds the household's TVs via Bonjour (_couchpotato._tcp) while the app is in front.
@MainActor
final class TvBrowser: ObservableObject {
    @Published private(set) var devices: [TvDevice] = []
    private var browser: NWBrowser?
    private var resolving: [String: NWConnection] = [:]
    private var key: Data?

    func start(linkKey: Data?) {
        guard let linkKey else { stop(); devices = []; return }
        if browser != nil, key == linkKey { return }
        stop()
        key = linkKey
        let tag = HouseholdKeys.tag(linkKey)
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: "_couchpotato._tcp", domain: nil), using: .tcp)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            Task { @MainActor in self?.update(results, tag: tag) }
        }
        b.start(queue: .main)
        browser = b
    }

    func stop() {
        browser?.cancel()
        browser = nil
        resolving.values.forEach { $0.cancel() }
        resolving = [:]
    }

    private func update(_ results: Set<NWBrowser.Result>, tag: String) {
        var names = Set<String>()
        for r in results {
            guard case let .service(name, _, _, _) = r.endpoint else { continue }
            if case let .bonjour(txt) = r.metadata, txt["h"] != tag { continue }   // a TV of another household
            names.insert(name)
            if devices.contains(where: { $0.name == name }) || resolving[name] != nil { continue }
            resolve(r.endpoint, name: name)
        }
        devices.removeAll { !names.contains($0.name) }
    }

    /// Bonjour gives a service name; a short TCP connection tells the IPv4 address and port.
    private func resolve(_ endpoint: NWEndpoint, name: String) {
        let params = NWParameters.tcp
        (params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options)?.version = .v4
        let c = NWConnection(to: endpoint, using: params)
        resolving[name] = c
        c.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in
                guard let self else { return }
                switch state {
                case .ready:
                    if case let .hostPort(host, port) = c.currentPath?.remoteEndpoint, let key = self.key {
                        var h = "\(host)"
                        if let i = h.firstIndex(of: "%") { h = String(h[..<i]) }
                        let d = TvDevice(name: name, host: h, port: Int(port.rawValue), key: key)
                        self.devices.removeAll { $0.name == name }
                        self.devices.append(d)
                        self.devices.sort { $0.name < $1.name }
                    }
                    c.cancel()
                    self.resolving[name] = nil
                case .failed, .cancelled:
                    self.resolving[name] = nil
                default: break
                }
            }
        }
        c.start(queue: .main)
    }
}
