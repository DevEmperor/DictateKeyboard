import { useState } from "react";
import { Check, Code, DeviceMobile, LockKey, PenNib, Translate } from "@phosphor-icons/react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { Reveal } from "./Reveal";

const personas = [
  {
    id: "developers",
    label: "Developers",
    icon: Code,
    title: "Talk through the boilerplate.",
    copy: "Dictate commit messages, code comments, issue replies and Slack messages hands-free — and keep a real keyboard one tap away for the symbols.",
    points: ["Commit messages & PR descriptions by voice", "Reply to issues and chat without typing", "On-device mode keeps your context private"],
  },
  {
    id: "writers",
    label: "Writers & notes",
    icon: PenNib,
    title: "Get the draft out of your head.",
    copy: "Speak the rough version at the speed you think, then let the writing model you choose polish the tone, trim the filler, or restructure it.",
    points: ["Capture ideas at speaking speed", "One tap: formal, concise, bullets, translate", "Searchable history to find the sentence again"],
  },
  {
    id: "multilingual",
    label: "Multilingual",
    icon: Translate,
    title: "Switch languages mid-sentence.",
    copy: "Dictate in dozens of languages with real per-language dictionaries — no more fighting an autocorrect that only knows English.",
    points: ["99 languages to speak, 40+ to type", "Glide typing & suggestions per language", "The app UI itself is localized"],
  },
  {
    id: "on-the-go",
    label: "On the go",
    icon: DeviceMobile,
    title: "Hands full? Just talk.",
    copy: "The floating mic works over any app while another keyboard stays active, and you can capture a thought straight from your wrist.",
    points: ["Floating mic button over any app", "Dictate from a Wear OS watch", "Bluetooth-headset mic + haptic feedback"],
  },
  {
    id: "privacy",
    label: "Privacy-first",
    icon: LockKey,
    title: "Nothing has to leave your phone.",
    copy: "Download a model and transcribe fully offline, with no account, no subscription and no developer backend between your voice and the text.",
    points: ["On-device Whisper / Parakeet models", "No tracking, telemetry or hosted backend", "Bring your own provider key, or none at all"],
  },
];

export function Personas() {
  const [active, setActive] = useState(personas[0].id);
  const reduceMotion = useReducedMotion();
  const persona = personas.find((p) => p.id === active);

  return (
    <section className="personas section-shell" id="use-cases">
      <div className="section-intro persona-intro">
        <Reveal>
          <span className="eyebrow">MADE FOR HOW YOU WORK</span>
          <h2>Whatever you write,<br /><span>your voice gets it down faster.</span></h2>
        </Reveal>
        <Reveal delay={0.08}>
          <p>One keyboard, but the way people use it differs. Pick the workflow that sounds like yours.</p>
        </Reveal>
      </div>

      <Reveal className="persona-shell" delay={0.1}>
        <div className="persona-tabs" role="tablist" aria-label="Use cases">
          {personas.map((p) => {
            const Icon = p.icon;
            return (
              <button
                key={p.id}
                type="button"
                role="tab"
                aria-selected={active === p.id}
                className={active === p.id ? "is-active" : ""}
                onClick={() => setActive(p.id)}
              >
                {active === p.id && (
                  <motion.span className="persona-tab-bg" layoutId={reduceMotion ? undefined : "persona-tab"} transition={{ type: "spring", bounce: 0, duration: 0.24 }} />
                )}
                <Icon size={17} weight="bold" aria-hidden="true" />
                <span>{p.label}</span>
              </button>
            );
          })}
        </div>

        <AnimatePresence mode="wait" initial={false}>
          <motion.div
            key={persona.id}
            className="persona-panel"
            role="tabpanel"
            initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, 8px, 0)" }}
            animate={{ opacity: 1, transform: "translate3d(0, 0, 0)" }}
            exit={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, -6px, 0)" }}
            transition={{ duration: reduceMotion ? 0.12 : 0.2, ease: [0.23, 1, 0.32, 1] }}
          >
            <h3>{persona.title}</h3>
            <p>{persona.copy}</p>
            <ul className="persona-points">
              {persona.points.map((point) => (
                <li key={point}><Check size={15} weight="bold" aria-hidden="true" />{point}</li>
              ))}
            </ul>
          </motion.div>
        </AnimatePresence>
      </Reveal>
    </section>
  );
}
