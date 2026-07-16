import { useMemo, useState } from "react";
import {
  ArrowUpRight,
  Cloud,
  HardDrives,
  Lightning,
  Microphone,
  Robot,
  SlidersHorizontal,
} from "@phosphor-icons/react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { ProviderIcon } from "./ProviderIcon";

const sttModels = [
  { provider: "On-device", model: "Whisper Tiny", route: "offline", cost: "$0", unit: "API usage", quality: "Fastest local", languages: "99 languages", detail: "~99 MB · lower local accuracy" },
  { provider: "On-device", model: "Whisper Base", route: "offline", cost: "$0", unit: "API usage", quality: "Balanced", languages: "99 languages", detail: "~153 MB · recommended Whisper balance" },
  { provider: "On-device", model: "Whisper Small", route: "offline", cost: "$0", unit: "API usage", quality: "Best local Whisper", languages: "99 languages", detail: "~358 MB · slower, more accurate" },
  { provider: "On-device", model: "Whisper Tiny.en", route: "offline", cost: "$0", unit: "API usage", quality: "Fastest local", languages: "English", detail: "~99 MB · English-optimized" },
  { provider: "On-device", model: "Whisper Base.en", route: "offline", cost: "$0", unit: "API usage", quality: "Balanced", languages: "English", detail: "~153 MB · recommended English balance" },
  { provider: "On-device", model: "Whisper Small.en", route: "offline", cost: "$0", unit: "API usage", quality: "Best local Whisper", languages: "English", detail: "~358 MB · highest local Whisper accuracy" },
  { provider: "On-device", model: "Parakeet TDT 0.6B v3", route: "offline", cost: "$0", unit: "API usage", quality: "High local", languages: "25 European languages", detail: "~670 MB · fast multilingual specialist" },
  { provider: "On-device", model: "Parakeet German / primeline", route: "offline", cost: "$0", unit: "API usage", quality: "German specialist", languages: "German", detail: "~670 MB · tuned for German speech" },

  { provider: "OpenAI", model: "gpt-4o-mini-transcribe", route: "async", cost: "$3.00", unit: "/ 1k min", wer: 4.47, languages: "Multilingual", detail: "Default OpenAI batch model" },
  { provider: "OpenAI", model: "gpt-4o-transcribe", route: "async", cost: "$6.00", unit: "/ 1k min", wer: 3.96, languages: "Multilingual", detail: "Lower AA-WER than the mini model" },
  { provider: "OpenAI", model: "whisper-1", route: "async", cost: "$6.00", unit: "/ 1k min", wer: 4.06, languages: "Multilingual", detail: "Whisper Large v2 API model" },
  { provider: "OpenAI", model: "gpt-realtime-whisper", route: "realtime", cost: "$17.00", unit: "/ 1k min", wer: 4.89, latency: "0.688s final", languages: "Multilingual", detail: "Default OpenAI realtime route" },
  { provider: "OpenAI", model: "gpt-4o-mini-transcribe", route: "realtime", cost: "$3.00", unit: "/ 1k min", quality: "4.47% batch WER†", languages: "Multilingual", detail: "Realtime API option · final transcript only" },
  { provider: "OpenAI", model: "gpt-4o-transcribe", route: "realtime", cost: "$6.00", unit: "/ 1k min", quality: "3.96% batch WER†", languages: "Multilingual", detail: "Realtime API option · final transcript only" },

  { provider: "Groq", model: "whisper-large-v3-turbo", route: "async", cost: "$0.67", unit: "/ 1k min", wer: 4.62, languages: "Multilingual", detail: "Lowest tested batch cost in AA snapshot" },
  { provider: "Groq", model: "whisper-large-v3", route: "async", cost: "$1.85", unit: "/ 1k min", quality: "Not AA-tested", languages: "Multilingual", detail: "Dynamic Groq catalog" },
  { provider: "Groq", model: "distil-whisper-large-v3-en", route: "async", cost: "Legacy", unit: "provider-retired", quality: "Not current", languages: "English", detail: "ID remains in Dictate Keyboard; retired by provider" },

  { provider: "OpenRouter", model: "Whisper Large v3 Turbo", route: "async", cost: "$0.67", unit: "/ 1k min", quality: "4.62% family WER*", languages: "Multilingual", detail: "Live catalog · exact host not AA-tested" },
  { provider: "OpenRouter", model: "NVIDIA Parakeet TDT v3", route: "async", cost: "$1.50", unit: "/ 1k min", quality: "4.55% hosted proxy*", languages: "25 European languages", detail: "Live catalog · not an on-device result" },
  { provider: "OpenRouter", model: "Whisper Large v3", route: "async", cost: "$1.50", unit: "/ 1k min", quality: "Not AA-tested", languages: "Multilingual", detail: "Live catalog" },
  { provider: "OpenRouter", model: "Qwen3 ASR Flash", route: "async", cost: "$2.10", unit: "/ 1k min", quality: "Not AA-tested", languages: "Model-dependent", detail: "Live catalog" },
  { provider: "OpenRouter", model: "Voxtral Mini Transcribe", route: "async", cost: "$3.00", unit: "/ 1k min", quality: "3.59% family WER*", languages: "13 languages", detail: "Live catalog · model-family evidence" },
  { provider: "OpenRouter", model: "GPT-4o Mini Transcribe", route: "async", cost: "$3.00", unit: "/ 1k min", quality: "4.47% family WER*", languages: "Multilingual", detail: "Live catalog · model-family evidence" },
  { provider: "OpenRouter", model: "MAI Transcribe 1.5", route: "async", cost: "$6.00", unit: "/ 1k min", quality: "2.38% family WER*", languages: "Model-dependent", detail: "Curated baseline · benchmark is Azure-hosted" },
  { provider: "OpenRouter", model: "Whisper-1", route: "async", cost: "$6.00", unit: "/ 1k min", quality: "4.06% family WER*", languages: "Multilingual", detail: "Live catalog · model-family evidence" },
  { provider: "OpenRouter", model: "GPT-4o Transcribe", route: "async", cost: "$6.00", unit: "/ 1k min", quality: "3.96% family WER*", languages: "Multilingual", detail: "Curated baseline · model-family evidence" },
  { provider: "OpenRouter", model: "Google Chirp 3", route: "async", cost: "$16.00", unit: "/ 1k min", quality: "Not AA-tested", languages: "Model-dependent", detail: "Live catalog · current snapshot" },

  { provider: "Google Gemini", model: "gemini-2.5-pro", route: "async", cost: "$11.39", unit: "/ 1k min observed", wer: 2.86, languages: "Model-dependent", detail: "Audio-capable multimodal model" },
  { provider: "Google Gemini", model: "gemini-2.5-flash", route: "async", cost: "$6.66", unit: "/ 1k min observed", wer: 5.14, languages: "Model-dependent", detail: "Faster multimodal route" },
  { provider: "Google Gemini", model: "gemini-2.5-flash-lite", route: "async", cost: "$6.56", unit: "/ 1k min observed", wer: 5.17, languages: "Model-dependent", detail: "Lightweight multimodal route" },
  { provider: "Google Gemini", model: "gemini-3.5-flash", route: "async", cost: "Live", unit: "token pricing", quality: "Not AA-tested", languages: "Model-dependent", detail: "Dynamic Gemini catalog" },

  { provider: "Mistral", model: "voxtral-mini-latest", route: "async", cost: "~$3.00", unit: "/ 1k min", quality: "3.59% alias proxy*", languages: "13 languages", detail: "Moving alias · current Voxtral Mini family benchmark" },
  { provider: "Soniox", model: "stt-async-v5", route: "async", cost: "$1.66", unit: "/ 1k min", wer: 3.81, languages: "60+ languages", detail: "Mixed-language capable" },
  { provider: "Soniox", model: "stt-rt-v5", route: "realtime", cost: "$2.00", unit: "/ 1k min", wer: 4.5, latency: "0.054s final", languages: "60+ languages", detail: "Fastest exact active Dictate Keyboard route in AA snapshot" },
  { provider: "ElevenLabs", model: "scribe_v2", route: "async", cost: "$3.67", unit: "/ 1k min", wer: 2.18, languages: "90+ languages", detail: "Lowest batch AA-WER in this buffet" },
  { provider: "ElevenLabs", model: "scribe_v2_realtime", route: "realtime", cost: "$6.50", unit: "/ 1k min", wer: 3.59, latency: "0.141s final", languages: "90+ languages", detail: "Lowest realtime AA-WER here" },
  { provider: "Deepgram", model: "nova-3", route: "async", cost: "~$4.80", unit: "/ 1k min", wer: 5.18, languages: "Live language catalog", detail: "Current posted promo pricing" },
  { provider: "Deepgram", model: "nova-2", route: "async", cost: "~$5.83", unit: "/ 1k min", wer: 5.31, languages: "Additional coverage", detail: "Fills model/language gaps" },
  { provider: "Deepgram", model: "nova-3", route: "realtime", cost: "~$4.80", unit: "/ 1k min", wer: 6.59, latency: "0.066s final", languages: "Live language catalog", detail: "Realtime route · very low measured final latency" },
  { provider: "Deepgram", model: "nova-2", route: "realtime", cost: "~$5.83", unit: "/ 1k min", quality: "Not AA-tested", languages: "Additional coverage", detail: "Enabled realtime route" },
  { provider: "AssemblyAI", model: "universal-3-pro", route: "async", cost: "$3.50", unit: "/ 1k min", wer: 3.12, languages: "6 languages", detail: "Accuracy-focused batch default" },
  { provider: "AssemblyAI", model: "universal-2", route: "async", cost: "~$2.50", unit: "/ 1k min", quality: "3.82% alias proxy*", languages: "99 languages", detail: "Wider language coverage" },
  { provider: "AssemblyAI", model: "universal-streaming", route: "realtime", cost: "~$2.50", unit: "/ 1k min", quality: "Not AA-tested", languages: "Endpoint-dependent", detail: "Dictate Keyboard realtime default" },
  { provider: "Custom", model: "OpenAI-compatible STT endpoint", route: "async", cost: "Your server", unit: "your model", quality: "Model-dependent", languages: "Model-dependent", detail: "Dynamic model ID or manual entry" },
];

