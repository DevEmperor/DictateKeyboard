import { raise } from '../alerts';
import { FREE_NEURONS_PER_DAY, billedNanoForDay, limitsFrom, type Env } from '../config';
import { homeCurrency, usdRate } from '../fx';
import { alertSettings } from '../settings';
import { num, today } from '../util';

/**
 * The watchdog, run every quarter of an hour against the ledger.
 *
 * What is deliberately **not** in here: high consumption. A customer cannot dictate you into a
 * loss — the balance is debited before the model is called, and even the largest pack consumed to
 * the last second leaves a healthy margin. Alerting on heavy use would train you to ignore the
 * mails that matter.
 *
 * What is in here are the shapes that actually cost money or hide a problem: credit spent
 * suspiciously fast (the run-up to a refund), one account starving the shared daily budget, a token
 * being passed around, a day whose compute is unlike the week before it, and a model that has
 * started thinking again.
 *
 * **What used to be in here and no longer can be:** the comparison against the provider's own
 * invoice. It was the only rule able to find a mistake in our *own* arithmetic, and it worked
 * because the old provider published a billing endpoint. Workers AI bills the account this Worker runs on and
 * offers nothing to ask, so the check moved out of the software and onto a calendar: the monthly
 * Cloudflare invoice, read by hand against the dashboard. Written down here because a guarantee
 * that quietly disappears is worse than one that was never claimed.
 *
 * The budget thresholds live in `guard.ts` instead, because only the Durable Object can decide
 * "this request crossed 80 %" without two of them crossing it at once. Refunds live in
 * `routes/rtdn.ts`, where the loss is already being worked out.
 */

const HOUR_MS = 3_600_000;
const NANO_PER_USD = 1_000_000_000;

export async function evaluateRules(env: Env, ctx: ExecutionContext): Promise<number> {
  const t = await alertSettings(env);
  // The master switch stops the work, not just the mail. Nothing is evaluated and nothing is
  // recorded — which is what "off" has to mean, or the dashboard fills up with warnings for a
  // watchdog somebody deliberately silenced.
  if (!t.enabled) return 0;

  const on = (key: string, run: () => Promise<number>) =>
    t.rules[key] === false ? Promise.resolve(0) : run();

  const results = await Promise.allSettled([
    on('fast_burn', () => fastBurn(env, ctx, t.fastBurnPercent, t.fastBurnHours)),
    on('budget_hog', () => hogsTheBudget(env, ctx, t.walletBudgetSharePercent)),
    on('shared_token', () => sharedToken(env, ctx, t.devicesPerWallet)),
    on('overall_loss', () => overallLoss(env, ctx, t.minLossHome)),
    on('error_rate', () => errorRate(env, ctx, t.errorRatePercent)),
    on('revenue_unreported', () => revenueUnreported(env, ctx)),
    on('neuron_spike', () => neuronSpike(env, ctx, t.neuronSpikeFactor)),
    on('slow_upstream', () => slowUpstream(env, ctx, t.slowShortMs)),
    on('invoice_missing', () => invoiceMissing(env, ctx)),
  ]);

  // One broken rule must not silence the other five. A rule that throws is itself worth knowing
  // about, so it goes to the log rather than being swallowed.
  let raised = 0;
  for (const result of results) {
    if (result.status === 'fulfilled') raised += result.value;
    else console.log(`alert rule failed: ${String(result.reason).slice(0, 200)}`);
  }
  return raised;
}

/**
 * A fresh pack emptied at a run.
 *
 * This is the run-up to the one manoeuvre that genuinely costs money: buy, consume, charge back.
 * It cannot be prevented — the minutes are legitimately paid for at the moment they are spent —
 * but seeing it happen means the refund a day later is not a surprise, and the account can be
 * watched before it buys again.
 *
 * Perfectly innocent explanations exist (someone transcribing a stack of recordings they had been
 * saving up), which is why nothing is blocked and the mail says so.
 */
