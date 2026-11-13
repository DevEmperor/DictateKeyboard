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
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.File
import kotlin.io.path.createTempFile

/**
 * How much a chat model thinks while it transcribes (#477): Gemini's `generateContent` and single call. The
 * refusals are the ones Google's endpoint answered on 2026-11-13.
 */
class TranscriptionReasoningTest : FunSpec({

    fun audio(): File = createTempFile(suffix = ".wav").toFile().apply {
        writeBytes("RIFF-test-audio".encodeToByteArray())
        deleteOnExit()
    }

    fun gemini(server: MockWebServer) = OpenAiCompatibleClient(
        ProviderConfig(
            baseUrl = server.url("/v1beta/openai/").toString(),
            apiKey = "test",
            transcriptionApi = TranscriptionApi.GEMINI_GENERATE_CONTENT,
        ),
    )

    fun transcript(text: String = "Hallo Welt", usage: String? = null) = MockResponse().setResponseCode(200).setBody(
        """{"candidates":[{"content":{"parts":[{"text":"$text"}]}}]${usage?.let { ""","usageMetadata":$it""" }.orEmpty()}}""",
    )

    fun refusal(message: String) = MockResponse().setResponseCode(400).setBody(
        """{"error":{"code":400,"message":"$message","status":"INVALID_ARGUMENT"}}""",
    )

    fun RecordedRequest.json(): JsonElement = Json.parseToJsonElement(body.readUtf8())

    /** The `generationConfig.thinkingConfig` a generateContent request went out with, null for none. */
    fun RecordedRequest.thinking(): String? =
        json().jsonObject["generationConfig"]?.jsonObject?.get("thinkingConfig")?.toString()

    context("a Gemini chat model gets its setting as thinkingConfig, and none as a budget of 0") {
        withData(
            nameFn = { "${it.first} → ${it.second}" },
            "low" to """{"thinkingLevel":"LOW"}""",
            " High " to """{"thinkingLevel":"HIGH"}""",
            "none" to """{"thinkingBudget":0}""",
        ) { (effort, sent) ->
            MockWebServer().use { server ->
                server.enqueue(transcript())
                gemini(server).transcribe(TranscriptionRequest(audio(), "gemini-3.6-flash", reasoningEffort = effort))
                val request = server.takeRequest()
                request.path shouldBe "/v1beta/models/gemini-3.6-flash:generateContent"
                val config = request.json().jsonObject["generationConfig"]!!.jsonObject
                config["thinkingConfig"].toString() shouldBe sent
                config["temperature"]!!.jsonPrimitive.content shouldBe "0.0"
            }
        }
    }

    test("no setting sends no thinkingConfig, as before") {
        MockWebServer().use { server ->
            server.enqueue(transcript())
            gemini(server).transcribe(TranscriptionRequest(audio(), "gemini-3.6-flash"))
            server.takeRequest().thinking() shouldBe null
        }
    }

    test("the thinking tokens Gemini counts come back with the transcript") {
        MockWebServer().use { server ->
            server.enqueue(transcript(usage = """{"promptTokenCount":220,"thoughtsTokenCount":317}"""))
            // Gemini leaves the count out when the model did not think.
            server.enqueue(transcript(usage = """{"promptTokenCount":220}"""))
            server.enqueue(transcript())
            val client = gemini(server)
            client.transcribe(TranscriptionRequest(audio(), "gemini-3.6-flash")).thinkingTokens shouldBe 317
            client.transcribe(TranscriptionRequest(audio(), "gemini-3.6-flash")).thinkingTokens shouldBe 0
            client.transcribe(TranscriptionRequest(audio(), "gemini-3.6-flash")).thinkingTokens shouldBe null
        }
    }

    context("a refused value costs one more request, without it, and is left out from then on") {
        withData(
            "Invalid value at 'generation_config.thinking_config.thinking_level' " +
                "(type.googleapis.com/google.ai.generativelanguage.v1beta.ThinkingConfig.ThinkingLevel), \\\"NONE\\\"",
            "Thinking level MINIMAL is not supported for this model. Please retry with other thinking level.",
            "Request contains an invalid argument.",
        ) { message ->
            MockWebServer().use { server ->
                server.enqueue(refusal(message))
                server.enqueue(transcript("first"))
                server.enqueue(transcript("second"))
                val client = gemini(server)
                val request = TranscriptionRequest(audio(), "gemini-3.8-flash", reasoningEffort = "minimal")

                client.transcribe(request).text shouldBe "first"
                server.takeRequest().thinking() shouldBe """{"thinkingLevel":"MINIMAL"}"""
                server.takeRequest().thinking() shouldBe null

                client.transcribe(request).text shouldBe "second"
                server.takeRequest().thinking() shouldBe null
                server.requestCount shouldBe 3
            }
        }
    }

    test("when the second try is refused as well, the user hears the first refusal and the value is kept") {
        MockWebServer().use { server ->
            server.enqueue(refusal("Request contains an invalid argument."))
            server.enqueue(refusal("Unsupported audio"))
            server.enqueue(transcript())
            val client = gemini(server)
            val request = TranscriptionRequest(audio(), "gemini-3.6-flash", reasoningEffort = "none")

            shouldThrow<DictateApiException> { client.transcribe(request) }.message shouldBe
                "Request contains an invalid argument."
            server.takeRequest()
            server.takeRequest()
            // Nothing was learnt about the value, so the next dictation still asks for it.
            client.transcribe(request)
            server.takeRequest().thinking() shouldBe """{"thinkingBudget":0}"""
        }
    }

    test("a refused key is not asked again without the setting") {
        MockWebServer().use { server ->
            server.enqueue(refusal("API key not valid. Please pass a valid API key."))
            shouldThrow<DictateApiException> {
                gemini(server).transcribe(TranscriptionRequest(audio(), "gemini-3.6-flash", reasoningEffort = "none"))
            }.kind shouldBe DictateApiException.Kind.INVALID_API_KEY
            server.requestCount shouldBe 1
        }
    }

    test("Google's speech model gets no setting, and its stand-in what the catalog sends that model") {
        MockWebServer().use { server ->
            server.enqueue(refusal("Thinking is not enabled for this model"))
            server.enqueue(transcript())
            gemini(server).transcribe(TranscriptionRequest(audio(), "gemini-3.5-transcribe"))

            val interactions = server.takeRequest()
            interactions.path shouldBe "/v1beta/interactions"
            interactions.body.readUtf8() shouldNotContain "thinking"
            val model = ProviderRegistry.GEMINI.defaultChatModel!!
            val standIn = server.takeRequest()
            standIn.path shouldBe "/v1beta/models/$model:generateContent"
            val default = ReasoningResolver.forTranscription(ModelCatalog.spec(ProviderRegistry.GEMINI.id, model), "")
            standIn.thinking() shouldBe default?.let {
                val config = OpenAiCompatibleClient.geminiThinkingConfig(it)!!
                Json.encodeToString(OpenAiCompatibleClient.GeminiThinkingConfigDto.serializer(), config)
            }
        }
    }

    test("single call sends the setting as reasoning_effort, and asks once more without it when refused") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(400).setBody(
                    """{"error":{"message":"Unrecognized request argument supplied: reasoning_effort"}}""",
                ),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"Hallo"}}]}"""))
            val client = OpenAiCompatibleClient(
                ProviderConfig(baseUrl = server.url("/v1/").toString(), apiKey = "test", useChatAudio = true),
            )

            client.transcribe(TranscriptionRequest(audio(), "own-audio-model", reasoningEffort = "low"), onRetry = {})
                .text shouldBe "Hallo"
            val first = server.takeRequest()
            first.path shouldBe "/v1/chat/completions"
            first.json().jsonObject["reasoning_effort"]!!.jsonPrimitive.content shouldBe "low"
            server.takeRequest().json().jsonObject["reasoning_effort"] shouldBe null
        }
    }

    test("a speech-to-text upload never carries it") {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Hallo"}"""))
            OpenAiCompatibleClient(ProviderConfig(baseUrl = server.url("/v1/").toString(), apiKey = "test"))
                .transcribe(TranscriptionRequest(audio(), "gpt-transcribe", reasoningEffort = "low"))
            val body = server.takeRequest().body.readUtf8()
            body shouldContain "gpt-transcribe"
            body shouldNotContain "reasoning"
        }
    }

    test("the native wire words, from the catalog's") {
        OpenAiCompatibleClient.geminiThinkingConfig(null) shouldBe null
        OpenAiCompatibleClient.geminiThinkingConfig("  ") shouldBe null
        OpenAiCompatibleClient.geminiThinkingConfig("none")!!.thinkingBudget shouldBe 0
        OpenAiCompatibleClient.geminiThinkingConfig("medium")!!.thinkingLevel shouldBe "MEDIUM"
    }
})
