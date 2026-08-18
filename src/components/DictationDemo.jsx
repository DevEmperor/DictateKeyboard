import { useEffect, useState } from "react";
import {
  ArrowRight,
  Check,
  Copy,
  Microphone,
  PaperPlaneTilt,
  Sparkle,
  Translate,
} from "@phosphor-icons/react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { Waveform } from "./Waveform";

/**
 * The hero preview — and it has to be honest about *how* the text arrives, because that is the product.
 *
 * The earlier version typed the sentence out letter by letter while the bar read LISTENING. Dictate never
 * does that. On an ordinary dictation nothing appears until you stop, and then the whole transcript lands at
 * once; only in realtime mode does text show up early, and then it arrives in word groups from the provider,
 * never character by character. So this cycles through the two behaviours the app actually has:
 *
 *   BATCH     mic open, field empty → you stop → the sentence appears whole
 *   REALTIME  mic open → words arrive in groups while it is still recording
 *
 * The timer counts real elapsed time instead of being derived from how much text is on screen, and the whole
 * thing advances on one interval rather than a timeout per character — which also stops the hero
 * re-rendering thirty times a second for as long as somebody is looking at it.
 */

const phrases = [
  {
    label: "Polish",
    text: "Move tomorrow’s review to 3:00 PM and add Mia to the invitation.",
    completion: "Tone polished",
    mode: "batch",
  },
  {
    label: "Translate",
    text: "Pouvez-vous déplacer notre réunion à demain matin ?",
    completion: "French translation",
    mode: "realtime",
  },
  {
    label: "Shorten",
    text: "Review moved to three. Mia added.",
    completion: "Shortened",
    mode: "batch",
  },
];

const promptOptions = [
  { label: "Polish", icon: Sparkle, weight: "fill" },
  { label: "Translate", icon: Translate, weight: "bold" },
  { label: "Shorten", icon: Copy, weight: "bold" },
];

const keyboardRows = ["QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"];

const TICK = 120;
const RECORD_MS = 2400; // mic open
const SETTLE_MS = 2600; // transcript on screen before the next phrase

/** Realtime arrives in word groups, so the boundaries here are words — never characters. */
function chunksOf(text) {
  const words = text.split(" ");
  const out = [];
  for (let i = 0; i < words.length; i += 3) out.push(words.slice(0, i + 3).join(" "));
  if (out[out.length - 1] !== text) out.push(text);
  return out;
}

