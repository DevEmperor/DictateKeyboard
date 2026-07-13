import { useState } from "react";
import { CloudArrowUp, DeviceMobile, Microphone, WifiSlash } from "@phosphor-icons/react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { Waveform } from "./Waveform";

const modes = [
  {
    id: "live",
    label: "Live",
    title: "Watch the sentence arrive.",
    copy: "With a supported streaming provider, words appear while you speak—directly in the field you already have open.",
    route: "Selected provider",
    icon: CloudArrowUp,
  },
  {
    id: "offline",
    label: "Offline",
    title: "Keep the audio on your phone.",
    copy: "Download a Whisper or Parakeet model once, then transcribe on-device without an internet connection or cloud API call.",
    route: "On-device model",
    icon: WifiSlash,
  },
  {
    id: "floating",
    label: "Floating",
    title: "Keep the keyboard you already use.",
    copy: "A movable mic button lets you dictate in any app while another keyboard stays active. The feature is optional and off by default.",
    route: "Any text field",
    icon: DeviceMobile,
  },
];

export function ModeSwitcher() {
  const [activeMode, setActiveMode] = useState("live");
  const reduceMotion = useReducedMotion();
  const mode = modes.find((item) => item.id === activeMode);
  const Icon = mode.icon;

  return (
    <div className="mode-switcher">
      <div className="mode-tabs" role="tablist" aria-label="Dictation modes">
        {modes.map((item) => (
          <button
            key={item.id}
            type="button"
            role="tab"
            aria-selected={activeMode === item.id}
            aria-controls={`mode-panel-${item.id}`}
            className={activeMode === item.id ? "is-active" : ""}
            onClick={() => setActiveMode(item.id)}
          >
            {activeMode === item.id && (
              <motion.span
                className="mode-tab-backdrop"
                layoutId={reduceMotion ? undefined : "mode-tab-backdrop"}
                transition={{ type: "spring", bounce: 0, duration: 0.24 }}
              />
            )}
            <span>{item.label}</span>
          </button>
        ))}
      </div>

      <AnimatePresence mode="popLayout" initial={false}>
        <motion.div
          id={`mode-panel-${mode.id}`}
          key={mode.id}
          className="mode-panel"
          role="tabpanel"
          initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, 10px, 0)" }}
          animate={{ opacity: 1, transform: "translate3d(0, 0, 0)" }}
          exit={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, -4px, 0)", transition: { duration: reduceMotion ? 0.12 : 0.13, ease: [0.23, 1, 0.32, 1] } }}
          transition={{ duration: reduceMotion ? 0.12 : 0.19, ease: [0.23, 1, 0.32, 1] }}
        >
          <div className="mode-copy">
            <span className="mode-icon"><Icon size={21} weight="bold" aria-hidden="true" /></span>
            <h3>{mode.title}</h3>
            <p>{mode.copy}</p>
          </div>

          <div className={`signal-map signal-map-${mode.id}`} aria-hidden="true">
            <div className="signal-node signal-source"><Microphone size={20} weight="fill" /></div>
            <div className="signal-line"><span /></div>
            <div className="signal-node signal-destination"><Icon size={22} weight="bold" /></div>
            <div className="signal-output">
              <Waveform active={!reduceMotion} compact />
              <span>{mode.route}</span>
            </div>
          </div>
        </motion.div>
      </AnimatePresence>
    </div>
  );
}
