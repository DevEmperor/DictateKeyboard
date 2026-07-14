import {
  ArrowRight,
  BracketsCurly,
  Check,
  CloudSlash,
  Key,
  Repeat,
  UserCircleMinus,
} from "@phosphor-icons/react";
import { Reveal } from "./Reveal";
import { SavingsCalculator } from "./SavingsCalculator";

const comparisonRows = [
  [Repeat, "Recurring Dictate fee", "None", "Paid tiers can recur monthly"],
  [UserCircleMinus, "Dictate account", "Not required", "Service account"],
  [Key, "Provider relationship", "Your key, your account", "Bundled by the service"],
  [CloudSlash, "Offline route", "Whisper or Parakeet", "Depends on the service"],
  [BracketsCurly, "Source code", "Apache 2.0", "Typically closed"],
];

export function FreedomComparison() {
  return (
    <section className="freedom-section" id="compare">
      <div className="freedom-inner section-shell">
        <div className="freedom-heading">
          <Reveal>
            <span className="eyebrow eyebrow-light">DICTATE VS. THE MONTHLY TOLL BOOTH</span>
            <h2>The voice keyboard<br />without the monthly gatekeeper.</h2>
          </Reveal>
          <Reveal delay={0.08}>
            <p>Paid plans from services such as Wispr Flow bundle app access, an account, and the speech engine into one managed service. Dictate separates them: no monthly Dictate subscription, open code, offline models, and optional providers you choose.</p>
          </Reveal>
        </div>

        <Reveal className="freedom-comparison" delay={0.1}>
          <div className="freedom-columns" aria-hidden="true">
            <span>WHAT YOU CONTROL</span>
            <strong>DICTATE</strong>
            <span>PAID VOICE SERVICE</span>
          </div>
          <div className="freedom-rows" role="table" aria-label="Dictate compared with paid managed voice services">
            {comparisonRows.map(([Icon, label, dictate, paid]) => (
              <div className="freedom-row" role="row" key={label}>
                <span className="freedom-label" role="rowheader"><Icon size={17} weight="bold" aria-hidden="true" />{label}</span>
                <strong role="cell"><Check size={16} weight="bold" aria-hidden="true" />{dictate}</strong>
                <span role="cell">{paid}</span>
              </div>
            ))}
          </div>
          <p className="freedom-note">Plans and capabilities vary by service. This table compares product models; the sourced usage example below uses current public list prices.</p>
        </Reveal>

        <Reveal delay={0.13}>
          <SavingsCalculator />
        </Reveal>

        <Reveal className="freedom-punchline" delay={0.14}>
          <span>No Dictate subscription</span>
          <ArrowRight size={18} weight="bold" aria-hidden="true" />
          <strong>Offline with no API usage, or cloud on your terms.</strong>
        </Reveal>
      </div>
    </section>
  );
}
