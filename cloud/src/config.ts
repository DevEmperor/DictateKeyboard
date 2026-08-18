/**
 * Prices, packages and limits in one place.
 *
 * Two rules that explain the rest of the project:
 *  - Anything OpenAI might change is a number here, not a rewrite.
 *  - Anything an attacker should not know (limits, budget) comes from the environment, so
 *    this source can be published.
 */

import type { GlobalGuard } from './guard';
import type { Wallet } from './wallet';

export interface Env {
  DB: D1Database;
  /** Typed so the object's methods can be called directly (RPC rather than `fetch`). */
  WALLET: DurableObjectNamespace<Wallet>;
  GLOBAL: DurableObjectNamespace<GlobalGuard>;

  /** Secret. Lives here only and never reaches a client. */
  OPENAI_API_KEY: string;
  /**
   * Secret, optional. An OpenAI **admin** key — a different thing from the project key above:
   * organisation-wide and used only to read the billing endpoint, so the dashboard can show what
   * OpenAI actually charged rather than what we calculated. Without it that panel says so.
   */
  OPENAI_ADMIN_KEY?: string;
  /** Secret. The full JSON key file of the Play service account. */
  GOOGLE_SERVICE_ACCOUNT: string;
  /** Secret. Guards the notification endpoint Google calls from outside. */
  RTDN_SECRET?: string;
  /** Must match the package name in the Play Console. */
  PACKAGE_NAME?: string;

  /**
   * Cloudflare Access, guarding `/admin`. Both must be set or the dashboard reports itself as
   * nonexistent — see `admin/auth.ts` on why that direction is the safe one.
   * `ACCESS_TEAM_DOMAIN` looks like `something.cloudflareaccess.com`; `ACCESS_AUD` is the
   * Application Audience tag of the Access application.
   */
  ACCESS_TEAM_DOMAIN?: string;
  ACCESS_AUD?: string;

  /**
   * The currency you are actually paid in, and the rate used to bring OpenAI's dollars into it.
   * The rate is an assumption, not a quote — it is shown as one wherever a converted figure
   * appears, because a profit line that silently invents an exchange rate is worse than none.
   */
  HOME_CURRENCY?: string;
  USD_TO_HOME_RATE?: string;
  /** Pins which OpenAI project is this service. Falls back to matching the name against "dictate". */
  OPENAI_PROJECT_ID?: string;

  /**
   * Cloudflare Email Routing, for the alerts. Absent means alerts are still recorded and shown in
   * the dashboard but never leave the building — a missing binding must not swallow the warning
   * itself.
   */
  MAIL?: SendEmailBinding;
  ALERT_EMAIL_TO?: string;
  /** Must sit on a domain verified for sending in this Cloudflare account. */
  ALERT_EMAIL_FROM?: string;
  /** Hour (UTC) the daily digest goes out. */
  DIGEST_HOUR_UTC?: string;
  /** Where the links in an alert mail point. */
  ADMIN_URL?: string;

  /** Thresholds — see [alertThresholds]. Kept out of the source so it can be published. */
  ALERT_BUDGET_STEPS?: string;
  ALERT_FAST_BURN_PERCENT?: string;
  ALERT_FAST_BURN_HOURS?: string;
  ALERT_REFUND_USED_PERCENT?: string;
  ALERT_WALLET_BUDGET_SHARE?: string;
  ALERT_DEVICES_PER_WALLET?: string;
  ALERT_COST_DRIFT_PERCENT?: string;
  ALERT_ERROR_RATE_PERCENT?: string;
  ALERT_MIN_LOSS?: string;

  TRANSCRIBE_MODEL?: string;
  CHAT_MODEL?: string;
  MAX_AUDIO_SECONDS?: string;
  MAX_CHAT_INPUT_TOKENS?: string;
  MAX_CHAT_OUTPUT_TOKENS?: string;
  RATE_LIMIT_PER_MINUTE?: string;
  DAILY_BUDGET_USD?: string;
  MAX_DEVICES?: string;
  /** How long individual request rows are kept. Daily totals are unaffected — see retention.ts. */
  USAGE_RETENTION_DAYS?: string;
}

