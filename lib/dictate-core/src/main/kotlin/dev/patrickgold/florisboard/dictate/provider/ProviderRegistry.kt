/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.provider

/**
 * A selectable provider option shown to the user.
 *
 * Base URLs are stable facts. Default model ids are conservative starting points only – the source
 * of truth is the live catalog via [LlmProvider.listModels] when [supportsDynamicModels] is true, so
 * users can freely pick any model the provider offers (important for OpenRouter's large catalog).
 *
 * NOTE: model ids must be re-verified against the provider when extending defaults – never guessed.
 */
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val capabilities: ProviderCapabilities,
    val supportsDynamicModels: Boolean,
    val apiKeyUrl: String? = null,
    val defaultChatModel: String? = null,
    val defaultTranscriptionModel: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    val isCustom: Boolean = false,
    /**
     * Curated, known-good model ids used by the model picker as an offline-capable starting set (and,
     * for transcription, to distinguish STT models since the `/models` catalog doesn't say which is
     * which). The live [LlmProvider.listModels] catalog is merged on top. Verify ids against the
     * provider when editing – never guessed.
     */
    val curatedChatModels: List<String> = emptyList(),
    val curatedTranscriptionModels: List<String> = emptyList(),
    /** Wire format of this provider's speech-to-text endpoint (OpenRouter differs – see [TranscriptionApi]). */
    val transcriptionApi: TranscriptionApi = TranscriptionApi.OPENAI_MULTIPART,
    /**
     * Real-time streaming transcription (issue #128). [supportsRealtime] gates whether the global
     * real-time mode applies to this provider; [realtimeApi] picks the WebSocket wire format;
     * [defaultRealtimeModel] is the streaming model used unless the user chooses another from
     * [curatedRealtimeModels]. Left off for batch-only providers (Groq, OpenRouter, …).
     */
    val supportsRealtime: Boolean = false,
    val realtimeApi: RealtimeApi? = null,
    val defaultRealtimeModel: String? = null,
    val curatedRealtimeModels: List<String> = emptyList(),
    /**
     * True for a built-in provider whose base URL is user-editable (issue #136): the editor shows a base
     * URL field pre-filled with [baseUrl], so e.g. Ollama can point at a LAN server instead of localhost.
     * Distinct from [isCustom] (a fully user-defined endpoint with its own name).
     */
    val allowsCustomBaseUrl: Boolean = false,
)

/**
 * Catalog of built-in OpenAI-compatible providers plus a factory for user-defined custom endpoints.
 */
object ProviderRegistry {

    private val CHAT_ONLY = ProviderCapabilities(chat = true, transcription = false)
    private val CHAT_AND_STT = ProviderCapabilities(chat = true, transcription = true)
    private val STT_ONLY = ProviderCapabilities(chat = false, transcription = true)

    val OPENAI = ProviderPreset(
        id = "openai",
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1/",
        capabilities = CHAT_AND_STT,
        supportsDynamicModels = true,
        apiKeyUrl = "https://platform.openai.com/api-keys",
        defaultChatModel = "gpt-4o-mini",
        defaultTranscriptionModel = "gpt-4o-mini-transcribe",
        curatedChatModels = listOf(
            "gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1", "gpt-4.1-nano",
        ),
        curatedTranscriptionModels = listOf(
            "gpt-4o-mini-transcribe", "gpt-4o-transcribe", "whisper-1",
        ),
        // Realtime (#128): wss /v1/realtime?intent=transcription. gpt-realtime-whisper is the natively
        // streaming model (emits deltas); the -transcribe models are more accurate but final-only.
        supportsRealtime = true,
        realtimeApi = RealtimeApi.OPENAI,
        defaultRealtimeModel = "gpt-realtime-whisper",
        curatedRealtimeModels = listOf(
            "gpt-realtime-whisper", "gpt-4o-transcribe", "gpt-4o-mini-transcribe",
        ),
    )

