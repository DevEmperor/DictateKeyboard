import { EDGES, NODES, ZONES } from './graph';

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

// `<` is escaped so a stray `</script>` inside any description can never end the block early.
const GRAPH_JSON = JSON.stringify({ zones: ZONES, nodes: NODES, edges: EDGES })
  .replace(/</g, '\\u003c');

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
<link rel="icon" type="image/png" href="data:image/png;base64,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">
<link rel="apple-touch-icon" href="data:image/png;base64,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">
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
    --surface: #121820;
    --surface-2: #19212B;
    --line: #1E2833;
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
    --z-openai: #A99AF0;
    --z-ext: #8FA0B4;
  }
  * { box-sizing: border-box; }
  html { -webkit-text-size-adjust: 100%; }
  body {
    margin: 0; background: var(--bg); color: var(--text);
    font: 15px/1.55 ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    overflow-wrap: break-word;
  }

  header { position: sticky; top: 0; z-index: 20; background: var(--surface); border-bottom: 1px solid var(--line); padding-top: env(safe-area-inset-top); }
  /* Wraps, and every item may shrink. Without both, the row simply grew past the window: flex
     items refuse to go below their own content width by default, so on a phone the bar pushed the
     whole document 45px wider than the screen and every tab under it sat shifted and clipped. */
  .bar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 12px; padding: 10px var(--pad); }
  .bar > * { min-width: 0; }
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
  .card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 14px 16px; }
  .card.lead { border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent) inset; }
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
  .panel { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: var(--pad); }
  .scroll { overflow-x: auto; -webkit-overflow-scrolling: touch; }
  table { width: 100%; border-collapse: collapse; font-size: 13.5px; }
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

  dialog { border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface); color: var(--text); padding: 0; max-width: min(940px, 96vw); width: 100%; max-height: 92vh; }
  dialog::backdrop { background: rgba(4, 12, 18, .55); }
  .dlg-head { display: flex; align-items: center; gap: 12px; padding: 13px var(--pad); border-bottom: 1px solid var(--line); position: sticky; top: 0; background: var(--surface); z-index: 2; }
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

  .kv { display: grid; grid-template-columns: max-content 1fr; gap: 5px 14px; font-size: 13.5px; }
  .kv dt { color: var(--muted); }
  .kv dd { margin: 0; }
  .muted { color: var(--muted); }
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
  .gwrap { position: relative; border: 1px solid var(--line); border-radius: var(--radius); overflow: hidden; background: var(--surface); }
  .gtools { position: absolute; top: 10px; right: 10px; display: flex; gap: 6px; z-index: 3; flex-wrap: wrap; justify-content: flex-end; }
  .gtools button { background: var(--surface); border: 1px solid var(--line); color: var(--text); border-radius: 8px; padding: 6px 11px; font-size: 13px; font-weight: 620; cursor: pointer; }
  .gtools button[aria-pressed="true"] { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); }
  #gsvg { display: block; width: 100%; height: min(72vh, 720px); touch-action: none; cursor: grab; background: var(--bg); }
  #gsvg.dragging { cursor: grabbing; }
  .zone-bg { rx: 18; fill-opacity: .07; stroke-opacity: .35; stroke-width: 1.5; stroke-dasharray: 7 6; }
  .zone-label { font: 650 15px ui-sans-serif, system-ui, sans-serif; fill-opacity: .85; }
  .zone-sub { font: 400 12px ui-sans-serif, system-ui, sans-serif; fill: currentColor; opacity: .55; }
  .gnode rect { rx: 11; fill: var(--surface); stroke: var(--line); stroke-width: 1.5; }
  .gnode:hover rect, .gnode.sel rect { stroke: var(--accent); stroke-width: 2.5; }
  .gnode text.t { font: 650 13.5px ui-sans-serif, system-ui, sans-serif; fill: var(--text); }
  .gnode text.s { font: 400 11.5px ui-sans-serif, system-ui, sans-serif; fill: var(--muted); }
  .gnode { cursor: pointer; }
  .gedge path { fill: none; stroke-width: 1.8; opacity: .5; }
  .gedge.dim { opacity: .07; }
  .gedge.hot path { opacity: 1; stroke-width: 3; }
  /* Labels live above the nodes, so a chip that lands on a box is still readable. */
  .glabel text { font: 550 10.5px ui-sans-serif, system-ui, sans-serif; fill: var(--text); }
  .glabel rect.lbl { fill: var(--surface); stroke: var(--line); stroke-width: .8; rx: 5; }
  .glabel.dim { opacity: .12; }
  .glabel.hot rect.lbl { stroke: var(--accent); stroke-width: 1.6; }
  .glabel.hot text { fill: var(--text); }
  .legend { display: flex; gap: 14px; flex-wrap: wrap; padding: 12px var(--pad); border-top: 1px solid var(--line); font-size: 12.5px; color: var(--muted); }
  .legend span { display: inline-flex; align-items: center; gap: 6px; }
  .swatch { width: 22px; height: 3px; border-radius: 2px; }
  .ndetail { padding: var(--pad); border-top: 1px solid var(--line); }
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
    .dlg-head { padding: 11px 14px; }

    /* Two buttons and an input in one row is a scrap heap at this width. */
    .row > .grow { flex-basis: 100%; }
    .pager { justify-content: space-between; }
  }
</style>
</head>
<body>

