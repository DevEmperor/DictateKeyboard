/**
 * The architecture as one interactive graph.
 *
 * Written as data rather than drawn as a picture, so it cannot drift from the system the way a
 * diagram in a wiki does: every node names the file it lives in, every edge names the protocol and
 * the credential it travels under, and adding a route here is a line, not a redraw.
 *
 * **Nothing here knows a pixel.** A node says which column and which row it belongs in; where that
 * lands, how wide the zone around it grows and which way a line takes to reach it are computed in
 * `graph-layout.ts` and checked by `tools/check-graph.mjs`. The previous version placed every box
 * by hand and gave every edge its own sides and offset, which held until it did not: the
 * twenty-fourth node pushed three older labels onto boxes nobody was looking at.
 *
 * Three kinds of text, deliberately not one:
 *   - `sub`    — what it is, in four words, on the box itself
 *   - `detail` — a paragraph in the panel under the diagram, for the reader who tapped it
 *   - `long`   — the full account behind the button: why it is built this way, what would go wrong
 *                otherwise, and what it explicitly does not do
 */

export interface GraphZone {
  id: string;
  label: string;
  sub: string;
  tone: 'client' | 'cloudflare' | 'google' | 'ext';
  /** The full account, shown by the button. HTML, paragraphs. */
  long: string;
}

/** A column of the grid. Order is left to right; the zone decides how wide the gutter before it is. */
export interface GraphColumn {
  zone: string;
  w?: number;
}

export interface GraphNode {
  id: string;
  zone: string;
  label: string;
  sub: string;
  col: number;
  row: number;
  /** Credentials this node holds, and where they are kept. */
  holds?: string[];
  /** What protects it. */
  guards?: string[];
  detail: string;
  long: string;
  source?: string;
}

export interface GraphEdge {
  from: string; to: string;
  label: string;
  /** The credential carried, if any — drives the "token flow" filter. */
  token?: string;
  /** A protection applied on this hop — drives the "safeguards" filter. */
  guard?: string;
  kind: 'data' | 'auth' | 'store' | 'notify';
  long: string;
}

export const COLUMNS: GraphColumn[] = [
  { zone: 'client' },
  { zone: 'cf' },
  { zone: 'cf' },
  { zone: 'google' },
];

export const ZONES: GraphZone[] = [
  {
    id: 'client', label: 'Gerät des Nutzers', sub: 'Android, Wear OS, Browser', tone: 'client',
    long:
      '<p>Alles in dieser Spalte gehört der nutzenden Person, nicht dem Dienst. Was hier liegt — das ' +
      'Zugangstoken, der Wiederherstellungscode, die Aufnahme vor dem Absenden —, liegt in der ' +
      'app-privaten Ablage von Android und ist für andere Apps nicht lesbar.</p>' +
      '<p>Wichtig für das Verständnis des ganzen Bildes: <strong>Ohne Dictate Cloud existiert diese ' +
      'Spalte allein.</strong> Wer einen eigenen API-Schlüssel hinterlegt oder die Erkennung auf dem ' +
      'Gerät nutzt, spricht nie mit irgendetwas rechts davon. Der bezahlte Weg ist der Sonderfall, ' +
      'nicht der Normalfall — auch wenn er hier die meisten Linien erzeugt.</p>',
  },
  {
    id: 'cf', label: 'Cloudflare', sub: 'Worker, Speicher, Zero Trust', tone: 'cloudflare',
    long:
      '<p>Der gesamte eigene Dienst: ein Worker als einziger Einstiegspunkt, D1 als Hauptbuch, zwei ' +
      'Arten von Durable Objects für alles, was exakt gezählt werden muss, und Cloudflare Access vor ' +
      'der Verwaltungsoberfläche.</p>' +
      '<p>Warum ausgerechnet hier: Ein Worker startet ohne Server, rechnet nach Anfragen ab und läuft ' +
      'in dem Rechenzentrum, das der anfragenden Person am nächsten liegt. Für einen Dienst, dessen ' +
      'Last aus einzelnen kurzen Anfragen besteht und dessen Betreiber nachts schlafen will, ist das ' +
      'die passende Bauform. Der Preis dafür steht in der Übertragungs-Dokumentation: Cloudflare, Inc. ' +
      'sitzt in den USA, Datenbank und Kontostände sind deshalb ausdrücklich auf die EU festgelegt.</p>' +
      '<p><strong>Für die Modelle in dieser Spalte gilt das ausdrücklich nicht.</strong> Die ' +
      'Festlegung auf Westeuropa greift für den Speicher, nicht für die Rechenzeit: Workers AI läuft, ' +
      'wo gerade Kapazität ist, und lässt sich ohne Enterprise-Zusatz nicht festlegen. Wer aus „alles ' +
      'bei Cloudflare" liest „alles in der EU", liest an dieser einen Stelle falsch.</p>',
  },
  {
    id: 'google', label: 'Google', sub: 'Play, Cloud, Identität', tone: 'google',
    long:
      '<p>Google verkauft, kassiert und führt die Steuer ab — und ist damit <strong>eigener ' +
      'Verantwortlicher</strong>, kein Auftragsverarbeiter. Der Dienst fragt hier nur nach, ob ein Kauf ' +
      'echt war, und erfährt, wenn Geld zurückgegeben wurde.</p>' +
      '<p>Alles in dieser Zone hängt an einem einzigen Dienstkonto, das in der Google Cloud angelegt ' +
      'und in der Play Console auf diese eine App berechtigt wurde. Das ist die kleinste mögliche ' +
      'Berechtigung: Kaufbelege lesen. Verkaufen, Preise ändern oder eine App veröffentlichen kann ' +
      'dieses Konto nicht.</p>',
  },
  {
    id: 'ext', label: 'Öffentliche Quellen', sub: 'ohne Anmeldung, ohne Vertrag', tone: 'ext',
    long:
      '<p>Was ohne Zugangsdaten abgefragt wird und deshalb weder einen Vertrag noch ein Geheimnis ' +
      'braucht. Zurzeit steht hier ein einziger Dienst.</p>' +
      '<p>Die Zone existiert trotzdem als eigene: Eine Abhängigkeit, die von außen kommt, gehört ' +
      'sichtbar hingeschrieben, auch wenn sie klein ist. Fällt sie aus, fehlt eine Umrechnung — kein ' +
      'Guthaben, keine Anfrage, kein Konto. Genau das soll das Bild auf einen Blick zeigen.</p>',
  },
];

