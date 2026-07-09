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

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Opens real-time transcription sessions (issue #128). Implements all seven [RealtimeApi] wire formats:
 * OpenAI and ElevenLabs/Gemini send audio as base64 in JSON; Deepgram/Soniox/AssemblyAI/Mistral stream
 * raw binary PCM. Mistral is experimental (its raw protocol is SDK-only/unverified). On any error the
 * caller falls back to batch transcription.
 *
 * The WebSocket client is long-lived (no read/call timeout, periodic ping), separate from the batch HTTP
 * client. Callers keep the batch [OpenAiCompatibleClient] for the fallback path.
 */
object RealtimeClient {

    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)   // fail a dead route fast instead of stalling the session
            .readTimeout(0, TimeUnit.SECONDS)   // long-lived stream
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            // Prefer IPv4: some hosts (e.g. Google's generativelanguage endpoint) return many IPv6
            // addresses, and on a network with broken/black-holed IPv6 OkHttp would try each one and
            // hit the connect timeout before falling back to IPv4 — an ~80s stall (issue #128). Trying
            // IPv4 first connects immediately there while still falling back to IPv6 when needed.
            .dns(Ipv4FirstDns)
            .build()
    }

    private object Ipv4FirstDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
    }

    /** The PCM sample rate a given realtime API expects (OpenAI wants 24 kHz; the rest 16 kHz). */
    fun sampleRateFor(api: RealtimeApi): Int = when (api) {
        RealtimeApi.OPENAI -> 24_000
        else -> 16_000
    }

    /**
     * Opens a session for [api] and starts connecting. [apiKey]/[model]/[language] identify the provider
     * call; [callbacks] deliver interim/final text (on background threads). The returned [RealtimeSession]
     * is fed PCM at [sampleRateFor] and finished/cancelled by the caller.
     */
    fun open(
        api: RealtimeApi,
        apiKey: String,
        model: String,
        language: String?,
        callbacks: RealtimeCallbacks,
    ): RealtimeSession = when (api) {
        RealtimeApi.OPENAI -> OpenAiRealtimeSession(wsClient, apiKey, model, language, callbacks).also { it.connect() }
        RealtimeApi.DEEPGRAM -> DeepgramRealtimeSession(wsClient, apiKey, model, language, callbacks).also { it.connect() }
        RealtimeApi.SONIOX -> SonioxRealtimeSession(wsClient, apiKey, model, language, callbacks).also { it.connect() }
        RealtimeApi.ASSEMBLYAI -> AssemblyAiRealtimeSession(wsClient, apiKey, model, language, callbacks).also { it.connect() }
        RealtimeApi.ELEVENLABS -> ElevenLabsRealtimeSession(wsClient, apiKey, model, language, callbacks).also { it.connect() }
        RealtimeApi.GEMINI -> GeminiRealtimeSession(wsClient, apiKey, model, language, callbacks).also { it.connect() }
        RealtimeApi.MISTRAL_VOXTRAL -> MistralRealtimeSession(wsClient, apiKey, model, language, callbacks).also { it.connect() }
    }
}

private fun base64AudioJson(prefix: String, pcm16: ByteArray, len: Int, suffix: String): String =
    prefix + Base64.encodeToString(pcm16, 0, len, Base64.NO_WRAP) + suffix

/**
 * OpenAI realtime transcription over `wss://api.openai.com/v1/realtime?intent=transcription`. Sends a
 * `session.update` transcription config on open, streams 24 kHz mono PCM16 as base64
 * `input_audio_buffer.append`, and turns `...input_audio_transcription.delta`/`.completed` events into
 * [RealtimeCallbacks.onPartial]/[onFinalSegment]. Model `gpt-realtime-whisper` streams deltas; the
 * `-transcribe` models only emit the final.
 */
