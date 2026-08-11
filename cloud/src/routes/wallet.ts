import { raise } from '../alerts';
import { authFailure, authenticate, touch } from '../auth';
import { TYPICAL_REWORD_SECONDS, type Env } from '../config';
import { walletStub } from '../meter';
import { alertSettings } from '../settings';
import { guardCodeAttempts, noteCodeFailure } from '../throttle';
import { apiError, json, newRecoveryCode, newToken, normalizeRecoveryCode, sha256, uuid } from '../util';

/**
 * Creating, reading and recovering credit accounts.
 *
 * The sore spot of the credit model: Google does **not** restore a consumed consumable after
 * a reinstall. Without a countermeasure, "phone reset" would mean "credit gone". So every
 * account carries a recovery code the user can see in the settings and write down.
 */

export interface NewWallet {
  walletId: string;
  token: string;
  recoveryCode: string;
}

/**
 * Creates an account and credits the first balance.
 *
 * Called from the Play redemption in step 2; until then from the test route below.
 */
export async function createWallet(
  env: Env,
  seconds: number,
  /**
   * What the buying device calls itself, e.g. "SM-A556B · Android 15 · 5.4.0". Recorded here and
   * not only on recovery: otherwise the device that made the *first* purchase — the one most
   * likely to turn up in a support request — is the only one without a name.
   */
  label?: string,
  /**
   * Marks the account as yours rather than a customer's — `license_tester` when Google says so,
   * `bootstrap` for one made by hand. Every money figure in the dashboard leaves these out.
   */
  testReason: string | null = null,
): Promise<NewWallet> {
  const walletId = uuid();
  const token = newToken();
  const recoveryCode = newRecoveryCode();
  const now = Date.now();

  await env.DB.batch([
    env.DB.prepare(
      // `rewords_left` is a display copy of the estimate the wallet derives, not a second balance.
      `INSERT INTO wallets (id, created_at, status, recovery_hash, seconds_left, rewords_left, seconds_bought,
                            is_test, test_reason)
       VALUES (?, ?, 'active', ?, ?, ?, ?, ?, ?)`,
    ).bind(walletId, now, await sha256(recoveryCode), seconds,
      Math.floor(seconds / TYPICAL_REWORD_SECONDS), seconds,
      testReason ? 1 : 0, testReason),
    env.DB.prepare(
      'INSERT INTO tokens (token_hash, wallet_id, created_at, label) VALUES (?, ?, ?, ?)',
    ).bind(await sha256(token), walletId, now, label?.slice(0, 60) ?? null),
  ]);

  await walletStub(env, walletId).credit(seconds);
  return { walletId, token, recoveryCode };
}

/**
 * `GET /v1/wallet` — balance for the token that was sent.
 *
 * This is the endpoint the app asks whenever the credit screen is opened, which makes it the place
 * where a device finds out that its account is gone — deleted from the web page, from another
 * phone, or signed out from the dashboard. So the 401 here says *which*: see `authFailure`. Every
 * other endpoint keeps the flat `invalid_token`, because nothing there can act on the difference.
 */
export async function handleBalance(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const session = await authenticate(request, env);
  if (!session) {
    return apiError(401, 'No valid credit token.', await authFailure(request, env), 'invalid_request_error');
  }
  touch(env, session, ctx);

  const state = await walletStub(env, session.walletId).state();
  const row = await env.DB.prepare('SELECT created_at AS createdAt FROM wallets WHERE id = ?')
    .bind(session.walletId)
    .first<{ createdAt: number }>();

  return json({
    wallet_id: session.walletId,
    // The app thinks in minutes, storage counts seconds — converting here means the client
    // never has to guess how rounding works.
    seconds_left: state.secondsLeft,
    minutes_left: Math.floor(state.secondsLeft / 60),
    rewords_left: state.rewordsLeft,
    seconds_bought: state.secondsBought,
    seconds_used: state.secondsUsed,
    status: state.status,
    created_at: row?.createdAt ?? null,
  });
}

/**
 * `POST /v1/wallet/restore` — recover an account on a new device.
 *
 * Issues an additional token; the old one stays valid so a second device does not lock out
 * the first. To lock every device out, revoke all tokens from the dashboard.
 */