export const NODES: GraphNode[] = [
  {
    id: 'app', zone: 'client', label: 'Dictate (Android)', sub: 'Tastatur, schwebender Knopf',
    col: 0, row: 0,
    holds: ['Wallet-Token', 'Wiederherstellungscode'],
    guards: ['Token nur in App-privaten Prefs', 'höchstens 3 Geräte je Konto'],
    detail: 'Das Wallet-Token liegt in <code>ProviderAccount.apiKey</code> — genau dort, wo sonst ein API-Schlüssel steht. Deshalb erreicht der unveränderte OpenAI-Client den Dienst, ohne dass eine einzige Zeile im Netzwerkpfad angepasst werden musste. Wird der Zugang auf einem vierten Gerät wiederhergestellt, antwortet der Server mit der Geräteliste statt mit einem Token: Abmelden ist eine Auswahl, keine automatische Verdrängung.',
    long:
      '<p>Dictate Cloud ist in der App <em>kein eigener Netzwerkpfad</em>, sondern ein Anbieter wie ' +
      'jeder andere. Das Zugangstoken liegt in <code>ProviderAccount.apiKey</code>, die Basis-URL zeigt ' +
      'auf <code>api.dictatekeyboard.com</code>, und der Server spricht dieselbe Sprache wie OpenAI. ' +
      'Deshalb musste für den bezahlten Weg keine Zeile im Sende- und Empfangspfad geändert werden — ' +
      'und deshalb kann dort auch nichts kaputtgehen, was für alle anderen Anbieter funktioniert.</p>' +
      '<p><strong>Die Gerätegrenze ist eine Auswahl, keine Verdrängung.</strong> Wer den Code auf einem ' +
      'vierten Gerät eingibt, bekommt kein Token, sondern die Liste der drei angemeldeten Geräte und ' +
      'die Frage, welches gehen soll. Automatisches Abmelden wäre bequemer zu bauen und wäre falsch: ' +
      'Das Gerät, das verdrängt wird, erfährt nichts davon und steht beim nächsten Diktat ohne ' +
      'Guthaben da.</p>' +
      '<p>Was hier bewusst nicht passiert: Der Anbieter wird nie automatisch gewechselt. Ist das ' +
      'Guthabenkonto weg, sagt die App das und bietet Kaufen, Wiederherstellen oder einen anderen ' +
      'Anbieter an — sie entscheidet nicht selbst, wohin die nächste Aufnahme geht.</p>',
    source: 'app/.../dictate/cloud/DictateCloud.kt',
  },
  {
    id: 'wear', zone: 'client', label: 'Dictate (Wear OS)', sub: 'Uhr, standalone oder getethert',
    col: 0, row: 1,
    holds: ['Wallet-Token (synchronisiert)'],
    detail: 'Bekommt Basis-URL und Token über den Data-Layer-Sync vom Telefon und rechnet dasselbe Guthaben ab. Nimmt 16-kHz-Mono-WAV auf — das Format, dessen Dauer der Server exakt misst.',
    long:
      '<p>Die Uhr hat kein eigenes Guthaben und soll keines haben. Sie bekommt Basis-URL und Token über ' +
      'den Data-Layer-Sync vom Telefon und bucht von demselben Konto ab. Ein zweites Konto für die Uhr ' +
      'wäre die naheliegende Lösung und die schlechtere: Man kauft einmal Minuten, nicht einmal je ' +
      'Gerät.</p>' +
      '<p>Sie zählt gegen die Gerätegrenze, weil sie ein eigenes Token hält — drei Geräte heißt hier ' +
      'wirklich drei, Uhr eingeschlossen.</p>' +
      '<p>Praktisch günstig: Die Uhr nimmt 16-kHz-Mono-WAV auf. Das ist das Format, dessen Dauer der ' +
      'Server auf die Sekunde aus dem Dateikopf lesen kann, ohne irgendetwas zu schätzen.</p>',
    source: 'app/.../dictate/wear/PhoneWearSettingsResolver.kt',
  },
  {
    id: 'billing', zone: 'client', label: 'Play Billing 8', sub: 'Kaufabwicklung in der App',
    col: 0, row: 2,
    holds: ['purchaseToken'],
    guards: ['Verbrauch erst nach Gutschrift'],
    detail: 'Liefert nach dem Kauf einen <code>purchaseToken</code>. Existiert auf dem Gerät bereits ein Guthabenkonto, reist dessen ID als <code>obfuscatedAccountId</code> mit und kommt vom Server als Prüfwert wieder — <strong>keine Kennung von Google</strong>, sondern der eigene Wert zurück. Beim Erstkauf und nach einer Löschung reist nichts mit, und genau dort endet die Wiedererkennung. Verbraucht wird der Kauf erst, wenn der Server das Guthaben gutgeschrieben hat — vorher ist er der einzige haltbare Beleg, dass Geld geflossen ist.',
    long:
      '<p>Die Reihenfolge ist das Wesentliche und sie darf nie umgedreht werden: <strong>zahlen → der ' +
      'Server löst ein → erst dann verbrauchen</strong>. Ein Kauf, den Play noch als vorhanden führt, ' +
      'ist der einzige dauerhafte Beleg dafür, dass Geld geflossen ist. Wer ihn verbraucht, bevor das ' +
      'Guthaben gutgeschrieben ist, hat im Fehlerfall eine Zahlung ohne Gegenwert und nichts mehr in ' +
      'der Hand, um es nachzuholen. Deshalb ist das Einlösen serverseitig wiederholbar: derselbe ' +
      'Kauf-Token führt beliebig oft zum selben Ergebnis.</p>' +
      '<p><strong>Der mitgeschickte Wert ist unserer.</strong> Liegt schon ein Guthabenkonto auf dem ' +
      'Gerät, hängt die App dessen zufällige ID über <code>setObfuscatedAccountId</code> an die ' +
      'Bestellung; Google reicht sie unverändert zurück, gespeichert wird davon nur ein Prüfwert. Das ' +
      'Feld heißt <code>obfuscatedExternalAccountId</code> und klingt nach einer Kennung von Google — ' +
      'es ist keine. Google liefert überhaupt keine Kennung des Käufers.</p>' +
      '<p>Daraus folgt die Grenze, die im Bild sonst niemand sähe: Beim <em>ersten</em> Kauf und bei ' +
      'jedem Kauf <em>nach einer Löschung</em> hängt nichts mit an. Die Wiedererkennung von ' +
      'Erstattungen greift also nur über Aufladungen hinweg, nicht über eine Löschung hinweg.</p>',
    source: 'app/.../dictate/cloud/DictateCloudBilling.kt',
  },
  {
    id: 'store', zone: 'client', label: 'Play Store', sub: 'Installation, Kaufdialog',
    col: 0, row: 3,
    detail: 'Liefert die App aus und zeigt den Kaufdialog. Billing funktioniert nur, wenn die App von hier installiert wurde — eine seitlich installierte APK trägt eine andere Signatur.',
    long:
      '<p>Der Store ist im Bild, weil er eine Bedingung stellt, die sonst niemand erklärt: ' +
      '<strong>Billing funktioniert nur bei einer von hier installierten App.</strong> Play prüft die ' +
      'Signatur des installierten Pakets gegen die des veröffentlichten. Eine selbst gebaute oder ' +
      'seitlich installierte APK trägt eine andere und bekommt den Kaufdialog gar nicht erst zu ' +
      'sehen.</p>' +
      '<p>Das ist keine Absicht dieses Diensts und auch keine Einschränkung, die jemandem den Zugang ' +
      'nähme: Wer die App selbst baut, nutzt sie mit eigenem Schlüssel — dem Weg, der ohnehin der ' +
      'Standard ist. Es erklärt nur, warum ein Fehlerbericht "der Kaufknopf tut nichts" fast immer ' +
      'mit der Frage beginnt, woher die App stammt.</p>',
  },
  {
    id: 'webuser', zone: 'client', label: 'Browser (Nutzer)', sub: 'Löschung ohne App',
    col: 0, row: 4,
    holds: ['Wiederherstellungscode'],
    detail: 'Ein Konto lässt sich löschen, ohne die App zu besitzen — nötig, wenn das Telefon weg ist oder die App längst deinstalliert wurde. Ausweis ist allein der Wiederherstellungscode, und die Seite sagt vorher, dass vorhandenes Guthaben dabei ersatzlos verfällt.',
    long:
      '<p>Ein Löschrecht, das eine bestimmte App auf einem bestimmten Telefon voraussetzt, ist im ' +
      'entscheidenden Moment keines. Telefon verloren, App längst deinstalliert, Gerät gewechselt — ' +
      'genau dann will jemand sein Konto loswerden und hat die App nicht mehr. Deshalb gibt es die ' +
      'Löschung auch im Browser, unter derselben Adresse wie der Dienst selbst.</p>' +
      '<p>Ausweis ist allein der Wiederherstellungscode. Etwas anderes gibt es nicht: Der Dienst kennt ' +
      'weder Namen noch E-Mail-Adresse noch Google-Konto, es existiert also nichts, womit sich ' +
      '"beweisen" ließe, wer jemand ist. Wer den Code nicht mehr hat, kann sein Konto nicht löschen — ' +
      'was unbequem ist und die ehrliche Folge davon, gar nichts über die Person zu speichern.</p>',
  },
  {
    id: 'mailbox', zone: 'client', label: 'Postfach (du)', sub: 'Warnungen und Tagesbericht',
    col: 0, row: 5,
    detail: 'Kritische Befunde kommen sofort, alles Übrige einmal täglich — auch dann, wenn nichts vorgefallen ist. Das Ausbleiben des Berichts ist selbst ein Signal: Ein Wachhund, von dem man nur hört, wenn er bellt, ist von einem toten Wachhund nicht zu unterscheiden.',
    long:
      '<p>Zwei Sorten Post, mit Absicht getrennt. <strong>Kritisch</strong> geht sofort raus: eine ' +
      'Erstattung, ein zu schnell verbrauchtes Paket, eine Preisabweichung. <strong>Alles Übrige</strong> ' +
      'sammelt der Tagesbericht — Dinge, die man wissen will, aber nicht um drei Uhr nachts.</p>' +
      '<p>Der Bericht kommt auch, wenn nichts vorgefallen ist, und das ist der eigentliche Trick daran: ' +
      'Ein Wachhund, von dem man nur hört, wenn er bellt, ist von einem toten Wachhund nicht zu ' +
      'unterscheiden. Bleibt die tägliche Post aus, ist das selbst der Befund — sei es, dass der Cron ' +
      'nicht läuft, dass der Versand klemmt oder dass die Zieladresse nicht mehr stimmt.</p>' +
      '<p>Deshalb steht das Postfach im Bild und nicht nur der Versand: Der Weg endet erst, wenn ihn ' +
      'jemand liest.</p>',
  },
  {
    id: 'browser', zone: 'client', label: 'Browser (du)', sub: 'Betriebs-Dashboard',
    col: 0, row: 6,
    holds: ['CF_Authorization-JWT'],
    detail: 'Der einzige Zugang zum Dashboard. Die Anmeldung läuft über Cloudflare Access; der Worker prüft die Signatur selbst.',
    long:
      '<p>Es gibt keinen zweiten Weg in die Verwaltung — kein Passwort im Code, kein Notfallzugang, ' +
      'keine geheime Adresse. Die Anmeldung läuft über Cloudflare Access, das nach erfolgreicher ' +
      'Prüfung ein signiertes Token als Cookie setzt.</p>' +
      '<p>Entscheidend ist, was danach passiert: Der Worker <strong>glaubt diesem Cookie nicht</strong>, ' +
      'sondern prüft seine Signatur gegen die öffentlichen Schlüssel des Anbieters, samt der Frage, ' +
      'für welche Anwendung es ausgestellt wurde. Ein Header allein genügt nicht. Das ist der ' +
      'Unterschied zwischen "hinter einem Login" und "tatsächlich geschützt": Wer den Worker direkt ' +
      'anspricht und einen Header erfindet, kommt nicht weiter.</p>',
  },
  {
    id: 'backup', zone: 'client', label: 'Sicherung (dein Rechner)', sub: 'wrangler d1 export',
    col: 0, row: 7,
    guards: ['von Hand angestoßen', 'nur im verschlüsselten Ordner'],
    detail: 'Kein laufender Dienst, sondern ein Handgriff: <code>npm run db:backup</code> zieht das komplette Hauptbuch als datierte SQL-Datei in den verschlüsselten Ordner, in dem auch die Konfiguration liegt. Der einzige Weg, auf dem die ganze Datenbank Cloudflare verlässt.',
    long:
      '<p>Cloudflare hält 30 Tage Zeitreise für D1 vor. Das deckt ein versehentliches DELETE ab — es ' +
      'deckt <strong>nicht</strong> den Verlust des Kontos ab, und lesen lässt es sich nirgends außer ' +
      'bei Cloudflare. Für ein Hauptbuch, an dem Kaufbelege und Kontostände hängen, ist das zu wenig; ' +
      'es gab lange gar keine Sicherung und auch nichts Aufgeschriebenes darüber, wie man eine macht.</p>' +
      '<p>Deshalb ein Befehl statt einer Anleitung, mit dem Datum im Dateinamen und mit dem Zielordner ' +
      'fest eingetragen. Die Ausfuhr trägt Konto-IDs und die Prüfwerte der Geräte; sie hat außerhalb ' +
      'des verschlüsselten Ordners nichts zu suchen, und ein Befehl, der den Pfad schon kennt, ist die ' +
      'zuverlässigste Art, das durchzuhalten.</p>' +
      '<p>Im Bild steht der Kasten, weil ein Netzbild, das jede Abfrage bei Google zeigt und den einen ' +
      'Weg auslässt, auf dem die vollständige Datenbank das Rechenzentrum verlässt, an der heikelsten ' +
      'Stelle unvollständig wäre.</p>',
    source: 'package.json · db:backup',
  },

  {
    id: 'workersai', zone: 'cf', label: 'Workers AI', sub: 'Whisper · Gemma',
    col: 1, row: 2,
    guards: ['kein Schlüssel — Bindung statt Netzweg', 'Denkmodus ausdrücklich aus', 'Modell ohne Deployment wechselbar'],
    detail: 'Diktat und Umformulierung, beides hier. Rechnet über <em>dieses</em> Konto ab, es gibt also kein Geheimnis, das leaken könnte. Meldet je Antwort die verbrauchten Neuronen zurück; genau die stehen im Hauptbuch. Welches Modell, sagt <code>TRANSCRIBE_MODEL</code> bzw. <code>CHAT_MODEL</code> — eine Konfigurationszeile, kein Deployment.',
    long:
      '<p>Seit dem Umzug der einzige Ort, an dem Inhalte verarbeitet werden. Die Modelle laufen bei ' +
      'Cloudflare selbst, erreichbar über eine Bindung statt über HTTPS: ' +
      '<strong>kein API-Schlüssel, kein Netzweg nach draußen.</strong> Was der Worker schickt, verlässt ' +
      'das Haus nicht — es war nie draußen.</p>' +
      '<p><strong>Und genau hier endet die gute Nachricht.</strong> „Nicht nach draußen" heißt nicht ' +
      '„in der EU". Datenbank und Kontostände sind ausdrücklich auf Westeuropa festgelegt; die ' +
      '<em>Inferenz</em> ist es nicht und kann es ohne Enterprise-Vertrag auch nicht werden — Custom ' +
      'Regions ist ein kostenpflichtiger Zusatz. Ein Diktat wird dort gerechnet, wo Cloudflare gerade ' +
      'Kapazität hat, und der Vertragspartner dafür ist Cloudflare, Inc. in den USA. Getragen wird ' +
      'die Übermittlung deshalb von den Standardvertragsklauseln und davon, dass nichts gespeichert ' +
      'wird — nicht von einer Ortswahl.</p>' +
      '<p><strong>Es wird nicht nachgedacht.</strong> Bei Workers AI ist der Denkmodus <em>standardmäßig ' +
      'an</em>, wird also ausdrücklich abgeschaltet. Gemessen an einem Satz: 20 Ausgabe-Token gegen 777, ' +
      '1,06 s gegen 7,70 s, 1,43 Neuronen gegen 22,10 — bei gleicher Antwort. Denk-Token gehen als ' +
      'Ausgabe vom Guthaben des Käufers ab, weshalb jede Antwort daraufhin geprüft wird und nicht bloß ' +
      'jede Anfrage entsprechend gestellt.</p>' +
      '<p>Die Menge wird <strong>abgelesen, nicht gerechnet</strong>: Jede Antwort bringt ihre Neuronen ' +
      'mit. Eine abgelesene Menge kann nicht dadurch falsch werden, dass sich still eine Preisliste ' +
      'ändert — die Preistabelle im Quelltext ist nur noch die Gegenprobe.</p>',
    source: 'src/routes/transcriptions.ts, src/routes/chat.ts',
  },
  {
    id: 'worker', zone: 'cf', label: 'Worker', sub: 'api.dictatekeyboard.com',
    col: 1, row: 1,
    // Kein Schlüssel für die Modelle: Workers AI läuft über die Bindung und rechnet über dasselbe
    // Konto ab wie dieser Worker.
    holds: ['GOOGLE_SERVICE_ACCOUNT', 'RTDN_SECRET', 'ACCESS_AUD'],
    guards: ['Rate-Limit je Konto (20/min)', 'Tagesbudget', 'Not-Aus', 'Audio ≤ 10 min', 'Chat ≤ 8k/2k Token', 'höchstens 3 Geräte', 'Codeversuche gedrosselt'],
    detail: 'Prüft das Token gegen den SHA-256-Abzug, liest die Audiodauer aus dem Dateikopf — WAV, FLAC, Ogg/Opus, MP4/M4A und MP3 sagen sie selbst —, bucht ab und reicht erst dann weiter. Nur ein unbekannter Container wird nach Größe geschätzt, und dann nur zum Zurücklegen. Abgerechnet wird alles in Sekunden, Diktat wie Umformulierung. Speichert niemals Audio oder Text.',
    long:
      '<p>Alles läuft hier durch, und die Reihenfolge innerhalb einer Anfrage ist Absicht, nicht ' +
      'Geschmack: <strong>wer bist du</strong> (sonst 401) → <strong>wie lang ist die Aufnahme</strong> ' +
      '(aus dem Dateikopf, nicht aus einer Behauptung des Clients) → <strong>darf der Dienst heute ' +
      'noch</strong> (Tagesbudget) → <strong>abbuchen</strong> → <strong>erst jetzt zum Modell</strong> ' +
      '→ bei Fehlschlag zurückbuchen. Würde nach dem Einkauf abgebucht, ginge jeder Abbruch auf Kosten ' +
      'des Betreibers.</p>' +
      '<p><strong>Die Länge wird gelesen, nicht geraten.</strong> WAV, FLAC, Ogg/Opus, MP4/M4A und MP3 ' +
      'schreiben ihre Dauer in den Dateikopf; das kostet ein paar hundert Bytes und keinerlei ' +
      'Dekodierung. Nur ein unbekannter Container wird nach Dateigröße geschätzt — und dann nur, um ' +
      'Guthaben zurückzulegen, nie um endgültig abzurechnen. Vorher wurde nach Größe geschätzt, und ' +
      'eine absichtlich niedrig kodierte Zwei-Stunden-Datei rechnete sich als drei Minuten ab.</p>' +
      '<p>Was der Client bestimmen darf, ist kurz: die Sprache und den Prompt. <strong>Modell, ' +
      'Antwortformat, Antwortlänge und Denkaufwand setzt der Server</strong> und verwirft, was ' +
      'mitgeschickt wurde. Sonst wäre die Kalkulation offen — ein teureres Modell im Aufruf, und der ' +
      'Preis je Sekunde stimmt nicht mehr.</p>' +
      '<p>Und was hier <em>nicht</em> passiert: Es wird nie eine Aufnahme, ein Transkript oder ein ' +
      'Prompt auf einen Datenträger geschrieben. Was nie geschrieben wird, kann weder abfließen noch ' +
      'muss es gelöscht werden.</p>',
    source: 'src/index.ts',
  },
  {
    id: 'deleteweb', zone: 'cf', label: 'Löschseite /delete', sub: 'öffentlich, ohne Anmeldung',
    col: 1, row: 4,
    guards: ['nur der Code als Ausweis', 'Versuche gedrosselt', 'zweistufige Bestätigung'],
    detail: 'Dieselbe Löschung wie in der App, nur ohne sie. Sie zeigt vorher, was verschwindet und was bleibt: die Kaufbelege, weil sie zehn Jahre aufzubewahren sind, und der Prüfwert des Vorgängerkontos für 24 Monate.',
    long:
      '<p>Die einzige Seite des Diensts, die ohne jede Anmeldung erreichbar ist. Sie muss es sein: Ein ' +
      'Löschrecht, das eine App voraussetzt, hilft nicht, wenn die App weg ist.</p>' +
      '<p>Sie sagt vorher, was passiert, und zwar vollständig — auch das Unangenehme. Dass vorhandenes ' +
      'Guthaben ersatzlos verfällt. Dass die Kaufbelege bleiben, weil sie steuerlich zehn Jahre ' +
      'aufzubewahren sind. Dass ein Prüfwert 24 Monate überdauert, wofür er da ist und was er nicht ' +
      'ist. Eine Löschseite, die erst nach dem Klick mit Bedingungen kommt, ist keine.</p>' +
      '<p>Technisch ist sie kein eigener Dienst, sondern eine Route desselben Workers — dieselbe ' +
      'Drossel gegen das Durchprobieren, derselbe Abgleich des Codes gegen seinen SHA-256-Abzug, ' +
      'dieselbe Löschreihenfolge wie in der App.</p>',
    source: 'src/routes/delete-page.ts',
  },
  {
    id: 'access', zone: 'cf', label: 'Cloudflare Access', sub: 'Zero Trust · nur /admin',
    col: 1, row: 6,
    holds: ['Signaturschlüssel (JWKS)'],
    guards: ['Default-Deny', 'Richtlinie auf E-Mail', 'Pfad nur /admin'],
    detail: 'Steht ausschließlich vor <code>/admin</code>. Läge es vor der ganzen Domain, bekäme die App statt einer Transkription eine Anmeldeseite — der Dienst wäre für alle tot.',
    long:
      '<p>Der Pfad in der Richtlinie ist der ganze Punkt. Access steht <strong>ausschließlich vor ' +
      '<code>/admin</code></strong>. Wäre die Richtlinie auf die ganze Domain gesetzt — die ' +
      'naheliegende, scheinbar sicherere Einstellung —, bekäme jede App beim nächsten Diktat eine ' +
      'Anmeldeseite statt einer Transkription. Der Dienst wäre für alle sofort tot, und der Fehler ' +
      'sähe wie ein Ausfall aus.</p>' +
      '<p>Ist Access gar nicht eingerichtet, antwortet die Oberfläche, als gäbe es sie nicht: 404, ' +
      'nicht 403. Ein Deployment ohne Zugangsschutz verrät damit nicht einmal, dass es eine Verwaltung ' +
      'gibt.</p>',
    source: 'src/admin/auth.ts',
  },
  {
    id: 'admin', zone: 'cf', label: 'Dashboard /admin', sub: 'diese Seite',
    col: 1, row: 7,
    guards: ['Signaturprüfung im Worker', 'Begründungspflicht', 'Admin-Protokoll'],
    detail: 'Jede Aktion verlangt eine Begründung und landet mit deiner Adresse im <code>admin_log</code>. Ohne diese Spur gehen die Zahlen nicht mehr auf, sobald einmal von Hand korrigiert wurde.',
    long:
      '<p>Die Seite, auf der dieses Diagramm steht. Sie zeigt Verkehr, Konten, Käufe, Finanzen, ' +
      'Warnungen und den Betrieb — und sie kann eingreifen: Guthaben gutschreiben, ein Konto sperren, ' +
      'ein Gerät abmelden, ein Konto zusammenführen, das Tagesbudget verstellen, den Dienst anhalten.</p>' +
      '<p><strong>Jeder Eingriff verlangt eine Begründung</strong> und wird mit Adresse, Konto und ' +
      'Zeitpunkt in <code>admin_log</code> geschrieben. Dieses Protokoll wird nie gelöscht. Der Grund ' +
      'ist buchhalterisch, nicht misstrauisch: Sobald einmal von Hand korrigiert wurde, gehen ' +
      'verkaufte und verbrauchte Sekunden nicht mehr auf, und ohne die Spur ist später nicht mehr ' +
      'feststellbar, ob die Differenz eine Korrektur oder ein Fehler war.</p>' +
      '<p>Einstellbar ist hier auch, was früher ein Deployment brauchte: Tagesbudget, Gerätegrenze und ' +
      'sämtliche Warnschwellen. Was von der ausgelieferten Fassung abweicht, ist als <em>geändert</em> ' +
      'markiert — sonst weiß nach einem halben Jahr niemand mehr, welche Zahl bewusst gesetzt war.</p>',
    source: 'src/admin/index.ts',
  },

  {
    id: 'd1', zone: 'cf', label: 'D1 · dictate-cloud', sub: 'Hauptbuch (Westeuropa)',
    col: 2, row: 0,
    guards: ['Nur Metadaten', '90 Tage Aufbewahrung', 'Kauf-Token als Primärschlüssel', 'Fremdschlüssel erzwungen'],
    detail: 'Konten, Token-Abzüge, Käufe, Verbrauch, Warnungen, Admin-Protokoll, Tagessummen und die vom Dashboard änderbaren Einstellungen. Der Kauf-Token ist Primärschlüssel in <code>purchases</code> — doppelte Einlösung ist damit strukturell unmöglich. Ein gelöschtes Konto behält seine Zeile als leeren Grabstein, weil die Kaufbelege zehn Jahre lang darauf verweisen müssen.',
    long:
      '<p>Das Hauptbuch: was gekauft und was verbraucht wurde, dazu Warnungen, Admin-Protokoll, ' +
      'Tagessummen und die Einstellungen. Was hier <strong>nicht</strong> liegt, ist ebenso wichtig — ' +
      'kein Audio, kein Transkript, kein Prompt, kein Name, keine Adresse. Das Nutzungsprotokoll ' +
      'besteht ausschließlich aus Zahlen: Zeitpunkt, Art, Sekunden, Token, Kosten, Statuscode, Dauer.</p>' +
      '<p><strong>Zwei Entwurfsentscheidungen tragen mehr als jeder Prüfcode.</strong> Erstens ist der ' +
      'Kauf-Token der Primärschlüssel der Kauftabelle — doppeltes Einlösen ist damit nicht ' +
      '"abgefangen", sondern unmöglich. Zweitens erzwingt D1 Fremdschlüssel, und die Kaufbelege ' +
      'verweisen auf das Konto. Genau daran scheiterte die erste Fassung der Löschung.</p>' +
      '<p>Deshalb bleibt ein gelöschtes Konto als <strong>leerer Grabstein</strong> stehen: Zustand ' +
      '"gelöscht", ein Zeitstempel, Summen — und nichts mehr darauf, was zu einer Person führt. ' +
      'Anmelden lässt sich daran nichts, denn der Prüfwert des Wiederherstellungscodes und sämtliche ' +
      'Zugangsschlüssel sind fort.</p>' +
      '<p>Der Kontostand hier ist eine <em>Kopie</em> für Listenansichten und darf um Sekunden ' +
      'hinterherhinken. Maßgeblich ist das Durable Object — nur dort ist Prüfen und Abbuchen ein ' +
      'einziger Schritt.</p>',
    source: 'schema.sql',
  },
  {
    id: 'walletdo', zone: 'cf', label: 'Wallet · Durable Object', sub: 'ein Objekt je Konto',
    col: 2, row: 1,
    guards: ['Prüfen und Abbuchen atomar', 'Rate-Limiter im Objekt'],
    detail: 'Der maßgebliche Saldo. Weil je Konto genau ein Objekt existiert, laufen gleichzeitige Anfragen serialisiert — gemessen: 15 gleichzeitig ergeben exakt 15 Abbuchungen. Die Spalten in D1 sind nur eine Kopie für Listenansichten.',
    long:
      '<p>Von diesem Objekt gibt es je Konto weltweit genau eines, und seine Aufrufe laufen ' +
      'nacheinander. Damit ist <strong>"prüfen, ob genug da ist, und abbuchen" ein einziger ' +
      'Schritt</strong>. Mit einer gewöhnlichen Datenbankzeile hätten zwei gleichzeitige Diktate ' +
      'denselben Stand gelesen und beide abgebucht — der klassische Weg, ein Guthaben doppelt ' +
      'auszugeben.</p>' +
      '<p>Nachgemessen gegen die Produktion: 15 gleichzeitige Abbuchungen ergeben exakt 15, keine geht ' +
      'verloren; 30 gleichzeitige ergeben 20 Abbuchungen und 10 Ablehnungen mit 429, und die ' +
      'Ablehnungen kosten nichts. Das Rate-Limit sitzt im selben Objekt, aus demselben Grund.</p>' +
      '<p>Es liegt in der Rechtszone <code>eu</code>. Das ist eine Eigenschaft des Objekts, keine ' +
      'Einstellung des Workers: Ein Kontostand wandert nicht dorthin, wo gerade jemand diktiert.</p>',
    source: 'src/wallet.ts',
  },
  {
    id: 'guarddo', zone: 'cf', label: 'GlobalGuard · DO', sub: 'ein Objekt für alles',
    col: 2, row: 2,
    guards: ['Tagesbudget', 'Not-Aus', 'Codeversuche je Adresse (10/min)', 'Fehlversuche insgesamt (60/min)'],
    detail: 'Bewusst ein einziges Objekt für den ganzen Dienst: Ein Ausgabenlimit ist nur dann eines, wenn es exakt zählt. Bei Erreichen antwortet jede kostenpflichtige Anfrage mit 503. Hier läuft auch die Bremse gegen das Durchprobieren von Wiederherstellungscodes — je Adresse und, weil sich Adressen mieten lassen, zusätzlich über alle Fehlversuche zusammen.',
    long:
      '<p>Ein einziges Objekt für den ganzen Dienst — der bewusste Gegenentwurf zum Wallet-Objekt. Ein ' +
      'Ausgabenlimit, das an mehreren Stellen gleichzeitig gezählt wird, ist keines: Zehn ' +
      'Rechenzentren mit je einem Zähler geben ein Zehnfaches des Limits frei. Der Preis ist ein ' +
      'Engpass, und er ist bezahlbar, weil hier nur eine Zahl hoch- und heruntergezählt wird.</p>' +
      '<p>Ist das Tagesbudget erreicht, antwortet <strong>jede kostenpflichtige Anfrage mit 503</strong> ' +
      'und die App meldet "vorübergehend nicht verfügbar". Das ist bewusst grob. Das Schlimmste, was ' +
      'verhindert werden muss, ist eine unbegrenzte Rechnung; ein verärgerter Tag ist billiger als ein ' +
      'leergeräumtes Konto.</p>' +
      '<p>Hier läuft auch die Bremse gegen das Durchprobieren von Wiederherstellungscodes. Zwei Stufen, ' +
      'weil eine nicht reicht: zehn Versuche je Minute und Adresse — und weil sich Adressen mieten ' +
      'lassen, zusätzlich 60 <em>Fehlversuche</em> je Minute über alle zusammen. Gezählt werden ' +
      'Fehlschläge, nicht Versuche: Wer seinen eigenen Code einfügt, trifft; eine Serie von Fehlgriffen ' +
      'ist eine Form, die nur das Raten erzeugt.</p>',
    source: 'src/guard.ts, src/throttle.ts',
  },
  {
    id: 'cron', zone: 'cf', label: 'Cron · 03:17 UTC', sub: 'nächtliche Langsamarbeit',
    col: 2, row: 3,
    guards: ['Speicherbegrenzung', 'Netz unter den Push-Meldungen'],
    detail: 'Fünf Arbeiten, bewusst zu einer krummen Minute statt zur vollen Stunde, wo sich jeder Cron der Plattform staut: Einzelzeilen aus <code>usage_log</code> älter als 90 Tage löschen; den Prüfwert des Vorgängerkontos 24 Monate nach einer Löschung kappen; die Tageskurse holen und fehlende Umrechnungen nachtragen; bei Google nachfragen, was Verkäufe eingebracht haben; und 30 Tage gegen Googles Liste stornierter Käufe abgleichen, weil eine Push-Meldung ausbleiben kann. Das Nachfragen und das Umrechnen laufen seit der Umstellung stündlich — hier stehen sie nur noch als Tagesnetz.',
    long:
      '<p>Fünf Arbeiten, die niemand im laufenden Betrieb sehen soll:</p>' +
      '<p><strong>Aufräumen.</strong> Einzelne Protokollzeilen älter als 90 Tage werden stapelweise ' +
      'gelöscht. Tagessummen und Admin-Protokoll bleiben — die Statistik ist vollständig, das ' +
      'Detailprotokoll altert weg.</p>' +
      '<p><strong>Kappen.</strong> Der Prüfwert des Vorgängerkontos, den eine Löschung bewusst ' +
      'stehenlässt, wird 24 Monate später auf NULL gesetzt. Ohne diesen Lauf wäre aus "24 Monate" ' +
      'stillschweigend "für immer" geworden.</p>' +
      '<p><strong>Umrechnen.</strong> Die Tageskurse werden geholt und Käufe ohne Umrechnung ' +
      'nachgetragen. Erst danach zählt ein Verkauf in Franken oder Zloty überhaupt mit.</p>' +
      '<p><strong>Nachfragen.</strong> Was Google beim Kauf noch nicht sagen konnte, wird erneut ' +
      'geholt: den Entwickleranteil rechnet Play erst nach der Abrechnung aus. Das ist inzwischen ' +
      'stündlich (siehe den Kasten darunter) — hier bleibt es als Tagesnetz stehen, damit eine Reihe ' +
      'gescheiterter Stundenläufe einmal am Tag doch noch aufgeholt wird.</p>' +
      '<p><strong>Abgleichen.</strong> 30 Tage gegen Googles eigene Liste stornierter Käufe. Eine ' +
      'Push-Nachricht ist nicht garantiert; ohne dieses Netz wäre eine verpasste Erstattung ein ' +
      'dauerhaft falscher Kontostand, den niemand bemerkt.</p>' +
      '<p>Die krumme Minute ist kein Zufall: Zur vollen Stunde staut sich jeder Cron der Plattform.</p>',
    source: 'src/retention.ts, src/fx.ts, src/orders.ts, src/sweep.ts',
  },
  {
    id: 'orders', zone: 'cf', label: 'Erlöse · stündlich :23', sub: 'fragt Google nach dem Anteil',
    col: 2, row: 4,
    guards: ['Aufgabefrist zwei Wochen ab Kauf', 'höchstens 50 Käufe je Lauf', 'Kursausfall folgenlos'],
    detail: 'Was ein Verkauf wirklich eingebracht hat, weiß Google erst nach der Abrechnung — die Frage beim Kauf kommt fast immer zu früh, und ohne eine zweite steht ein bezahlter Kauf für immer mit „nichts verdient" in den Büchern. Dieser Lauf stellt sie: <code>orders.get</code> für jeden Kauf ohne Erlöszahl, danach die Umrechnung dessen, was zurückkam. Zur Minute 23, also außerhalb der Viertelstunden des Wachhunds und außerhalb der vollen Stunde, zu der sich jeder Cron der Plattform staut.',
    long:
      '<p>Google beantwortet zwei Fragen auf zwei Uhren. <strong>Was die Kundschaft gezahlt hat</strong>, ' +
      'steht im Moment des Kaufs fest. <strong>Was davon beim Entwickler ankommt</strong>, wird erst ' +
      'ausgerechnet, wenn die Zahlung abgerechnet ist — bis dahin trägt die Bestellung schlicht keine ' +
      'Erlöszahl. Der Kaufweg fragt in derselben Sekunde wie der Verkauf und fragt deshalb fast immer ' +
      'zu früh. Das ist in Ordnung, solange jemand noch einmal fragt.</p>' +
      '<p>Niemand tat es. Der erste echte Verkauf stand daraufhin unbefristet mit 0,00 € im Hauptbuch, ' +
      'und jede Auswertung glaubte es.</p>' +
      '<p><strong>Warum stündlich.</strong> Die Abrechnung dauert Stunden, nicht einen Tag. Ein Verkauf ' +
      'vom Vormittag hatte seine Zahl längst bereitliegen, während die Bücher bis 03:17 der Folgenacht ' +
      'weiter 0,00 € zeigten — der Takt gehörte dem Job, nicht der Sache, auf die er wartete.</p>' +
      '<p><strong>Warum die Frist am Kaufdatum hängt.</strong> Früher war es eine Zählung: vierzehn ' +
      'Versuche. Das hieß nur so lange „zwei Wochen", wie einmal pro Nacht gefragt wurde — stündlich ' +
      'wäre das ganze Kontingent vor Ablauf des ersten Tages verbraucht gewesen, und eine Abrechnung, ' +
      'die ehrlich drei Tage braucht, wäre nach vierzehn Stunden aufgegeben worden. Gezählt werden die ' +
      'Versuche weiter, sie sind das Protokoll; die Uhr sind sie nicht mehr. Nebenwirkung: Eine Phase, ' +
      'in der Google nicht erreichbar ist, frisst nicht mehr die Frist auf, in der es antworten soll.</p>' +
      '<p><strong>Was nach zwei Wochen passiert:</strong> nichts mehr. Dann ist die Antwort nicht spät, ' +
      'sondern abwesend — ein fehlendes Recht am Dienstkonto, eine Bestellung, die Google nicht ' +
      'herausgibt. Weiterzufragen würde das hinter einer Zahl verstecken, die nie kommt; der Wachhund ' +
      'meldet stattdessen, was offen bleibt.</p>' +
      '<p>Die Kurse holt dieser Lauf mit, darf davon aber nicht aufgehalten werden: Ein Kauf, der seit ' +
      'dem Morgen wartet, soll nicht weiterwarten, weil eine Kursquelle gerade nicht erreichbar ist.</p>',
    source: 'src/orders.ts, src/fx.ts',
  },
  {
    id: 'watchdog', zone: 'cf', label: 'Wachhund · alle 15 min', sub: 'sieben Regeln, Versand',
    col: 2, row: 5,
    holds: ['MAIL-Bindung'],
    guards: ['Regeln einzeln abschaltbar', 'gleiche Meldung nur einmal', 'Empfänger in der Bindung festgeschrieben'],
    detail: 'Prüft auf ein zu schnell verbrauchtes Paket, ein Konto mit auffälligem Budgetanteil, weitergegebene Zugänge, Abweichungen zwischen kalkulierten und tatsächlichen Einkaufskosten, ein dauerhaftes Minus, erhöhte Fehlerquoten und Käufe, zu denen Google auch nach einer Woche keinen Erlös gemeldet hat. Viertelstündlich, weil das, worauf es wartet, in Minuten passiert — stündlich wäre ein Bericht statt einer Warnung. Der Versand läuft über Cloudflare Email Routing, und die Zieladresse steht in der Bindung, nicht nur in den Einstellungen: Eine im Dashboard vertippte Adresse führt damit zu einer fehlgeschlagenen Zustellung statt zu Post an Fremde.',
    long:
      '<p>Sieben Regeln, jede gegen einen anderen Weg, Geld zu verlieren: ein frisches Paket wird in ' +
      'Minuten verbraucht (das Muster vor einer Rückbuchung); ein einzelnes Konto beansprucht einen ' +
      'auffälligen Anteil des Tagesbudgets; ein Zugang läuft auf ungewöhnlich vielen Geräten; der ' +
      'Neuronenverbrauch springt gegenüber dem, was die Preistabelle erwarten lässt; die Einnahmen ' +
      'bleiben dauerhaft hinter den Kosten zurück; die ' +
      'Fehlerquote steigt; ein bezahlter Kauf hat auch nach einer Woche keinen gemeldeten Erlös.</p>' +
      '<p>Die letzte ist die stillste und deshalb aufgenommen worden: Ohne sie liest sich ein Verkauf, ' +
      'zu dem Google nichts herausrückt, einfach so, als hätte er nichts eingebracht — und genau das ' +
      'ist dem ersten echten passiert. Sie meldet, was der stündliche Lauf nicht beantwortet bekommt.</p>' +
      '<p>Sofortmeldungen wie „das Tagesbudget ist zu 80 % voll" oder eine eingehende Erstattung ' +
      'stammen nicht von hier: Die entstehen dort, wo das Ereignis passiert, und brauchen keinen ' +
      'Zeitplan.</p>' +
      '<p><strong>Viertelstündlich</strong>, weil das, worauf gewartet wird, in Minuten passiert. ' +
      'Stündlich wäre ein Bericht, keine Warnung — die Minuten sind dann längst verbraucht.</p>' +
      '<p>Dieselbe Meldung geht nur einmal raus; ohne diese Sperre erzeugt ein anhaltender Zustand ' +
      'alle 15 Minuten Post und wird binnen eines Tages weggeklickt. Jede Regel lässt sich einzeln ' +
      'abschalten, damit eine laute nicht dazu zwingt, alle stummzuschalten.</p>' +
      '<p>Der Versand läuft über Cloudflare Email Routing. Die Zieladresse steht dabei <strong>in der ' +
      'Bindung</strong> und nicht nur in den Einstellungen: Was dort nicht eingetragen ist, kann der ' +
      'Worker nicht anschreiben. Eine im Dashboard vertippte Adresse führt so zu einer fehlgeschlagenen ' +
      'Zustellung statt zu Post an Fremde.</p>',
    source: 'src/notify/rules.ts, src/notify/email.ts',
  },


  {
    id: 'oauth', zone: 'google', label: 'Google OAuth2', sub: 'oauth2.googleapis.com',
    col: 3, row: 0,
    holds: ['Access-Token (1 h)'],
    detail: 'Tauscht den selbstsignierten RS256-JWT des Dienstkontos gegen ein Access-Token. Der Worker baut den JWT von Hand mit WebCrypto — ein Worker hat keine Node-Bibliotheken.',
    long:
      '<p>Der Umweg, den jede Anfrage an Google nimmt. Der Worker baut aus den Angaben des Dienstkontos ' +
      'einen kurzlebigen JWT, signiert ihn mit dessen privatem Schlüssel und tauscht ihn hier gegen ein ' +
      'Access-Token, das eine Stunde gilt.</p>' +
      '<p>Gebaut wird der JWT <strong>von Hand mit WebCrypto</strong>: Ein Worker ist kein Node und hat ' +
      'keine der üblichen Google-Bibliotheken. Das klingt nach Mehrarbeit und hat einen Vorteil — es ' +
      'gibt keine Abhängigkeit, die eines Tages ein Update braucht, das der Dienst nicht mitbekommt.</p>' +
      '<p>Das Token wird zwischengespeichert, solange es gilt. Ohne das ginge jedem Kauf eine ' +
      'zusätzliche Anfrage voraus, und das ausgerechnet in dem Moment, in dem jemand gerade bezahlt ' +
      'hat und auf sein Guthaben wartet.</p>',
    source: 'src/google.ts',
  },
  {
    id: 'playapi', zone: 'google', label: 'Play Developer API', sub: 'androidpublisher v3',
    col: 3, row: 1,
    guards: ['Einzige verbindliche Quelle für Käufe'],
    detail: '<code>purchases.products.get</code> entscheidet, ob wirklich Geld geflossen ist — der Kauf-Token der App wird nicht geglaubt. <code>:acknowledge</code> verhindert, dass Google den Kauf nach drei Tagen zurückdreht. <code>orders.get</code> liefert getrennt, was der Kauf wirklich wert war (gezahlt, Steuer, Erlös), und <code>purchases.voidedpurchases</code> ist die Liste, gegen die der nächtliche Abgleich läuft.',
    long:
      '<p><strong>Die einzige Instanz, die entscheidet, ob Geld geflossen ist.</strong> Was die App ' +
      'schickt, ist ein Kauf-Token und damit eine Behauptung; geglaubt wird sie nie. Erst wenn ' +
      '<code>purchases.products.get</code> den Kauf bestätigt, entsteht Guthaben.</p>' +
      '<p>Vier Aufrufe, vier verschiedene Fragen: <code>products.get</code> — war der Kauf echt? ' +
      '<code>:acknowledge</code> — sagt Google, dass die Ware geliefert ist; ohne diese Bestätigung ' +
      'dreht Google den Kauf nach drei Tagen von selbst zurück. <code>orders.get</code> — was war die ' +
      'Bestellung wirklich wert (gezahlt, Steuer, Erlös), denn der Listenpreis ist weder das, was die ' +
      'Kundschaft zahlt, noch das, was ankommt. <code>voidedpurchases</code> — die Liste, gegen die ' +
      'nachts abgeglichen wird.</p>' +
      '<p>Am Rande, damit es nicht noch einmal Zeit kostet: <code>purchases.products.get</code> ist ' +
      '<em>nicht</em> abgekündigt. Die Meldung über eine angeblich nötige Migration betrifft die ' +
      'Katalogverwaltung, nicht die Kaufprüfung.</p>',
  },
  {
    id: 'pubsub', zone: 'google', label: 'Cloud Pub/Sub', sub: 'Thema play-rtdn',
    col: 3, row: 2,
    holds: ['RTDN_SECRET in der Push-URL'],
    guards: ['Kann ausschließlich Guthaben entfernen'],
    detail: 'Push-Abo an <code>/v1/rtdn?key=…</code>. Meldet Erstattungen. Selbst wer den Schlüssel kennt, kann darüber nur Guthaben abziehen — und nur für einen Kauf, den er bereits kennen müsste.',
    long:
      '<p>Der Rückkanal von Google: Wird ein Kauf storniert, kommt eine Push-Nachricht an eine Adresse, ' +
      'in der ein Geheimnis steht.</p>' +
      '<p>Ein Geheimnis in einer URL ist normalerweise ein Fehler — es landet in Protokollen und im ' +
      'Verlauf. Hier ist es vertretbar, weil <strong>über diesen Weg nur Guthaben verschwinden ' +
      'kann</strong>. Wer den Schlüssel kennt, kann keine Minuten erzeugen, kein Konto öffnen und ' +
      'nichts auslesen; er könnte lediglich eine Erstattung für einen Kauf melden, dessen Token er ' +
      'ohnehin schon kennen müsste. Der Schaden eines Missbrauchs träfe ihn selbst.</p>' +
      '<p>Trotzdem ist der Weg nicht die einzige Wahrheit: Push ist nicht garantiert, und deshalb ' +
      'gleicht der nächtliche Lauf zusätzlich gegen Googles eigene Liste ab.</p>',
    source: 'src/routes/rtdn.ts',
  },
  {
    id: 'console', zone: 'google', label: 'Play Console', sub: 'Produkte, Kanäle, Tester',
    col: 3, row: 3,
    detail: 'Hier liegen die vier Einmalkaufprodukte, die Testkanäle und die Lizenztester. Die Produkt-IDs müssen zeichengenau mit <code>PACKAGES</code> im Server übereinstimmen.',
    long:
      '<p>Kein technischer Bestandteil des Diensts, aber der Ort, an dem drei Dinge festgelegt werden, ' +
      'ohne die er nicht funktioniert: die vier Einmalkaufprodukte, die Berechtigung des Dienstkontos ' +
      'und das Push-Abo für Erstattungen.</p>' +
      '<p><strong>Die Produkt-IDs müssen zeichengenau mit <code>PACKAGES</code> im Server ' +
      'übereinstimmen.</strong> Weicht eine ab, verkauft Play sie trotzdem — der Server kennt sie nur ' +
      'nicht und schreibt kein Guthaben gut. Das ist der unangenehmste denkbare Fehler: bezahlt, nichts ' +
      'bekommen, und niemand merkt es, bis sich jemand meldet.</p>' +
      '<p>Ein Detail, das oft gesucht wird: "Verbrauchbar" ist in der Console gar keine Einstellung. ' +
      'Ob ein Produkt erneut gekauft werden kann, folgt allein daraus, dass die App den Kauf verbraucht.</p>',
  },
  {
    id: 'sa', zone: 'google', label: 'Dienstkonto', sub: 'IAM · Play-Berechtigung',
    col: 3, row: 4,
    holds: ['privater RSA-Schlüssel'],
    guards: ['Nur Leserecht auf Käufe nötig'],
    detail: 'In der Google Cloud angelegt, in der Play Console eingeladen und dort auf diese App berechtigt. Die vollständige JSON-Schlüsseldatei liegt als Worker-Secret und sonst nirgends.',
    long:
      '<p>Die Identität, mit der der Dienst gegenüber Google auftritt. Sie entsteht in zwei Schritten, ' +
      'und beide sind nötig: in der Google Cloud angelegt, <em>und</em> in der Play Console eingeladen ' +
      'und dort auf diese eine App berechtigt. Fehlt der zweite Schritt, ist der Schlüssel gültig und ' +
      'trotzdem wertlos — ein Fehlerbild, das von einem falschen Schlüssel kaum zu unterscheiden ist.</p>' +
      '<p>Die Berechtigung ist die kleinstmögliche: Kaufbelege lesen. Dieses Konto kann keine App ' +
      'veröffentlichen, keine Preise ändern und keine Auszahlung anstoßen. Der Schaden bei einem ' +
      'Abfluss bliebe damit auf "jemand kann sehen, ob ein bestimmter Kauf echt war" begrenzt.</p>' +
      '<p>Die vollständige JSON-Schlüsseldatei liegt als Worker-Secret und sonst nirgends — nicht im ' +
      'Repository, nicht in der Konfiguration, nicht auf dem Rechner des Betreibers als Kopie im ' +
      'Projektordner.</p>',
  },

  {
    id: 'fx', zone: 'ext', label: 'Frankfurter · EZB-Kurse', sub: 'api.frankfurter.dev',
    // Eine gebrochene Zeile, und das ist Absicht statt Schlamperei. „Öffentliche Quellen" ist die
    // einzige Zone mit einem einzigen Mitglied, und ihr Kasten braucht 54 px Kopf über dem Knoten.
    // Auf Zeile 7 liefe der in die Google-Zone hinein, auf Zeile 8 klafften 118 px Leere darüber —
    // das Loch unten rechts, das das Bild schief aussehen ließ. 7,2 ist die erste Stelle, an der
    // keins von beidem passiert: 106 px weniger Höhe, und zwölf zwischen den beiden Kästen.
    col: 3, row: 5.4,
    guards: ['ohne Anmeldung', 'Ausfall ist folgenlos'],
    detail: 'Die Referenzkurse der EZB, mit denen ein Verkauf in CHF oder PLN überhaupt in der Hauswährung zählbar wird. Der Kurs des Kauftags wird einmal geschrieben und nie neu gerechnet — eine Zahl, die sich von selbst ändert, ist keine Buchführung. Bleibt der Dienst aus, fehlt nur die Umrechnung, nicht das Guthaben.',
    long:
      '<p>Play verkauft in der Währung der Kundschaft. Ohne Umrechnung zählte nur, was in Euro gekauft ' +
      'wurde, und jeder Verkauf in Franken oder Zloty fiele aus der Statistik.</p>' +
      '<p><strong>Der Kurs des Kauftags wird einmal geschrieben und nie neu gerechnet.</strong> Das ist ' +
      'der eigentliche Punkt: Eine Zahl, die sich rückwirkend ändert, weil ein Wechselkurs sich bewegt ' +
      'hat, ist keine Buchführung. Ein Verkauf von vorletzter Woche muss heute denselben Betrag zeigen ' +
      'wie damals.</p>' +
      '<p>Was das <em>nicht</em> ist: Googles eigene Umrechnung. Google zahlt zu seinem Kurs an seinem ' +
      'Tag aus und behält gegebenenfalls eine Gebühr ein. Alles, was hieraus abgeleitet wird, ist ' +
      'deshalb eine gute Schätzung, und jede Ansicht, die es zeigt, sagt das auch. Verbindlich sind ' +
      'allein die Auszahlungsberichte in der Play Console.</p>' +
      '<p>Fällt der Dienst aus, fehlt eine Umrechnung — kein Guthaben, keine Anfrage, kein Konto ist ' +
      'davon betroffen.</p>',
    source: 'src/fx.ts',
  },
];

