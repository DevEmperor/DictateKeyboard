import { raise } from '../alerts';
import { LEGACY_REWORD_SECONDS, transcribeCostNano, type Env } from '../config';
import { homeCurrency, usdRate } from '../fx';
import { walletStub } from '../meter';
import { alertSettings } from '../settings';
import { json } from '../util';

/**
 * `POST /v1/rtdn` — Google's real-time developer notifications.
 *
 * This is where anything Google reports on its own arrives, refunds above all. Without this
 * endpoint, "buy a pack, spend the minutes, claw the money back" would be wide open: the
 * purchase disappears at Google while the credit stays with us.
 *
 * Registered as a Pub/Sub push target. Google sends the actual notification base64-encoded in
 * `message.data`.
 *
 * **Access control:** a secret in the query string. Pub/Sub can also send OIDC tokens, but
 * verifying those would be a second pile of crypto for the same statement — "this request is
 * ours". The endpoint does nothing an attacker would want anyway: it can only *remove*
 * credit, and only for a purchase they would have to know already.
 */
export async function handleRtdn(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const url = new URL(request.url);
  if (!env.RTDN_SECRET || url.searchParams.get('key') !== env.RTDN_SECRET) {
    return new Response('forbidden', { status: 403 });
  }

  let envelope: { message?: { data?: string } };
  try {
    envelope = await request.json();
  } catch {
    // Pub/Sub retries anything not acknowledged with 2xx. Retrying will not fix broken JSON,
    // so accept and drop it — otherwise delivery runs forever.
    return json({ ok: true, ignored: 'bad_json' });
  }

  const raw = envelope.message?.data;
  if (!raw) return json({ ok: true, ignored: 'empty' });

  let notification: PlayNotification;
  try {
    notification = JSON.parse(atob(raw)) as PlayNotification;
  } catch {
    return json({ ok: true, ignored: 'bad_payload' });
  }

  const expectedPackage = env.PACKAGE_NAME ?? 'net.devemperor.dictate';
  if (notification.packageName && notification.packageName !== expectedPackage) {
    return json({ ok: true, ignored: 'other_package' });
  }

  const voided = notification.voidedPurchaseNotification;
  if (voided?.purchaseToken) {
    ctx.waitUntil(revoke(env, voided.purchaseToken, ctx));
  }

  // Everything else (test messages, purchase notifications) is only acknowledged: credit is
  // created solely through /v1/wallet/redeem, where the purchase is additionally verified
  // with Google directly.
  return json({ ok: true });
}

/**
 * Takes back the credit of a refunded purchase — into the negative if need be.
 *
 * The negative is intentional. Someone who buys 400 minutes, spends 300 and then claws their
 * money back should not be left with 100 minutes and free rein. The shortfall stays until a
 * new purchase settles it.
 */
