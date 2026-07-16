import { useEffect, useRef, useState } from "react";
import { useInView, useReducedMotion } from "motion/react";

/**
 * Counts a numeric stat up from zero when it scrolls into view. Accepts the display string (e.g. "4.8",
 * "240", "1K+", "€0", "40+") — it animates the numeric part and keeps any prefix/suffix. Reduced-motion
 * shows the final value immediately.
 */
export function CountUp({ value, duration = 1200, className }) {
  const match = String(value).match(/^(\D*)([\d.]+)(.*)$/);
  const prefix = match ? match[1] : "";
  const target = match ? parseFloat(match[2]) : 0;
  const suffix = match ? match[3] : "";
  const decimals = match && match[2].includes(".") ? match[2].split(".")[1].length : 0;

  const ref = useRef(null);
  const inView = useInView(ref, { once: true, margin: "-12% 0px" });
  const reduce = useReducedMotion();
  const [display, setDisplay] = useState(reduce || !match ? target : 0);

  useEffect(() => {
    if (!match) return undefined;
    if (reduce) { setDisplay(target); return undefined; }
    if (!inView) return undefined;
    let raf = 0;
    let start = 0;
    const step = (now) => {
      if (!start) start = now;
      const t = Math.min((now - start) / duration, 1);
      const eased = 1 - Math.pow(1 - t, 3); // easeOutCubic
      setDisplay(target * eased);
      if (t < 1) raf = requestAnimationFrame(step);
      else setDisplay(target);
    };
    raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf);
  }, [inView, reduce, target, duration, match]);

  if (!match) return <span className={className} ref={ref}>{value}</span>;

  return (
    <span className={className} ref={ref}>
      {prefix}{display.toFixed(decimals)}{suffix}
    </span>
  );
}
