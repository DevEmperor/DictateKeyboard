import React from "react";
import ReactDOM from "react-dom/client";
import "@fontsource-variable/manrope";
import "@fontsource-variable/newsreader";
import "@fontsource-variable/geist-mono";
import App from "./App";
import Maintenance from "./Maintenance";
import "./styles.css";

// Which page the public sees is decided by the git branch, not a dashboard variable (so it can't be
// misconfigured): the production branch `website` shows the maintenance page; every other branch (the
// `website-dev` preview) shows the real site — see __PREVIEW_BUILD__ in vite.config.js. Local `npm run dev`
// (DEV) always shows the real site. To launch, set VITE_LIVE=true on the production environment.
const forceLive = import.meta.env.VITE_LIVE === "true";
const showMaintenance = import.meta.env.PROD && !forceLive && !__PREVIEW_BUILD__;

ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    {showMaintenance ? <Maintenance /> : <App />}
  </React.StrictMode>,
);