<header>
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
        <span class="hint" tabindex="0" data-tip="Nicht gerechnet, sondern abgefragt: die Erlöse stammen aus Googles Orders-API und stehen je Kauf im Hauptbuch, die Ausgaben aus OpenAIs eigenem Kostenendpunkt."></span>
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
      <div id="planCards" class="stack"></div>
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
        <span class="hint" tabindex="0" data-tip="Zum Abgleich gedacht, nicht als Steuererklärung. Verbindlich sind Googles monatliche Auszahlungsberichte und OpenAIs Rechnungen — hier siehst du, ob die das sagen, was du erwartest, und dein Steuerberater bekommt eine Aufstellung je Monat."></span>
      </h2>
      <div id="taxYears" class="stack"></div>
    </div>

    <div class="panel">
      <h2>Ausgaben erfassen
        <span class="hint" tabindex="0" data-tip="OpenAI hat keine Schnittstelle für Aufladungen — Verbrauch ist abrufbar, Zahlungen nicht. Bei der Einnahmen-Überschuss-Rechnung ist aber genau die Aufladung die Betriebsausgabe, nicht der später verbrauchte Token. Deshalb hier von Hand, mit Rechnungsnummer."></span>
      </h2>
      <p class="sub" style="margin:0 0 12px">Trage ein, was tatsächlich von deinem Konto abgegangen ist. „Belastet" ist der Betrag auf dem Kontoauszug inklusive Fremdwährungsaufschlag — leer gelassen rechne ich mit dem EZB-Kurs des Tages und kennzeichne es als Näherung.</p>
      <div class="row">
        <input id="exDate" type="date" style="width:auto">
        <select id="exKind" style="width:auto">
          <option value="openai_topup">OpenAI-Aufladung</option>
          <option value="cloudflare">Cloudflare</option>
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
    <div class="gwrap">
      <div class="gtools">
        <button id="gAll" aria-pressed="true">Alles</button>
        <button id="gTok" aria-pressed="false">Schlüssel</button>
        <button id="gSec" aria-pressed="false">Absicherung</button>
        <button id="gOut">−</button><button id="gIn">+</button><button id="gFit">Einpassen</button>
      </div>
      <svg id="gsvg"><g id="gcam"></g></svg>
      <div class="legend" id="glegend"></div>
      <div class="ndetail" id="ndetail"></div>
    </div>
  </section>

  <section id="view-ops" class="stack" hidden></section>
