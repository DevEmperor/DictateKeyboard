-- Deleting an account could never work.
--
-- `purchases.wallet_id` references `wallets(id)` with no ON DELETE clause, and D1 enforces foreign
-- keys. So `DELETE FROM wallets` failed for every account that had ever bought anything — which is
-- every real account — while it succeeded for a wallet with no purchases, which is exactly the kind
-- I had tested with.
--
-- The receipts have to stay ten years (§ 147 AO), so the row they point at cannot go. The account
-- is therefore emptied in place instead: status 'deleted', a timestamp, and nothing left on the row
-- that could lead back to a person. Tokens, usage rows and alerts are still deleted outright.
--
-- Reading it back is the side benefit: the dashboard can now show that an account was deleted and
-- when, rather than having it silently vanish from a list.

ALTER TABLE wallets ADD COLUMN deleted_at INTEGER;
CREATE INDEX IF NOT EXISTS idx_wallets_deleted ON wallets(deleted_at);
