import SwiftUI

/// Films & series: Meine Liste, new arrivals, categories – and one search over everything.
struct MediaView: View {
    @EnvironmentObject private var model: AppModel
    @State private var kind: Character = Entry.movie
    @State private var query = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                if query.isEmpty { browse } else { SearchResultsView(query: query) }
            }
            .background(Theme.background.ignoresSafeArea())
            .navigationTitle("Mediathek")
            .searchable(text: $query, prompt: "Filme, Serien, Sender")
            .navigationDestination(for: Entry.self) { e in
                if e.kind == Entry.series { SeriesDetail(entry: e) } else { MovieDetail(entry: e) }
            }
            .navigationDestination(for: CategoryRoute.self) { CategoryGrid(route: $0) }
            .refreshable { await model.refreshCatalog(force: true, background: false) }
            .overlay { placeholder }
        }
    }

    @ViewBuilder private var browse: some View {
        VStack(alignment: .leading, spacing: 22) {
            Picker("Art", selection: $kind) {
                Text("Filme").tag(Entry.movie)
                Text("Serien").tag(Entry.series)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 20)

            let mine = model.myList.filter { $0.kind == kind }
            if !mine.isEmpty { PosterRow(title: "Meine Liste", entries: mine) }
            let fresh = model.newest(kind)
            if !fresh.isEmpty { PosterRow(title: kind == Entry.movie ? "Neue Filme" : "Neue Serien", entries: fresh) }

            let cats = model.categories(kind)
            if !cats.isEmpty {
                Text("Kategorien").font(Theme.heading(22)).foregroundStyle(Theme.text).padding(.horizontal, 20)
                LazyVStack(spacing: 0) {
                    ForEach(cats) { c in
                        NavigationLink(value: CategoryRoute(kind: kind, category: c)) {
                            HStack {
                                Text(c.name).foregroundStyle(Theme.text)
                                Spacer()
                                Image(systemName: "chevron.right").font(.footnote).foregroundStyle(Theme.muted)
                            }
                            .padding(.horizontal, 20).padding(.vertical, 13)
                        }
                        Divider().overlay(Theme.surface).padding(.leading, 20)
                    }
                }
            }
        }
        .padding(.vertical, 10)
    }

    @ViewBuilder private var placeholder: some View {
        if model.catalog.entries.isEmpty && query.isEmpty {
            if model.loadingCatalog {
                ProgressView("Katalog wird geladen …")
            } else {
                ContentUnavailableView("Noch kein Katalog", systemImage: "film.stack",
                                       description: Text(model.login == nil
                                                         ? "Der Katalog kommt von einem Couchpotato-Fernseher im selben WLAN – oder gib in den Einstellungen deinen IPTV-Zugang ein."
                                                         : "Zum Laden nach unten ziehen."))
            }
        }
    }
}

struct CategoryRoute: Hashable {
    let kind: Character
    let category: LiveCategory
}

struct CategoryGrid: View {
    @EnvironmentObject private var model: AppModel
    let route: CategoryRoute

    var body: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 12)], spacing: 16) {
                ForEach(model.titles(route.kind, in: route.category)) { e in
                    NavigationLink(value: e) { Poster(entry: e, width: 104) }
                }
            }
            .padding(16)
        }
        .background(Theme.background.ignoresSafeArea())
        .navigationTitle(route.category.name)
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct PosterRow: View {
    let title: String
    let entries: [Entry]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(Theme.heading(22)).foregroundStyle(Theme.text).padding(.horizontal, 20)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 12) {
                    ForEach(entries) { e in NavigationLink(value: e) { Poster(entry: e, width: 112) } }
                }
                .padding(.horizontal, 20)
            }
        }
    }
}

struct Poster: View {
    @EnvironmentObject private var model: AppModel
    let entry: Entry
    let width: CGFloat

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            RemoteImage(url: entry.image) {
                Theme.surface.overlay(Text(entry.name).font(Theme.heading(14)).multilineTextAlignment(.center).foregroundStyle(Theme.muted).padding(6))
            }
            .frame(width: width, height: width * 1.5)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .overlay(alignment: .topTrailing) {
                if model.onList(entry) {
                    Image(systemName: "bookmark.fill").font(.caption).foregroundStyle(Theme.accent).padding(6)
                }
            }
            Text(entry.name).font(.caption).lineLimit(2).foregroundStyle(Theme.text).frame(width: width, alignment: .leading)
        }
    }
}

