import type { Env } from './config';

/**
 * Access to the Google Play Developer API.
 *
 * Google wants an OAuth2 access token, which you fetch with a self-signed JWT. A Worker has
 * no library for that — no Node, no `jsonwebtoken` — so the JWT is assembled by hand here and
 * signed with WebCrypto. It sounds like more than it is: header and claims as base64url,
 * signed with the service account's private key, done.
 *
 * **Why at all:** the purchase token the app sends is worthless on its own — anyone could
 * invent one. Only Google's answer to this request says bindingly whether money changed
 * hands. That is the single reason this service can be secure at all while the app is open
 * source.
 */

interface ServiceAccount {
  client_email: string;
  private_key: string;
}

/**
 * Access token cached per isolate.
 *
 * Google issues them with an hour's life. An isolate rarely lives that long, but while it
 * does this saves one request per purchase — and purchases like to arrive in waves when
 * someone has just discovered the app.
 */
let cachedToken: { value: string; expiresAt: number } | null = null;

export async function playAccessToken(env: Env): Promise<string> {
  if (cachedToken && cachedToken.expiresAt > Date.now() + 60_000) return cachedToken.value;

  const account = JSON.parse(env.GOOGLE_SERVICE_ACCOUNT) as ServiceAccount;
  const now = Math.floor(Date.now() / 1000);
  const header = base64url(new TextEncoder().encode(JSON.stringify({ alg: 'RS256', typ: 'JWT' })));
  const claims = base64url(
    new TextEncoder().encode(
      JSON.stringify({
        iss: account.client_email,
        scope: 'https://www.googleapis.com/auth/androidpublisher',
        aud: 'https://oauth2.googleapis.com/token',
        iat: now,
        exp: now + 3600,
      }),
    ),
  );

  const unsigned = `${header}.${claims}`;
  const key = await importPrivateKey(account.private_key);
  const signature = await crypto.subtle.sign(
    'RSASSA-PKCS1-v1_5',
    key,
    new TextEncoder().encode(unsigned),
  );
  const assertion = `${unsigned}.${base64url(new Uint8Array(signature))}`;

  const response = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion,
    }),
  });

  if (!response.ok) {
    throw new Error(`Google rejected the service account (${response.status})`);
  }

  const token = (await response.json()) as { access_token: string; expires_in: number };
  cachedToken = {
    value: token.access_token,
    expiresAt: Date.now() + token.expires_in * 1000,
  };
  return token.access_token;
}

/** What Google says about a purchase — and only that counts. */
export interface PurchaseFacts {
  /** 0 = purchased, 1 = cancelled, 2 = pending (e.g. paying cash at a counter). */
  purchaseState: number;
  /** 0 = not yet consumed, 1 = consumed. */
  consumptionState: number;
  orderId?: string;
  purchaseTimeMillis?: string;
  /** The wallet ID the app attached to the purchase — our thread back to the account. */
  obfuscatedExternalAccountId?: string;
  /** ISO 3166-1 alpha-2 billing region, so the ledger records which price list applied. */
  regionCode?: string;
  quantity?: number;
  /**
   * 0 = test (a licence tester, no money), 1 = promo, 2 = rewarded. **Absent for a normal paid
   * purchase** — Google only sets it when the purchase was not an ordinary sale.
   *
   * Worth storing: a tester's order reports the nominal price with zero tax and zero developer
   * revenue, which is truthful but would drag a revenue average down and inflate the sales count
   * if it were mixed in with real ones.
   */
  purchaseType?: number;
}

/**
 * Asks Google whether this purchase is real.
 *
 * Throws on network trouble; returns `null` when Google does not know the purchase (404) —
 * which is the case for an invented token.
 */
export async function verifyPurchase(
  env: Env,
  productId: string,
  purchaseToken: string,
): Promise<PurchaseFacts | null> {
  const packageName = env.PACKAGE_NAME ?? 'net.devemperor.dictate';
  const url =
    `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/` +
    `${encodeURIComponent(packageName)}/purchases/products/` +
    `${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}`;

  const response = await fetch(url, {
    headers: { authorization: `Bearer ${await playAccessToken(env)}` },
  });

  if (response.status === 404 || response.status === 400) return null;
  if (!response.ok) throw new Error(`Play Developer API answered ${response.status}`);
  return (await response.json()) as PurchaseFacts;
}

/** An amount as Google states it: units + nanos + ISO-4217 code. */
export interface Money {
  currencyCode?: string;
  units?: string;
  nanos?: number;
}

/** What an order was actually worth — all amounts in micros of [currency]. */
export interface OrderFacts {
  currency: string;
  /** What the customer paid, including tax. */
  paidMicros: number;
  taxMicros: number;
  /**
   * What reaches you after Google's cut. The only figure that is actually income — and **null when
   * Google has not stated one**, which is the ordinary case in the hours after a sale: the share is
   * worked out once the payment settles, not at the moment the purchase completes.
   *
   * Null and 0 are different answers and must stay that way. A licence tester's order really is
   * worth zero; a fresh sale that Google has not accounted for yet is worth an unknown amount, and
   * the two looked identical in the ledger until a real sale sat there reading 0.00 €.
   */
  revenueMicros: number | null;
  buyerCountry?: string;
  /** `PENDING`, `PROCESSED`, `REFUNDED`, … — worth storing to explain a missing revenue figure. */
  state?: string;
}

