import SwiftUI

/// Start: "Weiterschauen" from all devices of the household, then the favourite channels
/// and the 3 channels watched last – like the home screen on the TVs.
struct HomeView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 26) {
                    let continuing = model.continueWatching
                    if !continuing.isEmpty {
                        section("Weiterschauen") {
                            ScrollView(.horizontal, showsIndicators: false) {
                                LazyHStack(spacing: 14) {
                                    ForEach(continuing) { e in ContinueCard(entry: e) }
                                }
                                .padding(.horizontal, 20)
                            }
                        }
                    }

                    let favs = model.favorites
                    let recent = model.recent.filter { r in !favs.contains { $0.id == r.id } }.prefix(3)
                    let channels = favs + recent
                    if !channels.isEmpty {
                        section("Live-TV") {
                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 14)], spacing: 16) {
                                ForEach(channels) { c in
                                    Button { model.play(c, in: channels) } label: {
                                        VStack(spacing: 6) {
                                            ChannelLogo(channel: c, size: 78)
                                            Text(c.name).font(.footnote).lineLimit(1).foregroundStyle(Theme.text)
                                        }
                                    }
                                    .contextMenu { FavoriteButton(channel: c) }
                                }
                            }
                            .padding(.horizontal, 20)
                        }
                    }

                    if continuing.isEmpty && channels.isEmpty { emptyState }
                }
                .padding(.vertical, 12)
            }
            .refreshable { await model.syncNow(quiet: false) }
            .background(Theme.background.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .principal) { Text("Couchpotato").font(Theme.title(24)).foregroundStyle(Theme.text) }
                ToolbarItem(placement: .topBarTrailing) { ProfileMenu() }
            }
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private func section<C: View>(_ title: String, @ViewBuilder content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(Theme.heading(22)).foregroundStyle(Theme.text).padding(.horizontal, 20)
            content()
        }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "sofa").font(.system(size: 44)).foregroundStyle(Theme.accent)
            Text("Noch nichts hier").font(Theme.heading(22)).foregroundStyle(Theme.text)
            Text(model.household.linked
                 ? "Was du am Fernseher schaust und als Favorit markierst, erscheint hier."
                 : "Markiere unter Live-TV Sender mit dem Stern – sie landen dann hier.")
                .multilineTextAlignment(.center).foregroundStyle(Theme.muted)
        }
        .frame(maxWidth: .infinity).padding(40)
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
                        .frame(width: 220, height: 124).clipped()
                    if entry.fraction > 0 {
                        GeometryReader { g in
                            Rectangle().fill(Theme.accent).frame(width: g.size.width * entry.fraction, height: 4)
                        }
                        .frame(height: 4)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 12))
                Text(entry.item.title).font(Theme.heading(16)).lineLimit(1).foregroundStyle(Theme.text)
                Text(entry.upNext ? "Nächste Folge" : (entry.item.subtitle ?? remaining)).font(.caption).lineLimit(1).foregroundStyle(Theme.muted)
            }
            .frame(width: 220)
        }
        .contextMenu {
            Button(role: .destructive) { model.hideContinue(entry) } label: { Label("Aus „Weiterschauen“ entfernen", systemImage: "eye.slash") }
        }
    }

    private var remaining: String {
        guard entry.duration > 0 else { return "" }
        return "noch \(Int((entry.duration - entry.position) / 60)) Min."
    }
}

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

struct ProfileMenu: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Menu {
            Picker("Profil", selection: $model.profileId) {
                ForEach(model.profiles) { p in Text(p.name).tag(p.id) }
            }
        } label: {
            Circle().fill(Theme.avatarColors[model.profile.color % Theme.avatarColors.count])
                .frame(width: 32, height: 32)
                .overlay(Text(String(model.profile.name.prefix(1))).font(Theme.heading(16)).foregroundStyle(Theme.onAccent))
        }
        .accessibilityLabel("Profil: \(model.profile.name)")
    }
}
