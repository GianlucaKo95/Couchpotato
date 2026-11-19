import Foundation
import Network

/// Live timeshift like on the TVs (Timeshift.kt): ONE connection to the provider records the channel into
/// small files, the player always reads from there. So you can pause and rewind up to [keep] seconds –
/// and the provider still only sees a single viewer.
final class TimeshiftBuffer: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    static let keep: TimeInterval = 5 * 60
    private static let chunk: Int64 = 4 * 1024 * 1024
    private static let maxBytes: Int64 = 600 * 1024 * 1024

    private let url: URL
    private let dir: URL
    private let cond = NSCondition()
    private(set) var head: Int64 = 0          // bytes recorded so far
    private(set) var oldest: Int64 = 0        // first byte still on disk
    private var index: [(wall: TimeInterval, offset: Int64)] = []   // every ~0.5 s
    private(set) var failed: String?
    private var stopped = false
    private var failures = 0
    private var session: URLSession?
    private var out: FileHandle?
    private var outChunk: Int64 = -1
    let startedAt = Date().timeIntervalSince1970

    init(url: URL, directory: URL) {
        self.url = url
        dir = directory
        super.init()
    }

    func start() -> TimeshiftBuffer {
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        connect()
        return self
    }

    func stop() {
        cond.lock()
        stopped = true
        cond.broadcast()
        cond.unlock()
        session?.invalidateAndCancel()
        session = nil
        try? out?.close()
        let d = dir
        DispatchQueue.global(qos: .utility).async { try? FileManager.default.removeItem(at: d) }
    }

    private func connect() {
        guard !stopped else { return }
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 20
        cfg.httpAdditionalHeaders = ["User-Agent": XtreamClient.userAgent]
        let s = URLSession(configuration: cfg, delegate: self, delegateQueue: nil)
        session = s
        s.dataTask(with: url).resume()
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        let code = (response as? HTTPURLResponse)?.statusCode ?? 200
        completionHandler((200..<300).contains(code) ? .allow : .cancel)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        failures = 0
        write(data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        session.finishTasksAndInvalidate()
        guard !stopped else { return }
        failures += 1
        if failures >= 6 {
            cond.lock()
            failed = error?.localizedDescription ?? "Sender nicht erreichbar"
            cond.broadcast()
            cond.unlock()
            return
        }
        DispatchQueue.global().asyncAfter(deadline: .now() + 1.5 * Double(failures)) { [weak self] in self?.connect() }
    }

    private func chunkFile(_ n: Int64) -> URL { dir.appendingPathComponent("c\(n).ts") }

    private func write(_ data: Data) {
        var off = 0
        while off < data.count {
            let chunk = head / Self.chunk
            if chunk != outChunk {
                try? out?.close()
                FileManager.default.createFile(atPath: chunkFile(chunk).path, contents: nil)
                out = try? FileHandle(forWritingTo: chunkFile(chunk))
                outChunk = chunk
            }
            let room = Int((chunk + 1) * Self.chunk - head)
            let len = min(room, data.count - off)
            try? out?.write(contentsOf: data.subdata(in: off..<(off + len)))
            off += len
            cond.lock()
            head += Int64(len)
            let now = Date().timeIntervalSince1970
            if index.isEmpty || now - index[index.count - 1].wall >= 0.5 { index.append((now, head)) }
            evict(now)
            cond.broadcast()
            cond.unlock()
        }
    }

    /// Drops what is older than [keep] (and keeps the disk use bounded). Called with the lock held.
    private func evict(_ now: TimeInterval) {
        let keepFrom = index.last { $0.wall <= now - Self.keep - 20 }?.offset ?? 0
        let from = max(keepFrom, head - Self.maxBytes, 0)
        while oldest / Self.chunk < from / Self.chunk && oldest / Self.chunk < head / Self.chunk {
            try? FileManager.default.removeItem(at: chunkFile(oldest / Self.chunk))
            oldest = (oldest / Self.chunk + 1) * Self.chunk
        }
        while index.count > 2 && index[1].offset < oldest { index.removeFirst() }
    }

    /// Byte offset and wall time of what was recorded [delay] seconds ago (or the oldest still kept).
    func offset(at delay: TimeInterval) -> (offset: Int64, wall: TimeInterval) {
        cond.lock(); defer { cond.unlock() }
        let target = Date().timeIntervalSince1970 - delay
        var best: (TimeInterval, Int64)?
        for e in index { if e.wall <= target && e.offset >= oldest { best = (e.wall, e.offset) }; if e.wall > target { break } }
        if let best { return (best.1, best.0) }
        if let first = index.first(where: { $0.offset >= oldest }) { return (first.offset, first.wall) }
        return (head, Date().timeIntervalSince1970)
    }

    /// How far back the buffer reaches right now (seconds).
    var span: TimeInterval {
        cond.lock(); defer { cond.unlock() }
        guard let first = index.first(where: { $0.offset >= oldest }) else { return 0 }
        return Date().timeIntervalSince1970 - first.wall
    }

    /// Reads at [pos]; waits for the recording when it caught up with live. nil = stopped or failed.
    func read(at pos: Int64, max: Int) -> (data: Data, from: Int64)? {
        cond.lock()
        let deadline = Date().addingTimeInterval(20)
        while pos >= head {
            if stopped || failed != nil || Date() > deadline { cond.unlock(); return nil }
            cond.wait(until: Date().addingTimeInterval(0.5))
        }
        let from = Swift.max(pos, oldest)
        let chunk = from / Self.chunk
        let avail = Swift.min(head, (chunk + 1) * Self.chunk) - from
        cond.unlock()
        guard let h = try? FileHandle(forReadingFrom: chunkFile(chunk)) else { return nil }
        defer { try? h.close() }
        try? h.seek(toOffset: UInt64(from - chunk * Self.chunk))
        guard let d = try? h.read(upToCount: Int(Swift.min(Int64(max), avail))) else { return nil }
        return (d, from)
    }
}

