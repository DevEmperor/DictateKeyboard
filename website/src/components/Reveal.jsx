import { motion, useReducedMotion } from "motion/react";

export function Reveal({ children, className = "", delay = 0, as = "div" }) {
  const reduceMotion = useReducedMotion();
  const Component = motion[as] ?? motion.div;

  return (
    <Component
      className={className}
      initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, 22px, 0)" }}
      whileInView={{ opacity: 1, transform: "translate3d(0, 0, 0)" }}
      viewport={{ once: true, amount: 0.2 }}
      transition={{ duration: reduceMotion ? 0.18 : 0.7, delay, ease: [0.23, 1, 0.32, 1] }}
    >
      {children}
    </Component>
  );
}