const llmRoutes = [
  { provider: "OpenAI", price: "$0.20–$5 in / $1.25–$30 out per 1M tokens", tier: "Fast to frontier", models: [
    { name: "GPT-5.4 nano", price: "$0.20 in / $1.25 out" },
    { name: "GPT-5.4 mini", price: "$0.75 in / $4.50 out" },
    { name: "GPT-5.6 Luna", price: "$1.00 in / $6.00 out" },
    { name: "GPT-5.6 Terra", price: "$2.50 in / $15 out" },
    { name: "GPT-5.6 Sol", price: "$5.00 in / $30 out" },
  ] },
  { provider: "Anthropic", price: "$1–$5 in / $5–$25 out per 1M tokens", tier: "Strongest writing", models: [
    { name: "Claude Haiku 4.5", price: "$1 in / $5 out" },
    { name: "Claude Sonnet 5", price: "$2 / $10 (intro) · then $3 / $15" },
    { name: "Claude Opus 4.8", price: "$5 in / $25 out" },
  ] },
  { provider: "Google Gemini", price: "Free tier · $0.10–$1.25 in / $0.40–$10 out per 1M", tier: "Fast to frontier", models: [
    { name: "Gemini 2.5 Flash-Lite", price: "$0.10 in / $0.40 out" },
    { name: "Gemini 2.5 Flash", price: "$0.30 in / $2.50 out" },
    { name: "Gemini 2.5 Pro", price: "$1.25 in / $10 out" },
  ] },
  { provider: "Groq", price: "$0.075–$1 in / $0.30–$3 out per 1M tokens", tier: "Fastest inference", models: [
    { name: "GPT-OSS 20B", price: "$0.075 in / $0.30 out" },
    { name: "Llama 4 Scout", price: "$0.11 in / $0.34 out" },
    { name: "Llama 3.3 70B", price: "$0.59 in / $0.79 out" },
    { name: "Kimi K2", price: "$1.00 in / $3.00 out" },
  ] },
  { provider: "Mistral AI", price: "$0.15–$1.50 in / $0.60–$7.50 out per 1M tokens", tier: "Fast European models", models: [
    { name: "Mistral Small 4", price: "$0.15 in / $0.60 out" },
    { name: "Mistral Large 3", price: "$0.50 in / $1.50 out" },
    { name: "Mistral Medium 3.5", price: "$1.50 in / $7.50 out" },
  ] },
  { provider: "OpenRouter", price: "Pass-through · 400+ live models", tier: "Broadest choice", models: [
    { name: "any hosted model", price: "Provider list price" },
    { name: "25+ :free routes", price: "$0 model cost" },
    { name: "manual model ID", price: "Route pricing" },
  ] },
  { provider: "Ollama", price: "$0 API cost · your own compute", tier: "Self-hosted, local", models: [
    { name: "Llama · Qwen · Gemma · Mistral", price: "$0 API cost" },
    { name: "any model on your server", price: "Your hardware" },
  ] },
  { provider: "Together AI", price: "Open-model catalog · live pricing", tier: "Open-model range", models: [{ name: "live model catalog", price: "Live pricing" }, { name: "manual model ID", price: "Model-dependent" }] },
  { provider: "DeepInfra", price: "Open-model catalog · live pricing", tier: "Open-model range", models: [{ name: "live model catalog", price: "Live pricing" }, { name: "manual model ID", price: "Model-dependent" }] },
  { provider: "xAI / Grok", price: "Provider-native · live pricing", tier: "Grok models", models: [{ name: "live model catalog", price: "Live pricing" }, { name: "manual model ID", price: "Model-dependent" }] },
  { provider: "DeepSeek", price: "Value reasoning · live pricing", tier: "Value reasoning", models: [{ name: "live model catalog", price: "Live pricing" }, { name: "manual model ID", price: "Model-dependent" }] },
  { provider: "Custom", price: "Your own server pricing", tier: "OpenAI-compatible", models: [{ name: "any compatible chat-completions model", price: "Server-defined" }] },
];

