import AVFoundation
import SwiftUI
import UIKit
import VLCKit

/// How the picture fills the screen ("Bildformat").
enum PictureFit: String, CaseIterable {
    case fit, fill, wide
    var label: String {
        switch self {
        case .fit: return "Einpassen"
        case .fill: return "Ausfüllen"
        case .wide: return "16:9"
        }
    }
}

/// Subtitle size ("Untertitel-Darstellung").
enum SubtitleSize: String, CaseIterable {
    case small, normal, large, huge
    var label: String {
        switch self {
        case .small: return "Klein"
        case .normal: return "Normal"
        case .large: return "Groß"
        case .huge: return "Sehr groß"
        }
    }
    var scale: Float {
        switch self {
        case .small: return 0.75
        case .normal: return 1
        case .large: return 1.35
        case .huge: return 1.7
        }
    }
}

struct TrackOption: Identifiable, Hashable {
    let id: String
    let name: String
    let selected: Bool
}

/// One VLC player for everything (live with timeshift, films, episodes in any format) – with picture in picture.
/// The video can move between views (preview in the live list ↔ full screen) without reconnecting.
@MainActor
final class PlayerEngine: NSObject, ObservableObject {
    let player = VLCMediaPlayer()

    @Published private(set) var time: Double = 0        // seconds since the media started
    @Published private(set) var length: Double = 0
    @Published private(set) var playing = false
    @Published private(set) var buffering = true
    @Published private(set) var failed = false
    @Published private(set) var pipActive = false
    @Published var fit: PictureFit = PictureFit(rawValue: UserDefaults.standard.string(forKey: "cp.fit") ?? "") ?? .fit {
        didSet { UserDefaults.standard.set(fit.rawValue, forKey: "cp.fit"); applyFit() }
    }
    @Published var subtitleSize: SubtitleSize = SubtitleSize(rawValue: UserDefaults.standard.string(forKey: "cp.subs") ?? "") ?? .normal {
        didSet { UserDefaults.standard.set(subtitleSize.rawValue, forKey: "cp.subs"); player.currentSubTitleFontScale = subtitleSize.scale }
    }

    var onEnd: (() -> Void)?
    private var timer: Timer?
    private var started = false
    private var startAt: Double = 0
    private(set) weak var container: UIView?
    private var videoViews: [UIView] = []
    private var pip: VLCPictureInPictureWindowControlling?

    override init() {
        super.init()
        player.drawable = self
    }

    func open(_ url: URL, at start: Double = 0) {
        let media = VLCMedia(url: url)
        media?.addOption(":network-caching=\(Prefs.liveBuffer)")
        media?.addOption(":http-user-agent=\(XtreamClient.userAgent)")
        started = false
        failed = false
        buffering = true
        startAt = start
        time = 0
        length = 0
        player.media = media
        player.play()
        player.currentSubTitleFontScale = subtitleSize.scale
        if timer == nil {
            timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.poll() }
            }
        }
    }

    /// Shows the video in [view] (moves it over from where it was).
    func attach(_ view: UIView) {
        container = view
        view.isUserInteractionEnabled = false
        for v in videoViews {
            v.removeFromSuperview()
            v.frame = view.bounds
            v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            view.addSubview(v)
        }
        applyFit()
    }

    private func poll() {
        time = Double(player.time.value?.int64Value ?? 0) / 1000
        if let l = player.media?.length.value?.int64Value, l > 0 { length = Double(l) / 1000 }
        let wasPlaying = playing
        playing = player.isPlaying
        if wasPlaying != playing { pip?.invalidatePlaybackState() }
        switch player.state {
        case .opening: buffering = true
        case .playing:
            buffering = false
            if !started {
                started = true
                if startAt > 5 { seek(to: startAt - 3) }
                applyFit()
            }
        case .error:
            failed = true
            buffering = false
        case .stopped:
            if started, length > 0, time > length * 0.9 {
                started = false
                onEnd?()
            }
        default: break
        }
    }

    func toggle() { player.isPlaying ? player.pause() : player.play() }

    func seek(by seconds: Double) { seek(to: max(0, time + seconds)) }

    func seek(to seconds: Double) {
        player.time = VLCTime(number: NSNumber(value: Int64(max(0, seconds) * 1000)))
        time = seconds
    }

    // MARK: tracks

    var audioTracks: [TrackOption] {
        player.audioTracks.enumerated().map { i, t in TrackOption(id: t.trackId, name: Self.trackName(t.trackName, "Tonspur", i), selected: t.isSelected) }
    }

    var textTracks: [TrackOption] {
        player.textTracks.enumerated().map { i, t in TrackOption(id: t.trackId, name: Self.trackName(t.trackName, "Untertitel", i), selected: t.isSelected) }
    }

    var subtitlesOff: Bool { !player.textTracks.contains { $0.isSelected } }

    func selectAudio(_ id: String) {
        player.audioTracks.first { $0.trackId == id }?.isSelectedExclusively = true
        objectWillChange.send()
    }

    func selectText(_ id: String?) {
        if let id { player.textTracks.first { $0.trackId == id }?.isSelectedExclusively = true } else { player.deselectAllTextTracks() }
        objectWillChange.send()
    }

    private static func trackName(_ raw: String, _ fallback: String, _ i: Int) -> String {
        let t = raw.trimmingCharacters(in: .whitespaces)
        return t.isEmpty ? "\(fallback) \(i + 1)" : t
    }

    // MARK: picture

    private func applyFit() {
        switch fit {
        case .fit:
            player.videoAspectRatio = nil
            player.videoFitMode = .smaller
        case .fill:
            player.videoAspectRatio = nil
            player.videoFitMode = .larger
        case .wide:
            player.videoAspectRatio = "16:9"
            player.videoFitMode = .smaller
        }
    }

    // MARK: picture in picture

    var pipPossible: Bool { pip != nil }

    func togglePictureInPicture() {
        if pipActive { pip?.stopPictureInPicture() } else { pip?.startPictureInPicture() }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        if pipActive { pip?.stopPictureInPicture() }
        player.stop()
        onEnd = nil
    }
}

