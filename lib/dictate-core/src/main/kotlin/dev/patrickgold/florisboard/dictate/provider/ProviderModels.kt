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

import java.io.File

/** What a provider can do. Most OpenAI-compatible endpoints do chat; only some do transcription. */
data class ProviderCapabilities(
    val chat: Boolean,
    val transcription: Boolean,
)

/** A selectable model, as offered by a provider (statically or via [LlmProvider.listModels]). */
data class ModelInfo(
    val id: String,
    val displayName: String = id,
    /**
     * Input modalities the model accepts (e.g. "text", "image", "audio"), when the provider's catalog
     * reports them (OpenRouter does, via `architecture.input_modalities`). Empty when unknown. A model
     * that accepts "audio" input is transcription-capable regardless of its name (issue #132).
     */
    val inputModalities: List<String> = emptyList(),
    /**
     * Output modalities the model produces, when reported. A dedicated speech-to-text model outputs
     * `transcription` (not `text`), which is how it's told apart from an audio-input chat model (#157).
     */
    val outputModalities: List<String> = emptyList(),
) {
    /**
     * True when the catalog says this model accepts audio input **as a chat model** (text output) → it
     * can transcribe via the single-call multimodal chat path (issue #130).
     */
    val acceptsAudioInput: Boolean get() = inputModalities.any { it.equals("audio", ignoreCase = true) }

    /**
     * True for a **dedicated** speech-to-text model (audio in → `transcription` out, e.g. OpenRouter's
     * MAI-Transcribe / Whisper / Parakeet). Served via the transcription endpoint, not the chat-audio
     * path — so it belongs in the transcription picker but must stay out of [acceptsAudioInput] (#157).
     */
    val isTranscriptionModel: Boolean
        get() = outputModalities.any { it.equals("transcription", ignoreCase = true) }
}

enum class ChatRole(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
}

data class ChatMessage(
    val role: ChatRole,
    val content: String,
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    /**
     * OpenAI-compatible `reasoning_effort` (e.g. `minimal`/`low`/`medium`/`high`) for reasoning models
     * (issue #141). Null omits the field entirely — the provider default is used and non-reasoning models
     * are unaffected.
     */
    val reasoningEffort: String? = null,
) {
    companion object {
        /** Convenience for the common single-user-message rewording case. */
        fun ofUser(model: String, prompt: String, reasoningEffort: String? = null) =
            ChatRequest(model, listOf(ChatMessage(ChatRole.USER, prompt)), reasoningEffort = reasoningEffort)
    }
}

data class TokenUsage(
    val promptTokens: Long,
    val completionTokens: Long,
)

data class ChatResult(
    val text: String,
    val usage: TokenUsage?,
)

data class TranscriptionRequest(
    val audioFile: File,
    val model: String,
    /** ISO language code, or null / "detect" for auto-detection. */
    val language: String? = null,
    /** Optional style/punctuation prompt to bias recognition. */
    val prompt: String? = null,
)

data class TranscriptionResult(
    val text: String,
)
