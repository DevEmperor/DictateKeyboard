/**
 * The architecture as one interactive graph.
 *
 * Written as data rather than drawn as a picture, so it cannot drift from the system the way a
 * diagram in a wiki does: every node names the file it lives in, every edge names the protocol and
 * the credential it travels under, and adding a route here is a line, not a redraw.
 *
 * Positions are hand-placed. A force layout would save the effort and cost the point — this is read
 * to answer "what talks to what, holding which key", and that needs a stable arrangement someone can
 * learn, not one that reshuffles on every load. The canvas extent is derived from the zones at draw
 * time, so adding one below the others does not need a second number changed somewhere else.
 */

export interface GraphZone {
  id: string;
  label: string;
  sub: string;
  x: number; y: number; w: number; h: number;
  tone: 'client' | 'cloudflare' | 'google' | 'openai' | 'ext';
}

export interface GraphNode {
  id: string;
  zone: string;
  label: string;
  sub: string;
  x: number; y: number; w?: number; h?: number;
  /** Credentials this node holds, and where they are kept. */
  holds?: string[];
  /** What protects it. */
  guards?: string[];
  detail: string;
  source?: string;
}

export type Anchor = 'l' | 'r' | 't' | 'b';

export interface GraphEdge {
  from: string; to: string;
  fromSide: Anchor; toSide: Anchor;
  label: string;
  /** The credential carried, if any — drives the "token flow" filter. */
  token?: string;
  /** A protection applied on this hop — drives the "safeguards" filter. */
  guard?: string;
  kind: 'data' | 'auth' | 'store' | 'notify';
  /** Perpendicular offset for the routed corner, when two edges would overlap. */
  bend?: number;
}

export const ZONES: GraphZone[] = [
  { id: 'client', label: 'Gerät des Nutzers', sub: 'Android, Wear OS, Browser', x: 24, y: 60, w: 300, h: 1000, tone: 'client' },
  { id: 'cf', label: 'Cloudflare', sub: 'Worker, Speicher, Zero Trust', x: 372, y: 60, w: 640, h: 1000, tone: 'cloudflare' },
  { id: 'openai', label: 'OpenAI', sub: 'Modelle', x: 1060, y: 60, w: 460, h: 210, tone: 'openai' },
  { id: 'google', label: 'Google', sub: 'Play, Cloud, Identität', x: 1060, y: 310, w: 460, h: 750, tone: 'google' },
  { id: 'ext', label: 'Öffentliche Quellen', sub: 'ohne Anmeldung, ohne Vertrag', x: 1060, y: 1100, w: 460, h: 140, tone: 'ext' },
];

