import { useEffect } from "react";
import Lenis from "lenis";

/**
 * Momentum smooth-scrolling on the desktop wheel (via Lenis), plus smooth in-page anchor jumps. Scrolls the
 * window natively, so position:sticky, IntersectionObserver (useInView) and the scroll-reactive marquees
 * keep working. Disabled for reduced-motion, where native scrolling stays.
 */
export function SmoothScroll() {
  useEffect(() => {
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return undefined;

    const lenis = new Lenis({
      duration: 1.05,
      easing: (t) => 1 - Math.pow(1 - t, 3),
      smoothWheel: true,
      // leave touch devices on their native scrolling — this is for the PC wheel
      syncTouch: false,
    });

    let raf = 0;
    const loop = (time) => {
      lenis.raf(time);
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);

    // Route in-page anchor clicks through Lenis so they glide too (offset clears the fixed header).
    const onClick = (event) => {
      const link = event.target.closest?.('a[href^="#"]');
      if (!link) return;
      const hash = link.getAttribute("href");
      if (!hash || hash.length < 2) return;
      const target = document.querySelector(hash);
      if (!target) return;
      event.preventDefault();
      lenis.scrollTo(target, { offset: -76 });
    };
    document.addEventListener("click", onClick);

    return () => {
      cancelAnimationFrame(raf);
      document.removeEventListener("click", onClick);
      lenis.destroy();
    };
  }, []);

  return null;
}
