import type { Env } from './config';

/**
 * Letting the detail log age out.
 *
 * `usage_log` holds one row per request and would otherwise grow without end. The daily totals
 * are what the dashboard's history is actually built from, and those stay forever — so deleting
 * the individual rows costs no statistic, only the ability to look at a single request from
 * months ago, which nobody does.
 *
 * Two reasons, and the second is the real one. It keeps the database small, and it satisfies
 * storage limitation: metadata that no longer serves a purpose should not still be lying around.
 * The audit trail in `admin_log` is deliberately exempt — the point of it is that the numbers
 * still add up years later.
 */
export const DEFAULT_RETENTION_DAYS = 90;

/** How many rows one DELETE takes, and how many rounds a single run will do. */
const BATCH = 1000;
const MAX_ROUNDS = 50;

export async function pruneUsageLog(env: Env): Promise<{ deleted: number; cutoff: number }> {
  const days = Number(env.USAGE_RETENTION_DAYS) || DEFAULT_RETENTION_DAYS;
  const cutoff = Date.now() - days * 86_400_000;

  // Deleted in batches rather than one sweep: a single statement over a long-neglected table
  // could run past the request's time budget and then roll back, achieving nothing. In batches
  // an interrupted run has still made progress, and the next night carries on.
  let deleted = 0;
  for (let round = 0; round < MAX_ROUNDS; round++) {
    const result = await env.DB.prepare(
      'DELETE FROM usage_log WHERE id IN (SELECT id FROM usage_log WHERE ts < ? LIMIT ?)',
    )
      .bind(cutoff, BATCH)
      .run();
    const changes = result.meta?.changes ?? 0;
    deleted += changes;
    if (changes < BATCH) break;
  }

  return { deleted, cutoff };
}

/**
 * How long the pseudonym of the buying Play account outlives a deleted credit account.
 *
 * Deletion removes everything that leads back to a person — with one exception, kept on purpose:
 * the hash of Google's per-app pseudonym for the buyer. It is what recognises a second refund from
 * the same person after they started over with a fresh account, and clearing it on deletion would
 * turn deleting into the last step of the trick rather than the end of the relationship.
 *
 * An exception has to be bounded to be defensible, which is what this is for. Two years is far
 * longer than any refund or chargeback window and far shorter than the ten years the receipts
 * themselves must be kept; after it, the last thread back to a person is cut and what remains is
 * an order number and an amount.
 *
 * The hash was never reversible: it answers "the same buyer as before" and nothing else.
 */
export const PLAY_HASH_RETENTION_DAYS = 730;

/** Cuts that last thread once the window has passed. Runs with the nightly sweep. */
export async function pruneDeletedWallets(env: Env): Promise<{ cleared: number; cutoff: number }> {
  const cutoff = Date.now() - PLAY_HASH_RETENTION_DAYS * 86_400_000;
  const result = await env.DB.prepare(
    `UPDATE wallets SET play_account_hash = NULL
      WHERE status = 'deleted' AND play_account_hash IS NOT NULL AND deleted_at < ?`,
  ).bind(cutoff).run();

  return { cleared: result.meta?.changes ?? 0, cutoff };
}
