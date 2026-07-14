import React from "react";
import ReactDOM from "react-dom/client";
import "@fontsource-variable/manrope";
import "@fontsource-variable/newsreader";
import "@fontsource-variable/geist-mono";
import App from "./App";
import Maintenance from "./Maintenance";
import "./styles.css";

// A production build shows the maintenance page by default (fail-safe: the public domain never leaks the
// unfinished site). A Cloudflare Pages *preview* deployment sets VITE_MAINTENANCE=false to render the real
// site, kept private via Cloudflare Access. Local `npm run dev` (DEV) always shows the real site.
const showMaintenance = import.meta.env.PROD && import.meta.env.VITE_MAINTENANCE !== "false";

ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    {showMaintenance ? <Maintenance /> : <App />}
  </React.StrictMode>,
);
