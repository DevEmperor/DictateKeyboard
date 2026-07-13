# Dictate website

Conversion-focused English landing page for Dictate. The site presents Dictate as a free, open-source Android voice keyboard with offline transcription, provider choice, realtime routes, and AI rewriting.

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
- Every price in the Model Buffet refers exclusively to an external provider’s API usage. Dictate itself is free, and offline transcription uses no cloud API.
- Provider API prices are normalized snapshots dated 13 July 2026 and must be reviewed before publication because catalogs, promotions, and rates can change.
- The comparison section avoids unverifiable savings claims and describes product-model differences rather than claiming universal benchmark superiority.

## Main source areas

- `src/App.jsx` — page structure and conversion copy
- `src/components/ModelBuffet.jsx` — STT/LLM catalog, curated highlights, benchmark notes
- `src/components/DictationDemo.jsx` — interactive hero demo
- `src/styles.css` — responsive visual and motion system
- `tests/site.spec.js` — browser-level regression coverage

## Credits

Dictate is created and maintained by [Jannis Zahn (@DevEmperor)](https://github.com/DevEmperor). Its keyboard foundation builds on the work of the FlorisBoard contributors.
