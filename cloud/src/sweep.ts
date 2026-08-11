import { raise } from './alerts';
import type { Env } from './config';
import { listVoidedPurchases } from './google';
import { revoke } from './routes/rtdn';

/**
 * The nightly check that nothing was missed.
 *
 * Refunds normally arrive as a push notification. Push is not a guarantee: Pub/Sub retries for a
 * while and then gives up, so a notification that arrived while this Worker was broken, or during
 * a bad deploy, is gone for good. What it would have taken back is credit that stays granted for
 * ever — the only failure mode in this service that costs money *and* stays silent.
 *
 * So once a night the ledger is compared against Google's own list of voided purchases. Anything
 * still marked as granted here but voided there is put right, and reported, because a discrepancy
 * means the push path is not working and that is worth knowing before the next one.
 *
 * The window is deliberately far wider than Pub/Sub's retention: re-offering a purchase that was
 * already handled costs one indexed lookup and changes nothing.
 */
const WINDOW_DAYS = 30;

export async function sweepVoidedPurchases(env: Env, ctx: ExecutionContext): Promise<number> {
  const since = Date.now() - WINDOW_DAYS * 86_400_000;

  let voided;
  try {
    voided = await listVoidedPurchases(env, since);
  } catch (error) {
    // Google being unreachable is not a finding. Saying so out loud is still better than a sweep
    // that reports "nothing missed" when it never managed to look.
    console.log(`void sweep failed: ${String(error).slice(0, 200)}`);
    return 0;
  }

  let repaired = 0;
  for (const entry of voided) {
    if (await revoke(env, entry.purchaseToken, ctx)) repaired++;
  }

  if (repaired > 0) {
    await raise(env, {
      kind: 'void_sweep',
      severity: 'critical',
      value: repaired,
      title: `${repaired} Erstattung${repaired === 1 ? '' : 'en'} nachträglich verarbeitet`,
      detail:
        `Der nächtliche Abgleich mit Google hat ${repaired} stornierte${repaired === 1 ? 'n' : ''} Kauf` +
        `${repaired === 1 ? '' : 'e'} gefunden, ${repaired === 1 ? 'der' : 'die'} über die Push-Benachrichtigung ` +
        `nie angekommen ${repaired === 1 ? 'war' : 'waren'}. Das Guthaben ist jetzt zurückgeholt. ` +
        `Dass etwas nachzuholen war, heißt aber, dass der Meldeweg nicht sauber lief — prüfe die ` +
        `Pub/Sub-Verknüpfung in der Play Console und ob RTDN_SECRET noch stimmt. Ohne diesen Abgleich ` +
        `wäre das Guthaben dauerhaft verschenkt geblieben.`,
      dedupeKey: `void_sweep:${new Date().toISOString().slice(0, 10)}`,
    }, ctx);
  }

  return repaired;
}
