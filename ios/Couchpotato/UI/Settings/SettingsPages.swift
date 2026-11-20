import SwiftUI

/// Einstellungen – grouped like on the TV.
struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @StateObject private var prefs = DevicePrefs()

    var body: some View {
        NavigationStack {
            if model.profile.kid {
                KidLockView()
            } else {
                SettingsPage(title: "Einstellungen") {
            Section {
                    if model.household.linked {
                        Button { model.showRemote = true } label: { RowLabel(title: "Fernbedienung", icon: "appletvremote.gen4", value: model.orderedTVs.first?.name ?? "kein Fernseher im WLAN") }
                            .listRowBackground(Theme.panel)
                    }
                    NavRow(title: "IPTV-Zugang", icon: "key.fill", value: model.login == nil ? "nicht eingetragen" : model.login?.user) { AccountPage(prefs: prefs) }
                    NavRow(title: "Profile", icon: "person.2.fill", value: model.profile.name) { ProfilesPage() }
                    NavRow(title: "Darstellung", icon: "paintpalette.fill") { DisplayPage(prefs: prefs) }
                    NavRow(title: "Live TV", icon: "play.tv.fill") { LiveSettingsPage(prefs: prefs) }
                    NavRow(title: "Kategorien", icon: "list.bullet.rectangle") { CategoriesPage() }
                    NavRow(title: "Kindersicherung", icon: "lock.fill") { ParentalPage() }
                    NavRow(title: "Geräte & Daten", icon: "laptopcomputer.and.iphone") { DevicesPage(prefs: prefs) }
                    NavRow(title: "System-Info", icon: "info.circle.fill") { SystemInfoPage() }
            }
        }
                .toolbar { ToolbarItem(placement: .topBarTrailing) { TopButtons() } }
            }
        }
        .tint(Theme.text)
    }
}

/// Kids don't get into the settings – only back to a grown-up profile (with the PIN).
struct KidLockView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(spacing: 18) {
            Avatar(profile: model.profile, size: 96)
            Text("Kinderprofil „\(model.profile.name)“").font(Theme.title(26)).foregroundStyle(Theme.text)
            if case .allowed(let left?) = model.screenTime.state(model.profile) {
                Text("Heute noch \(left) Minuten Fernsehzeit").foregroundStyle(Theme.muted)
            }
            Menu {
                ForEach(model.profiles.filter { $0.id != model.profile.id }) { p in Button(p.name) { model.chooseProfile(p.id) } }
            } label: {
                Text("Profil wechseln (Eltern-PIN)").font(Theme.heading(17)).frame(maxWidth: 360).padding(.vertical, 13)
                    .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14)).foregroundStyle(Theme.text)
            }
        }
        .padding(28).frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.background)
    }
}

// MARK: IPTV

struct AccountPage: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var prefs: DevicePrefs
    @State private var images = 0
    @State private var login = false
    @State private var status: String?
    @State private var checking = false
    @State private var requests = 0
    @State private var blockedUntil: Date?

    var body: some View {
        SettingsPage(title: "IPTV-Zugang") {
            Section {
            if let l = model.login {
                RowLabel(title: "Server", value: l.base)
                RowLabel(title: "Benutzer", value: l.user)
                ActionRow(title: checking ? "Wird geprüft …" : "Verbindung prüfen", detail: status ?? "Kostet eine Anfrage beim Anbieter") {
                    Task {
                        checking = true
                        defer { checking = false }
                        do { status = try await XtreamClient(login: l).checkLogin() } catch { status = error.localizedDescription }
                        await count()
                    }
                }
                Button { login = true } label: { RowLabel(title: "Zugang ändern") }.listRowBackground(Theme.panel)
                ActionRow(title: "Zugang entfernen", confirm: "Der Zugang wird von diesem iPhone gelöscht. Der Katalog bleibt, solange ein Fernseher im WLAN ihn liefert.", destructive: true) {
                    Task { _ = await model.setLogin(nil) }
                }
            } else {
                Button { login = true } label: { RowLabel(title: "IPTV-Zugang eingeben", icon: "plus.circle") }.listRowBackground(Theme.panel)
                if let share = model.household.linked ? "Teilt dein Hauptfernseher seinen Zugang mit dem Haushalt, kannst du ihn unter „Geräte & Daten → Jetzt abgleichen“ übernehmen." : nil {
                    Text(share).font(.footnote).foregroundStyle(Theme.muted).listRowBackground(Color.clear)
                }
            }
            }
            Section("Anfrage-Bremse") {
            RowLabel(title: "Anfragen in der letzten Stunde", value: "\(requests) von \(RequestGate.maxPerHour)",
                     detail: "Couchpotato fragt höchstens alle 2,5 Sekunden und höchstens 40-mal pro Stunde – so sperrt der Anbieter dein Konto nicht.")
            ToggleRow(title: "Bilder vom Anbieter-Server",
                      detail: "Senderlogos und Cover, die direkt beim Anbieter liegen: höchstens 60 pro Stunde, einzeln nacheinander – oder gar nicht laden. In der letzten Stunde: \(images).",
                      on: $prefs.providerImages)
            if let blockedUntil, blockedUntil > Date() {
                RowLabel(title: "Pause wegen Sperre bis", value: blockedUntil.formatted(date: .omitted, time: .shortened))
            }
            }
        }
        .sheet(isPresented: $login) { LoginSheet() }
        .task { await count() }
    }

    private func count() async {
        requests = await RequestGate.shared.requestsLastHour()
        images = await RequestGate.shared.imagesLastHour()
        blockedUntil = await RequestGate.shared.blockedUntil
    }
}

