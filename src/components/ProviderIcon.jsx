/*
 * Provider brand glyphs. Simplified, theme-aware marks (single-path SVGs drawn in `currentColor`) shown in
 * a brand-tinted rounded tile. These are evocative geometric glyphs, not exact trademarks — each is always
 * paired with the provider's name so recognition never rests on the glyph alone.
 */

// name -> brand accent colour used for the tile tint + glyph colour. Mid-saturation values that stay legible
// on both the cream (light) and ink (dark) grounds.
export const PROVIDER_META = {
  OpenAI: { color: "#0f9d78" },
  Anthropic: { color: "#d97757" },
  "Google Gemini": { color: "#4285f4" },
  Gemini: { color: "#4285f4" },
  Groq: { color: "#f55036" },
  Mistral: { color: "#fa520f" },
  "Mistral AI": { color: "#fa520f" },
  Deepgram: { color: "#1bb877" },
  Soniox: { color: "#3a70f0" },
  ElevenLabs: { color: "#8a7be6" },
  AssemblyAI: { color: "#4a5bd4" },
  OpenRouter: { color: "#7b61e6" },
  Ollama: { color: "#9a7b63" },
  "On-device": { color: "#30b7e6" },
  Custom: { color: "#68707b" },
  "Together AI": { color: "#1f7fd6" },
  DeepInfra: { color: "#3aa76d" },
  "xAI / Grok": { color: "#5b6470" },
  DeepSeek: { color: "#4d6bfe" },
};

