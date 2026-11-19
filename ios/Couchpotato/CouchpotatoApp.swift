import SwiftUI

@main
struct CouchpotatoApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
                .onChange(of: phase) { _, p in
                    if p == .active { Task { await model.becameActive() } }
                    if p == .background { Task { await model.syncNow(quiet: true) } }
                }
                .onAppear { ImageStore.shared.providerHost = model.login?.host }
                .onChange(of: model.login) { _, l in ImageStore.shared.providerHost = l?.host }
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Group {
            if model.ready {
                MainTabs()
            } else {
                WelcomeView()
            }
        }
        .fullScreenCover(item: $model.playback) { PlayerView(start: $0) }
        .sheet(item: $model.pinRequest) { PinSheet(request: $0) }
        .fullScreenCover(isPresented: Binding(get: { model.lockedReason != nil }, set: { if !$0 { model.lockedReason = nil } })) {
            LockedView(reason: model.lockedReason ?? "")
        }
        .alert("Hinweis", isPresented: Binding(get: { model.notice != nil }, set: { if !$0 { model.notice = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.notice ?? "")
        }
        .alert("IPTV-Zugang übernehmen?", isPresented: Binding(get: { model.pendingIptv != nil }, set: { if !$0 { model.pendingIptv = nil } })) {
            Button("Übernehmen") {
                let login = model.pendingIptv
                model.pendingIptv = nil
                Task { _ = await model.setLogin(login) }
            }
            Button("Nicht jetzt", role: .cancel) {}
        } message: {
            Text("Dein Hauptfernseher teilt seinen IPTV-Zugang mit dem Haushalt. Soll das iPhone ihn auch nutzen?")
        }
    }
}

struct MainTabs: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        TabView {
            HomeView().tabItem { Label("Start", systemImage: "house.fill") }
            LiveView().tabItem { Label("Live-TV", systemImage: "dot.radiowaves.left.and.right") }
            MediaView().tabItem { Label("Mediathek", systemImage: "film.stack") }
            if model.household.linked {
                RemoteView().tabItem { Label("Fernseher", systemImage: "tv.and.mediabox") }
            }
            SettingsView().tabItem { Label("Einstellungen", systemImage: "gearshape") }
        }
    }
}
