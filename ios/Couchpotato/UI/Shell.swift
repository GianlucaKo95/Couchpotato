import SwiftUI

enum AppSection: Hashable {
    case start, live, movies, series, search
}

/// Apple's own tab bar (Liquid Glass), upright and sideways. Profile, TV remote and settings sit behind
/// the avatar top right, like the account button in Apple's TV app.
struct MainShell: View {
    @EnvironmentObject private var model: AppModel
    @State private var section: AppSection = .start

    var body: some View {
        TabView(selection: $section) {
            HomeView(section: $section)
                .tabItem { Label("Start", systemImage: "house") }.tag(AppSection.start)
            LiveView()
                .tabItem { Label("Live TV", systemImage: "dot.radiowaves.left.and.right") }.tag(AppSection.live)
            MediaView(kind: Entry.movie)
                .tabItem { Label("Filme", systemImage: "film") }.tag(AppSection.movies)
            MediaView(kind: Entry.series)
                .tabItem { Label("Serien", systemImage: "play.rectangle.on.rectangle") }.tag(AppSection.series)
            SearchView()
                .tabItem { Label("Suche", systemImage: "magnifyingglass") }.tag(AppSection.search)
        }
        .tint(Theme.text)
        .sheet(isPresented: $model.showSettings) { SettingsView() }
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

/// The avatar top right: switch profile, open the TV remote or the settings.
struct AccountButton: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Menu {
            Section("Wer schaut?") {
                ForEach(model.profiles) { p in
                    Button { model.chooseProfile(p.id) } label: {
                        if p.id == model.profile.id { Label(p.name, systemImage: "checkmark") } else { Text(p.kid ? "\(p.name) (Kinder)" : p.name) }
                    }
                }
            }
            if model.household.linked {
                Button { model.showRemote = true } label: { Label("Fernbedienung", systemImage: "appletvremote.gen4") }
            }
            Button { model.showSettings = true } label: { Label("Einstellungen", systemImage: "gearshape") }
        } label: {
            Avatar(profile: model.profile, size: 32)
        }
        .accessibilityLabel("Profil \(model.profile.name), Fernbedienung und Einstellungen")
    }
}
