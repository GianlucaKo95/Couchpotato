import AVKit
import Combine
import SwiftUI

/// Full-screen player. MP4/HLS play with Apple's player (AirPlay, picture in picture, track menus);
/// MKV, AVI & co. with VLC. Episodes play on by themselves; live TV has a channel strip.
struct PlayerView: View {
    @EnvironmentObject private var model: AppModel
    let start: Playback

    @State private var current: Playback?
    @State private var showChannels = false
    @State private var lastDuration: Double = 0

    private let tick = Timer.publish(every: 30, on: .main, in: .common).autoconnect()

    var body: some View {
        let p = current ?? start
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()
            Group {
                switch p.engine {
                case .native:
                    NativePlayer(playback: p, report: report, ended: { ended(p) }, failed: { failed(p) })
                case .vlc:
                    VLCPlayerScreen(playback: p, report: report, ended: { ended(p) }, close: close)
                }
            }
            .id(p.id)
            .ignoresSafeArea()

            if p.engine == .native {
                HStack(spacing: 10) {
                    Button(action: close) {
                        Image(systemName: "xmark").font(.system(size: 17, weight: .bold)).frame(width: 40, height: 40)
                    }
                    if !p.channels.isEmpty {
                        Button { withAnimation { showChannels.toggle() } } label: {
                            Label(p.item.title, systemImage: "list.bullet")
                                .font(Theme.heading(16)).lineLimit(1).padding(.horizontal, 14).frame(height: 40)
                        }
                    }
                }
                .foregroundStyle(Theme.text)
                .background(.ultraThinMaterial, in: Capsule())
                .padding(.leading, 16).padding(.top, 8)
            } else if !p.channels.isEmpty {
                Button { withAnimation { showChannels.toggle() } } label: {
                    Image(systemName: "list.bullet").font(.system(size: 18, weight: .bold)).frame(width: 44, height: 44)
                }
                .foregroundStyle(Theme.text)
                .background(.ultraThinMaterial, in: Circle())
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                .padding(20)
            }

            if showChannels { channelStrip(p).transition(.move(edge: .bottom).combined(with: .opacity)) }
        }
        .statusBarHidden()
        .onAppear {
            try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            try? AVAudioSession.sharedInstance().setActive(true)
        }
        .onReceive(tick) { _ in
            // kids: count the time and stop when it is up
            guard model.profile.kid else { return }
            model.screenTime.add(30, for: model.profile)
            if !model.mayPlay() { close() }
        }
    }

    private func channelStrip(_ p: Playback) -> some View {
        VStack {
            Spacer()
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 12) {
                        ForEach(p.channels) { c in
                            Button { switchTo(c, in: p) } label: {
                                VStack(spacing: 6) {
                                    ChannelLogo(channel: c, size: 64)
                                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.accent, lineWidth: p.item.key == "live:\(c.id)" ? 3 : 0))
                                    Text(c.name).font(.caption).lineLimit(1).frame(width: 84).foregroundStyle(Theme.text)
                                }
                            }
                            .id(c.id)
                        }
                    }
                    .padding(16)
                }
                .onAppear { proxy.scrollTo(p.item.streamId, anchor: .center) }
            }
            .background(Theme.panel.opacity(0.92))
        }
    }

    private func switchTo(_ c: LiveChannel, in p: Playback) {
        guard let url = model.tune(c) else { return }
        var next = Playback(url: url, item: .live(c), start: 0, engine: .native)
        next.channels = p.channels
        next.fallback = c.id > 0 ? model.client?.liveTSURL(c.id) : nil
        current = next
        withAnimation { showChannels = false }
    }

    private func report(_ pos: Double, _ dur: Double) {
        let p = current ?? start
        if dur > 0 { lastDuration = dur }
        model.saveProgress(p.item, position: pos, duration: dur, next: p.queue.first)
    }

    private func ended(_ p: Playback) {
        if p.item.kind == "live" { return }
        if lastDuration > 0 { model.saveProgress(p.item, position: lastDuration, duration: lastDuration, next: p.queue.first) }
        guard let next = p.queue.first, let client = model.client,
              let url = next.kind == "episode" ? client.episodeURL(next.streamId, ext: next.ext) : client.movieURL(next.streamId, ext: next.ext)
        else { close(); return }
        var n = Playback(url: url, item: next, start: 0, engine: AppModel.nativeExtensions.contains(next.ext.lowercased()) ? .native : .vlc)
        n.queue = Array(p.queue.dropFirst())
        lastDuration = 0
        current = n
    }

    /// Apple's player couldn't open it (e.g. a provider without HLS for live TV): try again with VLC.
    private func failed(_ p: Playback) {
        var n = Playback(url: p.fallback ?? p.url, item: p.item, start: p.start, engine: .vlc)
        n.channels = p.channels
        n.queue = p.queue
        current = n
    }

    private func close() {
        model.playback = nil
    }
}

/// AVPlayerViewController – picture in picture, AirPlay and the track menus come for free.
struct NativePlayer: UIViewControllerRepresentable {
    let playback: Playback
    let report: (Double, Double) -> Void
    let ended: () -> Void
    let failed: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        vc.allowsPictureInPicturePlayback = true
        vc.canStartPictureInPictureAutomaticallyFromInline = true
        vc.videoGravity = .resizeAspect
        context.coordinator.attach(to: vc)
        return vc
    }

    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {}

    static func dismantleUIViewController(_ vc: AVPlayerViewController, coordinator: Coordinator) { coordinator.stop() }

    @MainActor
    final class Coordinator {
        let parent: NativePlayer
        let player = AVPlayer()
        private var observer: Any?
        private var bag: [AnyCancellable] = []

        init(_ parent: NativePlayer) { self.parent = parent }

        func attach(to vc: AVPlayerViewController) {
            let item = AVPlayerItem(url: parent.playback.url)
            player.replaceCurrentItem(with: item)
            vc.player = player
            if parent.playback.start > 5 {
                player.seek(to: CMTime(seconds: max(0, parent.playback.start - 3), preferredTimescale: 600))
            }
            player.play()
            item.publisher(for: \.status).filter { $0 == .failed }.first().receive(on: RunLoop.main)
                .sink { [weak self] _ in self?.parent.failed() }.store(in: &bag)
            NotificationCenter.default.publisher(for: .AVPlayerItemDidPlayToEndTime, object: item).receive(on: RunLoop.main)
                .sink { [weak self] _ in self?.parent.ended() }.store(in: &bag)
            guard parent.playback.item.kind != "live" else { return }
            observer = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 10, preferredTimescale: 1), queue: .main) { [weak self] _ in
                Task { @MainActor in self?.save() }
            }
        }

        func save() {
            guard let item = player.currentItem else { return }
            let pos = item.currentTime().seconds, dur = item.duration.seconds
            guard pos.isFinite, pos > 1 else { return }
            parent.report(pos, dur.isFinite ? dur : 0)
        }

        func stop() {
            if parent.playback.item.kind != "live" { save() }
            if let observer { player.removeTimeObserver(observer) }
            observer = nil
            bag = []
            player.pause()
            player.replaceCurrentItem(with: nil)
        }
    }
}
