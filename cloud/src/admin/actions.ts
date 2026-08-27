import { raise } from '../alerts';
import type { Env } from '../config';
import { guardStub, walletStub } from '../meter';
import { syncOrderForPurchase } from '../orders';
import { newRecoveryCode, sha256, today } from '../util';
import type { AdminIdentity } from './auth';

/**
 * Everything the dashboard can change.
 *
 * Two rules hold throughout. **Every action is logged** with who did it, to which account and why —
 * without that the numbers stop adding up the moment anything is corrected by hand, and a gift made
 * six months ago becomes indistinguishable from a bug. And **a note is required**, not optional:
 * the one reading it later is you, with no memory of the support thread that prompted it.
 *
 * The D1 columns are kept in step with the Durable Object after each change. The object stays
 * authoritative — D1 is the copy the list views read.
 */

export interface ActionResult {
  ok: boolean;
  message: string;
}

export async function giftCredit(
  env: Env,
  admin: AdminIdentity,
  walletId: string,
  minutes: number,
  note: string,
): Promise<ActionResult> {
  if (!note.trim()) return { ok: false, message: 'Eine Begründung ist Pflicht.' };
  if (!Number.isFinite(minutes) || minutes === 0) {
    return { ok: false, message: 'Bitte eine Minutenzahl ungleich null angeben.' };
  }

  const seconds = Math.round(minutes * 60);
  const stub = walletStub(env, walletId);
  // A negative gift is a deduction, which is `claw` — it may go below zero, whereas `credit` with a
  // negative number would quietly inflate secondsBought and corrupt the sold-minutes figure.
  const state = seconds >= 0 ? await stub.credit(seconds) : await stub.claw(-seconds);

  await syncToD1(env, walletId, state);
  await log(env, admin, walletId, seconds >= 0 ? 'gift' : 'deduct', seconds, 0, note);
  return { ok: true, message: `${minutes > 0 ? '+' : ''}${minutes} Minuten gebucht.` };
}

export async function setBlocked(
  env: Env,
  admin: AdminIdentity,
  walletId: string,
  blocked: boolean,
  note: string,
): Promise<ActionResult> {
  if (!note.trim()) return { ok: false, message: 'Eine Begründung ist Pflicht.' };
  const state = await walletStub(env, walletId).setStatus(blocked ? 'blocked' : 'active');
  await env.DB.prepare('UPDATE wallets SET status = ? WHERE id = ?')
    .bind(state.status, walletId)
    .run();
  await log(env, admin, walletId, blocked ? 'block' : 'unblock', 0, 0, note);
  return { ok: true, message: blocked ? 'Konto gesperrt.' : 'Konto entsperrt.' };
}

/**
 * Issues a fresh recovery code and returns it **once**.
 *
 * Only the hash is stored, so this is the single moment the code exists in readable form. The old
 * one stops working immediately, which is the point when someone believes theirs has been seen by
 * a third party.
 */
export async function resetRecoveryCode(
  env: Env,
  admin: AdminIdentity,
  walletId: string,
  note: string,
): Promise<ActionResult & { code?: string }> {
  if (!note.trim()) return { ok: false, message: 'Eine Begründung ist Pflicht.' };
  const code = newRecoveryCode();
  await env.DB.prepare('UPDATE wallets SET recovery_hash = ? WHERE id = ?')
    .bind(await sha256(code), walletId)
    .run();
  await log(env, admin, walletId, 'recovery_reset', 0, 0, note);
  return { ok: true, message: 'Neuer Wiederherstellungscode erzeugt.', code };
}

/** Revokes one device's token — the others keep working. */
export async function revokeToken(
  env: Env,
  admin: AdminIdentity,
  walletId: string,
  tokenHash: string,
  note: string,
): Promise<ActionResult> {
  if (!note.trim()) return { ok: false, message: 'Eine Begründung ist Pflicht.' };
  await env.DB.prepare('UPDATE tokens SET revoked_at = ? WHERE token_hash = ? AND wallet_id = ?')
    .bind(Date.now(), tokenHash, walletId)
    .run();
  await log(env, admin, walletId, 'revoke_token', 0, 0, `${note} (${tokenHash.slice(0, 12)}…)`);
  return { ok: true, message: 'Gerät abgemeldet.' };
}

