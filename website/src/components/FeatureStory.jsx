import { useEffect, useRef, useState } from "react";
import {
  ArrowsClockwise,
  CloudCheck,
  Keyboard,
  MagicWand,
  Watch,
} from "@phosphor-icons/react";
import { motion, useInView, useReducedMotion } from "motion/react";

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

const decodedImages = new Map();

function decodeFeatureImage(src) {
  if (decodedImages.has(src)) return decodedImages.get(src);

  const readiness = new Promise((resolve, reject) => {
    const image = new Image();
    let settled = false;

    const finish = () => {
      if (settled) return;
      settled = true;
      const decode = typeof image.decode === "function" ? image.decode() : Promise.resolve();
      decode.catch(() => undefined).then(() => resolve(src));
    };

    image.decoding = "async";
    image.addEventListener("load", finish, { once: true });
    image.addEventListener("error", () => {
      if (settled) return;
      settled = true;
      reject(new Error(`Unable to load feature preview: ${src}`));
    }, { once: true });
    image.src = src;

    if (image.complete && image.naturalWidth > 0) finish();
  });

  decodedImages.set(src, readiness);
  readiness.catch(() => decodedImages.delete(src));
  return readiness;
}

function useDesktopPreview() {
  const [isDesktop, setIsDesktop] = useState(false);

  useEffect(() => {
    const media = window.matchMedia("(min-width: 1021px)");
    const update = () => setIsDesktop(media.matches);

    update();
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);

  return isDesktop;
}

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
  const [displayedIndex, setDisplayedIndex] = useState(0);
  const [incomingIndex, setIncomingIndex] = useState(null);
  const [incomingReady, setIncomingReady] = useState(false);
  const baseImageRef = useRef(null);
  const incomingImageRef = useRef(null);
  const reduceMotion = useReducedMotion();
  const isDesktopPreview = useDesktopPreview();
  const displayedFeature = features[displayedIndex];

  useEffect(() => {
    if (!isDesktopPreview) return undefined;

    const preload = () => {
      features.slice(1).forEach((item) => {
        decodeFeatureImage(item.image).catch(() => undefined);
      });
    };

    if ("requestIdleCallback" in window) {
      const idleId = window.requestIdleCallback(preload, { timeout: 2400 });
      return () => window.cancelIdleCallback(idleId);
    }

    const timeoutId = window.setTimeout(preload, 700);
    return () => window.clearTimeout(timeoutId);
  }, [isDesktopPreview]);

  useEffect(() => {
    if (!isDesktopPreview) return;

    if (activeIndex === displayedIndex) {
      if (incomingIndex !== null && !incomingReady) setIncomingIndex(null);
      return;
    }

    if (incomingIndex === null || (!incomingReady && incomingIndex !== activeIndex)) {
      setIncomingReady(false);
      setIncomingIndex(activeIndex);
    }
  }, [activeIndex, displayedIndex, incomingIndex, incomingReady, isDesktopPreview]);

  useEffect(() => {
    if (incomingIndex === null) return undefined;

    const image = incomingImageRef.current;
    if (!image) return undefined;
    let cancelled = false;

    const reveal = async () => {
      try {
        if (typeof image.decode === "function") await image.decode();
      } catch {
        return;
      }
      if (!cancelled) setIncomingReady(true);
    };

    if (image.complete && image.naturalWidth > 0) {
      reveal();
    } else {
      image.addEventListener("load", reveal, { once: true });
    }

    return () => {
      cancelled = true;
      image.removeEventListener("load", reveal);
    };
  }, [incomingIndex]);

  useEffect(() => {
    if (incomingIndex === null || !incomingReady || displayedIndex !== incomingIndex) return undefined;

    const image = baseImageRef.current;
    if (!image) return undefined;
    let cancelled = false;

    const revealBase = async () => {
      try {
        if (typeof image.decode === "function") await image.decode();
      } catch {
        return;
      }
      if (cancelled) return;
      setIncomingReady(false);
      setIncomingIndex(null);
    };

    if (image.complete && image.naturalWidth > 0) {
      revealBase();
    } else {
      image.addEventListener("load", revealBase, { once: true });
    }

    return () => {
      cancelled = true;
      image.removeEventListener("load", revealBase);
    };
  }, [displayedIndex, incomingIndex, incomingReady]);

  const promoteIncoming = (index) => {
    if (!incomingReady || incomingIndex !== index) return;
    setDisplayedIndex(index);
  };

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
          <figure>
            <div className="feature-visual-media">
              <img
                ref={baseImageRef}
                className="feature-visual-base"
                src={displayedFeature.image}
                width="1080"
                height="1920"
                loading={isDesktopPreview ? "eager" : "lazy"}
                decoding="async"
                alt={displayedFeature.alt}
              />
              {incomingIndex !== null && (
                <motion.img
                  ref={incomingImageRef}
                  className="feature-visual-incoming"
                  key={features[incomingIndex].image}
                  src={features[incomingIndex].image}
                  width="1080"
                  height="1920"
                  initial={{ opacity: 0, transform: reduceMotion ? "translate3d(0, 0, 0)" : "translate3d(0, 6px, 0) scale(0.997)" }}
                  animate={incomingReady
                    ? { opacity: 1, transform: "translate3d(0, 0, 0) scale(1)" }
                    : { opacity: 0, transform: reduceMotion ? "translate3d(0, 0, 0)" : "translate3d(0, 6px, 0) scale(0.997)" }}
                  transition={{ duration: reduceMotion ? 0.12 : 0.2, ease: [0.23, 1, 0.32, 1] }}
                  onAnimationComplete={() => promoteIncoming(incomingIndex)}
                  alt=""
                  aria-hidden="true"
                />
              )}
            </div>
            <figcaption aria-live="polite">
              <span>{displayedFeature.tag}</span>
              <span>{String(displayedIndex + 1).padStart(2, "0")} / {String(features.length).padStart(2, "0")}</span>
            </figcaption>
          </figure>
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
