# Dictate Cloud

The credit server behind Dictate's optional "buy minutes instead of bringing your own API key"
path. A single Cloudflare Worker: it verifies a Google Play purchase, keeps a balance, and passes
dictation and rewording to Cloudflare's own Workers AI while metering what they cost.

The models run inside the same platform the Worker does, reached through a binding rather than over
the network — so there is no API key for them, and nothing has to leave for an outside service.
Which model does the work is `TRANSCRIBE_MODEL` and `CHAT_MODEL`, changeable without a code change.

Using Dictate does not require any of this. Bring an API key from a provider of your choice and the
app never speaks to this server at all — see the app's provider settings. This exists so that
someone who does not want a provider account has a way in.

## Why the source is here

Because the privacy policy makes claims about it that ought to be checkable. It says recordings and
transcripts are never written to disk, that only numbers are stored, that no name or email address
is attached to an account. Those are statements about *this* code, and a claim you can read the
source of is worth more than one you have to take on trust.

`src/meter.ts` is the place to start if that is what brought you here: it is the only file that
writes anything, and it writes wallet id, timestamp, duration, token counts, status code and
milliseconds. Nothing else.

## What is not here

**`wrangler.jsonc`.** It carries the alert recipient's address, the database id and every threshold
the watchdog uses. The thresholds guard nothing on their own — they only decide when a warning is
sent — but knowing them tells you exactly how to stay underneath. `wrangler.example.jsonc` has the
same structure with the values removed.

**Secrets**, of course. The Play service account and the notification secret live in Cloudflare's
secret store and never in a file. There is no key for the models: the binding bills the account this
Worker runs on. The source is written so that publishing it gives nothing away.

**The business papers.** Record of processing, technical and organisational measures, the tax notes,
the third-country documentation, the abuse test plan. They belong to the operator, not to the code.

## Why half of this is in German

Code, comments, configuration and everything a user of the app can ever see are **English** — API
errors, the public deletion page at `/delete`, this file.

The dashboard under `/admin` and the alert mails are **German**, and that is a decision rather than
an unfinished translation. Both have exactly one reader: the dashboard sits behind Cloudflare Access
bound to a single address, and the mails go to a single inbox. Translating a surface with one reader
into a language that reader does not think in buys consistency for people reading the source and
costs clarity for the one person who has to act on a warning at seven in the morning.

The line runs at the boundary, not through the middle of a file: `src/admin/*` and the alert texts
in `notify/`, `rules.ts`, `rtdn.ts`, `throttle.ts`, `meter.ts`, `sweep.ts`, `redeem.ts`,
`transcriptions.ts` and `wallet.ts` are German; their comments, and everything else, are not. Anything
that can reach an app user is English wherever it lives.

## Layout

```
src/
  index.ts        the router, and the three cron rhythms
  wallet.ts       one credit account, as a Durable Object — where "check and deduct" is atomic
  guard.ts        the daily spend ceiling and the code-guessing counter, one object for all
  meter.ts        the ledger; the only thing that writes
  routes/         transcriptions, chat, redeem, restore, delete, Google's notifications
  config.ts       prices, packages, limits — and the neuron table the reported figures are checked against
  admin/          the dashboard behind Cloudflare Access
  notify/         alert rules, digest, the mails
migrations/       applied by hand, in order — see the note in each file
schema.sql        the shape of a fresh database
```

## Running it

```sh
npm install
cp wrangler.example.jsonc wrangler.jsonc   # then fill in the placeholders
npx wrangler secret put GOOGLE_SERVICE_ACCOUNT
npx wrangler d1 create dictate-cloud --location weur
npx wrangler d1 execute dictate-cloud --remote --file schema.sql
npx tsc --noEmit && npx wrangler deploy
```

The database is created in Western Europe and the Durable Objects are pinned to the EU
(`meter.ts`, `eu()`), so the balances stay there. That is about where the bytes sit; it does not
remove the transfer to Cloudflare as a processor, which the standard contractual clauses carry.

## The one rule worth repeating

Credit is deducted **before** the upstream call, never after. Bill last and every dropped connection
is a giveaway; bill first and a failure costs a refund instead. Every route in `routes/` follows it,
and `wallet.ts` exists so that "is there enough, and take it" is a single indivisible step.
