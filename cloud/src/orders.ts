import type { Env } from './config';
import { rateOn } from './fx';
import { fetchOrder } from './google';
import { today } from './util';

/**
 * Asking Google again about what a sale was worth.
 *
 * Google answers two different questions on two different clocks. What the buyer paid is known the
 * moment the purchase completes; what reaches the developer is worked out once the payment settles,
 * and until then the order simply carries no revenue figure at all. The redemption path asks in the
 * same breath as the sale, so it almost always asks too early — which is fine, as long as somebody
 * asks again.
 *
 * Nobody did. The first real sale therefore sat in the ledger reading 0,00 € earned, indefinitely,
 * and every view believed it. This is the second ask: nightly, bounded, and recording each attempt
 * so a purchase nobody could get an answer for never looks like one nobody asked about.
 */

/**
 * How many nights to keep asking before treating the silence as final.
 *
 * Two weeks is well past any settlement delay. Beyond that the answer is not late, it is absent —
 * a permission missing on the service account, an order Google will not hand over — and repeating
 * the request every night for ever would only hide that behind a number that never arrives. The
 * watchdog reports what stays open (see `notify/rules.ts`).
 */
const MAX_ATTEMPTS = 14;

export interface OrderSyncOutcome {
  /** True only when a revenue figure was actually written. */
  ok: boolean;
  /** Plain German, shown verbatim in the dashboard — this is a report, not a status code. */
  message: string;
  revenueMicros?: number | null;
  currency?: string | null;
  orderState?: string | null;
}

interface PendingRow {
  token: string;
  orderId: string | null;
  purchasedAt: number;
  currency: string | null;
}

/**
 * The nightly pass: every real sale still without a revenue figure, youngest first.
 *
 * Licence testers are excluded on purpose — their orders really are worth nothing, and asking about
 * them for a fortnight would be asking about a zero that is already correct.
 */
export async function syncOrderFigures(env: Env, max = 50): Promise<{ asked: number; filled: number }> {
  const pending = await env.DB.prepare(
    `SELECT purchase_token AS token, order_id AS orderId, purchased_at AS purchasedAt, currency
       FROM purchases
      WHERE state = 'granted' AND order_id IS NOT NULL AND revenue_micros IS NULL
        AND purchase_type IS NULL AND order_attempts < ?
      ORDER BY purchased_at DESC LIMIT ?`,
  ).bind(MAX_ATTEMPTS, max).all<PendingRow>();

  const rows = pending.results ?? [];
  let filled = 0;
  for (const row of rows) {
    const outcome = await syncRow(env, row);
    if (outcome.ok) filled++;
  }
  return { asked: rows.length, filled };
}

/**
 * One purchase, asked about right now — what the dashboard's button calls.
 *
 * Unlike the nightly pass this ignores the attempt limit and does not care whether a figure is
 * already stored: pressing it deliberately means "ask Google again and tell me what it says".
 */
export async function syncOrderForPurchase(env: Env, purchaseToken: string): Promise<OrderSyncOutcome> {
  const row = await env.DB.prepare(
    `SELECT purchase_token AS token, order_id AS orderId, purchased_at AS purchasedAt, currency
       FROM purchases WHERE purchase_token = ?`,
  ).bind(purchaseToken).first<PendingRow>();

  if (!row) return { ok: false, message: 'Diesen Kauf gibt es im Hauptbuch nicht.' };
  if (!row.orderId) {
    return {
      ok: false,
      message: 'Zu diesem Kauf hat Google nie eine Bestellnummer genannt — es gibt nichts abzufragen.',
    };
  }
  return syncRow(env, row);
}

async function syncRow(env: Env, row: PendingRow): Promise<OrderSyncOutcome> {
  const now = Date.now();
  let order;
  try {
    order = await fetchOrder(env, row.orderId as string);
  } catch (error) {
    // Google being unreachable is not an answer about the money, so nothing is written but the
    // attempt. Counted, because a night that failed is still a night that has passed.
    await bumpAttempt(env, row.token, now, null);
    return {
      ok: false,
      message: `Google war nicht erreichbar: ${String(error).slice(0, 120)}`,
    };
  }

  if (!order) {
    await bumpAttempt(env, row.token, now, null);
    return { ok: false, message: 'Google gibt zu dieser Bestellung nichts heraus.' };
  }

  if (order.revenueMicros === null) {
    await bumpAttempt(env, row.token, now, order.state ?? null);
    return {
      ok: false,
      orderState: order.state ?? null,
      message: order.state
        ? `Google meldet weiterhin keinen Erlös (Bestellzustand ${order.state}).`
        : 'Google meldet weiterhin keinen Erlös.',
    };
  }

  // Converted with the rate of the day the sale happened, not today's — the same rule the ledger
  // has always followed, so a figure arriving three nights late is still worth what it was worth.
  const rate = await rateOn(env, order.currency, today(row.purchasedAt)).catch(() => null);
  const home = rate === null ? null : Math.round(order.revenueMicros * rate);

  await env.DB.prepare(
    `UPDATE purchases
        SET paid_micros = ?, tax_micros = ?, revenue_micros = ?, currency = ?,
            buyer_country = COALESCE(?, buyer_country),
            fx_rate = COALESCE(?, fx_rate), revenue_home_micros = ?,
            order_state = ?, order_synced_at = ?, order_attempts = order_attempts + 1
      WHERE purchase_token = ?`,
  ).bind(
    order.paidMicros,
    order.taxMicros,
    order.revenueMicros,
    order.currency,
    order.buyerCountry ?? null,
    rate,
    // Null where no rate exists yet; `fx.backfillPurchases` fills it in the same night.
    home,
    order.state ?? null,
    now,
    row.token,
  ).run();

  return {
    ok: true,
    revenueMicros: order.revenueMicros,
    currency: order.currency,
    orderState: order.state ?? null,
    message: `Erlös ${money(order.revenueMicros, order.currency)} nachgetragen.`,
  };
}

async function bumpAttempt(env: Env, token: string, now: number, state: string | null): Promise<void> {
  await env.DB.prepare(
    `UPDATE purchases
        SET order_state = COALESCE(?, order_state), order_synced_at = ?,
            order_attempts = order_attempts + 1
      WHERE purchase_token = ?`,
  ).bind(state, now, token).run();
}

function money(micros: number, currency: string): string {
  return new Intl.NumberFormat('de-DE', { style: 'currency', currency }).format(micros / 1_000_000);
}
