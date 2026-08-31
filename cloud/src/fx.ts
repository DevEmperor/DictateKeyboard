import type { Env } from './config';
import { today } from './util';

/**
 * Exchange rates, so a sale in francs is worth something in the profit line.
 *
 * Until now `summary()` picked out the euro row and quietly dropped everything else — a purchase
 * in CHF or PLN appeared in a side list and counted towards nothing. That is not a rounding error,
 * it is a missing summand.
 *
 * Two rules, and the second is the one that matters:
 *
 *  - Rates come from the **ECB** through Frankfurter, free and without a key. A central bank's
 *    reference rate is not a trading rate, which is exactly right here: it is the rate a tax
 *    office and an accountant recognise.
 *  - A purchase is converted **once, with the rate of the day it happened**, and the result is
 *    written onto the row. Recomputing on every page view would mean last quarter's profit moves
 *    because the franc did. A number that changes on its own is not bookkeeping.
 *
 * What this cannot be: Google's own conversion. Play pays out at Google's rate on Google's day
 * and may charge for the conversion. Everything derived from here is therefore a close estimate,
 * and every place that shows it says so.
 */

const API = 'https://api.frankfurter.dev/v2/rates';

export function homeCurrency(env: Env): string {
  return (env.HOME_CURRENCY ?? 'EUR').toUpperCase();
}

/**
 * Fetches one day's rates and stores them.
 *
 * The response is a flat list, one entry per currency, and **each entry carries its own date** —
 * central banks do not all publish on the same schedule, so asking for the 8th can legitimately
 * return a rate stamped the 7th. That stamp is what gets stored, so the table never claims a rate
 * was published on a day it was not. Lookups walk backwards anyway.
 */
export async function fetchRates(env: Env, day?: string): Promise<{ day: string; count: number } | null> {
  const home = homeCurrency(env);
  const url = new URL(API);
  url.searchParams.set('base', home);
  if (day) url.searchParams.set('date', day);

  const response = await fetch(url, { headers: { accept: 'application/json' } });
  if (!response.ok) return null;

  const body = await response.json();
  const quotes = parseRates(body, day ?? today());
  if (quotes.length === 0) return null;

  const stamped = day ?? today();
  const rows: D1PreparedStatement[] = [
    // The home currency is worth one of itself. Stored rather than special-cased, so that every
    // lookup can take the same path and no caller has to remember the exception.
    env.DB.prepare('INSERT OR REPLACE INTO fx_rates (day, currency, rate) VALUES (?, ?, 1.0)')
      .bind(stamped, home),
  ];

  for (const quote of quotes) {
    // The API answers "one home unit buys `rate` of this currency". Stored the other way round,
    // because every question asked here is "what is this foreign amount worth to me".
    rows.push(
      env.DB.prepare('INSERT OR REPLACE INTO fx_rates (day, currency, rate) VALUES (?, ?, ?)')
        .bind(quote.day, quote.currency, 1 / quote.perHome),
    );
  }

  await env.DB.batch(rows);
  return { day: stamped, count: rows.length };
}

/**
 * Reads both shapes the service has used.
 *
 * v2 answers with a flat array of `{date, base, quote, rate}`; v1 answered with a single
 * `{date, rates: {CHF: 0.93, …}}` object. Accepting either costs ten lines and means a change at
 * the other end degrades to "rates stop updating and the dashboard says so" rather than to a
 * conversion that silently disappears.
 */
function parseRates(body: unknown, fallbackDay: string): Array<{ day: string; currency: string; perHome: number }> {
  const out: Array<{ day: string; currency: string; perHome: number }> = [];

  if (Array.isArray(body)) {
    for (const row of body as Array<Record<string, unknown>>) {
      const currency = String(row.quote ?? '').toUpperCase();
      const perHome = Number(row.rate);
      if (!currency || !Number.isFinite(perHome) || perHome <= 0) continue;
      out.push({ day: String(row.date ?? fallbackDay), currency, perHome });
    }
    return out;
  }

  const rates = (body as { rates?: Record<string, unknown> } | null)?.rates;
  const day = String((body as { date?: unknown } | null)?.date ?? fallbackDay);
  if (rates && typeof rates === 'object') {
    for (const [currency, value] of Object.entries(rates)) {
      const perHome = Number(value);
      if (!Number.isFinite(perHome) || perHome <= 0) continue;
      out.push({ day, currency: currency.toUpperCase(), perHome });
    }
  }
  return out;
}

