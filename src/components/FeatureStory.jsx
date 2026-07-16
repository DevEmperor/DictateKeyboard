import { useEffect, useRef, useState } from "react";
import {
  ArrowsClockwise,
  Cloud,
  CloudCheck,
  Keyboard,
  MagicWand,
  Microphone,
  Watch,
  WifiSlash,
} from "@phosphor-icons/react";
import { motion, useInView, useReducedMotion } from "motion/react";
import { Waveform } from "./Waveform";

const features = [
  {
    icon: Keyboard,
    label: "01 — ONE KEYBOARD",
    title: "Speak or type. Never switch context.",
    copy: "A complete Android keyboard with glide typing, live word suggestions, autocorrect, emoji, clipboard tools, themes, and one-handed layouts — the mic is just one more key.",
    tag: "Full Android keyboard",
    visual: "keyboard",
  },
  {
    icon: CloudCheck,
    label: "02 — ONLINE OR OFFLINE",
    title: "You choose what handles your voice.",
    copy: "Stream from a cloud provider for realtime speed and top accuracy, or download a model and transcribe fully on-device. Same keyboard, two honest routes — switch any time.",
    tag: "Cloud streaming or on-device",
    visual: "route",
  },
  {
    icon: MagicWand,
    label: "03 — READY TO SEND",
    title: "Turn rough thoughts into finished text.",
    copy: "Make a selection formal, concise, translated, or summarized with the writing model you pick. Install community prompts, build custom actions, and save reusable snippets.",
    tag: "AI rewriting · your model",
    visual: "reword",
  },
  {
    icon: ArrowsClockwise,
    label: "04 — ANY TEXT FIELD",
    title: "Add voice without giving up your keyboard.",
    copy: "The optional floating button works while another keyboard is active. Move it, resize it, pick Pill, Ring, or Orb, and long-press to reword — anywhere on screen.",
    tag: "Optional floating mic",
    visual: "floating",
  },
  {
    icon: Watch,
    label: "05 — WEAR OS 3+",
    title: "Capture the thought from your wrist.",
    copy: "Dictate from a Wear OS watch through your phone or fully standalone — for the moments when reaching for a phone is the slow part.",
    tag: "Wear OS 3+ · tethered or standalone",
    visual: "wear",
  },
];

/* ---- animated per-feature visuals (pure CSS/motion, no screenshots) ---- */

const KEY_ROWS = ["QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"];

function KeyboardVisual() {
  return (
    <div className="fv fv-keyboard" aria-hidden="true">
      <div className="fv-suggest">
        <span>hello</span>
        <span className="is-active">world</span>
        <span>voice</span>
      </div>
      <div className="fv-keys">
        {KEY_ROWS.map((row) => (
          <div className="fv-key-row" key={row}>
            {row.split("").map((k) => <span key={k}>{k}</span>)}
          </div>
        ))}
        <div className="fv-key-row fv-key-row-space">
          <span className="fv-key-fn">?123</span>
          <span className="fv-key-space" />
          <span className="fv-key-fn"><Microphone size={13} weight="fill" /></span>
        </div>
        <svg className="fv-glide" viewBox="0 0 300 150" preserveAspectRatio="none">
          <path
            d="M40 30 L150 30 L120 66 L210 66 L95 102 L180 102"
            fill="none"
            stroke="var(--accent)"
            strokeWidth="4"
            strokeLinecap="round"
            strokeLinejoin="round"
            pathLength="1"
          />
        </svg>
      </div>
    </div>
  );
}

function RouteVisual() {
  return (
    <div className="fv fv-route" aria-hidden="true">
      <div className="fv-route-node fv-route-mic"><Microphone size={22} weight="fill" /><small>YOUR VOICE</small></div>
      <div className="fv-route-rails">
        <div className="fv-rail fv-rail-cloud">
          <span className="fv-rail-line"><i /></span>
          <div className="fv-route-dest"><Cloud size={20} weight="fill" /><strong>Cloud provider</strong><small>Realtime · streaming</small></div>
        </div>
        <div className="fv-rail fv-rail-offline">
          <span className="fv-rail-line"><i /></span>
          <div className="fv-route-dest"><WifiSlash size={20} weight="bold" /><strong>On-device model</strong><small>Private · $0 API</small></div>
        </div>
      </div>
    </div>
  );
}

