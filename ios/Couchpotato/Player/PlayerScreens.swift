import AVKit
import Combine
import SwiftUI

// MARK: building blocks

/// Round transport button like on the TV: ±10 s and play/pause in rings.
struct RingButton: View {
    let icon: String
    var size: CGFloat = 64
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: size * 0.42, weight: .regular))
                .frame(width: size, height: size)
                .overlay(Circle().stroke(Color.white.opacity(0.35), lineWidth: 1.5))
                .foregroundStyle(.white)
                .contentShape(Circle())
        }
    }
}

/// Thick progress bar with a big thumb you can grab with the thumb.
struct Scrubber: View {
    let value: Double            // 0…1
    var tint: Color = Theme.accent
    let onScrub: (Double) -> Void
    let onCommit: (Double) -> Void
    @State private var dragging: Double?

    var body: some View {
        GeometryReader { g in
            let v = dragging ?? value
            let x = g.size.width * min(max(v, 0), 1)
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.18)).frame(height: 6)
                Capsule().fill(tint).frame(width: max(x, 0), height: 6)
                Capsule().fill(.white).frame(width: 46, height: 30)
                    .shadow(radius: 3)
                    .offset(x: min(max(x - 23, 0), g.size.width - 46))
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { d in
                    let f = min(max(d.location.x / g.size.width, 0), 1)
                    dragging = f
                    onScrub(f)
                }
                .onEnded { d in
                    let f = min(max(d.location.x / g.size.width, 0), 1)
                    dragging = nil
                    onCommit(f)
                })
        }
        .frame(height: 36)
    }
}

/// Panel sliding in from the right (sideways) or up from the bottom (upright).
struct SidePanel<Content: View>: View {
    let title: String
    let close: () -> Void
    @ViewBuilder let content: () -> Content
    @Environment(\.verticalSizeClass) private var vertical

    var body: some View {
        let landscape = vertical == .compact
        let panel = VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(title).font(Theme.title(26)).foregroundStyle(Theme.text)
                Spacer()
                Button(action: close) { Image(systemName: "xmark").font(.system(size: 22, weight: .semibold)).foregroundStyle(Theme.text).frame(width: 44, height: 44) }
            }
            .padding(.horizontal, 22).padding(.top, 14)
            ScrollView { VStack(alignment: .leading, spacing: 0, content: content).padding(.horizontal, 22).padding(.bottom, 24) }
        }
        .background(Theme.panel.opacity(0.97))
        if landscape {
            HStack(spacing: 0) {
                Color.black.opacity(0.35).onTapGesture(perform: close)
                panel.frame(width: 420).ignoresSafeArea(edges: .vertical)
            }
            .transition(.move(edge: .trailing))
        } else {
            VStack(spacing: 0) {
                Color.black.opacity(0.35).onTapGesture(perform: close)
                panel.frame(maxHeight: 460).clipShape(UnevenRoundedRectangle(topLeadingRadius: 22, topTrailingRadius: 22))
            }
            .transition(.move(edge: .bottom))
        }
    }
}

struct PanelHeading: View {
    let text: String
    var body: some View { Text(text).font(Theme.heading(17)).foregroundStyle(Theme.muted).padding(.top, 22).padding(.bottom, 6) }
}

struct PanelRow: View {
    let text: String
    var detail: String? = nil
    let checked: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(text).font(checked ? Theme.heading(19) : .system(size: 18)).foregroundStyle(Theme.text)
                    if let detail { Text(detail).font(.caption).foregroundStyle(Theme.muted) }
                }
                Spacer()
                if checked { Image(systemName: "checkmark").font(.system(size: 20, weight: .bold)).foregroundStyle(Theme.accent) }
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
    }
}

/// Audio tracks, subtitles and how big they are.
struct TracksPanel: View {
    @ObservedObject var engine: PlayerEngine
    let close: () -> Void

