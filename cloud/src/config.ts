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

  /**
   * Workers AI. Not a secret and not a key — the binding bills the account the Worker belongs to.
   *
   * That is the whole legal difference to OpenAI in one line: nothing leaves for an outside
   * service, because the model already runs inside the one this Worker lives in. What it does not
   * change is *where*: the inference runs wherever Cloudflare has capacity, and that cannot be
   * pinned without an Enterprise contract.
   */
  AI: Ai;

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
  ALERT_NEURON_SPIKE_FACTOR?: string;
  ALERT_MIN_LOSS?: string;

  /** `openai` (default) or `workers-ai`. One per service, never one for both — the two moves have
   *  different reasons, different measurements and different ways back. */
  TRANSCRIBE_PROVIDER?: string;
  CHAT_PROVIDER?: string;
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
 * What the upstream provider charges *us*, in **nano-dollars** (1e-9 $), so everything stays
 * integer and nothing rounds away across millions of requests.
 *
 * Purchase prices only. What a second is worth when it is sold is `SECOND_VALUE_NANO`, and the two
 * are deliberately not connected — see the note there.
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

/**
 * The same figure at whichever provider is in use.
 *
 * Needed because the day's budget is reserved *before* the request goes out, when the only thing
 * known about the cost is who is about to be asked. On the way back Workers AI reports the neurons
 * it actually spent, and that measurement replaces this estimate — this one only has to be close
 * enough to hold the right amount of budget, and to be wrong in the safe direction if it is wrong.
 */
export function transcribeCostNanoFor(p: Provider, seconds: number): number {
  if (p !== 'workers-ai') return transcribeCostNano(seconds);
  const perMinute = NEURONS['@cf/openai/whisper-large-v3-turbo'].perAudioMinute;
  return Math.ceil((seconds / 60) * perMinute * NANO_PER_NEURON);
}

export function chatCostNano(tokensIn: number, tokensOut: number): number {
  return tokensIn * COST.chatInputPerTokenNano + tokensOut * COST.chatOutputPerTokenNano;
}

/**
 * The same at whichever provider is in use, for the reservation only.
 *
 * A model the neuron table does not know falls back to OpenAI's prices rather than refusing to
 * estimate. They are the same order of magnitude across every candidate, and this figure exists to
 * hold roughly the right amount of budget for the length of one request — the real cost comes back
 * as neurons and replaces it.
 */
export function chatCostNanoFor(p: Provider, model: string, tokensIn: number, tokensOut: number): number {
  if (p !== 'workers-ai') return chatCostNano(tokensIn, tokensOut);
  const neurons = expectedNeurons(model, { tokensIn, tokensOut });
  return neurons === null ? chatCostNano(tokensIn, tokensOut) : Math.ceil(neurons * NANO_PER_NEURON);
}

/**
 * What one sold second is worth, in nano-dollars. The unit the whole balance is denominated in.
 *
 * A credit account holds seconds and nothing else, and every service prices itself into them.
 * That is not a simplification but the safety property: a pack of 150 minutes is 9000 seconds is
 * exactly $0.675, whatever the buyer does with it. Before this, rewordings were counted rather
 * than costed, and a large one cost five times what it deducted — so a pack could be turned into
 * a loss simply by using it in a way the price list had not imagined.
 *
 * **It follows no provider's price, and that is the point.** This value was once written as
 * `COST.transcribePerMinuteNano / 60`, which was correct for exactly as long as the two numbers
 * meant the same thing. They stop meaning the same thing the moment transcription is bought
 * somewhere cheaper: the second sold is still worth what it was sold for, while the second bought
 * is not. Deriving one from the other would have shrunk the unit along with the purchase price and
 * made an ordinary rewording deduct seventeen seconds instead of two — with no test failing and no
 * warning raised, only balances draining eight times faster.
 *
 * The invariant that has to hold: no service may cost more than this per second it deducts.
 * `costToSeconds` rounds up to guarantee it.
 */
export const SECOND_VALUE_NANO = 75_000;

