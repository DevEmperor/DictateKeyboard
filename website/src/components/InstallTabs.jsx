import { useEffect, useState } from "react";
import {
  ArrowUpRight,
  Check,
  ClipboardText,
  Code,
  DownloadSimple,
  GithubLogo,
  Key,
  Microphone,
} from "@phosphor-icons/react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";

const cloneCommand = `git clone https://github.com/DevEmperor/DictateKeyboard.git
cd DictateKeyboard
./gradlew :app:assembleDebug`;

const playSteps = [
  { icon: DownloadSimple, label: "Install", copy: "Get the official build from Google Play." },
  { icon: Check, label: "Enable", copy: "Follow the setup to enable and select Dictate." },
  { icon: Key, label: "Choose", copy: "Download an offline model or connect a provider." },
  { icon: Microphone, label: "Speak", copy: "Open any text field and tap the mic." },
];

export function InstallTabs() {
  const [tab, setTab] = useState("play");
  const [copyState, setCopyState] = useState("idle");
  const reduceMotion = useReducedMotion();

  useEffect(() => {
    if (copyState === "idle") return undefined;
    const timer = window.setTimeout(() => setCopyState("idle"), 2200);
    return () => window.clearTimeout(timer);
  }, [copyState]);

  async function copyCommand() {
    try {
      await navigator.clipboard.writeText(cloneCommand);
      setCopyState("copied");
    } catch {
      setCopyState("error");
    }
  }

  return (
    <div className="install-box">
      <div className="install-tabs" role="tablist" aria-label="Installation routes">
        <button
          type="button"
          role="tab"
          aria-selected={tab === "play"}
          aria-controls="install-panel-play"
          onClick={() => setTab("play")}
          className={tab === "play" ? "is-active" : ""}
        >
          {tab === "play" && <motion.span layoutId={reduceMotion ? undefined : "install-tab"} className="install-tab-bg" transition={{ type: "spring", bounce: 0, duration: 0.24 }} />}
          <span>Google Play</span>
          <small>Recommended</small>
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === "source"}
          aria-controls="install-panel-source"
          onClick={() => setTab("source")}
          className={tab === "source" ? "is-active" : ""}
        >
          {tab === "source" && <motion.span layoutId={reduceMotion ? undefined : "install-tab"} className="install-tab-bg" transition={{ type: "spring", bounce: 0, duration: 0.24 }} />}
          <span>Build from source</span>
          <small>Developer route</small>
        </button>
      </div>

      <AnimatePresence mode="popLayout" initial={false}>
        {tab === "play" ? (
          <motion.div
            key="play"
            className="install-panel"
            id="install-panel-play"
            role="tabpanel"
            initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(12px, 0, 0)" }}
            animate={{ opacity: 1, transform: "translate3d(0, 0, 0)" }}
            exit={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(-6px, 0, 0)", transition: { duration: reduceMotion ? 0.12 : 0.13, ease: [0.23, 1, 0.32, 1] } }}
            transition={{ duration: reduceMotion ? 0.12 : 0.19, ease: [0.23, 1, 0.32, 1] }}
          >
            <div className="install-panel-heading">
              <span className="install-route-icon"><DownloadSimple size={24} weight="bold" /></span>
              <div>
                <span className="eyebrow">OFFICIAL BUILD</span>
                <h3>The shortest route to your first sentence.</h3>
                <p>A small one-time Play Store purchase supports development and includes lifetime app updates. Dictate itself has no subscription.</p>
              </div>
            </div>

            <ol className="install-steps">
              {playSteps.map((step, index) => {
                const Icon = step.icon;
                return (
                  <li key={step.label}>
                    <span className="install-step-number">0{index + 1}</span>
                    <Icon size={20} weight="bold" aria-hidden="true" />
                    <strong>{step.label}</strong>
                    <span>{step.copy}</span>
                  </li>
                );
              })}
            </ol>

            <div className="install-panel-footer">
              <a className="button button-accent" href="https://play.google.com/store/apps/details?id=net.devemperor.dictate" target="_blank" rel="noreferrer">
                Get it on Google Play
                <ArrowUpRight size={17} weight="bold" />
              </a>
              <p>Android 8.0+ · Regional pricing applies · Provider usage may cost extra</p>
            </div>
          </motion.div>
        ) : (
          <motion.div
            key="source"
            className="install-panel install-panel-source"
            id="install-panel-source"
            role="tabpanel"
            initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(-12px, 0, 0)" }}
            animate={{ opacity: 1, transform: "translate3d(0, 0, 0)" }}
            exit={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(6px, 0, 0)", transition: { duration: reduceMotion ? 0.12 : 0.13, ease: [0.23, 1, 0.32, 1] } }}
            transition={{ duration: reduceMotion ? 0.12 : 0.19, ease: [0.23, 1, 0.32, 1] }}
          >
            <div className="source-copy">
              <span className="install-route-icon"><Code size={24} weight="bold" /></span>
              <span className="eyebrow">APACHE 2.0</span>
              <h3>Inspect it. Fork it. Build it.</h3>
              <p>The complete source is public. Clone it, open it in Android Studio so the declared SDK and NDK can be installed, then build the debug app with the Gradle wrapper. GitHub does not currently provide a signed end-user APK.</p>
              <a href="https://github.com/DevEmperor/DictateKeyboard" target="_blank" rel="noreferrer">
                Read the repository
                <ArrowUpRight size={16} weight="bold" />
              </a>
            </div>

            <div className="source-terminal">
              <div className="terminal-topbar">
                <span><span /><span /><span /></span>
                <small>TERMINAL</small>
              </div>
              <div className="terminal-command">
                <span>$</span>
                <code>{cloneCommand}</code>
                <button type="button" onClick={copyCommand} aria-label="Copy source build commands">
                  {copyState === "copied" ? <Check size={18} weight="bold" /> : <ClipboardText size={18} weight="bold" />}
                </button>
              </div>
              <p className={`copy-feedback ${copyState}`} aria-live="polite">
                {copyState === "copied" && "Command copied."}
                {copyState === "error" && "Copy failed. Select the command manually."}
                {copyState === "idle" && "Windows: use .\\gradlew.bat :app:assembleDebug. Debug APKs are written below app/build/outputs/apk/."}
              </p>
              <a className="source-github" href="https://github.com/DevEmperor/DictateKeyboard" target="_blank" rel="noreferrer">
                <GithubLogo size={20} weight="fill" />
                DevEmperor / DictateKeyboard
              </a>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
