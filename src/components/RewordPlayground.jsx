import { useState } from "react";
import {
  ListBullets,
  MagicWand,
  Scissors,
  Sparkle,
  Translate,
} from "@phosphor-icons/react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { Reveal } from "./Reveal";

const prompts = [
  {
    label: "Formal",
    icon: Sparkle,
    instruction: "Professional and email-ready",
    output: "I’ll send the proposal by Friday. The figures are currently undergoing final review.",
  },
  {
    label: "Translate",
    icon: Translate,
    instruction: "Translate to German",
    output: "Ich sende den Vorschlag bis Freitag. Die Zahlen befinden sich derzeit in der finalen Prüfung.",
  },
  {
    label: "Shorten",
    icon: Scissors,
    instruction: "Short and direct",
    output: "Proposal Friday. Figures are in final review.",
  },
  {
    label: "Bullets",
    icon: ListBullets,
    instruction: "Turn into action points",
    output: "• Proposal: send by Friday\n• Figures: complete final review",
  },
];

const rawText = "uh yeah tell the client we’ll send the proposal by Friday and that the numbers are still being checked";

export function RewordPlayground() {
  const [activeIndex, setActiveIndex] = useState(0);
  const reduceMotion = useReducedMotion();
  const active = prompts[activeIndex];

  return (
    <Reveal className="reword-playground">
      <div className="reword-heading">
        <span className="eyebrow">AI REWORDING · YOUR MODEL, YOUR PROMPT</span>
        <h3>Dictate rough. Send polished.</h3>
        <p>Turn spoken first drafts into ready-to-send text with the rewrite model you choose. Try a prompt.</p>
      </div>

      <div className="reword-stage">
        <div className="reword-card reword-card-raw">
          <span>YOU SAID</span>
          <p>“{rawText}”</p>
        </div>

        <div className="reword-transform" aria-hidden="true">
          <span><MagicWand size={19} weight="fill" /></span>
          <i />
        </div>

        <div className="reword-card reword-card-output">
          <span>DICTATE WROTE · {active.instruction}</span>
          <div className="reword-live" aria-live="polite" aria-atomic="true">
            <AnimatePresence mode="wait" initial={false}>
              <motion.p
                key={active.label}
                initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, 5px, 0)" }}
                animate={{ opacity: 1, transform: "translate3d(0, 0, 0)" }}
                exit={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, -3px, 0)" }}
                transition={{ duration: reduceMotion ? 0.01 : 0.18, ease: [0.23, 1, 0.32, 1] }}
              >
                {active.output}
              </motion.p>
            </AnimatePresence>
          </div>
        </div>
      </div>

      <div className="reword-prompts" role="group" aria-label="Try a rewording prompt">
        {prompts.map((prompt, index) => {
          const Icon = prompt.icon;
          return (
            <button
              type="button"
              className={activeIndex === index ? "is-active" : ""}
              aria-pressed={activeIndex === index}
              onClick={() => setActiveIndex(index)}
              key={prompt.label}
            >
              <Icon size={16} weight={index === 0 ? "fill" : "bold"} aria-hidden="true" />
              {prompt.label}
            </button>
          );
        })}
      </div>
    </Reveal>
  );
}
