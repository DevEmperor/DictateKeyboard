import { ArrowUpRight, Coins } from "@phosphor-icons/react";
import { Reveal } from "./Reveal";

const REPO = "https://github.com/DevEmperor/DictateKeyboard";

/**
 * Dictate Cloud — the fourth route, and the one place where audio passes through a machine we run.
 *
 * The whole section is written to survive a sceptical reader, because that is who it is for: someone who
 * chose this app *because* there was no server in the middle. So the admission comes before the pitch, the
 * three claims are the ones the code can be checked against, and the closing line says plainly that their
 * own key is still cheaper. A page that sold this without saying that would be trading a reputation for a
 * conversion.
 *
 * The layout carries that split: the left column is what we say, the right column is what can be checked.
 * Numbering the claims is borrowed from the privacy section further down — it reads as a list of facts
 * rather than three marketing cards, which is the register this particular argument has to be made in.
 */
const promises = [
  {
    title: "Nothing is written down",
    copy: "Your audio and your text are forwarded to the provider and the answer comes straight back. What is kept is numbers: wallet id, timestamp, duration, token counts, status code, milliseconds.",
  },
  {
    title: "It doesn’t know who you are",
    copy: "An account is a wallet and a recovery code — no name, no email address, no sign-in. You can delete it from inside the app, and the app tells you what deletion leaves behind before you do.",
  },
  {
    title: "You can read the server",
    copy: "One Cloudflare Worker, in the same repository as the app. meter.ts is the only file in it that writes anything at all — which is the claim above, in a form you can check yourself.",
  },
];

export function CloudSection() {
  return (
    <section className="cloud-section" id="cloud">
      <div className="cloud-inner section-shell">
        <div className="cloud-heading">
          <Reveal>
            <span className="eyebrow eyebrow-light">NEW IN 6.0 · DICTATE CLOUD</span>
            <h2>No key to find.<br />Just buy the minutes.</h2>
          </Reveal>
          <Reveal delay={0.08}>
            <p>
              Setting up a provider account is a small wall, and for some people it is the whole wall. So there
              is now a fourth way in: buy prepaid minutes on Google Play and start dictating. Larger packs cost
              less per minute, and the app shows by how much.
            </p>
          </Reveal>
        </div>

        <div className="cloud-body">
          <div className="cloud-say">
            <Reveal className="cloud-admission" delay={0.06}>
              <span className="cloud-admission-mark"><Coins size={26} weight="bold" aria-hidden="true" /></span>
              <h3>Yes — this one runs through a server of ours.</h3>
              <p>
                It is the only route that does, and you never have to take it. Since we are asking you to,
                here is what that server does with what you say.
              </p>
            </Reveal>

            <Reveal className="cloud-links" delay={0.1}>
              <a href={`${REPO}/tree/main/cloud`} target="_blank" rel="noreferrer">
                Read the server source
                <ArrowUpRight size={16} weight="bold" aria-hidden="true" />
              </a>
              <a href={`${REPO}/blob/main/cloud/src/meter.ts`} target="_blank" rel="noreferrer">
                meter.ts — the only file that writes
                <ArrowUpRight size={16} weight="bold" aria-hidden="true" />
              </a>
            </Reveal>

            <Reveal className="cloud-footnote" delay={0.12}>
              <p>
                If you already have an API key, keep using it — it is the cheaper route and nothing about it
                has changed. Credit exists so that not having one stops being a reason to walk away.
              </p>
            </Reveal>
          </div>

          <ol className="cloud-promises">
            {promises.map((item, index) => (
              <Reveal as="li" className="cloud-promise" delay={0.1 + index * 0.06} key={item.title}>
                <span className="cloud-promise-number">{String(index + 1).padStart(2, "0")}</span>
                <div>
                  <strong>{item.title}</strong>
                  <p>{item.copy}</p>
                </div>
              </Reveal>
            ))}
          </ol>
        </div>
      </div>
    </section>
  );
}
