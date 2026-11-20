import Foundation

/// "Für dich" – same scoring as the TVs (Recommender.kt), computed from the catalogue, no request.
/// At first mostly rating and newness; the more you watch (and put on "Meine Liste"),
/// the more your favourite categories and genres count.
enum Recommender {
    static let listWeight = 0.7

    static func recommend(_ entries: [Entry], allowed: (Entry) -> Bool, history: [String: Int64],
                          watchlist: [String: Int64], limit: Int = 30,
                          now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> (items: [Entry], personal: Bool) {
        var byKey: [String: Entry] = [:]
        for e in entries where e.kind != Entry.live { byKey[e.id] = e }
        let watched = history.keys.compactMap { byKey[$0] }
        let listed = watchlist.keys.filter { history[$0] == nil }.compactMap { byKey[$0] }

        var catLove: [String: Double] = [:], genreLove: [String: Double] = [:]
        func love(_ e: Entry, _ w: Double) {
            catLove[e.categoryKey, default: 0] += w
            for g in genres(e.genre) { genreLove[g, default: 0] += w }
        }
        for w in watched {
            let age = Double(now - (history[w.id] ?? now)) / 86_400_000
            love(w, exp(-max(age, 0) / 120))
        }
        for l in listed {
            let age = Double(now - (watchlist[l.id] ?? now)) / 86_400_000
            love(l, listWeight * exp(-max(age, 0) / 180))
        }
        let maxCat = catLove.values.max() ?? 0, maxGenre = genreLove.values.max() ?? 0
        let signals = Double(watched.count) + Double(listed.count) * 0.5
        let personal = min(signals / 12, 1) * 0.65

        let nowSec = now / 1000
        var seen = Set<String>()
        let candidates = entries.filter {
            $0.kind != Entry.live && history[$0.id] == nil && watchlist[$0.id] == nil && allowed($0) && seen.insert($0.id).inserted
        }
        let anyRatings = candidates.contains { $0.rating > 0 }

        func score(_ e: Entry) -> Double {
            let rating = e.rating > 0 ? Double(e.rating) / 10 : (anyRatings ? 0.35 : 0.5)
            let age = e.added > 0 ? Double(nowSec - e.added) / 86_400 : 3650
            let base = 0.75 * rating + 0.25 * exp(-max(age, 0) / 365)
            if personal == 0 { return base }
            let cat = maxCat > 0 ? (catLove[e.categoryKey] ?? 0) / maxCat : 0
            let g = genres(e.genre)
            let genre = maxGenre > 0 && !g.isEmpty ? g.map { (genreLove[$0] ?? 0) / maxGenre }.max()! : 0
            let taste = 0.6 * cat + 0.4 * genre
            return (1 - personal) * base + personal * (0.4 * base + 0.6 * taste)
        }

        let ranked = candidates
            .filter { !anyRatings || $0.rating == 0 || $0.rating >= 5.5 }
            .map { ($0, score($0)) }
            .sorted { $0.1 > $1.1 }

        // variety: at most 4 titles per category
        var perCat: [String: Int] = [:], picked: [Entry] = []
        for (e, _) in ranked {
            let n = perCat[e.categoryKey] ?? 0
            if n >= 4 { continue }
            perCat[e.categoryKey] = n + 1
            picked.append(e)
            if picked.count >= limit { break }
        }
        return (picked, personal > 0)
    }

    static func genres(_ genre: String?) -> [String] {
        guard let genre else { return [] }
        return genre.split(whereSeparator: { ",/&|".contains($0) })
            .map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
            .filter { $0.count > 1 }
    }
}