// MARK: Profiles

struct ProfilesPage: View {
    @EnvironmentObject private var model: AppModel
    /// Set when shown as "Wer schaut?" sheet: picking closes it.
    var onPick: ((String) -> Void)? = nil
    @State private var editing: Profile?
    @State private var manage = false

    var body: some View {
        ScrollView {
            VStack(spacing: 30) {
                Text("Wer schaut?").font(.largeTitle.bold()).foregroundStyle(Theme.text).padding(.top, 8)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 24)], spacing: 24) {
                    ForEach(model.profiles) { p in
                        Button {
                            if manage { editing = p } else if let onPick { onPick(p.id) } else { model.chooseProfile(p.id) }
                        } label: {
                            VStack(spacing: 10) {
                                Avatar(profile: p, size: 104, rounded: true)
                                    .overlay(RoundedRectangle(cornerRadius: 25).stroke(p.id == model.profile.id ? Theme.text : .clear, lineWidth: 4))
                                    .overlay(alignment: .bottomTrailing) {
                                        if manage { Image(systemName: "pencil.circle.fill").font(.system(size: 30)).foregroundStyle(Theme.text, Theme.surface).offset(x: 6, y: 6) }
                                    }
                                Text(p.name).font(.headline).foregroundStyle(Theme.text).lineLimit(1)
                                if p.kid { Text("Kinder").font(.caption).foregroundStyle(Theme.muted) }
                            }
                        }
                    }
                    Button { editing = model.newProfile() } label: {
                        VStack(spacing: 10) {
                            RoundedRectangle(cornerRadius: 25).stroke(Theme.muted, lineWidth: 3).frame(width: 104, height: 104)
                                .overlay(Image(systemName: "plus").font(.system(size: 40, weight: .light)).foregroundStyle(Theme.muted))
                            Text("Profil hinzufügen").font(.system(size: 16)).foregroundStyle(Theme.muted)
                        }
                    }
                }
                .padding(.horizontal, 24)
                Button { manage.toggle() } label: {
                    Label(manage ? "Fertig" : "Profile verwalten", systemImage: manage ? "checkmark" : "pencil")
                }
                .buttonStyle(QuietButton(wide: false))
                Text("Profile gelten im ganzen Haushalt – auch auf deinen Fernsehern.").font(.footnote).foregroundStyle(Theme.muted)
            }
            .padding(.bottom, 30)
            .frame(maxWidth: .infinity)
        }
        .background(Theme.background.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $editing) { e in ProfileEditor(profile: e, isNew: !model.profiles.contains { $0.id == e.id }) }
    }
}