function RewordVisual() {
  return (
    <div className="fv fv-reword" aria-hidden="true">
      <div className="fv-reword-in">
        <span>YOU SAID</span>
        <p>“uh yeah tell the client we'll send the proposal friday and the numbers are still being checked”</p>
      </div>
      <div className="fv-reword-wand"><MagicWand size={18} weight="fill" /></div>
      <div className="fv-reword-out">
        <span>DICTATE WROTE</span>
        <p>I'll send the proposal by Friday. The figures are currently in final review.</p>
        <em className="fv-reword-cursor" />
      </div>
    </div>
  );
}

function FloatingVisual() {
  return (
    <div className="fv fv-floating" aria-hidden="true">
      <div className="fv-app">
        <div className="fv-app-bar"><i /><i /><i /></div>
        <div className="fv-app-line" style={{ width: "82%" }} />
        <div className="fv-app-line" style={{ width: "64%" }} />
        <div className="fv-app-line" style={{ width: "74%" }} />
        <div className="fv-app-line fv-app-line-type" style={{ width: "40%" }} />
      </div>
      <div className="fv-float-btn"><Microphone size={20} weight="fill" /><span className="fv-float-ring" /></div>
    </div>
  );
}

function WearVisual() {
  return (
    <div className="fv fv-wear" aria-hidden="true">
      <div className="fv-watch">
        <div className="fv-watch-face">
          <span className="fv-watch-dot" />
          <span className="fv-watch-label">LISTENING</span>
          <Waveform active />
          <div className="fv-watch-text">Reminder: call the studio at four</div>
        </div>
        <span className="fv-watch-crown" />
      </div>
      <div className="fv-watch-sent"><CloudCheck size={15} weight="bold" />Synced to phone</div>
    </div>
  );
}

const VISUALS = {
  keyboard: KeyboardVisual,
  route: RouteVisual,
  reword: RewordVisual,
  floating: FloatingVisual,
  wear: WearVisual,
};

function FeatureVisual({ feature, index, total }) {
  const reduceMotion = useReducedMotion();
  const Visual = VISUALS[feature.visual];
  return (
    <motion.div
      key={feature.visual}
      className={`feature-visual-stage stage-${feature.visual}`}
      initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, 10px, 0) scale(0.99)" }}
      animate={{ opacity: 1, transform: "translate3d(0, 0, 0) scale(1)" }}
      transition={{ duration: reduceMotion ? 0.12 : 0.32, ease: [0.23, 1, 0.32, 1] }}
    >
      <Visual />
      <div className="feature-visual-caption">
        <span>{feature.tag}</span>
        <span>{String(index + 1).padStart(2, "0")} / {String(total).padStart(2, "0")}</span>
      </div>
    </motion.div>
  );
}

function FeatureCopy({ feature, index, activeIndex, setActiveIndex }) {
  const ref = useRef(null);
  const inView = useInView(ref, { margin: "-38% 0px -48% 0px" });
  const Icon = feature.icon;
  const Visual = VISUALS[feature.visual];

  useEffect(() => {
    if (inView) setActiveIndex(index);
  }, [inView, index, setActiveIndex]);

  return (
    <button
      ref={ref}
      type="button"
      className={`feature-copy ${activeIndex === index ? "is-active" : ""}`}
      onClick={() => setActiveIndex(index)}
      aria-pressed={activeIndex === index}
    >
      <span className="feature-copy-icon"><Icon size={20} weight="bold" aria-hidden="true" /></span>
      <span className="feature-copy-text">
        <span className="eyebrow">{feature.label}</span>
        <strong>{feature.title}</strong>
        <span>{feature.copy}</span>
      </span>
      {/* Inline animated visual on mobile, where the sticky column is hidden */}
      <span className="feature-mobile-visual"><Visual /></span>
    </button>
  );
}

export function FeatureStory() {
  const [activeIndex, setActiveIndex] = useState(0);
  const active = features[activeIndex];

  return (
    <div className="feature-story">
      <div className="feature-copy-list">
        {features.map((item, index) => (
          <FeatureCopy
            key={item.label}
            feature={item}
            index={index}
            activeIndex={activeIndex}
            setActiveIndex={setActiveIndex}
          />
        ))}
      </div>

      <div className="feature-visual-column">
        <div className="feature-visual">
          <div className="feature-visual-grid" aria-hidden="true" />
          <FeatureVisual feature={active} index={activeIndex} total={features.length} />
          <div className="feature-dots" role="group" aria-label="Feature preview selection">
            {features.map((item, index) => (
              <button
                type="button"
                key={item.label}
                className={activeIndex === index ? "is-active" : ""}
                aria-label={`Show feature ${index + 1}: ${item.title}`}
                onClick={() => setActiveIndex(index)}
              />
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
