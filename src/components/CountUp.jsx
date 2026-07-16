import { useEffect, useMemo, useRef, useState } from "react";
import { useInView, useReducedMotion } from "motion/react";

/**
 * Counts a numeric stat up from zero when it scrolls into view. Accepts the display string (e.g. "4.8",
 * "240", "1K+", "€0", "40+") — it animates the numeric part and keeps any prefix/suffix. Reduced-motion
 * shows the final value immediately.
 *
 * The parse is memoised on `value`; feeding a fresh regex-match object into the effect deps (as an earlier
 * version did) restarted the animation every render, which showed up as a flickering 0.
 */
export function CountUp({ value, duration = 1200, className }) {
  const parsed = useMemo(() => {
    const m = String(value).match(/^(\D*)([\d.]+)(.*)$/);
    if (!m) return null;
    return {
      prefix: m[1],
      target: parseFloat(m[2]),
      suffix: m[3],
      decimals: m[2].includes(".") ? m[2].split(".")[1].length : 0,
    };
  }, [value]);

  const ref = useRef(null);
  const inView = useInView(ref, { once: true, margin: "-10% 0px" });
  const reduce = useReducedMotion();
  const [display, setDisplay] = useState(() => (reduce || !parsed ? (parsed ? parsed.target : 0) : 0));

  useEffect(() => {
    if (!parsed) return undefined;
    if (reduce) { setDisplay(parsed.target); return undefined; }
    if (!inView) return undefined;

    let raf = 0;
    let start = 0;
    const step = (now) => {
      if (!start) start = now;
      const t = Math.min((now - start) / duration, 1);
      const eased = 1 - Math.pow(1 - t, 3); // easeOutCubic
      setDisplay(parsed.target * eased);
      if (t < 1) raf = requestAnimationFrame(step);
      else setDisplay(parsed.target);
    };
    raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf);
  }, [inView, reduce, parsed, duration]);

  if (!parsed) return <span className={className} ref={ref}>{value}</span>;

  return (
    <span className={className} ref={ref}>
      {parsed.prefix}{display.toFixed(parsed.decimals)}{parsed.suffix}
    </span>
  );
}
