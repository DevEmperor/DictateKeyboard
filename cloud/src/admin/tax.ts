import type { Env } from '../config';
import { homeCurrency, rateOn, usdRate } from '../fx';
import type { AdminIdentity } from './auth';
import { NOT_A_TEST, PURCHASE_RATE, REAL_SALES } from './finance';
import { num } from '../util';

/**
 * The figures for the tax return, and the honest limits of them.
 *
 * Two things have to be said plainly, because getting either wrong is the sort of mistake that
 * only surfaces a year later in a letter:
 *
 * **This is not your tax return, it is the reconciliation.** The binding documents are Google's
 * monthly earnings reports and Cloudflare's invoices. What this page does is let you check that those
 * documents say what you expected, and hand your accountant a per-month breakdown.
 *
 * **Income arrives when Google pays, not when someone buys.** Under the cash-basis method most
 * small operations use (§ 4 Abs. 3 EStG), a purchase made on 31 December that Google pays out in
 * mid-January is income for the *new* year. Everything here is grouped by purchase date because
 * that is what the ledger knows exactly — so December always needs a second look. It is called out
 * on the page rather than quietly smoothed over.
 *
 * **Spending is what was invoiced, not what was consumed.** Compute is billed in arrears on the
 * monthly Cloudflare invoice; on a cash basis the expense is that payment, on the day it leaves the
 * account. Entered by hand below, with the invoice number — there is no endpoint to ask, and the
 * dashboard's own running total is a self-report rather than a bill.
 *
 * Cloudflare invoices in arrears, so an expense is recorded on the date of the invoice — there is
 * nothing to top up in advance.
 * They stay: a kind that disappears takes its year's figures out of the evaluation with it.
 */

const MICROS = 1_000_000;

export interface Expense {
  id: number;
  paidAt: number;
  kind: string;
  amount: number;
  currency: string;
  amountHome: number | null;
  reference: string | null;
  note: string | null;
}

/**
 * Records money that actually left your account.
 *
 * `amountHome` is what your bank or card statement says was debited, including any currency
 * surcharge — that is the figure a tax office recognises, not a converted one. Left empty it is
 * filled in with the day's ECB rate as an approximation, and marked as such.
 */
