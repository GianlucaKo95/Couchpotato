import Foundation
import Security

/// Small secrets (household keys, IPTV login) – only on this iPhone, not in iCloud backups.
enum Keychain {
    private static let service = "de.koeven.couchpotato"

    static func data(_ name: String) -> Data? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: name,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        return SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess ? out as? Data : nil
    }

    static func set(_ name: String, _ value: Data?) {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: name,
        ]
        SecItemDelete(q as CFDictionary)
        guard let value else { return }
        var add = q
        add[kSecValueData as String] = value
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    static func string(_ name: String) -> String? { data(name).flatMap { String(data: $0, encoding: .utf8) } }
    static func setString(_ name: String, _ value: String?) { set(name, value.map { Data($0.utf8) }) }
}
