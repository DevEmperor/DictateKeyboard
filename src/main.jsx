import React from "react";
import ReactDOM from "react-dom/client";
import "@fontsource-variable/manrope";
import "@fontsource-variable/newsreader";
import "@fontsource-variable/geist-mono";
import App from "./App";
import Maintenance from "./Maintenance";
import Legal from "./components/Legal";
import "./styles.css";

// Single committed maintenance switch. The `website` (production) branch keeps this `true`, so the public
// domain shows the maintenance page. The `website-dev` branch sets it to `false`, so its Cloudflare preview
// shows the real site. Publishing is then just `git merge website-dev` into `website` — a fast-forward that
// flips this to false — nothing else to toggle. Set it back to true on `website` to re-enable maintenance.
const MAINTENANCE = false;

// Legal pages are served at /imprint and /privacy (SPA fallback via public/_redirects) and stay reachable
// even during maintenance.
const path = window.location.pathname.replace(/\/+$/, "");
const legalPage = path === "/imprint" ? "imprint" : path === "/privacy" ? "privacy" : null;

function Root() {
  if (legalPage) return <Legal page={legalPage} />;
  return MAINTENANCE ? <Maintenance /> : <App />;
}

ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <Root />
  </React.StrictMode>,
);
