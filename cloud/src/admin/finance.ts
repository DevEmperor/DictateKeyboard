import {
  COST, PACKAGES, PLAY_SERVICE_FEE, TYPICAL_REWORD_SECONDS, chatCostNano, limitsFrom,
  savingsPercent, type Env,
} from '../config';
import { num, openaiCosts } from '../costs';
import { homeCurrency, usdRate } from '../fx';

/**
 * The money, from the sources that actually hold it.
 *
 * Everything else in this dashboard is derived from our own ledger, which is fine for counting
 * requests and seconds but wrong for counting money: the list price in `config.ts` is what we ask
 * for, not what Play collects (it converts per country and adds local tax) and certainly not what
 * Play pays out (it keeps a share). So the takings come from Google's Orders API, stored per
 * purchase, and the spend comes from OpenAI's own cost endpoint.
 *
 * **Currencies are added up now, and that is a change.** Until recently only the euro row counted
 * towards the profit and a sale in francs was quietly worth nothing. Each purchase now carries the
 * ECB rate of the day it happened, written once and never recomputed — so the total is a sum of
 * fixed figures rather than a number that moves with the market. It is still an estimate against
 * Google's payout, which converts at Google's own rate, and every view says so.
 *
 * **Test purchases are excluded everywhere.** A licence tester's order carries the nominal price
 * with zero tax and zero revenue: counted in, it inflates the order count and drags every average
 * down. Counted separately instead.
 */

const NANO_PER_USD = 1_000_000_000;
const MICROS = 1_000_000;

/** Repeated in every money query. Written once so no view can quietly forget it. */
const REAL_SALES = `p.state = 'granted' AND (p.purchase_type IS NULL OR p.purchase_type != 0)
  AND EXISTS (SELECT 1 FROM wallets w WHERE w.id = p.wallet_id AND w.is_test = 0)`;

export interface CurrencyTotals {
  currency: string;
  paid: number;      // what customers paid, incl. tax
  paidHome: number;  // the same, converted with the rate of the purchase day
  tax: number;
  revenue: number;   // what reaches you after Google's cut, in the buyer's currency
  revenueHome: number; // the same, converted with the rate of the purchase day
  orders: number;
  /** Orders Google has taken payment for but not yet stated your share of. */
  unreported: number;
  /** What those orders are worth if the usual arithmetic holds. An estimate, kept apart. */
  estimatedHome: number;
}

