import type { Severity } from '../alerts';
import type { Env } from '../config';
import { num, openaiCosts } from '../costs';
import { homeCurrency, usdRate } from '../fx';
import { alertSettings } from '../settings';
import { sendMail } from './email';
import { renderDigestMail } from './render';

/**
 * One mail a day, whether or not anything happened.
 *
 * The "whether or not" is the point. A digest that only arrives when there is bad news is
 * indistinguishable from a digest that failed to send, and after a quiet fortnight you no longer
 * know which of the two you are looking at. Its arrival is the proof that the watchdog is awake.
 *
 * It carries yesterday's figures and everything the rules raised that was not urgent enough to
 * interrupt. Critical alerts are in here too, listed for completeness — they were already mailed
 * on their own at the time.
 */

const DIGEST_MARK = 'digest:last-day';

export async function maybeSendDigest(env: Env): Promise<boolean> {
  const settings = await alertSettings(env);
  if (!settings.enabled || !settings.digest || !settings.mail) return false;

  const now = new Date();
  if (now.getUTCHours() !== settings.digestHourUtc) return false;

  const day = now.toISOString().slice(0, 10);
  const mark = await env.DB.prepare('SELECT payload FROM cache WHERE key = ?')
    .bind(DIGEST_MARK).first<{ payload: string }>();
  // The cron fires four times an hour. Without this the digest would go out four times.
  if (mark?.payload === JSON.stringify(day)) return false;

  const sent = await sendDigest(env);
  await env.DB.prepare('INSERT OR REPLACE INTO cache (key, payload, fetched_at) VALUES (?, ?, ?)')
    .bind(DIGEST_MARK, JSON.stringify(day), Date.now()).run();
  return sent;
}

/** Builds and sends it. Exposed separately so the dashboard can trigger a test copy. */
export async function sendDigest(env: Env): Promise<boolean> {
  const home = homeCurrency(env);
  const yesterday = new Date(Date.now() - 86_400_000).toISOString().slice(0, 10);
  const dayStart = Date.parse(`${yesterday}T00:00:00Z`);
  const dayEnd = dayStart + 86_400_000;

  const [totals, sales, wallets, liability, alerts, costs, fx] = await Promise.all([
    env.DB.prepare(
      'SELECT requests, seconds, errors, cost_nano AS costNano FROM daily_totals WHERE day = ?',
    ).bind(yesterday).first<{ requests: number; seconds: number; errors: number; costNano: number }>(),
    env.DB.prepare(
      `SELECT COUNT(*) AS orders, COALESCE(SUM(p.revenue_home_micros), 0) AS revenue
         FROM purchases p JOIN wallets w ON w.id = p.wallet_id AND w.is_test = 0
        WHERE p.state = 'granted' AND p.purchased_at >= ? AND p.purchased_at < ?`,
    ).bind(dayStart, dayEnd).first<{ orders: number; revenue: number }>(),
    env.DB.prepare(
      'SELECT COUNT(*) AS n FROM wallets WHERE is_test = 0 AND created_at >= ? AND created_at < ?',
    ).bind(dayStart, dayEnd).first<{ n: number }>(),
    env.DB.prepare(
      "SELECT COALESCE(SUM(seconds_left), 0) AS seconds FROM wallets WHERE status = 'active' AND is_test = 0",
    ).first<{ seconds: number }>(),
    env.DB.prepare(
      `SELECT ts, severity, title, detail FROM alerts
        WHERE ts >= ? ORDER BY CASE severity WHEN 'critical' THEN 0 ELSE 1 END, ts DESC LIMIT 25`,
    ).bind(Date.now() - 86_400_000).all<{ ts: number; severity: Severity; title: string; detail: string }>(),
    openaiCosts(env, 30),
    usdRate(env),
  ]);

  const revenue = num(sales?.revenue) / 1_000_000;
  const costUsd = num(totals?.costNano) / 1_000_000_000;

  const figures: Array<{ label: string; value: string }> = [
    { label: 'Anfragen', value: `${num(totals?.requests).toLocaleString('de-DE')}${num(totals?.errors) ? ` (${num(totals?.errors)} Fehler)` : ''}` },
    { label: 'Diktiert', value: `${Math.round(num(totals?.seconds) / 60).toLocaleString('de-DE')} Minuten` },
    { label: 'Verkäufe', value: `${num(sales?.orders)} · ${revenue.toFixed(2)} ${home}` },
    { label: 'Einkauf (eigene Rechnung)', value: `${costUsd.toFixed(4)} $` },
    { label: 'Neue Konten', value: String(num(wallets?.n)) },
    { label: 'Offenes Guthaben', value: `${Math.round(num(liability?.seconds) / 60).toLocaleString('de-DE')} Minuten` },
  ];

  // Only when OpenAI is actually reachable. A line that says "unknown" every morning teaches you
  // to skip the block it sits in.
  if (costs.connected && costs.serviceUsd !== null) {
    figures.push({
      label: 'Einkauf laut OpenAI (30 T)',
      value: `${costs.serviceUsd.toFixed(2)} $ ≈ ${(costs.serviceUsd * fx.rate).toFixed(2)} ${home}`,
    });
  }

  const settings = await alertSettings(env);
  return sendMail(env, renderDigestMail(env, {
    day: yesterday,
    figures,
    alerts: (alerts.results ?? []).map((a) => ({
      ts: a.ts, severity: a.severity, title: a.title, detail: a.detail,
    })),
  }), { to: settings.emailTo, from: settings.emailFrom });
}
