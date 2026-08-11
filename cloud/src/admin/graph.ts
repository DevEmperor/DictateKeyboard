/**
 * The architecture as one interactive graph.
 *
 * Written as data rather than drawn as a picture, so it cannot drift from the system the way a
 * diagram in a wiki does: every node names the file it lives in, every edge names the protocol and
 * the credential it travels under, and adding a route here is a line, not a redraw.
 *
 * Positions are hand-placed on a 1560×1120 canvas. A force layout would save the effort and cost
 * the point — this is read to answer "what talks to what, holding which key", and that needs a
 * stable arrangement someone can learn, not one that reshuffles on every load.
 */

export interface GraphZone {
  id: string;
  label: string;
  sub: string;
  x: number; y: number; w: number; h: number;
  tone: 'client' | 'cloudflare' | 'google' | 'openai';
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
];

export const NODES: GraphNode[] = [
  {
    id: 'app', zone: 'client', label: 'Dictate (Android)', sub: 'Tastatur, schwebender Knopf',
    x: 48, y: 110, holds: ['Wallet-Token', 'Wiederherstellungscode'],
    guards: ['Token nur in App-privaten Prefs'],
    detail: 'Das Wallet-Token liegt in <code>ProviderAccount.apiKey</code> — genau dort, wo sonst ein API-Schlüssel steht. Deshalb erreicht der unveränderte OpenAI-Client den Dienst, ohne dass eine einzige Zeile im Netzwerkpfad angepasst werden musste.',
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
    detail: 'Liefert nach dem Kauf einen <code>purchaseToken</code>. Die Wallet-ID reist als <code>obfuscatedAccountId</code> mit. Verbraucht wird der Kauf erst, wenn der Server das Guthaben gutgeschrieben hat — vorher ist er der einzige haltbare Beleg, dass Geld geflossen ist.',
    source: 'app/.../dictate/cloud/DictateCloudBilling.kt',
  },
  {
    id: 'store', zone: 'client', label: 'Play Store', sub: 'Installation, Kaufdialog',
    x: 48, y: 530,
    detail: 'Liefert die App aus und zeigt den Kaufdialog. Billing funktioniert nur, wenn die App von hier installiert wurde — eine seitlich installierte APK trägt eine andere Signatur.',
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
    guards: ['Rate-Limit je Konto', 'Tagesbudget', 'Not-Aus', 'Audio ≤ 10 min', 'Chat ≤ 8k/2k Token'],
    detail: 'Prüft das Token gegen den SHA-256-Abzug, misst die Audiodauer aus dem WAV-Header, bucht ab und reicht erst dann weiter. Speichert niemals Audio oder Text.',
    source: 'src/index.ts',
  },
  {
    id: 'd1', zone: 'cf', label: 'D1 · dictate-cloud', sub: 'Hauptbuch (Westeuropa)',
    x: 730, y: 120,
    guards: ['Nur Metadaten', '90 Tage Aufbewahrung', 'Kauf-Token als Primärschlüssel'],
    detail: 'Konten, Token-Abzüge, Käufe, Verbrauch, Admin-Protokoll, Tagessummen. Der Kauf-Token ist Primärschlüssel in <code>purchases</code> — doppelte Einlösung ist damit strukturell unmöglich.',
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
    guards: ['Tagesbudget', 'Not-Aus'],
    detail: 'Bewusst ein einziges Objekt für den ganzen Dienst: Ein Ausgabenlimit ist nur dann eines, wenn es exakt zählt. Bei Erreichen antwortet jede kostenpflichtige Anfrage mit 503.',
    source: 'src/guard.ts',
  },
  {
    id: 'cron', zone: 'cf', label: 'Cron · 03:17 UTC', sub: 'Aufbewahrung',
    x: 730, y: 540,
    guards: ['Speicherbegrenzung'],
    detail: 'Löscht Einzelzeilen aus <code>usage_log</code>, die älter als 90 Tage sind. Tagessummen und Admin-Protokoll bleiben — die Statistik ist vollständig, das Detailprotokoll altert weg.',
    source: 'src/retention.ts',
  },
  {
    id: 'admin', zone: 'cf', label: 'Dashboard /admin', sub: 'diese Seite',
    x: 400, y: 660,
    guards: ['Signaturprüfung im Worker', 'Begründungspflicht', 'Admin-Protokoll'],
    detail: 'Jede Aktion verlangt eine Begründung und landet mit deiner Adresse im <code>admin_log</code>. Ohne diese Spur gehen die Zahlen nicht mehr auf, sobald einmal von Hand korrigiert wurde.',
    source: 'src/admin/index.ts',
  },

  {
    id: 'openai', zone: 'openai', label: 'OpenAI API', sub: 'gpt-transcribe · gpt-5-nano',
    x: 1088, y: 120, w: 250,
    guards: ['Modell serverseitig festgelegt'],
    detail: 'Diktat und Umformulierung. Das Modell bestimmt der Server, nicht der Client — sonst wäre die Kalkulation offen. Der Schlüssel liegt ausschließlich als Worker-Secret vor.',
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
    detail: '<code>purchases.products.get</code> entscheidet, ob wirklich Geld geflossen ist — der Kauf-Token der App wird nicht geglaubt. <code>:acknowledge</code> verhindert, dass Google den Kauf nach drei Tagen zurückdreht.',
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
    label: 'DELETE älter als 90 Tage', guard: 'Speicherbegrenzung' },

  { from: 'worker', to: 'openai', fromSide: 'r', toSide: 'l', kind: 'data',
    label: 'HTTPS · Audio & Text durchgereicht', token: 'OPENAI_API_KEY (Secret)',
    guard: 'nichts gespeichert' },

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

  { from: 'browser', to: 'access', fromSide: 'r', toSide: 'l', kind: 'auth', label: 'HTTPS · Anmeldung' },
  { from: 'access', to: 'admin', fromSide: 't', toSide: 'b', kind: 'auth',
    label: 'setzt Cookie', token: 'CF_Authorization-JWT' },
  { from: 'admin', to: 'worker', fromSide: 't', toSide: 'b', kind: 'auth',
    label: 'Signatur gegen JWKS geprüft', guard: 'nicht der Kopfzeile geglaubt' },
];
