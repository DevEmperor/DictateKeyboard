import { layoutGraph } from './graph-layout';

/**
 * The dashboard, as one self-contained page.
 *
 * No build step and no CDN: a Worker serving a single string has nothing that can rot, and the
 * whole thing stays readable next to the code it reports on. It is scanned rather than read, so the
 * layout puts summary before detail and encodes state in shape as well as number.
 *
 * Outstanding credit leads deliberately. It is the number a prepaid shop most easily flatters
 * itself by omitting: minutes already paid for are work still owed, not money earned.
 *
 * Every figure carries a "?" that explains what it is and, where it matters, what it is not — hover
 * *and* focus, so a tap works on a phone where there is no hovering. Confirmations are drawn here
 * rather than handed to `confirm()`: a browser dialog cannot say which account it is about, cannot
 * be styled to distinguish "block this" from "save a note", and looks like a scam on a phone.
 *
 * The page script avoids template literals so the whole document can live in one here.
 */

// Rectangles, routed paths and label positions are worked out in `graph-layout.ts` before the page
// is ever assembled, so the browser only draws. `<` is escaped so a stray `</script>` inside any
// description can never end the block early.
const GRAPH_JSON = JSON.stringify(layoutGraph()).replace(/</g, '\\u003c');

export const DASHBOARD_HTML = `<!doctype html>
<html lang="de">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="robots" content="noindex, nofollow">
<meta name="theme-color" content="#30B7E6">
<title>Dictate Cloud — Betrieb</title>
<!-- The real Dictate launcher icon (img/Icon_512x512_2_round.png), resized to 128 px and
     palette-reduced to 128 colours: flat shapes lose nothing to that and it costs 2.9 KB
     inline instead of 13. Inlined so the page stays self-contained. -->
<link rel="icon" type="image/png" href="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAIAAAACACAMAAAD04JH5AAABgFBMVEVPV20mJSsvt+YCAgMAAAAUFRlITWExd5UywvM1OkcbHCIvMTs7QlEhHiNVVVUsptFCSVoph6pPVmxPV20VJC1OVmxOV20kGh5OWG5/f38qXHESRlg/P39QVm4POUgXWG8snMQbaYUec5EqlbtPVm1VVaoNMT1RYHgZYnwusN4UTWEAAH8AAP8/Pz8ubIdMTGZBZYAuZX0hf6BBPk1Ie5pBgaEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACqT3R5AAAAgHRSTlP+////AP///////////wP///9Nyv9xrv8vAv//BBP///////+OA///////AgEE/wr///////8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAC42RWgAAAY1SURBVHja7ZsLd6MqEIAhRoOvYBLzat7NbvrYbfd17///axdFDSBCRGn2ntM5p21irPNlGIcBZ8CwLuM1+bWbzhcz0IvMFvPpjlxyPZYoA3X15Gc1X4CeZTFfFRdXA2RnTHvXXjBMJQigpn86A9ZkNq0RcABk7FcW1ecIq1yNHGA8/PEFWJcvPzgjMABb61+/MsJWBrAdTsEHyZQhAFf7z8GHyfw6CiXAm06/11J0BG88wFanf9RadARbFmCsGf/JwEAmGj8YXwHWw5Xy5CMyAUBH5UVXNB7kAOOd8v6bGOknBEobzHbjEmA8VMafnwNj+amMSPkggEz/qn8HuMUNVhkBAVh/UwdAbA6A1SHx2zoD+Kq5AzqMgGYMyJ3wlQB836oNMFJ42QAfj3ig8FF1NJhtvw+BzgAqgNHedRx3PzIFyEwA1sOFIQC+OIVcsCHAYrgGwx0wA8CuU4mLzQDAjvjA3BAgdRhJDQHmBGBhBIBODicnZASwGALdCDRZIOABAjMLEPVTM4CzI8jZDGAK5kYA6LcI8BsZAczBwgxgJAKMzAAWYHZfgBvycLsA4BPgE+AT4BPgE+D/BoCQDgAhqwCjw0QNgJ9PFgHQM9E0oQtfEYAuobNUeW/RAlkiuKeaRABcETpHewBZKu7Slxde/4WaaF+x2BmC18rY4hhMrnZJLfpArnWT60IbVn9x7DF7fUD2huDFZcaYIdiwi4WzRSdEB6dyw8HgVKwO3RNzk/CrtN7jAHX+R0qA8OPmctk84uLtmfGGbgCRfxX4a1AzQaUkC3tV6MP5AOy5UPwLMpeKblTvQ1aWD0iyKK7v3CGqnw0C5ODDkruYf4N6D/ISCwDHYjVc2xN02dGpAGLhcl5b/TULlLPA5XzdG0IIF7fEQThZsMANBFAHUNzs2Wg/YqKauACelHfkQTy3DgA17qcHYINgun993Vy3Sp5rp0oAonYGkAAM0DF1JOJKPFMCAFsCxA+yTOfk1vQfsCQnagvg1U+HkTQlwyfOCoFU/WAQxfULei0BIG7YJT2fNrkd3D1Jk+R7VFh2vbYA8ahpKxaVsx9qOAONlj0AQL95r7gAeGk6wY9NAXzGK+N3pAR4bfz4fcn4nG8MAOGk0cTXZET2xCaGPQE0ESgB0IS/6zoB+Mf2AOcw7hFA/uCIzRFrj4sSv1cA8G8jwF4GMEo8P+gC8MQBROTtPxI1R9kUmMn7n44AkGZlUfFfYURePmDpnLCvRwEcwTAMIQWocjvjQFTNiz6WzAmyY6njEgniGPYRipmgDM83PMRF59QNiHoifQMoQhKXHFJpJOgEAEcaAhIaXA1BN4D4Xa3/UOlvJOgGQFI0FQDJTl2OoH8AuIwaZ9/BniaHPEMngCihEtK3+es/Tw0E5e1HVackfPABwARACGPFuycsrdgIHPqV3Zwji0BRFCWRBQDgSzLwSWH7IHAqgIzACkBYCwjFii0noH9TvyCAsQULiKkq3ZsoCOifoASIrQDAJbdv8OrwAI7rBGFI5zNLAFxA2DvNAN0skPAAXuUDeUCgq6Fs9mOXiCUALO5DMRJ0DUSMhCP8MsCSdSITjerxuEcA4t+BVDsbDgOLFsjGwbkzAIQagMA6ANQDxHYB4L0tICdozgksAECFD7ofMAQSAkU+0nJpVkVBzS6ryygOghTSrATSlCBJPK/lyihqCwCCajbO9JcAuXpa42sbAATM978LACgywiDNANISINEBzHoDANApBwCG8ApQVFnLAWZ8IVM3AABL/RVAkmgAFnwp1xXgqTr25ItHmiWk9id5AMzzc+glXm0f3ONLuaZSgDC6Slg70iyVDdLcHZgHNsVlRIApX85nHIgqcbl5OJ+JlA9NdnxBY3cAqFua8gALoaSzBwCo088BZCWdO/XzgvYSqPVDoaiVL+uFPREo9EOhrJcvbPZ7IYCuQr8vFDYLpd39AMBm/VAs7RaK25O+CJo+SGrF7UJ5f18EN+gvyvtrDQ49+YFsm5KfUMoGh1qLhxeFPUgevzmJ+Im4avHQNrnYEabJRdvmY0XYNh9to5MF4Rqdbmj16luEVq8bmt361v/2t7X73b/h8f4tn/dver1/2+/9G5//gtbv+ze/f3j7/38kb5eXkeMLDAAAAABJRU5ErkJggg==">
<link rel="apple-touch-icon" href="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAIAAAACACAMAAAD04JH5AAABgFBMVEVPV20mJSsvt+YCAgMAAAAUFRlITWExd5UywvM1OkcbHCIvMTs7QlEhHiNVVVUsptFCSVoph6pPVmxPV20VJC1OVmxOV20kGh5OWG5/f38qXHESRlg/P39QVm4POUgXWG8snMQbaYUec5EqlbtPVm1VVaoNMT1RYHgZYnwusN4UTWEAAH8AAP8/Pz8ubIdMTGZBZYAuZX0hf6BBPk1Ie5pBgaEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACqT3R5AAAAgHRSTlP+////AP///////////wP///9Nyv9xrv8vAv//BBP///////+OA///////AgEE/wr///////8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAC42RWgAAAY1SURBVHja7ZsLd6MqEIAhRoOvYBLzat7NbvrYbfd17///axdFDSBCRGn2ntM5p21irPNlGIcBZ8CwLuM1+bWbzhcz0IvMFvPpjlxyPZYoA3X15Gc1X4CeZTFfFRdXA2RnTHvXXjBMJQigpn86A9ZkNq0RcABk7FcW1ecIq1yNHGA8/PEFWJcvPzgjMABb61+/MsJWBrAdTsEHyZQhAFf7z8GHyfw6CiXAm06/11J0BG88wFanf9RadARbFmCsGf/JwEAmGj8YXwHWw5Xy5CMyAUBH5UVXNB7kAOOd8v6bGOknBEobzHbjEmA8VMafnwNj+amMSPkggEz/qn8HuMUNVhkBAVh/UwdAbA6A1SHx2zoD+Kq5AzqMgGYMyJ3wlQB836oNMFJ42QAfj3ig8FF1NJhtvw+BzgAqgNHedRx3PzIFyEwA1sOFIQC+OIVcsCHAYrgGwx0wA8CuU4mLzQDAjvjA3BAgdRhJDQHmBGBhBIBODicnZASwGALdCDRZIOABAjMLEPVTM4CzI8jZDGAK5kYA6LcI8BsZAczBwgxgJAKMzAAWYHZfgBvycLsA4BPgE+AT4BPgE+D/BoCQDgAhqwCjw0QNgJ9PFgHQM9E0oQtfEYAuobNUeW/RAlkiuKeaRABcETpHewBZKu7Slxde/4WaaF+x2BmC18rY4hhMrnZJLfpArnWT60IbVn9x7DF7fUD2huDFZcaYIdiwi4WzRSdEB6dyw8HgVKwO3RNzk/CrtN7jAHX+R0qA8OPmctk84uLtmfGGbgCRfxX4a1AzQaUkC3tV6MP5AOy5UPwLMpeKblTvQ1aWD0iyKK7v3CGqnw0C5ODDkruYf4N6D/ISCwDHYjVc2xN02dGpAGLhcl5b/TULlLPA5XzdG0IIF7fEQThZsMANBFAHUNzs2Wg/YqKauACelHfkQTy3DgA17qcHYINgun993Vy3Sp5rp0oAonYGkAAM0DF1JOJKPFMCAFsCxA+yTOfk1vQfsCQnagvg1U+HkTQlwyfOCoFU/WAQxfULei0BIG7YJT2fNrkd3D1Jk+R7VFh2vbYA8ahpKxaVsx9qOAONlj0AQL95r7gAeGk6wY9NAXzGK+N3pAR4bfz4fcn4nG8MAOGk0cTXZET2xCaGPQE0ESgB0IS/6zoB+Mf2AOcw7hFA/uCIzRFrj4sSv1cA8G8jwF4GMEo8P+gC8MQBROTtPxI1R9kUmMn7n44AkGZlUfFfYURePmDpnLCvRwEcwTAMIQWocjvjQFTNiz6WzAmyY6njEgniGPYRipmgDM83PMRF59QNiHoifQMoQhKXHFJpJOgEAEcaAhIaXA1BN4D4Xa3/UOlvJOgGQFI0FQDJTl2OoH8AuIwaZ9/BniaHPEMngCihEtK3+es/Tw0E5e1HVackfPABwARACGPFuycsrdgIHPqV3Zwji0BRFCWRBQDgSzLwSWH7IHAqgIzACkBYCwjFii0noH9TvyCAsQULiKkq3ZsoCOifoASIrQDAJbdv8OrwAI7rBGFI5zNLAFxA2DvNAN0skPAAXuUDeUCgq6Fs9mOXiCUALO5DMRJ0DUSMhCP8MsCSdSITjerxuEcA4t+BVDsbDgOLFsjGwbkzAIQagMA6ANQDxHYB4L0tICdozgksAECFD7ofMAQSAkU+0nJpVkVBzS6ryygOghTSrATSlCBJPK/lyihqCwCCajbO9JcAuXpa42sbAATM978LACgywiDNANISINEBzHoDANApBwCG8ApQVFnLAWZ8IVM3AABL/RVAkmgAFnwp1xXgqTr25ItHmiWk9id5AMzzc+glXm0f3ONLuaZSgDC6Slg70iyVDdLcHZgHNsVlRIApX85nHIgqcbl5OJ+JlA9NdnxBY3cAqFua8gALoaSzBwCo088BZCWdO/XzgvYSqPVDoaiVL+uFPREo9EOhrJcvbPZ7IYCuQr8vFDYLpd39AMBm/VAs7RaK25O+CJo+SGrF7UJ5f18EN+gvyvtrDQ49+YFsm5KfUMoGh1qLhxeFPUgevzmJ+Im4avHQNrnYEabJRdvmY0XYNh9to5MF4Rqdbmj16luEVq8bmt361v/2t7X73b/h8f4tn/dver1/2+/9G5//gtbv+ze/f3j7/38kb5eXkeMLDAAAAABJRU5ErkJggg==">
<style>
  /*
   * One palette, dark, deliberately — not a theme that half-serves two.
   *
   * A monitoring page is looked at in the evening and in the middle of the night far more often
   * than at a sunlit desk, and committing to one look buys a coherence that two half-tuned
   * palettes never reach. The greys carry a slight blue cast towards the accent so they read as
   * chosen rather than inherited.
   *
   * State colours are kept clear of the accent. If "good" were the same blue as the brand,
   * nothing could be said with colour any more.
   */
  :root {
    color-scheme: dark;
    --accent: #30B7E6;            /* Dictate light blue — the app's own default accent */
    --accent-ink: #04121A;        /* text placed ON the accent */
    --accent-soft: rgba(48, 183, 230, .14);
    --bg: #0B0F14;
    /*
     * The panels let the fog through rather than sitting on top of it.
     *
     * Deliberately plain alpha and no backdrop-filter. Frosted glass exists to blur detail behind a
     * surface, and there is none here: the background is a 200-pixel image stretched tenfold, which
     * is already softer than any blur would make it. A filter would look the same and cost a blur
     * pass per panel per frame — fifteen panels against a background that repaints sixteen times a
     * second is two hundred and forty of them a second, which is the trap this whole page has twice
     * fallen into.
     */
    --surface: rgba(18, 24, 32, .56);
    /* Where a surface must hide what is behind it: dialogs, and the boxes and labels of the network
       diagram, where a line showing through a label is worse than no transparency at all. */
    --surface-solid: #121820;
    /*
     * The calm ground, for the panels that carry tables.
     *
     * Letting the fog through is right for a panel and wrong for forty numbers in rows: a column of
     * figures wants a contrast that does not depend on where a cloud happens to be. So this sits
     * behind the rows themselves rather than under the whole panel — everything keeps the glass,
     * and only the densest thing on the page gets a quieter ground under it.
     */
    --surface-dense: rgba(13, 18, 25, .55);
    /* A lightening rather than a colour, so it holds up over whatever the fog is doing behind it. */
    --surface-2: rgba(255, 255, 255, .055);
    --line: rgba(255, 255, 255, .13);
    --text: #E6EDF3;
    --muted: #7D8B9A;
    --ok: #3FB950;
    --warn: #D29922;
    --crit: #F85149;
    --radius: 14px;
    --pad: clamp(12px, 3vw, 22px);
    --z-client: #30B7E6;
    --z-cf: #F5A24A;
    --z-google: #5EC87C;
    /* Nur noch Dekoration: der Nebel im Hintergrund. Es gibt keine Zone mehr, die diese Farbe trägt. */
    --z-violet: #A99AF0;
    --z-ext: #8FA0B4;
  }
  * { box-sizing: border-box; }
  html { -webkit-text-size-adjust: 100%; }
  body {
    margin: 0; background: var(--bg); color: var(--text);
    font: 15px/1.55 ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    overflow-wrap: break-word;
  }

  /*
   * The sky behind the numbers — a slow flow of colour, drawn at postage-stamp size.
   *
   * Two attempts at this in CSS pegged a GPU, and the reason both times was the same one: a
   * full-screen layer means the compositor handles millions of pixels, and a 4K window is eight
   * million of them. Whether the layer scaled, translated or merely tiled a gradient across itself
   * only decided how often that happened.
   *
   * So the field is not a full-screen layer at all. It is a canvas of about 160 by 100 pixels —
   * fourteen thousand, half a percent of that window — on which five soft lights are drawn
   * additively, and which the browser then stretches over the whole viewport. The stretch is one
   * textured quad, the cheapest thing a GPU does, and the bilinear filtering on a 24-fold upscale
   * is what gives the soft edges: no blur filter, and no banding either, because there is not
   * enough resolution left to band.
   *
   * The lights move on sine pairs whose periods share no factor, so the picture never returns to
   * an earlier state — that is where the flow comes from, rather than from a keyframe loop.
   *
   * Redrawn about twelve times a second, which at this size is a few thousand pixel writes, and not
   * at all while the tab is in the background. The cards stay opaque, so no column of figures gets
   * harder to read.
   */
  .sky {
    position: fixed; inset: 0; z-index: -1; pointer-events: none;
    width: 100%; height: 100%; display: block;
  }
  /*
   * The header carries the same sky rather than sitting on it as a slab.
   *
   * It cannot simply be made translucent: it is sticky, so what would show through is the content
   * scrolling underneath it, not the background. It gets its own canvas instead, holding a copy of
   * the very same image, sized to the viewport and clipped to the bar. Because the header padding
   * box starts at the viewport top-left corner, the copy lands exactly where the field behind the
   * page would have been.
   */
  #skyBar {
    position: absolute; left: 0; top: 0; z-index: 0;
    width: 100vw; height: 100vh; pointer-events: none;
  }

  /* The base is --bg rather than --surface: with the sky painted across it the bar is a piece of
     the background, and the cards are the only thing that lifts off it. overflow: hidden is what
     crops the copy of the field down to the height of the bar. */
  header {
    position: sticky; top: 0; z-index: 20;
    background: var(--bg); border-bottom: 1px solid var(--line);
    padding-top: env(safe-area-inset-top);
    overflow: hidden;
  }
  /* The canvas is a child too, and would otherwise be lifted along with the labels — it carries its
     own z-index above, and an id outranks this. */
  header > * { position: relative; z-index: 1; }
  /* Wraps, and every item may shrink. Without both, the row simply grew past the window: flex
     items refuse to go below their own content width by default, so on a phone the bar pushed the
     whole document 45px wider than the screen and every tab under it sat shifted and clipped. */
  .bar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 12px; padding: 10px var(--pad); }
  .bar > * { min-width: 0; }
  /*
   * One row, one line.
   *
   * Centring boxes is not the same as aligning what is written in them. The bell and the refresh
   * button were built for different places and came out 32px and 39px tall, so even perfectly
   * centred they read as two sizes rather than one row; here they are given a common height and
   * their vertical padding is replaced by it.
   *
   * The address was a second, separate problem: it wears .sub, whose 4px top margin is right for
   * its real job — a line of detail under a value in a card — and simply pushed it down here.
   */
  .bar .sub { margin-top: 0; }
  .bar .bell, .bar button.btn { min-height: 34px; display: inline-flex; align-items: center; justify-content: center; }
  .bar .bell { padding: 0 13px; }
  .bar button.btn { padding: 0 15px; }
  /* Loading, shown on the button that asked for it rather than beside it.
     A separate bar needed a strip of the header reserved for it at all times — visible for a second
     now and then, blank the rest of the day. The accent sweeping through the label says the same
     thing and costs no space: it is the one control the eye is already on after a tab change. */
  #refresh.working { border-color: color-mix(in srgb, var(--accent) 45%, var(--line)); }
  #refresh.working span {
    /* Not currentColor: the element's own colour is transparent here, so currentColor inside the
       gradient would resolve to transparent and the label would vanish between sweeps. */
    background: linear-gradient(100deg, var(--text) 0 38%, var(--accent) 50%, var(--text) 62% 100%);
    background-size: 320% 100%;
    -webkit-background-clip: text; background-clip: text;
    color: transparent;
    animation: sweep 1s linear infinite;
  }
  @keyframes sweep { from { background-position: 160% 0; } to { background-position: -160% 0; } }
  /* No sweep where motion is unwelcome — the label simply turns accent-coloured while it works.
     The sky stops too, and stays as a still image: the point of it is the depth, not the drift. */
  @media (prefers-reduced-motion: reduce) {
    #refresh.working span { animation: none; background: none; color: var(--accent); }
    /* Everything that arrives, arrives already there; the traffic dots stand still on their routes;
       the sky is drawn once and left. Nothing here is load-bearing — each is a way of saying
       something the page also says in words or in a number. */
    .grid > .card, .stack > .panel, #taxYears > .card, .plans > .card,
    .spark path.line, .spark path.area, .spark circle, .gedge path.flow { animation: none; }
    .spark path.line { stroke-dashoffset: 0; }
  }
  .brand { display: flex; align-items: center; gap: 9px; font-weight: 680; letter-spacing: -0.015em; white-space: nowrap; }
  .dot { width: 11px; height: 11px; border-radius: 50%; background: var(--accent); flex: none; }
  /* The signed-in address: useful, but the longest unbreakable thing in the row. */
  #who { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  nav { display: flex; gap: 2px; padding: 0 var(--pad) 8px; overflow-x: auto; scrollbar-width: none; overscroll-behavior-x: contain; }
  nav::-webkit-scrollbar { display: none; }
  nav button { background: transparent; border: 0; color: var(--muted); padding: 6px 13px; border-radius: 999px; font-weight: 600; font-size: 14px; white-space: nowrap; cursor: pointer; }
  nav button[aria-current="true"] { background: var(--accent-soft); color: var(--accent); }
  /* One label, two lengths. Which one shows is a question of room, so CSS answers it — the
     alternative is JavaScript that has to be told every time the window changes. */
  .wide-only { display: inline; }
  .narrow-only { display: none; }

  main { padding: var(--pad); max-width: 1360px; margin: 0 auto; }
  section[hidden] { display: none; }
  .stack { display: grid; gap: 18px; }
  /* Without this a grid item grows to the widest thing it contains, and a scroll wrapper does not
     stop it: a nine-column table therefore pushed its whole panel — heading, search box and all —
     past the window instead of scrolling inside its own box. Only visible on a narrow screen,
     because on a desk the tables happen to fit. */
  .stack > *, .dlg-body > * { min-width: 0; }
  .grid { display: grid; gap: 12px; grid-template-columns: repeat(auto-fit, minmax(min(100%, 230px), 1fr)); }
  /*
   * Lifted off the fog rather than drawn on it.
   *
   * Two shadows doing two jobs: the outer one puts the panel in front of the background, which a
   * hairline border alone cannot do once that background has depth of its own; the inner top line
   * is a lit edge, and it is what makes a translucent surface read as glass instead of as something
   * that has faded.
   */
  .card, .panel, .zgroup, .gwrap {
    box-shadow: 0 10px 30px rgba(0, 0, 0, .45), inset 0 1px 0 rgba(255, 255, 255, .09);
  }
  .card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 14px 16px;
    /* Beides zusammen ist der Grund, warum ein Hinweistext nicht mehr hinter der Nachbarkachel
       verschwindet. Die Einblend-Animation weiter unten läuft mit fill-mode: both und lässt
       deshalb **dauerhaft** einen eigenen Stapelkontext je Kachel zurück; darin nützt das z-index
       des Hinweises nichts, weil die nächste Kachel im Dokument als Ganzes darüber liegt. Geordnet
       werden muss also auf der Ebene der Kacheln, und das geht nur, wenn sie positioniert sind. */
    position: relative; }
  /* Die Kachel, auf der die Maus steht, kommt nach vorn — samt ihrem Hinweisfeld. focus-within
     deckt denselben Fall über die Tastatur ab, wo das Fragezeichen den Fokus bekommt.
     (Keine Backticks in dieser Datei: sie ist ein einziges Template-Literal, und einer davon
     beendet es. Derselbe Fallstrick wie ein Backslash — siehe modelCell.) */
  .card:hover, .card:focus-within, .panel:hover, .panel:focus-within { z-index: 40; }
  /* Keeps its accent ring, and gains the depth with it. */
  .card.lead { border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent) inset, 0 10px 30px rgba(0, 0, 0, .45); }
  @media (min-width: 780px) { .card.lead { grid-column: span 2; } }
  .label { font-size: 11.5px; color: var(--muted); text-transform: uppercase; letter-spacing: 0.07em; font-weight: 640; display: flex; align-items: center; gap: 6px; }
  .value { font-size: 27px; font-weight: 680; font-variant-numeric: tabular-nums; margin-top: 5px; letter-spacing: -0.025em; line-height: 1.15; }
  .sub { font-size: 12.5px; color: var(--muted); font-variant-numeric: tabular-nums; margin-top: 4px; }

  .hint { display: inline-grid; place-items: center; width: 16px; height: 16px; flex: none; border-radius: 50%; border: 1px solid var(--line); color: var(--muted); cursor: help; position: relative; background: var(--surface); }
  .hint::before { content: "?"; font-size: 11px; font-weight: 700; line-height: 1; }
  .hint:hover, .hint:focus { outline: none; border-color: var(--accent); color: var(--accent); }
  .hint::after {
    content: attr(data-tip); position: absolute; left: 50%; top: calc(100% + 8px); transform: translateX(-50%);
    width: max-content; max-width: min(78vw, 320px); background: var(--text); color: var(--bg);
    padding: 9px 11px; border-radius: 9px; font-size: 12.5px; font-weight: 450; line-height: 1.45;
    letter-spacing: 0; text-transform: none; opacity: 0; pointer-events: none; transition: opacity .12s; z-index: 30;
  }
  .hint:hover::after, .hint:focus::after { opacity: 1; }
  /* Hidden by opacity, but still laid out — and 320px of it hanging out of a card is what made the
     page scroll sideways on a phone: every question mark pushed its container past the window,
     which is why
     the whole layout looked shifted. Where there is no pointer to hover with, the tooltip is not
     merely useless but harmful, so it is removed outright and a tap opens the text in a dialog. */
  @media (hover: none), (max-width: 620px) {
    .hint::after { display: none; }
  }

  /* Untereinander statt in einer Zeile: nebeneinander brachen sie bei schmalen Karten mitten im
     Satz um, und welche Pille zu welcher Aussage gehörte, war dann Ratesache. */
  .pillcol { display: flex; flex-direction: column; align-items: flex-start; gap: 5px; margin-top: 9px; }
  .pill { display: inline-flex; align-items: center; gap: 5px; padding: 2px 9px; border-radius: 999px; font-size: 12px; font-weight: 650; white-space: nowrap; }
  .pill.ok { background: color-mix(in srgb, var(--ok) 15%, transparent); color: var(--ok); }
  .pill.warn { background: color-mix(in srgb, var(--warn) 18%, transparent); color: var(--warn); }
  .pill.crit { background: color-mix(in srgb, var(--crit) 15%, transparent); color: var(--crit); }
  .pill.info { background: var(--accent-soft); color: var(--accent); }

  .bar-track { height: 7px; border-radius: 999px; background: var(--surface-2); overflow: hidden; margin-top: 11px; }
  .bar-track > i { display: block; height: 100%; background: var(--ok); border-radius: 999px; }
  .bar-track > i.warn { background: var(--warn); }
  .bar-track > i.crit { background: var(--crit); }

  h2 { font-size: 12.5px; text-transform: uppercase; letter-spacing: 0.08em; color: var(--muted); margin: 0 0 10px; display: flex; align-items: center; gap: 7px; }
  h3 { font-size: 15px; margin: 0 0 10px; font-weight: 660; }
  /* Zwei Pakete nebeneinander, sobald zwei nebeneinander passen. Untereinander stand jede Karte
     über die volle Breite, und die Leiter rechts trieb Bezeichner und Betrag so weit auseinander,
     dass man mit den Augen die Zeile suchen musste. */
  .plans { display: grid; gap: 12px; grid-template-columns: 1fr; }
  @media (min-width: 1080px) { .plans { grid-template-columns: 1fr 1fr; } }
  .panel { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: var(--pad); position: relative; }
  .scroll { overflow-x: auto; -webkit-overflow-scrolling: touch; }
  /* The rows get their own quieter ground inside the glass, and only they: forty numbers in columns
     are the one thing on this page whose contrast must not depend on where a cloud happens to be. */
  table { width: 100%; border-collapse: collapse; font-size: 13.5px; background: var(--surface-dense); border-radius: 10px; }
  th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid var(--line); white-space: nowrap; }
  th { font-size: 11px; text-transform: uppercase; letter-spacing: 0.06em; color: var(--muted); font-weight: 650; }
  td.num, th.num { text-align: right; font-variant-numeric: tabular-nums; }
  td.wrap { white-space: normal; min-width: 190px; }
  tbody tr[data-id]:hover { background: var(--surface-2); cursor: pointer; }
  tbody tr:last-child td { border-bottom: none; }
  code, .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12.5px; }

  input, select, textarea, button { font: inherit; color: inherit; }
  input, textarea, select { background: var(--bg); border: 1px solid var(--line); border-radius: 9px; padding: 8px 11px; width: 100%; min-width: 0; }
  input:focus, select:focus, textarea:focus { outline: 2px solid var(--accent); outline-offset: -1px; border-color: transparent; }
  button.btn { background: var(--accent); color: var(--accent-ink); border: 0; border-radius: 9px; padding: 9px 15px; font-weight: 650; cursor: pointer; white-space: normal; text-align: center; line-height: 1.25; }
  button.btn:hover { filter: brightness(1.06); }
  button.btn.ghost { background: transparent; color: var(--text); border: 1px solid var(--line); }
  button.btn.danger { background: var(--crit); color: #fff; }
  button.btn:disabled { opacity: .5; cursor: not-allowed; }
  button.btn:focus-visible, .hint:focus-visible, a:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
  .row { display: flex; gap: 9px; align-items: center; flex-wrap: wrap; }
  .row > .grow { flex: 1; min-width: 170px; }

  .chart { display: flex; align-items: flex-end; gap: 3px; height: 96px; }
  .chart > div { flex: 1; background: color-mix(in srgb, var(--accent) 45%, transparent); border-radius: 3px 3px 0 0; min-height: 2px; }
  .chart > div:last-child { background: var(--accent); }

  dialog { border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface-solid); color: var(--text); padding: 0; max-width: min(940px, 96vw); width: 100%; max-height: 92vh; }
  dialog::backdrop { background: rgba(4, 12, 18, .7); }
  .dlg-head { display: flex; align-items: center; gap: 12px; padding: 13px var(--pad); border-bottom: 1px solid var(--line); position: sticky; top: 0; background: var(--surface-solid); z-index: 2; }
  .dlg-body { padding: var(--pad); display: grid; gap: 18px; overflow: auto; max-height: calc(92vh - 62px); }
  /* The account view is the one screen that has to hold everything known about a wallet at once.
     At the shared dialog width its tables each grew a horizontal scrollbar of their own, and a
     column you have to go looking for is a column you stop reading. Wider, with headings allowed
     to wrap and identifiers allowed to break, the whole thing fits without a bar; .scroll stays
     underneath as the fallback for a narrow screen. */
  #detail { max-width: min(1240px, 96vw); }
  #detail .dlg-body { overflow-x: hidden; }
  #detail th { white-space: normal; }
  #detail td.mono { white-space: normal; overflow-wrap: anywhere; }

  #modal { max-width: min(460px, 94vw); }
  #modal .dlg-body { gap: 12px; }
  #modal .m-title { font-size: 17px; font-weight: 680; }
  #modal .m-text { color: var(--muted); font-size: 14px; }
  #modal .m-code { font-family: ui-monospace, monospace; font-size: 19px; font-weight: 700; letter-spacing: .06em; background: var(--surface-2); border-radius: 10px; padding: 14px; text-align: center; user-select: all; }
  #modal .m-actions { display: flex; gap: 9px; justify-content: flex-end; flex-wrap: wrap; }

  /* Die Leiter füllt die Karte nicht mehr aus, sondern nur sich selbst: Bezeichner und Betrag
     stehen so dicht beieinander, dass eine Zeile in einem Blick zu lesen ist statt in zweien. */
  .ladder { width: auto; min-width: 240px; font-size: 13px; background: none; }
  .ladder td { border: 0; padding: 3px 0; }
  .ladder td:first-child { padding-right: 26px; }
  .kv { display: grid; grid-template-columns: max-content 1fr; gap: 5px 14px; font-size: 13.5px; }
  .kv dt { color: var(--muted); }
  .kv dd { margin: 0; }
  .muted { color: var(--muted); }
  /* Für den einen Satz je Karte, der sagt, ob die Zahl darüber zu glauben ist. Als Textfarbe und
     nicht als Pille: eine Pille zieht das Auge auf sich, und diese Sätze sollen gelesen werden,
     wenn man ohnehin auf die Zahl schaut. */
  .ok-text { color: var(--ok); }
  .warn-text { color: var(--warn); }
  .empty { color: var(--muted); font-size: 13.5px; padding: 14px 0; text-align: center; }
  .pager { display: flex; align-items: center; gap: 10px; justify-content: flex-end; margin-top: 12px; flex-wrap: wrap; }
  .pager .count { font-size: 12.5px; color: var(--muted); font-variant-numeric: tabular-nums; margin-right: auto; }

  /* Account actions: one column per concern, equal height, nothing spilling out. */
  .acts { display: grid; gap: 12px; grid-template-columns: repeat(auto-fit, minmax(min(100%, 265px), 1fr)); align-items: stretch; }
  .act { border: 1px solid var(--line); border-radius: 12px; padding: 13px; display: flex; flex-direction: column; gap: 10px; min-width: 0; }
  .act > .label { margin-bottom: 2px; }
  .act .spacer { flex: 1; }
  .act.danger-zone { border-color: color-mix(in srgb, var(--crit) 45%, var(--line)); background: color-mix(in srgb, var(--crit) 4%, transparent); }
  .act .row > * { min-width: 0; }
  /* The "changed" marker belongs at the end of its row, not between the words of the label. At the
     width of a settings card most of these labels run to two lines, and a pill dropped into the
     middle of them pushed the second line out under the badge. Lowercase and unspaced, because it
     is an annotation on the heading rather than a second heading. */
  .act .label { flex-wrap: wrap; }
  .act .label .chg { margin-left: auto; text-transform: none; letter-spacing: 0; font-weight: 600; font-size: 11px; padding: 1px 8px; }

  /* ---- network graph ---- */
  /*
   * The diagram floats on the fog instead of sitting in a dark box of its own.
   *
   * That is what the transparent svg is for: it used to paint --bg across the whole plate, which
   * made the one page with the most to look at the only one that had cut itself out of the design.
   * Everything drawn on it — zones, boxes, labels — carries its own backing now, so nothing relies
   * on the plate underneath being opaque.
   */
  .gwrap { position: relative; border: 1px solid var(--line); border-radius: var(--radius); overflow: hidden; background: var(--surface); }
  .gtools { position: absolute; top: 10px; right: 10px; display: flex; gap: 6px; z-index: 3; flex-wrap: wrap; justify-content: flex-end; }
  .gtools button { background: var(--surface-solid); border: 1px solid var(--line); color: var(--text); border-radius: 8px; padding: 6px 11px; font-size: 13px; font-weight: 620; cursor: pointer; box-shadow: 0 4px 14px rgba(0, 0, 0, .4); }
  .gtools button[aria-pressed="true"] { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); }
  #gsvg { display: block; width: 100%; height: min(72vh, 720px); touch-action: none; cursor: grab; background: rgba(11, 15, 20, .45); }
  #gsvg.dragging { cursor: grabbing; }
  /* A shade more presence than before, because the fog behind them now has some of its own. */
  .zone-bg { rx: 18; fill-opacity: .09; stroke-opacity: .42; stroke-width: 1.5; stroke-dasharray: 7 6; }
  .zone-label { font: 650 15px ui-sans-serif, system-ui, sans-serif; fill-opacity: .85; }
  .zone-sub { font: 400 12px ui-sans-serif, system-ui, sans-serif; fill: currentColor; opacity: .55; }
  /*
   * A box is a card, in SVG.
   *
   * The same three things that make the panels read as glass: a translucent ground, a border of
   * white rather than of grey, and light from above. The sheen is a gradient defined once in defs
   * and referenced by all twenty-six boxes rather than a filter on each — a drop shadow here would
   * be re-rasterised on every pan and zoom, which on this page is exactly the cost not to pay.
   */
  .gnode rect { rx: 11; fill: url(#gglass); stroke: rgba(255, 255, 255, .16); stroke-width: 1.2; }
  /* Both of these are rects inside .gnode and would otherwise inherit the box's own fill and
     border from the rule above — a CSS declaration beats a fill="…" attribute every time. The
     accent bar was painted over in surface grey, and the invisible tap target came out as an
     empty box sitting on the "?" it was meant to enlarge. */
  .gnode rect.bar { stroke: none; rx: 2; }
  .gask rect.hit { fill: transparent; stroke: none; }
  .gnode:hover rect, .gnode.sel rect { stroke: var(--accent); stroke-width: 2.5; }
  .gnode text.t { font: 650 13.5px ui-sans-serif, system-ui, sans-serif; fill: var(--text); }
  .gnode text.s { font: 400 11.5px ui-sans-serif, system-ui, sans-serif; fill: var(--muted); }
  .gnode { cursor: pointer; }
  .gedge path { fill: none; stroke-width: 1.8; opacity: .5; }
  .gedge.dim { opacity: .07; }
  .gedge.hot path { opacity: 1; stroke-width: 3; }
  /* Labels live above the nodes, so a chip that lands on a box is still readable. */
  .glabel text { font: 550 10.5px ui-sans-serif, system-ui, sans-serif; fill: var(--text); }
  /* Chips stay nearly opaque: they sit on the routes, and a line running through a word is worse
     than any amount of transparency is worth. */
  .glabel rect.lbl { fill: rgba(12, 17, 24, .93); stroke: rgba(255, 255, 255, .14); stroke-width: .8; rx: 5; }
  .glabel.dim { opacity: .12; }
  .glabel.hot rect.lbl { stroke: var(--accent); stroke-width: 1.6; }
  .glabel.hot text { fill: var(--text); }
  .legend { display: flex; gap: 14px; flex-wrap: wrap; padding: 12px var(--pad); border-top: 1px solid var(--line); font-size: 12.5px; color: var(--muted); }
  .legend span { display: inline-flex; align-items: center; gap: 6px; }
  .swatch { width: 22px; height: 3px; border-radius: 2px; }
  .ndetail { padding: var(--pad); border-top: 1px solid var(--line); }
  .gmode { display: flex; gap: 6px; }
  .gmode button { background: var(--surface); border: 1px solid var(--line); color: var(--muted); border-radius: 10px; padding: 8px 15px; font-size: 13.5px; font-weight: 620; cursor: pointer; box-shadow: 0 6px 18px rgba(0, 0, 0, .3), inset 0 1px 0 rgba(255, 255, 255, .07); }
  .gmode button[aria-pressed="true"] { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); }
  /* The "?" on a box. Drawn as SVG rather than an HTML overlay so it pans and zooms with the
     diagram instead of drifting away from the box it belongs to. */
  .gask circle { fill: rgba(255, 255, 255, .07); stroke: rgba(255, 255, 255, .16); stroke-width: 1; }
  .gask text { font: 700 11px ui-sans-serif, system-ui, sans-serif; fill: var(--muted); }
  .gnode:hover .gask circle, .gnode.sel .gask circle { stroke: var(--accent); }
  .gnode:hover .gask text, .gnode.sel .gask text { fill: var(--accent); }
  .gedge path { cursor: pointer; }
  .gedge.sel path { opacity: 1; stroke-width: 3.4; }
  .glabel { cursor: pointer; }
  .glabel.sel rect.lbl { stroke: var(--accent); stroke-width: 1.8; }
  /* Zoomed far out, every chip at once is a grey haze rather than information. They step aside and
     only the selected or hovered line still says what it carries. */
  .hush .glabel { opacity: 0; pointer-events: none; }
  .hush .glabel.sel, .hush .glabel.hot { opacity: 1; pointer-events: auto; }
  /* The list, which is what a phone gets first: the same graph, read top to bottom. */
  .zgroup { border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface); overflow: hidden; }
  .zgroup > header { display: flex; align-items: center; gap: 10px; padding: 13px var(--pad); border-bottom: 1px solid var(--line); }
  .zgroup > header .bar-i { width: 4px; height: 22px; border-radius: 2px; }
  .zgroup > header h3 { margin: 0; font-size: 15px; }
  .zgroup > header .sub { margin: 0; }
  .ncard { padding: 13px var(--pad); border-top: 1px solid var(--line); display: grid; gap: 8px; }
  .ncard:first-of-type { border-top: 0; }
  .ncard .row { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
  .ncard h4 { margin: 0; font-size: 14.5px; }
  .ncard .links { display: grid; gap: 4px; }
  .ncard .links button { display: flex; gap: 8px; align-items: baseline; width: 100%; text-align: left; background: var(--surface-2); border: 1px solid transparent; border-radius: 9px; padding: 7px 10px; color: var(--text); font: inherit; font-size: 12.5px; cursor: pointer; }
  .ncard .links button:hover { border-color: var(--accent); }
  .ncard .links .dir { color: var(--muted); font-variant-numeric: tabular-nums; }
  .ncard .links .what { color: var(--muted); }
  .btn.tiny { padding: 4px 10px; font-size: 12px; border-radius: 8px; }
  #explain { max-width: min(680px, 94vw); }
  #explain .dlg-body { display: block; font-size: 14.5px; line-height: 1.62; }
  #explain .dlg-body p { margin: 0 0 12px; }
  #explain .dlg-body p:last-child { margin-bottom: 0; }
  #explain .dlg-body code { background: var(--surface-2); border-radius: 5px; padding: 1px 5px; font-size: .92em; }
  #explain .xmeta { display: grid; gap: 8px; margin: 0 0 16px; padding: 0 0 14px; border-bottom: 1px solid var(--line); }
  .taglist { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
  .tag { font-size: 11.5px; padding: 3px 9px; border-radius: 999px; background: var(--surface-2); color: var(--muted); }
  .tag.key { background: var(--accent-soft); color: var(--accent); }
  .tag.guard { background: color-mix(in srgb, var(--ok) 15%, transparent); color: var(--ok); }

  /* ---- alerts ---- */
  /*
   * Severity is carried by a stripe *and* a worded pill, never by colour alone. Red and amber are
   * the two hues most often indistinguishable to a colour-blind reader, and this is the one list
   * on the page where getting the order wrong matters.
   */
  .alert { display: flex; gap: 12px; align-items: flex-start; padding: 13px 14px; border: 1px solid var(--line); border-left: 3px solid var(--muted); border-radius: 11px; background: var(--surface); }
  .alert.critical { border-left-color: var(--crit); background: color-mix(in srgb, var(--crit) 5%, var(--surface)); }
  .alert.notice { border-left-color: var(--warn); }
  .alert.done { opacity: .55; border-left-color: var(--line); }
  .alert .a-main { flex: 1; min-width: 0; }
  .alert .a-title { font-weight: 660; font-size: 14.5px; }
  .alert .a-detail { font-size: 13px; color: var(--muted); margin-top: 3px; overflow-wrap: anywhere; }
  .alert .a-meta { font-size: 11.5px; color: var(--muted); margin-top: 7px; display: flex; gap: 10px; flex-wrap: wrap; align-items: center; }
  .bell { position: relative; display: inline-flex; align-items: center; gap: 7px; background: transparent; border: 1px solid var(--line); border-radius: 999px; padding: 5px 12px; font-size: 13px; font-weight: 650; cursor: pointer; color: var(--muted); }
  .bell.has { border-color: var(--crit); color: var(--crit); background: color-mix(in srgb, var(--crit) 10%, transparent); }
  .bell.quiet { border-color: var(--line); color: var(--muted); }

  /* ---- test accounts ---- */
  /*
   * Deliberately muted rather than loud. These rows are not a problem to be noticed, they are
   * rows that must not be mistaken for income — a quiet, unmistakable label does that better
   * than a warning colour, which would read as "something is wrong here".
   */
  .pill.test { background: var(--surface-2); color: var(--muted); border: 1px dashed var(--line); }
  tr.is-test td:first-child { box-shadow: inset 2px 0 0 var(--muted); }

  /* ---- skeletons ---- */
  /*
   * Shown the instant a view is opened, before its data arrives. Most of what felt slow was not
   * waiting — it was waiting at an empty rectangle with nothing to say whether anything was
   * happening. A shape that matches the answer reads as "loading"; a blank one reads as "broken".
   */
  .sk { background: linear-gradient(90deg, var(--surface-2) 25%, var(--line) 50%, var(--surface-2) 75%); background-size: 260% 100%; border-radius: 7px; animation: shimmer 1.35s linear infinite; }
  @keyframes shimmer { to { background-position: -260% 0; } }
  .sk-line { height: 12px; margin: 9px 0; }
  .sk-card { height: 92px; }
  @media (prefers-reduced-motion: reduce) { .sk { animation: none; } }

  /* ---- sparkline ---- */
  .spark { display: block; width: 100%; height: 34px; margin-top: 10px; overflow: visible; }
  .spark path.area { fill: color-mix(in srgb, var(--accent) 16%, transparent); stroke: none; }
  .spark path.line { fill: none; stroke: var(--accent); stroke-width: 1.6; stroke-linejoin: round; }
  .spark circle { fill: var(--accent); }

  /*
   * Arriving.
   *
   * All one-shot: they run once when a view is rendered and then the element is static for as long
   * as it is on screen. Nothing here loops, which is what separates a page that settles from one
   * that fidgets.
   *
   * The line draws itself left to right, which is the direction it is read in, and the endpoint
   * lands last because on a series ending today that is the value being looked for. It works on any
   * series because the path carries pathLength="100": the dash arithmetic is then in percent and
   * does not depend on how long the line actually is.
   */
  @keyframes rise { from { opacity: 0; transform: translateY(7px); } to { opacity: 1; transform: none; } }
  @keyframes draw { to { stroke-dashoffset: 0; } }
  @keyframes appear { from { opacity: 0; } to { opacity: 1; } }
  @keyframes pop { from { opacity: 0; transform: scale(.2); } to { opacity: 1; transform: scale(1); } }

  .grid > .card, .stack > .panel, #taxYears > .card, .plans > .card { animation: rise .34s ease-out both; }
  /* A short ladder, then nothing: past the eighth card the delay would be longer than the animation
     and the last row would visibly lag behind the scroll. */
  .grid > .card:nth-child(2), .stack > .panel:nth-child(2), #taxYears > .card:nth-child(2), .plans > .card:nth-child(2) { animation-delay: .04s; }
  .grid > .card:nth-child(3), .stack > .panel:nth-child(3), #taxYears > .card:nth-child(3), .plans > .card:nth-child(3) { animation-delay: .08s; }
  .grid > .card:nth-child(4), .stack > .panel:nth-child(4), #taxYears > .card:nth-child(4), .plans > .card:nth-child(4) { animation-delay: .12s; }
  .grid > .card:nth-child(5), .stack > .panel:nth-child(5), .plans > .card:nth-child(5) { animation-delay: .16s; }
  .grid > .card:nth-child(6), .stack > .panel:nth-child(6) { animation-delay: .20s; }
  .grid > .card:nth-child(7) { animation-delay: .24s; }
  .grid > .card:nth-child(8) { animation-delay: .28s; }

  .spark path.line { stroke-dasharray: 100; stroke-dashoffset: 100; animation: draw .85s ease-out .1s forwards; }
  .spark path.area { animation: appear .5s ease-out .4s both; }
  .spark circle { transform-box: fill-box; transform-origin: center; animation: pop .28s ease-out .85s both; }

  /*
   * Traffic on the diagram: dots travelling each route in the direction the arrow points.
   *
   * A dash pattern of zero-length dashes with a round cap is a row of dots, and sliding the offset
   * by exactly one gap moves them along by one place — so the pattern lands back on itself and
   * there is no jump at the end of the cycle. The path is a second copy of the same d attribute,
   * which means the dots follow the routing that graph-layout.ts already worked out, curves and all.
   *
   * This is the one animation here that loops, and it is the only reason to watch the GPU on this
   * page: an SVG dash offset is repainted rather than composited. It runs on this tab alone —
   * section[hidden] is display: none, and a display: none subtree animates nothing.
   */
  .gedge path.flow {
    fill: none; stroke-width: 3.4; stroke-linecap: round;
    /* 0.1 rather than 0: a zero-length dash with a round cap is a dot in Chrome but not everywhere,
       and a tenth of a unit under a 3.4-wide round cap looks identical where it does work. */
    stroke-dasharray: 0.1 26; opacity: .85;
    animation: gflow 2.6s linear infinite;
  }
  @keyframes gflow { from { stroke-dashoffset: 0; } to { stroke-dashoffset: -26; } }
  /* A route the current filter has dimmed carries no traffic worth showing. */
  .gedge.dim path.flow { display: none; }

  .stale { font-size: 11px; color: var(--muted); }

  /* Phones.
     This page is read on one as often as at a desk — a budget alert arrives on a phone, and the
     account it names has to be reachable from there. The rules below are the ones that were
     actually broken, not a general shrink: the header row overflowed the window and dragged every
     tab under it out of alignment, the account dialog opened as a small box on a large backdrop,
     and the tab strip gave no sign that it scrolls. */
  @media (max-width: 620px) {
    .value { font-size: 23px; }
    .dlg-body { padding: 14px; }
    #gsvg { height: 62vh; }

    .wide-only { display: none; }
    .narrow-only { display: inline; }
    /* The address is the first thing to go: it is the longest item in the row and the least
       needed on a screen you had to sign in to reach anyway. */
    #who { display: none; }
    .bar { gap: 8px; padding: 9px var(--pad); }
    #refresh { padding: 8px 13px; font-size: 16px; line-height: 1; }

    /* Fingers, not a mouse: 40px is the smallest target that is not a lottery. */
    nav { gap: 4px; padding-bottom: 9px; }
    nav button { padding: 9px 14px; }
    /* Says "there is more to the right" without a scrollbar to say it with. */
    nav { -webkit-mask-image: linear-gradient(90deg, #000 88%, transparent); mask-image: linear-gradient(90deg, #000 88%, transparent); }

    /* Full screen, because a modal at 96vw on a phone is a window with a useless frame — and the
       account view is the one that has the most to say. #modal stays a small box: a confirmation
       that fills the screen reads as a page rather than a question. */
    #detail { max-width: 100vw; width: 100vw; max-height: 100dvh; height: 100dvh; border: 0; border-radius: 0; margin: 0; }
    #detail .dlg-body { max-height: calc(100dvh - 58px); padding-bottom: calc(14px + env(safe-area-inset-bottom)); }
    /* Same reasoning as the account view: a long read in a small box on a large backdrop is worse
       than no dialog at all. #modal stays small — a confirmation is not a text. */
    #explain { max-width: 100vw; width: 100vw; max-height: 100dvh; height: 100dvh; border-radius: 0; border: 0; }
    #explain .dlg-body { max-height: calc(100dvh - 58px); padding-bottom: calc(14px + env(safe-area-inset-bottom)); }
    .dlg-head { padding: 11px 14px; }

    /* Two buttons and an input in one row is a scrap heap at this width. */
    .row > .grow { flex-basis: 100%; }
    .pager { justify-content: space-between; }
  }
</style>
</head>
<body>

<!-- Decoration only, and marked as such: a tiny canvas stretched over the window, invisible to a
     screen reader and untouchable by the pointer. The .sky rules in the stylesheet say why it is
     drawn at this size, and what the two full-screen attempts before it cost. -->
<canvas class="sky" id="sky" aria-hidden="true"></canvas>

<header>
  <canvas id="skyBar" aria-hidden="true"></canvas>
  <div class="bar">
    <span class="brand"><span class="dot"></span>Dictate&nbsp;Cloud</span>
    <span id="killPill"></span>
    <span style="flex:1"></span>
    <button class="bell quiet" id="bell" title="Offene Warnungen"><span class="wide-only">— Warnungen</span><span class="narrow-only">—</span></button>
    <span class="sub mono" id="who"></span>
    <button class="btn ghost" id="refresh" title="Aktualisieren" aria-label="Aktualisieren"><span class="wide-only">Aktualisieren</span><span class="narrow-only" aria-hidden="true">↻</span></button>
  </div>
  <nav id="nav">
    <button data-view="overview" aria-current="true">Übersicht</button>
    <button data-view="alerts" aria-current="false">Warnungen</button>
    <button data-view="stats" aria-current="false">Statistik</button>
    <button data-view="plans" aria-current="false">Pläne</button>
    <button data-view="tax" aria-current="false">Steuer</button>
    <button data-view="accounts" aria-current="false">Konten</button>
    <button data-view="traffic" aria-current="false">Verkehr</button>
    <button data-view="audit" aria-current="false">Protokoll</button>
    <button data-view="network" aria-current="false">Netzwerk</button>
    <button data-view="ops" aria-current="false">Betrieb</button>
  </nav>
</header>

<main>
  <section id="view-overview" class="stack">
    <div class="grid" id="stats"></div>
    <div class="panel">
      <h2>Anfragen der letzten 30 Tage
        <span class="hint" tabindex="0" data-tip="Eine Säule je Tag, gezählt werden alle abgerechneten Anfragen — Diktat und Umformulierung zusammen. Die letzte Säule ist heute und daher noch unvollständig."></span>
      </h2>
      <div class="chart" id="chart"></div>
      <div class="sub" id="chartNote"></div>
    </div>
    <div class="panel"><h2>Verkaufte Pakete</h2><div class="scroll" id="packs"></div></div>
  </section>

  <section id="view-alerts" class="stack" hidden>
    <div class="panel">
      <h2>Offene Warnungen
        <span class="hint" tabindex="0" data-tip="Kritische Meldungen gehen sofort als E-Mail raus, alles andere sammelt der Tagesbericht. Dieselbe Meldung wird sechs Stunden lang nicht wiederholt — eine Lage, die stundenlang anhält, ist eine Warnung und nicht neunzig."></span>
      </h2>
      <div class="row" style="margin-bottom:12px">
        <button class="btn ghost" id="ackAll">Alle als erledigt markieren</button>
      </div>
      <div class="stack" id="alertList" style="gap:10px"></div>
    </div>
    <div class="panel">
      <h2>Verlauf
        <span class="hint" tabindex="0" data-tip="Erledigte Warnungen werden nicht gelöscht, nur abgehakt. Was wann ausgelöst hat, ist genau dann interessant, wenn man rückwärts nachvollziehen will, wie eine Störung entstanden ist."></span>
      </h2>
      <div class="stack" id="alertHistory" style="gap:10px"></div>
      <div class="pager" id="alertPager"></div>
    </div>
  </section>

  <section id="view-stats" class="stack" hidden>
    <div class="panel">
      <h2>Echte Zahlen
        <span class="hint" tabindex="0" data-tip="Nicht gerechnet, sondern abgefragt: die Erlöse stammen aus Googles Orders-API und stehen je Kauf im Hauptbuch, die Ausgaben sind der Listenpreis des tatsächlich Gerechneten aus dem eigenen Hauptbuch."></span>
      </h2>
      <div id="finance"></div>
    </div>
    <div class="panel">
      <h2>Verlauf
        <span class="hint" tabindex="0" data-tip="Aus den Tagessummen und den Käufen gebaut, nicht aus dem Einzelprotokoll — deshalb reicht die Kurve auch über die 90-Tage-Aufbewahrung hinaus zurück."></span>
      </h2>
      <div class="row" style="margin-bottom:12px">
        <select id="sRange" style="width:auto">
          <option value="30">30 Tage</option><option value="90" selected>90 Tage</option>
          <option value="365">1 Jahr</option><option value="1095">3 Jahre</option>
        </select>
        <select id="sMetric" style="width:auto">
          <option value="requests">Anfragen</option>
          <option value="seconds">Diktierte Sekunden</option>
          <option value="costUsd">Einkaufskosten</option>
          <option value="revenue">Erlöse</option>
          <option value="orders">Käufe</option>
          <option value="newWallets">Neue Konten</option>
          <option value="errors">Fehler</option>
        </select>
      </div>
      <div class="chart" id="sChart" style="height:150px"></div>
      <div class="sub" id="sChartNote"></div>
      <div class="grid" id="sSummary" style="margin-top:16px"></div>
    </div>
    <div class="panel">
      <h2>Monate</h2>
      <div class="scroll" id="sMonths"></div>
    </div>
    <div class="panel">
      <h2>Tage</h2>
      <div class="scroll" id="sDays"></div>
    </div>
  </section>

  <section id="view-plans" class="stack" hidden>
    <div class="panel">
      <h2>Was jedes Paket einbringt
        <span class="hint" tabindex="0" data-tip="Zwei Spalten nebeneinander: das Modell, mit dem die Preise kalkuliert wurden, und was tatsächlich passiert ist. Wo noch nichts verkauft wurde, bleibt die Ist-Spalte leer — eine Hochrechnung als Messung auszugeben wäre genau der Fehler, den diese Seite nicht machen darf."></span>
      </h2>
      <div id="planCards" class="plans"></div>
    </div>
    <div class="panel">
      <h2>Vergleich</h2>
      <div class="scroll" id="planTable"></div>
    </div>
    <div class="panel">
      <h2>Grundlage der Rechnung</h2>
      <div id="planBasis"></div>
    </div>
  </section>

  <section id="view-tax" class="stack" hidden>
    <div class="panel">
      <h2>Jahresübersicht
        <span class="hint" tabindex="0" data-tip="Zum Abgleich gedacht, nicht als Steuererklärung. Verbindlich sind Googles monatliche Auszahlungsberichte und Cloudflares Rechnungen — hier siehst du, ob die das sagen, was du erwartest, und dein Steuerberater bekommt eine Aufstellung je Monat."></span>
      </h2>
      <div id="taxYears" class="stack"></div>
    </div>

    <div class="panel">
      <h2>Abgleich mit Cloudflares Rechnung
        <span class="hint" tabindex="0" data-tip="Die einzige Prüfung gegen echtes Geld. Bis zum Umzug verglich eine Regel täglich die eigene Rechnung mit der Abrechnung des Anbieters; Workers AI hat keinen solchen Endpunkt, also bleibt die Monatsrechnung von Hand. Links steht, was wir sagen — Neuronen abzüglich Freikontingent, tagweise. Rechts, was berechnet wurde, sobald du die Rechnung unter Ausgaben erfasst hast. Ein abgeschlossener Monat ohne Rechnung meldet sich nach zwei Wochen von selbst."></span>
      </h2>
      <div id="reconcile" class="scroll"></div>
    </div>

    <div class="panel">
      <h2>Ausgaben erfassen
        <span class="hint" tabindex="0" data-tip="Rechnungen werden von Hand erfasst, mit Rechnungsnummer. Für die Einnahmen-Überschuss-Rechnung zählt der Tag, an dem das Geld abgeht — nicht der Tag, an dem die Rechenzeit verbraucht wird."></span>
      </h2>
      <p class="sub" style="margin:0 0 12px">Trage ein, was tatsächlich von deinem Konto abgegangen ist. „Belastet" ist der Betrag auf dem Kontoauszug inklusive Fremdwährungsaufschlag — leer gelassen rechne ich mit dem EZB-Kurs des Tages und kennzeichne es als Näherung.</p>
      <div class="row">
        <input id="exDate" type="date" style="width:auto">
        <select id="exKind" style="width:auto">
          <option value="cloudflare">Cloudflare (inkl. Workers AI)</option>
          <option value="domain">Domain</option>
          <option value="other">Sonstiges</option>
        </select>
        <input id="exAmount" type="number" step="0.01" placeholder="Betrag laut Rechnung" style="width:auto">
        <select id="exCurrency" style="width:auto"><option>USD</option><option>EUR</option></select>
        <input id="exHome" type="number" step="0.01" placeholder="belastet (optional)" style="width:auto">
        <input id="exRef" placeholder="Rechnungsnummer" style="width:auto">
        <button class="btn" id="exAdd">Erfassen</button>
      </div>
      <div class="scroll" style="margin-top:14px" id="taxExpenses"></div>
    </div>

    <div class="panel">
      <h2>Monate
        <span class="hint" tabindex="0" data-tip="Nach Kaufdatum gruppiert, weil das Hauptbuch das exakt weiß. Zufluss ist aber der Tag, an dem Google auszahlt — meist Mitte des Folgemonats. Für Dezember gehört deshalb ein zweiter Blick in Googles Auszahlungsbericht."></span>
      </h2>
      <div class="scroll" id="taxMonths"></div>
      <div class="row" style="margin-top:12px"><button class="btn ghost" id="taxCsv">Als CSV herunterladen</button></div>
    </div>
  </section>

  <section id="view-accounts" class="stack" hidden>
    <div class="panel">
      <h2>Konto suchen
        <span class="hint" tabindex="0" data-tip="Ein Feld für vier Kennungen: Wallet-ID, Wiederherstellungscode, Play-Bestellnummer oder Kauf-Token. Leer lassen zeigt die zuletzt aktiven Konten."></span>
      </h2>
      <div class="row">
        <input id="q" class="grow" placeholder="Wallet-ID, Wiederherstellungscode, Bestellnummer oder Kauf-Token">
        <button class="btn" id="search">Suchen</button>
        <button class="btn ghost" id="clearSearch">Zurücksetzen</button>
      </div>
      <div class="row" style="margin-top:10px">
        <label class="row" style="gap:6px"><input type="checkbox" id="wTest" style="width:auto"> Testkonten einblenden</label>
        <span class="hint" tabindex="0" data-tip="Konten, die dir gehören: Play-Lizenztester erkennt der Server selbst, per Hand angelegte ebenfalls. Sie sind aus jeder Geld- und Nutzungszahl herausgerechnet. Eine Suche findet sie immer, auch ohne diesen Haken."></span>
        <label class="row" style="gap:6px"><input type="checkbox" id="wDeleted" style="width:auto"> Gelöschte einblenden</label>
        <span class="hint" tabindex="0" data-tip="Ein gelöschtes Konto behält nur deshalb eine Zeile, weil die Kaufbelege darauf zeigen und zehn Jahre bleiben müssen. Darauf steht nichts mehr, was zu einer Person führt: kein Wiederherstellungscode, kein Play-Pseudonym, keine Geräte, kein Nutzungsprotokoll. Aus allen Kennzahlen ist es heraus."></span>
      </div>
      <div class="scroll" style="margin-top:14px"><table id="walletTable">
        <thead><tr><th>Konto</th><th>Zustand</th><th class="num">Guthaben</th><th class="num">Gekauft</th>
        <th class="num">Verbraucht</th><th>Zuletzt gesehen</th><th class="wrap">Notiz</th></tr></thead><tbody></tbody>
      </table></div>
      <div class="empty" id="walletEmpty" hidden>Keine Konten gefunden.</div>
    </div>
  </section>

  <section id="view-traffic" class="stack" hidden>
    <div class="panel">
      <h2>Verkehr
        <span class="hint" tabindex="0" data-tip="Eine Zeile je Anfrage — ausschließlich Metadaten. Inhalte, also Audio und Text, werden nie gespeichert. Einzelzeilen werden nach 90 Tagen gelöscht, die Tagessummen bleiben."></span>
      </h2>
      <div class="row">
        <select id="tKind" style="width:auto"><option value="">Alle Arten</option><option value="transcribe">Diktat</option><option value="reword">Umformulierung</option></select>
        <label class="row" style="gap:6px"><input type="checkbox" id="tFail" style="width:auto"> nur Fehler</label>
        <label class="row" style="gap:6px"><input type="checkbox" id="tTest" style="width:auto" checked> Testkonten</label>
      </div>
      <div class="scroll" id="traffic" style="margin-top:12px"></div>
      <div class="pager" id="trafficPager"></div>
    </div>
  </section>

  <section id="view-audit" class="stack" hidden>
    <div class="panel">
      <h2>Von Hand geändert
        <span class="hint" tabindex="0" data-tip="Jede Aktion aus diesem Dashboard, mit Person, Konto und Begründung. Ohne diese Spur gehen die Zahlen nicht mehr auf, sobald einmal korrigiert wurde."></span>
      </h2>
      <div class="scroll" id="audit"></div>
      <div class="pager" id="auditPager"></div>
    </div>
  </section>

  <section id="view-network" class="stack" hidden>
    <div class="gmode">
      <button id="gModeList" aria-pressed="false">Liste</button>
      <button id="gModeMap" aria-pressed="true">Diagramm</button>
    </div>
    <div class="gwrap" id="gmap">
      <div class="gtools" id="gtools">
        <button id="gAll" aria-pressed="true">Alles</button>
        <button id="gTok" aria-pressed="false">Schlüssel</button>
        <button id="gSec" aria-pressed="false">Absicherung</button>
        <button id="gOut">−</button><button id="gIn">+</button><button id="gFit">Einpassen</button>
      </div>
      <svg id="gsvg"><g id="gcam"></g></svg>
      <div class="legend" id="glegend"></div>
      <div class="ndetail" id="ndetail"></div>
    </div>
    <div id="glist" class="stack" hidden></div>
  </section>

  <section id="view-ops" class="stack" hidden></section>
</main>

<dialog id="explain">
  <div class="dlg-head"><strong id="xTitle"></strong><button class="btn ghost" id="xClose">Schließen</button></div>
  <div class="dlg-body" id="xBody"></div>
</dialog>
<dialog id="detail">
  <div class="dlg-head">
    <strong id="dTitle" class="mono" style="font-size:13px"></strong>
    <span style="flex:1"></span>
    <button class="btn ghost" id="dClose">Schließen</button>
  </div>
  <div class="dlg-body" id="dBody"></div>
</dialog>

<dialog id="modal"><div class="dlg-body" id="mBody"></div></dialog>

<script>
var GRAPH = ${GRAPH_JSON};
(function () {
  var $ = function (id) { return document.getElementById(id); };
  var PAGE = 50, trafficOffset = 0, auditOffset = 0, alertOffset = 0, current = null;

  function fmtMinutes(sec) {
    if (sec === null || sec === undefined) return '—';
    var s = n(sec);
    var neg = s < 0, a = Math.abs(s), h = Math.floor(a / 3600), m = Math.floor((a % 3600) / 60);
    return (neg ? '−' : '') + (h > 0 ? h + ' h ' + m + ' min' : m + ' min');
  }
  // Every figure passes through here, so a value that arrives as a string — which happens with
  // external APIs and with SQLite sums — degrades to a wrong-looking number instead of throwing
  // and taking a whole panel down with it.
  function n(v) { var x = typeof v === 'number' ? v : Number(v); return isFinite(x) ? x : 0; }
  function fmtUsd(v) { return '$' + n(v).toFixed(2); }
  function fmtUsd4(v) { return '$' + n(v).toFixed(4); }
  function fmtDate(ms) { return ms ? new Date(ms).toLocaleString('de-DE', { dateStyle: 'short', timeStyle: 'short' }) : '—'; }
  function esc(s) {
    return String(s === null || s === undefined ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
  function hint(t) { return '<span class="hint" tabindex="0" data-tip="' + esc(t) + '"></span>'; }

  // On a touch screen there is nothing to hover with, so the same text opens as a dialog. Delegated
  // from the document because these marks are written into innerHTML all over the page and rewired
  // on every refresh — one listener that outlives all of it beats a hundred that do not.
  function tooltipsNeedTapping() {
    return !matchMedia('(hover: hover)').matches || innerWidth <= 620;
  }
  document.addEventListener('click', function (e) {
    var mark = e.target.closest && e.target.closest('.hint');
    if (!mark || !tooltipsNeedTapping()) return;
    e.preventDefault();
    e.stopPropagation();
    modal({ title: 'Dazu', text: esc(mark.getAttribute('data-tip') || ''), cancel: false, okLabel: 'Verstanden' });
  }, true);
  /**
   * Anything talking to the server says so, on the refresh button.
   *
   * Counted rather than switched: a view fires several requests at once, and the first one to land
   * must not call it finished while three are still running.
   *
   * And held for a moment at the end — a full sweep, however fast the answer came. The point of the
   * signal is to confirm that a refresh happened at all; a request answered in forty milliseconds
   * would otherwise flick the colour on and off inside a single frame and confirm nothing.
   */
  var busyCount = 0, busySince = 0, busyOff = null;
  var SWEEP = 620;
  function busy(delta) {
    busyCount = Math.max(0, busyCount + delta);
    var el = $('refresh');
    if (!el) return;
    clearTimeout(busyOff);
    if (busyCount > 0) {
      if (!el.classList.contains('working')) busySince = Date.now();
      el.classList.add('working');
      return;
    }
    busyOff = setTimeout(function () { el.classList.remove('working'); },
      Math.max(120, SWEEP - (Date.now() - busySince)));
  }
  function tracked(promise) {
    busy(1);
    return promise.then(
      function (v) { busy(-1); return v; },
      function (e) { busy(-1); throw e; },
    );
  }

  function get(p) {
    return tracked(fetch(p, { credentials: 'same-origin' })
      .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); }));
  }
  function act(p) {
    return tracked(fetch('/admin/api/action', { method: 'POST', credentials: 'same-origin',
      headers: { 'content-type': 'application/json' }, body: JSON.stringify(p) })
      .then(function (r) { return r.json(); }));
  }

  /* --------------------------------------------------------------- Dialoge */

  function modal(opts) {
    return new Promise(function (resolve) {
      var html = '<div class="m-title">' + esc(opts.title) + '</div>';
      if (opts.text) html += '<div class="m-text">' + opts.text + '</div>';
      if (opts.code) html += '<div class="m-code">' + esc(opts.code) + '</div>';
      html += '<div class="m-actions">';
      if (opts.cancel !== false) html += '<button class="btn ghost" data-a="0">' + esc(opts.cancelLabel || 'Abbrechen') + '</button>';
      html += '<button class="btn ' + (opts.danger ? 'danger' : '') + '" data-a="1">' + esc(opts.okLabel || 'OK') + '</button></div>';
      $('mBody').innerHTML = html;
      var done = function (v) { $('modal').close(); resolve(v); };
      Array.prototype.forEach.call($('mBody').querySelectorAll('[data-a]'), function (b) {
        b.onclick = function () { done(b.getAttribute('data-a') === '1'); };
      });
      $('modal').onclose = function () { resolve(false); };
      $('modal').showModal();
      var ok = $('mBody').querySelector('[data-a="1"]');
      if (ok) ok.focus();
    });
  }
  function tell(title, text) { return modal({ title: title, text: esc(text), cancel: false }); }

  /*
   * Welches Modell die Anfrage bearbeitet hat — kurz in der Spalte, genau im Tooltip.
   *
   * Nicht der Anbieter: den gibt es nur noch einen, eine Spalte mit einem einzigen Wert in jeder
   * Zeile ist Rauschen. Das Modell dagegen kann sich ohne Deployment ändern, und dann ist es die
   * Angabe, die man als Erstes sucht.
   *
   * Neuronen stehen im Tooltip dazu: die Menge, die sich als Einzige gegen Cloudflares eigene
   * Zählung halten lässt.
   */
  function modelCell(x) {
    if (!x.model) return '<span class="muted">—</span>';
    // Split rather than a regex, and that is not a style choice: this whole document is one template
    // literal, so a backslash inside it is consumed before the browser ever sees the script. The
    // regex this replaced read /^@cf\\/[^/]+\\// in the source, arrived as /^@cf/[^/]+// in the page,
    // and took the entire dashboard down with a syntax error — no data, no working tabs, and nothing
    // in the toolchain able to say why. Escapes in here are a trap; not needing one is the fix.
    var parts = String(x.model).split('/');
    var short = parts[parts.length - 1];
    var neurons = (x.neuronsMicro || 0) / 1e6;
    var tip = x.model + (x.provider ? ' · ' + x.provider : '') +
      (neurons ? ' · ' + neurons.toFixed(3) + ' Neuronen' : '');
    return '<span class="pill" title="' + esc(tip) + '">' + esc(short) + '</span>';
  }

  function card(label, tip, value, sub, cls, extra) {
    return '<div class="card' + (cls ? ' ' + cls : '') + '"><div class="label">' + esc(label) +
      (tip ? hint(tip) : '') + '</div><div class="value">' + value + '</div>' +
      (sub ? '<div class="sub">' + sub + '</div>' : '') + (extra || '') + '</div>';
  }

  /* ------------------------------------------------------- Zahlen, die laufen */

  /*
   * A figure counts up to what it now says — but only when it is not what it said before.
   *
   * That condition is the point of the whole thing. On a page that is refreshed all day, motion
   * that happens every time says nothing; motion that happens only on a change turns the animation
   * itself into information, and the eye finds the one card that moved without reading the others.
   *
   * The numbers arrive here already formatted, as text, so they have to be read back out of it.
   * [numberIn] does that without guessing a locale: it works out the decimal and grouping
   * characters from the string in front of it and puts the same ones back, and returns null the
   * moment anything is ambiguous — a figure that cannot be read back is simply not animated, which
   * is always better than one that is redrawn wrong.
   */
  var seenValues = {}, rollCount = 0, valueTimer = 0;
  var motionOff = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);

  function numberIn(text) {
    var m = /^([^\\d-]*)(-?[\\d.,]*\\d)(.*)$/.exec(text);
    if (!m) return null;
    var prefix = m[1], raw = m[2], suffix = m[3];
    var cut = Math.max(raw.lastIndexOf(','), raw.lastIndexOf('.'));
    var dec = 0, decSep = '', groupSep = '';
    if (cut >= 0) {
      var tail = raw.slice(cut + 1);
      if (!/^\\d+$/.test(tail)) return null;
      // Exactly three digits after the last separator is a thousands group, not a fraction: 1.234
      // is a thousand, 33.04 is not. Nothing on this page is written to three decimal places.
      if (tail.length === 3) {
        groupSep = raw.charAt(cut);
      } else {
        dec = tail.length; decSep = raw.charAt(cut);
        var earlier = /[.,]/.exec(raw.slice(0, cut));
        if (earlier) groupSep = earlier[0];
      }
    }
    var digits = '';
    for (var i = 0; i < raw.length; i++) {
      var c = raw.charAt(i);
      if ((c >= '0' && c <= '9') || c === '-') digits += c;
      else if (i === cut && decSep) digits += '.';
    }
    var value = parseFloat(digits);
    if (!isFinite(value)) return null;
    return {
      value: value,
      write: function (v) {
        var body = Math.abs(v).toFixed(dec);
        var whole = dec ? body.slice(0, body.length - dec - 1) : body;
        var frac = dec ? body.slice(body.length - dec) : '';
        if (groupSep) whole = whole.replace(/\\B(?=(\\d{3})+(?!\\d))/g, groupSep);
        return prefix + (v < 0 ? '-' : '') + whole + (dec ? decSep + frac : '') + suffix;
      },
    };
  }

  /* A card is identified by its heading, so the same figure is recognised across a re-render. */
  function valueKey(el) {
    if (!el.closest) return null;
    var box = el.closest('.card');
    var label = box ? box.querySelector('.label') : null;
    var text = label ? label.textContent.trim() : '';
    if (!text) return null;
    var sect = el.closest('section');
    return (sect ? sect.id : '-') + '|' + text;
  }

  function rollValue(el, from, to, write) {
    rollCount++;
    var t0 = 0;
    function frame(now) {
      if (!t0) t0 = now;
      var p = Math.min(1, (now - t0) / 520);
      // Fast at first and settling at the end, so the final figure is legible well before it stops.
      el.textContent = write(from + (to - from) * (1 - Math.pow(1 - p, 3)));
      if (p < 1) requestAnimationFrame(frame); else rollCount--;
    }
    requestAnimationFrame(frame);
  }

  function countUp(scope) {
    var nodes = (scope || document).querySelectorAll('.value');
    Array.prototype.forEach.call(nodes, function (el) {
      // Only a plain number: a .value holding markup, or an error message, is left alone.
      if (el.children.length) return;
      var key = valueKey(el);
      if (!key) return;
      var read = numberIn(el.textContent);
      if (!read) { delete seenValues[key]; return; }
      var before = seenValues[key];
      seenValues[key] = read.value;
      // The first sighting is recorded, never animated — a page that counts up on arrival says
      // "everything changed", which is the opposite of what this is for.
      if (motionOff || before === undefined || before === read.value) return;
      rollValue(el, before, read.value, read.write);
    });
  }

  /*
   * One hook rather than a call at the end of every render: each view replaces its own innerHTML,
   * and there are a dozen places that do it. The observer fires on those replacements, not per
   * frame, and stands down entirely while a figure is mid-roll — otherwise the roll would observe
   * itself sixty times a second.
   */
  function watchValues() {
    var root = document.querySelector('main');
    if (!root || !window.MutationObserver) return;
    new MutationObserver(function () {
      if (rollCount) return;
      clearTimeout(valueTimer);
      valueTimer = setTimeout(function () { countUp(root); }, 30);
    }).observe(root, { childList: true, subtree: true });
  }

  /**
   * A filled area over a series of numbers, with the last point marked.
   *
   * Bars answer "how much on that day"; a filled line answers "which way is this going", which is
   * the question a card is glanced at for. The endpoint is emphasised because on a series that
   * ends today, the last value is the one being looked for.
   */
  function spark(values) {
    if (!values || values.length < 2) return '';
    var max = Math.max.apply(null, values.concat([1]));
    var step = 100 / (values.length - 1);
    var pts = values.map(function (v, i) {
      return (i * step).toFixed(2) + ',' + (30 - (n(v) / max) * 28).toFixed(2);
    });
    // pathLength="100" so the draw-in animation can work in percent: without it the dash arithmetic
    // would depend on the real length of the line, which differs with every series.
    return '<svg class="spark" viewBox="0 0 100 32" preserveAspectRatio="none" aria-hidden="true">' +
      '<path class="area" d="M0,32 L' + pts.join(' L') + ' L100,32 Z"></path>' +
      '<path class="line" pathLength="100" d="M' + pts.join(' L') + '"></path>' +
      '<circle cx="100" cy="' + (30 - (n(values[values.length - 1]) / max) * 28).toFixed(2) + '" r="1.9"></circle></svg>';
  }

  /** Placeholder in the shape of the answer, shown the moment a view is opened. */
  function skeleton(kind, count) {
    var one = kind === 'card'
      ? '<div class="card"><div class="sk sk-line" style="width:45%"></div><div class="sk sk-card"></div></div>'
      : '<div class="sk sk-line" style="width:' + (60 + (count % 3) * 12) + '%"></div>';
    var out = '';
    for (var i = 0; i < count; i++) out += one;
    return kind === 'card' ? out : '<div>' + out + '</div>';
  }

  /* -------------------------------------------------------------- Übersicht */

  var sum = null;

  /**
   * The three money cards, from the sources that actually hold the money.
   *
   * One function, used by both the overview and the statistics view: two places computing the same
   * profit is how a dashboard starts contradicting itself.
   */
  function moneyCards(s) {
    if (!s) return skeleton('card', 3);
    var cur = s.homeCurrency;
    var rateNote = s.rateSource === 'ecb'
      ? 'EZB-Tageskurs ' + n(s.rate).toFixed(4)
      : 'angenommener Kurs ' + s.rate;

    var foreign = (s.byCurrency || []).filter(function (c) { return c.currency !== cur; });
    var others = foreign.length
      ? '<br>darin ' + foreign.map(function (c) { return money(c.revenue, c.currency); }).join(' · ') + ' umgerechnet'
      : '';
    var extra = [];
    if (s.testOrders) extra.push(s.testOrders + ' Testkauf/-käufe ausgenommen');
    if (s.withoutFigures) extra.push(s.withoutFigures + ' ohne echte Beträge');
    // Named rather than hidden: the total is knowingly short by these, and a figure that quietly
    // omits sales is worse than one that says how many it omitted.
    if (s.withoutRate) extra.push(s.withoutRate + ' noch ohne Umrechnungskurs');

    // Der Erlös kommt später als die Zahlung — bis dahin stünde hier sonst „0,00 €" für einen Kauf,
    // der längst bezahlt ist. Die Schätzung steht deshalb daneben, nie in der Zahl darüber.
    var pending = s.unreportedOrders
      ? '<br>+ ca. ' + money(s.revenueEstimatedHome, cur) + ' aus ' + s.unreportedOrders +
        ' Kauf/Käufen, die Google noch nicht abgerechnet hat'
      : '';

    var html = card('Einnahmen',
      'Was nach Googles Anteil und Steuern bei dir ankommt — von Google je Bestellung gemeldet, nicht gerechnet. ' +
      'Fremdwährungen sind mit dem EZB-Kurs des Kauftags umgerechnet und zählen mit; der Kurs steht fest auf der ' +
      'Buchung, ändert sich also nachträglich nicht mehr. Testkäufe sind ausgenommen: die kosten nichts und ' +
      'bringen nichts ein. Den Entwickleranteil meldet Google erst, wenn die Zahlung abgerechnet ist — was noch ' +
      'aussteht, steht als Schätzung darunter (Brutto − Steuer − Googles Anteil) und zählt in keiner Summe mit.',
      money(s.revenueHome, cur),
      s.orders + ' Kauf/Käufe · Kundschaft zahlte ' + money(s.paidHome, cur) + others + pending +
      (extra.length ? '<br>' + extra.join(' · ') : ''));

    // Was auf der Rechnung landet, nicht der Listenpreis. Der Unterschied ist bei diesem Umfang
    // nicht klein, sondern fast alles: Ein Tag unter dem Freikontingent kostet **null**, egal was
    // sein Listenpreis sagt. Stünde hier die Liste, wäre der Gewinn jeden Tag um das ganze
    // Kontingent zu niedrig — ein Fehler, der mit der Zeit wächst und immer in dieselbe Richtung.
    var listNote = s.listUsd > s.costUsd
      ? '<br><span class="muted">Listenpreis ' + fmtUsd4(s.listUsd) + ' · die Differenz deckt das Freikontingent</span>'
      : '';
    html += card('Einkaufskosten',
      'Was Cloudflare tatsächlich berechnet: der Neuronenverbrauch aus dem eigenen Hauptbuch, **je Tag abzüglich des Freikontingents**. Tagweise gerechnet, weil das Kontingent ein Tageswert ist und nicht überträgt — ein Monat ist die Summe der Tagesergebnisse und nie eine Rechnung auf den Monatsneuronen. Die Neuronen meldet jedes Modell in seiner eigenen Antwort, es ist also gemessen und nicht geschätzt. Eigene Testanfragen zählen mit: Das Kontingent gehört dem Konto, nicht der Kundschaft. Geprüft wird die Zahl einmal im Monat gegen die echte Cloudflare-Rechnung, von Hand.',
      fmtUsd(s.costUsd),
      '≈ ' + money(s.costHome, cur) + ' · ' + rateNote + listNote);

    var profitCls = s.profitHome < 0 ? 'crit' : '';
    html += card('Gewinn',
      'Einnahmen minus dem, was Cloudflare wirklich berechnet — nicht minus dem Listenpreis. Beides in ' + cur + '. Der Dollarkurs kommt von der EZB, ist aber trotzdem ' +
      'nicht Googles Kurs — Play zahlt zu eigenen Kursen aus, die Zahl bleibt also eine gute Näherung. ' +
      'Cloudflare-Grundgebühr, Domain und deine Zeit stehen nirgends darin.',
      '<span' + (profitCls ? ' style="color:var(--crit)"' : '') + '>' + money(s.profitHome, cur) + '</span>',
      'nach Play-Gebühr und Steuer' +
          // Ein Minus, das nur an einer ausstehenden Meldung hängt, soll nicht wie ein Verlust
          // dastehen. Beide Zahlen nebeneinander, keine davon in der anderen.
          (s.unreportedOrders && s.profitWithEstimateHome !== null && s.profitWithEstimateHome !== undefined
            ? '<br>mit den noch nicht abgerechneten Käufen: ' + money(s.profitWithEstimateHome, cur)
            : ''));
    return html;
  }

  /*
   * Das Freikontingent und die Kosten des Tages.
   *
   * Beide Karten erscheinen erst, wenn es Neuronen gibt — vor der Umstellung wäre ein Balken auf
   * null keine Information, sondern eine Zeile, die man wegzulesen lernt.
   *
   * Der Balken darf über 100 % laufen. Mehr als 214 Audiominuten am Tag sind kein Fehler, sondern
   * ein guter Tag; nur ist ab dort eben etwas zu bezahlen, und genau das steht dann darunter.
   */
  function untilReset(ms) {
    var left = Math.max(0, ms - Date.now());
    var h = Math.floor(left / 3600000);
    var m = Math.floor((left % 3600000) / 60000);
    return h ? h + ' h ' + m + ' min' : m + ' min';
  }

  function neuronCards(o) {
    var ne = o.neurons || {};
    // Der Balken erscheint erst, wenn es Neuronen gibt. Vor der Umstellung wäre er dauerhaft auf
    // null — eine Zeile, die man wegzulesen lernt, bevor sie etwas zu sagen hat.
    var html = ne.freePerDay && (ne.total || ne.today) ? freeQuotaCard(ne) : '';

    // Die Karte zeigt, was Cloudflare berechnet, und nicht den Listenpreis. Das ist der Unterschied
    // zwischen einer Zahl, die man ablesen kann, und einer, die man erst umrechnen muss: Solange der
    // Tag unter dem Freikontingent bleibt — und das sind die meisten —, ist der Einkauf **null**,
    // und eine Karte, die dann 0,0008 $ anzeigt, behauptet eine Ausgabe, die es nicht gibt.
    //
    // Der Listenpreis steht darunter, weil er nicht wertlos ist: Er ist die Größe, gegen die die
    // Marge gerechnet wird, und er sagt, wie nah der Tag am Kontingent war.
    var free = ne.freePerDay && ne.today < ne.freePerDay;
    var sub = free
      ? '<span class="ok-text">im Freikontingent</span> · Listenpreis ' + fmtUsd4(o.cost.todayUsd)
      : 'Listenpreis ' + fmtUsd4(o.cost.todayUsd);
    var perMonth = 'Monat <strong>' + fmtUsd4(o.cost.billedMonthUsd) + '</strong>' +
      ' <span class="muted">(Liste ' + fmtUsd4(o.cost.monthUsd) + ')</span>';
    var perTotal = 'gesamt <strong>' + fmtUsd4(o.cost.billedTotalUsd) + '</strong>' +
      ' <span class="muted">(Liste ' + fmtUsd4(o.cost.totalUsd) + ')</span>';
    var trend = ne.total
      ? '<br><span class="muted">gestern ' + ne.yesterday.toLocaleString('de-DE') +
        ' · Schnitt 7 T ' + ne.avg7.toLocaleString('de-DE') + ' Neuronen</span>'
      : '';

    // Woher die Zahl kommt. Alles andere auf dieser Karte ist wertlos, wenn dieser Satz nicht
    // „gemessen" sagt — deshalb steht er dabei und nicht in der Erklärung.
    var est = o.cost.estimatedRequests || 0;
    var meas = o.cost.measuredRequests || 0;
    var origin = (meas + est) === 0 ? ''
      : est === 0
        ? '<br><span class="ok-text">alle ' + meas + ' Anfragen aus der Antwort gemessen</span>'
        : '<br><span class="warn-text">' + est + ' von ' + (meas + est) +
          ' Anfragen geschätzt — die Antwort nannte keine Neuronen</span>';

    html += card('Einkauf heute',
      'Was Cloudflare für heute wirklich berechnet: der Neuronenverbrauch, **abzüglich des Freikontingents**. An den meisten Tagen ist das null, und dann springt es — das ist kein Fehler, sondern ein Tag, der über dem Kontingent lag. Der Listenpreis darunter ist die Zahl ohne den Abzug; gegen ihn wird die Marge gerechnet. Beide stammen aus den Neuronen, die die Modelle selbst je Antwort melden, nicht aus einer Schätzung.',
      fmtUsd4(o.cost.billedTodayUsd), sub + '<br>' + perMonth + '<br>' + perTotal + trend + origin);
    return html;
  }

  function freeQuotaCard(ne) {
    var pct = ne.freeUsedPercent;
    var cls = pct >= 100 ? 'crit' : pct >= 80 ? 'warn' : '';
    var over = Math.max(0, ne.today - ne.freePerDay);
    var html = '<div class="card"><div class="label">Freikontingent' +
      hint('Workers Paid enthält ' + ne.freePerDay.toLocaleString('de-DE') + ' Neuronen je Tag — das entspricht etwa ' +
        ne.freeAudioMinutes + ' Audiominuten, wenn nur diktiert wird. Rücksetzung um 00:00 UTC, also 02:00 unserer Sommerzeit. Nichts wird übertragen: Was heute übrig bleibt, ist morgen weg. Höchstwert ' +
        fmtUsd4(ne.freeValueUsd) + ' am Tag.') +
      '</div><div class="value">' + pct + ' %</div><div class="sub">' +
      ne.today.toLocaleString('de-DE') + ' von ' + ne.freePerDay.toLocaleString('de-DE') + ' Neuronen' +
      (over ? ' · <strong>' + over.toLocaleString('de-DE') + ' darüber</strong>' : '') +
      '<br>Rücksetzung in ' + untilReset(ne.resetAtMs) +
      '</div><div class="bar-track"><i class="' + cls + '" style="width:' + Math.min(100, pct) + '%"></i></div></div>';
    return html;
  }

  function renderOverview(o) {
    current = o;
    var bcls = o.budget.usedPercent >= 90 ? 'crit' : o.budget.usedPercent >= 60 ? 'warn' : '';
    var html = '';
    // Wie alt das offene Guthaben ist, gehört neben die Summe und nicht in eine eigene Ansicht:
    // Guthaben auf einem Konto, das sich seit einem Monat nicht gemeldet hat, ist etwas anderes als
    // Guthaben auf einem, das gestern diktiert hat — einmal eine Verbindlichkeit, die kommt, einmal
    // eine, die vermutlich liegen bleibt. Und die zweite Zahl ist zugleich das deutlichste
    // Produktsignal, das dieser Dienst hat.
    var liaSub = 'Über alle aktiven Konten';
    if (o.liability.staleSeconds > 0) {
      liaSub += '<br><span class="muted">' + fmtMinutes(o.liability.freshSeconds) + ' auf Konten der letzten 30 Tage · ' +
        fmtMinutes(o.liability.staleSeconds) + ' länger unberührt</span>';
    }
    if (o.liability.dormantWallets > 0) {
      liaSub += '<br><span class="warn-text">' + o.liability.dormantWallets +
        ' Konto/Konten mit Guthaben seit über 90 Tagen still</span>';
    }
    html += card('Offenes Guthaben', 'Bezahlte, aber noch nicht verbrauchte Minuten. Eine Verbindlichkeit, kein Umsatz — Arbeit, die du noch schuldest. Die Zahl, die ein Prepaid-Modell am leichtesten unterschlägt. Darunter, wie alt sie ist: Guthaben auf einem Konto, das sich seit Monaten nicht gemeldet hat, wird vermutlich nie eingelöst — und sagt zugleich, dass jemand gekauft und aufgehört hat.', fmtMinutes(o.liability.seconds), liaSub, 'lead');
    html += moneyCards(sum);
    html += '<div class="card"><div class="label">Tagesbudget' + hint('Obergrenze für die Einkaufskosten eines Tages. Ist sie erreicht, antwortet der Dienst mit 503 und die App meldet „vorübergehend nicht verfügbar".') + '</div><div class="value">' + o.budget.usedPercent + ' %</div><div class="sub">' + fmtUsd4(o.budget.spentUsd) + ' von ' + fmtUsd(o.budget.limitUsd) + '</div><div class="bar-track"><i class="' + bcls + '" style="width:' + Math.min(100, o.budget.usedPercent) + '%"></i></div></div>';
    html += neuronCards(o);
    html += card('Konten', '„Aktiv" heißt: hat im Zeitraum mindestens eine Anfrage gestellt. Testkonten sind nicht mitgezählt.', o.wallets.total, o.wallets.active7 + ' aktiv (7 T) · ' + o.wallets.active30 + ' (30 T)<br>' + o.wallets.new30 + ' neu (30 T) · ' + o.wallets.blocked + ' gesperrt' + (o.wallets.test ? ' · ' + o.wallets.test + ' Testkonten' : ''));

    var trend = o.days.slice().reverse().map(function (d) { return d.requests; });
    html += card('Heute', 'p95 heißt: 95 % der Anfragen waren schneller als dieser Wert — aussagekräftiger als ein Mittelwert, der Ausreißer verdeckt. Die Kurve zeigt die letzten 30 Tage.',
      o.usage.requestsToday + ' Anfragen',
      fmtMinutes(o.usage.secondsToday) + ' diktiert · ' + o.usage.errorsToday + ' Fehler' + (o.usage.p95Ms !== null ? '<br>p95 ' + o.usage.p95Ms + ' ms' : ''),
      '', spark(trend));
    $('stats').innerHTML = html;

    $('killPill').innerHTML = o.budget.killed ? '<span class="pill crit">Dienst gestoppt</span>' : '<span class="pill ok">Läuft</span>';
    renderBell(o.alerts);

    var days = o.days.slice().reverse();
    var max = Math.max.apply(null, days.map(function (d) { return d.requests; }).concat([1]));
    $('chart').innerHTML = days.map(function (d) {
      return '<div title="' + esc(d.day) + ': ' + d.requests + ' Anfragen" style="height:' + Math.max(2, Math.round(d.requests / max * 100)) + '%"></div>';
    }).join('');
    $('chartNote').textContent = days.length ? days[0].day + ' bis ' + days[days.length - 1].day : 'Noch kein Verkehr.';

    var cur = sum ? sum.homeCurrency : 'EUR';
    $('packs').innerHTML = o.revenue.byPack.length ?
      '<table><thead><tr><th>Paket</th><th class="num">Verkäufe</th><th class="num">Minuten</th><th class="num">Erlös</th></tr></thead><tbody>' +
      o.revenue.byPack.map(function (p) {
        return '<tr><td>' + esc(p.name) + ' <span class="mono muted">' + esc(p.productId) + '</span></td><td class="num">' + p.count + '</td><td class="num">' + p.minutesSold + '</td><td class="num">' + money(p.revenue, cur) + '</td></tr>';
      }).join('') + '</tbody></table>' : '<div class="empty">Noch nichts verkauft.</div>';
    renderOps(o);
  }

  /* -------------------------------------------------------------- Warnungen */

  // Two lengths of the same statement, so a phone gets the short one without a second code path
  // deciding what "phone" means. The long form is the one to read; the short one still says the
  // number, which is the part that decides whether you keep scrolling.
  function renderBell(a) {
    var b = $('bell');
    var say = function (long, short) {
      b.innerHTML = '<span class="wide-only">' + long + '</span><span class="narrow-only">' + short + '</span>';
      b.title = long;
    };
    if (!a || !a.open) {
      b.className = 'bell quiet';
      say('Keine Warnungen', '0');
      return;
    }
    b.className = 'bell' + (a.critical ? ' has' : '');
    say(a.open + (a.critical ? ' offen · ' + a.critical + ' kritisch' : ' offen'),
        a.open + (a.critical ? '!' : ''));
  }

  function alertRow(a, withAck) {
    var when = fmtDate(a.ts);
    var pill = a.severity === 'critical'
      ? '<span class="pill crit">kritisch</span>' : '<span class="pill warn">Hinweis</span>';
    var meta = [pill, when];
    // Whether it actually left the building. A warning that was recorded but never mailed is a
    // very different thing from one that reached you, and only one of them means the alarm works.
    if (a.severity === 'critical') meta.push(a.sentAt ? 'per Mail verschickt' : 'nicht verschickt');
    if (a.walletId) meta.push('<a href="#" data-open="' + esc(a.walletId) + '" class="mono">' + esc(String(a.walletId).slice(0, 8)) + '…</a>');
    if (a.ackAt) meta.push('erledigt von ' + esc(a.ackBy || '—'));

    return '<div class="alert ' + (a.ackAt ? 'done' : esc(a.severity)) + '">' +
      '<div class="a-main"><div class="a-title">' + esc(a.title) + '</div>' +
      '<div class="a-detail">' + esc(a.detail) + '</div>' +
      '<div class="a-meta">' + meta.join('<span class="muted">·</span>') + '</div></div>' +
      (withAck && !a.ackAt ? '<button class="btn ghost" data-ack="' + a.id + '">Erledigt</button>' : '') +
      '</div>';
  }

  function wireAlerts(root) {
    Array.prototype.forEach.call(root.querySelectorAll('[data-ack]'), function (b) {
      b.onclick = function () {
        act({ action: 'ack_alert', id: Number(b.getAttribute('data-ack')) }).then(function () {
          loadAlerts(); load('overview', true);
        });
      };
    });
    Array.prototype.forEach.call(root.querySelectorAll('[data-open]'), function (a) {
      a.onclick = function (e) { e.preventDefault(); openDetail(a.getAttribute('data-open')); };
    });
  }

  function loadAlerts() {
    get('/admin/api/alerts').then(function (r) {
      renderBell(r);
      $('alertList').innerHTML = r.alerts.length
        ? r.alerts.map(function (a) { return alertRow(a, true); }).join('')
        : '<div class="empty">Nichts offen. Der Wachhund läuft alle 15 Minuten.</div>';
      wireAlerts($('alertList'));
    });
    get('/admin/api/alerts?all=1&limit=' + PAGE + '&offset=' + alertOffset).then(function (r) {
      $('alertHistory').innerHTML = r.alerts.length
        ? r.alerts.map(function (a) { return alertRow(a, false); }).join('')
        : '<div class="empty">Noch nie etwas ausgelöst.</div>';
      wireAlerts($('alertHistory'));
      pager($('alertPager'), r, function (nn) { alertOffset = nn; loadAlerts(); });
    });
  }

  /* ----------------------------------------------------------------- Konten */

  function renderWallets(list) {
    var body = document.querySelector('#walletTable tbody');
    $('walletEmpty').hidden = list.length > 0;
    body.innerHTML = list.map(function (w) {
      var pill = w.status === 'deleted' ? '<span class="pill crit">gelöscht</span>'
        : w.status === 'blocked' ? '<span class="pill crit">gesperrt</span>'
        : w.secondsLeft <= 0 ? '<span class="pill warn">leer</span>' : '<span class="pill ok">aktiv</span>';
      if (w.isTest) pill += ' <span class="pill test">' + esc(testLabel(w.testReason)) + '</span>';
      // A deleted account has no "last seen" worth the name — the devices are gone. The date that
      // answers the question you actually have is when it was deleted.
      var when = w.status === 'deleted' ? fmtDate(w.deletedAt) : fmtDate(w.lastSeenAt);
      return '<tr data-id="' + esc(w.id) + '"' + (w.isTest ? ' class="is-test"' : '') + '><td class="mono">' + esc(String(w.id).slice(0, 8)) + '…</td><td>' + pill +
        '</td><td class="num">' + fmtMinutes(w.secondsLeft) + '</td><td class="num">' + fmtMinutes(w.secondsBought) +
        '</td><td class="num">' + fmtMinutes(w.secondsUsed) + '</td><td>' + when +
        '</td><td class="wrap muted">' + esc(w.note || '') + '</td></tr>';
    }).join('');
    Array.prototype.forEach.call(body.querySelectorAll('tr'), function (tr) {
      tr.onclick = function () { openDetail(tr.getAttribute('data-id')); };
    });
  }

  /* ---------------------------------------------------------------- Verkehr */

  /** What kind of test account it is — the reason matters when deciding whether to unmark it. */
  function testLabel(reason) {
    return reason === 'license_tester' ? 'Lizenztester'
      : reason === 'bootstrap' ? 'Testkonto'
      : reason === 'manual' ? 'als Test markiert' : 'Test';
  }

  // 402 out of credit · 403 blocked · 429 too fast · 413 too long · 503 budget spent. All of them
  // are the service saying no on purpose; anything else past 400 is the service failing.
  var REFUSALS = [402, 403, 413, 429, 503];
  function statusPill(status) {
    if (status < 400) return status;
    var cls = REFUSALS.indexOf(status) >= 0 ? 'warn' : 'crit';
    return '<span class="pill ' + cls + '">' + status + '</span>';
  }

  function loadTraffic() {
    var p = '?limit=' + PAGE + '&offset=' + trafficOffset;
    if ($('tKind').value) p += '&kind=' + $('tKind').value;
    if ($('tFail').checked) p += '&failures=1';
    if (!$('tTest').checked) p += '&test=0';
    get('/admin/api/requests' + p).then(function (r) {
      $('traffic').innerHTML = r.requests.length ? '<table><thead><tr><th>Zeit</th><th>Konto</th><th>Gerät</th><th>Art</th><th>Modell</th><th class="num">Sek.</th><th class="num">Tokens</th><th class="num">Kosten</th><th class="num">HTTP</th><th class="num">ms</th></tr></thead><tbody>' +
        r.requests.map(function (x) {
          return '<tr' + (x.isTest ? ' class="is-test"' : '') + '><td>' + fmtDate(x.ts) + '</td><td class="mono">' + esc(String(x.walletId).slice(0, 8)) + '…' +
            (x.isTest ? ' <span class="pill test">Test</span>' : '') + '</td><td class="muted">' + esc(x.device || '—') +
            // A rewording has no audio, so its second count is not zero — it does not exist. Printing
            // 0 invites the reading "this dictation was empty", which is a different thing entirely.
            '</td><td>' + (x.kind === 'transcribe' ? 'Diktat' : 'Umformulierung') +
            // Das Modell, gekürzt; Anbieter und Neuronen im Tooltip. Ein Strich heißt, dass die
            // Zeile aus einer Zeit ohne diese Spalte stammt — nach dem Umzug gibt es davon keine.
            '</td><td>' + modelCell(x) + '</td><td class="num">' + (x.kind === 'transcribe' ? (x.seconds || 0) : '—') +
            '</td><td class="num">' + ((x.tokensIn || 0) + (x.tokensOut || 0) || '—') + '</td><td class="num">' + fmtUsd4((x.costNano || 0) / 1e9) +
            // A refusal is not a fault, and colouring it like one turns every out-of-credit
            // customer into a red line in a list meant to show breakage. Amber says "we said no",
            // red says "we broke".
            '</td><td class="num">' + statusPill(x.status) + '</td><td class="num">' + (x.ms || '—') + '</td></tr>';
        }).join('') + '</tbody></table>' : '<div class="empty">Keine Anfragen für diese Auswahl.</div>';
      pager($('trafficPager'), r, function (n) { trafficOffset = n; loadTraffic(); });
    });
  }
  function loadAudit() {
    get('/admin/api/log?limit=' + PAGE + '&offset=' + auditOffset).then(function (r) {
      $('audit').innerHTML = r.log.length ? '<table><thead><tr><th>Zeit</th><th>Wer</th><th>Aktion</th><th>Konto</th><th class="num">Δ</th><th class="wrap">Begründung</th></tr></thead><tbody>' +
        r.log.map(function (x) {
          return '<tr><td>' + fmtDate(x.ts) + '</td><td>' + esc(x.actor) + '</td><td><span class="pill ' + (x.deltaSecs < 0 ? 'crit' : 'info') + '">' + esc(x.action) +
            '</span></td><td class="mono">' + (x.walletId ? esc(String(x.walletId).slice(0, 8)) + '…' : '—') + '</td><td class="num">' + (x.deltaSecs ? fmtMinutes(x.deltaSecs) : '—') +
            '</td><td class="wrap muted">' + esc(x.note) + '</td></tr>';
        }).join('') + '</tbody></table>' : '<div class="empty">Es wurde noch nichts von Hand geändert.</div>';
      pager($('auditPager'), r, function (n) { auditOffset = n; loadAudit(); });
    });
  }
  function pager(el, r, go) {
    var from = r.total === 0 ? 0 : r.offset + 1, to = Math.min(r.offset + r.limit, r.total);
    el.innerHTML = '<span class="count">' + from + '–' + to + ' von ' + r.total + '</span>' +
      '<button class="btn ghost" ' + (r.offset === 0 ? 'disabled' : '') + ' data-go="p">Zurück</button>' +
      '<button class="btn ghost" ' + (to >= r.total ? 'disabled' : '') + ' data-go="n">Weiter</button>';
    var p = el.querySelector('[data-go="p"]'), n = el.querySelector('[data-go="n"]');
    if (p) p.onclick = function () { go(Math.max(0, r.offset - r.limit)); };
    if (n) n.onclick = function () { go(r.offset + r.limit); };
  }

  /* ---------------------------------------------------------------- Betrieb */

  function renderOps(o) {
    $('view-ops').innerHTML =
      '<div class="panel"><h2>Not-Aus</h2><p class="sub">Solange gestoppt, antwortet jede kostenpflichtige Anfrage mit 503 und die App fällt auf „vorübergehend nicht verfügbar". Bewusst grob: Das Schlimmste, was verhindert werden muss, ist eine unbegrenzte Rechnung — ein verärgerter Tag ist billiger als ein leergeräumtes Konto.</p><div class="row" style="margin-top:12px"><input id="killNote" class="grow" placeholder="Begründung (Pflicht)"><button class="btn ' + (o.budget.killed ? '' : 'danger') + '" id="killBtn">' + (o.budget.killed ? 'Dienst wieder starten' : 'Dienst stoppen') + '</button></div></div>' +
      '<div class="panel" id="alertSettings"></div>' +
      '<div class="panel"><h2>Warnungen prüfen' +
        hint('Ein Alarm, den man nie ausgelöst hat, ist eine Annahme. Diese beiden Knöpfe stoßen genau das an, was sonst der Cron macht — so merkst du eine falsch eingetragene Absenderadresse jetzt und nicht in der Nacht, in der es darauf ankommt.') +
      '</h2><p class="sub">Der Wachhund läuft alle 15 Minuten von selbst, der Tagesbericht einmal täglich. Hier lässt sich beides sofort auslösen. Der Versand geht über Cloudflare Email Routing — dafür muss die Absenderdomain dort zum Versand freigegeben sein.</p>' +
      '<div class="row" style="margin-top:12px"><button class="btn ghost" id="runRules">Regeln jetzt prüfen</button><button class="btn ghost" id="runDigest">Testbericht senden</button></div></div>' +
      '<div class="panel"><h2>Grenzwerte</h2><p class="sub">Tagesbudget, Gerätegrenze und die Schwellenwerte der Warnungen stehen oben und gelten sofort, ohne Ausrollen. Rate-Limits, Modelle und Paketpreise nicht: die stehen in <code>wrangler.jsonc</code>. So bleibt der veröffentlichte Quelltext frei von den Zahlen, gegen die jemand austarieren würde. Was in der Datenbank vom Ausgelieferten abweicht, ist oben mit <span class="pill info chg">geändert</span> markiert.</p><div class="kv" style="margin-top:10px"><dt>Tagesbudget</dt><dd>' + fmtUsd(o.budget.limitUsd) + '</dd><dt>Heute verbraucht</dt><dd>' + fmtUsd4(o.budget.spentUsd) + '</dd><dt>Zustand</dt><dd>' + (o.budget.killed ? 'gestoppt' : 'läuft') + '</dd></div></div>';

    loadSettings();

    $('runRules').onclick = function () {
      $('runRules').disabled = true;
      act({ action: 'test_rules' }).then(function (r) {
        $('runRules').disabled = false;
        tell('Regeln geprüft', r.message);
        loadAlerts();
      });
    };
    $('runDigest').onclick = function () {
      $('runDigest').disabled = true;
      act({ action: 'test_digest' }).then(function (r) {
        $('runDigest').disabled = false;
        tell(r.ok ? 'Verschickt' : 'Nicht verschickt', r.message);
      });
    };

    $('killBtn').onclick = function () {
      var note = $('killNote').value;
      if (!note.trim()) { tell('Begründung fehlt', 'Jede Aktion braucht eine Begründung — sie landet im Protokoll.'); return; }
      var stopping = !o.budget.killed;
      modal({
        title: stopping ? 'Dienst für alle stoppen?' : 'Dienst wieder starten?',
        text: stopping
          ? 'Jede kostenpflichtige Anfrage antwortet danach mit <strong>503</strong>. Laufende Nutzer sehen „vorübergehend nicht verfügbar". Guthaben bleibt unangetastet.'
          : 'Anfragen werden wieder normal bedient.',
        okLabel: stopping ? 'Stoppen' : 'Starten', danger: stopping,
      }).then(function (yes) {
        if (!yes) return;
        act({ action: stopping ? 'kill_on' : 'kill_off', note: note }).then(function (r) {
          if (!r.ok) { tell('Nicht ausgeführt', r.message); return; }
          load('overview', true); loadAlerts();
        });
      });
    };
  }

  /* ------------------------------------------------- Einstellungen Warnungen */

  var RULE_TEXT = {
    fast_burn: ['Guthaben rasant verbraucht', 'Der Vorlauf zur Rückbuchung — kaufen, verbrauchen, Geld zurückholen.'],
    budget_hog: ['Ein Konto frisst das Tagesbudget', 'Kein Verlust, aber alle anderen bekommen dann 503.'],
    shared_token: ['Zugang weitergegeben', 'Viele Geräte an einem Guthaben.'],
    overall_loss: ['Insgesamt im Minus', 'Alles jemals eingenommen gegen alles jemals ausgegeben.'],
    error_rate: ['Viele Fehler', 'Meist eine Störung bei Workers AI.'],
    revenue_unreported: ['Erlös nicht gemeldet', 'Ein bezahlter Kauf steht seit einer Woche ohne Erlös in den Büchern.'],
    neuron_spike: ['Neuronen-Ausschlag', 'Ein Tag, der nicht zur Woche davor passt — die Zahl, die direkt zur Rechnung wird.'],
    reasoning_leak: ['Das Modell denkt wieder', 'Denk-Token gehen vom Guthaben des Käufers ab, ohne dass er etwas davon bekommt.'],
    invoice_missing: ['Cloudflare-Rechnung fehlt', 'Ein abgeschlossener Monat ohne erfasste Rechnung — die einzige Prüfung gegen echtes Geld bleibt sonst aus.'],
    slow_upstream: ['Kurze Diktate werden langsam', 'Gemessen nur an Aufnahmen bis 30 s — die schwanken nicht, lange schon. Das ist die Zahl, die Nutzende spüren.'],
  };

  var NUMBERS = [
    ['fastBurnPercent', 'Schnellverbrauch ab', '%', 'Anteil eines frischen Pakets, der als auffällig gilt.'],
    ['fastBurnHours', 'innerhalb von', 'Std.', 'Zeitfenster dafür.'],
    ['refundUsedPercent', 'Erstattung meldet ab', '%', 'Wie viel verbraucht sein muss, damit eine Erstattung als Verlust gilt.'],
    ['walletBudgetSharePercent', 'Konto-Anteil am Budget', '%', 'Ab welchem Anteil des Tagesbudgets ein einzelnes Konto auffällt.'],
    ['devicesPerWallet', 'Geräte je Konto', 'Stk.', 'Mehr als so viele an einem Tag deuten auf Weitergabe.'],
    ['errorRatePercent', 'Fehlerquote', '%', 'Anteil fehlgeschlagener Anfragen je Stunde.'],
    ['neuronSpikeFactor', 'Neuronen-Ausschlag ab', '×', 'Wie oft der Wochenschnitt an einem Tag überschritten sein muss.'],
    ['slowShortMs', 'Kurze Diktate langsam ab', 'ms', 'p95 der Aufnahmen bis 30 s Audio, über eine Stunde. Normal sind 2 000 bis 4 000 ms; lange Aufnahmen zählen bewusst nicht mit.'],
    ['minLossHome', 'Minus meldet ab', '€', 'Darunter ist es Rundung oder Anlaufphase.'],
  ];

  function renderSettings(d) {
    var s = d.settings;
    var changed = {};
    (d.changed || []).forEach(function (k) { changed[k] = true; });
    var mark = function (k) {
      return changed[k] ? ' <span class="pill info chg" title="Weicht von dem ab, was das Deployment mitbringt">geändert</span>' : '';
    };
    var check = function (id, on, label, tip) {
      return '<label class="row" style="gap:8px;align-items:flex-start"><input type="checkbox" id="' + id + '"' +
        (on ? ' checked' : '') + ' style="width:auto;margin-top:3px">' +
        '<span><strong>' + esc(label) + '</strong>' + (tip ? '<br><span class="sub" style="margin:0">' + esc(tip) + '</span>' : '') + '</span></label>';
    };

    // The daily ceiling stands apart from everything below it: the rest decides what you are told,
    // this one decides what the service does. It sits first, with the day's spend beside it, so
    // moving it is an informed act rather than a number typed into a blank box.
    var spent = current && current.budget ? current.budget.spentUsd : null;
    var html = '<h2>Betriebsgrenze' +
      hint('Die Sicherung des Dienstes: Sobald die geschätzten Einkaufskosten eines Tages diesen Betrag erreichen, antwortet der Dienst mit 503, statt weiter einzukaufen. Ein Tag kann dich also nie mehr kosten als das — egal, was sonst schiefgeht. Höher setzen heißt mehr Umsatz möglich und mehr Schaden möglich; niedriger heißt früher Feierabend für alle. Wirkt binnen einer Minute, ohne Deployment.') +
      '</h2>' +
      '<div class="acts" style="margin-bottom:20px"><div class="act">' +
        '<div class="label">Tagesbudget' + mark('dailyBudgetUsd') + '</div>' +
        '<div class="row"><input id="set_dailyBudgetUsd" type="number" step="1" min="1" value="' +
          esc(String(s.dailyBudgetUsd)) + '" class="grow"><span class="muted">$ / Tag</span></div>' +
        '<p class="sub" style="margin:0">' +
          (spent !== null ? 'Heute verbraucht: ' + fmtUsd4(spent) + '. ' : '') +
          'Null ist keine gültige Eingabe — zum vollständigen Anhalten gibt es den Not-Aus.</p>' +
      '</div>' +
      '<div class="act">' +
        '<div class="label">Geräte je Konto' +
        hint('Wie viele Geräte gleichzeitig einen gültigen Zugang zu einem Guthaben halten dürfen. Handy, Uhr und Tablet sind der ehrliche Fall; darüber hinaus ist ein Wiederherstellungscode kein Konto mehr, sondern ein geteiltes Passwort. Wird die Grenze erreicht, weist der Server die Wiederherstellung ab und die App bietet an, ein Gerät abzumelden — nicht automatisch das älteste, denn das würde bei einem weitergegebenen Code den rechtmäßigen Besitzer verdrängen.') +
        mark('maxDevices') + '</div>' +
        '<div class="row"><input id="set_maxDevices" type="number" step="1" min="1" value="' +
          esc(String(s.maxDevices)) + '" class="grow"><span class="muted">gleichzeitig</span></div>' +
        '<p class="sub" style="margin:0">Bereits angemeldete Geräte bleiben angemeldet, auch über der Grenze.</p>' +
      '</div></div>';

    html += '<h2>Warnungen einstellen' +
      hint('Was hier steht, überschreibt die Werte aus wrangler.jsonc. Was du nicht anfasst, kommt weiter aus der Auslieferung — eine leere Tabelle verhält sich also genau wie eine eingestellte. Änderungen wirken binnen einer Minute auf allen Servern.') +
      '</h2>' +
      '<div class="stack" style="gap:14px">' +
        check('setEnabled', s.enabled, 'Wachhund aktiv', 'Aus heißt: Die Regeln laufen gar nicht. Nichts wird geprüft, nichts aufgezeichnet, nichts verschickt.') +
        check('setMail', s.mail, 'E-Mails verschicken', 'Aus heißt: Alles wird weiter aufgezeichnet und steht hier im Dashboard — es geht nur keine Mail raus.') +
        check('setDigest', s.digest, 'Täglicher Bericht', 'Kommt auch, wenn nichts passiert ist. Genau das ist der Sinn: Ein Bericht, der nur bei schlechten Nachrichten kommt, ist von einem ausgefallenen nicht zu unterscheiden.') +
      '</div>';

    html += '<h3 style="margin-top:20px">Einzelne Regeln</h3><div class="acts">' +
      Object.keys(RULE_TEXT).map(function (k) {
        return '<div class="act">' + check('rule_' + k, s.rules[k] !== false, RULE_TEXT[k][0], RULE_TEXT[k][1]) + '</div>';
      }).join('') + '</div>';

    html += '<h3 style="margin-top:20px">Schwellenwerte</h3><div class="grid">' +
      NUMBERS.map(function (f) {
        return '<div class="act"><div class="label">' + esc(f[1]) + hint(f[3]) + mark(f[0]) + '</div>' +
          '<div class="row"><input id="set_' + f[0] + '" type="number" step="0.1" value="' + esc(String(s[f[0]])) + '" class="grow">' +
          '<span class="muted">' + esc(f[2]) + '</span></div></div>';
      }).join('') +
      '<div class="act"><div class="label">Budgetstufen' + hint('Bei welchen Prozentsätzen des Tagesbudgets gemeldet wird. Kommagetrennt. Ab 80 % gilt es als kritisch und geht sofort raus.') + mark('budgetSteps') + '</div>' +
        '<input id="set_budgetSteps" value="' + esc(s.budgetSteps.join(',')) + '"></div>' +
      '<div class="act"><div class="label">Bericht um (UTC)' + hint('5 UTC ist 6 Uhr im Winter, 7 Uhr im Sommer.') + mark('digestHourUtc') + '</div>' +
        '<input id="set_digestHourUtc" type="number" min="0" max="23" value="' + esc(String(s.digestHourUtc)) + '"></div>' +
    '</div>';

    html += '<h3 style="margin-top:20px">Adressen</h3><div class="grid">' +
      '<div class="act"><div class="label">Empfänger' + mark('emailTo') + '</div>' +
        '<input id="set_emailTo" value="' + esc(s.emailTo) + '"></div>' +
      '<div class="act"><div class="label">Absender' + mark('emailFrom') + '</div>' +
        '<input id="set_emailFrom" value="' + esc(s.emailFrom) + '"></div>' +
    '</div>';

    // The one thing this page genuinely cannot change, said plainly rather than discovered later
    // through a bounce.
    if (d.pinnedRecipient) {
      html += '<p class="sub" style="margin-top:10px">Der Versand ist im Deployment auf <span class="mono">' +
        esc(d.pinnedRecipient) + '</span> festgenagelt (<code>destination_address</code> in wrangler.jsonc). ' +
        'Eine andere Empfängeradresse hier wird abgelehnt, bis sie dort ebenfalls eingetragen und bei ' +
        'Cloudflare als Zieladresse bestätigt ist. Das ist Absicht: Diese Beschränkung hält den Versand ' +
        'kostenlos und sorgt dafür, dass der Dienst selbst bei einem Fehler niemandem sonst schreiben kann.</p>';
    }
    // Not a setting, but it belongs on the page that gets looked at after a deploy: it is the one
    // fact behind a sentence in the privacy policy, and it fails silently.
    html += '<h3 style="margin-top:20px">Speicherort der Kontostände</h3>' +
      (d.doPlacement === 'eu'
        ? '<p class="sub" style="margin:0"><span class="pill ok">EU</span> Die Durable Objects werden auf die EU festgelegt. Damit stimmt die Aussage, dass die Daten in der EU gespeichert werden — der Drittlandtransfer an Cloudflare selbst bleibt davon unberührt und wird von den Standardvertragsklauseln getragen.</p>'
        : '<p class="sub" style="margin:0"><span class="pill warn">unbeschränkt</span> Die Jurisdiktion greift hier nicht — Objekte entstehen dort, wo die erste Anfrage ankommt. Lokal ist das normal, in der Produktion nicht: Dann wäre der Satz „gespeichert in der EU" in der Datenschutzerklärung falsch.</p>');

    if (!d.mailBound) {
      html += '<p class="sub" style="color:var(--warn);margin-top:10px">Keine Mail-Bindung im Deployment — ' +
        'Warnungen werden aufgezeichnet, verlassen aber das Haus nicht.</p>';
    }

    html += '<div class="row" style="margin-top:16px"><button class="btn" id="setSave">Speichern</button>' +
      '<button class="btn ghost" id="setReset">Auf Auslieferungswerte zurücksetzen</button></div>';

    $('alertSettings').innerHTML = html;

    $('setSave').onclick = function () {
      var payload = { action: 'save_settings', note: 'über das Dashboard geändert' };
      payload.enabled = $('setEnabled').checked ? '1' : '0';
      payload.mail = $('setMail').checked ? '1' : '0';
      payload.digest = $('setDigest').checked ? '1' : '0';
      Object.keys(RULE_TEXT).forEach(function (k) {
        payload['rule.' + k] = $('rule_' + k).checked ? '1' : '0';
      });
      NUMBERS.forEach(function (f) { payload[f[0]] = $('set_' + f[0]).value; });
      payload.budgetSteps = $('set_budgetSteps').value;
      payload.digestHourUtc = $('set_digestHourUtc').value;
      payload.emailTo = $('set_emailTo').value;
      payload.emailFrom = $('set_emailFrom').value;
      payload.dailyBudgetUsd = $('set_dailyBudgetUsd').value;
      payload.maxDevices = $('set_maxDevices').value;

      var save = function () {
        act(payload).then(function (r) { tell(r.ok ? 'Gespeichert' : 'Nicht gespeichert', r.message).then(loadSettings); });
      };

      // Raising the ceiling is the one change on this page that can cost money, and a stray zero
      // is the way it would happen. Lowering it needs no ceremony — the worst it can do is stop
      // the service early, which is visible immediately and reversible in a minute.
      var now = Number(s.dailyBudgetUsd), next = Number(payload.dailyBudgetUsd);
      if (!(next > now)) { save(); return; }
      modal({
        title: 'Tagesbudget anheben?',
        text: 'Von <strong>' + esc(fmtUsd(now)) + '</strong> auf <strong>' + esc(fmtUsd(next)) +
          '</strong> je Tag. Das ist die Obergrenze dessen, was ein einzelner Tag dich an Rechenzeit ' +
          'kosten kann — und damit auch die Obergrenze des Schadens, wenn etwas schiefgeht.',
        okLabel: 'Anheben und speichern',
        danger: next > now * 3,
      }).then(function (y) { if (y) save(); });
    };
    $('setReset').onclick = function () {
      modal({
        title: 'Auf Auslieferungswerte zurücksetzen?',
        text: 'Alle hier vorgenommenen Änderungen werden verworfen. Danach gelten wieder die Werte aus <code>wrangler.jsonc</code> — der Wachhund läuft dann in jedem Fall wieder.',
        okLabel: 'Zurücksetzen', danger: true,
      }).then(function (y) {
        if (y) act({ action: 'reset_settings', note: 'zurückgesetzt' }).then(function (r) { tell('Zurückgesetzt', r.message).then(loadSettings); });
      });
    };
  }

  function loadSettings() {
    $('alertSettings').innerHTML = '<h2>Warnungen einstellen</h2>' + skeleton('line', 5);
    return get('/admin/api/settings').then(renderSettings).catch(function (e) {
      $('alertSettings').innerHTML = '<div class="empty">Einstellungen nicht ladbar: ' + esc(e.message) + '</div>';
    });
  }

  /* ----------------------------------------------------------- Konto-Detail */

  function openDetail(id) {
    get('/admin/api/wallet/' + encodeURIComponent(id)).then(function (d) {
      var w = d.wallet, live = d.live || {};
      var left = live.secondsLeft !== undefined ? live.secondsLeft : w.secondsLeft;
      var blocked = w.status === 'blocked';
      var deleted = w.status === 'deleted';
      $('dTitle').textContent = w.id;

      var statePill = deleted ? '<span class="pill crit">gelöscht</span>'
        : blocked ? '<span class="pill crit">gesperrt</span>'
        : '<span class="pill ok">aktiv</span>';
      var stateSub = deleted
        ? 'am ' + fmtDate(w.deletedAt) + '<br>nur noch wegen der Kaufbelege da'
        : d.devices.length + ' Gerät(e)' + (w.isTest ? '<br>aus allen Geldzahlen ausgenommen' : '');

      var html = '<div class="grid">' +
        card('Guthaben', 'Direkt aus dem Durable Object gelesen, also der maßgebliche Wert — die Spalten in der Liste sind eine Kopie und dürfen Sekunden hinterherhinken. Sekunden sind die einzige Größe; die Umformulierungen darunter sind daraus abgeleitet und keine zweite Währung.', fmtMinutes(left), 'reicht für ~' + (live.rewordsLeft !== undefined ? live.rewordsLeft : w.rewordsLeft) + ' Umformulierungen') +
        card('Gekauft', 'Summe aller jemals gutgeschriebenen Minuten.', fmtMinutes(w.secondsBought), 'Konto seit ' + fmtDate(w.createdAt)) +
        card('Verbraucht', 'Summe aller abgerechneten Diktatsekunden.', fmtMinutes(w.secondsUsed), 'Zuletzt ' + fmtDate(w.lastSeenAt)) +
        card('Zustand', 'Ein gesperrtes Konto wird bei jeder Anfrage abgewiesen, behält aber sein Guthaben. Ein gelöschtes ist endgültig weg — die Zeile bleibt nur, weil die Kaufbelege darauf zeigen und zehn Jahre aufzubewahren sind.',
          statePill + (w.isTest ? ' <span class="pill test">' + esc(testLabel(w.testReason)) + '</span>' : ''),
          stateSub) +
        '</div>';

      // The question this answers before it is asked: no, the recovery code cannot be shown. Only
      // its SHA-256 is stored, which is the point of storing it that way — whoever obtains the
      // database cannot sign in with it, and that has to include the operator. Said here rather
      // than left to be discovered, because the absence of a field reads as an oversight.
      if (!deleted) {
        html += '<div class="empty" style="text-align:left">' +
          '<strong>Der Wiederherstellungscode lässt sich nicht anzeigen.</strong> Gespeichert ist nur sein ' +
          'SHA-256-Abzug — genau darin besteht der Schutz: Wer die Datenbank in die Hände bekommt, kann ' +
          'sich damit nicht anmelden, und das schließt dich ein. Zwei Dinge sind trotzdem möglich: ' +
          'einen genannten Code oben in der Suche eingeben, um zu <em>prüfen</em>, ob er zu diesem Konto ' +
          'gehört — und unter <em>Wiederherstellung</em> einen neuen ausgeben, der genau einmal ' +
          'angezeigt wird.</div>';
      }

      // Nothing here can be done to a deleted account: it has no devices to sign out, no code to
      // reissue, and credit given to it would sit in a wallet nobody can reach. Showing the
      // buttons anyway would be an offer that quietly does nothing.
      if (deleted) {
        html += '<div class="empty">Dieses Konto ist gelöscht. Wiederherstellungscode, Zugänge, Geräte und ' +
          'Nutzungsprotokoll sind entfernt; es gibt daran nichts mehr einzustellen. Was unten steht, sind die ' +
          'Kaufbelege — sie bleiben nach § 147 AO zehn Jahre erhalten.</div>';
      }

      // Warnings this account has already produced. On the account page rather than only in the
      // list, because the question "has this one done that before" is the one that decides whether
      // a second fast burn is coincidence.
      if (d.alerts && d.alerts.length) {
        html += '<div><h3>Warnungen zu diesem Konto</h3><div class="stack" style="gap:10px">' +
          d.alerts.map(function (a) { return alertRow(a, false); }).join('') + '</div></div>';
      }

      if (!deleted) html += '<div><h3>Aktionen</h3>' +
        '<div class="row" style="margin-bottom:12px"><input id="aNote" class="grow" placeholder="Begründung — Pflicht, landet im Protokoll"></div>' +
        '<div class="acts">' +
          '<div class="act"><div class="label">Guthaben' + hint('Positive Zahl schreibt gut, negative zieht ab. Ein Abzug darf ins Minus gehen — sonst könnte jemand kaufen, verbrauchen, erstatten lassen und bei null neu anfangen.') + '</div>' +
            '<input id="aMinutes" type="number" step="1" placeholder="Minuten, z. B. 30 oder −15">' +
            '<div class="spacer"></div><button class="btn" id="bGift">Guthaben anpassen</button></div>' +
          '<div class="act"><div class="label">Wiederherstellung' + hint('Erzeugt einen neuen Code und zeigt ihn genau einmal an. Der alte gilt sofort nicht mehr — gedacht für den Fall, dass jemand seinen Code offengelegt hat.') + '</div>' +
            '<p class="sub" style="margin:0">Der alte Code wird sofort ungültig.</p>' +
            '<div class="spacer"></div><button class="btn ghost" id="bCode">Neuen Code ausgeben</button></div>' +
          '<div class="act"><div class="label">Notiz' + hint('Freitext für alles, was das Protokoll nicht hergibt. Erscheint in der Kontenliste.') + '</div>' +
            '<input id="aNoteText" value="' + esc(w.note || '') + '" placeholder="Interne Notiz">' +
            '<div class="spacer"></div><button class="btn ghost" id="bNote">Notiz speichern</button></div>' +
          '<div class="act"><div class="label">Einordnung' + hint('Play-Lizenztester erkennt der Server von selbst an Googles Kaufart. Für alles andere — ein Demogerät, ein Konto aus einem Test über ein fremdes Handy — ist das hier der Schalter. Markierte Konten fallen aus jeder Umsatz-, Kosten- und Nutzungszahl heraus.') + '</div>' +
            '<p class="sub" style="margin:0">' + (w.isTest ? 'Gilt als <strong>' + esc(testLabel(w.testReason)) + '</strong> und ist aus allen Zahlen heraus.' : 'Zählt als echtes Kundenkonto.') + '</p>' +
            '<div class="spacer"></div><button class="btn ghost" id="bTest">' + (w.isTest ? 'Als echtes Konto zählen' : 'Als Testkonto markieren') + '</button></div>' +
          '<div class="act danger-zone"><div class="label">Eingriffe' + hint('Sperren hält alle Geräte dieses Kontos an, ohne Guthaben zu vernichten. Zusammenführen verschiebt alles in ein anderes Konto und leert dieses — der häufigste Fall nach einem Gerätewechsel.') + '</div>' +
            '<button class="btn ' + (blocked ? 'ghost' : 'danger') + '" id="bBlock">' + (blocked ? 'Konto entsperren' : 'Konto sperren') + '</button>' +
            '<input id="aTarget" placeholder="Ziel-Wallet-ID">' +
            '<button class="btn ghost" id="bMerge">In dieses Konto zusammenführen</button></div>' +
        '</div></div>';

      var homeCur = sum ? sum.homeCurrency : 'EUR';
      html += sect('Käufe', d.purchases.length ? '<table><thead><tr><th>Datum</th><th>Paket</th><th>Bestellnummer</th><th class="num">Minuten</th>' +
        '<th class="num">Gezahlt' + hint('Was die Kundschaft tatsächlich gezahlt hat, in ihrer Währung und inklusive der Steuer ihres Landes — von Google gemeldet. Wo Google keine Bestellung herausgab, steht ersatzweise der Listenpreis.') + '</th>' +
        '<th class="num">Dein Erlös' + hint('Von Google gemeldet, nicht gerechnet — und erst, wenn die Zahlung abgerechnet ist. Bis dahin steht hier „noch nicht gemeldet" samt Schätzung: Brutto minus Steuer minus Googles Anteil. Die Schätzung zählt in keiner Summe mit.') + '</th>' +
        '<th>Region</th><th>Zustand</th><th></th></tr></thead><tbody>' +
        d.purchases.map(function (p) {
          var paid = p.paidMicros != null && p.currency
            ? money(p.paidMicros / 1e6, p.currency)
            : '<span class="muted">' + money(p.priceEur, 'EUR') + ' (Liste)</span>';
          // Drei verschiedene Aussagen, die früher alle wie „0,00" aussahen: gemeldet, noch nicht
          // gemeldet (mit Schätzung), und gar keine Bestellung.
          var rev;
          if (p.revenueHomeMicros != null) rev = money(p.revenueHomeMicros / 1e6, homeCur);
          else if (p.revenueMicros != null && p.currency) rev = money(p.revenueMicros / 1e6, p.currency);
          else if (p.orderId) {
            rev = '<span class="muted">noch nicht gemeldet</span>' +
              (p.estimatedHomeMicros != null
                ? '<br><span class="muted">≈ ' + money(p.estimatedHomeMicros / 1e6, homeCur) + '</span>' : '');
          } else rev = '—';

          var asked = p.orderSyncedAt
            ? 'Zuletzt gefragt: ' + fmtDate(p.orderSyncedAt) +
              (p.orderState ? ' · Bestellzustand ' + esc(p.orderState) : '') +
              ' · ' + (p.orderAttempts || 0) + ' Versuch(e)'
            : 'Noch nie nachgefragt.';

          return '<tr><td>' + fmtDate(p.purchasedAt) + '</td><td class="mono">' + esc(p.productId) +
            (p.purchaseType === 0 ? ' <span class="pill test">Lizenztester</span>' : '') +
            '</td><td class="mono">' + esc(p.orderId || '—') +
            '</td><td class="num">' + Math.round(p.seconds / 60) + '</td><td class="num">' + paid +
            '</td><td class="num" title="' + esc(asked) + '">' + rev + '</td><td>' + esc(p.regionCode || '—') +
            '</td><td>' + (p.state === 'voided' ? '<span class="pill crit">erstattet</span>' : '<span class="pill ok">gutgeschrieben</span>') +
            '</td><td>' + (p.orderId
              ? '<button class="btn ghost" data-refetch="' + esc(p.purchaseToken) + '" style="padding:4px 8px;font-size:12px">neu abfragen</button>'
              : '') + '</td></tr>';
        }).join('') + '</tbody></table>' : null, 'Noch keine Käufe.');

      html += sect('Geräte', d.devices.length ? '<table><thead><tr><th>Gerät</th><th>Hinzugefügt</th><th>Zuletzt gesehen</th><th></th></tr></thead><tbody>' +
        d.devices.map(function (t) {
          return '<tr><td>' + esc(t.label || 'ohne Namen') + (t.revokedAt ? ' <span class="pill crit">abgemeldet</span>' : '') + '</td><td>' + fmtDate(t.createdAt) +
            '</td><td>' + fmtDate(t.lastSeenAt) + '</td><td>' + (t.revokedAt ? '' : '<button class="btn ghost" data-revoke="' + esc(t.tokenHash) + '">Abmelden</button>') + '</td></tr>';
        }).join('') + '</tbody></table>' : null, 'Keine Geräte.');

      html += sect('Verbrauch je Tag', d.daily.length ? '<table><thead><tr><th>Tag</th><th class="num">Diktat</th><th class="num">Umformul.</th><th class="num">Anfragen</th><th class="num">Kosten</th></tr></thead><tbody>' +
        d.daily.map(function (r) {
          return '<tr><td>' + esc(r.day) + '</td><td class="num">' + fmtMinutes(r.dictationSeconds) + '</td><td class="num">' + r.rewords + '</td><td class="num">' + r.requests + '</td><td class="num">' + fmtUsd4((r.costNano || 0) / 1e9) + '</td></tr>';
        }).join('') + '</tbody></table>' : null, 'Noch nichts verbraucht.');

      if (d.errors.length) html += sect('Letzte Fehler', '<table><thead><tr><th>Zeit</th><th>Art</th><th class="num">HTTP</th><th class="num">ms</th></tr></thead><tbody>' +
        d.errors.map(function (e) { return '<tr><td>' + fmtDate(e.ts) + '</td><td>' + esc(e.kind) + '</td><td class="num"><span class="pill crit">' + e.status + '</span></td><td class="num">' + (e.ms || '—') + '</td></tr>'; }).join('') + '</tbody></table>', null);

      if (d.adminLog.length) html += sect('An diesem Konto geändert', '<table><thead><tr><th>Zeit</th><th>Wer</th><th>Aktion</th><th class="num">Δ</th><th class="wrap">Begründung</th></tr></thead><tbody>' +
        d.adminLog.map(function (r) { return '<tr><td>' + fmtDate(r.ts) + '</td><td>' + esc(r.actor) + '</td><td>' + esc(r.action) + '</td><td class="num">' + (r.deltaSecs ? fmtMinutes(r.deltaSecs) : '—') + '</td><td class="wrap muted">' + esc(r.note) + '</td></tr>'; }).join('') + '</tbody></table>', null);

      $('dBody').innerHTML = html;
      wireDetail(w);
      if (!$('detail').open) $('detail').showModal();
    }).catch(function (e) {
      // Without this the dialog simply never appeared and the page looked idle: the failure was a
      // rejected promise nobody was listening to. Show the reason in the dialog instead — an account
      // that cannot be opened is something to see, not something to guess at.
      $('dTitle').textContent = id;
      $('dBody').innerHTML = '<div class="empty">Konto nicht ladbar: ' + esc(e.message) + '</div>';
      if (!$('detail').open) $('detail').showModal();
    });
  }
  function sect(title, table, empty) {
    return '<div><h3>' + esc(title) + '</h3>' + (table ? '<div class="scroll">' + table + '</div>' : '<div class="empty">' + esc(empty) + '</div>') + '</div>';
  }

  function wireDetail(w) {
    // A deleted account draws no action panel, so there is nothing here to attach to.
    if (w.status === 'deleted') return;
    function note() { return $('aNote').value || ''; }
    function need() {
      if (note().trim()) return true;
      tell('Begründung fehlt', 'Jede Änderung braucht eine Begründung — sie landet mit deiner Adresse im Protokoll.');
      return false;
    }
    function run(payload) {
      act(payload).then(function (r) {
        if (!r.ok) { tell('Nicht ausgeführt', r.message); return; }
        var after = function () { openDetail(w.id); loadAll(); };
        if (r.code) modal({ title: 'Neuer Wiederherstellungscode', text: 'Wird <strong>nur dieses eine Mal</strong> angezeigt. Der alte Code gilt ab sofort nicht mehr.', code: r.code, cancel: false, okLabel: 'Notiert' }).then(after);
        else { tell('Erledigt', r.message).then(after); }
      });
    }
    $('bGift').onclick = function () {
      var m = Number($('aMinutes').value);
      if (!m) { tell('Keine Zahl', 'Bitte eine Minutenzahl angeben — negativ zieht ab.'); return; }
      if (!need()) return;
      modal({ title: m < 0 ? 'Guthaben abziehen?' : 'Guthaben gutschreiben?',
        text: m < 0 ? 'Dem Konto werden <strong>' + Math.abs(m) + ' Minuten</strong> abgezogen. Das darf ins Minus gehen.'
                    : 'Dem Konto werden <strong>' + m + ' Minuten</strong> gutgeschrieben.',
        okLabel: m < 0 ? 'Abziehen' : 'Gutschreiben', danger: m < 0 })
        .then(function (y) { if (y) run({ action: 'gift', walletId: w.id, minutes: m, note: note() }); });
    };
    $('bBlock').onclick = function () {
      if (!need()) return;
      var blocked = w.status === 'blocked';
      modal({ title: blocked ? 'Konto entsperren?' : 'Konto sperren?',
        text: blocked ? 'Alle Geräte dieses Kontos können wieder diktieren.'
          : 'Alle Geräte dieses Kontos werden ab sofort abgewiesen. Das <strong>Guthaben bleibt erhalten</strong> und ist nach dem Entsperren wieder nutzbar.',
        okLabel: blocked ? 'Entsperren' : 'Sperren', danger: !blocked })
        .then(function (y) { if (y) run({ action: blocked ? 'unblock' : 'block', walletId: w.id, note: note() }); });
    };
    $('bCode').onclick = function () {
      if (!need()) return;
      modal({ title: 'Neuen Code ausgeben?', text: 'Der bisherige Wiederherstellungscode funktioniert danach <strong>nicht mehr</strong>. Wer ihn aufgeschrieben hat, kommt damit nicht mehr an das Guthaben.', okLabel: 'Neu ausgeben', danger: true })
        .then(function (y) { if (y) run({ action: 'recovery_reset', walletId: w.id, note: note() }); });
    };
    $('bMerge').onclick = function () {
      var t = $('aTarget').value.trim();
      if (!t) { tell('Ziel fehlt', 'Bitte die Wallet-ID des Zielkontos angeben.'); return; }
      if (!need()) return;
      modal({ title: 'Konten zusammenführen?', text: 'Das gesamte Guthaben wandert nach <span class="mono">' + esc(t) + '</span>. Dieses Konto wird geleert und gesperrt, Geräte und Käufe ziehen mit um.', okLabel: 'Zusammenführen', danger: true })
        .then(function (y) { if (y) run({ action: 'merge', walletId: w.id, targetId: t, note: note() }); });
    };
    $('bNote').onclick = function () { run({ action: 'note', walletId: w.id, note: $('aNoteText').value }); };
    $('bTest').onclick = function () {
      if (!need()) return;
      var marking = !w.isTest;
      modal({
        title: marking ? 'Als Testkonto markieren?' : 'Wieder als echtes Konto zählen?',
        text: marking
          ? 'Das Konto verschwindet danach aus allen Umsatz-, Kosten- und Nutzungszahlen. Guthaben und Funktion bleiben unverändert — es ist nur eine Einordnung für die Auswertung.'
          : 'Das Konto zählt danach wieder in allen Zahlen mit.',
        okLabel: marking ? 'Markieren' : 'Aufheben',
      }).then(function (y) {
        if (y) run({ action: marking ? 'mark_test' : 'unmark_test', walletId: w.id, note: note() });
      });
    };
    // Kein Grund, keine Rückfrage: hier wird nichts entschieden, sondern eine fremde Zahl geholt.
    // Und „Google meldet weiterhin nichts" ist eine Auskunft, kein Fehlschlag — deshalb nicht über
    // run(), das jede Antwort mit ok:false als „Nicht ausgeführt" überschreiben würde.
    Array.prototype.forEach.call($('dBody').querySelectorAll('[data-refetch]'), function (b) {
      b.onclick = function () {
        b.disabled = true;
        act({ action: 'refetch_order', walletId: w.id, purchaseToken: b.getAttribute('data-refetch') })
          .then(function (r) {
            b.disabled = false;
            tell(r.ok ? 'Nachgetragen' : 'Googles Antwort', r.message)
              .then(function () { openDetail(w.id); loadAll(); });
          }, function () { b.disabled = false; });
      };
    });
    Array.prototype.forEach.call($('dBody').querySelectorAll('[data-revoke]'), function (b) {
      b.onclick = function () {
        if (!need()) return;
        modal({ title: 'Gerät abmelden?', text: 'Nur dieses Gerät verliert den Zugang. Die anderen bleiben angemeldet, das Guthaben bleibt unberührt.', okLabel: 'Abmelden', danger: true })
          .then(function (y) { if (y) run({ action: 'revoke_token', walletId: w.id, tokenHash: b.getAttribute('data-revoke'), note: note() }); });
      };
    });
  }

  /* -------------------------------------------------------------- Statistik */

  var histDays = [], histMonths = [];

  function money(v, cur) { return n(v).toFixed(2) + ' ' + (cur || ''); }

  function renderFinance(f) {
    var html = '<div class="grid" style="margin-bottom:16px">' + moneyCards(sum) + '</div>';
    var p = f.play;
    var cur = p.homeCurrency;
    if (p.byCurrency.length) {
      html += '<div class="scroll"><table><thead><tr><th>Währung</th><th class="num">Käufe</th>' +
        '<th class="num">Gezahlt<span class="hint" tabindex="0" data-tip="Was die Kundschaft insgesamt bezahlt hat, in ihrer eigenen Währung und inklusive der Steuer ihres Landes. Google zieht die Steuer ein und führt sie ab — bei dir kommt sie nie an."></span></th>' +
        '<th class="num">davon Steuer</th>' +
        '<th class="num">Dein Erlös<span class="hint" tabindex="0" data-tip="Was nach Googles Anteil und Steuern bei dir ankommt — die einzige Zahl, die wirklich Einnahme ist. Von Google gemeldet, nicht gerechnet."></span></th>' +
        '<th class="num">in ' + esc(cur) + '<span class="hint" tabindex="0" data-tip="Mit dem EZB-Kurs des jeweiligen Kauftags umgerechnet und fest auf die Buchung geschrieben. Googles Auszahlung rechnet mit Googles eigenem Kurs — die Zahl ist also eine gute Näherung, nicht der Kontoauszug."></span></th>' +
        '</tr></thead><tbody>' +
        p.byCurrency.map(function (c) {
          return '<tr><td class="mono">' + esc(c.currency) + '</td><td class="num">' + c.orders +
            (c.unreported ? '<br><span class="muted" style="font-size:12px">' + c.unreported + ' offen</span>' : '') +
            '</td><td class="num">' + money(c.paid, c.currency) + '</td><td class="num muted">' + money(c.tax, c.currency) +
            '</td><td class="num">' + money(c.revenue, c.currency) +
            '</td><td class="num"><strong>' + money(c.revenueHome, cur) + '</strong>' +
            (c.unreported ? '<br><span class="muted" style="font-size:12px">≈ + ' + money(c.estimatedHome, cur) + '</span>' : '') +
            '</td></tr>';
        }).join('') +
        '<tr><td colspan="5"><strong>Summe</strong></td><td class="num"><strong>' + money(p.revenueHomeTotal, cur) + '</strong></td></tr>' +
        '</tbody></table></div>';
      if (p.withoutFigures) {
        html += '<p class="sub">' + p.withoutFigures + ' Kauf/Käufe ohne echte Beträge — entweder vor dieser Auswertung getätigt oder die Bestellung war nicht abrufbar. Die fehlen in dieser Tabelle.</p>';
      }
      if (p.withoutRate) {
        html += '<p class="sub">' + p.withoutRate + ' Kauf/Käufe warten noch auf einen Umrechnungskurs — die Summe ist um diese Beträge zu niedrig. Der stündliche Lauf trägt sie nach, sobald der Kurs des Kauftags vorliegt.</p>';
      }
      if (p.unreportedOrders) {
        html += '<p class="sub">' + p.unreportedOrders + ' Kauf/Käufe sind bezahlt, aber Google hat den Entwickleranteil ' +
          'noch nicht gemeldet — das passiert erst, wenn die Zahlung abgerechnet ist. Die Summe oben ist deshalb um ' +
          'geschätzte ' + money(p.revenueEstimatedHome, cur) + ' zu niedrig. Es wird stündlich erneut nachgefragt; ' +
          'im Konto lässt sich eine einzelne Bestellung sofort neu abfragen.</p>';
      }
    } else {
      html += '<div class="empty">Noch keine Käufe mit abgerufenen Beträgen.</div>';
    }

    $('finance').innerHTML = html;
  }

  function renderHistory() {
    var range = Number($('sRange').value);
    var metric = $('sMetric').value;
    var rows = histDays.slice(-range);
    var vals = rows.map(function (r) { return r[metric] || 0; });
    var max = Math.max.apply(null, vals.concat([1]));
    $('sChart').innerHTML = rows.map(function (r, i) {
      return '<div title="' + esc(r.day) + ': ' + (Math.round(vals[i] * 100) / 100) + '" style="height:' +
        Math.max(2, Math.round(vals[i] / max * 100)) + '%"></div>';
    }).join('');
    $('sChartNote').textContent = rows.length ? rows[0].day + ' bis ' + rows[rows.length - 1].day + ' · Höchstwert ' + (Math.round(max * 100) / 100) : 'Noch keine Daten.';

    var sum = function (k) { return rows.reduce(function (a, r) { return a + (r[k] || 0); }, 0); };
    // Every row is already in the payout currency — each purchase was converted with the rate of
    // its own day when it was booked, so the series can simply be added up.
    var cur = sum ? sum.homeCurrency : 'EUR';
    $('sSummary').innerHTML =
      card('Anfragen', 'Diktate und Umformulierungen zusammen im gewählten Zeitraum.', sum('requests').toLocaleString('de-DE'), sum('errors') + ' Fehler') +
      card('Diktiert', 'Summe der abgerechneten Audiosekunden.', fmtMinutes(sum('seconds')), '') +
      card('Verkauft', 'Minuten, die im Zeitraum gekauft wurden — nicht dieselben, die verbraucht wurden.', fmtMinutes(sum('secondsSold')), sum('orders') + ' Käufe') +
      card('Erlös', 'Nach Googles Anteil, wie von Google gemeldet.', money(sum('revenue'), cur), '') +
      card('Einkauf', 'Was Cloudflare für den Zeitraum berechnet — je Tag abzüglich des Freikontingents, dann summiert. Ein Tag unter dem Kontingent kostet nichts. Der Listenpreis daneben ist dieselbe Rechenzeit ohne den Abzug.',
        fmtUsd(sum('costUsd')),
        sum('listUsd') > sum('costUsd') ? 'Listenpreis ' + fmtUsd4(sum('listUsd')) : '') +
      card('Neue Konten', 'Erstmals angelegte Guthabenkonten.', sum('newWallets'), '');

    $('sDays').innerHTML = rows.length ? '<table><thead><tr><th>Tag</th><th class="num">Anfragen</th><th class="num">Diktiert</th>' +
      '<th class="num">Fehler</th><th class="num">Käufe</th><th class="num">Erlös</th><th class="num">Einkauf</th>' +
      '<th class="num">Liste</th><th class="num">Neue Konten</th></tr></thead><tbody>' +
      rows.slice().reverse().map(function (r) {
        return '<tr><td>' + esc(r.day) + '</td><td class="num">' + r.requests + '</td><td class="num">' + fmtMinutes(r.seconds) +
          '</td><td class="num">' + (r.errors || '—') + '</td><td class="num">' + (r.orders || '—') +
          '</td><td class="num">' + (r.revenue ? money(r.revenue, cur) : '—') + '</td><td class="num">' + fmtUsd4(r.costUsd) +
          '</td><td class="num muted">' + fmtUsd4(r.listUsd) +
          '</td><td class="num">' + (r.newWallets || '—') + '</td></tr>';
      }).join('') + '</tbody></table>' : '<div class="empty">Noch keine Tage erfasst.</div>';

    $('sMonths').innerHTML = histMonths.length ? '<table><thead><tr><th>Monat</th><th class="num">Anfragen</th>' +
      '<th class="num">Diktiert</th><th class="num">Verkauft</th><th class="num">Käufe</th><th class="num">Erlös</th>' +
      '<th class="num">Einkauf</th><th class="num">Liste</th></tr></thead><tbody>' +
      histMonths.map(function (m) {
        return '<tr><td>' + esc(m.month) + '</td><td class="num">' + m.requests + '</td><td class="num">' + fmtMinutes(m.seconds) +
          '</td><td class="num">' + fmtMinutes(m.secondsSold) + '</td><td class="num">' + (m.orders || '—') +
          '</td><td class="num">' + (m.revenue ? money(m.revenue, cur) : '—') + '</td><td class="num">' + fmtUsd4(m.costUsd) +
          '</td><td class="num muted">' + fmtUsd4(m.listUsd) + '</td></tr>';
      }).join('') + '</tbody></table>' : '<div class="empty">Noch keine Monate erfasst.</div>';
  }

  /**
   * The money view, which is the one call that leaves the house.
   *
   * It asks Google for the payouts — the spend needs nothing from outside any more, it is our own
   * ledger — so it is still the slowest thing on the page and the only one with a rate limit at the
   * far end. Since every tab visit reloads, it keeps a minute of cache: switching between tabs must
   * not fire this again and again, and figures that count in whole days do not change in the
   * meantime. The refresh button clears it outright.
   */
  var moneyAt = 0;
  var MONEY_TTL = 60000;
  function loadMoney(force) {
    if (!force && sum && Date.now() - moneyAt < MONEY_TTL) return Promise.resolve();
    $('finance').innerHTML = skeleton('card', 3);
    return get('/admin/api/money').then(function (r) {
      sum = r.summary;
      moneyAt = Date.now();
      renderFinance(r);
      if (current) renderOverview(current);
    }).catch(function (e) {
      $('finance').innerHTML = '<div class="empty">Konnte nicht geladen werden: ' + esc(e.message) + '</div>';
    });
  }

  function loadHistory() {
    return get('/admin/api/history?days=1095').then(function (r) {
      histDays = r.days; histMonths = r.months; renderHistory();
    });
  }

  /* ----------------------------------------------------------------- Steuer */

  var taxData = null;
  var KIND_LABEL = {
    cloudflare: 'Cloudflare (inkl. Workers AI)', domain: 'Domain', other: 'Sonstiges',
  };

  function renderReconcile(r) {
    var cur = r.homeCurrency;
    var rows = r.months || [];
    if (!rows.length) { $('reconcile').innerHTML = '<div class="empty">Noch kein Monat mit Verkehr.</div>'; return; }
    $('reconcile').innerHTML = '<table><thead><tr><th>Monat</th><th class="num">unsere Rechnung</th>' +
      '<th class="num">Cloudflares Rechnung</th><th class="num">Differenz</th><th>Stand</th></tr></thead><tbody>' +
      rows.map(function (m) {
        // Drei Zustände, und nur einer davon ist ein Ergebnis: läuft noch, fehlt, oder verglichen.
        var state, delta = '—';
        if (m.open) {
          state = '<span class="pill">läuft noch</span>';
        } else if (m.invoiceHome === null) {
          state = '<span class="pill warn">Rechnung fehlt</span>';
        } else {
          var d = n(m.deltaHome);
          // Ein Cent auf eine Monatsrechnung ist Rundung, kein Befund. Alles darüber gehört gesehen.
          var off = Math.abs(d) > 0.01;
          delta = '<span class="' + (off ? 'warn-text' : 'ok-text') + '">' +
            (d >= 0 ? '+' : '−') + money(Math.abs(d), cur) + '</span>';
          state = off
            ? '<span class="pill warn">weicht ab</span>'
            : '<span class="pill ok">stimmt</span>';
          if (m.invoiceReference) state += ' <span class="mono muted">' + esc(m.invoiceReference) + '</span>';
        }
        return '<tr><td class="mono">' + esc(m.month) + ' <span class="muted">' + m.days + ' T</span></td>' +
          '<td class="num">' + money(m.ownHome, cur) + ' <span class="muted">(' + fmtUsd4(m.ownUsd) + ')</span></td>' +
          '<td class="num">' + (m.invoiceHome === null ? '—' : money(m.invoiceHome, cur)) + '</td>' +
          '<td class="num">' + delta + '</td><td>' + state + '</td></tr>';
      }).join('') + '</tbody></table>';
  }

  function renderTax(t) {
    taxData = t;
    var cur = t.homeCurrency;

    $('taxYears').innerHTML = t.years.length ? t.years.map(function (y) {
      // The order of a profit-and-loss, so it can be read straight down: what came in, what was
      // never yours, what went back out, what you actually paid, what is left.
      // Brutto und Steuer stehen in der Währung des Käufers in der Datenbank und werden mit dem
      // Kurs des Kauftags umgerechnet. Das muss an der Zahl stehen, nicht in der Dokumentation:
      // eine umgerechnete Zahl, die sich als gemessene ausgibt, ist genau der Fehler, den diese
      // Ansicht hatte.
      var fxHint = y.foreignOrders
        ? '<span class="hint" tabindex="0" data-tip="Beträge in Fremdwährung sind mit dem EZB-Kurs des jeweiligen Kauftags umgerechnet und fest auf die Buchung geschrieben. Googles Auszahlung rechnet mit Googles eigenem Kurs — eine gute Näherung, nicht der Kontoauszug."></span>'
        : '';
      var lines = [
        ['Kundschaft zahlte brutto' + fxHint, money(y.paidGross, cur), 'nur zur Einordnung — nie deine Einnahme'],
        ['davon Steuer', '− ' + money(y.taxCollected, cur), 'von Google eingezogen und abgeführt'],
        ['<strong>Erlös von Google</strong>', '<strong>' + money(y.revenue, cur) + '</strong>', 'nach Googles Anteil — das ist die Einnahme'],
      ];
      if (y.refunded) lines.push(['Erstattungen', '− ' + money(y.refunded, cur), y.refundedOrders + ' storniert']);
      lines.push(['<strong>Einnahmen netto</strong>', '<strong>' + money(y.revenueNet, cur) + '</strong>', '']);
      Object.keys(y.spendByKind).forEach(function (k) {
        lines.push([KIND_LABEL[k] || k, '− ' + money(y.spendByKind[k], cur), 'erfasste Zahlungen']);
      });
      lines.push(['<strong>Bleibt</strong>', '<strong>' + money(y.profit, cur) + '</strong>', 'vor Steuern und ohne deine Zeit']);

      var warn = [];
      // Ein Jahr, dem eine Einnahme fehlt, darf nicht vollständig aussehen — das ist die eine
      // Ansicht, in der eine stille Lücke später teuer wird.
      if (y.unreported) {
        warn.push(y.unreported + ' Kauf/Käufe sind bezahlt, aber Google hat den Erlös noch nicht ' +
          'gemeldet — sie fehlen in dieser Rechnung.');
      }
      if (y.unconverted) warn.push(y.unconverted + ' Kauf/Käufe noch ohne Umrechnungskurs — Brutto, Steuer und Erlös sind um diese Beträge zu niedrig.');
      if (y.spendUnconverted) warn.push(y.spendUnconverted + ' Ausgabe(n) ohne belasteten Betrag.');
      if (!y.spend) warn.push('Für dieses Jahr ist noch keine Ausgabe erfasst — ohne die ist „Bleibt" kein Gewinn, sondern nur die Einnahme.');

      return '<div class="card"><div class="row" style="align-items:flex-start">' +
        '<div style="flex:1;min-width:min(100%,190px)"><div class="label">Jahr ' + esc(y.year) + '</div>' +
          '<div class="value">' + money(y.profit, cur) + '</div>' +
          '<div class="sub">' + y.orders + ' Käufe' + (y.foreignOrders ? ' · ' + y.foreignOrders + ' in Fremdwährung' : '') + '</div>' +
          (warn.length ? '<div class="sub" style="color:var(--warn);margin-top:8px">' + warn.map(esc).join('<br>') + '</div>' : '') +
        '</div>' +
        '<div style="flex:1.5;min-width:min(100%,280px)"><table style="font-size:13px">' +
          lines.map(function (l) {
            return '<tr><td style="border:0;padding:3px 0">' + l[0] +
              (l[2] ? '<br><span class="muted" style="font-size:11.5px">' + esc(l[2]) + '</span>' : '') +
              '</td><td class="num" style="border:0;padding:3px 0;vertical-align:top">' + l[1] + '</td></tr>';
          }).join('') + '</table></div>' +
      '</div></div>';
    }).join('') : '<div class="empty">Noch keine Käufe und keine Ausgaben erfasst.</div>';

    $('taxExpenses').innerHTML = t.expenses.length
      ? '<table><thead><tr><th>Datum</th><th>Art</th><th class="num">Rechnung</th><th class="num">Belastet</th><th>Beleg</th><th></th></tr></thead><tbody>' +
        t.expenses.map(function (e) {
          return '<tr><td>' + esc(new Date(e.paidAt).toLocaleDateString('de-DE')) + '</td>' +
            '<td>' + esc(KIND_LABEL[e.kind] || e.kind) + '</td>' +
            '<td class="num">' + money(e.amount, e.currency) + '</td>' +
            '<td class="num">' + (e.amountHome === null ? '<span class="pill warn">fehlt</span>' : money(e.amountHome, cur)) + '</td>' +
            '<td class="mono muted">' + esc(e.reference || '—') + '</td>' +
            '<td class="num"><button class="btn ghost" data-del="' + e.id + '">Löschen</button></td></tr>';
        }).join('') + '</tbody></table>'
      : '<div class="empty">Noch nichts erfasst. Die erste Cloudflare-Rechnung gehört hier hinein.</div>';

    Array.prototype.forEach.call($('taxExpenses').querySelectorAll('[data-del]'), function (b) {
      b.onclick = function () {
        modal({ title: 'Ausgabe löschen?', text: 'Die Zeile verschwindet aus allen Jahressummen.', okLabel: 'Löschen', danger: true })
          .then(function (y) { if (y) act({ action: 'delete_expense', id: Number(b.getAttribute('data-del')) }).then(loadTax); });
      };
    });

    $('taxMonths').innerHTML = t.months.length
      ? '<table><thead><tr><th>Monat</th><th class="num">Käufe</th>' +
        '<th class="num">Brutto<span class="hint" tabindex="0" data-tip="Alles in ' + esc(cur) + ', mit dem EZB-Kurs des jeweiligen Kauftags umgerechnet — Käufe in Fremdwährung inbegriffen."></span></th>' +
        '<th class="num">Steuer</th><th class="num">Dein Erlös</th></tr></thead><tbody>' +
        t.months.map(function (m) {
          return '<tr><td>' + esc(m.month) + '</td><td class="num">' + m.orders + '</td><td class="num">' +
            money(m.paidGross, cur) + '</td><td class="num muted">' + money(m.taxCollected, cur) +
            '</td><td class="num"><strong>' + money(m.revenue, cur) + '</strong></td></tr>';
        }).join('') + '</tbody></table>'
      : '<div class="empty">Noch keine Monate mit Verkäufen.</div>';
  }

  function loadTax() {
    $('taxYears').innerHTML = skeleton('card', 2);
    // Zwei Anfragen, aber ein Fehlschlag der einen darf die andere nicht mitnehmen: Der Abgleich
    // ist die Ansicht, wegen der man diesen Reiter im Zweifel öffnet.
    get('/admin/api/reconcile').then(renderReconcile).catch(function (e) {
      $('reconcile').innerHTML = '<div class="empty">Konnte nicht geladen werden: ' + esc(e.message) + '</div>';
    });
    return get('/admin/api/tax').then(renderTax).catch(function (e) {
      $('taxYears').innerHTML = '<div class="empty">Konnte nicht geladen werden: ' + esc(e.message) + '</div>';
    });
  }

  /* ------------------------------------------------------------------ Pläne */

  function renderPlans(p) {
    var cur = p.homeCurrency;
    // Aus der Antwort statt hier hartkodiert: die Zahl steht im Server, und zwei Kopien einer Rate
    // sind eine Kopie zu viel.
    var fee = typeof p.playServiceFee === 'number' ? p.playServiceFee : 0.15;

    $('planCards').innerHTML = p.packs.map(function (k) {
      var a = k.actual;
      var shown = a || k.model;
      var pct = Math.round(shown.marginPercent);
      // 45 % ist die Untergrenze, auf die die Preisliste gerechnet wurde; unter 35 % trägt ein
      // Paket seine eigenen Fixkosten nicht mehr. Beides ist kein Naturgesetz, sondern das Band,
      // in dem die Preise gewählt wurden — wer die Preise ändert, ändert auch diese Zeile.
      var cls = pct >= 45 ? 'ok' : pct >= 35 ? 'warn' : 'crit';

      // Every line of the sum, in the order it happens: the customer pays, tax and Google come
      // off, the compute is bought, what is left is yours.
      // The first four lines are the buyer's own currency, because that is what happened at the
      // till. From "Dein Erlös" on it is the payout currency — mixing the two in one sum is how a
      // margin quietly comes out wrong.
      // Das gilt nur, solange ein Paket in genau einer Währung verkauft wurde. In mehreren ist ein
      // Durchschnitt darüber kein Preis, den jemand bezahlt hat — dann steht die ganze Leiter in
      // der Auszahlungswährung, und die Karte sagt, dass sie es tut.
      var oneCurrency = !!a && a.currencies === 1;
      // Die Leiter rechnet mit dem **Regelfall** — dem Paket, das verdiktiert wird, so wie Pakete
      // verbraucht werden (94,3 % der Guthabensekunden, gemessen). Das ist die Zahl, die zählt.
      //
      // Darunter der Boden: dasselbe Paket vollständig umformuliert, also am teureren der beiden
      // Dienste verbraucht. Er ist erreichbar und deshalb erwähnenswert — anders als die bauliche
      // Obergrenze (die Sekunden zu ihrem Verkaufswert), die niemand erreichen kann und die als
      // Schlagzeile aus 96 % magere 66 % machte.
      var maxRow = ['Einkauf (' + k.minutes + ' Min. verdiktiert)', '− ' + fmtUsd4(k.cost.typicalUsd)];
      var typicalRow = k.cost.worstUsd > k.cost.typicalUsd
        ? ['ganz verformuliert wären es', '− ' + fmtUsd4(k.cost.worstUsd)]
        : null;
      var steps = a && oneCurrency
        ? [['Kundschaft zahlt', money(a.paid, k.currency)],
           ['davon Steuer', '− ' + money(a.tax, k.currency)],
           ['Google-Anteil', '− ' + money(a.paid - a.tax - a.revenue, k.currency)],
           ['<strong>Dein Erlös</strong>', '<strong>' + money(a.revenue, k.currency) + '</strong>'],
           (k.currency !== cur ? ['umgerechnet', money(a.revenueHome, cur)] : null),
           maxRow, typicalRow,
           ['<strong>Bleibt</strong>', '<strong>' + money(a.margin, cur) + '</strong>'],
           (a.marginWorst !== undefined && a.marginWorst !== a.margin
             ? ['<span class="muted">im schlechtesten Fall</span>', '<span class="muted">' + money(a.marginWorst, cur) + '</span>'] : null)].filter(Boolean)
        : a
        ? [['Kundschaft zahlt', money(a.paidHome, cur)],
           ['davon Steuer', '− ' + money(a.taxHome, cur)],
           ['Google-Anteil', '− ' + money(a.paidHome - a.taxHome - a.revenueHome, cur)],
           ['<strong>Dein Erlös</strong>', '<strong>' + money(a.revenueHome, cur) + '</strong>'],
           maxRow, typicalRow,
           ['<strong>Bleibt</strong>', '<strong>' + money(a.margin, cur) + '</strong>'],
           (a.marginWorst !== undefined && a.marginWorst !== a.margin
             ? ['<span class="muted">im schlechtesten Fall</span>', '<span class="muted">' + money(a.marginWorst, cur) + '</span>'] : null)].filter(Boolean)
        : [['Listenpreis (netto)', money(k.listPrice, cur)],
           ['Google-Anteil (' + Math.round(fee * 100) + ' % angenommen)', '− ' + money(k.listPrice * fee, cur)],
           ['<strong>Erlös laut Modell</strong>', '<strong>' + money(k.model.revenue, cur) + '</strong>'],
           maxRow, typicalRow,
           ['<strong>Bleibt</strong>', '<strong>' + money(k.model.margin, cur) + '</strong>'],
           (k.model.marginWorst !== undefined && k.model.marginWorst !== k.model.margin
             ? ['<span class="muted">im schlechtesten Fall</span>', '<span class="muted">' + money(k.model.marginWorst, cur) + '</span>'] : null)].filter(Boolean);

      // Die Frage hat sich mit dem Einkaufspreis gedreht.
      //
      // Früher stand hier der Preis für eine Zielmarge — sinnvoll, solange der Einkauf so groß war,
      // dass er den Preis bestimmte. Bei $0,0005 die Minute kommt dabei „14 Cent für 45 % Marge"
      // heraus: arithmetisch richtig und als Antwort wertlos, weil kein Paket zu diesem Preis
      // verkauft würde.
      //
      // Umgekehrt ist es jetzt die Frage, die man wirklich hat: **Zum heutigen Preis — wie viele
      // Minuten könnten drin sein?** Das ist die Rechnung hinter einem Angebot, und sie geht so:
      // Vom Erlös darf (1 − Marge) für den Einkauf draufgehen, und das geteilt durch den Preis je
      // Minute sind die Minuten.
      var revHome = a ? a.revenueHome : k.model.revenue;
      var targets = [90, 92, 94, 96].map(function (m) {
        var allowedUsd = (revHome * (1 - m / 100)) / n(p.rate);
        var minutes = k.cost.perMinuteUsd > 0 ? allowedUsd / k.cost.perMinuteUsd : 0;
        var here = Math.abs(m - pct) < 1;
        return '<tr><td class="' + (here ? '' : 'muted') + '">' + m + ' % Marge' +
          (here ? ' <span class="muted">(heute)</span>' : '') + '</td>' +
          '<td class="num' + (here ? '' : ' muted') + '">' + Math.round(minutes).toLocaleString('de-DE') + ' Min.</td></tr>';
      }).join('');

      return '<div class="card"><div class="row" style="align-items:flex-start">' +
        '<div style="flex:1;min-width:min(100%,190px)">' +
          '<div class="label">' + esc(k.name) + ' <span class="mono muted">' + esc(k.id) + '</span></div>' +
          '<div class="value">' + money(shown.margin, cur) + '</div>' +
          '<div class="sub">' + k.minutes.toLocaleString('de-DE') + ' Minuten <span class="muted">oder ~' + k.rewords.toLocaleString('de-DE') + ' Umformulierungen</span><br>' +
            k.pricePerMinuteCents.toFixed(2) + ' ct/Min. Preis · ' + k.marginPerMinuteCents.toFixed(2) + ' ct/Min. Marge</div>' +
          '<div class="pillcol"><span class="pill ' + cls + '">' + pct + ' % Marge</span> ' +
            // Der Boden gehört daneben und nicht in die Fußnote: Er ist die Zahl, die zählt, wenn
            // jemand sein ganzes Paket in Umformulierungen steckt.
            (typeof shown.marginPercentWorst === 'number' && Math.round(shown.marginPercentWorst) !== pct
              ? '<span class="pill">min. ' + Math.round(shown.marginPercentWorst) + ' %</span> ' : '') +
            (k.savingsPercent === null || k.savingsPercent === undefined ? ''
              : '<span class="pill info">' + k.savingsPercent + ' % günstiger je Min.</span> ') +
            (a ? '<span class="pill info">' + a.orders + ' Verkauf/Verkäufe</span>'
               : '<span class="pill">nur Modell</span>') +
            // Ein Paket, dessen einziger Verkauf noch nicht abgerechnet ist, hat nichts Gemessenes —
            // es steht deshalb auf „nur Modell" und sagt hier, warum.
            (k.unreportedOrders ? ' <span class="pill">' + k.unreportedOrders + ' noch nicht abgerechnet</span>' : '') +
            // Warum die Leiter rechts in Euro steht statt an der Kasse: mehrere Währungen lassen
            // sich nicht mitteln, also ist jede Zeile umgerechnet.
            (a && a.currencies > 1 ? ' <span class="pill">in ' + a.currencies + ' Währungen verkauft</span>' : '') + '</div>' +
        '</div>' +
        '<div style="flex:1.4;min-width:min(100%,250px)"><table class="ladder">' +
          steps.map(function (s) {
            return '<tr><td>' + s[0] + '</td><td class="num">' + s[1] + '</td></tr>';
          }).join('') + '</table>' +
          '<div class="sub" style="margin-top:10px">Zum heutigen Preis wären drin' +
            hint('Wie groß das Paket bei einer bestimmten Marge sein dürfte, ohne den Preis zu ändern — die Rechnung hinter einem Angebot. Vom Erlös darf (1 − Marge) für den Einkauf draufgehen; geteilt durch den Preis je Minute sind das die Minuten. Die Zeile ohne Graustufe ist der heutige Stand.') + '</div>' +
          '<table class="ladder">' + targets + '</table></div>' +
      '</div></div>';
    }).join('');

    $('planTable').innerHTML = '<table><thead><tr><th>Paket</th><th class="num">Preis</th>' +
      '<th class="num">Minuten</th><th class="num">ct/Min.</th><th class="num">günstiger</th>' +
      '<th class="num">Einkauf</th>' +
      '<th class="num">Erlös</th><th class="num">Marge</th><th class="num">%</th><th>Quelle</th></tr></thead><tbody>' +
      p.packs.map(function (k) {
        var s = k.actual || k.model;
        var save = k.savingsPercent === null || k.savingsPercent === undefined
          ? '<span class="muted">—</span>' : k.savingsPercent + ' %';
        return '<tr><td>' + esc(k.name) + '</td><td class="num">' + money(k.listPrice, cur) +
          '</td><td class="num">' + k.minutes.toLocaleString('de-DE') + '</td><td class="num">' + k.pricePerMinuteCents.toFixed(2) +
          '</td><td class="num">' + save +
          '</td><td class="num">' + fmtUsd4(k.cost.totalUsd) + '</td><td class="num">' + money(k.actual ? s.revenueHome : s.revenue, cur) +
          '</td><td class="num">' + money(s.margin, cur) + '</td><td class="num">' + Math.round(s.marginPercent) +
          ' %</td><td>' + (k.actual ? '<span class="pill ok">gemessen</span>' : '<span class="pill">Modell</span>') + '</td></tr>';
      }).join('') + '</tbody></table>';

    $('planBasis').innerHTML = '<div class="kv">' +
      '<dt>Diktatmodell</dt><dd class="mono">' + esc(p.transcribeModel) + ' · ' + fmtUsd4(p.transcribeUsdPerMinute) + ' je Minute</dd>' +
      '<dt>Umformulierung</dt><dd class="mono">' + esc(p.chatModel) + ' · ' + fmtUsd4(p.rewordUsd) + ' bei 500 Token rein, 300 raus</dd>' +
      '<dt>Abrechnung</dt><dd>Alles kostet Sekunden: eine typische Umformulierung 2, eine maximale 16</dd>' +
      '<dt>Umrechnung</dt><dd>USD → ' + cur + ' mit ' + n(p.rate).toFixed(4) +
        (p.rateSource === 'ecb' ? ' <span class="muted">(EZB-Tageskurs)</span>' : ' <span class="muted">(Annahme, noch kein EZB-Kurs geholt)</span>') + '</dd>' +
      '<dt>Steuer</dt><dd>Der Listenpreis ist ein <strong>Nettopreis</strong>. Google schlägt den ' +
        'Satz des Käuferlandes oben drauf und führt ihn ab — 1,99 € werden für eine deutsche ' +
        'Kundschaft zu etwa 2,39 €. Der Erlös ist deshalb der Listenpreis minus Googles Anteil und ' +
        '<em>nicht</em> noch einmal minus Steuer.</dd>' +
      '<dt>„günstiger"</dt><dd>Preis je Minute gegenüber dem kleinsten Paket, abgerundet. Die App ' +
        'blendet alles unter 10 % aus, zeigt also nicht zwingend jede Zahl, die hier steht.</dd>' +
      '</div>' +
      '<p class="sub" style="margin-top:10px">Die Einkaufsspalte ist eine <strong>Obergrenze</strong>, keine Schätzung: ' +
      'jede Leistung wird in dieselben Sekunden eingepreist, also sind die verkauften Sekunden zugleich der ' +
      'gesamte Einkauf. Wie die Kundschaft sie aufteilt, ändert daran nichts — die Marge kann nur nach oben ' +
      'abweichen. Fixkosten stehen nirgends darin: Cloudflare, Domain und deine Zeit fehlen.</p>';
  }


  /* --------------------------------------------------------------- Netzwerk */

  var TONE = { client: 'var(--z-client)', cloudflare: 'var(--z-cf)', google: 'var(--z-google)', ext: 'var(--z-ext)' };
  var EKIND = { data: 'var(--accent)', auth: 'var(--z-google)', store: 'var(--muted)', notify: 'var(--z-cf)' };
  var cam = { s: 1, x: 0, y: 0 }, filter = 'all', selected = null, graphMode = 'map';
  var byId = {};
  GRAPH.nodes.forEach(function (n) { byId[n.id] = n; });
  var zoneById = {};
  GRAPH.zones.forEach(function (z) { zoneById[z.id] = z; });

  /**
   * Everything a label must not land on.
   *
   * The old pass only pushed labels away from each other, which is why a chip could sit squarely on
   * a heading: boxes were never obstacles at all. They are now, and so are the zone titles, which
   * are text without a box and therefore the easiest thing in the picture to bury.
   */
  function obstacles() {
    var list = GRAPH.nodes.map(function (n) { return { x: n.x, y: n.y, w: n.w, h: n.h }; });
    GRAPH.zones.forEach(function (z) { list.push({ x: z.x + 10, y: z.y + 6, w: 260, h: 46 }); });
    return list;
  }
  function hits(a, b) {
    return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
  }

  function drawGraph() {
    var parts = [], labels = [];
    // One gradient for all twenty-six boxes: light from above over a translucent ground, which is
    // the same thing the cards do with an inset highlight. Defined once and referenced, so the cost
    // is a single paint server rather than a filter per node.
    parts.push('<defs><marker id="arw" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="context-stroke"/></marker>' +
      '<linearGradient id="gglass" x1="0" y1="0" x2="0" y2="1">' +
      '<stop offset="0" stop-color="rgba(34,45,60,.88)"/>' +
      '<stop offset="1" stop-color="rgba(13,18,26,.88)"/></linearGradient></defs>');

    GRAPH.zones.forEach(function (z) {
      parts.push('<rect class="zone-bg" x="' + z.x + '" y="' + z.y + '" width="' + z.w + '" height="' + z.h +
        '" fill="' + TONE[z.tone] + '" stroke="' + TONE[z.tone] + '"/>');
      parts.push('<text class="zone-label" x="' + (z.x + 16) + '" y="' + (z.y + 28) + '" fill="' + TONE[z.tone] + '">' + esc(z.label) + '</text>');
      parts.push('<text class="zone-sub" x="' + (z.x + 16) + '" y="' + (z.y + 46) + '">' + esc(z.sub) + '</text>');
    });

    // Paths first, then boxes, then labels — so a box covers a line that passes behind it, and a
    // label is never buried by either.
    GRAPH.edges.forEach(function (e, i) {
      var dim = (filter === 'token' && !e.token) || (filter === 'guard' && !e.guard);
      var on = selected && selected.type === 'edge' && selected.i === i;
      var label = filter === 'guard' && e.guard ? e.guard : (filter === 'token' && e.token ? e.token : e.label);
      // A second copy of the same d, carrying the dots. Its speed is nudged per route so the whole
      // diagram does not pulse in unison — different pipes, different flow — and it takes no arrow
      // marker: the line underneath already says which way this goes.
      var flowSecs = (2.2 + (i % 7) * 0.31).toFixed(2);
      parts.push('<g class="gedge' + (dim ? ' dim' : '') + (on ? ' sel' : '') + '" data-e="' + i + '">' +
        '<path d="' + e.d + '" stroke="' + EKIND[e.kind] + '" marker-end="url(#arw)"/>' +
        '<path class="flow" d="' + e.d + '" stroke="' + EKIND[e.kind] + '" style="animation-duration:' + flowSecs + 's"/></g>');
      labels.push('<g class="glabel' + (dim ? ' dim' : '') + (on ? ' sel' : '') + '" data-e="' + i + '">' +
        '<rect class="lbl"/><text text-anchor="middle">' + esc(label) + '</text></g>');
    });

    GRAPH.nodes.forEach(function (n) {
      var zone = zoneById[n.zone];
      var on = selected && selected.type === 'node' && selected.id === n.id;
      parts.push('<g class="gnode' + (on ? ' sel' : '') + '" data-n="' + esc(n.id) + '">' +
        '<rect x="' + n.x + '" y="' + n.y + '" width="' + n.w + '" height="' + n.h + '"/>' +
        '<rect class="bar" x="' + n.x + '" y="' + n.y + '" width="4" height="' + n.h + '" style="fill:' + TONE[zone ? zone.tone : 'client'] + '"/>' +
        '<text class="t" x="' + (n.x + 16) + '" y="' + (n.y + 27) + '">' + esc(n.label) + '</text>' +
        '<text class="s" x="' + (n.x + 16) + '" y="' + (n.y + 46) + '">' + esc(n.sub) + '</text>' +
        '<g class="gask" data-ask="' + esc(n.id) + '"><circle cx="' + (n.x + n.w - 17) + '" cy="' + (n.y + 17) + '" r="9"/>' +
        '<text x="' + (n.x + n.w - 17) + '" y="' + (n.y + 21) + '" text-anchor="middle">?</text>' +
        // A finger is wider than nine pixels. The invisible square is the target; the ring is only
        // what it looks like.
        '<rect class="hit" x="' + (n.x + n.w - 34) + '" y="' + (n.y) + '" width="34" height="34" fill="transparent"/>' +
        '</g></g>');
    });

    $('gcam').innerHTML = parts.join('') + labels.join('');
    placeLabels();

    // Taps are handled once, on the canvas — see the handler in initGraph. Wiring every shape
    // individually meant re-wiring all of them on every redraw, and it broke the moment a pointer
    // was captured, because a captured click never reaches the shape it was aimed at.
    Array.prototype.forEach.call($('gcam').querySelectorAll('.gedge, .glabel'), function (g) {
      var i = g.getAttribute('data-e');
      g.addEventListener('mouseenter', function () { hot(i, true); });
      g.addEventListener('mouseleave', function () { hot(i, false); });
    });
  }

  function hot(i, on) {
    Array.prototype.forEach.call($('gcam').querySelectorAll('[data-e="' + i + '"]'), function (g) {
      g.classList[on ? 'add' : 'remove']('hot');
    });
  }

  /**
   * Where each label goes.
   *
   * The layout offers several places along the route, best first; the browser is the only side that
   * knows how wide the text turns out, so it measures and then scores every candidate — each offered
   * place, and each of those shifted sideways off the line in steps.
   *
   * A score, not a search for the first free spot. There is not always a free spot, and the version
   * that took the first one and gave up afterwards left two chips exactly on top of each other,
   * which is worse than either of them sitting slightly off its line. Overlap is counted in square
   * pixels and weighed against how far the chip has strayed, so the least bad place wins and an
   * unobstructed one always beats a compromise.
   */
  // Off the line, and along it. A chip that may only step sideways has one way out of a clash;
  // sliding it a little further down its own route is usually the tidier answer and is charged
  // less accordingly.
  var LABEL_PERP = [0, -19, 19, -38, 38, -57, 57, -76, 76];
  var LABEL_ALONG = [0, -24, 24, -48, 48];

  function areaOver(a, b) {
    var w = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
    var h = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
    return w > 0 && h > 0 ? w * h : 0;
  }

  function placeLabels() {
    var blockers = obstacles();
    var placed = [];
    // Fewest options first. Seated in drawing order, an edge with a single possible place can arrive
    // to find it taken by one that had a dozen and picked this one; the other way round everybody
    // fits. Ties keep drawing order, so the arrangement is the same on every redraw.
    var chips = Array.prototype.slice.call($('gcam').querySelectorAll('.glabel'));
    chips.sort(function (p, q) {
      var pe = GRAPH.edges[Number(p.getAttribute('data-e'))];
      var qe = GRAPH.edges[Number(q.getAttribute('data-e'))];
      return pe.spots.length - qe.spots.length ||
        Number(p.getAttribute('data-e')) - Number(q.getAttribute('data-e'));
    });
    chips.forEach(function (g) {
      var e = GRAPH.edges[Number(g.getAttribute('data-e'))];
      var text = g.querySelector('text'), rect = g.querySelector('rect');
      var tw = 0;
      try { tw = text.getComputedTextLength(); } catch (err) { tw = 0; }
      if (!tw) tw = text.textContent.length * 5.7;
      var w = tw + 12, h = 17;

      var best = null, bestCost = Infinity;
      for (var k = 0; k < e.spots.length && bestCost > 0; k++) {
        var spot = e.spots[k];
        for (var o = 0; o < LABEL_PERP.length; o++) {
          for (var a = 0; a < LABEL_ALONG.length; a++) {
            var off = LABEL_PERP[o], slide = LABEL_ALONG[a];
            var box = spot.along === 'h'
              ? { x: spot.x - w / 2 + slide, y: spot.y - h / 2 + off, w: w, h: h }
              : { x: spot.x - w / 2 + off, y: spot.y - h / 2 + slide, w: w, h: h };
            var cost = k * 26 + Math.abs(off) * 1.4 + Math.abs(slide) * 0.5;
            var m;
            for (m = 0; m < blockers.length; m++) cost += areaOver(box, blockers[m]) * 0.9;
            for (m = 0; m < placed.length; m++) cost += areaOver(box, placed[m]) * 1.6;
            if (cost < bestCost) { bestCost = cost; best = box; }
            if (cost === 0) break;
          }
          if (bestCost === 0) break;
        }
      }

      placed.push(best);
      rect.setAttribute('x', best.x); rect.setAttribute('y', best.y);
      rect.setAttribute('width', w); rect.setAttribute('height', h);
      text.setAttribute('x', best.x + w / 2); text.setAttribute('y', best.y + h / 2 + 4);
    });
  }

  /* ------------------------------------------------------------ Detailleiste */

  /**
   * One connection, as a row.
   *
   * In the panel it selects the line on the map — the map is right there and highlighting it is the
   * more useful answer. In the list there is no map, so the same row opens the explanation instead.
   */
  function linkRow(e, i, self, opens) {
    var other = e.from === self ? byId[e.to] : byId[e.from];
    var dir = e.from === self ? '→' : '←';
    return '<button data-' + (opens === 'explain' ? 'edge' : 'edge-select') + '="' + i + '">' +
      '<span class="dir">' + dir + '</span>' +
      '<span><strong>' + esc(other ? other.label : '?') + '</strong> ' +
      '<span class="what">' + esc(e.label) + '</span></span></button>';
  }

  function selectNode(id) {
    selected = { type: 'node', id: id };
    var n = byId[id];
    drawGraph();
    if (!n) { $('ndetail').innerHTML = ''; return; }
    var links = [];
    GRAPH.edges.forEach(function (e, i) { if (e.from === id || e.to === id) links.push(linkRow(e, i, id, 'select')); });
    var html = '<div class="row" style="justify-content:space-between;align-items:baseline;gap:10px">' +
      '<h3 style="margin:0">' + esc(n.label) + ' <span class="muted" style="font-weight:400">' + esc(n.sub) + '</span></h3>' +
      '<button class="btn ghost tiny" data-explain-node="' + esc(n.id) + '">Ausführlich</button></div>' +
      '<p class="sub" style="font-size:13.5px;margin:8px 0 8px">' + n.detail + '</p>';
    if (n.holds && n.holds.length) html += '<div class="label">Hält</div><div class="taglist">' + n.holds.map(function (t) { return '<span class="tag key">' + esc(t) + '</span>'; }).join('') + '</div>';
    if (n.guards && n.guards.length) html += '<div class="label" style="margin-top:10px">Absicherung</div><div class="taglist">' + n.guards.map(function (t) { return '<span class="tag guard">' + esc(t) + '</span>'; }).join('') + '</div>';
    if (links.length) html += '<div class="label" style="margin-top:10px">Verbindungen</div><div class="links" style="margin-top:6px">' + links.join('') + '</div>';
    if (n.source) html += '<p class="sub" style="margin-top:10px">Quelle: <code>' + esc(n.source) + '</code></p>';
    $('ndetail').innerHTML = html;
    wireExplainers($('ndetail'));
  }

  function selectEdge(i) {
    selected = { type: 'edge', i: i };
    var e = GRAPH.edges[i];
    drawGraph();
    if (!e) return;
    var a = byId[e.from], b = byId[e.to];
    var html = '<div class="row" style="justify-content:space-between;align-items:baseline;gap:10px">' +
      '<h3 style="margin:0">' + esc(a ? a.label : e.from) + ' <span class="muted">→</span> ' + esc(b ? b.label : e.to) + '</h3>' +
      '<button class="btn ghost tiny" data-explain-edge="' + i + '">Ausführlich</button></div>' +
      '<p class="sub" style="font-size:13.5px;margin:8px 0 0">' + esc(e.label) + '</p>';
    if (e.token) html += '<div class="label" style="margin-top:10px">Trägt</div><div class="taglist"><span class="tag key">' + esc(e.token) + '</span></div>';
    if (e.guard) html += '<div class="label" style="margin-top:10px">Absicherung</div><div class="taglist"><span class="tag guard">' + esc(e.guard) + '</span></div>';
    $('ndetail').innerHTML = html;
    wireExplainers($('ndetail'));
  }

  function clearSelection() {
    selected = null;
    drawGraph();
    $('ndetail').innerHTML = '<p class="sub" style="margin:0">Auf eine Kachel oder einen Weg tippen. Das <strong>?</strong> an jeder Kachel öffnet die ausführliche Erklärung.</p>';
  }

  /* ------------------------------------------------------------ Erklärungen */

  function openExplain(title, meta, body) {
    $('xTitle').textContent = title;
    $('xBody').innerHTML = (meta ? '<div class="xmeta">' + meta + '</div>' : '') + body;
    $('explain').showModal();
  }
  function explainNode(id) {
    var n = byId[id];
    if (!n) return;
    var z = zoneById[n.zone];
    var meta = '<div class="sub">' + esc(n.sub) + (z ? ' · ' + esc(z.label) : '') + '</div>';
    if (n.holds && n.holds.length) meta += '<div class="taglist">' + n.holds.map(function (t) { return '<span class="tag key">' + esc(t) + '</span>'; }).join('') + '</div>';
    if (n.guards && n.guards.length) meta += '<div class="taglist">' + n.guards.map(function (t) { return '<span class="tag guard">' + esc(t) + '</span>'; }).join('') + '</div>';
    if (n.source) meta += '<div class="sub">Quelle: <code>' + esc(n.source) + '</code></div>';
    openExplain(n.label, meta, n.long);
  }
  function explainEdge(i) {
    var e = GRAPH.edges[i];
    if (!e) return;
    var a = byId[e.from], b = byId[e.to];
    var meta = '<div class="sub">' + esc(a ? a.label : e.from) + ' → ' + esc(b ? b.label : e.to) + ' · ' + esc(e.label) + '</div>';
    var tags = '';
    if (e.token) tags += '<span class="tag key">' + esc(e.token) + '</span>';
    if (e.guard) tags += '<span class="tag guard">' + esc(e.guard) + '</span>';
    if (tags) meta += '<div class="taglist">' + tags + '</div>';
    openExplain((a ? a.label : e.from) + ' → ' + (b ? b.label : e.to), meta, e.long);
  }
  function explainZone(id) {
    var z = zoneById[id];
    if (z) openExplain(z.label, '<div class="sub">' + esc(z.sub) + '</div>', z.long);
  }

  /** One wiring routine for both the panel and the list — the same buttons appear in both. */
  /**
   * Wires the "?" explainer buttons inside a rendered block of the network diagram.
   *
   * Named apart from wireDetail() deliberately: both are plain function declarations in the same
   * scope, so sharing a name is not an overload but a silent overwrite — the later declaration
   * hoists over the earlier one and takes over its call sites too. That is exactly what happened
   * here, and the account dialog stopped opening because it was handing a wallet object to a
   * function expecting a DOM node.
   */
  function wireExplainers(root) {
    Array.prototype.forEach.call(root.querySelectorAll('[data-explain-node]'), function (b) {
      b.onclick = function (ev) { ev.stopPropagation(); explainNode(b.getAttribute('data-explain-node')); };
    });
    Array.prototype.forEach.call(root.querySelectorAll('[data-explain-edge]'), function (b) {
      b.onclick = function (ev) { ev.stopPropagation(); explainEdge(Number(b.getAttribute('data-explain-edge'))); };
    });
    Array.prototype.forEach.call(root.querySelectorAll('[data-explain-zone]'), function (b) {
      b.onclick = function (ev) { ev.stopPropagation(); explainZone(b.getAttribute('data-explain-zone')); };
    });
    Array.prototype.forEach.call(root.querySelectorAll('[data-edge]'), function (b) {
      b.onclick = function (ev) { ev.stopPropagation(); explainEdge(Number(b.getAttribute('data-edge'))); };
    });
    Array.prototype.forEach.call(root.querySelectorAll('[data-edge-select]'), function (b) {
      b.onclick = function (ev) { ev.stopPropagation(); selectEdge(Number(b.getAttribute('data-edge-select'))); };
    });
  }

  /* ------------------------------------------------------------------ Liste */

  /**
   * The same graph, read top to bottom.
   *
   * A phone gets this first. Fitting a 1500-pixel drawing onto a 390-pixel screen leaves labels at
   * three pixels; panning and pinching to read an architecture is not reading it. Same object, same
   * texts, no second place to keep up to date.
   */
  function renderList() {
    var html = GRAPH.zones.map(function (z) {
      var members = GRAPH.nodes.filter(function (n) { return n.zone === z.id; });
      var cards = members.map(function (n) {
        var links = [];
        GRAPH.edges.forEach(function (e, i) { if (e.from === n.id || e.to === n.id) links.push(linkRow(e, i, n.id, 'explain')); });
        var tags = (n.holds || []).map(function (t) { return '<span class="tag key">' + esc(t) + '</span>'; })
          .concat((n.guards || []).map(function (t) { return '<span class="tag guard">' + esc(t) + '</span>'; })).join('');
        return '<div class="ncard">' +
          '<div class="row" style="justify-content:space-between">' +
            '<h4>' + esc(n.label) + ' <span class="muted" style="font-weight:400">' + esc(n.sub) + '</span></h4>' +
            '<button class="btn ghost tiny" data-explain-node="' + esc(n.id) + '">Ausführlich</button>' +
          '</div>' +
          '<p class="sub" style="margin:0;font-size:13px">' + n.detail + '</p>' +
          (tags ? '<div class="taglist">' + tags + '</div>' : '') +
          (links.length ? '<div class="links">' + links.join('') + '</div>' : '') +
          (n.source ? '<p class="sub" style="margin:0">Quelle: <code>' + esc(n.source) + '</code></p>' : '') +
        '</div>';
      }).join('');
      return '<section class="zgroup">' +
        '<header><i class="bar-i" style="background:' + TONE[z.tone] + '"></i>' +
          '<div style="flex:1;min-width:0"><h3>' + esc(z.label) + '</h3><p class="sub">' + esc(z.sub) + '</p></div>' +
          '<button class="btn ghost tiny" data-explain-zone="' + esc(z.id) + '">Ausführlich</button>' +
        '</header>' + cards + '</section>';
    }).join('');
    $('glist').innerHTML = html;
    wireExplainers($('glist'));
  }

  function setGraphMode(mode) {
    graphMode = mode;
    $('gModeList').setAttribute('aria-pressed', String(mode === 'list'));
    $('gModeMap').setAttribute('aria-pressed', String(mode === 'map'));
    $('glist').hidden = mode !== 'list';
    $('gmap').hidden = mode !== 'map';
    // Redrawn on the way back, not just re-fitted: label widths are measured, and a measurement
    // taken while the canvas was hidden is a guess with a number attached to it.
    if (mode === 'list') renderList();
    else setTimeout(function () { drawGraph(); fit(); }, 0);
  }

  /* ----------------------------------------------------------- Kamera */

  function applyCam() {
    $('gcam').setAttribute('transform', 'translate(' + cam.x + ',' + cam.y + ') scale(' + cam.s + ')');
    // Twenty-eight chips at a quarter of full size are a grey haze, not information. Below that
    // they step aside and only the selected or hovered line still says what it carries.
    $('gcam').classList[cam.s < 0.42 ? 'add' : 'remove']('hush');
  }
  function fit() {
    var box = $('gsvg').getBoundingClientRect();
    if (!box.width) return;
    // The toolbar floats over the canvas and wraps to two rows on a phone, so its height is
    // measured rather than guessed. Without this the top-right box sits under the buttons.
    var tools = $('gtools').getBoundingClientRect();
    var inset = Math.min(box.height / 3, tools.height + 18);
    var s = Math.min(box.width / GRAPH.extent.w, (box.height - inset) / GRAPH.extent.h);
    cam.s = s;
    cam.x = (box.width - GRAPH.extent.w * s) / 2;
    cam.y = inset + (box.height - inset - GRAPH.extent.h * s) / 2;
    applyCam();
  }
  function zoomAt(factor, cx, cy) {
    var ns = Math.max(0.25, Math.min(3, cam.s * factor));
    cam.x = cx - (cx - cam.x) * (ns / cam.s);
    cam.y = cy - (cy - cam.y) * (ns / cam.s);
    cam.s = ns; applyCam();
  }

  function initGraph() {
    var svg = $('gsvg'), pts = {}, last = null, pinch = null, moved = 0, held = {};
    /**
     * Capture only once a drag is really under way.
     *
     * setPointerCapture on pointerdown looks like the tidy way to keep a drag alive past the edge
     * of the canvas, and it quietly costs every tap: with the pointer captured, the click that
     * follows is delivered to the element that holds the capture — the canvas — and never reaches
     * the box or the "?" that was actually pressed. So the capture waits for movement, which is the
     * only moment it is needed for anything.
     */
    function grab(id) {
      if (held[id]) return;
      try { svg.setPointerCapture(id); held[id] = 1; } catch (err) { /* nothing to hold on to */ }
    }
    svg.addEventListener('pointerdown', function (e) {
      pts[e.pointerId] = { x: e.clientX, y: e.clientY };
      if (Object.keys(pts).length === 1) { last = { x: e.clientX, y: e.clientY }; moved = 0; }
    });
    svg.addEventListener('pointermove', function (e) {
      if (!pts[e.pointerId]) return;
      pts[e.pointerId] = { x: e.clientX, y: e.clientY };
      var ids = Object.keys(pts);
      if (ids.length >= 2) {
        ids.forEach(grab);
        var a = pts[ids[0]], b = pts[ids[1]];
        var dist = Math.hypot(a.x - b.x, a.y - b.y);
        var box = svg.getBoundingClientRect();
        var mid = { x: (a.x + b.x) / 2 - box.left, y: (a.y + b.y) / 2 - box.top };
        if (pinch) zoomAt(dist / pinch, mid.x, mid.y);
        pinch = dist; last = null; return;
      }
      if (last) {
        moved += Math.abs(e.clientX - last.x) + Math.abs(e.clientY - last.y);
        if (moved > 4) { grab(e.pointerId); svg.classList.add('dragging'); }
        cam.x += e.clientX - last.x; cam.y += e.clientY - last.y;
        last = { x: e.clientX, y: e.clientY }; applyCam();
      }
    });
    function up(e) {
      delete pts[e.pointerId]; delete held[e.pointerId];
      if (!Object.keys(pts).length) { last = null; pinch = null; svg.classList.remove('dragging'); }
    }
    svg.addEventListener('pointerup', up); svg.addEventListener('pointercancel', up);
    svg.addEventListener('wheel', function (e) {
      e.preventDefault();
      var box = svg.getBoundingClientRect();
      zoomAt(e.deltaY < 0 ? 1.12 : 1 / 1.12, e.clientX - box.left, e.clientY - box.top);
    }, { passive: false });
    /**
     * Every tap on the canvas, in one place.
     *
     * Delegated rather than bound per shape: the drawing is rebuilt on each redraw, and one
     * listener that outlives all of it beats a hundred that do not. The lookup by point is the
     * safety net — if a pointer was captured after all, the click arrives at the canvas with no
     * memory of what was under it, and this asks the document instead of giving up.
     */
    svg.addEventListener('click', function (ev) {
      if (moved >= 6) return;
      var t = ev.target;
      if (!t.closest || t === svg || t.id === 'gcam') {
        t = document.elementFromPoint(ev.clientX, ev.clientY) || t;
      }
      var ask = t.closest && t.closest('.gask');
      if (ask) { explainNode(ask.getAttribute('data-ask')); return; }
      var node = t.closest && t.closest('.gnode');
      if (node) { selectNode(node.getAttribute('data-n')); return; }
      var line = t.closest && t.closest('.gedge, .glabel');
      if (line) { selectEdge(Number(line.getAttribute('data-e'))); return; }
      clearSelection();
    });

    $('gIn').onclick = function () { var b = svg.getBoundingClientRect(); zoomAt(1.25, b.width / 2, b.height / 2); };
    $('gOut').onclick = function () { var b = svg.getBoundingClientRect(); zoomAt(1 / 1.25, b.width / 2, b.height / 2); };
    $('gFit').onclick = fit;
    function setFilter(f) {
      filter = f;
      $('gAll').setAttribute('aria-pressed', String(f === 'all'));
      $('gTok').setAttribute('aria-pressed', String(f === 'token'));
      $('gSec').setAttribute('aria-pressed', String(f === 'guard'));
      drawGraph();
    }
    $('gAll').onclick = function () { setFilter('all'); };
    $('gTok').onclick = function () { setFilter('token'); };
    $('gSec').onclick = function () { setFilter('guard'); };
    $('gModeList').onclick = function () { setGraphMode('list'); };
    $('gModeMap').onclick = function () { setGraphMode('map'); };
    $('xClose').onclick = function () { $('explain').close(); };

    $('glegend').innerHTML =
      '<span><i class="swatch" style="background:var(--accent)"></i>Nutzdaten</span>' +
      '<span><i class="swatch" style="background:var(--z-google)"></i>Authentifizierung</span>' +
      '<span><i class="swatch" style="background:var(--muted)"></i>Speicher</span>' +
      '<span><i class="swatch" style="background:var(--z-cf)"></i>Benachrichtigung</span>' +
      '<span class="muted">Ziehen · Rad oder zwei Finger zum Zoomen · Kachel oder Weg antippen · <strong>?</strong> erklärt ausführlich</span>';
    clearSelection();
    setGraphMode(innerWidth <= 620 ? 'list' : 'map');
  }

  /* ------------------------------------------------------------------ Start */

  /**
   * One loader per view, run the first time that view is opened.
   *
   * Everything used to be fetched at once, including three-year history and the plan calculation
   * for tabs that might never be opened. Loading on demand is most of the reason the page now
   * appears immediately; the skeletons are the other part, because a view that shows the shape of
   * its answer straight away reads as fast even while it is still waiting.
   */
  var LOADERS = {
    overview: function () {
      $('stats').innerHTML = skeleton('card', 6);
      var money = loadMoney();
      return get('/admin/api/overview').then(function (o) {
        // Wait for the money only if it is not in yet, so the first paint is not held up by
        // Google and the models — but never render the cards twice for nothing.
        return money.then(function () { renderOverview(o); });
      }).catch(function (e) {
        $('stats').innerHTML = '<div class="card"><div class="label">Fehler</div><div class="value" style="font-size:18px">' + esc(e.message) + '</div></div>';
      });
    },
    alerts: loadAlerts,
    stats: function () { return Promise.all([loadMoney(), loadHistory()]); },
    plans: function () {
      $('planCards').innerHTML = skeleton('card', 4);
      return get('/admin/api/plans').then(renderPlans).catch(function (e) {
        $('planCards').innerHTML = '<div class="empty">Konnte nicht geladen werden: ' + esc(e.message) + '</div>';
      });
    },
    tax: loadTax,
    accounts: loadWallets,
    traffic: loadTraffic,
    audit: loadAudit,
    network: function () {},
    // Drawn from the overview's data, so opening it before the overview has landed would show
    // nothing at all.
    // Its numbers come from the overview's call, so opening it has to make that call — the
    // difference to before is that this now happens on every visit rather than only the first.
    ops: function () { return LOADERS.overview(); },
  };

  var loaded = {};

  function load(view, force) {
    if (!force && loaded[view]) return;
    loaded[view] = true;
    var fn = LOADERS[view];
    if (fn) fn();
  }

  /** Reloads everything already on screen. The refresh button and anything that changed state. */
  function loadAll() {
    sum = null; moneyAt = 0;
    Object.keys(loaded).forEach(function (v) { if (LOADERS[v]) LOADERS[v](); });
    get('/admin/api/me').then(function (r) { $('who').textContent = r.email; });
  }

  function loadWallets() {
    var p = '?test=' + ($('wTest').checked ? '1' : '0') + '&deleted=' + ($('wDeleted').checked ? '1' : '0');
    if ($('q').value.trim()) p += '&q=' + encodeURIComponent($('q').value.trim());
    return get('/admin/api/wallets' + p).then(function (r) { renderWallets(r.wallets); });
  }

  Array.prototype.forEach.call($('nav').querySelectorAll('button'), function (b) {
    b.onclick = function () {
      var view = b.getAttribute('data-view');
      Array.prototype.forEach.call($('nav').querySelectorAll('button'), function (o) {
        var on = o === b;
        o.setAttribute('aria-current', String(on));
        $('view-' + o.getAttribute('data-view')).hidden = !on;
      });
      window.scrollTo(0, 0);
      // On a phone the strip is wider than the screen, so the tab you just landed on can sit off
      // to the right — reached from the bell, from a deep link, or simply because it is the ninth.
      // Being on a page whose tab you cannot see is the same as not knowing where you are.
      if (b.scrollIntoView) b.scrollIntoView({ block: 'nearest', inline: 'center', behavior: 'smooth' });
      // Reloaded on every visit, not only the first. A dashboard is read to find out what is true
      // now; a tab that shows what was true when it was last opened is worse than a slow one,
      // because nothing about it says the numbers are old.
      load(view, true);
      if (view === 'network') setTimeout(fit, 0);
    };
  });
  function show(view) {
    var b = $('nav').querySelector('[data-view="' + view + '"]');
    if (b) b.click();
  }
  $('bell').onclick = function () { show('alerts'); };
  $('ackAll').onclick = function () {
    modal({ title: 'Alle als erledigt markieren?', text: 'Die Warnungen werden nicht gelöscht, nur abgehakt — im Verlauf bleiben sie stehen.', okLabel: 'Abhaken' })
      .then(function (y) {
        if (!y) return;
        act({ action: 'ack_all_alerts' }).then(function (r) { tell('Erledigt', r.message).then(function () { loadAlerts(); load('overview', true); }); });
      });
  };
  $('refresh').onclick = loadAll;
  $('dClose').onclick = function () { $('detail').close(); };
  $('search').onclick = loadWallets;
  $('clearSearch').onclick = function () { $('q').value = ''; loadWallets(); };
  $('wTest').onchange = loadWallets;
  $('wDeleted').onchange = loadWallets;
  $('q').addEventListener('keydown', function (e) { if (e.key === 'Enter') $('search').click(); });
  $('tKind').onchange = function () { trafficOffset = 0; loadTraffic(); };
  $('tFail').onchange = function () { trafficOffset = 0; loadTraffic(); };
  $('tTest').onchange = function () { trafficOffset = 0; loadTraffic(); };
  $('sRange').onchange = renderHistory;
  $('sMetric').onchange = renderHistory;
  $('exAdd').onclick = function () {
    if (!$('exDate').value) { tell('Datum fehlt', 'Bitte den Tag angeben, an dem das Geld abgegangen ist.'); return; }
    if (!Number($('exAmount').value)) { tell('Betrag fehlt', 'Bitte den Rechnungsbetrag angeben.'); return; }
    act({
      action: 'add_expense',
      paidAt: $('exDate').value,
      kind: $('exKind').value,
      amount: Number($('exAmount').value),
      currency: $('exCurrency').value,
      amountHome: $('exHome').value,
      reference: $('exRef').value,
      note: 'im Dashboard erfasst',
    }).then(function (r) {
      if (!r.ok) { tell('Nicht erfasst', r.message); return; }
      $('exAmount').value = ''; $('exHome').value = ''; $('exRef').value = '';
      loadTax();
    });
  };
  // Built in the browser rather than on the server: the data is already here, and a download
  // that needs a round trip is a download that can fail while looking like it worked.
  $('taxCsv').onclick = function () {
    if (!taxData) return;
    var cur = taxData.homeCurrency;
    var rows = [['Monat', 'Kaeufe', 'Brutto ' + cur, 'Steuer ' + cur, 'Erloes ' + cur]];
    taxData.months.forEach(function (m) {
      rows.push([m.month, m.orders, n(m.paidGross).toFixed(2), n(m.taxCollected).toFixed(2), n(m.revenue).toFixed(2)]);
    });
    rows.push([]);
    rows.push(['Datum', 'Art', 'Betrag', 'Waehrung', 'Belastet ' + cur, 'Beleg']);
    taxData.expenses.forEach(function (e) {
      rows.push([new Date(e.paidAt).toISOString().slice(0, 10), e.kind, n(e.amount).toFixed(2),
        e.currency, e.amountHome === null ? '' : n(e.amountHome).toFixed(2), e.reference || '']);
    });
    // Semicolons and a BOM, because this is opened in a German Excel and nothing else.
    var csv = '\\ufeff' + rows.map(function (r) { return r.join(';'); }).join('\\r\\n');
    var a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    a.download = 'dictate-cloud-steuer.csv';
    a.click();
    URL.revokeObjectURL(a.href);
  };
  window.addEventListener('resize', function () { if (!$('view-network').hidden) fit(); });

  /* ------------------------------------------------------------------ Himmel */

  /*
   * The fog behind the page.
   *
   * Five soft banks of colour, each drifting on its own small orbit and each fading slowly between
   * the accent blue and a violet, so the two run into one another instead of sitting side by side.
   * Nothing has an edge, which is the point: the earlier flow field was drawn with one-pixel
   * strokes, and a one-pixel stroke is the one thing that cannot survive being stretched twelvefold.
   *
   * Same arithmetic as every version of this: it is drawn at 200 by about 125 and stretched over
   * the window, so the work is proportional to twenty-five thousand pixels rather than to the
   * several million a full-screen layer would cost. Two attempts at that in CSS pegged a GPU.
   *
   * Each bank keeps its own quarter of the canvas and only orbits inside it. Letting them all
   * circle the centre — which is what the first draft did — leaves half the window empty and piles
   * the rest into one bright lump.
   *
   * Rendered offline before shipping, at the size it is actually seen: blue and violet legible,
   * whole surface covered, nothing saturating.
   */
  function startSky() {
    var canvas = $('sky');
    var bar = $('skyBar');
    if (!canvas || !canvas.getContext) return;
    // #nosky leaves the background blank, so the cost of this can be measured against its absence
    // rather than argued about. Two versions of this feature were defended with reasoning that
    // turned out to be wrong; a switch settles it in one reload.
    if ((location.hash || '').indexOf('nosky') >= 0) return;
    var ctx = canvas.getContext('2d');
    var barCtx = bar && bar.getContext ? bar.getContext('2d') : null;
    var still = motionOff;

    var BLUE = [48, 183, 230];      // --accent
    var VIOLET = [169, 154, 240];   // --z-violet
    /*
     * home x/y, orbit x/y, the two periods that carry it, and the period on which its colour
     * crosses from one to the other. All of them awkward numbers with no common factor, so the
     * arrangement does not come back round: the shortest pair here repeats after about six hours.
     */
    var BANKS = [
      { from: BLUE,   to: VIOLET, a: .24, r: .58, hx: .20, hy: .24, ox: .19, oy: .21, px: 23, py: 31, pc: 37 },
      { from: VIOLET, to: BLUE,   a: .21, r: .54, hx: .80, hy: .28, ox: .18, oy: .23, px: 29, py: 19, pc: 43 },
      { from: BLUE,   to: VIOLET, a: .19, r: .52, hx: .50, hy: .86, ox: .23, oy: .17, px: 17, py: 37, pc: 29 },
      { from: VIOLET, to: BLUE,   a: .16, r: .46, hx: .90, hy: .80, ox: .17, oy: .21, px: 41, py: 23, pc: 53 },
      { from: BLUE,   to: VIOLET, a: .15, r: .48, hx: .08, hy: .82, ox: .21, oy: .19, px: 19, py: 43, pc: 31 },
    ];

    var w = 0, h = 0, last = -1e9, frame = 0, shownAt = 11;

    // The backing store follows the window's proportions so the banks stay round rather than
    // stretched, but never grows past a few hundred pixels on a side. That ceiling is the reason
    // this is cheap and it must not quietly rise.
    function skyMeasure() {
      var vw = window.innerWidth || 1, vh = window.innerHeight || 1;
      w = 200;
      h = Math.max(100, Math.min(320, Math.round(200 * vh / vw)));
      canvas.width = w; canvas.height = h;
      if (bar) { bar.width = w; bar.height = h; }
      // Setting width wipes the canvas, so it has to be repainted at once rather than left blank
      // until the next frame — and under reduced motion there is no next frame at all.
      skyPaint(shownAt);
    }

    function ink(from, to, t, alpha) {
      var m = 0.5 + 0.5 * Math.sin(t);
      return 'rgba(' + Math.round(from[0] + (to[0] - from[0]) * m) + ',' +
        Math.round(from[1] + (to[1] - from[1]) * m) + ',' +
        Math.round(from[2] + (to[2] - from[2]) * m) + ',' + alpha + ')';
    }

    function skyPaint(t) {
      shownAt = t;
      ctx.globalCompositeOperation = 'source-over';
      ctx.fillStyle = '#0B0F14';
      ctx.fillRect(0, 0, w, h);
      // Added rather than painted over, so where two banks meet the colours mix into a third
      // instead of one hiding the other. Safe here in a way it was not for the flow field: these
      // are wide and faint, and five of them at these alphas cannot reach white.
      ctx.globalCompositeOperation = 'lighter';
      var reach = Math.max(w, h);
      for (var i = 0; i < BANKS.length; i++) {
        var b = BANKS[i];
        var x = (b.hx + b.ox * Math.sin(t / b.px)) * w;
        var y = (b.hy + b.oy * Math.cos(t / b.py)) * h;
        var r = b.r * reach * (1 + 0.15 * Math.sin(t / (b.px * 1.6)));
        var g = ctx.createRadialGradient(x, y, 0, x, y, r);
        // The middle stop is what makes it a fog rather than a lamp: a plain two-stop gradient
        // falls off in a straight line and reads as a disc with a soft edge.
        g.addColorStop(0, ink(b.from, b.to, t / b.pc, b.a));
        g.addColorStop(0.45, ink(b.from, b.to, t / b.pc, b.a * 0.34));
        g.addColorStop(1, ink(b.from, b.to, t / b.pc, 0));
        ctx.fillStyle = g;
        // Only the square the bank actually reaches, not the whole canvas: five full-canvas fills
        // a frame would be five times the pixels for no visible difference.
        ctx.fillRect(Math.max(0, x - r), Math.max(0, y - r), Math.min(w, 2 * r), Math.min(h, 2 * r));
      }
      // The header shows the same image, not a second rendering of it: one copy of twenty-five
      // thousand pixels, which is also what keeps the two exactly in step.
      if (barCtx) barCtx.drawImage(canvas, 0, 0);
    }

    // Sixteen pictures a second. A fog has no edge to judge a frame rate by — what moves between
    // one picture and the next is a soft gradient shifting a fraction of a canvas pixel — so the
    // rate buys nothing above this, and every frame not drawn is twenty-five thousand pixels saved.
    function skyTick(ms) {
      frame = requestAnimationFrame(skyTick);
      if (ms - last < 62) return;
      last = ms;
      skyPaint(ms / 1000);
    }

    function skyRun() {
      if (frame || still) return;
      last = -1e9;
      frame = requestAnimationFrame(skyTick);
    }
    function skyHalt() {
      if (frame) { cancelAnimationFrame(frame); frame = 0; }
    }

    skyMeasure();
    // One picture either way; where motion is unwelcome that is the end of it. Unlike a field of
    // trails, a fog needs no warming up — any single moment of it is the whole thing.
    skyRun();
    // Nothing is drawn for a tab nobody is looking at. A dashboard is left open all day, and this
    // is the difference between a background that costs nothing and one that costs nothing visible.
    document.addEventListener('visibilitychange', function () {
      if (document.hidden) skyHalt(); else skyRun();
    });
    var resizeTimer = 0;
    window.addEventListener('resize', function () {
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(skyMeasure, 200);
    });
  }

  startSky();
  watchValues();

  initGraph();
  get('/admin/api/me').then(function (r) { $('who').textContent = r.email; });
  load('overview');
  // The bell is the one thing that must be right on every page, whichever view is open — an alert
  // nobody is told about is the same as no alert at all.
  loadAlerts();

  // A deep link from an alert mail: #wallet=<id> opens that account straight away.
  var hash = /^#wallet=(.+)$/.exec(location.hash || '');
  if (hash) openDetail(decodeURIComponent(hash[1]));
})();
</script>
</body>
</html>`;
