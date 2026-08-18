import { useEffect } from "react";

/**
 * Stop the decorative animations of sections nobody is looking at.
 *
 * Measured before this existed: 92 CSS animations running at once, of which only 43 were on screen — the
 * waveforms, scanning chips, marching arrows and blinking carets of the routes and feature blocks kept
 * animating the whole way down the page. That cost roughly 1.3 s of main-thread time per 5 s at idle, most
 * of it style recalculation, on a page that was doing nothing. On a laptop that is a warm fan for no reason.
 *
 * The mechanism is deliberately blunt: mark the section, let one CSS rule pause everything inside it. Doing
 * it per component would mean threading an in-view flag through a dozen visuals that have no other reason
 * to know about it, and each of those would then be a place to forget.
 *
 * What this does *not* touch: motion/react's scroll reveals (they only run on entry anyway), the marquees
 * (they stop themselves — see useMarqueeScroll), and anything a person is currently looking at. Paused
 * animations resume where they left off, which for a loop is indistinguishable from never having stopped.
 */
export function usePauseOffscreenAnimations() {
  useEffect(() => {
    if (typeof IntersectionObserver === "undefined") return undefined;

    const sections = Array.from(document.querySelectorAll("main > section"));
    if (sections.length === 0) return undefined;

    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          entry.target.toggleAttribute("data-offscreen", !entry.isIntersecting);
        }
      },
      // A third of a screen of slack, so a section is already running by the time it is scrolled to
      // and never animates into view from a standstill.
      { rootMargin: "35% 0px" },
    );

    for (const section of sections) observer.observe(section);
    return () => observer.disconnect();
  }, []);
}