export const EDGES: GraphEdge[] = [
  {
    from: 'app', to: 'worker', kind: 'data',
    label: 'HTTPS · Diktat & Umformulierung', token: 'Bearer Wallet-Token', guard: 'Rate-Limit, Budget',
    long:
      '<p>Der Weg, auf dem das Geld fließt. Die Aufnahme geht als <code>multipart/form-data</code> an ' +
      '<code>/v1/audio/transcriptions</code>, der umzuformulierende Text an ' +
      '<code>/v1/chat/completions</code> — dieselben Pfade, die OpenAI anbietet, mit dem Wallet-Token ' +
      'an der Stelle des API-Schlüssels.</p>' +
      '<p>Genau deshalb steht in der App kein Sonderfall: Derselbe Client, dieselbe Fehlerbehandlung, ' +
      'dieselbe Abbruchlogik wie bei jedem anderen Anbieter. Ein eigener Pfad für den bezahlten Weg ' +
      'wäre der zweite Ort gewesen, an dem Fehler auftreten können.</p>' +
      '<p>Auf dieser Verbindung greifen Rate-Limit je Konto und Tagesbudget. Beide antworten mit einem ' +
      'Statuscode, den die App in eine verständliche Meldung übersetzt — 402 heißt "Guthaben alle" und ' +
      'bietet Aufladen an, 503 heißt "gerade nicht verfügbar" und bietet Wiederholen an.</p>',
  },
  {
    from: 'wear', to: 'worker', kind: 'data',
    label: 'HTTPS · standalone', token: 'Bearer Wallet-Token',
    long:
      '<p>Ist die Uhr ohne Telefon unterwegs, spricht sie direkt mit dem Dienst — mit demselben Token ' +
      'und gegen dasselbe Guthaben.</p>' +
      '<p>Der Server unterscheidet die Uhr nicht von einem Telefon; er sieht nur ein weiteres Gerät an ' +
      'einem Konto. Das ist beabsichtigt: Eine Sonderbehandlung müsste an jeder Grenze mitgedacht ' +
      'werden — Rate-Limit, Gerätezählung, Abrechnung — und würde genau dort vergessen.</p>',
  },
  {
    from: 'wear', to: 'app', kind: 'auth',
    label: 'Data Layer · Einstellungs-Sync', token: 'Wallet-Token',
    long:
      '<p>Basis-URL, Token und Einstellungen wandern über Wear OS’ Data Layer vom Telefon auf die ' +
      'Uhr. Niemand tippt einen Wiederherstellungscode auf einer Uhr ab.</p>' +
      '<p>Die Richtung ist einseitig: Das Telefon ist die Quelle, die Uhr übernimmt. Zwei Seiten, die ' +
      'einander überschreiben dürfen, erzeugen Konflikte, die sich mit einem Token besonders unschön ' +
      'auflösen — ein alter Wert von der Uhr könnte einen frisch wiederhergestellten auf dem Telefon ' +
      'überschreiben.</p>',
  },
  {
    from: 'billing', to: 'app', kind: 'auth',
    label: 'Kaufergebnis', token: 'purchaseToken',
    long:
      '<p>Play meldet den abgeschlossenen Kauf zurück in die App: ein Kauf-Token, dazu Bestellnummer ' +
      'und Produkt.</p>' +
      '<p>Ab hier gilt die Reihenfolge, die nirgends verletzt werden darf: Der Kauf wird <strong>nicht ' +
      'verbraucht</strong>, bevor der Server Guthaben gutgeschrieben hat. Ein noch nicht verbrauchter ' +
      'Kauf ist der einzige Beleg, der einen Absturz, einen Netzausfall oder ein leeres Akku überlebt. ' +
      'Deshalb versucht die App das Einlösen beim nächsten Start erneut, und deshalb ist Einlösen ' +
      'serverseitig wiederholbar.</p>',
  },
  {
    from: 'store', to: 'billing', kind: 'data', label: 'Kaufdialog',
    long:
      '<p>Den Kaufdialog zeigt der Play Store, nicht die App. Die App kennt weder Zahlungsmittel noch ' +
      'Rechnungsadresse und bekommt sie auch nie zu sehen.</p>' +
      '<p>Das ist der Grund, warum der Dienst ganz ohne Zahlungsdaten auskommt: Google verkauft, ' +
      'kassiert und führt die Steuer ab. Was ankommt, ist eine Bestätigung, dass es passiert ist.</p>',
  },
  {
    from: 'app', to: 'worker', kind: 'auth',
    label: 'POST /v1/wallet/redeem', token: 'purchaseToken + Wallet-ID',
    long:
      '<p>Der Kauf wird eingelöst. Mitgeschickt wird der Kauf-Token und — falls schon eines existiert — ' +
      'die ID des bestehenden Guthabenkontos, damit die Minuten dort landen statt auf einem neuen.</p>' +
      '<p>Der Server glaubt davon nichts, sondern fragt Google. Erst dessen Antwort erzeugt Guthaben. ' +
      'Weil der Kauf-Token zugleich Primärschlüssel der Kauftabelle ist, ist ein zweiter Aufruf mit ' +
      'demselben Token folgenlos: Er liefert dieselbe Antwort und kein zweites Guthaben. Genau das ' +
      'macht das Wiederholen nach einem Abbruch gefahrlos.</p>',
  },

  {
    from: 'worker', to: 'walletdo', kind: 'store',
    label: 'RPC · debit / credit / claw', guard: 'atomar je Konto',
    long:
      '<p>Drei Aufrufe, die den Kontostand bewegen: abbuchen vor einer Anfrage, zurückbuchen wenn sie ' +
      'fehlschlägt, gutschreiben nach einem Kauf. Dazu das Zurückholen nach einer Erstattung, das ein ' +
      'Konto ausdrücklich ins Minus bringen darf.</p>' +
      '<p>Es ist ein direkter RPC-Aufruf auf das Objekt, keine Datenbankabfrage. Der Unterschied ist ' +
      'nicht die Geschwindigkeit, sondern die Unteilbarkeit: Prüfen und Abbuchen sind ein Schritt, und ' +
      'zwei gleichzeitige Diktate können nicht denselben Stand lesen.</p>' +
      '<p>Ein Minus ist bewusst erlaubt. Wer verbraucht und danach erstattet bekommt, soll nicht bei ' +
      'null landen, sondern bei dem, was er wirklich schuldet — sonst wäre der Vorgang ein kostenloser ' +
      'Weg an Minuten zu kommen, beliebig oft wiederholbar.</p>',
  },
  {
    from: 'worker', to: 'd1', kind: 'store',
    label: 'SQL · Hauptbuch', guard: 'nur Metadaten',
    long:
      '<p>Jede Anfrage hinterlässt eine Zeile: Zeitpunkt, Art, abgerechnete Sekunden, Token, Kosten, ' +
      'Statuscode, Dauer. <strong>Ausschließlich Zahlen.</strong> Kein Wort dessen, was diktiert oder ' +
      'umformuliert wurde, verlässt jemals den Arbeitsspeicher.</p>' +
      '<p>Auch abgewiesene Anfragen werden geschrieben — mit null Sekunden und null Kosten. Eine ' +
      'Ablehnung ist das, wonach eine Supportanfrage am ehesten fragt: "Seit Dienstag geht es nicht ' +
      'mehr" lässt sich aus einer Zeile beantworten und nicht aus deren Fehlen.</p>' +
      '<p>Geschrieben wird nach der Antwort, nicht davor: Die Buchhaltung darf das Diktat nicht ' +
      'verzögern.</p>',
  },
  {
    from: 'worker', to: 'guarddo', kind: 'store',
    label: 'RPC · spend / settle / kill', guard: 'Tagesbudget',
    long:
      '<p>Vor jeder kostenpflichtigen Anfrage wird der geschätzte Einkaufspreis auf das Tagesbudget ' +
      'gebucht; steht danach fest, was es wirklich gekostet hat, wird die Differenz nachgereicht. So ' +
      'ist das Budget in dem Moment belastet, in dem die Kosten entstehen, und nicht erst, wenn sie ' +
      'bekannt sind.</p>' +
      '<p>Der dritte Aufruf ist der Not-Aus: ein Schalter, der den ganzen Dienst anhält. Er ist bewusst ' +
      'grob und bewusst sofort. Wenn etwas so schiefgeht, dass man ihn braucht, will man nicht ' +
      'zwischen "welche Konten" und "welche Art von Anfragen" wählen müssen.</p>',
  },
  {
    from: 'cron', to: 'd1', kind: 'store',
    label: 'Aufräumen, Kurse, Erlöse, Abgleich', guard: 'Speicherbegrenzung',
    long:
      '<p>Der nächtliche Lauf schreibt in dieselbe Datenbank, aus der tagsüber gelesen wird: Er löscht ' +
      'alte Protokollzeilen stapelweise, kappt abgelaufene Prüfwerte, trägt Umrechnungen nach und ' +
      'korrigiert Käufe, deren Stornierung nie angekommen ist.</p>' +
      '<p>Stapelweise, weil D1 eine Anfrage nicht beliebig lange laufen lässt und ein Löschauftrag über ' +
      'Monate hinweg genau das täte. Lieber mehrere Nächte als eine abgebrochene Transaktion.</p>',
  },
  {
    from: 'cron', to: 'fx', kind: 'data',
    label: 'HTTPS · Tageskurse', guard: 'Ausfall folgenlos',
    long:
      '<p>Einmal nachts werden die Referenzkurse geholt und mit Datum abgelegt. Danach bekommen Käufe, ' +
      'die noch keine Umrechnung haben, den Kurs <em>ihres</em> Kauftags — nicht den von heute.</p>' +
      '<p>Reihenfolge: erst holen, dann nachtragen. Andersherum liefe der Nachtrag gegen die Kurse von ' +
      'gestern und müsste am nächsten Tag noch einmal ran.</p>' +
      '<p>Schlägt der Abruf fehl, passiert nichts weiter: Die betroffenen Käufe bleiben ohne ' +
      'Umrechnung und werden in einer der nächsten Nächte nachgetragen. Kein Guthaben und keine ' +
      'Anfrage hängt daran.</p>',
  },
  {
    from: 'cron', to: 'playapi', kind: 'auth',
    label: 'voidedpurchases · 30 Tage zurück', token: 'OAuth-Token', guard: 'Netz unter den Push-Meldungen',
    long:
      '<p>Googles eigene Liste stornierter Käufe, 30 Tage rückwärts. Sie ist das Netz unter den ' +
      'Push-Meldungen: Eine RTDN ist nicht garantiert, und eine verpasste Erstattung wäre ein dauerhaft ' +
      'falscher Kontostand, den niemand bemerkt — Geld zurück und die Minuten behalten.</p>' +
      '<p>Der Aufruf reitet auf demselben zwischengespeicherten Zugangstoken wie die Kaufprüfung; ' +
      'erklärt wird die Anmeldung an der Kette <code>Worker → OAuth2 → Play API</code> und hier nicht ' +
      'noch einmal. Die Linie steht trotzdem im Bild, weil sonst ein Netzweg zu Google unsichtbar ' +
      'bliebe, den es tatsächlich jede Nacht gibt.</p>',
  },
  {
    from: 'orders', to: 'playapi', kind: 'auth',
    label: 'orders.get', token: 'OAuth-Token', guard: 'Aufgabefrist zwei Wochen',
    long:
      '<p>Die Frage, die beim Kauf zu früh kam: Was hat diese Bestellung wirklich eingebracht? Die ' +
      'Antwort trennt sauber zwischen Gezahltem, Steuer und Entwickleranteil — und existiert erst, ' +
      'wenn Google die Zahlung abgerechnet hat.</p>' +
      '<p>Gefragt wird stündlich und nur für Käufe ohne Erlöszahl, höchstens 50 je Lauf, und nur ' +
      'innerhalb von zwei Wochen nach dem Kauf. Danach ist die Antwort nicht spät, sondern abwesend, ' +
      'und das ist ein Fall für den Wachhund, nicht für eine weitere Abfrage.</p>' +
      '<p>Auch dieser Aufruf nutzt das zwischengespeicherte Token der Kaufprüfung — dieselbe ' +
      'Berechtigung, dieselbe Stunde Gültigkeit, kein zusätzliches Geheimnis.</p>',
  },
  {
    from: 'orders', to: 'd1', kind: 'store',
    label: 'Erlös, Steuer, Umrechnung eintragen', guard: 'Kurs des Kauftags',
    long:
      '<p>Was zurückkommt, wird auf die Kaufzeile geschrieben: gezahlter Betrag, Steuer, ' +
      'Entwickleranteil, Währung, Käuferland — und die Umrechnung dazu, <strong>mit dem Kurs des ' +
      'Kauftags</strong>, nicht dem von heute. Eine Zahl, die drei Nächte zu spät eintrifft, ist damit ' +
      'noch immer wert, was sie wert war.</p>' +
      '<p>Mitgeschrieben wird auch jeder erfolglose Versuch. Ein Kauf, zu dem niemand eine Antwort ' +
      'bekommen konnte, soll nicht aussehen wie einer, nach dem niemand gefragt hat.</p>',
  },
  {
    from: 'orders', to: 'fx', kind: 'data',
    label: 'HTTPS · Tageskurse', guard: 'Ausfall hält den Lauf nicht auf',
    long:
      '<p>Ein gespeicherter Kurs trägt das Veröffentlichungsdatum der EZB, nicht den Tag des Abrufs. ' +
      'Stündlich erneut zu fragen schreibt deshalb dieselben Zeilen mit denselben Zahlen — bis die Bank ' +
      'veröffentlicht, und dann ist die neue Notierung binnen einer Stunde da statt erst in der Nacht.</p>' +
      '<p>Anders als im nächtlichen Lauf darf ein Fehlschlag hier nichts aufhalten: Er wird geschluckt, ' +
      'und die beiden Schritte, um die es geht, laufen trotzdem. Sonst hinge ein Kauf, der seit dem ' +
      'Morgen auf seine Zahl wartet, an der Erreichbarkeit einer Kursquelle.</p>',
  },
  {
    from: 'd1', to: 'backup', kind: 'store',
    label: 'wrangler d1 export · von Hand', guard: 'verschlüsselter Ordner',
    long:
      '<p>Die einzige Linie im Bild, die niemand automatisch auslöst. Sie läuft, wenn der Befehl ' +
      'aufgerufen wird, und holt die gesamte Datenbank als SQL-Datei über die Cloudflare-API auf den ' +
      'eigenen Rechner.</p>' +
      '<p>Sie steht hier, weil sie die Zone verlässt: Alles andere an Kontodaten bleibt in Westeuropa ' +
      'bei Cloudflare, diese Kopie nicht. Was am anderen Ende liegt, ist deshalb Teil der ' +
      'Sicherheitsbetrachtung und nicht bloß eine Bequemlichkeit.</p>',
  },
  {
    from: 'watchdog', to: 'd1', kind: 'store',
    label: 'SQL · sieben Regeln', guard: 'nur Zahlen, keine Inhalte',
    long:
      '<p>Der Wachhund liest, was ohnehin da ist: Käufe, Verbrauch, Statuscodes, Gerätezahlen. Er ' +
      'braucht keine zusätzliche Erhebung, weil jede seiner sieben Regeln aus Zahlen beantwortbar ist, ' +
      'die für die Abrechnung sowieso anfallen.</p>' +
      '<p>Er schreibt auch dorthin zurück: Jede ausgelöste Warnung wird als Zeile abgelegt, mit einem ' +
      'Schlüssel gegen Wiederholungen. Deshalb ist im Dashboard nachlesbar, was wann gemeldet wurde, ' +
      'auch wenn die Mail längst weggeräumt ist.</p>',
  },
  {
    from: 'watchdog', to: 'mailbox', kind: 'notify',
    label: 'Email Routing · kritisch sofort, Rest im Bericht', guard: 'Empfänger festgeschrieben',
    long:
      '<p>Kritische Befunde gehen sofort raus, alles Übrige sammelt der Tagesbericht. Versandt wird ' +
      'über Cloudflare Email Routing, also über dieselbe Infrastruktur, die auch die Domain bedient — ' +
      'kein zusätzlicher Dienstleister, kein zusätzlicher Vertrag, kein zusätzliches Geheimnis.</p>' +
      '<p>Der Empfänger steht in der Bindung des Workers und ist damit festgeschrieben. Das ist kein ' +
      'Versäumnis der Einstellungen, sondern ihr Gegengewicht: Wer sich im Dashboard vertippt, ' +
      'verschickt nichts an Fremde, sondern bekommt gar nichts.</p>' +
      '<p>Die Mail ist so gebaut, dass die Vorschau auf dem Sperrbildschirm bereits den Vorfall nennt. ' +
      'Eine Warnung, für die man erst das Telefon entsperren muss, um zu erfahren, ob sie eilt, ist ' +
      'eine halbe Warnung.</p>',
  },

  {
    from: 'worker', to: 'workersai', kind: 'data',
    label: 'Bindung · Audio & Text, ohne Netzweg',
    guard: 'kein Schlüssel, nichts gespeichert',
    long:
      '<p>Die einzige Linie, auf der Inhalte den Worker verlassen — und sie verlässt das Haus nicht. ' +
      'Die Bindung ist ein Aufruf innerhalb der Laufzeit: kein HTTPS, kein Schlüssel, keine ' +
      'Gegenstelle, die man erreichen können muss. Gespeichert wird nichts, in keine Richtung.</p>' +
      '<p><strong>Es gibt nur diese eine Linie.</strong> Diktat und Umformulierung gehen beide über ' +
      'dieselbe Bindung: ein Auftragsverarbeiter, kein Schlüssel in der Umgebung, und keine zweite ' +
      'Verbindung, an der jemand mitlesen könnte.</p>' +
      '<p><strong>Was diese Linie kostet, bevor sie überhaupt anfängt:</strong> Das Modell will das ' +
      'Audio als Zeichenkette, also wird es kodiert — aus 19 MB werden 26,5 MB und rund zwei Sekunden ' +
      'Rechenzeit. Gegen die Fünf-Minuten-Grenze unkritisch, aber wer annimmt, der Weg ohne Netzsprung ' +
      'sei deshalb der schnellere, liegt bei langen Aufnahmen zwei Sekunden daneben.</p>' +
      '<p>Was zurückkommt, wird auf das Nötige gekürzt: der Text, beziehungsweise die eine Antwort und ' +
      'ihr Verbrauch. Segmente, Wortzahlen, ein VTT-Spur und der <code>@cf/…</code>-Modellname bleiben ' +
      'hier — sie helfen niemandem und verraten, was hinter Dictate Cloud steht.</p>',
  },

  {
    from: 'worker', to: 'oauth', kind: 'auth',
    label: 'JWT-Bearer-Grant', token: 'RS256-JWT des Dienstkontos',
    long:
      '<p>Der Worker signiert einen kurzlebigen JWT mit dem privaten Schlüssel des Dienstkontos und ' +
      'tauscht ihn gegen ein Access-Token. Der private Schlüssel selbst verlässt dabei niemals den ' +
      'Worker — versandt wird nur die Signatur.</p>' +
      '<p>Passiert nur, wenn kein gültiges Token mehr im Zwischenspeicher liegt, also etwa einmal pro ' +
      'Stunde und nicht bei jedem Kauf.</p>',
  },
  {
    from: 'oauth', to: 'playapi', kind: 'auth',
    label: 'Access-Token, 1 h gültig', token: 'OAuth-Token',
    long:
      '<p>Mit dem eingetauschten Token werden die Play-Endpunkte angesprochen. Eine Stunde Gültigkeit ' +
      'ist Googles Vorgabe und gleichzeitig eine brauchbare Schadensgrenze: Ein abgeflossenes Token ' +
      'ist am nächsten Morgen wertlos, während ein abgeflossener Dienstkontoschlüssel es nicht wäre.</p>',
  },
  {
    from: 'playapi', to: 'worker', kind: 'auth',
    label: 'products.get · acknowledge', guard: 'verbindliche Kaufprüfung',
    long:
      '<p>Die Antwort, auf die alles wartet: Gab es diesen Kauf, für welches Produkt, wann, und war es ' +
      'ein Lizenztester? Erst danach entsteht Guthaben.</p>' +
      '<p>Das Feld <code>purchaseType</code> ist dabei wichtiger, als es aussieht: Der Wert 0 bedeutet ' +
      'Lizenztester. Ohne diese Unterscheidung zählten die eigenen Testkäufe als Umsatz — mit ' +
      'Listenpreis, ohne Steuer, ohne Erlös — und jede Durchschnittszahl im Dashboard wäre falsch. Sie ' +
      'wird im Moment des Kaufs festgehalten, weil sie sich später nicht mehr rekonstruieren lässt.</p>' +
      '<p><code>:acknowledge</code> ist die Quittung an Google, dass geliefert wurde. Bleibt sie aus, ' +
      'storniert Google den Kauf nach drei Tagen selbsttätig — und der Kundschaft wird das Geld ' +
      'zurückgegeben, obwohl sie die Minuten längst hat.</p>',
  },
  {
    from: 'sa', to: 'oauth', kind: 'auth',
    label: 'signiert den JWT', token: 'privater RSA-Schlüssel',
    long:
      '<p>Der private Schlüssel des Dienstkontos ist das, was die Signatur überhaupt möglich macht. Er ' +
      'liegt als Worker-Secret vor, wird beim Signieren im Arbeitsspeicher entpackt und nirgends ' +
      'abgelegt.</p>' +
      '<p>Wer ihn hätte, könnte im Namen des Diensts Kaufbelege abfragen — mehr nicht, weil die ' +
      'Berechtigung des Kontos auf genau das begrenzt ist. Das ist der Grund, warum diese Begrenzung ' +
      'nicht als Formalie behandelt wird.</p>',
  },
  {
    from: 'console', to: 'playapi', kind: 'auth',
    label: 'berechtigt das Dienstkonto',
    long:
      '<p>Kein Datenweg, sondern eine Voraussetzung: In der Play Console wird das Dienstkonto ' +
      'eingeladen und auf diese App berechtigt. Ohne diesen Schritt ist der Schlüssel gültig und ' +
      'trotzdem wertlos.</p>' +
      '<p>Die Kante steht im Bild, weil dieser Zusammenhang bei der Einrichtung am meisten Zeit kostet: ' +
      'Ein Schlüssel, der nicht berechtigt ist, erzeugt dieselbe Fehlermeldung wie ein falscher.</p>',
  },
  {
    from: 'console', to: 'pubsub', kind: 'notify',
    label: 'RTDN aktiviert',
    long:
      '<p>Auch das ist eine Einstellung und kein Verkehr: In der Console wird das Pub/Sub-Thema ' +
      'hinterlegt, in das Google Echtzeitmeldungen schreibt.</p>' +
      '<p>Ohne diesen Eintrag bleibt es still, und zwar unauffällig still — es gibt keine Fehlermeldung ' +
      'für "es kommt nie eine Erstattungsmeldung". Genau deshalb existiert der nächtliche Abgleich ' +
      'gegen Googles Liste als Netz darunter.</p>',
  },
  {
    from: 'pubsub', to: 'worker', kind: 'notify',
    label: 'Push · Erstattungen', token: 'RTDN_SECRET in der URL', guard: 'nimmt nur weg',
    long:
      '<p>Die Meldung selbst: Ein Kauf wurde storniert. Der Dienst holt das Guthaben zurück — auch ins ' +
      'Minus —, markiert den Kauf als erstattet und schlägt Alarm, wenn davon schon etwas verbraucht ' +
      'war.</p>' +
      '<p>Ob mit oder ohne Anspruchsentzug macht dabei einen Unterschied, den Google selbst nicht immer ' +
      'deutlich macht: Nur mit Anspruchsentzug ist die Ware zurückzugeben. Der Dienst behandelt jede ' +
      'Stornierung so, weil die Alternative wäre, Geld zurückzugeben und die Minuten zu lassen.</p>' +
      '<p>Und weil eine Push-Nachricht ausbleiben kann, ist dieser Weg nicht die einzige Quelle: Der ' +
      'nächtliche Abgleich prüft 30 Tage rückwärts nach.</p>',
  },
  {
    from: 'console', to: 'store', kind: 'data',
    label: 'Produkte & Testkanäle',
    long:
      '<p>Was in der Console eingerichtet wird, erscheint im Store: die vier Guthabenpakete, die ' +
      'Testkanäle und die Liste der Lizenztester.</p>' +
      '<p>Für den Betrieb heißt das vor allem eines: Ein Testkauf sieht bis auf ein einziges Feld ' +
      'exakt aus wie ein echter. Ohne dieses Feld — <code>purchaseType</code> — wäre nachträglich nicht ' +
      'mehr feststellbar, welche Umsätze eigene Tests waren.</p>',
  },

  {
    from: 'webuser', to: 'deleteweb', kind: 'data',
    label: 'HTTPS · Löschung', token: 'Wiederherstellungscode', guard: 'Versuche gedrosselt',
    long:
      '<p>Der Code wird eingegeben, gegen seinen SHA-256-Abzug geprüft und die Löschung in zwei ' +
      'Schritten bestätigt. Erst zeigt die Seite, was verschwindet und was bleibt; dann fragt sie noch ' +
      'einmal.</p>' +
      '<p>Zwei Schritte, weil dies der einzige Vorgang im ganzen Dienst ist, der sich nicht rückgängig ' +
      'machen lässt und dabei bezahltes Guthaben vernichtet. Eine einzelne Bestätigung wäre der ' +
      'billigere Weg zu einem Supportfall, den niemand mehr reparieren kann.</p>' +
      '<p>Auf dieser Verbindung greift dieselbe zweistufige Drossel wie beim Wiederherstellen.</p>',
  },
  {
    from: 'deleteweb', to: 'worker', kind: 'auth',
    label: 'derselbe Worker', guard: 'Code gegen SHA-256-Abzug',
    long:
      '<p>Die Löschseite ist keine eigene Anwendung, sondern eine Route desselben Workers. Sie ruft ' +
      'genau die Löschung auf, die auch die App auslöst — es gibt nur eine.</p>' +
      '<p>Das ist der Grund, warum beide Wege dieselben Eigenschaften haben: dieselbe Reihenfolge ' +
      '(erst die Datenbank leeren, dann das Durable Object löschen), derselbe Grabstein, dieselbe ' +
      'Behandlung des Prüfwerts. Zwei Implementierungen einer Löschung wären zwei Gelegenheiten, ' +
      'unterschiedlich viel zu vergessen.</p>',
  },

  {
    from: 'browser', to: 'access', kind: 'auth', label: 'HTTPS · Anmeldung',
    long:
      '<p>Die Anmeldung an der Verwaltung. Cloudflare Access prüft gegen eine Richtlinie, die auf eine ' +
      'einzelne E-Mail-Adresse lautet, und setzt danach ein signiertes Token als Cookie.</p>' +
      '<p>Der Dienst selbst sieht dabei kein Passwort und speichert keines. Das ist der eigentliche ' +
      'Gewinn dieser Bauform: Es gibt in der Datenbank nichts, was zum Anmelden taugt.</p>',
  },
  {
    from: 'access', to: 'admin', kind: 'auth',
    label: 'setzt Cookie', token: 'CF_Authorization-JWT',
    long:
      '<p>Nach erfolgreicher Prüfung setzt Access ein Cookie mit einem signierten Token, das die ' +
      'Sitzung trägt. Es läuft ab und wird dann neu ausgestellt.</p>' +
      '<p>Es ist ein Nachweis, keine Berechtigung: Was der Inhaber darf, entscheidet nicht das Token, ' +
      'sondern der Worker — und der prüft es bei jeder einzelnen Anfrage neu.</p>',
  },
  {
    from: 'admin', to: 'worker', kind: 'auth',
    label: 'Signatur gegen JWKS geprüft', guard: 'nicht der Kopfzeile geglaubt',
    long:
      '<p>Der entscheidende Schritt, und der am leichtesten zu übersehende. Jede Anfrage der ' +
      'Verwaltungsoberfläche trägt das Access-Token, und der Worker <strong>prüft dessen Signatur ' +
      'selbst</strong> — gegen die öffentlichen Schlüssel des Anbieters, samt der Frage, für welche ' +
      'Anwendung es ausgestellt wurde.</p>' +
      '<p>Der bequeme Weg wäre, dem Header zu glauben, den Access mitschickt. Der taugt nichts: Wer den ' +
      'Worker direkt anspricht, kann sich jeden Header ausdenken. Access schützt den Weg über ' +
      'Cloudflare, nicht den Worker vor jemandem, der daran vorbeigeht.</p>' +
      '<p>Jede Aktion von hier verlangt zusätzlich eine Begründung und landet im Admin-Protokoll.</p>',
  },
];