struct SearchResultsView: View {
    @EnvironmentObject private var model: AppModel
    let query: String

    var body: some View {
        let hits = model.search(query)
        VStack(alignment: .leading, spacing: 22) {
            if let m = hits[Entry.movie], !m.isEmpty { PosterRow(title: "Filme", entries: m) }
            if let s = hits[Entry.series], !s.isEmpty { PosterRow(title: "Serien", entries: s) }
            if let l = hits[Entry.live], !l.isEmpty {
                Text("Sender").font(Theme.heading(22)).foregroundStyle(Theme.text).padding(.horizontal, 20)
                let channels = l.map(\.channel)
                ForEach(channels) { c in ChannelRow(channel: c, list: channels).padding(.horizontal, 20) }
            }
            if hits.values.allSatisfy(\.isEmpty) {
                Text(query.count < 2 ? "" : "Nichts gefunden für „\(query)“").foregroundStyle(Theme.muted).frame(maxWidth: .infinity).padding(40)
            }
        }
        .padding(.vertical, 10)
    }
}

/// "Auf Fernseher abspielen" – shown only when a TV of the household is in the Wi-Fi.
struct PlayOnTVButton: View {
    @EnvironmentObject private var model: AppModel
    let body_: () -> [String: Any]

    init(_ body: @escaping () -> [String: Any]) { body_ = body }

    var body: some View {
        let tvs = model.orderedTVs
        if tvs.count == 1, let tv = tvs.first {
            Button { model.playOnTV(body_(), on: tv) } label: { Label("Auf „\(tv.name)“", systemImage: "tv") }
                .buttonStyle(QuietButton())
        } else if tvs.count > 1 {
            Menu {
                ForEach(tvs) { tv in Button(tv.name) { model.playOnTV(body_(), on: tv) } }
            } label: {
                Label("Auf Fernseher", systemImage: "tv").font(Theme.heading(17)).frame(maxWidth: .infinity).padding(.vertical, 13)
                    .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14)).foregroundStyle(Theme.text)
            }
        }
    }
}

struct MovieDetail: View {
    @EnvironmentObject private var model: AppModel
    let entry: Entry
    @State private var info: MovieInfo?
    @State private var loading = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header
                let p = model.progress("vod:\(entry.itemId)")
                Button { model.playMovie(entry) } label: {
                    Label(p.map { $0.position > 10 ? "Weiterschauen (\(VLCPlayerScreen.clock($0.position)))" : "Abspielen" } ?? "Abspielen", systemImage: "play.fill")
                }
                .buttonStyle(AccentButton())
                PlayOnTVButton { model.tvBody(movie: entry) }
                ListButton(entry: entry)
                if let info {
                    if let plot = info.plot { Text(plot).foregroundStyle(Theme.text) }
                    facts([("Regie", info.director), ("Darsteller", info.cast), ("Dauer", info.duration)])
                } else if model.client != nil {
                    Button(loading ? "Lädt …" : "Beschreibung laden") { Task { await loadDetails() } }
                        .disabled(loading).foregroundStyle(Theme.accent)
                }
            }
            .padding(20)
        }
        .background(Theme.background.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .task { info = await model.titles.cachedMovie(entry.itemId, tv: model.orderedTVs.first) }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 16) {
            RemoteImage(url: entry.image) { Theme.surface }
                .frame(width: 120, height: 180).clipShape(RoundedRectangle(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 6) {
                Text(entry.name).font(Theme.title(26)).foregroundStyle(Theme.text)
                Text([entry.year, entry.genre ?? info?.genre].compactMap { $0 }.joined(separator: " · ")).font(.subheadline).foregroundStyle(Theme.muted)
                if entry.rating > 0 { Label(String(format: "%.1f", entry.rating), systemImage: "star.fill").font(.subheadline).foregroundStyle(Theme.accent) }
            }
        }
    }

    private func facts(_ rows: [(String, String?)]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(rows.filter { $0.1 != nil }, id: \.0) { r in
                (Text(r.0 + ": ").bold() + Text(r.1 ?? "")).font(.subheadline).foregroundStyle(Theme.muted)
            }
        }
    }

    /// ONE request to the provider, only on demand – kept for good afterwards.
    private func loadDetails() async {
        guard let client = model.client else { return }
        loading = true
        defer { loading = false }
        do { info = try await model.titles.loadMovie(entry.itemId, client: client) } catch { model.notice = error.localizedDescription }
    }
}

