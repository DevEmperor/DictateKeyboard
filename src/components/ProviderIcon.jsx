/*
 * Provider brand icons, hybrid across three sources (in priority order):
 *   1. Official Simple Icons logos (Anthropic, Google Gemini, Mistral, Deepgram, ElevenLabs, OpenRouter,
 *      Ollama, DeepSeek) — real trademark paths in each brand's official colour, 24x24 viewBox.
 *   2. Official logomarks fetched from the vendor's own assets for the ones Simple Icons lacks — OpenAI,
 *      Groq, xAI/Grok — see ../data/providerLogos.js (each keeps its native viewBox).
 *   3. A hand-drawn fallback glyph for what neither covers: Soniox and AssemblyAI (which publish only a
 *      horizontal wordmark, no square mark), the app's own On-device / Custom entries, and the long-tail
 *      LLM providers (Together AI, DeepInfra) that only ship wordmarks.
 * Each mark sits in a brand-tinted tile and is always paired with the provider's name. Near-black official
 * marks use the theme-adaptive --ink token so they stay visible in dark mode.
 */
import {
  siAnthropic,
  siDeepgram,
  siDeepseek,
  siElevenlabs,
  siGooglegemini,
  siMistralai,
  siOllama,
  siOpenrouter,
} from "simple-icons";
import { MANUAL_LOGOS } from "../data/providerLogos";

// 1. Simple Icons (24x24 viewBox, single path).
const OFFICIAL = {
  Anthropic: siAnthropic,
  "Google Gemini": siGooglegemini,
  Gemini: siGooglegemini,
  Mistral: siMistralai,
  "Mistral AI": siMistralai,
  Deepgram: siDeepgram,
  ElevenLabs: siElevenlabs,
  OpenRouter: siOpenrouter,
  Ollama: siOllama,
  DeepSeek: siDeepseek,
};

// Official hexes that are effectively black -> theme-adaptive ink token.
const ADAPTIVE_HEX = new Set(["191919", "000000", "0d0d0d", "111111"]);

// 3. Fallback brand colours + glyphs.
const FALLBACK_COLOR = {
  Soniox: "#3a70f0",
  AssemblyAI: "#4a5bd4",
  "On-device": "#30b7e6",
  Custom: "#68707b",
  "Together AI": "#1f7fd6",
  DeepInfra: "#3aa76d",
};

const FALLBACK_GLYPH = {
  Soniox: (
    <path d="M3 10.5h2.2v3H3v-3Zm3.8-3h2.2v9H6.8v-9Zm3.8-3.5h2.2v16h-2.2V4Zm3.8 3.5h2.2v9h-2.2v-9Zm3.8 3h2.2v3H18v-3Z" />
  ),
  AssemblyAI: (
    <path d="M12 3 3 21h3.8l1.62-3.4h7.16L17.2 21H21L12 3Zm-2.2 11.6L12 9.9l2.2 4.7H9.8Z" />
  ),
  "On-device": (
    <path d="M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2Zm0 3v12h10V5H7Zm4 13.2h2v1.4h-2v-1.4ZM9.4 8.6a2.6 2.6 0 0 1 5.2 0v2.2a2.6 2.6 0 0 1-5.2 0V8.6Z" />
  ),
  Custom: (
    <path d="M4 4h16a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1Zm2.6 4.2 3 2.8-3 2.8 1.2 1.3 4.3-4.1-4.3-4.1-1.2 1.3ZM13 13.8h4v1.7h-4v-1.7Z" />
  ),
};

const FALLBACK_ALIAS = {
  "Together AI": "Custom",
  DeepInfra: "Custom",
};

function resolve(name) {
  const si = OFFICIAL[name];
  if (si) {
    return {
      viewBox: "0 0 24 24",
      paths: [si.path],
      color: ADAPTIVE_HEX.has(si.hex.toLowerCase()) ? "var(--ink)" : `#${si.hex}`,
    };
  }
  const manual = MANUAL_LOGOS[name];
  if (manual) {
    return { viewBox: manual.viewBox, paths: manual.paths, color: manual.color };
  }
  const key = FALLBACK_ALIAS[name] ?? name;
  return {
    viewBox: "0 0 24 24",
    glyph: FALLBACK_GLYPH[key] ?? FALLBACK_GLYPH.Custom,
    color: FALLBACK_COLOR[name] ?? FALLBACK_COLOR.Custom,
  };
}

export function ProviderIcon({ name, size = 22, className = "" }) {
  const { viewBox, paths, glyph, color } = resolve(name);
  return (
    <span
      className={`provider-icon ${className}`}
      style={{ "--brand": color, width: size, height: size }}
      aria-hidden="true"
    >
      <svg viewBox={viewBox} width={size * 0.6} height={size * 0.6} fill="currentColor" role="presentation">
        {paths ? paths.map((d, i) => <path key={i} d={d} />) : glyph}
      </svg>
    </span>
  );
}