    var body: some View {
        SidePanel(title: "Audio & Untertitel", close: close) {
            PanelHeading(text: "Tonspur")
            let audio = engine.audioTracks
            if audio.isEmpty { Text("Nur eine Tonspur").foregroundStyle(Theme.muted).padding(.vertical, 8) }
            ForEach(audio) { t in PanelRow(text: t.name, checked: t.selected) { engine.selectAudio(t.id) } }
            PanelHeading(text: "Untertitel")
            PanelRow(text: "Aus", checked: engine.subtitlesOff) { engine.selectText(nil) }
            ForEach(engine.textTracks) { t in PanelRow(text: t.name, checked: t.selected) { engine.selectText(t.id) } }
            PanelHeading(text: "Untertitel-Darstellung")
            ForEach(SubtitleSize.allCases, id: \.self) { s in PanelRow(text: s.label, checked: engine.subtitleSize == s) { engine.subtitleSize = s } }
        }
    }
}

struct FitPanel: View {
    @ObservedObject var engine: PlayerEngine
    let close: () -> Void

    var body: some View {
        SidePanel(title: "Bildformat", close: close) {
            ForEach(PictureFit.allCases, id: \.self) { f in
                PanelRow(text: f.label, detail: detail(f), checked: engine.fit == f) { engine.fit = f }
            }
        }
    }

    private func detail(_ f: PictureFit) -> String {
        switch f {
        case .fit: return "Ganzes Bild, ggf. mit Rand"
        case .fill: return "Füllt den Bildschirm, Ränder werden abgeschnitten"
        case .wide: return "Erzwingt 16:9"
        }
    }
}

struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.tintColor = .white
        v.activeTintColor = UIColor(Theme.accent)
        return v
    }
    func updateUIView(_ v: AVRoutePickerView, context: Context) {}
}

func clock(_ s: Double) -> String {
    let t = Int(max(s, 0))
    return t >= 3600 ? String(format: "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60) : String(format: "%02d:%02d", t / 60, t % 60)
}

/// Controls that disappear after a few seconds and come back with a tap.
@MainActor
final class ChromeVisibility: ObservableObject {
    @Published var shown = true
    private var task: Task<Void, Never>?
    var hold = false

    func poke() {
        withAnimation(.easeInOut(duration: 0.2)) { shown = true }
        task?.cancel()
        task = Task {
            try? await Task.sleep(nanoseconds: 4_500_000_000)
            guard !Task.isCancelled, !hold else { return }
            withAnimation(.easeInOut(duration: 0.3)) { shown = false }
        }
    }

    func toggle() { shown ? hide() : poke() }

    func hide() {
        task?.cancel()
        withAnimation(.easeInOut(duration: 0.2)) { shown = false }
    }
}

// MARK: films & episodes

struct VodPlayerScreen: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var session: VodSession
    @StateObject private var chrome = ChromeVisibility()
    @State private var panel: Panel?
    @State private var scrub: Double?

    enum Panel { case tracks, fit }

    var body: some View {
        let e = session.engine
        ZStack {
            Color.black.ignoresSafeArea()
            PlayerSurface(engine: e).ignoresSafeArea()
                .onTapGesture { chrome.toggle() }
            VodChrome(session: session, engine: e, chrome: chrome, scrub: $scrub, panel: $panel)
                .opacity(chrome.shown ? 1 : 0)
                .allowsHitTesting(chrome.shown)
            if e.buffering && !e.failed { ProgressView().tint(.white).scaleEffect(1.5).allowsHitTesting(false) }
            if e.failed { failure }
            if panel == .tracks { TracksPanel(engine: e) { withAnimation { panel = nil }; chrome.hold = false; chrome.poke() } }
            if panel == .fit { FitPanel(engine: e) { withAnimation { panel = nil }; chrome.hold = false; chrome.poke() } }
        }
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .onAppear {
            try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            try? AVAudioSession.sharedInstance().setActive(true)
            chrome.poke()
        }
    }

    private var failure: some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.circle").font(.system(size: 44)).foregroundStyle(Theme.accent)
            Text("Das lässt sich gerade nicht abspielen").font(Theme.heading(22))
            Text("Bitte versuch es später noch einmal.").foregroundStyle(Theme.muted)
            Button("Schließen") { model.closeVod() }.buttonStyle(AccentButton()).frame(width: 200)
        }
        .foregroundStyle(Theme.text)
        .padding(28).background(Theme.panel.opacity(0.9), in: RoundedRectangle(cornerRadius: 22))
    }
}

