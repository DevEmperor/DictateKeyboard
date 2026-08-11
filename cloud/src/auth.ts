import type { Env } from './config';
import { sha256 } from './util';

/**
 * Who is asking?
 *
 * The client sends its wallet token as `Authorization: Bearer …` — exactly where every other
 * provider expects an API key. That is why the app's existing `OpenAiCompatibleClient` works
 * without a single change: to it, the credit token is simply a key.
 *
 * Only the SHA-256 of the token is stored. Whoever gets hold of the database cannot sign in
 * with it.
 */

export interface Session {
  walletId: string;
  tokenHash: string;
  /**
   * Whether this is one of your own test accounts.
   *
   * Carried on the session because the join to `wallets` happens here anyway — reading one more
   * column costs nothing, whereas asking again in the metering path would be an extra query on
   * every single request.
   */
  isTest: boolean;
}

export async function authenticate(request: Request, env: Env): Promise<Session | null> {
  const header = request.headers.get('authorization') ?? '';
  const token = header.toLowerCase().startsWith('bearer ') ? header.slice(7).trim() : '';
  if (!token) return null;

  const tokenHash = await sha256(token);
  const row = await env.DB.prepare(
    `SELECT t.wallet_id AS walletId, w.is_test AS isTest
       FROM tokens t
       JOIN wallets w ON w.id = t.wallet_id
      WHERE t.token_hash = ? AND t.revoked_at IS NULL AND w.status != 'deleted'`,
  )
    .bind(tokenHash)
    .first<{ walletId: string; isTest: number }>();

  return row ? { walletId: row.walletId, tokenHash, isTest: row.isTest === 1 } : null;
}

/**
 * Why authentication failed — for the one caller that has to act differently on each answer.
 *
 * A token that no longer works means one of two things, and to the app they are opposites. If the
 * account was deleted (here, on the web page or from another device), the app should forget all of
 * it, recovery code included: the code opens nothing any more, and leaving it on screen offers a
 * way back to something that no longer exists. If only *this device* was signed out from the
 * dashboard, the account is alive and the recovery code is the way back — throwing it away would
 * destroy the user's only copy over an action that was meant to be reversible.
 *
 * `authenticate` cannot tell them apart, because deleting an account deletes its tokens: both end
 * up as "no row". Asking a second time without the revocation filter separates the two, and it only
 * ever runs on the failure path.
 */
export async function authFailure(request: Request, env: Env): Promise<'device_revoked' | 'invalid_token'> {
  const header = request.headers.get('authorization') ?? '';
  const token = header.toLowerCase().startsWith('bearer ') ? header.slice(7).trim() : '';
  if (!token) return 'invalid_token';

  const row = await env.DB.prepare('SELECT revoked_at AS revokedAt FROM tokens WHERE token_hash = ?')
    .bind(await sha256(token))
    .first<{ revokedAt: number | null }>();

  return row?.revokedAt ? 'device_revoked' : 'invalid_token';
}

/**
 * Keeps "last seen" up to date.
 *
 * Runs through `waitUntil`, because nobody should wait on a statistics write. The column
 * carries the dashboard: it answers "how many accounts are still in use".
 */
export function touch(env: Env, session: Session, ctx: ExecutionContext): void {
  const now = Date.now();
  ctx.waitUntil(
    env.DB.batch([
      env.DB.prepare('UPDATE tokens SET last_seen_at = ? WHERE token_hash = ?').bind(now, session.tokenHash),
      env.DB.prepare('UPDATE wallets SET last_seen_at = ? WHERE id = ?').bind(now, session.walletId),
    ]).then(() => undefined),
  );
}
