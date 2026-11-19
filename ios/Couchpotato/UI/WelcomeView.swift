import SwiftUI

/// First start. Couchpotato brings no content of its own: you connect your TVs' household or your own IPTV access.
struct WelcomeView: View {
    @EnvironmentObject private var model: AppModel
    @State private var sheet: Sheet?

    enum Sheet: String, Identifiable { case join, login; var id: String { rawValue } }

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(spacing: 18) {
                Spacer()
                Image("Logo").resizable().frame(width: 132, height: 132).clipShape(RoundedRectangle(cornerRadius: 30))
                Text("Couchpotato").font(Theme.title(40)).foregroundStyle(Theme.text)
                Text("Deine Sender, deine Favoriten und „Weiterschauen“ – auf dem iPhone und im Takt mit deinen Fernsehern.")
                    .multilineTextAlignment(.center).foregroundStyle(Theme.muted).padding(.horizontal, 12)
                Spacer()
                if HouseholdSync.configured {
                    Button { sheet = .join } label: { Label("Mit meinen Fernsehern verbinden", systemImage: "tv.and.mediabox") }
                        .buttonStyle(AccentButton())
                }
                Button { sheet = .login } label: { Label("IPTV-Zugang eingeben", systemImage: "key") }
                    .buttonStyle(HouseholdSync.configured ? AnyButtonStyle(QuietButton()) : AnyButtonStyle(AccentButton()))
                Button("Erst mal ausprobieren") { model.demo = true }
                    .font(Theme.heading(16)).foregroundStyle(Theme.accent).padding(.top, 4)
                Text("Couchpotato enthält keine Sender, Filme oder Serien. Du nutzt ausschließlich Quellen, für die du selbst berechtigt bist.")
                    .font(.footnote).multilineTextAlignment(.center).foregroundStyle(Theme.muted.opacity(0.8)).padding(.top, 6)
            }
            .padding(24)
        }
        .sheet(item: $sheet) { s in
            switch s {
            case .join: JoinSheet()
            case .login: LoginSheet()
            }
        }
    }
}

struct AnyButtonStyle: ButtonStyle {
    private let make: (Configuration) -> AnyView
    init<S: ButtonStyle>(_ style: S) { make = { AnyView(style.makeBody(configuration: $0)) } }
    func makeBody(configuration: Configuration) -> some View { make(configuration) }
}

/// Household code from the TV: Einstellungen → Haushalt.
struct JoinSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var code = ""
    @State private var working = false
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("z. B. 7KQ2M-X9P4T", text: $code)
                        .font(.system(.title2, design: .monospaced))
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .focused($focused)
                } header: {
                    Text("Haushalts-Code")
                } footer: {
                    Text("Am Fernseher unter Einstellungen → Haushalt. Profile, Favoriten und „Weiterschauen“ werden dann Ende-zu-Ende-verschlüsselt abgeglichen – der Server kann nichts davon lesen.")
                }
            }
            .navigationTitle("Mit Fernsehern verbinden")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if working { ProgressView() } else {
                        Button("Verbinden") {
                            working = true
                            Task {
                                if await model.join(code: code) { dismiss() }
                                working = false
                            }
                        }
                        .disabled(HouseholdCode.normalize(code).count != HouseholdCode.length)
                    }
                }
            }
            .onAppear { focused = true }
        }
    }
}

struct LoginSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var server = ""
    @State private var user = ""
    @State private var password = ""
    @State private var link = ""
    @State private var working = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Link vom Anbieter einfügen", text: $link, axis: .vertical)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                        .onChange(of: link) { _, l in
                            if let found = XtreamLogin.fromLink(l) {
                                server = found.server; user = found.user; password = found.password
                            }
                        }
                } footer: {
                    Text("Ein M3U- oder Xtream-Link mit Benutzername und Passwort füllt alles unten aus.")
                }
                Section("Zugang") {
                    TextField("Server (z. B. http://anbieter.tv:8080)", text: $server)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    TextField("Benutzername", text: $user).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("Passwort", text: $password)
                }
                Section {
                    Text("Couchpotato fragt deinen Anbieter nur selten und mit Abstand an (höchstens 40 Anfragen pro Stunde) – so sperrt er dein Konto nicht. Die Zugangsdaten bleiben verschlüsselt auf diesem iPhone.")
                        .font(.footnote).foregroundStyle(Theme.muted)
                }
            }
            .navigationTitle("IPTV-Zugang")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if working { ProgressView() } else {
                        Button("Prüfen") {
                            working = true
                            Task {
                                let login = XtreamLogin(server: server.trimmed, user: user.trimmed, password: password.trimmed)
                                if await model.setLogin(login) != nil { dismiss() }
                                working = false
                            }
                        }
                        .disabled(!XtreamLogin(server: server, user: user, password: password).isValid)
                    }
                }
            }
            .onAppear {
                if let l = model.login { server = l.server; user = l.user }   // the password is never shown again
            }
        }
    }
}
