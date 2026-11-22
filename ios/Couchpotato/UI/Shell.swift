import SwiftUI

enum AppSection: Hashable {
    case start, live, movies, series, settings, search
}

/// Apple's own tab bar (Liquid Glass), upright and sideways. Profile, TV remote and settings sit behind
/// the avatar top right, like the account button in Apple's TV app.
struct MainShell: View {
    @EnvironmentObject private var model: AppModel
    @State private var section: AppSection = .start

    var body: some View {
        TabView(selection: $section) {
            Tab("Start", systemImage: "house", value: AppSection.start) { HomeView(section: $section) }
            Tab("Live TV", systemImage: "dot.radiowaves.left.and.right", value: AppSection.live) { LiveView() }
            Tab("Filme", systemImage: "film", value: AppSection.movies) { MediaView(kind: Entry.movie) }
            Tab("Serien", systemImage: "play.rectangle.on.rectangle", value: AppSection.series) { MediaView(kind: Entry.series) }
            Tab("Einstellungen", systemImage: "gearshape", value: AppSection.settings) { SettingsView() }
        }
        .tabBarMinimizeBehavior(.onScrollDown)
        .tint(Theme.text)
        .overlay(alignment: .bottom) {
            if let h = model.handoff {
                HandoffCard(offer: h)
                    .padding(.horizontal, 12).padding(.bottom, 64)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.spring(duration: 0.35), value: model.handoff?.item.key)
        .sheet(isPresented: $model.showProfiles) { WhoIsWatchingSheet() }
        .sheet(isPresented: $model.showSearch) { SearchView(inSheet: true) }
        .onChange(of: model.openSettings) { _, open in if open { section = .settings; model.openSettings = false } }
        .sheet(isPresented: $model.showRemote) { RemoteView().presentationDetents([.large]) }
        .onChange(of: model.liveFullscreen) { _, full in
            // full screen closed somewhere else than on the live tab: nothing shows the picture any more
            if !full, section != .live, !(model.live?.engine.pipActive ?? false) { model.stopLive() }
        }
        .onChange(of: section) { _, s in
            // the live preview only runs on the live tab
            if s != .live, !model.liveFullscreen, !(model.live?.engine.pipActive ?? false) { model.stopLive() }
        }
    }
}

/// Top right on every page: search and the avatar – the tab bar keeps its five places for the sections.
struct TopButtons: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        HStack(spacing: 14) {
            Button { model.showSearch = true } label: {
                Image(systemName: "magnifyingglass").font(.system(size: 17, weight: .semibold)).foregroundStyle(Theme.text)
            }
            .accessibilityLabel("Suche")
            AccountButton()
        }
    }
}

/// The avatar top right: opens "Wer schaut?" – pick a profile, or manage them from there.
struct AccountButton: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Button { model.showProfiles = true } label: { Avatar(profile: model.profile, size: 32) }
            .accessibilityLabel("Profil \(model.profile.name) – wechseln oder verwalten")
    }
}

/// "Wer schaut?" as a sheet: tap a profile to switch, "Profile verwalten" to edit, add or delete.
struct WhoIsWatchingSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ProfilesPage { id in
                dismiss()
                // a PIN prompt (leaving a kids profile) can only appear once this sheet is gone
                Task { @MainActor in
                    try? await Task.sleep(nanoseconds: 450_000_000)
                    model.chooseProfile(id)
                }
            }
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
        }
        .presentationDetents([.large])
    }
}


/// "Weiterschauen vom Fernseher": what a linked TV is playing or just stopped – one tap continues here.
struct HandoffCard: View {
    @EnvironmentObject private var model: AppModel
    let offer: HandoffOffer

    var body: some View {
        HStack(spacing: 12) {
            Button { model.takeHandoff() } label: {
                HStack(spacing: 12) {
                    RemoteImage(url: offer.item.image) { Theme.surface.overlay(Image(systemName: "tv").foregroundStyle(Theme.muted)) }
                        .frame(width: 64, height: 40).clipShape(RoundedRectangle(cornerRadius: 8))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(headline).font(.caption).foregroundStyle(Theme.muted).lineLimit(1)
                        Text(offer.item.title).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text).lineLimit(1)
                        if let detail { Text(detail).font(.caption).foregroundStyle(Theme.muted).lineLimit(1) }
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "play.fill").font(.body.weight(.semibold)).foregroundStyle(Theme.accent)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Button { model.dismissHandoff() } label: {
                Image(systemName: "xmark").font(.footnote.weight(.semibold)).foregroundStyle(Theme.muted).frame(width: 28, height: 28)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Ausblenden")
        }
        .padding(.leading, 10).padding(.trailing, 8).padding(.vertical, 10)
        .glassEffect(.regular.interactive(), in: RoundedRectangle(cornerRadius: 22))
        .accessibilityElement(children: .contain)
    }

    private var headline: String {
        offer.tv != nil ? "Läuft gerade auf „\(offer.from)“" : "Eben auf „\(offer.from)“ geschaut"
    }

    private var detail: String? {
        if offer.item.kind == "live" { return "Live" }
        let at = "bei \(Self.clock(offer.position))"
        if let sub = offer.item.subtitle, !sub.isEmpty { return "\(sub) · \(at)" }
        return at
    }

    static func clock(_ s: Double) -> String {
        let t = Int(max(s, 0)), h = t / 3600, m = t / 60 % 60, sec = t % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
    }
}
