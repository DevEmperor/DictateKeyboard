import {
  FREE_NEURONS_PER_DAY, NANO_PER_NEURON, NEURONS, PACKAGES, PLAY_SERVICE_FEE, billedNanoForDay,
  type Env,
} from '../config';
import { guardStub, walletStub } from '../meter';
import { alertSettings } from '../settings';
import { num, today } from '../util';
import { REAL_SALES } from './finance';

/**
 * Everything the dashboard reads. Queries only — the mutations live in `actions.ts`.
 *
 * One number here deserves its prominence: **outstanding credit**. Minutes that were paid for and
 * not yet used are a liability, not revenue — they are work still owed. It is the figure a shop
 * like this most easily flatters itself by leaving out, so it sits at the top.
 *
 * Two things that used to be wrong and are worth naming, because the old numbers are still in
 * screenshots. Revenue was summed from `price_eur`, the German list price, and labelled as
 * takings — while the finance panel two cards away showed what Google actually paid out. Two
 * different answers to one question on one screen. And test accounts counted as customers. Both
 * are gone: every figure below comes from the Orders API through `finance.ts`, and everything
 * excludes accounts marked as yours.
 */

const NANO_PER_USD = 1_000_000_000;

export async function overview(env: Env) {
  const day = today();
  const monthPrefix = day.slice(0, 7);
  const monthStartMs = Date.parse(`${monthPrefix}-01T00:00:00Z`);
  const dayStartMs = Date.parse(`${day}T00:00:00Z`);
  const now = Date.now();

  // Sales are counted in the payout currency from the rate frozen onto each purchase — never from
  // the list price, which is what we ask for rather than what anyone paid.
  const salesSince = (since: number) => env.DB.prepare(
    `SELECT COUNT(*) AS count, COALESCE(SUM(p.revenue_home_micros), 0) AS revenueMicros
       FROM purchases p WHERE ${REAL_SALES} AND p.purchased_at >= ?`,
  ).bind(since).first<{ count: number; revenueMicros: number }>();

  const [byPackRows, salesToday, salesMonth, liability, wallets, totals, todayRow, recent, alerts] =
    await Promise.all([
      env.DB.prepare(
        `SELECT p.product_id AS productId, COUNT(*) AS count,
                COALESCE(SUM(p.revenue_home_micros), 0) AS revenueMicros
           FROM purchases p WHERE ${REAL_SALES} GROUP BY p.product_id`,
      ).all<{ productId: string; count: number; revenueMicros: number }>(),
      salesSince(dayStartMs),
      salesSince(monthStartMs),
      // Offenes Guthaben, und daneben wie alt es ist.
      //
      // Die Summe allein sagt nur, wie viel Arbeit noch geschuldet wird. Interessant ist, wie viel
      // davon auf Konten liegt, die sich nicht mehr melden: Das ist zugleich die Verbindlichkeit,
      // die vermutlich nie eingelöst wird, und das deutlichste Produktsignal, das dieser Dienst
      // hat — gekauft und aufgehört. `last_seen_at` ist der letzte Kontakt, nicht der letzte
      // Verbrauch; wer die App offen hat, aber nicht diktiert, zählt hier als aktiv.
      env.DB.prepare(
        `SELECT COALESCE(SUM(seconds_left), 0) AS seconds,
                COALESCE(SUM(CASE WHEN last_seen_at >= ? THEN seconds_left ELSE 0 END), 0) AS fresh,
                COALESCE(SUM(CASE WHEN last_seen_at <  ? THEN seconds_left ELSE 0 END), 0) AS stale,
                COALESCE(SUM(CASE WHEN last_seen_at <  ? AND seconds_left > 0 THEN 1 ELSE 0 END), 0) AS staleWallets
           FROM wallets WHERE status = 'active' AND is_test = 0`,
      ).bind(now - 30 * 86_400_000, now - 30 * 86_400_000, now - 90 * 86_400_000)
        .first<{ seconds: number; fresh: number; stale: number; staleWallets: number }>(),
      // A deleted account is counted as deleted and nowhere else. Its row only still exists
      // because the receipts point at it; leaving it in "accounts" would make the customer count
      // a number that never goes down.
      env.DB.prepare(
        `SELECT
           SUM(CASE WHEN is_test = 0 AND status != 'deleted' THEN 1 ELSE 0 END) AS total,
           SUM(CASE WHEN is_test = 0 AND status != 'deleted' AND last_seen_at >= ? THEN 1 ELSE 0 END) AS active7,
           SUM(CASE WHEN is_test = 0 AND status != 'deleted' AND last_seen_at >= ? THEN 1 ELSE 0 END) AS active30,
           SUM(CASE WHEN is_test = 0 AND status != 'deleted' AND created_at   >= ? THEN 1 ELSE 0 END) AS new30,
           SUM(CASE WHEN is_test = 0 AND status  = 'blocked' THEN 1 ELSE 0 END) AS blocked,
           SUM(CASE WHEN is_test = 0 AND status  = 'deleted' THEN 1 ELSE 0 END) AS deleted,
           SUM(CASE WHEN is_test = 1 AND status != 'deleted' THEN 1 ELSE 0 END) AS test
         FROM wallets`,
      )
        .bind(now - 7 * 86_400_000, now - 30 * 86_400_000, now - 30 * 86_400_000)
        .first<{
          total: number; active7: number; active30: number; new30: number;
          blocked: number; deleted: number; test: number;
        }>(),
      env.DB.prepare(
        `SELECT COALESCE(SUM(cost_nano), 0) AS costNano, COALESCE(SUM(seconds), 0) AS seconds,
                COALESCE(SUM(requests), 0) AS requests, COALESCE(SUM(errors), 0) AS errors,
                COALESCE(SUM(test_requests), 0) AS testRequests,
                COALESCE(SUM(test_cost_nano), 0) AS testCostNano,
                COALESCE(SUM(cost_nano_cf), 0) AS costNanoCf
           FROM daily_totals`,
      ).first<{
        costNano: number; seconds: number; requests: number; errors: number;
        testRequests: number; testCostNano: number; costNanoCf: number;
      }>(),
      env.DB.prepare('SELECT * FROM daily_totals WHERE day = ?').bind(day)
        .first<{ requests: number; seconds: number; cost_nano: number; errors: number }>(),
      env.DB.prepare(
        'SELECT day, requests, seconds, cost_nano AS costNano, errors FROM daily_totals ORDER BY day DESC LIMIT 30',
      ).all<{ day: string; requests: number; seconds: number; costNano: number; errors: number }>(),
      env.DB.prepare(
        `SELECT COUNT(*) AS open,
                COALESCE(SUM(CASE WHEN severity = 'critical' THEN 1 ELSE 0 END), 0) AS critical
           FROM alerts WHERE ack_at IS NULL`,
      ).first<{ open: number; critical: number }>(),
    ]);

  const monthCost = await env.DB.prepare(
    'SELECT COALESCE(SUM(cost_nano), 0) AS costNano FROM daily_totals WHERE day LIKE ?',
  ).bind(`${monthPrefix}%`).first<{ costNano: number }>();

  // How much of the cost figure is a measurement rather than an estimate.
  //
  // Every reply from Workers AI carries the neurons it spent, and that number is what gets booked —
  // the estimate exists only to reserve budget before the request goes out, and is replaced on the
  // way back. But it is *only* replaced when a figure comes back: a reply without `usage.neurons`
  // leaves the estimate standing, and nothing on the page would have said so.
  //
  // So it is counted. A cost built entirely from measurements can be presented as exact; one with
  // estimates in it cannot, and the difference belongs on the page rather than in a comment.
  const measured = await env.DB.prepare(
    `SELECT COUNT(*) AS requests,
            COALESCE(SUM(CASE WHEN neurons_micro > 0 THEN 1 ELSE 0 END), 0) AS measured
       FROM usage_log WHERE cost_nano > 0`,
  ).first<{ requests: number; measured: number }>();
  const measuredToday = await env.DB.prepare(
    `SELECT COUNT(*) AS requests,
            COALESCE(SUM(CASE WHEN neurons_micro > 0 THEN 1 ELSE 0 END), 0) AS measured
       FROM usage_log WHERE cost_nano > 0 AND ts >= ?`,
  ).bind(dayStartMs).first<{ requests: number; measured: number }>();

  // Every day, not the last thirty: what Cloudflare bills can only be worked out one day at a time
  // (the free allowance is a daily figure and does not carry over), so a month or a lifetime total
  // is a sum of per-day results and never a calculation on the summed neurons. One row per day —
  // a decade of them is still four thousand rows.
  //
  // Both neuron columns are added together on purpose. The allowance belongs to the account, so
  // your own testing eats it exactly like a customer's request does; keeping test traffic out here
  // would understate what is left of it, which is the one number this section exists to show.
  const neuronDays = (await env.DB.prepare(
    `SELECT day, neurons_micro + test_neurons_micro AS neuronsMicro, cost_nano AS costNano
       FROM daily_totals ORDER BY day DESC`,
  ).all<{ day: string; neuronsMicro: number; costNano: number }>()).results ?? [];

  const neuronsOn = (d: string) => num(neuronDays.find((r) => r.day === d)?.neuronsMicro);
  const billedSum = (rows: typeof neuronDays) =>
    rows.reduce((sum, r) => sum + billedNanoForDay(num(r.neuronsMicro)), 0);
  const yesterday = new Date(dayStartMs - 86_400_000).toISOString().slice(0, 10);
  // The seven days before today, so today is judged against a week it is not part of.
  const lastSeven = neuronDays.filter((r) => r.day < day).slice(0, 7);
  const neuronsToday = neuronsOn(day);
  const freeMicro = FREE_NEURONS_PER_DAY * 1_000_000;

  // The ceiling as it currently stands: the dashboard's figure once one has been set there, the
  // deployment's otherwise. Reading the deployment's here would show a limit the service is not
  // actually applying.
  const budgetNano = Math.round((await alertSettings(env)).dailyBudgetUsd * NANO_PER_USD);
  const guard = await guardStub(env).state(day);

  // Latency is read from the raw log rather than the daily roll-up, because an average hides
  // exactly the cases worth seeing. The 95th percentile is taken by offset, which SQLite does
  // cheaply enough at this scale and exactly.
  const latency = await percentileMs(env, dayStartMs, 0.95);

  const byPack = (byPackRows.results ?? []).map((row) => {
    const pack = PACKAGES[row.productId];
    return {
      productId: row.productId,
      name: pack?.name ?? row.productId,
      count: num(row.count),
      revenue: round2(num(row.revenueMicros) / 1_000_000),
      minutesSold: (pack?.minutes ?? 0) * num(row.count),
    };
  });

  return {
    revenue: {
      /** After Google's cut and tax, converted to the payout currency. See `finance.ts`. */
      today: round2(num(salesToday?.revenueMicros) / 1_000_000),
      month: round2(num(salesMonth?.revenueMicros) / 1_000_000),
      total: round2(byPack.reduce((sum, p) => sum + p.revenue, 0)),
      purchasesToday: num(salesToday?.count),
      purchasesMonth: num(salesMonth?.count),
      byPack,
    },
    /**
     * Two figures for the same thing, and both are needed.
     *
     * `…Usd` is the list price: what the traffic cost before Cloudflare's daily allowance is taken
     * off. It is what the margin is calculated against, and it errs upwards, which is the right
     * direction for a cost.
     *
     * `billed…Usd` is what lands on the invoice. On most days it is zero, and then it jumps. Shown
     * beside the list price rather than alone, because a tile that reads 0.00 $ for a week and then
     * suddenly does not looks like a fault instead of a day that stayed under the allowance.
     */
    cost: {
      todayUsd: round6(num(todayRow?.cost_nano) / NANO_PER_USD),
      monthUsd: round6(num(monthCost?.costNano) / NANO_PER_USD),
      totalUsd: round6(num(totals?.costNano) / NANO_PER_USD),
      billedTodayUsd: round6(billedNanoForDay(neuronsToday) / NANO_PER_USD),
      billedMonthUsd: round6(billedSum(neuronDays.filter((r) => r.day.startsWith(monthPrefix))) / NANO_PER_USD),
      billedTotalUsd: round6(billedSum(neuronDays) / NANO_PER_USD),
      /** Your own testing, kept apart so it never quietly inflates the cost of the business. */
      testTotalUsd: round6(num(totals?.testCostNano) / NANO_PER_USD),
      /** The Workers AI share of the lifetime figure — zero until the switch is thrown. */
      workersAiTotalUsd: round6(num(totals?.costNanoCf) / NANO_PER_USD),
      /**
       * Whether the figures above are measurements. `estimated` counts the requests whose cost is
       * still the reservation estimate because the reply carried no neuron count — the only reason
       * any number here would not be exact.
       */
      measuredRequests: num(measured?.measured),
      estimatedRequests: num(measured?.requests) - num(measured?.measured),
      measuredToday: num(measuredToday?.measured),
      estimatedToday: num(measuredToday?.requests) - num(measuredToday?.measured),
    },
    /**
     * The free allowance, as a day that is being used up rather than a fact about the plan.
     *
     * Reset is 00:00 **UTC** — 02:00 German summer time, 01:00 in winter. Handed to the page as a
     * timestamp rather than a formatted time, so it can be shown as a remaining span: "in 6 h 12 min"
     * is the one form nobody can misread, and a date would have to be read twice.
     */
    neurons: {
      today: Math.round(neuronsToday / 1_000_000),
      yesterday: Math.round(neuronsOn(yesterday) / 1_000_000),
      avg7: lastSeven.length
        ? Math.round(lastSeven.reduce((s, r) => s + num(r.neuronsMicro), 0) / lastSeven.length / 1_000_000)
        : 0,
      month: Math.round(
        neuronDays.filter((r) => r.day.startsWith(monthPrefix))
          .reduce((s, r) => s + num(r.neuronsMicro), 0) / 1_000_000,
      ),
      total: Math.round(neuronDays.reduce((s, r) => s + num(r.neuronsMicro), 0) / 1_000_000),
      freePerDay: FREE_NEURONS_PER_DAY,
      /** Can exceed 100: past the allowance is a normal day, not an error. */
      freeUsedPercent: Math.round((neuronsToday / freeMicro) * 100),
      /** What the allowance is worth if it is used up — the ceiling on what it can ever save. */
      freeValueUsd: round6((FREE_NEURONS_PER_DAY * NANO_PER_NEURON) / NANO_PER_USD),
      /** Only meaningful for speech: mixed traffic has no single second unit. */
      freeAudioMinutes: Math.round(FREE_NEURONS_PER_DAY / NEURONS['@cf/openai/whisper-large-v3-turbo'].perAudioMinute),
      resetAtMs: dayStartMs + 86_400_000,
    },
    /** Minutes paid for and not yet used — money owed as work, not earned. */
    liability: {
      seconds: num(liability?.seconds),
      minutes: Math.floor(num(liability?.seconds) / 60),
      /** On accounts seen in the last 30 days — credit that is plausibly still going to be used. */
      freshSeconds: num(liability?.fresh),
      /** On accounts that have not been seen for 30 days. */
      staleSeconds: num(liability?.stale),
      /** How many accounts hold credit and have not been seen for 90 days. */
      dormantWallets: num(liability?.staleWallets),
    },
    wallets: {
      total: num(wallets?.total),
      active7: num(wallets?.active7),
      active30: num(wallets?.active30),
      new30: num(wallets?.new30),
      blocked: num(wallets?.blocked),
      deleted: num(wallets?.deleted),
      test: num(wallets?.test),
    },
    usage: {
      requestsToday: num(todayRow?.requests),
      secondsToday: num(todayRow?.seconds),
      errorsToday: num(todayRow?.errors),
      requestsTotal: num(totals?.requests),
      secondsTotal: num(totals?.seconds),
      testRequestsTotal: num(totals?.testRequests),
      p95Ms: latency,
    },
    budget: {
      spentUsd: round6(guard.spentNano / NANO_PER_USD),
      // The ceiling in force, which is the dashboard's figure once one has been set there.
      limitUsd: round2(budgetNano / NANO_PER_USD),
      usedPercent: budgetNano > 0 ? Math.round((guard.spentNano / budgetNano) * 100) : 0,
      killed: guard.killed,
    },
    alerts: { open: num(alerts?.open), critical: num(alerts?.critical) },
    days: recent.results ?? [],
  };
}

