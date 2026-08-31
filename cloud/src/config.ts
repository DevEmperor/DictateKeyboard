/**
 * Prices, packages and limits in one place.
 *
 * Two rules that explain the rest of the project:
 *  - Anything the model provider might change is a number here, not a rewrite.
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
   * Nothing leaves for an outside service: the model already runs inside the one this Worker lives
   * in. What that does not settle is *where* — the inference runs wherever Cloudflare has capacity,
   * and that cannot be pinned without an Enterprise contract.
   */
  AI: Ai;

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
   * The currency you are actually paid in, and the rate used to bring Cloudflare's dollars into it.
   * The rate is an assumption, not a quote — it is shown as one wherever a converted figure
   * appears, because a profit line that silently invents an exchange rate is worse than none.
   */
  HOME_CURRENCY?: string;
  USD_TO_HOME_RATE?: string;

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
  ALERT_ERROR_RATE_PERCENT?: string;
  ALERT_NEURON_SPIKE_FACTOR?: string;
  ALERT_SLOW_SHORT_MS?: string;
  ALERT_MIN_LOSS?: string;

  /** Which Workers AI model each service uses. Swappable without a deploy; see `modelFor`. */
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

/** The Workers AI models the switch falls back to, so flipping it is one line and not two. */
const CF_DEFAULT_TRANSCRIBE = '@cf/openai/whisper-large-v3-turbo';
const CF_DEFAULT_CHAT = '@cf/google/gemma-4-26b-a4b-it';

/**
 * What a request costs to buy, in **nano-dollars** (1e-9 $), so everything stays integer and
 * nothing rounds away across millions of requests.
 *
 * **Estimates only, and only for the reservation.** The day's budget has to be held before the
 * request goes out, when nothing is known about the cost yet; on the way back Workers AI reports
 * the neurons it actually spent and that measurement replaces this. A figure here only has to be
 * close enough to hold roughly the right amount, and to be wrong upwards if it is wrong.
 *
 * Purchase prices. What a second is worth when it is *sold* is `SECOND_VALUE_NANO`, and the two are
 * deliberately not connected — see the note there.
 */
export function transcribeCostNano(seconds: number): number {
  const perMinute = NEURONS['@cf/openai/whisper-large-v3-turbo'].perAudioMinute;
  return Math.ceil((seconds / 60) * perMinute * NANO_PER_NEURON);
}

