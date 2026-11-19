import CryptoKit
import Foundation

/// Parents' PIN on this iPhone (like on the TVs, it is never synced). Stored as a hash in the keychain.
enum ParentPin {
    static var isSet: Bool { Keychain.string("kids.pin") != nil }

    static func check(_ pin: String) -> Bool { Keychain.string("kids.pin") == hash(pin) }

    static func set(_ pin: String) { Keychain.setString("kids.pin", hash(pin)) }

    private static func hash(_ pin: String) -> String {
        SHA256.hash(data: Data("couchpotato:\(pin)".utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

/// Screen time for kids profiles, with the limits set on the TV (daily minutes, bedtime).
/// Counted is playing time on this iPhone; the count stays on the iPhone, like on each TV.
@MainActor
final class ScreenTime {
    enum State: Equatable {
        case allowed(remainingMinutes: Int?)
        case limit(evening: Bool)
        case bedtime(at: String)
    }

    private let defaults = UserDefaults.standard

    private static var today: String {
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd"
        return f.string(from: Date())
    }

    func used(_ p: Profile) -> TimeInterval { defaults.double(forKey: "cp.st.used.\(p.id).\(Self.today)") }

    func add(_ seconds: TimeInterval, for p: Profile) {
        guard p.kid else { return }
        defaults.set(used(p) + seconds, forKey: "cp.st.used.\(p.id).\(Self.today)")
    }

    /// Parents' "30 Minuten mehr" – today only; also moves bedtime back.
    func addBonus(_ minutes: Int, for p: Profile) {
        let key = "cp.st.bonus.\(p.id).\(Self.today)"
        defaults.set(defaults.integer(forKey: key) + minutes, forKey: key)
    }

    func state(_ p: Profile) -> State {
        guard p.kid else { return .allowed(remainingMinutes: nil) }
        let bonus = defaults.integer(forKey: "cp.st.bonus.\(p.id).\(Self.today)")
        let c = Calendar.current.dateComponents([.hour, .minute], from: Date())
        let now = (c.hour ?? 0) * 60 + (c.minute ?? 0)
        if p.bedtime >= 0, now >= p.bedtime + bonus || now < 6 * 60 {
            return .bedtime(at: String(format: "%d:%02d", p.bedtime / 60, p.bedtime % 60))
        }
        guard p.dailyMinutes > 0 else { return .allowed(remainingMinutes: nil) }
        let left = p.dailyMinutes + bonus - Int(used(p) / 60)
        if left > 0 { return .allowed(remainingMinutes: left) }
        return .limit(evening: (c.hour ?? 0) >= 19 || (c.hour ?? 0) < 6)
    }
}