export const NODES: GraphNode[] = [
  {
    id: 'app', zone: 'client', label: 'Dictate (Android)', sub: 'Tastatur, schwebender Knopf',
    x: 48, y: 110, holds: ['Wallet-Token', 'Wiederherstellungscode'],
    guards: ['Token nur in App-privaten Prefs', 'höchstens 3 Geräte je Konto'],
    detail: 'Das Wallet-Token liegt in <code>ProviderAccount.apiKey</code> — genau dort, wo sonst ein API-Schlüssel steht. Deshalb erreicht der unveränderte OpenAI-Client den Dienst, ohne dass eine einzige Zeile im Netzwerkpfad angepasst werden musste. Wird der Zugang auf einem vierten Gerät wiederhergestellt, antwortet der Server mit der Geräteliste statt mit einem Token: Abmelden ist eine Auswahl, keine automatische Verdrängung.',
    source: 'app/.../dictate/cloud/DictateCloud.kt',
  },
  {
    id: 'wear', zone: 'client', label: 'Dictate (Wear OS)', sub: 'Uhr, standalone oder getethert',
    x: 48, y: 250, holds: ['Wallet-Token (synchronisiert)'],
    detail: 'Bekommt Basis-URL und Token über den Data-Layer-Sync vom Telefon und rechnet dasselbe Guthaben ab. Nimmt 16-kHz-Mono-WAV auf — das Format, dessen Dauer der Server exakt misst.',
    source: 'app/.../dictate/wear/PhoneWearSettingsResolver.kt',
  },
  {
    id: 'billing', zone: 'client', label: 'Play Billing 8', sub: 'Kaufabwicklung in der App',
    x: 48, y: 390, holds: ['purchaseToken'],
    guards: ['Verbrauch erst nach Gutschrift'],
    detail: 'Liefert nach dem Kauf einen <code>purchaseToken</code>. Existiert auf dem Gerät bereits ein Guthabenkonto, reist dessen ID als <code>obfuscatedAccountId</code> mit und kommt vom Server als Prüfwert wieder — <strong>keine Kennung von Google</strong>, sondern der eigene Wert zurück. Beim Erstkauf und nach einer Löschung reist nichts mit, und genau dort endet die Wiedererkennung. Verbraucht wird der Kauf erst, wenn der Server das Guthaben gutgeschrieben hat — vorher ist er der einzige haltbare Beleg, dass Geld geflossen ist.',
    source: 'app/.../dictate/cloud/DictateCloudBilling.kt',
  },
  {
    id: 'store', zone: 'client', label: 'Play Store', sub: 'Installation, Kaufdialog',
    x: 48, y: 530,
    detail: 'Liefert die App aus und zeigt den Kaufdialog. Billing funktioniert nur, wenn die App von hier installiert wurde — eine seitlich installierte APK trägt eine andere Signatur.',
  },
  {
    id: 'webuser', zone: 'client', label: 'Browser (Nutzer)', sub: 'Löschung ohne App',
    x: 48, y: 670, holds: ['Wiederherstellungscode'],
    detail: 'Ein Konto lässt sich löschen, ohne die App zu besitzen — nötig, wenn das Telefon weg ist oder die App längst deinstalliert wurde. Ausweis ist allein der Wiederherstellungscode, und die Seite sagt vorher, dass vorhandenes Guthaben dabei ersatzlos verfällt.',
  },
  {
    id: 'mailbox', zone: 'client', label: 'Postfach (du)', sub: 'Warnungen und Tagesbericht',
    x: 48, y: 940,
    detail: 'Kritische Befunde kommen sofort, alles Übrige einmal täglich — auch dann, wenn nichts vorgefallen ist. Das Ausbleiben des Berichts ist selbst ein Signal: Ein Wachhund, von dem man nur hört, wenn er bellt, ist von einem toten Wachhund nicht zu unterscheiden.',
  },
  {
    id: 'browser', zone: 'client', label: 'Browser (du)', sub: 'Betriebs-Dashboard',
    x: 48, y: 800, holds: ['CF_Authorization-JWT'],
    detail: 'Der einzige Zugang zum Dashboard. Die Anmeldung läuft über Cloudflare Access; der Worker prüft die Signatur selbst.',
  },

  {
    id: 'access', zone: 'cf', label: 'Cloudflare Access', sub: 'Zero Trust · nur /admin',
    x: 400, y: 800, holds: ['Signaturschlüssel (JWKS)'],
    guards: ['Default-Deny', 'Richtlinie auf E-Mail', 'Pfad nur /admin'],
    detail: 'Steht ausschließlich vor <code>/admin</code>. Läge es vor der ganzen Domain, bekäme die App statt einer Transkription eine Anmeldeseite — der Dienst wäre für alle tot.',
    source: 'src/admin/auth.ts',
  },
  {
    id: 'worker', zone: 'cf', label: 'Worker', sub: 'api.dictatekeyboard.com',
    x: 400, y: 300, w: 260, h: 78,
    holds: ['OPENAI_API_KEY', 'GOOGLE_SERVICE_ACCOUNT', 'RTDN_SECRET', 'ACCESS_AUD'],
    guards: ['Rate-Limit je Konto (20/min)', 'Tagesbudget', 'Not-Aus', 'Audio ≤ 10 min', 'Chat ≤ 8k/2k Token', 'höchstens 3 Geräte', 'Codeversuche gedrosselt'],
    detail: 'Prüft das Token gegen den SHA-256-Abzug, liest die Audiodauer aus dem Dateikopf — WAV, FLAC, Ogg/Opus, MP4/M4A und MP3 sagen sie selbst —, bucht ab und reicht erst dann weiter. Nur ein unbekannter Container wird nach Größe geschätzt, und dann nur zum Zurücklegen. Abgerechnet wird alles in Sekunden, Diktat wie Umformulierung. Speichert niemals Audio oder Text.',
    source: 'src/index.ts',
  },
  {
    id: 'd1', zone: 'cf', label: 'D1 · dictate-cloud', sub: 'Hauptbuch (Westeuropa)',
    x: 730, y: 120,
    guards: ['Nur Metadaten', '90 Tage Aufbewahrung', 'Kauf-Token als Primärschlüssel', 'Fremdschlüssel erzwungen'],
    detail: 'Konten, Token-Abzüge, Käufe, Verbrauch, Warnungen, Admin-Protokoll, Tagessummen und die vom Dashboard änderbaren Einstellungen. Der Kauf-Token ist Primärschlüssel in <code>purchases</code> — doppelte Einlösung ist damit strukturell unmöglich. Ein gelöschtes Konto behält seine Zeile als leeren Grabstein, weil die Kaufbelege zehn Jahre lang darauf verweisen müssen.',
    source: 'schema.sql',
  },
  {
    id: 'walletdo', zone: 'cf', label: 'Wallet · Durable Object', sub: 'ein Objekt je Konto',
    x: 730, y: 260,
    guards: ['Prüfen und Abbuchen atomar', 'Rate-Limiter im Objekt'],
    detail: 'Der maßgebliche Saldo. Weil je Konto genau ein Objekt existiert, laufen gleichzeitige Anfragen serialisiert — gemessen: 15 gleichzeitig ergeben exakt 15 Abbuchungen. Die Spalten in D1 sind nur eine Kopie für Listenansichten.',
    source: 'src/wallet.ts',
  },
  {
    id: 'guarddo', zone: 'cf', label: 'GlobalGuard · DO', sub: 'ein Objekt für alles',
    x: 730, y: 400,
    guards: ['Tagesbudget', 'Not-Aus', 'Codeversuche je Adresse (10/min)', 'Fehlversuche insgesamt (60/min)'],
    detail: 'Bewusst ein einziges Objekt für den ganzen Dienst: Ein Ausgabenlimit ist nur dann eines, wenn es exakt zählt. Bei Erreichen antwortet jede kostenpflichtige Anfrage mit 503. Hier läuft auch die Bremse gegen das Durchprobieren von Wiederherstellungscodes — je Adresse und, weil sich Adressen mieten lassen, zusätzlich über alle Fehlversuche zusammen.',
    source: 'src/guard.ts, src/throttle.ts',
  },
  {
    id: 'cron', zone: 'cf', label: 'Cron · 03:17 UTC', sub: 'nächtliche Langsamarbeit',
    x: 730, y: 540,
    guards: ['Speicherbegrenzung', 'Netz unter den Push-Meldungen'],
    detail: 'Vier Arbeiten, bewusst zu einer krummen Minute statt zur vollen Stunde, wo sich jeder Cron der Plattform staut: Einzelzeilen aus <code>usage_log</code> älter als 90 Tage löschen; den Prüfwert des Vorgängerkontos 24 Monate nach einer Löschung kappen; die Tageskurse holen und fehlende Umrechnungen nachtragen; und 30 Tage gegen Googles Liste stornierter Käufe abgleichen, weil eine Push-Meldung ausbleiben kann.',
    source: 'src/retention.ts, src/fx.ts, src/sweep.ts',
  },
  {
    id: 'admin', zone: 'cf', label: 'Dashboard /admin', sub: 'diese Seite',
    x: 400, y: 660,
    guards: ['Signaturprüfung im Worker', 'Begründungspflicht', 'Admin-Protokoll'],
    detail: 'Jede Aktion verlangt eine Begründung und landet mit deiner Adresse im <code>admin_log</code>. Ohne diese Spur gehen die Zahlen nicht mehr auf, sobald einmal von Hand korrigiert wurde.',
    source: 'src/admin/index.ts',
  },

  {
    id: 'deleteweb', zone: 'cf', label: 'Löschseite /delete', sub: 'öffentlich, ohne Anmeldung',
    x: 400, y: 190,
    guards: ['nur der Code als Ausweis', 'Versuche gedrosselt', 'zweistufige Bestätigung'],
    detail: 'Dieselbe Löschung wie in der App, nur ohne sie. Sie zeigt vorher, was verschwindet und was bleibt: die Kaufbelege, weil sie zehn Jahre aufzubewahren sind, und der Prüfwert des Vorgängerkontos für 24 Monate.',
    source: 'src/routes/delete-page.ts',
  },
  {
    id: 'watchdog', zone: 'cf', label: 'Wachhund · alle 15 min', sub: 'sechs Regeln, Versand',
    x: 730, y: 680, holds: ['MAIL-Bindung'],
    guards: ['Regeln einzeln abschaltbar', 'gleiche Meldung nur einmal', 'Empfänger in der Bindung festgeschrieben'],
    detail: 'Prüft auf Budgetüberschreitung, ein zu schnell verbrauchtes Paket, ein Konto mit auffälligem Budgetanteil, weitergegebene Zugänge, Abweichungen zwischen kalkulierten und tatsächlichen Einkaufskosten und erhöhte Fehlerquoten. Viertelstündlich, weil das, worauf es wartet, in Minuten passiert — stündlich wäre ein Bericht statt einer Warnung. Der Versand läuft über Cloudflare Email Routing, und die Zieladresse steht in der Bindung, nicht nur in den Einstellungen: Eine im Dashboard vertippte Adresse führt damit zu einer fehlgeschlagenen Zustellung statt zu Post an Fremde.',
    source: 'src/notify/rules.ts, src/notify/email.ts',
  },
  {
    id: 'costs', zone: 'openai', label: 'Costs API', sub: 'was OpenAI wirklich berechnet',
    x: 1088, y: 190, w: 250, h: 60, holds: ['OPENAI_ADMIN_KEY'],
    guards: ['nur Lesen', 'eigener Schlüssel, nicht der des Diensts'],
    detail: 'Ein <strong>anderer</strong> Schlüssel als der, mit dem diktiert wird: organisationsweit und ausschließlich zum Lesen der Abrechnung. Ohne ihn sagt die Finanzansicht das offen, statt eine Zahl zu erfinden. Er ist die einzige Quelle, gegen die sich die eigene Kalkulation prüfen lässt — daher die Warnung bei Abweichung.',
    source: 'src/costs.ts',
  },
  {
    id: 'fx', zone: 'ext', label: 'Frankfurter · EZB-Kurse', sub: 'api.frankfurter.dev',
    x: 1088, y: 1150, w: 250, h: 60,
    guards: ['ohne Anmeldung', 'Ausfall ist folgenlos'],
    detail: 'Die Referenzkurse der EZB, mit denen ein Verkauf in CHF oder PLN überhaupt in der Hauswährung zählbar wird. Der Kurs des Kauftags wird einmal geschrieben und nie neu gerechnet — eine Zahl, die sich von selbst ändert, ist keine Buchführung. Bleibt der Dienst aus, fehlt nur die Umrechnung, nicht das Guthaben.',
    source: 'src/fx.ts',
  },
  {
    id: 'openai', zone: 'openai', label: 'OpenAI API', sub: 'gpt-transcribe · gpt-5-nano',
    x: 1088, y: 116, w: 250, h: 60,
    guards: ['Modell serverseitig festgelegt', 'Antwortlänge gedeckelt', 'Reasoning fest auf minimal'],
    detail: 'Diktat und Umformulierung. Modell, Antwortlänge und Denkaufwand bestimmt der Server, nicht der Client — sonst wäre die Kalkulation offen. Der Denkaufwand steht fest auf <code>minimal</code>, weil Reasoning-Token gegen dasselbe Ausgabebudget laufen wie die Antwort: mit der Voreinstellung kam bei langen Texten eine leere Antwort zurück, für die trotzdem bezahlt war. Der Schlüssel liegt ausschließlich als Worker-Secret vor.',
  },
  {
    id: 'oauth', zone: 'google', label: 'Google OAuth2', sub: 'oauth2.googleapis.com',
    x: 1088, y: 380, holds: ['Access-Token (1 h)'],
    detail: 'Tauscht den selbstsignierten RS256-JWT des Dienstkontos gegen ein Access-Token. Der Worker baut den JWT von Hand mit WebCrypto — ein Worker hat keine Node-Bibliotheken.',
    source: 'src/google.ts',
  },
  {
    id: 'playapi', zone: 'google', label: 'Play Developer API', sub: 'androidpublisher v3',
    x: 1088, y: 520,
    guards: ['Einzige verbindliche Quelle für Käufe'],
    detail: '<code>purchases.products.get</code> entscheidet, ob wirklich Geld geflossen ist — der Kauf-Token der App wird nicht geglaubt. <code>:acknowledge</code> verhindert, dass Google den Kauf nach drei Tagen zurückdreht. <code>orders.get</code> liefert getrennt, was der Kauf wirklich wert war (gezahlt, Steuer, Erlös), und <code>purchases.voidedpurchases</code> ist die Liste, gegen die der nächtliche Abgleich läuft.',
  },
  {
    id: 'pubsub', zone: 'google', label: 'Cloud Pub/Sub', sub: 'Thema play-rtdn',
    x: 1088, y: 660, holds: ['RTDN_SECRET in der Push-URL'],
    guards: ['Kann ausschließlich Guthaben entfernen'],
    detail: 'Push-Abo an <code>/v1/rtdn?key=…</code>. Meldet Erstattungen. Selbst wer den Schlüssel kennt, kann darüber nur Guthaben abziehen — und nur für einen Kauf, den er bereits kennen müsste.',
    source: 'src/routes/rtdn.ts',
  },
  {
    id: 'console', zone: 'google', label: 'Play Console', sub: 'Produkte, Kanäle, Tester',
    x: 1088, y: 800,
    detail: 'Hier liegen die vier Einmalkaufprodukte, die Testkanäle und die Lizenztester. Die Produkt-IDs müssen zeichengenau mit <code>PACKAGES</code> im Server übereinstimmen.',
  },
  {
    id: 'sa', zone: 'google', label: 'Dienstkonto', sub: 'IAM · Play-Berechtigung',
    x: 1088, y: 940, holds: ['privater RSA-Schlüssel'],
    guards: ['Nur Leserecht auf Käufe nötig'],
    detail: 'In der Google Cloud angelegt, in der Play Console eingeladen und dort auf diese App berechtigt. Die vollständige JSON-Schlüsseldatei liegt als Worker-Secret und sonst nirgends.',
  },
];

