import Combine
import Foundation
import SwiftUI

struct Profile: Identifiable, Hashable {
    var id: String
    var name: String
    var color: Int
    var kid: Bool
    var dailyMinutes: Int   // kids: screen time per day, 0 = no limit
    var bedtime: Int        // kids: minute of the day from which the profile is locked, -1 = none
    var animal: Int = -1    // avatar animal, -1 = picked from the id (like on the TVs)
    var maxFsk: Int = 6
    /// Data suffix like on the TVs: "" for the first profile, "_<id>" for the others.
    var suffix: String { id == "main" ? "" : "_\(id)" }

    static let animals = 12
    /// Same as on the TVs: Math.floorMod(id.hashCode(), 12) for profiles without a chosen animal.
    var animalIndex: Int {
        if animal >= 0 { return animal % Self.animals }
        var h: Int32 = 0
        for u in id.utf16 { h = h &* 31 &+ Int32(u) }
        return Int(((Int(h) % Self.animals) + Self.animals) % Self.animals)
    }
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
    @Published private(set) var catalog: Catalog = .empty {
        didSet { index = CatalogIndex(catalog); memo = [:] }
    }
    /// Lookups over the catalogue, built once per catalogue (tens of thousands of titles).
    private(set) var index = CatalogIndex(.empty)
    /// Results derived from household data and catalogue, thrown away whenever either changes.
    private var memo: [String: Any] = [:]

    private func memoized<T>(_ key: String, _ make: () -> T) -> T {
        if let v = memo[key] as? T { return v }
        let v = make()
        memo[key] = v
        return v
    }
    @Published private(set) var profileId: String { didSet { UserDefaults.standard.set(profileId, forKey: "cp.profile"); memo = [:] } }
    @Published private(set) var vod: VodSession?
    @Published private(set) var live: LiveSession?
    @Published var liveFullscreen = false
    @Published var showProfiles = false
    @Published var showSearch = false
    @Published var openSettings = false
    @Published var showRemote = false
    @Published var notice: String?
    @Published private(set) var loadingCatalog = false
    @Published var pendingIptv: XtreamLogin?
    @Published var pinRequest: PinRequest?
    @Published var lockedReason: String?           // kids: screen time is up
    @Published var preferredTV: String? { didSet { UserDefaults.standard.set(preferredTV, forKey: "cp.tv") } }

