# Couchpotato fürs iPhone

SwiftUI-App (iOS 17+), Projekt per XcodeGen aus `project.yml`. Gebaut wird auf GitHubs Mac-Runnern (`.github/workflows/ios.yml`), der Bericht steht auf dem Branch `ios-report`.

## Etappe 1 (fertig)
- Haushalt beitreten mit dem Code vom Fernseher. Gleiche Ende-zu-Ende-Verschlüsselung und gleiches Datenformat wie `CloudSync.kt`; das prüfen die Tests mit einem Testvektor.
- Live-TV über die Xtream-API mit derselben Anfrage-Bremse: 2,5 s Abstand, 40 Anfragen pro Stunde, 15 Min. Pause nach einer Sperre. Die Senderliste wird höchstens einmal täglich geholt (2 Anfragen).
- Favoriten (umsortierbar), zuletzt gesehene Sender und „Weiterschauen“, abgeglichen mit den Fernsehern.
- Player: AVPlayer mit Bild-in-Bild, AirPlay und einer Senderleiste zum Umschalten.
- Demo-Sender (Apples freie Teststreams) für die App-Prüfung.

## Einmalig einrichten (Luca)
1. **App-ID anlegen:** developer.apple.com → Identifiers → `de.koeven.couchpotato`, ohne besondere Capabilities.
2. **App anlegen:** App Store Connect → Apps → „+“, Name „Couchpotato“, Bundle-ID wie oben, SKU z. B. `couchpotato-ios`.
3. **Secrets im Couchpotato-Repo** (die Werte wie bei Meeterz): `APPLE_TEAM_ID`, `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8`. `SUPABASE_URL` und `SUPABASE_PUBLISHABLE_KEY` sind schon da.
4. **TestFlight:** Actions → iOS → Run workflow → Haken bei „Zu TestFlight hochladen“.

## Für die App-Prüfung
- Positionierung wie bei IsiPro: Die App enthält keine Inhalte; Nutzer tragen eigene, berechtigte Quellen ein.
- Demo-Zugang für Apple: „Erst mal ausprobieren“ auf dem Startbildschirm. Ein Konto ist nicht nötig.
- Datenschutz: Es werden keine Daten erhoben, die dem Nutzer zugeordnet sind (Haushaltsdaten nur Ende-zu-Ende-verschlüsselt). Den Text gibt es in der App unter Einstellungen → Datenschutz. Für den Store-Eintrag braucht es noch eine öffentliche URL.
- Verschlüsselung: nur Standardverfahren, deshalb `ITSAppUsesNonExemptEncryption = false`.
- `NSAllowsArbitraryLoads`: IPTV-Server sprechen meist http, und die Adresse gibt der Nutzer ein.

## Nächste Etappen
2. Filme und Serien mit einem Player für MKV/AVI. Meine Liste, Suche.
3. „Auf Fernseher abspielen“ und das iPhone als Fernbedienung (WLAN).
4. Kinderprofil mit PIN und Bildschirmzeit.
