import SwiftUI

enum AppSection: Hashable {
    case start, live, movies, series, search, remote, settings

    static let tabs: [(AppSection, String, String)] = [
        (.start, "Start", "house.fill"),
        (.live, "Live TV", "dot.radiowaves.left.and.right"),
        (.movies, "Filme", "film"),
        (.series, "Serien", "play.rectangle.on.rectangle"),
    ]
}

/// Couchpotato's frame like on the TV: tabs at the top when the phone lies sideways, at the bottom when upright;
/// search, TV remote, settings and profile always top right.
struct MainShell: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.verticalSizeClass) private var vertical
    @State private var section: AppSection = .start
    @State private var visited: Set<AppSection> = [.start]

    private var landscape: Bool { vertical == .compact }

    var body: some View {
        VStack(spacing: 0) {
            TopBar(section: $section, showTabs: landscape)
            ZStack {
                // tabs once opened stay alive (scroll position, opened details)
                ForEach(Array(visited), id: \.self) { s in
                    page(s)
                        .opacity(s == section ? 1 : 0)
                        .allowsHitTesting(s == section)
                        .accessibilityHidden(s != section)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            if !landscape { BottomBar(section: $section) }
        }
        .background(Theme.background.ignoresSafeArea())
        .onChange(of: model.liveFullscreen) { _, full in
            // full screen closed somewhere else than on the live tab: nothing shows the picture any more
            if !full, section != .live, !(model.live?.engine.pipActive ?? false) { model.stopLive() }
        }
        .onChange(of: section) { _, s in
            visited.insert(s)
            // the live preview only runs on the live tab
            if s != .live, !model.liveFullscreen, !(model.live?.engine.pipActive ?? false) { model.stopLive() }
        }
    }

    @ViewBuilder private func page(_ s: AppSection) -> some View {
        switch s {
        case .start: HomeView(section: $section)
        case .live: LiveView()
        case .movies: MediaView(kind: Entry.movie)
        case .series: MediaView(kind: Entry.series)
        case .search: SearchView()
        case .remote: RemoteView()
        case .settings: SettingsView()
        }
    }
}

struct TopBar: View {
    @EnvironmentObject private var model: AppModel
    @Binding var section: AppSection
    let showTabs: Bool

    var body: some View {
        HStack(spacing: 6) {
            if showTabs {
                ForEach(AppSection.tabs, id: \.0) { tab in
                    Button { section = tab.0 } label: {
                        Text(tab.1)
                            .font(Theme.heading(18))
                            .padding(.horizontal, 16).padding(.vertical, 6)
                            .background(section == tab.0 ? Theme.accent : .clear, in: Capsule())
                            .foregroundStyle(section == tab.0 ? Theme.onAccent : Theme.text)
                    }
                }
            } else {
                Text("Couchpotato").font(Theme.title(24)).foregroundStyle(Theme.text)
            }
            Spacer(minLength: 8)
            icon("magnifyingglass", .search, "Suche")
            if model.household.linked { icon("tv.and.mediabox", .remote, "Fernseher") }
            icon("gearshape.fill", .settings, "Einstellungen")
            ProfileMenu().padding(.leading, 6)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 6)
        .background(Theme.background)
    }

    private func icon(_ name: String, _ s: AppSection, _ label: String) -> some View {
        Button { section = s } label: {
            Image(systemName: name).font(.system(size: 19, weight: .semibold))
                .frame(width: 40, height: 40)
                .foregroundStyle(section == s ? Theme.accent : Theme.text)
        }
        .accessibilityLabel(label)
    }
}

struct BottomBar: View {
    @Binding var section: AppSection

    var body: some View {
        HStack {
            ForEach(AppSection.tabs, id: \.0) { tab in
                Button { section = tab.0 } label: {
                    VStack(spacing: 3) {
                        Image(systemName: tab.2).font(.system(size: 19))
                        Text(tab.1).font(.caption2.weight(.semibold))
                    }
                    .frame(maxWidth: .infinity)
                    .foregroundStyle(section == tab.0 ? Theme.accent : Theme.muted)
                }
            }
        }
        .padding(.top, 8)
        .background(Theme.panel.ignoresSafeArea(edges: .bottom))
    }
}
