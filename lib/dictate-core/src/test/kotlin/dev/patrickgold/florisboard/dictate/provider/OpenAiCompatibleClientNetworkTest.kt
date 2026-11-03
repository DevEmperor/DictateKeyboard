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
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
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

    test("a project that may not use the model is told so, not to check its key") {
        // OpenAI's own answers, asked of the endpoint on 2026-11-02 (project id shortened): a project with a
        // model allow-list, and a model that does not exist.
        val answers = listOf(
            403 to """{"error":{"message":"Project `proj_x` does not have access to model `gpt-6-luna`",""" +
                """"type":"invalid_request_error","param":null,"code":"model_not_found"}}""",
            404 to """{"error":{"message":"The model `no-such-model` does not exist or you do not have access to it.",""" +
                """"type":"invalid_request_error","param":null,"code":"model_not_found"}}""",
        )
        for ((status, body) in answers) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(status).setBody(body))
                val client = OpenAiCompatibleClient(
                    ProviderConfig(baseUrl = server.url("/v1/").toString(), apiKey = "test"),
                )

                val error = shouldThrow<DictateApiException> {
                    client.complete(ChatRequest.ofUser("gpt-6-luna", "Fix my typos"))
                }
                error.kind shouldBe DictateApiException.Kind.MODEL_UNAVAILABLE
                error.kind.isRetryable shouldBe false
            }
        }
    }

    test("a rejected key is still a key problem") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(401).setBody(
                    """{"error":{"message":"Incorrect API key provided.","type":"invalid_request_error",""" +
                        """"code":"invalid_api_key"}}""",
                ),
            )
            val client = OpenAiCompatibleClient(ProviderConfig(baseUrl = server.url("/v1/").toString(), apiKey = "x"))

            shouldThrow<DictateApiException> {
                client.complete(ChatRequest.ofUser("gpt-6-luna", "Fix my typos"))
            }.kind shouldBe DictateApiException.Kind.INVALID_API_KEY
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

    // Together's /models, shortened from the live answer of 2026-11-03: an array, no `data` around it.
    test("a model list sent as a bare array is read like one wrapped in data") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """[{"id":"openai/gpt-oss-120b","object":"model","type":"chat","running":false},""" +
                        """{"id":"deepseek-ai/DeepSeek-V4.1-Flash","object":"model","type":"chat"}]""",
                ),
            )
            val client = OpenAiCompatibleClient(
                ProviderConfig(baseUrl = server.url("/v1/").toString(), apiKey = "test"),
            )

            client.listModels().map { it.id } shouldBe listOf("deepseek-ai/DeepSeek-V4.1-Flash", "openai/gpt-oss-120b")
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

    // Issue #416: a refusal whose wording matches no keyword is UNKNOWN, and UNKNOWN used to be retried —
    // three more sends, three seconds apart, before the error the first answer already held. The body is
    // the shape of such a refusal, not a recorded one. The queued answer behind it is what a retry would
    // have received, so a regression shows up as a success rather than as a hang.
    test("a refused request is named at once, not sent four times") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(400).setBody(
                    """{"error":{"message":"The model `llama-3.1-8b-instant` has been decommissioned and """ +
                        """is no longer supported.","type":"invalid_request_error","code":"model_decommissioned"}}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"Hallo."}}]}"""),
            )
            val client = OpenAiCompatibleClient(
                ProviderConfig(baseUrl = server.url("/v1/").toString(), apiKey = "test"),
            )

            val error = shouldThrow<DictateApiException> {
                client.complete(ChatRequest.ofUser("llama-3.1-8b-instant", "Fix my typos"))
            }

            error.kind shouldBe DictateApiException.Kind.UNKNOWN
            error.message.orEmpty() shouldContain "decommissioned"
            server.requestCount shouldBe 1
        }
    }

    // The reasoning-effort fallback sat behind the same retries: a model that will not take the field was
    // asked four times *with* it before anything asked without it (#416).
    test("a model that will not take reasoning_effort is asked again without it straight away") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(400).setBody(
                    """{"error":{"message":"`reasoning_effort` is not supported with this model",""" +
                        """"type":"invalid_request_error"}}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"Hallo."}}]}"""),
            )
            val client = OpenAiCompatibleClient(
                ProviderConfig(baseUrl = server.url("/v1/").toString(), apiKey = "test"),
            )

            val result = client.complete(
                ChatRequest.ofUser("some/non-thinker", "Fix my typos", reasoningEffort = "minimal"),
            )

            result.text shouldBe "Hallo."
            server.takeRequest().body.readUtf8() shouldContain "\"reasoning_effort\":\"minimal\""
            server.takeRequest().body.readUtf8() shouldNotContain "reasoning_effort"
            server.requestCount shouldBe 2
        }
    }

    // The refusals below are OpenAI's own, verbatim, from 2026-10-16. Leaving the field out after them gave
    // the model its default, medium — the opposite of the least thinking that "minimal" asked for.
    fun refusal(value: String, supported: String) = MockResponse().setResponseCode(400).setBody(
        """{"error":{"message":"Unsupported value: 'reasoning_effort' does not support '$value' with this """ +
            """model. Supported values are: $supported.","type":"invalid_request_error",""" +
            """"param":"reasoning_effort","code":"unsupported_value"}}""",
    )

    test("a refused effort is asked again at the nearest one the provider names, and from then on at once") {
        MockWebServer().use { server ->
            server.enqueue(refusal("minimal", "'none', 'low', 'medium', 'high', and 'xhigh'"))
            repeat(2) {
                server.enqueue(
                    MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"Hi."}}]}"""),
                )
            }
            val client = OpenAiCompatibleClient(
                ProviderConfig(baseUrl = server.url("/v1/").toString(), apiKey = "test"),
            )
            val request = ChatRequest.ofUser("gpt-6-luna", "Fix my typos", reasoningEffort = "minimal")

            client.complete(request).text shouldBe "Hi."
            client.complete(request).text shouldBe "Hi."

            server.takeRequest().body.readUtf8() shouldContain "\"reasoning_effort\":\"minimal\""
            server.takeRequest().body.readUtf8() shouldContain "\"reasoning_effort\":\"none\""
            server.takeRequest().body.readUtf8() shouldContain "\"reasoning_effort\":\"none\""
            server.requestCount shouldBe 3
        }
    }

    test("a refused none goes up to the lowest effort the model takes") {
        MockWebServer().use { server ->
            server.enqueue(refusal("none", "'low', 'medium', 'high', and 'xhigh'"))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"Hi."}}]}"""),
            )
            val client = OpenAiCompatibleClient(
                ProviderConfig(baseUrl = server.url("/v1/").toString(), apiKey = "test"),
            )

            client.complete(ChatRequest.ofUser("gpt-6.1-sol", "Fix my typos", reasoningEffort = "none"))
                .text shouldBe "Hi."

            server.takeRequest()
            server.takeRequest().body.readUtf8() shouldContain "\"reasoning_effort\":\"low\""
        }
    }

    // Gemini's answer to "none" on gemini-3.5-flash-lite, verbatim from 2026-11-03: an array, and not a word
    // about the field. Low, minimal and high go through on the same model.
    val bareInvalidArgument = MockResponse().setResponseCode(400).setBody(
        """[{"error":{"code":400,"message":"Request contains an invalid argument.","status":"INVALID_ARGUMENT"}}]""",
    )

    test("a bare 400 is asked once more without the effort, and that is kept when it answers") {
        MockWebServer().use { server ->
            server.enqueue(bareInvalidArgument)
            repeat(2) {
                server.enqueue(
                    MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"Hi."}}]}"""),
                )
            }
            val client = OpenAiCompatibleClient(
                ProviderConfig(baseUrl = server.url("/v1beta/openai/").toString(), apiKey = "test"),
            )
            val request = ChatRequest.ofUser("gemini-3.5-flash-lite", "Fix my typos", reasoningEffort = "none")

            client.complete(request).text shouldBe "Hi."
            client.complete(request).text shouldBe "Hi."

            server.takeRequest().body.readUtf8() shouldContain "\"reasoning_effort\":\"none\""
            server.takeRequest().body.readUtf8() shouldNotContain "reasoning_effort"
            server.takeRequest().body.readUtf8() shouldNotContain "reasoning_effort"
            server.requestCount shouldBe 3
        }
    }

    test("a bare 400 that the effort did not cause reaches the user as it was, and nothing is kept") {
        MockWebServer().use { server ->
            server.enqueue(bareInvalidArgument)
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Second refusal"}}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"Hi."}}]}"""))
            val client = OpenAiCompatibleClient(
                ProviderConfig(baseUrl = server.url("/v1beta/openai/").toString(), apiKey = "test"),
            )
            val request = ChatRequest.ofUser("gemini-3.6-flash", "Fix my typos", reasoningEffort = "low")

            val e = shouldThrow<DictateApiException> { client.complete(request) }
            e.message shouldContain "Request contains an invalid argument."
            client.complete(request).text shouldBe "Hi."

            server.takeRequest()
            server.takeRequest()
            server.takeRequest().body.readUtf8() shouldContain "\"reasoning_effort\":\"low\""
        }
    }

    test("the nearest supported effort: ties go lower, and nothing is made up") {
        val luna = "Supported values are: 'none', 'low', 'medium', 'high', and 'xhigh'."
        OpenAiCompatibleClient.nearestSupportedEffort("minimal", luna) shouldBe "none"
        OpenAiCompatibleClient.nearestSupportedEffort("max", luna) shouldBe "xhigh"
        OpenAiCompatibleClient.nearestSupportedEffort("Minimal ", luna) shouldBe "none"
        // A value of the user's own (Custom) has no place on the scale to be near to.
        OpenAiCompatibleClient.nearestSupportedEffort("turbo", luna) shouldBe null
        // A refusal that names no list leaves the field out, as before.
        OpenAiCompatibleClient.nearestSupportedEffort("minimal", "`reasoning_effort` is not supported") shouldBe
            null
        OpenAiCompatibleClient.nearestSupportedEffort("minimal", null) shouldBe null
    }

    test("only a refusal loses its retries; a busy server, a lost connection and a 408 keep them") {
        fun http(status: Int) = DictateApiException.fromHttp(status, "Something went wrong")

        http(400).isRetryable shouldBe false
        http(404).isRetryable shouldBe false
        http(408).isRetryable shouldBe true
        http(425).isRetryable shouldBe true
        http(500).isRetryable shouldBe true
        http(503).isRetryable shouldBe true
        DictateApiException(DictateApiException.Kind.NETWORK, "reset").isRetryable shouldBe true
        DictateApiException(DictateApiException.Kind.TIMEOUT, "timeout").isRetryable shouldBe true
        // The resend chip still reads the kind: a resend the user taps is theirs to try, not a loop of ours.
        http(400).kind.isRetryable shouldBe true
    }

    // Issue #438: a connection that stalls was sent four times, each attempt waiting out the whole timeout,
    // so the error took eight minutes at the default. The queued answer behind the stall is what a retry
    // would have received, so a regression shows up as a success rather than as a hang.
    test("a request that stalled for its whole budget is not sent again") {
        val audio = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-test-audio".encodeToByteArray())
        }
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Hallo Welt"}"""))
                val client = OpenAiCompatibleClient(
                    ProviderConfig(baseUrl = server.url("/v1/").toString(), apiKey = "test", timeoutSeconds = 1),
                )

                val error = shouldThrow<DictateApiException> {
                    client.transcribe(TranscriptionRequest(audioFile = audio, model = "whisper-1"))
                }

                error.kind shouldBe DictateApiException.Kind.TIMEOUT
                server.requestCount shouldBe 1
            }
        } finally {
            audio.delete()
        }
    }

    // Judged by the time spent, not by the kind of error: a gateway that gives up after a long wait answers
    // with a 5xx, not a timeout, and repeating it multiplies the wait just the same. An error inside the
    // budget is still retried; once the request has been going for the budget, the pause included, it ends.
    test("an error is retried only while the request is inside its budget") {
        val audio = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-test-audio".encodeToByteArray())
        }
        try {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setResponseCode(504).setHeadersDelay(1, TimeUnit.SECONDS),
                )
                server.enqueue(MockResponse().setResponseCode(504))
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Hallo Welt"}"""))
                val client = OpenAiCompatibleClient(
                    ProviderConfig(baseUrl = server.url("/v1/").toString(), apiKey = "test", timeoutSeconds = 2),
                )

                val error = shouldThrow<DictateApiException> {
                    client.transcribe(TranscriptionRequest(audioFile = audio, model = "whisper-1"))
                }

                error.kind shouldBe DictateApiException.Kind.SERVER_ERROR
                server.requestCount shouldBe 2
            }
        } finally {
            audio.delete()
        }
    }

    // --- Azure Speech / MAI-Transcribe (issue #349) ---

    test("Azure carries every option in the definition part, with MAI switched on") {
        val audio = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-test-audio".encodeToByteArray())
        }
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {"durationMilliseconds":1820,
                     "combinedPhrases":[{"channel":0,"text":"Hallo Welt","locale":"de"}],
                     "phrases":[{"text":"Hallo Welt","offsetMilliseconds":0}]}
                    """.trimIndent(),
                ),
            )
            val client = OpenAiCompatibleClient(
                ProviderConfig(
                    baseUrl = server.url("/").toString(),
                    apiKey = "resource-key",
                    transcriptionApi = TranscriptionApi.AZURE_FAST_TRANSCRIPTION,
                ),
            )

            val result = client.transcribe(
                TranscriptionRequest(
                    audioFile = audio,
                    model = "MAI-Transcribe-2",
                    language = "de",
                    // The hint the app actually composes: the punctuation sample sentence with the
                    // user's glossary appended behind it (DictatePromptDefaults.appendCustomWords).
                    prompt = "Hallo. Vielen Dank. DevEmperor, Dictate",
                ),
            )
            val recorded = server.takeRequest()
            val body = recorded.body.readUtf8()

            result.text shouldBe "Hallo Welt"
            // The api-version is not decoration: `enhancedMode` — the switch that selects MAI at all —
            // arrived with it, and an older one would run the request on Azure's ordinary model instead.
            recorded.path shouldBe "/speechtotext/transcriptions:transcribe?api-version=2025-10-15"
            // Azure authenticates with its own header; a Bearer token is not accepted here.
            recorded.getHeader("Ocp-Apim-Subscription-Key") shouldBe "resource-key"
            recorded.getHeader("Authorization") shouldBe null
            body shouldContain "name=\"audio\""
            body shouldContain "name=\"definition\""
            body shouldContain "RIFF-test-audio"
            body shouldContain "\"enabled\":true"
            body shouldContain "\"model\":\"MAI-Transcribe-2\""
            // The point of the whole provider: fillers and false starts dropped before we ever see them.
            body shouldContain "\"transcribeStyle\":\"clean\""
            body shouldContain "\"locales\":[\"de\"]"
            // The style hint has no prose slot on a dedicated STT model, so its terms bias recognition —
            // and the sample sentence in front of them must not come along. Azure calls this field
            // keyword biasing, so "Hallo. Vielen Dank." in it would nudge every dictation towards a
            // greeting nobody asked for.
            body shouldContain "\"phrases\":[\"DevEmperor\",\"Dictate\"]"
            body shouldNotContain "Vielen Dank"
        }
    }

    test("Azure auto-detect pins no language at all, and the older generation gets no style") {
        val audio = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-test-audio".encodeToByteArray())
        }
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"combinedPhrases":[{"text":"Hello"}]}"""),
            )
            val client = OpenAiCompatibleClient(
                ProviderConfig(
                    baseUrl = server.url("/").toString(),
                    apiKey = "resource-key",
                    transcriptionApi = TranscriptionApi.AZURE_FAST_TRANSCRIPTION,
                ),
            )

            client.transcribe(
                TranscriptionRequest(audioFile = audio, model = "MAI-Transcribe-1.5", language = "detect"),
            )
            val body = server.takeRequest().body.readUtf8()

            // Microsoft calls a pinned locale "a very strong hint" and advises against one without cause;
            // detecting across 60 languages is what this model is for.
            body shouldNotContain "locales"
            // transcribeStyle is documented for the 2 generation alone, so 1.5 is asked for nothing.
            body shouldNotContain "transcribeStyle"
            body shouldNotContain "phraseList"
        }
    }

    test("Azure reads the whole transcript, not the first piece of it") {
        val audio = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-test-audio".encodeToByteArray())
        }
        MockWebServer().use { server ->
            // Code switching is what MAI is good at, and it comes back as one entry per language —
            // reading `first()` would drop half the sentence.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"combinedPhrases":[{"text":"Ich schicke dir"},{"text":"the invoice tomorrow"}]}""",
                ),
            )
            // A response with no combined text at all still has the timed pieces to fall back on.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"combinedPhrases":[],"phrases":[{"text":"Guten"},{"text":"Morgen"}]}""",
                ),
            )
            val client = OpenAiCompatibleClient(
                ProviderConfig(
                    baseUrl = server.url("/").toString(),
                    apiKey = "k",
                    transcriptionApi = TranscriptionApi.AZURE_FAST_TRANSCRIPTION,
                ),
            )
            val request = TranscriptionRequest(audioFile = audio, model = "MAI-Transcribe-2")

            client.transcribe(request).text shouldBe "Ich schicke dir the invoice tomorrow"
            client.transcribe(request).text shouldBe "Guten Morgen"
        }
    }

    test("an Azure endpoint that was never filled in is refused before anything is sent") {
        val audio = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-test-audio".encodeToByteArray())
        }
        // Every other provider has one address for everyone; an Azure resource has its own, so the preset
        // carries none and the settings field stands open. Both ways of leaving it that way have to be
        // caught here: an empty box, and the shape pasted in from the hint or from Microsoft's sample.
        // Neither resolves to anything, and the failure would otherwise read as "no internet".
        ProviderRegistry.AZURE.baseUrl shouldBe ""
        listOf(ProviderRegistry.AZURE.baseUrl, "https://<your-resource>.cognitiveservices.azure.com/")
            .forAll { unusable ->
                val client = OpenAiCompatibleClient(
                    ProviderConfig(
                        baseUrl = unusable,
                        apiKey = "resource-key",
                        transcriptionApi = TranscriptionApi.AZURE_FAST_TRANSCRIPTION,
                    ),
                )

                val error = shouldThrow<DictateApiException> {
                    client.transcribe(TranscriptionRequest(audioFile = audio, model = "MAI-Transcribe-2"))
                }

                error.message.orEmpty() shouldContain "Keys and Endpoint"
                // The kind that offers to open the provider's settings — the one screen where this is fixed.
                error.kind shouldBe DictateApiException.Kind.INVALID_API_KEY

                // And the connection test says the same thing, rather than counting the two curated ids
                // and reporting a success it never verified.
                shouldThrow<DictateApiException> { client.listModels() }
                    .message.orEmpty() shouldContain "Keys and Endpoint"
            }
    }

    test("Azure asks no catalog and offers the two ids that exist") {
        val client = OpenAiCompatibleClient(
            ProviderConfig(
                baseUrl = "https://my-speech.cognitiveservices.azure.com/",
                apiKey = "resource-key",
                transcriptionApi = TranscriptionApi.AZURE_FAST_TRANSCRIPTION,
                curatedModels = ProviderRegistry.AZURE.curatedTranscriptionModels,
            ),
        )

        // No server is running: reaching for one would fail the test rather than time out silently.
        client.listModels().map { it.id } shouldBe listOf("MAI-Transcribe-2", "MAI-Transcribe-1.5")
    }

    test("the Chinese variants the app offers are narrowed to the codes MAI's table holds") {
        // The app distinguishes zh-CN from zh-TW for the providers that care; MAI's language table is
        // written in bare codes, and a code it does not hold is worse than none at all.
        OpenAiCompatibleClient.azureLocale("zh-CN") shouldBe "zh"
        OpenAiCompatibleClient.azureLocale("yue-HK") shouldBe "yue"
        OpenAiCompatibleClient.azureLocale("de") shouldBe "de"
        OpenAiCompatibleClient.azureLocale("detect") shouldBe null
        OpenAiCompatibleClient.azureLocale(null) shouldBe null
        OpenAiCompatibleClient.azureLocale("") shouldBe null
    }

    test("a later MAI generation inherits the clean transcript instead of losing it") {
        OpenAiCompatibleClient.azureSupportsTranscribeStyle("MAI-Transcribe-2") shouldBe true
        OpenAiCompatibleClient.azureSupportsTranscribeStyle("MAI-Transcribe-3") shouldBe true
        OpenAiCompatibleClient.azureSupportsTranscribeStyle("mai-transcribe-1.5") shouldBe false
        OpenAiCompatibleClient.azureSupportsTranscribeStyle("MAI-Transcribe-1") shouldBe false
    }

    // A vocabulary term is a name, and a name has no full stop with a space behind it. Shared by Azure's
    // phraseList (#349) and Google's custom_vocabulary (#292), which had the same leak.
    test("a bias term never carries the sentence that stood in front of it") {
        // What the leak looked like: the glossary's first entry arrives welded to the sample sentence.
        OpenAiCompatibleClient.afterLastSentenceEnd("Hallo. Vielen Dank. DevEmperor") shouldBe "DevEmperor"
        OpenAiCompatibleClient.afterLastSentenceEnd("你好。谢谢。 Dictate") shouldBe "Dictate"
        OpenAiCompatibleClient.afterLastSentenceEnd("नमस्ते। धन्यवाद। Dictate") shouldBe "Dictate"

        // Everything that was already a term comes through untouched — including the abbreviation that
        // ends in a stop, which is why the rule asks for the space rather than the stop alone.
        listOf("DevEmperor", "FlorisBoard", "Dr.", "z.B.", "Anna-Lena Müller").forAll {
            OpenAiCompatibleClient.afterLastSentenceEnd(it) shouldBe it
        }
    }

    // --- Scaleway (issue #423) ---

    // Both halves of Scaleway are plain OpenAI on one host, which is the whole reason the preset needed no
    // wire format of its own. This holds it to that: the path under `/v1/`, a Bearer key, and the singular
    // `language` that whisper-large-v3 reads.
    test("Scaleway transcribes over plain OpenAI multipart with a Bearer key") {
        val audio = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-test-audio".encodeToByteArray())
        }
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Guten Morgen"}"""))
                val preset = ProviderRegistry.SCALEWAY
                val client = OpenAiCompatibleClient.from(
                    preset, "scw-secret", baseUrlOverride = server.url("/v1/").toString(),
                )

                val result = client.transcribe(
                    TranscriptionRequest(audio, preset.defaultTranscriptionModel.orEmpty(), language = "de"),
                )
                val recorded = server.takeRequest()
                val body = recorded.body.readUtf8()

                result.text shouldBe "Guten Morgen"
                recorded.path shouldBe "/v1/audio/transcriptions"
                recorded.getHeader("Authorization") shouldBe "Bearer scw-secret"
                body shouldContain "name=\"model\"\r\n\r\nwhisper-large-v3"
                body shouldContain "name=\"language\"\r\n\r\nde"
                body shouldNotContain "name=\"languages"
            }
        } finally {
            audio.delete()
        }
    }

    // Measured 2026-09-25 with a key on an account that had no payment method yet: the key is valid, and
    // every request is still refused, in these words and in Scaleway's flat error shape rather than
    // OpenAI's envelope. What has to survive is the kind — quota, not a wrong key, because the key is
    // fine — and Scaleway's own sentence rather than the raw JSON around it.
    test("a Scaleway account without quota reads as quota, in Scaleway's own words") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(429)
                    .setHeader("x-ratelimit-limit-requests", "0")
                    .setBody(
                        """{"status":429,"error":"INSUFFICIENT QUOTA","message":"You exceeded your """ +
                            """current quota of requests per minute. Slow down your usage or increase your """ +
                            """quotas."}""",
                    ),
            )
            val preset = ProviderRegistry.SCALEWAY
            val client = OpenAiCompatibleClient.from(
                preset, "scw-secret", baseUrlOverride = server.url("/v1/").toString(),
            )

            val error = shouldThrow<DictateApiException> {
                client.complete(ChatRequest.ofUser(preset.defaultChatModel.orEmpty(), "Hallo"))
            }

            error.kind shouldBe DictateApiException.Kind.QUOTA_EXCEEDED
            error.message.orEmpty() shouldStartWith "You exceeded your current quota"
            server.requestCount shouldBe 1
        }
    }

    // The model server behind Scaleway's gateway answers in OpenAI's envelope, but with `"code":400` as a
    // number, and a String-typed field used to refuse the whole envelope over it. Both bodies are what the
    // endpoint returned on 2026-09-25. What has to come out is the provider's sentence rather than the
    // JSON, and a kind the app can act on: an oversized file offers to keep the recording, and a format it
    // refuses gets the untouched WAV instead (#281). Two responses each, so a regression back to a retried
    // UNKNOWN fails on the count rather than hanging on an empty queue.
    test("Scaleway's refusals are read as the size and format errors they are") {
        val audio = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-test-audio".encodeToByteArray())
        }
        val tooBig = """{"error":{"message":"Maximum file size exceeded (parameter=audio_filesize_mb, """ +
            """value=25.000041961669922)","type":"BadRequestError","param":"audio_filesize_mb","code":400}}"""
        val badFormat = """{"error":{"message":"Invalid file format. Please convert your file to a """ +
            """supported format: ['flac', 'm4a', 'mpeg', 'mp2', 'mp3', 'mp4', 'ogg', 'wav', 'webm'].",""" +
            """"type":"BadRequestError","param":null,"code":400}}"""
        try {
            listOf(
                tooBig to DictateApiException.Kind.CONTENT_SIZE_LIMIT,
                badFormat to DictateApiException.Kind.FORMAT_NOT_SUPPORTED,
            ).forAll { (body, kind) ->
                MockWebServer().use { server ->
                    repeat(2) { server.enqueue(MockResponse().setResponseCode(400).setBody(body)) }
                    val client = OpenAiCompatibleClient.from(
                        ProviderRegistry.SCALEWAY, "scw-secret", baseUrlOverride = server.url("/v1/").toString(),
                    )

                    val error = shouldThrow<DictateApiException> {
                        client.transcribe(TranscriptionRequest(audio, "whisper-large-v3"))
                    }

                    error.kind shouldBe kind
                    error.code shouldBe "400"
                    error.message.orEmpty() shouldNotContain "\"error\""
                    server.requestCount shouldBe 1
                }
            }
        } finally {
            audio.delete()
        }
    }

    // A wrong key is a 403 at Scaleway, not a 401, and the credentials step of the connection test is the
    // catalog request below — so it is the one that has to name the key.
    test("a wrong Scaleway key is named as one, though it arrives as a 403") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(403).setBody(
                    """{"status":403,"error":"FORBIDDEN","message":"insufficient permissions to access the resource"}""",
                ),
            )
            val client = OpenAiCompatibleClient.from(
                ProviderRegistry.SCALEWAY, "not-a-key", baseUrlOverride = server.url("/v1/").toString(),
            )

            val error = shouldThrow<DictateApiException> { client.listModels() }

            error.kind shouldBe DictateApiException.Kind.INVALID_API_KEY
            error.message.orEmpty() shouldContain "insufficient permissions"
            server.takeRequest().path shouldBe "/v1/models"
        }
    }

    // --- OVHcloud (issue #423) ---

    // One base URL for the whole catalog, which is what made OVHcloud the same plain preset as Scaleway
    // rather than the per-model editor the reporter expected.
    test("OVHcloud transcribes over plain OpenAI multipart with a Bearer key") {
        val audio = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-test-audio".encodeToByteArray())
        }
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Guten Morgen"}"""))
                val preset = ProviderRegistry.OVHCLOUD
                val client = OpenAiCompatibleClient.from(
                    preset, "ovh-token", baseUrlOverride = server.url("/v1/").toString(),
                )

                val result = client.transcribe(
                    TranscriptionRequest(audio, preset.defaultTranscriptionModel.orEmpty(), language = "de"),
                )
                val recorded = server.takeRequest()
                val body = recorded.body.readUtf8()

                result.text shouldBe "Guten Morgen"
                recorded.path shouldBe "/v1/audio/transcriptions"
                recorded.getHeader("Authorization") shouldBe "Bearer ovh-token"
                body shouldContain "name=\"model\"\r\n\r\nwhisper-large-v3"
                body shouldContain "name=\"language\"\r\n\r\nde"
            }
        } finally {
            audio.delete()
        }
    }

    // OVHcloud serves anonymous requests, so the worry is a wrong key being served as no key at all — a
    // connection test that could never fail. It is not: both bodies are what the gateway answered on
    // 2026-09-25, flat rather than enveloped, and each has to arrive as its own kind with its own words.
    test("OVHcloud's gateway names a wrong key and a spent rate limit as what they are") {
        listOf(
            403 to """{"message":"Forbidden: authentication failed.  Please generate a new one at """ +
                """https://kepler.ai.cloud.ovh.net/v1/oauth/ovh/authorize?iam_action=publicCloudProject:""" +
                """ai:endpoints/call"}""",
            429 to """{"message":"API rate limit exceeded","request_id":"698f0f1a6059fc751c903236eec22a05"}""",
        ).forAll { (status, body) ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(status).setBody(body))
                val client = OpenAiCompatibleClient.from(
                    ProviderRegistry.OVHCLOUD, "not-a-key", baseUrlOverride = server.url("/v1/").toString(),
                )

                val error = shouldThrow<DictateApiException> { client.listModels() }

                if (status == 403) {
                    error.kind shouldBe DictateApiException.Kind.INVALID_API_KEY
                    error.message.orEmpty() shouldStartWith "Forbidden: authentication failed."
                } else {
                    error.kind shouldBe DictateApiException.Kind.QUOTA_EXCEEDED
                    error.message shouldBe "API rate limit exceeded"
                }
                server.requestCount shouldBe 1
            }
        }
    }

    // --- xAI Grok Voice Transcribe (issue #435) ---

    test("the xAI preset transcribes and streams on the key it already rewords with") {
        val xai = ProviderRegistry.XAI
        xai.capabilities.chat shouldBe true
        xai.capabilities.transcription shouldBe true
        xai.transcriptionApi shouldBe TranscriptionApi.XAI_STT
        xai.supportsRealtime shouldBe true
        xai.realtimeApi shouldBe RealtimeApi.XAI
        xai.defaultTranscriptionModel shouldBe "grok-voice-transcribe-2.0"
        // Documented: MKV only with MP3, AAC or FLAC inside, so WebM's Opus is not promised; AMR not named.
        xai.acceptedAudioContainers shouldNotContain AudioContainer.WEBM
        xai.acceptedAudioContainers shouldNotContain AudioContainer.AMR
        ProviderRegistry.maxUploadBytes("xai") shouldBe 500_000_000L
    }

    // The three ways this endpoint differs from OpenAI's, each held here: the path and its fields, the
    // file part last, and the vocabulary as `keyterm` with the sample sentence left behind.
    test("xAI sends every field before the file, and the glossary as key terms") {
        val audio = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-test-audio".encodeToByteArray())
        }
        try {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setResponseCode(200).setBody(
                        """
                        {"text":"Hallo Welt","language":"de","duration":1.2,
                         "words":[{"text":"Hallo","start":0.1,"end":0.5},{"text":"Welt","start":0.5,"end":1.0}]}
                        """.trimIndent(),
                    ),
                )
                val client = OpenAiCompatibleClient.from(
                    ProviderRegistry.XAI, "xai-test", baseUrlOverride = server.url("/v1/").toString(),
                )

                val result = client.transcribe(
                    TranscriptionRequest(
                        audioFile = audio,
                        model = "grok-voice-transcribe-2.0",
                        language = "de",
                        prompt = "Hallo. Vielen Dank. DevEmperor, Dictate, " +
                            "Donaudampfschifffahrtsgesellschaftskapitänswitwenrente",
                    ),
                )
                val recorded = server.takeRequest()
                val body = recorded.body.readUtf8()

                result.text shouldBe "Hallo Welt"
                recorded.path shouldBe "/v1/stt"
                recorded.getHeader("Authorization") shouldBe "Bearer xai-test"
                body shouldContain "name=\"model\"\r\n\r\ngrok-voice-transcribe-2.0"
                // The language alone only formats numbers when `format` is on, and `format` without a
                // language is a 400 — so they travel as a pair.
                body shouldContain "name=\"language\"\r\n\r\nde"
                body shouldContain "name=\"format\"\r\n\r\ntrue"
                body shouldContain "name=\"keyterm\"\r\n\r\nDevEmperor"
                body shouldContain "name=\"keyterm\"\r\n\r\nDictate"
                body shouldNotContain "Vielen Dank"
                // Past xAI's 50 characters a term, so it is left out rather than refused with the rest.
                body shouldNotContain "Donaudampfschifffahrt"
                // Fields after the file "may be ignored" by xAI's streaming upload: nothing may follow it.
                val file = body.indexOf("name=\"file\"")
                listOf("model", "language", "format", "keyterm").forAll { field ->
                    body.lastIndexOf("name=\"$field\"") shouldBeLessThan file
                }
                body shouldNotContain "name=\"prompt\""
                body shouldNotContain "name=\"response_format\""
            }
        } finally {
            audio.delete()
        }
    }

    test("xAI auto-detect sends neither the language nor the formatting switch, and no sample as a term") {
        val audio = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-test-audio".encodeToByteArray())
        }
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Hello"}"""))
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Привіт"}"""))
                val client = OpenAiCompatibleClient.from(
                    ProviderRegistry.XAI, "xai-test", baseUrlOverride = server.url("/v1/").toString(),
                )

                client.transcribe(TranscriptionRequest(audio, "grok-voice-transcribe-2.0", language = "detect"))
                // Ukrainian is transcribed but not in xAI's formatting table, and the hint is the bare
                // sample sentence the app sends when there are no custom words.
                client.transcribe(
                    TranscriptionRequest(
                        audio, "grok-voice-transcribe-2.0", language = "uk", prompt = "Привіт. Дуже дякую.",
                    ),
                )

                listOf(server.takeRequest(), server.takeRequest()).forAll { recorded ->
                    val body = recorded.body.readUtf8()
                    body shouldNotContain "name=\"language\""
                    body shouldNotContain "name=\"format\""
                    body shouldNotContain "name=\"keyterm\""
                }
            }
        } finally {
            audio.delete()
        }
    }

    test("the app's language codes become the ones xAI formats for, or none") {
        OpenAiCompatibleClient.xaiLanguage("de") shouldBe "de"
        OpenAiCompatibleClient.xaiLanguage("pt-BR") shouldBe "pt"
        // Whisper's Tagalog is the Filipino xAI lists as `fil`.
        OpenAiCompatibleClient.xaiLanguage("tl") shouldBe "fil"
        OpenAiCompatibleClient.xaiLanguage("zh-CN") shouldBe null
        OpenAiCompatibleClient.xaiLanguage("uk") shouldBe null
        OpenAiCompatibleClient.xaiLanguage("detect") shouldBe null
        OpenAiCompatibleClient.xaiLanguage(null) shouldBe null
    }

    // Measured 2026-09-29 against api.x.ai: a wrong key is a 400, and the sentence sits in `error` as a
    // string. Both have to come through — the kind from the sentence, and the sentence instead of JSON.
    test("a wrong xAI key is named as one in xAI's own words, though it arrives as a 400") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(400).setBody(
                    """{"code":"invalid-argument","error":"Incorrect API key provided. """ +
                        """You can obtain an API key from https://console.x.ai."}""",
                ),
            )
            val client = OpenAiCompatibleClient.from(
                ProviderRegistry.XAI, "xai-wrong", baseUrlOverride = server.url("/v1/").toString(),
            )

            val error = shouldThrow<DictateApiException> { client.listModels() }

            error.kind shouldBe DictateApiException.Kind.INVALID_API_KEY
            error.message shouldBe
                "Incorrect API key provided. You can obtain an API key from https://console.x.ai."
            error.code shouldBe "invalid-argument"
            server.requestCount shouldBe 1
        }
    }

    // The bare sample sentence is the whole hint whenever the glossary is empty, and its last sentence
    // used to come through as a two-word bias term for every dedicated STT model (#435).
    test("a hint with no glossary behind its sample sentence yields no bias term") {
        listOf(
            "Hallo. Vielen Dank.",
            "Hello. Thank you very much.",
            "สวัสดี. ขอบคุณมาก.",
            "你好。非常感谢。",
            "こんにちは。どうもありがとうございます。",
            "नमस्ते। बहुत-बहुत धन्यवाद।",
        ).forAll { OpenAiCompatibleClient.vocabularyTermOf(it) shouldBe null }

        OpenAiCompatibleClient.vocabularyTermOf("Hallo. Vielen Dank. DevEmperor") shouldBe "DevEmperor"
        OpenAiCompatibleClient.vocabularyTermOf("你好。非常感谢。 DevEmperor") shouldBe "DevEmperor"
        OpenAiCompatibleClient.vocabularyTermOf("Dr. Meier") shouldBe "Meier"
        listOf("DevEmperor", "Dr.", "z.B.", "Anna-Lena Müller", "FlorisBoard").forAll {
            OpenAiCompatibleClient.vocabularyTermOf(" $it ") shouldBe it
        }
    }

    // #455: only a server that was never reached may be handed to another provider. Once a connection was
    // made, the provider has (some of) the audio, and a timeout or a reset there is #391's slow answer.
    test("a connection that could not be made is told apart from one that broke or went quiet") {
        fun io(cause: Throwable) = DictateApiException.fromIo(cause)

        io(UnknownHostException("Unable to resolve host \"home.lan\"")).isConnectFailure shouldBe true
        io(ConnectException("Failed to connect to /192.168.1.20:8000")).isConnectFailure shouldBe true
        io(NoRouteToHostException("No route to host")).isConnectFailure shouldBe true
        // Android's and the JVM's words for a connect timeout.
        io(SocketTimeoutException("failed to connect to /192.168.1.20 (port 8000) from /10.0.0.5 (port 41234) after 4000ms"))
            .isConnectFailure shouldBe true
        io(SocketTimeoutException("Connect timed out")).isConnectFailure shouldBe true
        // Wrapped, as OkHttp sometimes hands it on.
        io(IOException("canceled", ConnectException("Connection refused"))).isConnectFailure shouldBe true

        io(SocketTimeoutException("timeout")).isConnectFailure shouldBe false
        io(SocketTimeoutException("Read timed out")).isConnectFailure shouldBe false
        io(SocketException("Connection reset")).isConnectFailure shouldBe false
        io(IOException("unexpected end of stream on http://192.168.1.20:8000/...")).isConnectFailure shouldBe false
        io(SSLHandshakeException("Trust anchor for certification path not found.")).isConnectFailure shouldBe false
        DictateApiException.fromHttp(502, "Bad gateway").isConnectFailure shouldBe false
    }

    test("a client with another provider waiting gives up on an unreachable server at once") {
        val audio = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-test-audio".encodeToByteArray())
        }
        // A port that was free a moment ago: nothing listens there, so the connection is refused.
        val closedPort = ServerSocket(0).use { it.localPort }
        try {
            val retries = mutableListOf<Int>()
            val client = OpenAiCompatibleClient(
                ProviderConfig(
                    baseUrl = "http://127.0.0.1:$closedPort/v1/",
                    apiKey = "test",
                    handOverUnreachable = true,
                ),
            )
            val started = System.nanoTime()

            val error = shouldThrow<DictateApiException> {
                client.transcribe(TranscriptionRequest(audioFile = audio, model = "whisper-1")) { retries += it }
            }

            error.isConnectFailure shouldBe true
            retries shouldBe emptyList()
            // Without the hand-over this is three retries three seconds apart.
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).toInt() shouldBeLessThan 2_000
        } finally {
            audio.delete()
        }
    }

    test("a server that answered keeps its retries while another provider waits") {
        val audio = createTempFile(suffix = ".wav").toFile().apply {
            writeBytes("RIFF-test-audio".encodeToByteArray())
        }
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(503))
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Hallo Welt"}"""))
                val client = OpenAiCompatibleClient(
                    ProviderConfig(baseUrl = server.url("/v1/").toString(), apiKey = "test", handOverUnreachable = true),
                )

                val result = client.transcribe(TranscriptionRequest(audioFile = audio, model = "whisper-1"))

                result.text shouldBe "Hallo Welt"
                server.requestCount shouldBe 2
            }
        } finally {
            audio.delete()
        }
    }

    test("a client with another provider waiting connects with the short timeout") {
        fun connectMillis(handOver: Boolean) = OpenAiCompatibleClient(
            ProviderConfig(baseUrl = "https://example.test/v1/", apiKey = "test", handOverUnreachable = handOver),
        ).buildClient().connectTimeoutMillis

        connectMillis(handOver = true) shouldBe 4_000
        connectMillis(handOver = false) shouldBe 8_000
    }

    // #447: Google's dedicated transcription model refused every request of a free-tier key on 2026-10-07,
    // and the chat model transcribed the same clip. The answers below are Google's own error shapes.
    fun geminiClient(server: MockWebServer) = OpenAiCompatibleClient(
        ProviderConfig(
            baseUrl = server.url("/v1beta/openai/").toString(),
            apiKey = "test",
            transcriptionApi = TranscriptionApi.GEMINI_GENERATE_CONTENT,
        ),
    )
    val geminiText = """{"candidates":[{"content":{"parts":[{"text":"Hallo Welt"}]}}]}"""

    test("a refusal of Google's transcription model hands the recording to the chat model once") {
        val refusals = listOf(
            400 to """{"error":{"code":400,"message":"Thinking is not enabled for this model","status":"INVALID_ARGUMENT"}}""",
            429 to """{"error":{"code":429,"message":"You exceeded your current quota.","status":"RESOURCE_EXHAUSTED"}}""",
            404 to """{"error":{"code":404,"message":"models/gemini-3.5-transcribe is not found","status":"NOT_FOUND"}}""",
        )
        val audio = createTempFile(suffix = ".wav").toFile().apply { writeBytes("RIFF-test-audio".encodeToByteArray()) }
        try {
            for ((status, body) in refusals) {
                MockWebServer().use { server ->
                    server.enqueue(MockResponse().setResponseCode(status).setBody(body))
                    server.enqueue(MockResponse().setResponseCode(200).setBody(geminiText))

                    val result = geminiClient(server).transcribe(TranscriptionRequest(audio, "gemini-3.5-transcribe"))

                    result.text shouldBe "Hallo Welt"
                    server.takeRequest().path shouldBe "/v1beta/interactions"
                    server.takeRequest().path shouldBe "/v1beta/models/${ProviderRegistry.GEMINI.defaultChatModel}:generateContent"
                    server.requestCount shouldBe 2
                }
            }
        } finally {
            audio.delete()
        }
    }

    test("a rejected Gemini key is not sent to the chat model as well") {
        val audio = createTempFile(suffix = ".wav").toFile().apply { writeBytes("RIFF-test-audio".encodeToByteArray()) }
        try {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setResponseCode(400).setBody(
                        """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.",""" +
                            """"status":"INVALID_ARGUMENT"}}""",
                    ),
                )
                shouldThrow<DictateApiException> {
                    geminiClient(server).transcribe(TranscriptionRequest(audio, "gemini-3.5-transcribe"))
                }.kind shouldBe DictateApiException.Kind.INVALID_API_KEY
                server.requestCount shouldBe 1
            }
        } finally {
            audio.delete()
        }
    }

    test("a model this key cannot use reads as that, not as an unknown error") {
        DictateApiException.fromHttp(400, "Thinking is not enabled for this model").kind shouldBe
            DictateApiException.Kind.MODEL_UNAVAILABLE
        DictateApiException.fromHttp(
            404,
            "Model 'gemini-3.5-transcribe-x' not found. Did you mean 'gemini-3.5-transcribe'?",
            code = "not_found",
        ).kind shouldBe DictateApiException.Kind.MODEL_UNAVAILABLE
        // The same words in a 5xx are the server's trouble, and a plain bad request stays unknown.
        DictateApiException.fromHttp(503, "Overloaded for this model").kind shouldBe DictateApiException.Kind.SERVER_ERROR
        DictateApiException.fromHttp(400, "Invalid JSON payload").kind shouldBe DictateApiException.Kind.UNKNOWN
    }
})
