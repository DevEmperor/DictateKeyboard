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

import java.time.LocalDate

/** What the app uses a model for. */
enum class ModelRole {
    /** Batch speech-to-text: a finished recording goes up, a transcript comes back. */
    TRANSCRIPTION,

    /** Streaming speech-to-text over a socket while the user speaks. */
    REALTIME,

    /** Text in, text out: rewording, prompts, auto-formatting. */
    CHAT,

    /** A chat model that also takes audio, so one request transcribes and formats (single call, #130). */
    CHAT_AUDIO,
}

/**
 * How a model can be asked to think less or more, as the provider check measured it.
 *
 * [values] are the provider's own words, least thinking first, and only those the endpoint takes **and**
 * that change something measurable. Where a provider takes every word and maps it itself (OpenRouter,
 * DeepInfra, Together, DeepSeek, Scaleway's Qwen), the list is `none` when it measurably switches thinking
 * off, then `low`, `medium`, `high`: offering seven words that land on three settings would be a choice that
 * does nothing. [whenOmitted] is what the model does without the field, where a measurement showed it.
 */
data class ReasoningSpec(
    val values: List<String>,
    val whenOmitted: String? = null,
) {
    companion object {
        /** Every word in use for reasoning effort, least thinking first. */
        val SCALE = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
    }
}

/**
 * One model the app offers for a provider, with what has been measured about it.
 *
 * [reasoning] null means the app sends no reasoning field and offers no choice: either the model refuses the
 * field, or it took every value and none of them changed anything (Anthropic, 2026-11-04). [aliases] are the
 * ids the provider's own list uses for the same model. [verified] is the day the provider check last saw it
 * do what [roles] say; null means listed but never confirmed, and [note] says why.
 */
data class ModelSpec(
    val id: String,
    val roles: Set<ModelRole>,
    val reasoning: ReasoningSpec? = null,
    val aliases: Set<String> = emptySet(),
    val verified: LocalDate? = null,
    val retiresOn: LocalDate? = null,
    val note: String? = null,
)

/**
 * Per provider, the models the app offers and what is known about each of them: one place instead of
 * curated id lists, name heuristics and refusal parsing (plan: provider catalog, 2026-11-03).
 *
 * Filled from the provider check (private/testing/provider-check/) of 2026-11-03 and 2026-11-04: batch,
 * formats and streaming on the 3rd, the chat and reasoning matrix with a real rewording on the 4th. Nothing
 * reads it yet; `ModelCatalogTest` keeps it in step with the presets' curated lists until they are made from
 * it. Ollama, the on-device engine and servers of the user's own have no entries: their models are the user's.
 */
object ModelCatalog {

    private val NOV_3 = LocalDate.of(2026, 11, 3)
    private val NOV_4 = LocalDate.of(2026, 11, 4)

    private val T = setOf(ModelRole.TRANSCRIPTION)
    private val R = setOf(ModelRole.REALTIME)
    private val TR = setOf(ModelRole.TRANSCRIPTION, ModelRole.REALTIME)
    private val C = setOf(ModelRole.CHAT)
    private val CTA = setOf(ModelRole.CHAT, ModelRole.TRANSCRIPTION, ModelRole.CHAT_AUDIO)

    private fun effort(vararg values: String, whenOmitted: String? = null) = ReasoningSpec(values.toList(), whenOmitted)

    /** none switches thinking off; the provider maps the rest itself (see [ReasoningSpec]). */
    private val NONE_TO_HIGH = effort("none", "low", "medium", "high")
    private val GPT_OSS = effort("low", "medium", "high")

    val OPENAI = listOf(
        // Without the field Luna thinks like medium: 65 tokens against 62 at medium and 13 at none.
        ModelSpec("gpt-6-luna", C, effort("none", "low", "medium", "high", "xhigh", whenOmitted = "medium"), verified = NOV_4),
        ModelSpec("gpt-6.1-sol", C, effort("low", "medium", "high", "xhigh"), verified = NOV_4),
        ModelSpec("gpt-transcribe", TR, verified = NOV_3),
        ModelSpec("gpt-live-transcribe", R, verified = NOV_3),
        ModelSpec("gpt-realtime-whisper", R, verified = NOV_3),
    )

