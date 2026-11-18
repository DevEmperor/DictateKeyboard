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
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * xAI's streaming protocol (issue #435). The event sequences below are the ones pipecat and N.E.K.O
 * captured from `wss://api.x.ai/v1/stt` — the documentation alone does not say that an utterance final
 * restates the chunks before it, and a client that believes otherwise prints every sentence twice.
 */
class XaiRealtimeTest : FunSpec({

    /** Records what a session hands the dictation engine, in order. */
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

    test("an utterance final replaces the chunks it restates instead of following them") {
        val out = Recorder()
        val assembler = XaiTranscriptAssembler(out)

        // pipecat-ai/pipecat#5671, one short utterance as captured from the live endpoint.
        assembler.onPartial("my order", isFinal = false, speechFinal = false)
        assembler.onPartial("my order number", isFinal = true, speechFinal = false)
        assembler.onPartial("is four", isFinal = false, speechFinal = false)
        assembler.onPartial("is four two one.", isFinal = true, speechFinal = false)
        assembler.onPartial("my order number is four two one.", isFinal = true, speechFinal = true)

        out.finals shouldContainExactly listOf("my order number is four two one.")
        // The interim after a lock covers only the audio after it, so the preview puts the two together.
        out.partials shouldContainExactly listOf(
            "my order", "my order number", "my order number is four", "my order number is four two one.",
        )
    }

    test("two utterances settle one after the other, each exactly once") {
        val out = Recorder()
        val assembler = XaiTranscriptAssembler(out)

        assembler.onPartial("Hallo", isFinal = true, speechFinal = false)
        assembler.onPartial("Hallo zusammen.", isFinal = true, speechFinal = true)
        assembler.onPartial("Wie geht", isFinal = false, speechFinal = false)
        assembler.onPartial("Wie geht es euch?", isFinal = true, speechFinal = true)
        assembler.onDone("")

        out.finals shouldContainExactly listOf("Hallo zusammen.", "Wie geht es euch?")
    }

    test("noise, which xAI answers with empty events and no utterance final, changes nothing") {
        val out = Recorder()
        val assembler = XaiTranscriptAssembler(out)

        // Project-N-E-K-O/N.E.K.O#3073: 25 s of room noise gave 34 empty partials of both kinds.
        repeat(17) {
            assembler.onPartial("", isFinal = false, speechFinal = false)
            assembler.onPartial("", isFinal = true, speechFinal = false)
        }
        assembler.flush()

        out.partials.shouldBeEmpty()
        out.finals.shouldBeEmpty()
    }

    test("an empty utterance final keeps the chunks the server had already locked") {
        val out = Recorder()
        val assembler = XaiTranscriptAssembler(out)

        assembler.onPartial("Das ist gesagt", isFinal = true, speechFinal = false)
        assembler.onPartial("und bleibt.", isFinal = true, speechFinal = false)
        assembler.onPartial("", isFinal = true, speechFinal = true)

        out.finals shouldContainExactly listOf("Das ist gesagt", "und bleibt.")
    }

    test("transcript.done is believed only while nothing has been settled") {
        // Nothing settled yet: whatever it restates, it cannot repeat anything, so it is the transcript.
        val first = Recorder()
        XaiTranscriptAssembler(first).apply {
            onPartial("Kurz", isFinal = false, speechFinal = false)
            onDone("Kurz und gut.")
        }
        first.finals shouldContainExactly listOf("Kurz und gut.")

        // Something settled already: if the text is the whole session it would say it twice, so the open
        // utterance is settled from what is here instead — locked chunk first, then the last guess.
        val second = Recorder()
        XaiTranscriptAssembler(second).apply {
            onPartial("Erster Satz.", isFinal = true, speechFinal = true)
            onPartial("Zweiter", isFinal = true, speechFinal = false)
            onPartial("Satz", isFinal = false, speechFinal = false)
            onDone("Erster Satz. Zweiter Satz.")
        }
        second.finals shouldContainExactly listOf("Erster Satz.", "Zweiter", "Satz")
    }

    test("the socket carries the config in its query, holds audio until ready and ends with finalize") {
        val received = LinkedBlockingQueue<Any>()
        // Waited for before shutdown, so the client's socket ends in a close handshake and not in an EOF.
        val serverClosed = CountDownLatch(1)
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    received += "open"
                    webSocket.send("""{"type":"transcript.created","id":"83f2f6fd"}""")
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    received += bytes
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received += text
                    if (text.contains("audio.done")) {
                        webSocket.send(
                            """{"type":"transcript.partial","text":"Hallo Welt.","words":[],""" +
                                """"is_final":true,"speech_final":true,"start":0.0,"duration":1.2}""",
                        )
                        webSocket.send("""{"type":"transcript.done","text":"","words":[],"duration":1.2}""")
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = serverClosed.countDown()
            }),
        )
        server.start()
        try {
            val out = Recorder()
            val session = XaiRealtimeSession(
                client = OkHttpClient(),
                apiKey = "xai-test",
                model = "grok-voice-transcribe-2.0",
                language = "de-DE",
                callbacks = out,
                endpoint = "ws://${server.hostName}:${server.port}/v1/stt",
            )
            session.connect()
            // Spoken before the server said it was ready — held, not dropped, and not sent early.
            session.sendAudio(byteArrayOf(1, 2, 3, 4), 4)

            received.poll(5, TimeUnit.SECONDS) shouldBe "open"
            received.poll(5, TimeUnit.SECONDS) shouldBe ByteString.of(1, 2, 3, 4)

            session.finish()
            received.poll(5, TimeUnit.SECONDS) shouldBe """{"type":"finalize"}"""
            received.poll(5, TimeUnit.SECONDS) shouldBe """{"type":"audio.done"}"""
            out.closed.await(5, TimeUnit.SECONDS) shouldBe true

            out.finals shouldContainExactly listOf("Hallo Welt.")
            out.errors.shouldBeEmpty()

            val handshake = server.takeRequest()
            handshake.getHeader("Authorization") shouldBe "Bearer xai-test"
            val path = handshake.path.orEmpty()
            path shouldContain "/v1/stt?"
            path shouldContain "sample_rate=16000"
            path shouldContain "encoding=pcm"
            path shouldContain "interim_results=true"
            path shouldContain "model=grok-voice-transcribe-2.0"
            path shouldContain "language=de"
            serverClosed.await(5, TimeUnit.SECONDS) shouldBe true
        } finally {
            server.shutdown()
        }
    }
})
