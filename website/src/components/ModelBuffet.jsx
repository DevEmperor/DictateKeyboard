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
  { provider: "Groq", model: "distil-whisper-large-v3-en", route: "async", cost: "Legacy", unit: "provider-retired", quality: "Not current", languages: "English", detail: "ID remains in Dictate; retired by provider" },

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
  { provider: "Soniox", model: "stt-rt-v5", route: "realtime", cost: "$2.00", unit: "/ 1k min", wer: 4.5, latency: "0.054s final", languages: "60+ languages", detail: "Fastest exact active Dictate route in AA snapshot" },
  { provider: "ElevenLabs", model: "scribe_v2", route: "async", cost: "$3.67", unit: "/ 1k min", wer: 2.18, languages: "90+ languages", detail: "Lowest batch AA-WER in this buffet" },
  { provider: "ElevenLabs", model: "scribe_v2_realtime", route: "realtime", cost: "$6.50", unit: "/ 1k min", wer: 3.59, latency: "0.141s final", languages: "90+ languages", detail: "Lowest realtime AA-WER here" },
  { provider: "Deepgram", model: "nova-3", route: "async", cost: "~$4.80", unit: "/ 1k min", wer: 5.18, languages: "Live language catalog", detail: "Current posted promo pricing" },
  { provider: "Deepgram", model: "nova-2", route: "async", cost: "~$5.83", unit: "/ 1k min", wer: 5.31, languages: "Additional coverage", detail: "Fills model/language gaps" },
  { provider: "Deepgram", model: "nova-3", route: "realtime", cost: "~$4.80", unit: "/ 1k min", wer: 6.59, latency: "0.066s final", languages: "Live language catalog", detail: "Realtime route · very low measured final latency" },
  { provider: "Deepgram", model: "nova-2", route: "realtime", cost: "~$5.83", unit: "/ 1k min", quality: "Not AA-tested", languages: "Additional coverage", detail: "Enabled realtime route" },
  { provider: "AssemblyAI", model: "universal-3-pro", route: "async", cost: "$3.50", unit: "/ 1k min", wer: 3.12, languages: "6 languages", detail: "Accuracy-focused batch default" },
  { provider: "AssemblyAI", model: "universal-2", route: "async", cost: "~$2.50", unit: "/ 1k min", quality: "3.82% alias proxy*", languages: "99 languages", detail: "Wider language coverage" },
  { provider: "AssemblyAI", model: "universal-streaming", route: "realtime", cost: "~$2.50", unit: "/ 1k min", quality: "Not AA-tested", languages: "Endpoint-dependent", detail: "Dictate realtime default" },
  { provider: "Custom", model: "OpenAI-compatible STT endpoint", route: "async", cost: "Your server", unit: "your model", quality: "Model-dependent", languages: "Model-dependent", detail: "Dynamic model ID or manual entry" },
];

const llmRoutes = [
  { provider: "OpenAI", price: "$0.10–$2.50 in / $0.40–$10 out per 1M tokens", tier: "Fast to frontier", models: [
    { name: "gpt-4.1-nano", price: "$0.10 in / $0.40 out" },
    { name: "gpt-4o-mini", price: "$0.15 in / $0.60 out" },
    { name: "gpt-4.1-mini", price: "$0.40 in / $1.60 out" },
    { name: "gpt-4.1", price: "$2.00 in / $8.00 out" },
    { name: "gpt-4o", price: "$2.50 in / $10.00 out" },
  ] },
  { provider: "Groq", price: "$0.05–$0.59 in / $0.08–$0.79 out per 1M tokens", tier: "Fast · catalog moving", models: [
    { name: "llama-3.1-8b-instant", price: "$0.05 / $0.08 · retires 16 Aug 2026" },
    { name: "llama-3.3-70b-versatile", price: "$0.59 / $0.79 · retires 16 Aug 2026" },
    { name: "gemma2-9b-it", price: "Provider-retired" },
    { name: "live model catalog", price: "Live pricing" },
  ] },
  { provider: "OpenRouter", price: "$0+ · hundreds of live models", tier: "Broadest choice", models: [
    { name: "openai/gpt-4o-mini", price: "Route pricing" },
    { name: "free chat routes", price: "$0 model cost" },
    { name: "live model catalog", price: "Provider-defined" },
    { name: "manual model ID", price: "Route pricing" },
  ] },
  { provider: "Google Gemini", price: "Free tier · $0.10–$1.50 in / $0.40–$10 out per 1M tokens", tier: "Fast to frontier", models: [
    { name: "gemini-2.5-flash-lite", price: "$0.10 in / $0.40 out" },
    { name: "gemini-3.1-flash-lite", price: "$0.25 in / $1.50 out" },
    { name: "gemini-2.5-flash", price: "$0.30 in / $2.50 out" },
    { name: "gemini-2.5-pro", price: "$1.25 in / $10.00 out" },
    { name: "gemini-3.5-flash", price: "$1.50 in / $9.00 out" },
  ] },
  { provider: "Anthropic", price: "$1–$5 in / $5–$25 out per 1M tokens", tier: "Strong writing", models: [
    { name: "claude-haiku-4-5-20251001", price: "$1 in / $5 out" },
    { name: "claude-sonnet-5", price: "$2 / $10 through 31 Aug 2026 · then $3 / $15" },
    { name: "claude-opus-4-8", price: "$5 in / $25 out" },
  ] },
  { provider: "Together AI", price: "$0.00025–$0.0042 per 1K input + 1K output tokens", tier: "Open-model range", models: [{ name: "live model catalog", price: "Live pricing" }, { name: "manual model ID", price: "Model-dependent" }] },
  { provider: "DeepInfra", price: "$0.00005–$0.010 per 1K input + 1K output tokens", tier: "Open-model range", models: [{ name: "live model catalog", price: "Live pricing" }, { name: "manual model ID", price: "Model-dependent" }] },
  { provider: "Mistral AI", price: "$0.0002–$0.009 per 1K input + 1K output tokens", tier: "Fast European models", models: [{ name: "live model catalog", price: "Live pricing" }, { name: "manual model ID", price: "Model-dependent" }] },
  { provider: "xAI / Grok", price: "$0.00375–$0.008 per 1K input + 1K output tokens", tier: "Provider-native", models: [{ name: "live model catalog", price: "Live pricing" }, { name: "manual model ID", price: "Model-dependent" }] },
  { provider: "DeepSeek", price: "$0.00042–$0.001305 per 1K input + 1K output tokens", tier: "Value reasoning", models: [{ name: "live model catalog", price: "Live pricing" }, { name: "manual model ID", price: "Model-dependent" }] },
  { provider: "Ollama", price: "$0 API cost · your compute", tier: "Self-hosted", models: [{ name: "models on your Ollama server", price: "$0 API cost" }, { name: "manual model ID", price: "Your hardware" }] },
  { provider: "Custom", price: "Your server pricing", tier: "OpenAI-compatible", models: [{ name: "any compatible chat-completions model", price: "Server-defined" }] },
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
    "Soniox|stt-async-v5|async",
    "ElevenLabs|scribe_v2|async",
  ],
  realtime: [
    "Soniox|stt-rt-v5|realtime",
    "ElevenLabs|scribe_v2_realtime|realtime",
    "Deepgram|nova-3|realtime",
  ],
};

