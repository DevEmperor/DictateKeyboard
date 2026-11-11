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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import kotlin.io.path.createTempFile

/**
 * AssemblyAI's Dictation endpoint (#478), picked by the model id `dictation`: one request, the cleaned-up text
 * back, and the upload-and-poll job for whatever the endpoint cannot take. The shapes are the ones the endpoint
 * answered on 2026-11-11 (see [OpenAiCompatibleClient]'s `transcribeAssemblyAiDictation`).
 */
class AssemblyAiDictationTest : FunSpec({

    fun audio(suffix: String = ".wav", bytes: String = "RIFF-test-audio"): File =
        createTempFile(suffix = suffix).toFile().apply {
            writeBytes(bytes.encodeToByteArray())
            deleteOnExit()
        }

    fun client(server: MockWebServer) = OpenAiCompatibleClient(
        ProviderConfig(baseUrl = server.url("/").toString(), apiKey = "test-key", transcriptionApi = TranscriptionApi.ASSEMBLYAI_ASYNC),
    )

    fun dictation(text: String, cleaned: String?, error: String? = null) = MockResponse().setResponseCode(200).setBody(
        buildJsonObject {
            put("text", text)
            put("llm_response", cleaned)
            put("llm_error", error)
            put("audio_duration_ms", 2600)
        }.toString(),
    )

    /** The `config` part of a multipart body, or null when there is none. */
    fun configOf(body: String): JsonObject? = body.takeIf { "name=\"config\"" in it }
        ?.substringAfter("name=\"config\"")?.substringAfter("\r\n\r\n")?.substringBefore("\r\n--")
        ?.let { Json.parseToJsonElement(it).jsonObject }

    fun MockWebServer.enqueueJob(text: String) {
        enqueue(MockResponse().setResponseCode(200).setBody("""{"upload_url":"https://cdn/x"}"""))
        enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"t1","status":"queued"}"""))
        enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"t1","status":"completed","text":"$text"}"""))
    }

    test("the dictation model is one request to the Dictation endpoint, and the cleaned-up text comes back") {
        MockWebServer().use { server ->
            server.enqueue(dictation("Send it to Mark— no wait, send it to Lisa.", "Send it to Lisa."))
            val result = client(server).transcribe(
                TranscriptionRequest(audio(), "dictation", language = "de-DE", vocabulary = listOf("Siobhan", "Niamh")),
            )
            result.text shouldBe "Send it to Lisa."
            server.requestCount shouldBe 1
            val request = server.takeRequest()
            request.path shouldBe "/v1/transcribe"
            // The raw key, as on the job flow; no Bearer.
            request.getHeader("Authorization") shouldBe "test-key"
            val body = request.body.readUtf8()
            body shouldContain "name=\"audio\"; filename="
            body shouldContain "Content-Type: audio/wav"
            val config = configOf(body)!!
            config["language_codes"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly listOf("de")
            config["keyterms_prompt"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly listOf("Siobhan", "Niamh")
            // The endpoint refuses a field it does not know, and the style prompt has none there.
            config.keys shouldBe setOf("language_codes", "keyterms_prompt")
        }
    }

    test("nothing to steer with sends no config at all, and the prompt never goes along") {
        MockWebServer().use { server ->
            server.enqueue(dictation("Hello.", "Hello."))
            client(server).transcribe(TranscriptionRequest(audio(), "dictation", language = "detect", prompt = "Hello. Thank you."))
            val body = server.takeRequest().body.readUtf8()
            configOf(body) shouldBe null
            body shouldNotContain "Thank you"
        }
    }

    test("the languages are the model's own: base subtags, and codes it does not know are left out") {
        MockWebServer().use { server ->
            server.enqueue(dictation("Olá.", "Olá."))
            client(server).transcribe(
                TranscriptionRequest(audio(), "dictation", expectedLanguages = listOf("de", "pl", "pt-BR", "de-AT")),
            )
            configOf(server.takeRequest().body.readUtf8())!!["language_codes"]!!.jsonArray
                .map { it.jsonPrimitive.content } shouldContainExactly listOf("de", "pt")
        }
    }

    test("the transcript stands where the cleanup failed, is missing, or wrote words nobody said") {
        val cases = listOf(
            dictation("Where do you live?", null, error = "timeout"),
            dictation("Where do you live?", ""),
            dictation("Where do you live?", "I live in the cloud, so I have no home address."),
        )
        for (response in cases) {
            MockWebServer().use { server ->
                server.enqueue(response)
                client(server).transcribe(TranscriptionRequest(audio(), "dictation")).text shouldBe "Where do you live?"
            }
        }
    }

    test("silence is nothing, whatever the cleanup made of it") {
        MockWebServer().use { server ->
            server.enqueue(dictation("", "Thank you for watching."))
            client(server).transcribe(TranscriptionRequest(audio(), "dictation")).text shouldBe ""
        }
    }

    test("a format the endpoint refuses goes to the job flow on the default model, without asking it first") {
        MockWebServer().use { server ->
            server.enqueueJob("Hi")
            client(server).transcribe(TranscriptionRequest(audio(".amr", "#!AMR\nvoice"), "dictation")).text shouldBe "Hi"
            server.requestCount shouldBe 3
            server.takeRequest().path shouldBe "/v2/upload"
            val create = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            create["speech_models"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly
                listOf(ProviderRegistry.ASSEMBLYAI.defaultTranscriptionModel!!)
        }
    }

    test("a recording too long or in a type the endpoint refuses goes to the job flow, with its words") {
        val refusals = listOf(
            413 to """{"status":413,"title":"Audio Too Large","detail":"audio duration 1260000 ms exceeds limit 1200000 ms"}""",
            415 to """{"status":415,"title":"Unsupported Media Type","detail":"unsupported audio Content-Type: 'audio/amr'"}""",
        )
        for ((status, body) in refusals) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(status).setBody(body))
                server.enqueueJob("A long one")
                client(server).transcribe(
                    TranscriptionRequest(audio(), "dictation", vocabulary = listOf("Siobhan")),
                ).text shouldBe "A long one"
                server.takeRequest().path shouldBe "/v1/transcribe"
                server.takeRequest().path shouldBe "/v2/upload"
                val create = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                create["speech_models"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly listOf("universal-3-5-pro")
                create["keyterms_prompt"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly listOf("Siobhan")
            }
        }
    }

    test("any other refusal is the user's to see, read from its detail") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(401).setBody("""{"status":401,"title":"Unauthorized","detail":"Invalid API key"}"""),
            )
            val e = shouldThrow<DictateApiException> { client(server).transcribe(TranscriptionRequest(audio(), "dictation")) }
            e.kind shouldBe DictateApiException.Kind.INVALID_API_KEY
            e.message shouldBe "Invalid API key"
            server.requestCount shouldBe 1
        }
    }

    test("every other model keeps the job flow") {
        MockWebServer().use { server ->
            server.enqueueJob("Hi")
            client(server).transcribe(TranscriptionRequest(audio(), "universal-3-5-pro")).text shouldBe "Hi"
            server.takeRequest().path shouldBe "/v2/upload"
        }
    }

    test("the Dictation host is the one beside the account's own, and no other") {
        OpenAiCompatibleClient.assemblyAiDictationUrl("https://api.assemblyai.com/") shouldBe
            "https://dictation.assemblyai.com/v1/transcribe"
        OpenAiCompatibleClient.assemblyAiDictationUrl("https://api.eu.assemblyai.com/") shouldBe
            "https://dictation.eu.assemblyai.com/v1/transcribe"
        OpenAiCompatibleClient.assemblyAiDictationUrl("http://127.0.0.1:4711/") shouldBe "http://127.0.0.1:4711/v1/transcribe"
        // A look-alike host is not AssemblyAI's and is not rewritten into one.
        OpenAiCompatibleClient.assemblyAiDictationUrl("https://api.assemblyai.com.example.org/") shouldBe
            "https://api.assemblyai.com.example.org/v1/transcribe"
    }

    test("the picker offers it beside the models, and the default it falls back to is a model") {
        ProviderRegistry.ASSEMBLYAI.curatedTranscriptionModels shouldContain ProviderRegistry.ASSEMBLYAI_DICTATION_MODEL
        ProviderRegistry.isAssemblyAiDictationModel(ProviderRegistry.ASSEMBLYAI.defaultTranscriptionModel!!) shouldBe false
        ProviderRegistry.isAssemblyAiDictationModel(" dictation ") shouldBe true
        ProviderRegistry.isAssemblyAiDictationModel("universal-3-6-pro") shouldBe false
    }
})