/** A credit pack, exactly as it is set up in the Play Console. */
export interface Package {
  /** Product ID in the Play Console — must match character for character. */
  id: string;
  name: string;
  minutes: number;
  /**
   * The list price in euro as it is entered in the Play Console, for the dashboard's model view
   * and as the fallback figure written onto a purchase whose order Google would not hand over.
   *
   * **Net, not gross.** Google adds the buyer's local tax on top of this and remits it — a pack
   * entered at 1.99 is shown to a German buyer as about 2.39. What reaches you is therefore this
   * figure minus [PLAY_SERVICE_FEE], and not that again minus tax. Getting this backwards
   * understates every margin on the dashboard by roughly a fifth.
   */
  priceEur: number;
}

function pack(id: string, name: string, minutes: number, priceEur: number): Package {
  return { id, name, minutes, priceEur };
}

/**
 * Google's share of a sale.
 *
 * 15 % applies to the first $1M a developer account takes in a year; above that it becomes 30 %
 * and this number turns wrong without anything failing. Named rather than written as a bare 0.85
 * where it is used, because a rate that appears in three places is a rate that will one day be
 * updated in two.
 */
export const PLAY_SERVICE_FEE = 0.15;

export const PACKAGES: Record<string, Package> = {
  credits_notes: pack('credits_notes', 'Notes', 150, 1.99),
  credits_daily: pack('credits_daily', 'Daily', 400, 4.99),
  credits_writer: pack('credits_writer', 'Writer', 1000, 9.99),
  credits_pro: pack('credits_pro', 'Pro', 2200, 19.99),
};

/**
 * The pack every "cheaper per minute" figure is measured against — the smallest one on offer.
 *
 * Derived rather than named, so that adding or removing a pack cannot leave a stale baseline
 * behind. The app makes the same comparison against Play's own prices; this one exists so the
 * dashboard shows what the shop shows.
 */
export function baselinePackage(): Package {
  return Object.values(PACKAGES).reduce((a, b) => (a.minutes <= b.minutes ? a : b));
}

/**
 * How much cheaper a minute is in [pack] than in the smallest pack, or null when there is nothing
 * worth claiming. Rounded down: an advertised saving must never exceed the real one.
 */
export function savingsPercent(pack: Package): number | null {
  const baseline = baselinePackage();
  if (pack.id === baseline.id) return null;
  if (baseline.priceEur <= 0 || baseline.minutes <= 0 || pack.minutes <= 0) return null;
  const basePerMinute = baseline.priceEur / baseline.minutes;
  const perMinute = pack.priceEur / pack.minutes;
  if (perMinute >= basePerMinute) return null;
  return Math.floor((1 - perMinute / basePerMinute) * 100);
}

/**
 * Upstream prices in **nano-dollars** (1e-9 $), so everything stays integer and nothing
 * rounds away across millions of requests.
 *
 * As of August 2026, from OpenAI's own pricing page.
 */
export const COST = {
  /** `gpt-transcribe`: $0.0045 per audio minute. */
  transcribePerMinuteNano: 4_500_000,
  /** `gpt-5-nano`: $0.05 per 1M input tokens. */
  chatInputPerTokenNano: 50,
  /** `gpt-5-nano`: $0.40 per 1M output tokens. */
  chatOutputPerTokenNano: 400,
} as const;

export function transcribeCostNano(seconds: number): number {
  return Math.ceil((seconds / 60) * COST.transcribePerMinuteNano);
}

export function chatCostNano(tokensIn: number, tokensOut: number): number {
  return tokensIn * COST.chatInputPerTokenNano + tokensOut * COST.chatOutputPerTokenNano;
}

/**
 * One second of dictation, in nano-dollars. The unit the whole balance is denominated in.
 *
 * A credit account holds seconds and nothing else, and every service prices itself into them.
 * That is not a simplification but the safety property: a pack of 150 minutes is 9000 seconds is
 * exactly $0.675 of upstream spend, whatever the buyer does with it. Before this, rewordings were
 * counted rather than costed, and a large one cost five times what it deducted — so a pack could
 * be turned into a loss simply by using it in a way the price list had not imagined.
 */
export const NANO_PER_SECOND = COST.transcribePerMinuteNano / 60;

/** What a service costs, expressed in the only currency the wallet knows. Always rounded up. */
export function costToSeconds(nano: number): number {
  return Math.ceil(nano / NANO_PER_SECOND);
}

/**
 * A rewording of ordinary length — roughly a dictated paragraph in, a tidied one out.
 *
 * Used for estimates only, never for billing: it turns a seconds balance into the "enough for
 * about 750 rewordings" the app shows, and it is what the old separate allowance is converted at
 * when an account is migrated. Billing uses the tokens OpenAI actually reports.
 */
