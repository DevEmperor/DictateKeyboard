import type { Alert, Severity } from '../alerts';
import type { Env } from '../config';

/**
 * What the mails look like.
 *
 * Written for the two seconds someone gives a notification on a phone at night. The subject alone
 * has to answer "is this money leaving": it leads with the severity and the one number that
 * matters, because a subject that reads "Dictate Cloud alert" makes you open the mail to find out
 * whether you can go back to sleep.
 *
 * Both a text and an HTML part, and the text one is not an afterthought — it is what a watch and a
 * notification preview show.
 */

export function adminUrl(env: Env): string {
  return (env.ADMIN_URL ?? 'https://api.dictatekeyboard.com/admin').replace(/\/+$/, '');
}

const LABEL: Record<Severity, string> = { critical: 'KRITISCH', notice: 'Hinweis' };
const TONE: Record<Severity, string> = { critical: '#F85149', notice: '#D29922' };

/**
 * The rule behind an alert, in words.
 *
 * `refund_loss` in a mail is a line of code showing through. The dashboard already names these
 * things properly; the mail should agree with it rather than leak the identifier.
 */
const KIND: Record<string, string> = {
  audio_duration_missing: 'Audiolänge fehlt in der Antwort',
  budget: 'Tagesbudget',
  code_guessing: 'Codes werden durchprobiert',
  device_limit: 'Gerätegrenze erreicht',
  budget_hog: 'Konto-Anteil am Budget',
  error_rate: 'Fehlerquote',
  fast_burn: 'Guthaben rasant verbraucht',
  kill_switch: 'Not-Aus',
  neuron_spike: 'Neuronen-Ausschlag',
  reasoning_leak: 'Das Modell denkt wieder',
  overall_loss: 'Insgesamt im Minus',
  refund_loss: 'Erstattung nach Verbrauch',
  repeat_buyer_refunded: 'Käufer mit Erstattungshistorie',
  revenue_unreported: 'Erlös nicht gemeldet',
  shared_token: 'Zugang weitergegeben',
  void_sweep: 'Nachgeholte Erstattung',
};

/** Local time, because that is the time the reader is standing in when the phone buzzes. */
function stamp(at: number): string {
  return new Intl.DateTimeFormat('de-DE', {
    timeZone: 'Europe/Berlin',
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
  }).format(new Date(at));
}

export function renderAlertMail(env: Env, alert: Alert) {
  const url = adminUrl(env);
  const link = alert.walletId ? `${url}#wallet=${encodeURIComponent(alert.walletId)}` : url;
  const when = stamp(Date.now());

  // `filter(Boolean)` here would drop the empty strings that *are* the paragraph breaks, which is
  // how this part came to be one unbroken wall — the one shape a notification preview cannot use.
  // Only the genuinely optional lines are removed.
  const text = [
    `${LABEL[alert.severity]}: ${alert.title}`,
    '',
    alert.detail,
    '',
    `Regel: ${KIND[alert.kind] ?? alert.kind}`,
    `Zeitpunkt: ${when}`,
    alert.walletId ? `Konto: ${alert.walletId}` : null,
    '',
    `Dashboard: ${link}`,
  ].filter((line) => line !== null).join('\n');

  const facts = [
    ['Regel', esc(KIND[alert.kind] ?? alert.kind)],
    ['Zeitpunkt', esc(when)],
    ...(alert.walletId ? [['Konto', mono(alert.walletId)]] : []),
  ] as Array<[string, string]>;

  return {
    subject: `${alert.severity === 'critical' ? '🔴' : '🟡'} Dictate Cloud — ${alert.title}`,
    text,
    html: shell({
      severity: alert.severity,
      preheader: alert.detail,
      body: `
      <h1 style="margin:0 0 10px;font-size:21px;line-height:1.3;font-weight:700;color:#E6EDF3">${esc(alert.title)}</h1>
      ${paragraphs(alert.detail)}
      ${factTable(facts)}
      ${button(link, alert.walletId ? 'Konto ansehen' : 'Dashboard öffnen')}
    `,
    }),
  };
}