    val CLOUD = listOf(
        ModelSpec(
            "dictate-cloud", setOf(ModelRole.CHAT, ModelRole.TRANSCRIPTION),
            note = "Dictate's own server: the provider check does not cover it, and the server fixes the reasoning.",
        ),
    )

    val GROQ = listOf(
        ModelSpec("openai/gpt-oss-120b", C, GPT_OSS, verified = NOV_4),
        ModelSpec("openai/gpt-oss-20b", C, GPT_OSS, verified = NOV_4),
        // Without the field qwen does not think: 11 tokens, as at none; 144 at low.
        ModelSpec("qwen/qwen3.8-27b", C, effort("none", "low", "medium", "high", whenOmitted = "none"), verified = NOV_4),
        ModelSpec("whisper-large-v3-turbo", T, verified = NOV_3),
        ModelSpec("whisper-large-v3", T, verified = NOV_3),
    )

    val OPENROUTER = listOf(
        ModelSpec(
            "openai/gpt-6-luna", C, effort("none", "low", "medium", "high", "xhigh", whenOmitted = "medium"),
            verified = NOV_4, note = "OpenRouter takes every word and maps it; listed are Luna's own.",
        ),
        ModelSpec("openai/gpt-transcribe", T, verified = NOV_3, note = "OpenRouter takes the prompt and ignores it."),
        ModelSpec("microsoft/mai-transcribe-2", T, verified = NOV_3),
        ModelSpec("mistralai/voxtral-mini-transcribe", T, verified = NOV_3),
        ModelSpec(
            "deepgram/nova-3", T, verified = NOV_3,
            note = "Auto-detect on German synthetic speech gave only \"Hello.\" (2026-11-03); check with real speech.",
        ),
        ModelSpec("openai/whisper-large-v3-turbo", T, verified = NOV_3),
        ModelSpec("openai/whisper-large-v3", T, verified = NOV_3),
    )

    val GEMINI = listOf(
        // Gemini's chat endpoint reports no thinking tokens, so only acceptance is measured; its refusals name
        // thinking levels ("Thinking level MINIMAL is not supported"), so the field is understood.
        ModelSpec("gemini-3.6-flash", CTA, effort("none", "minimal", "low", "medium", "high"), verified = NOV_4),
        ModelSpec("gemini-3.5-flash", CTA, effort("none", "minimal", "low", "medium", "high"), verified = NOV_4),
        ModelSpec(
            "gemini-3.5-flash-lite", CTA, effort("minimal", "low", "medium", "high"), verified = NOV_4,
            note = "Refuses none with a bare 400 \"Request contains an invalid argument.\"",
        ),
        ModelSpec("gemini-3.1-flash-lite", C, effort("none", "minimal", "low", "medium", "high"), verified = NOV_4),
        ModelSpec("gemini-3.8-flash", C, effort("none", "low", "medium", "high"), verified = NOV_4),
        ModelSpec("gemini-3.7-flash", C, effort("none", "low", "medium", "high"), verified = NOV_4),
        ModelSpec("gemini-3.5-transcribe", T, verified = NOV_3),
        ModelSpec(
            "gemini-3.5-transcribe-live", R,
            note = "Synthetic speech gave nothing in English and 22 % of the words in German (2026-11-03); " +
                "check with real speech.",
        ),
    )

    // Every reasoning value is taken and none changes the tokens, so none is offered (2026-11-04).
    val ANTHROPIC = listOf(
        ModelSpec("claude-haiku-4-5-20251001", C, verified = NOV_4),
        ModelSpec("claude-sonnet-5", C, verified = NOV_4),
        ModelSpec("claude-opus-5", C, verified = NOV_4),
        ModelSpec("claude-haiku-5-5", C, verified = NOV_4),
        ModelSpec("claude-sonnet-5-5", C, verified = NOV_4),
    )

    val TOGETHER = listOf(
        ModelSpec("openai/gpt-oss-120b", C, GPT_OSS, verified = NOV_4),
        ModelSpec("deepseek-ai/DeepSeek-V4.1-Flash", C, effort("none", "low", "high", "xhigh", "max"), verified = NOV_4),
        ModelSpec("moonshotai/Kimi-K3", C, NONE_TO_HIGH, verified = NOV_4),
        ModelSpec(
            "Qwen/Qwen3.8-Flash", C,
            note = "Served by a third party: refused until the account allows third-party data sharing.",
        ),
    )

