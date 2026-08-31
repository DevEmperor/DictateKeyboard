import { raise } from './alerts';
import { limitsFrom, type Env, type Limits } from './config';
import { alertSettings } from './settings';
import { today } from './util';
import type { Wallet } from './wallet';

/**
 * Bookkeeping: the daily budget, the ledger, and the copy of the balance.
 *
 * Everything here writes numbers only — wallet, timestamp, duration, status code. No audio,
 * no text, no prompts. That is not thrift but the core of the privacy promise: what is never
 * stored cannot leak and does not have to be deleted.
 */

/**
 * Every Durable Object in this service is created in the **EU**.
 *
 * Without a jurisdiction, an object is created wherever its first request happens to arrive, so the
 * authoritative balances could end up on any continent. D1 has been pinned to Western Europe from
 * the start (`--location weur`); this makes the same true of the objects, and therefore makes the
 * sentence "the data is stored in the EU" true of the *whole* service rather than most of it.
 *
 * **What this does not do:** it does not remove the third-country transfer. Cloudflare, Inc. is a
 * US company and can access the data it processes from there — and access alone is a transfer.
 * That is carried by the standard contractual clauses in Cloudflare's DPA and would be, wherever
 * the bytes sit. This is about the accuracy of a statement, not about a legal problem.
 *
 * **Why it had to happen before the first sale:** an id derived with a jurisdiction is a *different*
 * id. Switching does not move an object, it points at a new and empty one. Doing this while only
 * test wallets exist costs a reset; doing it later would mean migrating balances people paid for.
 */
/**
 * **The local runtime cannot do this.** `wrangler dev` runs workerd on its own, without the
 * placement service that implements jurisdictions, and answers `jurisdiction()` with
 * "not implemented in workerd" — which would take every Durable Object access down with it and
 * leave the project undevelopable offline.
 *
 * So it falls back, once, and says so in the log. The fallback is deliberately noisy rather than
 * silent: if this ever prints in production, the EU guarantee is not in force and the sentence in
 * the privacy policy is wrong. [durableObjectPlacement] surfaces the same fact in the dashboard,
 * so it can be checked after a deploy instead of assumed.
 */
let jurisdictionAvailable: boolean | null = null;

function eu<T extends Rpc.DurableObjectBranded | undefined>(
  namespace: DurableObjectNamespace<T>,
): DurableObjectNamespace<T> {
  if (jurisdictionAvailable === false) return namespace;
  try {
    const scoped = namespace.jurisdiction('eu');
    jurisdictionAvailable = true;
    return scoped;
  } catch (error) {
    if (jurisdictionAvailable === null) {
      console.log(`durable object jurisdiction unavailable, objects are unrestricted: ${String(error).slice(0, 120)}`);
    }
    jurisdictionAvailable = false;
    return namespace;
  }
}

/** Where the objects actually live, for the dashboard. Cheap: it creates no object. */
export function durableObjectPlacement(env: Env): 'eu' | 'unrestricted' {
  try {
    env.WALLET.jurisdiction('eu');
    return 'eu';
  } catch {
    return 'unrestricted';
  }
}

export function walletStub(env: Env, walletId: string): DurableObjectStub<Wallet> {
  return eu(env.WALLET).getByName(walletId);
}

/**
 * There is exactly one guard object for the whole service.
 *
 * A single Durable Object serving every request is normally a bottleneck — accepted here on
 * purpose, because a spending limit is only a limit if it counts exactly. It does two tiny
 * storage operations per request, which carries far beyond this service's needs. Should it
 * ever get tight, the way out is to shard the guard across N objects, each holding an Nth of
 * the budget.
 */
export function guardStub(env: Env) {
  return eu(env.GLOBAL).getByName('global');
}

/**
 * Asks the daily cap and books the estimated cost in one go.
 *
 * If this request is the one that carried the day past a threshold, the guard says so and the
 * warning goes out from here — behind the response, so nobody's dictation waits on a mail server.
 */