export function DictationDemo() {
  const reduceMotion = useReducedMotion();
  const [index, setIndex] = useState(0);
  const [elapsed, setElapsed] = useState(0);
  const [paused, setPaused] = useState(false);
  const phrase = phrases[index];

  const recording = !reduceMotion && !paused && elapsed < RECORD_MS;
  const settled = reduceMotion || elapsed >= RECORD_MS;

  // What is in the field right now. Batch shows nothing until the recording ends; realtime fills in while
  // the mic is still open.
  let visibleText = "";
  if (settled) {
    visibleText = phrase.text;
  } else if (phrase.mode === "realtime") {
    const chunks = chunksOf(phrase.text);
    const step = Math.floor((elapsed / RECORD_MS) * chunks.length);
    visibleText = chunks[Math.min(step, chunks.length - 1)] ?? "";
  }

  useEffect(() => {
    if (reduceMotion || paused) return undefined;
    const id = window.setInterval(() => {
      setElapsed((value) => {
        const next = value + TICK;
        if (next >= RECORD_MS + SETTLE_MS) {
          setIndex((current) => (current + 1) % phrases.length);
          return 0;
        }
        return next;
      });
    }, TICK);
    return () => window.clearInterval(id);
  }, [reduceMotion, paused]);

  function toggleRecording() {
    setPaused((value) => !value);
  }

  function selectPrompt(next) {
    setIndex(next);
    setElapsed(0);
    setPaused(false);
  }

  const seconds = Math.min(Math.floor(elapsed / 1000), Math.floor(RECORD_MS / 1000));
  const duration = `0:${String(seconds).padStart(2, "0")}`;
  const status = paused ? "PAUSED" : settled ? "TRANSCRIBED" : phrase.mode === "realtime" ? "LIVE" : "LISTENING";

  return (
    <div className="demo-orbit" aria-label="Interactive Dictate Keyboard product preview">
      <motion.div
        className="provider-tag provider-tag-top"
        initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, 12px, 0)" }}
        animate={{ opacity: 1, transform: "translate3d(0, 0, 0)" }}
        transition={{ delay: 0.85, duration: 0.55, ease: [0.23, 1, 0.32, 1] }}
      >
        <span className="provider-pulse" />
        On-device ready
      </motion.div>

      <motion.div
        className="provider-tag provider-tag-bottom"
        initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, -12px, 0)" }}
        animate={{ opacity: 1, transform: "translate3d(0, 0, 0)" }}
        transition={{ delay: 1.05, duration: 0.55, ease: [0.23, 1, 0.32, 1] }}
      >
        Your model. Your choice.
        <ArrowRight size={15} weight="bold" aria-hidden="true" />
      </motion.div>

      <motion.div
        className="phone-shell"
        initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, 28px, 0) rotate(1.5deg)" }}
        animate={{ opacity: 1, transform: "translate3d(0, 0, 0) rotate(0deg)" }}
        transition={{ delay: 0.35, duration: 0.9, ease: [0.23, 1, 0.32, 1] }}
      >
        <div className="phone-speaker" />
        <div className="phone-screen">
          <div className="demo-appbar">
            <div>
              <span className="demo-app-kicker">NEW MESSAGE</span>
              <strong>Project notes</strong>
            </div>
            <span className="demo-avatar" aria-hidden="true">MI</span>
          </div>

          <div className="demo-editor">
            <AnimatePresence mode="popLayout" initial={false}>
              <motion.p
                key={`${index}-${visibleText.length > 0 ? "text" : "empty"}`}
                initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, 4px, 0)" }}
                animate={{ opacity: 1, transform: "translate3d(0, 0, 0)" }}
                exit={{ opacity: 0, transition: { duration: 0.09 } }}
                transition={{ duration: reduceMotion ? 0.12 : 0.22, ease: [0.23, 1, 0.32, 1] }}
              >
                {visibleText || <span className="demo-placeholder">Speak — the text lands when you stop.</span>}
                <span className={`typing-cursor ${recording ? "is-active" : ""}`} aria-hidden="true" />
              </motion.p>
            </AnimatePresence>
            <motion.div
              className="completion-chip"
              animate={{ opacity: settled ? 1 : 0, transform: settled ? "translate3d(0, 0, 0)" : "translate3d(0, 5px, 0)" }}
              transition={{ duration: 0.2, ease: [0.23, 1, 0.32, 1] }}
            >
              <Check size={13} weight="bold" aria-hidden="true" />
              {phrase.completion}
            </motion.div>
          </div>

          <div className="prompt-strip" role="group" aria-label="Rewording shortcuts">
            {promptOptions.map((option, position) => {
              const Icon = option.icon;
              return (
                <button
                  type="button"
                  className={index === position ? "is-active" : ""}
                  aria-pressed={index === position}
                  key={option.label}
                  onClick={() => selectPrompt(position)}
                >
                  <Icon size={14} weight={option.weight} aria-hidden="true" />
                  {option.label}
                </button>
              );
            })}
          </div>

          <div className="recording-bar">
            <div className="recording-state">
              <span className={`record-dot ${recording ? "is-active" : ""}`} />
              <span>{status}</span>
              <strong>{duration}</strong>
            </div>
            <Waveform active={recording} />
            <button
              type="button"
              className="record-button"
              onClick={toggleRecording}
              aria-label={paused ? "Resume dictation preview" : "Pause dictation preview"}
              aria-pressed={!paused}
            >
              <Microphone size={22} weight="fill" aria-hidden="true" />
            </button>
          </div>

          <div className="keyboard" aria-hidden="true">
            {keyboardRows.map((row, rowIndex) => (
              <div className={`key-row key-row-${rowIndex + 1}`} key={row}>
                {row.split("").map((key) => <span className="key" key={key}>{key}</span>)}
              </div>
            ))}
            <div className="key-row key-row-bottom">
              <span className="key key-wide">123</span>
              <span className="key key-space">English</span>
              <span className="key key-send"><PaperPlaneTilt size={14} weight="fill" /></span>
            </div>
          </div>
        </div>
      </motion.div>
    </div>
  );
}