export interface DigestInput {
  day: string;
  alerts: Array<{ severity: Severity; title: string; detail: string; ts: number }>;
  figures: Array<{ label: string; value: string }>;
}

export function renderDigestMail(env: Env, digest: DigestInput) {
  const url = adminUrl(env);
  const critical = digest.alerts.filter((a) => a.severity === 'critical').length;

  const headline = digest.alerts.length === 0
    ? 'Ruhiger Tag'
    : `${digest.alerts.length} Meldung${digest.alerts.length === 1 ? '' : 'en'}${critical ? `, davon ${critical} kritisch` : ''}`;

  const text = [
    `Dictate Cloud — Tagesbericht ${digest.day}`,
    '',
    ...digest.figures.map((f) => `${f.label}: ${f.value}`),
    '',
    headline,
    ...digest.alerts.map((a) => `  · [${LABEL[a.severity]}] ${a.title} — ${a.detail}`),
    '',
    `Dashboard: ${url}`,
  ].join('\n');

  return {
    subject: `Dictate Cloud — Tagesbericht ${digest.day}${critical ? ` (${critical} kritisch)` : ''}`,
    text,
    html: shell({
      severity: critical ? 'critical' : 'notice',
      preheader: `${headline}. ${digest.figures.map((f) => `${f.label} ${f.value}`).join(' · ')}`,
      body: `
      <div style="font-size:11.5px;letter-spacing:.09em;text-transform:uppercase;font-weight:700;color:#7D8B9A">Tagesbericht ${esc(digest.day)}</div>
      <h1 style="margin:8px 0 18px;font-size:21px;line-height:1.3;font-weight:700;color:#E6EDF3">${esc(headline)}</h1>
      ${factTable(digest.figures.map((f) => [f.label, `<strong style="color:#E6EDF3">${esc(f.value)}</strong>`] as [string, string]))}
      ${digest.alerts.length ? `<div style="margin-top:20px">${digest.alerts.map((a) => `
        <table role="presentation" width="100%" style="border-collapse:separate;margin:0 0 12px"><tr>
          <td width="3" style="background:${TONE[a.severity]};font-size:0;line-height:0">&nbsp;</td>
          <td style="padding:2px 0 2px 12px">
            <div style="font-size:15px;line-height:1.35;font-weight:600;color:#E6EDF3">${esc(a.title)}</div>
            <div style="margin-top:3px;font-size:13.5px;line-height:1.5;color:#8FA0B0">${esc(a.detail)}</div>
          </td>
        </tr></table>`).join('')}</div>`
        : '<p style="margin:20px 0 0;font-size:15px;line-height:1.55;color:#8FA0B0">Keine Auffälligkeiten. Nichts zu tun.</p>'}
      ${button(url, 'Dashboard öffnen')}
    `,
    }),
  };
}

/* ------------------------------------------------------------------ Bausteine */

/**
 * One dark card on a dark ground.
 *
 * Tables all the way down and inline styles throughout: mail clients strip `<style>` blocks, most
 * have never heard of flexbox, and several drop `border-radius` — so nothing structural may depend
 * on any of it. `color-scheme` keeps a client from inverting the whole thing a second time in dark
 * mode. The severity stripe across the top carries the same message as the badge, in a form that
 * survives a client which decides to recolour text.
 */