async function fastBurn(env: Env, ctx: ExecutionContext, percent: number, hours: number): Promise<number> {
  const since = Date.now() - hours * HOUR_MS;
  const rows = await env.DB.prepare(
    `SELECT p.wallet_id AS walletId, p.purchase_token AS token, p.seconds, p.purchased_at AS purchasedAt,
            (SELECT COALESCE(SUM(u.seconds), 0) FROM usage_log u
              WHERE u.wallet_id = p.wallet_id AND u.ts >= p.purchased_at AND u.status < 400) AS used
       FROM purchases p
       JOIN wallets w ON w.id = p.wallet_id AND w.is_test = 0
      WHERE p.state = 'granted' AND p.purchased_at >= ?`,
  ).bind(since).all<{ walletId: string; token: string; seconds: number; purchasedAt: number; used: number }>();

  let raised = 0;
  for (const row of rows.results ?? []) {
    if (row.seconds <= 0) continue;
    const share = (num(row.used) / row.seconds) * 100;
    if (share < percent) continue;

    const minutes = Math.round(num(row.used) / 60);
    const elapsed = Math.max(1, Math.round((Date.now() - row.purchasedAt) / 60_000));
    const ok = await raise(env, {
      kind: 'fast_burn',
      severity: 'critical',
      walletId: row.walletId,
      value: share,
      title: `Guthaben in ${elapsed} Minuten zu ${Math.round(share)} % verbraucht`,
      detail:
        `Das Konto hat vor ${elapsed} Minuten ein Paket über ${Math.round(row.seconds / 60)} Minuten gekauft und davon ` +
        `bereits ${minutes} Minuten verbraucht. Das ist das übliche Muster vor einer Rückbuchung: kaufen, ` +
        `verbrauchen, Geld zurückfordern. Es kann genauso gut jemand sein, der einen Stapel Aufnahmen abarbeitet — ` +
        `deshalb wurde nichts gesperrt. Behalte das Konto im Auge, falls es erneut kauft.`,
      // Per purchase, not per wallet: a second pack burned the same way is news again.
      dedupeKey: `fast_burn:${row.token}`,
    }, ctx);
    if (ok) raised++;
  }
  return raised;
}

/**
 * One account eating the shared daily budget.
 *
 * Not a loss — those minutes are paid for. It is a denial of service against everyone else: the
 * cap is global, and once it is reached the service answers 503 for all accounts. Worth knowing
 * before the complaints arrive.
 */
async function hogsTheBudget(env: Env, ctx: ExecutionContext, sharePercent: number): Promise<number> {
  // The ceiling as it currently stands, which may have been moved from the dashboard — comparing a
  // day's spend against the deployment's figure would misreport the share the moment it is changed.
  const budgetNano = Math.round((await alertSettings(env)).dailyBudgetUsd * NANO_PER_USD);
  const day = today();
  const dayStart = Date.parse(`${day}T00:00:00Z`);
  const trigger = (budgetNano * sharePercent) / 100;

  const rows = await env.DB.prepare(
    `SELECT u.wallet_id AS walletId, SUM(u.cost_nano) AS cost
       FROM usage_log u
       JOIN wallets w ON w.id = u.wallet_id AND w.is_test = 0
      WHERE u.ts >= ? GROUP BY u.wallet_id HAVING cost > ?`,
  ).bind(dayStart, trigger).all<{ walletId: string; cost: number }>();

  let raised = 0;
  for (const row of rows.results ?? []) {
    const usd = num(row.cost) / NANO_PER_USD;
    const share = (num(row.cost) / budgetNano) * 100;
    const ok = await raise(env, {
      kind: 'budget_hog',
      severity: 'notice',
      walletId: row.walletId,
      value: share,
      title: `Ein Konto beansprucht ${Math.round(share)} % des Tagesbudgets`,
      detail:
        `Heute bereits ${usd.toFixed(2)} $ von ${(budgetNano / NANO_PER_USD).toFixed(2)} $ ` +
        `durch ein einziges Konto. Das Guthaben ist bezahlt, es entsteht kein Verlust — aber das Budget ist ` +
        `gemeinsam: Wird es erreicht, bekommen alle anderen 503. Wenn das öfter vorkommt, gehört das Tagesbudget ` +
        `hochgesetzt — im Dashboard unter Betrieb, ohne Deployment.`,
      dedupeKey: `budget_hog:${row.walletId}:${day}`,
    }, ctx);
    if (ok) raised++;
  }
  return raised;
}

