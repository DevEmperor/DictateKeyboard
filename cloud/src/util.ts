/** Odds and ends several routes need: hashes, codes, response shapes. */

/**
 * Error responses deliberately use OpenAI's shape.
 *
 * The client (`DictateApiException.fromHttp`) reads the status, `message`, `code` and `type`
 * and derives its error class from them. Invent a format here and the app shows "unknown
 * error" instead of "out of credit".
 */
export function apiError(
  status: number,
  message: string,
  code: string,
  type = 'dictate_cloud_error',
): Response {
  return json({ error: { message, type, code } }, status);
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', ...NO_STORE },
  });
}

/**
 * Nothing this service returns may ever be cached.
 *
 * A balance is only true for the moment it was read, and a transcript belongs to exactly one
 * request. Without this header the zone's own cache rules decide — and this domain also
 * serves a static site, where caching everything is the sensible default.
 */
export const NO_STORE = { 'cache-control': 'no-store' } as const;

/** SHA-256 as hex. Tokens and recovery codes are stored this way only. */
export async function sha256(input: string): Promise<string> {
  const data = new TextEncoder().encode(input);
  const digest = await crypto.subtle.digest('SHA-256', data);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** Access token: 32 random bytes, base64url. Issued exactly once and never logged. */
export function newToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return `dk_${base64url(bytes)}`;
}

/**
 * Recovery code in the form `DICT-7F3K-9QM2-XR41`.
 *
 * The alphabet drops 0/O, 1/I/L and U: the code gets typed in by hand, often from a photo or
 * a scrap of paper, and that is exactly where support cases are born. Twelve characters out
 * of 29 is about 58 bits — plenty against guessing, especially as every attempt costs a
 * database lookup.
 */
const CODE_ALPHABET = '23456789ABCDEFGHJKMNPQRSTVWXYZ';

export function newRecoveryCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(12));
  const chars = [...bytes].map((b) => CODE_ALPHABET[b % CODE_ALPHABET.length]!);
  return `DICT-${chars.slice(0, 4).join('')}-${chars.slice(4, 8).join('')}-${chars.slice(8, 12).join('')}`;
}

/** Forgiving about typos: case and dashes do not matter. */
export function normalizeRecoveryCode(input: string): string {
  const bare = input.toUpperCase().replace(/[^0-9A-Z]/g, '');
  if (bare.length !== 16 || !bare.startsWith('DICT')) return '';
  const body = bare.slice(4);
  return `DICT-${body.slice(0, 4)}-${body.slice(4, 8)}-${body.slice(8, 12)}`;
}

function base64url(bytes: Uint8Array): string {
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function uuid(): string {
  return crypto.randomUUID();
}

/** UTC day as `YYYY-MM-DD` — the key of the daily totals. */
export function today(now = Date.now()): string {
  return new Date(now).toISOString().slice(0, 10);
}

/**
 * Rough token estimate for the input check: about four characters per token.
 *
 * This is not real tokenisation and is not meant to be — it only exists to turn away an
 * absurdly large request before it costs money. Whatever slips through is capped by the model's
 * own context limit.
 */
export function estimateTokens(text: string): number {
  return Math.ceil(text.length / 4);
}

/**
 * A number out of something that may not be one.
 *
 * SQLite hands back a *string* whenever a sum outgrows what fits comfortably in a double, and that
 * turns arithmetic into concatenation without complaining — the mistake then shows up somewhere far
 * away, in a figure nobody suspects. Everything read out of the database goes through here.
 *
 * Lived in `costs.ts` while that file existed for OpenAI's billing endpoint; it never had anything
 * to do with OpenAI.
 */
export function num(value: unknown): number {
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) ? n : 0;
}
