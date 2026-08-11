-- Migration 002 — alerts, exchange rates, test accounts, response cache
--
-- `schema.sql` describes a *fresh* database and is safe to re-run because everything in it is
-- CREATE ... IF NOT EXISTS. Adding a column to a table that already holds rows is not, so it
-- lives here and runs exactly once:
--
--   npm run db:migrate            (local)
--   npm run db:migrate:remote     (production)
--
-- SQLite has no "ADD COLUMN IF NOT EXISTS". If a statement fails with "duplicate column name",
-- that column is already there — delete the line and run the rest. Nothing here destroys data.
--
-- NOT included on purpose: `purchases.purchase_type`. It is already in production (it was added
-- by hand when the Orders API was wired up) and was only missing from `schema.sql`, where it has
-- now been written down so a fresh database matches.

-- --------------------------------------------------------------- test accounts

ALTER TABLE wallets ADD COLUMN is_test INTEGER NOT NULL DEFAULT 0;
ALTER TABLE wallets ADD COLUMN test_reason TEXT;

-- Everything Google reported as a licence-tester purchase belongs to you, not to a customer.
-- Marked retroactively so the history is consistent from the first day rather than from today.
UPDATE wallets SET is_test = 1, test_reason = 'license_tester'
 WHERE is_test = 0
   AND EXISTS (SELECT 1 FROM purchases p WHERE p.wallet_id = wallets.id AND p.purchase_type = 0);

-- Accounts that never had a purchase at all can only have come from the bootstrap route.
UPDATE wallets SET is_test = 1, test_reason = 'bootstrap'
 WHERE is_test = 0
   AND NOT EXISTS (SELECT 1 FROM purchases p WHERE p.wallet_id = wallets.id);

-- ------------------------------------------------------------------ currencies

ALTER TABLE purchases ADD COLUMN fx_rate REAL;
ALTER TABLE purchases ADD COLUMN revenue_home_micros INTEGER;

-- Sales already in the payout currency need no rate and can be filled in exactly. Foreign ones
-- are deliberately left null: the correct rate is the one of the purchase day, and inventing
-- today's for a sale from three months ago would be a made-up number in a ledger. `src/fx.ts`
-- backfills them from the ECB series once rates have been fetched.
UPDATE purchases
   SET fx_rate = 1.0, revenue_home_micros = revenue_micros
 WHERE currency = 'EUR' AND revenue_micros IS NOT NULL AND revenue_home_micros IS NULL;

-- ------------------------------------------------------------------ daily roll-up

ALTER TABLE daily_totals ADD COLUMN test_requests INTEGER NOT NULL DEFAULT 0;
ALTER TABLE daily_totals ADD COLUMN test_seconds INTEGER NOT NULL DEFAULT 0;
ALTER TABLE daily_totals ADD COLUMN test_cost_nano INTEGER NOT NULL DEFAULT 0;

-- Days already recorded cannot be split after the fact — the individual rows they were built
-- from may already have been pruned. They stay as they are, counted as real traffic, and the
-- separation begins now. Better a known break in the series than a guessed correction.

-- ---------------------------------------------------------------------- alerts

CREATE TABLE IF NOT EXISTS alerts (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  ts          INTEGER NOT NULL,
  kind        TEXT NOT NULL,
  severity    TEXT NOT NULL,
  wallet_id   TEXT,
  title       TEXT NOT NULL,
  detail      TEXT NOT NULL,
  value       REAL,
  dedupe_key  TEXT NOT NULL,
  sent_at     INTEGER,
  ack_at      INTEGER,
  ack_by      TEXT
);
CREATE INDEX IF NOT EXISTS idx_alerts_ts ON alerts(ts);
CREATE INDEX IF NOT EXISTS idx_alerts_dedupe ON alerts(dedupe_key, ts);
CREATE INDEX IF NOT EXISTS idx_alerts_open ON alerts(ack_at);

-- ------------------------------------------------------------- rates and cache

CREATE TABLE IF NOT EXISTS fx_rates (
  day        TEXT NOT NULL,
  currency   TEXT NOT NULL,
  rate       REAL NOT NULL,
  PRIMARY KEY (day, currency)
);

CREATE TABLE IF NOT EXISTS cache (
  key        TEXT PRIMARY KEY,
  payload    TEXT NOT NULL,
  fetched_at INTEGER NOT NULL
);

-- Alles Weitere — Erstattungszähler, `settings`, `expenses` — kam später dazu und steht in 003.
-- Diese Datei bildet ab, was in der Produktion bereits gelaufen ist; sie wird nicht mehr geändert.