/** One account, many devices at once — the shape of a token that has been passed on or sold. */
async function sharedToken(env: Env, ctx: ExecutionContext, maxDevices: number): Promise<number> {
  const since = Date.now() - 24 * HOUR_MS;
  const rows = await env.DB.prepare(
    `SELECT u.wallet_id AS walletId, COUNT(DISTINCT u.token_hash) AS devices
       FROM usage_log u
       JOIN wallets w ON w.id = u.wallet_id AND w.is_test = 0
      WHERE u.ts >= ? AND u.token_hash IS NOT NULL
      GROUP BY u.wallet_id HAVING devices > ?`,
  ).bind(since, maxDevices).all<{ walletId: string; devices: number }>();

  let raised = 0;
  for (const row of rows.results ?? []) {
    const ok = await raise(env, {
      kind: 'shared_token',
      severity: 'notice',
      walletId: row.walletId,
      value: num(row.devices),
      title: `${row.devices} Geräte an einem Konto`,
      detail:
        `Innerhalb eines Tages haben ${row.devices} verschiedene Geräte dieses Guthaben benutzt. Ein Kostenrisiko ` +
        `ist das nicht, das Guthaben deckelt es. Es heißt aber, dass jemand seinen Zugang weitergegeben hat — ` +
        `unter „Geräte" auf der Kontoseite lassen sich einzelne abmelden, ohne die anderen zu treffen.`,
      dedupeKey: `shared_token:${row.walletId}:${today()}`,
    }, ctx);
    if (ok) raised++;
  }
  return raised;
}

/**
 * The bottom line turning negative.
 *
 * Deliberately cumulative rather than per day. Credit is prepaid: the money arrives on the day of
 * purchase and the cost falls on every day after, so a daily comparison would be in the red almost
 * always and mean nothing. What matters is the total — everything ever taken in against everything
 * ever spent.
 */
async function overallLoss(env: Env, ctx: ExecutionContext, minLoss: number): Promise<number> {
  const { rate } = await usdRate(env);
  const home = homeCurrency(env);

  const [row, spend] = await Promise.all([
    env.DB.prepare(
      `SELECT COALESCE(SUM(p.revenue_home_micros), 0) AS revenue
         FROM purchases p JOIN wallets w ON w.id = p.wallet_id AND w.is_test = 0
        WHERE p.state = 'granted'`,
    ).first<{ revenue: number }>(),
    // Our own ledger, at list price: there is no invoice endpoint to ask, and the free daily
    // allowance is deliberately not deducted. Both make the cost err upwards, so this rule warns
    // slightly too eagerly rather than slightly too late — the right way round for a loss.
    env.DB.prepare('SELECT COALESCE(SUM(cost_nano), 0) AS costNano FROM daily_totals')
      .first<{ costNano: number }>(),
  ]);

  const revenue = num(row?.revenue) / 1_000_000;
  const cost = (num(spend?.costNano) / NANO_PER_USD) * rate;
  const margin = revenue - cost;

  // A floor, not a sign test. Right at the start there are no sales and a handful of test requests,
  // so a bare "cost > revenue" would be true every single day — and a warning that arrives daily
  // is one that stops being read long before the day it matters.
  if (margin > -minLoss) return 0;

  return (await raise(env, {
    kind: 'overall_loss',
    severity: 'critical',
    value: margin,
    title: `Insgesamt im Minus: ${margin.toFixed(2)} ${home}`,
    detail:
      `Eingenommen wurden bisher ${revenue.toFixed(2)} ${home} (nach Googles Anteil, ohne Testkonten), ` +
      `an Rechenzeit eingekauft ${(num(spend?.costNano) / NANO_PER_USD).toFixed(2)} $ ≈ ${cost.toFixed(2)} ${home}. ` +
      `Am Anfang ist das normal, solange die Testphase mehr kostet als die ersten Verkäufe einbringen. ` +
      `Bleibt es so, stimmt entweder die Kalkulation nicht oder es wurde erstattet, nachdem verbraucht war.`,
    // Once a day at most: this is a state, not an event.
    dedupeKey: `overall_loss:${today()}`,
  }, ctx)) ? 1 : 0;
}

