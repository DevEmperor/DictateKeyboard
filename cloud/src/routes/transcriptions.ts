import { raise } from '../alerts';
import { estimateSeconds, probeDuration, shortestPossibleSeconds } from '../audio';
import { authenticate, touch } from '../auth';
import { OPENAI_BASE, transcribeCostNano, type Env, type Limits } from '../config';
import { budgetAllows, logUsage, settleBudget, walletStub } from '../meter';
import { NO_STORE, apiError } from '../util';

/**
 * `POST /v1/audio/transcriptions` — the path the money flows down.
 *
 * The order is intent, not taste:
 *   1. Who are you            → otherwise 401
 *   2. How long is the audio  → from the WAV header, not from a client claim
 *   3. May the service still  → daily cap
 *   4. Deduct                 → **before** OpenAI, or every abort is on the operator
 *   5. Forward
 *   6. Refund on failure, correct to the real duration on success
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
  // credit for minutes it does not contain. Corrected to the real duration once OpenAI reports it.
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

  // The server decides the model and the response format. Whatever the client sends as
  // `model` is deliberately discarded — otherwise the costing would be wide open.
  const upstream = new FormData();
  upstream.set('file', file, file.name || 'audio.wav');
  upstream.set('model', limits.transcribeModel);
  upstream.set('response_format', 'json');
  for (const key of ['language', 'prompt'] as const) {
    const value = form.get(key);
    if (typeof value === 'string' && value.trim()) upstream.set(key, value);
  }

  let response: Response;
  try {
    response = await fetch(`${OPENAI_BASE}/audio/transcriptions`, {
      method: 'POST',
      headers: { authorization: `Bearer ${env.OPENAI_API_KEY}` },
      body: upstream,
    });
  } catch {
    await wallet.refund(chargedSeconds);
    settleBudget(env, -estimateNano, ctx);
    return apiError(502, 'The transcription service is unreachable.', 'upstream_unreachable', 'server_error');
  }

  const body = await response.text();

  if (!response.ok) {
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
      status: response.status,
      ms: Date.now() - started,
      secondsLeft: state.secondsLeft,
      rewordsLeft: state.rewordsLeft,
      secondsUsedTotal: state.secondsUsed,
    }, ctx);
    return upstreamFailure(response.status);
  }

  // For WAV the duration was already exact. Other formats were estimated generously — if
  // OpenAI reports the real length, the difference goes back.
  let finalSeconds = chargedSeconds;
  let state = debit.state;
  if (!duration.exact) {
    const reported = reportedSeconds(body);
    if (reported !== null) {
      const actual = Math.ceil(reported);
      const delta = actual - chargedSeconds;
      if (delta !== 0) {
        state = await wallet.adjust(delta);
        settleBudget(env, transcribeCostNano(actual) - estimateNano, ctx);
        finalSeconds = actual;
      }
    } else {
      // The whole billing of non-WAV audio rests on this field. Without it the estimate stands, and
      // the estimate assumes 32 kbit/s — generous for an ordinary recording, so honest uploads are
      // simply over-charged and the correction that would have refunded them never runs. A file
      // deliberately encoded below that rate goes the other way and is billed short.
      //
      // Either way the number is wrong and nothing else would say so, which is why this reports
      // rather than guesses. Once every six hours per model is enough: this is a change at OpenAI,
      // not an event — it either happens for every request or for none.
      ctx.waitUntil(raise(env, {
        kind: 'audio_duration_missing',
        severity: 'critical',
        value: chargedSeconds,
        title: 'OpenAI meldet die Audiolänge nicht mehr',
        detail:
          `Eine Aufnahme, deren Länge sich nicht aus dem Dateikopf lesen ließ, wurde nach Größe ` +
          `geschätzt (${chargedSeconds} s) — und die Antwort von OpenAI enthielt kein ` +
          `\`usage.seconds\`, mit dem sich das hätte richtigstellen lassen. Bis dahin galt dieses ` +
          `Feld als gesetzt; fehlt es dauerhaft, wird jede Datei außer WAV falsch abgerechnet: ` +
          `gewöhnliche Aufnahmen zu teuer, absichtlich niedrig kodierte zu billig. Betroffen ist ` +
          `nur, was über die Dateiauswahl kommt — die App selbst nimmt WAV auf, und dort steht die ` +
          `Länge exakt im Kopf. Zu prüfen: das Antwortformat von \`${limits.transcribeModel}\` und ` +
          `ob OpenAI die Angabe umbenannt hat.`,
        dedupeKey: `audio_duration_missing:${limits.transcribeModel}`,
      }, ctx).then(() => undefined));
    }
  }

  logUsage(env, {
    walletId: session.walletId,
    tokenHash: session.tokenHash,
    isTest: session.isTest,
    kind: 'transcribe',
    seconds: finalSeconds,
    costNano: transcribeCostNano(finalSeconds),
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
 * An error from OpenAI is passed on, but not its wording.
 *
 * The user has no contract with OpenAI and can do nothing with "your organization has been
 * blocked" — and the message could give away internals.
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
