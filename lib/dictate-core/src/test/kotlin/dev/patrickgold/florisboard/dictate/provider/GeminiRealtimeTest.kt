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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Gemini Live's settled transcriptions (#488). Since 2026-11-17 SMART mode sends each turn's
 * `inputTranscription` as the whole transcript so far, and a later turn may rewrite earlier words, so
 * the session hands every one on as a replacement. The frames follow Google's documented shape.
 */
class GeminiRealtimeTest : FunSpec({

    /** Records what a session hands the dictation engine, in order. */
    class Recorder : RealtimeCallbacks {
        val partials = mutableListOf<String>()
        val appended = mutableListOf<String>()
        val replaced = mutableListOf<String>()
        /** Counted down once per replacement, so a test can wait for the last frame it sent. */
        var settledLatch = CountDownLatch(0)
        override fun onPartial(text: String) { synchronized(this) { partials += text } }
        override fun onFinalSegment(text: String) { synchronized(this) { appended += text } }
        override fun onTranscriptReplaced(text: String) {
            synchronized(this) { replaced += text }
            settledLatch.countDown()
        }
        override fun onError(t: Throwable) = Unit
        override fun onClosed() = Unit
    }

    /**
     * A server that answers the setup frame with setupComplete, then [frames]. It never closes the socket:
     * Google does not either (measured for #372), and the session has no answer to a close from that side.
     */
    fun answering(setup: LinkedBlockingQueue<String>, vararg frames: String) =
        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                setup += text
                webSocket.send("""{"setupComplete":{}}""")
                frames.forEach { webSocket.send(it) }
            }

            // The session closes the socket itself when the test cancels it; answering lets the server stop.
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        })

    fun open(server: MockWebServer, callbacks: RealtimeCallbacks) = RealtimeClient.open(
        RealtimeApi.GEMINI, "gemini-key", "gemini-3.5-transcribe-live", "en", callbacks,
        baseUrl = "ws://${server.hostName}:${server.port}/live",
    )

    fun settled(text: String) = """{"serverContent":{"inputTranscription":{"text":${JsonPrimitive(text)}}}}"""
    fun interim(text: String) = """{"serverContent":{"interimInputTranscription":{"text":${JsonPrimitive(text)}}}}"""

    test("a dictation with pauses replaces the transcript at every turn instead of repeating it") {
        val out = Recorder()
        val setup = LinkedBlockingQueue<String>()
        MockWebServer().use { server ->
            server.enqueue(
                answering(
                    setup,
                    interim("Please meet me"),
                    settled("Please meet me at the station."),
                    interim("Siobhan sent"),
                    settled("Please meet me at the station. Siobhan sent the invoice."),
                    interim("Make it more formal"),
                    settled("Kindly meet me at the station. Siobhan has sent the invoice."),
                ),
            )
            out.settledLatch = CountDownLatch(3)
            val session = open(server, out)

            out.settledLatch.await(5, TimeUnit.SECONDS) shouldBe true
            session.cancel()
            out.replaced shouldContainExactly listOf(
                "Please meet me at the station.",
                "Please meet me at the station. Siobhan sent the invoice.",
                "Kindly meet me at the station. Siobhan has sent the invoice.",
            )
            // Nothing is appended: the last replacement is the whole dictation, the edit included.
            out.appended.shouldBeEmpty()
            out.partials shouldContainExactly listOf("Please meet me", "Siobhan sent", "Make it more formal")
        }
    }

    test("a settled transcription without text is \"delete everything\", not something to skip") {
        val out = Recorder()
        val setup = LinkedBlockingQueue<String>()
        MockWebServer().use { server ->
            server.enqueue(
                answering(
                    setup,
                    settled("Some words."),
                    """{"serverContent":{"inputTranscription":{}}}""",
                ),
            )
            out.settledLatch = CountDownLatch(2)
            val session = open(server, out)

            out.settledLatch.await(5, TimeUnit.SECONDS) shouldBe true
            session.cancel()
            out.replaced shouldContainExactly listOf("Some words.", "")
        }
    }

    test("the session still asks for SMART, with the key in the query") {
        val setup = LinkedBlockingQueue<String>()
        MockWebServer().use { server ->
            server.enqueue(answering(setup))
            val session = open(server, Recorder())

            val frame = Json.parseToJsonElement(setup.poll(5, TimeUnit.SECONDS)!!).jsonObject["setup"]!!.jsonObject
            frame["inputAudioTranscription"]!!.jsonObject["mode"]!!.jsonPrimitive.content shouldBe "SMART"
            frame["model"]!!.jsonPrimitive.content shouldBe "models/gemini-3.5-transcribe-live"
            server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("key") shouldBe "gemini-key"
            session.cancel()
        }
    }
})