    val GROQ = ProviderPreset(
        id = "groq",
        displayName = "Groq",
        baseUrl = "https://api.groq.com/openai/v1/",
        capabilities = CHAT_AND_STT,
        supportsDynamicModels = true,
        apiKeyUrl = "https://console.groq.com/keys",
        defaultChatModel = "llama-3.3-70b-versatile",
        defaultTranscriptionModel = "whisper-large-v3-turbo",
        curatedChatModels = listOf(
            "llama-3.3-70b-versatile", "llama-3.1-8b-instant", "gemma2-9b-it",
        ),
        curatedTranscriptionModels = listOf(
            "whisper-large-v3-turbo", "whisper-large-v3", "distil-whisper-large-v3-en",
        ),
    )

    /**
     * Cerebras Inference is chat-only here. Its OpenAI-compatible API is a strong fit for
     * low-latency cleanup prompts after dictation; Gemma 4 31B is the fast default suggested for
     * punctuation, filler-word removal and light grammar correction.
     */
    val CEREBRAS = ProviderPreset(
        id = "cerebras",
        displayName = "Cerebras",
        baseUrl = "https://api.cerebras.ai/v1/",
        capabilities = CHAT_ONLY,
        supportsDynamicModels = true,
        apiKeyUrl = "https://cloud.cerebras.ai/",
        defaultChatModel = "gemma-4-31b",
        curatedChatModels = listOf(
            "gemma-4-31b", "gpt-oss-120b", "zai-glm-4.7",
        ),
    )

    val OPENROUTER = ProviderPreset(
        id = "openrouter",
        displayName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1/",
        // OpenRouter routes both chat and speech-to-text (its STT endpoint fronts Whisper, Voxtral,
        // MAI-Transcribe, …) – but via a JSON/base64 body, not the OpenAI multipart upload.
        capabilities = CHAT_AND_STT,
        transcriptionApi = TranscriptionApi.OPENROUTER_JSON,
        supportsDynamicModels = true,
        apiKeyUrl = "https://openrouter.ai/keys",
        // OpenRouter exposes hundreds of models (incl. Claude, Gemini, Llama …); users pick from the
        // live catalog. This is just a safe default to start with and is fully user-overridable.
        defaultChatModel = "openai/gpt-4o-mini",
        defaultTranscriptionModel = "openai/whisper-large-v3",
        // Dedicated STT models (MAI-Transcribe, Whisper, Parakeet, …) are discovered live now: the picker
        // queries /models?output_modalities=all and classifies audio→transcription entries (issue #157).
        // This short curated list is just an offline baseline shown before a catalog fetch / without a key.
        curatedTranscriptionModels = listOf(
            "microsoft/mai-transcribe-1.5",
            "openai/gpt-4o-transcribe", "openai/whisper-large-v3",
        ),
        // Attribution headers recommended by OpenRouter: both are used for app ranking and some routes
        // reject requests without an HTTP-Referer. The value is a stable identifier, not a real URL.
        extraHeaders = mapOf(
            "HTTP-Referer" to "https://github.com/DevEmperor/Dictate",
            "X-Title" to "Dictate",
        ),
    )

    val GEMINI = ProviderPreset(
        id = "gemini",
        displayName = "Google Gemini",
        // The OpenAI-compatible base URL serves chat/rewording and the live model catalog unchanged.
        // Transcription instead uses Gemini's native generateContent endpoint, derived from this URL by
        // dropping the trailing `openai/` (see TranscriptionApi.GEMINI_GENERATE_CONTENT).
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/",
        capabilities = CHAT_AND_STT,
        transcriptionApi = TranscriptionApi.GEMINI_GENERATE_CONTENT,
        supportsDynamicModels = true,
        apiKeyUrl = "https://aistudio.google.com/app/apikey",
        defaultChatModel = "gemini-2.5-flash",
        defaultTranscriptionModel = "gemini-2.5-flash",
        // Stable, audio-capable Gemini models (verified June 2026; 2.0-flash was retired 2026-06-01). The
        // live picker merges any newer ones on top.
        curatedChatModels = listOf(
            "gemini-2.5-flash", "gemini-3.5-flash", "gemini-3.1-flash-lite", "gemini-2.5-pro", "gemini-2.5-flash-lite",
        ),
        // Gemini has no dedicated STT model – its multimodal chat models double as transcription models, so
        // the curated STT set mirrors the (cheaper, faster) flash chat models.
        curatedTranscriptionModels = listOf(
            "gemini-2.5-flash", "gemini-3.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro",
        ),
        // Realtime (#128): Live API (BidiGenerateContent) with input_audio_transcription. Live models are
        // a distinct family — the exact id is verified when the Gemini Live session is built (later phase).
        // Realtime disabled: the Live API connects but never acks `setup` (no setupComplete) with our
        // config — the bidirectional Live protocol needs verified model id + setup shape (#128). Keep the
        // wiring so it can be re-enabled once confirmed; until then Gemini uses batch transcription.
        supportsRealtime = false,
        realtimeApi = RealtimeApi.GEMINI,
        defaultRealtimeModel = "gemini-live-2.5-flash-preview",
        curatedRealtimeModels = listOf("gemini-live-2.5-flash-preview"),
    )