/// Serves the timeshift buffer to VLC on 127.0.0.1: GET /live?from=<byte offset> streams from there on.
final class LoopbackServer: @unchecked Sendable {
    static let shared = LoopbackServer()
    private var listener: NWListener?
    private(set) var port: UInt16 = 0
    private let lock = NSLock()
    private var buffer: TimeshiftBuffer?
    private var generation = 0
    private let queue = DispatchQueue(label: "cp.loopback", attributes: .concurrent)

    /// Makes [b] the buffer that is served; returns the address for VLC.
    func serve(_ b: TimeshiftBuffer, from offset: Int64) -> URL? {
        lock.lock()
        buffer = b
        generation += 1
        let gen = generation
        lock.unlock()
        if listener == nil { startListener() }
        guard port > 0 else { return nil }
        return URL(string: "http://127.0.0.1:\(port)/live?from=\(offset)&g=\(gen)")
    }

    private func startListener() {
        let params = NWParameters.tcp
        params.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        guard let l = try? NWListener(using: params) else { return }
        let ready = DispatchSemaphore(value: 0)
        l.stateUpdateHandler = { [weak self] state in
            if case .ready = state { self?.port = l.port?.rawValue ?? 0; ready.signal() }
            if case .failed = state { ready.signal() }
        }
        l.newConnectionHandler = { [weak self] c in self?.handle(c) }
        l.start(queue: queue)
        listener = l
        _ = ready.wait(timeout: .now() + 2)
    }

    private func handle(_ c: NWConnection) {
        c.start(queue: queue)
        c.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, _, _ in
            guard let self, let data, let head = String(data: data, encoding: .utf8) else { c.cancel(); return }
            let target = head.split(separator: " ").dropFirst().first.map(String.init) ?? "/"
            let comps = URLComponents(string: "http://x" + target)
            let from = comps?.queryItems?.first { $0.name == "from" }?.value.flatMap { Int64($0) } ?? 0
            let gen = comps?.queryItems?.first { $0.name == "g" }?.value.flatMap { Int($0) } ?? -1
            self.lock.lock()
            let b = gen == self.generation ? self.buffer : nil
            self.lock.unlock()
            guard let b else {
                c.send(content: Data("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8), completion: .contentProcessed { _ in c.cancel() })
                return
            }
            let header = "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
            c.send(content: Data(header.utf8), completion: .contentProcessed { _ in })
            self.pump(c, b, from, gen)
        }
    }

    /// Sends the recording on as it grows; stops when VLC disconnects or another channel took over.
    private func pump(_ c: NWConnection, _ b: TimeshiftBuffer, _ pos: Int64, _ gen: Int) {
        queue.async { [weak self] in
            guard let self else { return }
            self.lock.lock(); let current = gen == self.generation; self.lock.unlock()
            guard current, let r = b.read(at: pos, max: 256 * 1024), !r.data.isEmpty else { c.cancel(); return }
            c.send(content: r.data, completion: .contentProcessed { error in
                if error != nil { c.cancel(); return }
                self.pump(c, b, r.from + Int64(r.data.count), gen)
            })
        }
    }
}
