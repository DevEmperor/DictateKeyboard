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
 * The on-the-wire realtime protocol of a provider (issue #128). Selects which [RealtimeSession]
 * implementation drives the WebSocket. Two families: raw-binary-PCM after a config handshake
 * ([SONIOX]/[DEEPGRAM]/[ASSEMBLYAI]) and JSON events carrying base64 audio ([OPENAI]/[ELEVENLABS]/
 * [GEMINI]/[MISTRAL_VOXTRAL]). All use 16 kHz mono PCM16 except OpenAI (24 kHz — resampled on the fly).
 */
enum class RealtimeApi {
    OPENAI,
    SONIOX,
    DEEPGRAM,
    ASSEMBLYAI,
    ELEVENLABS,
    GEMINI,
    MISTRAL_VOXTRAL,
}

/**
 * Streaming (real-time) speech-to-text capability (issue #128), the live counterpart to the one-shot
 * [TranscriptionProvider]. Instead of uploading a finished file and awaiting one result, the caller opens
 * a session, pushes microphone PCM as it is captured, and receives interim + final transcript pieces over
 * a WebSocket. Every supporting provider (OpenAI realtime, Soniox, Deepgram, AssemblyAI, ElevenLabs,
 * Gemini Live, Mistral Voxtral) reduces to this same shape; only the wire framing differs per provider.
 *
 * A provider client may implement this alongside [TranscriptionProvider]/[LlmProvider]; the dictation
 * engine uses the streaming path only when the user has real-time mode on AND the selected provider
 * supports it, otherwise it falls back to the batch [TranscriptionProvider].
 */
interface RealtimeTranscriptionProvider {
    /**
     * Opens a live transcription session and starts connecting. [callbacks] are invoked as the session
     * progresses (on background threads — the caller must marshal to the UI thread itself). The returned
     * [RealtimeSession] is the handle to feed audio and end the session.
     */
    fun openSession(request: RealtimeRequest, callbacks: RealtimeCallbacks): RealtimeSession
}

/** Parameters for a realtime session. [sampleRate] is the rate of the PCM fed via [RealtimeSession.sendAudio]. */
data class RealtimeRequest(
    val model: String,
    /** ISO language code, or null/"detect" for auto-detect. */
    val language: String? = null,
    /** Sample rate (Hz) of the mono 16-bit little-endian PCM the caller will send. */
    val sampleRate: Int = 16_000,
)

/**
 * A live handle to a streaming session. Audio is pushed in as it is captured; [finish] signals
 * end-of-utterance so the provider flushes and emits the final transcript; [cancel] aborts without
 * waiting for a final result. All methods are safe to call from the audio/capture thread.
 */
interface RealtimeSession {
    /**
     * Push [len] bytes of mono 16-bit LE PCM (at [RealtimeRequest.sampleRate]) to the provider.
     * Implementations must consume or copy the buffer before returning; callers may reuse it.
     */
    fun sendAudio(pcm16: ByteArray, len: Int)

    /** Signal that speaking has ended: flush buffered audio and request the final transcript, then close. */
    fun finish()

    /** Abort the session immediately without awaiting a final result (e.g. the user cancelled). */
    fun cancel()
}

/**
 * Callbacks from a [RealtimeSession]. Invoked on background threads (WebSocket/OkHttp dispatcher); the
 * consumer is responsible for thread-hopping to the UI. [onPartial] carries provisional text that may
 * still change; [onFinalSegment] carries a stabilized chunk that should be appended to the running
 * transcript; [onError] means the stream failed (the engine falls back to batch); [onClosed] fires once
 * when the session ends (after the last final segment, or after an error/cancel).
 */
interface RealtimeCallbacks {
    /** Provisional transcript for the current, not-yet-finalized segment. Replaces the previous partial. */
    fun onPartial(text: String)

    /** A finalized transcript chunk. Append to the accumulated transcript (interim for it is now settled). */
    fun onFinalSegment(text: String)

    /** The stream failed (connection/auth/protocol). The engine should fall back to batch transcription. */
    fun onError(t: Throwable)

    /** The session has ended. Fires exactly once, after the final data or an error/cancel. */
    fun onClosed()
}
