# Dictate Keyboard — website

The marketing website for **Dictate Keyboard** (the Android app itself lives on the `main` branch of
this repo). Built with React + Vite and deployed on Cloudflare Pages.

## Run locally

Requires a current Node.js LTS release.

```bash
npm install
npm run dev
```

Vite prints the local URL, normally `http://localhost:5173`.

## Build & test

```bash
npm run build   # production build → dist/
npm run qa      # Playwright browser tests
```

## Branches, deployment & maintenance mode

This is a re-rooted branch that holds **only** the Dictate Keyboard website (it shares no history with the
app's `main` branch — split out of the app repo's `website/` folder with `git subtree split`, preserving
authorship). It is deployed with **Cloudflare Pages** (build command `npm run build`, output `dist`, root
directory `/`).

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

**Dictate Keyboard** is created and maintained by [Jannis Zahn (@DevEmperor)](https://github.com/DevEmperor).
Its keyboard foundation builds on the work of the FlorisBoard contributors.
