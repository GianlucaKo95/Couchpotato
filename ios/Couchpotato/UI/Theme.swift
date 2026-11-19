import CryptoKit
import SwiftUI
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

/// The one primary action of a screen: off-white with dark text, like "Play" in Apple's TV app.
struct AccentButton: ButtonStyle {
    var wide = true
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 17, weight: .semibold))
            .frame(maxWidth: wide ? .infinity : nil)
            .padding(.horizontal, 24).frame(minHeight: 48)
            .background(Theme.text, in: RoundedRectangle(cornerRadius: 12))
            .foregroundStyle(Theme.background)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// Secondary actions: grey glass.
struct QuietButton: ButtonStyle {
    var wide = true
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 17, weight: .semibold))
            .frame(maxWidth: wide ? .infinity : nil)
            .padding(.horizontal, 20).frame(minHeight: 48)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.hairline))
            .foregroundStyle(Theme.text)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
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
@MainActor
final class ImageStore {
    static let shared = ImageStore()
    private let memory = NSCache<NSURL, UIImage>()
    private let folder: URL = {
        let f = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("images", isDirectory: true)
        try? FileManager.default.createDirectory(at: f, withIntermediateDirectories: true)
        return f
    }()
    var providerHost: String?

    func cached(_ url: URL) -> UIImage? { memory.object(forKey: url as NSURL) }

    func load(_ url: URL) async -> UIImage? {
        if let m = memory.object(forKey: url as NSURL) { return m }
        let name = SHA256.hash(data: Data(url.absoluteString.utf8)).map { String(format: "%02x", $0) }.joined()
        let file = folder.appendingPathComponent(name)
        if let d = try? Data(contentsOf: file), let img = UIImage(data: d) {
            memory.setObject(img, forKey: url as NSURL)
            return img
        }
        let fetch: () async throws -> Data = {
            var req = URLRequest(url: url, timeoutInterval: 20)
            req.setValue(XtreamClient.userAgent, forHTTPHeaderField: "User-Agent")
            let (d, r) = try await URLSession.shared.data(for: req)
            guard ((r as? HTTPURLResponse)?.statusCode ?? 0) / 100 == 2 else { throw URLError(.badServerResponse) }
            return d
        }
        let fromProvider = url.host?.lowercased() == providerHost
        guard let d = try? await (fromProvider ? RequestGate.shared.runImage(fetch) : fetch()),
              let img = UIImage(data: d) else { return nil }
        try? d.write(to: file, options: .atomic)
        memory.setObject(img, forKey: url as NSURL)
        return img
    }
}

struct RemoteImage<Placeholder: View>: View {
    let url: String?
    var mode: ContentMode = .fill
    @ViewBuilder let placeholder: () -> Placeholder
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: mode)
            } else {
                placeholder()
            }
        }
        .task(id: url) {
            guard let s = url, let u = URL(string: s), s.hasPrefix("http") else { image = nil; return }
            image = ImageStore.shared.cached(u)
            if image == nil { image = await ImageStore.shared.load(u) }
        }
    }
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
