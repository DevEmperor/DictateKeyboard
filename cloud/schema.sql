-- Dictate Cloud — D1 schema
--
-- Principle: D1 is the *ledger* (what was bought and what was spent) and the source for the
-- dashboard. The authoritative balance lives in the Durable Object per wallet, because only
-- there is "check and deduct" atomic. The `seconds_left`/`rewords_left` columns here are a
-- copy for list views and are allowed to lag by seconds.
--
-- What is NOT here: audio, transcripts, prompt contents. Ever. See README.

-- One credit account. No personal data: no email, no name, no Google account.
CREATE TABLE IF NOT EXISTS wallets (
  id                 TEXT PRIMARY KEY,           -- UUID, also the name of the Durable Object
  created_at         INTEGER NOT NULL,           -- unix ms
  status             TEXT NOT NULL DEFAULT 'active',   -- active | blocked | deleted
  -- A deleted account keeps its row rather than losing it, for one blunt reason: `purchases`
  -- references this table and those receipts must survive ten years (§ 147 AO), so the row they
  -- point at cannot go. What goes is everything on it that could lead back to a person — the
  -- recovery hash, the previous-account hash, the note — leaving a random id and totals.
  -- See routes/delete.ts.
  deleted_at         INTEGER,
  recovery_hash      TEXT NOT NULL,              -- SHA-256 of the recovery code; emptied on deletion
  seconds_left       INTEGER NOT NULL DEFAULT 0, -- copy from the DO
  rewords_left       INTEGER NOT NULL DEFAULT 0, -- copy from the DO
  seconds_bought     INTEGER NOT NULL DEFAULT 0,
  seconds_used       INTEGER NOT NULL DEFAULT 0,
  last_seen_at       INTEGER,
  note               TEXT,                       -- internal note from the dashboard

  -- Yours, not a customer's. Every money figure in the dashboard excludes these, because a
  -- licence tester's order carries the nominal price with zero tax and zero revenue: counted in,
  -- it inflates the order count and drags every average down.
  --
  -- Set automatically where it can be known (Google reports purchaseType 0 for a licence tester)
  -- and by hand for anything else. `bootstrap` is history: it marks the accounts a setup route
  -- created before billing worked, and that route is gone.
  is_test            INTEGER NOT NULL DEFAULT 0,
  test_reason        TEXT,                       -- license_tester | bootstrap | manual

  -- SHA-256 of the wallet id the app attached to the purchase, which Google echoes back as
  -- `obfuscatedExternalAccountId`.
  --
  -- **Not a Google identifier**, despite the name of the field it arrives in — it is our own value
  -- coming back. It exists so a refund history survives a reinstall, where a fresh wallet would
  -- otherwise reset the tally at exactly the moment it starts to mean something.
  --
  -- The limit follows from where it comes from: it is only present when the app *had* an account
  -- at the moment of purchase. A first purchase carries nothing, and so does one made after the
  -- account was deleted — which is the gap this does not close.
  play_account_hash  TEXT,
  -- How often a purchase on this wallet was clawed back.
  void_count         INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_wallets_play_account ON wallets(play_account_hash);
CREATE INDEX IF NOT EXISTS idx_wallets_recovery ON wallets(recovery_hash);
CREATE INDEX IF NOT EXISTS idx_wallets_last_seen ON wallets(last_seen_at);

-- Access tokens. Several per wallet are fine (phone, tablet, reinstalled).
-- Only the hash is stored: whoever gets the database cannot sign in with it.
CREATE TABLE IF NOT EXISTS tokens (
  token_hash   TEXT PRIMARY KEY,
  wallet_id    TEXT NOT NULL REFERENCES wallets(id) ON DELETE CASCADE,
  created_at   INTEGER NOT NULL,
  last_seen_at INTEGER,
  label        TEXT,                             -- e.g. "Pixel 8" — reported by the client, informational
  revoked_at   INTEGER
);
CREATE INDEX IF NOT EXISTS idx_tokens_wallet ON tokens(wallet_id);

-- Redeemed Play purchases. The purchase token is the primary key: that makes redeeming the
-- same purchase twice impossible without any extra logic.
CREATE TABLE IF NOT EXISTS purchases (
  purchase_token TEXT PRIMARY KEY,
  wallet_id      TEXT NOT NULL REFERENCES wallets(id),
  product_id     TEXT NOT NULL,
  order_id       TEXT,
  seconds        INTEGER NOT NULL,               -- granted
  rewords        INTEGER NOT NULL,
  -- The German list price from config.ts, NOT what Google actually charged: Play converts per
  -- country and the buyer's currency is their own. Good enough to see which pack sells; the
  -- binding figures are the payout reports in the Play Console.
  price_eur      REAL,
  -- Billing region Google reports, so the revenue view can at least say which price list
  -- applied. Only ever available going forward — a purchase not recorded with it cannot be
  -- annotated later, which is why it is captured from the first sale rather than at dashboard time.
  region_code    TEXT,
  quantity       INTEGER NOT NULL DEFAULT 1,

  -- What the order was actually worth, read from Google's Orders API rather than assumed.
  -- All in integer micros of `currency`, so summing thousands of orders never drifts.
  --   paid    = what the customer paid, including tax
  --   tax     = the tax share of that
  --   revenue = what reaches the developer after Google's cut — the only figure that is income
  -- Null on older rows and whenever the order could not be read; the ledger falls back to the
  -- list price then, and the dashboard says which of the two it is showing.
  --
  -- `revenue_micros` is null far more often than the other two, and not by accident: Google states
  -- the buyer's payment immediately but the developer's share only once the payment has settled.
  -- Null therefore means "not accounted for yet", 0 means "really nothing" (a licence tester), and
  -- `orders.ts` asks again each night until one of the two is true.
  paid_micros    INTEGER,
  tax_micros     INTEGER,
  revenue_micros INTEGER,
  currency       TEXT,
  buyer_country  TEXT,

  -- What Google said about the order itself, and when it was last asked. Without the timestamp and
  -- the counter, a purchase nobody could get figures for is indistinguishable from one nobody asked
  -- about — and the nightly sync would have no way to give up on a hopeless case.
  order_state     TEXT,                            -- PENDING | PROCESSED | REFUNDED …
  order_synced_at INTEGER,
  order_attempts  INTEGER NOT NULL DEFAULT 0,

  -- Absent on a real sale, 0 for a licence tester. The only reliable way to tell your own test
  -- purchases from income after the fact.
  purchase_type  INTEGER,

  -- The revenue converted into the payout currency, and the rate it was converted with.
  --
  -- Written **once, at redemption, with the rate of that day** and never recalculated. A figure
  -- that silently changes because a rate moved is not bookkeeping. It also means a sale in CHF
  -- or PLN can be added to the total at all — before this, only euro sales counted.
  fx_rate             REAL,
  revenue_home_micros INTEGER,

  purchased_at   INTEGER NOT NULL,
  state          TEXT NOT NULL DEFAULT 'granted' -- granted | voided
);
CREATE INDEX IF NOT EXISTS idx_purchases_wallet ON purchases(wallet_id);
CREATE INDEX IF NOT EXISTS idx_purchases_time ON purchases(purchased_at);

-- Consumption. One row per request — metadata only.
CREATE TABLE IF NOT EXISTS usage_log (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  wallet_id   TEXT NOT NULL,
  -- Which device made the request, joinable to `tokens`. One account may run on a phone, a
  -- tablet and a watch, and without this the dashboard can only ever total them together.
  -- It is a pseudonym, not an identity: the hash says "the same device as before", nothing more.
  token_hash  TEXT,
  ts          INTEGER NOT NULL,
  kind        TEXT NOT NULL,                     -- transcribe | reword
  -- Who the request was routed to, and with which model. Both, because they answer different
  -- questions: the provider says where the content went, the model says at which price. Swapping
  -- one Workers AI model for another moves the second and not the first. NULL only on rows written
  -- before these columns existed.
  provider    TEXT,                              -- openai | workers-ai
  model       TEXT,                              -- gpt-transcribe | @cf/openai/whisper-large-v3-turbo | …
  seconds     INTEGER NOT NULL DEFAULT 0,        -- billed audio seconds
  tokens_in   INTEGER NOT NULL DEFAULT 0,
  tokens_out  INTEGER NOT NULL DEFAULT 0,
  -- Neurons × 10⁶, as reported by Workers AI rather than computed from a price list. A quantity
  -- keeps its meaning when a price changes, and it is the only figure that can be held against
  -- Cloudflare's own count. 0 for OpenAI — a correct figure, not a missing one.
  neurons_micro INTEGER NOT NULL DEFAULT 0,
  cost_nano   INTEGER NOT NULL DEFAULT 0,        -- upstream cost in nano-USD, at list price
  status      INTEGER NOT NULL,                  -- HTTP status the client received
  ms          INTEGER                            -- request duration
);
CREATE INDEX IF NOT EXISTS idx_usage_wallet_ts ON usage_log(wallet_id, ts);
CREATE INDEX IF NOT EXISTS idx_usage_ts ON usage_log(ts);
CREATE INDEX IF NOT EXISTS idx_usage_token ON usage_log(token_hash);

-- Every action taken from the dashboard. Without this the numbers stop adding up later.
CREATE TABLE IF NOT EXISTS admin_log (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  ts          INTEGER NOT NULL,
  actor       TEXT NOT NULL,                     -- email from Cloudflare Access
  wallet_id   TEXT,
  action      TEXT NOT NULL,                     -- gift | deduct | block | unblock | merge | ...
  delta_secs  INTEGER NOT NULL DEFAULT 0,
  delta_words INTEGER NOT NULL DEFAULT 0,
  note        TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_admin_ts ON admin_log(ts);

-- Daily totals for the dashboard and the spending cap. The kill switch itself lives in the
-- GlobalGuard DO; this is the queryable history.
CREATE TABLE IF NOT EXISTS daily_totals (
  day         TEXT PRIMARY KEY,                  -- YYYY-MM-DD (UTC)
  requests    INTEGER NOT NULL DEFAULT 0,
  seconds     INTEGER NOT NULL DEFAULT 0,
  rewords     INTEGER NOT NULL DEFAULT 0,
  cost_nano   INTEGER NOT NULL DEFAULT 0,
  -- The Workers AI share of cost_nano, so a mixed day can still be split after usage_log has been
  -- pruned. Equal to cost_nano once everything has moved; during the changeover it is the only
  -- thing that keeps the two providers apart in the roll-up that survives.
  cost_nano_cf INTEGER NOT NULL DEFAULT 0,
  errors      INTEGER NOT NULL DEFAULT 0,
  -- Neurons × 10⁶. Belongs here and not only in usage_log: that table is pruned after 90 days, and
  -- a ledger with three months of memory cannot answer what the spring cost.
  neurons_micro INTEGER NOT NULL DEFAULT 0,

  -- Test traffic, counted separately rather than filtered out later. The roll-up is the only
  -- thing that survives the 90-day prune, so a history that cannot separate your own testing
  -- from real use can never be corrected afterwards. The columns above are real traffic only.
  test_requests  INTEGER NOT NULL DEFAULT 0,
  test_seconds   INTEGER NOT NULL DEFAULT 0,
  test_cost_nano INTEGER NOT NULL DEFAULT 0,
  -- Separate from the money, like its neighbours — but it has to be added *back* for the free
  -- allowance. Cloudflare's 10 000 neurons a day are account-wide and do not care whose request it
  -- was, so anything reading the allowance sums both neuron columns.
  test_neurons_micro INTEGER NOT NULL DEFAULT 0
);

-- Anything worth waking up for. One row per occurrence; `dedupe_key` plus a cooldown keeps a
-- condition that persists for hours from becoming an inbox full of the same sentence.
CREATE TABLE IF NOT EXISTS alerts (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  ts          INTEGER NOT NULL,
  kind        TEXT NOT NULL,                     -- budget | refund_loss | fast_burn | ...
  severity    TEXT NOT NULL,                     -- critical | notice
  wallet_id   TEXT,
  title       TEXT NOT NULL,
  detail      TEXT NOT NULL,
  value       REAL,                              -- the number the rule tripped on
  dedupe_key  TEXT NOT NULL,
  sent_at     INTEGER,                           -- when it left as mail; null = digest only
  ack_at      INTEGER,
  ack_by      TEXT
);
CREATE INDEX IF NOT EXISTS idx_alerts_ts ON alerts(ts);
CREATE INDEX IF NOT EXISTS idx_alerts_dedupe ON alerts(dedupe_key, ts);
CREATE INDEX IF NOT EXISTS idx_alerts_open ON alerts(ack_at);

-- ECB reference rates against the payout currency, fetched nightly.
--
-- Kept as history rather than "the current rate": a purchase is converted with the rate of the
-- day it happened, and that has to still be answerable next year.
CREATE TABLE IF NOT EXISTS fx_rates (
  day        TEXT NOT NULL,                      -- YYYY-MM-DD
  currency   TEXT NOT NULL,                      -- ISO 4217, e.g. CHF
  rate       REAL NOT NULL,                      -- 1 unit of `currency` = `rate` in home currency
  PRIMARY KEY (day, currency)
);

-- Money that actually left your account, entered by hand.
--
-- OpenAI runs on prepaid credit, and there is no API for what you loaded — costs are readable,
-- payments are not. On a cash basis the expense *is* the top-up, not the tokens consumed later,
-- so the figure the tax return needs is the one that can only be typed in.
--
-- `amount_home_micros` is what the bank actually debited, currency surcharge included. That is
-- what a tax office recognises; a converted amount is an approximation and is marked as one.
CREATE TABLE IF NOT EXISTS expenses (
  id                 INTEGER PRIMARY KEY AUTOINCREMENT,
  paid_at            INTEGER NOT NULL,           -- unix ms, the day the money left
  kind               TEXT NOT NULL,              -- openai_topup | cloudflare | domain | other
  amount_micros      INTEGER NOT NULL,           -- as invoiced
  currency           TEXT NOT NULL,
  amount_home_micros INTEGER,                    -- as debited; null = no rate yet
  reference          TEXT,                       -- invoice number
  note               TEXT,
  created_at         INTEGER NOT NULL,
  created_by         TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_expenses_paid ON expenses(paid_at);

-- Settings changed from the dashboard, overriding the deployment's defaults.
--
-- Only what was actually changed is stored. Anything absent falls back to `wrangler.jsonc`, so an
-- empty table behaves exactly like a configured one — and a wiped table degrades to sensible
-- alerting rather than to silence, which is the failure nobody would notice.
CREATE TABLE IF NOT EXISTS settings (
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  updated_by TEXT NOT NULL
);