private struct VodChrome: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var session: VodSession
    @ObservedObject var engine: PlayerEngine
    @ObservedObject var chrome: ChromeVisibility
    @Binding var scrub: Double?
    @Binding var panel: VodPlayerScreen.Panel?

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 14) {
                Button { model.closeVod() } label: { Image(systemName: "chevron.left").font(.system(size: 26, weight: .medium)).frame(width: 44, height: 44) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(session.item.title).font(Theme.heading(24)).lineLimit(1)
                    if let s = session.item.subtitle { Text(s).font(.subheadline).foregroundStyle(Theme.muted).lineLimit(1) }
                }
                Spacer()
                AirPlayButton().frame(width: 40, height: 40)
            }
            Spacer()
            HStack(spacing: 56) {
                RingButton(icon: "gobackward.10") { engine.seek(by: -10); chrome.poke() }
                RingButton(icon: engine.playing ? "pause.fill" : "play.fill", size: 84) { engine.toggle(); chrome.poke() }
                RingButton(icon: "goforward.10") { engine.seek(by: 10); chrome.poke() }
            }
            Spacer()
            if engine.length > 0 {
                Scrubber(value: engine.time / engine.length,
                         onScrub: { f in scrub = f * engine.length; chrome.hold = true; chrome.poke() },
                         onCommit: { f in engine.seek(to: f * engine.length); scrub = nil; chrome.hold = false; chrome.poke() })
                HStack {
                    Text(clock(scrub ?? engine.time))
                    Spacer()
                    Text(clock(engine.length)).foregroundStyle(Theme.muted)
                }
                .font(.system(size: 17, weight: .medium, design: .monospaced))
            }
            HStack(spacing: 22) {
                Button { open(.tracks) } label: { Label("Audio & Untertitel", systemImage: "captions.bubble") }
                Spacer()
                if let next = session.next {
                    Button { session.playNext() } label: { Label("Nächste Folge", systemImage: "forward.end") }
                        .accessibilityHint(next.subtitle ?? next.title)
                }
                Button { open(.fit) } label: { Label("Bildformat", systemImage: "aspectratio") }
                if engine.pipPossible {
                    Button { engine.togglePictureInPicture() } label: { Image(systemName: "pip.enter").font(.system(size: 22)) }
                        .accessibilityLabel("Bild-in-Bild")
                }
            }
            .font(.system(size: 18, weight: .medium))
            .padding(.top, 10)
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 28).padding(.vertical, 14)
        .background(LinearGradient(colors: [.black.opacity(0.75), .black.opacity(0.15), .black.opacity(0.15), .black.opacity(0.8)], startPoint: .top, endPoint: .bottom).ignoresSafeArea().allowsHitTesting(false))
    }

    private func open(_ p: VodPlayerScreen.Panel) {
        chrome.hold = true
        withAnimation(.easeInOut(duration: 0.25)) { panel = p }
    }
}

// MARK: live TV

