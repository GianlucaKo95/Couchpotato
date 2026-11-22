// Public pages for the store listings: privacy policy (?p=privacy, default) and support (?p=support) for the
// iPhone app, ?p=privacy-tv / ?p=support-tv for the Samsung TV app (Samsung Apps TV Seller Office).
const privacy = `<h1>Couchpotato – Datenschutz</h1>
<p>Stand: November 2026. Anbieter der App: Gianluca Köhni, Duisburg.</p>
<p>Couchpotato sammelt keine Daten über dich. Es gibt keine Werbung, kein Tracking, keine Analyse-Werkzeuge und kein Benutzerkonto.</p>
<h2>Inhalte</h2>
<p>Couchpotato enthält keine Sender, Filme oder Serien und vermittelt keine. Du verbindest die App ausschließlich mit Quellen, für die du selbst berechtigt bist.</p>
<h2>IPTV-Zugang</h2>
<p>Server, Benutzername und Passwort liegen verschlüsselt im Schlüsselbund deines iPhones. Die App verbindet sich damit nur mit dem Server, den du eingetragen hast.</p>
<h2>Haushalt</h2>
<p>Verbindest du die App mit deinen Couchpotato-Fernsehern, werden Profile, Favoriten, Merkliste und der Stand von „Weiterschauen“ zwischen deinen Geräten abgeglichen. Diese Daten werden auf deinen Geräten mit AES-256 verschlüsselt, bevor sie zum Abgleich-Server (Supabase, Rechenzentrum in Irland/EU) gehen. Der Schlüssel entsteht aus deinem Haushalts-Code und verlässt deine Geräte nie – der Server kann die Daten nicht lesen. Gespeichert wird nur der verschlüsselte Datenblock mit einem Zeitstempel.</p>
<h2>Lokales Netzwerk</h2>
<p>Im WLAN sucht die App deine Couchpotato-Fernseher (Bonjour), lädt von ihnen den Katalog und sendet Fernbedienungsbefehle. Diese Verbindungen bleiben in deinem Heimnetz und sind mit einem Schlüssel deines Haushalts signiert.</p>
<h2>Bilder</h2>
<p>Senderlogos und Vorschaubilder lädt die App direkt von den Adressen, die dein Anbieter angibt, und speichert sie auf dem iPhone zwischen.</p>
<h2>Löschen</h2>
<p>„Haushalt verlassen“ und „Zugang entfernen“ in den Einstellungen löschen die Daten vom iPhone; das Löschen der App entfernt alles. Den ganzen Haushalt samt Server-Daten löscht der Hauptfernseher.</p>
<h2>Kontakt</h2>
<p>Fragen zum Datenschutz: über die Support-Seite dieser App.</p>`;

const support = `<h1>Couchpotato – Hilfe</h1>
<h2>Was ist Couchpotato?</h2>
<p>Eine App für deine eigenen IPTV-Quellen (Xtream-API) mit Live-TV, Filmen, Serien, „Weiterschauen“ und Merkliste – im Takt mit Couchpotato auf Fire TV und Android-TV-Beamern. Die App enthält keine Inhalte.</p>
<h2>Mit dem Fernseher verbinden</h2>
<p>Am Fernseher unter Einstellungen → Haushalt den Code ablesen und in der App eingeben. Sind iPhone und Fernseher im selben WLAN, kommt der Katalog vom Fernseher, und du kannst dort abspielen und fernsteuern.</p>
<h2>Anfrage-Bremse</h2>
<p>Couchpotato fragt deinen Anbieter höchstens 40-mal pro Stunde und mit Abstand an, damit er dein Konto nicht sperrt.</p>
<h2>Kontakt</h2>
<p>Fehler und Fragen bitte per E-Mail an den Entwickler (Adresse im App-Store-Eintrag).</p>`;

