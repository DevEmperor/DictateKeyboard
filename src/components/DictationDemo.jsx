import { useEffect, useMemo, useState } from "react";
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

const phrases = [
  { label: "Polish", text: "Move tomorrow’s review to 3:00 PM and add Mia to the invitation.", completion: "Tone polished" },
  { label: "Translate", text: "Pouvez-vous déplacer notre réunion à demain matin ?", completion: "French translation" },
  { label: "Shorten", text: "Review moved to three. Mia added.", completion: "Shortened" },
];

const promptOptions = [
  { label: "Polish", icon: Sparkle, weight: "fill" },
  { label: "Translate", icon: Translate, weight: "bold" },
  { label: "Shorten", icon: Copy, weight: "bold" },
];

const keyboardRows = ["QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"];

export function DictationDemo() {
  const reduceMotion = useReducedMotion();
  const [phraseIndex, setPhraseIndex] = useState(0);
  const [characterIndex, setCharacterIndex] = useState(reduceMotion ? phrases[0].text.length : 0);
  const [recording, setRecording] = useState(true);
  const phrase = phrases[phraseIndex].text;
  const visibleText = phrase.slice(0, characterIndex);
  const done = characterIndex === phrase.length;

  useEffect(() => {
    if (reduceMotion || !recording) return undefined;

    if (characterIndex < phrase.length) {
      const timer = window.setTimeout(() => setCharacterIndex((value) => value + 1), 34);
      return () => window.clearTimeout(timer);
    }

    const pause = window.setTimeout(() => {
      setPhraseIndex((value) => (value + 1) % phrases.length);
      setCharacterIndex(0);
    }, 2300);

    return () => window.clearTimeout(pause);
  }, [characterIndex, phrase, recording, reduceMotion]);

  function toggleRecording() {
    if (!recording && done) {
      setPhraseIndex((value) => (value + 1) % phrases.length);
      setCharacterIndex(0);
    }
    setRecording((value) => !value);
  }

  function selectPrompt(index) {
    setPhraseIndex(index);
    setCharacterIndex(reduceMotion ? phrases[index].text.length : 0);
    setRecording(true);
  }

  const duration = useMemo(() => `0:${String(Math.max(1, Math.ceil(characterIndex / 9))).padStart(2, "0")}`, [characterIndex]);

  return (
    <div className="demo-orbit" aria-label="Interactive Dictate product preview">
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
                key={phraseIndex}
                initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, 3px, 0)" }}
                animate={{ opacity: 1, transform: "translate3d(0, 0, 0)" }}
                exit={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, -2px, 0)", transition: { duration: 0.1, ease: [0.23, 1, 0.32, 1] } }}
                transition={{ duration: reduceMotion ? 0.12 : 0.16, ease: [0.23, 1, 0.32, 1] }}
              >
                {visibleText}
                <span className={`typing-cursor ${recording ? "is-active" : ""}`} aria-hidden="true" />
              </motion.p>
            </AnimatePresence>
            <motion.div
              className="completion-chip"
              animate={{ opacity: done ? 1 : 0, transform: done ? "translate3d(0, 0, 0)" : "translate3d(0, 5px, 0)" }}
              transition={{ duration: 0.2, ease: [0.23, 1, 0.32, 1] }}
            >
              <Check size={13} weight="bold" aria-hidden="true" />
              {phrases[phraseIndex].completion}
            </motion.div>
          </div>

          <div className="prompt-strip" role="group" aria-label="Rewording shortcuts">
            {promptOptions.map((option, index) => {
              const Icon = option.icon;
              return (
                <button
                  type="button"
                  className={phraseIndex === index ? "is-active" : ""}
                  aria-pressed={phraseIndex === index}
                  key={option.label}
                  onClick={() => selectPrompt(index)}
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
              <span>{recording ? "LISTENING" : "PAUSED"}</span>
              <strong>{duration}</strong>
            </div>
            <Waveform active={recording} />
            <button
              type="button"
              className="record-button"
              onClick={toggleRecording}
              aria-label={recording ? "Pause dictation preview" : "Resume dictation preview"}
              aria-pressed={recording}
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
