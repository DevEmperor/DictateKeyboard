import { DurableObject } from 'cloudflare:workers';
import type { Env } from './config';

/**
 * The lid over everything.
 *
 * Each individual wallet is bounded by what it bought — that is the real protection. This
 * object catches the rest: a bug in our own code, a price jump at Cloudflare, anything nobody saw
 * coming. Once the daily budget is reached the service answers 503 and the app falls back to
 * "temporarily unavailable".
 *
 * The worst imaginable outcome is therefore an annoying day — not an emptied account.
 *
 * There is exactly one instance (`getByName('global')`). At this service's size the one extra
 * call per request is nothing; if it ever gets tight, the check can run against a cached
 * value with only the accumulation coming here.
 */

export interface GuardState {
  day: string;
  spentNano: number;
  requests: number;
  /** Set by hand in the dashboard — stops the service regardless of the budget. */
  killed: boolean;
  /**
   * Budget percentages already announced today.
   *
   * Has to live here and nowhere else. Two requests can cross 80 % in the same millisecond, and
   * only inside this object is "did anyone report it yet" a question with one answer. Checking it
   * against the database afterwards would send the mail twice on exactly the busy day when the
   * threshold matters.
   */
  notified: number[];
}

const FRESH = (day: string): GuardState => ({
  day, spentNano: 0, requests: 0, killed: false, notified: [],
});

export class GlobalGuard extends DurableObject<Env> {
  private async read(day: string): Promise<GuardState> {
    const stored = await this.ctx.storage.get<GuardState>('state');
    // Day rollover: the counter starts fresh, the kill switch deliberately stays.
    if (!stored || stored.day !== day) {
      const fresh = { ...FRESH(day), killed: stored?.killed ?? false };
      await this.ctx.storage.put('state', fresh);
      return fresh;
    }
    // Written before `notified` existed, so it is absent on the first read after a deploy.
    return stored.notified ? stored : { ...stored, notified: [] };
  }

  async state(day: string): Promise<GuardState> {
    return this.read(day);
  }

  /**
   * Asks whether money may still be spent today, and books it at the same time.
   *
   * Works off the estimated upstream cost of the request. The estimate is allowed to be too
   * high — [settle] corrects it downwards once the real usage is known.
   *
   * `steps` are the budget percentages worth reporting. When this request is the one that carries
   * the day past one of them, the highest newly reached step comes back in `crossed` — and is
   * marked as reported here, so a hundred parallel requests produce exactly one announcement. The
   * mail itself is sent by the caller: a Durable Object holding the whole service's spending lock
   * has no business waiting on a mail server.
   */
  async spend(day: string, estimateNano: number, budgetNano: number, steps: number[] = []): Promise<{
    allowed: boolean;
    state: GuardState;
    crossed: number | null;
  }> {
    const state = await this.read(day);
    if (state.killed) return { allowed: false, state, crossed: null };

    // A refusal reports as the full 100 %, even though the counter may stand at 92: the estimate
    // was never booked, so the level does not move, yet the service is now turning customers away.
    // Reporting the counter here would mean the one event that matters most — the cap actually
    // biting — is the only one that never sends a mail.
    if (state.spentNano + estimateNano > budgetNano) {
      const crossed = this.newlyCrossed(state, budgetNano, steps, budgetNano);
      if (crossed !== null) {
        const marked = { ...state, notified: [...state.notified, crossed] };
        await this.ctx.storage.put('state', marked);
        return { allowed: false, state: marked, crossed };
      }
      return { allowed: false, state, crossed: null };
    }

    const spentNano = state.spentNano + estimateNano;
    const crossed = this.newlyCrossed(state, budgetNano, steps, spentNano);
    const next: GuardState = {
      ...state,
      spentNano,
      requests: state.requests + 1,
      notified: crossed === null ? state.notified : [...state.notified, crossed],
    };
    await this.ctx.storage.put('state', next);
    return { allowed: true, state: next, crossed };
  }