const privacyTv = `<h1>Couchpotato für Samsung Smart TV – Datenschutz</h1>
<p>Stand: November 2026. Anbieter der App: Gianluca Köhni (KoeVen GbR), Duisburg.</p>
<p>Couchpotato sammelt keine Daten über dich. Es gibt keine Werbung, kein Tracking, keine Analyse-Werkzeuge und kein Benutzerkonto bei uns.</p>
<h2>Inhalte</h2>
<p>Couchpotato enthält keine Sender, Filme oder Serien und vermittelt keine. Du verbindest die App ausschließlich mit Quellen, für die du selbst berechtigt bist. Ohne eigenen Zugang zeigt die App nur frei verfügbare Testvideos von Apple.</p>
<h2>IPTV-Zugang</h2>
<p>Server, Benutzername und Passwort speichert die App nur auf deinem Fernseher. Sie verbindet sich damit ausschließlich mit dem Server, den du eingetragen hast, und fragt ihn gebremst an (höchstens 40 Anfragen pro Stunde).</p>
<h2>Haushalt</h2>
<p>Trittst du mit dem Haushalts-Code deinem Couchpotato-Haushalt bei, werden Profile, Favoriten, Merkliste und der Stand von „Weiterschauen“ mit deinen anderen Geräten abgeglichen. Diese Daten werden auf dem Fernseher mit AES-256 verschlüsselt, bevor sie zum Abgleich-Server (Supabase, Rechenzentrum in Irland/EU) gehen. Der Schlüssel entsteht aus deinem Haushalts-Code und verlässt deine Geräte nie – der Server kann die Daten nicht lesen. Gespeichert wird nur der verschlüsselte Datenblock mit einem Zeitstempel. Ohne Haushalt verlässt nichts den Fernseher.</p>
<h2>Geräteinformationen</h2>
<p>Die App liest den Modellnamen des Fernsehers, um ihn im Haushalt zu benennen (z. B. „Samsung QE65…“). Er bleibt in deinem verschlüsselten Haushalt.</p>
<h2>Bilder</h2>
<p>Senderlogos und Vorschaubilder lädt die App direkt von den Adressen, die dein Anbieter angibt.</p>
<h2>Löschen</h2>
<p>„Haushalt verlassen“ und „Entfernen“ beim IPTV-Zugang in den Einstellungen löschen die Daten vom Fernseher; das Deinstallieren der App entfernt alles.</p>
<h2>Kontakt</h2>
<p>Fragen zum Datenschutz: an die E-Mail-Adresse im Store-Eintrag der App.</p>`;

const supportTv = `<h1>Couchpotato für Samsung Smart TV – Hilfe</h1>
<h2>Was ist Couchpotato?</h2>
<p>Ein Player für deine eigenen IPTV-Quellen (Xtream-API) mit Live-TV und Programminfos, Filmen, Serien, „Weiterschauen“, „Für dich“, Merkliste und Profilen mit Kinderschutz. Die App enthält keine Inhalte.</p>
<h2>Erste Schritte</h2>
<p>Einstellungen → IPTV-Zugang: Server, Benutzername und Passwort deines Anbieters eintragen. Oder: Einstellungen → Haushalt: den Code von einem Couchpotato-Gerät eingeben, dann übernimmt der Fernseher Profile, Favoriten und „Weiterschauen“.</p>
<h2>Fernbedienung</h2>
<p>Pfeile und OK zum Bedienen, Zurück für eine Ebene zurück. Im Film: links/rechts spulen, Play/Pause. Im Live-TV: hoch/runter oder P+/P− zum Umschalten, OK für die Senderliste, rote Taste für Favoriten.</p>
<h2>Kontakt</h2>
<p>Fehler und Fragen per E-Mail an die Adresse im Store-Eintrag der App.</p>`;

// Supabase serves function answers on its own domain as text/plain – so the pages are plain, readable text
// (with a UTF-8 byte order mark, so browsers get the umlauts right).
function plain(html: string): string {
  return html
    .replace(/<h1>(.*?)<\/h1>/g, (_, t) => `${t}\n${"=".repeat(t.length)}\n`)
    .replace(/<h2>(.*?)<\/h2>/g, (_, t) => `\n${t}\n${"-".repeat(t.length)}`)
    .replace(/<p>(.*?)<\/p>/gs, (_, t) => `${t}\n`)
    .replace(/<[^>]+>/g, "");
}

Deno.serve((req) => {
  const p = new URL(req.url).searchParams.get("p");
  const pages: Record<string, string> = { support, "privacy-tv": privacyTv, "support-tv": supportTv };
  const body = plain(pages[p ?? ""] ?? privacy);
  return new Response("\uFEFF" + body, { headers: { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "public, max-age=3600" } });
});
