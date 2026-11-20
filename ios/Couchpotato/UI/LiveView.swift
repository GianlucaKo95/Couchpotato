import SwiftUI

/// Live TV like on the TV: channel list on the left, the picture and what's on on the right
/// (upright: picture on top). Tap a channel to watch it in the small picture, tap again for full screen.
struct LiveView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.verticalSizeClass) private var vertical
    @State private var query = ""
    @State private var categoryId: String?

    private static let favs = LiveCategory(id: "_favs", name: "Favoriten")
    private static let recentCat = LiveCategory(id: "_recent", name: "Zuletzt gesehen")

    private var categories: [LiveCategory] {
        var list: [LiveCategory] = []
        if !model.favorites.isEmpty { list.append(Self.favs) }
        if !model.recent.isEmpty { list.append(Self.recentCat) }
        return list + model.categories(Entry.live)
    }

    private var category: LiveCategory? {
        categories.first { $0.id == categoryId } ?? categories.first
    }

    private var channels: [LiveChannel] {
        if !query.isEmpty {
            return Array(model.allChannels.filter { $0.name.localizedCaseInsensitiveContains(query) }.prefix(300))
        }
        guard let c = category else { return [] }
        switch c.id {
        case Self.favs.id: return model.favorites
        case Self.recentCat.id: return model.recent
        default: return model.channels(in: c)
        }
    }

    var body: some View {
        NavigationStack {
            content
                .navigationTitle("Live TV")
                .navigationBarTitleDisplayMode(vertical == .compact ? .inline : .large)
                .toolbar { ToolbarItem(placement: .topBarTrailing) { TopButtons() } }
        }
    }

    private var content: some View {
        Group {
            if categories.isEmpty && query.isEmpty {
                empty
            } else if vertical == .compact {
                HStack(alignment: .top, spacing: 18) {
                    list.frame(maxWidth: .infinity)
                    ScrollView { VStack(spacing: 12) { preview; infoCard } }
                        .frame(maxWidth: .infinity)
                }
                .padding(.horizontal, 20).padding(.top, 6)
            } else {
                VStack(spacing: 10) {
                    if model.live != nil { preview; infoCard }
                    list
                }
                .padding(.horizontal, 16).padding(.top, 6)
            }
        }
        .background(Theme.background)
    }

    // MARK: list

    private var list: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(Theme.faint)
                TextField("Sender suchen", text: $query).foregroundStyle(Theme.text).autocorrectionDisabled()
                if !query.isEmpty { Button { query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(Theme.faint) } }
            }
            .padding(.horizontal, 14).frame(height: 44)
            .glassEffect(.regular.interactive(), in: Capsule())

            if query.isEmpty, let category {
                Menu {
                    ForEach(categories) { c in Button(c.name) { categoryId = c.id } }
                } label: {
                    HStack(spacing: 8) {
                        Text(category.name).font(.title3.weight(.semibold)).lineLimit(1)
                        Image(systemName: "chevron.down").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.faint)
                        Spacer()
                    }
                    .foregroundStyle(Theme.text)
                    .padding(.horizontal, 16).frame(height: 44)
                    .glassEffect(.regular.interactive(), in: Capsule())
                    .fixedSize(horizontal: false, vertical: true)
                }
            }

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 2) {
                        let list = channels
                        ForEach(Array(list.enumerated()), id: \.element.id) { i, c in
                            row(c, number: i + 1, in: list).id(c.id)
                        }
                        if list.isEmpty {
                            Text(query.isEmpty ? "Keine Sender in dieser Kategorie" : "Kein Sender gefunden").foregroundStyle(Theme.muted).padding(30)
                        }
                    }
                    .padding(.bottom, 20)
                }
                .onChange(of: categoryId) { _, _ in proxy.scrollTo(channels.first?.id, anchor: .top) }
            }
        }
    }

    private func row(_ c: LiveChannel, number: Int, in list: [LiveChannel]) -> some View {
        let playing = model.live?.current.id == c.id
        return Button {
            if playing { model.liveFullscreen = true } else {
                model.startLive(list, at: c, category: query.isEmpty ? (category?.name ?? "Live-TV") : "Suche", fullscreen: false)
            }
        } label: {
            HStack(spacing: 12) {
                Text("\(number)").font(.footnote.monospacedDigit()).foregroundStyle(Theme.faint).frame(minWidth: 28, alignment: .trailing)
                ChannelLogo(channel: c, size: 44)
                Text(c.name).font(.body.weight(playing ? .semibold : .regular)).foregroundStyle(Theme.text).lineLimit(1)
                Spacer()
                if playing { LiveBadge().foregroundStyle(Theme.muted) }
                else if model.isFavorite(c) { Image(systemName: "star.fill").font(.caption).foregroundStyle(Theme.faint) }
            }
            .padding(.horizontal, 12).frame(height: 60)
            .background(playing ? Theme.surface : .clear, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(playing ? Theme.hairline : .clear))
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .contextMenu {
            FavoriteButton(channel: c)
            TVMenuItems(body: model.tvBody(channel: c))
        }
    }

    // MARK: picture & info

    @ViewBuilder private var preview: some View {
        if let live = model.live {
            ZStack {
                PlayerSurface(engine: live.engine, refresh: model.liveFullscreen)
                PreviewOverlay(engine: live.engine)
            }
            .aspectRatio(16 / 9, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .contentShape(Rectangle())
            .onTapGesture { model.liveFullscreen = true }
            .overlay(alignment: .topTrailing) {
                Image(systemName: "arrow.up.left.and.arrow.down.right").font(.system(size: 14, weight: .semibold))
                    .frame(width: 34, height: 34).glassEffect(.regular, in: Circle()).foregroundStyle(.white).padding(10)
                    .allowsHitTesting(false)
            }
        } else {
            RoundedRectangle(cornerRadius: 16).fill(Theme.panel)
                .aspectRatio(16 / 9, contentMode: .fit)
                .overlay {
                    VStack(spacing: 8) {
                        Image(systemName: "play.tv").font(.system(size: 32, weight: .light)).foregroundStyle(Theme.faint)
                        Text("Sender antippen").font(.subheadline).foregroundStyle(Theme.muted)
                    }
                }
        }
    }

    @ViewBuilder private var infoCard: some View {
        if let live = model.live {
            LiveInfoCard(session: live)
        }
    }

    private var empty: some View {
        VStack(spacing: 12) {
            if model.loadingCatalog {
                ProgressView("Senderliste wird geladen …").tint(Theme.accent)
            } else {
                Image(systemName: "tv").font(.system(size: 44, weight: .light)).foregroundStyle(Theme.faint)
                Text("Keine Senderliste").font(.title3.weight(.semibold)).foregroundStyle(Theme.text)
                Text(model.login == nil
                     ? "Die Senderliste kommt von einem Couchpotato-Fernseher im selben WLAN – oder gib in den Einstellungen deinen IPTV-Zugang ein."
                     : "Gleich geht's los – oder in den Einstellungen „Katalog jetzt aktualisieren“.")
                    .multilineTextAlignment(.center).foregroundStyle(Theme.muted)
            }
        }
        .padding(30).frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct PreviewOverlay: View {
    @ObservedObject var engine: PlayerEngine

    var body: some View {
        if engine.failed {
            VStack(spacing: 6) {
                Image(systemName: "exclamationmark.circle").font(.system(size: 28, weight: .light)).foregroundStyle(Theme.muted)
                Text("Sender gerade nicht verfügbar").font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text)
            }
        } else if engine.buffering {
            ProgressView().tint(.white)
        }
    }
}

/// Favourite star, channel name and what's on now and next.
struct LiveInfoCard: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var session: LiveSession

    var body: some View {
        let c = session.current
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                Button { model.toggleFavorite(c) } label: {
                    Image(systemName: model.isFavorite(c) ? "star.fill" : "star").font(.system(size: 18, weight: .medium))
                        .foregroundStyle(model.isFavorite(c) ? Theme.accent : Theme.text)
                }
                .buttonStyle(GlassCircle(size: 44))
                .accessibilityLabel(model.isFavorite(c) ? "Aus Favoriten entfernen" : "Zu Favoriten")
                ForEach(model.orderedTVs.prefix(1)) { tv in
                    Button { model.playOnTV(model.tvBody(channel: c), on: tv) } label: {
                        Image(systemName: "tv").font(.system(size: 17, weight: .medium))
                    }
                    .buttonStyle(GlassCircle(size: 44))
                    .accessibilityLabel("Auf „\(tv.name)“ abspielen")
                }
                Text(c.name).font(.title3.weight(.semibold)).foregroundStyle(Theme.text).lineLimit(2)
            }
            let shown = Array(session.epg.prefix(2))
            if shown.isEmpty {
                Text(c.id < 0 ? "Demo-Stream" : "Programm wird geladen …").font(.subheadline).foregroundStyle(Theme.muted)
            }
            ForEach(shown) { p in
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Text(p.isNow ? "Jetzt" : p.start.formatted(date: .omitted, time: .shortened))
                            .font(.subheadline.weight(.semibold).monospacedDigit()).foregroundStyle(p.isNow ? Theme.accent : Theme.faint)
                            .frame(width: 52, alignment: .leading)
                        Text(p.title).font(.subheadline.weight(p.isNow ? .semibold : .regular)).foregroundStyle(p.isNow ? Theme.text : Theme.muted).lineLimit(1)
                    }
                    if p.isNow {
                        ProgressView(value: p.progress).tint(Theme.accent).padding(.leading, 60)
                    }
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.panel, in: RoundedRectangle(cornerRadius: 16))
    }
}

/// Used in context menus.
struct FavoriteButton: View {
    @EnvironmentObject private var model: AppModel
    let channel: LiveChannel

    var body: some View {
        let fav = model.isFavorite(channel)
        Button { model.toggleFavorite(channel) } label: {
            Label(fav ? "Aus Favoriten entfernen" : "Zu Favoriten", systemImage: fav ? "star.slash" : "star")
        }
    }
}

/// Channel row for search results elsewhere.
struct ChannelRow: View {
    @EnvironmentObject private var model: AppModel
    let channel: LiveChannel
    let list: [LiveChannel]

    var body: some View {
        Button { model.play(channel, in: list, category: "Suche") } label: {
            HStack(spacing: 14) {
                ChannelLogo(channel: channel, size: 44)
                Text(channel.name).foregroundStyle(Theme.text).lineLimit(2)
                Spacer()
                if model.isFavorite(channel) { Image(systemName: "star.fill").foregroundStyle(Theme.accent).font(.footnote) }
            }
        }
        .contextMenu {
            FavoriteButton(channel: channel)
            TVMenuItems(body: model.tvBody(channel: channel))
        }
    }
}
