import { raise } from '../alerts';
import { estimateSeconds, probeDuration, shortestPossibleSeconds } from '../audio';
import { authenticate, touch } from '../auth';
import { neuronsToNano, transcribeCostNano, type Env, type Limits } from '../config';
import { budgetAllows, logUsage, settleBudget, walletStub } from '../meter';
import { NO_STORE, apiError } from '../util';

/**
 * `POST /v1/audio/transcriptions` — the path the money flows down.
 *
 * The order is intent, not taste:
 *   1. Who are you            → otherwise 401
 *   2. How long is the audio  → from the WAV header, not from a client claim
 *   3. May the service still  → daily cap
 *   4. Deduct                 → **before** the model, or every abort is on the operator
 *   5. Forward
 *   6. Refund on failure, correct to the real duration on success
 *
 * Step 5 goes to Workers AI over the binding — no key, no network hop, nothing that has to be
 * reachable. Everything around it is unchanged from when it went somewhere else, because none of it
 * ever depended on who did the transcribing.
 */
export async function handleTranscription(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  limits: Limits,
): Promise<Response> {
  const started = Date.now();
  const session = await authenticate(request, env);
  if (!session) {
    return apiError(401, 'No valid credit token.', 'invalid_token', 'invalid_request_error');
  }
  touch(env, session, ctx);

  // `formData()` holds the file in memory. At ten minutes that is about 19 MB against a
  // 128 MB budget — acceptable, and it saves writing a multipart parser just to reach the
  // first 44 bytes.
  let form: FormData;
  try {
    form = await request.formData();
  } catch {
    return apiError(400, 'Request is not valid multipart/form-data.', 'bad_request', 'invalid_request_error');
  }

  const file = form.get('file');
  if (!(file instanceof File)) {
    return apiError(400, 'Field "file" is missing.', 'missing_file', 'invalid_request_error');
  }

  // Read from the file itself where the format says so — WAV, MP3, MP4/M4A, Ogg/Opus, FLAC all
  // state it in a header and none of them needs decoding. Only an unrecognised container falls back
  // to the estimate, and then only to decide how much credit to hold.
  const duration = (await probeDuration(file)) ?? estimateSeconds(file.size);

  // Refused only when the file cannot be within the limit however it is encoded — for a WAV that is
  // the header's own figure, for anything else the shortest length its size allows. Using the
  // generous estimate here turned an ordinary three-minute song into "eighteen minutes, too long".
  const tooLong = duration.exact
    ? duration.seconds > limits.maxAudioSeconds
    : shortestPossibleSeconds(file.size) > limits.maxAudioSeconds;

  if (tooLong) {
    // The app turns 413 into CONTENT_SIZE_LIMIT and offers to keep the recording — exactly
    // the right response to "too long".
    logRefusal(env, session, 'transcribe', 413, started, ctx);
    return apiError(
      413,
      `Recording is longer than ${Math.floor(limits.maxAudioSeconds / 60)} minutes.`,
      'audio_too_long',
      'invalid_request_error',
    );
  }

  // Held up front, and never more than the longest recording allowed: the estimate assumes speech
  // at 32 kbit/s, so a better-encoded file reads several times its true length and would demand
  // credit for minutes it does not contain. Corrected to the real duration once the model reports it.
  const chargedSeconds = Math.min(Math.ceil(duration.seconds), limits.maxAudioSeconds);
  const estimateNano = transcribeCostNano(chargedSeconds);

  if (!(await budgetAllows(env, limits, estimateNano, ctx))) {
    // Recorded like any other outcome. A refusal is the one event a support message is most
    // likely to be about — "it stopped working on Tuesday" is answerable from a row and not from
    // an absence — and it costs nothing to keep: no seconds, no money, and not counted as a fault.
    logRefusal(env, session, 'transcribe', 503, started, ctx);
    return apiError(
      503,
      'Dictate Cloud is unavailable right now. Please try again later.',
      'service_paused',
      'server_error',
    );
  }

  const wallet = walletStub(env, session.walletId);
  const debit = await wallet.debit(chargedSeconds, limits.rateLimitPerMinute);
  if (!debit.ok) {
    settleBudget(env, -estimateNano, ctx);
    const refusal = debitError(debit.reason);
    logRefusal(env, session, 'transcribe', refusal.status, started, ctx, debit.state);
    return refusal;
  }

  const upstream = await runWorkersAi(env, limits, file, form);

  if (upstream.kind === 'unreachable') {
    await wallet.refund(chargedSeconds);
    settleBudget(env, -estimateNano, ctx);
    return apiError(502, 'The transcription service is unreachable.', 'upstream_unreachable', 'server_error');
  }

  if (upstream.kind === 'failed') {
    // Nothing delivered, nothing charged.
    const state = await wallet.refund(chargedSeconds);
    settleBudget(env, -estimateNano, ctx);
    logUsage(env, {
      walletId: session.walletId,
      tokenHash: session.tokenHash,
      isTest: session.isTest,
      kind: 'transcribe',
      seconds: 0,
      costNano: 0,
      status: upstream.status,
      ms: Date.now() - started,
      secondsLeft: state.secondsLeft,
      rewordsLeft: state.rewordsLeft,
      secondsUsedTotal: state.secondsUsed,
    }, ctx);
    return upstreamFailure(upstream.status);
  }

  const body = upstream.body;

  // For WAV the duration was already exact. Other formats were estimated generously — if
  // the provider reports the real length, the difference goes back.
  let finalSeconds = chargedSeconds;
  let state = debit.state;
  if (!duration.exact) {
    const reported = upstream.reportedSeconds;
    if (reported !== null) {
      const actual = Math.ceil(reported);
      const delta = actual - chargedSeconds;
      if (delta !== 0) {
        state = await wallet.adjust(delta);
        finalSeconds = actual;
      }
    } else {
      // The whole billing of non-WAV audio rests on this field. Without it the estimate stands, and
      // the estimate assumes 32 kbit/s — generous for an ordinary recording, so honest uploads are
      // simply over-charged and the correction that would have refunded them never runs. A file
      // deliberately encoded below that rate goes the other way and is billed short.
      //
      // Either way the number is wrong and nothing else would say so, which is why this reports
      // rather than guesses. Once every six hours per model is enough: this is a change at
      // Cloudflare, not an event — it either happens for every request or for none.
      ctx.waitUntil(raise(env, {
        kind: 'audio_duration_missing',
        severity: 'critical',
        value: chargedSeconds,
        title: 'Workers AI meldet die Audiolänge nicht mehr',
        detail:
          `Eine Aufnahme, deren Länge sich nicht aus dem Dateikopf lesen ließ, wurde nach Größe ` +
          `geschätzt (${chargedSeconds} s) — und die Antwort enthielt kein ` +
          `\`transcription_info.duration\`, mit dem sich das hätte richtigstellen lassen. Bis dahin galt dieses ` +
          `Feld als gesetzt; fehlt es dauerhaft, wird jede Datei außer WAV falsch abgerechnet: ` +
          `gewöhnliche Aufnahmen zu teuer, absichtlich niedrig kodierte zu billig. Betroffen ist ` +
          `nur, was über die Dateiauswahl kommt — die App selbst nimmt WAV auf, und dort steht die ` +
          `Länge exakt im Kopf. Zu prüfen: das Antwortformat von \`${limits.transcribeModel}\` und ` +
          `ob Cloudflare die Angabe umbenannt hat.`,
        dedupeKey: `audio_duration_missing:${limits.transcribeModel}`,
      }, ctx).then(() => undefined));
    }
  }

  // What it really cost to buy. Workers AI reports the neurons it spent, and that is the figure to
  // keep: a measurement rather than our multiplication, and it stays right when the price list
  // moves. Only when it is missing does the per-minute table stand in.
  //
  // The day's budget is settled against exactly this number rather than against the duration, so
  // the guard and the ledger cannot drift apart.
  const actualNano = upstream.neurons > 0
    ? neuronsToNano(upstream.neurons)
    : transcribeCostNano(finalSeconds);
  settleBudget(env, actualNano - estimateNano, ctx);

  logUsage(env, {
    walletId: session.walletId,
    tokenHash: session.tokenHash,
    isTest: session.isTest,
    kind: 'transcribe',
    seconds: finalSeconds,
    neuronsMicro: Math.round(upstream.neurons * 1_000_000),
    costNano: actualNano,
    status: 200,
    ms: Date.now() - started,
    secondsLeft: state.secondsLeft,
    rewordsLeft: state.rewordsLeft,
    secondsUsedTotal: state.secondsUsed,
  }, ctx);

  return new Response(body, {
    status: 200,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      ...NO_STORE,
      // So the app can show the balance later without asking separately.
      'x-dictate-seconds-left': String(state.secondsLeft),
      'x-dictate-rewords-left': String(state.rewordsLeft),
    },
  });
}