function shell({ severity, preheader, body }: {
  severity: Severity; preheader: string; body: string;
}): string {
  return `<!doctype html><html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="dark light"><meta name="supported-color-schemes" content="dark light"></head>
<body style="margin:0;padding:0;background:#0B0F14;-webkit-text-size-adjust:100%;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif">
<div style="display:none;font-size:1px;line-height:1px;max-height:0;max-width:0;opacity:0;overflow:hidden">${esc(preheader).slice(0, 180)}</div>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;background:#0B0F14">
<tr><td align="center" style="padding:24px 12px">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:560px;border-collapse:separate;background:#121820;border:1px solid #1E2833;border-radius:14px;overflow:hidden">
<tr><td style="height:4px;background:${TONE[severity]};font-size:0;line-height:0">&nbsp;</td></tr>
<tr><td style="padding:22px 26px 12px">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse"><tr>
    <td style="font-size:13px;font-weight:700;color:#30B7E6;letter-spacing:.02em">Dictate&nbsp;Cloud</td>
    <td align="right">${badge(severity)}</td>
  </tr></table>
</td></tr>
<tr><td style="padding:10px 26px 26px">
${body}
</td></tr></table>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:560px;border-collapse:collapse">
<tr><td style="padding:14px 4px 0;font-size:11.5px;line-height:1.55;color:#5C6875">
Automatische Meldung deines eigenen Servers. Schwellen und Empfänger stellst du im Dashboard unter <strong style="color:#7D8B9A">Betrieb</strong> ein.
</td></tr></table>
</td></tr></table></body></html>`;
}

function badge(severity: Severity): string {
  return `<span style="display:inline-block;padding:3px 10px;border-radius:999px;font-size:11px;font-weight:700;letter-spacing:.07em;background:${TONE[severity]}22;color:${TONE[severity]}">${LABEL[severity]}</span>`;
}

/**
 * The prose, broken where it already breaks.
 *
 * Rule details are two to five sentences of German written to be read once. As a single block at
 * phone width that is a wall; split at the sentence boundaries the author already put there, it is
 * three short paragraphs. Nothing is rewritten — only where the line breaks changes.
 */
function paragraphs(detail: string): string {
  const sentences = String(detail).match(/[^.!?]+[.!?]+(\s|$)/g) ?? [detail];
  const chunks: string[] = [];
  let buffer = '';
  for (const sentence of sentences) {
    buffer += sentence;
    // Two sentences to a paragraph, unless one is long enough to stand alone.
    if (buffer.length > 150) { chunks.push(buffer.trim()); buffer = ''; }
  }
  if (buffer.trim()) chunks.push(buffer.trim());

  return chunks
    .map((c, i) => `<p style="margin:0 0 ${i === chunks.length - 1 ? '0' : '12px'};font-size:15px;line-height:1.6;color:#B6C2CF">${esc(c)}</p>`)
    .join('');
}

/** Label left, value right — but a value that cannot wrap gets the whole width instead. */
function factTable(rows: Array<[string, string]>): string {
  if (!rows.length) return '';
  return `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;margin-top:20px;background:#0E141B;border:1px solid #1E2833;border-radius:10px">
${rows.map(([label, value], i) => `<tr>
  <td style="padding:9px 14px;font-size:12.5px;color:#7D8B9A;white-space:nowrap;vertical-align:top${i ? ';border-top:1px solid #1A2430' : ''}">${esc(label)}</td>
  <td align="right" style="padding:9px 14px;font-size:13px;color:#E6EDF3;vertical-align:top${i ? ';border-top:1px solid #1A2430' : ''}">${value}</td>
</tr>`).join('')}
</table>`;
}

/** An identifier long enough to break the layout if it is allowed to stay in one piece. */
function mono(value: string): string {
  return `<span style="font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12px;color:#B6C2CF;word-break:break-all">${esc(value)}</span>`;
}

/** Table-based, because a styled `<a>` alone loses its background in Outlook. */
function button(href: string, label: string): string {
  return `<table role="presentation" cellpadding="0" cellspacing="0" style="border-collapse:separate;margin-top:22px">
<tr><td style="background:#30B7E6;border-radius:10px">
<a href="${esc(href)}" style="display:inline-block;padding:11px 20px;font-size:14px;font-weight:700;color:#04121A;text-decoration:none">${esc(label)}</a>
</td></tr></table>`;
}

function esc(value: string): string {
  return String(value)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}
