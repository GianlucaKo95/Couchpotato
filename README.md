# Couchpotato – eigener Launcher für den Fire TV Stick

Werbefreier Startbildschirm mit einer gemeinsamen **„Weiterschauen“-Reihe**, eigenem
**IPTV-Player (Xtream Codes)** und deinen Apps. Kotlin + Jetpack Compose for TV + Media3.

## Was drin ist

| Reihe | Quelle |
|---|---|
| **Weiterschauen** | IPTV-Filme/-Folgen aus dem eigenen Player (Position wird alle 10 s gespeichert, nach einer Folge steht automatisch die nächste drin) + experimentell die Android-„Watch Next“-Einträge anderer Apps (Netflix, Prime, RTL+ …), nach Zeit gemischt |
| **Apps** | alle installierten Apps, sortiert nach zuletzt geöffnet, plus Kachel „Fire TV Einstellungen“ |
| **Neue Filme / Neue Serien** | die 40 neuesten Einträge deines Anbieters (Xtream-API), auf dem Stick gecacht |

- Serien-Detail: Staffeln, Folgen, „Fortsetzen“/„Weiter mit …“; Folgen laufen am Stück durch.
- Lange **OK** auf einer App, einem Sender oder einem Titel in „Weiterschauen“ öffnet ein Menü:
  **Verschieben** (dann mit ◀ ▶ schieben, OK = fertig), **Ausblenden** bzw. **Entfernen**.
  Die Reihenfolge der Reihen selbst und „Apps: eigene Reihenfolge / zuletzt genutzt“ stellst du
  unter *Einstellungen* ein.
- **Home**-Taste springt immer zurück an den Anfang, **Zurück** schließt den Launcher nie.
- **Live-TV – Favoriten**: Sender über die Kachel „Sender verwalten“ auswählen (OK = Favorit an/aus,
  lange OK = sofort ansehen). Auf der Startseite zeigt jede Kachel, was gerade läuft. Im Couchpotato-Player schaltest du mit Hoch/Runter bzw. den Kanaltasten durch die
  Favoriten. Unter *Einstellungen* kannst du stattdessen „Sender öffnen mit: IsiPro“ wählen (IsiPro
  bekommt dann den Stream-Link – ob es den annimmt, musst du testen).

- **Mediathek** (oben rechts): Tabs **Live-TV · Filme · Serien**, links die Kategorien, rechts der
  Inhalt. **Kategorien bearbeiten** blendet Kategorien per OK aus/ein, mit **Alle ausblenden** /
  **Alle einblenden** für den aktuellen Tab. Ausgeblendete Kategorien fehlen auch bei Neue Filme/Serien,
  im Live-TV und in der Suche.
- **Suchen** (oben rechts): durchsucht Filme, Serien und Sender deines IPTV-Anbieters schon beim
  Tippen (Groß/Klein und Umlaute egal). Dafür legt Couchpotato beim ersten Öffnen einen Suchindex an
  und aktualisiert ihn alle 12 Stunden. Darunter: den Begriff an die **Fire-TV-Gesamtsuche** oder an
  einzelne Apps (Netflix, Prime …) übergeben – Apps, die das können, stehen vorne.

> „Weiterschauen“ von Netflix & Co. ist ein Versuch: Auf dem Fire TV melden diese Apps ihren
> Fortschritt meist nur an Amazons Oberfläche. Unter *Einstellungen* siehst du, ob und wie viele
> Einträge gefunden wurden (auf Fire OS 6 gibt es die Schnittstelle gar nicht).

## 1. APK bauen (GitHub Actions)

1. Neues **privates** Repo anlegen (z. B. `couchpotato`) und diesen Ordner pushen.
2. Signatur-Secrets setzen (aus dem separaten `couchpotato-signing.zip`, **nicht** ins Repo legen):
   ```bash
   gh secret set CP_KEYSTORE_B64 < couchpotato.jks.b64
   gh secret set CP_KEYSTORE_PASSWORD < couchpotato.pw
   ```
   Gleicher Schlüssel = jede neue Version installiert sich über die alte, Fortschritt bleibt erhalten.
3. Push auf `main` → Workflow *Build APK* → die APK liegt als Release `build-N` und als Artifact bereit.

Lokal geht auch: Projekt in Android Studio öffnen, `./gradlew assembleRelease`.

## 2. Auf den Stick bringen

Auf dem Fire TV: *Einstellungen → Mein Fire TV → Info* → 7× auf den Gerätenamen → *Entwickleroptionen*
→ **ADB-Debugging an**. IP-Adresse steht unter *Info → Netzwerk*.

```bash
adb connect 192.168.x.y
adb install -r couchpotato.apk
```

## 3. IPTV-Zugang eintragen

Entweder auf dem Stick unter *Einstellungen* – wahlweise als **Zugangsdaten** (Server, Benutzer,
Passwort) oder als **kompletter Link** (M3U-Link aus IsiPro/Anbieter-Mail) –, oder bequem vom Rechner:

```bash
adb shell am start -n de.koeven.couchpotato/.ui.MainActivity \
  --es m3u "'http://anbieter.tv:8080/get.php?username=USER&password=PASS&type=m3u_plus'"
```

## 4. Als Startbildschirm setzen (Launcher Manager)

Dem Guide von AFTVNews folgen und als Launcher **Couchpotato** auswählen:
<https://www.aftvnews.com/?p=41438>

Danach ein paar Mal den Fernseher aus- und einschalten (der Stick bootet dann neu) und prüfen,
dass Couchpotato jedes Mal erscheint.

**Rückgängig:** Launcher Manager deinstallieren bzw. die ADB-Änderungen zurücknehmen; notfalls
*Auf Werkseinstellungen zurücksetzen*.

## Projektaufbau

```
app/src/main/java/de/koeven/couchpotato/
  data/   XtreamClient (streamt die Kataloge, hält nur die neuesten N), ProgressStore,
          CatalogCache, SettingsStore, AppsRepository, WatchNextSource
  player/ PlayerActivity (Media3), Playback (Playlists, Intents)
  ui/     MainActivity, HomeScreen, SeriesScreen, SettingsScreen, HomeViewModel, Komponenten
```

Mindestversion Android 7.1 (Fire OS 6). Ideen für später: Kategorien & Suche, Live-TV-Reihe mit
EPG, Home-Assistant-Kacheln.