struct LivePlayerScreen: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var session: LiveSession
    @StateObject private var chrome = ChromeVisibility()
    @State private var panel: Panel?

    enum Panel { case channels, epg, tracks, fit }

    var body: some View {
        let e = session.engine
        ZStack {
            Color.black.ignoresSafeArea()
            PlayerSurface(engine: e).ignoresSafeArea()
                .onTapGesture { chrome.toggle() }
                .gesture(DragGesture(minimumDistance: 40).onEnded { d in
                    // swipe left/right: next/previous channel
                    if abs(d.translation.width) > abs(d.translation.height) { session.zap(d.translation.width < 0 ? 1 : -1); chrome.poke() }
                })
            LiveChrome(session: session, engine: e, chrome: chrome, panel: $panel)
                .opacity(chrome.shown ? 1 : 0)
                .allowsHitTesting(chrome.shown)
            if e.buffering && !e.failed { ProgressView().tint(.white).scaleEffect(1.5).allowsHitTesting(false) }
            if e.failed {
                VStack(spacing: 10) {
                    Image(systemName: "info.circle").font(.system(size: 52)).foregroundStyle(Theme.accent)
                    Text("Dieser Sender ist derzeit nicht verfügbar").font(Theme.heading(22)).multilineTextAlignment(.center)
                    Text("Bitte versuch es später noch einmal oder wähl einen anderen Sender.").foregroundStyle(Theme.muted).multilineTextAlignment(.center)
                }
                .foregroundStyle(Theme.text)
                .padding(28).background(Theme.panel.opacity(0.9), in: RoundedRectangle(cornerRadius: 22))
                .allowsHitTesting(false)
            }
            switch panel {
            case .channels: channelPanel
            case .epg: epgPanel
            case .tracks: TracksPanel(engine: e) { closePanel() }
            case .fit: FitPanel(engine: e) { closePanel() }
            case nil: EmptyView()
            }
        }
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .onAppear {
            try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            try? AVAudioSession.sharedInstance().setActive(true)
            chrome.poke()
        }
    }

    private func closePanel() {
        withAnimation { panel = nil }
        chrome.hold = false
        chrome.poke()
    }

    private var channelPanel: some View {
        SidePanel(title: session.categoryName, close: closePanel) {
            ForEach(Array(session.channels.enumerated()), id: \.element.id) { i, c in
                Button {
                    session.tune(i)
                    closePanel()
                } label: {
                    HStack(spacing: 14) {
                        ChannelLogo(channel: c, size: 46)
                        Text(c.name).font(i == session.index ? Theme.heading(18) : .system(size: 17)).foregroundStyle(Theme.text).lineLimit(1)
                        Spacer()
                        if i == session.index { Image(systemName: "speaker.wave.2.fill").foregroundStyle(Theme.accent) }
                    }
                    .padding(.vertical, 7)
                }
            }
        }
    }

    private var epgPanel: some View {
        SidePanel(title: "Programm · \(session.current.name)", close: closePanel) {
            if session.epg.isEmpty {
                Text(session.current.id < 0 ? "Für Demo-Sender gibt es kein Programm." : "Für diesen Sender liegt gerade kein Programm vor.")
                    .foregroundStyle(Theme.muted).padding(.vertical, 12)
            }
            ForEach(session.epg) { p in EpgRow(entry: p) }
        }
    }
}

struct EpgRow: View {
    let entry: EpgEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 10) {
                Text(entry.start.formatted(date: .omitted, time: .shortened)).font(Theme.heading(18)).foregroundStyle(entry.isNow ? Theme.accent : Theme.muted)
                if entry.isNow {
                    Text("JETZT").font(.caption.weight(.heavy)).padding(.horizontal, 7).padding(.vertical, 3)
                        .background(Theme.accent, in: RoundedRectangle(cornerRadius: 5)).foregroundStyle(Theme.onAccent)
                }
                Text(entry.title).font(Theme.heading(18)).foregroundStyle(Theme.text).lineLimit(2)
            }
            if entry.isNow { ProgressView(value: entry.progress).tint(Theme.accent) }
            if let info = entry.info { Text(info).font(.footnote).foregroundStyle(Theme.muted).lineLimit(3) }
        }
        .padding(.vertical, 10)
    }
}

