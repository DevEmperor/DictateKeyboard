import { alertThresholds, limitsFrom, type AlertThresholds, type Env } from './config';

/**
 * Settings that can be changed without a deployment.
 *
 * Everything here has a default in `wrangler.jsonc` and an optional override in the database. The
 * environment stays the source of the *defaults* — a fresh database behaves exactly like a
 * configured one, and nothing has to be seeded before the service works.
 *
 * Why not simply move them into the database entirely: the environment is what a deployment
 * carries with it. Leaving the fallbacks there means a wiped `settings` table degrades to sensible
 * behaviour instead of to no alerting at all, which is the failure mode that matters — nobody
 * notices that warnings stopped.
 *
 * **Reads are memoised per isolate for a minute.** These are consulted on the metering path, and a
 * database read on every dictation to find out whether alerts are switched on would be a poor
 * trade. The cost is that a change takes up to a minute to reach every isolate; for thresholds
 * that is irrelevant, and the dashboard says so where it matters.
 */

const TTL_MS = 60_000;
let memo: { at: number; values: Record<string, string> } | null = null;

export interface AlertSettings extends AlertThresholds {
  /**
   * The day's ceiling on upstream spend, in dollars.
   *
   * The one setting here that is not about warnings but about the service itself: once the day's
   * estimated spend reaches it, requests are answered with 503 instead of being bought.
   * It is the fuse — whatever else is wrong, a day cannot cost more than this.
   *
   * Adjustable from the dashboard because it is the number most likely to need moving, and needing
   * a deployment to raise it would mean the service stays down until one happens.
   */
  dailyBudgetUsd: number;
  /**
   * How many devices may hold a working token for one account at once. See `Limits.maxDevices`;
   * here so it can be moved without a deployment, like the budget.
   */
  maxDevices: number;
  /** Off means the rules do not even run. Nothing is recorded, nothing is sent. */
  enabled: boolean;
  /** Off means everything is still recorded and visible here, but no mail leaves. */
  mail: boolean;
  /** Off means no daily report; urgent mails are unaffected. */
  digest: boolean;
  digestHourUtc: number;
  emailTo: string;
  emailFrom: string;
  /** Per rule, so a single noisy one can be silenced without going quiet altogether. */
  rules: Record<string, boolean>;
}

/** The rules that can be switched off individually, in the order they are shown. */
/**
 * Every setting the dashboard may write, in one place.
 *
 * It used to be a second list in `admin/index.ts`, hand-kept beside this one, and they drifted:
 * `costDriftPercent` stayed on it after the rule was deleted, and `neuronSpikeFactor` never made it
 * on — so the dashboard offered a threshold, accepted it, said "Gespeichert", and dropped it. A
 * setting that silently does not save is worse than one that is missing, because it is believed.
 */
export const SETTING_KEYS = [
  'enabled', 'mail', 'digest', 'digestHourUtc', 'emailTo', 'emailFrom',
  'dailyBudgetUsd', 'maxDevices', 'budgetSteps',
  'fastBurnPercent', 'fastBurnHours', 'refundUsedPercent', 'walletBudgetSharePercent',
  'devicesPerWallet', 'errorRatePercent', 'neuronSpikeFactor', 'slowShortMs', 'minLossHome',
] as const;

export const RULE_KEYS = [
  'fast_burn',
  'budget_hog',
  'shared_token',
  'overall_loss',
  'error_rate',
  'revenue_unreported',
  'neuron_spike',
  'reasoning_leak',
  'slow_upstream',
  'invoice_missing',
] as const;

async function overrides(env: Env): Promise<Record<string, string>> {
  const now = Date.now();
  if (memo && now - memo.at < TTL_MS) return memo.values;

  try {
    const rows = await env.DB.prepare('SELECT key, value FROM settings').all<{ key: string; value: string }>();
    const values: Record<string, string> = {};
    for (const row of rows.results ?? []) values[row.key] = row.value;
    memo = { at: now, values };
    return values;
  } catch {
    // A missing table (migration not run yet) must not take the service down. Defaults it is.
    memo = { at: now, values: {} };
    return {};
  }
}

