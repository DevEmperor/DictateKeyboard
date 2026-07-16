import { ArrowUpRight, Lightning, MagicWand, Microphone } from "@phosphor-icons/react";
import { Reveal } from "./Reveal";
import { GooglePlayGlyph } from "./GooglePlayBadge";

const PLAY_URL = "https://play.google.com/store/apps/details?id=net.devemperor.dictate";

const points = [
  [Microphone, "Tap the mic in any field", "No app-switching — dictate right where you’re already typing."],
  [Lightning, "Words land as you speak", "With a streaming provider the text arrives in realtime."],
  [MagicWand, "Polish before you send", "One tap turns a rough draft into finished text."],
];

export function VideoShowcase() {
  return (
    <section className="video-showcase section-shell">
      <div className="video-grid">
        <Reveal className="video-copy">
          <span className="eyebrow">SEE IT IN ACTION</span>
          <h2>Speak.<br />Watch it land.</h2>
          <p>A real recording of Dictate Keyboard on Android — from tapping the mic to sending clean, finished text. No edits, no cuts.</p>
          <ul className="video-points">
            {points.map(([Icon, title, copy]) => (
              <li key={title}>
                <span className="video-point-icon"><Icon size={17} weight="bold" aria-hidden="true" /></span>
                <div><strong>{title}</strong><span>{copy}</span></div>
              </li>
            ))}
          </ul>
          <a className="button button-accent" href={PLAY_URL} target="_blank" rel="noreferrer">
            <GooglePlayGlyph size={17} />
            Try it yourself
            <ArrowUpRight size={16} weight="bold" aria-hidden="true" />
          </a>
        </Reveal>

        <Reveal className="video-frame" delay={0.08}>
          <div className="video-phone">
            <span className="video-phone-notch" aria-hidden="true" />
            <video
              className="video-el"
              autoPlay
              muted
              loop
              playsInline
              preload="metadata"
              poster="/media/demo-poster.jpg"
              aria-label="Screen recording of Dictate Keyboard dictating and rewording text on Android"
            >
              <source src="/media/demo.mp4" type="video/mp4" />
            </video>
          </div>
          <div className="video-glow" aria-hidden="true" />
        </Reveal>
      </div>
    </section>
  );
}
