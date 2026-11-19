import CryptoKit
import SwiftUI
import UIKit

/// Couchpotato's colours, the same as on the TVs (ui/Theme.kt).
enum Theme {
    static let background = Color(hex: 0x0E3E44)
    static let surface = Color(hex: 0x165A61)
    static let panel = Color(hex: 0x0B3236)
    static let accent = Color(hex: 0xE8B86D)
    static let onAccent = Color(hex: 0x1B1408)
    static let text = Color(hex: 0xF4F1EC)
    static let muted = Color(hex: 0xC3D6D7)
    static let error = Color(hex: 0xFFA197)

    static func title(_ size: CGFloat) -> Font { .custom("Baloo2-ExtraBold", size: size, relativeTo: .title) }
    static func heading(_ size: CGFloat) -> Font { .custom("Baloo2-Bold", size: size, relativeTo: .headline) }

    static let avatarColors: [Color] = [0xE8B86D, 0xEE8C72, 0x8FD3C1, 0x9DB7F5, 0xD99BE0, 0xF2D16B, 0x7FC77F, 0xF29E9E].map { Color(hex: $0) }
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double(hex >> 16 & 0xFF) / 255, green: Double(hex >> 8 & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

struct AccentButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Theme.heading(18))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(Theme.accent.opacity(configuration.isPressed ? 0.75 : 1), in: RoundedRectangle(cornerRadius: 14))
            .foregroundStyle(Theme.onAccent)
    }
}

struct QuietButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Theme.heading(17))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 13)
            .background(Theme.surface.opacity(configuration.isPressed ? 0.7 : 1), in: RoundedRectangle(cornerRadius: 14))
            .foregroundStyle(Theme.text)
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
