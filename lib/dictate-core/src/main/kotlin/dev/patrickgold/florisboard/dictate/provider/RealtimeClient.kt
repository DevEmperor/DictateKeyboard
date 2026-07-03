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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Opens real-time transcription sessions (issue #128). Currently implements OpenAI's realtime WebSocket
 * ([RealtimeApi.OPENAI]); the other providers ([RealtimeApi.SONIOX] etc.) are declared but not yet wired
 * and throw until their sessions are built.
 *
 * The WebSocket client is long-lived (no read/call timeout, periodic ping), separate from the batch HTTP
 * client. Callers keep the batch [OpenAiCompatibleClient] for the fallback path.
 */
object RealtimeClient {

    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
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
    fun open(
        api: RealtimeApi,
        apiKey: String,
        model: String,
        language: String?,
        callbacks: RealtimeCallbacks,
    ): RealtimeSession = when (api) {
        RealtimeApi.OPENAI -> OpenAiRealtimeSession(wsClient, apiKey, model, language, callbacks).also { it.connect() }
        else -> throw DictateApiException(
            DictateApiException.Kind.UNKNOWN,
            "Real-time transcription for $api is not implemented yet",
        )
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
) : RealtimeSession {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var ws: WebSocket? = null
    private val partial = StringBuilder()
    @Volatile private var committing = false
    @Volatile private var done = false

    private companion object {
        const val URL = "wss://api.openai.com/v1/realtime?intent=transcription"
    }

    fun connect() {
        val request = Request.Builder()
            .url(URL)
            .header("Authorization", "Bearer $apiKey")
            .header("OpenAI-Beta", "realtime=v1")
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

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = emitError(t)
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
        val bytes = if (len == pcm16.size) pcm16 else pcm16.copyOf(len)
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val msg = buildJsonObject {
            put("type", "input_audio_buffer.append")
            put("audio", b64)
        }.toString()
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
