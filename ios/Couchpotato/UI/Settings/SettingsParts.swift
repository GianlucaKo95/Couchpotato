import SwiftUI

/// A settings page in Apple's grouped style.
struct SettingsPage<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        List(content: content)
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(Theme.background)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.large)
            .listRowBackground(Theme.panel)
    }
}

/// Title (with optional icon and explanation) and a value on the right.
struct RowLabel: View {
    let title: String
    var icon: String? = nil
    var value: String? = nil
    var detail: String? = nil
    var chevron = false
    var destructive = false

    var body: some View {
        HStack(spacing: 12) {
            if let icon {
                Image(systemName: icon).font(.system(size: 17, weight: .medium)).frame(width: 28).foregroundStyle(destructive ? Theme.error : Theme.muted)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(destructive ? Theme.error : Theme.text)
                if let detail { Text(detail).font(.footnote).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true) }
            }
            Spacer(minLength: 8)
            if let value { Text(value).foregroundStyle(Theme.muted).multilineTextAlignment(.trailing).lineLimit(2) }
            if chevron { Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.faint) }
        }
        .listRowBackground(Theme.panel)
    }
}

/// Row that opens a page.
struct NavRow<Destination: View>: View {
    let title: String
    var icon: String? = nil
    var value: String? = nil
    @ViewBuilder let destination: () -> Destination

    var body: some View {
        NavigationLink { destination() } label: { RowLabel(title: title, icon: icon, value: value) }
            .listRowBackground(Theme.panel)
    }
}

/// Row with a choice of values (a menu on the right, like in Apple's Settings).
struct ChoiceRow<T: Hashable>: View {
    let title: String
    var detail: String? = nil
    let options: [(T, String)]
    @Binding var value: T

    var body: some View {
        Picker(selection: $value) {
            ForEach(options, id: \.0) { o in Text(o.1).tag(o.0) }
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(Theme.text)
                if let detail { Text(detail).font(.footnote).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true) }
            }
        }
        .pickerStyle(.menu)
        .tint(Theme.muted)
        .listRowBackground(Theme.panel)
    }
}

struct ToggleRow: View {
    let title: String
    var detail: String? = nil
    @Binding var on: Bool

    var body: some View {
        Toggle(isOn: $on) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(Theme.text)
                if let detail { Text(detail).font(.footnote).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true) }
            }
        }
        .tint(Theme.accent)
        .listRowBackground(Theme.panel)
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
        .listRowBackground(Theme.panel)
        .confirmationDialog(confirm ?? "", isPresented: $asking, titleVisibility: .visible) {
            Button(title, role: destructive ? .destructive : nil, action: action)
        }
    }
}

/// Small heading outside lists (profile editor).
struct SectionTitle: View {
    let text: String
    var body: some View { Text(text).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.faint).padding(.top, 12) }
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