export async function handleRestore(request: Request, env: Env, ctx?: ExecutionContext): Promise<Response> {
  // The same throttle as account deletion, and here it matters more: this endpoint hands back a
  // token, so a guessed code buys the balance rather than merely destroying it. See throttle.ts.
  const throttled = await guardCodeAttempts(env, request);
  if (throttled) return throttled;

  let payload: { code?: string; label?: string };
  try {
    payload = (await request.json()) as { code?: string; label?: string };
  } catch {
    return apiError(400, 'Request is not valid JSON.', 'bad_request', 'invalid_request_error');
  }

  const code = normalizeRecoveryCode(payload.code ?? '');
  if (!code) {
    return apiError(400, 'Recovery code is incomplete.', 'bad_code', 'invalid_request_error');
  }

  const row = await env.DB.prepare('SELECT id, status FROM wallets WHERE recovery_hash = ?')
    .bind(await sha256(code))
    .first<{ id: string; status: string }>();

  if (!row || row.status !== 'active') {
    // Deliberately the same answer as for a blocked account: someone working through codes
    // should not learn that they hit a real one.
    await noteCodeFailure(env, ctx);
    return apiError(404, 'No credit found for this code.', 'wallet_not_found', 'invalid_request_error');
  }

  // Three at once is a phone, a watch and a tablet — the honest case, and the one a code has to
  // keep serving. Beyond that it stops being a person's set of devices and starts being a shared
  // password, which drains a personal pack at a speed one person could not manage.
  //
  // Refused rather than resolved by evicting the oldest device. Evicting sounds friendlier but
  // hands the win to the wrong side: whoever passed the code around would push the owner out and
  // carry on. The reply carries the devices instead, so the app can offer to sign one out — the
  // way back for a phone that was lost, broken or reset, which is the case a bare refusal would
  // otherwise strand.
  const limit = (await alertSettings(env)).maxDevices;
  const devices = await env.DB.prepare(
    `SELECT token_hash AS tokenHash, label, created_at AS createdAt, last_seen_at AS lastSeenAt
       FROM tokens WHERE wallet_id = ? AND revoked_at IS NULL
      ORDER BY COALESCE(last_seen_at, created_at) ASC`,
  ).bind(row.id).all<{ tokenHash: string; label: string | null; createdAt: number; lastSeenAt: number | null }>();
  const active = devices.results ?? [];

  if (active.length >= limit) {
    ctx?.waitUntil(raise(env, {
      kind: 'device_limit',
      severity: 'notice',
      walletId: row.id,
      value: active.length,
      title: `Gerätegrenze erreicht (${active.length} von ${limit})`,
      detail:
        `Jemand hat den Wiederherstellungscode dieses Kontos auf einem weiteren Gerät eingegeben, ` +
        `während bereits ${active.length} angemeldet sind. Abgewiesen — in der App lässt sich ein ` +
        `Gerät abmelden und dann fortfahren. Einmal ist das ein Gerätewechsel. Häufen sich die ` +
        `Versuche auf demselben Konto, ist der Code weitergegeben worden, und dann hilft nur ein ` +
        `neuer Code (im Konto unter „Neuen Code ausgeben").`,
      dedupeKey: `device_limit:${row.id}`,
    }, ctx).then(() => undefined));

    return json({
      error: {
        message: `This account is already signed in on ${active.length} devices.`,
        type: 'invalid_request_error',
        code: 'device_limit',
      },
      limit,
      devices: active.map((d) => ({
        token_hash: d.tokenHash,
        label: d.label,
        created_at: d.createdAt,
        last_seen_at: d.lastSeenAt,
      })),
    }, 409);
  }

  const token = newToken();
  await env.DB.prepare(
    'INSERT INTO tokens (token_hash, wallet_id, created_at, label) VALUES (?, ?, ?, ?)',
  ).bind(await sha256(token), row.id, Date.now(), payload.label?.slice(0, 60) ?? null).run();

  const state = await walletStub(env, row.id).state();
  return json({
    wallet_id: row.id,
    token,
    seconds_left: state.secondsLeft,
    minutes_left: Math.floor(state.secondsLeft / 60),
    rewords_left: state.rewordsLeft,
  });
}

/**
 * `POST /v1/wallet/devices/revoke` — sign one device out, using the recovery code as the warrant.
 *
 * What makes the device limit liveable rather than a wall. The device to be removed is usually the
 * one that cannot ask for anything any more — lost, broken, wiped — so the request has to come from
 * somewhere else, and the only credential that reaches the account from somewhere else is the code.
 *
 * It grants no new power. Whoever holds the code can already spend the balance and delete the whole
 * account; being able to sign a device out is strictly less than either. Same throttle as the other
 * two code-bearing endpoints, for the same reason.
 */
export async function handleRevokeDevice(request: Request, env: Env): Promise<Response> {
  const throttled = await guardCodeAttempts(env, request);
  if (throttled) return throttled;

  let payload: { code?: string; tokenHash?: string };
  try {
    payload = (await request.json()) as { code?: string; tokenHash?: string };
  } catch {
    return apiError(400, 'Request is not valid JSON.', 'bad_request', 'invalid_request_error');
  }

  const code = normalizeRecoveryCode(payload.code ?? '');
  const tokenHash = (payload.tokenHash ?? '').trim();
  if (!code || !tokenHash) {
    return apiError(400, 'Recovery code and device are required.', 'bad_request', 'invalid_request_error');
  }

  const row = await env.DB.prepare("SELECT id FROM wallets WHERE recovery_hash = ? AND status = 'active'")
    .bind(await sha256(code))
    .first<{ id: string }>();
  if (!row) {
    await noteCodeFailure(env);
    return apiError(404, 'No credit found for this code.', 'wallet_not_found', 'invalid_request_error');
  }

  // Scoped to this wallet, so a device hash learned somewhere else cannot be used to sign out a
  // stranger. Already-revoked rows are left alone: revoking twice must not move the timestamp.
  const result = await env.DB.prepare(
    'UPDATE tokens SET revoked_at = ? WHERE token_hash = ? AND wallet_id = ? AND revoked_at IS NULL',
  ).bind(Date.now(), tokenHash, row.id).run();

  if (!(result.meta?.changes ?? 0)) {
    return apiError(404, 'No such device on this account.', 'device_not_found', 'invalid_request_error');
  }

  const remaining = await env.DB.prepare(
    'SELECT COUNT(*) AS n FROM tokens WHERE wallet_id = ? AND revoked_at IS NULL',
  ).bind(row.id).first<{ n: number }>();

  return json({ revoked: true, devices_left: Number(remaining?.n ?? 0) });
}
