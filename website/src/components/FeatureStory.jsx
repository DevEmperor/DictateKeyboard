import { useEffect, useRef, useState } from "react";
import {
  ArrowsClockwise,
  CloudCheck,
  Keyboard,
  MagicWand,
  Watch,
} from "@phosphor-icons/react";
import { AnimatePresence, motion, useInView, useReducedMotion } from "motion/react";

const features = [
  {
    icon: Keyboard,
    label: "01 — ONE KEYBOARD",
    title: "Speak or type. Never switch context.",
    copy: "Dictate is a complete keyboard with glide typing, suggestions, autocorrect, emoji, clipboard tools, themes, and one-handed layouts.",
    image: "/media/feature-anywhere.png",
    alt: "Dictate keyboard inside an Android note app",
    tag: "Full Android keyboard",
  },
  {
    icon: CloudCheck,
    label: "02 — YOUR ROUTE",
    title: "Choose what handles your voice.",
    copy: "Run a downloadable model on-device, connect a cloud provider, or point Dictate at a compatible endpoint. Capabilities stay visible and configurable.",
    image: "/media/feature-provider.png",
    alt: "Dictate provider selection screen",
    tag: "Offline or cloud",
  },
  {
    icon: MagicWand,
    label: "03 — READY TO SEND",
    title: "Turn rough thoughts into useful text.",
    copy: "Make a selection formal, concise, translated, or summarized. Install community prompts, build custom actions, and save reusable snippets.",
    image: "/media/feature-reword.png",
    alt: "Dictate AI rewording prompts inside the keyboard",
    tag: "Custom AI prompts",
  },
  {
    icon: ArrowsClockwise,
    label: "04 — ANY TEXT FIELD",
    title: "Add voice without giving up your keyboard.",
    copy: "The optional floating button works while another keyboard is active. Move it, resize it, pick Pill, Ring, or Orb, and long-press to reword.",
    image: "/media/feature-floating.png",
    alt: "Dictate floating microphone button over another Android app",
    tag: "Optional floating mode",
  },
  {
    icon: Watch,
    label: "05 — WEAR OS 3+",
    title: "Capture the thought from your wrist.",
    copy: "Dictate from a Wear OS watch through your phone or in standalone mode—useful when pulling out a phone is the slow part.",
    image: "/media/feature-wear.png",
    alt: "Dictate voice input on a Wear OS watch",
    tag: "Wear OS 3+ · tethered or standalone",
  },
];

function FeatureCopy({ feature, index, activeIndex, setActiveIndex }) {
  const ref = useRef(null);
  const inView = useInView(ref, { margin: "-32% 0px -48% 0px" });
  const Icon = feature.icon;

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
      <img className="feature-mobile-image" src={feature.image} width="1080" height="1920" loading="lazy" decoding="async" alt={feature.alt} />
    </button>
  );
}

export function FeatureStory() {
  const [activeIndex, setActiveIndex] = useState(0);
  const reduceMotion = useReducedMotion();
  const feature = features[activeIndex];

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
          <AnimatePresence mode="popLayout" initial={false}>
            <motion.figure
              key={feature.image}
              initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, 12px, 0) scale(0.985)" }}
              animate={{ opacity: 1, transform: "translate3d(0, 0, 0) scale(1)" }}
              exit={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, -4px, 0) scale(0.995)", transition: { duration: reduceMotion ? 0.12 : 0.14, ease: [0.23, 1, 0.32, 1] } }}
              transition={{ duration: reduceMotion ? 0.14 : 0.26, ease: [0.23, 1, 0.32, 1] }}
            >
              <img src={feature.image} width="1080" height="1920" loading="lazy" decoding="async" alt={feature.alt} />
              <figcaption>
                <span>{feature.tag}</span>
                <span>{String(activeIndex + 1).padStart(2, "0")} / {String(features.length).padStart(2, "0")}</span>
              </figcaption>
            </motion.figure>
          </AnimatePresence>
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
