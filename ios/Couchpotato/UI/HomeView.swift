import SwiftUI

/// Start: a big title picture (what you were watching, otherwise the newest film), then "Weiterschauen",
/// the favourite channels, Meine Liste and what's new – like the home screen on the TVs.
struct HomeView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.verticalSizeClass) private var vertical
    @Binding var section: AppSection
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            GeometryReader { g in
                ScrollView {
                    VStack(alignment: .leading, spacing: 32) {
                        if let hero {
                            HeroView(hero: hero, height: vertical == .compact ? g.size.height * 0.92 : 520) { path.append(hero.entry) }
                        } else {
                            Text("Start").font(.largeTitle.bold()).foregroundStyle(Theme.text).padding(.horizontal, 20).padding(.top, 56)
                        }
                        rows
                        if model.continueWatching.isEmpty && model.favorites.isEmpty && model.catalog.entries.isEmpty { emptyState }
                    }
                    .padding(.bottom, 32)
                }
                .ignoresSafeArea(edges: .top)
                .overlay(alignment: .topTrailing) {
                    TopButtons().padding(.leading, 14).padding(.trailing, 6).padding(.vertical, 6).glassEffect(.regular.interactive(), in: Capsule())
                        .padding(.trailing, 16).padding(.top, 4)
                }
            }
            .background(Theme.background)
            .refreshable { await model.syncNow(quiet: false) }
            .navigationDestination(for: Entry.self) { e in
                if e.kind == Entry.series { SeriesDetail(entry: e) } else { MovieDetail(entry: e) }
            }
            .toolbar(.hidden, for: .navigationBar)
        }
    }

    // MARK: hero

    struct Hero {
        let entry: Entry
        let image: String?
        let progress: ProgressEntry?
    }

    private var hero: Hero? {
        if let p = model.continueWatching.first {
            let e = p.item.kind == "episode" ? p.item.seriesId.flatMap { model.entry(Entry.series, $0) } : model.entry(Entry.movie, p.item.streamId)
            if let e { return Hero(entry: e, image: p.item.image ?? e.image, progress: p) }
        }
        if let e = model.newest(Entry.movie, limit: 10).first(where: { $0.image != nil }) { return Hero(entry: e, image: e.image, progress: nil) }
        return nil
    }

    // MARK: rows

    @ViewBuilder private var rows: some View {
        let continuing = model.continueWatching
        if !continuing.isEmpty {
            row("Weiterschauen") {
                ForEach(continuing) { e in ContinueCard(entry: e) }
            }
        }
        let favs = model.favorites
        let channels = favs + model.recent.filter { r in !favs.contains { $0.id == r.id } }.prefix(3)
        if !channels.isEmpty {
            row("Live TV") {
                ForEach(channels) { c in
                    Button { model.play(c, in: channels, category: "Favoriten") } label: {
                        VStack(spacing: 8) {
                            ChannelLogo(channel: c, size: 84)
                            Text(c.name).font(.footnote).lineLimit(1).frame(width: 96).foregroundStyle(Theme.muted)
                        }
                    }
                    .contextMenu {
                        FavoriteButton(channel: c)
                        TVMenuItems(body: model.tvBody(channel: c))
                    }
                }
            }
        }
        let forYou = model.forYou
        if !forYou.items.isEmpty { PosterRow(title: "Für dich", entries: forYou.items) }
        let mine = model.myList
        if !mine.isEmpty { PosterRow(title: "Meine Liste", entries: mine) }
        let films = model.newest(Entry.movie)
        if !films.isEmpty { PosterRow(title: "Neue Filme", entries: films) }
        let series = model.newest(Entry.series)
        if !series.isEmpty { PosterRow(title: "Neue Serien", entries: series) }
    }

    private func row<C: View>(_ title: String, @ViewBuilder content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.title2.weight(.semibold)).foregroundStyle(Theme.text).padding(.horizontal, 20)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 12, content: content).padding(.horizontal, 20)
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "sofa").font(.system(size: 44, weight: .light)).foregroundStyle(Theme.muted)
            Text("Noch nichts hier").font(.title3.weight(.semibold)).foregroundStyle(Theme.text)
            Text(model.household.linked
                 ? "Sobald ein Couchpotato-Fernseher im WLAN ist oder dein IPTV-Zugang eingetragen ist, geht's los."
                 : "Trag in den Einstellungen deinen IPTV-Zugang ein – oder verbinde das iPhone mit deinen Fernsehern.")
                .multilineTextAlignment(.center).foregroundStyle(Theme.muted)
            if !model.household.linked && model.login == nil {
                Button("Zu den Einstellungen") { section = .settings }.buttonStyle(QuietButton(wide: false)).padding(.top, 8)
            }
        }
        .frame(maxWidth: .infinity).padding(40)
    }
}