/**
 * Asks Google what an order was really worth.
 *
 * The list price in `config.ts` is what we *asked* for; this is what was *paid* — Play converts per
 * country, adds the local tax and keeps its share, so the two are never the same number. Taking it
 * from here rather than from the app matters twice: the app could be modified to claim anything,
 * and it does not know the developer's share at all.
 *
 * Returns null when the order cannot be read, which is never fatal — the credit was already granted
 * on the strength of the purchase check, and this only enriches the ledger.
 */
export async function fetchOrder(env: Env, orderId: string): Promise<OrderFacts | null> {
  const packageName = env.PACKAGE_NAME ?? 'net.devemperor.dictate';
  const url =
    `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/` +
    `${encodeURIComponent(packageName)}/orders/${encodeURIComponent(orderId)}`;

  const response = await fetch(url, {
    headers: { authorization: `Bearer ${await playAccessToken(env)}` },
  });
  if (!response.ok) return null;

  const order = (await response.json()) as {
    total?: Money; tax?: Money; developerRevenueInBuyerCurrency?: Money;
    buyerAddress?: { buyerCountry?: string };
    state?: string;
  };

  const total = order.total;
  if (!total?.currencyCode) return null;

  return {
    currency: total.currencyCode,
    paidMicros: toMicros(order.total) ?? 0,
    taxMicros: toMicros(order.tax) ?? 0,
    revenueMicros: toMicros(order.developerRevenueInBuyerCurrency),
    buyerCountry: order.buyerAddress?.buyerCountry,
    state: order.state,
  };
}

/**
 * Money → integer micros, or null where Google sent no such amount at all.
 *
 * Kept integer so summing thousands of orders never drifts, and kept nullable so an absent field
 * cannot pass itself off as a zero — see [OrderFacts.revenueMicros] for why that distinction is the
 * difference between "not accounted for yet" and "earned nothing".
 */
function toMicros(money: Money | undefined): number | null {
  if (!money) return null;
  const units = Number(money.units ?? 0);
  const nanos = Number(money.nanos ?? 0);
  return Math.round(units * 1_000_000 + nanos / 1000);
}

/**
 * Acknowledges a purchase to Google.
 *
 * Google automatically voids any purchase left unacknowledged for three days. For consumables
 * the app normally handles this with `consumeAsync`. This is the safety net: if the app
 * crashes between redemption and consumption, the credit is already granted — and the
 * purchase must not be unwound three days later.
 */
export async function acknowledgePurchase(
  env: Env,
  productId: string,
  purchaseToken: string,
): Promise<void> {
  const packageName = env.PACKAGE_NAME ?? 'net.devemperor.dictate';
  const url =
    `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/` +
    `${encodeURIComponent(packageName)}/purchases/products/` +
    `${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}:acknowledge`;

  await fetch(url, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${await playAccessToken(env)}`,
      'content-type': 'application/json',
    },
    body: '{}',
  });
}

async function importPrivateKey(pem: string): Promise<CryptoKey> {
  const body = pem
    .replace('-----BEGIN PRIVATE KEY-----', '')
    .replace('-----END PRIVATE KEY-----', '')
    .replace(/\s+/g, '');
  const raw = Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
  return crypto.subtle.importKey(
    'pkcs8',
    raw,
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['sign'],
  );
}

function base64url(bytes: Uint8Array): string {
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/**
 * Every purchase Google has voided in a window — refunds, chargebacks, developer cancellations.
 *
 * A backstop for the push notifications, not a replacement. RTDN arrives over Pub/Sub, and Pub/Sub
 * gives up after its retention window: a notification lost while this Worker was broken, or during
 * a bad deploy, is lost for good. What it would have taken back is credit that stays granted
 * forever — the one failure in this service that silently costs money and never announces itself.
 *
 * Cheap enough to run nightly over a generous window: this returns tokens, and re-processing one
 * that was already handled is a no-op because `revoke` checks the state first.
 */
export async function listVoidedPurchases(
  env: Env,
  sinceMs: number,
): Promise<Array<{ purchaseToken: string; orderId?: string; voidedTimeMillis?: string }>> {
  const packageName = env.PACKAGE_NAME ?? 'net.devemperor.dictate';
  const out: Array<{ purchaseToken: string; orderId?: string; voidedTimeMillis?: string }> = [];
  let token: string | null = null;

  for (let page = 0; page < 20; page++) {
    const url = new URL(
      `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/` +
      `${encodeURIComponent(packageName)}/purchases/voidedpurchases`,
    );
    url.searchParams.set('startTime', String(sinceMs));
    // 0 = only user-initiated voids, 1 = those plus the ones Google voided itself. We want both:
    // a chargeback the bank forced is exactly as expensive as a refund the customer asked for.
    url.searchParams.set('type', '1');
    url.searchParams.set('maxResults', '1000');
    if (token) url.searchParams.set('token', token);

    const response = await fetch(url, {
      headers: { authorization: `Bearer ${await playAccessToken(env)}` },
    });
    if (!response.ok) throw new Error(`voidedpurchases answered ${response.status}`);

    const body = (await response.json()) as {
      voidedPurchases?: Array<{ purchaseToken?: string; orderId?: string; voidedTimeMillis?: string }>;
      tokenPagination?: { nextPageToken?: string };
    };
    for (const v of body.voidedPurchases ?? []) {
      if (v.purchaseToken) {
        out.push({ purchaseToken: v.purchaseToken, orderId: v.orderId, voidedTimeMillis: v.voidedTimeMillis });
      }
    }
    token = body.tokenPagination?.nextPageToken ?? null;
    if (!token) break;
  }
  return out;
}
