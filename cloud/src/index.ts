import { handleAdmin } from './admin';
import { limitsFrom, type Env } from './config';
import { backfillPurchases, fetchRates } from './fx';
import { maybeSendDigest } from './notify/digest';
import { evaluateRules } from './notify/rules';
import { syncOrderFigures } from './orders';
import { handleChat } from './routes/chat';
import { handleDelete, handleDeleteByCode } from './routes/delete';
import { DELETE_PAGE_HTML } from './routes/delete-page';
import { handleModels } from './routes/models';
import { handleRedeem } from './routes/redeem';
import { handleRtdn } from './routes/rtdn';
import { pruneDeletedWallets, pruneUsageLog } from './retention';
import { sweepVoidedPurchases } from './sweep';
import { handleTranscription } from './routes/transcriptions';
import { handleBalance, handleRestore, handleRevokeDevice } from './routes/wallet';
import { NO_STORE, apiError, json } from './util';

/**
 * Dictate Cloud — the credit proxy.
 *
 * The three OpenAI-compatible endpoints are no accident: because this service speaks the same
 * paths and formats as OpenAI, the app's existing `OpenAiCompatibleClient` works unchanged —
 * the credit token simply takes the place of the API key.
 *
 * What never happens here: writing audio or text to disk. Everything is passed through; only
 * numbers are stored (see `meter.ts`).
 */
export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';
    const limits = limitsFrom(env);

    // Liveness — answers "is the service up" without giving anything away.
    if (path === '/' || path === '/health') {
      return json({ service: 'dictate-cloud', ok: true });
    }

    // The dashboard. Returns null for anything that is not under /admin, so this costs one string
    // comparison on the hot path.
    const admin = await handleAdmin(request, env, ctx);
    if (admin) return admin;

    if (request.method === 'POST' && path === '/v1/audio/transcriptions') {
      return handleTranscription(request, env, ctx, limits);
    }
    if (request.method === 'POST' && path === '/v1/chat/completions') {
      return handleChat(request, env, ctx, limits);
    }
    if (request.method === 'GET' && path === '/v1/models') {
      return handleModels(limits);
    }
    if (request.method === 'GET' && path === '/v1/wallet') {
      return handleBalance(request, env, ctx);
    }
    if (request.method === 'POST' && path === '/v1/wallet/restore') {
      return handleRestore(request, env, ctx);
    }
    // Frees a slot when the device limit is reached — see handleRevokeDevice on why the recovery
    // code is the right warrant for it.
    if (request.method === 'POST' && path === '/v1/wallet/devices/revoke') {
      return handleRevokeDevice(request, env);
    }
    // Account deletion, both ways Google requires it: from inside the app with the credit token,
    // and from a browser with the recovery code for anyone who has already uninstalled.
    if (request.method === 'POST' && path === '/v1/wallet/delete') {
      return handleDelete(request, env);
    }
    if (request.method === 'POST' && path === '/v1/wallet/delete-by-code') {
      return handleDeleteByCode(request, env);
    }
    // HEAD as well as GET. Link checkers — Google's among them, when it verifies the account
    // deletion URL for the Play listing — routinely ask with HEAD first, because they only want to
    // know whether the page exists. Answering 404 to that makes a page that works perfectly in a
    // browser look broken to the one visitor who decides whether the app ships.
    if ((request.method === 'GET' || request.method === 'HEAD') && path === '/delete') {
      return new Response(request.method === 'HEAD' ? null : DELETE_PAGE_HTML, {
        headers: { 'content-type': 'text/html; charset=utf-8', ...NO_STORE },
      });
    }
    if (request.method === 'POST' && path === '/v1/wallet/redeem') {
      return handleRedeem(request, env, ctx);
    }
    if (request.method === 'POST' && path === '/v1/rtdn') {
      return handleRtdn(request, env, ctx);
    }
    return apiError(404, `Unknown endpoint: ${path}`, 'not_found', 'invalid_request_error');
  },

  /**
   * Two rhythms, told apart by the cron expression that fired.
   *
   * **Every quarter hour** the watchdog runs and the digest checks whether its hour has come.
   * Quarter-hourly rather than hourly because the thing it is looking for — a fresh pack being
   * burned through — happens on the scale of minutes, and an hour late is a report rather than a
   * warning.
   *
   * **Nightly** the slow work: let the detail log age out, fetch the day's exchange rates, fill in
   * conversions for purchases still missing one. Deliberately at an odd minute rather than on the
   * hour, where every cron on the platform piles up.
   */
  async scheduled(event: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    const nightly = event.cron === NIGHTLY_CRON;

    if (!nightly) {
      ctx.waitUntil(evaluateRules(env, ctx).then((n) => {
        if (n > 0) console.log(`alerts: raised ${n}`);
      }).catch((error) => console.log(`alerts failed: ${String(error).slice(0, 200)}`)));
      ctx.waitUntil(maybeSendDigest(env).catch((error) => {
        console.log(`digest failed: ${String(error).slice(0, 200)}`);
        return false;
      }).then(() => undefined));
      return;
    }

    ctx.waitUntil(
      pruneUsageLog(env).then(({ deleted, cutoff }) => {
        if (deleted > 0) {
          console.log(`retention: removed ${deleted} usage rows older than ${new Date(cutoff).toISOString()}`);
        }
      }),
    );
    // The one identifier a deletion deliberately leaves behind, cut loose once its window passes.
    ctx.waitUntil(
      pruneDeletedWallets(env).then(({ cleared }) => {
        if (cleared > 0) console.log(`retention: cleared the Play pseudonym on ${cleared} deleted wallet(s)`);
      }),
    );
    // Three steps that each need the one before it, so they run in a row rather than side by side:
    // today's rates, then the sales Google had not worked out its share of at redemption time, then
    // the conversion of whatever that turned up. A failure in the middle does not stop the end —
    // the conversion still has last night's figures to do something with.
    ctx.waitUntil(
      fetchRates(env)
        .then(() => syncOrderFigures(env))
        .then(({ asked, filled }) => {
          if (asked > 0) console.log(`orders: asked about ${asked}, ${filled} now have a revenue figure`);
        })
        .catch((error) => console.log(`order sync failed: ${String(error).slice(0, 200)}`))
        .then(() => backfillPurchases(env))
        .then((filled) => {
          if (filled > 0) console.log(`fx: converted ${filled} purchases`);
        })
        .catch((error) => console.log(`fx failed: ${String(error).slice(0, 200)}`)),
    );
    // The safety net under the refund notifications — see sweep.ts on why push alone is not enough.
    ctx.waitUntil(
      sweepVoidedPurchases(env, ctx)
        .then((n) => { if (n > 0) console.log(`void sweep: repaired ${n}`); })
        .catch((error) => console.log(`void sweep failed: ${String(error).slice(0, 200)}`)),
    );
  },
} satisfies ExportedHandler<Env>;

/** Must match `triggers.crons` in `wrangler.jsonc` character for character. */
const NIGHTLY_CRON = '17 3 * * *';

export { Wallet } from './wallet';
export { GlobalGuard } from './guard';
