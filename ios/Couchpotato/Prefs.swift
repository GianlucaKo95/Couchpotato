import SwiftUI
import UIKit

/// Settings of this iPhone only (like the device settings on each TV).
enum Prefs {
    private static let d = UserDefaults.standard

    /// Live pausieren & zurückspulen: minutes kept, 0 = off.
    static var timeshiftMinutes: Int {
        get { d.object(forKey: "cp.timeshift") as? Int ?? 5 }
        set { d.set(newValue, forKey: "cp.timeshift") }
    }

    /// Live-Puffer: VLC network caching in milliseconds.
    static var liveBuffer: Int {
        get { d.object(forKey: "cp.livebuffer") as? Int ?? 1500 }
        set { d.set(newValue, forKey: "cp.livebuffer") }
    }

    static var swipeToZap: Bool {
        get { d.object(forKey: "cp.swipe") as? Bool ?? true }
        set { d.set(newValue, forKey: "cp.swipe") }
    }

    /// Pictures that lie on the provider's own server: load them (throttled, 60 per hour) or not at all.
    static var loadProviderImages: Bool {
        get { d.object(forKey: "cp.providerimages") as? Bool ?? true }
        set { d.set(newValue, forKey: "cp.providerimages") }
    }

    static var loadEpg: Bool {
        get { d.object(forKey: "cp.epg") as? Bool ?? true }
        set { d.set(newValue, forKey: "cp.epg") }
    }

    /// Listen-Aktualisierung: catalogue from the provider at most every N hours.
    static var catalogHours: Int {
        get { d.object(forKey: "cp.cataloghours") as? Int ?? 24 }
        set { d.set(newValue, forKey: "cp.cataloghours") }
    }

    static var orientation: Orientation {
        get { Orientation(rawValue: d.string(forKey: "cp.orientation") ?? "") ?? .auto }
        set { d.set(newValue.rawValue, forKey: "cp.orientation"); Orientation.apply() }
    }

    enum Orientation: String, CaseIterable {
        case auto, landscape, portrait
        var label: String {
            switch self {
            case .auto: return "Automatisch"
            case .landscape: return "Immer quer"
            case .portrait: return "Immer hochkant"
            }
        }
        var mask: UIInterfaceOrientationMask {
            switch self {
            case .auto: return .allButUpsideDown
            case .landscape: return .landscape
            case .portrait: return .portrait
            }
        }

        static func apply() {
            for case let scene as UIWindowScene in UIApplication.shared.connectedScenes {
                scene.windows.first?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
                scene.requestGeometryUpdate(.iOS(interfaceOrientations: Prefs.orientation.mask)) { _ in }
            }
        }
    }
}

/// Lets the app lock the orientation ("Darstellung → Ausrichtung").
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        Prefs.orientation.mask
    }
}