// VLC hands us its video view through these; the PiP window controls playback through them.
extension PlayerEngine: VLCDrawable, VLCPictureInPictureDrawable, VLCPictureInPictureMediaControlling {
    nonisolated func addSubview(_ view: UIView) {
        onMain {
            videoViews.append(view)
            view.isUserInteractionEnabled = false   // taps belong to our controls
            if let container {
                view.frame = container.bounds
                view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                container.addSubview(view)
            }
        }
    }

    nonisolated func bounds() -> CGRect {
        onMain { container?.bounds ?? UIScreen.main.bounds }
    }

    nonisolated func mediaController() -> (any VLCPictureInPictureMediaControlling)! { self }

    // the header has no nullability annotations, so the block's argument comes in as optional
    nonisolated func pictureInPictureReady() -> (((any VLCPictureInPictureWindowControlling)?) -> Void)! {
        { [weak self] controller in
            Task { @MainActor in
                guard let self, let controller else { return }
                self.pip = controller
                controller.stateChangeEventHandler = { [weak self] on in
                    Task { @MainActor in self?.pipActive = on }
                }
                self.objectWillChange.send()
            }
        }
    }

    nonisolated func play() { Task { @MainActor in self.player.play() } }
    nonisolated func pause() { Task { @MainActor in self.player.pause() } }

    nonisolated func seek(by offset: Int64, completion: @escaping () -> Void) {
        Task { @MainActor in
            self.seek(by: Double(offset) / 1000)
            completion()
        }
    }

    nonisolated func mediaLength() -> Int64 { onMain { Int64(length * 1000) } }
    nonisolated func mediaTime() -> Int64 { onMain { Int64(time * 1000) } }
    nonisolated func isMediaSeekable() -> Bool { onMain { player.isSeekable && length > 0 } }
    nonisolated func isMediaPlaying() -> Bool { onMain { player.isPlaying } }
}

/// VLC and the PiP window may call in from other threads; UIKit and our state live on the main thread.
private func onMain<T>(_ work: @MainActor () -> T) -> T {
    if Thread.isMainThread { return MainActor.assumeIsolated(work) }
    return DispatchQueue.main.sync { MainActor.assumeIsolated(work) }
}

/// Where the video of an engine appears.
struct PlayerSurface: UIViewRepresentable {
    let engine: PlayerEngine
    /// Changes when another surface may have taken the video (e.g. full screen closed) – takes it back.
    var refresh: Bool = false

    func makeUIView(context: Context) -> UIView {
        let v = UIView()
        v.backgroundColor = .black
        v.clipsToBounds = true
        engine.attach(v)
        return v
    }

    func updateUIView(_ v: UIView, context: Context) {
        if engine.container !== v { engine.attach(v) }
    }
}