struct ProfileEditor: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State var profile: Profile
    let isNew: Bool

    private func withAnimal(_ i: Int) -> Profile {
        var p = profile
        p.animal = i
        return p
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 16) {
                        Avatar(profile: profile, size: 72, rounded: true)
                        TextField("Name", text: $profile.name).font(.title2.weight(.semibold)).foregroundStyle(Theme.text)
                    }
                    .padding(.vertical, 4)
                    .listRowBackground(Theme.panel)
                }
                Section("Farbe") {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 12) {
                            ForEach(0..<8, id: \.self) { i in
                                Button { profile.color = i } label: {
                                    Circle().fill(Theme.avatarColors[i]).frame(width: 40, height: 40)
                                        .overlay(Circle().stroke(Theme.text, lineWidth: profile.color == i ? 3 : 0).padding(-4))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(8)
                    }
                    .listRowBackground(Theme.panel)
                }
                Section("Tier") {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 12) {
                            ForEach(0..<Profile.animals, id: \.self) { i in
                                Button { profile.animal = i } label: {
                                    Avatar(profile: withAnimal(i), size: 56, rounded: true)
                                        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.text, lineWidth: profile.animalIndex == i ? 3 : 0).padding(-3))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(6)
                    }
                    .listRowBackground(Theme.panel)
                }
                if profile.id != "main" {
                    Section {
                        ToggleRow(title: "Kinderprofil", on: $profile.kid)
                        if profile.kid {
                            ChoiceRow(title: "Fernsehzeit pro Tag",
                                      options: [(0, "Unbegrenzt"), (30, "30 Min."), (45, "45 Min."), (60, "1 Std."), (90, "1,5 Std."), (120, "2 Std."), (180, "3 Std.")],
                                      value: $profile.dailyMinutes)
                            ChoiceRow(title: "Schlafenszeit",
                                      options: [(-1, "Keine")] + stride(from: 17 * 60, through: 22 * 60, by: 30).map { ($0, String(format: "%d:%02d Uhr", $0 / 60, $0 % 60)) },
                                      value: $profile.bedtime)
                        }
                    } footer: {
                        Text("Kinderprofile sehen nur die freigegebenen Kategorien (Kindersicherung) und lassen sich nur mit der Eltern-PIN verlassen.")
                    }
                }
                if !isNew && profile.id != "main" {
                    Section {
                        ActionRow(title: "Profil löschen", confirm: "„\(profile.name)“ mit Merkliste und Verlauf auf allen Geräten löschen?", destructive: true) {
                            model.deleteProfile(profile.id)
                            dismiss()
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(Theme.background)
            .navigationTitle(isNew ? "Profil hinzufügen" : "Profil bearbeiten")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Speichern") {
                        profile.name = profile.name.trimmed
                        if profile.kid && !ParentPin.isSet {
                            let p = profile
                            dismiss()
                            model.pinRequest = PinRequest(title: "Eltern-PIN für Kinderprofile festlegen", setNew: true) { model.saveProfile(p) }
                        } else {
                            model.saveProfile(profile)
                            dismiss()
                        }
                    }
                    .disabled(profile.name.trimmed.isEmpty)
                }
            }
        }
    }
}

// MARK: Darstellung

struct DisplayPage: View {
    @ObservedObject var prefs: DevicePrefs

    var body: some View {
        SettingsPage(title: "Darstellung") {
            Section {
            ChoiceRow(title: "Ausrichtung", detail: "Quer sieht aus wie am Fernseher.",
                      options: Prefs.Orientation.allCases.map { ($0, $0.label) }, value: $prefs.orientation)
            ChoiceRow(title: "Bildformat", detail: "Gilt für alle Videos; im Player änderbar.",
                      options: PictureFit.allCases.map { ($0, $0.label) }, value: $prefs.fit)
            ChoiceRow(title: "Untertitel-Darstellung", options: SubtitleSize.allCases.map { ($0, $0.label) }, value: $prefs.subtitles)
            }
        }
    }
}

// MARK: Live TV

struct LiveSettingsPage: View {
    @ObservedObject var prefs: DevicePrefs

    var body: some View {
        SettingsPage(title: "Live TV") {
            Section {
            ChoiceRow(title: "Live pausieren & zurückspulen",
                      detail: "Couchpotato nimmt den laufenden Sender auf dem iPhone mit – über nur eine Verbindung zum Anbieter.",
                      options: [(0, "Aus"), (5, "5 Minuten"), (10, "10 Minuten"), (15, "15 Minuten")], value: $prefs.timeshift)
            ChoiceRow(title: "Live-Puffer", detail: "Größer = weniger Ruckler bei schwachem WLAN, aber später live.",
                      options: [(800, "Klein"), (1500, "Normal"), (3000, "Groß"), (5000, "Sehr groß")], value: $prefs.buffer)
            ToggleRow(title: "Wischgesten im Player", detail: "Nach links/rechts wischen schaltet um.", on: $prefs.swipe)
            ToggleRow(title: "Programm (EPG) laden",
                      detail: "Kommt vom Fernseher im WLAN; sonst eine Anfrage pro Sender beim Anbieter, nur solange die Stunde noch Luft hat.",
                      on: $prefs.epg)
            }
        }
    }
}

