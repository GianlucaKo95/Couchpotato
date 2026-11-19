import Foundation

/// One title of the provider's catalogue – the same columns as the TVs' catalog_v3.tsv,
/// so the iPhone can take the file straight from a TV.
struct Entry: Hashable, Identifiable {
    let kind: Character      // m = film, s = series, l = live channel
    let itemId: Int64
    let ext: String
    let image: String?
    let name: String
    let norm: String
    let cat: String?
    let added: Int64          // unix seconds (films: added, series: last change)
    let rating: Float
    let genre: String?
    let year: String?
    let tmdb: Int64?

    var id: String { "\(kind):\(itemId)" }
    var categoryKey: String { "\(kind):\(cat ?? "")" }

    var channel: LiveChannel { LiveChannel(id: itemId, name: name, logo: image, cat: cat) }

    static let movie: Character = "m", series: Character = "s", live: Character = "l"
}

struct Catalog {
    var entries: [Entry]
    var categories: [Character: [LiveCategory]]
    var fetchedAt: Date
    var source: String          // "Fernseher „…“" or "Anbieter"

    static let empty = Catalog(entries: [], categories: [:], fetchedAt: .distantPast, source: "")

    // MARK: files (same format as on the TV)

    static func parseTSV(_ text: String) -> [Entry] {
        var out: [Entry] = []
        out.reserveCapacity(20_000)
        var seen = Set<String>()
        text.enumerateLines { line, _ in
            let p = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
            guard p.count >= 6, let kind = p[0].first, let id = Int64(p[1]) else { return }
            func col(_ i: Int) -> String? { i < p.count && !p[i].isEmpty ? p[i] : nil }
            let e = Entry(
                kind: kind, itemId: id, ext: col(2) ?? "mp4", image: col(3), name: Names.display(p[4]),
                norm: p[5], cat: col(6), added: col(7).flatMap { Int64($0) } ?? 0,
                rating: col(8).flatMap { Float($0) } ?? 0, genre: col(9), year: col(10), tmdb: col(11).flatMap { Int64($0) }
            )
            if seen.insert(e.id).inserted { out.append(e) }
        }
        return out
    }

    static func tsvLine(_ e: Entry) -> String {
        func clean(_ s: String?) -> String { (s ?? "").replacingOccurrences(of: "\t", with: " ").replacingOccurrences(of: "\n", with: " ").replacingOccurrences(of: "\r", with: " ") }
        return [String(e.kind), String(e.itemId), clean(e.ext), clean(e.image), clean(e.name), e.norm, clean(e.cat), String(e.added),
                e.rating > 0 ? String(format: "%.1f", e.rating) : "", clean(e.genre), e.year ?? "", e.tmdb.map(String.init) ?? ""].joined(separator: "\t")
    }

    static func parseCategories(_ data: Data) -> [Character: [LiveCategory]] {
        guard let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        var out: [Character: [LiveCategory]] = [:]
        for k in ["m", "s", "l"] {
            out[Character(k)] = (o[k] as? [[String: Any]] ?? []).compactMap { c in
                guard let id = XtreamClient.string(c["id"]) else { return nil }
                return LiveCategory(id: id, name: XtreamClient.string(c["name"]) ?? id)
            }
        }
        return out
    }

    static func categoriesJSON(_ cats: [Character: [LiveCategory]]) -> Data {
        var o: [String: Any] = [:]
        for (k, list) in cats { o[String(k)] = list.map { ["id": $0.id, "name": $0.name] } }
        return (try? JSONSerialization.data(withJSONObject: o)) ?? Data("{}".utf8)
    }

    // MARK: search (like the TVs)

    /// "Café – Größe!" → "cafe grosse": case, accents and punctuation don't matter.
    static func normalize(_ s: String) -> String {
        let folded = s.replacingOccurrences(of: "ß", with: "ss").folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "de_DE")).lowercased()
        let mapped = folded.unicodeScalars.map { CharacterSet.letters.contains($0) || CharacterSet.decimalDigits.contains($0) ? Character($0) : " " }
        return String(mapped).split(separator: " ").joined(separator: " ")
    }

    func search(_ query: String, allowed: (Entry) -> Bool, limit: Int = 40) -> [Character: [Entry]] {
        let q = Self.normalize(query)
        let tokens = q.split(separator: " ").map(String.init)
        guard q.count >= 2, let first = tokens.first else { return [:] }
        func score(_ e: Entry) -> Int {
            if e.norm.hasPrefix(q) { return 0 }
            if (" " + e.norm).contains(" " + q) { return 1 }
            if (" " + e.norm).contains(" " + first) { return 2 }
            return 3
        }
        let hits = entries.filter { e in tokens.allSatisfy { e.norm.contains($0) } && allowed(e) }
            .sorted { (score($0), $0.name.count) < (score($1), $1.name.count) }
        var out: [Character: [Entry]] = [:]
        for k in [Entry.movie, Entry.series, Entry.live] {
            out[k] = Array(hits.filter { $0.kind == k }.prefix(limit))
        }
        return out
    }
}