  /**
   * The highest threshold this level has reached and nobody has reported yet.
   *
   * Highest rather than each in turn: one request can jump from 40 % to 90 %, and three mails
   * describing the same moment are two mails too many.
   */
  private newlyCrossed(
    state: GuardState,
    budgetNano: number,
    steps: number[],
    spentNano: number,
  ): number | null {
    if (budgetNano <= 0 || steps.length === 0) return null;
    const percent = (spentNano / budgetNano) * 100;
    let highest: number | null = null;
    for (const step of steps) {
      if (percent >= step && !state.notified.includes(step)) highest = step;
    }
    return highest;
  }

  /** Corrects the day's spend once the real cost is known. */
  async settle(day: string, deltaNano: number): Promise<GuardState> {
    const state = await this.read(day);
    const next: GuardState = { ...state, spentNano: Math.max(0, state.spentNano + deltaNano) };
    await this.ctx.storage.put('state', next);
    return next;
  }

  /**
   * Counts recovery-code attempts per client address, and says whether one more is allowed.
   *
   * Lives in this object rather than a class of its own for a dull reason that turned out to
   * matter: a freshly added Durable Object class answered every call with "internal error", and a
   * throttle that cannot be demonstrated is worse than none. This object already exists and is
   * already exercised on every metered request, so the count is provably reaching storage.
   *
   * Cloudflare's own rate-limiting binding was tried first. Its documentation calls it "permissive,
   * eventually consistent, and intentionally designed to not be used as an accurate accounting
   * system", and twenty-five attempts against a limit of ten went through untouched. Fine for
   * shedding load, useless as a security control.
   *
   * Kept apart from the day's spending state on purpose: the storage key is separate, so a busy
   * budget write and a code attempt never overwrite one another.
   */
  async attemptCode(
    key: string,
    limit: number,
    windowMs: number,
  ): Promise<{ allowed: boolean; recent: number; failures: number }> {
    const now = Date.now();
    const stored = (await this.ctx.storage.get<Record<string, number[]>>('codeAttempts')) ?? {};
    const cutoff = now - windowMs;

    const recent = (stored[key] ?? []).filter((t) => t > cutoff);
    // The attempt counts even when it is refused: somebody hammering the endpoint keeps their own
    // window full and gains nothing by carrying on.
    recent.push(now);

    // Addresses nobody has used inside the window are dropped, so a burst from many addresses
    // cannot grow this object without bound.
    const next: Record<string, number[]> = { [key]: recent };
    for (const [address, times] of Object.entries(stored)) {
      if (address === key) continue;
      const kept = times.filter((t) => t > cutoff);
      if (kept.length > 0) next[address] = kept;
    }

    await this.ctx.storage.put('codeAttempts', next);
    return {
      allowed: recent.length <= limit,
      recent: recent.length,
      failures: await this.recentCodeFailures(windowMs),
    };
  }

  /**
   * Records a code that did not match, and answers how many have not matched lately.
   *
   * The per-address limit above is the wrong shape for a botnet: it is a *per-address* limit, so a
   * thousand addresses simply multiply it. This counter has no key at all, which is the point —
   * it cannot be spread across more machines.
   *
   * Failures rather than attempts, because that is what separates the two populations. Someone
   * restoring their own account pastes the code and it works; a run of misses is a shape that only
   * guessing produces. Counting attempts would put ordinary use and an attack in the same bucket
   * and force the limit up until it stopped meaning anything.
   */
  async noteCodeFailure(windowMs: number): Promise<number> {
    const now = Date.now();
    const cutoff = now - windowMs;
    const kept = ((await this.ctx.storage.get<number[]>('codeFailures')) ?? []).filter((t) => t > cutoff);
    kept.push(now);
    await this.ctx.storage.put('codeFailures', kept);
    return kept.length;
  }

  private async recentCodeFailures(windowMs: number): Promise<number> {
    const cutoff = Date.now() - windowMs;
    return ((await this.ctx.storage.get<number[]>('codeFailures')) ?? []).filter((t) => t > cutoff).length;
  }

  async setKilled(killed: boolean, day: string): Promise<GuardState> {
    const state = await this.read(day);
    const next: GuardState = { ...state, killed };
    await this.ctx.storage.put('state', next);
    return next;
  }
}
