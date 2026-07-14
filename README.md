# Dictate website

Conversion-focused English landing page for Dictate. The site presents Dictate as an open-source Android voice keyboard without a monthly Dictate subscription, with offline transcription, provider choice, realtime routes, and AI rewriting.

## Run locally

Requires a current Node.js LTS release.

```bash
npm install
npm run dev
```

Vite prints the local URL, normally `http://localhost:5173`.

## Build and test

```bash
npm run build
npm run qa
```

The production build is written to `dist/`. Playwright covers the primary desktop conversion path, mobile navigation, responsive model highlights, model-catalog expansion, and both installation routes.

## Content notes

- The default Model Buffet shows three curated highlights in each STT category and three LLM-provider highlights. The complete catalog remains available through the expansion controls.
- Artificial Analysis WER and latency values are identified as independent benchmark signals; lower WER is better.
- Every price in the Model Buffet refers exclusively to an external provider’s API usage. Dictate adds no monthly subscription or provider markup, and offline transcription uses no cloud API.
- Provider API prices are normalized snapshots dated 13 July 2026 and must be reviewed before publication because catalogs, promotions, and rates can change.
- The savings calculator defaults to 20 transcription hours per month. It uses Soniox’s current ~$0.12/hour realtime estimate and Flow Pro’s current $15 monthly plan, with the $12/month annual-billing equivalent shown as a countercheck. Both official sources and calculation caveats are linked in the component.
- Newsreader supplies the editorial display voice, Manrope handles interface and body copy, and Geist Mono is reserved for technical data.

## Main source areas

- `src/App.jsx` — page structure and conversion copy
- `src/components/ModelBuffet.jsx` — STT/LLM catalog, curated highlights, benchmark notes
- `src/components/SavingsCalculator.jsx` — sourced, interactive recurring-cost comparison
- `src/components/CapabilityDeck.jsx` — compact secondary feature set
- `src/components/RewordPlayground.jsx` — interactive before/after rewrite demo
- `src/components/DictationDemo.jsx` — interactive hero demo
- `src/styles.css` — responsive visual and motion system
- `tests/site.spec.js` — browser-level regression coverage

## Branches, deployment & maintenance mode

This is a re-rooted branch that holds **only** the website (it shares no history with the app's `main`
branch — split out of the app repo's `website/` folder with `git subtree split`, preserving authorship).
It is deployed with **Cloudflare Pages** (build command `npm run build`, output `dist`, root directory
`/`).

Two branches:

- **`website`** — production. Deployed to `dictatekeyboard.com`. Ships with the maintenance page on
  (`const MAINTENANCE = true` in `src/main.jsx`).
- **`website-dev`** — where you build the real site. Deployed as a Cloudflare **preview** (private via
  Cloudflare Access) with `MAINTENANCE = false`, so the preview shows the real site.

**Publish** = merge `website-dev` into `website`:

```bash
git checkout website && git merge website-dev && git push
```

The merge flips `MAINTENANCE` to `false` on production, so the real site goes live. Set it back to `true`
on `website` to re-enable maintenance. `npm run dev` always shows the real site locally.

## Credits

Dictate is created and maintained by [Jannis Zahn (@DevEmperor)](https://github.com/DevEmperor). Its keyboard foundation builds on the work of the FlorisBoard contributors.
