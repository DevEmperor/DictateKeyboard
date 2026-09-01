import {
  AddressBook,
  Bluetooth,
  BookOpenText,
  ChartBar,
  Eraser,
  FileAudio,
  Gif,
  Palette,
  Sticker,
  TextAa,
} from "@phosphor-icons/react";
import { Reveal } from "./Reveal";

const capabilities = [
  {
    icon: FileAudio,
    eyebrow: "BEYOND THE MIC",
    title: "Transcribe existing recordings.",
    copy: "Long-press the mic to choose an audio file—or a compatible video. Dictate Keyboard uses your selected provider or an installed offline model. Supported formats and recording lengths vary by engine; cloud upload limits may also apply.",
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
  {
    icon: Gif,
    eyebrow: "MORE THAN TEXT",
    title: "Send the perfect GIF.",
    copy: "Search millions of GIFs straight from the keyboard and drop one into any chat—no app-switching. Bring your own key; nothing bundled.",
    visual: "gifs",
  },
  {
    icon: Sticker,
    eyebrow: "YOUR OWN FOLDER",
    title: "Bring your own stickers.",
    copy: "Point the keyboard at a folder of images and they become stickers—subfolders turn into tabs, with favourites and recents of their own. Add one from any app through the share sheet, delete one without leaving the keyboard. Nothing is uploaded anywhere.",
    visual: "stickers",
  },
  {
    icon: AddressBook,
    eyebrow: "NAMES IT KNOWS",
    title: "Stop underlining your friends.",
    copy: "Put your contacts' names into the personal dictionary and the keyboard suggests them instead of correcting them. It never asks for contacts permission—you pick the names yourself, or import a vCard.",
    visual: "contacts",
  },
  {
    icon: Eraser,
    eyebrow: "TYPING, FIXED",
    title: "Catch the typo, keep the word.",
    copy: "A thumb that lands squarely on the neighbouring key is the ordinary typo, and it now gets corrected. Set how eagerly it happens, see which word the space bar is about to take, and press backspace to get back exactly what you typed.",
    visual: "correct",
  },
];

function CapabilityVisual({ type }) {
  if (type === "files") return <div className="cap-files" aria-hidden="true"><span>WAV</span><span>M4A</span><span>MP4</span><i /></div>;
  if (type === "words") return <div className="cap-words" aria-hidden="true"><span>dev emperor</span><i>→</i><strong>DevEmperor</strong></div>;
  if (type === "prompts") return <div className="cap-prompts" aria-hidden="true"><span>Formal</span><span>Translate</span><span>My prompt</span></div>;
  if (type === "stats") return <div className="cap-stats" aria-hidden="true"><span style={{ "--height": "36%" }} /><span style={{ "--height": "58%" }} /><span style={{ "--height": "46%" }} /><span style={{ "--height": "78%" }} /><span style={{ "--height": "92%" }} /><strong>VOICE MOMENTUM</strong></div>;
  if (type === "audio") return <div className="cap-audio" aria-hidden="true"><span><Bluetooth size={21} weight="bold" /></span><i /><i /></div>;
  if (type === "stickers") return (
    <div className="cap-stickers" aria-hidden="true">
      <span className="cap-sticker-tabs"><i /><i /><i /></span>
      <div className="cap-sticker-grid"><i /><i /><i /><i /><i /><i /></div>
    </div>
  );
  if (type === "contacts") return (
    <div className="cap-contacts" aria-hidden="true">
      <span>Marlene</span><span>Okonkwo</span><span className="is-new">Þórdís</span>
      <i>no contacts permission</i>
    </div>
  );
  if (type === "correct") return (
    <div className="cap-correct" aria-hidden="true">
      <span className="cap-correct-typed">teh</span>
      <div className="cap-correct-strip"><i>teh</i><strong>the</strong><i>ten</i></div>
    </div>
  );
  if (type === "gifs") return <div className="cap-gifs" aria-hidden="true"><span className="cap-gif-bar">Search GIFs</span><div className="cap-gif-grid"><i /><i /><i /><i /><i /><i /></div></div>;
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