export async function revoke(env: Env, purchaseToken: string, ctx: ExecutionContext): Promise<boolean> {
  const row = await env.DB.prepare(
    `SELECT p.wallet_id AS walletId, p.seconds, p.rewords, p.state, p.purchased_at AS purchasedAt,
            p.revenue_home_micros AS revenueHome, p.currency, w.status AS walletStatus
       FROM purchases p LEFT JOIN wallets w ON w.id = p.wallet_id
      WHERE p.purchase_token = ?`,
  )
    .bind(purchaseToken)
    .first<{
      walletId: string; seconds: number; rewords: number; state: string;
      purchasedAt: number; revenueHome: number | null; currency: string | null;
      walletStatus: string | null;
    }>();

  // Already handled, or never ours. Returning false rather than throwing is what makes the nightly
  // sweep safe to run over a wide window: re-offering a purchase that was dealt with weeks ago
  // costs one indexed lookup and changes nothing.
  if (!row || row.state === 'voided') return false;

  // Refunded after the account was already deleted — an order that stays in the books while the
  // account it belonged to is an emptied row. There is nothing left to claw back: the balance was
  // erased on deletion and no token reaches that wallet any more. Taking it negative anyway would
  // only put a debt on a row nobody can ever settle, and write minutes onto an account that is
  // supposed to hold none.
  const walletGone = row.walletStatus === null || row.walletStatus === 'deleted';

  // Read *before* the claw-back, because that is the number that decides whether this refund cost
  // anything. Minutes already spent are minutes already paid for at OpenAI. For a deleted account
  // the usage rows went with it, so the honest answer is "unknown" rather than a confident zero.
  const used = walletGone
    ? null
    : await env.DB.prepare(
        `SELECT COALESCE(SUM(seconds), 0) AS seconds FROM usage_log
          WHERE wallet_id = ? AND ts >= ? AND status < 400`,
      ).bind(row.walletId, row.purchasedAt).first<{ seconds: number }>();
  const usedSeconds = walletGone ? null : Math.min(Number(used?.seconds ?? 0), row.seconds);

  // Purchases made before rewordings were priced also granted a separate allowance, which the
  // wallet folded into its seconds on first touch. Clawing back only `seconds` would leave that
  // part behind, so it is converted the same way the wallet converted it — at the *frozen* rate,
  // not at what a rewording costs today. The two used to be one number and no longer are.
  const clawSeconds = row.seconds + Math.max(0, row.rewords) * LEGACY_REWORD_SECONDS;
  const state = walletGone ? null : await walletStub(env, row.walletId).claw(clawSeconds);

  const writes = [
    env.DB.prepare("UPDATE purchases SET state = 'voided' WHERE purchase_token = ?").bind(purchaseToken),
    env.DB.prepare(
      `INSERT INTO admin_log (ts, actor, wallet_id, action, delta_secs, delta_words, note)
       VALUES (?, 'google-rtdn', ?, 'void', ?, ?, ?)`,
    ).bind(
      Date.now(),
      row.walletId,
      walletGone ? 0 : -clawSeconds,
      0,
      `Refund for purchase ${purchaseToken.slice(0, 12)}…` +
        (walletGone ? ' — Konto war bereits gelöscht, nichts zurückzuholen.' : ''),
    ),
  ];
  if (state) {
    writes.splice(1, 0, env.DB.prepare(
      'UPDATE wallets SET seconds_left = ?, rewords_left = ? WHERE id = ?',
    ).bind(state.secondsLeft, state.rewordsLeft, row.walletId));
  }
  await env.DB.batch(writes);

  // Counted on the account, not only in the alert. One refund is life; the second from the same
  // person is a pattern, and the pattern is only visible if somebody kept score.
  await env.DB.prepare('UPDATE wallets SET void_count = COALESCE(void_count, 0) + 1 WHERE id = ?')
    .bind(row.walletId).run();

  await reportLoss(env, ctx, {
    walletId: row.walletId,
    purchaseToken,
    seconds: row.seconds,
    usedSeconds,
    revenueHomeMicros: row.revenueHome,
    walletGone,
  });
  return true;
}

/**
 * Puts a number on what the refund actually cost.
 *
 * This is the one place in the whole service where money leaves and does not come back. Everything
 * else is bounded: a customer can only spend what they bought. Here they bought, spent, and took
 * the payment back — the credit returns to us as a negative balance, the seconds at OpenAI do not.
 *
 * A refund with nothing consumed is a non-event and stays silent; that is a customer changing
 * their mind, which is allowed and costs nothing.
 *
 * [usedSeconds] is null when the account was deleted before the refund arrived: its usage rows went
 * with it, so how much had been dictated is no longer knowable. That case always reports, whatever
 * the threshold says — an unmeasurable refund is not the same as a harmless one, and delete-then-
 * refund is the exact order somebody would use to leave no trace.
 */
