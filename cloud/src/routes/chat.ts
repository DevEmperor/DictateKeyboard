import { raise } from '../alerts';
import { authenticate, touch } from '../auth';
import { chatCostNano, costToSeconds, neuronsToNano, type Env, type Limits } from '../config';
import { budgetAllows, logUsage, settleBudget, walletStub } from '../meter';
import { NO_STORE, apiError, estimateTokens } from '../util';
import { debitError, logRefusal } from './transcriptions';

/**
 * `POST /v1/chat/completions` — the rewording.
 *
 * Billed in the same seconds as dictation, at what it actually costs: a typical rewording is
 * worth about two, a maximal one sixteen. It used to be counted instead — one unit per request,
 * whatever its size — which meant a large rewording deducted a fifth of what it cost. The two
 * token limits capped the damage per request but not per pack, so a pack could be turned into a
 * loss simply by rewording at full length instead of dictating.
 *
 * Exact billing cannot happen before the call, because the token count is only known after it.
 * So the worst case is reserved — the input as estimated plus the largest permitted answer — and
 * the difference is given back once the model reports what it really was. The same reserve-then-
 * settle the dictation path uses for files whose length cannot be read from a header.
 */
export async function handleChat(
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

  let payload: ChatRequest;
  try {
    payload = (await request.json()) as ChatRequest;
  } catch {
    return apiError(400, 'Request is not valid JSON.', 'bad_request', 'invalid_request_error');
  }

  const messages = Array.isArray(payload.messages) ? payload.messages : [];
  if (messages.length === 0) {
    return apiError(400, 'Field "messages" is missing.', 'missing_messages', 'invalid_request_error');
  }

  const inputTokens = messages.reduce((sum, m) => sum + estimateTokens(textOf(m.content)), 0);
  if (inputTokens > limits.maxChatInputTokens) {
    logRefusal(env, session, 'reword', 413, started, ctx);
    return apiError(
      413,
      'The text is too long to reword through Dictate Cloud.',
      'input_too_long',
      'invalid_request_error',
    );
  }

  // At worst the request costs the full input plus the full permitted output. That ceiling is
  // what the daily budget is checked against; it is corrected to the real usage afterwards.
  const worstCaseNano = chatCostNano(limits.chatModel, inputTokens, limits.maxChatOutputTokens);
  if (!(await budgetAllows(env, limits, worstCaseNano, ctx))) {
    logRefusal(env, session, 'reword', 503, started, ctx);
    return apiError(
      503,
      'Dictate Cloud is unavailable right now. Please try again later.',
      'service_paused',
      'server_error',
    );
  }

  const wallet = walletStub(env, session.walletId);
  const reservedSeconds = costToSeconds(worstCaseNano);

  const debit = await wallet.debit(reservedSeconds, limits.rateLimitPerMinute);
  if (!debit.ok) {
    settleBudget(env, -worstCaseNano, ctx);
    const refusal = debit.reason === 'insufficient'
      ? apiError(402, 'Out of credit.', 'insufficient_credits', 'insufficient_quota')
      : debitError(debit.reason);
    logRefusal(env, session, 'reword', refusal.status, started, ctx, debit.state);
    return refusal;
  }

  // The server decides the model and the output length. Whatever the client sends for either is
  // discarded — otherwise the costing would be wide open.
  const maxTokens = Math.min(
    Number(payload.max_completion_tokens ?? payload.max_tokens ?? limits.maxChatOutputTokens),
    limits.maxChatOutputTokens,
  );

  const upstream = await runWorkersAi(env, limits, messages, maxTokens);

  if (upstream.kind === 'unreachable') {
    await wallet.refund(reservedSeconds);
    settleBudget(env, -worstCaseNano, ctx);
    return apiError(502, 'The service is unreachable.', 'upstream_unreachable', 'server_error');
  }

  if (upstream.kind === 'failed') {
    const state = await wallet.refund(reservedSeconds);
    settleBudget(env, -worstCaseNano, ctx);
    logUsage(env, {
      walletId: session.walletId,
      tokenHash: session.tokenHash,
      isTest: session.isTest,
      kind: 'reword',
      costNano: 0,
      status: upstream.status,
      ms: Date.now() - started,
      secondsLeft: state.secondsLeft,
      rewordsLeft: state.rewordsLeft,
      secondsUsedTotal: state.secondsUsed,
    }, ctx);
    return apiError(502, 'The rewording failed.', 'upstream_rejected', 'server_error');
  }

  const body = upstream.body;

  // What it really cost, from the model's own count rather than our estimate of it: it reports the
  // neurons it spent, and that measurement wins. The reservation is settled against it, so the
  // account is charged to the second — usually a good deal less than was held, because the full
  // permitted answer is rarely used.
  const usage = parseUsage(body);
  const actualNano = upstream.neurons > 0
    ? neuronsToNano(upstream.neurons)
    : chatCostNano(limits.chatModel, usage.in || inputTokens, usage.out);
  const actualSeconds = costToSeconds(actualNano);
  settleBudget(env, actualNano - worstCaseNano, ctx);

  let state = debit.state;
  if (actualSeconds !== reservedSeconds) {
    state = await wallet.adjust(actualSeconds - reservedSeconds);
  }

  // Dictate Cloud does not think. Rewriting a sentence needs no deliberation, thinking tokens are
  // billed to the buyer's balance like any other output, and one request on the old provider once
  // spent 116 seconds and twelve thousand tokens on it before running out of room to answer.
  //
  // Which is why this is checked on every response rather than trusted to the request.
  // `chat_template_kwargs.enable_thinking` is the switch, thinking is *on* by default, and a model
  // update, a renamed field or a new value in CHAT_MODEL would each silently undo it.
  //
  // Measured against the answer, because the obvious instrument does not work: the type definitions
  // carry `usage.completion_tokens_details.reasoning_tokens`, and Workers AI never fills it (probed
  // 30.08.2026, with thinking on and off). A guard on that field would have reported quiet while
  // the model was thinking, which is worse than no guard. Thinking tokens do land in
  // `completion_tokens`, so a model that thinks spends far more than it says — the same test showed
  // 777 tokens against a twenty-token answer.
  ctx.waitUntil(reportReasoning(env, usage.out, answerTextOf(body), limits.chatModel));

  // An answer that ran out of room is not an answer. The budget covers the visible reply *and*
  // whatever the model spends on reasoning, so a request can come back with a perfectly valid
  // 200, a `length` finish and nothing usable in it — the app then quietly keeps the original and
  // the user is left wondering why their text did not change. Refused, so at least it says so. A
  // truncated rewrite is not the friendlier option: it would replace the text with a version that
  // stops mid-sentence.
  //
  // **Charged all the same**, and that is the deliberate part. The tokens were spent at OpenAI on
  // this account's behalf; they are gone whatever the answer looked like. Handing the credit back
  // would mean the operator pays for someone else's request — and with a full refund on a failure
  // the client can choose, a caller could burn the day's budget for free and take the service down
  // for everyone. Settled to the *real* cost rather than the reservation, so a failed attempt
  // costs a couple of seconds rather than the worst case.
  if (wasTruncated(body)) {
    logUsage(env, {
      walletId: session.walletId,
      tokenHash: session.tokenHash,
      isTest: session.isTest,
      kind: 'reword',
      seconds: actualSeconds,
      tokensIn: usage.in || inputTokens,
      tokensOut: usage.out,
      costNano: actualNano,
      status: 413,
      ms: Date.now() - started,
      secondsLeft: state.secondsLeft,
      rewordsLeft: state.rewordsLeft,
      secondsUsedTotal: state.secondsUsed,
    }, ctx);
    return apiError(
      413,
      'The rewording did not fit in the allowed answer length.',
      'reword_truncated',
      'invalid_request_error',
    );
  }

  logUsage(env, {
    walletId: session.walletId,
    tokenHash: session.tokenHash,
    isTest: session.isTest,
    kind: 'reword',
    seconds: actualSeconds,
    tokensIn: usage.in || inputTokens,
    tokensOut: usage.out,
    // The column the transcription route writes and this one did not. It cost nothing in money —
    // `costNano` above is already the measured figure — but it emptied the one number that turns
    // into an invoice: every rewording booked zero neurons, so the day's total, the free-allowance
    // bar and the billed cost were all short by whatever rewording had spent, and the dashboard
    // counted each one as *estimated* while its cost had in fact been measured.
    //
    // Provider and model are deliberately not passed: `meter.ts` fills both in, and a second copy
    // of a default is how the two drift apart.
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
      'x-dictate-seconds-left': String(state.secondsLeft),
      'x-dictate-rewords-left': String(state.rewordsLeft),
    },
  });
}

