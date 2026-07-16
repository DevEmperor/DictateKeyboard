import { useEffect } from "react";
import { ArrowLeft } from "@phosphor-icons/react";

/**
 * Standalone legal pages (Impressum + Datenschutzerklärung), served at /impressum and /datenschutz via the
 * SPA fallback (public/_redirects). Plain German boilerplate — the operator must review and complete the
 * address before publishing. Not legal advice.
 */

const ADDRESS_PLACEHOLDER = "[Straße & Hausnummer — bitte ergänzen]";
const CITY_PLACEHOLDER = "[PLZ Ort — bitte ergänzen]";
const EMAIL = "accounts@devemperor.net";

function Impressum() {
  return (
    <>
      <h1>Impressum</h1>

      <h2>Angaben gemäß § 5 DDG</h2>
      <p>
        Jannis Zahn<br />
        {ADDRESS_PLACEHOLDER}<br />
        {CITY_PLACEHOLDER}<br />
        Deutschland
      </p>

      <h2>Kontakt</h2>
      <p>E-Mail: <a href={`mailto:${EMAIL}`}>{EMAIL}</a></p>

      <h2>Verantwortlich für den Inhalt nach § 18 Abs. 2 MStV</h2>
      <p>Jannis Zahn (Anschrift wie oben)</p>

      <h2>Verbraucherstreitbeilegung</h2>
      <p>
        Wir sind nicht bereit oder verpflichtet, an Streitbeilegungsverfahren vor einer
        Verbraucherschlichtungsstelle teilzunehmen. Die Europäische Kommission stellt eine Plattform zur
        Online-Streitbeilegung (OS) bereit:{" "}
        <a href="https://ec.europa.eu/consumers/odr/" target="_blank" rel="noreferrer">https://ec.europa.eu/consumers/odr/</a>.
      </p>

      <h2>Haftung für Inhalte und Links</h2>
      <p>
        Die Inhalte dieser Seiten wurden mit größter Sorgfalt erstellt. Für die Richtigkeit, Vollständigkeit
        und Aktualität der Inhalte kann jedoch keine Gewähr übernommen werden. Für Inhalte externer Links sind
        ausschließlich deren Betreiber verantwortlich; zum Zeitpunkt der Verlinkung waren keine Rechtsverstöße
        erkennbar. Bei Bekanntwerden von Rechtsverletzungen werden solche Links umgehend entfernt.
      </p>

      <h2>Urheberrecht</h2>
      <p>
        Der Quellcode der App „Dictate Keyboard“ steht unter der Apache-2.0-Lizenz. Die Inhalte und Werke
        dieser Website unterliegen dem deutschen Urheberrecht; eine Verwertung außerhalb der Lizenz bzw. der
        gesetzlichen Grenzen bedarf der Zustimmung des jeweiligen Urhebers.
      </p>
    </>
  );
}

function Datenschutz() {
  return (
    <>
      <h1>Datenschutzerklärung</h1>

      <h2>1. Verantwortlicher</h2>
      <p>
        Jannis Zahn<br />
        {ADDRESS_PLACEHOLDER}, {CITY_PLACEHOLDER}<br />
        E-Mail: <a href={`mailto:${EMAIL}`}>{EMAIL}</a>
      </p>

      <h2>2. Hosting</h2>
      <p>
        Diese Website wird bei Cloudflare, Inc. (101 Townsend St, San Francisco, CA 94107, USA) über
        Cloudflare Pages gehostet. Beim Aufruf verarbeitet Cloudflare technisch notwendige Daten (u. a.
        IP-Adresse) zur sicheren und stabilen Auslieferung der Seite. Rechtsgrundlage ist Art. 6 Abs. 1 lit. f
        DSGVO (berechtigtes Interesse an einem sicheren, funktionsfähigen Auftritt). Eine Übermittlung in die
        USA ist möglich; Cloudflare stützt sich hierfür auf Standardvertragsklauseln bzw. das EU-US Data
        Privacy Framework.
      </p>

      <h2>3. Server-Logfiles</h2>
      <p>
        Bei jedem Zugriff werden automatisch Informationen erhoben, die Ihr Browser übermittelt: IP-Adresse,
        Datum und Uhrzeit, angeforderte Seite, Referrer-URL sowie Browser- und Betriebssystem-Angaben. Diese
        Daten dienen ausschließlich dem sicheren Betrieb und werden nicht mit anderen Datenquellen
        zusammengeführt.
      </p>

      <h2>4. Web-Analyse (Cloudflare Web Analytics)</h2>
      <p>
        Wir nutzen Cloudflare Web Analytics, um die Nutzung der Website statistisch auszuwerten. Der Dienst
        arbeitet <strong>cookielos</strong>, ohne Fingerprinting und ohne Speicherung personenbezogener Daten
        auf Ihrem Endgerät. Erhoben werden ausschließlich aggregierte, anonyme Kennzahlen (z. B. Seitenaufrufe
        und ungefähre Herkunftsregion). Ein Rückschluss auf einzelne Personen ist nicht möglich. Rechtsgrundlage
        ist Art. 6 Abs. 1 lit. f DSGVO.
      </p>

      <h2>5. Cookies</h2>
      <p>
        Diese Website setzt keine Cookies und nutzt keinen lokalen Speicher zu Tracking-Zwecken. Ein
        Cookie-Banner ist daher nicht erforderlich.
      </p>

      <h2>6. Externe Links</h2>
      <p>
        Die Website verweist auf externe Angebote (u. a. Google Play, GitHub, PayPal). Mit dem Klick verlassen
        Sie diese Website; es gelten die Datenschutzbestimmungen der jeweiligen Anbieter.
      </p>

      <h2>7. Ihre Rechte</h2>
      <p>
        Sie haben das Recht auf Auskunft, Berichtigung, Löschung, Einschränkung der Verarbeitung, Widerspruch
        gegen die Verarbeitung sowie Datenübertragbarkeit. Wenden Sie sich hierfür an die oben genannte
        E-Mail-Adresse. Zudem steht Ihnen ein Beschwerderecht bei einer Datenschutz-Aufsichtsbehörde zu.
      </p>

      <p className="legal-updated">Stand: Juli 2026</p>
    </>
  );
}

export default function Legal({ page }) {
  useEffect(() => {
    document.title = page === "impressum" ? "Impressum — Dictate Keyboard" : "Datenschutz — Dictate Keyboard";
  }, [page]);

  return (
    <main className="legal">
      <div className="legal-shell">
        <a className="legal-back" href="/">
          <ArrowLeft size={16} weight="bold" aria-hidden="true" />
          Zurück zu Dictate Keyboard
        </a>
        <div className="legal-body">
          {page === "impressum" ? <Impressum /> : <Datenschutz />}
        </div>
        <footer className="legal-foot">
          <a href="/impressum">Impressum</a>
          <span aria-hidden="true">·</span>
          <a href="/datenschutz">Datenschutz</a>
        </footer>
      </div>
    </main>
  );
}
