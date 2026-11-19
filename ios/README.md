# Couchpotato fürs iPhone

SwiftUI-App (iOS 17+), Projekt per XcodeGen aus `project.yml`. Gebaut wird auf GitHubs Mac-Runnern (`.github/workflows/ios.yml`), der Bericht steht auf dem Branch `ios-report`.

## Was die App kann
- **Haushalt:** Beitreten mit dem Code vom Fernseher. Profile, Favoriten, „Weiterschauen“ und Meine Liste werden Ende-zu-Ende-verschlüsselt abgeglichen, im selben Format wie `CloudSync.kt` (die Tests prüfen das mit Testvektoren).
- **Katalog:** Im TSV-Format der Fernseher (`catalog_v3.tsv`). Er kommt vom Fernseher im WLAN, also ohne Anfrage beim Anbieter. Ohne Fernseher stellt das iPhone selbst 6 Anfragen, höchstens einmal am Tag. Für jede Anfrage gilt dieselbe Bremse wie am Fernseher (2,5 s Abstand, 40 pro Stunde, 15 Min. Pause nach einer Sperre).
- **Live-TV:** Kategorien, Favoriten, zuletzt gesehene Sender, Senderleiste im Player. HLS spielt der Apple-Player, sonst übernimmt VLC mit MPEG-TS.
- **Mediathek:** Filme und Serien mit Kategorien, „Neu“, Staffeln und Folgen, Meine Liste und einer Suche über alles. MP4 und HLS laufen im Apple-Player (Bild-in-Bild, AirPlay), MKV, AVI und TS in VLC mit Ton- und Untertitelspuren. Folgen laufen automatisch weiter.
- **Fernseher:** „Auf Fernseher abspielen“ und Fernbedienung (`TvLink.kt` am Fernseher, Port 8790, Bonjour `_couchpotato._tcp`, jede Anfrage mit dem Haushaltsschlüssel signiert).
- **Kinder:** Kinderprofile sehen nur freigegebene Kategorien. Dazu Eltern-PIN, Fernsehzeit und Schlafenszeit aus dem Profil.
- **Demo:** Freie Teststreams von Apple für die App-Prüfung.

## Einmalig einrichten (Luca)
1. **App-ID anlegen:** developer.apple.com → Identifiers → `de.koeven.couchpotato`, ohne besondere Capabilities.
2. **App anlegen:** App Store Connect → Apps → „+“, Name „Couchpotato“, Bundle-ID wie oben, SKU z. B. `couchpotato-ios`.
3. **Secrets im Couchpotato-Repo** (die Werte wie bei Meeterz): `APPLE_TEAM_ID`, `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8`. `SUPABASE_URL` und `SUPABASE_PUBLISHABLE_KEY` sind schon da.
4. **TestFlight:** Actions → iOS → Run workflow → Haken bei „Zu TestFlight hochladen“.

## Store-Eintrag
- Texte: `docs/store-listing.json` (de/en).
- Datenschutz: https://axdnmlqdvewmtmwfovgr.supabase.co/functions/v1/couchpotato-legal
- Support: dieselbe Adresse mit `?p=support`. Supabase liefert die Seiten nur als Text aus. Eine eigene Domain wäre schöner.
- Actions → „iOS – App Store Connect“:
  - `listing` füllt die Produktseite.
  - `setup-apply` setzt Inhaltsrechte, Altersfreigabe und Copyright. Den Preis lässt es aus.
  - `status` zeigt die Builds.
  - Den Bericht schreibt der Workflow auf den Branch `ios-store-report`.

## Hinweis für die App-Prüfung (in App Store Connect unter „Hinweise“ einfügen)
> Couchpotato is a player for IPTV sources the user is entitled to (Xtream API). The app contains no channels, movies or series and does not sell or link to any. To try it without an account, tap "Erst mal ausprobieren" on the first screen: two free Apple HLS test streams appear under Live-TV. "Mit meinen Fernsehern verbinden" links the app to the developer's own Couchpotato TV app (Fire TV) via an end-to-end encrypted household code; the local network permission is used only to find those TVs. Video formats Apple's player can't open (MKV, AVI) are played with VLCKit (LGPL).

## Für die App-Prüfung
- Positionierung wie bei IsiPro: Die App enthält keine Inhalte; Nutzer tragen eigene, berechtigte Quellen ein. Altersfreigabe: Apple fragt nach Inhalten der App. Dort steht überall „keine“, nur Elternkontrollen „ja“. Fordert Apple wegen frei wählbarer Quellen eine höhere Freigabe, wie bei IsiPro (16+), lässt sich das in App Store Connect anpassen.
- Demo-Zugang für Apple: „Erst mal ausprobieren“ auf dem Startbildschirm. Ein Konto ist nicht nötig.
- Datenschutz: Es werden keine Daten erhoben, die dem Nutzer zugeordnet sind (Haushaltsdaten nur Ende-zu-Ende-verschlüsselt). Den Text gibt es in der App unter Einstellungen → Datenschutz. Die öffentliche Fassung steht unter der Adresse oben.
- Verschlüsselung: nur Standardverfahren, deshalb `ITSAppUsesNonExemptEncryption = false`.
- `NSAllowsArbitraryLoads`: IPTV-Server sprechen meist http, und die Adresse gibt der Nutzer ein.
