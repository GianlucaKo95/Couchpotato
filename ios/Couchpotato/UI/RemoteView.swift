import Combine
import SwiftUI

/// The iPhone as remote for a Couchpotato TV in the same Wi-Fi.
struct RemoteView: View {
    @EnvironmentObject private var model: AppModel
    @State private var state: [String: Any] = [:]
    @State private var error: String?
    @State private var visible = false
    private let poll = Timer.publish(every: 3, on: .main, in: .common).autoconnect()

    private var tv: TvDevice? { model.orderedTVs.first }

    var body: some View {
        NavigationStack {
            Group {
                if let tv {
                    ScrollView { remote(tv).padding(20) }
                } else {
                    ContentUnavailableView {
                        Label("Kein Fernseher gefunden", systemImage: "tv.slash")
                    } description: {
                        Text(model.household.linked
                             ? "Couchpotato-Fernseher deines Haushalts erscheinen hier, sobald iPhone und Fernseher im selben WLAN sind."
                             : "Verbinde das iPhone zuerst mit deinen Fernsehern (Einstellungen → Haushalt).")
                    }
                }
            }
            .background(Theme.background.ignoresSafeArea())
            .navigationTitle("Fernbedienung")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Fertig") { model.showRemote = false } }
                if model.orderedTVs.count > 1 {
                    ToolbarItem(placement: .topBarLeading) {
                        Menu {
                            ForEach(model.orderedTVs) { t in
                                Button { model.preferredTV = t.name } label: { Label(t.name, systemImage: t.name == tv?.name ? "checkmark" : "tv") }
                            }
                        } label: { Label(tv?.name ?? "", systemImage: "tv") }
                    }
                }
            }
            .onReceive(poll) { _ in if visible { Task { await refresh() } } }
            .onAppear { visible = true }
            .onDisappear { visible = false }
            .task(id: tv?.name) { await refresh() }
        }
    }

    private func remote(_ tv: TvDevice) -> some View {
        VStack(spacing: 26) {
            nowPlaying(tv)
            if let error { Text(error).font(.footnote).foregroundStyle(Theme.error).multilineTextAlignment(.center) }
            dpad(tv)
            HStack(spacing: 18) {
                key(tv, "back", "arrow.uturn.backward", "Zurück")
                key(tv, "home", "house", "Start")
                key(tv, "menu", "line.3.horizontal", "Menü")
            }
            HStack(spacing: 18) {
                key(tv, "rew", "backward.fill", "Zurück spulen")
                key(tv, "playpause", "playpause.fill", "Wiedergabe/Pause", big: true)
                key(tv, "ff", "forward.fill", "Vor spulen")
            }
            HStack(spacing: 18) {
                VStack(spacing: 10) {
                    key(tv, "chup", "chevron.up", "Sender hoch")
                    Text("Sender").font(.caption).foregroundStyle(Theme.muted)
                    key(tv, "chdown", "chevron.down", "Sender runter")
                }
                key(tv, "last", "arrow.left.arrow.right", "Letzter Sender")
                VStack(spacing: 10) {
                    key(tv, "volup", "speaker.plus", "Lauter")
                    key(tv, "mute", "speaker.slash", "Ton aus")
                    key(tv, "voldown", "speaker.minus", "Leiser")
                }
            }
        }
    }

    private func nowPlaying(_ tv: TvDevice) -> some View {
        let title = state["title"] as? String
        let pos = (state["pos"] as? NSNumber)?.doubleValue ?? 0
        let dur = (state["dur"] as? NSNumber)?.doubleValue ?? 0
        return VStack(alignment: .leading, spacing: 6) {
            Text(tv.name).font(.caption).foregroundStyle(Theme.muted)
            Text(title ?? ((state["screen"] as? String) == "home" ? "Couchpotato-Startseite" : "Gerade läuft nichts aus Couchpotato"))
                .font(Theme.heading(20)).foregroundStyle(Theme.text).lineLimit(2)
            if let s = state["subtitle"] as? String { Text(s).font(.subheadline).foregroundStyle(Theme.muted).lineLimit(1) }
            if dur > 0 {
                ProgressView(value: min(pos / dur, 1)).tint(Theme.accent)
                Text("\(clock(pos / 1000)) von \(clock(dur / 1000))").font(.caption.monospacedDigit()).foregroundStyle(Theme.muted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 16))
    }

    private func dpad(_ tv: TvDevice) -> some View {
        ZStack {
            Circle().fill(Theme.surface).frame(width: 230, height: 230)
            VStack(spacing: 0) {
                arrow(tv, "up", "chevron.up")
                HStack(spacing: 0) {
                    arrow(tv, "left", "chevron.left")
                    Button { send(tv, "ok") } label: {
                        Text("OK").font(.title3.weight(.semibold)).frame(width: 84, height: 84).background(Theme.text, in: Circle()).foregroundStyle(Theme.background)
                    }
                    .accessibilityLabel("OK")
                    arrow(tv, "right", "chevron.right")
                }
                arrow(tv, "down", "chevron.down")
            }
        }
    }

    private func arrow(_ tv: TvDevice, _ k: String, _ icon: String) -> some View {
        Button { send(tv, k) } label: {
            Image(systemName: icon).font(.system(size: 26, weight: .bold)).frame(width: 72, height: 72).foregroundStyle(Theme.text)
        }
        .accessibilityLabel(k)
    }

    private func key(_ tv: TvDevice, _ k: String, _ icon: String, _ label: String, big: Bool = false) -> some View {
        Button { send(tv, k) } label: {
            Image(systemName: icon).font(.system(size: big ? 26 : 20, weight: .semibold))
                .frame(width: big ? 76 : 60, height: big ? 76 : 60)
                .background(Theme.surface, in: Circle())
                .foregroundStyle(Theme.text)
        }
        .accessibilityLabel(label)
    }

    private func send(_ tv: TvDevice, _ k: String) {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        Task {
            do {
                try await tv.post("/key", ["k": k])
                error = nil
                try? await Task.sleep(nanoseconds: 400_000_000)
                await refresh()
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    private func refresh() async {
        guard let tv else { return }
        if let s = try? await tv.getJSON("/state") { state = s; error = nil }
    }
}
