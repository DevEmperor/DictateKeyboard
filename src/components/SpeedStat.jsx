import { useRef } from "react";
import { Keyboard, Microphone } from "@phosphor-icons/react";
import { useInView } from "motion/react";
import { Reveal } from "./Reveal";
import { CountUp } from "./CountUp";

/**
 * "Voice is faster than typing" band — a quantified hook (like Wispr Flow's 220-vs-45 wpm), with two bars
 * that fill on scroll-in. Numbers are typical figures, not a benchmark.
 */
export function SpeedStat() {
  const ref = useRef(null);
  const inView = useInView(ref, { once: true, margin: "-15% 0px" });

  return (
    <section className="speed-stat section-shell">
      <div className="speed-grid">
        <Reveal className="speed-copy">
          <span className="eyebrow">WHY VOICE</span>
          <h2>Roughly <span>3× faster</span><br />than typing.</h2>
          <p>Most people speak around 150 words a minute but thumb-type closer to 40. Say the sentence and let Dictate Keyboard write it — the draft is done before you’d have finished the first line.</p>
        </Reveal>

        <div className={`speed-bars ${inView ? "is-in" : ""}`} ref={ref}>
          <div className="speed-bar">
            <div className="speed-bar-head">
              <span className="speed-bar-icon speed-bar-icon-voice"><Microphone size={16} weight="fill" aria-hidden="true" /></span>
              Speaking
            </div>
            <div className="speed-bar-track">
              <span className="speed-bar-fill speed-bar-fill-voice" style={{ "--w": "100%" }} />
            </div>
            <strong><CountUp value="150" /> <em>wpm</em></strong>
          </div>

          <div className="speed-bar">
            <div className="speed-bar-head">
              <span className="speed-bar-icon"><Keyboard size={16} weight="bold" aria-hidden="true" /></span>
              Typing
            </div>
            <div className="speed-bar-track">
              <span className="speed-bar-fill" style={{ "--w": "27%" }} />
            </div>
            <strong><CountUp value="40" /> <em>wpm</em></strong>
          </div>

          <small className="speed-note">Typical speaking vs. mobile typing speed — your mileage varies.</small>
        </div>
      </div>
    </section>
  );
}