private struct LiveChrome: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var session: LiveSession
    @ObservedObject var engine: PlayerEngine
    @ObservedObject var chrome: ChromeVisibility
    @Binding var panel: LivePlayerScreen.Panel?
    @State private var scrubBehind: Double?

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 14) {
                Button { model.liveFullscreen = false } label: { Image(systemName: "chevron.left").font(.system(size: 26, weight: .medium)).frame(width: 44, height: 44) }
                Text(session.categoryName).font(Theme.heading(24)).lineLimit(1)
                Spacer()
                ChannelLogo(channel: session.current, size: 44)
                Menu {
                    Button { open(.tracks) } label: { Label("Audio & Untertitel", systemImage: "captions.bubble") }
                    Button { open(.fit) } label: { Label("Bildformat", systemImage: "aspectratio") }
                    if session.previous != nil {
                        Button { session.backToPrevious() } label: { Label("Letzter Sender", systemImage: "arrow.uturn.backward") }
                    }
                    Button { model.toggleFavorite(session.current) } label: {
                        Label(model.isFavorite(session.current) ? "Aus Favoriten entfernen" : "Zu Favoriten", systemImage: "star")
                    }
                } label: { Image(systemName: "ellipsis").rotationEffect(.degrees(90)).font(.system(size: 22, weight: .bold)).frame(width: 44, height: 44) }
            }
            Spacer()
            ZStack {
                HStack {
                    if let p = session.previousChannel { zapButton(p, forward: false) }
                    Spacer()
                    if let n = session.nextChannel { zapButton(n, forward: true) }
                }
                HStack(spacing: 56) {
                    RingButton(icon: "gobackward.10") { session.shift(by: -10); chrome.poke() }.opacity(session.canRewind ? 1 : 0.3)
                    RingButton(icon: engine.playing ? "pause.fill" : "play.fill", size: 84) { engine.toggle(); chrome.poke() }
                    RingButton(icon: "goforward.10") { session.shift(by: 10); chrome.poke() }.opacity(session.canRewind && !session.atLive ? 1 : 0.3)
                }
            }
            Spacer()
            HStack {
                Spacer()
                Button { session.goLive(); chrome.poke() } label: {
                    HStack(spacing: 8) {
                        Circle().fill(session.atLive ? Color.red : Color.gray).frame(width: 12, height: 12)
                        Text("LIVE").font(Theme.heading(18))
                    }
                    .padding(.horizontal, 16).padding(.vertical, 8)
                    .background(Color.white.opacity(0.14), in: Capsule())
                }
            }
            if session.canRewind {
                let span = max(session.span, 1)
                Scrubber(value: 1 - (scrubBehind ?? session.behind) / span,
                         onScrub: { f in scrubBehind = (1 - f) * span; chrome.hold = true; chrome.poke() },
                         onCommit: { f in session.seek(behind: (1 - f) * span); scrubBehind = nil; chrome.hold = false; chrome.poke() })
                HStack {
                    Text(Date().addingTimeInterval(-span).formatted(date: .omitted, time: .shortened))
                    Spacer()
                    if (scrubBehind ?? session.behind) >= 8 { Text("−" + clock(scrubBehind ?? session.behind)).foregroundStyle(Theme.accent) }
                    Spacer()
                    Text(Date().formatted(date: .omitted, time: .shortened))
                }
                .font(.system(size: 17, weight: .medium, design: .monospaced))
            }
            HStack(spacing: 18) {
                ChannelLogo(channel: session.current, size: 52)
                VStack(alignment: .leading, spacing: 2) {
                    Text(session.now?.title ?? session.current.name).font(Theme.heading(22)).lineLimit(1)
                    if let n = session.now { Text(n.times).foregroundStyle(Theme.muted) }
                }
                Spacer()
                Button { open(.channels) } label: { Label("Sender", systemImage: "list.bullet") }
                Button { open(.epg) } label: { Label("EPG", systemImage: "calendar") }
                if engine.pipPossible {
                    Button { engine.togglePictureInPicture() } label: { Image(systemName: "pip.enter").font(.system(size: 22)) }
                        .accessibilityLabel("Bild-in-Bild")
                }
            }
            .font(.system(size: 18, weight: .medium))
            .padding(.top, 8)
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 28).padding(.vertical, 14)
        .background(LinearGradient(colors: [.black.opacity(0.75), .black.opacity(0.15), .black.opacity(0.15), .black.opacity(0.85)], startPoint: .top, endPoint: .bottom).ignoresSafeArea().allowsHitTesting(false))
    }

    private func zapButton(_ c: LiveChannel, forward: Bool) -> some View {
        Button { session.zap(forward ? 1 : -1); chrome.poke() } label: {
            VStack(spacing: 6) {
                HStack(spacing: 6) {
                    if !forward { Image(systemName: "chevron.left") }
                    ChannelLogo(channel: c, size: 46)
                    if forward { Image(systemName: "chevron.right") }
                }
                Text(c.name).font(.caption.weight(.semibold)).lineLimit(1).frame(maxWidth: 110)
            }
            .padding(10)
            .background(Color.black.opacity(0.45), in: RoundedRectangle(cornerRadius: 14))
        }
    }

    private func open(_ p: LivePlayerScreen.Panel) {
        chrome.hold = true
        withAnimation(.easeInOut(duration: 0.25)) { panel = p }
    }
}
