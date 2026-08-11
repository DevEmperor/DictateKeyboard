import { raise } from '../alerts';
import { limitsFrom, type Env } from '../config';
import { num, openaiCosts } from '../costs';
import { homeCurrency, usdRate } from '../fx';
import { alertSettings } from '../settings';
import { today } from '../util';

/**
 * The watchdog, run every quarter of an hour against the ledger.
 *
 * What is deliberately **not** in here: high consumption. A customer cannot dictate you into a
 * loss — the balance is debited before OpenAI is called, and even the largest pack consumed to the
 * last second leaves a healthy margin. Alerting on heavy use would train you to ignore the mails
 * that matter.
 *
 * What is in here are the four shapes that actually cost money or hide a problem: credit spent
 * suspiciously fast (the run-up to a refund), one account starving the shared daily budget,
 * a token being passed around, and OpenAI's invoice drifting away from our own price list.
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
    on('cost_drift', () => costDrift(env, ctx, t.costDriftPercent)),
    on('overall_loss', () => overallLoss(env, ctx, t.minLossHome)),
    on('error_rate', () => errorRate(env, ctx, t.errorRatePercent)),
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
 * OpenAI's invoice drifting away from our own price list.
 *
 * The quiet one. Everything else in this service calculates cost from the numbers in `config.ts`,
 * so if OpenAI raises a price, the calculation carries on agreeing with itself while the margin
 * disappears. Comparing yesterday against OpenAI's own figure is the only way that ever surfaces.
 */
async function costDrift(env: Env, ctx: ExecutionContext, percent: number): Promise<number> {
  const costs = await openaiCosts(env, 7, ctx);
  if (!costs.connected || costs.serviceUsd === null) return 0;

  const yesterday = new Date(Date.now() - 86_400_000).toISOString().slice(0, 10);
  const theirs = costs.days.find((d) => d.day === yesterday)?.usd ?? 0;

  const row = await env.DB.prepare('SELECT cost_nano AS costNano FROM daily_totals WHERE day = ?')
    .bind(yesterday).first<{ costNano: number }>();
  const ours = num(row?.costNano) / NANO_PER_USD;

  // Below a few cents the percentage is noise: one long dictation moves it by half.
  if (ours < 0.1 || theirs < 0.1) return 0;

  const drift = ((theirs - ours) / ours) * 100;
  if (Math.abs(drift) < percent) return 0;

  return (await raise(env, {
    kind: 'cost_drift',
    severity: 'critical',
    value: drift,
    title: `OpenAI rechnet ${drift > 0 ? 'mehr' : 'weniger'} ab als kalkuliert (${Math.round(drift)} %)`,
    detail:
      `Für ${yesterday} weist OpenAI ${theirs.toFixed(4)} $ aus, unsere eigene Rechnung kommt auf ` +
      `${ours.toFixed(4)} $. Die Preise in config.ts stimmen dann nicht mehr mit der Wirklichkeit überein — ` +
      `bei einer Erhöhung schrumpft die Marge, ohne dass irgendeine Zahl im Dashboard sich verändert. ` +
      `Preisliste bei OpenAI prüfen und COST in config.ts nachziehen.`,
    dedupeKey: `cost_drift:${yesterday}`,
  }, ctx)) ? 1 : 0;
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
  const costs = await openaiCosts(env, 180, ctx);
  if (!costs.connected || costs.serviceUsd === null) return 0;

  const { rate } = await usdRate(env);
  const home = homeCurrency(env);

  const row = await env.DB.prepare(
    `SELECT COALESCE(SUM(p.revenue_home_micros), 0) AS revenue
       FROM purchases p JOIN wallets w ON w.id = p.wallet_id AND w.is_test = 0
      WHERE p.state = 'granted'`,
  ).first<{ revenue: number }>();

  const revenue = num(row?.revenue) / 1_000_000;
  const cost = costs.serviceUsd * rate;
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
      `bei OpenAI ausgegeben ${costs.serviceUsd.toFixed(2)} $ ≈ ${cost.toFixed(2)} ${home}. ` +
      `Am Anfang ist das normal, solange die Testphase mehr kostet als die ersten Verkäufe einbringen. ` +
      `Bleibt es so, stimmt entweder die Kalkulation nicht oder es wurde erstattet, nachdem verbraucht war.`,
    // Once a day at most: this is a state, not an event.
    dedupeKey: `overall_loss:${today()}`,
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
      `Meist ist es eine Störung bei OpenAI; im Verkehrsprotokoll steht, welcher Statuscode zurückkam.`,
    dedupeKey: `error_rate:${new Date().toISOString().slice(0, 13)}`,
  }, ctx)) ? 1 : 0;
}
