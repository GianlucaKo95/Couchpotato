import Foundation

struct EpgEntry: Identifiable, Hashable {
    let title: String
    let info: String?
    let start: Date
    let stop: Date
    var id: TimeInterval { start.timeIntervalSince1970 }
    var isNow: Bool { (start..<stop).contains(Date()) }
    var progress: Double { min(max(Date().timeIntervalSince(start) / max(stop.timeIntervalSince(start), 1), 0), 1) }

    var times: String {
        "\(start.formatted(date: .omitted, time: .shortened)) – \(stop.formatted(date: .omitted, time: .shortened))"
    }

    /// Xtream sends titles and descriptions base64-encoded.
    static func parse(_ data: Data) -> [EpgEntry] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let list = root["epg_listings"] as? [[String: Any]] else { return [] }
        func text(_ v: Any?) -> String? {
            guard let raw = XtreamClient.string(v) else { return nil }
            if let d = Data(base64Encoded: raw), let s = String(data: d, encoding: .utf8)?.trimmed, !s.isEmpty { return s }
            return raw
        }
        return list.compactMap { o in
            guard let title = text(o["title"]),
                  let start = XtreamClient.string(o["start_timestamp"]).flatMap(Double.init),
                  let stop = XtreamClient.string(o["stop_timestamp"]).flatMap(Double.init), stop > start else { return nil }
            return EpgEntry(title: title, info: text(o["description"]), start: Date(timeIntervalSince1970: start), stop: Date(timeIntervalSince1970: stop))
        }
        .filter { $0.stop > Date() }
        .sorted { $0.start < $1.start }
    }
}

/// Programme guide per channel. Every channel costs the provider a request, so: kept 20 minutes, taken from a TV
/// of the household when possible (it keeps it for all phones), and asked ourselves only while plenty of the
/// hourly budget is left.
actor EpgStore {
    private var cache: [Int64: (at: Date, entries: [EpgEntry])] = [:]

    func cached(_ id: Int64) -> [EpgEntry]? {
        guard let c = cache[id], Date().timeIntervalSince(c.at) < 20 * 60 else { return nil }
        return c.entries.filter { $0.stop > Date() }
    }

    func load(_ id: Int64, tv: TvDevice?, client: XtreamClient?) async -> [EpgEntry] {
        if let c = cached(id) { return c }
        var data: Data?
        if let tv { data = try? await tv.get("/epg?id=\(id)", timeout: 12) }
        if data == nil, let client, await RequestGate.shared.requestsLastHour() < 25 {
            data = try? await client.shortEpgRaw(id)
        }
        let entries = data.map(EpgEntry.parse) ?? []
        cache[id] = (Date(), entries)
        return entries
    }
}
