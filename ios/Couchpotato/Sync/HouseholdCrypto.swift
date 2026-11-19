import CommonCrypto
import CryptoKit
import Foundation

/// Keys of a household, derived from its code exactly like the TVs do (CloudSync.kt):
/// PBKDF2-HMAC-SHA256, 210 000 rounds, 512 bit. First half = access key (the server only sees its
/// hash), second half = AES-256 key that never leaves the devices.
struct HouseholdKeys: Equatable {
    let auth: Data
    let enc: Data

    static let iterations: UInt32 = 210_000
    static let salt = Array("couchpotato-household-v1".utf8)
    private static let format: UInt8 = 1

    var authHex: String { auth.map { String(format: "%02x", $0) }.joined() }
    var encoded: Data { auth + enc }

    init(auth: Data, enc: Data) {
        self.auth = auth
        self.enc = enc
    }

    init?(encoded: Data) {
        guard encoded.count == 64 else { return nil }
        self.init(auth: encoded.prefix(32), enc: encoded.suffix(32))
    }

    /// Slow on purpose (about a quarter second on an iPhone): call it off the main thread.
    static func derive(code: String) -> HouseholdKeys {
        var out = [UInt8](repeating: 0, count: 64)
        let password = Array(code.utf8).map { CChar(bitPattern: $0) }
        _ = CCKeyDerivationPBKDF(
            CCPBKDFAlgorithm(kCCPBKDF2), password, password.count, salt, salt.count,
            CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), iterations, &out, out.count
        )
        return HouseholdKeys(auth: Data(out[0..<32]), enc: Data(out[32..<64]))
    }

    private var aad: Data { Data(SHA256.hash(data: auth)) }

    /// Key for talking to the TVs in the same Wi-Fi (TvLink.kt): derived from the AES key, never sent anywhere.
    var linkKey: Data {
        Data(HMAC<SHA256>.authenticationCode(for: Data("couchpotato-link-v1".utf8), using: SymmetricKey(data: enc)))
    }

    /// What the TVs announce in Bonjour so the phone only lists its own household's TVs.
    static func tag(_ linkKey: Data) -> String { SHA256.hash(data: linkKey).prefix(6).map { String(format: "%02x", $0) }.joined() }

    /// JSON → gzip → AES-256-GCM, bound to this household. Same layout as on the TVs: 1 | iv | ciphertext | tag.
    func seal(_ json: Data) throws -> String {
        let box = try AES.GCM.seal(Gzip.compress(json), using: SymmetricKey(data: enc), nonce: AES.GCM.Nonce(), authenticating: aad)
        var packed = Data([Self.format])
        packed += Data(box.nonce)
        packed += box.ciphertext
        packed += box.tag
        return packed.base64EncodedString()
    }

    func open(_ blob: String) -> Data? {
        guard let packed = Data(base64Encoded: blob), packed.count > 1 + 12 + 16, packed.first == Self.format else { return nil }
        let bytes = [UInt8](packed)
        guard let nonce = try? AES.GCM.Nonce(data: bytes[1..<13]),
              let box = try? AES.GCM.SealedBox(nonce: nonce, ciphertext: bytes[13..<(bytes.count - 16)], tag: bytes[(bytes.count - 16)...]),
              let zipped = try? AES.GCM.open(box, using: SymmetricKey(data: enc), authenticating: aad)
        else { return nil }
        return Gzip.decompress(zipped)
    }
}

enum HouseholdCode {
    static let length = 10

    /// Same rules as the TVs: no I, L, O, U – typed letters that look alike are fixed.
    static func normalize(_ code: String) -> String {
        let fixed = code.uppercased()
            .replacingOccurrences(of: "O", with: "0")
            .replacingOccurrences(of: "I", with: "1")
            .replacingOccurrences(of: "L", with: "1")
        let allowed = Set("0123456789ABCDEFGHJKMNPQRSTVWXYZ")
        return String(fixed.filter { allowed.contains($0) })
    }

    static func display(_ code: String) -> String { "\(code.prefix(5))-\(code.dropFirst(5))" }
}

/// gzip as Java's GZIPInputStream/GZIPOutputStream expect it (the TVs read and write it that way).
enum Gzip {
    static func compress(_ data: Data) throws -> Data {
        let deflated = try (data as NSData).compressed(using: .zlib) as Data   // raw DEFLATE
        var out = Data([0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 0xff])
        out += deflated
        out += littleEndian(crc32(data))
        out += littleEndian(UInt32(truncatingIfNeeded: data.count))
        return out
    }

    static func decompress(_ data: Data) -> Data? {
        let b = [UInt8](data)
        guard b.count > 18, b[0] == 0x1f, b[1] == 0x8b, b[2] == 8 else { return nil }
        let flags = b[3]
        var pos = 10
        if flags & 4 != 0 { guard pos + 2 <= b.count else { return nil }; pos += 2 + Int(b[pos]) + Int(b[pos + 1]) << 8 }
        if flags & 8 != 0 { while pos < b.count, b[pos] != 0 { pos += 1 }; pos += 1 }
        if flags & 16 != 0 { while pos < b.count, b[pos] != 0 { pos += 1 }; pos += 1 }
        if flags & 2 != 0 { pos += 2 }
        guard pos < b.count - 8 else { return nil }
        let body = Data(b[pos..<(b.count - 8)])
        guard let out = try? (body as NSData).decompressed(using: .zlib) as Data else { return nil }
        let expected = UInt32(b[b.count - 8]) | UInt32(b[b.count - 7]) << 8 | UInt32(b[b.count - 6]) << 16 | UInt32(b[b.count - 5]) << 24
        return crc32(out) == expected ? out : nil
    }

    private static let table: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i)
        for _ in 0..<8 { c = c & 1 != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    static func crc32(_ data: Data) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        for byte in data { c = table[Int((c ^ UInt32(byte)) & 0xFF)] ^ (c >> 8) }
        return c ^ 0xFFFF_FFFF
    }

    private static func littleEndian(_ v: UInt32) -> Data { Data([UInt8(v & 0xFF), UInt8(v >> 8 & 0xFF), UInt8(v >> 16 & 0xFF), UInt8(v >> 24)]) }
}
