const PACKAGE = "net.devemperor.dictate";

/** The plain listing URL. Use this where a canonical link is wanted — structured data, for instance. */
export const PLAY_URL = `https://play.google.com/store/apps/details?id=${PACKAGE}`;

/**
 * A Play link tagged so the Play Console can tell where the visitor came from.
 *
 * Play Console → Grow → Acquisition reports already splits organic search, browse and third-party
 * referrers, but a referrer only tells you the domain: every link from this site collapses into one row,
 * and a link from the README collapses into github.com. The `referrer` parameter is what separates them,
 * and it survives all the way to "buyers" — so it answers which button actually sells, not just which one
 * gets clicked.
 *
 * The value has to be a single URL-encoded query string, which is why it is built rather than written out:
 * one unencoded `&` and Play silently drops everything after it, leaving a campaign that reports nothing
 * and looks like it simply had no visitors.
 */
export function playLink(medium, { source = "dictatekeyboard.com", campaign = "site" } = {}) {
  const referrer = new URLSearchParams({
    utm_source: source,
    utm_medium: medium,
    utm_campaign: campaign,
  }).toString();
  return `${PLAY_URL}&referrer=${encodeURIComponent(referrer)}`;
}
