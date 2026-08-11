import { cached } from './cache';
import type { Env } from './config';

/**
 * What OpenAI says it charged.
 *
 * Our own tally in `usage_log` is exact for what passed through this Worker, but it is a
 * *calculation* from a price list written down in `config.ts`. If OpenAI changes that list, the
 * calculation keeps agreeing with itself and quietly stops agreeing with the invoice. This is the
 * second opinion — and the rule that watches the gap between the two is the only thing that would
 * ever notice a price rise.
 *
 * Lives here rather than under `admin/` because two very different callers need it now: the
 * dashboard and the alert rules. It was also the single biggest reason the dashboard felt slow —
 * fetched twice per page load, up to a dozen sequential requests each time. Everything goes
 * through the cache below.
 */

/** Long enough that clicking around never refetches, short enough to feel current. */
const TTL_MS = 10 * 60 * 1000;

/**
 * Anything from an external API or a SUM() becomes a real number here.
 *
 * Both lie in the same direction: OpenAI sends amounts as strings, and SQLite hands back a string
 * whenever a sum outgrows what fits comfortably in a double. Either one turns arithmetic into
 * concatenation without complaining, and the mistake only shows up somewhere far away.
 */
export function num(value: unknown): number {
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) ? n : 0;
}

export interface OpenAiCosts {
  connected: boolean;
  reason?: string;
  days: Array<{ day: string; usd: number }>;
  byProject: Array<{ id: string; name: string; usd: number }>;
  serviceProject: { id: string; name: string } | null;
  /** This service's own project only. Null when it could not be identified. */
  serviceUsd: number | null;
  totalUsd: number;
}

const EMPTY = (reason: string): OpenAiCosts => ({
  connected: false, reason, days: [], byProject: [], serviceProject: null,
  serviceUsd: null, totalUsd: 0,
});

/** The cached view. `ctx` lets an expired entry refresh behind the response instead of blocking. */
export async function openaiCosts(
  env: Env,
  days = 30,
  ctx?: ExecutionContext,
): Promise<OpenAiCosts & { fetchedAt: number; stale: boolean }> {
  const entry = await cached<OpenAiCosts>(
    env,
    `openai:costs:${days}`,
    TTL_MS,
    () => fetchOpenAiCosts(env, days),
    ctx,
  );
  return { ...entry.value, fetchedAt: entry.fetchedAt, stale: entry.stale };
}

/**
 * The uncached fetch.
 *
 * Needs an **admin** key, which is a different thing from the project key that pays for requests —
 * organisation-wide and read-only for billing. Without it everything downstream falls back to our
 * own tally, which is exact for what passed through here but blind to anything else on the account.
 */
export async function fetchOpenAiCosts(env: Env, days = 30): Promise<OpenAiCosts> {
  if (!env.OPENAI_ADMIN_KEY) return EMPTY('kein Admin-Schlüssel hinterlegt');

  const auth = { authorization: `Bearer ${env.OPENAI_ADMIN_KEY}` };
  const startTime = Math.floor((Date.now() - days * 86_400_000) / 1000);

  try {
    // Grouped by project, because the account total answers the wrong question. What matters here
    // is "what did *this service* cost", and an organisation-wide figure next to Play revenue
    // invites exactly the comparison that is not true.
    const raw: Array<{ start_time: number; results?: Array<Record<string, unknown>> }> = [];
    let page: string | null = null;
    // Buckets come back oldest-first and paginated. Stopping at the first page therefore drops the
    // *newest* days — today above all, which is the one anyone actually looks for.
    for (let guard = 0; guard < 12; guard++) {
      const url = new URL('https://api.openai.com/v1/organization/costs');
      url.searchParams.set('start_time', String(startTime));
      url.searchParams.set('bucket_width', '1d');
      url.searchParams.set('limit', '180');
      url.searchParams.append('group_by', 'project_id');
      if (page) url.searchParams.set('page', page);

      const response = await fetch(url, { headers: auth });
      if (!response.ok) return EMPTY(`OpenAI antwortete ${response.status}`);

      const body = (await response.json()) as {
        data?: typeof raw; has_more?: boolean; next_page?: string | null;
      };
      raw.push(...(body.data ?? []));
      if (!body.has_more || !body.next_page) break;
      page = body.next_page;
    }

    const perDay = new Map<string, number>();
    const perProject = new Map<string, number>();
    let total = 0;

    for (const bucket of raw) {
      const day = new Date(bucket.start_time * 1000).toISOString().slice(0, 10);
      for (const result of bucket.results ?? []) {
        // `value` arrives as a string, not a number — unconverted it would concatenate.
        const usd = num((result.amount as { value?: unknown } | undefined)?.value);
        const project = String(result.project_id ?? 'ohne Projekt');
        perDay.set(day, (perDay.get(day) ?? 0) + usd);
        perProject.set(project, (perProject.get(project) ?? 0) + usd);
        total += usd;
      }
    }

    const names = await projectNames(env, auth);
    const projects = [...perProject.entries()]
      .map(([id, usd]) => ({ id, name: names.get(id) ?? id, usd }))
      .sort((a, b) => b.usd - a.usd);

    // Which project is this service. Pinned by id if configured, otherwise matched by name — and
    // null rather than "the whole account" when neither works, because overstating the cost of the
    // service is the one direction that would flatter the profit figure.
    const mine = env.OPENAI_PROJECT_ID
      ? projects.find((p) => p.id === env.OPENAI_PROJECT_ID)
      : projects.find((p) => /dictate/i.test(p.name));

    return {
      connected: true,
      days: [...perDay.entries()].sort().map(([day, usd]) => ({ day, usd })),
      byProject: projects,
      serviceProject: mine ? { id: mine.id, name: mine.name } : null,
      serviceUsd: mine ? mine.usd : null,
      totalUsd: total,
    };
  } catch (error) {
    return EMPTY(String(error).slice(0, 120));
  }
}

/** Project ids are opaque; the names make the breakdown readable. Failure here is cosmetic. */
async function projectNames(env: Env, auth: Record<string, string>): Promise<Map<string, string>> {
  const map = new Map<string, string>();
  try {
    const response = await fetch('https://api.openai.com/v1/organization/projects?limit=100', { headers: auth });
    if (!response.ok) return map;
    const body = (await response.json()) as { data?: Array<{ id?: string; name?: string }> };
    for (const p of body.data ?? []) if (p.id) map.set(p.id, p.name ?? p.id);
  } catch {
    // ignored on purpose
  }
  return map;
}
