import { useRef } from "react";
import { GlobeHemisphereWest, Keyboard, Translate } from "@phosphor-icons/react";
import { Reveal } from "./Reveal";
import { CountUp } from "./CountUp";
import { useMarqueeScroll } from "../lib/interactions";

const stats = [
  [Translate, "99", "languages to speak", "Whisper, Parakeet and cloud providers transcribe a huge range of languages."],
  [Keyboard, "40+", "languages to type", "Glide typing, suggestions and autocorrect ship per-language dictionaries."],
  [GlobeHemisphereWest, "20+", "languages in the app", "The interface itself is translated, so the whole keyboard speaks your language."],
];

// Native-script names — a broad, representative spread (not the full list).
const languageNames = [
  "English", "Deutsch", "Español", "Français", "Italiano", "Português", "Nederlands",
  "Polski", "Русский", "Українська", "Čeština", "Svenska", "Norsk", "Dansk", "Suomi",
  "Magyar", "Română", "Türkçe", "Ελληνικά", "Български", "Català", "Hrvatski",
  "中文", "日本語", "한국어", "Tiếng Việt", "ไทย", "العربية", "עברית", "हिन्दी", "فارسی",
];

export function Languages() {
  const trackRef = useRef(null);
  useMarqueeScroll(trackRef, 30);
  return (
    <section className="languages" id="languages">
      <div className="languages-inner section-shell">
        <div className="section-intro languages-intro">
          <Reveal>
            <span className="eyebrow eyebrow-light">SPEAKS YOUR LANGUAGE</span>
            <h2>Dictate in the language<br /><span>you actually think in.</span></h2>
          </Reveal>
          <Reveal delay={0.08}>
            <p>Voice input is only useful if it understands you. Dictate Keyboard transcribes dozens of languages, types with real per-language dictionaries, and even localizes its own interface.</p>
          </Reveal>
        </div>

        <div className="languages-stats">
          {stats.map(([Icon, big, label, copy], index) => (
            <Reveal className="languages-stat" delay={index * 0.06} key={label}>
              <span className="languages-stat-icon"><Icon size={20} weight="bold" aria-hidden="true" /></span>
              <strong><CountUp value={big} /></strong>
              <span className="languages-stat-label">{label}</span>
              <p>{copy}</p>
            </Reveal>
          ))}
        </div>

        <Reveal className="language-marquee" delay={0.1}>
          <div className="language-marquee-track" ref={trackRef} aria-label={`Supported languages include ${languageNames.join(", ")}`}>
            {[...languageNames, ...languageNames].map((name, index) => (
              <span key={`${name}-${index}`} aria-hidden={index >= languageNames.length}>{name}</span>
            ))}
          </div>
        </Reveal>
      </div>
    </section>
  );
}
