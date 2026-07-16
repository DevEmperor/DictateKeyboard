import { HandHeart, Keyboard, PersonArmsSpread, SpeakerHigh } from "@phosphor-icons/react";
import { Reveal } from "./Reveal";

const cases = [
  {
    icon: HandHeart,
    title: "Easier on hands and wrists",
    copy: "When typing hurts — RSI, arthritis, an injury — speaking a message instead keeps you writing without the strain.",
  },
  {
    icon: PersonArmsSpread,
    title: "A path around fine-motor barriers",
    copy: "For limited dexterity or tremor, a voice-first flow and the classic large-target layout make everyday text far more reachable.",
  },
  {
    icon: Keyboard,
    title: "Spelling stops being the blocker",
    copy: "Dyslexia and unfamiliar spellings matter less when you say the sentence and let transcription and AI cleanup handle the letters.",
  },
  {
    icon: SpeakerHigh,
    title: "Works with the tools you rely on",
    copy: "A real system keyboard with haptics, Bluetooth-mic input and configurable feedback, so it fits into how you already use your phone.",
  },
];

export function Accessibility() {
  return (
    <section className="accessibility section-shell" id="accessibility">
      <div className="access-intro section-intro">
        <Reveal>
          <span className="eyebrow">VOICE FOR EVERYONE</span>
          <h2>Typing shouldn’t be the hard part.</h2>
        </Reveal>
        <Reveal delay={0.08}>
          <p>Voice input isn’t only about speed. For a lot of people it’s the difference between writing comfortably and not writing at all — so accessibility is a first-class reason Dictate Keyboard exists, not an afterthought.</p>
        </Reveal>
      </div>

      <div className="access-grid">
        {cases.map((item, index) => {
          const Icon = item.icon;
          return (
            <Reveal className="access-card" delay={(index % 2) * 0.06} key={item.title}>
              <span className="access-icon"><Icon size={22} weight="bold" aria-hidden="true" /></span>
              <h3>{item.title}</h3>
              <p>{item.copy}</p>
            </Reveal>
          );
        })}
      </div>
    </section>
  );
}
