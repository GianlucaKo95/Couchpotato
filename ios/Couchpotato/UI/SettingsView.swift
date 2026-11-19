import SwiftUI

struct PrivacyView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(Self.text)
                Link("Datenschutzerklärung im Web", destination: URL(string: Self.url)!).foregroundStyle(Theme.accent)
            }
            .frame(maxWidth: .infinity, alignment: .leading).padding(20).foregroundStyle(Theme.text)
        }
        .background(Theme.background.ignoresSafeArea())
        .navigationTitle("Datenschutz")
    }

    static let url = "https://axdnmlqdvewmtmwfovgr.supabase.co/functions/v1/couchpotato-legal"

    static let text = """
    Couchpotato sammelt keine Daten über dich und enthält keine Werbung, kein Tracking und keine Analyse-Werkzeuge.

    IPTV-Zugang: Server, Benutzername und Passwort liegen verschlüsselt im Schlüsselbund dieses iPhones. Couchpotato verbindet sich damit nur mit dem Server, den du eingetragen hast.

    Haushalt: Wenn du dich mit deinen Fernsehern verbindest, werden Profile, Favoriten, Meine Liste und der Stand von „Weiterschauen“ zwischen deinen Geräten abgeglichen. Diese Daten werden auf deinen Geräten verschlüsselt (AES-256), bevor sie zum Abgleich-Server (Supabase, EU) gehen. Der Schlüssel entsteht aus deinem Haushalts-Code und verlässt deine Geräte nie – der Server kann die Daten nicht lesen.

    WLAN: Couchpotato sucht im lokalen Netzwerk deine Couchpotato-Fernseher, lädt von ihnen den Katalog und schickt Fernbedienungsbefehle. Das bleibt in deinem Heimnetz und ist mit dem Schlüssel deines Haushalts signiert.

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