/** Newer transcription models report the billed length under `usage`. */
function reportedSeconds(body: string): number | null {
  try {
    const parsed = JSON.parse(body) as { usage?: { seconds?: number; duration?: number } };
    const seconds = parsed.usage?.seconds ?? parsed.usage?.duration;
    return typeof seconds === 'number' && seconds > 0 ? seconds : null;
  } catch {
    return null;
  }
}

/**
 * A request that was turned away, in the ledger.
 *
 * Zero seconds and zero cost, because nothing was bought — the row exists so the refusal can be
 * found later. Shares `logUsage` rather than a table of its own: the traffic view is where anyone
 * would look, and a refusal that lives somewhere else is a refusal nobody finds.
 */
export function logRefusal(
  env: Env,
  session: { walletId: string; tokenHash: string; isTest: boolean },
  kind: 'transcribe' | 'reword',
  status: number,
  started: number,
  ctx: ExecutionContext,
  state?: { secondsLeft: number; rewordsLeft: number; secondsUsed: number },
): void {
  logUsage(env, {
    walletId: session.walletId,
    tokenHash: session.tokenHash,
    isTest: session.isTest,
    kind,
    seconds: 0,
    costNano: 0,
    status,
    ms: Date.now() - started,
    secondsLeft: state?.secondsLeft,
    rewordsLeft: state?.rewordsLeft,
    secondsUsedTotal: state?.secondsUsed,
  }, ctx);
}