/**
 * Moves everything from one account into another — the commonest support case after a device change.
 *
 * The source is emptied and blocked rather than deleted, so the purchase history stays attached to
 * something and the merge remains traceable afterwards.
 */
export async function mergeWallets(
  env: Env,
  admin: AdminIdentity,
  sourceId: string,
  targetId: string,
  note: string,
): Promise<ActionResult> {
  if (!note.trim()) return { ok: false, message: 'Eine Begründung ist Pflicht.' };
  if (sourceId === targetId) return { ok: false, message: 'Quelle und Ziel sind dasselbe Konto.' };

  // A deleted target is treated as absent, not as a target: moving credit and receipts onto a row
  // that only survives for the tax record would put them somewhere no token can reach and quietly
  // undo the deletion in the ledger.
  const exists = await env.DB.prepare("SELECT id FROM wallets WHERE id = ? AND status != 'deleted'")
    .bind(targetId)
    .first();
  if (!exists) return { ok: false, message: 'Zielkonto nicht gefunden.' };

  const source = await walletStub(env, sourceId).state();
  const target = await walletStub(env, targetId).absorb(source);
  const drained = await walletStub(env, sourceId).drain();

  await env.DB.batch([
    env.DB.prepare(
      'UPDATE wallets SET seconds_left = ?, rewords_left = ?, seconds_bought = ?, seconds_used = ? WHERE id = ?',
    ).bind(target.secondsLeft, target.rewordsLeft, target.secondsBought, target.secondsUsed, targetId),
    env.DB.prepare(
      "UPDATE wallets SET seconds_left = 0, rewords_left = 0, status = 'blocked' WHERE id = ?",
    ).bind(sourceId),
    // Tokens follow the credit, so the device that was recovered onto keeps working afterwards.
    env.DB.prepare('UPDATE tokens SET wallet_id = ? WHERE wallet_id = ?').bind(targetId, sourceId),
    env.DB.prepare('UPDATE purchases SET wallet_id = ? WHERE wallet_id = ?').bind(targetId, sourceId),
  ]);

  await log(env, admin, sourceId, 'merge_out', -source.secondsLeft, -source.rewordsLeft, `${note} → ${targetId}`);
  await log(env, admin, targetId, 'merge_in', source.secondsLeft, source.rewordsLeft, `${note} ← ${sourceId}`);
  void drained;
  return { ok: true, message: `${Math.floor(source.secondsLeft / 60)} Minuten verschoben.` };
}

/** Free-text note on an account, for whatever the log cannot say. */
export async function setNote(
  env: Env,
  admin: AdminIdentity,
  walletId: string,
  note: string,
): Promise<ActionResult> {
  await env.DB.prepare('UPDATE wallets SET note = ? WHERE id = ?')
    .bind(note.slice(0, 500), walletId)
    .run();
  await log(env, admin, walletId, 'note', 0, 0, note.slice(0, 500));
  return { ok: true, message: 'Notiz gespeichert.' };
}

/**
 * The emergency stop for the whole service.
 *
 * While set, every metered request answers 503 and the app falls back to "temporarily unavailable".
 * Blunt on purpose: the worst outcome it has to prevent is an unbounded bill, and an annoyed day is
 * cheaper than an emptied account.
 */
