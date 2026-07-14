import {
  ArrowDown,
  ArrowRight,
  ArrowUpRight,
  BracketsCurly,
  DeviceMobile,
  Gift,
  GithubLogo,
  Keyboard,
  LockKey,
  MagicWand,
  Microphone,
  ShieldCheck,
  Sparkle,
  Translate,
  Watch,
  WifiSlash,
} from "@phosphor-icons/react";
import { motion, useReducedMotion } from "motion/react";
import { DictationDemo } from "./components/DictationDemo";
import { Faq } from "./components/Faq";
import { CapabilityDeck } from "./components/CapabilityDeck";
import { FeatureStory } from "./components/FeatureStory";
import { FreedomComparison } from "./components/FreedomComparison";
import { Header } from "./components/Header";
import { InstallTabs } from "./components/InstallTabs";
import { MagneticLink } from "./components/MagneticLink";
import { ModelBuffet } from "./components/ModelBuffet";
import { ModeSwitcher } from "./components/ModeSwitcher";
import { RewordPlayground } from "./components/RewordPlayground";
import { Reveal } from "./components/Reveal";
import { ScrollProgress } from "./components/ScrollProgress";
import { Waveform } from "./components/Waveform";

const PLAY_URL = "https://play.google.com/store/apps/details?id=net.devemperor.dictate";
const GITHUB_URL = "https://github.com/DevEmperor/DictateKeyboard";
const DEVELOPER_URL = "https://github.com/DevEmperor";

const providers = [
  "OpenAI",
  "Gemini",
  "Groq",
  "Deepgram",
  "Soniox",
  "Mistral",
  "OpenRouter",
  "Anthropic",
  "ElevenLabs",
  "Ollama",
];

const benefits = [
  {
    icon: WifiSlash,
    title: "Use it offline",
    copy: "Download a Whisper or Parakeet model and transcribe on-device, with no cloud usage after the model is installed.",
    visual: (
      <div className="offline-visual" aria-hidden="true">
        <span className="offline-phone"><DeviceMobile size={20} weight="bold" /></span>
        <span className="offline-path"><span /></span>
        <span className="offline-label">LOCAL MODEL</span>
      </div>
    ),
  },
  {
    icon: Gift,
    title: "Open source. No subscription.",
    copy: "Inspect the Apache-2.0 code or build it yourself. Offline models add no API usage; optional cloud providers bill you directly.",
    visual: (
      <div className="free-visual" aria-hidden="true">
        <span><GithubLogo size={54} weight="fill" /></span>
        <small>OPEN SOURCE<br />NO SUBSCRIPTION</small>
      </div>
    ),
  },
  {
    icon: MagicWand,
    title: "Send cleaner text",
    copy: "Translate, summarize, shorten, change tone, or build a custom action for the exact writing you repeat every day.",
    visual: (
      <div className="prompt-visual" aria-hidden="true">
        <span><Sparkle size={13} weight="fill" />Polish</span>
        <span><Translate size={13} weight="bold" />Translate</span>
        <span><ArrowRight size={13} weight="bold" />Shorten</span>
      </div>
    ),
  },
  {
    icon: Keyboard,
    title: "Keep a real keyboard",
    copy: "Glide typing, suggestions, autocorrect, emoji, clipboard, themes, gestures, and cursor tools remain one tap away.",
    visual: (
      <div className="mini-keyboard" aria-hidden="true">
        {["D", "I", "C", "T", "A", "T", "E"].map((key, index) => <span key={`${key}-${index}`}>{key}</span>)}
      </div>
    ),
  },
];

function ProviderMarquee() {
  const repeated = [...providers, ...providers];
  return (
    <div className="provider-marquee" aria-label={`Supported providers include ${providers.join(", ")}`}>
      <div className="provider-marquee-track" aria-hidden="true">
        {repeated.map((provider, index) => (
          <span key={`${provider}-${index}`}><i />{provider}</span>
        ))}
      </div>
    </div>
  );
}

