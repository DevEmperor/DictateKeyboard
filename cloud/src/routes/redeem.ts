import { raise } from '../alerts';
import { PACKAGES, type Env } from '../config';
import { rateOn } from '../fx';
import { acknowledgePurchase, fetchOrder, verifyPurchase } from '../google';
import { walletStub } from '../meter';
import { apiError, json, sha256, today } from '../util';
import { createWallet } from './wallet';

/**
 * `POST /v1/wallet/redeem` — a Play purchase becomes credit.
 *
 * The purchase token the app sends is **not** believed. Only Google's answer decides. That is
 * why this service can be secure even though anyone can read the app's source and build their
 * own client: what counts was signed by Google, not by us.
 *
 * The purchase token is also the primary key in `purchases`. Redeeming the same purchase
 * twice is therefore structurally impossible — no extra logic to forget.
 */
export async function handleRedeem(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  let payload: { purchaseToken?: string; productId?: string; walletId?: string; label?: string };
  try {
    payload = await request.json();
  } catch {
    return apiError(400, 'Request is not valid JSON.', 'bad_request', 'invalid_request_error');
  }

  const purchaseToken = payload.purchaseToken?.trim();
  const productId = payload.productId?.trim();
  if (!purchaseToken || !productId) {
    return apiError(400, 'purchaseToken and productId are required.', 'bad_request', 'invalid_request_error');
  }

  const pkg = PACKAGES[productId];
  if (!pkg) {
    return apiError(400, `Unknown package: ${productId}`, 'unknown_product', 'invalid_request_error');
  }

  // Already redeemed? Then the answer is the same as the first time. This is not an edge case
  // but the normal one: the app redeems again after crashing between purchase and
  // acknowledgement, or after losing the network.
  const known = await env.DB.prepare(
    'SELECT wallet_id AS walletId, seconds, rewords, state FROM purchases WHERE purchase_token = ?',
  )
    .bind(purchaseToken)
    .first<{ walletId: string; seconds: number; rewords: number; state: string }>();

  if (known) {
    if (known.state === 'voided') {
      return apiError(403, 'This purchase was refunded.', 'purchase_voided', 'invalid_request_error');
    }
    const state = await walletStub(env, known.walletId).state();
    return json({
      wallet_id: known.walletId,
      already_redeemed: true,
      seconds_left: state.secondsLeft,
      minutes_left: Math.floor(state.secondsLeft / 60),
      rewords_left: state.rewordsLeft,
    });
  }

  let facts;
  try {
    facts = await verifyPurchase(env, productId, purchaseToken);
  } catch {
    // Google unreachable: that is not an invalid purchase but a problem on our side. The
    // client may try again straight away — hence 503 rather than 403.
    return apiError(503, 'Purchase verification is unavailable right now.', 'verify_unavailable', 'server_error');
  }

  if (!facts) {
    return apiError(403, 'Google does not know this purchase.', 'purchase_unknown', 'invalid_request_error');
  }
  if (facts.purchaseState === 2) {
    // Pending, for instance when paying cash at a counter. No credit, but no error either —
    // the app asks again later.
    return apiError(202, 'The purchase is not complete yet.', 'purchase_pending', 'invalid_request_error');
  }
  if (facts.purchaseState !== 0) {
    return apiError(403, 'This purchase is not valid.', 'purchase_invalid', 'invalid_request_error');
  }

  const seconds = pkg.minutes * 60;

  // Top up an existing account or create a new one. The app sends its wallet ID once it has
  // one; on the very first purchase it does not.
  const requested = payload.walletId?.trim() || facts.obfuscatedExternalAccountId?.trim();
  const existing = requested
    ? await env.DB.prepare("SELECT id FROM wallets WHERE id = ? AND status = 'active'")
        .bind(requested)
        .first<{ id: string }>()
    : null;

  // Google reports 0 for a licence tester and nothing at all for a real sale. This is the only
  // moment the difference is knowable, so it is written down rather than guessed at later.
  const isTest = facts.purchaseType === 0;

  // The wallet id the app attached to this purchase, kept only as a hash.
  //
  // **It is not a Google identifier.** It arrives in a field called
  // `obfuscatedExternalAccountId`, which reads like one, but it is the value *we* sent —
  // `setObfuscatedAccountId(walletId)` in the billing flow — coming back. Google offers no
  // identifier for the buyer at all; that is deliberate on their side.
  //
  // The consequence is worth stating where it is created rather than leaving it to be discovered:
  // this is null whenever the app had no account at the moment of purchase. A first purchase, and
  // every purchase made after a deletion. So it links a *top-up* to the account being topped up,
  // and it does not link a fresh purchase to a deleted one.
  const playAccountHash = facts.obfuscatedExternalAccountId
    ? await sha256(facts.obfuscatedExternalAccountId)
    : null;

  let walletId: string;
  let issued: { token: string; recoveryCode: string } | null = null;

  if (existing) {
    walletId = existing.id;
    // The wallet is the authority; D1 is written from what it says afterwards rather than by
    // adding the same numbers a second time — the rewording figure is an estimate it derives.
    const state = await walletStub(env, walletId).credit(seconds);
    await env.DB.prepare(
      'UPDATE wallets SET seconds_left = ?, rewords_left = ?, seconds_bought = ? WHERE id = ?',
    ).bind(state.secondsLeft, state.rewordsLeft, state.secondsBought, walletId).run();
    // Never the other way round: an account that once bought as a tester stays marked, but a real
    // purchase on a marked account clears it — that is someone who tested and then actually paid.
    if (!isTest) {
      await env.DB.prepare("UPDATE wallets SET is_test = 0, test_reason = NULL WHERE id = ? AND test_reason = 'license_tester'")
        .bind(walletId).run();
    }
  } else {
    const created = await createWallet(env, seconds, payload.label, isTest ? 'license_tester' : null);
    walletId = created.walletId;
    issued = { token: created.token, recoveryCode: created.recoveryCode };
  }

  if (playAccountHash) {
    await env.DB.prepare('UPDATE wallets SET play_account_hash = ? WHERE id = ?')
      .bind(playAccountHash, walletId).run();

    // Has this buyer clawed money back before — on this wallet or an earlier one? The credit is
    // granted either way: refusing on suspicion would turn a false positive into a customer who
    // paid and got nothing, which is the more expensive mistake. But it is worth knowing at the
    // moment it happens rather than after the next refund.
    const history = await env.DB.prepare(
      'SELECT COALESCE(SUM(void_count), 0) AS n FROM wallets WHERE play_account_hash = ?',
    ).bind(playAccountHash).first<{ n: number }>();
    const priorVoids = Number(history?.n ?? 0);

    if (priorVoids > 0 && !isTest) {
      ctx.waitUntil(raise(env, {
        kind: 'repeat_buyer_refunded',
        severity: 'critical',
        walletId,
        value: priorVoids,
        title: `Neuer Kauf von jemandem mit ${priorVoids} Erstattung${priorVoids === 1 ? '' : 'en'}`,
        detail:
          `Wer hier gekauft hat, hat schon ${priorVoids} Mal Geld zurückgeholt und gerade erneut gekauft ` +
          `(${pkg.name}, ${pkg.minutes} Minuten). Das Guthaben wurde gutgeschrieben — auf Verdacht abzulehnen ` +
          `hieße, jemanden zahlen zu lassen und nichts zu liefern. Wenn sich das Muster bestätigt, ist das ` +
          `Konto hier zu sperren, bevor die Minuten verbraucht sind.`,
        dedupeKey: `repeat_buyer:${purchaseToken}`,
      }, ctx).then(() => undefined));
    }
  }

  // What the order was really worth. Asked for separately because the purchase check does not
  // carry money at all — and because the list price we charge is not what Play collects, nor what
  // Play pays out. Failure here is deliberately not fatal: the credit is already granted, and a
  // ledger row with an estimated price beats a customer without minutes.
  const order = facts.orderId ? await fetchOrder(env, facts.orderId).catch(() => null) : null;

  // Converted here, once, with today's rate — and stored. Doing it at display time would mean a
  // sale from March is worth something different every time the page is opened. Missing rate is
  // left null rather than filled with a guess; `fx.ts` backfills it overnight.
  const purchasedAt = Number(facts.purchaseTimeMillis ?? Date.now());
  const fxRate = order?.currency
    ? await rateOn(env, order.currency, today(purchasedAt)).catch(() => null)
    : null;

  await env.DB.prepare(
    `INSERT INTO purchases (purchase_token, wallet_id, product_id, order_id, seconds, rewords,
                            price_eur, region_code, quantity, purchased_at, state,
                            paid_micros, tax_micros, revenue_micros, currency, buyer_country,
                            purchase_type, fx_rate, revenue_home_micros)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'granted', ?, ?, ?, ?, ?, ?, ?, ?)`,
  )
    .bind(
      purchaseToken,
      walletId,
      productId,
      facts.orderId ?? null,
      seconds,
      0,
      pkg.priceEur,
      facts.regionCode ?? null,
      facts.quantity ?? 1,
      purchasedAt,
      order?.paidMicros ?? null,
      order?.taxMicros ?? null,
      order?.revenueMicros ?? null,
      order?.currency ?? null,
      order?.buyerCountry ?? facts.regionCode ?? null,
      // Absent on a normal sale; 0 for a licence tester. Kept so revenue can exclude the latter.
      facts.purchaseType ?? null,
      fxRate,
      fxRate !== null && order?.revenueMicros != null ? Math.round(order.revenueMicros * fxRate) : null,
    )
    .run();

  // Acknowledge so Google does not void the purchase after three days. Runs alongside: the
  // credit is already granted and nobody should wait for this.
  ctx.waitUntil(acknowledgePurchase(env, productId, purchaseToken).catch(() => undefined));

  const state = await walletStub(env, walletId).state();
  return json({
    wallet_id: walletId,
    // Token and recovery code are handed out exactly once: when the account is created.
    ...(issued ? { token: issued.token, recovery_code: issued.recoveryCode } : {}),
    granted_minutes: pkg.minutes,
    seconds_left: state.secondsLeft,
    minutes_left: Math.floor(state.secondsLeft / 60),
    rewords_left: state.rewordsLeft,
  });
}