export const EDGES: GraphEdge[] = [
  { from: 'app', to: 'worker', fromSide: 'r', toSide: 'l', kind: 'data',
    label: 'HTTPS · Diktat & Umformulierung', token: 'Bearer Wallet-Token', guard: 'Rate-Limit, Budget' },
  { from: 'wear', to: 'worker', fromSide: 'r', toSide: 'l', kind: 'data', bend: 26,
    label: 'HTTPS · standalone', token: 'Bearer Wallet-Token' },
  { from: 'wear', to: 'app', fromSide: 't', toSide: 'b', kind: 'auth',
    label: 'Data Layer · Einstellungs-Sync', token: 'Wallet-Token' },
  { from: 'billing', to: 'app', fromSide: 't', toSide: 'b', kind: 'auth', bend: 40,
    label: 'Kaufergebnis', token: 'purchaseToken' },
  { from: 'store', to: 'billing', fromSide: 't', toSide: 'b', kind: 'data', label: 'Kaufdialog' },
  { from: 'app', to: 'worker', fromSide: 'r', toSide: 'l', kind: 'auth', bend: -30,
    label: 'POST /v1/wallet/redeem', token: 'purchaseToken + Wallet-ID' },

  { from: 'worker', to: 'walletdo', fromSide: 'r', toSide: 'l', kind: 'store',
    label: 'RPC · debit / credit / claw', guard: 'atomar je Konto' },
  { from: 'worker', to: 'd1', fromSide: 'r', toSide: 'l', kind: 'store',
    label: 'SQL · Hauptbuch', guard: 'nur Metadaten' },
  { from: 'worker', to: 'guarddo', fromSide: 'r', toSide: 'l', kind: 'store',
    label: 'RPC · spend / settle / kill', guard: 'Tagesbudget' },
  { from: 'cron', to: 'd1', fromSide: 'r', toSide: 'r', kind: 'store',
    label: 'Aufräumen, Kurse, Abgleich', guard: 'Speicherbegrenzung' },
  { from: 'cron', to: 'fx', fromSide: 'r', toSide: 'l', kind: 'data', bend: 20,
    label: 'HTTPS · Tageskurse', guard: 'Ausfall folgenlos' },

  { from: 'watchdog', to: 'd1', fromSide: 'l', toSide: 'l', kind: 'store',
    label: 'SQL · sechs Regeln', guard: 'nur Zahlen, keine Inhalte' },
  { from: 'watchdog', to: 'mailbox', fromSide: 'b', toSide: 'r', kind: 'notify',
    label: 'Email Routing · kritisch sofort, Rest im Bericht', guard: 'Empfänger festgeschrieben' },

  { from: 'worker', to: 'openai', fromSide: 'r', toSide: 'l', kind: 'data',
    label: 'HTTPS · Audio & Text durchgereicht', token: 'OPENAI_API_KEY (Secret)',
    guard: 'nichts gespeichert' },

  { from: 'worker', to: 'costs', fromSide: 'r', toSide: 'l', kind: 'auth', bend: -160,
    label: 'HTTPS · Abrechnung lesen', token: 'OPENAI_ADMIN_KEY (Secret)', guard: 'nur Lesen' },

  { from: 'worker', to: 'oauth', fromSide: 'b', toSide: 'l', kind: 'auth',
    label: 'JWT-Bearer-Grant', token: 'RS256-JWT des Dienstkontos' },
  { from: 'oauth', to: 'playapi', fromSide: 'b', toSide: 't', kind: 'auth',
    label: 'Access-Token, 1 h gültig', token: 'OAuth-Token' },
  { from: 'playapi', to: 'worker', fromSide: 'l', toSide: 'b', kind: 'auth', bend: 60,
    label: 'products.get · acknowledge', guard: 'verbindliche Kaufprüfung' },
  { from: 'sa', to: 'oauth', fromSide: 'l', toSide: 'l', kind: 'auth',
    label: 'signiert den JWT', token: 'privater RSA-Schlüssel' },
  { from: 'console', to: 'playapi', fromSide: 'l', toSide: 'l', kind: 'auth', bend: 30,
    label: 'berechtigt das Dienstkonto' },
  { from: 'console', to: 'pubsub', fromSide: 'r', toSide: 'r', kind: 'notify',
    label: 'RTDN aktiviert' },
  { from: 'pubsub', to: 'worker', fromSide: 'l', toSide: 'b', kind: 'notify', bend: 100,
    label: 'Push · Erstattungen', token: 'RTDN_SECRET in der URL', guard: 'nimmt nur weg' },
  { from: 'console', to: 'store', fromSide: 'b', toSide: 'b', kind: 'data',
    label: 'Produkte & Testkanäle' },

  { from: 'webuser', to: 'deleteweb', fromSide: 'r', toSide: 'l', kind: 'data', bend: 14,
    label: 'HTTPS · Löschung', token: 'Wiederherstellungscode', guard: 'Versuche gedrosselt' },
  { from: 'deleteweb', to: 'worker', fromSide: 'r', toSide: 'b', kind: 'auth', bend: 40,
    label: 'derselbe Worker', guard: 'Code gegen SHA-256-Abzug' },

  { from: 'browser', to: 'access', fromSide: 'r', toSide: 'l', kind: 'auth', label: 'HTTPS · Anmeldung' },
  { from: 'access', to: 'admin', fromSide: 't', toSide: 'b', kind: 'auth',
    label: 'setzt Cookie', token: 'CF_Authorization-JWT' },
  { from: 'admin', to: 'worker', fromSide: 't', toSide: 'b', kind: 'auth',
    label: 'Signatur gegen JWKS geprüft', guard: 'nicht der Kopfzeile geglaubt' },
];
