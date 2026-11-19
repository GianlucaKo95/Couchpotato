import Combine
import SwiftUI
import UIKit
import VLCKit

/// VLC for everything Apple's player can't open (MKV, AVI, MPEG-TS …), with our own controls.
@MainActor
final class VLCController: ObservableObject {
    let player = VLCMediaPlayer()
    @Published var time: Double = 0       // seconds
    @Published var length: Double = 0
    @Published var playing = false
    @Published var buffering = true
    @Published var failed = false
    private var timer: Timer?
    private var started = false
    private var startAt: Double = 0
    var onEnd: (() -> Void)?

    func open(_ url: URL, at start: Double, in view: UIView) {
        let media = VLCMedia(url: url)
        media?.addOption(":network-caching=1500")
        media?.addOption(":http-user-agent=\(XtreamClient.userAgent)")
        player.media = media
        player.drawable = view
        startAt = start
        player.play()
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.poll() }
        }
    }

    private func poll() {
        time = Double(player.time.value?.int64Value ?? 0) / 1000
        if let l = player.media?.length.value?.int64Value, l > 0 { length = Double(l) / 1000 }
        playing = player.isPlaying
        switch player.state {
        case .opening: buffering = true
        case .playing:
            buffering = false
            if !started {
                started = true
                if startAt > 5 { seek(to: startAt - 3) }
            }
        case .error: failed = true; buffering = false
        case .stopped:
            if started, length > 0, time > length * 0.9 { onEnd?(); onEnd = nil }
        default: break
        }
    }

    func toggle() { player.isPlaying ? player.pause() : player.play() }

    func seek(by seconds: Double) { seek(to: max(0, time + seconds)) }

    func seek(to seconds: Double) {
        player.time = VLCTime(number: NSNumber(value: Int64(seconds * 1000)))
        time = seconds
    }

    var audioTracks: [VLCMediaPlayer.Track] { player.audioTracks }
    var textTracks: [VLCMediaPlayer.Track] { player.textTracks }

    func select(_ t: VLCMediaPlayer.Track) {
        t.isSelectedExclusively = true
        objectWillChange.send()
    }

    func subtitlesOff() {
        player.deselectAllTextTracks()
        objectWillChange.send()
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        player.stop()
        player.drawable = nil
    }
}

struct VLCPlayerScreen: View {
    let playback: Playback
    let report: (Double, Double) -> Void
    let ended: () -> Void
    let close: () -> Void

    @StateObject private var vlc = VLCController()
    @State private var controls = true
    @State private var hideTask: Task<Void, Never>?
    @State private var scrubbing: Double?
    private let saver = Timer.publish(every: 10, on: .main, in: .common).autoconnect()

    private var live: Bool { playback.item.kind == "live" }

    var body: some View {
        ZStack {
            VLCSurface(controller: vlc, url: playback.url, start: playback.start)
                .contentShape(Rectangle())
                .onTapGesture { show(!controls) }
            if vlc.buffering { ProgressView().tint(.white).scaleEffect(1.4) }
            if vlc.failed {
                VStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle").font(.largeTitle)
                    Text("Das lässt sich gerade nicht abspielen.").font(Theme.heading(18))
                    Button("Schließen", action: close).buttonStyle(.borderedProminent)
                }
                .foregroundStyle(Theme.text)
            }
            if controls { overlay.transition(.opacity) }
        }
        .background(Color.black)
        .onAppear {
            vlc.onEnd = ended
            show(true)
        }
        .onDisappear {
            if !live, vlc.time > 1 { report(vlc.time, vlc.length) }
            vlc.stop()
        }
        .onReceive(saver) { _ in
            if !live, vlc.time > 1, vlc.length > 0 { report(vlc.time, vlc.length) }
        }
    }

    private var overlay: some View {
        VStack {
            HStack(spacing: 12) {
                Button(action: close) { Image(systemName: "xmark").font(.system(size: 18, weight: .bold)).frame(width: 44, height: 44) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(playback.item.title).font(Theme.heading(18)).lineLimit(1)
                    if let s = playback.item.subtitle { Text(s).font(.caption).lineLimit(1).foregroundStyle(Theme.muted) }
                }
                Spacer()
                tracksMenu
            }
            .padding(.horizontal, 16).padding(.top, 10)
            Spacer()
            HStack(spacing: 48) {
                if !live { Button { vlc.seek(by: -10); show(true) } label: { Image(systemName: "gobackward.10") } }
                Button { vlc.toggle(); show(true) } label: { Image(systemName: vlc.playing ? "pause.fill" : "play.fill").font(.system(size: 44)) }
                if !live { Button { vlc.seek(by: 30); show(true) } label: { Image(systemName: "goforward.30") } }
            }
            .font(.system(size: 30))
            Spacer()
            if !live && vlc.length > 0 {
                VStack(spacing: 4) {
                    Slider(value: Binding(get: { scrubbing ?? vlc.time }, set: { scrubbing = $0; show(true) }), in: 0...max(vlc.length, 1)) { editing in
                        if !editing, let s = scrubbing { vlc.seek(to: s); scrubbing = nil }
                    }
                    HStack {
                        Text(Self.clock(scrubbing ?? vlc.time))
                        Spacer()
                        Text("-" + Self.clock(max(0, vlc.length - (scrubbing ?? vlc.time))))
                    }
                    .font(.caption.monospacedDigit()).foregroundStyle(Theme.muted)
                }
                .padding(.horizontal, 20).padding(.bottom, 16)
            }
        }
        .foregroundStyle(Theme.text)
        .background(LinearGradient(colors: [.black.opacity(0.7), .clear, .clear, .black.opacity(0.7)], startPoint: .top, endPoint: .bottom).allowsHitTesting(false))
    }

    private var tracksMenu: some View {
        Menu {
            if vlc.audioTracks.count > 1 {
                Section("Tonspur") {
                    ForEach(vlc.audioTracks, id: \.trackId) { t in
                        Button { vlc.select(t) } label: { Label(t.trackName, systemImage: t.isSelected ? "checkmark" : "") }
                    }
                }
            }
            Section("Untertitel") {
                Button { vlc.subtitlesOff() } label: { Text("Aus") }
                ForEach(vlc.textTracks, id: \.trackId) { t in
                    Button { vlc.select(t) } label: { Label(t.trackName, systemImage: t.isSelected ? "checkmark" : "") }
                }
            }
        } label: {
            Image(systemName: "captions.bubble").font(.system(size: 20)).frame(width: 44, height: 44)
        }
    }

    private func show(_ on: Bool) {
        withAnimation(.easeInOut(duration: 0.2)) { controls = on }
        hideTask?.cancel()
        guard on else { return }
        hideTask = Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard !Task.isCancelled, scrubbing == nil else { return }
            withAnimation { controls = false }
        }
    }

    static func clock(_ s: Double) -> String {
        let t = Int(s)
        return t >= 3600 ? String(format: "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60) : String(format: "%d:%02d", t / 60, t % 60)
    }
}

struct VLCSurface: UIViewRepresentable {
    let controller: VLCController
    let url: URL
    let start: Double

    func makeUIView(context: Context) -> UIView {
        let v = UIView()
        v.backgroundColor = .black
        controller.open(url, at: start, in: v)
        return v
    }

    func updateUIView(_ v: UIView, context: Context) {}
}