/**
 * A sale that is paid for and still has no revenue figure a week later.
 *
 * Google states the developer's share once the payment settles, which is normally a matter of hours
 * and is why `orders.ts` asks again every hour. Past a week the delay is no longer a delay: a
 * permission missing on the service account, an order Google will not hand over, an assumption that
 * stopped holding. Worth a warning precisely because the failure is so quiet — the books simply read
 * as if that sale earned nothing, which is what happened to the first real one.
 */
const UNREPORTED_AFTER_DAYS = 7;

async function revenueUnreported(env: Env, ctx: ExecutionContext): Promise<number> {
  const cutoff = Date.now() - UNREPORTED_AFTER_DAYS * 24 * HOUR_MS;
  const row = await env.DB.prepare(
    `SELECT COUNT(*) AS n, MIN(p.purchased_at) AS oldest, MAX(p.order_attempts) AS attempts
       FROM purchases p JOIN wallets w ON w.id = p.wallet_id AND w.is_test = 0
      WHERE p.state = 'granted' AND p.purchase_type IS NULL AND p.order_id IS NOT NULL
        AND p.revenue_micros IS NULL AND p.purchased_at < ?`,
  ).bind(cutoff).first<{ n: number; oldest: number; attempts: number }>();

  const open = num(row?.n);
  if (open === 0) return 0;

  const days = Math.floor((Date.now() - num(row?.oldest)) / (24 * HOUR_MS));
  return (await raise(env, {
    kind: 'revenue_unreported',
    // Not critical: no money is being lost this minute, and a mail at three in the morning would
    // not make Google answer any sooner. It belongs in the daily digest.
    severity: 'notice',
    value: open,
    title: `${open} Kauf/Käufe ohne gemeldeten Erlös`,
    detail:
      `Google hat für ${open} bezahlte${open === 1 ? 'n' : ''} Kauf${open === 1 ? '' : 'e'} den ` +
      `Entwickleranteil bis heute nicht gemeldet, der älteste liegt ${days} Tage zurück ` +
      `(${num(row?.attempts)} Abfragen bisher). Normal ist das für ein paar Stunden nach dem Kauf, ` +
      `nicht für eine Woche. In den Büchern sieht so ein Kauf aus, als hätte er nichts eingebracht — ` +
      `deshalb diese Meldung. Zu prüfen: hat das Dienstkonto in der Play Console das Recht ` +
      `„Finanzdaten, Bestellungen und Antworten auf Kündigungsumfragen einsehen"? Im Konto lässt sich ` +
      `die Bestellung mit „neu abfragen" sofort erneut holen, dann steht Googles Antwort im Klartext da.`,
    // A state, not an event: once a day is enough.
    dedupeKey: `revenue_unreported:${today()}`,
  }, ctx)) ? 1 : 0;
}

/** Something upstream is broken. Needs volume, or two failed requests read as a 100 % outage. */
async function errorRate(env: Env, ctx: ExecutionContext, percent: number): Promise<number> {
  const since = Date.now() - HOUR_MS;
  const row = await env.DB.prepare(
    // Refusals are excluded from both sides: they are neither a fault nor evidence that the
    // service is healthy. Counting them as errors would ring this bell every time a handful of
    // accounts ran out of credit at the same time.
    `SELECT COUNT(*) AS total, COALESCE(SUM(CASE WHEN status >= 400 THEN 1 ELSE 0 END), 0) AS errors
       FROM usage_log WHERE ts >= ? AND status NOT IN (402, 403, 429, 503)`,
  ).bind(since).first<{ total: number; errors: number }>();

  const total = num(row?.total);
  const errors = num(row?.errors);
  if (total < 20) return 0;

  const share = (errors / total) * 100;
  if (share < percent) return 0;

  return (await raise(env, {
    kind: 'error_rate',
    severity: 'notice',
    value: share,
    title: `${Math.round(share)} % Fehler in der letzten Stunde`,
    detail:
      `${errors} von ${total} Anfragen sind fehlgeschlagen. Kunden bekommen dann Fehlermeldungen statt Text. ` +
      `Meist ist es eine Störung bei Workers AI; im Verkehrsprotokoll steht, welcher Statuscode zurückkam.`,
    dedupeKey: `error_rate:${new Date().toISOString().slice(0, 13)}`,
  }, ctx)) ? 1 : 0;
}

