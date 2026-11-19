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
                    VStack(alignment: .leading, spacing: 26) {
                        if let hero { HeroView(hero: hero, height: vertical == .compact ? g.size.height * 0.82 : 440) { path.append(hero.entry) } }
                        rows
                        if model.continueWatching.isEmpty && model.favorites.isEmpty && model.catalog.entries.isEmpty { emptyState }
                    }
                    .padding(.bottom, 24)
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
                        VStack(spacing: 6) {
                            ChannelLogo(channel: c, size: 84)
                            Text(c.name).font(.footnote).lineLimit(1).frame(width: 96).foregroundStyle(Theme.text)
                        }
                    }
                    .contextMenu {
                        FavoriteButton(channel: c)
                        TVMenuItems(body: model.tvBody(channel: c))
                    }
                }
            }
        }
        let mine = model.myList
        if !mine.isEmpty { PosterRow(title: "Meine Liste", entries: mine) }
        let films = model.newest(Entry.movie)
        if !films.isEmpty { PosterRow(title: "Neue Filme", entries: films) }
        let series = model.newest(Entry.series)
        if !series.isEmpty { PosterRow(title: "Neue Serien", entries: series) }
    }

    private func row<C: View>(_ title: String, @ViewBuilder content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(Theme.title(26)).foregroundStyle(Theme.text).padding(.horizontal, 24)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 14, content: content).padding(.horizontal, 24)
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "sofa").font(.system(size: 44)).foregroundStyle(Theme.accent)
            Text("Noch nichts hier").font(Theme.heading(22)).foregroundStyle(Theme.text)
            Text(model.household.linked
                 ? "Sobald ein Couchpotato-Fernseher im WLAN ist oder dein IPTV-Zugang eingetragen ist, geht's los."
                 : "Trag in den Einstellungen deinen IPTV-Zugang ein – oder verbinde das iPhone mit deinen Fernsehern.")
                .multilineTextAlignment(.center).foregroundStyle(Theme.muted)
            if !model.household.linked && model.login == nil {
                Button("Zu den Einstellungen") { section = .settings }.foregroundStyle(Theme.accent).padding(.top, 6)
            }
        }
        .frame(maxWidth: .infinity).padding(40)
    }
}

struct HeroView: View {
    @EnvironmentObject private var model: AppModel
    let hero: HomeView.Hero
    let height: CGFloat
    let more: () -> Void

    var body: some View {
        let e = hero.entry
        ZStack(alignment: .bottomLeading) {
            RemoteImage(url: hero.image) { Theme.surface }
                .frame(height: height).frame(maxWidth: .infinity).clipped()
            LinearGradient(colors: [Theme.background.opacity(0.95), Theme.background.opacity(0.4), .clear], startPoint: .leading, endPoint: .trailing)
            LinearGradient(colors: [.clear, .clear, Theme.background], startPoint: .top, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 10) {
                Text(e.year.map { "\(e.name) (\($0))" } ?? e.name)
                    .font(Theme.title(38)).foregroundStyle(Theme.text).lineLimit(2).minimumScaleFactor(0.7)
                let facts = [e.year, e.genre].compactMap { $0 }.joined(separator: " • ")
                if !facts.isEmpty { Text(facts).font(.system(size: 18)).foregroundStyle(Theme.muted).lineLimit(1) }
                if let p = hero.progress, let s = p.item.subtitle { Text(s).font(.subheadline).foregroundStyle(Theme.muted).lineLimit(1) }
                HStack(spacing: 14) {
                    Button {
                        if let p = hero.progress { model.resume(p) } else if e.kind == Entry.movie { model.playMovie(e) } else { more() }
                    } label: {
                        Text(hero.progress == nil ? "Abspielen" : "Weiterschauen").font(Theme.heading(20))
                            .padding(.horizontal, 28).padding(.vertical, 12)
                            .background(Theme.accent, in: RoundedRectangle(cornerRadius: 14)).foregroundStyle(Theme.onAccent)
                    }
                    Button(action: more) {
                        Text("Mehr Infos").font(Theme.heading(20))
                            .padding(.horizontal, 24).padding(.vertical, 12)
                            .background(Color.white.opacity(0.14), in: RoundedRectangle(cornerRadius: 14))
                            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.white.opacity(0.25)))
                            .foregroundStyle(Theme.text)
                    }
                }
                .padding(.top, 6)
            }
            .padding(.horizontal, 24).padding(.bottom, 22)
            .frame(maxWidth: 620, alignment: .leading)
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
                        GeometryReader { g in
                            Rectangle().fill(Theme.accent).frame(width: g.size.width * entry.fraction, height: 4)
                        }
                        .frame(height: 4)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 14))
                Text(entry.item.title).font(Theme.heading(16)).lineLimit(1).foregroundStyle(Theme.text)
                Text(entry.upNext ? "Nächste Folge" : (entry.item.subtitle ?? remaining)).font(.caption).lineLimit(1).foregroundStyle(Theme.muted)
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

struct ProfileMenu: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Menu {
            Picker("Profil", selection: Binding(get: { model.profile.id }, set: { model.chooseProfile($0) })) {
                ForEach(model.profiles) { p in Text(p.kid ? "\(p.name) (Kinder)" : p.name).tag(p.id) }
            }
        } label: {
            Avatar(profile: model.profile, size: 38, rounded: true)
        }
        .accessibilityLabel("Profil: \(model.profile.name)")
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