/** The same for a rewording. An unknown model estimates as the default one rather than as nothing. */
export function chatCostNano(model: string, tokensIn: number, tokensOut: number): number {
  const neurons = expectedNeurons(model, { tokensIn, tokensOut })
    ?? expectedNeurons(CF_DEFAULT_CHAT, { tokensIn, tokensOut })
    ?? 0;
  return Math.ceil(neurons * NANO_PER_NEURON);
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
 * **It follows no provider's price, and that is the point.** This value was once derived from the
 * transcription price, which was correct for exactly as long as the two numbers meant the same
 * thing. They stopped meaning the same thing the moment transcription was bought somewhere cheaper:
 * the second sold is still worth what it was sold for, while the second bought is not. Left derived,
 * the move to Workers AI would have shrunk the unit along with the purchase price and made an
 * ordinary rewording deduct seventeen seconds instead of two — with no test failing and no warning
 * raised, only balances draining eight times faster.
 *
 * The invariant that has to hold: no service may cost more than this per second it deducts.
 * `costToSeconds` guarantees it with room to spare — it converts at [BILLING_NANO_PER_SECOND], which
 * is what a second *costs*, roughly a ninth of what it sells for. This value is what a second is
 * worth on the sales side: it prices the packs, and it is the ceiling the margin is measured against.
 */
export const SECOND_VALUE_NANO = 75_000;

/**
 * What a second of credit **costs to serve**, in nano-dollars — the rate every service is billed at.
 *
 * Not the same thing as [SECOND_VALUE_NANO], and the distance between them is the margin. A second
 * of credit sells for 75 000 and buys 8 549 worth of dictation, so dictation keeps 88.6 %. Deriving
 * this from the transcription model is the whole point: it makes **one audio second cost exactly one
 * credit second**, by construction, and then holds every other service to the same rate.
 *
 * Before this existed, `costToSeconds` divided by the *sale* value, and the consequence was invisible
 * until it was worked out: a rewording costs 51 601 and so deducted a single second, keeping 31 %
 * where dictation kept 89. A customer who spent a whole pack on rewording was not doing anything
 * wrong — the price list simply had a hole in it that nobody could see from the outside. Now the
 * margin is the same whatever the credit is spent on, and stays the same when a model's price moves,
 * because both sides of the fraction move with it.
 */
export const BILLING_NANO_PER_SECOND =
  (NEURONS[CF_DEFAULT_TRANSCRIBE].perAudioMinute / 60) * NANO_PER_NEURON;

/**
 * What a service costs, expressed in the only currency the wallet knows. Always rounded up.
 *
 * Rounding up is what keeps the guarantee one-directional: a service can only ever deduct more
 * than it cost, never less, so no pattern of use can turn a pack into a loss.
 */
export function costToSeconds(nano: number): number {
  return Math.max(1, Math.ceil(nano / BILLING_NANO_PER_SECOND));
}

/**
 * A rewording of ordinary length, **measured rather than assumed**.
 *
 * 327 tokens in and 63 out is what 131 real rewordings averaged over the fortnight from 16.08.2026.
 * It is input-heavy, and obviously so once seen: the input carries the instruction, the system
 * prompt and the text, the output carries one tidied sentence. The figure written here before was
 * 500/300 — three and a half times too large, and output-heavy, which had also made two models rank
 * the wrong way round against each other.
 *
 * Used for estimates only, never for billing: it turns a seconds balance into the "enough for about
 * 750 rewordings" the app shows. Billing uses the neurons the model actually reports.
 */
export const TYPICAL_REWORD_NANO = chatCostNano(CF_DEFAULT_CHAT, 327, 63);
export const TYPICAL_REWORD_SECONDS = costToSeconds(TYPICAL_REWORD_NANO);

/**
 * What the old separate rewording allowance is converted at, **frozen**.
 *
 * There used to be a second balance counting rewordings; a wallet whose stored state predates the
 * seconds model still gets converted on first read. That conversion happened at whatever
 * `TYPICAL_REWORD_SECONDS` said at the time, which was fine while nobody expected that number to
 * move — and it has now moved, from 2 to 1, because it was measured.
 *
 * Left tied together, that measurement would have quietly halved an old allowance on the day it
 * landed. A past conversion has to keep its own rate, so it gets one.
 */
export const LEGACY_REWORD_SECONDS = 2;


/**
 * The dearest a credit-second can actually be, in nano-dollars.
 *
 * Two bounds exist and they are far apart, which is why this one is written down. The *structural*
 * one is `SECOND_VALUE_NANO`: `costToSeconds` rounds up, so no service can ever deduct fewer seconds
 * than it cost, and a pack therefore cannot cost more than its seconds are worth under any use at
 * all. That guarantee holds — but it describes a service priced exactly at what it sells for, and
 * neither of ours is.
 *
 * This is the bound that can be reached: the worst of the services actually offered, per second it
 * deducts. Dictation buys a second of audio for a fraction of what the second sells for; a rewording
 * of ordinary length deducts one second and costs rather more of it. Rewording is therefore the
 * expensive end, and a pack spent entirely on it is the real floor under a margin — the number worth
 * showing beside the ordinary case, because the structural bound flatters nobody and frightens
 * everybody.
 */
export const WORST_COST_PER_SECOND_NANO = Math.max(
  // Dictation: one audio second sold is one credit-second.
  (NEURONS[CF_DEFAULT_TRANSCRIBE].perAudioMinute / 60) * NANO_PER_NEURON,
  // Rewording: one of ordinary length, over the seconds it deducts.
  TYPICAL_REWORD_NANO / TYPICAL_REWORD_SECONDS,
);


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


/**
 * The model to use, refusing one that does not belong here.
 *
 * `TRANSCRIBE_MODEL` and `CHAT_MODEL` exist so a model can be swapped without a deploy. A name left
 * that is not a `@cf/…` model would fail every request for as long as it took
 * someone to notice, so it is corrected to the default instead. Nothing is hidden by that: the wrong
 * name is still whatever the configuration says, and what actually ran is in the ledger's `model`
 * column.
 */
function modelFor(configured: string | undefined, fallback: string): string {
  return configured?.startsWith('@cf/') ? configured : fallback;
}

export function limitsFrom(env: Env): Limits {
  return {
    transcribeModel: modelFor(env.TRANSCRIBE_MODEL, CF_DEFAULT_TRANSCRIBE),
    chatModel: modelFor(env.CHAT_MODEL, CF_DEFAULT_CHAT),
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
  slowShortMs: number;
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
    errorRatePercent: num(env.ALERT_ERROR_RATE_PERCENT, 25),
    neuronSpikeFactor: num(env.ALERT_NEURON_SPIKE_FACTOR, 3),
    slowShortMs: num(env.ALERT_SLOW_SHORT_MS, 10_000),
    minLossHome: num(env.ALERT_MIN_LOSS, 1),
  };
}

function num(value: string | undefined, fallback: number): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}
