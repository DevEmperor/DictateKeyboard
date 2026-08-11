import type { Env } from './config';

/**
 * Answers from outside, kept so they are not fetched twice a minute.
 *
 * The dashboard used to ask OpenAI's billing endpoint **twice on every page load** — once for the
 * summary and once for the finance panel — and each of those walks up to a dozen pages one after
 * the other. That is the whole reason opening the page felt slow, and none of it bought anything:
 * billing figures are not live, they settle over hours.
 *
 * The strategy is **stale-while-revalidate**. A stored answer is handed back immediately, however
 * old; if it has passed its age the refresh runs *behind* the response through `waitUntil`. The
 * reader waits for the network at most once, when there is nothing stored at all.
 *
 * D1 rather than the Cache API on purpose: a cache entry per data centre would mean the first
 * visit from each is slow again, and this table is read once per page view.
 */

interface Entry<T> {
  value: T;
  fetchedAt: number;
  /** True when the stored copy was past its age and a refresh is running behind this response. */
  stale: boolean;
}

export async function cached<T>(
  env: Env,
  key: string,
  ttlMs: number,
  load: () => Promise<T>,
  ctx?: ExecutionContext,
): Promise<Entry<T>> {
  const row = await env.DB.prepare('SELECT payload, fetched_at AS fetchedAt FROM cache WHERE key = ?')
    .bind(key)
    .first<{ payload: string; fetchedAt: number }>();

  if (row) {
    let value: T | null = null;
    try {
      value = JSON.parse(row.payload) as T;
    } catch {
      value = null;
    }

    if (value !== null) {
      const age = Date.now() - row.fetchedAt;
      if (age <= ttlMs) return { value, fetchedAt: row.fetchedAt, stale: false };

      // Past its age but still perfectly readable. Serve it and refresh behind the response —
      // without a context to hang the refresh on there is nowhere to run it, so fall through and
      // pay for the fetch.
      if (ctx) {
        ctx.waitUntil(refresh(env, key, load).then(() => undefined).catch(() => undefined));
        return { value, fetchedAt: row.fetchedAt, stale: true };
      }
    }
  }

  const fresh = await refresh(env, key, load);
  return { value: fresh, fetchedAt: Date.now(), stale: false };
}

/** Forces a reload regardless of age — for the refresh button and the nightly warm-up. */
export async function refresh<T>(env: Env, key: string, load: () => Promise<T>): Promise<T> {
  const value = await load();
  await env.DB.prepare(
    'INSERT OR REPLACE INTO cache (key, payload, fetched_at) VALUES (?, ?, ?)',
  ).bind(key, JSON.stringify(value), Date.now()).run();
  return value;
}

/** Drops an entry, so the next read is guaranteed to go out to the source. */
export async function invalidate(env: Env, key: string): Promise<void> {
  await env.DB.prepare('DELETE FROM cache WHERE key = ?').bind(key).run();
}
