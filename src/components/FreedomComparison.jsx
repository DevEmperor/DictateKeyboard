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
  [Repeat, "Recurring fee", "None — no subscription", "$15/mo · $144/yr Pro plan"],
  [UserCircleMinus, "Account", "Not required", "Wispr account required"],
  [Key, "Provider & keys", "Your key, your account, your choice", "One bundled cloud engine"],
  [CloudSlash, "Offline route", "Whisper or Parakeet, on-device", "Cloud only — no offline mode"],
  [BracketsCurly, "Source code", "Open, Apache 2.0", "Closed source"],
];

export function FreedomComparison() {
  return (
    <section className="freedom-section" id="compare">
      <div className="freedom-inner section-shell">
        <div className="freedom-heading">
          <Reveal>
            <span className="eyebrow eyebrow-light">DICTATE KEYBOARD vs WISPR FLOW PRO</span>
            <h2>The same dictation,<br />without the monthly bill.</h2>
          </Reveal>
          <Reveal delay={0.08}>
            <p>Subscription apps like Wispr Flow bundle the app, an account, and one speech engine into a flat monthly plan. Dictate Keyboard separates them: no subscription, open code, an offline route, and any provider you choose. The priced example below pairs Dictate Keyboard with Soniox realtime — one of its fastest cloud routes — against Wispr Flow Pro at public list prices.</p>
          </Reveal>
        </div>

        <Reveal className="freedom-comparison" delay={0.1}>
          <div className="freedom-columns" aria-hidden="true">
            <span>WHAT YOU CONTROL</span>
            <strong>DICTATE KEYBOARD</strong>
            <span>WISPR FLOW PRO</span>
          </div>
          <div className="freedom-rows" role="table" aria-label="Dictate Keyboard compared with Wispr Flow Pro">
            {comparisonRows.map(([Icon, label, dictate, paid]) => (
              <div className="freedom-row" role="row" key={label}>
                <span className="freedom-label" role="rowheader"><Icon size={17} weight="bold" aria-hidden="true" />{label}</span>
                <strong role="cell"><Check size={16} weight="bold" aria-hidden="true" />{dictate}</strong>
                <span role="cell">{paid}</span>
              </div>
            ))}
          </div>
          <p className="freedom-note">Wispr Flow tiers and features change over time; this compares public product positioning as of July 2026. The sourced example below uses current public list prices.</p>
        </Reveal>

        <Reveal delay={0.13}>
          <SavingsCalculator />
        </Reveal>

        <Reveal className="freedom-punchline" delay={0.14}>
          <span>No subscription</span>
          <ArrowRight size={18} weight="bold" aria-hidden="true" />
          <strong>Offline with no API usage, or cloud on your terms.</strong>
        </Reveal>
      </div>
    </section>
  );
}
