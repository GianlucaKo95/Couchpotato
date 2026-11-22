import CryptoKit
import SwiftUI
import ImageIO
import UIKit

/// The living room in the evening: a dark room with a hint of Couchpotato's petrol, the colour comes from
/// the pictures. Gold is the signature – the primary action, progress and the current selection, nothing else.
enum Theme {
    static let background = Color(hex: 0x0B0F10)   // the room
    static let panel = Color(hex: 0x151A1B)        // raised one step (rows, cards)
    static let surface = Color(hex: 0x20272A)      // raised two steps (selected, controls)
    static let hairline = Color.white.opacity(0.08)
    static let accent = Color(hex: 0xE8B86D)       // Couchpotato gold
    static let onAccent = Color(hex: 0x1B1408)
    static let text = Color(hex: 0xF2F2F0)
    static let muted = Color(hex: 0x9EA7A9)        // secondary text
    static let faint = Color(hex: 0x6B7476)        // tertiary text
    static let live = Color(hex: 0xE5484D)         // the live dot
    static let error = Color(hex: 0xFF8A80)

    /// San Francisco, like Apple's apps; the size follows the iPhone's text size setting.
    static func title(_ size: CGFloat) -> Font { .system(size: size, weight: .bold) }
    static func heading(_ size: CGFloat) -> Font { .system(size: size, weight: .semibold) }
    /// Only for the wordmark – Couchpotato's own rounded face.
    static func logo(_ size: CGFloat) -> Font { .custom("Baloo2-ExtraBold", size: size) }

    /// Soft backgrounds for the avatar animals – the same as on the TVs.
    static let avatarColors: [Color] = [0xF6D9A8, 0xF7C1C1, 0xBFD9F2, 0xC8E6C0, 0xDCC7EE, 0xFBE3B5, 0xBDE9EE, 0xF8C9DE].map { Color(hex: $0) }
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double(hex >> 16 & 0xFF) / 255, green: Double(hex >> 8 & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

/// The one primary action of a screen: bright Liquid Glass with dark text, like "Play" in Apple's TV app.
struct AccentButton: ButtonStyle {
    var wide = true
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 17, weight: .semibold))
            .frame(maxWidth: wide ? .infinity : nil)
            .padding(.horizontal, 24).frame(minHeight: 50)
            .foregroundStyle(Theme.background)
            .glassEffect(.regular.tint(Theme.text).interactive(), in: Capsule())
    }
}

/// Secondary actions: clear Liquid Glass.
struct QuietButton: ButtonStyle {
    var wide = true
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 17, weight: .semibold))
            .frame(maxWidth: wide ? .infinity : nil)
            .padding(.horizontal, 20).frame(minHeight: 50)
            .foregroundStyle(Theme.text)
            .glassEffect(.regular.interactive(), in: Capsule())
    }
}

/// Round glass control (player, remote).
struct GlassCircle: ButtonStyle {
    var size: CGFloat = 52
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .frame(width: size, height: size)
            .foregroundStyle(Theme.text)
            .glassEffect(.regular.interactive(), in: Circle())
    }
}

/// A small text line under a title: "Live" with a red dot, "Jetzt" in gold.
struct LiveBadge: View {
    var on = true
    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(on ? Theme.live : Theme.faint).frame(width: 8, height: 8)
            Text("Live").font(.system(size: 13, weight: .semibold))
        }
    }
}

