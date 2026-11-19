import Combine
import Foundation
import SwiftUI

struct Profile: Identifiable, Hashable {
    let id: String
    let name: String
    let color: Int
    let kid: Bool
    let dailyMinutes: Int   // kids: screen time per day, 0 = no limit
    let bedtime: Int        // kids: minute of the day from which the profile is locked, -1 = none
    /// Data suffix like on the TVs: "" for the first profile, "_<id>" for the others.
    var suffix: String { id == "main" ? "" : "_\(id)" }
}

/// Something to play – the same unit the TVs store for "Weiterschauen".
struct PlayItem: Hashable {
    let key: String          // "vod:<id>", "ep:<id>", "live:<id>"
    let kind: String         // movie, episode, live
    let streamId: Int64
    let ext: String
    let title: String
    let subtitle: String?
    let image: String?
    let seriesId: Int64?

    init(key: String, kind: String, streamId: Int64, ext: String, title: String, subtitle: String?, image: String?, seriesId: Int64?) {
        self.key = key; self.kind = kind; self.streamId = streamId; self.ext = ext
        self.title = title; self.subtitle = subtitle; self.image = image; self.seriesId = seriesId
    }

    init?(json o: [String: Any]) {
        guard let key = o["key"] as? String, let kind = o["kind"] as? String,
              let id = (o["streamId"] as? NSNumber)?.int64Value ?? (o["streamId"] as? String).flatMap({ Int64($0) }) else { return nil }
        self.init(
            key: key, kind: kind, streamId: id, ext: (o["ext"] as? String) ?? "mp4",
            title: Names.display((o["title"] as? String) ?? ""), subtitle: o["subtitle"] as? String,
            image: o["image"] as? String,
            seriesId: (o["seriesId"] as? NSNumber)?.int64Value ?? (o["seriesId"] as? String).flatMap { Int64($0) }
        )
    }

    var json: [String: Any] {
        ["key": key, "kind": kind, "streamId": streamId, "ext": ext, "title": title,
         "subtitle": subtitle ?? NSNull(), "image": image ?? NSNull(), "seriesId": seriesId.map { NSNumber(value: $0) } ?? NSNull()]
    }

    static func live(_ c: LiveChannel) -> PlayItem {
        PlayItem(key: "live:\(c.id)", kind: "live", streamId: c.id, ext: "m3u8", title: c.name, subtitle: nil, image: c.logo, seriesId: nil)
    }

    static func movie(_ e: Entry) -> PlayItem {
        PlayItem(key: "vod:\(e.itemId)", kind: "movie", streamId: e.itemId, ext: e.ext, title: e.name, subtitle: nil, image: e.image, seriesId: nil)
    }
}

struct ProgressEntry: Identifiable, Hashable {
    let item: PlayItem
    let position: Double   // seconds
    let duration: Double
    let updatedAt: Date
    let upNext: Bool
    var id: String { item.key }
    var fraction: Double { duration > 0 ? min(max(position / duration, 0), 1) : 0 }
}

/// What the player shows.
struct Playback: Identifiable {
    enum Engine { case native, vlc }
    let id = UUID()
    let url: URL
    let item: PlayItem
    let start: Double
    let engine: Engine
    var channels: [LiveChannel] = []   // live: switch channels in the player
    var queue: [PlayItem] = []         // episodes that follow
    var fallback: URL?                  // live: MPEG-TS for VLC if the provider has no HLS
}

struct PinRequest: Identifiable {
    let id = UUID()
    let title: String
    let setNew: Bool
    let onSuccess: () -> Void
}

/// App state: household data (profiles, favourites, progress, Meine Liste), IPTV login, catalogue, TVs nearby.
@MainActor
final class AppModel: ObservableObject {
    let data: HouseholdData
    let household: HouseholdSync
    let tvBrowser = TvBrowser()
    let screenTime = ScreenTime()

    @Published var login: XtreamLogin?
    @Published var demo: Bool { didSet { UserDefaults.standard.set(demo, forKey: "cp.demo") } }
    @Published private(set) var catalog: Catalog = .empty
    @Published private(set) var profileId: String { didSet { UserDefaults.standard.set(profileId, forKey: "cp.profile") } }
    @Published var playback: Playback?
    @Published var notice: String?
    @Published private(set) var loadingCatalog = false
    @Published var pendingIptv: XtreamLogin?
    @Published var pinRequest: PinRequest?
    @Published var lockedReason: String?           // kids: screen time is up
    @Published var preferredTV: String? { didSet { UserDefaults.standard.set(preferredTV, forKey: "cp.tv") } }

