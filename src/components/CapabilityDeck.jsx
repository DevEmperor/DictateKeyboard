import {
  Bluetooth,
  BookOpenText,
  ChartBar,
  FileAudio,
  Palette,
  TextAa,
} from "@phosphor-icons/react";
import { Reveal } from "./Reveal";

const capabilities = [
  {
    icon: FileAudio,
    eyebrow: "BEYOND THE MIC",
    title: "Transcribe existing recordings.",
    copy: "Long-press the mic to choose an audio file—or a compatible video. Dictate uses your selected provider or an installed offline model. Supported formats and recording lengths vary by engine; cloud upload limits may also apply.",
    visual: "files",
    wide: true,
  },
  {
    icon: TextAa,
    eyebrow: "YOUR WORDS",
    title: "Teach it names and jargon.",
    copy: "Bias compatible models with custom vocabulary, then lock exact corrections with deterministic find-and-replace rules.",
    visual: "words",
  },
  {
    icon: BookOpenText,
    eyebrow: "PROMPTS & SNIPPETS",
    title: "Reuse the cleanup you repeat.",
    copy: "Build custom rewrite actions, install community prompts, and insert saved snippets without an AI call.",
    visual: "prompts",
  },
  {
    icon: ChartBar,
    eyebrow: "VISIBLE PROGRESS",
    title: "See what speaking saves.",
    copy: "Track dictations, words, speaking pace, streaks, rewordings, and optional milestones from the statistics screen.",
    visual: "stats",
    wide: true,
  },
  {
    icon: Bluetooth,
    eyebrow: "FEELS NATIVE",
    title: "Use the mic you already wear.",
    copy: "Route recording through a connected Bluetooth headset and use haptics for start, stop, ready, and rewording feedback.",
    visual: "audio",
  },
  {
    icon: Palette,
    eyebrow: "ACTUALLY YOUR KEYBOARD",
    title: "Tune the interface, not your habits.",
    copy: "Choose themes—including E-Reader mode—plus layouts, one-handed mode, gestures, sound, haptics, and a classic voice-first view.",
    visual: "themes",
  },
];

function CapabilityVisual({ type }) {
  if (type === "files") return <div className="cap-files" aria-hidden="true"><span>WAV</span><span>M4A</span><span>MP4</span><i /></div>;
  if (type === "words") return <div className="cap-words" aria-hidden="true"><span>dev emperor</span><i>→</i><strong>DevEmperor</strong></div>;
  if (type === "prompts") return <div className="cap-prompts" aria-hidden="true"><span>Formal</span><span>Translate</span><span>My prompt</span></div>;
  if (type === "stats") return <div className="cap-stats" aria-hidden="true"><span style={{ "--height": "36%" }} /><span style={{ "--height": "58%" }} /><span style={{ "--height": "46%" }} /><span style={{ "--height": "78%" }} /><span style={{ "--height": "92%" }} /><strong>VOICE MOMENTUM</strong></div>;
  if (type === "audio") return <div className="cap-audio" aria-hidden="true"><span><Bluetooth size={21} weight="bold" /></span><i /><i /></div>;
  return <div className="cap-themes" aria-hidden="true"><span /><span /><span /><span /></div>;
}

export function CapabilityDeck() {
  return (
    <div className="capability-section">
      <div className="capability-heading">
        <Reveal>
          <span className="eyebrow">BUILT DEEPER THAN THE MIC BUTTON</span>
          <h3>Small features.<br />Serious daily leverage.</h3>
        </Reveal>
        <Reveal delay={0.08}>
          <p>No companion subscription required. Every feature earns its place by removing a tap, a switch, or a cleanup step.</p>
        </Reveal>
      </div>

      <div className="capability-grid">
        {capabilities.map((item, index) => {
          const Icon = item.icon;
          return (
            <Reveal className={`capability-card ${item.wide ? "is-wide" : ""}`} delay={(index % 3) * 0.05} key={item.title}>
              <div className="capability-card-top">
                <span>{String(index + 1).padStart(2, "0")}</span>
                <i><Icon size={19} weight="bold" aria-hidden="true" /></i>
              </div>
              <CapabilityVisual type={item.visual} />
              <div className="capability-copy">
                <span>{item.eyebrow}</span>
                <h4>{item.title}</h4>
                <p>{item.copy}</p>
              </div>
            </Reveal>
          );
        })}
      </div>
    </div>
  );
}