export async function playRevenue(env: Env, sinceMs = 0) {
  const home = homeCurrency(env);

  // The rate is read from the purchase rather than from today's table, and a sale in the payout
  // currency needs none — that is the `CASE`. Everything else is summed off it, so the gross figure
  // no longer borrows the revenue's conversion: it used to be derived from the ratio
  // `revenueHome / revenue`, which quietly became zero the moment a revenue figure was missing, and
  // the overview then claimed the customer had paid nothing at all.
  const rows = await env.DB.prepare(
    `WITH sales AS (
       SELECT p.currency AS currency, p.paid_micros AS paid, p.tax_micros AS tax,
              p.revenue_micros AS revenue, p.revenue_home_micros AS revenueHome,
              COALESCE(p.fx_rate, CASE WHEN p.currency = ? THEN 1.0 END) AS rate
         FROM purchases p
        WHERE ${REAL_SALES} AND p.purchased_at >= ? AND p.currency IS NOT NULL
     )
     SELECT currency,
            SUM(paid)        AS paid,
            SUM(tax)         AS tax,
            SUM(revenue)     AS revenue,
            SUM(revenueHome) AS revenueHome,
            COALESCE(SUM(CASE WHEN rate IS NULL THEN 0 ELSE paid * rate END), 0) AS paidHome,
            COUNT(*)         AS orders,
            SUM(CASE WHEN revenue IS NULL THEN 1 ELSE 0 END) AS unreported,
            COALESCE(SUM(CASE WHEN revenue IS NULL AND rate IS NOT NULL
                              THEN (paid - COALESCE(tax, 0)) * ? * rate ELSE 0 END), 0) AS estimatedHome
       FROM sales GROUP BY currency ORDER BY revenueHome DESC`,
  ).bind(home, sinceMs, 1 - PLAY_SERVICE_FEE).all<{
    currency: string; paid: number; paidHome: number; tax: number; revenue: number;
    revenueHome: number; orders: number; unreported: number; estimatedHome: number;
  }>();

  const [testOrders, missing, unconverted] = await Promise.all([
    env.DB.prepare(
      `SELECT COUNT(*) AS n FROM purchases p WHERE p.state = 'granted' AND p.purchased_at >= ?
         AND (p.purchase_type = 0 OR EXISTS (SELECT 1 FROM wallets w WHERE w.id = p.wallet_id AND w.is_test = 1))`,
    ).bind(sinceMs).first<{ n: number }>(),
    // How many purchases carry no real figures — older rows, or an order Google would not hand
    // over. Reported rather than hidden: a revenue view that silently omits sales is worse than
    // one that says how many it omitted.
    env.DB.prepare(
      `SELECT COUNT(*) AS n FROM purchases p WHERE ${REAL_SALES} AND p.purchased_at >= ? AND p.currency IS NULL`,
    ).bind(sinceMs).first<{ n: number }>(),
    // And how many have figures but no rate yet, so the total is knowingly short by that much.
    env.DB.prepare(
      `SELECT COUNT(*) AS n FROM purchases p WHERE ${REAL_SALES} AND p.purchased_at >= ?
         AND p.revenue_micros IS NOT NULL AND p.revenue_home_micros IS NULL`,
    ).bind(sinceMs).first<{ n: number }>(),
  ]);

  const byCurrency: CurrencyTotals[] = (rows.results ?? []).map((r) => ({
    currency: r.currency,
    paid: num(r.paid) / MICROS,
    paidHome: num(r.paidHome) / MICROS,
    tax: num(r.tax) / MICROS,
    revenue: num(r.revenue) / MICROS,
    revenueHome: num(r.revenueHome) / MICROS,
    orders: num(r.orders),
    unreported: num(r.unreported),
    estimatedHome: num(r.estimatedHome) / MICROS,
  }));

  return {
    byCurrency,
    homeCurrency: home,
    /** Every currency brought into one figure — the only total that is a total. */
    revenueHomeTotal: byCurrency.reduce((sum, c) => sum + c.revenueHome, 0),
    paidHomeTotal: byCurrency.reduce((sum, c) => sum + c.paidHome, 0),
    orders: byCurrency.reduce((sum, c) => sum + c.orders, 0),
    /**
     * Sales Google has taken money for but not yet stated a developer share of, and what they would
     * come to. Deliberately its own pair of figures: added into the revenue it would be a guess
     * wearing the clothes of a measurement, and left out entirely it would read as nothing earned —
     * which is exactly the mistake this whole path exists to undo.
     */
    unreportedOrders: byCurrency.reduce((sum, c) => sum + c.unreported, 0),
    revenueEstimatedHome: byCurrency.reduce((sum, c) => sum + c.estimatedHome, 0),
    withoutFigures: num(missing?.n),
    withoutRate: num(unconverted?.n),
    testOrders: num(testOrders?.n),
  };
}

/**
 * The bottom line, in one place so the overview and the statistics view cannot disagree.
 *
 * Both outside figures — Play's developer revenue and OpenAI's billing — are cached, so this is
 * fast after the first call of the ten-minute window. `ctx` lets an expired entry refresh behind
 * the response rather than making someone wait for OpenAI's pagination.
 */
