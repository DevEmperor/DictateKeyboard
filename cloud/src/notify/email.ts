// Statically, not through `await import`. It is a runtime built-in rather than a bundled module,
// and a dynamic import of one is the kind of thing that resolves in the bundler and fails on the
// edge — at the exact moment something is going wrong and the mail matters.
import { EmailMessage } from 'cloudflare:email';
import type { Env } from '../config';

/**
 * Sending mail through Cloudflare Email Routing.
 *
 * No third party and no API key: the Worker hands the message to the same infrastructure the
 * domain's mail already runs on. The cost is that the message has to be a complete MIME document,
 * which is why this file exists.
 *
 * Written by hand rather than with a library. The whole requirement is four headers and two body
 * parts; a dependency for that is a dependency to keep updated for years.
 *
 * **Prerequisites, and both are silent when missing:** Email Routing must be active on the zone,
 * and the *sender* domain must be verified for sending. An unverified sender fails with
 * `E_SENDER_NOT_VERIFIED`.
 */

export interface Mail {
  subject: string;
  text: string;
  html: string;
}

export interface MailRoute {
  to: string;
  from: string;
}

export function mailConfigured(env: Env, route?: MailRoute): boolean {
  return Boolean(env.MAIL && (route?.to ?? env.ALERT_EMAIL_TO) && (route?.from ?? env.ALERT_EMAIL_FROM));
}

/**
 * Returns whether it went out. Never throws: an alert that cannot be mailed is still an alert,
 * and it is already recorded by the time this runs. Losing the record because the mail server
 * had a bad minute would be the wrong trade.
 *
 * The addresses are passed in rather than read from the environment, because they can now be
 * changed from the dashboard — see `settings.ts`. The binding in `wrangler.jsonc` still pins which
 * recipient is permitted at all, and that pin is deliberately not overridable from a web page.
 */
export async function sendMail(env: Env, mail: Mail, route?: MailRoute): Promise<boolean> {
  if (!mailConfigured(env, route)) return false;
  const to = route?.to || env.ALERT_EMAIL_TO!;
  const from = route?.from || env.ALERT_EMAIL_FROM!;

  try {
    await env.MAIL!.send(new EmailMessage(from, to, buildMime(from, to, mail)));
    return true;
  } catch (error) {
    console.log(`alert mail failed: ${String(error).slice(0, 200)}`);
    return false;
  }
}

/**
 * A minimal `multipart/alternative` message.
 *
 * Both parts are base64 over UTF-8. Quoted-printable would be more readable on the wire, but it
 * has to break lines without splitting an escape and without touching a trailing space — three
 * ways to produce a mail that renders as mojibake in exactly one client. Base64 has none of them,
 * and nobody reads the raw source of an alert.
 */
function buildMime(from: string, to: string, mail: Mail): string {
  const boundary = `dictate-${crypto.randomUUID()}`;
  const date = new Date().toUTCString();

  return [
    `From: Dictate Cloud <${from}>`,
    `To: ${to}`,
    `Subject: ${encodeHeader(mail.subject)}`,
    `Date: ${date}`,
    `Message-ID: <${crypto.randomUUID()}@${from.split('@')[1] ?? 'dictate'}>`,
    'MIME-Version: 1.0',
    // Alerts are transactional. Without this an autoresponder somewhere can answer the alert,
    // which then arrives as mail, and two machines talk to each other all weekend.
    'Auto-Submitted: auto-generated',
    `Content-Type: multipart/alternative; boundary="${boundary}"`,
    '',
    `--${boundary}`,
    'Content-Type: text/plain; charset=utf-8',
    'Content-Transfer-Encoding: base64',
    '',
    base64Utf8(mail.text),
    `--${boundary}`,
    'Content-Type: text/html; charset=utf-8',
    'Content-Transfer-Encoding: base64',
    '',
    base64Utf8(mail.html),
    `--${boundary}--`,
    '',
  ].join('\r\n');
}

/** RFC 2047, so a subject containing "Warnung: Guthaben übersprungen" survives the journey. */
function encodeHeader(value: string): string {
  // eslint-disable-next-line no-control-regex
  if (!/[^\x00-\x7F]/.test(value)) return value;
  return `=?UTF-8?B?${base64(new TextEncoder().encode(value))}?=`;
}

function base64Utf8(text: string): string {
  return wrap(base64(new TextEncoder().encode(text)));
}

function base64(bytes: Uint8Array): string {
  let binary = '';
  // Byte by byte rather than spreading into `String.fromCharCode`: a spread of a long body is
  // one argument per byte and blows the call stack somewhere around a hundred thousand of them.
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary);
}

/** Base64 in a mail body must not exceed 76 characters a line. */
function wrap(value: string): string {
  const lines: string[] = [];
  for (let i = 0; i < value.length; i += 76) lines.push(value.slice(i, i + 76));
  return lines.join('\r\n');
}