/** Wallet list, optionally narrowed by a search term. */
export async function wallets(env: Env, query: string, includeTest = false, includeDeleted = false) {
  const q = query.trim();
  const columns = `w.id, w.created_at AS createdAt, w.status, w.seconds_left AS secondsLeft,
            w.seconds_bought AS secondsBought, w.seconds_used AS secondsUsed,
            w.last_seen_at AS lastSeenAt, w.deleted_at AS deletedAt,
            w.note, w.is_test AS isTest, w.test_reason AS testReason`;

  if (!q) {
    // Deleted accounts are out by default. Their rows survive only because the receipts reference
    // them, so leaving them in the list would mean it never gets shorter — but they are one tick
    // away, because "what happened to that account" is a question that gets asked.
    const where = [
      includeTest ? '' : 'w.is_test = 0',
      includeDeleted ? '' : "w.status != 'deleted'",
    ].filter(Boolean);
    const rows = await env.DB.prepare(
      `SELECT ${columns} FROM wallets w ${where.length ? `WHERE ${where.join(' AND ')}` : ''}
        ORDER BY COALESCE(w.last_seen_at, w.created_at) DESC LIMIT 100`,
    ).all();
    return rows.results ?? [];
  }

  // One box, four kinds of needle. Support asks arrive as whichever identifier the person happens
  // to have — a wallet id from the app, a recovery code off a scrap of paper, a Play order number
  // from an email — and making the helper pick the right field first is friction for no reason.
  //
  // A search always looks through test accounts too: if you are typing an id, you want that
  // account, not a lecture about which bucket it is in.
  const like = `%${q}%`;
  const rows = await env.DB.prepare(
    `SELECT ${columns}
       FROM wallets w
      WHERE (w.id LIKE ?
         OR w.recovery_hash = ?
         OR EXISTS (SELECT 1 FROM purchases p WHERE p.wallet_id = w.id
                      AND (p.order_id LIKE ? OR p.purchase_token LIKE ?)))
      ORDER BY COALESCE(w.last_seen_at, w.created_at) DESC LIMIT 100`,
  )
    .bind(like, await recoveryHash(q), like, like)
    .all();
  return rows.results ?? [];
}