export async function summary(env: Env, ctx?: ExecutionContext) {
  const home = homeCurrency(env);
  const [play, openai, fx] = await Promise.all([
    playRevenue(env),
    openaiCosts(env, 180, ctx),
    usdRate(env),
  ]);

  const revenueHome = play.revenueHomeTotal;
  // Converted per purchase with its own stored rate — see the query in [playRevenue].
  const paidHome = play.paidHomeTotal;

  const costUsd = openai.connected ? openai.serviceUsd : null;
  const costHome = costUsd === null ? null : costUsd * fx.rate;
  const profitHome = costHome === null ? null : revenueHome - costHome;

  return {
    homeCurrency: home,
    rate: fx.rate,
    rateSource: fx.source,
    revenueHome,
    paidHome,
    costUsd,
    costHome,
    profitHome,
    /**
     * The same bottom line with the unaccounted sales counted in at the usual rate. Never shown as
     * *the* figure — shown beside it, so a red month that is only waiting on Google's arithmetic is
     * recognisable as such.
     */
    revenueEstimatedHome: play.revenueEstimatedHome,
    profitWithEstimateHome: costHome === null ? null : revenueHome + play.revenueEstimatedHome - costHome,
    unreportedOrders: play.unreportedOrders,
    orders: play.orders,
    testOrders: play.testOrders,
    withoutFigures: play.withoutFigures,
    withoutRate: play.withoutRate,
    byCurrency: play.byCurrency,
    openaiConnected: openai.connected,
    openaiReason: openai.connected ? null : openai.reason,
    openaiFetchedAt: openai.fetchedAt,
    serviceProject: openai.connected ? openai.serviceProject : null,
  };
}

/** The finance panel: takings per currency and the spend, side by side. */
export async function finance(env: Env, days = 30, ctx?: ExecutionContext) {
  const [play, openai] = await Promise.all([playRevenue(env), openaiCosts(env, days, ctx)]);
  return { play, openai };
}

/**
 * The long view: one row per day, as far back as the roll-ups go.
 *
 * Deliberately built from `daily_totals` and `purchases` rather than from `usage_log`, because the
 * detail log is pruned after 90 days while these two are kept forever. History that quietly stops
 * at the retention boundary would be the wrong kind of surprise.
 */
export async function history(env: Env, days = 365) {
  const sinceMs = Date.now() - days * 86_400_000;

  const [usage, sales, signups] = await Promise.all([
    env.DB.prepare(
      `SELECT day, requests, seconds, rewords, cost_nano AS costNano, errors,
              test_requests AS testRequests, test_cost_nano AS testCostNano
         FROM daily_totals WHERE day >= date('now', ?) ORDER BY day ASC`,
    ).bind(`-${days} days`).all(),
    env.DB.prepare(
      `SELECT date(p.purchased_at / 1000, 'unixepoch') AS day,
              COUNT(*) AS orders,
              SUM(p.seconds) AS secondsSold,
              SUM(COALESCE(p.revenue_home_micros, 0)) AS revenueHomeMicros
         FROM purchases p WHERE ${REAL_SALES} AND p.purchased_at >= ?
        GROUP BY day ORDER BY day ASC`,
    ).bind(sinceMs).all(),
    env.DB.prepare(
      `SELECT date(created_at / 1000, 'unixepoch') AS day, COUNT(*) AS wallets
         FROM wallets WHERE created_at >= ? AND is_test = 0 GROUP BY day ORDER BY day ASC`,
    ).bind(sinceMs).all(),
  ]);

  // Merged into one series so the front end never has to align three arrays by date.
  const merged: Record<string, Record<string, unknown>> = {};
  const touch = (day: string) => (merged[day] = merged[day] || {
    day, requests: 0, seconds: 0, rewords: 0, costUsd: 0, errors: 0, testRequests: 0,
    orders: 0, secondsSold: 0, revenue: 0, newWallets: 0,
  });

  for (const r of (usage.results ?? []) as Array<Record<string, unknown>>) {
    const e = touch(String(r.day));
    e.requests = num(r.requests); e.seconds = num(r.seconds); e.rewords = num(r.rewords);
    e.costUsd = num(r.costNano) / NANO_PER_USD; e.errors = num(r.errors);
    e.testRequests = num(r.testRequests);
  }
  for (const r of (sales.results ?? []) as Array<Record<string, unknown>>) {
    const e = touch(String(r.day));
    e.orders = num(r.orders); e.secondsSold = num(r.secondsSold);
    e.revenue = num(r.revenueHomeMicros) / MICROS;
  }
  for (const r of (signups.results ?? []) as Array<Record<string, unknown>>) {
    touch(String(r.day)).newWallets = num(r.wallets);
  }

  return Object.keys(merged).sort().map((k) => merged[k]);
}

