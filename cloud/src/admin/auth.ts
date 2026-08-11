import type { Env } from '../config';

/**
 * Who may see the dashboard.
 *
 * Cloudflare Access does the actual gatekeeping — Google login, no password of our own to store or
 * lose. It puts a signed JWT on every request that gets through, and this verifies that signature
 * rather than trusting the header. The difference matters: a header can be forged by anything that
 * reaches the Worker, a signature cannot.
 *
 * **Fail closed.** Without `ACCESS_TEAM_DOMAIN` and `ACCESS_AUD` configured, the admin routes report
 * themselves as nonexistent. Deploying before Access is set up therefore exposes nothing — the
 * alternative, an open dashboard during a window nobody is watching, is the one outcome worth
 * engineering against.
 */

export interface AdminIdentity {
  email: string;
}

/** JWKS cached per isolate. Cloudflare rotates these keys, hence the short life. */
let cachedKeys: { fetchedAt: number; keys: JsonWebKey[] } | null = null;
const KEYS_TTL_MS = 60 * 60 * 1000;

export function adminConfigured(env: Env): boolean {
  return Boolean(env.ACCESS_TEAM_DOMAIN && env.ACCESS_AUD);
}

/** Returns the signed-in administrator, or null when the request may not pass. */
export async function authenticateAdmin(request: Request, env: Env): Promise<AdminIdentity | null> {
  if (!adminConfigured(env)) return null;

  const token =
    request.headers.get('cf-access-jwt-assertion') ||
    cookie(request, 'CF_Authorization');
  if (!token) return null;

  const parts = token.split('.');
  if (parts.length !== 3) return null;
  const [rawHeader, rawPayload, rawSignature] = parts as [string, string, string];

  let header: { kid?: string; alg?: string };
  let payload: { aud?: string | string[]; exp?: number; iss?: string; email?: string };
  try {
    header = JSON.parse(decoder(rawHeader));
    payload = JSON.parse(decoder(rawPayload));
  } catch {
    return null;
  }

  // Only RS256 — accepting whatever the token names would let a caller pick "none".
  if (header.alg !== 'RS256' || !header.kid) return null;

  const issuer = `https://${env.ACCESS_TEAM_DOMAIN}`;
  if (payload.iss !== issuer) return null;

  const audiences = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
  if (!audiences.includes(env.ACCESS_AUD!)) return null;

  if (!payload.exp || payload.exp * 1000 <= Date.now()) return null;

  const jwk = await findKey(env, header.kid);
  if (!jwk) return null;

  const key = await crypto.subtle.importKey(
    'jwk',
    jwk,
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['verify'],
  );
  const ok = await crypto.subtle.verify(
    'RSASSA-PKCS1-v1_5',
    key,
    base64urlToBytes(rawSignature),
    new TextEncoder().encode(`${rawHeader}.${rawPayload}`),
  );
  if (!ok) return null;

  // Every admin action is written to admin_log under this address, which is the whole point of
  // having a real identity here rather than a shared secret.
  return { email: payload.email ?? 'unknown' };
}

async function findKey(env: Env, kid: string): Promise<JsonWebKey | null> {
  const fresh = cachedKeys && Date.now() - cachedKeys.fetchedAt < KEYS_TTL_MS;
  if (!fresh) {
    const response = await fetch(`https://${env.ACCESS_TEAM_DOMAIN}/cdn-cgi/access/certs`);
    if (!response.ok) return null;
    const body = (await response.json()) as { keys?: JsonWebKey[] };
    cachedKeys = { fetchedAt: Date.now(), keys: body.keys ?? [] };
  }
  return cachedKeys?.keys.find((k) => (k as { kid?: string }).kid === kid) ?? null;
}

function cookie(request: Request, name: string): string | null {
  const header = request.headers.get('cookie');
  if (!header) return null;
  for (const part of header.split(';')) {
    const [key, ...rest] = part.trim().split('=');
    if (key === name) return rest.join('=');
  }
  return null;
}

function base64urlToBytes(value: string): Uint8Array {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(padded + '='.repeat((4 - (padded.length % 4)) % 4));
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

function decoder(value: string): string {
  return new TextDecoder().decode(base64urlToBytes(value));
}
