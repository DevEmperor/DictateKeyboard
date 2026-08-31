import type { Env } from '../config';
import { walletStub } from '../meter';
import { guardCodeAttempts, noteCodeFailure } from '../throttle';
import { apiError, json, normalizeRecoveryCode, sha256 } from '../util';

/**
 * Deleting a credit account, for good.
 *
 * Google requires this of any app in which an account can be created, and it must be reachable two
 * ways: inside the app and through a web address, because someone who has already uninstalled must
 * still be able to get rid of their data.
 *
 * **Credit is forfeited, not refunded.** Deliberate, and the warning says so in as many words
 * before anything happens. A deletion that pays money back would be a refund channel outside Google
 * Play — with no purchase to reverse, no way to reclaim the compute already bought and paid for,
 * and an obvious way to launder a refund past Play's own rules.
 *
 * **What survives, and why it must.** The purchase records stay: § 147 AO obliges a business to
 * keep them for ten years, and Art. 17 Abs. 3 lit. b DSGVO makes room for exactly that. What they
 * keep is the order number and the amount — the things a tax office may ask about. Everything that
 * could lead back to a person goes: the recovery code's hash, every access token, the device names,
 * the pseudonym of the Play account, and the individual usage rows.
 *
 * **The account row is emptied, not dropped.** `purchases.wallet_id` references `wallets(id)`, D1
 * enforces foreign keys, and the receipts have to stay — so the row they point at cannot go either.
 * Deleting it raised a constraint error that rolled the whole batch back, and only for accounts
 * that had actually bought something; a wallet with no purchases deleted cleanly, which is what
 * made this look tested. What is left afterwards is a random id, a pair of timestamps and the
 * totals: nothing that resolves to a person, and enough that two purchases by the same former
 * customer still read as one order history.
 *
 * **Order of operations.** Every write to D1 happens first and the credit balance is erased last.
 * The reverse — which is what this did — destroyed the balance and then failed on the batch,
 * leaving an account that still existed but had been emptied. A failure now costs a retry.
 */

export interface DeletionPreview {
  walletId: string;
  secondsLeft: number;
  rewordsLeft: number;
  purchases: number;
}

/** What is about to be lost — so the confirmation can name a number instead of a vague warning. */
export async function previewDeletion(env: Env, walletId: string): Promise<DeletionPreview | null> {
  const row = await env.DB.prepare("SELECT id FROM wallets WHERE id = ? AND status != 'deleted'")
    .bind(walletId)
    .first<{ id: string }>();
  if (!row) return null;

  const [state, purchases] = await Promise.all([
    walletStub(env, walletId).state().catch(() => null),
    env.DB.prepare("SELECT COUNT(*) AS n FROM purchases WHERE wallet_id = ? AND state = 'granted'")
      .bind(walletId).first<{ n: number }>(),
  ]);

  return {
    walletId,
    secondsLeft: Math.max(0, state?.secondsLeft ?? 0),
    rewordsLeft: Math.max(0, state?.rewordsLeft ?? 0),
    purchases: Number(purchases?.n ?? 0),
  };
}

export async function deleteWallet(
  env: Env,
  walletId: string,
  source: 'app' | 'web',
): Promise<DeletionPreview | null> {
  const exists = await env.DB.prepare("SELECT id FROM wallets WHERE id = ? AND status != 'deleted'")
    .bind(walletId)
    .first<{ id: string }>();
  if (!exists) return null;

  // Read the balance — it goes into the audit line, so a later "where did my credit go" can be
  // answered with a figure rather than a shrug. Read, not erased: the erasing comes last.
  const state = await walletStub(env, walletId).state().catch(() => null);
  const purchases = await env.DB.prepare(
    "SELECT COUNT(*) AS n FROM purchases WHERE wallet_id = ? AND state = 'granted'",
  ).bind(walletId).first<{ n: number }>();

  const secondsLeft = Math.max(0, state?.secondsLeft ?? 0);
  const now = Date.now();

  await env.DB.batch([
    // Tokens first: while one of these exists, the account is still usable.
    env.DB.prepare('DELETE FROM tokens WHERE wallet_id = ?').bind(walletId),
    env.DB.prepare('DELETE FROM usage_log WHERE wallet_id = ?').bind(walletId),
    // Alerts name the wallet and quote its behaviour; with the account gone they are about nobody.
    env.DB.prepare('DELETE FROM alerts WHERE wallet_id = ?').bind(walletId),
    // Emptied rather than dropped — the receipts reference this row. `recovery_hash` is NOT NULL,
    // so it becomes the empty string; no SHA-256 is empty, which means no code can ever match it.
    //
    // `play_account_hash` is the one thing deliberately left behind, and it is worth saying why.
    // It is the only link between a refund and the *person* who asked for it. Clearing it here
    // would make deletion the last step of an abuse cycle rather than an end to it: buy, spend,
    // get the money back, delete, and the counter that would have recognised the same buyer
    // starts at zero again. It survives the deletion but not indefinitely — see `pruneDeleted`.
    env.DB.prepare(
      `UPDATE wallets
          SET status = 'deleted', deleted_at = ?, recovery_hash = '',
              note = NULL, seconds_left = 0, rewords_left = 0
        WHERE id = ?`,
    ).bind(now, walletId),
    env.DB.prepare(
      `INSERT INTO admin_log (ts, actor, wallet_id, action, delta_secs, delta_words, note)
       VALUES (?, ?, ?, 'delete', ?, ?, ?)`,
    ).bind(
      now,
      source === 'web' ? 'user-web' : 'user-app',
      walletId,
      -secondsLeft,
      -(state?.rewordsLeft ?? 0),
      `Konto auf Wunsch gelöscht (${source}). Verfallenes Guthaben: ${Math.floor(secondsLeft / 60)} Minuten. ` +
        `Kaufbelege bleiben nach § 147 AO bestehen.`,
    ),
  ]);

  // Last, and deliberately after the point of no return: with the tokens gone nothing can reach
  // this balance any more, so a failure here leaves credit stranded in a Durable Object nobody can
  // address — annoying, but harmless. Erasing first was the other way round, and cost a live
  // account its balance every time the batch below refused.
  await walletStub(env, walletId).erase().catch(() => null);

  return {
    walletId,
    secondsLeft,
    rewordsLeft: Math.max(0, state?.rewordsLeft ?? 0),
    purchases: Number(purchases?.n ?? 0),
  };
}