    /**
     * Anthropic Claude — rewording only (issue: Anthropic provider). Anthropic has no speech-to-text or
     * realtime-audio API, so this is [CHAT_ONLY]; Claude is excellent for rewording, translation and tone
     * changes. It is reached through Anthropic's OpenAI-compatible endpoint (`POST {baseUrl}chat/completions`
     * with an `Authorization: Bearer <key>` header), so the standard [OpenAiCompatibleClient] works
     * unchanged. `GET {baseUrl}models` also accepts that same Bearer key and returns the Claude models in
     * the OpenAI `{ data: [{ id }] }` shape, so [supportsDynamicModels] = true keeps the picker current when
     * Anthropic adds/renames models — no app update needed; [curatedChatModels] is only the offline baseline
     * shown before a catalog fetch / without a key. Default is the fast/cheap Haiku, mirroring the mini/flash
     * defaults of the other providers. Verify ids against Anthropic's model list when editing – never guess.
     */
    val ANTHROPIC = ProviderPreset(
        id = "anthropic",
        displayName = "Anthropic (Claude)",
        baseUrl = "https://api.anthropic.com/v1/",
        capabilities = CHAT_ONLY,
        supportsDynamicModels = true,
        apiKeyUrl = "https://console.anthropic.com/settings/keys",
        defaultChatModel = "claude-haiku-4-5-20251001",
        curatedChatModels = listOf(
            "claude-haiku-4-5-20251001", "claude-sonnet-5", "claude-opus-4-8",
        ),
    )

    val TOGETHER = ProviderPreset(
        id = "together",
        displayName = "Together AI",
        baseUrl = "https://api.together.xyz/v1/",
        capabilities = CHAT_ONLY,
        supportsDynamicModels = true,
        apiKeyUrl = "https://api.together.ai/settings/api-keys",
    )

    val DEEPINFRA = ProviderPreset(
        id = "deepinfra",
        displayName = "DeepInfra",
        baseUrl = "https://api.deepinfra.com/v1/openai/",
        capabilities = CHAT_ONLY,
        supportsDynamicModels = true,
        apiKeyUrl = "https://deepinfra.com/dash/api_keys",
    )

    val MISTRAL = ProviderPreset(
        id = "mistral",
        displayName = "Mistral AI",
        baseUrl = "https://api.mistral.ai/v1/",
        // Voxtral transcription via the standard OpenAI multipart endpoint (/v1/audio/transcriptions).
        capabilities = CHAT_AND_STT,
        supportsDynamicModels = true,
        apiKeyUrl = "https://console.mistral.ai/api-keys",
        defaultTranscriptionModel = "voxtral-mini-latest",
        curatedTranscriptionModels = listOf("voxtral-mini-latest"),
        // Realtime (#128): Voxtral realtime (/v1/realtime, vLLM-style). Model id verified when built.
        // Realtime disabled: Mistral's raw WS returns 403 and the protocol is SDK-only/unverified (#128).
        // Keep the wiring (RealtimeApi + session) so it can be re-enabled once the protocol is confirmed.
        supportsRealtime = false,
        realtimeApi = RealtimeApi.MISTRAL_VOXTRAL,
        defaultRealtimeModel = "voxtral-mini-transcribe-realtime-2602",
        curatedRealtimeModels = listOf("voxtral-mini-transcribe-realtime-2602"),
    )