/** What a service costs, expressed in the only currency the wallet knows. Always rounded up. */
export function costToSeconds(nano: number): number {
  return Math.ceil(nano / SECOND_VALUE_NANO);
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

/**
 * Who a request is routed to.
 *
 * Recorded on every ledger row, including refused ones: the column says which service the request
 * was *routed to*, not which one answered. A refusal that never left the building still belongs to
 * the provider it was on its way to, and reading it any other way would leave a second meaning of
 * NULL — "nobody was involved" next to "recorded before this column existed".
 */
export type Provider = 'openai' | 'workers-ai';

/** Neuron rates, for checking the figure the provider reports rather than replacing it.
 *
 * Workers AI returns `usage.neurons` on every response — measured 30.08.2026, on both the chat and
 * the speech model, and it appears in no type definition. That reported number is what gets booked:
 * a quantity that is read cannot quietly diverge from a price list nobody updated.
 *
 * This table exists so that divergence is *noticed*. Whisper's reported figure implies 46.6302
 * neurons per minute against the published 46.63, which is rounding in the documentation; anything
 * beyond a per-mille is the price list having moved.
 *
 * Stand 30.08.2026, from <https://developers.cloudflare.com/workers-ai/platform/pricing/>.
 */
export const NEURONS = {
  '@cf/openai/whisper-large-v3-turbo': { perAudioMinute: 46.63 },
  '@cf/google/gemma-4-26b-a4b-it': { perMTokensIn: 9_091, perMTokensOut: 27_273 },
} as const;

/** $0.011 per 1000 neurons, in nano-dollars per neuron. */
export const NANO_PER_NEURON = 11_000;

export function neuronsToNano(neurons: number): number {
  return Math.round(neurons * NANO_PER_NEURON);
}

/** Neurons included per UTC day on the Workers Paid plan. Resets at 00:00 UTC, no rollover. */
export const FREE_NEURONS_PER_DAY = 10_000;

/**
 * What Cloudflare actually charges for one day's neurons.
 *
 * The allowance is a *daily* figure, and everything awkward about it follows from that. It cannot
 * be applied per request — the same recording would then cost nothing in the morning and money in
 * the evening, depending only on how many came before it. And it cannot be summed across a month:
 * a quiet day's unused neurons are not credit, they are simply gone.
 *
 * So `usage_log.cost_nano` stays the list price, always, and this function is the only place the
 * allowance is ever subtracted. Note that it is fed *both* neuron columns: the allowance is granted
 * to the account, not to the paying customers, and does not care whose request spent it.
 *
 * Worth at most $0.11 a day, $40 a year. Small enough that the cost figure is deliberately shown
 * without it (see the dashboard) — a cost that errs upwards is the right kind of wrong.
 */
export function billedNanoForDay(neuronsMicro: number): number {
  const billable = Math.max(0, neuronsMicro - FREE_NEURONS_PER_DAY * 1_000_000);
  return Math.round((billable / 1_000_000) * NANO_PER_NEURON);
}

/**
 * What the table above says a request should have cost in neurons, or null for a model it does not
 * know. Only ever compared against the reported figure — never billed.
 */
export function expectedNeurons(
  model: string,
  usage: { audioSeconds?: number; tokensIn?: number; tokensOut?: number },
): number | null {
  const rate = (NEURONS as Record<string, { perAudioMinute?: number; perMTokensIn?: number; perMTokensOut?: number }>)[model];
  if (!rate) return null;
  if (rate.perAudioMinute !== undefined) return ((usage.audioSeconds ?? 0) / 60) * rate.perAudioMinute;
  return ((usage.tokensIn ?? 0) * (rate.perMTokensIn ?? 0) + (usage.tokensOut ?? 0) * (rate.perMTokensOut ?? 0)) / 1e6;
}

/** The resolved limits for one request — read from the environment once per call. */
export interface Limits {
  transcribeProvider: Provider;
  chatProvider: Provider;
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

function provider(value: string | undefined): Provider {
  return value === 'workers-ai' ? 'workers-ai' : 'openai';
}

/** The Workers AI models the switch falls back to, so flipping it is one line and not two. */
const CF_DEFAULT_TRANSCRIBE = '@cf/openai/whisper-large-v3-turbo';
const CF_DEFAULT_CHAT = '@cf/google/gemma-4-26b-a4b-it';

/**
 * The model to use, refusing one that belongs to the other provider.
 *
 * `TRANSCRIBE_PROVIDER` and `TRANSCRIBE_MODEL` are two settings that have to agree, and the moment
 * anyone flips one at three in the morning is the moment they forget the other. Sending
 * `gpt-transcribe` to `env.AI.run` would fail every request for as long as it took to notice.
 * Falling back to the right default keeps the service up; the wrong name is visible in the ledger's
 * `model` column either way, so nothing is hidden by this — only nothing is broken by it.
 */
function modelFor(p: Provider, configured: string | undefined, cfDefault: string, openaiDefault: string): string {
  if (p === 'workers-ai') return configured?.startsWith('@cf/') ? configured : cfDefault;
  return configured?.startsWith('@cf/') ? openaiDefault : (configured ?? openaiDefault);
}

export function limitsFrom(env: Env): Limits {
  return {
    // Read here so the ledger can record who a request was routed to. Nothing branches on these
    // yet — the calls themselves still go to OpenAI either way. Recording starts before switching
    // deliberately: a column that only begins to be filled on the day of the move cannot show what
    // the day before looked like, and that comparison is the whole point of the exercise.
    transcribeProvider: provider(env.TRANSCRIBE_PROVIDER),
    chatProvider: provider(env.CHAT_PROVIDER),
    transcribeModel: modelFor(
      provider(env.TRANSCRIBE_PROVIDER), env.TRANSCRIBE_MODEL, CF_DEFAULT_TRANSCRIBE, 'gpt-transcribe',
    ),
    chatModel: modelFor(provider(env.CHAT_PROVIDER), env.CHAT_MODEL, CF_DEFAULT_CHAT, 'gpt-5-nano'),
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
   * A day's neuron use this many times the last week's average is worth looking at.
   *
   * Neurons are the one figure that turns straight into a bill, and they can move for reasons that
   * are nobody's fault — a new customer, a long recording — as well as for reasons that are: a loop,
   * a model that started thinking again, someone else's project on the same account. The alert does
   * not judge which; it says the day is unlike the week.
   */
  neuronSpikeFactor: number;
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
    neuronSpikeFactor: num(env.ALERT_NEURON_SPIKE_FACTOR, 3),
    minLossHome: num(env.ALERT_MIN_LOSS, 1),
  };
}

function num(value: string | undefined, fallback: number): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

export const OPENAI_BASE = 'https://api.openai.com/v1';
