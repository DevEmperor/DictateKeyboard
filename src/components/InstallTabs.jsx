import { ArrowUpRight, Check, DownloadSimple, Key, Microphone, ShieldCheck } from "@phosphor-icons/react";
import { GooglePlayBadge } from "./GooglePlayBadge";

const PLAY_URL = "https://play.google.com/store/apps/details?id=net.devemperor.dictate";

const steps = [
  { icon: DownloadSimple, label: "Install", copy: "Get the app from Google Play — one tap, automatic updates." },
  { icon: Check, label: "Enable", copy: "Follow the quick setup to switch on Dictate Keyboard." },
  { icon: Key, label: "Choose", copy: "Download an offline model, or connect the provider you prefer." },
  { icon: Microphone, label: "Speak", copy: "Open any text field, tap the mic, and start talking." },
];

export function InstallTabs() {
  return (
    <div className="install-box">
      <div className="install-panel">
        <div className="install-panel-heading">
          <div className="install-heading-text">
            <span className="install-route-icon"><DownloadSimple size={24} weight="bold" /></span>
            <div>
              <span className="eyebrow">FROM GOOGLE PLAY</span>
              <h3>The shortest route to your first sentence.</h3>
              <p>
                Install Dictate Keyboard from Google Play and you are set up in under a minute, with automatic
                updates. No subscription: offline models use no cloud API at all, and any provider you connect
                bills its own usage directly to you.
              </p>
            </div>
          </div>

          <div className="install-cta">
            <span className="install-cta-kicker">Ready in a minute</span>
            <GooglePlayBadge href={PLAY_URL} target="_blank" rel="noreferrer" className="install-cta-badge" />
            <p className="install-cta-fine">Android 8.0+ · Free download · No subscription</p>
          </div>
        </div>

        <ol className="install-steps">
          {steps.map((step, index) => {
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

        <p className="install-oss-note">
          <ShieldCheck size={15} weight="bold" aria-hidden="true" />
          Fully open source under Apache 2.0 —{" "}
          <a href="https://github.com/DevEmperor/DictateKeyboard" target="_blank" rel="noreferrer">
            browse the code on GitHub
            <ArrowUpRight size={13} weight="bold" />
          </a>
        </p>
      </div>
    </div>
  );
}