// MARK: Kategorien

struct CategoriesPage: View {
    var body: some View {
        SettingsPage(title: "Kategorien") {
            Section {
            NavRow(title: "Live-Kategorien ausblenden") { CategoryToggles(kind: Entry.live, mode: .hide) }
            NavRow(title: "Film-Kategorien ausblenden") { CategoryToggles(kind: Entry.movie, mode: .hide) }
            NavRow(title: "Serien-Kategorien ausblenden") { CategoryToggles(kind: Entry.series, mode: .hide) }
            NavRow(title: "Kategorien sortieren – Sender") { CategoryOrder(kind: Entry.live) }
            NavRow(title: "Kategorien sortieren – Filme") { CategoryOrder(kind: Entry.movie) }
            NavRow(title: "Kategorien sortieren – Serien") { CategoryOrder(kind: Entry.series) }
            Text("Gilt im ganzen Haushalt – auch auf deinen Fernsehern.").font(.footnote).foregroundStyle(Theme.muted).listRowBackground(Color.clear)
            }
        }
    }
}

/// Switch categories on/off: hide them, or release them for kids.
struct CategoryToggles: View {
    enum Mode { case hide, kids }
    @EnvironmentObject private var model: AppModel
    let kind: Character
    let mode: Mode
    @State private var query = ""

    private func bulkButton(_ text: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(text, systemImage: icon).font(Theme.heading(16)).frame(maxWidth: .infinity).padding(.vertical, 12)
                .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12)).foregroundStyle(Theme.text)
        }
        .buttonStyle(.plain)
    }

    private var title: String {
        let what = kind == Entry.live ? "Sender" : kind == Entry.movie ? "Filme" : "Serien"
        return mode == .hide ? "Ausblenden – \(what)" : "Kinderprofil – \(what)"
    }

    var body: some View {
        let cats = model.orderedCategories(kind).filter { query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) }
        List {
            Section {
                // all at once – applies to what the search shows, otherwise to every category
                HStack(spacing: 12) {
                    bulkButton(mode == .hide ? "Alle ausblenden" : "Keine freigeben", icon: mode == .hide ? "eye.slash" : "circle") {
                        if mode == .hide { model.setHidden(kind, cats, true) } else { model.setKidCategories(kind, cats, false) }
                    }
                    bulkButton(mode == .hide ? "Alle einblenden" : "Alle freigeben", icon: mode == .hide ? "eye" : "checkmark.circle") {
                        if mode == .hide { model.setHidden(kind, cats, false) } else { model.setKidCategories(kind, cats, true) }
                    }
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }
            Section {
                ForEach(cats) { c in
                    let on = mode == .hide ? !model.isHidden(kind, c) : model.isKidCategory(kind, c)
                    Button {
                        if mode == .hide { model.setHidden(kind, c, on) } else { model.setKidCategory(kind, c, !on) }
                    } label: {
                        HStack {
                            Text(c.name).foregroundStyle(on ? Theme.text : Theme.muted)
                            Spacer()
                            Image(systemName: on ? (mode == .hide ? "eye" : "checkmark.circle.fill") : (mode == .hide ? "eye.slash" : "circle"))
                                .foregroundStyle(on ? Theme.accent : Theme.muted)
                        }
                    }
                    .listRowBackground(Theme.panel)
                }
            } footer: {
                Text(mode == .hide ? "Ausgeblendete Kategorien verschwinden überall – auch auf deinen Fernsehern."
                                   : "Kinderprofile sehen nur diese Kategorien – auch auf deinen Fernsehern.")
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, prompt: "Kategorie suchen")
        .overlay { if model.catalog.entries.isEmpty { ContentUnavailableView("Noch kein Katalog", systemImage: "list.bullet") } }
    }
}

struct CategoryOrder: View {
    @EnvironmentObject private var model: AppModel
    let kind: Character
    @State private var list: [LiveCategory] = []

