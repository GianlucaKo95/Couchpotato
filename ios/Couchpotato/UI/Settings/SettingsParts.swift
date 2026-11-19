import SwiftUI

/// A settings page like on the TV: big title, cards below each other.
struct SettingsPage<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(title).font(Theme.title(32)).foregroundStyle(Theme.text).padding(.bottom, 6)
                content()
            }
            .padding(.horizontal, 24).padding(.vertical, 12)
            .frame(maxWidth: 900, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .background(Theme.background)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Theme.background, for: .navigationBar)
    }
}

/// The card look of every row.
struct SettingsCard<Content: View>: View {
    @ViewBuilder let content: () -> Content
    var body: some View {
        HStack(spacing: 16, content: content)
            .padding(.horizontal, 20).frame(minHeight: 60)
            .background(Theme.panel, in: RoundedRectangle(cornerRadius: 16))
            .contentShape(RoundedRectangle(cornerRadius: 16))
    }
}

struct RowLabel: View {
    let title: String
    var icon: String? = nil
    var value: String? = nil
    var detail: String? = nil
    var chevron = false
    var destructive = false

    var body: some View {
        SettingsCard {
            if let icon { Image(systemName: icon).font(.system(size: 22)).frame(width: 30).foregroundStyle(Theme.text) }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 18, weight: .medium)).foregroundStyle(destructive ? Theme.error : Theme.text)
                if let detail { Text(detail).font(.footnote).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true) }
            }
            .padding(.vertical, 10)
            Spacer(minLength: 8)
            if let value { Text(value).font(.system(size: 17)).foregroundStyle(Theme.muted).multilineTextAlignment(.trailing).lineLimit(2) }
            if chevron { Image(systemName: "chevron.right").font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.muted) }
        }
    }
}

/// Row that opens a page.
struct NavRow<Destination: View>: View {
    let title: String
    var icon: String? = nil
    var value: String? = nil
    @ViewBuilder let destination: () -> Destination

    var body: some View {
        NavigationLink { destination() } label: { RowLabel(title: title, icon: icon, value: value, chevron: true) }
            .buttonStyle(.plain)
    }
}

/// Row with a choice of values (opens a menu).
struct ChoiceRow<T: Hashable>: View {
    let title: String
    var detail: String? = nil
    let options: [(T, String)]
    @Binding var value: T

    var body: some View {
        Menu {
            ForEach(options, id: \.0) { o in
                Button { value = o.0 } label: { if o.0 == value { Label(o.1, systemImage: "checkmark") } else { Text(o.1) } }
            }
        } label: {
            RowLabel(title: title, value: options.first { $0.0 == value }?.1, detail: detail)
        }
        .buttonStyle(.plain)
    }
}

struct ToggleRow: View {
    let title: String
    var detail: String? = nil
    @Binding var on: Bool

    var body: some View {
        Button { on.toggle() } label: { RowLabel(title: title, value: on ? "an" : "aus", detail: detail) }
            .buttonStyle(.plain)
    }
}

/// Row that does something, optionally after asking.
struct ActionRow: View {
    let title: String
    var detail: String? = nil
    var value: String? = nil
    var confirm: String? = nil
    var destructive = false
    let action: () -> Void
    @State private var asking = false

    var body: some View {
        Button { if confirm != nil { asking = true } else { action() } } label: {
            RowLabel(title: title, value: value, detail: detail, destructive: destructive)
        }
        .buttonStyle(.plain)
        .confirmationDialog(confirm ?? "", isPresented: $asking, titleVisibility: .visible) {
            Button(title, role: destructive ? .destructive : nil, action: action)
        }
    }
}

struct SectionTitle: View {
    let text: String
    var body: some View { Text(text).font(Theme.heading(17)).foregroundStyle(Theme.muted).padding(.top, 12) }
}

/// Bindings onto the device settings, so pages update when they change.
@MainActor
final class DevicePrefs: ObservableObject {
    @Published var timeshift = Prefs.timeshiftMinutes { didSet { Prefs.timeshiftMinutes = timeshift } }
    @Published var buffer = Prefs.liveBuffer { didSet { Prefs.liveBuffer = buffer } }
    @Published var swipe = Prefs.swipeToZap { didSet { Prefs.swipeToZap = swipe } }
    @Published var epg = Prefs.loadEpg { didSet { Prefs.loadEpg = epg } }
    @Published var catalogHours = Prefs.catalogHours { didSet { Prefs.catalogHours = catalogHours } }
    @Published var orientation = Prefs.orientation { didSet { Prefs.orientation = orientation } }
    @Published var fit = PictureFit(rawValue: UserDefaults.standard.string(forKey: "cp.fit") ?? "") ?? .fit {
        didSet { UserDefaults.standard.set(fit.rawValue, forKey: "cp.fit") }
    }
    @Published var subtitles = SubtitleSize(rawValue: UserDefaults.standard.string(forKey: "cp.subs") ?? "") ?? .normal {
        didSet { UserDefaults.standard.set(subtitles.rawValue, forKey: "cp.subs") }
    }
}