export async function budgetAllows(
  env: Env,
  limits: Limits,
  estimateNano: number,
  ctx: ExecutionContext,
): Promise<boolean> {
  // Memoised per isolate for a minute, so this costs a database read roughly once a minute rather
  // than once per dictation — see settings.ts.
  const settings = await alertSettings(env);
  const steps = settings.enabled ? settings.budgetSteps : [];
  // The dashboard's figure where one has been set, the deployment's otherwise. Read here rather
  // than from `limits` so raising the ceiling takes a minute instead of a deployment — which
  // matters precisely when it is reached, because until then the service is answering 503.
  const budgetNano = Math.round(settings.dailyBudgetUsd * 1_000_000_000);
  const result = await guardStub(env).spend(today(), estimateNano, budgetNano, steps);

  if (result.crossed !== null) {
    const budgetUsd = budgetNano / 1_000_000_000;
    const spentUsd = result.state.spentNano / 1_000_000_000;
    const full = result.crossed >= 100;
    ctx.waitUntil(raise(env, {
      kind: 'budget',
      // Half the budget is worth knowing, not worth waking up for. From 80 % on it is: at that
      // point the day has a good chance of ending in 503s for everyone.
      severity: result.crossed >= 80 ? 'critical' : 'notice',
      value: result.crossed,
      title: full
        ? 'Tagesbudget erschöpft — der Dienst lehnt Anfragen ab'
        : `Tagesbudget zu ${result.crossed} % verbraucht`,
      detail: full
        ? `Von ${budgetUsd.toFixed(2)} $ Tagesbudget sind ${spentUsd.toFixed(2)} $ gebucht, und weitere Anfragen ` +
          `werden mit 503 abgewiesen. Für die Kundschaft sieht das aus wie eine Störung. Bis Mitternacht UTC ` +
          `bleibt es so, sofern DAILY_BUDGET_USD nicht heraufgesetzt wird. Vorher lohnt der Blick, ob ein ` +
          `einzelnes Konto das Budget aufgebraucht hat oder ob es echtes Wachstum ist.`
        : `${spentUsd.toFixed(2)} $ von ${budgetUsd.toFixed(2)} $ sind heute gebucht. Bei 100 % antwortet der ` +
          `Dienst allen mit 503 — das Budget ist gemeinsam, nicht je Konto.`,
      // One announcement per step per day. The guard has already made sure only one request gets
      // here; the key stops a redeploy that resets the object from repeating it.
      dedupeKey: `budget:${today()}:${result.crossed}`,
    }, ctx).then(() => undefined));
  }

  return result.allowed;
}

/** Corrects the day's spend once the real cost is known (may be negative). */
export function settleBudget(env: Env, deltaNano: number, ctx: ExecutionContext): void {
  if (deltaNano === 0) return;
  ctx.waitUntil(guardStub(env).settle(today(), deltaNano).then(() => undefined));
}

export interface UsageEntry {
  walletId: string;
  /** Which device — see `usage_log.token_hash`. A pseudonym, joinable to `tokens`. */
  tokenHash?: string;
  /**
   * Your own testing rather than a customer's use.
   *
   * Routed into separate columns of the daily roll-up instead of being filtered out at read time,
   * because the roll-up is the only thing that survives the 90-day prune. A history that cannot
   * tell the two apart can never be corrected afterwards.
   */
  isTest?: boolean;
  kind: 'transcribe' | 'reword';
  /**
   * Who handled it, and with what. Left out here, the model is read from the environment and the
   * provider is what it can only be.
   *
   * Both are recorded although one of them is currently a constant. They answer different
   * questions: the provider answers the legal one (where did the content go), the model the
   * commercial one (at which price). Swapping gemma-4 for qwen3 moves the second and not the first.
   * A column that says what it is costs one word a row and means a day six months old can still be
   * recalculated without knowing what the code looked like then.
   */
  provider?: string;
  model?: string;
  seconds?: number;
  tokensIn?: number;
  tokensOut?: number;
  /**
   * Neurons × 10⁶, as *reported* by Workers AI, not as computed from a price list.
   *
   * A quantity and a price are two different things and both are kept: prices change, quantities do
   * not, and only the quantity can be held against Cloudflare's own count. Zero when a request
   * never reached a model — a refusal spends none, and that is a correct figure, not a missing one.
   */
  neuronsMicro?: number;
  costNano: number;
  status: number;
  ms: number;
  /** Balance after the booking — the copy list views in the dashboard read. */
  secondsLeft?: number;
  rewordsLeft?: number;
  secondsUsedTotal?: number;
}

/**
 * Which failures are *ours* and which are the service being at fault.
 *
 * A request refused because the account has no credit, is blocked, is asking too fast, or because
 * the day's budget is spent is not a malfunction — it is the system doing its job, and the person
 * on the other end has been told so. An outage is a different thing, and only that should show up
 * as an error rate.
 *
 * The distinction earns its keep twice: it keeps the "many errors" alert from firing every time a
 * few customers run out of credit at once, and it keeps the dashboard honest — a support question
 * ("why did it stop working on Tuesday?") is answered by a refusal, not by a fault.
 */