interface ChatRequest {
  messages?: Array<{ role: string; content: unknown }>;
  max_tokens?: number;
  max_completion_tokens?: number;
  /** Accepted from the wire and deliberately ignored — see where the request is built. */
  reasoning_effort?: string;
}

/** Content may be text or an array of parts — the estimate only needs the text. */
function textOf(content: unknown): string {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    return content
      .map((part) => (typeof part === 'object' && part && 'text' in part ? String((part as { text: unknown }).text) : ''))
      .join(' ');
  }
  return '';
}

/**
 * Whether the model stopped because it ran out of room rather than because it was finished.
 *
 * Belt and braces: `finish_reason: "length"` is the statement, and an empty message is the
 * symptom — a reasoning model that spends its whole budget thinking returns the second without
 * always setting the first.
 */
function wasTruncated(body: string): boolean {
  try {
    const parsed = JSON.parse(body) as {
      choices?: Array<{ finish_reason?: string; message?: { content?: string | null } }>;
    };
    const choice = parsed.choices?.[0];
    if (!choice) return false;
    if (choice.finish_reason === 'length') return true;
    return !choice.message?.content?.trim();
  } catch {
    return false;
  }
}

function parseUsage(body: string): { in: number; out: number } {
  try {
    const parsed = JSON.parse(body) as {
      usage?: { prompt_tokens?: number; completion_tokens?: number };
    };
    return {
      in: parsed.usage?.prompt_tokens ?? 0,
      out: parsed.usage?.completion_tokens ?? 0,
    };
  } catch {
    return { in: 0, out: 0 };
  }
}

