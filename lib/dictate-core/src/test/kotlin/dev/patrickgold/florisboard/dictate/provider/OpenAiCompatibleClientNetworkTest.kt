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
import io.kotest.inspectors.forAll
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
                body shouldContain "name=\"temperature\""
                body shouldContain "0.0"
                body shouldContain "RIFF-test-audio"
                body shouldNotContain "input_audio"
                server.requestCount shouldBe 1
            }
        } finally {
            audio.delete()
        }
    }

    // Issue #248: gpt-transcribe renamed the singular `language` field to a `languages` array, which in
    // multipart is the repeated bracket form the docs show — the form that carries more than one code.
    // A field name the server does not read is dropped in silence rather than refused, so the user's
    // language choice would just stop applying; both directions are asserted.
    test("gpt-transcribe receives the language as `languages[]`, older models as `language`") {
        val audio = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-test-audio".encodeToByteArray())
        }
        try {
            MockWebServer().use { server ->
                repeat(2) { server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Hallo"}""")) }
                val client = OpenAiCompatibleClient(
                    ProviderConfig(baseUrl = server.url("/").toString(), apiKey = "test"),
                )

                client.transcribe(TranscriptionRequest(audio, "gpt-transcribe", language = "de"))
                val newModel = server.takeRequest().body.readUtf8()
                newModel shouldContain "name=\"languages[]\"\r\n\r\nde"
                newModel shouldNotContain "name=\"language\"\r\n"

                client.transcribe(TranscriptionRequest(audio, "gpt-4o-mini-transcribe", language = "de"))
                val oldModel = server.takeRequest().body.readUtf8()
                oldModel shouldContain "name=\"language\""
                oldModel shouldNotContain "name=\"languages"
            }
        } finally {
            audio.delete()
        }
    }

    // Issue #321: the same model reached through OpenRouter is called `openai/gpt-transcribe`, and it
    // must keep getting the SINGULAR field. OpenRouter's transcription endpoint documents `language`
    // and nothing else; a field it does not read is dropped without an error, so a well-meant
    // "the prefix check misses the vendor-qualified id" fix would silently switch every OpenRouter
    // dictation to detect-anything. This test is the tripwire for that change.
    test("the vendor-qualified gpt-transcribe keeps OpenRouter's singular `language`") {
        val audio = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-test-audio".encodeToByteArray())
        }
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Hallo"}"""))
                val client = OpenAiCompatibleClient(
                    ProviderConfig(
                        baseUrl = server.url("/").toString(),
                        apiKey = "test",
                        transcriptionApi = TranscriptionApi.OPENROUTER_MULTIPART,
                    ),
                )

                // The id OpenRouter serves it under, and the one this app now dictates with by default —
                // asserted so the scenario cannot quietly stop being real.
                val model = "openai/gpt-transcribe"
                ProviderRegistry.OPENROUTER.defaultTranscriptionModel shouldBe model

                client.transcribe(
                    TranscriptionRequest(
                        audio, model,
                        language = "de",
                        expectedLanguages = listOf("de", "en"),
                    ),
                )
                val body = server.takeRequest().body.readUtf8()
                body shouldContain "name=\"language\"\r\n\r\nde"
                body shouldNotContain "name=\"languages"
            }
        } finally {
            audio.delete()
        }
    }

    // Issue #99: four dictation languages and no pinned one. The generation that takes a list gets the
    // whole set (detect among *these*); the generation that takes one language gets nothing, because a
    // single code cannot say "one of these four" and guessing one is how the wrong language gets forced.
    test("expected languages reach a list-shaped model, and no older one") {
        val audio = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-test-audio".encodeToByteArray())
        }
        try {
            MockWebServer().use { server ->
                repeat(2) { server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Hallo"}""")) }
                val client = OpenAiCompatibleClient(
                    ProviderConfig(baseUrl = server.url("/").toString(), apiKey = "test"),
                )
                val expected = listOf("de", "en", "fr")

                client.transcribe(
                    TranscriptionRequest(audio, "gpt-transcribe", expectedLanguages = expected),
                )
                val listModel = server.takeRequest().body.readUtf8()
                expected.forAll { listModel shouldContain "name=\"languages[]\"\r\n\r\n$it" }

                client.transcribe(
                    TranscriptionRequest(audio, "gpt-4o-transcribe", expectedLanguages = expected),
                )
                server.takeRequest().body.readUtf8() shouldNotContain "name=\"language"
            }
        } finally {
            audio.delete()
        }
    }

    // A picked language stays picked: the model may be able to juggle several, but the user said German.
    test("a pinned language is not widened by the selection") {
        val audio = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-test-audio".encodeToByteArray())
        }
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Hallo"}"""))
                val client = OpenAiCompatibleClient(
                    ProviderConfig(baseUrl = server.url("/").toString(), apiKey = "test"),
                )

                client.transcribe(
                    TranscriptionRequest(
                        audio, "gpt-transcribe", language = "de", expectedLanguages = listOf("de", "en", "fr"),
                    ),
                )
                val body = server.takeRequest().body.readUtf8()
                body shouldContain "name=\"languages[]\"\r\n\r\nde"
                body shouldNotContain "\r\n\r\nen"
                body shouldNotContain "\r\n\r\nfr"
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
                jsonBody shouldContain "\"temperature\":0.0"
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

    // Issue #284: a rewording that answers with nothing used to come back as "" — and the auto-apply
    // chain then committed that empty string over the user's dictation without a word being said.
    test("an empty completion is a failure, not an answer") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"choices":[{"message":{"content":""},"finish_reason":"length"}]}""",
                ),
            )
            val client = OpenAiCompatibleClient(
                ProviderConfig(baseUrl = server.url("/v1/").toString(), apiKey = "test"),
            )

            val error = shouldThrow<DictateApiException> {
                client.complete(ChatRequest.ofUser("some-reasoning-model", "Fix my typos"))
            }
            error.kind shouldBe DictateApiException.Kind.UNKNOWN
            error.message.orEmpty() shouldContain "finish_reason=length"
        }
    }

    // Issue #284: decoding runs outside executeForBody's catch, so an unexpected shape escaped as a raw
    // SerializationException — "unknown error" with a kotlinx message that never showed what came back.
    test("a response that cannot be read says what the provider sent") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("<html><body>502 Bad Gateway (proxy)</body></html>"),
            )
            val client = OpenAiCompatibleClient(
                ProviderConfig(baseUrl = server.url("/v1/").toString(), apiKey = "test"),
            )

            val error = shouldThrow<DictateApiException> {
                client.complete(ChatRequest.ofUser("gpt-4o-mini", "Fix my typos"))
            }
            error.kind shouldBe DictateApiException.Kind.UNKNOWN
            error.message.orEmpty() shouldContain "502 Bad Gateway (proxy)"
        }
    }

    test("Deepgram offers only models the file endpoint can serve") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {"stt":[
                      {"canonical_name":"nova-3","batch":true,"streaming":true},
                      {"canonical_name":"flux-general-en","batch":false,"streaming":true},
                      {"canonical_name":"whisper-large"}
                    ]}
                    """.trimIndent(),
                ),
            )
            val client = OpenAiCompatibleClient(
                ProviderConfig(
                    baseUrl = server.url("/v1/").toString(),
                    apiKey = "test",
                    transcriptionApi = TranscriptionApi.DEEPGRAM,
                ),
            )

            // flux is streaming-only (#291) and would fail every upload; an entry that says nothing about
            // its endpoints is kept, so a changed catalog leaves the user with a list rather than none.
            client.listModels().map { it.id } shouldBe listOf("nova-3", "whisper-large")
            server.takeRequest().getHeader("Authorization") shouldBe "Token test"
        }
    }

    // Issue #304: a thinking model that spends its whole answer on the thinking comes back as a perfectly
    // valid 200 with nothing in it. The one remedy on this side is to ask again with less thinking.
    test("a reasoning-only answer is asked again with the thinking turned down") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {"choices":[{"finish_reason":"length","message":{
                      "role":"assistant","content":"","reasoning":"The user wants this shortened, so I"}}]}
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Kurz und knapp."}}]}""",
                ),
            )
            val client = OpenAiCompatibleClient(
                ProviderConfig(baseUrl = server.url("/v1/").toString(), apiKey = "test"),
            )

            // The user's own setting is OFF, so nothing was sent and the provider used its own default —
            // exactly the shape the report came in with.
            val result = client.complete(ChatRequest.ofUser("google/gemini-3.5-flash", "Fasse das zusammen."))

            result.text shouldBe "Kurz und knapp."
            server.requestCount shouldBe 2
            server.takeRequest().body.readUtf8() shouldNotContain "reasoning_effort"
            server.takeRequest().body.readUtf8() shouldContain "\"reasoning_effort\":\"low\""
        }
    }

    test("the thinking never becomes the answer, and the failure says what happened") {
        MockWebServer().use { server ->
            repeat(2) {
                server.enqueue(
                    MockResponse().setResponseCode(200).setBody(
                        """
                        {"choices":[{"finish_reason":"length","message":{
                          "role":"assistant","content":null,"reasoning":"Let me think about this at length"}}]}
                        """.trimIndent(),
                    ),
                )
            }
            val client = OpenAiCompatibleClient(
                ProviderConfig(baseUrl = server.url("/v1/").toString(), apiKey = "test"),
            )

            val error = shouldThrow<DictateApiException> {
                client.complete(ChatRequest.ofUser("google/gemini-3.5-flash", "Fasse das zusammen."))
            }

            error.message.orEmpty() shouldContain "reasoning only"
            error.message.orEmpty() shouldContain "finish_reason=length"
            // The thinking is measured, never repeated: it must not reach the user through the notice
            // either, and above all it must never come back as the rewritten text.
            error.message.orEmpty() shouldNotContain "Let me think"
            server.requestCount shouldBe 2
        }
    }

    // The other way an empty 200 arrives. Asking again with less thinking would only pay twice for the
    // same refusal, so the provider's own words are what comes back.
    test("an error reported inside a 200 is named rather than retried") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"choices":[],"error":{"message":"No endpoints found for this model."}}""",
                ),
            )
            val client = OpenAiCompatibleClient(
                ProviderConfig(baseUrl = server.url("/v1/").toString(), apiKey = "test"),
            )

            val error = shouldThrow<DictateApiException> {
                client.complete(ChatRequest.ofUser("openrouter/does-not-exist", "Fasse das zusammen."))
            }

            error.message.orEmpty() shouldContain "No endpoints found for this model."
            server.requestCount shouldBe 1
        }
    }

    // Nothing left to turn down. A second identical request would cost the user twice for one answer.
    test("an answer already asked for at the floor is not asked for again") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"choices":[{"finish_reason":"length","message":{"content":"","reasoning":"hmm"}}]}""",
                ),
            )
            val client = OpenAiCompatibleClient(
                ProviderConfig(baseUrl = server.url("/v1/").toString(), apiKey = "test"),
            )

            shouldThrow<DictateApiException> {
                client.complete(
                    ChatRequest.ofUser("some/thinker", "Fasse das zusammen.", reasoningEffort = "low"),
                )
            }

            server.requestCount shouldBe 1
        }
    }
})
