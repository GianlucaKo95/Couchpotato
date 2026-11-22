# Couchpotato für Samsung-Fernseher (Tizen)

Die Samsung-Version von Couchpotato: IPTV mit Live-TV und Programminfos, Filme, Serien, Weiterschauen,
„Für dich“, Meine Liste, Profile mit Kinderprofilen – und Ende-zu-Ende-verschlüsselter Abgleich mit
den Fire TVs und dem iPhone über den Haushalts-Code.

Anders als auf Fire TV ist Couchpotato hier eine normale App (Samsung erlaubt keinen eigenen
Startbildschirm), und „Weiterschauen“ aus anderen Apps gibt es nicht.

## Installieren (Entwicklermodus)

1. **Entwicklermodus am Fernseher:** Apps öffnen → auf der Fernbedienung `1 2 3 4 5` drücken →
   „Developer mode“ auf **On**, die IP-Adresse des Computers eintragen, Fernseher neu starten.
2. **Widget holen:** die neueste `couchpotato-samsung-….wgt` aus den
   [Releases](../../releases) (Tag `tizen-…`).
3. **Aufspielen** – am einfachsten mit [Apps2Samsung](https://github.com/Jellyfin2Samsung/Samsung-Jellyfin-Installer)
   („custom .wgt“ wählen; das Tool kümmert sich um das Samsung-Zertifikat). Alternativ mit
   Tizen Studio: Gerät im Device Manager verbinden, dann `tizen install -n couchpotato.wgt`.
4. Couchpotato starten → **Haushalt beitreten** (Code vom Fire TV unter Einstellungen → Haushalt)
   oder den IPTV-Zugang direkt eintragen.

Updates kommen ab Version 1.0.5 über die App selbst (wie beim Fire TV): Beim Start und alle
6 Stunden schaut sie auf dem Branch `tizen-update` nach, lädt neuen Code (SHA-256 geprüft) und fragt
„Jetzt neu starten?“ – nie während der Wiedergabe. Startet eine geladene Version nicht sauber, nimmt
der nächste Start automatisch wieder die installierte und überspringt diese Version. Manuell:
Einstellungen → Info → *Nach Updates suchen* bzw. *Zur installierten Version zurück*.
Eine neue `.wgt` muss nur noch aufgespielt werden, wenn sich das Paket selbst ändert
(Berechtigungen, Icon, Schriften/Bilder). Store-Builds: `CP_SELF_UPDATE=0` schaltet das ab –
dort aktualisiert Samsung über den Seller Office.

## Fernbedienung

| Taste | Wirkung |
|---|---|
| Pfeile / OK | bewegen / auswählen |
| Zurück | eine Ebene zurück, im Player beenden |
| Play/Pause, ⏩ ⏪ | Wiedergabe steuern (±30 s / −10 s) |
| ◀ ▶ im Film | spulen (gedrückt halten = schneller) |
| ▲ ▼ / P+ P− im Live-TV | Sender wechseln; OK = Senderliste |
| Rot | Sender als Favorit merken |

## Anfragen an den Anbieter

Wie auf allen Couchpotato-Geräten werden Anfragen gebremst: eine alle 2,5 s, höchstens 40 pro Stunde
(20 im Hintergrund), nach einer Sperr-Antwort 15 Minuten Pause; der Katalog höchstens einmal am Tag
(6 Anfragen), Programminfos nur für Sender, auf denen man verweilt, Bilder vom Anbieter höchstens 60 pro Stunde.

## Entwickeln

```
npm ci
npm test                      # Krypto/Abgleich gegen die Testdaten der Fire TVs
npm run build                 # → dist/couchpotato.wgt
node test/mock-server.mjs     # App + Test-Anbieter auf http://localhost:8765 (im Browser bedienbar)
```

Im Browser: Pfeiltasten, Enter, Esc = Zurück, Leertaste = Play/Pause, Bild↑/↓ = Senderwechsel, R = Rot.

## Samsung-Store

Alles für die Einreichung (Bilder, Screenshots, Texte, UI-Beschreibung, Signier-Skript) liegt in [`store/`](store/STORE.md).