/** Everything about one account. */
export async function walletDetail(env: Env, id: string) {
  const row = await env.DB.prepare(
    `SELECT id, created_at AS createdAt, status, seconds_left AS secondsLeft,
            rewords_left AS rewordsLeft, seconds_bought AS secondsBought,
            seconds_used AS secondsUsed, last_seen_at AS lastSeenAt, deleted_at AS deletedAt,
            note, is_test AS isTest, test_reason AS testReason
       FROM wallets WHERE id = ?`,
  ).bind(id).first();
  if (!row) return null;

  const [purchases, devices, daily, errors, admin, alerts] = await Promise.all([
    env.DB.prepare(
      `SELECT purchase_token AS purchaseToken, product_id AS productId, order_id AS orderId,
              seconds, price_eur AS priceEur, region_code AS regionCode,
              paid_micros AS paidMicros, tax_micros AS taxMicros, revenue_micros AS revenueMicros,
              revenue_home_micros AS revenueHomeMicros, currency, purchase_type AS purchaseType,
              purchased_at AS purchasedAt, state,
              order_state AS orderState, order_synced_at AS orderSyncedAt,
              order_attempts AS orderAttempts,
              -- What the sale is worth if the usual arithmetic holds, for the rows Google has not
              -- accounted for yet. Worked out here so the page never does money arithmetic of its
              -- own, and null wherever it cannot be said honestly.
              CASE WHEN revenue_micros IS NULL AND paid_micros IS NOT NULL AND fx_rate IS NOT NULL
                   THEN CAST((paid_micros - COALESCE(tax_micros, 0)) * ? * fx_rate AS INTEGER)
              END AS estimatedHomeMicros
         FROM purchases WHERE wallet_id = ? ORDER BY purchased_at DESC`,
    ).bind(1 - PLAY_SERVICE_FEE, id).all(),
    env.DB.prepare(
      `SELECT token_hash AS tokenHash, created_at AS createdAt, last_seen_at AS lastSeenAt,
              label, revoked_at AS revokedAt
         FROM tokens WHERE wallet_id = ? ORDER BY COALESCE(last_seen_at, created_at) DESC`,
    ).bind(id).all(),
    env.DB.prepare(
      `SELECT date(ts / 1000, 'unixepoch') AS day,
              SUM(CASE WHEN kind = 'transcribe' THEN seconds ELSE 0 END) AS dictationSeconds,
              SUM(CASE WHEN kind = 'reword' THEN 1 ELSE 0 END) AS rewords,
              COUNT(*) AS requests,
              SUM(cost_nano) AS costNano
         FROM usage_log WHERE wallet_id = ?
        GROUP BY day ORDER BY day DESC LIMIT 30`,
    ).bind(id).all(),
    env.DB.prepare(
      `SELECT ts, kind, status, ms FROM usage_log
        WHERE wallet_id = ? AND status >= 400 ORDER BY ts DESC LIMIT 25`,
    ).bind(id).all(),
    env.DB.prepare(
      `SELECT ts, actor, action, delta_secs AS deltaSecs, note
         FROM admin_log WHERE wallet_id = ? ORDER BY ts DESC LIMIT 25`,
    ).bind(id).all(),
    env.DB.prepare(
      `SELECT id, ts, kind, severity, title, detail, ack_at AS ackAt
         FROM alerts WHERE wallet_id = ? ORDER BY ts DESC LIMIT 10`,
    ).bind(id).all(),
  ]);

  // The authoritative balance lives in the Durable Object; the D1 columns are a copy that may lag
  // by seconds. On a single account it costs nothing to ask the object itself and be exact.
  const live = await walletStub(env, id).state().catch(() => null);

  return {
    wallet: row,
    live,
    purchases: purchases.results ?? [],
    devices: devices.results ?? [],
    daily: daily.results ?? [],
    errors: errors.results ?? [],
    adminLog: admin.results ?? [],
    alerts: alerts.results ?? [],
  };
}