function BenefitPanel({ item, index }) {
  const Icon = item.icon;
  return (
    <Reveal className={`benefit-panel benefit-panel-${index + 1}`} delay={index * 0.06}>
      <div className="benefit-panel-top">
        <span className="benefit-index">0{index + 1}</span>
        <span className="benefit-icon"><Icon size={20} weight="bold" aria-hidden="true" /></span>
      </div>
      {item.visual}
      <div className="benefit-panel-copy">
        <h3>{item.title}</h3>
        <p>{item.copy}</p>
      </div>
    </Reveal>
  );
}

function App() {
  const reduceMotion = useReducedMotion();

  return (
    <>
      <a className="skip-link" href="#main-content">Skip to content</a>
      <ScrollProgress />
      <Header />

      <main id="main-content">
        <section className="hero" id="top">
          <div className="hero-noise" aria-hidden="true" />
          <div className="hero-grid">
            <div className="hero-copy">
              <motion.div
                className="hero-eyebrow"
                initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, 10px, 0)" }}
                animate={{ opacity: 1, transform: "translate3d(0, 0, 0)" }}
                transition={{ delay: 0.15, duration: 0.55, ease: [0.23, 1, 0.32, 1] }}
              >
                <span />
                OPEN-SOURCE VOICE TYPING FOR ANDROID
              </motion.div>

              <h1 className="hero-title" aria-label="Stop renting your own voice.">
                {[
                  { key: "renting", content: <>Stop <span className="renting-word">renting</span></> },
                  { key: "your-own", content: "your own" },
                  { key: "voice", content: "voice.", accent: true },
                ].map((line, index) => (
                  <span className={line.accent ? "accent-line" : ""} key={line.key}>
                    <motion.span
                      initial={{ transform: reduceMotion ? "none" : "translate3d(0, 112%, 0)" }}
                      animate={{ transform: "translate3d(0, 0, 0)" }}
                      transition={{ delay: 0.22 + index * 0.08, duration: 0.85, ease: [0.23, 1, 0.32, 1] }}
                    >
                      {line.content}
                    </motion.span>
                  </span>
                ))}
              </h1>

              <motion.p
                className="hero-body"
                initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, 16px, 0)" }}
                animate={{ opacity: 1, transform: "translate3d(0, 0, 0)" }}
                transition={{ delay: 0.58, duration: 0.65, ease: [0.23, 1, 0.32, 1] }}
              >
                Paid voice apps turn access to your own words into another managed plan. Dictate is the open-source Android keyboard that lets you choose what happens behind the mic: realtime cloud, private offline, or your own compatible provider.
              </motion.p>

              <motion.div
                className="hero-actions"
                initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, 14px, 0)" }}
                animate={{ opacity: 1, transform: "translate3d(0, 0, 0)" }}
                transition={{ delay: 0.7, duration: 0.6, ease: [0.23, 1, 0.32, 1] }}
              >
                <MagneticLink className="button button-accent" href={PLAY_URL} target="_blank" rel="noreferrer">
                  Get Dictate on Google Play
                  <ArrowUpRight size={17} weight="bold" aria-hidden="true" />
                </MagneticLink>
                <a className="button button-ghost" href={GITHUB_URL} target="_blank" rel="noreferrer">
                  <GithubLogo size={18} weight="fill" aria-hidden="true" />
                  Explore the source
                </a>
              </motion.div>

              <motion.p
                className="hero-fineprint"
                initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, 6px, 0)" }}
                animate={{ opacity: 1, transform: "translate3d(0, 0, 0)" }}
                transition={{ delay: 0.92, duration: 0.5, ease: [0.23, 1, 0.32, 1] }}
              >
                No monthly Dictate subscription · Android 8.0+ · Optional provider API charges only
              </motion.p>
            </div>

            <div className="hero-visual">
              <DictationDemo />
            </div>
          </div>

          <motion.a
            className="scroll-cue"
            href="#benefits"
            initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, 6px, 0)" }}
            animate={{ opacity: 1, transform: "translate3d(0, 0, 0)" }}
            transition={{ delay: 1.2, duration: 0.5, ease: [0.23, 1, 0.32, 1] }}
          >
            <span>DISCOVER DICTATE</span>
            <ArrowDown size={16} weight="bold" aria-hidden="true" />
          </motion.a>
        </section>

        <section className="proof-strip" aria-label="Dictate product facts">
          <div className="proof-strip-inner">
            {[
              [BracketsCurly, "Apache 2.0", "Open source"],
              [Gift, "No monthly fee", "No Dictate subscription"],
              [WifiSlash, "On-device", "Offline option"],
              [ShieldCheck, "No tracking", "No hosted Dictate backend"],
              [DeviceMobile, "Any app", "Android system keyboard"],
              [Watch, "Wear OS 3+", "Tethered or standalone"],
            ].map(([Icon, title, copy]) => (
              <div className="proof-item" key={title}>
                <Icon size={19} weight="bold" aria-hidden="true" />
                <div><strong>{title}</strong><span>{copy}</span></div>
              </div>
            ))}
          </div>
        </section>

        <section className="benefits section-shell" id="benefits">
          <div className="section-intro section-intro-split">
            <Reveal>
              <span className="eyebrow">WHY RENT THE VOICE LAYER?</span>
              <h2>More than voice-to-text.<br /><span>Open, flexible, and yours.</span></h2>
            </Reveal>
            <Reveal delay={0.08}>
              <p>Dictate meets you where the sentence belongs. Speak into messages, notes, email, documents, and forms—without sending every thought through the same company.</p>
            </Reveal>
          </div>

          <div className="benefit-grid">
            {benefits.map((item, index) => <BenefitPanel item={item} index={index} key={item.title} />)}
          </div>
        </section>

        <FreedomComparison />

        <section className="modes section-shell">
          <div className="section-intro mode-intro">
            <Reveal>
              <span className="eyebrow">ONE KEYBOARD. THREE ROUTES.</span>
              <h2>Use the path that fits the moment.</h2>
            </Reveal>
            <Reveal delay={0.08}>
              <p>Fast cloud streaming when you want it. On-device control when you need it. A floating mic when you want to keep another keyboard.</p>
            </Reveal>
          </div>
          <Reveal delay={0.1}><ModeSwitcher /></Reveal>
        </section>

        <ProviderMarquee />

        <section className="model-buffet-section section-shell" id="models">
          <div className="section-intro buffet-intro">
            <Reveal>
              <span className="eyebrow">THE MODEL BUFFET</span>
              <h2>No bundled engine.<br /><span>No monthly Dictate plan.</span></h2>
            </Reveal>
            <Reveal delay={0.08}>
              <p>Managed voice apps bundle the model, account, and bill into one service. Dictate separates them: choose for privacy, language, realtime speed, measured word error rate, or provider API cost—then pick a writing model for the finish.</p>
            </Reveal>
          </div>
          <Reveal delay={0.1}><ModelBuffet /></Reveal>
        </section>

        <section className="features section-shell" id="features">
          <div className="section-intro feature-intro">
            <Reveal>
              <span className="eyebrow">NOT ANOTHER DICTATION WIDGET</span>
              <h2>A complete Android keyboard.<br /><span>Voice just happens to be the shortcut.</span></h2>
            </Reveal>
            <Reveal delay={0.08}>
              <p>Glide typing, autocorrect, clipboard, emoji, themes, gestures, and transcription live in one Android input method. Prefer your current keyboard? The floating mic remains optional.</p>
            </Reveal>
          </div>
          <FeatureStory />
          <RewordPlayground />
          <CapabilityDeck />
        </section>

        <section className="depth-section section-shell">
          <div className="depth-grid">
            <Reveal className="depth-primary">
              <span className="eyebrow">LONG-FORM MODE</span>
              <h2>Keep talking.<br />Dictate keeps up.</h2>
              <p>Long recordings are split into background transcription segments, so text can arrive sooner without a fixed dictation-length limit.</p>
              <div className="longform-timeline" aria-hidden="true">
                <div className="timeline-labels"><span>00:00</span><span>04:32</span><span>09:18</span></div>
                <div className="timeline-track">
                  <span className="timeline-progress" />
                  <i /><i /><i /><i /><i />
                </div>
                <div className="timeline-status"><Waveform compact /><span>TRANSCRIBING IN SEGMENTS</span></div>
              </div>
            </Reveal>

            <Reveal className="depth-secondary" delay={0.08}>
              <span className="eyebrow">HISTORY, WITH CONTROL</span>
              <h3>Find the sentence again.</h3>
              <p>Search, reinsert, replay, re-transcribe, or pin a previous dictation. Transcript history and audio retention stay configurable in the app.</p>
              <div className="history-list" aria-hidden="true">
                {["Product review notes", "Message to the design team", "Train of thought — launch"].map((item, index) => (
                  <div key={item}><span>0{index + 1}</span><strong>{item}</strong><small>{["2 min", "38 sec", "6 min"][index]}</small></div>
                ))}
              </div>
            </Reveal>
          </div>
        </section>

        <section className="control-section" id="privacy">
          <div className="control-inner section-shell">
            <div className="control-heading">
              <Reveal>
                <span className="eyebrow eyebrow-light">YOUR VOICE. YOUR DATA. YOUR CODE.</span>
                <h2>Verify the route.<br />Don’t just trust the promise.</h2>
              </Reveal>
              <Reveal delay={0.08}>
                <p>A closed-source service asks you to trust its privacy promise. Dictate gives you code to inspect, an offline route that keeps transcription audio on-device, and explicit control over any provider or proxy you configure.</p>
              </Reveal>
            </div>

            <div className="privacy-proof">
              <Reveal className="privacy-statement">
                <span className="privacy-lock"><LockKey size={27} weight="bold" /></span>
                <span className="eyebrow eyebrow-light">PRIVACY, MADE LEGIBLE</span>
                <h2>No Dictate server between your voice and your chosen route.</h2>
                <p>Dictate has no developer-operated backend or remote database, ads, analytics, tracking, telemetry, or crash-reporting SDK. Offline audio stays on your device. By default, cloud audio goes directly to the provider you configure; an optional HTTP/SOCKS proxy follows your own routing settings.</p>
                <a href="https://github.com/DevEmperor/DictateKeyboard/blob/main/PRIVACY_POLICY.md" target="_blank" rel="noreferrer">
                  Read the repository privacy policy
                  <ArrowUpRight size={16} weight="bold" />
                </a>
                <small className="privacy-policy-note">Current releases can also keep configurable local transcript history or audio. Those copies remain under the app’s on-device retention settings.</small>
              </Reveal>

              <div className="privacy-facts">
                {[
                  ["01", "No Dictate account", "Start without creating another identity."],
                  ["02", "No keystroke logging", "General typing is not recorded or transmitted."],
                  ["03", "Keys stay local", "Provider credentials live in app-private storage."],
                  ["04", "Offline is real", "Downloaded models transcribe directly on-device."],
                ].map(([number, title, copy], index) => (
                  <Reveal className="privacy-fact" delay={index * 0.05} key={number}>
                    <span>{number}</span><div><strong>{title}</strong><p>{copy}</p></div>
                  </Reveal>
                ))}
              </div>
            </div>
          </div>
        </section>

        <section className="install section-shell" id="install">
          <div className="section-intro install-intro">
            <Reveal>
              <span className="eyebrow">INSTALL DICTATE</span>
              <h2>Two ways in.<br />One clear setup.</h2>
            </Reveal>
            <Reveal delay={0.08}>
              <p>Choose the supported Play Store build with automatic updates, or inspect and build every line from the Apache-2.0 source. Neither route adds a monthly Dictate subscription.</p>
            </Reveal>
          </div>
          <Reveal delay={0.1}><InstallTabs /></Reveal>
        </section>

        <section className="faq section-shell" id="faq">
          <div className="faq-heading">
            <Reveal>
              <span className="eyebrow">THE STRAIGHT ANSWERS</span>
              <h2>Before you switch.</h2>
            </Reveal>
            <Reveal delay={0.08}>
              <p>Clear answers about free use, provider APIs, offline models, privacy, and installation.</p>
            </Reveal>
          </div>
          <Reveal delay={0.1}><Faq /></Reveal>
        </section>

        <section className="final-cta section-shell">
          <div className="final-cta-inner">
            <div className="final-orbit final-orbit-one" aria-hidden="true" />
            <div className="final-orbit final-orbit-two" aria-hidden="true" />
            <Reveal>
              <span className="eyebrow">YOUR NEXT SENTENCE</span>
              <h2>Skip the monthly plan.<br />Start talking.</h2>
              <p>No Dictate subscription. No Dictate account. Use an offline model with no API usage, or connect a provider that bills you directly.</p>
              <div className="final-actions">
                <MagneticLink className="button button-accent" href={PLAY_URL} target="_blank" rel="noreferrer">
                  Get Dictate on Google Play
                  <ArrowUpRight size={17} weight="bold" />
                </MagneticLink>
                <a className="button button-dark-outline" href={GITHUB_URL} target="_blank" rel="noreferrer">
                  <GithubLogo size={18} weight="fill" />
                  Star on GitHub
                </a>
              </div>
              <a className="creator-credit" href={DEVELOPER_URL} target="_blank" rel="noreferrer" aria-label="Jannis Zahn, creator and lead developer of Dictate on GitHub">
                <span className="creator-credit-mark"><GithubLogo size={22} weight="fill" /></span>
                <span className="creator-credit-copy"><small>CREATOR &amp; LEAD DEVELOPER</small><strong>Jannis Zahn <em>@DevEmperor</em></strong></span>
                <ArrowUpRight size={16} weight="bold" aria-hidden="true" />
              </a>
              <small>No monthly Dictate subscription · Optional cloud APIs may charge for usage · Offline transcription uses no API</small>
            </Reveal>
          </div>
        </section>
      </main>

      <footer className="footer section-shell">
        <div className="footer-brand">
          <a className="brand" href="#top"><img src="/media/dictate-icon.png" width="36" height="36" alt="" /><span>Dictate</span></a>
          <p>Open-source voice typing for Android—created by <a href={DEVELOPER_URL} target="_blank" rel="noreferrer">Jannis Zahn / DevEmperor</a>.</p>
        </div>
        <div className="footer-links">
          <div><strong>Product</strong><a href="#features">Features</a><a href="#privacy">Privacy</a><a href="#install">Install</a><a href="#faq">FAQ</a></div>
          <div><strong>Project</strong><a href={GITHUB_URL} target="_blank" rel="noreferrer">GitHub</a><a href={`${GITHUB_URL}/issues`} target="_blank" rel="noreferrer">Issues</a><a href={`${GITHUB_URL}/blob/main/LICENSE`} target="_blank" rel="noreferrer">Apache 2.0</a><a href="https://paypal.me/DevEmperor" target="_blank" rel="noreferrer">Support development</a></div>
        </div>
        <div className="footer-bottom">
          <span>Created and maintained by <a href={DEVELOPER_URL} target="_blank" rel="noreferrer">Jannis Zahn / DevEmperor</a>. Keyboard foundation by FlorisBoard contributors.</span>
          <a href="#top">Back to top <ArrowUpRight size={14} weight="bold" /></a>
        </div>
      </footer>
    </>
  );
}

export default App;
