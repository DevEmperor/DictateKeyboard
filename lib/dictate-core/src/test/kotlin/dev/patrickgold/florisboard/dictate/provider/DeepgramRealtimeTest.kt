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
import io.kotest.matchers.string.shouldContain
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Deepgram's streaming session. The end of a dictation is the server's own close: measured against
 * `wss://api.deepgram.com/v1/listen` (nova-3) on 2026-11-02, `CloseStream` is answered with an empty final
 * `Results`, a `Metadata` message and a close frame with code 1000, 0.18 s after the stop. Flux
 * (`/v2/listen`) closes just as fast with an empty close frame, which OkHttp reports as 1005 — a mock
 * server cannot send one.
 */
class DeepgramRealtimeTest : FunSpec({

    class Recorder : RealtimeCallbacks {
        val partials = mutableListOf<String>()
        val finals = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        val closed = CountDownLatch(1)
        override fun onPartial(text: String) { synchronized(this) { partials += text } }
        override fun onFinalSegment(text: String) { synchronized(this) { finals += text } }
        override fun onError(t: Throwable) { synchronized(this) { errors += t } }
        override fun onClosed() = closed.countDown()
    }

    fun results(text: String, final: Boolean) =
        """{"type":"Results","is_final":$final,"channel":{"alternatives":[{"transcript":"$text"}]}}"""

    test("the server's close after CloseStream ends the session at once, with the unsettled tail") {
        val received = LinkedBlockingQueue<Any>()
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    received += bytes
                    webSocket.send(results("Can you pick me up", final = true))
                    webSocket.send(results("from the station", final = false))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received += text
                    // As measured: no message says "done"; the close does.
                    webSocket.send(results("", final = true))
                    webSocket.send("""{"type":"Metadata","duration":5.1}""")
                    webSocket.close(1000, null)
                }
            }),
        )
        server.start()
        try {
            val out = Recorder()
            val session = DeepgramRealtimeSession(
                client = OkHttpClient(),
                apiKey = "dg-test",
                model = "nova-3",
                language = null,
                callbacks = out,
                host = "ws://${server.hostName}:${server.port}",
            )
            session.connect()
            session.sendAudio(byteArrayOf(1, 2, 3, 4), 4)
            received.poll(5, TimeUnit.SECONDS) shouldBe ByteString.of(1, 2, 3, 4)

            session.finish()
            received.poll(5, TimeUnit.SECONDS) shouldBe """{"type":"CloseStream"}"""
            // Before the fix only a close of our own reached onClosed, and this waited in vain.
            out.closed.await(2, TimeUnit.SECONDS) shouldBe true

            out.finals shouldContainExactly listOf("Can you pick me up", "from the station")
            out.errors.shouldBeEmpty()
            server.takeRequest().path.orEmpty() shouldContain "/v1/listen?model=nova-3"
        } finally {
            server.shutdown()
        }
    }

    test("a close with an error code is a failure the dictation can fall back from, not its end") {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    webSocket.send(results("Can you pick me up", final = false))
                    // Deepgram's own words when no audio arrives in time.
                    webSocket.close(1011, "NET-0001")
                }
            }),
        )
        server.start()
        try {
            val out = Recorder()
            val session = DeepgramRealtimeSession(
                client = OkHttpClient(),
                apiKey = "dg-test",
                model = "flux-general-en",
                language = null,
                callbacks = out,
                host = "ws://${server.hostName}:${server.port}",
            )
            session.connect()
            session.sendAudio(byteArrayOf(1, 2, 3, 4), 4)

            out.closed.await(2, TimeUnit.SECONDS) shouldBe true
            out.errors.single().message.orEmpty() shouldContain "1011"
            // Half a sentence is not passed off as the dictation: the recording is transcribed instead.
            out.finals.shouldBeEmpty()
            server.takeRequest().path.orEmpty() shouldContain "/v2/listen?model=flux-general-en"
        } finally {
            server.shutdown()
        }
    }
})