/**
 * Recent traffic across all accounts — metadata only, as everywhere.
 *
 * Paged rather than capped at some round number: a cap quietly decides for the reader that older
 * traffic does not matter, and the moment it does, there is no way to reach it. Filters are applied
 * in SQL so paging stays honest — narrowing to failures and then paging must walk the failures, not
 * a page of everything that happens to contain a few.
 */
export async function recentRequests(
  env: Env,
  { limit = 50, offset = 0, kind = '', failuresOnly = false, walletId = '', includeTest = true } = {},
) {
  const where: string[] = [];
  const args: unknown[] = [];
  if (kind) { where.push('u.kind = ?'); args.push(kind); }
  if (failuresOnly) where.push('u.status >= 400');
  if (walletId) { where.push('u.wallet_id = ?'); args.push(walletId); }
  if (!includeTest) where.push('COALESCE(w.is_test, 0) = 0');
  const clause = where.length ? `WHERE ${where.join(' AND ')}` : '';

  // Counting and listing go through the same join and the same clause, so the pager can never
  // promise a page that the list will not produce.
  const from = `FROM usage_log u LEFT JOIN wallets w ON w.id = u.wallet_id`;

  const [total, rows] = await Promise.all([
    env.DB.prepare(`SELECT COUNT(*) AS n ${from} ${clause}`).bind(...args).first<{ n: number }>(),
    env.DB.prepare(
      `SELECT u.id, u.wallet_id AS walletId, u.ts, u.kind, u.seconds,
              u.tokens_in AS tokensIn, u.tokens_out AS tokensOut, u.cost_nano AS costNano,
              -- Null on rows written before migration 006, which is the honest answer for them:
              -- "not recorded then" is a different thing from a name filled in afterwards.
              u.provider, u.model, u.neurons_micro AS neuronsMicro,
              u.status, u.ms, COALESCE(t.label, '') AS device, COALESCE(w.is_test, 0) AS isTest
         ${from} LEFT JOIN tokens t ON t.token_hash = u.token_hash
         ${clause}
        ORDER BY u.ts DESC LIMIT ? OFFSET ?`,
    ).bind(...args, limit, offset).all(),
  ]);

  return { total: num(total?.n), limit, offset, requests: rows.results ?? [] };
}

