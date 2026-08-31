import {
  NANO_PER_NEURON, NEURONS, PACKAGES, PLAY_SERVICE_FEE, SECOND_VALUE_NANO, TYPICAL_REWORD_NANO,
  TYPICAL_REWORD_SECONDS, WORST_COST_PER_SECOND_NANO, billedNanoForDay, limitsFrom, savingsPercent,
  type Env,
} from '../config';
import { homeCurrency, usdRate } from '../fx';
import { num } from '../util';

/**
 * The money, from the sources that actually hold it.
 *
 * Everything else in this dashboard is derived from our own ledger, which is fine for counting
 * requests and seconds but wrong for counting money: the list price in `config.ts` is what we ask
 * for, not what Play collects (it converts per country and adds local tax) and certainly not what
 * Play pays out (it keeps a share). So the takings come from Google's Orders API, stored per
 * purchase.
 *
 * **The spend does not.** It is the list price of what ran, out of our own roll-up — a self-report
 * rather than an invoice, because Workers AI bills the account this Worker lives on and there is no
 * endpoint to ask. The check on it is the monthly Cloudflare invoice, read by hand.
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

/**
 * Not one of your own orders. Split out from [REAL_SALES] because a refund is not a sale and still
 * has to be filtered the same way — the tax view asked for voided purchases without this and would
 * have subtracted a test wallet's cancellation from your income.
 */
export const NOT_A_TEST = `(p.purchase_type IS NULL OR p.purchase_type != 0)
  AND EXISTS (SELECT 1 FROM wallets w WHERE w.id = p.wallet_id AND w.is_test = 0)`;

/** Repeated in every money query. Written once so no view can quietly forget it. */
export const REAL_SALES = `p.state = 'granted' AND ${NOT_A_TEST}`;

/**
 * What one unit of the buyer's currency was worth in the payout currency, from the purchase itself.
 *
 * The rate is frozen onto the row on the day of the sale, so this never moves. A sale already in the
 * payout currency carries no rate at all — that is the `CASE`, and without it every euro sale would
 * silently drop out of a converted sum. Takes one bound parameter: the home currency.
 */
export const PURCHASE_RATE = `COALESCE(p.fx_rate, CASE WHEN p.currency = ? THEN 1.0 END)`;

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
              ${PURCHASE_RATE} AS rate
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
 * The one outside figure — Play's developer revenue — is cached, so this is fast after the first
 * call of the ten-minute window. The spend needs nothing from outside: it is our own ledger.
 */
export async function summary(env: Env) {
  const home = homeCurrency(env);
  const [play, fx] = await Promise.all([playRevenue(env), usdRate(env)]);

  const revenueHome = play.revenueHomeTotal;
  // Converted per purchase with its own stored rate — see the query in [playRevenue].
  const paidHome = play.paidHomeTotal;

  // The spend, from our own ledger — and that is worth saying out loud, because it used to come
  // from an invoice.
  //
  // Workers AI is billed to the same account this Worker runs on, and there is no equivalent of
  // no billing endpoint to ask. So this is summed from `daily_totals`, and it is a *self-report*: a
  // different class of evidence from a bill, and the page says so. The one thing that replaces the
  // lost second opinion is the monthly invoice, read by hand.
  //
  // **Cost here is what Cloudflare charges, not the list price.** The two differ by the daily free
  // allowance, and on a small service they differ by nearly all of it: a day that stays under the
  // allowance costs nothing at all, whatever its list price says. Reporting the list price as the
  // cost would understate the profit by the whole allowance every single day — an error that grows
  // with time and always in the same direction, which is the kind that goes unnoticed longest.
  //
  // It has to be summed **per day**, because the allowance is a daily figure and does not carry
  // over: a month is a sum of per-day results and never a calculation on the summed neurons. Both
  // neuron columns go in, because the allowance belongs to the account — own testing eats it
  // exactly like a customer's request does, and once the day is over the allowance, that testing
  // costs real money.
  const costRows = await env.DB.prepare(
    `SELECT neurons_micro + test_neurons_micro AS neuronsMicro, cost_nano AS costNano
       FROM daily_totals`,
  ).all<{ neuronsMicro: number; costNano: number }>();
  const days = costRows.results ?? [];
  const costUsd = days.reduce((sum, r) => sum + billedNanoForDay(num(r.neuronsMicro)), 0) / NANO_PER_USD;
  /** The same traffic before the allowance — what the margin is calculated against. */
  const listUsd = days.reduce((sum, r) => sum + num(r.costNano), 0) / NANO_PER_USD;
  const costHome = costUsd * fx.rate;
  const profitHome = revenueHome - costHome;

  return {
    homeCurrency: home,
    rate: fx.rate,
    rateSource: fx.source,
    revenueHome,
    paidHome,
    costUsd,
    listUsd,
    listHome: listUsd * fx.rate,
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
  };
}