    val DEEPINFRA = listOf(
        ModelSpec("deepseek-ai/DeepSeek-V4.1-Flash", C, NONE_TO_HIGH.copy(whenOmitted = "none"), verified = NOV_4),
        ModelSpec("Qwen/Qwen3.6-27B", C, NONE_TO_HIGH, verified = NOV_4),
        ModelSpec(
            "zai-org/GLM-5.3", C, verified = NOV_4,
            note = "Takes every reasoning value, but none does not switch thinking off and the rest are erratic.",
        ),
        ModelSpec("meta-llama/Llama-4-Scout-17B-16E-Instruct", C, verified = NOV_4),
    )

    val MISTRAL = listOf(
        // Mistral names exactly none and high. At high the answer comes as parts (thinking + text).
        ModelSpec("mistral-small-latest", C, effort("none", "high", whenOmitted = "none"), verified = NOV_4),
        ModelSpec("mistral-medium-latest", C, effort("none", "high", whenOmitted = "none"), verified = NOV_4),
        ModelSpec("magistral-small-latest", C, effort("none", "high", whenOmitted = "none"), verified = NOV_4),
        ModelSpec("voxtral-mini-latest", T, verified = NOV_3),
        ModelSpec("voxtral-mini-transcribe-3", T, verified = NOV_3),
        ModelSpec("voxtral-small-transcribe-3", T, verified = NOV_3),
        ModelSpec(
            "voxtral-mini-transcribe-realtime-2602", R,
            note = "Streaming is switched off in the preset (#128), so the check does not open it.",
        ),
    )

    val SONIOX = listOf(
        ModelSpec("stt-async-v5", T, verified = NOV_3),
        ModelSpec("stt-async-v6", T, verified = NOV_3),
        ModelSpec("stt-rt-v5", R, verified = NOV_3),
    )

    val ELEVENLABS = listOf(
        ModelSpec("scribe_v2", T, verified = NOV_3),
        ModelSpec("scribe_v2_realtime", R, verified = NOV_3),
    )

    val DEEPGRAM = listOf(
        // Deepgram's list names them `nova-3-general`…; the short ids are what the app sends and what it takes.
        ModelSpec(
            "nova-3", TR, aliases = setOf("nova-3-general"), verified = NOV_3,
            note = "Auto-detect on German synthetic speech gave only \"Hello.\" (2026-11-03); check with real speech.",
        ),
        ModelSpec("nova-2", TR, aliases = setOf("nova-2-general"), verified = NOV_3),
        ModelSpec("flux-general-en", R, verified = NOV_3),
        ModelSpec(
            "flux-general-multi", R, verified = NOV_3,
            note = "German synthetic speech: 11 % of the words (2026-11-03); check with real speech.",
        ),
    )

    val ASSEMBLYAI = listOf(
        ModelSpec("universal-3-pro", T, verified = NOV_3),
        ModelSpec("universal-2", T, verified = NOV_3),
        ModelSpec("universal-3-6-pro", R, verified = NOV_3),
        ModelSpec(
            "universal-streaming-multilingual", R, verified = NOV_3,
            note = "German synthetic speech came back empty (2026-11-03); check with real speech.",
        ),
        ModelSpec("universal-streaming-english", R, verified = NOV_3),
    )

    val AZURE = listOf(
        ModelSpec("MAI-Transcribe-2", T, verified = NOV_3),
        ModelSpec("MAI-Transcribe-1.5", T, verified = NOV_3),
    )

    val XAI = listOf(
        ModelSpec("grok-voice-transcribe-2.0", TR, verified = NOV_3),
        // xAI reports no thinking tokens, so only acceptance is measured; it refuses the others by name.
        ModelSpec("grok-4.7", C, effort("minimal", "low", "medium", "high", "xhigh"), verified = NOV_4),
        ModelSpec("grok-4.3", C, effort("none", "minimal", "low", "medium", "high", "xhigh"), verified = NOV_4),
        ModelSpec("grok-4.20-0309-non-reasoning", C, verified = NOV_4),
    )