    val SONIOX = ProviderPreset(
        id = "soniox",
        displayName = "Soniox",
        // Soniox is transcription-only and does NOT speak the OpenAI wire format: it uses a multi-step
        // async REST flow (see TranscriptionApi.SONIOX_ASYNC). Very accurate, strong multilingual/German.
        baseUrl = "https://api.soniox.com/v1/",
        capabilities = STT_ONLY,
        transcriptionApi = TranscriptionApi.SONIOX_ASYNC,
        // /v1/models is supported and returns transcription_mode per model; the client filters to async.
        supportsDynamicModels = true,
        apiKeyUrl = "https://console.soniox.com",
        defaultTranscriptionModel = "stt-async-v5",
        // Verified against Soniox's model catalog; the live picker adds any newer async models.
        curatedTranscriptionModels = listOf("stt-async-v5"),
        // Realtime (#128): wss stt-rt.soniox.com/transcribe-websocket, model stt-rt-v5.
        supportsRealtime = true,
        realtimeApi = RealtimeApi.SONIOX,
        defaultRealtimeModel = "stt-rt-v5",
        curatedRealtimeModels = listOf("stt-rt-v5"),
    )

    /**
     * ElevenLabs Scribe (issue #143): transcription-only, multipart upload with an `xi-api-key` header
     * (see [TranscriptionApi.ELEVENLABS_MULTIPART]). Strong multilingual accuracy.
     */
    val ELEVENLABS = ProviderPreset(
        id = "elevenlabs",
        displayName = "ElevenLabs Scribe",
        baseUrl = "https://api.elevenlabs.io/v1/",
        capabilities = STT_ONLY,
        transcriptionApi = TranscriptionApi.ELEVENLABS_MULTIPART,
        // /v1/models mixes TTS + STT models, so no clean STT filter — curated instead. scribe_v1 was
        // retired on 2026-07-09, leaving scribe_v2.
        supportsDynamicModels = false,
        apiKeyUrl = "https://elevenlabs.io/app/settings/api-keys",
        defaultTranscriptionModel = "scribe_v2",
        curatedTranscriptionModels = listOf("scribe_v2"),
        // Realtime (#128): Scribe v2 Realtime WebSocket (~150ms). Model id verified when the session is built.
        supportsRealtime = true,
        realtimeApi = RealtimeApi.ELEVENLABS,
        defaultRealtimeModel = "scribe_v2_realtime",
        curatedRealtimeModels = listOf("scribe_v2_realtime"),
    )

    /**
     * Deepgram (issue #143): transcription-only, raw-body POST to `listen` with a `Token` auth header
     * (see [TranscriptionApi.DEEPGRAM]). Fast and accurate; nova-3 is the current general model.
     */
    val DEEPGRAM = ProviderPreset(
        id = "deepgram",
        displayName = "Deepgram",
        baseUrl = "https://api.deepgram.com/v1/",
        capabilities = STT_ONLY,
        transcriptionApi = TranscriptionApi.DEEPGRAM,
        // GET /v1/models returns the live STT catalog (canonical_name); curated ids are the offline fallback.
        supportsDynamicModels = true,
        apiKeyUrl = "https://console.deepgram.com/",
        defaultTranscriptionModel = "nova-3",
        curatedTranscriptionModels = listOf("nova-3", "nova-2"),
        // Realtime (#128): wss /v1/listen?encoding=linear16&sample_rate=16000&interim_results=true.
        supportsRealtime = true,
        realtimeApi = RealtimeApi.DEEPGRAM,
        defaultRealtimeModel = "nova-3",
        curatedRealtimeModels = listOf("nova-3", "nova-2"),
    )

