import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var sheet: WelcomeView.Sheet?
    @State private var requests = 0
    @State private var confirmLeave = false

    var body: some View {
        NavigationStack {
            if model.profile.kid {
                kidLock
            } else {
                form
            }
        }
    }

    /// Kids don't get into the settings – only switching back to a grown-up profile (with the PIN).
    private var kidLock: some View {
        VStack(spacing: 18) {
            Image(systemName: "lock.fill").font(.system(size: 48)).foregroundStyle(Theme.accent)
            Text("Kinderprofil „\(model.profile.name)“").font(Theme.title(26)).foregroundStyle(Theme.text)
            if case .allowed(let left?) = model.screenTime.state(model.profile) {
                Text("Heute noch \(left) Minuten Fernsehzeit").foregroundStyle(Theme.muted)
            }
            Menu {
                ForEach(model.profiles.filter { $0.id != model.profile.id }) { p in
                    Button(p.name) { model.chooseProfile(p.id) }
                }
            } label: {
                Text("Profil wechseln (Eltern-PIN)").font(Theme.heading(17)).frame(maxWidth: .infinity).padding(.vertical, 13)
                    .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14)).foregroundStyle(Theme.text)
            }
        }
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.background.ignoresSafeArea())
        .navigationTitle("Einstellungen")
    }

    private var form: some View {
            Form {
                Section {
                    if let login = model.login {
                        LabeledContent("Server", value: login.base)
                        LabeledContent("Benutzer", value: login.user)
                        Button("Zugang ändern") { sheet = .login }
                        Button("Katalog jetzt aktualisieren") { Task { await model.refreshCatalog(force: true, background: false); await count() } }
                            .disabled(model.loadingCatalog)
                        Button("Zugang entfernen", role: .destructive) { Task { _ = await model.setLogin(nil) } }
                    } else {
                        Button("IPTV-Zugang eingeben") { sheet = .login }
                    }
                } header: {
                    Text("IPTV")
                } footer: {
                    VStack(alignment: .leading, spacing: 4) {
                        if !model.catalog.entries.isEmpty {
                            Text("Katalog vom \(model.catalog.fetchedAt.formatted(date: .abbreviated, time: .shortened)) · \(model.catalog.source)")
                        }
                        Text("Anfragen an den Anbieter in der letzten Stunde: \(requests) von \(RequestGate.maxPerHour)")
                    }
                }

                if HouseholdSync.configured {
                    Section {
                        if model.household.linked {
                            LabeledContent("Code", value: model.household.code ?? "–")
                            if let at = model.household.lastSync {
                                LabeledContent("Zuletzt abgeglichen", value: at.formatted(date: .omitted, time: .shortened))
                            }
                            Button("Jetzt abgleichen") { Task { await model.syncNow(quiet: false) } }
                                .disabled(model.household.busy)
                            if model.orderedTVs.isEmpty {
                                Text("Kein Fernseher im WLAN gefunden").foregroundStyle(Theme.muted)
                            } else {
                                ForEach(model.orderedTVs) { tv in Label(tv.name, systemImage: "tv").foregroundStyle(Theme.text) }
                            }
                            Button("Haushalt auf diesem iPhone verlassen", role: .destructive) { confirmLeave = true }
                        } else {
                            Button("Mit meinen Fernsehern verbinden") { sheet = .join }
                        }
                    } header: {
                        Text("Haushalt")
                    } footer: {
                        Text("Profile, Favoriten, „Weiterschauen“ und Meine Liste – Ende-zu-Ende-verschlüsselt. Fernseher im selben WLAN liefern den Katalog (ohne Anfragen beim Anbieter), spielen ab und lassen sich fernsteuern. Den IPTV-Zugang teilt nur der Hauptfernseher, und nur wenn du es dort einschaltest.")
                    }
                }

                if model.profiles.count > 1 {
                    Section {
                        Picker("Wer schaut?", selection: Binding(get: { model.profile.id }, set: { model.chooseProfile($0) })) {
                            ForEach(model.profiles) { p in Text(p.kid ? "\(p.name) (Kinder)" : p.name).tag(p.id) }
                        }
                        Button(ParentPin.isSet ? "Eltern-PIN ändern" : "Eltern-PIN festlegen") {
                            model.pinRequest = PinRequest(title: "Neue Eltern-PIN", setNew: true) {}
                        }
                    } header: {
                        Text("Profil")
                    } footer: {
                        Text("Kinderprofile sehen nur die Kategorien, die am Fernseher für Kinder freigegeben sind; Fernsehzeit und Schlafenszeit gelten wie dort.")
                    }
                }

                Section {
                    Toggle("Demo-Sender zeigen", isOn: $model.demo)
                } footer: {
                    Text("Freie Teststreams, ganz ohne Zugang. Sie erscheinen nur, solange kein IPTV-Zugang eingetragen ist.")
                }

                Section("Über") {
                    NavigationLink("Datenschutz") { PrivacyView() }
                    NavigationLink("Lizenzen") { LicensesView() }
                    LabeledContent("Version", value: Self.version)
                    Text("Couchpotato enthält keine Sender, Filme oder Serien und vermittelt keine. Nutze nur Quellen, für die du selbst berechtigt bist.")
                        .font(.footnote).foregroundStyle(Theme.muted)
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.background.ignoresSafeArea())
            .navigationTitle("Einstellungen")
            .task { await count() }
            .sheet(item: $sheet) { s in
                switch s {
                case .join: JoinSheet()
                case .login: LoginSheet()
                }
            }
            .confirmationDialog("Haushalt verlassen?", isPresented: $confirmLeave, titleVisibility: .visible) {
                Button("Verlassen", role: .destructive) { model.leaveHousehold() }
            } message: {
                Text("Profile, Favoriten und „Weiterschauen“ verschwinden von diesem iPhone. Deine Fernseher behalten alles.")
            }
    }

    private func count() async { requests = await RequestGate.shared.requestsLastHour() }

    static var version: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(v) (\(b))"
    }
}

