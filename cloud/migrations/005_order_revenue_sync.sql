-- The first real sale showed 0,00 € earnings, and the ledger agreed with it.
--
-- Google reports what a buyer paid straight away, but works out the developer's share later — after
-- the payment settles. `fetchOrder` runs once, in the same breath as the redemption, so it asked at
-- the one moment the answer does not exist yet. `toMicros` then turned the absent field into a hard
-- 0, and nothing ever asked again: a sale worth about 1,71 € sat in the books as nothing earned,
-- past every "how many purchases are missing figures" counter, because those look for NULL.
--
-- Three columns so the ledger can say what it knows and when it last asked, and one correction so
-- the nightly sync picks up what is already there. Licence testers keep their 0 — for them it is the
-- truth, and re-asking Google about them every night for a fortnight would be asking for nothing.

ALTER TABLE purchases ADD COLUMN order_state     TEXT;    -- Google's own: PENDING | PROCESSED | REFUNDED …
ALTER TABLE purchases ADD COLUMN order_synced_at INTEGER; -- when the order was last asked about
ALTER TABLE purchases ADD COLUMN order_attempts  INTEGER NOT NULL DEFAULT 0;

UPDATE purchases
   SET revenue_micros = NULL, revenue_home_micros = NULL
 WHERE revenue_micros = 0 AND purchase_type IS NULL AND order_id IS NOT NULL;
