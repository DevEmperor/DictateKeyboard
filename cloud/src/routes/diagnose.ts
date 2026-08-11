import { PACKAGES, type Env } from '../config';
import { playAccessToken } from '../google';
import { apiError, json } from '../util';

/**
 * `GET /v1/diagnose` — does the Play side actually work?
 *
 * There are two separate things that can be wrong before the first purchase, and from the
 * outside they look the same: the service account key may be bad, or the key may be fine while
 * the account was never given access to *this app* in the Play Console. Redeeming an invented
 * purchase token cannot tell them apart, because Google answers a malformed token much the same
 * way either way.
 *
 * So this asks questions that have real answers. The subscription listing needs exactly the
 * permission a purchase check needs and is unaffected by the one-time-product migration, which
 * makes it the honest test of access. The rest establishes which purchase-verification endpoint
 * this app is actually served by — the legacy `purchases.products.get` or its v2 replacement —
 * because getting that wrong would only ever surface as a customer who paid and got nothing.
 *
 * Guarded by `BOOTSTRAP_TOKEN` and meant for setup, not for operations; the dashboard replaces it.
 */
export async function handleDiagnose(request: Request, env: Env): Promise<Response> {
  if (!env.BOOTSTRAP_TOKEN) {
    return apiError(404, 'Not available.', 'not_found', 'invalid_request_error');
  }
  if (request.headers.get('x-bootstrap-token') !== env.BOOTSTRAP_TOKEN) {
    return apiError(403, 'Wrong bootstrap token.', 'forbidden', 'invalid_request_error');
  }

  const packageName = env.PACKAGE_NAME ?? 'net.devemperor.dictate';

  // `?order=<id>` dumps what Google says about one order, verbatim. Added because the ledger
  // showed a paid amount with zero tax and zero revenue, and guessing whether that is a bug or
  // simply what a free tester purchase looks like is exactly the kind of question worth answering
  // with the raw response rather than an opinion.
  const orderId = new URL(request.url).searchParams.get('order');
  if (orderId) {
    const token = await playAccessToken(env).catch(() => null);
    if (!token) return json({ error: 'kein Zugriffstoken' }, 500);
    const response = await fetch(
      `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(packageName)}/orders/${encodeURIComponent(orderId)}`,
      { headers: { authorization: `Bearer ${token}` } },
    );
    const text = await response.text();
    return json({ status: response.status, order: safeParse(text) });
  }

  let token: string;
  try {
    token = await playAccessToken(env);
  } catch (error) {
    return json({
      package_name: packageName,
      service_account: { ok: false, detail: String(error) },
      hint: 'The key itself is being rejected. Re-upload GOOGLE_SERVICE_ACCOUNT (the whole JSON file).',
    });
  }

  const base = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(packageName)}`;
  const probeToken = 'diagnose-not-a-real-purchase-token';

  const candidates: Array<{ name: string; url: string; role: 'access' | 'purchase' }> = [
    // The catalog listing doubles as the access test: it needs the same permission a purchase
    // check needs, and a 200 also reveals which product IDs the console actually holds. Note the
    // URL — the resource is documented as `monetization.onetimeproducts` but the path carries no
    // `monetization/` segment, which is what the earlier 404s were about.
    { name: 'oneTimeProducts', url: `${base}/oneTimeProducts`, role: 'access' },
    // Which verification endpoint serves this app. `purchases.products.get` is not deprecated —
    // the "please migrate" answer from `inappproducts` concerns catalog management, not purchase
    // verification — but confirming it here costs one request and settles it.
    {
      name: 'purchases/products (in use)',
      url: `${base}/purchases/products/credits_notes/tokens/${probeToken}`,
      role: 'purchase',
    },
    {
      name: 'purchases/productsv2',
      url: `${base}/purchases/productsv2/tokens/${probeToken}`,
      role: 'purchase',
    },
  ];

  const checks: Array<Record<string, unknown>> = [];
  let productIds: string[] | null = null;

  for (const candidate of candidates) {
    const response = await fetch(candidate.url, {
      headers: { authorization: `Bearer ${token}` },
    });
    const text = await response.text();
    const check: Record<string, unknown> = {
      name: candidate.name,
      role: candidate.role,
      status: response.status,
    };

    if (text.trimStart().startsWith('<')) {
      // An HTML page rather than JSON means the API gateway did not recognise the path at all —
      // a wrong URL on our side, not a verdict about this app.
      check.detail = 'HTML error page — this API path does not exist';
    } else {
      try {
        const parsed = JSON.parse(text) as Record<string, any>;
        if (parsed.error) {
          check.detail = `${parsed.error.status ?? ''} ${parsed.error.message ?? ''}`.trim();
        } else {
          check.keys = Object.keys(parsed);
          const ids = (parsed.oneTimeProducts ?? parsed.inappproduct ?? [])
            .map((p: any) => p?.productId ?? p?.sku)
            .filter(Boolean);
          if (ids.length) {
            check.product_ids = ids;
            if (!productIds) productIds = ids;
          }
        }
      } catch {
        check.detail = text.slice(0, 200);
      }
    }
    checks.push(check);
  }

  // Which build each track actually serves. Needs a draft edit — Google exposes track state only
  // through one. It is never committed, so nothing about the app changes; unused edits expire.
  let tracks: unknown = undefined;
  try {
    const edit = await fetch(`${base}/edits`, {
      method: 'POST',
      headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
      body: '{}',
    });
    if (edit.ok) {
      const { id } = (await edit.json()) as { id: string };
      const list = await fetch(`${base}/edits/${id}/tracks`, {
        headers: { authorization: `Bearer ${token}` },
      });
      if (list.ok) {
        const body = (await list.json()) as { tracks?: Array<Record<string, any>> };
        tracks = (body.tracks ?? []).map((t) => ({
          track: t.track,
          releases: (t.releases ?? []).map((r: any) => ({
            status: r.status,
            name: r.name,
            versionCodes: r.versionCodes,
            userFraction: r.userFraction,
          })),
        }));
      }
      // Tidy up rather than leaving drafts lying around in the console.
      await fetch(`${base}/edits/${id}`, {
        method: 'DELETE',
        headers: { authorization: `Bearer ${token}` },
      }).catch(() => undefined);
    }
  } catch {
    tracks = { error: 'tracks unavailable' };
  }

  const accessCheck = checks.find((c) => c.role === 'access');
  const expected = Object.keys(PACKAGES);

  return json({
    package_name: packageName,
    service_account: { ok: true },
    play_access: {
      ok: accessCheck?.status === 200,
      hint:
        accessCheck?.status === 200
          ? undefined
          : 'The key works, but this account cannot reach the app. In the Play Console under ' +
            'Users and permissions, invite the service account by e-mail and grant it access to ' +
            'this app, including "View financial data" and "Manage orders and subscriptions".',
    },
    checks,
    tracks,
    products: productIds
      ? {
          expected,
          missing: expected.filter((id) => !productIds!.includes(id)),
          unexpected: productIds.filter((id) => !expected.includes(id)),
        }
      : undefined,
  });
}

function safeParse(text: string): unknown {
  try { return JSON.parse(text); } catch { return text.slice(0, 500); }
}