    var body: some View {
        List {
            Section {
                ForEach(list) { c in Text(c.name).foregroundStyle(model.isHidden(kind, c) ? Theme.muted : Theme.text).listRowBackground(Theme.panel) }
                    .onMove { from, to in
                        list.move(fromOffsets: from, toOffset: to)
                        model.saveOrder(kind, list)
                    }
            } footer: {
                Text("Zum Verschieben halten und ziehen. Gilt auch auf deinen Fernsehern.")
            }
        }
        .environment(\.editMode, .constant(.active))
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .navigationTitle(kind == Entry.live ? "Sortieren – Sender" : kind == Entry.movie ? "Sortieren – Filme" : "Sortieren – Serien")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Zurücksetzen") { model.resetOrder(kind); list = model.orderedCategories(kind) }
            }
        }
        .onAppear { list = model.orderedCategories(kind) }
    }
}

// MARK: Kindersicherung

struct ParentalPage: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        SettingsPage(title: "Kindersicherung") {
            Section {
            ActionRow(title: ParentPin.isSet ? "Eltern-PIN ändern" : "Eltern-PIN festlegen",
                      detail: "Zum Verlassen eines Kinderprofils und für „30 Minuten mehr“. Gilt nur auf diesem iPhone.",
                      value: ParentPin.isSet ? "gesetzt" : "aus") {
                model.pinRequest = PinRequest(title: "Neue Eltern-PIN", setNew: true) {}
            }
            NavRow(title: "Kinderprofil – Sender") { CategoryToggles(kind: Entry.live, mode: .kids) }
            NavRow(title: "Kinderprofil – Filme") { CategoryToggles(kind: Entry.movie, mode: .kids) }
            NavRow(title: "Kinderprofil – Serien") { CategoryToggles(kind: Entry.series, mode: .kids) }
            }
            Section("Fernsehzeit") {
                let kids = model.profiles.filter(\.kid)
                if kids.isEmpty {
                    Text("Ein Kinderprofil legst du unter Profile an.").foregroundStyle(Theme.muted).listRowBackground(Theme.panel)
                }
                ForEach(kids) { p in
                    RowLabel(title: p.name,
                             value: [p.dailyMinutes > 0 ? "\(p.dailyMinutes) Min./Tag" : "unbegrenzt",
                                     p.bedtime >= 0 ? String(format: "bis %d:%02d", p.bedtime / 60, p.bedtime % 60) : nil].compactMap { $0 }.joined(separator: " · "),
                             detail: "Ändern unter Profile → Profile verwalten")
                }
            }
        }
    }
}

struct DevicesPage: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var prefs: DevicePrefs
    @State private var join = false

    var body: some View {
        SettingsPage(title: "Geräte & Daten") {
            Section {
            if HouseholdSync.configured {
                if model.household.linked {
                    RowLabel(title: "Geräte synchronisieren", value: model.household.code,
                             detail: model.household.lastSync.map { "Zuletzt abgeglichen \($0.formatted(date: .omitted, time: .shortened))" } ?? "Noch nicht abgeglichen")
                    RowLabel(title: "Fernseher im WLAN", value: model.orderedTVs.isEmpty ? "keiner gefunden" : model.orderedTVs.map(\.name).joined(separator: ", "))
                    ActionRow(title: model.household.busy ? "Gleicht ab …" : "Jetzt abgleichen") { Task { await model.syncNow(quiet: false) } }
                    ActionRow(title: "Haushalt auf diesem iPhone verlassen",
                              confirm: "Profile, Favoriten und „Weiterschauen“ verschwinden von diesem iPhone. Deine Fernseher behalten alles.",
                              destructive: true) { model.leaveHousehold() }
                } else {
                    Button { join = true } label: {
                        RowLabel(title: "Mit meinen Fernsehern verbinden", icon: "tv.and.mediabox", detail: "Haushalts-Code vom Fernseher eingeben")
                    }
                    .listRowBackground(Theme.panel)
                }
            }
            }
            Section("Katalog") {
            ChoiceRow(title: "Listen-Aktualisierung",
                      detail: "Wie oft das iPhone den Anbieter selbst fragt (6 Anfragen), wenn kein Fernseher im WLAN den Katalog liefert.",
                      options: [(12, "Alle 12 Stunden"), (24, "Alle 24 Stunden"), (48, "Alle 2 Tage")], value: $prefs.catalogHours)
            ActionRow(title: model.loadingCatalog ? "Lädt …" : "Katalog jetzt aktualisieren", value: model.catalog.entries.isEmpty ? nil : model.catalog.source) {
                Task { await model.refreshCatalog(force: true, background: false) }
            }
            }
            Section("Verlauf (Profil „\(model.profile.name)“)") {
            ActionRow(title: "Live-Verlauf löschen", confirm: "Die zuletzt gesehenen Sender löschen?", destructive: true) { model.clearLiveHistory() }
            ActionRow(title: "Film-Verlauf löschen", confirm: "Alle Filme aus „Weiterschauen“ löschen – auf allen Geräten?", destructive: true) { model.clearProgress(prefix: "vod:") }
            ActionRow(title: "Serien-Verlauf löschen", confirm: "Alle Serien aus „Weiterschauen“ löschen – auf allen Geräten?", destructive: true) { model.clearProgress(prefix: "ep:") }
            ActionRow(title: "„Für dich“ zurücksetzen", confirm: "Den Verlauf löschen, aus dem „Für dich“ lernt – auf allen Geräten?", destructive: true) { model.clearWatchHistory() }
            ActionRow(title: "Meine Liste leeren", confirm: "Meine Liste leeren – auf allen Geräten?", destructive: true) { model.clearMyList() }
            }
        }
        .sheet(isPresented: $join) { JoinSheet() }
    }
}