/** Month-by-month roll-up, for the view that answers "is this growing". */
export async function months(env: Env, count = 24) {
  const [rows, sales] = await Promise.all([
    env.DB.prepare(
      `SELECT substr(day, 1, 7) AS month, SUM(requests) AS requests, SUM(seconds) AS seconds,
              SUM(cost_nano) AS costNano, SUM(errors) AS errors
         FROM daily_totals GROUP BY month ORDER BY month DESC LIMIT ?`,
    ).bind(count).all(),
    env.DB.prepare(
      `SELECT strftime('%Y-%m', p.purchased_at / 1000, 'unixepoch') AS month,
              COUNT(*) AS orders, SUM(COALESCE(p.revenue_home_micros, 0)) AS revenueHomeMicros,
              SUM(p.seconds) AS secondsSold
         FROM purchases p WHERE ${REAL_SALES} GROUP BY month ORDER BY month DESC LIMIT ?`,
    ).bind(count).all(),
  ]);

  const blank = (month: string) => ({
    month, requests: 0, seconds: 0, costUsd: 0, errors: 0, orders: 0, revenue: 0, secondsSold: 0,
  });
  const byMonth: Record<string, ReturnType<typeof blank>> = {};

  for (const r of (rows.results ?? []) as Array<Record<string, unknown>>) {
    const month = String(r.month);
    byMonth[month] = {
      ...blank(month),
      requests: num(r.requests), seconds: num(r.seconds),
      costUsd: num(r.costNano) / NANO_PER_USD, errors: num(r.errors),
    };
  }
  for (const r of (sales.results ?? []) as Array<Record<string, unknown>>) {
    const month = String(r.month);
    const entry = byMonth[month] ?? (byMonth[month] = blank(month));
    entry.orders = num(r.orders);
    entry.revenue = num(r.revenueHomeMicros) / MICROS;
    entry.secondsSold = num(r.secondsSold);
  }

  return Object.keys(byMonth).sort().reverse().map((k) => byMonth[k]);
}

/**
 * What each pack is actually worth, per pack.
 *
 * Two columns of truth side by side. The **model** is the calculation the pricing was built on:
 * list price, upstream cost, Google's share. The **actual** is what has really happened — averaged
 * over the orders that exist. They diverge for reasons worth seeing: prices set net rather than
 * gross, local tax, Play's rounding, a country with a different price list.
 *
 * Where there are no sales yet the actual column is simply absent. It is not filled in from the
 * model — a projection dressed as a measurement is the one thing this page must not produce.
 */
