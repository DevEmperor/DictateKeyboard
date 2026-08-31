-- Migration 007 — a clean ledger for a service that now buys somewhere else
--
-- **Run this at the cut, not before.** It is the second half of one deploy: the code that only
-- knows Workers AI goes up, and the history of the service that bought elsewhere goes out. Running
-- it early throws away the baseline the changeover is measured against; running it late leaves a
-- ledger whose first weeks are priced in a currency the code can no longer explain.
--
--   npm run db:migrate7            (local)
--   npm run db:migrate7:remote     (production)
--
-- ---------------------------------------------------------------------------------------------
-- What is deleted, and why it can be
--
-- Every row below is a *record of what happened*, not a record of what is owed. It was written to
-- answer questions about a service whose costs were denominated in another provider's price list:
-- `cost_nano` at $0.0045 an audio minute, no neurons, no model, an alert history about a rule that
-- no longer exists. Kept, it would be a decade of figures that quietly need a footnote. The service
-- is three weeks old and has eight real purchases behind it — this is the last moment at which
-- throwing it away costs nothing.
--
-- What that costs: the before-and-after comparison. It is written down instead, in the migration
-- plan, from the measurement of 30.08.2026 — 75 000 nano per credit-second for dictation, 37 498
-- for rewording, 94,3/5,7 by credit-seconds. A sentence that survives a DELETE.
--
-- ---------------------------------------------------------------------------------------------
-- What is **not** deleted, and why it must not be
--
--   wallets    Eight people paid for seconds they have not used yet. A "fresh start" that reset a
--              balance would not be tidying up, it would be keeping paid-for goods.
--   tokens     Their devices. Clearing these logs everyone out of an account they still own.
--   purchases  Receipts. Subject to a retention period, and the revenue chain the tax view runs on.
--   expenses   Invoices actually paid, including the old provider's. A kind that vanishes takes its
--              year out of the evaluation with it.
--   admin_log  What was done to accounts by hand — gifts, blocks, merges. The audit trail of the
--              living accounts above, not a log of traffic.
--   fx_rates   Rates frozen onto past purchases. Deleting them unconverts sales already converted.
--   settings   Thresholds changed from the dashboard. Not history at all.

DELETE FROM usage_log;
DELETE FROM daily_totals;

-- Alerts too. Every one of them is about a condition of the old arrangement — a price drift against
-- an invoice that will not be fetched again, a budget expressed at eight times the current cost.
-- An unacknowledged warning that can no longer be acted on is worse than none: it teaches the bell
-- to be ignored.
DELETE FROM alerts;

-- Dead since the dashboard stopped paging through a foreign billing endpoint: nothing reads or
-- writes it any more, and an empty table nobody opens is a place for a wrong assumption to hide.
DROP TABLE IF EXISTS cache;
