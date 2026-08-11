import { raise } from './alerts';
import type { Env } from './config';
import { guardStub } from './meter';
import { apiError } from './util';

/**
 * The throttle on the two endpoints that accept a recovery code as proof of identity.
 *
 * **Why it is needed, with the actual numbers.** A code is twelve characters from a thirty-letter
 * alphabet — 5.3 × 10¹⁷ combinations, about 59 bits. Guessing one *particular* code is hopeless
 * forever. But an attacker does not need a particular one: any hit against any account will do, so
 * the odds scale with how many accounts exist. Unthrottled, at a million requests a second:
 *
 *   - 100 accounts        → first hit after ~168 years
 *   - 10 000 accounts     → ~2 years
 *   - 1 000 000 accounts  → under a year
 *
 * So it is not a problem today and becomes one at scale. Ten attempts a minute per address turns
 * even a ten-thousand-address botnet into roughly 1 700 guesses a second, which puts the last row
 * back into the decades. Someone typing in their own code never comes close to the limit.
 *
 * **This is not new with account deletion.** `/v1/wallet/restore` has always taken the same code
 * and hands back a token — that is, the balance itself. Being able to *destroy* an account is
 * strictly less useful to an attacker than being able to *spend* it. The deletion endpoint only
 * made the gap worth looking at, so the fix covers both.
 *
 * Counted in the GlobalGuard Durable Object rather than through Cloudflare's rate-limiting
 * binding — see `guard.ts`, `attemptCode`, for why the binding was not good enough here.
 */

const LIMIT = 10;
const WINDOW_MS = 60_000;

/**
 * The second limit, and the one a botnet cannot buy its way around.
 *
 * The per-address count multiplies with the number of addresses: ten a minute across a thousand
 * machines is ten thousand a minute. This one has no key, so it holds however the traffic is
 * spread — and it counts **failures**, which is what tells the two populations apart. Someone
 * restoring their own account pastes a code that works; a run of misses is a shape only guessing
 * produces.
 *
 * Sixty a minute is far above anything ordinary use produces — a mistyped code is rare, and the
 * app pastes rather than types — and far below what an attack needs. It caps a botnet at 86 400
 * guesses a day however many machines it has, against 14.4 million before. With the code's 5.3 ×
 * 10¹⁷ combinations that is not a narrower margin; it is a different arithmetic.
 *
 * The cost is honest and worth naming: while the breaker is tripped, someone genuinely restoring
 * an account waits up to a minute. Recovery is a rare act and a minute is survivable; an account
 * guessed open is not.
 */
const GLOBAL_FAILURE_LIMIT = 60;

/** Keyed by client address. The address is never stored beyond the sliding window. */
export async function guardCodeAttempts(env: Env, request: Request): Promise<Response | null> {
  const address = request.headers.get('cf-connecting-ip') ?? 'unknown';

  let allowed = true;
  try {
    const result = await guardStub(env).attemptCode(address, LIMIT, WINDOW_MS);
    allowed = result.allowed && result.failures < GLOBAL_FAILURE_LIMIT;
    // Only the refusals. A legitimate person types their code once or twice, so a line here means
    // somebody is probing — and the count says how hard. No address is logged: the number is the
    // signal, and the address would be personal data in a log that outlives the sliding window.
    if (!allowed) {
      console.log(
        `code throttle refused an attempt (${result.recent} from this address, ` +
        `${result.failures} failures overall in the last minute)`,
      );
    }
  } catch (error) {
    // The local runtime has no jurisdictions, and a throttle must never be the reason the service
    // stops working. Failing open is the right trade for a control whose job is to slow a guessing
    // attack, not to be the only thing between an attacker and the data.
    console.log(`code throttle unavailable, attempt not counted: ${String(error).slice(0, 120)}`);
    return null;
  }

  if (allowed) return null;
  return apiError(
    429,
    'Too many attempts. Please wait a minute and try again.',
    'rate_limited',
    'rate_limit_error',
  );
}

/**
 * Records that a code did not match, and reports it once the rate stops looking like typos.
 *
 * Called after the lookup rather than before it, because only then is it known whether this was a
 * person or a guess. The alert is what turns a silent control into something you find out about:
 * the breaker holding is good news, but not knowing it ever engaged is not.
 */
export async function noteCodeFailure(env: Env, ctx?: ExecutionContext): Promise<void> {
  let failures: number;
  try {
    failures = await guardStub(env).noteCodeFailure(WINDOW_MS);
  } catch {
    return;
  }
  if (failures < GLOBAL_FAILURE_LIMIT) return;

  // Hourly at most. While an attack runs every single request would qualify, and a mailbox with
  // four hundred copies of the same warning is a mailbox with none.
  await raise(env, {
    kind: 'code_guessing',
    severity: 'critical',
    value: failures,
    title: `${failures} falsche Wiederherstellungscodes in einer Minute`,
    detail:
      `So viele Fehlversuche entstehen nicht durch Vertippen — die App fügt den Code ein, statt ihn ` +
      `tippen zu lassen. Das ist der Versuch, Codes durchzuprobieren, und zwar verteilt: die Grenze je ` +
      `Adresse (10/min) hätte das allein nicht auffangen können. Die adressunabhängige Bremse greift ` +
      `ab jetzt und deckelt das Ganze auf ${GLOBAL_FAILURE_LIMIT} Fehlversuche je Minute, gleich über ` +
      `wie viele Rechner es läuft. Zu tun ist nichts: ein Code hat 5,3 × 10¹⁷ Möglichkeiten, gedeckelt ` +
      `bleibt selbst ein großes Botnetz aussichtslos. Nachsehen lohnt sich trotzdem — wer so viel Mühe ` +
      `investiert, probiert danach etwas anderes. Nebenwirkung: solange die Bremse hält, wartet auch ` +
      `eine echte Wiederherstellung bis zu einer Minute.`,
    dedupeKey: 'code_guessing',
  }, ctx);
}
