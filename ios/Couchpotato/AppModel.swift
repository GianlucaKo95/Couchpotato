import Combine
import Foundation
import SwiftUI

struct Profile: Identifiable, Hashable {
    let id: String
    let name: String
    let color: Int
    let kid: Bool
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
    let id = UUID()
    let url: URL
    let item: PlayItem
    let start: Double
    let channels: [LiveChannel]   // for switching channels in the player (live only)
}

/// App state: household data (profiles, favourites, progress), IPTV login and the live catalogue.
@MainActor
final class AppModel: ObservableObject {
    let data: HouseholdData
    let household: HouseholdSync

    @Published var login: XtreamLogin?
    @Published var demo: Bool { didSet { UserDefaults.standard.set(demo, forKey: "cp.demo") } }
    @Published private(set) var catalog: LiveCatalog?
    @Published var profileId: String { didSet { UserDefaults.standard.set(profileId, forKey: "cp.profile") } }
    @Published var playback: Playback?
    @Published var notice: String?
    @Published private(set) var loadingCatalog = false
    @Published var pendingIptv: XtreamLogin?

    private let directory: URL
    private var syncTask: Task<Void, Never>?
    private var forward: [AnyCancellable] = []

    init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Couchpotato", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        directory = dir
        let data = HouseholdData(directory: dir)
        self.data = data
        household = HouseholdSync(data: data, directory: dir)
        login = XtreamLogin.load()
        demo = UserDefaults.standard.bool(forKey: "cp.demo")
        profileId = UserDefaults.standard.string(forKey: "cp.profile") ?? "main"
        if let d = try? Data(contentsOf: dir.appendingPathComponent("live.json")) {
            catalog = try? JSONDecoder().decode(LiveCatalog.self, from: d)
        }
        // the screens read through this model: pass changes of the household data on
        forward = [
            data.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() },
            household.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() },
        ]
    }

    var ready: Bool { login != nil || household.linked || demo }
    var client: XtreamClient? { login.map { XtreamClient(login: $0) } }

    // MARK: profiles

    var profiles: [Profile] {
        let list = (data.json("profiles", "list") as? [[String: Any]] ?? []).compactMap { o -> Profile? in
            guard let id = o["id"] as? String, let name = o["name"] as? String else { return nil }
            return Profile(id: id, name: name, color: (o["color"] as? Int) ?? 0, kid: (o["kid"] as? Bool) ?? false)
        }
        return list.isEmpty ? [Profile(id: "main", name: "Ich", color: 0, kid: false)] : list
    }

    var profile: Profile { profiles.first { $0.id == profileId } ?? profiles[0] }

    // MARK: live TV

    var categories: [LiveCategory] {
        if demo && login == nil { return DemoSource.categories }
        guard let catalog else { return [] }
        let hidden = data.stringSet("couchpotato", "hiddenCats")
        let kidOnly = profile.kid ? data.stringSet("profiles", "kidCats") : nil
        let order = data.json("couchpotato", "catOrder_l") as? [String] ?? []
        let visible = catalog.categories.filter { c in
            !hidden.contains("l:\(c.id)") && (kidOnly?.contains("l:\(c.id)") ?? true)
        }
        guard !order.isEmpty else { return visible }
        let rank = Dictionary(order.enumerated().map { ($1, $0) }, uniquingKeysWith: { a, _ in a })
        return visible.enumerated().sorted { a, b in
            (rank[a.element.id] ?? Int.max, a.offset) < (rank[b.element.id] ?? Int.max, b.offset)
        }.map(\.element)
    }

    func channels(in category: LiveCategory) -> [LiveChannel] {
        if demo && login == nil { return DemoSource.channels }
        return catalog?.channels.filter { $0.cat == category.id } ?? []
    }

    /// Channels the profile may see (hidden and – for kids – locked categories are left out).
    var allChannels: [LiveChannel] {
        if demo && login == nil { return DemoSource.channels }
        let allowed = Set(categories.map(\.id))
        return catalog?.channels.filter { $0.cat.map(allowed.contains) ?? !profile.kid } ?? []
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
        return arr.compactMap { o in
            guard let id = (o["id"] as? NSNumber)?.int64Value, seen.insert(id).inserted else { return nil }
            return LiveChannel(id: id, name: Names.display(o["name"] as? String ?? ""), logo: o["logo"] as? String, cat: o["cat"] as? String)
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

    func refreshCatalog(background: Bool) async {
        guard let login, !loadingCatalog else { return }
        loadingCatalog = true
        defer { loadingCatalog = false }
        do {
            let fresh = try await XtreamClient(login: login, background: background).liveCatalog()
            catalog = fresh
            if let d = try? JSONEncoder().encode(fresh) {
                try? d.write(to: directory.appendingPathComponent("live.json"), options: .atomic)
            }
        } catch {
            if !background { notice = error.localizedDescription }
        }
    }

    /// Once a day is plenty: channel lists hardly change, and every request counts with the provider.
    func refreshCatalogIfOld() async {
        guard login != nil else { return }
        if let catalog, Date().timeIntervalSince(catalog.fetchedAt) < 24 * 3600 { return }
        await refreshCatalog(background: true)
    }

    // MARK: playing

    func play(_ channel: LiveChannel, in list: [LiveChannel]) {
        guard let url = tune(channel) else { return }
        playback = Playback(url: url, item: .live(channel), start: 0, channels: list)
    }

    /// Stream address of a channel; remembers it as watched last (the TVs show those behind the favourites).
    func tune(_ channel: LiveChannel) -> URL? {
        let url: URL?
        if demo && login == nil { url = DemoSource.url(channel.id) } else { url = client?.liveURL(channel.id) }
        guard let url else { notice = "Bitte zuerst den IPTV-Zugang eingeben."; return nil }
        if channel.id > 0 {
            var recents = recent.filter { $0.id != channel.id }
            recents.insert(channel, at: 0)
            saveChannels("liveRecent", Array(recents.prefix(10)))
            syncSoon()
        }
        return url
    }

    /// Formats AVPlayer can play. Others (mkv, avi …) are common with IPTV – support follows in a later version.
    static let playableExtensions: Set<String> = ["mp4", "m4v", "mov", "m3u8"]

    func resume(_ e: ProgressEntry) {
        guard let client else { notice = "Bitte zuerst den IPTV-Zugang eingeben."; return }
        guard Self.playableExtensions.contains(e.item.ext.lowercased()) else {
            notice = "„\(e.item.title)“ liegt als .\(e.item.ext) vor – das spielt das iPhone noch nicht ab. Am Fernseher geht's weiter, auf dem iPhone kommt das mit einem der nächsten Updates."
            return
        }
        let url = e.item.kind == "episode" ? client.episodeURL(e.item.streamId, ext: e.item.ext) : client.movieURL(e.item.streamId, ext: e.item.ext)
        guard let url else { return }
        playback = Playback(url: url, item: e.item, start: e.upNext ? 0 : e.position, channels: [])
    }

    // MARK: Weiterschauen

    var continueWatching: [ProgressEntry] {
        let file = "progress" + profile.suffix
        let hidden = data.stringSet("couchpotato", "hiddenContinue" + profile.suffix)
        return data.keys(file).compactMap { key -> ProgressEntry? in
            guard !hidden.contains(key), let o = data.json(file, key) as? [String: Any],
                  let itemJson = o["item"] as? [String: Any], let item = PlayItem(json: itemJson) else { return nil }
            let pos = Double((o["pos"] as? NSNumber)?.int64Value ?? 0) / 1000
            let dur = Double((o["dur"] as? NSNumber)?.int64Value ?? 0) / 1000
            let at = Date(timeIntervalSince1970: Double((o["at"] as? NSNumber)?.int64Value ?? 0) / 1000)
            let upNext = (o["upNext"] as? Bool) ?? false
            guard upNext || (pos > 10 && (dur <= 0 || pos < dur * 0.95)) else { return nil }
            return ProgressEntry(item: item, position: pos, duration: dur, updatedAt: at, upNext: upNext)
        }
        .sorted { $0.updatedAt > $1.updatedAt }
        .prefix(30).map { $0 }
    }

    /// Same rules as on the TVs: keep only the latest episode per series; finished = gone.
    func saveProgress(_ item: PlayItem, position: Double, duration: Double) {
        guard item.kind != "live", position > 0 else { return }
        let file = "progress" + profile.suffix
        if duration > 0, position >= duration * 0.95 {
            data.remove(file, item.key)
        } else {
            if let sid = item.seriesId {
                for key in data.keys(file) where key != item.key {
                    if let o = data.json(file, key) as? [String: Any], let i = o["item"] as? [String: Any],
                       (i["seriesId"] as? NSNumber)?.int64Value == sid { data.remove(file, key) }
                }
            }
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

    // MARK: login & household

    func setLogin(_ new: XtreamLogin?) async -> String? {
        guard let new else {
            XtreamLogin.save(nil)
            login = nil
            return nil
        }
        do {
            let status = try await XtreamClient(login: new).checkLogin()
            if new != login { await RequestGate.shared.clearBlock() }
            XtreamLogin.save(new)
            login = new
            catalog = nil
            await refreshCatalog(background: false)
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
            objectWillChange.send()
            return true
        } catch {
            notice = error.localizedDescription
            return false
        }
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
        if household.linked, Date().timeIntervalSince(household.lastSync ?? .distantPast) > 120 { await syncNow(quiet: true) }
        await refreshCatalogIfOld()
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