/**
 * A day whose compute use is unlike the week before it.
 *
 * Neurons are the one figure that turns straight into an invoice, and they can move for reasons
 * that are nobody's fault — a new customer, a stack of long recordings — as well as for reasons
 * that are: a retry loop, a model that quietly started thinking again, another project on the same
 * account eating the allowance. The rule does not try to tell those apart. It says the day is
 * unlike the week, which is the point at which looking is cheap and not looking is not.
 *
 * Both neuron columns are summed, test traffic included, because Cloudflare's allowance is granted
 * to the account and does not care whose request spent it.
 *
 * Two guards against crying wolf. Nothing fires below a floor — going from four neurons to twenty
 * is a factor of five and worth nothing — and nothing fires before there is a week to compare
 * against, since the first days of a service are all spikes by construction.
 */
async function neuronSpike(env: Env, ctx: ExecutionContext, factor: number): Promise<number> {
  const day = today();
  const rows = (await env.DB.prepare(
    `SELECT day, neurons_micro + test_neurons_micro AS neuronsMicro
       FROM daily_totals WHERE day <= ? ORDER BY day DESC LIMIT 8`,
  ).bind(day).all<{ day: string; neuronsMicro: number }>()).results ?? [];

  const todayMicro = num(rows.find((r) => r.day === day)?.neuronsMicro);
  const before = rows.filter((r) => r.day < day);
  if (before.length < 5) return 0;

  const avgMicro = before.reduce((sum, r) => sum + num(r.neuronsMicro), 0) / before.length;
  // A tenth of the free allowance. Below that the whole day still costs nothing at all, and a
  // multiple of nothing is not news.
  const floorMicro = 1_000 * 1_000_000;
  if (todayMicro < floorMicro || avgMicro <= 0) return 0;
  if (todayMicro < avgMicro * factor) return 0;

  const neurons = Math.round(todayMicro / 1_000_000);
  const avg = Math.round(avgMicro / 1_000_000);
  const billedUsd = billedNanoForDay(todayMicro) / NANO_PER_USD;
  return (await raise(env, {
    kind: 'neuron_spike',
    severity: 'notice',
    value: todayMicro / avgMicro,
    title: `${neurons.toLocaleString('de-DE')} Neuronen heute — ${(todayMicro / avgMicro).toFixed(1)}× der Wochenschnitt`,
    detail:
      `Der Schnitt der letzten ${before.length} Tage liegt bei ${avg.toLocaleString('de-DE')}. ` +
      `Berechnet werden für heute bisher ${billedUsd.toFixed(4)} $ — alles über ${FREE_NEURONS_PER_DAY.toLocaleString('de-DE')} ` +
      `Neuronen am Tag kostet. Harmlos, wenn jemand viel diktiert hat; nachsehen lohnt trotzdem, ` +
      `weil dieselbe Kurve entsteht, wenn ein Modell wieder nachdenkt oder etwas in eine Schleife läuft.`,
    // A state of the day, not an event: once per day is enough.
    dedupeKey: `neuron_spike:${day}`,
  }, ctx)) ? 1 : 0;
}

/**
 * Short dictations that suddenly take a long time.
 *
 * Measured against **short recordings only** — thirty seconds of audio or less — and that
 * restriction is the whole idea. A ten-minute recording legitimately takes thirty to fifty seconds
 * on Workers AI, and the spread between one run and the next is wider than any threshold worth
 * setting; a rule over all requests would either shout at every long dictation or never fire at
 * all. Under thirty seconds the answer comes back in two to four, and 89 % of real dictations are
 * in that group — so a p95 of ten seconds there is unambiguous, and it is the number the people
 * using the service actually feel.
 *
 * Latency is the one thing the move to Workers AI made worse and nothing else watches. The error
 * rate stays flat while a service crawls, and a customer noticing before the operator does is the
 * failure this exists to prevent.
 */