    let directory: URL
    let catalogStore: CatalogStore
    let titles: TitleInfoStore
    private var syncTask: Task<Void, Never>?
    private var forward: [AnyCancellable] = []

    init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Couchpotato", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        directory = dir
        let data = HouseholdData(directory: dir)
        self.data = data
        household = HouseholdSync(data: data, directory: dir)
        catalogStore = CatalogStore(directory: dir)
        titles = TitleInfoStore(directory: dir)
        login = XtreamLogin.load()
        demo = UserDefaults.standard.bool(forKey: "cp.demo")
        profileId = UserDefaults.standard.string(forKey: "cp.profile") ?? "main"
        preferredTV = UserDefaults.standard.string(forKey: "cp.tv")
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("live.json"))   // catalogue of the first test build
        forward = [
            data.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() },
            household.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() },
            tvBrowser.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() },
            // a TV turned up in the Wi-Fi: maybe it has a newer catalogue – costs the provider nothing
            tvBrowser.$devices.map { $0.map(\.name) }.removeDuplicates().dropFirst().sink { [weak self] names in
                guard !names.isEmpty else { return }
                Task { await self?.refreshCatalog(force: false, background: true) }
            },
        ]
        Task {
            if let c = await catalogStore.load() { catalog = c }
        }
    }

    var ready: Bool { login != nil || household.linked || demo }
    var client: XtreamClient? { login.map { XtreamClient(login: $0) } }
    var demoOnly: Bool { demo && login == nil && catalog.entries.isEmpty }

    // MARK: profiles & kids

    var profiles: [Profile] {
        let list = (data.json("profiles", "list") as? [[String: Any]] ?? []).compactMap { o -> Profile? in
            guard let id = o["id"] as? String, let name = o["name"] as? String else { return nil }
            return Profile(id: id, name: name, color: (o["color"] as? Int) ?? 0, kid: (o["kid"] as? Bool) ?? false,
                           dailyMinutes: (o["limit"] as? Int) ?? 0, bedtime: (o["bed"] as? Int) ?? -1)
        }
        return list.isEmpty ? [Profile(id: "main", name: "Ich", color: 0, kid: false, dailyMinutes: 0, bedtime: -1)] : list
    }

    var profile: Profile { profiles.first { $0.id == profileId } ?? profiles[0] }

    /// Leaving a kids profile needs the parents' PIN (like on the TVs); entering one first sets a PIN if there is none.
    func chooseProfile(_ id: String) {
        guard id != profile.id, let target = profiles.first(where: { $0.id == id }) else { return }
        if profile.kid {
            pinRequest = PinRequest(title: "Eltern-PIN, um das Kinderprofil zu verlassen", setNew: !ParentPin.isSet) { [weak self] in self?.profileId = id }
        } else if target.kid && !ParentPin.isSet {
            pinRequest = PinRequest(title: "Lege zuerst eine Eltern-PIN fest – damit verlässt man das Kinderprofil wieder", setNew: true) { [weak self] in self?.profileId = id }
        } else {
            profileId = id
        }
    }

    /// Categories a kids profile may see ("m:<id>", "s:<id>", "l:<id>"); nil = everything.
    private var kidAllowed: Set<String>? { profile.kid ? data.stringSet("profiles", "kidCats") : nil }

    func allowed(_ e: Entry) -> Bool {
        if data.stringSet("couchpotato", "hiddenCats").contains(e.categoryKey) { return false }
        if let kid = kidAllowed { return kid.contains(e.categoryKey) }
        return true
    }

    // MARK: categories & titles

    func categories(_ kind: Character) -> [LiveCategory] {
        if kind == Entry.live && demoOnly { return DemoSource.categories }
        let all = catalog.categories[kind] ?? []
        let hidden = data.stringSet("couchpotato", "hiddenCats")
        let kid = kidAllowed
        let order = data.json("couchpotato", "catOrder_\(kind)") as? [String] ?? []
        let used = Set(catalog.entries.lazy.filter { $0.kind == kind }.compactMap(\.cat))
        let visible = all.filter { c in
            let key = "\(kind):\(c.id)"
            return !hidden.contains(key) && (kid?.contains(key) ?? true) && used.contains(c.id)
        }
        guard !order.isEmpty else { return visible }
        let rank = Dictionary(order.enumerated().map { ($1, $0) }, uniquingKeysWith: { a, _ in a })
        return visible.enumerated().sorted { a, b in
            (rank[a.element.id] ?? Int.max, a.offset) < (rank[b.element.id] ?? Int.max, b.offset)
        }.map(\.element)
    }

    func titles(_ kind: Character, in category: LiveCategory) -> [Entry] {
        catalog.entries.filter { $0.kind == kind && $0.cat == category.id }.sorted { $0.added > $1.added }
    }

    func newest(_ kind: Character, limit: Int = 30) -> [Entry] {
        Array(catalog.entries.filter { $0.kind == kind && allowed($0) }.sorted { $0.added > $1.added }.prefix(limit))
    }

    func entry(_ kind: Character, _ id: Int64) -> Entry? {
        catalog.entries.first { $0.kind == kind && $0.itemId == id }
    }

    func search(_ q: String) -> [Character: [Entry]] { catalog.search(q, allowed: allowed) }

    // MARK: live TV

    func channels(in category: LiveCategory) -> [LiveChannel] {
        if demoOnly { return DemoSource.channels }
        return catalog.entries.filter { $0.kind == Entry.live && $0.cat == category.id }.map(\.channel)
    }

    var allChannels: [LiveChannel] {
        if demoOnly { return DemoSource.channels }
        return catalog.entries.filter { $0.kind == Entry.live && allowed($0) }.map(\.channel)
    }

    var favorites: [LiveChannel] { channelList("liveFavs") }
    var recent: [LiveChannel] { channelList("liveRecent") }

    func isFavorite(_ c: LiveChannel) -> Bool { favorites.contains { $0.id == c.id } }

    func toggleFavorite(_ c: LiveChannel) {
        var list = favorites
        if let i = list.firstIndex(where: { $0.id == c.id }) { list.remove(at: i) } else { list.append(c) }
        saveChannels("liveFavs", list)
        syncSoon()
    }

    func moveFavorites(from: IndexSet, to: Int) {
        var list = favorites
        list.move(fromOffsets: from, toOffset: to)
        saveChannels("liveFavs", list)
        syncSoon()
    }

    private func channelList(_ key: String) -> [LiveChannel] {
        let arr = data.json("couchpotato", key + profile.suffix) as? [[String: Any]] ?? []
        var seen = Set<Int64>()
        let kid = kidAllowed
        return arr.compactMap { o in
            guard let id = (o["id"] as? NSNumber)?.int64Value, seen.insert(id).inserted else { return nil }
            let c = LiveChannel(id: id, name: Names.display(o["name"] as? String ?? ""), logo: o["logo"] as? String, cat: o["cat"] as? String)
            if let kid, !kid.contains("l:\(c.cat ?? "")") { return nil }
            return c
        }
    }

    private func saveChannels(_ key: String, _ list: [LiveChannel]) {
        let arr: [[String: Any]] = list.map { c in
            var o: [String: Any] = ["id": c.id, "name": c.name]
            if let l = c.logo { o["logo"] = l }
            if let cat = c.cat { o["cat"] = cat }
            return o
        }
        data.setJSON("couchpotato", key + profile.suffix, arr)
    }

    // MARK: Meine Liste (same keys as on the TVs: "m:<id>" / "s:<id>" → added at)

    private var watchlistFile: String { "watchlist" + profile.suffix }

    func onList(_ e: Entry) -> Bool { data.value(watchlistFile, e.id) != nil }

    func toggleList(_ e: Entry) {
        if onList(e) { data.remove(watchlistFile, e.id) } else { data.setLong(watchlistFile, e.id, Int64(Date().timeIntervalSince1970 * 1000)) }
        syncSoon()
    }

    var myList: [Entry] {
        let keys = data.keys(watchlistFile)
        guard !keys.isEmpty else { return [] }
        let added = Dictionary(keys.map { ($0, (data.value(watchlistFile, $0) as? NSNumber)?.int64Value ?? 0) }, uniquingKeysWith: { a, _ in a })
        var found: [String: Entry] = [:]
        for e in catalog.entries where e.kind != Entry.live && added[e.id] != nil && found[e.id] == nil && allowed(e) { found[e.id] = e }
        return found.values.sorted { (added[$0.id] ?? 0) > (added[$1.id] ?? 0) }
    }

    // MARK: catalogue

    func refreshCatalog(force: Bool, background: Bool) async {
        guard !loadingCatalog else { return }
        loadingCatalog = true
        defer { loadingCatalog = false }
        // 1. a TV of the household with a newer catalogue: no request to the provider at all
        for tv in orderedTVs {
            guard let info = try? await tv.getJSON("/info"), let stamp = (info["catalog"] as? NSNumber)?.doubleValue, stamp > 0 else { continue }
            let tvDate = Date(timeIntervalSince1970: stamp)
            if !catalog.entries.isEmpty && tvDate.timeIntervalSince(catalog.fetchedAt) < 60 {
                if !force || Date().timeIntervalSince(tvDate) < 24 * 3600 { return }   // ours is as new as the TV's
                break
            }
            do {
                async let tsv = tv.get("/catalog", timeout: 120)
                async let cats = tv.get("/categories")
                catalog = try await catalogStore.store(tsvData: tsv, categories: cats, modified: tvDate, source: "Fernseher „\(tv.name)“")
                return
            } catch {
                continue
            }
        }
        // 2. the provider: 6 requests, at most once a day unless asked
        guard let login else {
            if force && !background { notice = "Kein Fernseher im WLAN gefunden und kein eigener IPTV-Zugang eingetragen." }
            return
        }
        if !force, !catalog.entries.isEmpty, Date().timeIntervalSince(catalog.fetchedAt) < 24 * 3600 { return }
        do {
            catalog = try await catalogStore.download(XtreamClient(login: login, background: background))
        } catch {
            if !background { notice = error.localizedDescription }
        }
    }

    // MARK: playing

    /// Formats the iPhone plays itself (with AirPlay and picture in picture); everything else goes through VLC.
    static let nativeExtensions: Set<String> = ["mp4", "m4v", "mov", "m3u8"]

    func mayPlay() -> Bool {
        guard profile.kid else { return true }
        switch screenTime.state(profile) {
        case .allowed: return true
        case .limit(let evening): lockedReason = evening ? "Gute Nacht! Für heute ist die Fernsehzeit vorbei." : "Die Fernsehzeit für heute ist aufgebraucht."
        case .bedtime(let at): lockedReason = "Ab \(at) Uhr ist Schlafenszeit."
        }
        return false
    }

    func play(_ channel: LiveChannel, in list: [LiveChannel]) {
        guard mayPlay(), let url = tune(channel) else { return }
        var p = Playback(url: url, item: .live(channel), start: 0, engine: .native)
        p.channels = list
        p.fallback = channel.id > 0 ? client?.liveTSURL(channel.id) : nil
        playback = p
    }

    /// Stream address of a channel; remembers it as watched last (the TVs show those behind the favourites).
    func tune(_ channel: LiveChannel) -> URL? {
        let url: URL?
        if channel.id < 0 { url = DemoSource.url(channel.id) } else { url = client?.liveURL(channel.id) }
        guard let url else { notice = needLogin; return nil }
        if channel.id > 0 {
            var recents = recent.filter { $0.id != channel.id }
            recents.insert(channel, at: 0)
            saveChannels("liveRecent", Array(recents.prefix(10)))
            syncSoon()
        }
        return url
    }

    private var needLogin: String {
        tvBrowser.devices.isEmpty
            ? "Zum Abspielen auf dem iPhone braucht es den IPTV-Zugang (Einstellungen)."
            : "Zum Abspielen auf dem iPhone braucht es den IPTV-Zugang (Einstellungen) – oder spiel es auf dem Fernseher ab."
    }

    func play(_ item: PlayItem, from start: Double = 0, queue: [PlayItem] = []) {
        guard mayPlay() else { return }
        guard let client else { notice = needLogin; return }
        let url = item.kind == "episode" ? client.episodeURL(item.streamId, ext: item.ext) : client.movieURL(item.streamId, ext: item.ext)
        guard let url else { return }
        var p = Playback(url: url, item: item, start: start, engine: Self.nativeExtensions.contains(item.ext.lowercased()) ? .native : .vlc)
        p.queue = queue
        playback = p
    }

    /// The next item of a queue as a new playback (episodes play on by themselves).
    func playNext(after p: Playback) {
        guard let next = p.queue.first else { playback = nil; return }
        play(next, from: 0, queue: Array(p.queue.dropFirst()))
    }

    func playMovie(_ e: Entry) {
        let item = PlayItem.movie(e)
        play(item, from: progress(item.key)?.position ?? 0)
    }

    func playEpisode(_ e: Episode, of s: SeriesInfo) {
        let item = s.item(e)
        let rest = s.episodes.drop { $0.id != e.id }.dropFirst().prefix(40).map { s.item($0) }
        let p = progress(item.key)
        play(item, from: p?.upNext == true ? 0 : (p?.position ?? 0), queue: Array(rest))
    }

    func resume(_ e: ProgressEntry) {
        if let sid = e.item.seriesId, let series = entry(Entry.series, sid) {
            Task {
                if let info = try? await titles.series(series, tv: orderedTVs.first, client: client),
                   let ep = info.episodes.first(where: { $0.id == e.item.streamId }) {
                    playEpisode(ep, of: info)
                } else {
                    play(e.item, from: e.upNext ? 0 : e.position)
                }
            }
        } else {
            play(e.item, from: e.upNext ? 0 : e.position)
        }
    }

    // MARK: Weiterschauen

    func progress(_ key: String) -> ProgressEntry? {
        let file = "progress" + profile.suffix
        guard let o = data.json(file, key) as? [String: Any], let itemJson = o["item"] as? [String: Any], let item = PlayItem(json: itemJson) else { return nil }
        return ProgressEntry(
            item: item,
            position: Double((o["pos"] as? NSNumber)?.int64Value ?? 0) / 1000,
            duration: Double((o["dur"] as? NSNumber)?.int64Value ?? 0) / 1000,
            updatedAt: Date(timeIntervalSince1970: Double((o["at"] as? NSNumber)?.int64Value ?? 0) / 1000),
            upNext: (o["upNext"] as? Bool) ?? false
        )
    }

    var continueWatching: [ProgressEntry] {
        let file = "progress" + profile.suffix
        let hidden = data.stringSet("couchpotato", "hiddenContinue" + profile.suffix)
        return data.keys(file).compactMap { key -> ProgressEntry? in
            guard !hidden.contains(key), let e = progress(key) else { return nil }
            guard e.upNext || (e.position > 10 && (e.duration <= 0 || e.position < e.duration * 0.95)) else { return nil }
            if profile.kid, !kidMayWatch(e.item) { return nil }
            return e
        }
        .sorted { $0.updatedAt > $1.updatedAt }
        .prefix(30).map { $0 }
    }

    private func kidMayWatch(_ item: PlayItem) -> Bool {
        let e = item.kind == "episode" ? item.seriesId.flatMap { entry(Entry.series, $0) } : entry(Entry.movie, item.streamId)
        return e.map(allowed) ?? false
    }

    /// Same rules as on the TVs: keep only the latest episode per series; finished = gone (next episode queued).
    func saveProgress(_ item: PlayItem, position: Double, duration: Double, next: PlayItem? = nil) {
        guard item.kind != "live", position > 0 else { return }
        let file = "progress" + profile.suffix
        let finished = duration > 0 && position >= duration * 0.95
        if let sid = item.seriesId {
            for key in data.keys(file) where key != item.key {
                if let o = data.json(file, key) as? [String: Any], let i = o["item"] as? [String: Any],
                   (i["seriesId"] as? NSNumber)?.int64Value == sid { data.remove(file, key) }
            }
        }
        if finished {
            data.remove(file, item.key)
            if let next {
                data.setJSON(file, next.key, ["item": next.json, "pos": 0, "dur": 0, "at": Int64(Date().timeIntervalSince1970 * 1000), "upNext": true] as [String: Any])
            }
        } else {
            data.setJSON(file, item.key, [
                "item": item.json, "pos": Int64(position * 1000), "dur": Int64(max(duration, 0) * 1000),
                "at": Int64(Date().timeIntervalSince1970 * 1000), "upNext": false,
            ] as [String: Any])
        }
        syncSoon()
    }

    func hideContinue(_ e: ProgressEntry) {
        let key = "hiddenContinue" + profile.suffix
        data.setStringSet("couchpotato", key, data.stringSet("couchpotato", key).union([e.item.key]))
        syncSoon()
    }

    // MARK: TVs in the Wi-Fi

    /// The TV picked last first.
    var orderedTVs: [TvDevice] {
        tvBrowser.devices.sorted { a, b in (a.name == preferredTV ? 0 : 1, a.name) < (b.name == preferredTV ? 0 : 1, b.name) }
    }

    func playOnTV(_ body: [String: Any], on tv: TvDevice) {
        preferredTV = tv.name
        Task {
            do {
                try await tv.post("/play", body)
            } catch {
                notice = "Am Fernseher „\(tv.name)“ ging das nicht: \(error.localizedDescription)"
            }
        }
    }

    func tvBody(channel c: LiveChannel) -> [String: Any] { ["kind": "live", "id": c.id, "cat": c.cat ?? ""] }

    func tvBody(movie e: Entry) -> [String: Any] {
        let p = progress("vod:\(e.itemId)")
        return ["kind": "movie", "id": e.itemId, "pos": Int64((p?.position ?? 0) * 1000), "dur": Int64((p?.duration ?? 0) * 1000)]
    }

    func tvBody(episode e: Episode, series s: SeriesInfo) -> [String: Any] {
        let p = progress("ep:\(e.id)")
        return ["kind": "episode", "series": s.id, "ep": e.id, "pos": Int64((p?.position ?? 0) * 1000), "dur": Int64((p?.duration ?? 0) * 1000)]
    }

    func tvBody(progress e: ProgressEntry) -> [String: Any] {
        if e.item.kind == "episode", let sid = e.item.seriesId {
            return ["kind": "episode", "series": sid, "ep": e.item.streamId, "pos": Int64(e.position * 1000), "dur": Int64(e.duration * 1000)]
        }
        return ["kind": "movie", "id": e.item.streamId, "pos": Int64(e.position * 1000), "dur": Int64(e.duration * 1000)]
    }

    // MARK: login & household

    func setLogin(_ new: XtreamLogin?) async -> String? {
        guard let new else {
            XtreamLogin.save(nil)
            login = nil
            return nil
        }
        do {
            let status = try await XtreamClient(login: new).checkLogin()
            let changed = new.base != login?.base || new.user != login?.user
            if changed { await RequestGate.shared.clearBlock() }
            XtreamLogin.save(new)
            login = new
            if changed && catalog.source == "Anbieter" {
                await catalogStore.clear()
                await titles.clear()
                catalog = .empty
            }
            await refreshCatalog(force: false, background: false)
            return status
        } catch {
            notice = error.localizedDescription
            return nil
        }
    }

    func join(code: String) async -> Bool {
        do {
            let share = try await household.join(code: code)
            if login == nil, let share { pendingIptv = share.login }   // asked first, never taken silently
            tvBrowser.start(linkKey: household.linkKey)
            objectWillChange.send()
            return true
        } catch {
            notice = error.localizedDescription
            return false
        }
    }

    func leaveHousehold() {
        household.leave()
        tvBrowser.start(linkKey: nil)
        profileId = "main"
    }

    func syncSoon(after seconds: Double = 4) {
        guard household.linked else { return }
        syncTask?.cancel()
        syncTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await self?.syncNow(quiet: true)
        }
    }

    func syncNow(quiet: Bool) async {
        guard household.linked else { return }
        do {
            let share = try await household.sync()
            if login == nil, let share, pendingIptv == nil, !quiet { pendingIptv = share.login }
            objectWillChange.send()
        } catch {
            if !quiet { notice = error.localizedDescription }
        }
    }

    func becameActive() async {
        tvBrowser.start(linkKey: household.linkKey)
        if household.linked, Date().timeIntervalSince(household.lastSync ?? .distantPast) > 120 { await syncNow(quiet: true) }
        // give Bonjour a moment to find a TV before asking the provider
        try? await Task.sleep(nanoseconds: 2_500_000_000)
        await refreshCatalog(force: false, background: true)
    }
}

/// Free test streams (Apple's HLS examples) – lets anyone, including App Review, try the app without an IPTV account.
enum DemoSource {
    static let categories = [LiveCategory(id: "demo", name: "Demo")]
    static let channels = [
        LiveChannel(id: -1, name: "Demo · HLS fMP4", logo: nil, cat: "demo"),
        LiveChannel(id: -2, name: "Demo · HLS 16:9", logo: nil, cat: "demo"),
    ]

    static func url(_ id: Int64) -> URL? {
        switch id {
        case -1: return URL(string: "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8")
        case -2: return URL(string: "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8")
        default: return nil
        }
    }
}
