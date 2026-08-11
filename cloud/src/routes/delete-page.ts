/**
 * The web route for deleting a credit account.
 *
 * Google requires a way to delete an account that does not go through the app, because someone who
 * has already uninstalled must still be able to get rid of their data. Served by the Worker itself
 * rather than from the marketing site: same origin as the API, no second deployment to keep in
 * step, and no chance of the page outliving the endpoint it calls.
 *
 * **In English, not German.** Whoever lands here could be anywhere — the app ships in 21 languages
 * and the store sells worldwide. English is the one that fails least often. The app's own deletion
 * dialog is translated properly; this page is the fallback for people who no longer have the app,
 * and a fallback nobody can read is not one.
 *
 * The recovery code is the only handle there is — no email, no password, nothing else was ever
 * collected. That is not a weakness of this page but the reason it can exist at all: **anyone
 * holding the code can already spend the balance**, so being able to delete it grants an attacker
 * nothing they did not already have.
 *
 * Two steps, always. The first only looks, and shows the exact number of minutes about to be
 * destroyed; the second destroys them. A page that deleted on the first submit would turn a
 * mistyped intention into an unrecoverable loss.
 */

export const DELETE_PAGE_HTML = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="robots" content="noindex, nofollow">
<meta name="theme-color" content="#0B0F14">
<title>Dictate Cloud — Delete your credit account</title>
<style>
  :root {
    color-scheme: dark;
    --accent: #30B7E6; --accent-ink: #04121A;
    --bg: #0B0F14; --surface: #121820; --line: #1E2833;
    --text: #E6EDF3; --muted: #7D8B9A; --crit: #F85149; --ok: #3FB950;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; padding: 28px 16px; background: var(--bg); color: var(--text);
    font: 16px/1.6 ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  }
  main { max-width: 560px; margin: 0 auto; }
  .brand { display: flex; align-items: center; gap: 9px; font-weight: 680; margin-bottom: 22px; }
  .dot { width: 11px; height: 11px; border-radius: 50%; background: var(--accent); }
  h1 { font-size: 24px; line-height: 1.25; margin: 0 0 10px; letter-spacing: -0.02em; text-wrap: balance; }
  p { margin: 0 0 14px; }
  .muted { color: var(--muted); font-size: 14.5px; }
  .card { background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 20px; margin-bottom: 16px; }
  .card.danger { border-color: var(--crit); background: color-mix(in srgb, var(--crit) 7%, var(--surface)); }
  label { display: block; font-size: 13px; color: var(--muted); margin-bottom: 6px; text-transform: uppercase; letter-spacing: .07em; font-weight: 640; }
  input {
    width: 100%; background: var(--bg); border: 1px solid var(--line); border-radius: 10px;
    padding: 13px 14px; color: inherit; font: 600 18px/1.3 ui-monospace, SFMono-Regular, Menlo, monospace;
    letter-spacing: .08em; text-transform: uppercase;
  }
  input:focus { outline: 2px solid var(--accent); outline-offset: -1px; border-color: transparent; }
  button {
    font: inherit; font-weight: 660; border: 0; border-radius: 10px; padding: 13px 20px;
    cursor: pointer; background: var(--accent); color: var(--accent-ink); width: 100%; margin-top: 14px;
  }
  button.danger { background: var(--crit); color: #fff; }
  button.ghost { background: transparent; color: var(--text); border: 1px solid var(--line); }
  button:disabled { opacity: .5; cursor: not-allowed; }
  button:focus-visible, input:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
  .row { display: flex; gap: 10px; }
  .row button { margin-top: 0; }
  dl { display: grid; grid-template-columns: max-content 1fr; gap: 6px 16px; margin: 0 0 4px; font-size: 15px; }
  dt { color: var(--muted); }
  dd { margin: 0; font-variant-numeric: tabular-nums; }
  .big { font-size: 30px; font-weight: 700; letter-spacing: -0.02em; }
  .msg { border-radius: 10px; padding: 12px 14px; font-size: 14.5px; margin-top: 14px; }
  .msg.err { background: color-mix(in srgb, var(--crit) 14%, transparent); color: var(--crit); }
  .msg.ok { background: color-mix(in srgb, var(--ok) 14%, transparent); color: var(--ok); }
  ul { margin: 6px 0 0; padding-left: 20px; }
  li { margin-bottom: 5px; font-size: 14.5px; }
  [hidden] { display: none !important; }
  footer { margin-top: 26px; font-size: 13px; color: var(--muted); }
  a { color: var(--accent); }
</style>
</head>
<body>
<main>
  <div class="brand"><span class="dot"></span>Dictate&nbsp;Cloud</div>

  <section id="step1">
    <h1>Delete your credit account</h1>
    <p class="muted">This permanently deletes your Dictate Cloud credit. You will need your recovery
      code — you can find it in the app under <strong>Settings → Dictate Cloud</strong>. It is the
      only way we can identify your account: we store no name, no email address and no login.</p>
    <div class="card">
      <label for="code">Recovery code</label>
      <input id="code" placeholder="DICT-XXXX-XXXX-XXXX" autocomplete="off" spellcheck="false" inputmode="latin" aria-describedby="err1">
      <button id="check">Find my account</button>
      <div id="err1" class="msg err" role="alert" hidden></div>
    </div>
    <!--
      Visible before a code is entered, on purpose. Google requires the deletion page to state what
      is deleted, what is kept and for how long — and whoever checks that arrives without a recovery
      code, so anything behind the form does not count as stated.
    -->
    <div class="card">
      <p style="margin:0"><strong>What deletion removes</strong></p>
      <ul>
        <li>Your credit account and any remaining balance</li>
        <li>Your recovery code</li>
        <li>Every signed-in device — the app can no longer dictate through Dictate Cloud</li>
        <li>Your usage log: when you dictated, for how long, and whether it worked</li>
      </ul>
      <p style="margin:14px 0 0"><strong>What is kept, and for how long</strong></p>
      <ul>
        <li><strong>Records of your purchases, for ten years.</strong> German tax law requires this
          (§ 147 AO). Each record holds an order number, an amount and a date — nothing that leads
          back to you once the account is gone.</li>
        <li><strong>An empty entry where the account was.</strong> Those records refer to it, so it
          cannot be dropped outright. What remains is the random identifier, two dates and the
          minute totals — no recovery code, no device, nothing that can be signed in to.</li>
        <li><strong>A one-way hash of this account's own identifier, for 24 months.</strong> Only
          so that repeated refunds after full consumption can be recognised. It is a random
          identifier, not anything of Google's, and says nothing about your Play account, your name
          or your email address. It is erased after the 24 months.</li>
      </ul>
      <p style="margin:14px 0 0"><strong>What was never stored anyway</strong></p>
      <ul>
        <li>Your recordings and transcripts. They pass through the server and are never written to
          disk, so there is nothing of them to delete.</li>
        <li>Your name, email address or Google account. None of these are collected.</li>
      </ul>
      <p class="muted" style="margin:14px 0 0"><strong>Any remaining credit is forfeited.</strong>
        It is not refunded. If you want your money back, contact Google Play before deleting here.</p>
    </div>

    <p class="muted">Still have the app installed? It is easier there — the same option sits under
      Dictate Cloud.</p>
  </section>

  <section id="step2" hidden>
    <h1>This is what will be deleted</h1>
    <div class="card danger">
      <dl>
        <dt>Remaining credit</dt><dd><span class="big" id="minutes">—</span> minutes</dd>
        <dt>Rewordings</dt><dd id="rewords">—</dd>
        <dt>Purchases</dt><dd id="purchases">—</dd>
      </dl>
      <p style="margin-top:14px"><strong>This credit is forfeited.</strong> It is not refunded and
        cannot be restored — not by you and not by us. If you want your money back, contact Google
        Play first, before deleting here.</p>
    </div>
    <div class="card">
      <p style="margin:0"><strong>What goes</strong></p>
      <ul>
        <li>Your credit and your recovery code</li>
        <li>Every signed-in device — the app will no longer be able to dictate through Dictate Cloud</li>
        <li>Your usage log</li>
      </ul>
      <p style="margin:14px 0 0"><strong>What stays</strong></p>
      <ul>
        <li>The records of your purchases. German tax law obliges us to keep those for ten years
          (§ 147 AO). They hold an order number and an amount — nothing that leads back to you once
          the account itself is gone.</li>
      </ul>
    </div>
    <div class="row">
      <button class="ghost" id="back">Cancel</button>
      <button class="danger" id="confirm">Delete permanently</button>
    </div>
    <div id="err2" class="msg err" role="alert" hidden></div>
  </section>

  <section id="step3" hidden>
    <h1>Account deleted</h1>
    <div class="card">
      <div class="msg ok" style="margin-top:0">Your credit account has been deleted completely.</div>
      <p class="muted" id="summary" style="margin-top:14px"></p>
      <p class="muted">You can keep using the app — with your own API key, or with on-device
        recognition. Neither of those involves us at all.</p>
    </div>
  </section>

  <footer>
    Questions? <a href="mailto:contact@devemperor.net">contact@devemperor.net</a>
  </footer>
</main>

<script>
(function () {
  var $ = function (id) { return document.getElementById(id); };
  var walletCode = null;

  function show(step) {
    $('step1').hidden = step !== 1;
    $('step2').hidden = step !== 2;
    $('step3').hidden = step !== 3;
    window.scrollTo(0, 0);
  }
  function fail(el, message) {
    el.textContent = message;
    el.hidden = false;
  }
  function post(body) {
    return fetch('/v1/wallet/delete-by-code', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
    }).then(function (r) { return r.json().then(function (d) { return { status: r.status, data: d }; }); });
  }

  $('check').onclick = function () {
    var code = $('code').value.trim();
    $('err1').hidden = true;
    if (!code) { fail($('err1'), 'Please enter your recovery code.'); return; }
    $('check').disabled = true;
    post({ code: code }).then(function (res) {
      $('check').disabled = false;
      if (res.status !== 200) {
        fail($('err1'), (res.data && res.data.error && res.data.error.message) ||
          'No credit account matches this code.');
        return;
      }
      walletCode = code;
      $('minutes').textContent = res.data.minutes_left;
      $('rewords').textContent = res.data.rewords_left;
      $('purchases').textContent = res.data.purchases;
      show(2);
    }).catch(function () {
      $('check').disabled = false;
      fail($('err1'), 'Could not reach the server. Please try again.');
    });
  };

  $('back').onclick = function () { walletCode = null; show(1); };

  $('confirm').onclick = function () {
    $('err2').hidden = true;
    $('confirm').disabled = true;
    post({ code: walletCode, confirm: true }).then(function (res) {
      $('confirm').disabled = false;
      if (res.status !== 200) {
        fail($('err2'), (res.data && res.data.error && res.data.error.message) ||
          'Deleting the account failed.');
        return;
      }
      var minutes = res.data.forfeited_minutes;
      var kept = res.data.purchases_retained;
      $('summary').textContent =
        minutes + ' minute' + (minutes === 1 ? '' : 's') + ' of credit were forfeited. ' +
        kept + ' purchase record' + (kept === 1 ? '' : 's') + ' remain for tax purposes.';
      show(3);
    }).catch(function () {
      $('confirm').disabled = false;
      fail($('err2'), 'Could not reach the server. Please try again.');
    });
  };

  $('code').addEventListener('keydown', function (e) { if (e.key === 'Enter') $('check').click(); });
})();
</script>
</body>
</html>`;