async function reportLoss(
  env: Env,
  ctx: ExecutionContext,
  { walletId, purchaseToken, seconds, usedSeconds, revenueHomeMicros, walletGone }: {
    walletId: string; purchaseToken: string; seconds: number;
    usedSeconds: number | null; revenueHomeMicros: number | null; walletGone: boolean;
  },
): Promise<void> {
  const usedPercent = seconds > 0 && usedSeconds !== null ? (usedSeconds / seconds) * 100 : 0;

  // How often it has happened across the accounts that are demonstrably linked, not just on this
  // one. A reinstall makes a new wallet, so counting per wallet would reset the history exactly
  // when it starts to matter.
  //
  // The link is `play_account_hash` — the wallet id the app attached to the purchase, hashed. It is
  // **not** a Google identifier, so it only exists where a purchase topped up an account that was
  // already there. Where someone deleted before buying again, this count legitimately reads 1 and
  // says nothing about whether it is the first time.
  const repeat = await env.DB.prepare(
    `SELECT COALESCE(SUM(void_count), 0) AS n FROM wallets
      WHERE play_account_hash IS NOT NULL
        AND play_account_hash = (SELECT play_account_hash FROM wallets WHERE id = ?)`,
  ).bind(walletId).first<{ n: number }>();
  const times = Math.max(1, Number(repeat?.n ?? 1));

  const settings = await alertSettings(env);
  if (!settings.enabled) return;
  if (!walletGone && usedPercent < settings.refundUsedPercent && times < 2) return;

  const { rate } = await usdRate(env);
  const home = homeCurrency(env);
  const revenue = revenueHomeMicros === null ? null : revenueHomeMicros / 1_000_000;

  if (walletGone) {
    await raise(env, {
      kind: 'refund_loss',
      severity: 'critical',
      walletId,
      value: 0,
      title: times > 1
        ? `${times}. Erstattung aus dieser Kette, Konto gelöscht`
        : 'Erstattung nach gelöschtem Konto',
      detail:
        `Ein Kauf über ${Math.round(seconds / 60)} Minuten wurde storniert, obwohl das zugehörige Konto ` +
        `bereits gelöscht war. Zurückzuholen gab es nichts — das Guthaben ist beim Löschen verfallen. ` +
        `Wie viel davon vorher diktiert wurde, lässt sich nicht mehr sagen: das Nutzungsprotokoll ist mit ` +
        `dem Konto entfernt worden.` +
        (revenue !== null ? ` Vom Erlös gehen ${revenue.toFixed(2)} ${home} wieder ab.` : '') +
        ` Löschen und danach erstatten ist genau die Reihenfolge, in der jemand keine Spuren ` +
        `hinterlassen würde` +
        (times > 1
          ? `, und es ist bereits die ${times}. Erstattung aus dieser Kette von Konten.`
          : `.`) +
        ` Erwarte hier keine Warnung beim nächsten Kauf: Die Wiedererkennung hängt an der Kennung, ` +
        `die die App beim Kauf anhängt, und nach einer Löschung hängt sie keine an. Ein neuer Kauf ` +
        `von hier sieht aus wie ein Erstkauf. Wenn dir das zu oft begegnet, ist das der Punkt, an ` +
        `dem sich die Lücke zu schließen lohnt.`,
      dedupeKey: `refund_loss:${purchaseToken}`,
    }, ctx);
    return;
  }

  const spent = usedSeconds ?? 0;
  const lossUsd = transcribeCostNano(spent) / 1_000_000_000;
  const lossHome = lossUsd * rate;

  await raise(env, {
    kind: 'refund_loss',
    severity: 'critical',
    walletId,
    value: lossHome,
    title: times > 1
      ? `${times}. Erstattung aus dieser Kette von Konten`
      : `Erstattung nach ${Math.round(spent / 60)} verbrauchten Minuten`,
    detail:
      `Ein Kauf über ${Math.round(seconds / 60)} Minuten wurde storniert, nachdem davon ` +
      `${Math.round(usedPercent)} % bereits diktiert waren. Das Guthaben ist zurückgeholt — das Konto steht ` +
      `jetzt im Minus und kann erst nach einem neuen Kauf weiterarbeiten. Zurück kommt aber nur das Guthaben: ` +
      `die ${Math.round(spent / 60)} Minuten sind bei OpenAI bezahlt und kosten dich rund ` +
      `${lossHome.toFixed(2)} ${home}` +
      (revenue !== null ? `, während ${revenue.toFixed(2)} ${home} Erlös wieder abgezogen werden` : '') +
      (times > 1
        ? `. Es ist bereits die ${times}. Erstattung aus dieser Kette von Konten — die Aufladungen hängen ` +
          `nachweislich zusammen. Ein Muster, kein Zufall: Sperren ist hier angebracht.`
        : `. Wiederholt sich das mit demselben Konto, ist Sperren angebracht — dann ist es kein Zufall mehr.`),
    // Per purchase. Google can send the same notification more than once, and the same customer
    // refunding a second pack is genuinely new.
    dedupeKey: `refund_loss:${purchaseToken}`,
  }, ctx);
}

interface PlayNotification {
  packageName?: string;
  eventTimeMillis?: string;
  voidedPurchaseNotification?: {
    purchaseToken?: string;
    orderId?: string;
    productType?: number;
    refundType?: number;
  };
  oneTimeProductNotification?: {
    notificationType?: number;
    purchaseToken?: string;
    sku?: string;
  };
  testNotification?: { version?: string };
}
