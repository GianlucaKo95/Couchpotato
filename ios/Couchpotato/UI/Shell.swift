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
