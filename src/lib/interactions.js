import { useEffect } from "react";

const reduceMotion = () =>
  typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

/**
 * Drive a duplicated marquee track (`[...items, ...items]`) by requestAnimationFrame so its speed can react
 * to scroll velocity. Replaces the element's CSS animation while active; on reduced-motion it bows out and
 * leaves the stylesheet in charge.
 *
 * Two things are deliberately restrained. The scroll coupling used to multiply velocity by 14, turning
 * 38 px/s into over 1100 px/s on a flick — that reads as a glitch rather than as a response, so the factor
 * is now small enough to be felt and not seen. And the loop only runs while the track is on screen: a
 * marquee nobody can see has no business holding a frame callback open, and there are two on the page.
 */
export function useMarqueeScroll(ref, basePxPerSec = 42) {
  useEffect(() => {
    const el = ref.current;
    if (!el || reduceMotion()) return undefined;

    el.style.animation = "none";
    el.style.willChange = "transform";

    let offset = 0;
    let last = performance.now();
    let velocity = 0;
    let lastScrollY = window.scrollY;
    let raf = 0;
    let visible = true;

    const onScroll = () => {
      const y = window.scrollY;
      velocity = Math.min(Math.abs(y - lastScrollY), 80);
      lastScrollY = y;
    };
    window.addEventListener("scroll", onScroll, { passive: true });

    const tick = (now) => {
      const dt = Math.min((now - last) / 1000, 0.05);
      last = now;
      const speed = basePxPerSec + velocity * 1.5; // scroll nudges the marquee; it must not whip
      offset -= speed * dt;
      const half = el.scrollWidth / 2;
      if (half > 0) {
        while (-offset >= half) offset += half;
      }
      el.style.transform = `translate3d(${offset}px, 0, 0)`;
      velocity *= 0.9; // decay back to base
      raf = requestAnimationFrame(tick);
    };
    // Restarting sets `last` first: otherwise the first frame after a pause carries the whole gap as
    // elapsed time and the track jumps.
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting === visible) return;
        visible = entry.isIntersecting;
        if (visible) {
          last = performance.now();
          raf = requestAnimationFrame(tick);
        } else {
          cancelAnimationFrame(raf);
        }
      },
      { rootMargin: "120px" },
    );
    observer.observe(el);

    raf = requestAnimationFrame(tick);

    return () => {
      observer.disconnect();
      cancelAnimationFrame(raf);
      window.removeEventListener("scroll", onScroll);
      el.style.animation = "";
      el.style.transform = "";
      el.style.willChange = "";
    };
  }, [ref, basePxPerSec]);
}

/**
 * Subtle mouse-parallax tilt for a hero element. Sets `--tilt-x` / `--tilt-y` (deg) on the target ref from
 * the pointer position over `zoneRef`; the CSS applies the rotation. No-op on touch / reduced-motion.
 */
export function useTilt(zoneRef, targetRef, max = 7) {
  useEffect(() => {
    const zone = zoneRef.current;
    const target = targetRef.current;
    if (!zone || !target || reduceMotion() || !window.matchMedia("(pointer: fine)").matches) return undefined;

    let raf = 0;
    const onMove = (e) => {
      const r = zone.getBoundingClientRect();
      const px = (e.clientX - r.left) / r.width - 0.5;
      const py = (e.clientY - r.top) / r.height - 0.5;
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => {
        target.style.setProperty("--tilt-y", `${(px * max).toFixed(2)}deg`);
        target.style.setProperty("--tilt-x", `${(-py * max).toFixed(2)}deg`);
      });
    };
    const onLeave = () => {
      cancelAnimationFrame(raf);
      target.style.setProperty("--tilt-x", "0deg");
      target.style.setProperty("--tilt-y", "0deg");
    };
    zone.addEventListener("pointermove", onMove, { passive: true });
    zone.addEventListener("pointerleave", onLeave);
    return () => {
      cancelAnimationFrame(raf);
      zone.removeEventListener("pointermove", onMove);
      zone.removeEventListener("pointerleave", onLeave);
    };
  }, [zoneRef, targetRef, max]);
}