export const TYPICAL_REWORD_NANO = chatCostNano(500, 300);
export const TYPICAL_REWORD_SECONDS = costToSeconds(TYPICAL_REWORD_NANO);

/** The resolved limits for one request — read from the environment once per call. */
export interface Limits {
  transcribeModel: string;
  chatModel: string;
  maxAudioSeconds: number;
  maxChatInputTokens: number;
  maxChatOutputTokens: number;
  rateLimitPerMinute: number;
  dailyBudgetNano: number;
  /**
   * How many devices may hold a working access token for one account at the same time.
   *
   * A recovery code is deliberately reusable — a phone and a watch and a tablet is the ordinary
   * case, and a new phone has to be able to take over from a lost one. But without a ceiling the
   * same code turns a personal pack into a shared one, and nothing about that shows up as a fault:
   * the balance simply drains faster than one person could manage.
   */
  maxDevices: number;
}

export function limitsFrom(env: Env): Limits {
  return {
    transcribeModel: env.TRANSCRIBE_MODEL ?? 'gpt-transcribe',
    chatModel: env.CHAT_MODEL ?? 'gpt-5-nano',
    maxAudioSeconds: num(env.MAX_AUDIO_SECONDS, 600),
    maxChatInputTokens: num(env.MAX_CHAT_INPUT_TOKENS, 8000),
    maxChatOutputTokens: num(env.MAX_CHAT_OUTPUT_TOKENS, 2000),
    rateLimitPerMinute: num(env.RATE_LIMIT_PER_MINUTE, 20),
    dailyBudgetNano: Math.round(num(env.DAILY_BUDGET_USD, 25) * 1_000_000_000),
    maxDevices: num(env.MAX_DEVICES, 3),
  };
}

/**
 * What the Email Routing binding offers. Declared here rather than imported so the project keeps
 * building without the `cloudflare:email` types present.
 */
export interface SendEmailBinding {
  send(message: unknown): Promise<void>;
}

/**
 * When the watchdog barks.
 *
 * Every one of these is a judgement call, so every one is a variable. The defaults are chosen so
 * that a normal day is silent — a threshold that fires weekly stops being read after a month, and
 * an alarm nobody reads is worse than none, because it feels like cover.
 */
export interface AlertThresholds {
  /** Percentages of the daily budget worth a word. */
  budgetSteps: number[];
  /** Share of a fresh purchase spent within [fastBurnHours] that looks like refund abuse. */
  fastBurnPercent: number;
  fastBurnHours: number;
  /** How much of a refunded purchase must have been used before it is a real loss. */
  refundUsedPercent: number;
  /** One account eating this share of the *shared* daily budget starves everyone else. */
  walletBudgetSharePercent: number;
  /** Distinct devices on one account within a day — the shape of a passed-around token. */
  devicesPerWallet: number;
  /** Gap between OpenAI's own bill and our calculation that means the price list moved. */
  costDriftPercent: number;
  errorRatePercent: number;
  /**
   * How far into the red the running total has to be before it is worth saying so, in the payout
   * currency.
   *
   * Zero would fire on a few cents of rounding — and at the very start, when a single test request
   * outweighs no sales at all, that is every single day. A warning that arrives daily is one that
   * stops being read.
   */
  minLossHome: number;
}

export function alertThresholds(env: Env): AlertThresholds {
  const steps = (env.ALERT_BUDGET_STEPS ?? '50,80,95,100')
    .split(',')
    .map((s) => Number(s.trim()))
    .filter((n) => Number.isFinite(n) && n > 0)
    .sort((a, b) => a - b);

  return {
    budgetSteps: steps.length ? steps : [50, 80, 95, 100],
    fastBurnPercent: num(env.ALERT_FAST_BURN_PERCENT, 70),
    fastBurnHours: num(env.ALERT_FAST_BURN_HOURS, 2),
    refundUsedPercent: num(env.ALERT_REFUND_USED_PERCENT, 30),
    walletBudgetSharePercent: num(env.ALERT_WALLET_BUDGET_SHARE, 20),
    devicesPerWallet: num(env.ALERT_DEVICES_PER_WALLET, 5),
    costDriftPercent: num(env.ALERT_COST_DRIFT_PERCENT, 20),
    errorRatePercent: num(env.ALERT_ERROR_RATE_PERCENT, 25),
    minLossHome: num(env.ALERT_MIN_LOSS, 1),
  };
}

function num(value: string | undefined, fallback: number): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

export const OPENAI_BASE = 'https://api.openai.com/v1';
