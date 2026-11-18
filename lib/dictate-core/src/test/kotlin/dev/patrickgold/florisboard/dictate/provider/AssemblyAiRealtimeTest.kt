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
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * AssemblyAI's streaming session. The message shapes below are the ones the live endpoint sent on
 * 2026-10-20; the refusals are its own words for an id or a language code it does not take — both end the
 * session, which is why the client must never send one.
 */
class AssemblyAiRealtimeTest : FunSpec({

    class Recorder : RealtimeCallbacks {
        val partials = mutableListOf<String>()
        val finals = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        val closed = CountDownLatch(1)
        override fun onPartial(text: String) { synchronized(this) { partials += text } }
        override fun onFinalSegment(text: String) { synchronized(this) { finals += text } }
        override fun onTranscriptReplaced(text: String) { synchronized(this) { finals.clear(); finals += text } }
        override fun onError(t: Throwable) { synchronized(this) { errors += t } }
        override fun onClosed() = closed.countDown()
    }

    fun session(model: String, language: String?, expected: List<String> = emptyList()) =
        AssemblyAiRealtimeSession(OkHttpClient(), "aai-test", model, language, Recorder(), expected)

    fun query(model: String, language: String?, expected: List<String> = emptyList()) =
        URLDecoder.decode(session(model, language, expected).url().substringAfter('?'), "UTF-8")

    test("the model is always named, and the old placeholder id becomes the default") {
        query("universal-3-6-pro", null) shouldContain "speech_model=universal-3-6-pro"
        query("universal-streaming-english", null) shouldContain "speech_model=universal-streaming-english"
        // What the dialog offered before: the server refuses it outright (error 3006).
        query("universal-streaming", null) shouldContain "speech_model=universal-3-6-pro"
        query("", null) shouldContain "speech_model=universal-3-6-pro"
    }

    test("a pinned language steers the Pro model as a one-element JSON list") {
        query("universal-3-6-pro", "fr") shouldContain """language_codes=["fr"]"""
    }

    test("auto-detect hears the expected languages, cut to the codes the model knows") {
        val q = query("universal-3-6-pro", "detect", listOf("de", "zh-CN", "zh-TW", "pl", "yue-HK"))
        // Polish is not one of 3.6 Pro's languages: sent, it would fail the whole session.
        q shouldContain """language_codes=["de","zh","yue"]"""
    }

    test("nothing to steer by sends no language at all") {
        query("universal-3-6-pro", "detect") shouldNotContain "language_codes"
        query("universal-3-6-pro", "pl") shouldNotContain "language_codes"
    }

    test("the older models get formatted turns and no language list") {
        val q = query("universal-streaming-multilingual", "fr")
        q shouldContain "format_turns=true"
        q shouldNotContain "language_codes"
        // The Pro model always formats and refuses the switch.
        query("universal-3-6-pro", "fr") shouldNotContain "format_turns"
    }

    test("partials grow the turn, end_of_turn settles it, Terminate flushes and Termination ends") {
        val received = LinkedBlockingQueue<Any>()
        val serverClosed = CountDownLatch(1)
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"Begin","id":"b1","configuration":{"model":"universal-3-6-pro"}}""")
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    received += bytes
                    webSocket.send(turn(0, false, "Bonjour, je voudrais réserver"))
                    webSocket.send(turn(0, true, "Bonjour, je voudrais réserver une table."))
                    webSocket.send(turn(1, false, ""))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received += text
                    // As measured: the turn in progress is settled before the session ends.
                    webSocket.send(turn(1, true, "Est-ce possible?"))
                    webSocket.send("""{"type":"Termination","audio_duration_seconds":4}""")
                    webSocket.close(1000, "Session Ended")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = serverClosed.countDown()
            }),
        )
        server.start()
        try {
            val out = Recorder()
            val session = AssemblyAiRealtimeSession(
                client = OkHttpClient(),
                apiKey = "aai-test",
                model = "universal-3-6-pro",
                language = "fr",
                callbacks = out,
                endpoint = "ws://${server.hostName}:${server.port}/v3/ws",
            )
            session.connect()
            session.sendAudio(byteArrayOf(1, 2, 3, 4), 4)
            received.poll(5, TimeUnit.SECONDS) shouldBe ByteString.of(1, 2, 3, 4)

            session.finish()
            received.poll(5, TimeUnit.SECONDS) shouldBe """{"type":"Terminate"}"""
            out.closed.await(5, TimeUnit.SECONDS) shouldBe true

            out.partials shouldContainExactly listOf("Bonjour, je voudrais réserver")
            out.finals shouldContainExactly listOf("Bonjour, je voudrais réserver une table.", "Est-ce possible?")
            out.errors.shouldBeEmpty()

            val handshake = server.takeRequest()
            handshake.getHeader("Authorization") shouldBe "aai-test"
            val path = URLDecoder.decode(handshake.path.orEmpty(), "UTF-8")
            path shouldContain "/v3/ws?sample_rate=16000&encoding=pcm_s16le"
            path shouldContain "speech_model=universal-3-6-pro"
            serverClosed.await(5, TimeUnit.SECONDS) shouldBe true
        } finally {
            server.shutdown()
        }
    }

    test("a refused session is an error the dictation can fall back from, not an empty stream") {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(
                        """{"type":"Error","error_code":3006,"error":"User Input Validation Error: """ +
                            """Invalid 'language_codes.0': Input should be 'en', 'es' or 'multi'"}""",
                    )
                    webSocket.close(3006, "See Error message for details")
                }
            }),
        )
        server.start()
        try {
            val out = Recorder()
            AssemblyAiRealtimeSession(
                client = OkHttpClient(),
                apiKey = "aai-test",
                model = "universal-3-6-pro",
                language = null,
                callbacks = out,
                endpoint = "ws://${server.hostName}:${server.port}/v3/ws",
            ).connect()
            out.closed.await(5, TimeUnit.SECONDS) shouldBe true
            out.errors shouldHaveSize 1
            out.errors.single().message.orEmpty() shouldContain "language_codes"
        } finally {
            server.shutdown()
        }
    }

    test("a close without an Error message is still an error") {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.close(1008, "Unauthorized connection")
                }
            }),
        )
        server.start()
        try {
            val out = Recorder()
            AssemblyAiRealtimeSession(
                client = OkHttpClient(),
                apiKey = "aai-test",
                model = "universal-3-6-pro",
                language = null,
                callbacks = out,
                endpoint = "ws://${server.hostName}:${server.port}/v3/ws",
            ).connect()
            out.closed.await(5, TimeUnit.SECONDS) shouldBe true
            out.errors shouldHaveSize 1
            out.errors.single().message.orEmpty() shouldContain "1008"
        } finally {
            server.shutdown()
        }
    }
})

private fun turn(order: Int, endOfTurn: Boolean, transcript: String) =
    """{"type":"Turn","turn_order":$order,"turn_is_formatted":true,"end_of_turn":$endOfTurn,""" +
        """"transcript":"$transcript","end_of_turn_confidence":0.9,"words":[]}"""
