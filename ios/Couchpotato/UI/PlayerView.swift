import AVKit
import SwiftUI

/// Full-screen player: Apple's own controls (AirPlay, picture in picture, subtitles and audio tracks).
/// Live TV gets a channel strip to switch without leaving the player.
struct PlayerView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let playback: Playback

    @State private var player = AVPlayer()
    @State private var current: LiveChannel?
    @State private var showChannels = false
    @State private var lastSave = Date.distantPast
    @State private var timeObserver: Any?

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()
            VideoPlayerController(player: player).ignoresSafeArea()
            HStack(spacing: 10) {
                Button { close() } label: {
                    Image(systemName: "xmark").font(.system(size: 17, weight: .bold)).frame(width: 40, height: 40)
                }
                if !playback.channels.isEmpty {
                    Button { withAnimation { showChannels.toggle() } } label: {
                        Label(current?.name ?? playback.item.title, systemImage: "list.bullet")
                            .font(Theme.heading(16)).lineLimit(1).padding(.horizontal, 14).frame(height: 40)
                    }
                }
            }
            .foregroundStyle(Theme.text)
            .background(.ultraThinMaterial, in: Capsule())
            .padding(.leading, 16).padding(.top, 8)

            if showChannels {
                channelStrip.transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .statusBarHidden()
        .onAppear(perform: start)
        .onDisappear(perform: stop)
    }

    private var channelStrip: some View {
        VStack {
            Spacer()
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 12) {
                        ForEach(playback.channels) { c in
                            Button { switchTo(c) } label: {
                                VStack(spacing: 6) {
                                    ChannelLogo(channel: c, size: 64)
                                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.accent, lineWidth: c.id == current?.id ? 3 : 0))
                                    Text(c.name).font(.caption).lineLimit(1).frame(width: 84).foregroundStyle(Theme.text)
                                }
                            }
                            .id(c.id)
                        }
                    }
                    .padding(16)
                }
                .onAppear { proxy.scrollTo(current?.id, anchor: .center) }
            }
            .background(Theme.panel.opacity(0.92))
        }
    }

    private func start() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        current = playback.channels.first { "live:\($0.id)" == playback.item.key }
        let item = AVPlayerItem(url: playback.url)
        player.replaceCurrentItem(with: item)
        if playback.start > 5 {
            player.seek(to: CMTime(seconds: max(0, playback.start - 3), preferredTimescale: 600))
        }
        player.play()
        guard playback.item.kind != "live" else { return }
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 10, preferredTimescale: 1), queue: .main) { _ in
            Task { @MainActor in saveProgress() }
        }
    }

    private func switchTo(_ c: LiveChannel) {
        current = c
        if let url = model.tune(c) {
            player.replaceCurrentItem(with: AVPlayerItem(url: url))
            player.play()
        }
        withAnimation { showChannels = false }
    }

    private func saveProgress() {
        guard let item = player.currentItem else { return }
        let pos = item.currentTime().seconds
        let dur = item.duration.seconds
        guard pos.isFinite, pos > 0 else { return }
        model.saveProgress(playback.item, position: pos, duration: dur.isFinite ? dur : 0)
    }

    private func close() {
        model.playback = nil
        dismiss()
    }

    private func stop() {
        if playback.item.kind != "live" { saveProgress() }
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        timeObserver = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
    }
}

/// AVPlayerViewController in SwiftUI – brings picture in picture, AirPlay and the track menus.
struct VideoPlayerController: UIViewControllerRepresentable {
    let player: AVPlayer

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        vc.player = player
        vc.allowsPictureInPicturePlayback = true
        vc.canStartPictureInPictureAutomaticallyFromInline = true
        vc.videoGravity = .resizeAspect
        return vc
    }

    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {
        if vc.player !== player { vc.player = player }
    }
}
