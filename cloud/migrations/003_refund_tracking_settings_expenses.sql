-- Migration 003 — refund tracking, dashboard settings, expenses
--
-- Everything here was added *after* migration 002 had already been applied to production, which is
-- why it is a file of its own rather than more lines in 002. Re-running 002 would abort on the
-- columns it already created, and a migration that cannot be run twice safely is one that will
-- eventually be run twice unsafely.
--
--   npm run db:migrate3            (local)
--   npm run db:migrate3:remote     (production)

-- ------------------------------------------------------- refund-abuse tracking

-- SHA-256 of Google's per-app pseudonym for the buying account. Lets a refund history survive a
-- reinstall: a wiped phone gets a fresh wallet, and a tally kept per wallet would reset at exactly
-- the moment it starts to mean something.
ALTER TABLE wallets ADD COLUMN play_account_hash TEXT;
ALTER TABLE wallets ADD COLUMN void_count INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_wallets_play_account ON wallets(play_account_hash);

-- Purchases already clawed back, counted so the history starts complete rather than at zero.
UPDATE wallets SET void_count = (
  SELECT COUNT(*) FROM purchases p WHERE p.wallet_id = wallets.id AND p.state = 'voided'
);

-- The account pseudonym itself cannot be filled in retroactively: Google hands it over only with a
-- purchase, and the purchases that already happened are done. It starts collecting from the next
-- sale — which is the one that matters, since the point is recognising a returning buyer.

-- ------------------------------------------------------------------- settings

-- Only what was actually changed in the dashboard is stored here. Anything absent falls back to
-- wrangler.jsonc, so an empty table behaves exactly like a configured one — and a wiped table
-- degrades to sensible alerting rather than to silence.
CREATE TABLE IF NOT EXISTS settings (
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  updated_by TEXT NOT NULL
);

-- ------------------------------------------------------------------- expenses

-- Money that actually left the account, entered by hand. OpenAI has no API for top-ups — costs are
-- readable, payments are not — and on a cash basis the top-up is the expense, not the tokens.
CREATE TABLE IF NOT EXISTS expenses (
  id                 INTEGER PRIMARY KEY AUTOINCREMENT,
  paid_at            INTEGER NOT NULL,
  kind               TEXT NOT NULL,
  amount_micros      INTEGER NOT NULL,
  currency           TEXT NOT NULL,
  amount_home_micros INTEGER,
  reference          TEXT,
  note               TEXT,
  created_at         INTEGER NOT NULL,
  created_by         TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_expenses_paid ON expenses(paid_at);