struct ListButton: View {
    @EnvironmentObject private var model: AppModel
    let entry: Entry

    var body: some View {
        let on = model.onList(entry)
        Button { model.toggleList(entry) } label: {
            Label(on ? "Auf Meiner Liste" : "Zu Meiner Liste", systemImage: on ? "bookmark.fill" : "bookmark")
        }
        .buttonStyle(QuietButton())
    }
}

struct SeriesDetail: View {
    @EnvironmentObject private var model: AppModel
    let entry: Entry
    @State private var info: SeriesInfo?
    @State private var error: String?
    @State private var season = 1

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .top, spacing: 16) {
                    RemoteImage(url: info?.cover ?? entry.image) { Theme.surface }
                        .frame(width: 120, height: 180).clipShape(RoundedRectangle(cornerRadius: 12))
                    VStack(alignment: .leading, spacing: 6) {
                        Text(entry.name).font(Theme.title(26)).foregroundStyle(Theme.text)
                        Text([entry.year ?? info?.year, entry.genre ?? info?.genre].compactMap { $0 }.joined(separator: " · "))
                            .font(.subheadline).foregroundStyle(Theme.muted)
                    }
                }
                if let info, let next = nextEpisode(info) {
                    Button { model.playEpisode(next, of: info) } label: {
                        Label(String(format: "S%02d E%02d abspielen", next.season, next.number), systemImage: "play.fill")
                    }
                    .buttonStyle(AccentButton())
                    PlayOnTVButton { model.tvBody(episode: next, series: info) }
                }
                ListButton(entry: entry)
                if let plot = info?.plot { Text(plot).foregroundStyle(Theme.text) }

                if let info {
                    if info.seasons.count > 1 {
                        Picker("Staffel", selection: $season) {
                            ForEach(info.seasons, id: \.self) { Text("Staffel \($0)").tag($0) }
                        }
                        .pickerStyle(.menu)
                    }
                    ForEach(info.episodes.filter { $0.season == season }) { e in episodeRow(e, info) }
                } else if let error {
                    Text(error).foregroundStyle(Theme.error)
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
            .padding(20)
        }
        .background(Theme.background.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func episodeRow(_ e: Episode, _ info: SeriesInfo) -> some View {
        let p = model.progress("ep:\(e.id)")
        return Button { model.playEpisode(e, of: info) } label: {
            HStack(spacing: 12) {
                RemoteImage(url: e.image) { Theme.surface.overlay(Text("\(e.number)").font(Theme.heading(20)).foregroundStyle(Theme.muted)) }
                    .frame(width: 112, height: 63).clipShape(RoundedRectangle(cornerRadius: 8))
                    .overlay(alignment: .bottomLeading) {
                        if let p, p.fraction > 0 {
                            GeometryReader { g in Rectangle().fill(Theme.accent).frame(width: g.size.width * p.fraction, height: 3) }.frame(height: 3)
                        }
                    }
                VStack(alignment: .leading, spacing: 3) {
                    Text("\(e.number). \(e.title)").font(Theme.heading(16)).foregroundStyle(Theme.text).lineLimit(2).multilineTextAlignment(.leading)
                    if e.duration > 0 { Text("\(e.duration / 60) Min.").font(.caption).foregroundStyle(Theme.muted) }
                }
                Spacer()
            }
        }
        .contextMenu {
            ForEach(model.orderedTVs) { tv in
                Button { model.playOnTV(model.tvBody(episode: e, series: info), on: tv) } label: { Label("Auf „\(tv.name)“ abspielen", systemImage: "tv") }
            }
        }
    }

    /// Where to go on: the episode in "Weiterschauen", otherwise the first one.
    private func nextEpisode(_ info: SeriesInfo) -> Episode? {
        if let p = model.continueWatching.first(where: { $0.item.seriesId == info.id }),
           let e = info.episodes.first(where: { $0.id == p.item.streamId }) { return e }
        return info.episodes.first
    }

    private func load() async {
        guard info == nil else { return }
        do {
            let i = try await model.titles.series(entry, tv: model.orderedTVs.first, client: model.client)
            info = i
            season = nextEpisode(i)?.season ?? i.seasons.first ?? 1
        } catch {
            self.error = error.localizedDescription
        }
    }
}