// MARK: System-Info

struct SystemInfoPage: View {
    @EnvironmentObject private var model: AppModel
    @State private var dataSize: Int64 = 0
    @State private var cacheSize: Int64 = 0
    @State private var requests = 0

    var body: some View {
        SettingsPage(title: "System-Info") {
            Section {
            RowLabel(title: "Version", value: Self.version)
            RowLabel(title: "Gerät", value: "\(Self.deviceModel) · iOS \(UIDevice.current.systemVersion) · \(Self.free) frei")
            let e = model.catalog.entries
            RowLabel(title: "Katalog", value: e.isEmpty ? "noch keiner" : model.catalog.fetchedAt.formatted(date: .numeric, time: .shortened),
                     detail: e.isEmpty ? nil : "\(model.catalog.source) · \(count(Entry.live)) Live TV · \(count(Entry.movie)) Filme · \(count(Entry.series)) Serien")
            RowLabel(title: "Anfragen beim Anbieter", value: "\(requests) in der letzten Stunde")
            ActionRow(title: "Speicher (antippen = Cache leeren)", value: "Daten \(Self.mb(dataSize)) · Cache \(Self.mb(cacheSize))") {
                Self.clearCache()
                Task { await measure() }
            }
            NavRow(title: "Datenschutz") { PrivacyView() }
            NavRow(title: "Lizenzen") { LicensesView() }
            Text("Couchpotato enthält keine Sender, Filme oder Serien. Nutze nur Quellen, für die du selbst berechtigt bist.")
                .font(.footnote).foregroundStyle(Theme.muted).padding(.top, 6)
            }
        }
        .task { await measure() }
    }

    private func count(_ k: Character) -> String { model.catalog.entries.lazy.filter { $0.kind == k }.count.formatted() }

    private func measure() async {
        requests = await RequestGate.shared.requestsLastHour()
        let dir = model.directory
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let (d, c) = await Task.detached { (Self.size(dir), Self.size(caches)) }.value
        dataSize = d
        cacheSize = c
    }

    nonisolated static func size(_ url: URL) -> Int64 {
        guard let e = FileManager.default.enumerator(at: url, includingPropertiesForKeys: [.totalFileAllocatedSizeKey]) else { return 0 }
        var total: Int64 = 0
        for case let f as URL in e { total += Int64((try? f.resourceValues(forKeys: [.totalFileAllocatedSizeKey]).totalFileAllocatedSize) ?? 0) }
        return total
    }

    static func clearCache() {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        for name in ["images"] { try? FileManager.default.removeItem(at: caches.appendingPathComponent(name)) }
        URLCache.shared.removeAllCachedResponses()
    }

    static func mb(_ b: Int64) -> String { ByteCountFormatter.string(fromByteCount: b, countStyle: .file) }

    static var version: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(v) (\(b))"
    }

    static var deviceModel: String {
        var info = utsname()
        uname(&info)
        let id = withUnsafeBytes(of: &info.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
        return "\(UIDevice.current.model) (\(id))"
    }

    static var free: String {
        let home = URL(fileURLWithPath: NSHomeDirectory())
        let v = (try? home.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage) ?? 0
        return mb(Int64(v))
    }
}