/** The audit trail of everything done from this dashboard. */
export async function adminLog(env: Env, { limit = 50, offset = 0 } = {}) {
  const total = await env.DB.prepare('SELECT COUNT(*) AS n FROM admin_log').first<{ n: number }>();
  const rows = await env.DB.prepare(
    `SELECT id, ts, actor, wallet_id AS walletId, action, delta_secs AS deltaSecs,
            delta_words AS deltaWords, note
       FROM admin_log ORDER BY ts DESC LIMIT ? OFFSET ?`,
  ).bind(limit, offset).all();
  return { total: num(total?.n), limit, offset, log: rows.results ?? [] };
}

async function percentileMs(env: Env, sinceMs: number, p: number): Promise<number | null> {
  const count = await env.DB.prepare(
    'SELECT COUNT(*) AS n FROM usage_log WHERE ts >= ? AND ms IS NOT NULL',
  ).bind(sinceMs).first<{ n: number }>();
  const n = num(count?.n);
  if (n === 0) return null;
  const offset = Math.min(n - 1, Math.floor(n * p));
  const row = await env.DB.prepare(
    'SELECT ms FROM usage_log WHERE ts >= ? AND ms IS NOT NULL ORDER BY ms ASC LIMIT 1 OFFSET ?',
  ).bind(sinceMs, offset).first<{ ms: number }>();
  return row?.ms ?? null;
}

async function recoveryHash(code: string): Promise<string> {
  const { normalizeRecoveryCode, sha256 } = await import('../util');
  const normalized = normalizeRecoveryCode(code);
  return normalized ? await sha256(normalized) : '';
}

function round2(v: number): number {
  return Math.round(v * 100) / 100;
}

function round6(v: number): number {
  return Math.round(v * 1_000_000) / 1_000_000;
}