const filters = [
  { id: "all", label: "All routes", icon: SlidersHorizontal },
  { id: "offline", label: "Free offline", icon: HardDrives },
  { id: "async", label: "Cloud async", icon: Cloud },
  { id: "realtime", label: "Realtime", icon: Lightning },
];

const sttRouteOrder = ["offline", "async", "realtime"];

const sttRouteMeta = {
  offline: {
    kicker: "FREE OFFLINE",
    title: "Private, capable, $0 API usage",
    copy: "Three useful local starting points. Download once, then dictate on-device.",
  },
  async: {
    kicker: "CLOUD ASYNC",
    title: "The API-cost-to-accuracy standouts",
    copy: "A low-cost pick, a balanced pick, and the strongest measured batch result here.",
  },
  realtime: {
    kicker: "REALTIME",
    title: "Fast feedback while you speak",
    copy: "Three live routes selected for cost, final latency, or measured word error rate.",
  },
};

const sttHighlightIds = {
  offline: [
    "On-device|Whisper Base|offline",
    "On-device|Whisper Small|offline",
    "On-device|Parakeet TDT 0.6B v3|offline",
  ],
  async: [
    "Groq|whisper-large-v3-turbo|async",
    "OpenAI|gpt-4o-transcribe|async",
    "Soniox|stt-async-v5|async",
    "ElevenLabs|scribe_v2|async",
    "Google Gemini|gemini-2.5-pro|async",
  ],
  realtime: [
    "Soniox|stt-rt-v5|realtime",
    "ElevenLabs|scribe_v2_realtime|realtime",
    "Deepgram|nova-3|realtime",
    "OpenAI|gpt-realtime-whisper|realtime",
  ],
};