    /**
     * AssemblyAI (issue #143): transcription-only, async upload/create/poll flow with a raw `authorization`
     * header (see [TranscriptionApi.ASSEMBLYAI_ASYNC]).
     */
    val ASSEMBLYAI = ProviderPreset(
        id = "assemblyai",
        displayName = "AssemblyAI",
        baseUrl = "https://api.assemblyai.com/",
        capabilities = STT_ONLY,
        transcriptionApi = TranscriptionApi.ASSEMBLYAI_ASYNC,
        supportsDynamicModels = false,
        apiKeyUrl = "https://www.assemblyai.com/app/api-keys",
        defaultTranscriptionModel = "universal-3-pro",
        curatedTranscriptionModels = listOf("universal-3-pro", "universal-2"),
        // Realtime (#128): Universal-Streaming wss streaming.assemblyai.com/v3/ws (~300ms). Model ids
        // verified when the session is built (billed by connection-open duration).
        supportsRealtime = true,
        realtimeApi = RealtimeApi.ASSEMBLYAI,
        defaultRealtimeModel = "universal-streaming",
        curatedRealtimeModels = listOf("universal-streaming"),
    )

    val XAI = ProviderPreset(
        id = "xai",
        displayName = "xAI (Grok)",
        baseUrl = "https://api.x.ai/v1/",
        capabilities = CHAT_ONLY,
        supportsDynamicModels = true,
        apiKeyUrl = "https://console.x.ai",
    )

    val DEEPSEEK = ProviderPreset(
        id = "deepseek",
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1/",
        capabilities = CHAT_ONLY,
        supportsDynamicModels = true,
        apiKeyUrl = "https://platform.deepseek.com/api_keys",
    )

    /**
     * Ollama server (OpenAI-compatible). No API key required by default. The base URL is user-editable
     * (issue #136) and defaults to localhost — point it at `http://<lan-ip>:11434/v1/` for a server on
     * another machine (localhost resolves to the phone itself).
     */
    val OLLAMA = ProviderPreset(
        id = "ollama",
        displayName = "Ollama",
        baseUrl = "http://localhost:11434/v1/",
        capabilities = CHAT_ONLY,
        supportsDynamicModels = true,
        apiKeyUrl = null,
        allowsCustomBaseUrl = true,
    )

    /**
     * On-device, fully offline transcription (issue #104). No network, no API key. Handled by
     * [LocalTranscriptionProvider] (sherpa-onnx + a bundled Whisper model), not by the HTTP client –
     * [TranscriptionApi.LOCAL_ONDEVICE] marks it so the dictation flow routes there. The model id is the
     * name of an installed model directory; models are downloaded on demand (the catalog is fixed, not
     * fetched), hence supportsDynamicModels = false.
     */
    val LOCAL = ProviderPreset(
        id = "local",
        displayName = "On-device (offline)",
        baseUrl = "",
        capabilities = STT_ONLY,
        transcriptionApi = TranscriptionApi.LOCAL_ONDEVICE,
        supportsDynamicModels = false,
        apiKeyUrl = null,
        // Base is the recommended balance of accuracy/speed; tiny is offered for low-end devices.
        defaultTranscriptionModel = "whisper-base",
        curatedTranscriptionModels = listOf("whisper-base", "whisper-tiny"),
    )

    /** All built-in presets in display order. The custom option is added by the UI on top of these. */
    val presets: List<ProviderPreset> = listOf(
        OPENAI, GROQ, CEREBRAS, OPENROUTER, GEMINI, ANTHROPIC, TOGETHER, DEEPINFRA, MISTRAL, SONIOX,
        ELEVENLABS, DEEPGRAM, ASSEMBLYAI, XAI, DEEPSEEK, OLLAMA, LOCAL,
    )

    fun byId(id: String): ProviderPreset? = presets.firstOrNull { it.id == id }

    /** Builds a preset for a user-defined OpenAI-compatible endpoint. */
    fun custom(
        baseUrl: String,
        displayName: String = "Custom server",
        capabilities: ProviderCapabilities = CHAT_AND_STT,
    ): ProviderPreset = ProviderPreset(
        id = "custom",
        displayName = displayName,
        baseUrl = baseUrl,
        capabilities = capabilities,
        supportsDynamicModels = true,
        isCustom = true,
    )
}
