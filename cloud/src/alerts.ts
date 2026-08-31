import type { Env } from './config';
import { sendMail } from './notify/email';
import { renderAlertMail } from './notify/render';
import { alertSettings } from './settings';

/**
 * Raising, storing and de-duplicating alerts.
 *
 * The service used to be mute. It could reach its spending cap, be drained by a refund, or watch
 * a price list move under it, and none of that reached anyone until somebody happened to
 * open the dashboard. This is the part that speaks.
 *
 * Two decisions shape everything here:
 *
 *  - **Recording and sending are separate.** An alert is written to the database first and mailed
 *    second. If mail is misconfigured or Cloudflare has a bad minute, the warning still exists and
 *    shows up in the dashboard. The reverse — a warning that was sent but never recorded — would
 *    leave nothing to look at afterwards.
 *  - **A condition that lasts for hours is one alert, not ninety-six.** The rules run every quarter
 *    of an hour; without a cooldown, "budget at 82 %" would arrive until midnight and the next
 *    genuine alert would be read as more of the same.
 */

export type Severity = 'critical' | 'notice';

export interface Alert {
  kind: string;
  severity: Severity;
  title: string;
  detail: string;
  walletId?: string | null;
  value?: number | null;
  /**
   * What makes this alert "the same one again". Include the thing that would have to change for
   * it to be worth another mail — the budget step, the wallet, the day — and nothing else.
   */
  dedupeKey: string;
}

/** How long the same key stays quiet. Long enough to survive a condition, short enough to renew. */
const COOLDOWN_MS = 6 * 60 * 60 * 1000;

/**
 * Records an alert unless the same one is already fresh, and mails it if it is critical.
 *
 * Returns false when it was suppressed as a duplicate — useful for the rules, which can then stop
 * doing expensive follow-up work for something nobody will see.
 */
export async function raise(env: Env, alert: Alert, ctx?: ExecutionContext): Promise<boolean> {
  const now = Date.now();

  const recent = await env.DB.prepare(
    'SELECT id FROM alerts WHERE dedupe_key = ? AND ts > ? LIMIT 1',
  ).bind(alert.dedupeKey, now - COOLDOWN_MS).first<{ id: number }>();
  if (recent) return false;

  const inserted = await env.DB.prepare(
    `INSERT INTO alerts (ts, kind, severity, wallet_id, title, detail, value, dedupe_key)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
  )
    .bind(
      now,
      alert.kind,
      alert.severity,
      alert.walletId ?? null,
      alert.title.slice(0, 200),
      alert.detail.slice(0, 2000),
      alert.value ?? null,
      alert.dedupeKey,
    )
    .run();

  const id = Number(inserted.meta?.last_row_id ?? 0);

  // Only the critical ones interrupt. Everything else waits for the daily digest — a mailbox that
  // receives one message a day gets read, one that receives fifteen gets a filter.
  //
  // Recording happened above regardless of whether mail is switched on. Turning off delivery must
  // not turn off the record: the dashboard is then the only place the warning exists, and losing it
  // there too would make "quiet" indistinguishable from "nothing happened".
  const settings = await alertSettings(env);
  if (alert.severity === 'critical' && settings.mail) {
    const deliver = async () => {
      const sent = await sendMail(env, renderAlertMail(env, alert), {
        to: settings.emailTo,
        from: settings.emailFrom,
      });
      if (sent && id) {
        await env.DB.prepare('UPDATE alerts SET sent_at = ? WHERE id = ?').bind(Date.now(), id).run();
      }
    };
    if (ctx) ctx.waitUntil(deliver());
    else await deliver();
  }

  return true;
}

/** Everything not yet acknowledged, newest first — the dashboard's list and the header count. */
export async function openAlerts(env: Env, limit = 50) {
  const rows = await env.DB.prepare(
    `SELECT id, ts, kind, severity, wallet_id AS walletId, title, detail, value, sent_at AS sentAt
       FROM alerts WHERE ack_at IS NULL ORDER BY ts DESC LIMIT ?`,
  ).bind(limit).all();

  const counts = await env.DB.prepare(
    `SELECT COALESCE(SUM(CASE WHEN severity = 'critical' THEN 1 ELSE 0 END), 0) AS critical,
            COUNT(*) AS total
       FROM alerts WHERE ack_at IS NULL`,
  ).first<{ critical: number; total: number }>();

  return {
    alerts: rows.results ?? [],
    open: Number(counts?.total ?? 0),
    critical: Number(counts?.critical ?? 0),
  };
}

/** The history, including what has been dealt with. */
export async function alertHistory(env: Env, { limit = 50, offset = 0 } = {}) {
  const total = await env.DB.prepare('SELECT COUNT(*) AS n FROM alerts').first<{ n: number }>();
  const rows = await env.DB.prepare(
    `SELECT id, ts, kind, severity, wallet_id AS walletId, title, detail, value,
            sent_at AS sentAt, ack_at AS ackAt, ack_by AS ackBy
       FROM alerts ORDER BY ts DESC LIMIT ? OFFSET ?`,
  ).bind(limit, offset).all();
  return { total: Number(total?.n ?? 0), limit, offset, alerts: rows.results ?? [] };
}

/**
 * Marks an alert as dealt with.
 *
 * Acknowledging is not deleting. What tripped, when, and who looked at it stays — the point of the
 * list is that it can be read backwards after something went wrong.
 */
export async function acknowledge(env: Env, actor: string, id: number): Promise<boolean> {
  const result = await env.DB.prepare(
    'UPDATE alerts SET ack_at = ?, ack_by = ? WHERE id = ? AND ack_at IS NULL',
  ).bind(Date.now(), actor, id).run();
  return (result.meta?.changes ?? 0) > 0;
}

/** Acknowledges everything open at once, for the morning after a noisy night. */
export async function acknowledgeAll(env: Env, actor: string): Promise<number> {
  const result = await env.DB.prepare(
    'UPDATE alerts SET ack_at = ?, ack_by = ? WHERE ack_at IS NULL',
  ).bind(Date.now(), actor).run();
  return result.meta?.changes ?? 0;
}
