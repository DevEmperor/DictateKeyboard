import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The public production branch (`website`) shows the maintenance page; every other branch — e.g. the
// `website-dev` Cloudflare Pages preview — shows the real site. Cloudflare injects CF_PAGES_BRANCH at
// build time, so this needs no dashboard variable and can't be misconfigured. Fail-safe: if the branch
// can't be read (empty), it is treated as production → maintenance, so the public domain never leaks the
// unfinished site. To go live, set VITE_LIVE=true on the production environment (see main.jsx).
const branch = process.env.CF_PAGES_BRANCH || "";
const isPreviewBranch = branch !== "" && branch !== "website";

export default defineConfig({
  plugins: [react()],
  define: {
    __PREVIEW_BUILD__: JSON.stringify(isPreviewBranch),
  },
});
