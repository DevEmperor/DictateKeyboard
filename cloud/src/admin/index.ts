import { acknowledge, acknowledgeAll, alertHistory, openAlerts } from '../alerts';
import type { Env } from '../config';
import { sendDigest } from '../notify/digest';
import { durableObjectPlacement } from '../meter';
import { evaluateRules } from '../notify/rules';
import { RULE_KEYS, SETTING_KEYS, alertSettings, changedKeys, resetSettings, saveSettings } from '../settings';
import { NO_STORE, json } from '../util';
import {
  giftCredit,
  mergeWallets,
  refreshOrder,
  resetRecoveryCode,
  revokeToken,
  setBlocked,
  setKillSwitch,
  setNote,
  setTestAccount,
} from './actions';
import { adminConfigured, authenticateAdmin } from './auth';
import { adminLog, overview, recentRequests, walletDetail, wallets } from './data';
import { finance, history, months, plans, summary, reconciliation } from './finance';
import { DASHBOARD_HTML } from './page';
import { addExpense, deleteExpense, taxReport } from './tax';

/**
 * Routes everything under `/admin`.
 *
 * Returns null when the path is not ours, so the main router can carry on. Anything that *is* ours
 * is authenticated first — including the HTML, so an unauthenticated visitor gets nothing at all
 * rather than an empty shell that hints at what lives here.
 *
 * Every handler that reaches outside takes `ctx`, so a cached answer past its age can be served
 * immediately and refreshed behind the response. That single change is most of the reason the
 * dashboard stopped feeling slow: it used to call OpenAI's paginated billing endpoint twice on
 * every page load and wait for both.
 */