/// The one bright thing on Start: the picture fills the top edge to edge, the text sits on the dark lower part.
struct HeroView: View {
    @EnvironmentObject private var model: AppModel
    let hero: HomeView.Hero
    let height: CGFloat
    let more: () -> Void

    var body: some View {
        let e = hero.entry
        ZStack(alignment: .bottomLeading) {
            Color.clear.frame(maxWidth: .infinity).frame(height: height)
                .overlay { RemoteImage(url: hero.image) { Theme.panel } }
                .clipped()
            // scrims in the room's own colour – the picture fades into the page, nothing glows
            LinearGradient(stops: [.init(color: .clear, location: 0.35), .init(color: Theme.background.opacity(0.85), location: 0.8), .init(color: Theme.background, location: 1)],
                           startPoint: .top, endPoint: .bottom)
            LinearGradient(colors: [Theme.background.opacity(0.6), .clear], startPoint: .leading, endPoint: .center)
            VStack(alignment: .leading, spacing: 8) {
                Text(e.name)
                    .font(.system(size: 34, weight: .bold)).foregroundStyle(Theme.text).lineLimit(2).minimumScaleFactor(0.7)
                let facts = [e.year, e.genre].compactMap { $0 }.joined(separator: " · ")
                if !facts.isEmpty { Text(facts).font(.subheadline).foregroundStyle(Theme.muted).lineLimit(1) }
                if let p = hero.progress {
                    HStack(spacing: 8) {
                        Capsule().fill(Color.white.opacity(0.2)).frame(width: 80, height: 4)
                            .overlay(alignment: .leading) { Capsule().fill(Theme.accent).frame(width: 80 * p.fraction, height: 4) }
                        Text(p.item.subtitle ?? (p.duration > 0 ? "noch \(Int((p.duration - p.position) / 60)) Min." : "")).font(.footnote).foregroundStyle(Theme.muted).lineLimit(1)
                    }
                }
                HStack(spacing: 12) {
                    Button {
                        if let p = hero.progress { model.resume(p) } else if e.kind == Entry.movie { model.playMovie(e) } else { more() }
                    } label: {
                        Label(hero.progress == nil ? "Abspielen" : "Weiterschauen", systemImage: "play.fill")
                    }
                    .buttonStyle(AccentButton(wide: false))
                    Button("Mehr Infos", action: more).buttonStyle(QuietButton(wide: false))
                }
                .padding(.top, 8)
            }
            .padding(.horizontal, 20).padding(.bottom, 16)
            .frame(maxWidth: 560, alignment: .leading)
        }
        .frame(height: height)
    }
}

struct ContinueCard: View {
    @EnvironmentObject private var model: AppModel
    let entry: ProgressEntry

    var body: some View {
        Button { model.resume(entry) } label: {
            VStack(alignment: .leading, spacing: 6) {
                ZStack(alignment: .bottomLeading) {
                    RemoteImage(url: entry.item.image) { Theme.surface.overlay(Image(systemName: "film").foregroundStyle(Theme.muted)) }
                        .frame(width: 250, height: 141).clipped()
                    if entry.fraction > 0 {
                        // the gold progress line – Couchpotato's signature
                        Capsule().fill(Color.white.opacity(0.25)).frame(height: 4)
                            .overlay(alignment: .leading) {
                                GeometryReader { g in Capsule().fill(Theme.accent).frame(width: g.size.width * entry.fraction) }
                            }
                            .padding(8)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 12))
                Text(entry.item.title).font(.subheadline.weight(.semibold)).lineLimit(1).foregroundStyle(Theme.text)
                Text(entry.upNext ? "Nächste Folge" : (entry.item.subtitle ?? remaining)).font(.footnote).lineLimit(1).foregroundStyle(Theme.muted)
            }
            .frame(width: 250)
        }
        .contextMenu {
            TVMenuItems(body: model.tvBody(progress: entry))
            Button(role: .destructive) { model.hideContinue(entry) } label: { Label("Aus „Weiterschauen“ entfernen", systemImage: "eye.slash") }
        }
    }

    private var remaining: String {
        guard entry.duration > 0 else { return "" }
        return "noch \(Int((entry.duration - entry.position) / 60)) Min."
    }
}

/// "Auf „Fernseher“ abspielen" entries for context menus – one per TV in the Wi-Fi.
struct TVMenuItems: View {
    @EnvironmentObject private var model: AppModel
    let body_: [String: Any]

    init(body: [String: Any]) { body_ = body }

    var body: some View {
        ForEach(model.orderedTVs) { tv in
            Button { model.playOnTV(body_, on: tv) } label: { Label("Auf „\(tv.name)“ abspielen", systemImage: "tv") }
        }
    }
}
