import Foundation
import SwiftUI

/// Live TV: one channel at a time through the timeshift buffer (pause, rewind up to 5 minutes, back to live),
/// zapping within the list, programme guide of the channel. Preview in the list and full screen share it –
/// switching between them doesn't open a second connection to the provider.
@MainActor
final class LiveSession: ObservableObject {
    let engine = PlayerEngine()
    let categoryName: String
    @Published private(set) var channels: [LiveChannel]
    @Published private(set) var index: Int
    @Published private(set) var epg: [EpgEntry] = []
    @Published private(set) var behind: TimeInterval = 0
    @Published private(set) var span: TimeInterval = 0
    @Published private(set) var previous: LiveChannel?

    private weak var model: AppModel?
    private var buffer: TimeshiftBuffer?
    private var startWall: TimeInterval = Date().timeIntervalSince1970
    private var ticker: Timer?
    private var epgTask: Task<Void, Never>?

    init(model: AppModel, channels: [LiveChannel], index: Int, category: String) {
        self.model = model
        self.channels = channels
        self.index = min(max(index, 0), max(channels.count - 1, 0))
        categoryName = category
        ticker = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        tune(self.index)
    }

    var current: LiveChannel { channels[index] }
    var canRewind: Bool { buffer != nil }
    var atLive: Bool { behind < 8 }
    var nextChannel: LiveChannel? { channels.isEmpty ? nil : channels[(index + 1) % channels.count] }
    var previousChannel: LiveChannel? { channels.isEmpty ? nil : channels[(index - 1 + channels.count) % channels.count] }
    var now: EpgEntry? { epg.first { $0.isNow } }

    func tune(_ i: Int) {
        guard let model, channels.indices.contains(i) else { return }
        if i != index { previous = current }
        index = i
        let c = channels[i]
        buffer?.stop()
        buffer = nil
        epg = []
        if c.id < 0 {
            if let url = DemoSource.url(c.id) { engine.open(url) }
        } else if Prefs.timeshiftMinutes == 0, let ts = model.client?.liveTSURL(c.id) {
            engine.open(ts)   // timeshift switched off: straight from the provider
        } else if let ts = model.client?.liveTSURL(c.id) {
            let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("timeshift", isDirectory: true)
            let b = TimeshiftBuffer(url: ts, directory: dir).start()
            buffer = b
            if let local = LoopbackServer.shared.serve(b, from: 0) { engine.open(local) } else { engine.open(ts) }
        } else {
            model.notice = "Zum Abspielen auf dem iPhone braucht es den IPTV-Zugang (Einstellungen)."
            return
        }
        startWall = Date().timeIntervalSince1970
        behind = 0
        model.rememberWatched(c)
        loadEpg()
    }

    func zap(_ delta: Int) {
        guard !channels.isEmpty else { return }
        tune((index + delta + channels.count) % channels.count)
    }

    func backToPrevious() {
        guard let p = previous, let i = channels.firstIndex(where: { $0.id == p.id }) else { return }
        tune(i)
    }

    /// Rewind (negative) or forward within the buffer.
    func shift(by delta: TimeInterval) { seek(behind: behind - delta) }

    /// Plays from [seconds] behind live.
    func seek(behind target: TimeInterval) {
        guard let b = buffer else { return }
        let t = min(max(target, 0), min(TimeshiftBuffer.keep, b.span))
        if t < 8 { goLive(); return }
        let (offset, wall) = b.offset(at: t)
        guard let url = LoopbackServer.shared.serve(b, from: offset) else { return }
        engine.open(url)
        startWall = wall
        behind = Date().timeIntervalSince1970 - wall
    }

    func goLive() {
        guard let b = buffer, let url = LoopbackServer.shared.serve(b, from: b.offset(at: 2).offset) else { return }
        engine.open(url)
        startWall = Date().timeIntervalSince1970 - 2
        behind = 0
    }

    private func tick() {
        span = buffer?.span ?? 0
        if buffer != nil {
            behind = max(0, Date().timeIntervalSince1970 - startWall - engine.time)
        }
        if let n = now, Date() >= n.stop { loadEpg() }
    }

    private func loadEpg() {
        epgTask?.cancel()
        let id = current.id
        guard id > 0, Prefs.loadEpg, let model else { return }
        epgTask = Task {
            // a moment of patience while zapping: only the channel you stay on costs a request
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            guard !Task.isCancelled else { return }
            let list = await model.epgStore.load(id, tv: model.orderedTVs.first, client: model.client)
            if !Task.isCancelled, current.id == id { epg = list }
        }
    }

    func stop() {
        ticker?.invalidate()
        ticker = nil
        epgTask?.cancel()
        engine.stop()
        buffer?.stop()
        buffer = nil
    }
}

/// A film or an episode (and the episodes that follow), with "Weiterschauen" every 10 seconds.
@MainActor
final class VodSession: ObservableObject {
    let engine = PlayerEngine()
    @Published private(set) var item: PlayItem
    @Published private(set) var queue: [PlayItem]
    private weak var model: AppModel?
    private var saver: Timer?

    init?(model: AppModel, item: PlayItem, start: Double, queue: [PlayItem]) {
        guard let url = model.streamURL(item) else { return nil }
        self.model = model
        self.item = item
        self.queue = queue
        engine.onEnd = { [weak self] in self?.finished() }
        engine.open(url, at: start)
        saver = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.save() }
        }
    }

    var next: PlayItem? { queue.first }

    func save() {
        guard engine.time > 1, engine.length > 0 else { return }
        model?.saveProgress(item, position: engine.time, duration: engine.length, next: queue.first)
    }

    func playNext() {
        guard let model, let n = queue.first, let url = model.streamURL(n) else { return }
        save()
        item = n
        queue.removeFirst()
        engine.onEnd = { [weak self] in self?.finished() }
        engine.open(url)
    }

    private func finished() {
        if engine.length > 0 { model?.saveProgress(item, position: engine.length, duration: engine.length, next: queue.first) }
        if queue.isEmpty { model?.closeVod() } else { playNext() }
    }

    func stop() {
        save()
        saver?.invalidate()
        saver = nil
        engine.stop()
    }
}