export async function addExpense(
  env: Env,
  admin: AdminIdentity,
  input: { paidAt: number; kind: string; amount: number; currency: string; amountHome?: number | null; reference?: string; note?: string },
): Promise<{ ok: boolean; message: string }> {
  if (!Number.isFinite(input.amount) || input.amount <= 0) {
    return { ok: false, message: 'Bitte einen Betrag größer als null angeben.' };
  }
  if (!Number.isFinite(input.paidAt) || input.paidAt <= 0) {
    return { ok: false, message: 'Bitte ein gültiges Zahlungsdatum angeben.' };
  }

  const currency = (input.currency || 'USD').toUpperCase();
  const home = homeCurrency(env);
  let amountHome = input.amountHome ?? null;

  if (amountHome === null) {
    if (currency === home) {
      amountHome = input.amount;
    } else {
      const day = new Date(input.paidAt).toISOString().slice(0, 10);
      const rate = await rateOn(env, currency, day).catch(() => null);
      amountHome = rate === null ? null : input.amount * rate;
    }
  }

  await env.DB.prepare(
    `INSERT INTO expenses (paid_at, kind, amount_micros, currency, amount_home_micros, reference, note, created_at, created_by)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).bind(
    Math.round(input.paidAt),
    input.kind || 'cloudflare',
    Math.round(input.amount * MICROS),
    currency,
    amountHome === null ? null : Math.round(amountHome * MICROS),
    (input.reference ?? '').slice(0, 120) || null,
    (input.note ?? '').slice(0, 500) || null,
    Date.now(),
    admin.email,
  ).run();

  return { ok: true, message: 'Ausgabe erfasst.' };
}

export async function deleteExpense(env: Env, id: number): Promise<{ ok: boolean; message: string }> {
  const result = await env.DB.prepare('DELETE FROM expenses WHERE id = ?').bind(id).run();
  return (result.meta?.changes ?? 0) > 0
    ? { ok: true, message: 'Ausgabe gelöscht.' }
    : { ok: false, message: 'Nicht gefunden.' };
}

export async function listExpenses(env: Env, year?: number): Promise<Expense[]> {
  const clause = year ? "WHERE strftime('%Y', paid_at / 1000, 'unixepoch') = ?" : '';
  const stmt = env.DB.prepare(
    `SELECT id, paid_at AS paidAt, kind, amount_micros AS amountMicros, currency,
            amount_home_micros AS amountHomeMicros, reference, note
       FROM expenses ${clause} ORDER BY paid_at DESC`,
  );
  const rows = year ? await stmt.bind(String(year)).all() : await stmt.all();

  return ((rows.results ?? []) as Array<Record<string, unknown>>).map((r) => ({
    id: Number(r.id),
    paidAt: num(r.paidAt),
    kind: String(r.kind),
    amount: num(r.amountMicros) / MICROS,
    currency: String(r.currency),
    amountHome: r.amountHomeMicros === null ? null : num(r.amountHomeMicros) / MICROS,
    reference: (r.reference as string) ?? null,
    note: (r.note as string) ?? null,
  }));
}

/**
 * One block of figures per calendar year, plus the months inside it.
 *
 * Test purchases are out, as everywhere. **Every amount is in the payout currency**, converted with
 * the rate frozen onto each purchase on the day it happened — including the gross and the tax.
 *
 * That last part is the whole reason these queries look the way they do. `paid_micros` and
 * `tax_micros` are stored in the *buyer's* currency, and this view used to add them up as they
 * stood and label the total in euros. For as long as every sale was a euro sale that was
 * accidentally right; the first three sales in another currency turned the year's gross into a
 * number roughly twenty times too large. The revenue line never had the problem, because it has
 * always come from `revenue_home_micros`.
 */
export async function taxReport(env: Env, ctx?: ExecutionContext) {
  const home = homeCurrency(env);

  const [years, months, refunds, expenseYears, fx] = await Promise.all([
    env.DB.prepare(
      `WITH sales AS (
         SELECT strftime('%Y', p.purchased_at / 1000, 'unixepoch') AS year,
                p.currency AS currency, p.paid_micros AS paid, p.tax_micros AS tax,
                p.revenue_micros AS revenue, p.revenue_home_micros AS revenueHome,
                ${PURCHASE_RATE} AS rate
           FROM purchases p WHERE ${REAL_SALES}
       )
       SELECT year, COUNT(*) AS orders,
              COALESCE(SUM(CASE WHEN rate IS NULL THEN 0 ELSE paid * rate END), 0)             AS paidHomeMicros,
              COALESCE(SUM(CASE WHEN rate IS NULL THEN 0 ELSE COALESCE(tax, 0) * rate END), 0) AS taxHomeMicros,
              COALESCE(SUM(revenueHome), 0)                                                    AS revenueHomeMicros,
              -- A purchase with no currency at all carries no figures either; it is missing, not
              -- foreign, and counting it here made the first sale ever look like an export.
              COALESCE(SUM(CASE WHEN currency IS NOT NULL AND currency <> ? THEN 1 ELSE 0 END), 0) AS foreignOrders,
              -- Sales whose amounts are known but cannot be converted yet. Every total above is
              -- short by exactly these, which is what the page says.
              COALESCE(SUM(CASE WHEN paid IS NOT NULL AND rate IS NULL THEN 1 ELSE 0 END), 0) AS unconverted,
              -- Sales Google has taken money for but not yet stated a share of. Counted here rather
              -- than left to be noticed, because a year that is short by a sale is the one figure on
              -- this page that must never look complete when it is not.
              COALESCE(SUM(CASE WHEN revenue IS NULL THEN 1 ELSE 0 END), 0) AS unreported
         FROM sales GROUP BY year ORDER BY year DESC`,
    ).bind(home, home).all(),
    env.DB.prepare(
      `WITH sales AS (
         SELECT strftime('%Y-%m', p.purchased_at / 1000, 'unixepoch') AS month,
                p.paid_micros AS paid, p.tax_micros AS tax,
                p.revenue_home_micros AS revenueHome, ${PURCHASE_RATE} AS rate
           FROM purchases p WHERE ${REAL_SALES}
       )
       SELECT month, COUNT(*) AS orders,
              COALESCE(SUM(CASE WHEN rate IS NULL THEN 0 ELSE paid * rate END), 0)             AS paidHomeMicros,
              COALESCE(SUM(CASE WHEN rate IS NULL THEN 0 ELSE COALESCE(tax, 0) * rate END), 0) AS taxHomeMicros,
              COALESCE(SUM(revenueHome), 0)                                                    AS revenueHomeMicros
         FROM sales GROUP BY month ORDER BY month DESC`,
    ).bind(home).all(),
    // Refunds, so the year does not overstate what you kept. Grouped by the day of the *purchase*,
    // like everything else here — a void carries no date of its own in the ledger. Filtered like
    // every other money query: without `NOT_A_TEST` a cancelled test purchase would have been
    // subtracted from real income.
    env.DB.prepare(
      `SELECT strftime('%Y', p.purchased_at / 1000, 'unixepoch') AS year, COUNT(*) AS orders,
              COALESCE(SUM(p.revenue_home_micros), 0) AS revenueHomeMicros
         FROM purchases p WHERE p.state = 'voided' AND ${NOT_A_TEST} GROUP BY year`,
    ).all(),
    env.DB.prepare(
      `SELECT strftime('%Y', paid_at / 1000, 'unixepoch') AS year, kind,
              COUNT(*) AS n,
              COALESCE(SUM(amount_home_micros), 0) AS homeMicros,
              COALESCE(SUM(CASE WHEN amount_home_micros IS NULL THEN 1 ELSE 0 END), 0) AS unconverted
         FROM expenses GROUP BY year, kind ORDER BY year DESC`,
    ).all(),
    usdRate(env),
  ]);

  const refundsByYear: Record<string, { orders: number; revenue: number }> = {};
  for (const r of (refunds.results ?? []) as Array<Record<string, unknown>>) {
    refundsByYear[String(r.year)] = {
      orders: num(r.orders),
      revenue: num(r.revenueHomeMicros) / MICROS,
    };
  }

  const spendByYear: Record<string, { total: number; unconverted: number; byKind: Record<string, number> }> = {};
  for (const r of (expenseYears.results ?? []) as Array<Record<string, unknown>>) {
    const year = String(r.year);
    const entry = spendByYear[year] ?? (spendByYear[year] = { total: 0, unconverted: 0, byKind: {} });
    const amount = num(r.homeMicros) / MICROS;
    entry.total += amount;
    entry.unconverted += num(r.unconverted);
    entry.byKind[String(r.kind)] = (entry.byKind[String(r.kind)] ?? 0) + amount;
  }

  const rows = ((years.results ?? []) as Array<Record<string, unknown>>).map((r) => {
    const year = String(r.year);
    const revenue = num(r.revenueHomeMicros) / MICROS;
    const refunded = refundsByYear[year]?.revenue ?? 0;
    const spend = spendByYear[year] ?? { total: 0, unconverted: 0, byKind: {} };
    return {
      year,
      orders: num(r.orders),
      foreignOrders: num(r.foreignOrders),
      unconverted: num(r.unconverted),
      unreported: num(r.unreported),
      /**
       * What buyers paid in total, tax included, **converted into the payout currency** with each
       * purchase's own frozen rate. Informational — never your income.
       */
      paidGross: num(r.paidHomeMicros) / MICROS,
      /** Collected and remitted by Google, likewise converted. Never touches your account. */
      taxCollected: num(r.taxHomeMicros) / MICROS,
      /** After Google's share: the figure that is income. */
      revenue,
      refundedOrders: refundsByYear[year]?.orders ?? 0,
      refunded,
      revenueNet: revenue - refunded,
      /** What you actually paid out, from the entries below. */
      spend: spend.total,
      spendUnconverted: spend.unconverted,
      spendByKind: spend.byKind,
      profit: revenue - refunded - spend.total,
    };
  });

  // Everything with a year, including ones where money only went out.
  for (const year of Object.keys(spendByYear)) {
    if (rows.some((r) => r.year === year)) continue;
    const spend = spendByYear[year]!;
    rows.push({
      year, orders: 0, foreignOrders: 0, unconverted: 0, unreported: 0, paidGross: 0, taxCollected: 0,
      revenue: 0, refundedOrders: 0, refunded: 0, revenueNet: 0,
      spend: spend.total, spendUnconverted: spend.unconverted, spendByKind: spend.byKind,
      profit: -spend.total,
    });
  }
  rows.sort((a, b) => b.year.localeCompare(a.year));

  return {
    homeCurrency: home,
    years: rows,
    months: ((months.results ?? []) as Array<Record<string, unknown>>).map((r) => ({
      month: String(r.month),
      orders: num(r.orders),
      paidGross: num(r.paidHomeMicros) / MICROS,
      taxCollected: num(r.taxHomeMicros) / MICROS,
      revenue: num(r.revenueHomeMicros) / MICROS,
    })),
    expenses: await listExpenses(env),
    rate: fx.rate,
    rateSource: fx.source,
  };
}