struct PrivacyView: View {
    var body: some View {
        ScrollView {
            Text(Self.text).frame(maxWidth: .infinity, alignment: .leading).padding(20).foregroundStyle(Theme.text)
        }
        .background(Theme.background.ignoresSafeArea())
        .navigationTitle("Datenschutz")
    }

    static let text = """
    Couchpotato sammelt keine Daten über dich und enthält keine Werbung, kein Tracking und keine Analyse-Werkzeuge.

    IPTV-Zugang: Server, Benutzername und Passwort liegen verschlüsselt im Schlüsselbund dieses iPhones. Couchpotato verbindet sich damit nur mit dem Server, den du eingetragen hast.

    Haushalt: Wenn du dich mit deinen Fernsehern verbindest, werden Profile, Favoriten und der Stand von „Weiterschauen“ zwischen deinen Geräten abgeglichen. Diese Daten werden auf deinen Geräten verschlüsselt (AES-256), bevor sie zum Abgleich-Server (Supabase, EU) gehen. Der Schlüssel entsteht aus deinem Haushalts-Code und verlässt deine Geräte nie – der Server kann die Daten nicht lesen.

    Bilder: Senderlogos und Vorschaubilder lädt das iPhone direkt von den Adressen, die dein Anbieter angibt, und speichert sie zwischen.

    Löschen: „Haushalt verlassen“ und „Zugang entfernen“ in den Einstellungen löschen die Daten von diesem iPhone. Den ganzen Haushalt löscht der Hauptfernseher.
    """
}

struct LicensesView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("VLCKit").font(Theme.heading(20))
                Text("Filme und Serien in Formaten wie MKV oder AVI spielt Couchpotato mit VLCKit von VideoLAN ab. VLCKit steht unter der GNU Lesser General Public License (LGPL) 2.1. Quelltext: https://code.videolan.org/videolan/VLCKit")
                Text("Schrift").font(Theme.heading(20))
                Text("Baloo 2 – SIL Open Font License 1.1.")
            }
            .frame(maxWidth: .infinity, alignment: .leading).padding(20).foregroundStyle(Theme.text)
        }
        .background(Theme.background.ignoresSafeArea())
        .navigationTitle("Lizenzen")
    }
}