export async function alertSettings(env: Env): Promise<AlertSettings> {
  const stored = await overrides(env);
  const base = alertThresholds(env);

  const bool = (key: string, fallback: boolean) =>
    stored[key] === undefined ? fallback : stored[key] === '1';
  const number = (key: string, fallback: number) => {
    const value = Number(stored[key]);
    return Number.isFinite(value) && value > 0 ? value : fallback;
  };

  const rules: Record<string, boolean> = {};
  for (const key of RULE_KEYS) rules[key] = bool(`rule.${key}`, true);

  const steps = (stored['budgetSteps'] ?? '')
    .split(',').map((s) => Number(s.trim())).filter((n) => Number.isFinite(n) && n > 0)
    .sort((a, b) => a - b);

  return {
    dailyBudgetUsd: number('dailyBudgetUsd', limitsFrom(env).dailyBudgetNano / 1_000_000_000),
    maxDevices: Math.max(1, Math.floor(number('maxDevices', limitsFrom(env).maxDevices))),
    enabled: bool('enabled', true),
    mail: bool('mail', true),
    digest: bool('digest', true),
    digestHourUtc: stored['digestHourUtc'] === undefined
      ? (Number(env.DIGEST_HOUR_UTC ?? '7') || 7)
      : Math.min(23, Math.max(0, Math.floor(Number(stored['digestHourUtc']) || 0))),
    emailTo: stored['emailTo'] || env.ALERT_EMAIL_TO || '',
    emailFrom: stored['emailFrom'] || env.ALERT_EMAIL_FROM || '',

    budgetSteps: steps.length ? steps : base.budgetSteps,
    fastBurnPercent: number('fastBurnPercent', base.fastBurnPercent),
    fastBurnHours: number('fastBurnHours', base.fastBurnHours),
    refundUsedPercent: number('refundUsedPercent', base.refundUsedPercent),
    walletBudgetSharePercent: number('walletBudgetSharePercent', base.walletBudgetSharePercent),
    devicesPerWallet: number('devicesPerWallet', base.devicesPerWallet),
    errorRatePercent: number('errorRatePercent', base.errorRatePercent),
    neuronSpikeFactor: number('neuronSpikeFactor', base.neuronSpikeFactor),
    slowShortMs: number('slowShortMs', base.slowShortMs),
    minLossHome: number('minLossHome', base.minLossHome),

    rules,
  };
}

/**
 * Which values actually differ from what the deployment ships — the dashboard marks those.
 *
 * Not "which keys are in the database": saving writes every field on the form at once, so after a
 * single visit to the settings page every last one would carry a "changed" badge, including the
 * eight that were left exactly as they came. A marker that is on everywhere marks nothing.
 *
 * Compared numerically where both sides are numbers, so `30` and `30.0` are not a difference.
 */
export async function changedKeys(env: Env): Promise<string[]> {
  const stored = await overrides(env);
  const base = alertThresholds(env);

  const shipped: Record<string, string> = {
    dailyBudgetUsd: String(limitsFrom(env).dailyBudgetNano / 1_000_000_000),
    maxDevices: String(limitsFrom(env).maxDevices),
    enabled: '1',
    mail: '1',
    digest: '1',
    digestHourUtc: String(Number(env.DIGEST_HOUR_UTC ?? '7') || 7),
    emailTo: env.ALERT_EMAIL_TO ?? '',
    emailFrom: env.ALERT_EMAIL_FROM ?? '',
    budgetSteps: base.budgetSteps.join(','),
    fastBurnPercent: String(base.fastBurnPercent),
    fastBurnHours: String(base.fastBurnHours),
    refundUsedPercent: String(base.refundUsedPercent),
    walletBudgetSharePercent: String(base.walletBudgetSharePercent),
    devicesPerWallet: String(base.devicesPerWallet),
    errorRatePercent: String(base.errorRatePercent),
    neuronSpikeFactor: String(base.neuronSpikeFactor),
    slowShortMs: String(base.slowShortMs),
    minLossHome: String(base.minLossHome),
  };
  for (const key of RULE_KEYS) shipped[`rule.${key}`] = '1';

  // Keys with a colon are the cron's own bookkeeping, not settings — `digest:last-day` records which
  // day the daily report already went out for. They live in this table because it is the one that
  // survives a ledger wipe, and they must never appear in the dashboard's list of changed settings:
  // a mark that rewrites itself every morning would make that list permanently non-empty.
  // Ein Schlüssel, den `SETTING_KEYS` nicht kennt, ist entweder eine Regel (`rule.…`) oder eine
  // Altlast aus einer entfernten Einstellung. Beides gehört nicht in die Liste „geändert".
  const known = (key: string) => (SETTING_KEYS as readonly string[]).includes(key) || key.startsWith('rule.');
  return Object.keys(stored)
    .filter((key) => !key.includes(':'))
    .filter(known)
    .filter((key) => !matchesShipped(stored[key] ?? '', shipped[key]));
}

function matchesShipped(stored: string, shipped: string | undefined): boolean {
  // A key the deployment knows nothing about can only be an override.
  if (shipped === undefined) return false;
  const a = stored.trim();
  const b = shipped.trim();
  if (a === b) return true;
  const na = Number(a);
  const nb = Number(b);
  return a !== '' && b !== '' && Number.isFinite(na) && Number.isFinite(nb) && na === nb;
}

export async function saveSettings(
  env: Env,
  actor: string,
  patch: Record<string, string | number | boolean>,
): Promise<void> {
  const now = Date.now();
  const statements = Object.entries(patch).map(([key, value]) =>
    env.DB.prepare(
      `INSERT INTO settings (key, value, updated_at, updated_by) VALUES (?, ?, ?, ?)
       ON CONFLICT(key) DO UPDATE SET value = excluded.value,
         updated_at = excluded.updated_at, updated_by = excluded.updated_by`,
    ).bind(
      key,
      typeof value === 'boolean' ? (value ? '1' : '0') : String(value),
      now,
      actor,
    ),
  );
  if (statements.length) await env.DB.batch(statements);
  memo = null;
}

/** Drops every override, so the deployment's values apply again. */
export async function resetSettings(env: Env): Promise<void> {
  await env.DB.prepare('DELETE FROM settings').run();
  memo = null;
}