/** The finance panel: takings per currency. */
export async function finance(env: Env) {
  return { play: await playRevenue(env) };
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
              test_requests AS testRequests, test_cost_nano AS testCostNano,
              neurons_micro + test_neurons_micro AS neuronsMicro
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
    day, requests: 0, seconds: 0, rewords: 0, costUsd: 0, listUsd: 0, errors: 0, testRequests: 0,
    orders: 0, secondsSold: 0, revenue: 0, newWallets: 0,
  });

  for (const r of (usage.results ?? []) as Array<Record<string, unknown>>) {
    const e = touch(String(r.day));
    e.requests = num(r.requests); e.seconds = num(r.seconds); e.rewords = num(r.rewords);
    // Was der Tag gekostet hat, ist was er *berechnet* bekommt — das Freikontingent lässt sich hier
    // exakt anwenden, weil eine Zeile genau einen Tag ist. Der Listenpreis bleibt daneben stehen.
    e.costUsd = billedNanoForDay(num(r.neuronsMicro)) / NANO_PER_USD;
    e.listUsd = num(r.costNano) / NANO_PER_USD; e.errors = num(r.errors);
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
      // Tageszeilen und **nicht** nach Monat summiert: Das Freikontingent ist ein Tageswert, also
      // ist der Monat die Summe der Tagesergebnisse und niemals eine Rechnung auf den
      // Monatsneuronen. Andersherum bekäme jeder Monat nur ein einziges Kontingent abgezogen.
      `SELECT day, substr(day, 1, 7) AS month, requests, seconds, cost_nano AS costNano, errors,
              neurons_micro + test_neurons_micro AS neuronsMicro
         FROM daily_totals ORDER BY day DESC`,
    ).all(),
    env.DB.prepare(
      `SELECT strftime('%Y-%m', p.purchased_at / 1000, 'unixepoch') AS month,
              COUNT(*) AS orders, SUM(COALESCE(p.revenue_home_micros, 0)) AS revenueHomeMicros,
              SUM(p.seconds) AS secondsSold
         FROM purchases p WHERE ${REAL_SALES} GROUP BY month ORDER BY month DESC LIMIT ?`,
    ).bind(count).all(),
  ]);

  const blank = (month: string) => ({
    month, requests: 0, seconds: 0, costUsd: 0, listUsd: 0, errors: 0, orders: 0, revenue: 0, secondsSold: 0,
  });
  const byMonth: Record<string, ReturnType<typeof blank>> = {};

  for (const r of (rows.results ?? []) as Array<Record<string, unknown>>) {
    const month = String(r.month);
    const e = byMonth[month] ?? (byMonth[month] = blank(month));
    e.requests += num(r.requests);
    e.seconds += num(r.seconds);
    e.errors += num(r.errors);
    e.costUsd += billedNanoForDay(num(r.neuronsMicro)) / NANO_PER_USD;
    e.listUsd += num(r.costNano) / NANO_PER_USD;
  }
  for (const r of (sales.results ?? []) as Array<Record<string, unknown>>) {
    const month = String(r.month);
    const entry = byMonth[month] ?? (byMonth[month] = blank(month));
    entry.orders = num(r.orders);
    entry.revenue = num(r.revenueHomeMicros) / MICROS;
    entry.secondsSold = num(r.secondsSold);
  }

  // Begrenzt wird erst hier. Die Tageszeilen mussten vollständig gelesen werden, weil sich sonst
  // ein Monat aus einem angeschnittenen Satz Tage zusammensetzt.
  return Object.keys(byMonth).sort().reverse().slice(0, count).map((k) => byMonth[k]);
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

  // Grouped by pack **and currency**, never by pack alone. Averaging a euro price together with a
  // lira one produces a number that is neither, and `MAX(currency)` then stamps one of the two
  // currencies onto it — a pack that sold once in each would have shown a margin computed from a
  // figure that never existed. The per-currency rows are folded together below in the payout
  // currency, which is the only sum that is a sum.
  const rows = await env.DB.prepare(
    `WITH sales AS (
       SELECT p.product_id AS productId, p.currency AS currency, p.paid_micros AS paid,
              p.tax_micros AS tax, p.revenue_micros AS revenue,
              p.revenue_home_micros AS revenueHome, ${PURCHASE_RATE} AS rate
         FROM purchases p WHERE ${REAL_SALES} AND p.currency IS NOT NULL
     )
     SELECT productId, currency, COUNT(*) AS orders, COUNT(revenue) AS reported,
            AVG(paid) AS avgPaid, AVG(tax) AS avgTax, AVG(revenue) AS avgRevenue,
            COALESCE(SUM(CASE WHEN rate IS NULL THEN 0 ELSE paid * rate END), 0) AS paidHomeMicros,
            COALESCE(SUM(CASE WHEN rate IS NULL THEN 0 ELSE COALESCE(tax, 0) * rate END), 0) AS taxHomeMicros,
            COALESCE(SUM(revenueHome), 0) AS revenueHomeMicros,
            SUM(CASE WHEN revenueHome IS NULL THEN 0 ELSE 1 END) AS converted
       FROM sales GROUP BY productId, currency`,
  ).bind(home).all<{
    productId: string; currency: string; orders: number; reported: number;
    avgPaid: number; avgTax: number; avgRevenue: number;
    paidHomeMicros: number; taxHomeMicros: number; revenueHomeMicros: number; converted: number;
  }>();

  const actual = new Map<string, {
    orders: number; unreported: number; converted: number;
    /** Averages in the buyer's own currency — only meaningful when there is exactly one. */
    paid: number; tax: number; revenue: number; currency: string; currencies: number;
    /** The same, in the payout currency. Always comparable, whatever was sold where. */
    paidHome: number; taxHome: number; revenueHome: number;
  }>();
  for (const r of rows.results ?? []) {
    const productId = r.productId;
    const entry = actual.get(productId) ?? {
      orders: 0, unreported: 0, converted: 0,
      paid: 0, tax: 0, revenue: 0, currency: r.currency, currencies: 0,
      paidHome: 0, taxHome: 0, revenueHome: 0,
    };
    const orders = num(r.orders);
    const reported = num(r.reported);
    entry.orders += orders;
    // `COUNT(column)` counts the rows that have one, and `AVG` averages only those — so a pack whose
    // only sale is still unaccounted for has no measured revenue at all. Recorded as such: averaging
    // it as zero would put a loss next to a pack that has in fact been paid for.
    entry.unreported += orders - reported;
    entry.converted += num(r.converted);
    entry.currencies += 1;
    // Kept per pack only while a single currency answers for it; the second one makes these
    // meaningless and the page stops showing them.
    entry.currency = r.currency;
    entry.paid = num(r.avgPaid) / MICROS;
    entry.tax = num(r.avgTax) / MICROS;
    entry.revenue = reported > 0 ? num(r.avgRevenue) / MICROS : 0;
    entry.paidHome += num(r.paidHomeMicros) / MICROS;
    entry.taxHome += num(r.taxHomeMicros) / MICROS;
    entry.revenueHome += num(r.revenueHomeMicros) / MICROS;
    actual.set(productId, entry);
  }

  const transcribeUsdPerMinute =
    (NEURONS['@cf/openai/whisper-large-v3-turbo'].perAudioMinute * NANO_PER_NEURON) / NANO_PER_USD;
  // What a sold minute is worth, which is what bounds the spend — not what a bought minute costs.
  const secondValueUsdPerMinute = (SECOND_VALUE_NANO * 60) / NANO_PER_USD;
  // A rewording of ordinary length, as 131 real ones measured out: 327 tokens in, 63 out.
  const rewordUsd = TYPICAL_REWORD_NANO / NANO_PER_USD;

  const packs = Object.values(PACKAGES).map((pack) => {
    // Three figures, and which of them leads decides whether this card is read as encouraging or
    // as alarming.
    //
    // `boundUsd` is the *structural* guarantee: `costToSeconds` rounds up, so no service can deduct
    // fewer seconds than it cost, and a pack therefore cannot exceed the value of the seconds it
    // sold — whatever the buyer does with it. True, and useless as a headline: it describes a
    // service priced at exactly what it sells for, which neither of ours is. Led with, it reported
    // 66 % where the business actually runs at 96 %.
    //
    // `worstUsd` is the floor that can really be reached: the whole pack spent on the dearer of the
    // two services. That is rewording — one of ordinary length deducts a second and costs 51.6 of
    // the 75 nano-dollars that second sold for, against dictation's 8.5.
    //
    // `typicalUsd` is the ordinary case: the pack spent on dictation, which is how packs are spent
    // — 94.3 % of credit-seconds, measured. This is what the margin badge shows, with the floor
    // beside it, because a number nobody's usage produces is not the honest headline either.
    const boundUsd = pack.minutes * secondValueUsdPerMinute;
    const worstUsd = (pack.minutes * 60 * WORST_COST_PER_SECOND_NANO) / NANO_PER_USD;
    const typicalUsd = pack.minutes * transcribeUsdPerMinute;
    const costUsd = typicalUsd;
    const costHome = costUsd * rate;
    const worstHome = worstUsd * rate;
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
    // accounting — or on an exchange rate — has nothing measured about it, and the model has to
    // carry the row a while longer.
    const real = row && row.orders > row.unreported && row.converted > 0 ? row : null;
    // Per sale, and only ever the converted figure. It used to fall back to `revenue` when the
    // conversion was missing, which put a lira amount next to a euro cost and called the difference
    // a margin. No rate means no comparable revenue, and the model keeps the card instead.
    const realRevenueHome = real ? real.revenueHome / real.converted : null;

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
        dictationUsd: typicalUsd,
        rewordsUsd: 0,
        /** The ordinary case — the pack spent on dictation. What the headline margin uses. */
        totalUsd: costUsd,
        totalHome: costHome,
        typicalUsd,
        typicalHome: costHome,
        /** The whole pack spent on rewording: the dearest thing it can actually be spent on. */
        worstUsd,
        worstHome,
        /** The structural ceiling. Cannot be exceeded by any usage; also cannot be reached. */
        boundUsd,
        boundHome: boundUsd * rate,
        perMinuteUsd: transcribeUsdPerMinute,
      },
      model: {
        revenue: modelRevenue,
        margin: modelRevenue - costHome,
        marginPercent: modelRevenue > 0 ? ((modelRevenue - costHome) / modelRevenue) * 100 : 0,
        /** The same pack spent entirely on rewording. */
        marginWorst: modelRevenue - worstHome,
        marginPercentWorst: modelRevenue > 0 ? ((modelRevenue - worstHome) / modelRevenue) * 100 : 0,
      },
      /** Sales that exist but carry no revenue figure yet — shown, never averaged in. */
      unreportedOrders: row?.unreported ?? 0,
      actual: real
        ? {
            orders: real.orders - real.unreported,
            /**
             * How many currencies this pack was sold in. One means the buyer-currency figures below
             * describe every sale of it and the page may show them; more than one means they
             * describe the last currency only, and the page has to fall back to the converted
             * ladder. Mixing the two in one sum is how a margin quietly comes out wrong.
             */
            currencies: real.currencies,
            paid: real.paid,
            tax: real.tax,
            revenue: real.revenue,
            /** Averaged over the sales that have a rate, in the payout currency. Always comparable. */
            paidHome: real.paidHome / real.orders,
            taxHome: real.taxHome / real.orders,
            revenueHome: realRevenueHome ?? 0,
            margin: (realRevenueHome ?? 0) - costHome,
            marginPercent: realRevenueHome ? ((realRevenueHome - costHome) / realRevenueHome) * 100 : 0,
            marginWorst: (realRevenueHome ?? 0) - worstHome,
            marginPercentWorst: realRevenueHome ? ((realRevenueHome - worstHome) / realRevenueHome) * 100 : 0,
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

/**
 * Our own arithmetic against Cloudflare's invoice, month by month.
 *
 * Until the move there was a rule that did this every day: the old provider published a billing
 * endpoint, and `cost_drift` compared what we had calculated against what it said. Workers AI bills
 * the account this Worker runs on and has no such endpoint, so **the monthly invoice, read by hand,
 * is now the only check against real money.** Everything else on the dashboard is self-report.
 *
 * A check that depends on remembering is a check that stops happening, so it is given a place to be
 * written down and a column that shows the difference. A month with no invoice recorded is not
 * silently blank: it says so, and after a fortnight the watchdog says so too.
 *
 * Both sides are in the payout currency. Ours is converted at today's rate rather than the rate of
 * each day — the invoice is one payment on one date, and pretending to a precision the comparison
 * does not have would only make a real difference look like a rounding one.
 */
export async function reconciliation(env: Env) {
  const home = homeCurrency(env);
  const { rate } = await usdRate(env);

  const [dayRows, invoiceRows] = await Promise.all([
    env.DB.prepare(
      `SELECT day, substr(day, 1, 7) AS month,
              neurons_micro + test_neurons_micro AS neuronsMicro, cost_nano AS costNano
         FROM daily_totals ORDER BY day DESC`,
    ).all<{ day: string; month: string; neuronsMicro: number; costNano: number }>(),
    env.DB.prepare(
      `SELECT strftime('%Y-%m', paid_at / 1000, 'unixepoch') AS month,
              COUNT(*) AS n,
              COALESCE(SUM(amount_home_micros), 0) AS homeMicros,
              COALESCE(SUM(CASE WHEN amount_home_micros IS NULL THEN 1 ELSE 0 END), 0) AS unconverted,
              MAX(reference) AS reference
         FROM expenses WHERE kind = 'cloudflare' GROUP BY month`,
    ).all<{ month: string; n: number; homeMicros: number; unconverted: number; reference: string | null }>(),
  ]);

  const invoices = new Map<string, { n: number; home: number; unconverted: number; reference: string | null }>();
  for (const r of invoiceRows.results ?? []) {
    invoices.set(r.month, {
      n: num(r.n), home: num(r.homeMicros) / MICROS,
      unconverted: num(r.unconverted), reference: r.reference,
    });
  }

  const byMonth = new Map<string, { month: string; billedUsd: number; listUsd: number; days: number }>();
  for (const r of dayRows.results ?? []) {
    const e = byMonth.get(r.month) ?? { month: r.month, billedUsd: 0, listUsd: 0, days: 0 };
    // Per day, always — the free allowance does not carry over. See `billedNanoForDay`.
    e.billedUsd += billedNanoForDay(num(r.neuronsMicro)) / NANO_PER_USD;
    e.listUsd += num(r.costNano) / NANO_PER_USD;
    e.days += 1;
    byMonth.set(r.month, e);
  }

  const thisMonth = new Date().toISOString().slice(0, 7);
  return {
    homeCurrency: home,
    rate,
    months: [...byMonth.values()].sort((a, b) => (a.month < b.month ? 1 : -1)).map((m) => {
      const invoice = invoices.get(m.month) ?? null;
      const ownHome = m.billedUsd * rate;
      return {
        month: m.month,
        days: m.days,
        /** What we say it cost: neurons, less the daily allowance. */
        ownUsd: m.billedUsd,
        ownHome,
        /** The same traffic at list price, for context. */
        listUsd: m.listUsd,
        /** What was actually invoiced, if it has been entered. */
        invoiceHome: invoice ? invoice.home : null,
        invoiceCount: invoice?.n ?? 0,
        invoiceReference: invoice?.reference ?? null,
        invoiceUnconverted: invoice?.unconverted ?? 0,
        deltaHome: invoice ? invoice.home - ownHome : null,
        /** A month still running cannot be missing its invoice yet. */
        open: m.month >= thisMonth,
      };
    }),
  };
}
