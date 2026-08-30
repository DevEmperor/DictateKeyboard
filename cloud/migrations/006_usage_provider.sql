-- Migration 006 — the ledger learns who handled a request, and how much compute it took
--
-- Until now there was one provider per service, so the ledger did not have to say which. With a
-- second one there are four possible combinations, and a row that names none of them cannot be
-- recalculated afterwards: not to check a bill against, not to compare before and after, not to
-- work out where the content actually went.
--
-- Two columns for that, because they answer different questions. `provider` answers the legal one
-- — which company processed the content. `model` answers the commercial one — at which price.
-- Swapping one Workers AI model for another moves the second and leaves the first alone; falling
-- back to OpenAI moves both.
--
-- And `neurons_micro` alongside `cost_nano`, although one follows from the other today. It only
-- follows today: a price is a number that changes, a quantity is not, and only the quantity can be
-- held against Cloudflare's own count. Workers AI reports it on every response (measured
-- 30.08.2026 — it appears in no type definition and comes back all the same), so it is read rather
-- than computed.
--
--   npm run db:migrate6            (local)
--   npm run db:migrate6:remote     (production)

ALTER TABLE usage_log ADD COLUMN provider      TEXT;     -- openai | workers-ai
ALTER TABLE usage_log ADD COLUMN model         TEXT;     -- gpt-transcribe | @cf/openai/whisper-large-v3-turbo | …
ALTER TABLE usage_log ADD COLUMN neurons_micro INTEGER NOT NULL DEFAULT 0;

-- The roll-up matters more than the ledger here: usage_log is pruned after 90 days and
-- daily_totals is the only thing that survives it. A neuron column that exists only in the pruned
-- table is a bookkeeping with three months of memory.
ALTER TABLE daily_totals ADD COLUMN neurons_micro      INTEGER NOT NULL DEFAULT 0;
-- Kept apart from the money like its neighbours, but added back for the free allowance: the
-- 10 000 neurons a day are account-wide and do not care whose request it was.
ALTER TABLE daily_totals ADD COLUMN test_neurons_micro INTEGER NOT NULL DEFAULT 0;
-- The Workers AI share of cost_nano. During the changeover it is the only thing that keeps the two
-- providers apart in the surviving roll-up; afterwards it simply equals cost_nano.
ALTER TABLE daily_totals ADD COLUMN cost_nano_cf       INTEGER NOT NULL DEFAULT 0;

-- The backfill, and it is not a guess: before this migration there was exactly one provider per
-- service, so every existing row can only have gone where it went. Leaving them NULL would have
-- been the more literal choice and the worse one — every query would need a special case, and the
-- before-and-after comparison that justifies the whole move runs on this very column.
--
-- neurons_micro stays 0 for them, which is not a missing figure but a correct one: no neurons were
-- spent. And `WHERE provider IS NULL` so this can be run twice without touching anything new.
UPDATE usage_log
   SET provider = 'openai',
       model = CASE kind WHEN 'transcribe' THEN 'gpt-transcribe' ELSE 'gpt-5-nano' END
 WHERE provider IS NULL;