export async function setKillSwitch(
  env: Env,
  admin: AdminIdentity,
  killed: boolean,
  note: string,
): Promise<ActionResult> {
  if (!note.trim()) return { ok: false, message: 'Eine Begründung ist Pflicht.' };
  await guardStub(env).setKilled(killed, today());
  await log(env, admin, null, killed ? 'kill_on' : 'kill_off', 0, 0, note);

  // The only admin action that gets its own alert. The rest you performed yourself and do not need
  // mailed back to you — but this one changes the state of the whole service and, unlike the daily
  // budget, it does not clear itself at midnight. A stop switched on and forgotten is a service
  // that is down all weekend for no reason anyone remembers.
  await raise(env, {
    kind: 'kill_switch',
    severity: killed ? 'critical' : 'notice',
    value: killed ? 1 : 0,
    title: killed ? 'Notaus aktiviert — der Dienst nimmt nichts mehr an' : 'Notaus aufgehoben',
    detail: killed
      ? `Von ${admin.email} gestoppt: „${note.trim()}". Alle Anfragen werden ab sofort mit 503 abgewiesen, ` +
        `bis das Notaus von Hand wieder ausgeschaltet wird. Ein Tageswechsel hebt es nicht auf.`
      : `Von ${admin.email} wieder freigegeben: „${note.trim()}". Der Dienst nimmt wieder Anfragen an.`,
    dedupeKey: `kill_switch:${killed ? 'on' : 'off'}:${Date.now()}`,
  });

  return { ok: true, message: killed ? 'Dienst gestoppt.' : 'Dienst läuft wieder.' };
}

/**
 * Marks an account as one of yours, or removes the mark.
 *
 * For everything the automatic detection cannot see: an account you set up through a friend's
 * phone, a demo device, a refund case you want out of the averages. Purchases made by a Play
 * licence tester are recognised on their own — this is the manual override for the rest.
 */
export async function setTestAccount(
  env: Env,
  admin: AdminIdentity,
  walletId: string,
  isTest: boolean,
  note: string,
): Promise<ActionResult> {
  if (!note.trim()) return { ok: false, message: 'Eine Begründung ist Pflicht.' };
  await env.DB.prepare('UPDATE wallets SET is_test = ?, test_reason = ? WHERE id = ?')
    .bind(isTest ? 1 : 0, isTest ? 'manual' : null, walletId)
    .run();
  await log(env, admin, walletId, isTest ? 'mark_test' : 'unmark_test', 0, 0, note);
  return {
    ok: true,
    message: isTest
      ? 'Als Testkonto markiert — ab sofort aus allen Geldzahlen ausgenommen.'
      : 'Markierung entfernt — das Konto zählt wieder als echt.',
  };
}

/**
 * Ask Google again what one order was worth.
 *
 * The one action here that requires no reason, because it does not decide anything: it fetches a
 * figure that belongs to Google and writes down whatever comes back. It exists because the developer
 * share arrives after the sale — sometimes hours after — and waiting for the nightly pass to find
 * out whether a purchase is merely late or genuinely unreadable is a poor way to spend a day.
 *
 * Logged all the same, with the answer as the note: the ledger changing without a trace is how the
 * numbers stop adding up later.
 */
export async function refreshOrder(
  env: Env,
  admin: AdminIdentity,
  walletId: string,
  purchaseToken: string,
): Promise<ActionResult> {
  if (!purchaseToken.trim()) return { ok: false, message: 'Kein Kauf angegeben.' };

  const outcome = await syncOrderForPurchase(env, purchaseToken.trim());
  await log(env, admin, walletId || null, 'refetch_order', 0, 0, outcome.message);
  return { ok: outcome.ok, message: outcome.message };
}

async function syncToD1(env: Env, walletId: string, state: {
  secondsLeft: number; rewordsLeft: number; secondsBought: number; secondsUsed: number;
}): Promise<void> {
  await env.DB.prepare(
    'UPDATE wallets SET seconds_left = ?, rewords_left = ?, seconds_bought = ?, seconds_used = ? WHERE id = ?',
  ).bind(state.secondsLeft, state.rewordsLeft, state.secondsBought, state.secondsUsed, walletId).run();
}

async function log(
  env: Env,
  admin: AdminIdentity,
  walletId: string | null,
  action: string,
  deltaSecs: number,
  deltaWords: number,
  note: string,
): Promise<void> {
  await env.DB.prepare(
    `INSERT INTO admin_log (ts, actor, wallet_id, action, delta_secs, delta_words, note)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
  ).bind(Date.now(), admin.email, walletId, action, deltaSecs, deltaWords, note.slice(0, 500)).run();
}