export function debitError(reason: 'blocked' | 'insufficient' | 'rate_limited'): Response {
  switch (reason) {
    case 'blocked':
      return apiError(403, 'This credit account is blocked.', 'wallet_blocked', 'invalid_request_error');
    case 'rate_limited':
      return apiError(429, 'Too many requests in a short time.', 'rate_limited', 'rate_limit_error');
    case 'insufficient':
      // The app turns 402 into QUOTA_EXCEEDED; the distinct `code` lets it offer "top up"
      // rather than "quota reached" later on.
      return apiError(402, 'Out of credit.', 'insufficient_credits', 'insufficient_quota');
  }
}

/**
 * An upstream error is passed on, but not its wording.
 *
 * The user has no contract with the model provider and can do nothing with "your organization has
 * been blocked" — and the message could give away internals. Kept although the binding throws
 * rather than returning a status: the shape stays ready for a status that does arrive.
 */
function upstreamFailure(status: number): Response {
  if (status === 429) {
    return apiError(503, 'The service is busy right now. Please try again shortly.', 'upstream_busy', 'server_error');
  }
  if (status >= 500) {
    return apiError(502, 'The transcription service reported an error.', 'upstream_error', 'server_error');
  }
  return apiError(502, 'The recording could not be transcribed.', 'upstream_rejected', 'server_error');
}

/**
 * One transcription.
 *
 * `body` is what the client receives: a `{ "text": … }` built from the answer. The app reads nothing
 * but `text` (its `TranscriptionResponseDto` has one field), and handing back segments, word counts
 * and a `vtt` track would publish which model is behind Dictate Cloud for no one's benefit.
 */
