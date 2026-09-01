import { MagnifyingGlass, Play, ShareNetwork } from "@phosphor-icons/react";
import { Reveal } from "./Reveal";

/**
 * The share sheet as a way in.
 *
 * File transcription has existed since 4.0.0, and it only ever answered one of the two questions
 * people have about an audio file. Long-press the mic and the words land at the cursor: that is
 * "put this file's words into the field I am typing in". What someone actually does with a voice
 * message is the reverse — you are in a chat, holding the audio, wanting to know what it says.
 *
 * That difference is the section, and it is why this is not a bullet under the file card: the
 * feature lives in *other* apps' share sheets, so a reader who never opens Dictate is exactly the
 * reader who needs to be told it exists.
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

export function VoiceMessage() {
  return (
    <section className="voice-message section-shell" id="voice-messages">
      <div className="section-intro voice-message-intro">
        <Reveal>
          <span className="eyebrow">NEW IN 6.1</span>
          <h2>That voice message.<br /><span>Just read it.</span></h2>
        </Reveal>
        <Reveal delay={0.08}>
          <p>
            Someone sends ninety seconds of audio and you are somewhere you cannot listen. Until now the way
            to the words was: leave the app, open a keyboard, find a text field, long-press the mic, navigate
            back to the file. Now you share it to Dictate Keyboard and read it.
          </p>
        </Reveal>
      </div>

      <div className="voice-message-body">
        <Reveal className="voice-message-art" delay={0.06}>
          <div className="vm-card" aria-hidden="true">
            <span className="vm-card-label">Share with</span>
            <span className="vm-card-app"><i />Dictate Keyboard</span>
          </div>
          <span className="vm-arrow" aria-hidden="true" />
          <div className="vm-wave" aria-hidden="true">
            {Array.from({ length: 22 }, (_, i) => (
              <i key={i} style={{ "--h": `${28 + Math.round(58 * Math.abs(Math.sin(i * 1.1)))}%` }} />
            ))}
          </div>
          <div className="vm-lines" aria-hidden="true"><i /><i /><i /><i /></div>
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