/// Keeps the catalogue on the iPhone. Sources, in this order:
/// 1. a Couchpotato TV of the household in the same Wi-Fi (no request to the provider at all),
/// 2. the provider itself: 6 requests (categories + lists for films, series, live), at most once a day.
actor CatalogStore {
    private let dir: URL
    private var tsv: URL { dir.appendingPathComponent("catalog_v3.tsv") }
    private var cats: URL { dir.appendingPathComponent("categories_v3.json") }
    private var meta: URL { dir.appendingPathComponent("catalog-source.txt") }

    init(directory: URL) { dir = directory }

    func load() -> Catalog? {
        guard let text = try? String(contentsOf: tsv, encoding: .utf8), let c = try? Data(contentsOf: cats) else { return nil }
        let date = (try? FileManager.default.attributesOfItem(atPath: tsv.path)[.modificationDate] as? Date) ?? .distantPast
        let source = (try? String(contentsOf: meta, encoding: .utf8)) ?? ""
        let entries = Catalog.parseTSV(text)
        return entries.isEmpty ? nil : Catalog(entries: entries, categories: Catalog.parseCategories(c), fetchedAt: date, source: source)
    }

    /// Stores a catalogue file as it came from a TV.
    func store(tsvData: Data, categories: Data, modified: Date, source: String) throws -> Catalog {
        guard let text = String(data: tsvData, encoding: .utf8) else { throw XtreamError.badAnswer("Katalog vom Fernseher unlesbar") }
        let entries = Catalog.parseTSV(text)
        guard !entries.isEmpty else { throw XtreamError.badAnswer("Der Fernseher hat noch keinen Katalog") }
        try tsvData.write(to: tsv, options: .atomic)
        try categories.write(to: cats, options: .atomic)
        try Data(source.utf8).write(to: meta, options: .atomic)
        try? FileManager.default.setAttributes([.modificationDate: modified], ofItemAtPath: tsv.path)
        return Catalog(entries: entries, categories: Catalog.parseCategories(categories), fetchedAt: modified, source: source)
    }

    /// Own sync with the provider: 6 requests through the brake, stops at the first lock.
    func download(_ client: XtreamClient) async throws -> Catalog {
        var categories: [Character: [LiveCategory]] = [:]
        for (kind, action) in [(Entry.movie, "get_vod_categories"), (Entry.series, "get_series_categories"), (Entry.live, "get_live_categories")] {
            categories[kind] = try await client.categories(action)
        }
        var entries: [Entry] = []
        for (kind, action, idField, imageField) in [
            (Entry.movie, "get_vod_streams", "stream_id", "stream_icon"),
            (Entry.series, "get_series", "series_id", "cover"),
            (Entry.live, "get_live_streams", "stream_id", "stream_icon"),
        ] {
            let list = try await client.list(action)
            for o in list {
                guard let id = XtreamClient.string(o[idField]).flatMap({ Int64($0) }) else { continue }
                let name = Names.display(XtreamClient.string(o["name"]) ?? "")
                guard !name.isEmpty else { continue }
                let rating = XtreamClient.string(o["rating"]).flatMap { Float($0.replacingOccurrences(of: ",", with: ".")) } ?? 0
                let rating5 = XtreamClient.string(o["rating_5based"]).flatMap { Float($0.replacingOccurrences(of: ",", with: ".")) } ?? 0
                let yearRaw = ["year", "releaseDate", "release_date", "releasedate"].lazy.compactMap { XtreamClient.string(o[$0]) }.first
                let year = yearRaw.map { String($0.prefix(4)) }.flatMap { $0.count == 4 && $0.allSatisfy(\.isNumber) ? $0 : nil }
                entries.append(Entry(
                    kind: kind, itemId: id,
                    ext: XtreamClient.string(o["container_extension"]) ?? (kind == Entry.live ? "ts" : "mp4"),
                    image: XtreamClient.string(o[imageField]).flatMap { $0.hasPrefix("http") ? $0 : nil },
                    name: name, norm: Catalog.normalize(name), cat: XtreamClient.string(o["category_id"]),
                    added: (XtreamClient.string(o["added"]) ?? XtreamClient.string(o["last_modified"])).flatMap { Int64($0) } ?? 0,
                    rating: min(max(rating > 0 ? rating : rating5 * 2, 0), 10),
                    genre: XtreamClient.string(o["genre"]), year: year,
                    tmdb: (XtreamClient.string(o["tmdb"]) ?? XtreamClient.string(o["tmdb_id"])).flatMap { Int64($0) }.flatMap { $0 > 0 ? $0 : nil }
                ))
            }
        }
        guard !entries.isEmpty else { throw XtreamError.badAnswer("Der Anbieter hat keine Titel geliefert") }
        let text = entries.map(Catalog.tsvLine).joined(separator: "\n") + "\n"
        try Data(text.utf8).write(to: tsv, options: .atomic)
        try Catalog.categoriesJSON(categories).write(to: cats, options: .atomic)
        try Data("Anbieter".utf8).write(to: meta, options: .atomic)
        return Catalog(entries: Catalog.parseTSV(text), categories: categories, fetchedAt: Date(), source: "Anbieter")
    }

    func clear() {
        for f in [tsv, cats, meta] { try? FileManager.default.removeItem(at: f) }
    }
}
