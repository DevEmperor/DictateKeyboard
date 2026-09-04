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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
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
            // Race IPv6/IPv4 with OkHttp 5 Happy Eyeballs instead of forcing either address family.
            .fastFallback(true)
            .readTimeout(0, TimeUnit.SECONDS)   // long-lived stream
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
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
    /**
     * [baseUrl] redirects the OpenAI-shaped session at a server of the user's own (#249) — several
     * self-hosted transcription servers expose exactly this protocol under `/v1/realtime`. Null, and every
     * session goes to its vendor's fixed address as before.
     *
     * [expectedLanguages] is read only by the three providers whose language field is a list (OpenAI's
     * gpt-transcribe generation, Soniox, Gemini), and only when no language is pinned: it turns
     * auto-detect into detection among the languages the user dictates in (issue #99).
     */
    fun open(
        api: RealtimeApi,
        apiKey: String,
        model: String,
        language: String?,
        callbacks: RealtimeCallbacks,
        baseUrl: String? = null,
        expectedLanguages: List<String> = emptyList(),
    ): RealtimeSession = when (api) {
        RealtimeApi.OPENAI ->
            OpenAiRealtimeSession(wsClient, apiKey, model, language, callbacks, baseUrl, expectedLanguages)
                .also { it.connect() }
        RealtimeApi.DEEPGRAM -> DeepgramRealtimeSession(wsClient, apiKey, model, language, callbacks).also { it.connect() }
        RealtimeApi.SONIOX ->
            SonioxRealtimeSession(wsClient, apiKey, model, language, callbacks, expectedLanguages)
                .also { it.connect() }
        RealtimeApi.ASSEMBLYAI -> AssemblyAiRealtimeSession(wsClient, apiKey, model, language, callbacks).also { it.connect() }
        RealtimeApi.ELEVENLABS -> ElevenLabsRealtimeSession(wsClient, apiKey, model, language, callbacks).also { it.connect() }
        RealtimeApi.GEMINI ->
            GeminiRealtimeSession(wsClient, apiKey, model, language, callbacks, expectedLanguages)
                .also { it.connect() }
        RealtimeApi.MISTRAL_VOXTRAL -> MistralRealtimeSession(wsClient, apiKey, model, language, callbacks).also { it.connect() }
    }
}

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
    private val baseUrl: String? = null,
    expectedLanguages: List<String> = emptyList(),
) : RealtimeSession {

    private val languageHints = languageHintsOf(language, expectedLanguages)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var ws: WebSocket? = null
    private val partial = StringBuilder()
    private val audioGate = RealtimeAudioGate()
    @Volatile private var committing = false
    @Volatile private var done = false

    private companion object {
        const val URL = "wss://api.openai.com/v1/realtime?intent=transcription"

        /**
         * The websocket address of a self-hosted server, derived from the base URL its batch requests
         * already use (#249) — `http(s)://host/v1/` becomes `ws(s)://host/v1/realtime`. Cleartext is
         * deliberately allowed through: these live on a LAN, where https is the exception.
         */
        fun realtimeUrlFrom(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            val ws = when {
                trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.removeRange(0, 8)
                trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.removeRange(0, 7)
                trimmed.startsWith("ws://", ignoreCase = true) ||
                    trimmed.startsWith("wss://", ignoreCase = true) -> trimmed
                else -> "ws://$trimmed"
            }
            // Already pointed at the endpoint itself (or carrying its own query): take it as given.
            return if (ws.contains("/realtime")) ws else "$ws/realtime?intent=transcription"
        }
    }

    fun connect() {
        // GA interface (the OpenAI-Beta: realtime=v1 header would force the retired beta shape →
        // "beta_api_shape_disabled"). Session type ("transcription") distinguishes the session in GA.
        val request = Request.Builder()
            .url(baseUrl?.takeIf { it.isNotBlank() }?.let { realtimeUrlFrom(it) } ?: URL)
            .apply {
                // A server of one's own usually has no key at all, and sending an empty bearer makes some
                // of them reject the handshake outright.
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            }
            .build()
        ws = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(sessionUpdate())
            // OkHttp accepts send() calls while a socket is still connecting. Without this gate, mic
            // frames queued during the handshake precede this session.update and therefore arrive before
            // OpenAI knows the intended PCM format/model. Put the config first, then replay the beginning.
            audioGate.markReady(
                send = { pcm16, len -> sendAudioFrame(webSocket, pcm16, len) },
                finish = { sendCommit(webSocket) },
            )
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
                        // The gpt-transcribe generation takes `languages` as an array (it can hint
                        // several for code-switching audio); the older models take a single
                        // `language` string. Sending the wrong one is not an error, it is simply
                        // ignored — the user's language choice would quietly stop applying (#248).
                        if (usesLanguagesField(model)) {
                            if (languageHints.isNotEmpty()) {
                                put("languages", buildJsonArray { languageHints.forEach { add(JsonPrimitive(it)) } })
                            }
                        } else if (!language.isNullOrBlank() && language != "detect") {
                            put("language", language)
                        }
                    })
                    // No server-side turn detection: Dictate decides when a dictation ends and commits the
                    // buffer itself, so letting the server also cut turns would segment the same audio a
                    // second time on its own schedule (from #243).
                    put("turn_detection", JsonNull)
                })
            })
        })
    }.toString()

    override fun sendAudio(pcm16: ByteArray, len: Int) {
        audioGate.sendAudio(pcm16, len) { audio, length ->
            ws?.let { sendAudioFrame(it, audio, length) }
        }
    }

    private fun sendAudioFrame(socket: WebSocket, pcm16: ByteArray, len: Int) {
        val b64 = Base64.encodeToString(pcm16, 0, len, Base64.NO_WRAP)
        val msg = buildJsonObject {
            put("type", "input_audio_buffer.append")
            put("audio", b64)
        }.toString()
        runCatching { socket.send(msg) }
    }

    private fun sendCommit(socket: WebSocket) {
        runCatching { socket.send("""{"type":"input_audio_buffer.commit"}""") }
    }

    override fun finish() {
        committing = true
        // Flush the buffered audio; the server responds with the final `completed`, then we close.
        audioGate.finish {
            ws?.let { sendCommit(it) }
        }
    }

    override fun cancel() {
        audioGate.close()
        done = true
        runCatching { ws?.close(1000, null) }
        callbacks.onClosed()
    }

    private fun emitError(t: Throwable) {
        if (done) return
        done = true
        audioGate.close()
        runCatching { ws?.cancel() }
        callbacks.onError(t)
        callbacks.onClosed()
    }

    private fun finishClosed(webSocket: WebSocket?) {
        if (done) return
        done = true
        audioGate.close()
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
    expectedLanguages: List<String> = emptyList(),
) : RealtimeSession {

    private val languageHints = languageHintsOf(language, expectedLanguages)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var ws: WebSocket? = null
    private val permanent = StringBuilder()
    private val audioGate = RealtimeAudioGate()
    @Volatile private var done = false

    private companion object { const val URL = "wss://stt-rt.soniox.com/transcribe-websocket" }

    fun connect() {
        ws = client.newWebSocket(Request.Builder().url(URL).build(), listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(config())
            // Soniox requires the config text to be the first frame. Replay every microphone frame
            // captured while the socket connected immediately after it, instead of losing the start.
            audioGate.markReady(
                send = { pcm16, len -> runCatching { webSocket.send(pcm16.toByteString(0, len)) } },
                finish = { runCatching { webSocket.send("") } },
            )
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
        // Soniox hints a list, so auto-detect can still name the languages the user dictates in (#99).
        if (languageHints.isNotEmpty()) {
            put("language_hints", buildJsonArray { languageHints.forEach { add(it) } })
        }
    }.toString()

    override fun sendAudio(pcm16: ByteArray, len: Int) {
        audioGate.sendAudio(pcm16, len) { audio, length ->
            runCatching { ws?.send(audio.toByteString(0, length)) }
        }
    }

    override fun finish() {
        audioGate.finish {
            runCatching { ws?.send("") }   // empty frame → flush + finished
        }
    }

    override fun cancel() {
        audioGate.close()
        done = true
        runCatching { ws?.close(1000, null) }
        callbacks.onClosed()
    }

    private fun emitError(t: Throwable) {
        if (done) return
        done = true
        audioGate.close()
        runCatching { ws?.cancel() }
        callbacks.onError(t)
        callbacks.onClosed()
    }

    private fun finalizeAndClose(webSocket: WebSocket?) {
        if (done) return
        done = true
        audioGate.close()
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
    private val audioGate = RealtimeAudioGate()
    @Volatile private var done = false

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
                // The official lifecycle starts media after this acknowledgement. Preserve everything the
                // mic captured while connecting, then replay it once ElevenLabs confirms the session.
                "session_started" -> audioGate.markReady(
                    send = { pcm16, len -> sendAudioFrame(webSocket, pcm16, len) },
                    finish = { sendCommit(webSocket) },
                )
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
        audioGate.sendAudio(pcm16, len) { audio, length ->
            ws?.let { sendAudioFrame(it, audio, length) }
        }
    }

    private fun sendAudioFrame(socket: WebSocket, pcm16: ByteArray, len: Int) {
        val msg = buildJsonObject {
            put("message_type", "input_audio_chunk")
            put("audio_base_64", Base64.encodeToString(pcm16, 0, len, Base64.NO_WRAP))
            put("commit", false)
            put("sample_rate", 16_000)
        }.toString()
        runCatching { socket.send(msg) }
    }

    private fun sendCommit(socket: WebSocket) {
        val msg = buildJsonObject {
            put("message_type", "input_audio_chunk")
            put("audio_base_64", "")
            put("commit", true)
        }.toString()
        runCatching { socket.send(msg) }
    }

    override fun finish() {
        audioGate.finish {
            ws?.let { sendCommit(it) }
        }
    }

    override fun cancel() {
        audioGate.close()
        done = true
        runCatching { ws?.close(1000, null) }
        callbacks.onClosed()
    }

    private fun emitError(t: Throwable) {
        if (done) return
        done = true
        audioGate.close()
        runCatching { ws?.cancel() }
        callbacks.onError(t)
        callbacks.onClosed()
    }

    private fun finishClosed(webSocket: WebSocket?) {
        if (done) return
        done = true
        audioGate.close()
        runCatching { (webSocket ?: ws)?.close(1000, null) }
        callbacks.onClosed()
    }
}

/**
 * Google Gemini Live over the BidiGenerateContent WebSocket (`?key=` auth), driving the dedicated
 * streaming model `gemini-3.5-transcribe-live` (issue #292). A `setup` message enables input-audio
 * transcription (TEXT response modality); 16 kHz mono PCM16 is sent base64 as `realtimeInput`.
 * `finish()` sends `audioStreamEnd`; the last chunks flush and `turnComplete`/`generationComplete`
 * closes the session.
 *
 * The transcription model answers in two fields where the conversational live models had one:
 * `interimInputTranscription` is a speculative hypothesis that keeps changing while someone speaks,
 * `inputTranscription` is what stands once they pause. That maps exactly onto the
 * [RealtimeCallbacks] contract — interim replaces the partial, final appends a segment — so text
 * settles as it is spoken instead of all at once when the session ends.
 */
private class GeminiRealtimeSession(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val model: String,
    private val language: String?,
    private val callbacks: RealtimeCallbacks,
    expectedLanguages: List<String> = emptyList(),
) : RealtimeSession {

    private val languageHints = languageHintsOf(language, expectedLanguages)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var ws: WebSocket? = null
    private val transcript = StringBuilder()
    /** The latest speculative text, cleared by every final. What is left at close was heard but never settled. */
    @Volatile private var pendingInterim = ""
    private val audioGate = RealtimeAudioGate()
    @Volatile private var finishing = false
    @Volatile private var done = false

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
        if (obj.containsKey("setupComplete")) {
            // Gemini also forbids audio before setupComplete. Preserve and replay the mic startup rather
            // than dropping it while waiting for the server acknowledgement.
            audioGate.markReady(
                send = { pcm16, len -> sendAudioFrame(webSocket, pcm16, len) },
                finish = { sendAudioEnd(webSocket) },
            )
        }
        val server = obj["serverContent"]?.jsonObject
        // Speculative text: show it, never keep it. The next interim replaces this one outright.
        server?.get("interimInputTranscription")?.jsonObject?.get("text")?.jsonPrimitive?.content?.let { hypothesis ->
            if (hypothesis.isNotEmpty()) {
                pendingInterim = hypothesis
                callbacks.onPartial(hypothesis)
            }
        }
        server?.get("inputTranscription")?.jsonObject?.get("text")?.jsonPrimitive?.content?.let { chunk ->
            if (chunk.isNotEmpty()) emitFinal(chunk)
        }
        val ended = server?.get("turnComplete")?.jsonPrimitive?.booleanOrNull == true ||
            server?.get("generationComplete")?.jsonPrimitive?.booleanOrNull == true
        if (ended && finishing) finalizeAndClose(webSocket)
    }

    /**
     * Appends one finalized chunk, guarding against the one thing the protocol does not state: whether
     * `inputTranscription` carries just the settled segment or everything settled so far. If it repeats
     * what we already have, only the growth is passed on — otherwise every pause would duplicate the
     * whole dictation.
     */
    private fun emitFinal(chunk: String) {
        val seen = transcript.toString()
        val addition = if (seen.isNotEmpty() && chunk.startsWith(seen)) chunk.substring(seen.length) else chunk
        pendingInterim = ""
        if (addition.isEmpty()) return
        transcript.append(addition)
        callbacks.onFinalSegment(addition)
    }

    private fun setup(): String = buildJsonObject {
        putJsonObject("setup") {
            put("model", "models/$model")
            putJsonObject("generationConfig") {
                put("responseModalities", buildJsonArray { add("TEXT") })
            }
            putJsonObject("inputAudioTranscription") {
                // An empty list is Google's own way of asking for automatic detection, so the user's
                // chosen language reaches Gemini for the first time — it used to be accepted and dropped.
                // With none pinned the list carries the languages they dictate in, which is what Google
                // means by a list in the first place (#99).
                put("languageCodes", buildJsonArray { languageHints.forEach { add(it) } })
                // SMART drops "um"/"uh", folds spoken self-corrections into the sentence and punctuates.
                // VERBATIM would hand all of that to the rewording step instead — for a keyboard, clean
                // text on arrival is worth more than a faithful record of the stumbles.
                put("mode", "SMART")
            }
        }
    }.toString()

    override fun sendAudio(pcm16: ByteArray, len: Int) {
        audioGate.sendAudio(pcm16, len) { audio, length ->
            ws?.let { sendAudioFrame(it, audio, length) }
        }
    }

    private fun sendAudioFrame(socket: WebSocket, pcm16: ByteArray, len: Int) {
        val msg = buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("audio") {
                    put("data", Base64.encodeToString(pcm16, 0, len, Base64.NO_WRAP))
                    put("mimeType", "audio/pcm;rate=16000")
                }
            }
        }.toString()
        runCatching { socket.send(msg) }
    }

    private fun sendAudioEnd(socket: WebSocket) {
        runCatching { socket.send("""{"realtimeInput":{"audioStreamEnd":true}}""") }
    }

    override fun finish() {
        finishing = true
        audioGate.finish {
            ws?.let { sendAudioEnd(it) }
        }
    }

    override fun cancel() {
        audioGate.close()
        done = true
        runCatching { ws?.close(1000, null) }
        callbacks.onClosed()
    }

    private fun emitError(t: Throwable) {
        if (done) return
        done = true
        audioGate.close()
        runCatching { ws?.cancel() }
        callbacks.onError(t)
        callbacks.onClosed()
    }

    private fun finalizeAndClose(webSocket: WebSocket?) {
        if (done) return
        done = true
        audioGate.close()
        // Settled text was already handed over segment by segment, so nothing is repeated here. What can
        // still be outstanding is a hypothesis the server never got to confirm — the last words of a
        // dictation that ended on the closing socket. Keep them: heard-but-unconfirmed beats dropped.
        if (pendingInterim.isNotEmpty()) {
            callbacks.onFinalSegment(pendingInterim)
            pendingInterim = ""
        }
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
 * Deepgram streaming transcription. Streams raw 16 kHz mono PCM16 as binary WebSocket frames, and speaks
 * whichever of Deepgram's two wire versions the chosen model belongs to — the framing around them (`Token`
 * auth, binary audio, `CloseStream` to flush) is the same:
 *
 *  - **Nova/Whisper** on `wss://api.deepgram.com/v1/listen`, reporting `Results`: each interim revises the
 *    current segment ([RealtimeCallbacks.onPartial]), `is_final` settles it ([onFinalSegment]).
 *  - **Flux** on `/v2/listen`, reporting `TurnInfo` (issue #291). Flux is Deepgram's conversational model:
 *    it decides itself when a turn has ended instead of waiting out a silence timer, and every event
 *    carries the whole turn so far — so each one is a partial until `EndOfTurn` settles it.
 *
 * Text that is still unsettled when the socket closes is emitted as a final segment, so the tail of a
 * dictation survives a close that arrives before the last `is_final`/`EndOfTurn`.
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
    /** Latest partial, cleared when it is settled — emitted at close if nothing ever settled it. */
    @Volatile private var pending = ""
    @Volatile private var done = false

    /** The dictation language the user pinned, or null when they left auto-detection on. */
    private val pinnedLanguage = language?.takeIf { it.isNotBlank() && it != "detect" }

    fun connect() {
        val request = Request.Builder()
            .url(if (model.startsWith("flux")) fluxUrl() else listenUrl())
            .header("Authorization", "Token $apiKey")
            .build()
        ws = client.newWebSocket(request, listener)
    }

    private fun listenUrl(): String {
        val lang = pinnedLanguage?.let { "&language=$it" }.orEmpty()
        return "wss://api.deepgram.com/v1/listen?model=$model" +
            "&encoding=linear16&sample_rate=16000&channels=1&interim_results=true&punctuate=true$lang"
    }

    /**
     * Flux takes neither `interim_results` nor `punctuate` — the turn is its unit, and its transcript comes
     * formatted. Its language parameter is a *hint* that biases detection, which only the multilingual
     * model does at all; the single-language models reject the parameter, and no variant knows regions.
     */
    private fun fluxUrl(): String {
        val hint = pinnedLanguage
            ?.takeIf { model.endsWith("multi") }
            ?.let { "&language_hint=${it.substringBefore('-')}" }
            .orEmpty()
        return "wss://api.deepgram.com/v2/listen?model=$model&encoding=linear16&sample_rate=16000$hint"
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (obj["type"]?.jsonPrimitive?.content) {
                "Results" -> emit(
                    transcript = obj["channel"]?.jsonObject
                        ?.get("alternatives")?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("transcript")?.jsonPrimitive?.content.orEmpty(),
                    settled = obj["is_final"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
                "TurnInfo" -> emit(
                    transcript = obj["transcript"]?.jsonPrimitive?.content.orEmpty(),
                    settled = obj["event"]?.jsonPrimitive?.content == "EndOfTurn",
                )
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
        // Ask Deepgram to flush the last segment; it then emits it and closes.
        runCatching { socket.send("""{"type":"CloseStream"}""") }
    }

    override fun cancel() {
        done = true
        runCatching { ws?.close(1000, null) }
        callbacks.onClosed()
    }

    /** Hands one message's transcript to the right callback and tracks what is still unsettled. */
    private fun emit(transcript: String, settled: Boolean) {
        if (transcript.isBlank()) return
        if (settled) {
            pending = ""
            callbacks.onFinalSegment(transcript)
        } else {
            pending = transcript
            callbacks.onPartial(transcript)
        }
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
        pending.takeIf { it.isNotEmpty() }?.let { callbacks.onFinalSegment(it) }
        pending = ""
        runCatching { (webSocket ?: ws)?.close(1000, null) }
        callbacks.onClosed()
    }
}

/**
 * True for OpenAI's gpt-transcribe generation, which renamed the singular `language` field to a
 * `languages` array. Matched on the id prefix so later snapshots and variants are covered, while
 * gpt-realtime-whisper and the gpt-4o-*-transcribe models keep the old field (issue #248).
 *
 * Measured on the batch endpoint (2026-08-28): gpt-transcribe there answers an invalid code under
 * `language`, `languages` and `languages[]` alike, so it reads all three and this branch is a
 * courtesy rather than the thing that makes a language choice apply. What the array does earn is the
 * *list*: every entry is validated, so several languages genuinely arrive (issue #99), which no
 * singular field can express. Unverified for the realtime socket, where the field name may well
 * matter — hence the branch stays.
 *
 * **The prefix is deliberately not vendor-tolerant.** OpenRouter's id for the same model reads
 * `openai/gpt-transcribe` and therefore does NOT match, which is correct: OpenRouter's transcription
 * endpoint documents the singular `language` and nothing else, and an unread multipart field is
 * dropped in silence rather than refused — so "generalising" this to strip a `vendor/` prefix would
 * turn every language choice on OpenRouter into a no-op with no error to show for it. A test in
 * OpenAiCompatibleClientNetworkTest holds that line (issue #321).
 */
internal fun usesLanguagesField(model: String): Boolean {
    val id = model.lowercase()
    return id.startsWith("gpt-transcribe") || id.startsWith("gpt-live-transcribe")
}

/**
 * The codes for a provider field that takes a *list* of languages: the pinned language when the user
 * chose one — a choice stays a choice, however many the model could juggle — and otherwise the
 * languages they actually dictate in, which is what turns "detect automatically" into "detect among
 * my languages" (issue #99). Empty means detect freely, as before.
 */
internal fun languageHintsOf(language: String?, expectedLanguages: List<String>): List<String> {
    val pinned = language?.takeIf { it.isNotBlank() && it != "detect" }
    return (if (pinned != null) listOf(pinned) else expectedLanguages)
        .filter { it.isNotBlank() && it != "detect" }
}