// Each glyph is authored in a 24x24 viewBox and inherits colour from `currentColor`.
const GLYPHS = {
  OpenAI: (
    <path d="M21.5 10.2a5.4 5.4 0 0 0-.46-4.42 5.4 5.4 0 0 0-5.82-2.6A5.4 5.4 0 0 0 6.9 4.2a5.4 5.4 0 0 0-3.6 2.62 5.4 5.4 0 0 0 .66 6.34 5.4 5.4 0 0 0 .46 4.42 5.4 5.4 0 0 0 5.82 2.6 5.4 5.4 0 0 0 8.32-1.02 5.4 5.4 0 0 0 3.6-2.62 5.4 5.4 0 0 0-.66-6.34ZM12 20.1a4 4 0 0 1-2.57-.93l3.6-2.08a.6.6 0 0 0 .3-.52v-5.08l1.52.88v4.2A4 4 0 0 1 12 20.1Zm-8.6-3.67a4 4 0 0 1-.48-2.7l3.6 2.08a.6.6 0 0 0 .6 0l4.4-2.54v1.76l-3.65 2.1a4 4 0 0 1-5.47-.7ZM3.9 7.3a4 4 0 0 1 2.1-1.76v4.28a.6.6 0 0 0 .3.52l4.4 2.54-1.52.88-3.65-2.1A4 4 0 0 1 3.9 7.3Zm12.7 2.95-4.4-2.54 1.52-.88 3.65 2.1a4 4 0 0 1-.6 7.22v-4.28a.6.6 0 0 0-.17-.52Zm1.5-2.28-3.6-2.08a.6.6 0 0 0-.6 0l-4.4 2.54V6.67l3.65-2.1a4 4 0 0 1 5.95 4.15ZM10.7 12.9 9.18 12V7.8a4 4 0 0 1 6.56-3.07l-3.6 2.08a.6.6 0 0 0-.3.52l-.14 5.04Z" />
  ),
  Anthropic: (
    <path d="M14.6 3.5h-3.1l6.3 17h3.4l-6.6-17Zm-6 0L2 20.5h3.46l1.34-3.6h6.6l1.34 3.6h3.46L11.6 3.5H8.6Zm-.66 10.1 2.1-5.66 2.1 5.66H7.94Z" />
  ),
  Gemini: (
    <path d="M12 2c.3 4.8 4.9 9.7 10 10-5.1.3-9.7 5.2-10 10-.3-4.8-4.9-9.7-10-10 5.1-.3 9.7-5.2 10-10Z" />
  ),
  Groq: (
    <path d="M12 2.5A6.6 6.6 0 0 0 5.4 9.1v5.8A6.6 6.6 0 0 0 12 21.5a6.6 6.6 0 0 0 4.7-1.96l-2-2A3.77 3.77 0 0 1 12 18.7a3.8 3.8 0 0 1-3.8-3.8V9.1a3.8 3.8 0 1 1 7.6 0v3.03h-3.5v2.8h6.3V9.1A6.6 6.6 0 0 0 12 2.5Z" />
  ),
  Mistral: (
    <path d="M3 4h3.6v3.6H3V4Zm14.4 0H21v3.6h-3.6V4ZM6.6 7.6h3.6v3.6H6.6V7.6Zm7.2 0h3.6v3.6h-3.6V7.6ZM3 11.2h14.4v3.6H3v-3.6ZM3 14.8h3.6v3.6H3v-3.6Zm14.4 0H21v3.6h-3.6v-3.6Z" />
  ),
  Deepgram: (
    <path d="M12 2.5a9.5 9.5 0 0 0-9.5 9.5c0 2 .62 3.86 1.68 5.4L2 21.5l4.2-2.1A9.5 9.5 0 1 0 12 2.5Zm0 3a6.5 6.5 0 0 1 0 13 6.4 6.4 0 0 1-3.4-.97l-.5-.3-1.2.6.6-1.24-.32-.5A6.5 6.5 0 0 1 12 5.5Zm0 3.1a3.4 3.4 0 1 0 0 6.8 3.4 3.4 0 0 0 0-6.8Z" />
  ),
  Soniox: (
    <path d="M3 10.5h2.2v3H3v-3Zm3.8-3h2.2v9H6.8v-9Zm3.8-3.5h2.2v16h-2.2V4Zm3.8 3.5h2.2v9h-2.2v-9Zm3.8 3h2.2v3H18v-3Z" />
  ),
  ElevenLabs: <path d="M7 4h3.2v16H7V4Zm6.8 0H17v16h-3.2V4Z" />,
  AssemblyAI: (
    <path d="M12 3 3 21h3.8l1.62-3.4h7.16L17.2 21H21L12 3Zm-2.2 11.6L12 9.9l2.2 4.7H9.8Z" />
  ),
  OpenRouter: (
    <path d="M4.5 12H9m10.5 0h-4m-6.5 0a2.5 2.5 0 0 1 2.5-2.5h1.4a3 3 0 0 0 2.4-1.2l.9-1.2a3 3 0 0 1 2.4-1.2H21m-12 6a2.5 2.5 0 0 0 2.5 2.5h1.4a3 3 0 0 1 2.4 1.2l.9 1.2a3 3 0 0 0 2.4 1.2H21M2.5 12a2 2 0 1 0 4 0 2 2 0 0 0-4 0Z" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
  ),
  Ollama: (
    <path d="M8.5 3c-1.2 0-2 1.1-2 2.4 0 .5.1 1 .3 1.4A4.6 4.6 0 0 0 4.5 11c0 .9.2 1.7.7 2.4-.5.7-.9 1.6-.9 2.7 0 .9.3 1.7.8 2.3H6c-.3-.5-.5-1.1-.5-1.8 0-1 .4-1.8 1-2.3l.6-.5-.6-.6a3 3 0 0 1-.8-2.1c0-1.4.9-2.6 2.2-3.1l1-.4-.7-.8a1.5 1.5 0 0 1-.4-1c0-.6.3-.9.6-.9.4 0 .8.4 1 1.1l.2.9.8-.5c.3-.2.7-.3 1.2-.3s.9.1 1.2.3l.8.5.2-.9c.2-.7.6-1.1 1-1.1.3 0 .6.3.6.9 0 .4-.1.7-.4 1l-.7.8 1 .4c1.3.5 2.2 1.7 2.2 3.1 0 .8-.3 1.5-.8 2.1l-.6.6.6.5c.6.5 1 1.3 1 2.3 0 .7-.2 1.3-.5 1.8h1.1c.5-.6.8-1.4.8-2.3 0-1.1-.4-2-.9-2.7.5-.7.7-1.5.7-2.4a4.6 4.6 0 0 0-2.3-4.2c.2-.4.3-.9.3-1.4C17.5 4.1 16.7 3 15.5 3c-1 0-1.8.7-2.2 1.7-.4-.1-.8-.2-1.3-.2s-.9.1-1.3.2C10 3.7 9.2 3 8.5 3Zm3.5 9c1.4 0 2.5.8 2.5 1.9 0 .5-.3 1-.7 1.3.3.3.5.7.5 1.1 0 1-1 1.7-2.3 1.7s-2.3-.7-2.3-1.7c0-.4.2-.8.5-1.1-.4-.3-.7-.8-.7-1.3 0-1.1 1.1-1.9 2.5-1.9Zm-2 1.6c-.4 0-.7.3-.7.6s.3.6.7.6.7-.3.7-.6-.3-.6-.7-.6Zm4 0c-.4 0-.7.3-.7.6s.3.6.7.6.7-.3.7-.6-.3-.6-.7-.6Z" />
  ),
  "On-device": (
    <path d="M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2Zm0 3v12h10V5H7Zm4 13.2h2v1.4h-2v-1.4ZM9.4 8.6a2.6 2.6 0 0 1 5.2 0v2.2a2.6 2.6 0 0 1-5.2 0V8.6Z" />
  ),
  Custom: (
    <path d="M4 4h16a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1Zm2.6 4.2 3 2.8-3 2.8 1.2 1.3 4.3-4.1-4.3-4.1-1.2 1.3ZM13 13.8h4v1.7h-4v-1.7Z" />
  ),
};

// aliases -> canonical glyph key
const GLYPH_ALIAS = {
  "Google Gemini": "Gemini",
  "Mistral AI": "Mistral",
  "Together AI": "Custom",
  DeepInfra: "Custom",
  "xAI / Grok": "Custom",
  DeepSeek: "Custom",
};

export function ProviderIcon({ name, size = 22, className = "" }) {
  const key = GLYPH_ALIAS[name] ?? name;
  const glyph = GLYPHS[key] ?? GLYPHS.Custom;
  const color = (PROVIDER_META[name] ?? PROVIDER_META.Custom).color;
  return (
    <span
      className={`provider-icon ${className}`}
      style={{ "--brand": color, width: size, height: size }}
      aria-hidden="true"
    >
      <svg viewBox="0 0 24 24" width={size * 0.62} height={size * 0.62} fill="currentColor" role="presentation">
        {glyph}
      </svg>
    </span>
  );
}