const llmHighlightProviders = ["OpenRouter", "Google Gemini", "Ollama"];

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

            <div className="buffet-table" role="table" aria-label="Dictate speech-to-text model options">
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
                          <div className="buffet-model" role="cell"><span>{model.provider}</span><strong>{model.model}</strong><small>{model.detail}</small></div>
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
                ? "Back to the three highlights per category"
                : filter === "all"
                  ? `Explore all ${filteredModels.length} mapped STT options`
                  : `Show all ${filteredModels.length} ${filters.find((item) => item.id === filter)?.label.toLowerCase()} options`}
            </button>

            <div className="buffet-notes">
              <p><strong>Accuracy:</strong> AA-WER is Artificial Analysis’ independent English benchmark; lower is better. It is an edit rate, not a conventional “accuracy %,” and does not score every local or dynamic route. *Family or alias evidence is not an exact route result. †Batch WER does not measure streaming behavior.</p>
              <p><strong>Third-party API pricing:</strong> Dictate adds no provider markup. These USD provider list-price snapshots are normalized per 1,000 audio minutes on 13 July 2026; token-priced Gemini rows use AA’s observed effective cost. The 44 rows cover every fixed Dictate ID plus OpenRouter’s current 10-route STT snapshot. AA observed costs, live catalogs, rates, free tiers, and promotions can differ or change.</p>
              <div><a href="https://artificialanalysis.ai/speech-to-text/non-streaming" target="_blank" rel="noreferrer">Async benchmark <ArrowUpRight size={13} weight="bold" /></a><a href="https://artificialanalysis.ai/speech-to-text/streaming" target="_blank" rel="noreferrer">Realtime benchmark <ArrowUpRight size={13} weight="bold" /></a><a href="https://artificialanalysis.ai/speech-to-text/methodology" target="_blank" rel="noreferrer">Methodology <ArrowUpRight size={13} weight="bold" /></a><a href="https://deepgram.com/pricing" target="_blank" rel="noreferrer">Deepgram billing <ArrowUpRight size={13} weight="bold" /></a></div>
            </div>
          </motion.div>
        ) : (
          <motion.div id="buffet-panel-llm" role="tabpanel" key="llm" className="buffet-panel buffet-llm" initial={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, 4px, 0)" }} animate={{ opacity: 1, transform: "translate3d(0, 0, 0)" }} exit={{ opacity: 0, transform: reduceMotion ? "none" : "translate3d(0, -3px, 0)", transition: { duration: reduceMotion ? 0.12 : 0.13, ease: [0.23, 1, 0.32, 1] } }} transition={{ duration: reduceMotion ? 0.12 : 0.18, ease: [0.23, 1, 0.32, 1] }}>
            <div className="buffet-llm-intro">
              <div><span className="eyebrow">MIX AND MATCH</span><h3>Your transcription model and writing model are separate choices.</h3></div>
              <p>Start with three distinct routes: broad model choice, a free-tier cloud option, or your own Ollama server. Expand the catalog only when you want more. Rewrite quality is task-dependent, so STT-style WER does not apply.</p>
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
                  <div className="llm-route-head"><span>{String(index + 1).padStart(2, "0")}</span><strong>{route.provider}</strong><small>{route.tier}</small></div>
                  <div className="llm-models">{route.models.map((model) => <code key={model.name}><span>{model.name}</span><small>{model.price}</small></code>)}</div>
                  <div className="llm-price"><span>Provider API cost</span><strong>{route.price}</strong></div>
                </motion.article>
              ))}
            </div>
            <button type="button" className="buffet-expand" aria-expanded={llmExpanded} onClick={() => setLlmExpanded((value) => !value)}>
              {llmExpanded ? "Back to the three rewrite highlights" : `Show all ${llmRoutes.length} rewrite providers`}
            </button>
            <div className="buffet-notes">
              <p><strong>Dynamic by design:</strong> Except for the curated starting IDs shown above, each provider can load its current model catalog and accepts manual compatible IDs. Dictate adds no monthly subscription or provider markup; the USD public API-price snapshots shown here belong to external providers and are dated 13 July 2026. Prices are per 1M input/output tokens unless marked otherwise.</p>
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