async function slowUpstream(env: Env, ctx: ExecutionContext, thresholdMs: number): Promise<number> {
  if (thresholdMs <= 0) return 0;
  const since = Date.now() - 3_600_000;

  const counted = await env.DB.prepare(
    `SELECT COUNT(*) AS n FROM usage_log
      WHERE ts >= ? AND kind = 'transcribe' AND ms IS NOT NULL AND seconds > 0 AND seconds <= 30
        AND status = 200`,
  ).bind(since).first<{ n: number }>();
  const n = num(counted?.n);
  // Four is not a sample, and a single slow request is not a trend. Below this the rule says
  // nothing rather than something it cannot support.
  if (n < 5) return 0;

  const offset = Math.min(n - 1, Math.floor(n * 0.95));
  const row = await env.DB.prepare(
    `SELECT ms FROM usage_log
      WHERE ts >= ? AND kind = 'transcribe' AND ms IS NOT NULL AND seconds > 0 AND seconds <= 30
        AND status = 200
      ORDER BY ms ASC LIMIT 1 OFFSET ?`,
  ).bind(since, offset).first<{ ms: number }>();
  const p95 = num(row?.ms);
  if (p95 <= thresholdMs) return 0;

  return (await raise(env, {
    kind: 'slow_upstream',
    severity: 'notice',
    value: p95,
    title: `Kurze Diktate brauchen ${(p95 / 1000).toFixed(1)} s`,
    detail:
      `Das p95 kurzer Aufnahmen (bis 30 s Audio) liegt in der letzten Stunde bei ${p95} ms, über der ` +
      `Schwelle von ${thresholdMs} ms — gemessen an ${n} Anfragen. Normal sind zwei bis vier Sekunden. ` +
      `Lange Aufnahmen sind hier absichtlich nicht mitgezählt: Die schwanken bei Workers AI von Haus ` +
      `aus zu stark, um eine Schwelle zu tragen. Wenn diese Zahl steigt, merken es die Nutzenden.`,
    dedupeKey: `slow_upstream:${new Date().toISOString().slice(0, 13)}`,
  }, ctx)) ? 1 : 0;
}

/**
 * A finished month whose Cloudflare invoice has not been entered.
 *
 * Before the move, a rule compared our arithmetic against the provider's own billing figures every
 * day. Workers AI has no billing endpoint, so that comparison is now a monthly one, done by hand —
 * and a check that depends on remembering is a check that stops happening. This is the reminder.
 *
 * Two weeks after a month ends, not one day: Cloudflare does not invoice on the first, and an alarm
 * that fires before the thing it asks for exists teaches you to ignore it.
 */
async function invoiceMissing(env: Env, ctx: ExecutionContext): Promise<number> {
  const now = new Date();
  // The month that ended most recently, and only once a fortnight has passed inside the new one.
  if (now.getUTCDate() < 15) return 0;
  const previous = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1))
    .toISOString().slice(0, 7);

  // Nothing was bought, so there is nothing to reconcile.
  const used = await env.DB.prepare(
    'SELECT COUNT(*) AS n FROM daily_totals WHERE day LIKE ?',
  ).bind(`${previous}%`).first<{ n: number }>();
  if (num(used?.n) === 0) return 0;

  const invoice = await env.DB.prepare(
    `SELECT COUNT(*) AS n FROM expenses
      WHERE kind = 'cloudflare' AND strftime('%Y-%m', paid_at / 1000, 'unixepoch') = ?`,
  ).bind(previous).first<{ n: number }>();
  if (num(invoice?.n) > 0) return 0;

  return (await raise(env, {
    kind: 'invoice_missing',
    severity: 'notice',
    value: 0,
    title: `Für ${previous} ist keine Cloudflare-Rechnung erfasst`,
    detail:
      `Der Monat ist abgeschlossen und hat Verkehr, aber unter Steuer → Ausgaben steht keine Rechnung ` +
      `dafür. Das ist die einzige Prüfung gegen echtes Geld, die es seit dem Umzug noch gibt: Alles ` +
      `andere auf dem Dashboard ist die eigene Rechnung. Eintragen, dann steht die Differenz im ` +
      `Abgleich.`,
    // Einmal je Monat, nicht einmal je Viertelstunde bis zum Eintrag.
    dedupeKey: `invoice_missing:${previous}`,
  }, ctx)) ? 1 : 0;
}