private class OpenAiRealtimeSession(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val model: String,
    private val language: String?,
    private val callbacks: RealtimeCallbacks,
) : RealtimeSession {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var ws: WebSocket? = null
    private val partial = StringBuilder()
    @Volatile private var committing = false
    @Volatile private var done = false

    private companion object {
        const val URL = "wss://api.openai.com/v1/realtime?intent=transcription"
        const val AUDIO_APPEND_PREFIX = "{\"type\":\"input_audio_buffer.append\",\"audio\":\""
        const val AUDIO_APPEND_SUFFIX = "\"}"
    }

    fun connect() {
        // GA interface (the OpenAI-Beta: realtime=v1 header would force the retired beta shape →
        // "beta_api_shape_disabled"). Session type ("transcription") distinguishes the session in GA.
        val request = Request.Builder()
            .url(URL)
            .header("Authorization", "Bearer $apiKey")
            .build()
        ws = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(sessionUpdate())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (obj["type"]?.jsonPrimitive?.content) {
                "conversation.item.input_audio_transcription.delta" -> {
                    val delta = obj["delta"]?.jsonPrimitive?.content ?: return
                    partial.append(delta)
                    callbacks.onPartial(partial.toString())
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = obj["transcript"]?.jsonPrimitive?.content ?: partial.toString()
                    partial.setLength(0)
                    callbacks.onFinalSegment(transcript)
                    // After we asked to commit, the completed event is our cue that the final is in.
                    if (committing) finishClosed(webSocket)
                }
                "error" -> emitError(RuntimeException("OpenAI realtime error: ${obj["error"] ?: obj}"))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Concise error only (no transcript/body content): the engine falls back to batch on error.
            android.util.Log.w("DictateRT", "realtime WS failed (http=${response?.code}): ${t.message}")
            emitError(t)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finishClosed(webSocket)
    }

    private fun sessionUpdate(): String = buildJsonObject {
        put("type", "session.update")
        put("session", buildJsonObject {
            put("type", "transcription")
            put("audio", buildJsonObject {
                put("input", buildJsonObject {
                    put("format", buildJsonObject {
                        put("type", "audio/pcm")
                        put("rate", 24_000)
                    })
                    put("transcription", buildJsonObject {
                        put("model", model)
                        if (!language.isNullOrBlank() && language != "detect") put("language", language)
                    })
                })
            })
        })
    }.toString()

    override fun sendAudio(pcm16: ByteArray, len: Int) {
        val socket = ws ?: return
        val msg = base64AudioJson(AUDIO_APPEND_PREFIX, pcm16, len, AUDIO_APPEND_SUFFIX)
        runCatching { socket.send(msg) }
    }

    override fun finish() {
        val socket = ws ?: return finishClosed(null)
        committing = true
        // Flush the buffered audio; the server responds with the final `completed`, then we close.
        runCatching { socket.send("""{"type":"input_audio_buffer.commit"}""") }
    }

    override fun cancel() {
        done = true
        runCatching { ws?.close(1000, null) }
        callbacks.onClosed()
    }

    private fun emitError(t: Throwable) {
        if (done) return
        done = true
        runCatching { ws?.cancel() }
        callbacks.onError(t)
        callbacks.onClosed()
    }

    private fun finishClosed(webSocket: WebSocket?) {
        if (done) return
        done = true
        runCatching { (webSocket ?: ws)?.close(1000, null) }
        callbacks.onClosed()
    }
}

/**
 * Soniox streaming transcription over `wss://stt-rt.soniox.com/transcribe-websocket`. The API key and
 * config go in the first JSON message (no auth header); 16 kHz mono PCM16 is streamed as raw binary
 * frames. Each `tokens[]` message carries `is_final` tokens (permanent) plus a replaceable tail; the full
 * text (permanent + tail) is emitted as the partial, and the permanent text as the final on flush. An
 * empty frame flushes; the server replies `finished:true` and closes.
 */
private class SonioxRealtimeSession(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val model: String,
    private val language: String?,
    private val callbacks: RealtimeCallbacks,
) : RealtimeSession {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var ws: WebSocket? = null
    private val permanent = StringBuilder()
    @Volatile private var started = false   // config text must be the first frame — gate audio until sent
    @Volatile private var done = false

    private companion object { const val URL = "wss://stt-rt.soniox.com/transcribe-websocket" }

    fun connect() {
        ws = client.newWebSocket(Request.Builder().url(URL).build(), listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(config())
            started = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            obj["tokens"]?.jsonArray?.let { tokens ->
                val tail = StringBuilder()
                for (tk in tokens) {
                    val o = tk.jsonObject
                    val txt = o["text"]?.jsonPrimitive?.content ?: continue
                    if (o["is_final"]?.jsonPrimitive?.booleanOrNull == true) permanent.append(txt) else tail.append(txt)
                }
                callbacks.onPartial(permanent.toString() + tail)
            }
            if (obj["finished"]?.jsonPrimitive?.booleanOrNull == true) finalizeAndClose(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            android.util.Log.w("DictateRT", "soniox realtime WS failed (http=${response?.code}): ${t.message}")
            emitError(t)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finalizeAndClose(webSocket)
    }

    private fun config(): String = buildJsonObject {
        put("api_key", apiKey)
        put("model", model)
        put("audio_format", "pcm_s16le")
        put("sample_rate", 16_000)
        put("num_channels", 1)
        if (!language.isNullOrBlank() && language != "detect") {
            put("language_hints", buildJsonArray { add(language) })
        }
    }.toString()

    override fun sendAudio(pcm16: ByteArray, len: Int) {
        if (!started) return   // drop audio captured before the config frame was sent
        runCatching { ws?.send(pcm16.toByteString(0, len)) }
    }

    override fun finish() {
        val socket = ws ?: return finalizeAndClose(null)
        runCatching { socket.send("") }   // empty frame → flush + finished
    }

    override fun cancel() {
        done = true
        runCatching { ws?.close(1000, null) }
        callbacks.onClosed()
    }

    private fun emitError(t: Throwable) {
        if (done) return
        done = true
        runCatching { ws?.cancel() }
        callbacks.onError(t)
        callbacks.onClosed()
    }

    private fun finalizeAndClose(webSocket: WebSocket?) {
        if (done) return
        done = true
        if (permanent.isNotEmpty()) callbacks.onFinalSegment(permanent.toString())
        runCatching { (webSocket ?: ws)?.close(1000, null) }
        callbacks.onClosed()
    }
}

/**
 * AssemblyAI Universal Streaming over `wss://streaming.assemblyai.com/v3/ws` (config in query params, raw
 * key in the `Authorization` header — no "Bearer"). Streams 16 kHz mono PCM16 as raw binary frames. `Turn`
 * messages carry the current turn text in `transcript`; `end_of_turn` finalizes it. `format_turns` is off
 * (our rewording stage formats), so each turn produces one clean final. `finish()` sends `Terminate`.
 */
private class AssemblyAiRealtimeSession(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val model: String,
    private val language: String?,
    private val callbacks: RealtimeCallbacks,
) : RealtimeSession {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var ws: WebSocket? = null
    @Volatile private var done = false

    private companion object {
        const val URL = "wss://streaming.assemblyai.com/v3/ws?sample_rate=16000&encoding=pcm_s16le"
    }

    fun connect() {
        // Universal Streaming is selected by the /v3/ws endpoint itself; the raw key goes in Authorization.
        val request = Request.Builder().url(URL).header("Authorization", apiKey).build()
        ws = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (obj["type"]?.jsonPrimitive?.content) {
                "Turn" -> {
                    val transcript = obj["transcript"]?.jsonPrimitive?.content.orEmpty()
                    if (transcript.isBlank()) return
                    if (obj["end_of_turn"]?.jsonPrimitive?.booleanOrNull == true) {
                        callbacks.onFinalSegment(transcript)
                    } else {
                        callbacks.onPartial(transcript)
                    }
                }
                "Termination" -> finishClosed(webSocket)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            android.util.Log.w("DictateRT", "assemblyai realtime WS failed (http=${response?.code}): ${t.message}")
            emitError(t)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finishClosed(webSocket)
    }

    override fun sendAudio(pcm16: ByteArray, len: Int) {
        runCatching { ws?.send(pcm16.toByteString(0, len)) }
    }

    override fun finish() {
        val socket = ws ?: return finishClosed(null)
        runCatching { socket.send("""{"type":"Terminate"}""") }
    }

    override fun cancel() {
        done = true
        runCatching { ws?.close(1000, null) }
        callbacks.onClosed()
    }

    private fun emitError(t: Throwable) {
        if (done) return
        done = true
        runCatching { ws?.cancel() }
        callbacks.onError(t)
        callbacks.onClosed()
    }

    private fun finishClosed(webSocket: WebSocket?) {
        if (done) return
        done = true
        runCatching { (webSocket ?: ws)?.close(1000, null) }
        callbacks.onClosed()
    }
}

/**
 * ElevenLabs Scribe realtime over `wss://api.elevenlabs.io/v1/speech-to-text/realtime` (config in query
 * params, `xi-api-key` header). 16 kHz mono PCM16 is sent base64 in `input_audio_chunk` messages;
 * `partial_transcript`/`committed_transcript` messages carry the text in `text`. `finish()` sends a final
 * chunk with `commit:true` so the server flushes the last committed transcript.
 */
private class ElevenLabsRealtimeSession(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val model: String,
    private val language: String?,
    private val callbacks: RealtimeCallbacks,
) : RealtimeSession {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var ws: WebSocket? = null
    @Volatile private var done = false

    private companion object {
        const val AUDIO_CHUNK_PREFIX =
            "{\"message_type\":\"input_audio_chunk\",\"audio_base_64\":\""
        const val AUDIO_CHUNK_SUFFIX = "\",\"commit\":false,\"sample_rate\":16000}"
        const val FINAL_CHUNK = "{\"message_type\":\"input_audio_chunk\",\"audio_base_64\":\"\",\"commit\":true}"
    }

    fun connect() {
        val lang = if (!language.isNullOrBlank() && language != "detect") "&language_code=$language" else ""
        val url = "wss://api.elevenlabs.io/v1/speech-to-text/realtime" +
            "?model_id=$model&audio_format=pcm_16000&commit_strategy=vad$lang"
        val request = Request.Builder().url(url).header("xi-api-key", apiKey).build()
        ws = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val body = obj["text"]?.jsonPrimitive?.content.orEmpty()
            when (obj["message_type"]?.jsonPrimitive?.content) {
                "partial_transcript" -> if (body.isNotBlank()) callbacks.onPartial(body)
                "committed_transcript", "committed_transcript_with_timestamps" ->
                    if (body.isNotBlank()) callbacks.onFinalSegment(body)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            android.util.Log.w("DictateRT", "elevenlabs realtime WS failed (http=${response?.code}): ${t.message}")
            emitError(t)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finishClosed(webSocket)
    }

    override fun sendAudio(pcm16: ByteArray, len: Int) {
        val msg = base64AudioJson(AUDIO_CHUNK_PREFIX, pcm16, len, AUDIO_CHUNK_SUFFIX)
        runCatching { ws?.send(msg) }
    }

    override fun finish() {
        val socket = ws ?: return finishClosed(null)
        runCatching { socket.send(FINAL_CHUNK) }
    }

    override fun cancel() {
        done = true
        runCatching { ws?.close(1000, null) }
        callbacks.onClosed()
    }

    private fun emitError(t: Throwable) {
        if (done) return
        done = true
        runCatching { ws?.cancel() }
        callbacks.onError(t)
        callbacks.onClosed()
    }

    private fun finishClosed(webSocket: WebSocket?) {
        if (done) return
        done = true
        runCatching { (webSocket ?: ws)?.close(1000, null) }
        callbacks.onClosed()
    }
}

/**
 * Google Gemini Live over the BidiGenerateContent WebSocket (`?key=` auth). A `setup` message enables
 * input-audio transcription (TEXT response modality); 16 kHz mono PCM16 is sent base64 as `realtimeInput`.
 * Input transcript chunks arrive in `serverContent.inputTranscription.text` and are concatenated. `finish()`
 * sends `audioStreamEnd`; the last chunks flush and `turnComplete`/`generationComplete` closes the session.
 */
private class GeminiRealtimeSession(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val model: String,
    private val language: String?,
    private val callbacks: RealtimeCallbacks,
) : RealtimeSession {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var ws: WebSocket? = null
    private val transcript = StringBuilder()
    @Volatile private var started = false    // gate audio until the server acks setup (setupComplete)
    @Volatile private var finishing = false
    @Volatile private var done = false

    private companion object {
        const val AUDIO_PREFIX = "{\"realtimeInput\":{\"audio\":{\"data\":\""
        const val AUDIO_SUFFIX = "\",\"mimeType\":\"audio/pcm;rate=16000\"}}}"
    }

    fun connect() {
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(setup())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)
        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) = handle(webSocket, bytes.utf8())

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            android.util.Log.w("DictateRT", "gemini realtime WS failed (http=${response?.code}): ${t.message}")
            emitError(t)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finalizeAndClose(webSocket)
    }

    private fun handle(webSocket: WebSocket, text: String) {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        if (obj.containsKey("setupComplete")) started = true   // now safe to stream audio
        val server = obj["serverContent"]?.jsonObject
        server?.get("inputTranscription")?.jsonObject?.get("text")?.jsonPrimitive?.content?.let { chunk ->
            if (chunk.isNotEmpty()) {
                transcript.append(chunk)
                callbacks.onPartial(transcript.toString())
            }
        }
        val ended = server?.get("turnComplete")?.jsonPrimitive?.booleanOrNull == true ||
            server?.get("generationComplete")?.jsonPrimitive?.booleanOrNull == true
        if (ended && finishing) finalizeAndClose(webSocket)
    }

    private fun setup(): String = buildJsonObject {
        putJsonObject("setup") {
            put("model", "models/$model")
            putJsonObject("generationConfig") {
                put("responseModalities", buildJsonArray { add("TEXT") })
            }
            putJsonObject("inputAudioTranscription") { }
        }
    }.toString()

    override fun sendAudio(pcm16: ByteArray, len: Int) {
        if (!started) return   // wait for setupComplete before streaming audio (Gemini Live requirement)
        val msg = base64AudioJson(AUDIO_PREFIX, pcm16, len, AUDIO_SUFFIX)
        runCatching { ws?.send(msg) }
    }

    override fun finish() {
        val socket = ws ?: return finalizeAndClose(null)
        finishing = true
        runCatching { socket.send("""{"realtimeInput":{"audioStreamEnd":true}}""") }
    }

    override fun cancel() {
        done = true
        runCatching { ws?.close(1000, null) }
        callbacks.onClosed()
    }

    private fun emitError(t: Throwable) {
        if (done) return
        done = true
        runCatching { ws?.cancel() }
        callbacks.onError(t)
        callbacks.onClosed()
    }

    private fun finalizeAndClose(webSocket: WebSocket?) {
        if (done) return
        done = true
        if (transcript.isNotEmpty()) callbacks.onFinalSegment(transcript.toString())
        runCatching { (webSocket ?: ws)?.close(1000, null) }
        callbacks.onClosed()
    }
}

/**
 * Mistral Voxtral realtime (`voxtral-mini-transcribe-realtime-2602`). EXPERIMENTAL/UNVERIFIED: Mistral
 * only documents this via its SDK, so the raw WebSocket path, handshake and event field names here are a
 * best-effort reconstruction and may not match the live wire format — a mismatch simply closes the socket
 * and the engine falls back to batch transcription. Assumes `Authorization: Bearer` auth, a JSON config
 * with the audio format, binary PCM frames, and `*TextDelta`/`*Done`-style events carrying `text`.
 */
private class MistralRealtimeSession(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val model: String,
    private val language: String?,
    private val callbacks: RealtimeCallbacks,
) : RealtimeSession {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var ws: WebSocket? = null
    private val transcript = StringBuilder()
    @Volatile private var done = false

    private companion object { const val URL = "wss://api.mistral.ai/v1/audio/transcriptions/realtime" }

    fun connect() {
        val request = Request.Builder().url(URL).header("Authorization", "Bearer $apiKey").build()
        ws = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(config())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val type = obj["type"]?.jsonPrimitive?.content.orEmpty()
            when {
                type.contains("TextDelta", ignoreCase = true) || type.contains("delta", ignoreCase = true) -> {
                    val chunk = obj["text"]?.jsonPrimitive?.content
                        ?: obj["delta"]?.jsonPrimitive?.content ?: return
                    transcript.append(chunk)
                    callbacks.onPartial(transcript.toString())
                }
                type.contains("Done", ignoreCase = true) || type.contains("completed", ignoreCase = true) ->
                    finalizeAndClose(webSocket)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            android.util.Log.w("DictateRT", "mistral realtime WS failed (http=${response?.code}): ${t.message}")
            emitError(t)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finalizeAndClose(webSocket)
    }

    private fun config(): String = buildJsonObject {
        put("type", "transcription_session.update")
        put("model", model)
        put("encoding", "pcm_s16le")
        put("sample_rate", 16_000)
        if (!language.isNullOrBlank() && language != "detect") put("language", language)
    }.toString()

    override fun sendAudio(pcm16: ByteArray, len: Int) {
        runCatching { ws?.send(pcm16.toByteString(0, len)) }
    }

    override fun finish() {
        val socket = ws ?: return finalizeAndClose(null)
        runCatching { socket.send("""{"type":"transcription_session.close"}""") }
    }

    override fun cancel() {
        done = true
        runCatching { ws?.close(1000, null) }
        callbacks.onClosed()
    }

    private fun emitError(t: Throwable) {
        if (done) return
        done = true
        runCatching { ws?.cancel() }
        callbacks.onError(t)
        callbacks.onClosed()
    }

    private fun finalizeAndClose(webSocket: WebSocket?) {
        if (done) return
        done = true
        if (transcript.isNotEmpty()) callbacks.onFinalSegment(transcript.toString())
        runCatching { (webSocket ?: ws)?.close(1000, null) }
        callbacks.onClosed()
    }
}

/**
 * Deepgram streaming transcription over `wss://api.deepgram.com/v1/listen`. Streams raw 16 kHz mono PCM16
 * as binary WebSocket frames and parses `Results` messages: each interim revises the current segment
 * ([RealtimeCallbacks.onPartial]), `is_final` finalizes it ([onFinalSegment]). `finish()` sends
 * `CloseStream` so the server flushes the last segment before closing.
 */
private class DeepgramRealtimeSession(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val model: String,
    private val language: String?,
    private val callbacks: RealtimeCallbacks,
) : RealtimeSession {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var ws: WebSocket? = null
    @Volatile private var done = false

    fun connect() {
        val lang = if (!language.isNullOrBlank() && language != "detect") "&language=$language" else ""
        val url = "wss://api.deepgram.com/v1/listen?model=$model" +
            "&encoding=linear16&sample_rate=16000&channels=1&interim_results=true&punctuate=true$lang"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token $apiKey")
            .build()
        ws = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (obj["type"]?.jsonPrimitive?.content) {
                "Results" -> {
                    val transcript = obj["channel"]?.jsonObject
                        ?.get("alternatives")?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("transcript")?.jsonPrimitive?.content.orEmpty()
                    if (transcript.isBlank()) return
                    val isFinal = obj["is_final"]?.jsonPrimitive?.booleanOrNull ?: false
                    if (isFinal) callbacks.onFinalSegment(transcript) else callbacks.onPartial(transcript)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            android.util.Log.w("DictateRT", "deepgram realtime WS failed (http=${response?.code}): ${t.message}")
            emitError(t)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finishClosed(webSocket)
    }

    override fun sendAudio(pcm16: ByteArray, len: Int) {
        runCatching { ws?.send(pcm16.toByteString(0, len)) }
    }

    override fun finish() {
        val socket = ws ?: return finishClosed(null)
        // Ask Deepgram to flush the final segment; it then emits the last Results and closes.
        runCatching { socket.send("""{"type":"CloseStream"}""") }
    }

    override fun cancel() {
        done = true
        runCatching { ws?.close(1000, null) }
        callbacks.onClosed()
    }

    private fun emitError(t: Throwable) {
        if (done) return
        done = true
        runCatching { ws?.cancel() }
        callbacks.onError(t)
        callbacks.onClosed()
    }

    private fun finishClosed(webSocket: WebSocket?) {
        if (done) return
        done = true
        runCatching { (webSocket ?: ws)?.close(1000, null) }
        callbacks.onClosed()
    }
}
