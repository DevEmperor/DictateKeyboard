import { useState } from "react";
import { ArrowUpRight, GithubLogo, List, X } from "@phosphor-icons/react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { MagneticLink } from "./MagneticLink";

const navItems = [
  ["Models", "#models"],
  ["Features", "#features"],
  ["Privacy", "#privacy"],
  ["Install", "#install"],
];

export function Header() {
  const [menuOpen, setMenuOpen] = useState(false);
  const reduceMotion = useReducedMotion();

  return (
    <header className="site-header">
      <a className="brand" href="#top" aria-label="Dictate home" onClick={() => setMenuOpen(false)}>
        <img src="/media/dictate-icon.png" width="36" height="36" alt="" />
        <span>Dictate</span>
      </a>

      <nav className="desktop-nav" aria-label="Main navigation">
        {navItems.map(([label, href]) => <a key={href} href={href}>{label}</a>)}
      </nav>

      <div className="header-actions">
        <a
          className="github-link"
          href="https://github.com/DevEmperor/DictateKeyboard"
          target="_blank"
          rel="noreferrer"
          aria-label="Dictate on GitHub"
        >
          <GithubLogo size={19} weight="fill" aria-hidden="true" />
        </a>
        <MagneticLink
          className="button button-small button-dark header-cta"
          href="https://play.google.com/store/apps/details?id=net.devemperor.dictate"
          target="_blank"
          rel="noreferrer"
        >
          Get Dictate
          <ArrowUpRight size={15} weight="bold" aria-hidden="true" />
        </MagneticLink>
        <button
          type="button"
          className="menu-toggle"
          onClick={() => setMenuOpen((value) => !value)}
          aria-expanded={menuOpen}
          aria-controls="mobile-menu"
          aria-label={menuOpen ? "Close navigation" : "Open navigation"}
        >
          {menuOpen ? <X size={20} weight="bold" /> : <List size={22} weight="bold" />}
        </button>
      </div>

      <AnimatePresence>
        {menuOpen && (
          <motion.nav
            id="mobile-menu"
            className="mobile-nav"
            aria-label="Mobile navigation"
            initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, -10px, 0) scale(0.98)" }}
            animate={{ opacity: 1, transform: "translate3d(0, 0, 0) scale(1)" }}
            exit={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, -6px, 0) scale(0.985)" }}
            transition={{ duration: reduceMotion ? 0.1 : 0.22, ease: [0.23, 1, 0.32, 1] }}
          >
            {navItems.map(([label, href], index) => (
              <a key={href} href={href} onClick={() => setMenuOpen(false)}>
                <span>0{index + 1}</span>
                {label}
              </a>
            ))}
            <a
              className="mobile-nav-cta"
              href="https://play.google.com/store/apps/details?id=net.devemperor.dictate"
              target="_blank"
              rel="noreferrer"
              onClick={() => setMenuOpen(false)}
            >
              Get Dictate on Google Play
              <ArrowUpRight size={18} weight="bold" />
            </a>
          </motion.nav>
        )}
      </AnimatePresence>
    </header>
  );
}
