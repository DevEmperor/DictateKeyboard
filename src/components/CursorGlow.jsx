import { useEffect, useRef } from "react";

/**
 * A soft accent glow that follows the cursor. Rendered once, fixed, pointer-events-none, with
 * mix-blend-mode: screen so it lights up the dark sections and stays near-invisible on the cream ones —
 * no per-section wiring needed. Desktop pointers only; disabled for reduced-motion.
 */
export function CursorGlow() {
  const ref = useRef(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return undefined;
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return undefined;
    if (!window.matchMedia("(pointer: fine)").matches) return undefined;

    let raf = 0;
    const move = (e) => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => {
        el.style.transform = `translate3d(${e.clientX}px, ${e.clientY}px, 0)`;
        el.style.opacity = "1";
      });
    };
    const hide = () => { el.style.opacity = "0"; };
    window.addEventListener("pointermove", move, { passive: true });
    document.addEventListener("pointerleave", hide);
    window.addEventListener("blur", hide);
    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("pointermove", move);
      document.removeEventListener("pointerleave", hide);
      window.removeEventListener("blur", hide);
    };
  }, []);

  return <div ref={ref} className="cursor-glow" aria-hidden="true" />;
}