/** The visible reply, for measuring the token count against. Empty when the shape is unexpected. */
function answerTextOf(body: string): string {
  try {
    const parsed = JSON.parse(body) as { choices?: { message?: { content?: string } }[] };
    return parsed.choices?.[0]?.message?.content ?? '';
  } catch {
    return '';
  }
}

/**
 * Raises `reasoning_leak` when a reply cost far more output tokens than it contains.
 *
 * Factor three, not two: token counts are estimated on our side and counted on theirs, and the
 * two disagree by a fair margin on short text. A model that is actually thinking overshoots by a
 * factor of tens, so the slack costs nothing in sensitivity. The floor keeps one-word answers —
 * where an estimate of four tokens against a real twelve is noise — from ringing it.
 */
async function reportReasoning(env: Env, tokensOut: number, answer: string, model: string): Promise<void> {
  const expected = Math.max(estimateTokens(answer), 16);
  if (tokensOut <= expected * 3) return;
  await raise(env, {
    kind: 'reasoning_leak',
    severity: 'critical',
    value: tokensOut / expected,
    title: `Das Umformulierungsmodell denkt wieder (${tokensOut} Token für ${expected} Token Antwort)`,
    detail:
      `\`${model}\` hat ${tokensOut} Ausgabe-Token verbraucht, in der Antwort stehen aber nur etwa ${expected}. ` +
      `Die Differenz sind Denk-Token: Sie werden wie jede andere Ausgabe abgerechnet und gehen damit vom ` +
      `Guthaben des Käufers ab, ohne dass er etwas davon bekommt. Bei Dictate Cloud soll nicht gedacht werden — ` +
      `zu prüfen ist, ob \`chat_template_kwargs.enable_thinking\` noch gesetzt wird bzw. das Modell den Schalter ` +
      `noch kennt. Ein Modellwechsel in CHAT_MODEL ist die häufigste Ursache.`,
    // Per model and hour: it is a state of the configuration, not a property of one request.
    dedupeKey: `reasoning_leak:${model}:${new Date().toISOString().slice(0, 13)}`,
  });
}

/**
 * One rewording.
 *
 * `body` is the OpenAI chat-completion shape, because `wasTruncated` and `parseUsage` read it and
 * the app's own parser expects it. Workers AI answers in that shape already — its binding is typed
 * `ChatCompletionsInput` in, `ChatCompletionsOutput` out — so what happens below is not a
 * translation but a trim: only the choice and the usage go back, not the `@cf/…` model name, which
 * would publish what is behind Dictate Cloud for no one's benefit.
 */
type Upstream =
  | { kind: 'ok'; body: string; neurons: number }
  | { kind: 'failed'; status: number }
  | { kind: 'unreachable' };

/**
 * The rewording itself, over the binding, and it does not think.
 *
 * `reasoning_effort` is deliberately **not** sent. Workers AI accepts only `low | medium | high`
 * for it — `minimal`, the value the gpt-5 family understood, does not exist here, so sending it
 * would at best be ignored. The switch that works is `chat_template_kwargs`, and the
 * type definition spells out why it has to be set rather than left alone: *"Whether to enable
 * reasoning, enabled by default."* Sending nothing means thinking.
 *
 * Measured on 30.08.2026, same sentence, same model: 20 output tokens against 777, 1.06 s against
 * 7.70, 1.43 neurons against 22.10 — for an answer that was the same either way. Whether it stays
 * off is not trusted to this line; every response is checked against the length of its own answer
 * (see `reportReasoning`).
 */
async function runWorkersAi(env: Env, limits: Limits, messages: unknown[], maxTokens: number): Promise<Upstream> {
  let result: {
    choices?: { message?: { content?: string | null }; finish_reason?: string }[];
    usage?: { prompt_tokens?: number; completion_tokens?: number; neurons?: number };
  };
  try {
    result = await env.AI.run(limits.chatModel as keyof AiModels, {
      messages,
      max_completion_tokens: maxTokens,
      chat_template_kwargs: { enable_thinking: false },
    } as never) as typeof result;
  } catch (error) {
    // The binding throws instead of answering with a status, so there is nothing to map: anything
    // that is not an answer refunds and returns 502.
    console.log(`workers-ai chat failed: ${String(error).slice(0, 200)}`);
    return { kind: 'unreachable' };
  }

  const choice = result.choices?.[0];
  return {
    kind: 'ok',
    body: JSON.stringify({
      choices: [{
        message: { role: 'assistant', content: choice?.message?.content ?? '' },
        finish_reason: choice?.finish_reason ?? 'stop',
      }],
      usage: {
        prompt_tokens: result.usage?.prompt_tokens ?? 0,
        completion_tokens: result.usage?.completion_tokens ?? 0,
      },
    }),
    neurons: typeof result.usage?.neurons === 'number' ? result.usage.neurons : 0,
  };
}
