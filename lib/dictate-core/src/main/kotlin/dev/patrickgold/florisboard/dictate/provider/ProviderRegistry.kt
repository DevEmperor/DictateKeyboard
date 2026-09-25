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
 * One data-residency region of a provider (issue #403).
 *
 * A region is not a base URL the user thought up: it is a published address of the same service, and the
 * provider decides which ones exist. That is why it is a list on the preset rather than a free text box —
 * a typo in a residency host does not fail loudly, it silently sends the audio to the wrong continent.
 *
 * [realtimeUrl] is the point of the whole type. Soniox runs a second host for streaming that is nowhere
 * derivable from the REST one the user picked, so a region that could only move the batch address would
 * leave live dictation talking to the default region while everything else moved — which is exactly the
 * failure this issue asked us not to build. Null where the provider has no streaming (OpenRouter).
 *
 * The region is stored as the account's own base URL, so nothing about persistence, the Wear bridge, the
 * importer or the connection test needed a new field to learn about.
 */
data class ProviderRegion(
    /** Stable key, mapped to a translated name in the settings UI. */
    val id: String,
    val baseUrl: String,
    val realtimeUrl: String? = null,
)

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
    /**
     * The provider's own page for creating an API key — the deepest link that survives a sign-in, not
     * the dashboard root, because the last step is exactly the one someone without a key cannot guess.
     *
     * **Null means "this provider has no key page", never "we did not look it up"** (#410). Both places
     * that offer the page — the setup wizard's button and the key icon in the provider dialog's title
     * row — key off null alone, so a missing URL silently removes the way out of a dialog whose key
     * field is the thing being stared at. Null is right for Dictate Cloud, Ollama, the on-device
     * provider and custom endpoints: there is nowhere to send anyone.
     *
     * Checked against the live host, with the date, like every other fact in here.
     */
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
    /**
     * The audio containers this provider documents as accepted uploads (issue #322).
     *
     * **Empty means unknown, never "anything goes"** — the same rule [maxUploadBytes] follows, and for
     * the same reason: a provider that publishes no list is left to speak for itself, and an unknown
     * file is sent as it is rather than converted on a guess. A non-empty list is a promise the app
     * acts on: a container missing from it is transcoded before upload instead of being refused by the
     * provider after the bytes have already been paid for.
     *
     * Filled in only from the provider's own documentation, with the date it was read.
     */
    val acceptedAudioContainers: Set<AudioContainer> = emptySet(),
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
     *
     * A preset that also lists [regions] keeps this on — the account still carries its own base URL, and
     * every resolution site already reads it — but the editor offers the region list instead of a text
     * box, because there the set of valid addresses is known and short (#403).
     */
    val allowsCustomBaseUrl: Boolean = false,
    /**
     * The provider's data-residency regions (issue #403), the first being the one [baseUrl] points at.
     * Empty for everyone who serves the world from one address.
     */
    val regions: List<ProviderRegion> = emptyList(),
    /**
     * True where the provider keeps the data in the EU **by default**, in its own published words, so
     * the "EU" filter of the add-provider list can offer it. A provider that merely *offers* an EU
     * region says so through [regions] instead and is found by [servesFromEu] all the same.
     */
    val hostedInEu: Boolean = false,
) {
    /** Whether the audio and text can stay in the EU with this provider — by default, or by region. */
    val servesFromEu: Boolean
        get() = hostedInEu || regions.any { it.id == "eu" }
}

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
        // gpt-transcribe (2026-07) is both cheaper than gpt-4o-transcribe ($0.0045 vs $0.006 per minute)
        // and markedly more accurate, so it is the default. This applies to everyone who never chose a
        // model — the account stores an empty string in that case and resolves through here on every
        // call, so existing installs move to it too. An explicit choice is stored verbatim and untouched.
        defaultTranscriptionModel = "gpt-transcribe",
        curatedChatModels = listOf(
            "gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1", "gpt-4.1-nano",
        ),
        curatedTranscriptionModels = listOf(
            "gpt-transcribe", "gpt-4o-mini-transcribe", "gpt-4o-transcribe", "whisper-1",
        ),
        // The guide lists mp3, mp4, mpeg, mpga, m4a, wav, webm; the API reference adds flac and ogg.
        // Rather than pick a page, the endpoint was asked directly on 2026-09-04 with the same Ogg/Opus
        // bytes twice, same `audio/ogg` part type, only the file NAME differing:
        //     filename=PTT-….opus -> 400 "Unsupported file format opus"
        //     filename=voice.ogg  -> 200 and a correct transcript (gpt-transcribe and whisper-1 alike)
        // So Ogg belongs here: what OpenAI refuses is the extension `opus`, not the container — which is
        // why uploads now travel under the canonical name for what is inside them (see
        // [audioUploadNameOf]). A shared voice note therefore needs no transcode at all any more.
        // flac stays out: the same disagreement applies to it and nobody has measured it.
        acceptedAudioContainers = setOf(
            AudioContainer.MP3, AudioContainer.M4A, AudioContainer.WAV,
            AudioContainer.WEBM, AudioContainer.OGG,
        ),
        // Realtime (#128): wss /v1/realtime?intent=transcription. gpt-live-transcribe is the streaming
        // model of the gpt-transcribe generation and emits deltas like gpt-realtime-whisper did, at the
        // same $0.017/min but a lower word error rate (11.65% -> 9.60%), so it is the default. The
        // "-transcribe" models are more accurate still but final-only, with no interim text.
        supportsRealtime = true,
        realtimeApi = RealtimeApi.OPENAI,
        defaultRealtimeModel = "gpt-live-transcribe",
        curatedRealtimeModels = listOf(
            "gpt-live-transcribe", "gpt-realtime-whisper", "gpt-transcribe",
            "gpt-4o-transcribe", "gpt-4o-mini-transcribe",
        ),
    )

    /**
     * Dictate Cloud — credit bought inside the app instead of an API key of one's own.
     *
     * Technically the least remarkable entry in this list: the server speaks the same OpenAI formats
     * as everything else, so [OpenAiCompatibleClient] reaches it unchanged and the wallet token
     * simply sits where an API key would, in [ProviderAccount.apiKey]. What differs is who decides.
     * The model is not the user's pick but the server's, because the price is calculated from it —
     * so there is nothing for the picker to offer and [supportsDynamicModels] is false. The ids
     * below travel with the request and are overwritten upstream; they exist so the request stays
     * well-formed, not because they name anything real.
     *
     * Realtime stays off on purpose rather than by omission: streaming costs nearly four times a
     * dictated minute, and the on-device engine already does it for nothing.
     */
    val CLOUD = ProviderPreset(
        id = "cloud",
        displayName = "Dictate Cloud",
        baseUrl = "https://api.dictatekeyboard.com/v1/",
        capabilities = CHAT_AND_STT,
        supportsDynamicModels = false,
        apiKeyUrl = null,
        defaultChatModel = "dictate-cloud",
        defaultTranscriptionModel = "dictate-cloud",
        supportsRealtime = false,
        // Not copied from an upstream provider's list — the server does not say which one it used. This
        // is what its OWN duration probe reads (`cloud/src/audio.ts`), and that matters to the person
        // paying: a container it cannot probe is billed from a generous size estimate instead of the
        // real length. Converting into this set is therefore cheaper for the user, not just safer.
        acceptedAudioContainers = setOf(
            AudioContainer.WAV, AudioContainer.MP3, AudioContainer.M4A,
            AudioContainer.OGG, AudioContainer.FLAC,
        ),
    )

    val GROQ = ProviderPreset(
        id = "groq",
        displayName = "Groq",
        baseUrl = "https://api.groq.com/openai/v1/",
        capabilities = CHAT_AND_STT,
        supportsDynamicModels = true,
        apiKeyUrl = "https://console.groq.com/keys",
        // Every Llama and Gemma entry that used to stand here was decommissioned by Groq (announced
        // 2026-06-17, gone by 2026-08-16), and the default among them is what made #313 look like a
        // model-resolution bug: a request went out naming a model the user had never chosen and could
        // not have. Verified against the live catalog on 2026-09-02, which now lists 14 models in total.
        // gpt-oss-120b rather than the smaller 20b because it is the first replacement Groq's own
        // deprecation notice names for the 70B model it succeeds — a default should not quietly cost
        // quality. Both gpt-oss models are reasoning models, which is fine here: they return their
        // thinking in a separate `reasoning` field, and nothing in the app caps `max_tokens`, so the
        // reply is the answer alone.
        //
        // qwen/qwen3.6-27b is the third model Groq's notice names and is deliberately NOT listed: asked
        // to fix a sentence it wrote 1161 tokens of `<think>…</think>` *into the content*, and nothing
        // strips that, so it would land verbatim in the user's text field. qwen3.8-27b answers cleanly.
        // Measured against the live API on 2026-09-02 with the app's own Fix Grammar prompt — an id
        // existing in the catalog is not the same as a model that can reword.
        defaultChatModel = "openai/gpt-oss-120b",
        defaultTranscriptionModel = "whisper-large-v3-turbo",
        curatedChatModels = listOf(
            "openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b",
        ),
        // distil-whisper-large-v3-en went the same way; both remaining Whispers are live.
        curatedTranscriptionModels = listOf(
            "whisper-large-v3-turbo", "whisper-large-v3",
        ),
        // Read 2026-09-04: flac, mp3, mp4, mpeg, mpga, m4a, ogg, wav, webm. Ogg included, so a shared
        // voice note goes to Groq untouched where OpenAI needs it transcoded.
        acceptedAudioContainers = setOf(
            AudioContainer.FLAC, AudioContainer.MP3, AudioContainer.M4A,
            AudioContainer.OGG, AudioContainer.WAV, AudioContainer.WEBM,
        ),
    )

    val OPENROUTER = ProviderPreset(
        id = "openrouter",
        displayName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1/",
        // OpenRouter routes both chat and speech-to-text (its STT endpoint fronts Whisper, Voxtral,
        // MAI-Transcribe, …). Its endpoint currently accepts OpenAI-compatible multipart; streaming the
        // file avoids the extra base64 copy and oversized JSON body. The client retains documented JSON
        // as a compatibility fallback if OpenRouter explicitly rejects multipart.
        //
        // Two documented ceilings apply and neither is ours to widen (#321): a multipart upload may not
        // exceed 25 MB (see [maxUploadBytes]), and a request gets ~60 seconds of *processing* time — not
        // a cap on audio length, so it cannot honestly be turned into a duration: how much speech fits
        // depends on the chosen model's speed. `prompt` is accepted by the endpoint and then discarded
        // (only Groq reads one, through `provider.options`), so a vocabulary hint does nothing here.
        capabilities = CHAT_AND_STT,
        transcriptionApi = TranscriptionApi.OPENROUTER_MULTIPART,
        supportsDynamicModels = true,
        apiKeyUrl = "https://openrouter.ai/keys",
        // OpenRouter exposes hundreds of models (incl. Claude, Gemini, Llama …); users pick from the
        // live catalog. This is just a safe default to start with and is fully user-overridable.
        defaultChatModel = "openai/gpt-4o-mini",
        // gpt-transcribe is the same model the OpenAI preset defaults to, which is the point: one model
        // behaving one way across providers, already exercised by this app. It is also the only entry
        // below that OpenRouter does not route — OpenAI is its sole upstream, so nobody's dictation
        // quality depends on which backend won the draw that minute. It costs $0.0045/min against the
        // ~$0.0005/min the outgoing Whisper default reached via DeepInfra; the cheap route stays one tap
        // away in the picker. Everyone who never chose a model stores an empty string and resolves
        // through here on every call, so existing installs move too; an explicit choice is untouched.
        defaultTranscriptionModel = "openai/gpt-transcribe",
        // Dedicated STT models (MAI-Transcribe, Whisper, Parakeet, …) are discovered live: the picker
        // queries /models?output_modalities=all and classifies audio→transcription entries (issue #157).
        // That parameter is not optional trivia — the bare /models defaults to `output_modalities=text`
        // and hides EVERY speech-to-text model, which is how #321 came to report a catalog that had lost
        // them all. `tools/check-openrouter-models.py` re-checks the ids below against the right URL.
        //
        // Verified 2026-09-04, each with a live endpoint. Prices are read off the model pages, never off
        // `pricing.prompt`: OpenRouter does not normalize that field, so the same key means per minute
        // for gpt-transcribe, per hour for Groq's Whisper and per second for DeepInfra's.
        //   openai/gpt-transcribe          $0.0045/min   OpenAI
        //   microsoft/mai-transcribe-2     $0.10/h       Azure — 60 languages, code-switching
        //   mistralai/voxtral-mini-transcribe $0.003/min Mistral (also an EU region)
        //   deepgram/nova-3                $0.0043/min   Deepgram
        //   openai/whisper-large-v3-turbo  cheapest      DeepInfra, Groq
        //   openai/whisper-large-v3        from $0.00048/min via DeepInfra (also Together, Groq) — kept
        //                                  because it was the default until now, so a choice stays visible.
        curatedTranscriptionModels = listOf(
            "openai/gpt-transcribe", "microsoft/mai-transcribe-2",
            "mistralai/voxtral-mini-transcribe", "deepgram/nova-3",
            "openai/whisper-large-v3-turbo", "openai/whisper-large-v3",
        ),
        // Read 2026-09-04 from the speech-to-text guide: wav, mp3, flac, m4a, ogg, webm, aac.
        acceptedAudioContainers = setOf(
            AudioContainer.WAV, AudioContainer.MP3, AudioContainer.FLAC, AudioContainer.M4A,
            AudioContainer.OGG, AudioContainer.WEBM, AudioContainer.AAC,
        ),
        // Attribution headers recommended by OpenRouter: both are used for app ranking and some routes
        // reject requests without an HTTP-Referer. The value is a stable identifier, not a real URL.
        extraHeaders = mapOf(
            "HTTP-Referer" to "https://github.com/DevEmperor/Dictate",
            "X-Title" to "Dictate",
        ),
        // Data residency (#403): the EU entry point decrypts in the EU and routes only to in-region
        // provider endpoints, failing a request outright rather than letting it leave the region. Same
        // key, same model slugs — unlike Soniox, nothing but the address changes. Verified 2026-09-18:
        // GET https://eu.openrouter.ai/api/v1/models answers 200 with the full catalog. It is a Business
        // plan feature, which is the account's business and not ours to check for.
        //
        // This is a region rather than the "add your own server" workaround because that path speaks plain
        // OpenAI multipart: it would have cost the documented JSON fallback, the OpenRouter retry handling
        // and every speech-to-text model in the picker (the catalog needs output_modalities=all, which is
        // only asked for when the wire format is OPENROUTER_MULTIPART — a custom account never is).
        allowsCustomBaseUrl = true,
        regions = listOf(
            ProviderRegion("global", "https://openrouter.ai/api/v1/"),
            ProviderRegion("eu", "https://eu.openrouter.ai/api/v1/"),
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
        // gemini-2.5-flash answers 404 with "no longer available to new users" (issue #313). Google names
        // the successor in three places that agree: that error text, the deprecations page, and its
        // migration notes for the 2.0 family. gemini-3.8-flash appeared on 2026-09-02 and is too fresh to
        // be what everyone who never chose a model gets.
        defaultChatModel = "gemini-3.6-flash",
        // gemini-3.5-transcribe (2026-08) is Google's first dedicated speech-to-text model: ~$0.005/min
        // against chat-model token pricing, 2.6% word error rate, 85+ languages (issue #292). It does not
        // speak generateContent — see TranscriptionApi.GEMINI_GENERATE_CONTENT and [isGeminiTranscribeModel].
        // Everyone who never picked a model stores an empty string and resolves through here on every call,
        // so existing installs move to it too; an explicit choice is stored verbatim and untouched.
        defaultTranscriptionModel = "gemini-3.5-transcribe",
        // Stable, audio-capable Gemini models (verified June 2026; 2.0-flash was retired 2026-06-01). The
        // live picker merges any newer ones on top.
        // The whole 2.5 generation is retired; the live picker merges any newer ones on top.
        curatedChatModels = listOf(
            "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite",
        ),
        // The dedicated STT model first, then the multimodal chat models that double as transcription
        // models — they travel a different endpoint, so both belong here.
        curatedTranscriptionModels = listOf(
            "gemini-3.5-transcribe",
            "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite",
        ),
        // Read 2026-09-04 from Google's audio docs: wav, mp3, aiff, aac, ogg, flac, mpeg, m4a, l16,
        // opus, alaw, mulaw, webm. The voice note in #322 was never a format Gemini refuses — the app
        // only failed to say what it was. Nothing here has to be converted for Gemini.
        acceptedAudioContainers = setOf(
            AudioContainer.WAV, AudioContainer.MP3, AudioContainer.AAC, AudioContainer.OGG,
            AudioContainer.FLAC, AudioContainer.M4A, AudioContainer.WEBM,
        ),
        // Realtime (#128/#292): Live API (BidiGenerateContent) with inputAudioTranscription. This was off
        // until Google shipped a streaming model built for it — the conversational live models connect but
        // never ack `setup`, so the session died before a word was spoken. gemini-3.5-transcribe-live is a
        // dedicated streaming STT model at ~$0.009/min, about half of OpenAI's, and emits both speculative
        // and finalized text.
        supportsRealtime = true,
        realtimeApi = RealtimeApi.GEMINI,
        defaultRealtimeModel = "gemini-3.5-transcribe-live",
        curatedRealtimeModels = listOf("gemini-3.5-transcribe-live"),
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
        // The console moved hosts: console.anthropic.com/settings/keys 301s to this one (2026-09-21).
        apiKeyUrl = "https://platform.claude.com/settings/keys",
        defaultChatModel = "claude-haiku-4-5-20251001",
        curatedChatModels = listOf(
            "claude-haiku-4-5-20251001", "claude-sonnet-5", "claude-opus-5",
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
        // "By default, your data is hosted in the European Union" (Mistral help centre, updated
        // 2026-08-12). The same page names a separate US endpoint, which this base URL is not, and
        // subprocessors outside the EU for some features.
        hostedInEu = true,
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
        // Not the console root: /api-keys is a real route rather than a client-side guess — /apikeys
        // answers 404, so the server itself distinguishes them (2026-09-21).
        apiKeyUrl = "https://console.soniox.com/api-keys",
        defaultTranscriptionModel = "stt-async-v5",
        // Verified against Soniox's model catalog; the live picker adds any newer async models.
        curatedTranscriptionModels = listOf("stt-async-v5"),
        // Realtime (#128): wss stt-rt.soniox.com/transcribe-websocket, model stt-rt-v5.
        supportsRealtime = true,
        realtimeApi = RealtimeApi.SONIOX,
        defaultRealtimeModel = "stt-rt-v5",
        curatedRealtimeModels = listOf("stt-rt-v5"),
        // Data residency (#403). Soniox sets the region per *project*, and each regional project mints its
        // own key that is valid against that region's hosts only — which is why the report reads as "the
        // new key doesn't work" rather than as anything about a region. Hosts from Soniox's data-residency
        // docs, read 2026-09-18, and the EU pair asked directly: GET https://api.eu.soniox.com/v1/models
        // answers 401 unauthenticated with the same error body as the US host, and stt-rt.eu.soniox.com
        // resolves. Same API, another address; nothing here is a port.
        //
        // Both hosts move together or neither does. The streaming host is a sibling of the REST one, not a
        // path under it, so a base URL the user typed could never have carried it — a region that moved
        // only the async flow would have gone on streaming to the US while claiming to be in the EU.
        allowsCustomBaseUrl = true,
        regions = listOf(
            ProviderRegion("us", "https://api.soniox.com/v1/", "wss://stt-rt.soniox.com/transcribe-websocket"),
            ProviderRegion("eu", "https://api.eu.soniox.com/v1/", "wss://stt-rt.eu.soniox.com/transcribe-websocket"),
            ProviderRegion("jp", "https://api.jp.soniox.com/v1/", "wss://stt-rt.jp.soniox.com/transcribe-websocket"),
            ProviderRegion("in", "https://api.in.soniox.com/v1/", "wss://stt-rt.in.soniox.com/transcribe-websocket"),
        ),
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
        // The keys page left settings for the developers section; the old path redirects (2026-09-21).
        apiKeyUrl = "https://elevenlabs.io/app/developers/api-keys",
        defaultTranscriptionModel = "scribe_v2",
        curatedTranscriptionModels = listOf("scribe_v2"),
        // Read 2026-09-04: aac, aiff, ogg, mpeg/mp3, opus, wav, webm, flac, mp4/m4a — the most generous
        // list of any provider here, and one of only two that name Opus explicitly.
        acceptedAudioContainers = setOf(
            AudioContainer.AAC, AudioContainer.OGG, AudioContainer.MP3, AudioContainer.WAV,
            AudioContainer.WEBM, AudioContainer.FLAC, AudioContainer.M4A,
        ),
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
        // Deepgram's console answers 200 for any path, so a status code proves nothing about a deep link
        // and their own docs decide instead: `?jump=keys` is the parameter Deepgram publishes for landing
        // on the keys page, and it survives the account step someone without a key has to take first
        // (docs.deepgram.com, create-additional-api-keys, read 2026-09-21).
        apiKeyUrl = "https://console.deepgram.com/signup?jump=keys",
        defaultTranscriptionModel = "nova-3",
        curatedTranscriptionModels = listOf("nova-3", "nova-2"),
        // Realtime (#128): wss /v1/listen?encoding=linear16&sample_rate=16000&interim_results=true.
        // The flux models (#291) are streaming-only and speak /v2/listen instead — offered here but not
        // as the default, since they cost more, cover one or ten languages, and end turns on their own.
        supportsRealtime = true,
        realtimeApi = RealtimeApi.DEEPGRAM,
        defaultRealtimeModel = "nova-3",
        curatedRealtimeModels = listOf("nova-3", "nova-2", "flux-general-en", "flux-general-multi"),
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
        // `/app/api-keys` was the old dashboard and now lands on a bare login. Same 200-for-anything
        // problem as Deepgram, so again their own words: AssemblyAI's support article on getting a key
        // names this URL (support.assemblyai.com, read 2026-09-21).
        apiKeyUrl = "https://www.assemblyai.com/dashboard/api-keys",
        defaultTranscriptionModel = "universal-3-pro",
        curatedTranscriptionModels = listOf("universal-3-pro", "universal-2"),
        // Realtime (#128): Universal-Streaming wss streaming.assemblyai.com/v3/ws (~300ms). Model ids
        // verified when the session is built (billed by connection-open duration).
        supportsRealtime = true,
        realtimeApi = RealtimeApi.ASSEMBLYAI,
        defaultRealtimeModel = "universal-streaming",
        curatedRealtimeModels = listOf("universal-streaming"),
    )

    /**
     * Azure Speech with Microsoft's own MAI-Transcribe models (issue #349).
     *
     * MAI-Transcribe was already reachable here through OpenRouter, and the reporter's question was
     * whether it could be reached directly. It can, and the direct route is not merely one hop shorter:
     * OpenRouter fronts the model with the plain OpenAI transcription endpoint, which has nowhere to
     * put MAI's own options, so `transcribeStyle` — the "clean transcript" the request was really
     * about — is unreachable there. See [TranscriptionApi.AZURE_FAST_TRANSCRIPTION].
     *
     * **The base URL is the user's own and there is no useful default.** An Azure Speech resource
     * answers on its own hostname, printed next to the key on the portal's *Keys and Endpoint* page, so
     * [baseUrl] is deliberately **empty** and the field stands open with the shape as its hint
     * ([allowsCustomBaseUrl]). A template pre-filled into the box would only look like an address that
     * had already been set — Ollama's localhost default is a working server, this would be a decoy.
     * MAI-Transcribe itself runs in six regions — `eastus`, `westus`, `westus2`, `northeurope`,
     * `southeastasia`, `centralindia` (read 2026-09-09) — and a resource anywhere else answers the
     * endpoint but not with this model, which is worth knowing before hunting for a typo.
     *
     * Model ids read from Microsoft's own page on 2026-09-09: `MAI-Transcribe-2` (60 languages,
     * code-switching, in public preview) and the older `MAI-Transcribe-1.5`. `MAI-Transcribe-1` was
     * deprecated on 2026-08-20 and is deliberately not offered.
     */
    val AZURE = ProviderPreset(
        id = "azure",
        // The service, not the model: the row is what you sign up for, and the model is a choice inside
        // it — the same way "OpenAI" does not read "OpenAI (gpt-transcribe)".
        displayName = "Azure Speech",
        baseUrl = "",
        capabilities = STT_ONLY,
        transcriptionApi = TranscriptionApi.AZURE_FAST_TRANSCRIPTION,
        // No live catalog, and the reason is not that Azure has no models endpoint — it has one, and the
        // resource key opens it: `GET {endpoint}/speechtotext/models/base?api-version=…`. It answers with
        // the wrong namespace. Those are the *custom speech* base models — one per locale, named
        // "en-US Base model", addressed by a GUID — and none of them is a value `enhancedMode.model`
        // accepts, which takes the literal string "MAI-Transcribe-2". Fetching it would fill the picker
        // with dozens of entries that every one of them fails on. The field stays a free text box, so a
        // future MAI-Transcribe-3 can be typed in without waiting for an app update (read 2026-09-09).
        supportsDynamicModels = false,
        // A resource has to exist before it has a key, so this is the page that creates one; the portal
        // is one click from there for anyone who already has one.
        apiKeyUrl = "https://portal.azure.com/#create/Microsoft.CognitiveServicesAIFoundry",
        allowsCustomBaseUrl = true,
        defaultTranscriptionModel = "MAI-Transcribe-2",
        curatedTranscriptionModels = listOf("MAI-Transcribe-2", "MAI-Transcribe-1.5"),
        // Two of Microsoft's pages describe the same endpoint differently, and this list follows the
        // wider one. The MAI page's prerequisites name WAV, MP3 and FLAC — the formats of its sample
        // file; the Fast Transcription page, which is the API MAI is invoked through, names WAV, MP3,
        // Opus/Ogg, FLAC, WMA, AAC, A-law and µ-law in WAV, AMR, WebM and Speex (both read 2026-09-09).
        // Neither names MP4/M4A, and it is here anyway for two reasons that outweigh the omission: it is
        // AAC in an MP4 container, which the wider list does name, and leaving it out would make every
        // dictation past the packing threshold transcode straight back to WAV after being packed. A
        // refusal costs one request and no dictation — FORMAT_NOT_SUPPORTED resends the untouched WAV
        // (#281). Unmeasured: nobody here has an Azure key to ask the endpoint with.
        acceptedAudioContainers = setOf(
            AudioContainer.WAV, AudioContainer.MP3, AudioContainer.FLAC, AudioContainer.OGG,
            AudioContainer.M4A, AudioContainer.AAC, AudioContainer.WEBM, AudioContainer.AMR,
        ),
        // Realtime is off, and not by omission. Azure streams MAI-Transcribe through the Voice Live API,
        // which is a different protocol on a different host and expects a Foundry deployment rather than
        // a Speech resource key — a feature of its own, not a flag on this one.
        supportsRealtime = false,
    )

    val XAI = ProviderPreset(
        id = "xai",
        displayName = "xAI (Grok)",
        baseUrl = "https://api.x.ai/v1/",
        capabilities = CHAT_ONLY,
        supportsDynamicModels = true,
        // Not the console root: the login carries `return_to`, so the deep link is still there once the
        // sign-in is done, and `default` is the team slug xAI's own quickstart uses (2026-09-21).
        apiKeyUrl = "https://console.x.ai/team/default/api-keys",
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
     * SiliconFlow (硅基流动) — the one transcription provider reachable from mainland China (issue #262).
     *
     * Everything else in this list is blocked or unreachable there without a VPN, which left users in
     * China with no dictation at all unless they found the on-device engine or typed a custom endpoint
     * in by hand. SiliconFlow hosts open models on domestic infrastructure and speaks plain OpenAI
     * multipart at `POST {baseUrl}audio/transcriptions`, so it needs nothing new in the upload path.
     * Rewording was already covered: DeepSeek above is domestically reachable too.
     *
     * `.cn` is the mainland domain and the reason this entry exists; `.com` serves the same API
     * internationally, so the base URL is left editable (#136) rather than forcing the wrong one.
     *
     * Model ids verified against SiliconFlow's own pages on 2026-08-17: the two ASR ids from the
     * transcription API reference, the chat ids from the DeepSeek model page. Their catalog is far
     * larger — the live `/models` list is merged on top of these.
     *
     * One thing that could not be verified without an account: their reference documents only `model`
     * and `file`, while [OpenAiCompatibleClient.buildMultipartTranscriptionRequest] also sends
     * `response_format`, and `language`/`prompt` when set. Servers normally ignore fields they do not
     * know. If this one rejects them instead, the symptom is a 400 on every dictation and the fix is to
     * drop the extras for this id — not a reason to guess at it now.
     */
    val SILICONFLOW = ProviderPreset(
        id = "siliconflow",
        displayName = "SiliconFlow",
        baseUrl = "https://api.siliconflow.cn/v1/",
        capabilities = CHAT_AND_STT,
        supportsDynamicModels = true,
        apiKeyUrl = "https://cloud.siliconflow.cn/account/ak",
        allowsCustomBaseUrl = true,
        defaultChatModel = "deepseek-ai/DeepSeek-V3.2",
        // SenseVoice is the better of the two for dictation: it covers Mandarin, Cantonese, English,
        // Japanese and Korean, where TeleSpeech is Mandarin and Chinese dialects only.
        defaultTranscriptionModel = "FunAudioLLM/SenseVoiceSmall",
        curatedChatModels = listOf(
            "deepseek-ai/DeepSeek-V3.2", "deepseek-ai/DeepSeek-V3.1", "deepseek-ai/DeepSeek-V3",
        ),
        curatedTranscriptionModels = listOf(
            "FunAudioLLM/SenseVoiceSmall", "TeleAI/TeleSpeechASR",
        ),
    )

    /**
     * Scaleway Generative APIs — a provider that is EU-hosted end to end (issue #423).
     *
     * Asked for as an everyday provider for someone who wants neither the audio nor the text to leave the
     * EU. Scaleway processes both in Paris as a GDPR data processor with zero data retention by default,
     * and says so in its own privacy terms rather than in marketing: it does not "collect, read, reuse, or
     * analyze" inputs or outputs, trains nothing on them, and keeps a request's content only when that
     * request breaks the service (a 500, say), for at most two weeks (data-privacy page, read 2026-09-25).
     *
     * Both halves are plain OpenAI on one host — multipart `audio/transcriptions` and `chat/completions` —
     * so this entry needs no wire format of its own. OVHcloud's AI Endpoints would cost the same and were
     * left for later on purpose: every preset is one more catalogue to re-check by hand.
     *
     * **A new account answers nothing until it has a payment method.** The key is valid from the start —
     * `/models` answers 200 — but every chat and transcription request comes back 429 with
     * `x-ratelimit-limit-requests: 0`, worded "You exceeded your current quota of requests per minute".
     * Measured 2026-09-25 with a fresh key; Scaleway's rate-limit page says base limits begin with a
     * validated payment method. So someone's first dictation reads as a rate limit rather than a missing
     * setup step, and the key page link is the way back to the console.
     *
     * Errors come in two shapes, depending on who refuses. The gateway (key, quota) answers flat —
     * `{"status":429,"error":"INSUFFICIENT QUOTA","message":"…"}` — and a wrong key is a plain 403. The
     * model server behind it answers in OpenAI's envelope, but with the status as a *number* in `code`,
     * which is what the client's error parser had to learn to read. Neither needs anything
     * Scaleway-specific beyond that.
     */
    val SCALEWAY = ProviderPreset(
        id = "scaleway",
        displayName = "Scaleway",
        baseUrl = "https://api.scaleway.ai/v1/",
        capabilities = CHAT_AND_STT,
        // The live list answers even without quota, and mixes chat, embedding and transcription models with
        // nothing to tell them apart (2026-09-25: 16 ids, `whisper-large-v3` the only transcription one).
        // The picker's name filter sorts them; `bge-multilingual-gemma2` is the embedding model it had to
        // be taught.
        supportsDynamicModels = true,
        // Scaleway's own docs link exactly this page for creating a key. Their console answers 200 for any
        // path at all, so a status code proves nothing here (2026-09-25).
        apiKeyUrl = "https://console.scaleway.com/iam/api-keys",
        // All three measured on 2026-09-25 with the app's own Fix Grammar prompt on a German sentence full
        // of mistakes; each returned the corrected sentence alone. mistral-small-3.2 is the default: the
        // fastest (0.6 s), the model Scaleway's own examples use and where it routes two retired models,
        // so the one least likely to vanish, and it does not reason, so a rewording pays for no hidden
        // thinking. llama-3.3-70b took 0.9 s; gpt-oss-120b 4.6 s, its thinking in a separate `reasoning`
        // field rather than in the text. None is on the deprecation list (supported-models page, read the
        // same day) — pixtral-12b answers today but retires on 2026-10-01, and is left to the live list.
        defaultChatModel = "mistral-small-3.2-24b-instruct-2506",
        curatedChatModels = listOf(
            "mistral-small-3.2-24b-instruct-2506", "llama-3.3-70b-instruct", "gpt-oss-120b",
        ),
        // The only transcription model left: voxtral-small-24b retired on 2026-08-01, and Scaleway routes
        // what was sent to it here. Ten seconds of German came back in 0.6 s, thirteen minutes in 34 s.
        // It reads every field the client sends: `prompt` measurably steers the transcript (a lowercase,
        // unpunctuated prompt turned the answer lowercase and unpunctuated), and `language` can be left
        // out for auto-detect.
        defaultTranscriptionModel = "whisper-large-v3",
        curatedTranscriptionModels = listOf("whisper-large-v3"),
        // The documented list for whisper-large-v3 — flac, m4a, mpeg, mp2, mp3, mp4, ogg, wav, webm — and
        // this time the endpoint agrees with it exactly. Asked on 2026-09-25 with one German sample in
        // every container the app knows: these six transcribed; aac and amr came back 400 "Invalid file
        // format", and so did the Ogg file when it was named `.opus` — the name decides here as it does at
        // OpenAI, which [audioUploadNameOf] already handles. Scaleway still calls the endpoint beta
        // ("support of the full feature set will be incremental"), so this is the list to re-measure first.
        acceptedAudioContainers = setOf(
            AudioContainer.FLAC, AudioContainer.M4A, AudioContainer.MP3,
            AudioContainer.OGG, AudioContainer.WAV, AudioContainer.WEBM,
        ),
        // Batch only: Scaleway has no streaming transcription.
        supportsRealtime = false,
        hostedInEu = true,
    )

    /**
     * OVHcloud AI Endpoints — the second EU-hosted provider of issue #423, after [SCALEWAY].
     *
     * Served from Gravelines, France, and in OVHcloud's own words "data is not stored or shared during or
     * after model use" (capabilities page, updated 2026-02-03). The reporter remembered one endpoint URL per
     * model and expected a different editor for it; that is out of date. One OpenAI-compatible base URL now
     * serves the whole catalog, chat and `audio/transcriptions` alike, so this is the same plain preset as
     * Scaleway's.
     *
     * **Everything here was asked of the endpoint without a key.** OVHcloud serves anonymous requests at 2
     * a minute per IP and per model, capped at 10 MB or 60 seconds of audio — far too little to dictate
     * with, which is why the app still asks for a key, but enough to measure the facts below on
     * 2026-09-25. A key lifts that to 400 requests a minute per project and model.
     *
     * A wrong key is refused, not quietly served as anonymous: `/models` answers 403 "Forbidden:
     * authentication failed", so the credentials step of the connection test can fail as it must. Gateway
     * errors are flat (`{"message":"…","request_id":"…"}`), the model server's are OpenAI's envelope; the
     * client reads both.
     */
    val OVHCLOUD = ProviderPreset(
        id = "ovhcloud",
        displayName = "OVHcloud",
        baseUrl = "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/",
        capabilities = CHAT_AND_STT,
        // The live list mixes chat, embedding, image, text-to-speech and safety-classifier models, with
        // nothing to tell them apart (2026-09-25: 24 ids). The picker's name filter learned the three it did
        // not know yet — bge-m3, stable-diffusion-xl and Qwen3Guard.
        supportsDynamicModels = true,
        // A key belongs to a Public Cloud project, so the page that creates one sits under a project id no
        // link can know. This is the deepest address OVHcloud's own documentation links for it, on the EU
        // control panel, which is where an account for these endpoints lives (their docs' link table, read
        // 2026-09-25).
        apiKeyUrl = "https://manager.eu.ovhcloud.com/#/public-cloud/pci/projects",
        // Both measured with the app's own Fix Grammar prompt; each returned the corrected sentence alone.
        // Mistral-Small-3.2 took 0.9 s, Llama-3.3-70B 4.8 s. gpt-oss-120b and Qwen3.8-27B are in the catalog
        // but only ever answered the anonymous probe with 429, so nothing is claimed for them and the live
        // list offers them unvouched. OVHcloud's ids are capitalised, unlike Scaleway's for the same models.
        defaultChatModel = "Mistral-Small-3.2-24B-Instruct-2506",
        curatedChatModels = listOf(
            "Mistral-Small-3.2-24B-Instruct-2506", "Meta-Llama-3_3-70B-Instruct",
        ),
        // whisper-large-v3 rather than the turbo as the default because the turbo is the one that got words
        // wrong: on the same German sample it wrote "Sprachkennung" in five containers out of five, where
        // the full model heard "Spracherkennung" — at 0.5 against 0.8 seconds for ten seconds of audio, a
        // difference nobody dictating waits for. Both read `prompt` (a lowercase, unpunctuated prompt made
        // the answer lowercase and unpunctuated) and detect the language when none is sent.
        defaultTranscriptionModel = "whisper-large-v3",
        curatedTranscriptionModels = listOf("whisper-large-v3", "whisper-large-v3-turbo"),
        // Documented: mp3, mp4, aac, m4a, wav, flac, ogg, opus, webm, mpeg, mpga (speech-to-text guide,
        // updated 2026-05-11). Asked on 2026-09-25 with one German sample per container: these six came back
        // correct, and so did an Ogg file named `.opus`, which OpenAI and Scaleway both refuse; amr is refused
        // outright. AAC is left out although it is documented and accepted: the turbo model turned the second
        // half of the ADTS sample into "und das Worttast 23" repeated, twice out of twice, while the full
        // model read the same bytes correctly. A container that transcribes into a loop on one of the two
        // models is not one to send untouched — a transcode costs a moment, a looped transcript a dictation.
        acceptedAudioContainers = setOf(
            AudioContainer.FLAC, AudioContainer.M4A, AudioContainer.MP3,
            AudioContainer.OGG, AudioContainer.WAV, AudioContainer.WEBM,
        ),
        // Batch only: the guide says streaming is "not yet supported" for transcription.
        supportsRealtime = false,
        hostedInEu = true,
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
        CLOUD, OPENAI, GROQ, OPENROUTER, GEMINI, ANTHROPIC, TOGETHER, DEEPINFRA, MISTRAL, SONIOX,
        ELEVENLABS, DEEPGRAM, ASSEMBLYAI, AZURE, XAI, DEEPSEEK, SILICONFLOW, SCALEWAY, OVHCLOUD,
        OLLAMA, LOCAL,
    )

    fun byId(id: String): ProviderPreset? = presets.firstOrNull { it.id == id }

    /**
     * The data-residency region [customBaseUrl] selects on [preset] (issue #403).
     *
     * A blank URL is the default region, because that is what every account stored before regions existed
     * and what a fresh one still stores. Null for a preset without regions, and for a URL that is none of
     * them — an account pointed somewhere by hand keeps going where it was pointed instead of being
     * quietly snapped onto a region it never chose.
     */
    fun regionOf(preset: ProviderPreset, customBaseUrl: String): ProviderRegion? {
        if (preset.regions.isEmpty()) return null
        if (customBaseUrl.isBlank()) return preset.regions.first()
        val wanted = customBaseUrl.trim().trimEnd('/')
        return preset.regions.firstOrNull { it.baseUrl.trimEnd('/').equals(wanted, ignoreCase = true) }
    }

    /**
     * The largest audio upload [providerId] accepts, or 0 when the provider does not document one.
     *
     * **0 means unknown, never unlimited.** Callers must treat it as "no figure to check against", not
     * as permission to send anything.
     *
     * Kept here rather than in the file-import screen (where it started) because the recording path
     * needs it too: 16 kHz mono WAV is 32 kB per second, so 25 MB is reached after 13 minutes
     * 39 seconds — which is what made a 14-minute dictation fail outright (#281).
     *
     * Figures read from each provider's own documentation on 2026-08-17:
     *  - OpenAI 25 MB.
     *  - Dictate Cloud 25 MB — but for its own reason, not a borrowed one. The figure used to be
     *    justified as "it proxies to OpenAI, so it inherits the ceiling", and that reasoning has
     *    stopped being true: the server now chooses between two providers per service and the app is
     *    deliberately never told which. What the number has to express is the **server's own
     *    promise**, `MAX_AUDIO_SECONDS` — ten minutes, which at 16 kHz mono WAV is about 19 MB. The
     *    25 MB stays because it is the ceiling the server would reject at, and rejecting is its job:
     *    it answers 413 with `CONTENT_SIZE_LIMIT`, which the app turns into an offer to keep the
     *    recording. A lower figure here would refuse locally what the server would have accepted.
     *  - Groq 25 MB on the free tier, 100 MB on the dev tier. The key does not say which tier it is
     *    on, so the lower one is assumed.
     *  - Gemini caps the whole request at 20 MB, and its audio travels **base64-inline**
     *    (`transcribeGeminiGenerateContent`), which inflates it by 4/3 — so the audio itself may not
     *    exceed about 15 MB. This is the one provider whose limit bites before the general packing
     *    threshold does. The Interactions path taken by the dedicated STT model (#292) documents the
     *    same 20 MB request ceiling and encodes the same way, so the figure covers both.
     *  - ElevenLabs 3 GB, Deepgram 2 GB, AssemblyAI 2.2 GB through the upload endpoint. Far beyond
     *    anything a keyboard produces; recorded so the number is not looked up twice.
     *  - SiliconFlow 50 MB (and one hour), from its transcription API reference.
     *  - Scaleway 25 MB for whisper-large-v3 on its serverless API (supported-models page, read
     *    2026-09-25), and asked the same day: the "MB" is a MiB. A 25,500,044-byte WAV transcribed; one
     *    of 26,214,444 bytes came back 400 "Maximum file size exceeded (…, value=25.000041961669922)". Its
     *    rate limit counts audio seconds, 1800 a minute once a payment method is on file; a single upload
     *    at this ceiling is about 820 seconds of 16 kHz WAV, so the size, not the rate, is met first.
     *  - OVHcloud 2048 MB or three hours per request with a key (speech-to-text guide, updated
     *    2026-05-11) — with Deepgram's, since a keyboard never gets near it. Unmeasured: the anonymous
     *    probe is capped at 10 MB, and the app never sends without a key.
     *  - OpenRouter 25 MB for a multipart upload, added 2026-09-04 while checking #321 — it was simply
     *    missing, which meant the file-import path never split anything for it and a shared recording
     *    went out whole to be refused. Its harder limit is not a size at all: a request gets about 60
     *    seconds of *processing* time, and that does not convert into an audio length honestly, because
     *    how much speech fits depends on the chosen model's speed rather than on anything we control.
     *  - Mistral and Soniox document a *duration* (3 hours, 300 minutes) but no size, so they stay 0.
     *    Do not translate a duration into bytes here: the encoding is not theirs to assume.
     *  - Azure 300 MB, from the MAI-Transcribe page's prerequisites (read 2026-09-09). The Fast
     *    Transcription API it travels allows 500 MB in general; the model's own page is the stricter
     *    of the two and is the one that governs a MAI request.
     */
    fun maxUploadBytes(providerId: String): Long = when (providerId) {
        "openai", "cloud", "groq", "openrouter", "scaleway" -> 25L * 1024 * 1024
        "gemini" -> 15L * 1024 * 1024
        "siliconflow" -> 50L * 1024 * 1024
        "azure" -> 300L * 1024 * 1024
        "elevenlabs" -> 3L * 1024 * 1024 * 1024
        "deepgram", "ovhcloud" -> 2L * 1024 * 1024 * 1024
        "assemblyai" -> 2252L * 1024 * 1024
        else -> 0L
    }

    /**
     * True for Google's dedicated speech-to-text models (`gemini-3.5-transcribe`, `-transcribe-live`,
     * issue #292) as opposed to the multimodal chat models that transcribe as a side job.
     *
     * The distinction is not cosmetic: a dedicated model is reached over the **Interactions API**
     * (`POST /v1beta/interactions`, its own request and response shape), not over `generateContent`,
     * and it cannot serve single-call multimodal (#130) at all, because that route posts audio to
     * `chat/completions`. Both callers ask this one question, so it is asked in one place.
     *
     * Matched by name rather than a list, so a later `gemini-4-transcribe` needs no app update — the
     * same bet the model picker's own heuristic already makes.
     */
    fun isGeminiTranscribeModel(model: String): Boolean =
        model.removePrefix("models/").lowercase().contains("transcribe")

    // A general "is this a speech-to-text model" check briefly lived here, so the single-call switch and
    // the rewording model could be decided from it. It is gone on purpose: guessing a model's abilities
    // from its name held for the ids we knew and misfired on the rest, and the wrong answer cost more
    // than the right one gave — it refused to fold the settings fields together and explained nothing
    // (#313). Which model can do what is now the user's call. The Gemini check above stays because it
    // answers a different question: which *endpoint* a request travels.

    /** Builds a preset for a user-defined OpenAI-compatible endpoint. */
    /**
     * [realtime] marks a server the user has told us speaks the OpenAI realtime protocol under
     * `/v1/realtime` (#249). Several self-hosted transcription servers do; there is no way to detect it
     * without connecting, so it is a switch in the editor rather than a guess.
     */
    fun custom(
        baseUrl: String,
        displayName: String = "Custom server",
        capabilities: ProviderCapabilities = CHAT_AND_STT,
        realtime: Boolean = false,
    ): ProviderPreset = ProviderPreset(
        id = "custom",
        displayName = displayName,
        baseUrl = baseUrl,
        capabilities = capabilities,
        supportsDynamicModels = true,
        isCustom = true,
        supportsRealtime = realtime,
        realtimeApi = if (realtime) RealtimeApi.OPENAI else null,
    )
}
