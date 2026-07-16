/*
 * Provider brand icons, hybrid:
 *   - Official logos from Simple Icons (real trademark paths, in each brand's official colour) wherever the
 *     provider exists there.
 *   - A hand-drawn fallback glyph for the providers Simple Icons does not carry (OpenAI, Groq, Soniox,
 *     AssemblyAI, Together AI, DeepInfra, xAI/Grok) plus the app's own On-device / Custom entries.
 * Each mark sits in a brand-tinted rounded tile and is always paired with the provider's name.
 *
 * Near-black brand logos (Anthropic, ElevenLabs, Ollama) are rendered with the theme-adaptive --ink token
 * instead of literal black, so they stay visible in dark mode — otherwise a #000 glyph disappears.
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

// Providers Simple Icons carries -> its official single-path icon.
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

// Official hexes that are effectively black -> use the theme-adaptive ink token so they read in both themes.
const ADAPTIVE_HEX = new Set(["191919", "000000", "0d0d0d", "111111"]);

// Fallback brand colours for providers without an official Simple Icons logo.
const FALLBACK_COLOR = {
  OpenAI: "#0f9d78",
  Groq: "#f55036",
  Soniox: "#3a70f0",
  AssemblyAI: "#4a5bd4",
  "On-device": "#30b7e6",
  Custom: "#68707b",
  "Together AI": "#1f7fd6",
  DeepInfra: "#3aa76d",
  "xAI / Grok": "#5b6470",
};

// Hand-drawn fallback glyphs (24x24 viewBox, fill currentColor).
const FALLBACK_GLYPH = {
  OpenAI: (
    <path d="M21.5 10.2a5.4 5.4 0 0 0-.46-4.42 5.4 5.4 0 0 0-5.82-2.6A5.4 5.4 0 0 0 6.9 4.2a5.4 5.4 0 0 0-3.6 2.62 5.4 5.4 0 0 0 .66 6.34 5.4 5.4 0 0 0 .46 4.42 5.4 5.4 0 0 0 5.82 2.6 5.4 5.4 0 0 0 8.32-1.02 5.4 5.4 0 0 0 3.6-2.62 5.4 5.4 0 0 0-.66-6.34ZM12 20.1a4 4 0 0 1-2.57-.93l3.6-2.08a.6.6 0 0 0 .3-.52v-5.08l1.52.88v4.2A4 4 0 0 1 12 20.1Zm-8.6-3.67a4 4 0 0 1-.48-2.7l3.6 2.08a.6.6 0 0 0 .6 0l4.4-2.54v1.76l-3.65 2.1a4 4 0 0 1-5.47-.7ZM3.9 7.3a4 4 0 0 1 2.1-1.76v4.28a.6.6 0 0 0 .3.52l4.4 2.54-1.52.88-3.65-2.1A4 4 0 0 1 3.9 7.3Zm12.7 2.95-4.4-2.54 1.52-.88 3.65 2.1a4 4 0 0 1-.6 7.22v-4.28a.6.6 0 0 0-.17-.52Zm1.5-2.28-3.6-2.08a.6.6 0 0 0-.6 0l-4.4 2.54V6.67l3.65-2.1a4 4 0 0 1 5.95 4.15ZM10.7 12.9 9.18 12V7.8a4 4 0 0 1 6.56-3.07l-3.6 2.08a.6.6 0 0 0-.3.52l-.14 5.04Z" />
  ),
  Groq: (
    <path d="M12 2.5A6.6 6.6 0 0 0 5.4 9.1v5.8A6.6 6.6 0 0 0 12 21.5a6.6 6.6 0 0 0 4.7-1.96l-2-2A3.77 3.77 0 0 1 12 18.7a3.8 3.8 0 0 1-3.8-3.8V9.1a3.8 3.8 0 1 1 7.6 0v3.03h-3.5v2.8h6.3V9.1A6.6 6.6 0 0 0 12 2.5Z" />
  ),
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
  "xAI / Grok": "Custom",
};

export function ProviderIcon({ name, size = 22, className = "" }) {
  const official = OFFICIAL[name];
  let color;
  let pathData = null;
  let glyph = null;

  if (official) {
    color = ADAPTIVE_HEX.has(official.hex.toLowerCase()) ? "var(--ink)" : `#${official.hex}`;
    pathData = official.path;
  } else {
    color = FALLBACK_COLOR[name] ?? FALLBACK_COLOR.Custom;
    const key = FALLBACK_ALIAS[name] ?? name;
    glyph = FALLBACK_GLYPH[key] ?? FALLBACK_GLYPH.Custom;
  }

  return (
    <span
      className={`provider-icon ${className}`}
      style={{ "--brand": color, width: size, height: size }}
      aria-hidden="true"
    >
      <svg viewBox="0 0 24 24" width={size * 0.6} height={size * 0.6} fill="currentColor" role="presentation">
        {pathData ? <path d={pathData} /> : glyph}
      </svg>
    </span>
  );
}
