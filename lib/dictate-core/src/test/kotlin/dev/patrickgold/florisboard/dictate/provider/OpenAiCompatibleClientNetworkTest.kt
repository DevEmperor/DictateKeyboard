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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.io.path.createTempFile

class OpenAiCompatibleClientNetworkTest : FunSpec({
    test("batch clients preserve system DNS order and bound only the connect timeout") {
        val client = OpenAiCompatibleClient(
            ProviderConfig(
                baseUrl = "https://example.test/v1/",
                apiKey = "test",
                timeoutSeconds = 120,
            ),
        ).buildClient()

        client.dns shouldBe Dns.SYSTEM
        client.connectTimeoutMillis shouldBe 8_000
        client.callTimeoutMillis shouldBe 120_000
        client.readTimeoutMillis shouldBe 120_000
        client.writeTimeoutMillis shouldBe 120_000
    }

    test("OpenRouter streams an OpenAI-compatible multipart upload") {
        ProviderRegistry.OPENROUTER.transcriptionApi shouldBe TranscriptionApi.OPENROUTER_MULTIPART

        val audio = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-test-audio".encodeToByteArray())
        }
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Hallo Welt"}"""))
                val client = OpenAiCompatibleClient(
                    ProviderConfig(
                        baseUrl = server.url("/").toString(),
                        apiKey = "test",
                        transcriptionApi = TranscriptionApi.OPENROUTER_MULTIPART,
                    ),
                )

                val result = client.transcribe(
                    TranscriptionRequest(
                        audioFile = audio,
                        model = "microsoft/mai-transcribe-1.5",
                        language = "de",
                        prompt = "Eigennamen beibehalten",
                    ),
                )
                val recorded = server.takeRequest()
                val body = recorded.body.readUtf8()

                result.text shouldBe "Hallo Welt"
                recorded.method shouldBe "POST"
                recorded.path shouldBe "/audio/transcriptions"
                recorded.getHeader("Content-Type").orEmpty() shouldStartWith "multipart/form-data; boundary="
                body shouldContain "name=\"file\"; filename=\"${audio.name}\""
                body shouldContain "name=\"model\""
                body shouldContain "microsoft/mai-transcribe-1.5"
                body shouldContain "name=\"language\""
                body shouldContain "de"
                body shouldContain "name=\"prompt\""
                body shouldContain "RIFF-test-audio"
                body shouldNotContain "input_audio"
                server.requestCount shouldBe 1
            }
        } finally {
            audio.delete()
        }
    }

    test("separate provider instances reuse the same HTTP connection") {
        val audio = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-test-audio".encodeToByteArray())
        }
        try {
            MockWebServer().use { server ->
                repeat(2) {
                    server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"ok"}"""))
                }
                val config = ProviderConfig(
                    baseUrl = server.url("/").toString(),
                    apiKey = "test",
                    transcriptionApi = TranscriptionApi.OPENROUTER_MULTIPART,
                )

                repeat(2) {
                    OpenAiCompatibleClient(config).transcribe(
                        TranscriptionRequest(audio, "microsoft/mai-transcribe-1.5"),
                    )
                }

                val first = server.takeRequest()
                val second = server.takeRequest()
                first.sequenceNumber shouldBe 0
                second.sequenceNumber shouldBe 1
                server.requestCount shouldBe 2
            }
        } finally {
            audio.delete()
        }
    }

    test("OpenRouter prewarm connection is reused by the transcription POST") {
        val audio = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-test-audio".encodeToByteArray())
        }
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"label":"test"}}"""))
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"ok"}"""))
                val client = OpenAiCompatibleClient(
                    ProviderConfig(
                        baseUrl = server.url("/").toString(),
                        apiKey = "test",
                        transcriptionApi = TranscriptionApi.OPENROUTER_MULTIPART,
                    ),
                )

                client.prewarmOpenRouterConnection()
                client.transcribe(TranscriptionRequest(audio, "microsoft/mai-transcribe-1.5"))

                val prewarm = server.takeRequest()
                val transcription = server.takeRequest()
                prewarm.method shouldBe "GET"
                prewarm.path shouldBe "/key"
                prewarm.sequenceNumber shouldBe 0
                transcription.method shouldBe "POST"
                transcription.sequenceNumber shouldBe 1
            }
        } finally {
            audio.delete()
        }
    }

    test("OpenRouter falls back to documented JSON only when multipart is rejected") {
        val audio = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-test-audio".encodeToByteArray())
        }
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(415).setBody("unsupported media type"))
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"fallback ok"}"""))
                val client = OpenAiCompatibleClient(
                    ProviderConfig(
                        baseUrl = server.url("/").toString(),
                        apiKey = "test",
                        transcriptionApi = TranscriptionApi.OPENROUTER_MULTIPART,
                    ),
                )

                val result = client.transcribe(
                    TranscriptionRequest(audio, "microsoft/mai-transcribe-1.5", language = "de"),
                )

                val multipart = server.takeRequest()
                val json = server.takeRequest()
                val jsonBody = json.body.readUtf8()
                result.text shouldBe "fallback ok"
                multipart.getHeader("Content-Type").orEmpty() shouldStartWith "multipart/form-data"
                json.getHeader("Content-Type").orEmpty() shouldStartWith "application/json"
                jsonBody shouldContain "\"input_audio\""
                jsonBody shouldNotContain "multipart/form-data"
                server.requestCount shouldBe 2
            }
        } finally {
            audio.delete()
        }
    }

    test("OpenRouter transcription policy never replays a billable POST") {
        val audio = createTempFile(suffix = ".wav").toFile().apply { writeBytes(ByteArray(32)) }
        try {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setResponseCode(503).setBody("""{"error":{"message":"busy"}}"""),
                )
                server.enqueue(
                    MockResponse().setResponseCode(200).setBody("""{"text":"duplicate"}"""),
                )

                val client = OpenAiCompatibleClient(
                    ProviderConfig(
                        baseUrl = server.url("/").toString(),
                        apiKey = "test",
                        transcriptionApi = TranscriptionApi.OPENROUTER_MULTIPART,
                    ),
                )

                val error = shouldThrow<DictateApiException> {
                    client.transcribe(TranscriptionRequest(audio, "microsoft/mai-transcribe-1.5"))
                }

                error.kind shouldBe DictateApiException.Kind.SERVER_ERROR
                server.requestCount shouldBe 1
            }
        } finally {
            audio.delete()
        }
    }

    test("OpenRouter does not fall back for semantic client errors") {
        val audio = createTempFile(suffix = ".wav").toFile().apply { writeBytes(ByteArray(32)) }
        try {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setResponseCode(400)
                        .setBody("""{"error":{"message":"unknown model"}}"""),
                )
                val client = OpenAiCompatibleClient(
                    ProviderConfig(
                        baseUrl = server.url("/").toString(),
                        apiKey = "test",
                        transcriptionApi = TranscriptionApi.OPENROUTER_MULTIPART,
                    ),
                )

                shouldThrow<DictateApiException> {
                    client.transcribe(TranscriptionRequest(audio, "missing/model"))
                }
                server.requestCount shouldBe 1
            }
        } finally {
            audio.delete()
        }
    }
})
