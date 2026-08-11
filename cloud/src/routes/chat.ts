import { authenticate, touch } from '../auth';
import { OPENAI_BASE, chatCostNano, costToSeconds, type Env, type Limits } from '../config';
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
 * the difference is given back once OpenAI reports what it really was. The same reserve-then-
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
  const worstCaseNano = chatCostNano(inputTokens, limits.maxChatOutputTokens);
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

  // The server decides the model, the output length and the reasoning effort. Whatever the client
  // sends for any of the three is discarded — see below.
  const upstream: Record<string, unknown> = {
    model: limits.chatModel,
    messages,
    max_completion_tokens: Math.min(
      Number(payload.max_completion_tokens ?? payload.max_tokens ?? limits.maxChatOutputTokens),
      limits.maxChatOutputTokens,
    ),
  };
  // Fixed, and the client does not get a say. Two reasons, and the second is the one that matters.
  //
  // Rewriting does not need deliberation. Left unset the model applies its own default — `medium`
  // for the gpt-5 family — and pays for it out of the same budget as the answer: one request spent
  // 116 seconds and twelve thousand tokens thinking, then had no room left to write anything.
  //
  // And a client-chosen effort would be a dial that multiplies what a request costs *here* without
  // costing the person turning it anything. The price of a pack is calculated for a rewriting job;
  // a setting that turns one request into ten is not the buyer's to turn. On their own API key it
  // is exactly their business, and the app still offers it there.
  upstream.reasoning_effort = 'minimal';

  let response: Response;
  try {
    response = await fetch(`${OPENAI_BASE}/chat/completions`, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${env.OPENAI_API_KEY}`,
        'content-type': 'application/json',
      },
      body: JSON.stringify(upstream),
    });
  } catch {
    await wallet.refund(reservedSeconds);
    settleBudget(env, -worstCaseNano, ctx);
    return apiError(502, 'The service is unreachable.', 'upstream_unreachable', 'server_error');
  }

  const body = await response.text();

  if (!response.ok) {
    const state = await wallet.refund(reservedSeconds);
    settleBudget(env, -worstCaseNano, ctx);
    logUsage(env, {
      walletId: session.walletId,
      tokenHash: session.tokenHash,
      isTest: session.isTest,
      kind: 'reword',
      costNano: 0,
      status: response.status,
      ms: Date.now() - started,
      secondsLeft: state.secondsLeft,
      rewordsLeft: state.rewordsLeft,
      secondsUsedTotal: state.secondsUsed,
    }, ctx);
    return apiError(502, 'The rewording failed.', 'upstream_rejected', 'server_error');
  }

  // What it really cost, from OpenAI's own count rather than our estimate of it. The reservation
  // is settled against that, so the account is charged to the second — usually a good deal less
  // than was held, because the full permitted answer is rarely used.
  const usage = parseUsage(body);
  const actualNano = chatCostNano(usage.in || inputTokens, usage.out);
  const actualSeconds = costToSeconds(actualNano);
  settleBudget(env, actualNano - worstCaseNano, ctx);

  let state = debit.state;
  if (actualSeconds !== reservedSeconds) {
    state = await wallet.adjust(actualSeconds - reservedSeconds);
  }

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
