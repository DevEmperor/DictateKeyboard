import { MagnifyingGlass, Play, ShareNetwork } from "@phosphor-icons/react";
import { Reveal } from "./Reveal";

/**
 * The share sheet as a way in.
 *
 * This is not a bullet under the file card because the feature lives in *other* apps' menus: someone
 * who never opens Dictate Keyboard is exactly the person who has to be told it is there. So the art
 * draws the share sheet itself — the dimmed chat, the drag handle, the file being shared, the row of
 * targets — because that is the moment being described, and a reader recognises it before reading a
 * word of the copy.
 *
 * Shapes rather than a screenshot, like every other visual here: a real capture would need a light
 * and a dark version and would be wrong in every language the page is not written in.
 */
const steps = [
  {
    icon: ShareNetwork,
    title: "Share it, like anything else",
    copy: "Dictate is in the share sheet for audio and video — from a chat app, a file manager, a recorder. “Open with” works too.",
  },
  {
    icon: Play,
    title: "It starts on its own",
    copy: "A screen opens and transcription begins. Handing a file to a transcriber has already said what should happen to it; a second button would charge you for the same decision twice.",
  },
  {
    icon: MagnifyingGlass,
    title: "Read it, search it, keep it",
    copy: "The text arrives readable and searchable, with the recording playable beside it. Long files are handled in pieces instead of being turned away.",
  },
];

/** The four targets an Android share sheet would show; the third one is ours. */
const targets = ["Drive", "Files", "Dictate", "More"];

export function VoiceMessage() {
  return (
    <section className="voice-message section-shell" id="voice-messages">
      <div className="section-intro voice-message-intro">
        <Reveal>
          <span className="eyebrow">AUDIO YOU DIDN’T RECORD</span>
          <h2>That voice message.<br /><span>Just read it.</span></h2>
        </Reveal>
        <Reveal delay={0.08}>
          <p>
            Someone sends ninety seconds of audio and you are somewhere you cannot listen. Share it to
            Dictate Keyboard and read it instead — the same share sheet you already use for a photo or a
            link, with the words waiting on the other side of it.
          </p>
        </Reveal>
      </div>

      <div className="voice-message-body">
        <Reveal className="voice-message-art" delay={0.06}>
          <div className="vm-phone" aria-hidden="true">
            {/* The conversation the message arrived in, dimmed the way Android dims it behind a sheet. */}
            <div className="vm-chat">
              <span className="vm-bubble vm-bubble-them" />
              <span className="vm-bubble vm-bubble-me" />
              <span className="vm-bubble vm-bubble-audio">
                <i className="vm-bubble-play" />
                <i className="vm-bubble-wave" />
              </span>
            </div>
            <div className="vm-sheet">
              <span className="vm-handle" />
              <div className="vm-file">
                <span className="vm-file-icon" />
                <span className="vm-file-meta">
                  <strong>Voice message</strong>
                  <em>0:38 · audio/ogg</em>
                </span>
              </div>
              <div className="vm-targets">
                {targets.map((name) => (
                  <span className={`vm-target ${name === "Dictate" ? "is-ours" : ""}`} key={name}>
                    <i />
                    {name}
                  </span>
                ))}
              </div>
            </div>
          </div>

          <span className="vm-arrow" />

          {/* What comes back. Bars, not a sentence — nothing here needs translating. */}
          <div className="vm-result">
            <div className="vm-wave">
              {Array.from({ length: 20 }, (_, i) => (
                <i key={i} style={{ "--h": `${26 + Math.round(60 * Math.abs(Math.sin(i * 1.1)))}%` }} />
              ))}
            </div>
            <div className="vm-lines"><i /><i /><i /><i /></div>
          </div>
        </Reveal>

        <ol className="voice-message-steps">
          {steps.map((item, index) => {
            const Icon = item.icon;
            return (
              <Reveal as="li" className="voice-message-step" delay={0.1 + index * 0.06} key={item.title}>
                <span className="voice-message-step-mark"><Icon size={19} weight="bold" aria-hidden="true" /></span>
                <div>
                  <strong>{item.title}</strong>
                  <p>{item.copy}</p>
                </div>
              </Reveal>
            );
          })}
        </ol>
      </div>
    </section>
  );
}
