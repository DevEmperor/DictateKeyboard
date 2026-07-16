import { useEffect, useState } from "react";

/**
 * Brief (~1s) signature load animation: the logo and a waveform reveal over the ink ground, then the
 * overlay lifts away to the hero. Plays once per browser session (sessionStorage) and is skipped entirely
 * for reduced-motion.
 */
export function Intro() {
  const [done, setDone] = useState(() => {
    if (typeof window === "undefined") return true;
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return true;
    try { return sessionStorage.getItem("dk-intro-seen") === "1"; } catch { return false; }
  });

  useEffect(() => {
    if (done) return undefined;
    try { sessionStorage.setItem("dk-intro-seen", "1"); } catch { /* ignore */ }
    const timer = setTimeout(() => setDone(true), 1200);
    return () => clearTimeout(timer);
  }, [done]);

  if (done) return null;

  return (
    <div className="intro" aria-hidden="true">
      <div className="intro-inner">
        <img className="intro-logo" src="/media/dictate-icon.png" width="66" height="66" alt="" />
        <div className="intro-wave">
          {Array.from({ length: 9 }).map((_, i) => (
            <span key={i} style={{ animationDelay: `${(i * 0.07).toFixed(2)}s` }} />
          ))}
        </div>
      </div>
    </div>
  );
}
