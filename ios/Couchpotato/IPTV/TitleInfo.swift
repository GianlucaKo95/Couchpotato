import Foundation

struct Episode: Hashable, Identifiable {
    let id: Int64
    let season: Int
    let number: Int
    let title: String
    let image: String?
    let ext: String
    let duration: Int     // seconds
}

struct SeriesInfo {
    let id: Int64
    let name: String
    let cover: String?
    let plot: String?
    let genre: String?
    let year: String?
    let cast: String?
    let rating: Float
    let episodes: [Episode]   // by season, then number

    var seasons: [Int] { Array(Set(episodes.map(\.season))).sorted() }

    func item(_ e: Episode) -> PlayItem {
        PlayItem(key: "ep:\(e.id)", kind: "episode", streamId: e.id, ext: e.ext, title: name,
                 subtitle: String(format: "S%02d E%02d · %@", e.season, e.number, e.title),
                 image: e.image ?? cover, seriesId: id)
    }

    /// Same rules as XtreamClient.parseSeriesInfo on the TVs.
    static func parse(_ data: Data, id: Int64, fallbackName: String) -> SeriesInfo? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        let info = root["info"] as? [String: Any]
        let s = XtreamClient.string
        let seriesName = s(info?["name"]) ?? fallbackName
        var episodes: [Episode] = []
        func add(_ arr: [[String: Any]], _ seasonHint: Int?) {
            for (i, o) in arr.enumerated() {
                guard let eid = s(o["id"]).flatMap({ Int64($0) }) else { continue }
                let number = s(o["episode_num"]).flatMap { Int($0) } ?? (i + 1)
                let ei = o["info"] as? [String: Any]
                episodes.append(Episode(
                    id: eid, season: s(o["season"]).flatMap { Int($0) } ?? seasonHint ?? 1, number: number,
                    title: Names.episode(s(o["title"]), series: seriesName, number: number),
                    image: s(ei?["movie_image"]).flatMap { $0.hasPrefix("http") ? $0 : nil },
                    ext: s(o["container_extension"]) ?? "mp4",
                    duration: s(ei?["duration_secs"]).flatMap { Int($0) } ?? 0
                ))
            }
        }
        if let eps = root["episodes"] as? [String: Any] {
            for (k, v) in eps { if let arr = v as? [[String: Any]] { add(arr, Int(k)) } }
        } else if let eps = root["episodes"] as? [Any] {
            for (i, v) in eps.enumerated() { if let arr = v as? [[String: Any]] { add(arr, i + 1) } }
        }
        var seen = Set<Int64>()
        episodes = episodes.filter { seen.insert($0.id).inserted }.sorted { ($0.season, $0.number) < ($1.season, $1.number) }
        let yearRaw = s(info?["releaseDate"]) ?? s(info?["release_date"]) ?? s(info?["year"])
        return SeriesInfo(
            id: id, name: Names.display(seriesName),
            cover: s(info?["cover"]).flatMap { $0.hasPrefix("http") ? $0 : nil },
            plot: s(info?["plot"]), genre: s(info?["genre"]),
            year: yearRaw.map { String($0.prefix(4)) }.flatMap { $0.allSatisfy(\.isNumber) ? $0 : nil },
            cast: s(info?["cast"]),
            rating: min(max(s(info?["rating"]).flatMap { Float($0.replacingOccurrences(of: ",", with: ".")) } ?? 0, 0), 10),
            episodes: episodes
        )
    }
}

struct MovieInfo {
    let plot: String?
    let cast: String?
    let director: String?
    let genre: String?
    let duration: String?
    let backdrop: String?

    static func parse(_ data: Data) -> MovieInfo? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any], let i = root["info"] as? [String: Any] else { return nil }
        let s = XtreamClient.string
        let backdrop = (i["backdrop_path"] as? [String])?.first ?? s(i["backdrop_path"])
        return MovieInfo(plot: s(i["plot"]) ?? s(i["description"]), cast: s(i["cast"]) ?? s(i["actors"]), director: s(i["director"]),
                         genre: s(i["genre"]), duration: s(i["duration"]), backdrop: backdrop.flatMap { $0.hasPrefix("http") ? $0 : nil })
    }
}

/// Series and film details. Order: our own copy → a TV of the household (its copy or ONE request through
/// the TV's brake) → ONE request of our own. Series copies are refreshed when the catalogue says the series changed.
actor TitleInfoStore {
    private let seriesDir: URL
    private let movieDir: URL

    init(directory: URL) {
        seriesDir = directory.appendingPathComponent("seriesinfo", isDirectory: true)
        movieDir = directory.appendingPathComponent("vodinfo", isDirectory: true)
        try? FileManager.default.createDirectory(at: seriesDir, withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: movieDir, withIntermediateDirectories: true)
    }

    func series(_ entry: Entry, tv: TvDevice?, client: XtreamClient?) async throws -> SeriesInfo {
        let f = seriesDir.appendingPathComponent("\(entry.itemId).json")
        var stale: SeriesInfo?
        if let d = try? Data(contentsOf: f), let s = SeriesInfo.parse(d, id: entry.itemId, fallbackName: entry.name) {
            stale = s
            let modified = (try? FileManager.default.attributesOfItem(atPath: f.path)[.modificationDate] as? Date) ?? .distantPast
            let age = Date().timeIntervalSince(modified)
            let changedAt = Date(timeIntervalSince1970: TimeInterval(entry.added))
            let upToDate = entry.added > 0 ? modified >= changedAt && age < 14 * 86400 : age < 3 * 86400
            if upToDate { return s }
        }
        if let tv, let d = try? await tv.get("/series?id=\(entry.itemId)"), let s = SeriesInfo.parse(d, id: entry.itemId, fallbackName: entry.name) {
            try? d.write(to: f, options: .atomic)
            return s
        }
        do {
            guard let client else { throw XtreamError.badAnswer("Folgen gibt es nur mit IPTV-Zugang oder einem Fernseher im WLAN") }
            let d = try await client.seriesInfoRaw(entry.itemId)
            guard let s = SeriesInfo.parse(d, id: entry.itemId, fallbackName: entry.name) else { throw XtreamError.badAnswer("Serie konnte nicht geladen werden") }
            try? d.write(to: f, options: .atomic)
            return s
        } catch {
            if let stale { return stale }
            throw error
        }
    }

    /// Film details without asking the provider: our copy or the TV's.
    func cachedMovie(_ id: Int64, tv: TvDevice?) async -> MovieInfo? {
        let f = movieDir.appendingPathComponent("\(id).json")
        if let d = try? Data(contentsOf: f), let m = MovieInfo.parse(d) { return m }
        if let tv, let d = try? await tv.get("/movie?id=\(id)"), let m = MovieInfo.parse(d) {
            try? d.write(to: f, options: .atomic)
            return m
        }
        return nil
    }

    /// ONE request, only when the user asks for it.
    func loadMovie(_ id: Int64, client: XtreamClient) async throws -> MovieInfo {
        let d = try await client.vodInfoRaw(id)
        guard let m = MovieInfo.parse(d) else { throw XtreamError.badAnswer("Keine Details") }
        try? d.write(to: movieDir.appendingPathComponent("\(id).json"), options: .atomic)
        return m
    }

    func clear() {
        try? FileManager.default.removeItem(at: seriesDir)
        try? FileManager.default.removeItem(at: movieDir)
        try? FileManager.default.createDirectory(at: seriesDir, withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: movieDir, withIntermediateDirectories: true)
    }
}