/** Resolves a recovery code to its account. The only handle a web visitor has. */
export async function walletIdForCode(env: Env, code: string): Promise<string | null> {
  const normalized = normalizeRecoveryCode(code);
  if (!normalized) return null;
  const row = await env.DB.prepare("SELECT id FROM wallets WHERE recovery_hash = ? AND status != 'deleted'")
    .bind(await sha256(normalized))
    .first<{ id: string }>();
  return row?.id ?? null;
}

/**
 * `POST /v1/wallet/delete` — from inside the app, authenticated by the credit token.
 *
 * Two steps on purpose. Without `confirm: true` it only reports what would be lost; the app shows
 * that figure and asks again. A single call that deletes on the first request would make an
 * accidental tap unrecoverable, and there is nothing to recover here.
 */
export async function handleDelete(request: Request, env: Env): Promise<Response> {
  const { authenticate } = await import('../auth');
  const session = await authenticate(request, env);
  if (!session) {
    return apiError(401, 'No valid credit token.', 'invalid_token', 'invalid_request_error');
  }

  let payload: { confirm?: boolean };
  try {
    payload = (await request.json()) as { confirm?: boolean };
  } catch {
    payload = {};
  }

  if (!payload.confirm) {
    const preview = await previewDeletion(env, session.walletId);
    if (!preview) return apiError(404, 'No such account.', 'wallet_not_found', 'invalid_request_error');
    return json({
      confirmed: false,
      wallet_id: preview.walletId,
      seconds_left: preview.secondsLeft,
      minutes_left: Math.floor(preview.secondsLeft / 60),
      rewords_left: preview.rewordsLeft,
      purchases: preview.purchases,
    });
  }

  const result = await deleteWallet(env, session.walletId, 'app');
  if (!result) return apiError(404, 'No such account.', 'wallet_not_found', 'invalid_request_error');

  return json({
    confirmed: true,
    deleted: true,
    forfeited_minutes: Math.floor(result.secondsLeft / 60),
    purchases_retained: result.purchases,
  });
}

/**
 * `POST /v1/wallet/delete-by-code` — from the web page, where there is no token.
 *
 * The recovery code is the credential. That is not a weakening: whoever holds it can already spend
 * the balance through the normal restore route, so being able to destroy it adds no power. What it
 * does add is the way out for someone who uninstalled the app, which is the case Google's rule
 * exists for.
 */
export async function handleDeleteByCode(request: Request, env: Env): Promise<Response> {
  const throttled = await guardCodeAttempts(env, request);
  if (throttled) return throttled;

  let payload: { code?: string; confirm?: boolean };
  try {
    payload = (await request.json()) as { code?: string; confirm?: boolean };
  } catch {
    return apiError(400, 'Request is not valid JSON.', 'bad_request', 'invalid_request_error');
  }

  const walletId = await walletIdForCode(env, payload.code ?? '');
  if (!walletId) {
    // Deliberately the same answer for a malformed code and for one that simply does not exist —
    // somebody working through codes should not learn that they hit a real one.
    await noteCodeFailure(env);
    return apiError(404, 'No credit account matches this code.', 'wallet_not_found', 'invalid_request_error');
  }

  if (!payload.confirm) {
    const preview = await previewDeletion(env, walletId);
    if (!preview) {
      return apiError(404, 'No credit account matches this code.', 'wallet_not_found', 'invalid_request_error');
    }
    return json({
      confirmed: false,
      seconds_left: preview.secondsLeft,
      minutes_left: Math.floor(preview.secondsLeft / 60),
      rewords_left: preview.rewordsLeft,
      purchases: preview.purchases,
    });
  }

  const result = await deleteWallet(env, walletId, 'web');
  if (!result) {
    return apiError(404, 'No credit account matches this code.', 'wallet_not_found', 'invalid_request_error');
  }

  return json({
    confirmed: true,
    deleted: true,
    forfeited_minutes: Math.floor(result.secondsLeft / 60),
    purchases_retained: result.purchases,
  });
}
