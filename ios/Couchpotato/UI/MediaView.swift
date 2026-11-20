import SwiftUI

/// Films or series: Meine Liste, new arrivals, then the categories as rows of posters.
struct MediaView: View {
    @EnvironmentObject private var model: AppModel
    let kind: Character

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 32) {
                    let mine = model.myList.filter { $0.kind == kind }
                    if !mine.isEmpty { PosterRow(title: "Meine Liste", entries: mine) }
                    let fresh = model.newest(kind)
                    if !fresh.isEmpty { PosterRow(title: kind == Entry.movie ? "Neue Filme" : "Neue Serien", entries: fresh) }
                    ForEach(model.categories(kind)) { c in
                        let titles = model.titles(kind, in: c)
                        if !titles.isEmpty {
                            PosterRow(title: c.name, entries: Array(titles.prefix(30)),
                                      more: titles.count > 30 ? CategoryRoute(kind: kind, category: c) : nil)
                        }
                    }
                }
                .padding(.vertical, 12)
            }
            .background(Theme.background)
            .navigationTitle(kind == Entry.movie ? "Filme" : "Serien")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { TopButtons() } }
            .navigationDestination(for: Entry.self) { e in
                if e.kind == Entry.series { SeriesDetail(entry: e) } else { MovieDetail(entry: e) }
            }
            .navigationDestination(for: CategoryRoute.self) { CategoryGrid(route: $0) }
            .refreshable { await model.refreshCatalog(force: true, background: false) }
            .overlay { placeholder }
        }
    }

    @ViewBuilder private var placeholder: some View {
        if model.catalog.entries.isEmpty {
            if model.loadingCatalog {
                ProgressView("Katalog wird geladen …").tint(Theme.accent)
            } else {
                ContentUnavailableView("Noch kein Katalog", systemImage: "film.stack",
                                       description: Text(model.login == nil
                                                         ? "Der Katalog kommt von einem Couchpotato-Fernseher im selben WLAN – oder gib in den Einstellungen deinen IPTV-Zugang ein."
                                                         : "Zum Laden nach unten ziehen."))
            }
        }
    }
}

/// Search over films, series and channels.
struct SearchView: View {
    var inSheet = false
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                if query.isEmpty {
                    Text("Filme, Serien und Sender – alles in einer Suche").font(.subheadline).foregroundStyle(Theme.muted)
                        .frame(maxWidth: .infinity).padding(.top, 48)
                } else {
                    SearchResultsView(query: query)
                }
            }
            .background(Theme.background)
            .navigationTitle("Suche")
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Filme, Serien, Sender")
            .autocorrectionDisabled()
            .toolbar { if inSheet { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } } }
            .navigationDestination(for: Entry.self) { e in
                if e.kind == Entry.series { SeriesDetail(entry: e) } else { MovieDetail(entry: e) }
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
    var more: CategoryRoute? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                if let more {
                    NavigationLink(value: more) {
                        HStack(spacing: 4) {
                            Text(title).font(.title2.weight(.semibold)).foregroundStyle(Theme.text).lineLimit(1)
                            Image(systemName: "chevron.right").font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.faint)
                        }
                    }
                } else {
                    Text(title).font(.title2.weight(.semibold)).foregroundStyle(Theme.text).lineLimit(1)
                }
                Spacer()
            }
            .padding(.horizontal, 20)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 12) {
                    ForEach(entries) { e in NavigationLink(value: e) { Poster(entry: e, width: 120) } }
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
        VStack(alignment: .leading, spacing: 8) {
            RemoteImage(url: entry.image) {
                Theme.panel.overlay(Text(entry.name).font(.footnote.weight(.semibold)).multilineTextAlignment(.center).foregroundStyle(Theme.muted).padding(8))
            }
            .frame(width: width, height: width * 1.5)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.hairline))
            .overlay(alignment: .topTrailing) {
                if model.onList(entry) {
                    Image(systemName: "bookmark.fill").font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.text)
                        .padding(6).background(.ultraThinMaterial, in: Circle()).padding(6)
                }
            }
            Text(entry.name).font(.footnote).lineLimit(2).foregroundStyle(Theme.muted).frame(width: width, alignment: .leading)
        }
    }
}