    val DEEPSEEK = listOf(
        ModelSpec("deepseek-flash", C, NONE_TO_HIGH, verified = NOV_4),
        ModelSpec("deepseek-v4-pro", C, NONE_TO_HIGH, verified = NOV_4),
    )

    val SILICONFLOW = listOf(
        // Checked on the international host (api.siliconflow.com); the preset speaks only the China host.
        ModelSpec("deepseek-ai/DeepSeek-V3.2", C, verified = NOV_3),
        ModelSpec("deepseek-ai/DeepSeek-V3.1", C, verified = NOV_3),
        ModelSpec("deepseek-ai/DeepSeek-V3", C, verified = NOV_3),
        ModelSpec(
            "FunAudioLLM/SenseVoiceSmall", T,
            note = "Disabled on the international host, which serves no speech-to-text at all; China host untested.",
        ),
        ModelSpec(
            "TeleAI/TeleSpeechASR", T,
            note = "Does not exist on the international host; China host untested.",
        ),
    )

    val SCALEWAY = listOf(
        ModelSpec("mistral-small-3.2-24b-instruct-2506", C, verified = NOV_4),
        ModelSpec("llama-3.3-70b-instruct", C, verified = NOV_4),
        ModelSpec("gpt-oss-120b", C, GPT_OSS, verified = NOV_4),
        ModelSpec("qwen3.8-27b", C, NONE_TO_HIGH, verified = NOV_4),
        ModelSpec("deepseek-v4.1-flash-1017", C, effort("none", "low", "high", "xhigh", "max"), verified = NOV_4),
        ModelSpec("whisper-large-v3", T, verified = NOV_3),
    )

    val OVHCLOUD = listOf(
        ModelSpec("Mistral-Small-3.2-24B-Instruct-2506", C, verified = NOV_4),
        ModelSpec("Meta-Llama-3_3-70B-Instruct", C, verified = NOV_4),
        ModelSpec("gpt-oss-120b", C, GPT_OSS, verified = NOV_4),
        // OVH's refusal names its own default: "Supported types are xhigh (default), medium, and low."
        ModelSpec("Qwen3.8-27B", C, effort("none", "low", "medium", "xhigh", whenOmitted = "xhigh"), verified = NOV_4),
        ModelSpec("whisper-large-v3", T, verified = NOV_3),
        ModelSpec("whisper-large-v3-turbo", T, verified = NOV_3),
    )

    private val byProvider: Map<String, List<ModelSpec>> = mapOf(
        ProviderRegistry.OPENAI.id to OPENAI,
        ProviderRegistry.CLOUD.id to CLOUD,
        ProviderRegistry.GROQ.id to GROQ,
        ProviderRegistry.OPENROUTER.id to OPENROUTER,
        ProviderRegistry.GEMINI.id to GEMINI,
        ProviderRegistry.ANTHROPIC.id to ANTHROPIC,
        ProviderRegistry.TOGETHER.id to TOGETHER,
        ProviderRegistry.DEEPINFRA.id to DEEPINFRA,
        ProviderRegistry.MISTRAL.id to MISTRAL,
        ProviderRegistry.SONIOX.id to SONIOX,
        ProviderRegistry.ELEVENLABS.id to ELEVENLABS,
        ProviderRegistry.DEEPGRAM.id to DEEPGRAM,
        ProviderRegistry.ASSEMBLYAI.id to ASSEMBLYAI,
        ProviderRegistry.AZURE.id to AZURE,
        ProviderRegistry.XAI.id to XAI,
        ProviderRegistry.DEEPSEEK.id to DEEPSEEK,
        ProviderRegistry.SILICONFLOW.id to SILICONFLOW,
        ProviderRegistry.SCALEWAY.id to SCALEWAY,
        ProviderRegistry.OVHCLOUD.id to OVHCLOUD,
    )

    /** The providers with an entry. */
    val providerIds: Set<String> get() = byProvider.keys

    /** The models the catalog knows for [providerId], in the order the app should offer them. */
    fun modelsOf(providerId: String): List<ModelSpec> = byProvider[providerId].orEmpty()

    /** [modelId] at [providerId], by its id or one of its aliases; null for a model the catalog does not know. */
    fun spec(providerId: String, modelId: String): ModelSpec? =
        modelsOf(providerId).firstOrNull { it.id == modelId || modelId in it.aliases }
}
