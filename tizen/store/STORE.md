# Couchpotato im Samsung-Store (Samsung Apps TV)

Alles, was für die Einreichung im **Samsung Apps TV Seller Office** (seller.samsungapps.com/tv) gebraucht wird.

## Was du selbst tun musst

1. **Seller-Office-Konto:** Mit deinem Samsung-Konto auf seller.samsungapps.com/tv anmelden, als Kontotyp **Company or Business**
   wählen, Firma **KoeVen GbR** mit **USt-IdNr.** eintragen. In der EU verlangt der Digital Services Act eine Registriernummer,
   sonst wird die App dort nicht angeboten.
2. **Partner-Status für Deutschland:** Ein neues Konto ist „Public Seller“ und darf Apps **nur in den USA** veröffentlichen.
   Für Deutschland, Österreich und die Schweiz braucht es den **Partner-Status**. Den gibt es über eine Vereinbarung mit einem
   Samsung Content Manager: Im Seller Office unter *Membership → Partnership* die Anfrage stellen, mit einer kurzen Beschreibung
   (Text unten). Das ist der langsamste und unsicherste Schritt. Ob Samsung zustimmt, entscheidet Samsung.
3. **Samsung-Zertifikat (einmalig):** Tizen Studio installieren, dazu *TV Extensions* und *Samsung Certificate Extension*.
   Unter *Tools → Certificate Manager* ein **Samsung**-Profil namens `couchpotato` anlegen
   (Autor-Zertifikat neu, Distributor-Berechtigung **Public**).
4. **Paket signieren:** `store/sign.sh` lädt die neueste Samsung-Version von GitHub und signiert sie mit diesem Profil.
   Heraus kommt `couchpotato-signed.wgt`. Mit `store/sign.sh <IP des Fernsehers>` wird sie zusätzlich auf deinem Fernseher installiert.
5. **App anlegen und hochladen:** Im Seller Office *Applications → Create App* wählen, die Angaben unten eintragen,
   das signierte Paket hochladen, Pre-Test laufen lassen und dann *Request New Release* drücken.

## Dateien in diesem Ordner

| Datei | Wofür |
|---|---|
| `icon-512x423.png` | App-Symbol 512 × 423 (24-Bit-PNG) |
| `icon-1920x1080-logo.png` | Symbol 1920 × 1080, Logo-Ebene (transparent, 32-Bit-PNG) |
| `icon-1920x1080-background.png` | Symbol 1920 × 1080, Hintergrund |
| `screenshot-1-start.jpg` … `screenshot-5-profile.jpg` | Screenshots 1920 × 1080 JPG |
| `couchpotato-ui-description.pptx` | „Application UI description“ (Bedienung, Testhinweise, Tastenbelegung) |
| `sign.sh` | Signieren und optional installieren |

Die Bilder entstehen neu mit `python3 store/make_assets.py`. Die Screenshots entstehen mit dem Test-Anbieter
(`node test/mock-server.mjs dist/app 8765` und dann `node store/screenshots.mjs <playwright>`), mit erfundenen Titeln und Sendern.

## Angaben für das Seller Office

- **App-Name / Titel:** Couchpotato
- **Kategorie:** Video
- **Standardsprache:** Deutsch
- **Länder:** Deutschland, Österreich, Schweiz (setzt den Partner-Status voraus)
- **Modellgruppen:** 2018 bis heute (Tizen 4.0 und neuer)
- **Preis:** kostenlos, ohne In-App-Käufe und ohne Werbung
- **Datenschutz-URL:** https://axdnmlqdvewmtmwfovgr.supabase.co/functions/v1/couchpotato-legal?p=privacy-tv
- **Support-/Hilfe-URL:** https://axdnmlqdvewmtmwfovgr.supabase.co/functions/v1/couchpotato-legal?p=support-tv
- **Support-E-Mail:** deine Adresse (erscheint im Store)
- **Altersfreigabe:** Die App selbst enthält keine Inhalte. Die Freigabe richtet sich nach Samsungs Fragebogen.
  Bei der Frage nach Inhalten Dritter „vom Nutzer bereitgestellte Quellen“ angeben.
- **European Accessibility Act (EAA):** Die Erklärung ist für EU-Länder Pflicht. Nach meinem Verständnis sind Kleinstunternehmen
  (unter 10 Beschäftigte, höchstens 2 Mio. € Umsatz) bei Dienstleistungen von den EAA-Pflichten ausgenommen. Das bitte selbst prüfen
  und so angeben.