</main>

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
  function get(p) { return fetch(p, { credentials: 'same-origin' }).then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); }); }
  function act(p) {
    return fetch('/admin/api/action', { method: 'POST', credentials: 'same-origin',
      headers: { 'content-type': 'application/json' }, body: JSON.stringify(p) })
      .then(function (r) { return r.json(); });
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

  function card(label, tip, value, sub, cls, extra) {
    return '<div class="card' + (cls ? ' ' + cls : '') + '"><div class="label">' + esc(label) +
      (tip ? hint(tip) : '') + '</div><div class="value">' + value + '</div>' +
      (sub ? '<div class="sub">' + sub + '</div>' : '') + (extra || '') + '</div>';
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
    return '<svg class="spark" viewBox="0 0 100 32" preserveAspectRatio="none" aria-hidden="true">' +
      '<path class="area" d="M0,32 L' + pts.join(' L') + ' L100,32 Z"></path>' +
      '<path class="line" d="M' + pts.join(' L') + '"></path>' +
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

    var html = card('Einnahmen',
      'Was nach Googles Anteil und Steuern bei dir ankommt — von Google je Bestellung gemeldet, nicht gerechnet. ' +
      'Fremdwährungen sind mit dem EZB-Kurs des Kauftags umgerechnet und zählen mit; der Kurs steht fest auf der ' +
      'Buchung, ändert sich also nachträglich nicht mehr. Testkäufe sind ausgenommen: die kosten nichts und ' +
      'bringen nichts ein.',
      money(s.revenueHome, cur),
      s.orders + ' Kauf/Käufe · Kundschaft zahlte ' + money(s.paidHome, cur) + others +
      (extra.length ? '<br>' + extra.join(' · ') : ''));

    html += card('OpenAI-Kosten',
      s.openaiConnected
        ? 'Von OpenAI abgerechnet, nur das Projekt dieses Dienstes' + (s.serviceProject ? ' („' + s.serviceProject.name + '")' : '') + '. Abgefragt, nicht geschätzt.'
        : 'Kein Admin-Schlüssel hinterlegt — hier stünde sonst, was OpenAI tatsächlich abgerechnet hat.',
      s.costUsd === null ? '—' : fmtUsd(s.costUsd),
      s.costHome === null ? esc(s.openaiReason || 'kein Projekt zugeordnet')
                          : '≈ ' + money(s.costHome, cur) + ' · ' + rateNote);

    var profitCls = s.profitHome === null ? '' : (s.profitHome < 0 ? 'crit' : '');
    html += card('Gewinn',
      'Einnahmen minus OpenAI-Kosten, beides in ' + cur + '. Der Dollarkurs kommt von der EZB, ist aber trotzdem ' +
      'nicht Googles Kurs — Play zahlt zu eigenen Kursen aus, die Zahl bleibt also eine gute Näherung. ' +
      'Cloudflare, Domain und deine Zeit stehen nirgends darin.',
      s.profitHome === null ? '—'
        : '<span' + (profitCls ? ' style="color:var(--crit)"' : '') + '>' + money(s.profitHome, cur) + '</span>',
      s.profitHome === null ? 'OpenAI nicht verbunden' : 'nach Play-Gebühr und Steuer');
    return html;
  }

  function renderOverview(o) {
    current = o;
    var bcls = o.budget.usedPercent >= 90 ? 'crit' : o.budget.usedPercent >= 60 ? 'warn' : '';
    var html = '';
    html += card('Offenes Guthaben', 'Bezahlte, aber noch nicht verbrauchte Minuten. Eine Verbindlichkeit, kein Umsatz — Arbeit, die du noch schuldest. Die Zahl, die ein Prepaid-Modell am leichtesten unterschlägt.', fmtMinutes(o.liability.seconds), 'Über alle aktiven Konten', 'lead');
    html += moneyCards(sum);
    html += '<div class="card"><div class="label">Tagesbudget' + hint('Obergrenze für die Einkaufskosten eines Tages. Ist sie erreicht, antwortet der Dienst mit 503 und die App meldet „vorübergehend nicht verfügbar".') + '</div><div class="value">' + o.budget.usedPercent + ' %</div><div class="sub">' + fmtUsd4(o.budget.spentUsd) + ' von ' + fmtUsd(o.budget.limitUsd) + '</div><div class="bar-track"><i class="' + bcls + '" style="width:' + Math.min(100, o.budget.usedPercent) + '%"></i></div></div>';
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
      $('traffic').innerHTML = r.requests.length ? '<table><thead><tr><th>Zeit</th><th>Konto</th><th>Gerät</th><th>Art</th><th class="num">Sek.</th><th class="num">Tokens</th><th class="num">Kosten</th><th class="num">HTTP</th><th class="num">ms</th></tr></thead><tbody>' +
        r.requests.map(function (x) {
          return '<tr' + (x.isTest ? ' class="is-test"' : '') + '><td>' + fmtDate(x.ts) + '</td><td class="mono">' + esc(String(x.walletId).slice(0, 8)) + '…' +
            (x.isTest ? ' <span class="pill test">Test</span>' : '') + '</td><td class="muted">' + esc(x.device || '—') +
            // A rewording has no audio, so its second count is not zero — it does not exist. Printing
            // 0 invites the reading "this dictation was empty", which is a different thing entirely.
            '</td><td>' + (x.kind === 'transcribe' ? 'Diktat' : 'Umformulierung') + '</td><td class="num">' + (x.kind === 'transcribe' ? (x.seconds || 0) : '—') +
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
    cost_drift: ['OpenAI rechnet anders ab', 'Die stille Regel: Preiserhöhung, die sonst niemand bemerkt.'],
    overall_loss: ['Insgesamt im Minus', 'Alles jemals eingenommen gegen alles jemals ausgegeben.'],
    error_rate: ['Viele Fehler', 'Meist eine Störung bei OpenAI.'],
  };

  var NUMBERS = [
    ['fastBurnPercent', 'Schnellverbrauch ab', '%', 'Anteil eines frischen Pakets, der als auffällig gilt.'],
    ['fastBurnHours', 'innerhalb von', 'Std.', 'Zeitfenster dafür.'],
    ['refundUsedPercent', 'Erstattung meldet ab', '%', 'Wie viel verbraucht sein muss, damit eine Erstattung als Verlust gilt.'],
    ['walletBudgetSharePercent', 'Konto-Anteil am Budget', '%', 'Ab welchem Anteil des Tagesbudgets ein einzelnes Konto auffällt.'],
    ['devicesPerWallet', 'Geräte je Konto', 'Stk.', 'Mehr als so viele an einem Tag deuten auf Weitergabe.'],
    ['costDriftPercent', 'Abweichung OpenAI', '%', 'Unterschied zwischen unserer Rechnung und OpenAIs Abrechnung.'],
    ['errorRatePercent', 'Fehlerquote', '%', 'Anteil fehlgeschlagener Anfragen je Stunde.'],
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
      hint('Die Sicherung des Dienstes: Sobald die geschätzten OpenAI-Kosten eines Tages diesen Betrag erreichen, antwortet der Dienst mit 503, statt weiter einzukaufen. Ein Tag kann dich also nie mehr kosten als das — egal, was sonst schiefgeht. Höher setzen heißt mehr Umsatz möglich und mehr Schaden möglich; niedriger heißt früher Feierabend für alle. Wirkt binnen einer Minute, ohne Deployment.') +
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
          '</strong> je Tag. Das ist die Obergrenze dessen, was ein einzelner Tag dich bei OpenAI ' +
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
        '<th class="num">Dein Erlös</th><th>Region</th><th>Zustand</th></tr></thead><tbody>' +
        d.purchases.map(function (p) {
          var paid = p.paidMicros != null && p.currency
            ? money(p.paidMicros / 1e6, p.currency)
            : '<span class="muted">' + money(p.priceEur, 'EUR') + ' (Liste)</span>';
          var rev = p.revenueHomeMicros != null ? money(p.revenueHomeMicros / 1e6, homeCur)
            : p.revenueMicros != null && p.currency ? money(p.revenueMicros / 1e6, p.currency)
            : '—';
          return '<tr><td>' + fmtDate(p.purchasedAt) + '</td><td class="mono">' + esc(p.productId) +
            (p.purchaseType === 0 ? ' <span class="pill test">Lizenztester</span>' : '') +
            '</td><td class="mono">' + esc(p.orderId || '—') +
            '</td><td class="num">' + Math.round(p.seconds / 60) + '</td><td class="num">' + paid +
            '</td><td class="num">' + rev + '</td><td>' + esc(p.regionCode || '—') +
            '</td><td>' + (p.state === 'voided' ? '<span class="pill crit">erstattet</span>' : '<span class="pill ok">gutgeschrieben</span>') + '</td></tr>';
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
            '</td><td class="num">' + money(c.paid, c.currency) + '</td><td class="num muted">' + money(c.tax, c.currency) +
            '</td><td class="num">' + money(c.revenue, c.currency) +
            '</td><td class="num"><strong>' + money(c.revenueHome, cur) + '</strong></td></tr>';
        }).join('') +
        '<tr><td colspan="5"><strong>Summe</strong></td><td class="num"><strong>' + money(p.revenueHomeTotal, cur) + '</strong></td></tr>' +
        '</tbody></table></div>';
      if (p.withoutFigures) {
        html += '<p class="sub">' + p.withoutFigures + ' Kauf/Käufe ohne echte Beträge — entweder vor dieser Auswertung getätigt oder die Bestellung war nicht abrufbar. Die fehlen in dieser Tabelle.</p>';
      }
      if (p.withoutRate) {
        html += '<p class="sub">' + p.withoutRate + ' Kauf/Käufe warten noch auf einen Umrechnungskurs — die Summe ist um diese Beträge zu niedrig. Der nächtliche Lauf trägt sie nach.</p>';
      }
    } else {
      html += '<div class="empty">Noch keine Käufe mit abgerufenen Beträgen.</div>';
    }

    html += '<h3 style="margin-top:18px">OpenAI-Ausgaben</h3>';
    if (f.openai.connected) {
      var byProj = f.openai.byProject || [];
      // The project that carries this service — matched by name, because its id says nothing.
      var mine = byProj.filter(function (p) { return /dictate/i.test(p.name); })[0];
      html += '<div class="grid">' +
        (mine ? card('Dictate Cloud', 'Nur das OpenAI-Projekt dieses Dienstes. Diese Zahl ist mit dem Projektfilter auf OpenAIs eigener Usage-Seite vergleichbar.', fmtUsd4(mine.usd), 'Letzte 30 Tage', 'lead') : '') +
        card('Gesamtes Konto', 'Alle Projekte deiner OpenAI-Organisation zusammen — also auch alles, was nichts mit Dictate Cloud zu tun hat.', fmtUsd(f.openai.totalUsd), 'Letzte 30 Tage · ' + byProj.length + ' Projekt(e)') +
        '</div>';

      if (byProj.length > 1) {
        html += '<div class="scroll" style="margin-top:12px"><table><thead><tr><th>Projekt</th><th class="num">Kosten (30 T)</th></tr></thead><tbody>' +
          byProj.map(function (p) {
            return '<tr><td>' + esc(p.name) + (mine && p.id === mine.id ? ' <span class="pill info">dieser Dienst</span>' : '') +
              '</td><td class="num">' + fmtUsd4(p.usd) + '</td></tr>';
          }).join('') + '</tbody></table></div>';
      }

      var recent = f.openai.days.slice(-10).reverse();
      html += recent.length ? '<div class="scroll" style="margin-top:12px"><table><thead><tr><th>Tag</th>' +
        '<th class="num">Kosten (alle Projekte)</th></tr></thead><tbody>' +
        recent.map(function (d) { return '<tr><td>' + esc(d.day) + '</td><td class="num">' + fmtUsd4(d.usd) + '</td></tr>'; }).join('') +
        '</tbody></table></div>' : '';
    } else {
      html += '<p class="sub">Nicht verbunden — ' + esc(f.openai.reason) + '.<br>' +
        'Mit einem <strong>Admin-Schlüssel</strong> (nicht dem Projektschlüssel) zeigt diese Seite, was OpenAI tatsächlich abgerechnet hat, statt nur unserer eigenen Summe:<br>' +
        '<code>npx wrangler secret put OPENAI_ADMIN_KEY</code></p>';
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
      card('Einkauf', 'Was die durchgereichten Anfragen bei OpenAI gekostet haben.', fmtUsd(sum('costUsd')), '') +
      card('Neue Konten', 'Erstmals angelegte Guthabenkonten.', sum('newWallets'), '');

    $('sDays').innerHTML = rows.length ? '<table><thead><tr><th>Tag</th><th class="num">Anfragen</th><th class="num">Diktiert</th>' +
      '<th class="num">Fehler</th><th class="num">Käufe</th><th class="num">Erlös</th><th class="num">Einkauf</th><th class="num">Neue Konten</th></tr></thead><tbody>' +
      rows.slice().reverse().map(function (r) {
        return '<tr><td>' + esc(r.day) + '</td><td class="num">' + r.requests + '</td><td class="num">' + fmtMinutes(r.seconds) +
          '</td><td class="num">' + (r.errors || '—') + '</td><td class="num">' + (r.orders || '—') +
          '</td><td class="num">' + (r.revenue ? money(r.revenue, cur) : '—') + '</td><td class="num">' + fmtUsd4(r.costUsd) +
          '</td><td class="num">' + (r.newWallets || '—') + '</td></tr>';
      }).join('') + '</tbody></table>' : '<div class="empty">Noch keine Tage erfasst.</div>';

    $('sMonths').innerHTML = histMonths.length ? '<table><thead><tr><th>Monat</th><th class="num">Anfragen</th>' +
      '<th class="num">Diktiert</th><th class="num">Verkauft</th><th class="num">Käufe</th><th class="num">Erlös</th><th class="num">Einkauf</th></tr></thead><tbody>' +
      histMonths.map(function (m) {
        return '<tr><td>' + esc(m.month) + '</td><td class="num">' + m.requests + '</td><td class="num">' + fmtMinutes(m.seconds) +
          '</td><td class="num">' + fmtMinutes(m.secondsSold) + '</td><td class="num">' + (m.orders || '—') +
          '</td><td class="num">' + (m.revenue ? money(m.revenue, cur) : '—') + '</td><td class="num">' + fmtUsd4(m.costUsd) + '</td></tr>';
      }).join('') + '</tbody></table>' : '<div class="empty">Noch keine Monate erfasst.</div>';
  }

  /**
   * The money, in one request.
   *
   * There used to be two — /summary and /finance — and each of them separately paged through
   * OpenAI's billing endpoint. Two dozen sequential fetches to answer one screen, on every single
   * page load. Now it is one call, served from a ten-minute cache, refreshed behind the response
   * when it is stale.
   */
  function loadMoney() {
    $('finance').innerHTML = skeleton('card', 3);
    return get('/admin/api/money').then(function (r) {
      sum = r.summary;
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
    openai_topup: 'OpenAI-Aufladung', cloudflare: 'Cloudflare', domain: 'Domain', other: 'Sonstiges',
  };

  function renderTax(t) {
    taxData = t;
    var cur = t.homeCurrency;

    $('taxYears').innerHTML = t.years.length ? t.years.map(function (y) {
      // The order of a profit-and-loss, so it can be read straight down: what came in, what was
      // never yours, what went back out, what you actually paid, what is left.
      var lines = [
        ['Kundschaft zahlte brutto', money(y.paidGross, cur), 'nur zur Einordnung — nie deine Einnahme'],
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
      if (y.unconverted) warn.push(y.unconverted + ' Kauf/Käufe noch ohne Umrechnungskurs — die Summe ist zu niedrig.');
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

    // OpenAI's own usage figure, next to the payments rather than instead of them.
    if (t.openaiConnected && t.openaiUsageUsd !== null) {
      $('taxYears').innerHTML += '<p class="sub">Zum Abgleich: OpenAI weist für die letzten 180 Tage ' +
        fmtUsd(t.openaiUsageUsd) + ' <strong>Verbrauch</strong> aus. Das ist nicht dieselbe Zahl wie deine Aufladungen — ' +
        'verbraucht wird nach und nach, bezahlt hast du im Voraus. Für die Steuer zählt die Aufladung.</p>';
    }

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
      : '<div class="empty">Noch nichts erfasst. Die erste OpenAI-Aufladung gehört hier hinein.</div>';

    Array.prototype.forEach.call($('taxExpenses').querySelectorAll('[data-del]'), function (b) {
      b.onclick = function () {
        modal({ title: 'Ausgabe löschen?', text: 'Die Zeile verschwindet aus allen Jahressummen.', okLabel: 'Löschen', danger: true })
          .then(function (y) { if (y) act({ action: 'delete_expense', id: Number(b.getAttribute('data-del')) }).then(loadTax); });
      };
    });

    $('taxMonths').innerHTML = t.months.length
      ? '<table><thead><tr><th>Monat</th><th class="num">Käufe</th><th class="num">Brutto</th><th class="num">Steuer</th><th class="num">Dein Erlös</th></tr></thead><tbody>' +
        t.months.map(function (m) {
          return '<tr><td>' + esc(m.month) + '</td><td class="num">' + m.orders + '</td><td class="num">' +
            money(m.paidGross, cur) + '</td><td class="num muted">' + money(m.taxCollected, cur) +
            '</td><td class="num"><strong>' + money(m.revenue, cur) + '</strong></td></tr>';
        }).join('') + '</tbody></table>'
      : '<div class="empty">Noch keine Monate mit Verkäufen.</div>';
  }

  function loadTax() {
    $('taxYears').innerHTML = skeleton('card', 2);
    return get('/admin/api/tax').then(renderTax).catch(function (e) {
      $('taxYears').innerHTML = '<div class="empty">Konnte nicht geladen werden: ' + esc(e.message) + '</div>';
    });
  }

  /* ------------------------------------------------------------------ Pläne */

  function renderPlans(p) {
    var cur = p.homeCurrency;

    $('planCards').innerHTML = p.packs.map(function (k) {
      var a = k.actual;
      var shown = a || k.model;
      var pct = Math.round(shown.marginPercent);
      var cls = pct >= 55 ? 'ok' : pct >= 35 ? 'warn' : 'crit';

      // Every line of the sum, in the order it happens: the customer pays, tax and Google come
      // off, OpenAI is bought, what is left is yours.
      // The first four lines are the buyer's own currency, because that is what happened at the
      // till. From "Dein Erlös" on it is the payout currency — mixing the two in one sum is how a
      // margin quietly comes out wrong.
      var steps = a
        ? [['Kundschaft zahlt', money(a.paid, k.currency)],
           ['davon Steuer', '− ' + money(a.tax, k.currency)],
           ['Google-Anteil', '− ' + money(a.paid - a.tax - a.revenue, k.currency)],
           ['<strong>Dein Erlös</strong>', '<strong>' + money(a.revenue, k.currency) + '</strong>'],
           (k.currency !== cur ? ['umgerechnet', money(a.revenueHome, cur)] : null),
           ['OpenAI, h\u00f6chstens (' + k.minutes + ' Min. gekauft)', '− ' + fmtUsd4(k.cost.totalUsd)],
           ['<strong>Bleibt mindestens</strong>', '<strong>' + money(a.margin, cur) + '</strong>']].filter(Boolean)
        : [['Listenpreis', money(k.listPrice, cur)],
           ['Google-Anteil (15 % angenommen)', '− ' + money(k.listPrice * 0.15, cur)],
           ['<strong>Erlös laut Modell</strong>', '<strong>' + money(k.model.revenue, cur) + '</strong>'],
           ['OpenAI, h\u00f6chstens (' + k.minutes + ' Min. gekauft)', '− ' + fmtUsd4(k.cost.totalUsd)],
           ['<strong>Bleibt mindestens</strong>', '<strong>' + money(k.model.margin, cur) + '</strong>']];

      return '<div class="card"><div class="row" style="align-items:flex-start">' +
        '<div style="flex:1;min-width:min(100%,190px)">' +
          '<div class="label">' + esc(k.name) + ' <span class="mono muted">' + esc(k.id) + '</span></div>' +
          '<div class="value">' + money(shown.margin, cur) + '</div>' +
          '<div class="sub">' + k.minutes.toLocaleString('de-DE') + ' Minuten <span class="muted">oder ~' + k.rewords.toLocaleString('de-DE') + ' Umformulierungen</span><br>' +
            k.pricePerMinuteCents.toFixed(2) + ' ct/Min. Preis · ' + k.marginPerMinuteCents.toFixed(2) + ' ct/Min. Marge</div>' +
          '<div style="margin-top:8px"><span class="pill ' + cls + '">' + pct + ' % Marge</span> ' +
            (a ? '<span class="pill info">' + a.orders + ' Verkauf/Verkäufe</span>'
               : '<span class="pill">nur Modell</span>') + '</div>' +
        '</div>' +
        '<div style="flex:1.4;min-width:min(100%,250px)"><table style="font-size:13px">' +
          steps.map(function (s) {
            return '<tr><td style="border:0;padding:3px 0">' + s[0] + '</td>' +
              '<td class="num" style="border:0;padding:3px 0">' + s[1] + '</td></tr>';
          }).join('') + '</table></div>' +
      '</div></div>';
    }).join('');

    $('planTable').innerHTML = '<table><thead><tr><th>Paket</th><th class="num">Preis</th>' +
      '<th class="num">Minuten</th><th class="num">ct/Min.</th><th class="num">OpenAI</th>' +
      '<th class="num">Erlös</th><th class="num">Marge</th><th class="num">%</th><th>Quelle</th></tr></thead><tbody>' +
      p.packs.map(function (k) {
        var s = k.actual || k.model;
        return '<tr><td>' + esc(k.name) + '</td><td class="num">' + money(k.listPrice, cur) +
          '</td><td class="num">' + k.minutes.toLocaleString('de-DE') + '</td><td class="num">' + k.pricePerMinuteCents.toFixed(2) +
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
      '</div>' +
      '<p class="sub" style="margin-top:10px">Die OpenAI-Zeile ist eine <strong>Obergrenze</strong>, keine Schätzung: ' +
      'jede Leistung wird in dieselben Sekunden eingepreist, also sind die verkauften Sekunden zugleich der ' +
      'gesamte Einkauf. Wie die Kundschaft sie aufteilt, ändert daran nichts — die Marge kann nur nach oben ' +
      'abweichen. Fixkosten stehen nirgends darin: Cloudflare, Domain und deine Zeit fehlen.</p>';
  }

  /* --------------------------------------------------------------- Netzwerk */

  var TONE = { client: 'var(--z-client)', cloudflare: 'var(--z-cf)', google: 'var(--z-google)', openai: 'var(--z-openai)', ext: 'var(--z-ext)' };
  var EKIND = { data: 'var(--accent)', auth: 'var(--z-google)', store: 'var(--muted)', notify: 'var(--z-cf)' };
  var NW = 240, NH = 66;
  var cam = { s: 1, x: 0, y: 0 }, filter = 'all', selected = null;
  var byId = {};
  GRAPH.nodes.forEach(function (n) { byId[n.id] = n; });

  function anchor(n, side) {
    var w = n.w || NW, h = n.h || NH;
    if (side === 'l') return [n.x, n.y + h / 2];
    if (side === 'r') return [n.x + w, n.y + h / 2];
    if (side === 't') return [n.x + w / 2, n.y];
    return [n.x + w / 2, n.y + h];
  }
  function route(a, sa, b, sb, bend) {
    var p = anchor(a, sa), q = anchor(b, sb), d = bend || 0;
    var x1 = p[0], y1 = p[1], x2 = q[0], y2 = q[1];
    var h1 = sa === 'l' || sa === 'r', h2 = sb === 'l' || sb === 'r';
    if (h1 && h2) {
      if (sa === sb) {
        var ox = sa === 'r' ? Math.max(x1, x2) + 58 + d : Math.min(x1, x2) - 58 - d;
        return { d: 'M' + x1 + ' ' + y1 + ' H' + ox + ' V' + y2 + ' H' + x2, lx: ox, ly: (y1 + y2) / 2 };
      }
      var mx = (x1 + x2) / 2 + d;
      return { d: 'M' + x1 + ' ' + y1 + ' H' + mx + ' V' + y2 + ' H' + x2, lx: mx, ly: (y1 + y2) / 2 };
    }
    if (!h1 && !h2) {
      if (sa === sb) {
        var oy = sa === 'b' ? Math.max(y1, y2) + 48 + d : Math.min(y1, y2) - 48 - d;
        return { d: 'M' + x1 + ' ' + y1 + ' V' + oy + ' H' + x2 + ' V' + y2, lx: (x1 + x2) / 2, ly: oy };
      }
      var my = (y1 + y2) / 2 + d;
      return { d: 'M' + x1 + ' ' + y1 + ' V' + my + ' H' + x2 + ' V' + y2, lx: (x1 + x2) / 2, ly: my };
    }
    if (h1) return { d: 'M' + x1 + ' ' + y1 + ' H' + x2 + ' V' + y2, lx: (x1 + x2) / 2, ly: y1 };
    return { d: 'M' + x1 + ' ' + y1 + ' V' + y2 + ' H' + x2, lx: x1, ly: (y1 + y2) / 2 };
  }

  function drawGraph() {
    var svgNS = 'http://www.w3.org/2000/svg';
    var parts = [];
    parts.push('<defs><marker id="arw" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="context-stroke"/></marker></defs>');

    GRAPH.zones.forEach(function (z) {
      parts.push('<rect class="zone-bg" x="' + z.x + '" y="' + z.y + '" width="' + z.w + '" height="' + z.h +
        '" fill="' + TONE[z.tone] + '" stroke="' + TONE[z.tone] + '"/>');
      parts.push('<text class="zone-label" x="' + (z.x + 16) + '" y="' + (z.y + 28) + '" fill="' + TONE[z.tone] + '">' + esc(z.label) + '</text>');
      parts.push('<text class="zone-sub" x="' + (z.x + 16) + '" y="' + (z.y + 46) + '">' + esc(z.sub) + '</text>');
    });

    // Paths first, then nodes, then labels — labels last so they are never buried under a box, and
    // in their own layer so the sizing pass below can find them.
    var labels = [];
    GRAPH.edges.forEach(function (e, i) {
      var a = byId[e.from], b = byId[e.to];
      if (!a || !b) return;
      var r = route(a, e.fromSide, b, e.toSide, e.bend);
      var dim = (filter === 'token' && !e.token) || (filter === 'guard' && !e.guard);
      var label = filter === 'guard' && e.guard ? e.guard : (filter === 'token' && e.token ? e.token : e.label);
      parts.push('<g class="gedge' + (dim ? ' dim' : '') + '" data-e="' + i + '">' +
        '<path d="' + r.d + '" stroke="' + EKIND[e.kind] + '" marker-end="url(#arw)"/></g>');
      labels.push('<g class="glabel' + (dim ? ' dim' : '') + '" data-e="' + i + '" data-x="' + r.lx + '" data-y="' + r.ly + '">' +
        '<rect class="lbl"/><text text-anchor="middle">' + esc(label) + '</text></g>');
    });

    GRAPH.nodes.forEach(function (n) {
      var w = n.w || NW, h = n.h || NH;
      var zone = GRAPH.zones.filter(function (z) { return z.id === n.zone; })[0];
      parts.push('<g class="gnode' + (selected === n.id ? ' sel' : '') + '" data-n="' + esc(n.id) + '">' +
        '<rect x="' + n.x + '" y="' + n.y + '" width="' + w + '" height="' + h + '"/>' +
        '<rect x="' + n.x + '" y="' + n.y + '" width="4" height="' + h + '" fill="' + TONE[zone ? zone.tone : 'client'] + '" rx="2"/>' +
        '<text class="t" x="' + (n.x + 16) + '" y="' + (n.y + 27) + '">' + esc(n.label) + '</text>' +
        '<text class="s" x="' + (n.x + 16) + '" y="' + (n.y + 46) + '">' + esc(n.sub) + '</text></g>');
    });

    $('gcam').innerHTML = parts.join('') + labels.join('');
    layoutLabels();
    Array.prototype.forEach.call($('gcam').querySelectorAll('.gnode'), function (g) {
      g.addEventListener('click', function (ev) { ev.stopPropagation(); selectNode(g.getAttribute('data-n')); });
    });
    Array.prototype.forEach.call($('gcam').querySelectorAll('.gedge'), function (g) {
      var mate = $('gcam').querySelector('.glabel[data-e="' + g.getAttribute('data-e') + '"]');
      g.addEventListener('mouseenter', function () { g.classList.add('hot'); if (mate) mate.classList.add('hot'); });
      g.addEventListener('mouseleave', function () { g.classList.remove('hot'); if (mate) mate.classList.remove('hot'); });
    });
  }

  /**
   * Sizes each label chip to the text it actually contains, then pushes overlapping ones apart.
   *
   * The width used to be guessed from the character count, which is wrong for every string that is
   * not average — long labels spilled out of their chip and short ones floated in whitespace. SVG
   * can be asked instead, and a measured box is also what makes the collision pass below possible.
   */
  function layoutLabels() {
    var els = Array.prototype.slice.call($('gcam').querySelectorAll('.glabel'));
    var boxes = els.map(function (g) {
      var text = g.querySelector('text'), rect = g.querySelector('rect');
      var x = Number(g.getAttribute('data-x')), y = Number(g.getAttribute('data-y'));
      var w = 0;
      try { w = text.getComputedTextLength(); } catch (err) { w = text.textContent.length * 5.6; }
      return { g: g, text: text, rect: rect, x: x, y: y, w: w + 12, h: 17 };
    });

    // Nudge downwards until nothing overlaps. Two passes are plenty at this density and keep the
    // arrangement stable — a full force layout would move labels around on every redraw.
    for (var pass = 0; pass < 2; pass++) {
      for (var i = 0; i < boxes.length; i++) {
        for (var j = i + 1; j < boxes.length; j++) {
          var a = boxes[i], b = boxes[j];
          if (Math.abs(a.x - b.x) < (a.w + b.w) / 2 && Math.abs(a.y - b.y) < a.h + 2) {
            var shift = (a.h + 3) - Math.abs(a.y - b.y);
            if (a.y <= b.y) { b.y += shift; } else { a.y += shift; }
          }
        }
      }
    }

    boxes.forEach(function (bx) {
      bx.rect.setAttribute('x', bx.x - bx.w / 2);
      bx.rect.setAttribute('y', bx.y - bx.h / 2);
      bx.rect.setAttribute('width', bx.w);
      bx.rect.setAttribute('height', bx.h);
      bx.text.setAttribute('x', bx.x);
      bx.text.setAttribute('y', bx.y + 4);
    });
  }

  function selectNode(id) {
    selected = id;
    var n = byId[id];
    drawGraph();
    if (!n) { $('ndetail').innerHTML = ''; return; }
    var links = GRAPH.edges.filter(function (e) { return e.from === id || e.to === id; });
    var html = '<h3>' + esc(n.label) + ' <span class="muted" style="font-weight:400">' + esc(n.sub) + '</span></h3>' +
      '<p class="sub" style="font-size:13.5px;margin:0 0 8px">' + n.detail + '</p>';
    if (n.holds && n.holds.length) html += '<div class="label">Hält</div><div class="taglist">' + n.holds.map(function (t) { return '<span class="tag key">' + esc(t) + '</span>'; }).join('') + '</div>';
    if (n.guards && n.guards.length) html += '<div class="label" style="margin-top:10px">Absicherung</div><div class="taglist">' + n.guards.map(function (t) { return '<span class="tag guard">' + esc(t) + '</span>'; }).join('') + '</div>';
    if (links.length) {
      html += '<div class="label" style="margin-top:10px">Verbindungen</div><div class="scroll"><table style="margin-top:6px"><tbody>' +
        links.map(function (e) {
          var other = e.from === id ? byId[e.to] : byId[e.from];
          var dir = e.from === id ? '→' : '←';
          return '<tr><td>' + dir + ' <strong>' + esc(other ? other.label : '?') + '</strong></td><td class="wrap muted">' + esc(e.label) +
            (e.token ? ' · <span class="tag key">' + esc(e.token) + '</span>' : '') +
            (e.guard ? ' · <span class="tag guard">' + esc(e.guard) + '</span>' : '') + '</td></tr>';
        }).join('') + '</tbody></table></div>';
    }
    if (n.source) html += '<p class="sub" style="margin-top:10px">Quelle: <code>' + esc(n.source) + '</code></p>';
    $('ndetail').innerHTML = html;
  }

  function applyCam() { $('gcam').setAttribute('transform', 'translate(' + cam.x + ',' + cam.y + ') scale(' + cam.s + ')'); }
  // Measured from the zones themselves, plus the margin they already sit in. A hard-coded extent
  // silently crops the day someone adds a zone at the bottom, and it crops on the phone first.
  var EXTENT = (function () {
    var w = 0, h = 0;
    GRAPH.zones.forEach(function (z) {
      if (z.x + z.w > w) w = z.x + z.w;
      if (z.y + z.h > h) h = z.y + z.h;
    });
    return { w: w + 24, h: h + 60 };
  })();
  function fit() {
    var box = $('gsvg').getBoundingClientRect();
    var s = Math.min(box.width / EXTENT.w, box.height / EXTENT.h);
    cam.s = s; cam.x = (box.width - EXTENT.w * s) / 2; cam.y = (box.height - EXTENT.h * s) / 2;
    applyCam();
  }
  function zoomAt(factor, cx, cy) {
    var ns = Math.max(0.25, Math.min(3, cam.s * factor));
    cam.x = cx - (cx - cam.x) * (ns / cam.s);
    cam.y = cy - (cy - cam.y) * (ns / cam.s);
    cam.s = ns; applyCam();
  }

  function initGraph() {
    var svg = $('gsvg'), pts = {}, last = null, pinch = null;
    svg.addEventListener('pointerdown', function (e) {
      svg.setPointerCapture(e.pointerId); pts[e.pointerId] = { x: e.clientX, y: e.clientY };
      if (Object.keys(pts).length === 1) { last = { x: e.clientX, y: e.clientY }; svg.classList.add('dragging'); }
    });
    svg.addEventListener('pointermove', function (e) {
      if (!pts[e.pointerId]) return;
      pts[e.pointerId] = { x: e.clientX, y: e.clientY };
      var ids = Object.keys(pts);
      if (ids.length >= 2) {
        var a = pts[ids[0]], b = pts[ids[1]];
        var dist = Math.hypot(a.x - b.x, a.y - b.y);
        var box = svg.getBoundingClientRect();
        var mid = { x: (a.x + b.x) / 2 - box.left, y: (a.y + b.y) / 2 - box.top };
        if (pinch) zoomAt(dist / pinch, mid.x, mid.y);
        pinch = dist; last = null; return;
      }
      if (last) { cam.x += e.clientX - last.x; cam.y += e.clientY - last.y; last = { x: e.clientX, y: e.clientY }; applyCam(); }
    });
    function up(e) { delete pts[e.pointerId]; if (!Object.keys(pts).length) { last = null; pinch = null; svg.classList.remove('dragging'); } }
    svg.addEventListener('pointerup', up); svg.addEventListener('pointercancel', up);
    svg.addEventListener('wheel', function (e) {
      e.preventDefault();
      var box = svg.getBoundingClientRect();
      zoomAt(e.deltaY < 0 ? 1.12 : 1 / 1.12, e.clientX - box.left, e.clientY - box.top);
    }, { passive: false });
    svg.addEventListener('click', function () { selected = null; drawGraph(); $('ndetail').innerHTML = '<p class="sub" style="margin:0">Auf einen Knoten tippen, um Schlüssel, Absicherung und Verbindungen zu sehen.</p>'; });

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

    $('glegend').innerHTML =
      '<span><i class="swatch" style="background:var(--accent)"></i>Nutzdaten</span>' +
      '<span><i class="swatch" style="background:var(--z-google)"></i>Authentifizierung</span>' +
      '<span><i class="swatch" style="background:var(--muted)"></i>Speicher</span>' +
      '<span><i class="swatch" style="background:var(--z-cf)"></i>Benachrichtigung</span>' +
      '<span class="muted">Ziehen zum Verschieben · Rad oder zwei Finger zum Zoomen · Knoten antippen für Details</span>';
    $('ndetail').innerHTML = '<p class="sub" style="margin:0">Auf einen Knoten tippen, um Schlüssel, Absicherung und Verbindungen zu sehen.</p>';
    drawGraph();
    setTimeout(fit, 0);
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
      var money = sum ? Promise.resolve() : loadMoney();
      return get('/admin/api/overview').then(function (o) {
        // Wait for the money only if it is not in yet, so the first paint is not held up by
        // Google and OpenAI — but never render the cards twice for nothing.
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
    ops: function () { return loaded.overview ? null : LOADERS.overview(); },
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
    sum = null;
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
      load(view);
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
