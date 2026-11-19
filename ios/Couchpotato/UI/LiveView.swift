import SwiftUI

struct LiveView: View {
    @EnvironmentObject private var model: AppModel
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List {
                if query.isEmpty {
                    if !model.favorites.isEmpty {
                        NavigationLink(value: LiveCategory(id: "_favs", name: "Favoriten")) {
                            Label("Favoriten", systemImage: "star.fill").foregroundStyle(Theme.accent)
                        }
                    }
                    ForEach(model.categories(Entry.live)) { c in
                        NavigationLink(value: c) { Text(c.name).foregroundStyle(Theme.text) }
                    }
                } else {
                    let hits = model.allChannels.filter { $0.name.localizedCaseInsensitiveContains(query) }.prefix(200)
                    ForEach(Array(hits)) { c in ChannelRow(channel: c, list: Array(hits)) }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(Theme.background.ignoresSafeArea())
            .overlay { placeholder }
            .navigationTitle("Live-TV")
            .searchable(text: $query, prompt: "Sender suchen")
            .navigationDestination(for: LiveCategory.self) { ChannelList(category: $0) }
            .refreshable { await model.refreshCatalog(force: true, background: false) }
        }
    }

    @ViewBuilder private var placeholder: some View {
        if model.categories(Entry.live).isEmpty && query.isEmpty {
            if model.loadingCatalog {
                ProgressView("Senderliste wird geladen …")
            } else if model.login == nil && model.catalog.entries.isEmpty {
                ContentUnavailableView("Keine Senderliste", systemImage: "key",
                                       description: Text("Die Senderliste kommt von einem Couchpotato-Fernseher im selben WLAN – oder gib in den Einstellungen deinen IPTV-Zugang ein."))
            } else {
                ContentUnavailableView("Noch keine Senderliste", systemImage: "tv",
                                       description: Text("Zum Laden nach unten ziehen."))
            }
        }
    }
}

struct ChannelList: View {
    @EnvironmentObject private var model: AppModel
    let category: LiveCategory

    var body: some View {
        let channels = category.id == "_favs" ? model.favorites : model.channels(in: category)
        let move: ((IndexSet, Int) -> Void)? = category.id == "_favs" ? { model.moveFavorites(from: $0, to: $1) } : nil
        List {
            ForEach(channels) { c in ChannelRow(channel: c, list: channels) }
                .onMove(perform: move)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Theme.background.ignoresSafeArea())
        .navigationTitle(category.name)
        .toolbar { if category.id == "_favs" { EditButton() } }
    }
}

struct ChannelRow: View {
    @EnvironmentObject private var model: AppModel
    let channel: LiveChannel
    let list: [LiveChannel]

    var body: some View {
        Button { model.play(channel, in: list) } label: {
            HStack(spacing: 14) {
                ChannelLogo(channel: channel, size: 44)
                Text(channel.name).foregroundStyle(Theme.text).lineLimit(2)
                Spacer()
                if model.isFavorite(channel) { Image(systemName: "star.fill").foregroundStyle(Theme.accent).font(.footnote) }
            }
        }
        .listRowBackground(Color.clear)
        .swipeActions(edge: .trailing) {
            let fav = model.isFavorite(channel)
            Button { model.toggleFavorite(channel) } label: { Label(fav ? "Entfernen" : "Favorit", systemImage: fav ? "star.slash" : "star") }
                .tint(fav ? .gray : Theme.accent)
        }
        .contextMenu {
            FavoriteButton(channel: channel)
            TVMenuItems(body: model.tvBody(channel: channel))
        }
    }
}