/**
 * What one unit of `currency` was worth in the payout currency on `day`.
 *
 * Walks backwards to the most recent published rate rather than demanding an exact match:
 * weekends and holidays have none, and a purchase made on a Sunday is still worth something.
 * Returns null when nothing has ever been stored for that currency — the caller then leaves the
 * converted column empty instead of inventing a number.
 */
export async function rateOn(env: Env, currency: string, day: string): Promise<number | null> {
  const code = currency.toUpperCase();
  if (code === homeCurrency(env)) return 1;

  const row = await env.DB.prepare(
    'SELECT rate FROM fx_rates WHERE currency = ? AND day <= ? ORDER BY day DESC LIMIT 1',
  ).bind(code, day).first<{ rate: number }>();
  if (row?.rate) return row.rate;

  // Nothing that old on hand. The newest known rate is worse than the right one but far better
  // than dropping the sale, and the dashboard labels converted figures as estimates anyway.
  const newest = await env.DB.prepare(
    'SELECT rate FROM fx_rates WHERE currency = ? ORDER BY day DESC LIMIT 1',
  ).bind(code).first<{ rate: number }>();
  return newest?.rate ?? null;
}

/** Today's USD rate, for bringing Cloudflare's dollars home. Falls back to the assumption. */
export async function usdRate(env: Env): Promise<{ rate: number; source: 'ecb' | 'assumed' }> {
  const found = await rateOn(env, 'USD', today());
  if (found) return { rate: found, source: 'ecb' };
  return { rate: Number(env.USD_TO_HOME_RATE ?? '0.92') || 0.92, source: 'assumed' };
}

/**
 * Fills in the converted revenue for purchases that do not have it yet.
 *
 * Runs hourly and again at night, deliberately bounded: it fetches the historical rate for each
 * distinct day it still needs, not for each purchase. A hundred sales on one day cost one request.
 */
export async function backfillPurchases(env: Env, maxDays = 20): Promise<number> {
  const pending = await env.DB.prepare(
    `SELECT DISTINCT date(purchased_at / 1000, 'unixepoch') AS day
       FROM purchases
      WHERE revenue_home_micros IS NULL AND revenue_micros IS NOT NULL AND currency IS NOT NULL
      ORDER BY day DESC LIMIT ?`,
  ).bind(maxDays).all<{ day: string }>();

  let filled = 0;
  for (const { day } of pending.results ?? []) {
    // Only ask the ECB for a day nothing is stored for. Once a day's set is in, every currency
    // of that day is answerable.
    const have = await env.DB.prepare('SELECT 1 AS ok FROM fx_rates WHERE day <= ? LIMIT 1')
      .bind(day).first<{ ok: number }>();
    if (!have) await fetchRates(env, day).catch(() => null);

    const rows = await env.DB.prepare(
      `SELECT purchase_token AS token, currency, revenue_micros AS revenue
         FROM purchases
        WHERE revenue_home_micros IS NULL AND revenue_micros IS NOT NULL AND currency IS NOT NULL
          AND date(purchased_at / 1000, 'unixepoch') = ?`,
    ).bind(day).all<{ token: string; currency: string; revenue: number }>();

    const updates: D1PreparedStatement[] = [];
    for (const row of rows.results ?? []) {
      const rate = await rateOn(env, row.currency, day);
      if (rate === null) continue;
      updates.push(
        env.DB.prepare(
          'UPDATE purchases SET fx_rate = ?, revenue_home_micros = ? WHERE purchase_token = ?',
        ).bind(rate, Math.round(row.revenue * rate), row.token),
      );
    }
    if (updates.length) {
      await env.DB.batch(updates);
      filled += updates.length;
    }
  }
  return filled;
}