const llmHighlightProviders = ["OpenAI", "Anthropic", "Google Gemini", "Groq", "Mistral AI", "OpenRouter", "Ollama"];

function sttModelId(model) {
  return `${model.provider}|${model.model}|${model.route}`;
}

function RouteBadge({ route }) {
  return <span className={`route-badge route-${route}`}>{route === "async" ? "Cloud async" : route === "realtime" ? "Realtime" : "Offline"}</span>;
}

function AccuracyCell({ model }) {
  if (model.wer) {
    return <div className="buffet-metric"><strong>{model.wer.toFixed(2)}% WER</strong><span>{model.latency ?? "AA English batch"}</span></div>;
  }
  return <div className="buffet-metric"><strong>{model.quality}</strong><span>Independent exact WER unavailable</span></div>;
}

export function ModelBuffet() {
  const [tab, setTab] = useState("stt");
  const [filter, setFilter] = useState("all");
  const [expanded, setExpanded] = useState(false);
  const [llmExpanded, setLlmExpanded] = useState(false);
  const reduceMotion = useReducedMotion();

  const filteredModels = useMemo(() => {
    if (filter === "all") return sttModels;
    return sttModels.filter((model) => model.route === filter);
  }, [filter]);

  const visibleGroups = useMemo(() => {
    const routes = filter === "all" ? sttRouteOrder : [filter];

    return routes.map((route) => {
      const routeModels = sttModels.filter((model) => model.route === route);
      const highlightOrder = sttHighlightIds[route];
      const highlights = highlightOrder
        .map((id) => routeModels.find((model) => sttModelId(model) === id))
        .filter(Boolean);

      return {
        route,
        models: expanded ? routeModels : highlights,
        total: routeModels.length,
      };
    });
  }, [expanded, filter]);

  const visibleLlmRoutes = llmExpanded
    ? llmRoutes
    : llmHighlightProviders.map((provider) => llmRoutes.find((route) => route.provider === provider)).filter(Boolean);

  function changeFilter(nextFilter) {
    setFilter(nextFilter);
    setExpanded(false);
  }

  return (
    <div className="buffet-shell">
      <div className="buffet-topbar">
        <div className="buffet-tabs" role="tablist" aria-label="Model buffet type">
          {[
            ["stt", "Speech-to-text", Microphone],
            ["llm", "Rewrite LLMs", Robot],
          ].map(([id, label, Icon]) => (
            <button key={id} type="button" role="tab" aria-selected={tab === id} aria-controls={`buffet-panel-${id}`} onClick={() => setTab(id)} className={tab === id ? "is-active" : ""}>
              {tab === id && <motion.span className="buffet-tab-bg" layoutId={reduceMotion ? undefined : "buffet-tab"} transition={{ type: "spring", bounce: 0, duration: 0.24 }} />}
              <Icon size={17} weight="bold" aria-hidden="true" />
              <span>{label}</span>
            </button>
          ))}
        </div>
        <div className="buffet-counts">
          <span><strong>8</strong> free offline STT models</span>
          <span><strong>5</strong> realtime providers</span>
          <span><strong>12</strong> rewrite providers</span>
        </div>
      </div>

      <AnimatePresence mode="popLayout" initial={false}>
        {tab === "stt" ? (
          <motion.div id="buffet-panel-stt" role="tabpanel" key="stt" className="buffet-panel" initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, 4px, 0)" }} animate={{ opacity: 1, transform: "translate3d(0, 0, 0)" }} exit={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, -3px, 0)", transition: { duration: reduceMotion ? 0.12 : 0.13, ease: [0.23, 1, 0.32, 1] } }} transition={{ duration: reduceMotion ? 0.12 : 0.18, ease: [0.23, 1, 0.32, 1] }}>
            <div className="buffet-filters" role="group" aria-label="Filter speech-to-text models">
              {filters.map((item) => {
                const Icon = item.icon;
                return <button key={item.id} type="button" aria-pressed={filter === item.id} className={filter === item.id ? "is-active" : ""} onClick={() => changeFilter(item.id)}><Icon size={15} weight="bold" />{item.label}</button>;
              })}
            </div>

            <div className="buffet-table" role="table" aria-label="Dictate Keyboard speech-to-text model options">
              <div className="buffet-table-head" role="row">
                <span role="columnheader">Provider / model</span>
                <span role="columnheader">Route / language</span>
                <span role="columnheader">Accuracy signal</span>
                <span role="columnheader">Provider API cost</span>
              </div>
              {visibleGroups.map((group) => {
                const meta = sttRouteMeta[group.route];
                return (
                  <div className="buffet-group" role="rowgroup" key={group.route}>
                    <div className={`buffet-category-row buffet-category-${group.route}`} role="row">
                      <div role="cell">
                        <div><span>{meta.kicker}</span><strong>{expanded ? `${group.total} mapped options` : meta.title}</strong></div>
                        <p>{meta.copy}</p>
                      </div>
                    </div>
                    <AnimatePresence initial={false} mode="popLayout">
                      {group.models.map((model) => (
                        <motion.div
                          className="buffet-row"
                          role="row"
                          key={sttModelId(model)}
                          initial={{ opacity: 0 }}
                          animate={{ opacity: 1 }}
                          exit={{ opacity: 0 }}
                          transition={{ duration: reduceMotion ? 0.1 : 0.14, ease: [0.23, 1, 0.32, 1] }}
                        >
                          <div className="buffet-model" role="cell">
                            <ProviderIcon name={model.provider} size={30} className="buffet-model-icon" />
                            <div className="buffet-model-copy"><span>{model.provider}</span><strong>{model.model}</strong><small>{model.detail}</small></div>
                          </div>
                          <div className="buffet-route" role="cell"><RouteBadge route={model.route} /><span>{model.languages}</span></div>
                          <div role="cell"><AccuracyCell model={model} /></div>
                          <div className={`buffet-price ${model.cost === "$0" ? "is-free" : ""}`} role="cell"><strong>{model.cost}</strong><span>{model.unit}</span></div>
                        </motion.div>
                      ))}
                    </AnimatePresence>
                  </div>
                );
              })}
            </div>

            <button type="button" className="buffet-expand" aria-expanded={expanded} onClick={() => setExpanded((value) => !value)}>
              {expanded
                ? "Back to the highlighted models per category"
                : filter === "all"
                  ? `Explore all ${filteredModels.length} mapped STT options`
                  : `Show all ${filteredModels.length} ${filters.find((item) => item.id === filter)?.label.toLowerCase()} options`}
            </button>

            <div className="buffet-notes">
              <p><strong>Accuracy:</strong> AA-WER is Artificial Analysis’ independent English benchmark; lower is better. It is an edit rate, not a conventional “accuracy %,” and does not score every local or dynamic route. *Family or alias evidence is not an exact route result. †Batch WER does not measure streaming behavior.</p>
              <p><strong>Third-party API pricing:</strong> Dictate Keyboard adds no provider markup. These USD provider list-price snapshots are normalized per 1,000 audio minutes as of July 2026; token-priced Gemini rows use an observed effective cost. Rows cover every fixed Dictate Keyboard ID plus OpenRouter’s current STT snapshot. Observed costs, live catalogs, rates, free tiers, and promotions can differ or change.</p>
              <div><a href="https://artificialanalysis.ai/speech-to-text/non-streaming" target="_blank" rel="noreferrer">Async benchmark <ArrowUpRight size={13} weight="bold" /></a><a href="https://artificialanalysis.ai/speech-to-text/streaming" target="_blank" rel="noreferrer">Realtime benchmark <ArrowUpRight size={13} weight="bold" /></a><a href="https://artificialanalysis.ai/speech-to-text/methodology" target="_blank" rel="noreferrer">Methodology <ArrowUpRight size={13} weight="bold" /></a><a href="https://deepgram.com/pricing" target="_blank" rel="noreferrer">Deepgram billing <ArrowUpRight size={13} weight="bold" /></a></div>
            </div>
          </motion.div>
        ) : (
          <motion.div id="buffet-panel-llm" role="tabpanel" key="llm" className="buffet-panel buffet-llm" initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, 4px, 0)" }} animate={{ opacity: 1, transform: "translate3d(0, 0, 0)" }} exit={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, -3px, 0)", transition: { duration: reduceMotion ? 0.12 : 0.13, ease: [0.23, 1, 0.32, 1] } }} transition={{ duration: reduceMotion ? 0.12 : 0.18, ease: [0.23, 1, 0.32, 1] }}>
            <div className="buffet-llm-intro">
              <div><span className="eyebrow">MIX AND MATCH</span><h3>Your transcription model and writing model are separate choices.</h3></div>
              <p>The major writing models are here — OpenAI, Anthropic, Gemini, Groq, Mistral, plus OpenRouter’s 400+ routes and your own local Ollama. Every provider can also load its live catalog and accept manual model IDs. Rewrite quality is task-dependent, so STT-style WER does not apply.</p>
            </div>
            <div className={`llm-grid ${llmExpanded ? "" : "is-shortlist"}`}>
              {visibleLlmRoutes.map((route, index) => (
                <motion.article
                  className="llm-route"
                  key={route.provider}
                  initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, 12px, 0)" }}
                  animate={{ opacity: 1, transform: "translate3d(0, 0, 0)" }}
                  transition={{ delay: reduceMotion ? 0 : Math.min(index * 0.012, 0.09), duration: reduceMotion ? 0.12 : 0.22, ease: [0.23, 1, 0.32, 1] }}
                >
                  <div className="llm-route-head"><ProviderIcon name={route.provider} size={30} className="llm-route-icon" /><div className="llm-route-head-copy"><strong>{route.provider}</strong><small>{route.tier}</small></div></div>
                  <div className="llm-models">{route.models.map((model) => <code key={model.name}><span>{model.name}</span><small>{model.price}</small></code>)}</div>
                  <div className="llm-price"><span>Provider API cost</span><strong>{route.price}</strong></div>
                </motion.article>
              ))}
            </div>
            <button type="button" className="buffet-expand" aria-expanded={llmExpanded} onClick={() => setLlmExpanded((value) => !value)}>
              {llmExpanded ? "Back to the major rewrite providers" : `Show all ${llmRoutes.length} rewrite providers`}
            </button>
            <div className="buffet-notes">
              <p><strong>Dynamic by design:</strong> Except for the curated starting IDs shown above, each provider can load its current model catalog and accepts manual compatible IDs. Dictate Keyboard adds no subscription or provider markup; the USD public API-price snapshots shown here belong to external providers and are dated July 2026. Prices are per 1M input/output tokens unless marked otherwise.</p>
              <p><strong>Local LLM caveat:</strong> Ollama points to your own server or LAN machine; it is not an on-phone model. Validate the current credential setup before relying on a blank-key configuration.</p>
              <div>
                <a href="https://github.com/DevEmperor/DictateKeyboard/blob/c3bf0fe34ae0308490ff6c1572bf77ec825b0454/lib/dictate-core/src/main/kotlin/dev/patrickgold/florisboard/dictate/provider/ProviderRegistry.kt" target="_blank" rel="noreferrer">Dictate model registry <ArrowUpRight size={13} weight="bold" /></a>
                <a href="https://developers.openai.com/api/docs/pricing" target="_blank" rel="noreferrer">OpenAI pricing <ArrowUpRight size={13} weight="bold" /></a>
                <a href="https://ai.google.dev/gemini-api/docs/pricing" target="_blank" rel="noreferrer">Gemini pricing <ArrowUpRight size={13} weight="bold" /></a>
                <a href="https://docs.anthropic.com/en/docs/about-claude/pricing" target="_blank" rel="noreferrer">Anthropic pricing <ArrowUpRight size={13} weight="bold" /></a>
                <a href="https://groq.com/pricing" target="_blank" rel="noreferrer">Groq pricing <ArrowUpRight size={13} weight="bold" /></a>
                <a href="https://openrouter.ai/pricing" target="_blank" rel="noreferrer">OpenRouter pricing <ArrowUpRight size={13} weight="bold" /></a>
                <a href="https://www.together.ai/pricing" target="_blank" rel="noreferrer">Together pricing <ArrowUpRight size={13} weight="bold" /></a>
                <a href="https://deepinfra.com/pricing" target="_blank" rel="noreferrer">DeepInfra pricing <ArrowUpRight size={13} weight="bold" /></a>
                <a href="https://mistral.ai/pricing/api/" target="_blank" rel="noreferrer">Mistral pricing <ArrowUpRight size={13} weight="bold" /></a>
                <a href="https://docs.x.ai/docs/models" target="_blank" rel="noreferrer">xAI pricing <ArrowUpRight size={13} weight="bold" /></a>
                <a href="https://api-docs.deepseek.com/quick_start/pricing" target="_blank" rel="noreferrer">DeepSeek pricing <ArrowUpRight size={13} weight="bold" /></a>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