type Upstream =
  | { kind: 'ok'; body: string; reportedSeconds: number | null; neurons: number }
  | { kind: 'failed'; status: number }
  | { kind: 'unreachable' };

/**
 * The transcription itself, over the binding.
 *
 * Four things about it are worth keeping in mind, and each has a reason:
 *
 *  1. **Base64, not multipart.** `whisper-large-v3-turbo` takes the audio as a string. The older
 *     `@cf/openai/whisper` wants `number[]` — nineteen million array entries for a ten-minute file
 *     against a 128 MB worker — which is why turbo is not merely the better model here but the only
 *     usable one.
 *  2. **`duration`, never `duration_after_vad`.** Both come back. The second is the audio with the
 *     silence taken out, and billing by it would charge a recording with pauses for less than it is:
 *     "a minute of dictation costs a minute" would quietly stop being true.
 *  3. **`vad_filter` stays off.** The app already runs a local Silero VAD (#93) and never uploads a
 *     silent recording. A second one here would only change the duration that comes back — see 2.
 *  4. **It throws instead of returning a status.** `InferenceUpstreamError` and friends, so the
 *     failure arrives as an exception and the refund path is a `catch`.
 */
async function runWorkersAi(env: Env, limits: Limits, file: File, form: FormData): Promise<Upstream> {
  const text = (key: string): string | undefined => {
    const value = form.get(key);
    return typeof value === 'string' && value.trim() ? value : undefined;
  };

  let result: {
    text?: string;
    transcription_info?: { duration?: number };
    usage?: { neurons?: number };
  };
  try {
    result = await env.AI.run(limits.transcribeModel as keyof AiModels, {
      audio: base64(new Uint8Array(await file.arrayBuffer())),
      task: 'transcribe',
      language: text('language'),
      initial_prompt: text('prompt'),
    } as never) as typeof result;
  } catch (error) {
    // The binding throws rather than answering with a status, so there is nothing to map:
    // everything that is not an answer refunds and returns 502. Logged, because a binding that
    // starts failing says so nowhere else.
    console.log(`workers-ai transcribe failed: ${String(error).slice(0, 200)}`);
    return { kind: 'unreachable' };
  }

  const duration = result.transcription_info?.duration;
  return {
    kind: 'ok',
    // Only the transcript. See the note on `Upstream`.
    body: JSON.stringify({ text: result.text ?? '' }),
    reportedSeconds: typeof duration === 'number' && duration > 0 ? duration : null,
    // Reported rather than calculated (see `NEURONS` in config.ts). Absent it falls back to the
    // per-minute table, which is why this stays 0 instead of guessing here.
    neurons: typeof result.usage?.neurons === 'number' ? result.usage.neurons : 0,
  };
}

/**
 * Base64 for the audio, in chunks.
 *
 * `String.fromCharCode(...bytes)` on a ten-minute recording spreads nineteen million arguments
 * across the stack and takes the isolate down with it. Thirty-two kilobytes at a time is safe.
 *
 * Measured on 19 MB: **about two seconds of CPU** and a 26 MB string, byte-identical to a reference
 * encoder at every size including both sides of a chunk boundary. Chunk size makes no difference —
 * 8 k, 32 k and 64 k all land within a tenth of each other, and building an array to join is
 * slower than appending. The cost is the per-byte conversion itself and there is no way around it
 * while the model wants a string.
 *
 * Both figures matter and neither is a problem: two seconds against a five-minute CPU limit, and
 * 26 MB of string on top of the 19 MB buffer and the file the form is still holding — call it 65 MB
 * against 128. Worth knowing before assuming a call without a network hop is simply the faster one:
 * for a long recording it starts two seconds behind.
 */
function base64(bytes: Uint8Array): string {
  const step = 0x8000;
  let binary = '';
  for (let i = 0; i < bytes.length; i += step) {
    binary += String.fromCharCode(...bytes.subarray(i, i + step));
  }
  return btoa(binary);
}
