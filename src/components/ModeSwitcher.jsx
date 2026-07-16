import { useState } from "react";
import { CloudArrowUp, Cpu, DeviceMobile, Microphone, WifiSlash } from "@phosphor-icons/react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { Waveform } from "./Waveform";

const modes = [
  {
    id: "live",
    label: "Live",
    title: "Watch the sentence arrive.",
    copy: "With a supported streaming provider, words appear while you speak—directly in the field you already have open.",
    icon: CloudArrowUp,
  },
  {
    id: "offline",
    label: "Offline",
    title: "Keep the audio on your phone.",
    copy: "Download a Whisper or Parakeet model once, then transcribe on-device without an internet connection or cloud API call.",
    icon: WifiSlash,
  },
  {
    id: "floating",
    label: "Floating",
    title: "Keep the keyboard you already use.",
    copy: "A movable mic button lets you dictate in any app while another keyboard stays active. The feature is optional and off by default.",
    icon: DeviceMobile,
  },
];

/* ---- distinct, workflow-accurate visuals per mode ---- */

// LIVE: words stream into an already-open message field in realtime while you speak.
function LiveViz() {
  return (
    <div className="mv mv-live" aria-hidden="true">
      <div className="mv-field">
        <div className="mv-field-bar"><span /><span /><span /><em>New message</em></div>
        <p className="mv-stream"><span className="mv-typed mv-typed-live">Let’s ship the update on Friday.</span><i className="mv-caret" /></p>
      </div>
      <div className="mv-foot">
        <span className="mv-live-dot" />LIVE
        <Waveform active compact />
        <span className="mv-foot-tag">STREAMING · YOUR PROVIDER</span>
      </div>
    </div>
  );
}

// OFFLINE: audio → on-device model chip → text, with no network. Nothing leaves the phone.
function OfflineViz() {
  return (
    <div className="mv mv-offline" aria-hidden="true">
      <div className="mv-off-flow">
        <div className="mv-off-node mv-off-mic"><Microphone size={18} weight="fill" /><Waveform active compact /></div>
        <div className="mv-off-arrow"><i /><i /><i /></div>
        <div className="mv-off-node mv-off-chip">
          <Cpu size={20} weight="bold" />
          <div><strong>ON-DEVICE</strong><small>Whisper · Parakeet</small></div>
          <span className="mv-off-scan" />
        </div>
      </div>
      <div className="mv-off-out"><span className="mv-typed mv-typed-off">Transcribed locally on your phone.</span><i className="mv-caret" /></div>
      <div className="mv-foot mv-off-foot"><WifiSlash size={14} weight="bold" /><span>No network · $0 API · audio never leaves the device</span></div>
    </div>
  );
}

// FLOATING: a movable mic hovers over another app and drops text into its field — your keyboard stays.
function FloatingViz() {
  return (
    <div className="mv mv-floating" aria-hidden="true">
      <div className="mv-app">
        <div className="mv-app-top"><span className="mv-app-avatar" /><em>Another app · your keyboard stays</em></div>
        <div className="mv-app-msg" style={{ width: "72%" }} />
        <div className="mv-app-msg mv-app-msg-them" style={{ width: "54%" }} />
        <div className="mv-app-input"><span className="mv-typed mv-typed-float">On my way, five minutes out.</span><i className="mv-caret" /></div>
      </div>
      <div className="mv-fab"><Microphone size={20} weight="fill" /><span className="mv-fab-ring" /></div>
    </div>
  );
}

const VIZ = { live: LiveViz, offline: OfflineViz, floating: FloatingViz };

export function ModeSwitcher() {
  const [activeMode, setActiveMode] = useState("live");
  const reduceMotion = useReducedMotion();
  const mode = modes.find((item) => item.id === activeMode);
  const Icon = mode.icon;
  const Viz = VIZ[mode.id];

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

          <div className={`mode-viz-wrap mode-viz-${mode.id}`}>
            <Viz />
          </div>
        </motion.div>
      </AnimatePresence>
    </div>
  );
}
