import { useRef } from "react";
import { motion, useMotionTemplate, useMotionValue, useReducedMotion, useSpring } from "motion/react";

export function MagneticLink({ children, className = "", ...props }) {
  const ref = useRef(null);
  const boundsRef = useRef(null);
  const reduceMotion = useReducedMotion();
  const x = useMotionValue(0);
  const y = useMotionValue(0);
  const springX = useSpring(x, { stiffness: 180, damping: 18, mass: 0.35 });
  const springY = useSpring(y, { stiffness: 180, damping: 18, mass: 0.35 });
  const transform = useMotionTemplate`translate3d(${springX}px, ${springY}px, 0)`;

  function handlePointerMove(event) {
    if (reduceMotion || event.pointerType !== "mouse" || !ref.current) return;
    const bounds = boundsRef.current ?? ref.current.getBoundingClientRect();
    boundsRef.current = bounds;
    x.set((event.clientX - bounds.left - bounds.width / 2) * 0.12);
    y.set((event.clientY - bounds.top - bounds.height / 2) * 0.16);
  }

  function reset() {
    boundsRef.current = null;
    x.set(0);
    y.set(0);
  }

  return (
    <motion.span
      ref={ref}
      className="magnetic-link"
      style={{ transform: reduceMotion ? "none" : transform }}
      onPointerEnter={() => {
        if (!reduceMotion && ref.current) boundsRef.current = ref.current.getBoundingClientRect();
      }}
      onPointerMove={handlePointerMove}
      onPointerLeave={reset}
      onPointerCancel={reset}
    >
      <a className={className} {...props}>{children}</a>
    </motion.span>
  );
}
