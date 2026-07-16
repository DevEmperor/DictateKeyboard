import { useEffect } from "react";
import { ArrowLeft } from "@phosphor-icons/react";

/**
 * Standalone legal pages (Imprint / Privacy Policy), served at /imprint and /privacy via the SPA fallback
 * (public/_redirects). English, to match the international site, but grounded in German law (the operator is
 * based in Germany). Not legal advice.
 */

const EMAIL = "accounts@devemperor.net";

function Imprint() {
  return (
    <>
      <h1>Imprint</h1>

      <h2>Information pursuant to § 5 DDG</h2>
      <p>
        Jannis Zahn<br />
        Talstraße 84<br />
        35625 Hüttenberg<br />
        Germany
      </p>

      <h2>Contact</h2>
      <p>Email: <a href={`mailto:${EMAIL}`}>{EMAIL}</a></p>

      <h2>Responsible for content pursuant to § 18 (2) MStV</h2>
      <p>Jannis Zahn (address as above)</p>

      <h2>Consumer dispute resolution</h2>
      <p>
        We are neither willing nor obliged to participate in dispute-resolution proceedings before a consumer
        arbitration board. The European Commission provides a platform for online dispute resolution (ODR):{" "}
        <a href="https://ec.europa.eu/consumers/odr/" target="_blank" rel="noreferrer">https://ec.europa.eu/consumers/odr/</a>.
      </p>

      <h2>Liability for content and links</h2>
      <p>
        The contents of these pages were created with the greatest care. However, no guarantee can be given
        for the accuracy, completeness, or timeliness of the content. The operators of linked external pages
        are solely responsible for their content; no legal violations were apparent at the time of linking.
        Such links will be removed immediately upon becoming aware of any legal infringement.
      </p>

      <h2>Copyright</h2>
      <p>
        The source code of the “Dictate Keyboard” app is licensed under the Apache-2.0 License. The content and
        works on this website are subject to German copyright law; any use beyond the license or the limits of
        the law requires the consent of the respective author.
      </p>
    </>
  );
}

function Privacy() {
  return (
    <>
      <h1>Privacy Policy</h1>

      <h2>1. Controller</h2>
      <p>
        Jannis Zahn<br />
        Talstraße 84, 35625 Hüttenberg, Germany<br />
        Email: <a href={`mailto:${EMAIL}`}>{EMAIL}</a>
      </p>

      <h2>2. Hosting</h2>
      <p>
        This website is hosted on Cloudflare Pages (Cloudflare, Inc., 101 Townsend St, San Francisco, CA 94107,
        USA). When you access the site, Cloudflare processes technically necessary data (including your IP
        address) in order to deliver the site securely and reliably. The legal basis is Art. 6 (1)(f) GDPR
        (legitimate interest in a secure, functional website). A transfer to the USA may occur; Cloudflare
        relies on Standard Contractual Clauses and/or the EU-US Data Privacy Framework.
      </p>

      <h2>3. Server log files</h2>
      <p>
        Each time the site is accessed, information transmitted by your browser is automatically collected: IP
        address, date and time, the requested page, the referrer URL, and browser and operating-system
        details. This data is used solely for the secure operation of the site and is not merged with other
        data sources.
      </p>

      <h2>4. Web analytics (Cloudflare Web Analytics)</h2>
      <p>
        We use Cloudflare Web Analytics to evaluate the use of the website statistically. The service is{" "}
        <strong>cookieless</strong>, uses no fingerprinting, and stores no personal data on your device. Only
        aggregated, anonymous metrics (e.g. page views and approximate region) are collected; no conclusions
        can be drawn about individual persons. Legal basis: Art. 6 (1)(f) GDPR.
      </p>

      <h2>5. Cookies</h2>
      <p>
        This website sets no cookies and uses no local storage for tracking purposes. A cookie banner is
        therefore not required.
      </p>

      <h2>6. External links</h2>
      <p>
        The website links to external services (including Google Play, GitHub, and PayPal). When you click
        them, you leave this website; the privacy policies of the respective providers then apply.
      </p>

      <h2>7. Your rights</h2>
      <p>
        You have the right to information, rectification, erasure, restriction of processing, objection to
        processing, and data portability. To exercise these rights, please contact the email address above.
        You also have the right to lodge a complaint with a data-protection supervisory authority.
      </p>

      <p className="legal-updated">Last updated: July 2026</p>
    </>
  );
}

export default function Legal({ page }) {
  useEffect(() => {
    document.title = page === "imprint" ? "Imprint — Dictate Keyboard" : "Privacy Policy — Dictate Keyboard";
  }, [page]);

  return (
    <main className="legal">
      <div className="legal-shell">
        <a className="legal-back" href="/">
          <ArrowLeft size={16} weight="bold" aria-hidden="true" />
          Back to Dictate Keyboard
        </a>
        <div className="legal-body">
          {page === "imprint" ? <Imprint /> : <Privacy />}
        </div>
        <footer className="legal-foot">
          <a href="/imprint">Imprint</a>
          <span aria-hidden="true">·</span>
          <a href="/privacy">Privacy Policy</a>
        </footer>
      </div>
    </main>
  );
}