export async function handleAdmin(request: Request, env: Env, ctx: ExecutionContext): Promise<Response | null> {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, '') || '/';
  if (path !== '/admin' && !path.startsWith('/admin/')) return null;

  // Unconfigured is indistinguishable from absent, on purpose — see admin/auth.ts.
  if (!adminConfigured(env)) return new Response('Not found', { status: 404 });

  const admin = await authenticateAdmin(request, env);
  if (!admin) {
    return new Response('Forbidden', { status: 403, headers: NO_STORE });
  }

  if (path === '/admin' && request.method === 'GET') {
    return new Response(DASHBOARD_HTML, {
      headers: { 'content-type': 'text/html; charset=utf-8', ...NO_STORE },
    });
  }

  if (request.method === 'GET') {
    switch (path) {
      case '/admin/api/overview':
        return json(await overview(env));
      case '/admin/api/wallets':
        return json({
          wallets: await wallets(
            env,
            url.searchParams.get('q') ?? '',
            url.searchParams.get('test') === '1',
            url.searchParams.get('deleted') === '1',
          ),
        });
      case '/admin/api/requests':
        return json(await recentRequests(env, {
          limit: clamp(url.searchParams.get('limit'), 50, 200),
          offset: Math.max(0, Number(url.searchParams.get('offset') ?? 0) || 0),
          kind: url.searchParams.get('kind') ?? '',
          failuresOnly: url.searchParams.get('failures') === '1',
          walletId: url.searchParams.get('wallet') ?? '',
          includeTest: url.searchParams.get('test') !== '0',
        }));
      case '/admin/api/log':
        return json(await adminLog(env, {
          limit: clamp(url.searchParams.get('limit'), 50, 200),
          offset: Math.max(0, Number(url.searchParams.get('offset') ?? 0) || 0),
        }));
      case '/admin/api/me':
        return json({ email: admin.email });
      case '/admin/api/alerts':
        return json(url.searchParams.get('all') === '1'
          ? await alertHistory(env, {
              limit: clamp(url.searchParams.get('limit'), 50, 200),
              offset: Math.max(0, Number(url.searchParams.get('offset') ?? 0) || 0),
            })
          : await openAlerts(env));
      case '/admin/api/plans':
        return json(await plans(env));
      // One endpoint for the money, where there used to be two. `summary` and `finance` each
      case '/admin/api/money': {
        const [sum, fin] = await Promise.all([summary(env), finance(env)]);
        return json({ summary: sum, ...fin });
      }
      case '/admin/api/settings':
        return json({
          settings: await alertSettings(env),
          // Which values no longer come from the deployment. Shown so it is always clear whether
          // a number was typed in here or shipped with the code.
          changed: await changedKeys(env),
          mailBound: Boolean(env.MAIL),
          // Where the balances physically live. Checked rather than assumed: the local runtime
          // cannot do jurisdictions and silently falls back, and the same fallback in production
          // would make a sentence in the privacy policy untrue.
          doPlacement: durableObjectPlacement(env),
          // The binding pins the permitted recipient at deploy time and cannot be overridden from
          // a web page — on purpose. Shown so a changed address that silently bounces is not a
          // mystery.
          pinnedRecipient: env.ALERT_EMAIL_TO ?? null,
        });
      case '/admin/api/reconcile':
        return json(await reconciliation(env));
      case '/admin/api/tax':
        return json(await taxReport(env, ctx));
      case '/admin/api/history':
        return json({
          days: await history(env, clamp(url.searchParams.get('days'), 365, 1095)),
          months: await months(env),
        });
    }
    const detail = path.match(/^\/admin\/api\/wallet\/([^/]+)$/);
    if (detail) {
      const found = await walletDetail(env, decodeURIComponent(detail[1]!));
      return found ? json(found) : json({ error: 'not_found' }, 404);
    }
  }

  if (request.method === 'POST' && path === '/admin/api/action') {
    let body: Record<string, string | number | boolean>;
    try {
      body = (await request.json()) as Record<string, string | number | boolean>;
    } catch {
      return json({ ok: false, message: 'Die Anfrage ist kein gültiges JSON.' }, 400);
    }

    const walletId = String(body.walletId ?? '');
    const note = String(body.note ?? '');

    // Nothing can be done to a deleted account, and doing it anyway would be worse than useless:
    // credit written to it lands in a wallet no token reaches, and a fresh recovery code would
    // recreate a credential for data that was erased on request. The dashboard hides the buttons;
    // this is the same rule where it actually holds.
    if (walletId) {
      const target = await env.DB.prepare('SELECT status FROM wallets WHERE id = ?')
        .bind(walletId)
        .first<{ status: string }>();
      if (target?.status === 'deleted') {
        return json({ ok: false, message: 'Dieses Konto ist gelöscht — daran lässt sich nichts mehr ändern.' }, 409);
      }
    }

    switch (String(body.action)) {
      case 'gift':
        return json(await giftCredit(env, admin, walletId, Number(body.minutes), note));
      case 'block':
        return json(await setBlocked(env, admin, walletId, true, note));
      case 'unblock':
        return json(await setBlocked(env, admin, walletId, false, note));
      case 'recovery_reset':
        return json(await resetRecoveryCode(env, admin, walletId, note));
      case 'revoke_token':
        return json(await revokeToken(env, admin, walletId, String(body.tokenHash ?? ''), note));
      case 'merge':
        return json(await mergeWallets(env, admin, walletId, String(body.targetId ?? ''), note));
      case 'note':
        return json(await setNote(env, admin, walletId, note));
      // No note required: this decides nothing, it asks Google what the order was worth and writes
      // down the answer. See `refreshOrder`.
      case 'refetch_order':
        return json(await refreshOrder(env, admin, walletId, String(body.purchaseToken ?? '')));
      case 'mark_test':
        return json(await setTestAccount(env, admin, walletId, true, note));
      case 'unmark_test':
        return json(await setTestAccount(env, admin, walletId, false, note));
      case 'kill_on':
        return json(await setKillSwitch(env, admin, true, note));
      case 'kill_off':
        return json(await setKillSwitch(env, admin, false, note));
      case 'save_settings': {
        const patch: Record<string, string> = {};
        // Only known keys, so a typo in a request body cannot quietly create a setting that
        // nothing reads and that then looks like it is doing something.
        const allowed: string[] = [...SETTING_KEYS, ...RULE_KEYS.map((k) => `rule.${k}`)];
        for (const key of allowed) {
          if (body[key] !== undefined) patch[key] = String(body[key]);
        }
        await saveSettings(env, admin.email, patch);
        await logAdmin(env, admin.email, 'settings', note || 'Warnungen angepasst');
        return json({ ok: true, message: 'Gespeichert. Wirkt binnen einer Minute überall.' });
      }
      case 'reset_settings':
        await resetSettings(env);
        await logAdmin(env, admin.email, 'settings_reset', note || 'auf Auslieferungswerte zurückgesetzt');
        return json({ ok: true, message: 'Zurückgesetzt — es gelten wieder die Werte aus wrangler.jsonc.' });
      case 'add_expense':
        return json(await addExpense(env, admin, {
          paidAt: Date.parse(String(body.paidAt ?? '')),
          kind: String(body.kind ?? 'cloudflare'),
          amount: Number(body.amount),
          currency: String(body.currency ?? 'USD'),
          // Empty means "work it out from the day's rate". Zero would mean "it cost nothing",
          // which is a different claim and not one a blank field should make.
          amountHome: body.amountHome === '' || body.amountHome === undefined || body.amountHome === null
            ? null
            : Number(body.amountHome),
          reference: String(body.reference ?? ''),
          note,
        }));
      case 'delete_expense':
        return json(await deleteExpense(env, Number(body.id)));
      case 'ack_alert':
        return json({
          ok: await acknowledge(env, admin.email, Number(body.id)),
          message: 'Erledigt.',
        });
      case 'ack_all_alerts': {
        const n = await acknowledgeAll(env, admin.email);
        return json({ ok: true, message: `${n} Warnung${n === 1 ? '' : 'en'} als erledigt markiert.` });
      }
      // Proving the alarm works without waiting for something to go wrong. An untested alarm is
      // an assumption, and the first time you find out it was misconfigured should not be the
      // night it mattered.
      case 'test_rules': {
        const n = await evaluateRules(env, ctx);
        return json({
          ok: true,
          message: n > 0
            ? `${n} neue Warnung${n === 1 ? '' : 'en'} ausgelöst.`
            : 'Alle Regeln geprüft, nichts Auffälliges gefunden.',
        });
      }
      case 'test_digest': {
        const sent = await sendDigest(env);
        return json({
          ok: sent,
          message: sent
            ? 'Tagesbericht verschickt — schau in dein Postfach.'
            : 'Konnte nicht verschickt werden. Prüfe ALERT_EMAIL_FROM/TO und ob die Absenderdomain bei Cloudflare zum Versand freigegeben ist.',
        });
      }
      default:
        return json({ ok: false, message: 'Unbekannte Aktion.' }, 400);
    }
  }

  return new Response('Not found', { status: 404, headers: NO_STORE });
}

/**
 * Settings changes go into the same audit trail as everything else.
 *
 * Without it, "why did the warnings stop" has no answer six months later — and a watchdog that was
 * switched off and forgotten is worse than one that was never built.
 */
async function logAdmin(env: Env, actor: string, action: string, note: string): Promise<void> {
  await env.DB.prepare(
    `INSERT INTO admin_log (ts, actor, wallet_id, action, delta_secs, delta_words, note)
     VALUES (?, ?, NULL, ?, 0, 0, ?)`,
  ).bind(Date.now(), actor, action, note.slice(0, 500)).run();
}

/** Page sizes come from the query string, so they are bounded rather than believed. */
function clamp(value: string | null, fallback: number, max: number): number {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? Math.min(Math.floor(n), max) : fallback;
}