/// Pictures with a disk cache. Pictures on the provider's own server go through the request brake
/// (one at a time, limited per hour) – a list of channel logos must not hammer the provider.
/// Pictures: disk cache + memory cache. Reading, decoding and shrinking to the size on screen happen off the
/// main thread – a poster 120 pt wide no longer decodes a 1000-pixel original while scrolling.
final class ImageStore: @unchecked Sendable {
    static let shared = ImageStore()
    private let memory: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        c.totalCostLimit = 120 * 1024 * 1024          // decoded bytes
        return c
    }()
    private let folder: URL = {
        let f = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("images", isDirectory: true)
        try? FileManager.default.createDirectory(at: f, withIntermediateDirectories: true)
        return f
    }()
    @MainActor var providerHost: String?

    /// Sizes are rounded up to 64-pixel steps, so a row of equal posters shares one cache entry each.
    private static func bucket(_ px: CGSize) -> CGSize {
        func r(_ v: CGFloat) -> CGFloat { min(max(ceil(v / 64) * 64, 64), 2400) }
        return CGSize(width: r(px.width), height: r(px.height))
    }
    private static func key(_ url: URL, _ px: CGSize, _ fill: Bool) -> NSString {
        "\(url.absoluteString)|\(Int(px.width))x\(Int(px.height))\(fill ? "f" : "t")" as NSString
    }

    func cached(_ url: URL, pixels: CGSize, fill: Bool) -> UIImage? {
        memory.object(forKey: Self.key(url, Self.bucket(pixels), fill))
    }

    @MainActor
    func load(_ url: URL, pixels: CGSize, fill: Bool) async -> UIImage? {
        let px = Self.bucket(pixels), key = Self.key(url, px, fill)
        if let m = memory.object(forKey: key) { return m }
        let fromProvider = url.host?.lowercased() == providerHost
        if fromProvider && !Prefs.loadProviderImages {
            return await Task.detached(priority: .userInitiated) { self.fromDisk(url, px, fill, key) }.value
        }
        if let img = await Task.detached(priority: .userInitiated, operation: { self.fromDisk(url, px, fill, key) }).value {
            return img
        }
        let fetch: @Sendable () async throws -> Data = {
            var req = URLRequest(url: url, timeoutInterval: 20)
            req.setValue(XtreamClient.userAgent, forHTTPHeaderField: "User-Agent")
            let (d, r) = try await URLSession.shared.data(for: req)
            guard ((r as? HTTPURLResponse)?.statusCode ?? 0) / 100 == 2 else { throw URLError(.badServerResponse) }
            return d
        }
        guard let d = try? await (fromProvider ? RequestGate.shared.runImage(fetch) : fetch()) else { return nil }
        return await Task.detached(priority: .userInitiated) {
            try? d.write(to: self.file(url), options: .atomic)
            return self.decode(d, px, fill, key)
        }.value
    }

    private func file(_ url: URL) -> URL {
        folder.appendingPathComponent(SHA256.hash(data: Data(url.absoluteString.utf8)).map { String(format: "%02x", $0) }.joined())
    }

    private func fromDisk(_ url: URL, _ px: CGSize, _ fill: Bool, _ key: NSString) -> UIImage? {
        guard let d = try? Data(contentsOf: file(url)) else { return nil }
        return decode(d, px, fill, key)
    }

    /// Decodes straight at the needed size (ImageIO thumbnail) – fully decoded here, not later on the main thread.
    private func decode(_ d: Data, _ px: CGSize, _ fill: Bool, _ key: NSString) -> UIImage? {
        guard let src = CGImageSourceCreateWithData(d as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let iw = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.doubleValue,
              let ih = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.doubleValue, iw > 0, ih > 0 else {
            return UIImage(data: d)?.preparingForDisplay()
        }
        let sx = px.width / iw, sy = px.height / ih
        let scale = min(fill ? max(sx, sy) : min(sx, sy), 1)
        let maxPixel = max(iw, ih) * scale
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(Int(maxPixel.rounded(.up)), 1),
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
        let img = UIImage(cgImage: cg)
        memory.setObject(img, forKey: key, cost: cg.bytesPerRow * cg.height)
        return img
    }
}

struct RemoteImage<Placeholder: View>: View {
    let url: String?
    var mode: ContentMode = .fill
    @ViewBuilder let placeholder: () -> Placeholder
    @State private var image: UIImage?
    @State private var size: CGSize = .zero
    @Environment(\.displayScale) private var scale

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: mode)
            } else {
                placeholder()
            }
        }
        .onGeometryChange(for: CGSize.self) { $0.size } action: { size = $0 }
        .task(id: LoadKey(url: url, w: Int(size.width), h: Int(size.height))) {
            guard let s = url, let u = URL(string: s), s.hasPrefix("http") else { image = nil; return }
            guard size.width > 0, size.height > 0 else { return }
            let px = CGSize(width: size.width * scale, height: size.height * scale)
            if let c = ImageStore.shared.cached(u, pixels: px, fill: mode == .fill) { image = c; return }
            if let img = await ImageStore.shared.load(u, pixels: px, fill: mode == .fill), !Task.isCancelled { image = img }
        }
    }

    private struct LoadKey: Equatable { let url: String?; let w: Int; let h: Int }
}

/// Channel logo on a light tile (logos are made for light backgrounds), or the initials.
struct ChannelLogo: View {
    let channel: LiveChannel
    var size: CGFloat = 48

    var body: some View {
        RoundedRectangle(cornerRadius: size * 0.22)
            .fill(Theme.surface)
            .overlay {
                RemoteImage(url: channel.logo, mode: .fit) {
                    Text(initials).font(Theme.heading(size * 0.36)).foregroundStyle(Theme.muted)
                }
                .padding(size * 0.12)
            }
            .frame(width: size, height: size)
    }

    private var initials: String {
        let words = channel.name.split(separator: " ").prefix(2)
        return words.map { String($0.prefix(1)) }.joined().uppercased()
    }
}

/// A profile's animal on its colour, like on the TVs.
struct Avatar: View {
    let profile: Profile
    var size: CGFloat = 40
    var rounded = false

    var body: some View {
        let bg = Theme.avatarColors[((profile.color % 8) + 8) % 8]
        Image("Animal\(profile.animalIndex)").resizable().scaledToFit()
            .padding(size * 0.06)
            .frame(width: size, height: size)
            .background(bg, in: RoundedRectangle(cornerRadius: rounded ? size * 0.24 : size / 2))
            .accessibilityLabel(profile.name)
    }
}
