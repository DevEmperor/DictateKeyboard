import React, { useEffect } from "react";

/**
 * Public "under construction" page shown on the live domain while the real site is still being built.
 * Rendered when the committed MAINTENANCE switch in main.jsx is true (the `website` branch); the
 * `website-dev` branch turns it off to show the real site. Matches the site's editorial cream / ink / cyan
 * design tokens from styles.css.
 */
const PLAY_URL = "https://play.google.com/store/apps/details?id=net.devemperor.dictate";
const GITHUB_URL = "https://github.com/DevEmperor/DictateKeyboard";

export default function Maintenance() {
  const year = new Date().getFullYear();
  useEffect(() => {
    document.title = "Dictate Keyboard";
  }, []);
  return (
    <main className="mt-root">
      <style>{css}</style>
      <div className="mt-card">
        <div className="mt-brand">
          <img className="mt-logo" src="/media/dictate-icon.png" width="52" height="52" alt="Dictate Keyboard logo" />
          <span className="mt-wordmark">Dictate Keyboard</span>
        </div>
        <div className="mt-waves" aria-hidden="true">
          {Array.from({ length: 9 }).map((_, i) => (
            <span key={i} style={{ animationDelay: `${(i * 0.09).toFixed(2)}s` }} />
          ))}
        </div>
        <p className="mt-eyebrow">Website under construction</p>
        <h1 className="mt-title">Something good is on the way.</h1>
        <p className="mt-lead">
          Dictate Keyboard — the open-source voice keyboard for Android — is getting a proper home here.
          In the meantime, you can already install it or explore the code.
        </p>
        <div className="mt-actions">
          <a className="mt-btn mt-primary" href={PLAY_URL} rel="noopener">Get it on Google Play</a>
          <a className="mt-btn mt-ghost" href={GITHUB_URL} rel="noopener">View on GitHub</a>
        </div>
      </div>
      <footer className="mt-footer">© {year} Dictate Keyboard · dictatekeyboard.com</footer>
    </main>
  );
}

const css = `
.mt-root {
  min-height: 100dvh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2rem;
  padding: 2.5rem 1.25rem;
  text-align: center;
  background:
    radial-gradient(120% 80% at 50% -10%, var(--accent-soft) 0%, transparent 55%),
    var(--paper);
  color: var(--ink);
}
.mt-card { max-width: 46rem; }
.mt-brand {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.7rem;
  margin-bottom: 1.5rem;
}
.mt-logo {
  border-radius: 12px;
  box-shadow: 0 6px 20px rgba(13, 17, 23, 0.12);
}
.mt-wordmark {
  font-family: var(--font-display);
  font-size: 1.7rem;
  font-weight: 600;
  letter-spacing: -0.01em;
}
.mt-waves {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  height: 30px;
  margin-bottom: 1.75rem;
}
.mt-waves span {
  width: 5px;
  height: 10px;
  border-radius: 4px;
  background: var(--accent);
  animation: mt-bounce 1.1s var(--ease-in-out) infinite;
}
@keyframes mt-bounce {
  0%, 100% { height: 8px; opacity: 0.55; }
  50% { height: 32px; opacity: 1; }
}
.mt-eyebrow {
  text-transform: uppercase;
  letter-spacing: 0.18em;
  font-size: 0.72rem;
  font-weight: 700;
  color: var(--accent-dark);
  margin: 0 0 0.75rem;
}
.mt-title {
  font-family: var(--font-display);
  font-weight: 500;
  line-height: 1.08;
  font-size: clamp(2.1rem, 6vw, 3.4rem);
  letter-spacing: -0.02em;
  margin: 0 0 1rem;
  text-wrap: balance;
}
.mt-lead {
  font-size: clamp(1rem, 2.2vw, 1.15rem);
  line-height: 1.6;
  color: var(--muted);
  max-width: 34rem;
  margin: 0 auto 2rem;
}
.mt-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  justify-content: center;
}
.mt-btn {
  display: inline-flex;
  align-items: center;
  padding: 0.8rem 1.4rem;
  border-radius: var(--radius-small);
  font-weight: 600;
  font-size: 0.95rem;
  text-decoration: none;
  transition: transform 0.2s var(--ease-out), box-shadow 0.2s var(--ease-out), border-color 0.2s var(--ease-out);
}
.mt-btn:hover { transform: translateY(-2px); }
.mt-primary { background: var(--ink); color: var(--paper-bright); }
.mt-primary:hover { box-shadow: 0 10px 30px rgba(13, 17, 23, 0.22); }
.mt-ghost { background: transparent; color: var(--ink); border: 1px solid var(--line); }
.mt-ghost:hover { border-color: var(--ink); }
.mt-footer { font-size: 0.8rem; color: var(--muted-light); }
@media (prefers-reduced-motion: reduce) {
  .mt-waves span { animation: none; height: 18px; }
  .mt-btn:hover { transform: none; }
}
`;