export async function plans(env: Env) {
  const home = homeCurrency(env);
  const { rate, source } = await usdRate(env);
  const limits = limitsFrom(env);

  const rows = await env.DB.prepare(
    `SELECT p.product_id AS productId, COUNT(*) AS orders,
            COUNT(p.revenue_micros) AS reported,
            AVG(p.paid_micros) AS avgPaid, AVG(p.revenue_micros) AS avgRevenue,
            AVG(p.revenue_home_micros) AS avgRevenueHome,
            AVG(p.tax_micros) AS avgTax, MAX(p.currency) AS currency
       FROM purchases p WHERE ${REAL_SALES} AND p.currency IS NOT NULL
      GROUP BY p.product_id`,
  ).all<{
    productId: string; orders: number; reported: number; avgPaid: number; avgRevenue: number;
    avgRevenueHome: number; avgTax: number; currency: string;
  }>();

  const actual = new Map<string, {
    orders: number; unreported: number; paid: number; revenue: number; revenueHome: number;
    tax: number; currency: string;
  }>();
  for (const r of rows.results ?? []) {
    // `COUNT(column)` counts the rows that have one, and `AVG` averages only those — so a pack whose
    // only sale is still unaccounted for has no measured revenue at all. Recorded as such: averaging
    // it as zero would put a loss next to a pack that has in fact been paid for.
    actual.set(r.productId, {
      orders: num(r.orders),
      unreported: num(r.orders) - num(r.reported),
      paid: num(r.avgPaid) / MICROS,
      revenue: num(r.reported) > 0 ? num(r.avgRevenue) / MICROS : 0,
      revenueHome: num(r.reported) > 0 ? num(r.avgRevenueHome) / MICROS : 0,
      tax: num(r.avgTax) / MICROS,
      currency: r.currency,
    });
  }

  const transcribeUsdPerMinute = COST.transcribePerMinuteNano / NANO_PER_USD;
  // A typical rewording as the plan measured it: ~500 tokens in, ~300 out.
  const rewordUsd = chatCostNano(500, 300) / NANO_PER_USD;

  const packs = Object.values(PACKAGES).map((pack) => {
    // The whole cost of a pack, and not an estimate of it: every service is priced into the same
    // seconds, so the seconds sold *are* the upstream spend. Whatever the buyer does with them —
    // all dictation, all rewording, any mixture — this figure cannot be exceeded.
    const costUsd = pack.minutes * transcribeUsdPerMinute;
    const costHome = costUsd * rate;
    // How far the pack goes if it is spent entirely on rewordings of ordinary length. Shown
    // beside the minutes because "150 minutes" and "or about 4500 rewordings" are the same pack.
    const rewordsIfOnly = Math.floor((pack.minutes * 60) / TYPICAL_REWORD_SECONDS);

    // What the plan assumed: Google keeps its share, the rest is yours. Kept as the yardstick the
    // actual figure is judged against, not as a claim about what happens.
    //
    // Tax is deliberately not subtracted. The list price is entered net and Google adds the local
    // rate on top of it, so the buyer pays more than this and you are never handed the difference
    // in the first place — deducting it here would take the same money away twice.
    const modelRevenue = pack.priceEur * (1 - PLAY_SERVICE_FEE);
    const row = actual.get(pack.id);
    // Sales alone are not a measurement: a pack whose only order is still waiting on Google's
    // accounting has nothing measured about it, and the model has to carry the row a while longer.
    const real = row && row.orders > row.unreported ? row : null;
    // The converted figure where there is one — comparing a franc revenue against a euro cost
    // would produce a margin that is simply wrong.
    const realRevenueHome = real ? (real.revenueHome || real.revenue) : null;

    const revenue = realRevenueHome ?? modelRevenue;
    const margin = revenue - costHome;

    return {
      id: pack.id,
      name: pack.name,
      minutes: pack.minutes,
      rewords: rewordsIfOnly,
      listPrice: pack.priceEur,
      currency: real?.currency ?? home,

      cost: {
        dictationUsd: costUsd,
        rewordsUsd: 0,
        totalUsd: costUsd,
        totalHome: costHome,
        perMinuteUsd: transcribeUsdPerMinute,
      },
      model: {
        revenue: modelRevenue,
        margin: modelRevenue - costHome,
        marginPercent: modelRevenue > 0 ? ((modelRevenue - costHome) / modelRevenue) * 100 : 0,
      },
      /** Sales that exist but carry no revenue figure yet — shown, never averaged in. */
      unreportedOrders: row?.unreported ?? 0,
      actual: real
        ? {
            orders: real.orders - real.unreported,
            paid: real.paid,
            tax: real.tax,
            revenue: real.revenue,
            revenueHome: realRevenueHome ?? 0,
            margin: (realRevenueHome ?? 0) - costHome,
            marginPercent: realRevenueHome ? ((realRevenueHome - costHome) / realRevenueHome) * 100 : 0,
          }
        : null,
      // What a minute costs the customer, and what it earns you. The pair that shows whether the
      // volume discount across packs is actually paid for by the margin.
      pricePerMinuteCents: (pack.priceEur / pack.minutes) * 100,
      marginPerMinuteCents: (margin / pack.minutes) * 100,
      // The badge the shop puts on this pack, computed by the same rule. Null on the baseline and
      // wherever there is nothing to claim — the app additionally hides anything under 10 %, so a
      // figure here is not a promise that the pill appears.
      savingsPercent: savingsPercent(pack),
    };
  });

  return {
    homeCurrency: home,
    rate,
    rateSource: source,
    transcribeModel: limits.transcribeModel,
    chatModel: limits.chatModel,
    transcribeUsdPerMinute,
    rewordUsd,
    /** So the page can work back from a target margin to a price without a second copy of it. */
    playServiceFee: PLAY_SERVICE_FEE,
    packs,
  };
}
