import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var sheet: WelcomeView.Sheet?
    @State private var requests = 0
    @State private var confirmLeave = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if let login = model.login {
                        LabeledContent("Server", value: login.base)
                        LabeledContent("Benutzer", value: login.user)
                        Button("Zugang ändern") { sheet = .login }
                        Button("Senderliste jetzt aktualisieren") { Task { await model.refreshCatalog(background: false); await count() } }
                            .disabled(model.loadingCatalog)
                        Button("Zugang entfernen", role: .destructive) { Task { _ = await model.setLogin(nil) } }
                    } else {
                        Button("IPTV-Zugang eingeben") { sheet = .login }
                    }
                } header: {
                    Text("IPTV")
                } footer: {
                    VStack(alignment: .leading, spacing: 4) {
                        if let at = model.catalog?.fetchedAt { Text("Senderliste vom \(at.formatted(date: .abbreviated, time: .shortened))") }
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
                            Button("Haushalt auf diesem iPhone verlassen", role: .destructive) { confirmLeave = true }
                        } else {
                            Button("Mit meinen Fernsehern verbinden") { sheet = .join }
                        }
                    } header: {
                        Text("Haushalt")
                    } footer: {
                        Text("Profile, Favoriten und „Weiterschauen“ – Ende-zu-Ende-verschlüsselt. Den IPTV-Zugang teilt nur der Hauptfernseher, und nur wenn du es dort einschaltest.")
                    }
                }

                if model.profiles.count > 1 {
                    Section("Profil") {
                        Picker("Wer schaut?", selection: $model.profileId) {
                            ForEach(model.profiles) { p in Text(p.kid ? "\(p.name) (Kinder)" : p.name).tag(p.id) }
                        }
                    }
                }

                Section {
                    Toggle("Demo-Sender zeigen", isOn: $model.demo)
                } footer: {
                    Text("Freie Teststreams, ganz ohne Zugang. Sie erscheinen nur, solange kein IPTV-Zugang eingetragen ist.")
                }

                Section("Über") {
                    NavigationLink("Datenschutz") { PrivacyView() }
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
                Button("Verlassen", role: .destructive) { model.household.leave() }
            } message: {
                Text("Profile, Favoriten und „Weiterschauen“ verschwinden von diesem iPhone. Deine Fernseher behalten alles.")
            }
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