    let directory: URL
    let catalogStore: CatalogStore
    let titles: TitleInfoStore
    let epgStore = EpgStore()
    private var screenTicker: Timer?
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
            data.objectWillChange.sink { [weak self] _ in self?.memo = [:]; self?.objectWillChange.send() },
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
        // kids: playing time counts against the day's screen time
        screenTicker = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.countScreenTime() }
        }
    }

    private func countScreenTime() {
        guard profile.kid, (live?.engine.playing ?? false) || (vod?.engine.playing ?? false) else { return }
        screenTime.add(30, for: profile)
        if !mayPlay() {
            stopLive()
            closeVod()
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
                           dailyMinutes: (o["limit"] as? Int) ?? 0, bedtime: (o["bed"] as? Int) ?? -1,
                           animal: (o["animal"] as? Int) ?? -1, maxFsk: (o["maxFsk"] as? Int) ?? 6)
        }
        return list.isEmpty ? [Profile(id: "main", name: "Ich", color: 0, kid: false, dailyMinutes: 0, bedtime: -1)] : list
    }

    /// Adds or changes a profile – same format as the TVs, so they show it too.
    func saveProfile(_ p: Profile) {
        var raw = data.json("profiles", "list") as? [[String: Any]] ?? []
        if raw.isEmpty { raw = [["id": "main", "name": "Ich", "color": 0, "kid": false]] }
        var o = raw.first { ($0["id"] as? String) == p.id } ?? [:]
        o["id"] = p.id; o["name"] = p.name; o["color"] = p.color; o["kid"] = p.kid
        o["maxFsk"] = p.maxFsk; o["animal"] = p.animal; o["limit"] = p.dailyMinutes; o["bed"] = p.bedtime
        if let i = raw.firstIndex(where: { ($0["id"] as? String) == p.id }) { raw[i] = o } else { raw.append(o) }
        data.setJSON("profiles", "list", raw)
        syncSoon()
    }

    func deleteProfile(_ id: String) {
        guard id != "main" else { return }
        let raw = (data.json("profiles", "list") as? [[String: Any]] ?? []).filter { ($0["id"] as? String) != id }
        data.setJSON("profiles", "list", raw)
        if profileId == id { profileId = "main" }
        syncSoon()
    }

    func newProfile() -> Profile {
        let used = Set(profiles.map(\.animalIndex))
        return Profile(id: String(UUID().uuidString.prefix(8)).lowercased(), name: "", color: profiles.count % 8, kid: false,
                       dailyMinutes: 0, bedtime: -1, animal: (0..<Profile.animals).first { !used.contains($0) } ?? 0)
    }

    // MARK: clearing history (current profile)

    func clearLiveHistory() {
        data.remove("couchpotato", "liveRecent" + profile.suffix)
        syncSoon()
    }

    /// prefix "vod:" for films, "ep:" for series.
    func clearProgress(prefix: String) {
        let file = "progress" + profile.suffix
        for key in data.keys(file) where key.hasPrefix(prefix) { data.remove(file, key) }
        syncSoon()
    }

    func clearMyList() {
        let file = "watchlist" + profile.suffix
        for key in data.keys(file) { data.remove(file, key) }
        syncSoon()
    }

    // MARK: categories (hidden, order, kids) – shared with the TVs

    func isHidden(_ kind: Character, _ c: LiveCategory) -> Bool { data.stringSet("couchpotato", "hiddenCats").contains("\(kind):\(c.id)") }

    func setHidden(_ kind: Character, _ c: LiveCategory, _ hidden: Bool) {
        var set = data.stringSet("couchpotato", "hiddenCats")
        if hidden { set.insert("\(kind):\(c.id)") } else { set.remove("\(kind):\(c.id)") }
        data.setStringSet("couchpotato", "hiddenCats", set)
        syncSoon()
    }

    /// All categories of a kind in the user's order (hidden ones included).
    func orderedCategories(_ kind: Character) -> [LiveCategory] {
        let all = catalog.categories[kind] ?? []
        let order = data.json("couchpotato", "catOrder_\(kind)") as? [String] ?? []
        let rank = Dictionary(order.enumerated().map { ($1, $0) }, uniquingKeysWith: { a, _ in a })
        return all.enumerated().sorted { (rank[$0.element.id] ?? Int.max, $0.offset) < (rank[$1.element.id] ?? Int.max, $1.offset) }.map(\.element)
    }

    func saveOrder(_ kind: Character, _ list: [LiveCategory]) {
        data.setJSON("couchpotato", "catOrder_\(kind)", list.map(\.id))
        syncSoon()
    }

    func resetOrder(_ kind: Character) {
        data.remove("couchpotato", "catOrder_\(kind)")
        syncSoon()
    }

    /// "Alle ausblenden" / "Alle einblenden" in one go (one sync instead of hundreds).
    func setHidden(_ kind: Character, _ cats: [LiveCategory], _ hidden: Bool) {
        var set = data.stringSet("couchpotato", "hiddenCats")
        for c in cats { if hidden { set.insert("\(kind):\(c.id)") } else { set.remove("\(kind):\(c.id)") } }
        data.setStringSet("couchpotato", "hiddenCats", set)
        syncSoon()
    }

    func setKidCategories(_ kind: Character, _ cats: [LiveCategory], _ on: Bool) {
        var set = data.stringSet("profiles", "kidCats")
        for c in cats { if on { set.insert("\(kind):\(c.id)") } else { set.remove("\(kind):\(c.id)") } }
        data.setStringSet("profiles", "kidCats", set)
        syncSoon()
    }

    func isKidCategory(_ kind: Character, _ c: LiveCategory) -> Bool { data.stringSet("profiles", "kidCats").contains("\(kind):\(c.id)") }

    func setKidCategory(_ kind: Character, _ c: LiveCategory, _ on: Bool) {
        var set = data.stringSet("profiles", "kidCats")
        if on { set.insert("\(kind):\(c.id)") } else { set.remove("\(kind):\(c.id)") }
        data.setStringSet("profiles", "kidCats", set)
        syncSoon()
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
    private var kidAllowed: Set<String>? {
        memoized("kidAllowed") { profile.kid ? data.stringSet("profiles", "kidCats") : nil }
    }

    private var hiddenSet: Set<String> { memoized("hidden") { data.stringSet("couchpotato", "hiddenCats") } }

    func allowed(_ e: Entry) -> Bool {
        let key = e.categoryKey
        if hiddenSet.contains(key) { return false }
        if let kid = kidAllowed { return kid.contains(key) }
        return true
    }

    // MARK: categories & titles

    func categories(_ kind: Character) -> [LiveCategory] {
        if kind == Entry.live && demoOnly { return DemoSource.categories }
        return memoized("cats\(kind)") {
            let hidden = hiddenSet
            let kid = kidAllowed
            let order = data.json("couchpotato", "catOrder_\(kind)") as? [String] ?? []
            let used = index.byCategory[kind] ?? [:]
            let visible = (catalog.categories[kind] ?? []).filter { c in
                let key = "\(kind):\(c.id)"
                return !hidden.contains(key) && (kid?.contains(key) ?? true) && used[c.id] != nil
            }
            guard !order.isEmpty else { return visible }
            let rank = Dictionary(order.enumerated().map { ($1, $0) }, uniquingKeysWith: { a, _ in a })
            return visible.enumerated().sorted { a, b in
                (rank[a.element.id] ?? Int.max, a.offset) < (rank[b.element.id] ?? Int.max, b.offset)
            }.map(\.element)
        }
    }

    func titles(_ kind: Character, in category: LiveCategory) -> [Entry] {
        index.byCategory[kind]?[category.id] ?? []
    }

    func newest(_ kind: Character, limit: Int = 30) -> [Entry] {
        memoized("new\(kind)\(limit)") {
            var out: [Entry] = []
            for e in index.newest[kind] ?? [] where allowed(e) {
                out.append(e)
                if out.count == limit { break }
            }
            return out
        }
    }

    func entry(_ kind: Character, _ id: Int64) -> Entry? { index.byId["\(kind):\(id)"] }

    /// The same filter as [allowed], but usable off the main thread (for search).
    func searchFilter() -> @Sendable (Entry) -> Bool {
        let hidden = hiddenSet, kid = kidAllowed
        return { e in
            let key = e.categoryKey
            if hidden.contains(key) { return false }
            if let kid { return kid.contains(key) }
            return true
        }
    }

    // MARK: live TV

    func channels(in category: LiveCategory) -> [LiveChannel] {
        if demoOnly { return DemoSource.channels }
        return memoized("ch\(category.id)") { titles(Entry.live, in: category).map(\.channel) }
    }

    var allChannels: [LiveChannel] {
        if demoOnly { return DemoSource.channels }
        return memoized("allch") { (index.newest[Entry.live] ?? []).filter(allowed).map(\.channel) }
    }

    var favorites: [LiveChannel] { memoized("favs") { channelList("liveFavs") } }
    var recent: [LiveChannel] { memoized("recent") { channelList("liveRecent") } }

    func isFavorite(_ c: LiveChannel) -> Bool { memoized("favIds") { Set(favorites.map(\.id)) }.contains(c.id) }

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
        memoized("mylist") {
            let keys = data.keys(watchlistFile)
            let added = Dictionary(keys.map { ($0, (data.value(watchlistFile, $0) as? NSNumber)?.int64Value ?? 0) }, uniquingKeysWith: { a, _ in a })
            return keys.compactMap { index.byId[$0] }.filter(allowed).sorted { (added[$0.id] ?? 0) > (added[$1.id] ?? 0) }
        }
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
        if !force, !catalog.entries.isEmpty, Date().timeIntervalSince(catalog.fetchedAt) < Double(Prefs.catalogHours) * 3600 { return }
        do {
            catalog = try await catalogStore.download(XtreamClient(login: login, background: background))
        } catch {
            if !background { notice = error.localizedDescription }
        }
    }

    // MARK: playing

    func mayPlay() -> Bool {
        guard profile.kid else { return true }
        switch screenTime.state(profile) {
        case .allowed: return true
        case .limit(let evening): lockedReason = evening ? "Gute Nacht – für heute ist die Fernsehzeit vorbei" : "Die Fernsehzeit für heute ist aufgebraucht"
        case .bedtime(let at): lockedReason = "Ab \(at) Uhr ist Schlafenszeit"
        }
        return false
    }

    private var needLogin: String {
        tvBrowser.devices.isEmpty
            ? "Zum Abspielen auf dem iPhone braucht es den IPTV-Zugang (Einstellungen)."
            : "Zum Abspielen auf dem iPhone braucht es den IPTV-Zugang (Einstellungen) – oder spiel es auf dem Fernseher ab."
    }

    // live TV

    /// Starts a channel – in the preview of the live list, or straight in full screen.
    func startLive(_ list: [LiveChannel], at channel: LiveChannel, category: String, fullscreen: Bool) {
        guard mayPlay() else { return }
        guard channel.id < 0 || client != nil else { notice = needLogin; return }
        closeVod()
        if let live, live.channels.map(\.id) == list.map(\.id), let i = list.firstIndex(where: { $0.id == channel.id }) {
            if live.current.id != channel.id { live.tune(i) }
        } else {
            live?.stop()
            live = LiveSession(model: self, channels: list, index: list.firstIndex { $0.id == channel.id } ?? 0, category: category)
        }
        if fullscreen { liveFullscreen = true }
    }

    func play(_ channel: LiveChannel, in list: [LiveChannel], category: String = "Live-TV") {
        startLive(list, at: channel, category: category, fullscreen: true)
    }

    func stopLive() {
        liveFullscreen = false
        live?.stop()
        live = nil
    }

    /// Remembers a channel as watched last (the TVs show those behind the favourites).
    func rememberWatched(_ channel: LiveChannel) {
        guard channel.id > 0 else { return }
        var recents = recent.filter { $0.id != channel.id }
        recents.insert(channel, at: 0)
        saveChannels("liveRecent", Array(recents.prefix(10)))
        syncSoon()
    }

    // films & episodes

    func streamURL(_ item: PlayItem) -> URL? {
        guard let client else { return nil }
        return item.kind == "episode" ? client.episodeURL(item.streamId, ext: item.ext) : client.movieURL(item.streamId, ext: item.ext)
    }

    func play(_ item: PlayItem, from start: Double = 0, queue: [PlayItem] = []) {
        guard mayPlay() else { return }
        guard client != nil else { notice = needLogin; return }
        stopLive()
        vod?.stop()
        vod = VodSession(model: self, item: item, start: start, queue: queue)
    }

    func closeVod() {
        vod?.stop()
        vod = nil
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

    var continueWatching: [ProgressEntry] { memoized("continue") { computeContinue() } }

    private func computeContinue() -> [ProgressEntry] {
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