struct SearchResultsView: View {
    @EnvironmentObject private var model: AppModel
    let query: String
    @State private var hits: [Character: [Entry]] = [:]
    @State private var searching = false

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            if let m = hits[Entry.movie], !m.isEmpty { PosterRow(title: "Filme", entries: m) }
            if let s = hits[Entry.series], !s.isEmpty { PosterRow(title: "Serien", entries: s) }
            if let l = hits[Entry.live], !l.isEmpty {
                Text("Sender").font(.title2.weight(.semibold)).foregroundStyle(Theme.text).padding(.horizontal, 20)
                let channels = l.map(\.channel)
                ForEach(channels) { c in ChannelRow(channel: c, list: channels).padding(.horizontal, 20) }
            }
            if hits.values.allSatisfy(\.isEmpty) && !searching {
                Text(query.count < 2 ? "" : "Nichts gefunden für „\(query)“").foregroundStyle(Theme.muted).frame(maxWidth: .infinity).padding(40)
            }
        }
        .padding(.vertical, 12)
        // typing stays smooth: wait for a short pause, then search tens of thousands of titles off the main thread
        .task(id: query) {
            searching = true
            try? await Task.sleep(nanoseconds: 220_000_000)
            guard !Task.isCancelled else { return }
            let catalog = model.catalog
            let allowed = model.searchFilter()
            let q = query
            let result = await Task.detached(priority: .userInitiated) { catalog.search(q, allowed: allowed) }.value
            guard !Task.isCancelled else { return }
            hits = result
            searching = false
        }
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
            Button { model.playOnTV(body_(), on: tv) } label: { Label(tv.name, systemImage: "tv").lineLimit(1) }
                .buttonStyle(QuietButton())
                .accessibilityLabel("Auf „\(tv.name)“ abspielen")
        } else if tvs.count > 1 {
            Menu {
                ForEach(tvs) { tv in Button(tv.name) { model.playOnTV(body_(), on: tv) } }
            } label: {
                Label("Fernseher", systemImage: "tv").font(.system(size: 17, weight: .semibold)).frame(maxWidth: .infinity).frame(minHeight: 48)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.hairline)).foregroundStyle(Theme.text)
            }
        }
    }
}

/// Top of a detail page: the picture edge to edge, title and facts on its dark lower part.
struct DetailHeader: View {
    let image: String?
    let title: String
    let facts: String

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            RemoteImage(url: image) { Theme.panel }
                .frame(height: 340).frame(maxWidth: .infinity).clipped()
            LinearGradient(stops: [.init(color: .clear, location: 0.3), .init(color: Theme.background, location: 1)], startPoint: .top, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.system(size: 30, weight: .bold)).foregroundStyle(Theme.text).lineLimit(3).minimumScaleFactor(0.7)
                if !facts.isEmpty { Text(facts).font(.subheadline).foregroundStyle(Theme.muted) }
            }
            .padding(.horizontal, 20).padding(.bottom, 8)
        }
        .frame(height: 340)
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
                DetailHeader(image: info?.backdrop ?? entry.image, title: entry.name, facts: facts)
                VStack(alignment: .leading, spacing: 16) {
                    let p = model.progress("vod:\(entry.itemId)")
                    Button { model.playMovie(entry) } label: {
                        Label(p.map { $0.position > 10 ? "Weiterschauen ab \(clock($0.position))" : "Abspielen" } ?? "Abspielen", systemImage: "play.fill")
                    }
                    .buttonStyle(AccentButton())
                    HStack(spacing: 12) {
                        ListButton(entry: entry)
                        PlayOnTVButton { model.tvBody(movie: entry) }
                    }
                    if let info {
                        if let plot = info.plot { Text(plot).font(.body).foregroundStyle(Theme.text) }
                        credits([("Regie", info.director), ("Mit", info.cast), ("Dauer", info.duration)])
                    } else if model.client != nil {
                        Button(loading ? "Lädt …" : "Beschreibung laden") { Task { await loadDetails() } }
                            .disabled(loading).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.muted)
                    }
                }
                .padding(.horizontal, 20)
            }
            .padding(.bottom, 32)
        }
        .ignoresSafeArea(edges: .top)
        .background(Theme.background.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .task { info = await model.titles.cachedMovie(entry.itemId, tv: model.orderedTVs.first) }
    }

    private var facts: String {
        var parts = [entry.year, entry.genre ?? info?.genre].compactMap { $0 }
        if entry.rating > 0 { parts.append(String(format: "★ %.1f", entry.rating)) }
        return parts.joined(separator: " · ")
    }

    private func credits(_ rows: [(String, String?)]) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(rows.filter { $0.1 != nil }, id: \.0) { r in
                (Text(r.0 + "  ").foregroundStyle(Theme.faint) + Text(r.1 ?? "").foregroundStyle(Theme.muted)).font(.subheadline)
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
            Label("Meine Liste", systemImage: on ? "checkmark" : "plus")
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
                DetailHeader(image: info?.cover ?? entry.image, title: entry.name,
                             facts: [entry.year ?? info?.year, entry.genre ?? info?.genre].compactMap { $0 }.joined(separator: " · "))
                VStack(alignment: .leading, spacing: 16) {
                if let info, let next = nextEpisode(info) {
                    Button { model.playEpisode(next, of: info) } label: {
                        Label(String(format: "Staffel %d, Folge %d", next.season, next.number), systemImage: "play.fill")
                    }
                    .buttonStyle(AccentButton())
                }
                HStack(spacing: 12) {
                    ListButton(entry: entry)
                    if let info, let next = nextEpisode(info) { PlayOnTVButton { model.tvBody(episode: next, series: info) } }
                }
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
                .padding(.horizontal, 20)
            }
            .padding(.bottom, 32)
        }
        .ignoresSafeArea(edges: .top)
        .background(Theme.background.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
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
