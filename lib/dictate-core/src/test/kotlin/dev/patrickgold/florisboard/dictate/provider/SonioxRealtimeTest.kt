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
import kotlinx.serialization.json.Json
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
 * Soniox's streaming socket (#475). The key moved from the config message into the upgrade request's
 * header, and a refusal comes back as a frame on an open socket. The 401 below is the frame the live
 * endpoint sent on 2026-11-11, trimmed; the mid-stream error is made up in the same shape.
 */
class SonioxRealtimeTest : FunSpec({

    /** Records what a session hands the dictation engine, in order. */
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

    /** A server that answers the config message with [frames] and then closes, as Soniox does. */
    fun answering(vararg frames: String) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            frames.forEach { webSocket.send(it) }
            webSocket.close(1000, null)
        }
    })

    fun open(server: MockWebServer, callbacks: RealtimeCallbacks, key: String = "k") = RealtimeClient.open(
        RealtimeApi.SONIOX, key, "stt-rt-v5", "de", callbacks,
        baseUrl = "ws://${server.hostName}:${server.port}/transcribe-websocket",
    )

    test("the key travels in the Authorization header, and the config message no longer carries it") {
        val received = LinkedBlockingQueue<String>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received += text
                    webSocket.close(1000, null)
                }
            }))
            val session = open(server, Recorder(), key = "soniox-key")
            val config = Json.parseToJsonElement(received.poll(5, TimeUnit.SECONDS)!!).jsonObject
            session.cancel()

            server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization") shouldBe "Bearer soniox-key"
            // Soniox refuses a second key that differs from the header's (400), so none is sent at all.
            config.containsKey("api_key") shouldBe false
            config["model"]!!.jsonPrimitive.content shouldBe "stt-rt-v5"
        }
    }

    test("a refused key is reported as an error, not as a dictation that said nothing") {
        val out = Recorder()
        MockWebServer().use { server ->
            server.enqueue(
                answering(
                    """{"final_audio_proc_ms":0,"total_audio_proc_ms":0,"error_code":401,""" +
                        """"error_type":"unauthenticated","error_message":"Incorrect API key provided."}""",
                ),
            )
            open(server, out)

            out.closed.await(5, TimeUnit.SECONDS) shouldBe true
            out.errors shouldHaveSize 1
            out.errors.single().message!! shouldContain "401"
            out.finals.shouldBeEmpty()
        }
    }

    test("an error after some text fails the stream instead of handing over half a dictation") {
        val out = Recorder()
        MockWebServer().use { server ->
            server.enqueue(
                answering(
                    """{"tokens":[{"text":"Hallo","is_final":true}]}""",
                    """{"error_code":500,"error_type":"internal_error","error_message":"Try again."}""",
                ),
            )
            open(server, out)

            out.closed.await(5, TimeUnit.SECONDS) shouldBe true
            out.partials shouldContainExactly listOf("Hallo")
            out.errors shouldHaveSize 1
            // The controller transcribes the whole recording instead; a final here would be committed as
            // if the stream had ended normally.
            out.finals.shouldBeEmpty()
        }
    }
})