### Kurzbeschreibung (DE)
Dein IPTV-Player: Live-TV mit Programminfos, Filme, Serien und Weiterschauen – mit deinem eigenen Zugang.

### Beschreibung (DE)
Couchpotato ist ein Player für deinen eigenen IPTV-Zugang (Xtream-Codes-API). Die App enthält keine Sender, Filme oder Serien –
du verbindest sie mit dem Angebot, für das du ein Abo hast.

• Live-TV mit laufender Vorschau, Programminfos, Favoriten und Senderliste
• Filme und Serien mit Staffeln, Fortschritt und „Weiterschauen“
• „Für dich“: Empfehlungen aus deinem eigenen Katalog, ganz ohne Tracking
• Profile für die ganze Familie, Kinderprofile mit Fernsehzeit, Schlafenszeit und Eltern-PIN
• Haushalt: Profile, Favoriten und „Weiterschauen“ Ende-zu-Ende-verschlüsselt mit deinen anderen Couchpotato-Geräten abgleichen
• Schonend für deinen Zugang: Couchpotato bremst Anfragen an deinen Anbieter, damit er dein Konto nicht sperrt

Keine Werbung, kein Tracking, kein Konto bei uns. Ohne eigenen Zugang kannst du die App mit freien Testvideos ausprobieren.

### Short description (EN)
Your IPTV player: live TV with programme guide, films, series and continue watching – with your own subscription.

### Description (EN)
Couchpotato is a player for your own IPTV subscription (Xtream Codes API). The app contains no channels, films or series –
you connect it to the service you subscribe to.

• Live TV with a running preview, programme guide, favourites and channel list
• Films and series with seasons, progress and “continue watching”
• “For you”: recommendations from your own catalogue, without any tracking
• Profiles for the whole family; kids profiles with screen time, bedtime and a parents’ PIN
• Household: sync profiles, favourites and progress end-to-end encrypted with your other Couchpotato devices
• Gentle on your account: Couchpotato throttles requests to your provider so it doesn’t lock you out

No ads, no tracking, no account with us. Without a subscription you can try the app with free test videos.

### Hinweise für die Tester (Seller Office → Test information)
The app needs no account for testing. On the start screen choose “Ohne Zugang ausprobieren” (try without access):
the app then offers free Apple HLS test streams – Live TV → Demo (2 channels) and Filme → Demo (2 films).
All functions (profiles, favourites with the RED key, continue watching, search, settings) work with them.
See the attached UI description for keys and screens. RETURN on the start screen opens an exit pop-up.

### Text für die Partnerschafts-Anfrage
KoeVen GbR (Duisburg, Germany) would like to publish “Couchpotato”, a free media player app for users’ own IPTV subscriptions
(Xtream Codes API) in Germany, Austria and Switzerland. The app contains no content, has no ads, no tracking and no in-app
purchases; versions for Fire TV and iPhone already exist. It uses only public Tizen and Samsung Product APIs
(AVPlay, TV input device, product info, network). We kindly ask for partner status to release it in these countries.

## Was die App für die Prüfung schon erfüllt

- **Bedienung:** komplett mit der Fernbedienung. RETURN geht eine Ebene zurück, auf dem Startbildschirm kommt ein Beenden-Dialog.
- **Multitasking:** Wird die App während der Wiedergabe ausgeblendet (Smart-Hub-Taste, Ausschalten), verhält sie sich wie RETURN.
  Die Wiedergabe stoppt, die Position wird gespeichert.
- **Netzwerk weg:** Unten erscheint ein Hinweis, der verschwindet, sobald die Verbindung zurück ist.
- **Bildschirmschoner:** Während ein Video läuft, ist er aus.
- **Medientasten:** Play/Pause, Stop, Vor- und Rücklauf, P+/P−.
- **Untertitel:** AVPlay liefert sie zum passenden Zeitpunkt, die App blendet sie ein. Auf einem echten Fernseher noch nicht geprüft.
- **Testbar ohne Konto:** über die Demo mit Apples freien Teststreams.
- **Berechtigungen:** nur öffentliche (`internet`, `tv.inputdevice`, `productinfo`, `network.public`).