export function isServiceFault(status: number): boolean {
  return status >= 400 && ![402, 403, 429, 503].includes(status);
}

/**
 * Writes one ledger row, updates the daily total and mirrors the balance into D1.
 *
 * The authoritative balance stays in the Durable Object. This copy may lag by seconds — it
 * exists so the dashboard can fetch a list of a thousand accounts without talking to a
 * thousand objects.
 */
export function logUsage(env: Env, entry: UsageEntry, ctx: ExecutionContext): void {
  const now = Date.now();
  const day = today(now);
  const limits = limitsFrom(env);
  const provider = entry.provider ?? 'workers-ai';
  const model = entry.model ?? (entry.kind === 'transcribe' ? limits.transcribeModel : limits.chatModel);
  const neuronsMicro = Math.round(entry.neuronsMicro ?? 0);
  const statements: D1PreparedStatement[] = [
    env.DB.prepare(
      `INSERT INTO usage_log (wallet_id, token_hash, ts, kind, provider, model, seconds, tokens_in, tokens_out,
                              neurons_micro, cost_nano, status, ms)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).bind(
      entry.walletId,
      entry.tokenHash ?? null,
      now,
      entry.kind,
      provider,
      model,
      Math.round(entry.seconds ?? 0),
      entry.tokensIn ?? 0,
      entry.tokensOut ?? 0,
      neuronsMicro,
      entry.costNano,
      entry.status,
      entry.ms,
    ),
    env.DB.prepare(
      `INSERT INTO daily_totals (day, requests, seconds, rewords, cost_nano, cost_nano_cf, errors,
                                 neurons_micro, test_requests, test_seconds, test_cost_nano,
                                 test_neurons_micro)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(day) DO UPDATE SET
         requests           = requests           + excluded.requests,
         seconds            = seconds            + excluded.seconds,
         rewords            = rewords            + excluded.rewords,
         cost_nano          = cost_nano          + excluded.cost_nano,
         cost_nano_cf       = cost_nano_cf       + excluded.cost_nano_cf,
         errors             = errors             + excluded.errors,
         neurons_micro      = neurons_micro      + excluded.neurons_micro,
         test_requests      = test_requests      + excluded.test_requests,
         test_seconds       = test_seconds       + excluded.test_seconds,
         test_cost_nano     = test_cost_nano     + excluded.test_cost_nano,
         test_neurons_micro = test_neurons_micro + excluded.test_neurons_micro`,
    ).bind(
      day,
      // Every request lands in exactly one of the two sets of columns, never both — so the two
      // can be added together for "all traffic" and the real ones read on their own.
      entry.isTest ? 0 : 1,
      entry.isTest ? 0 : Math.round(entry.seconds ?? 0),
      entry.isTest || entry.kind !== 'reword' ? 0 : 1,
      entry.isTest ? 0 : entry.costNano,
      // The Workers AI share of the line above, so a mixed day can still be split once usage_log
      // has been pruned. Once both services have moved this equals cost_nano; during the changeover
      // it is the only thing that keeps the two apart in the surviving roll-up.
      !entry.isTest && provider === 'workers-ai' ? entry.costNano : 0,
      // Only genuine faults. A refusal is counted as a request but never as an error —
      // see isServiceFault.
      !entry.isTest && isServiceFault(entry.status) ? 1 : 0,
      entry.isTest ? 0 : neuronsMicro,
      entry.isTest ? 1 : 0,
      entry.isTest ? Math.round(entry.seconds ?? 0) : 0,
      entry.isTest ? entry.costNano : 0,
      // Test traffic is kept apart from the money but has to be added back for the free allowance:
      // Cloudflare's 10 000 neurons a day are account-wide and do not care whose request it was.
      // Anything reading the allowance therefore has to sum both columns, not just the first.
      entry.isTest ? neuronsMicro : 0,
    ),
  ];

  if (entry.secondsLeft !== undefined) {
    statements.push(
      env.DB.prepare(
        'UPDATE wallets SET seconds_left = ?, rewords_left = ?, seconds_used = ?, last_seen_at = ? WHERE id = ?',
      ).bind(
        entry.secondsLeft,
        entry.rewordsLeft ?? 0,
        entry.secondsUsedTotal ?? 0,
        now,
        entry.walletId,
      ),
    );
  }

  ctx.waitUntil(env.DB.batch(statements).then(() => undefined));
}
