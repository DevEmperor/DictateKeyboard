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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempFile

/**
 * Where the user's custom words go on each wire format (issue #466): as a list of terms in the field the
 * provider has for them, within that field's limits, and never at the cost of the dictation. What each
 * provider makes of the field was measured against the endpoints on 2026-11-04 (see [VocabularyField]).
 */
class CustomWordsWireTest : FunSpec({

    fun audio(): File = createTempFile(suffix = ".wav").toFile().apply {
        writeBytes("RIFF-test-audio".encodeToByteArray())
        deleteOnExit()
    }

    fun client(server: MockWebServer, api: TranscriptionApi, path: String = "/") = OpenAiCompatibleClient(
        ProviderConfig(baseUrl = server.url(path).toString(), apiKey = "test-key", transcriptionApi = api),
    )

    val words = listOf("Siobhan", "Niamh", "Anna-Lena Müller")

    // --- the limits ---

    test("a field keeps the terms it takes, in the user's order, and leaves out the ones it would refuse") {
        val field = VocabularyField(maxTerms = 3, maxChars = 10, maxWords = 2, refused = "<>")
        field.fit(listOf(" Acme ", "Acme", "Kubernetes Cluster", "a<b", "one two three", "OAuth", "Dr. X", "Late"))
            .shouldContainExactly("Acme", "OAuth", "Dr. X")
        // A line break would end a query or a form field early, so no field takes one.
        VocabularyField(maxTerms = 10).fit(listOf("Acme\nInc", "Acme")) shouldContainExactly listOf("Acme")
    }

    test("a total stops the list before the term that would cross it, so the first words are the ones that count") {
        VocabularyField(maxTerms = 10, maxTotalWords = 4).fit(listOf("a b", "c", "d e", "f"))
            .shouldContainExactly("a b", "c")
        // Bytes, not characters: Deepgram's budget fills faster with umlauts.
        VocabularyField(maxTerms = 10, maxTotalBytes = 8).fit(listOf("Müller", "Ök", "X"))
            .shouldContainExactly("Müller")
    }

    test("AssemblyAI's Universal-2 counts words across all terms, and the field stays inside 200 of them") {
        val terms = (1..80).map { "Alpha Beta Term$it" }
        val kept = VocabularyField.ASSEMBLYAI.fit(terms)
        kept.sumOf { it.split(' ').size } shouldBe 198
        VocabularyField.ASSEMBLYAI.fit(listOf("one two three four five six seven")) shouldBe emptyList()
    }

    test("AssemblyAI's Dictation endpoint takes 100 terms and 8000 characters, an umlaut counting as one") {
        VocabularyField.ASSEMBLYAI_DICTATION.fit((1..101).map { "Term$it" }).size shouldBe 100
        // 100 terms of 80 characters were taken at 15 800 bytes; 81 of 99 characters were refused at 8019.
        VocabularyField.ASSEMBLYAI_DICTATION.fit((10..109).map { "$it" + "ä".repeat(77) }).size shouldBe 100
        VocabularyField.ASSEMBLYAI_DICTATION.fit((10..90).map { "$it" + "y".repeat(97) }).size shouldBe 80
        VocabularyField.ASSEMBLYAI_DICTATION.fit(listOf("a b c d e f g h i j k l m n o p q r s t")).size shouldBe 1
    }

    // --- batch ---

    test("Deepgram's Nova-3 gets keyterm, Nova-2 keywords, and any other model nothing at all") {
        MockWebServer().use { server ->
            repeat(3) {
                server.enqueue(
                    MockResponse().setResponseCode(200)
                        .setBody("""{"results":{"channels":[{"alternatives":[{"transcript":"Hi"}]}]}}"""),
                )
            }
            val client = client(server, TranscriptionApi.DEEPGRAM, "/v1/")
            for (model in listOf("nova-3", "nova-2", "whisper-large")) {
                client.transcribe(TranscriptionRequest(audio(), model, vocabulary = words))
            }
            val nova3 = URLDecoder.decode(server.takeRequest().path.orEmpty(), "UTF-8")
            nova3 shouldContain "&keyterm=Siobhan&keyterm=Niamh&keyterm=Anna-Lena Müller"
            nova3 shouldNotContain "keywords="
            val nova2 = URLDecoder.decode(server.takeRequest().path.orEmpty(), "UTF-8")
            nova2 shouldContain "&keywords=Siobhan&keywords=Niamh"
            nova2 shouldNotContain "keyterm="
            // Deepgram's Whisper refuses keyterm by name; what it does with keywords was never measured.
            val whisper = server.takeRequest().path.orEmpty()
            whisper shouldNotContain "keyterm="
            whisper shouldNotContain "keywords="
        }
    }

    test("AssemblyAI gets the words as keyterms_prompt in the job") {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"upload_url":"https://cdn/x"}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"t1","status":"queued"}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"t1","status":"completed","text":"Hi"}"""))
            val client = client(server, TranscriptionApi.ASSEMBLYAI_ASYNC)

            client.transcribe(TranscriptionRequest(audio(), "universal-3-5-pro", vocabulary = words)).text shouldBe "Hi"
            server.takeRequest()
            val create = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            create["keyterms_prompt"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly words
        }
    }

    test("ElevenLabs gets one keyterms field a term, without the characters it refuses") {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Hi"}"""))
            val client = client(server, TranscriptionApi.ELEVENLABS_MULTIPART, "/v1/")

            client.transcribe(TranscriptionRequest(audio(), "scribe_v2", vocabulary = words + "{braces}"))
            val body = server.takeRequest().body.readUtf8()
            body shouldContain "name=\"keyterms\"\r\n\r\nSiobhan\r\n"
            body shouldContain "name=\"keyterms\"\r\n\r\nNiamh\r\n"
            body shouldNotContain "{braces}"
            body shouldNotContain "name=\"prompt\""
        }
    }

    test("Voxtral gets context_bias beside the prompt, and no other model on the same wire format gets it") {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Hi"}""")) }
            val client = client(server, TranscriptionApi.OPENAI_MULTIPART, "/v1/")

            client.transcribe(TranscriptionRequest(audio(), "voxtral-mini-latest", prompt = "Siobhan, Niamh", vocabulary = words))
            client.transcribe(TranscriptionRequest(audio(), "gpt-transcribe", prompt = "Siobhan, Niamh", vocabulary = words))
            val voxtral = server.takeRequest().body.readUtf8()
            voxtral shouldContain "name=\"context_bias\"\r\n\r\nSiobhan\r\n"
            voxtral shouldContain "name=\"context_bias\"\r\n\r\nNiamh\r\n"
            voxtral shouldContain "name=\"prompt\"\r\n\r\nSiobhan, Niamh"
            val openai = server.takeRequest().body.readUtf8()
            openai shouldNotContain "context_bias"
            openai shouldContain "name=\"prompt\"\r\n\r\nSiobhan, Niamh"
        }
    }

    test("OpenRouter carries the prompt and the words in provider options, which it forwards") {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Hi"}""")) }
            val client = client(server, TranscriptionApi.OPENROUTER_MULTIPART, "/api/v1/")

            client.transcribe(TranscriptionRequest(audio(), "openai/gpt-transcribe", prompt = "Siobhan, Niamh", vocabulary = words))
            val body = server.takeRequest().body.readUtf8()
            val provider = body.substringAfter("name=\"provider\"\r\n\r\n").substringBefore("\r\n--")
            val options = Json.parseToJsonElement(provider).jsonObject["options"]!!.jsonObject
            listOf("openai", "groq", "deepinfra", "together").forEach { slug ->
                options[slug]!!.jsonObject["prompt"]!!.jsonPrimitive.content shouldBe "Siobhan, Niamh"
            }
            options["mistral"]!!.jsonObject["context_bias"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly words
            options["azure"]!!.jsonObject["phraseList"]!!.jsonObject["phrases"]!!.jsonArray.size shouldBe 3
            // Dropped on the way whatever its spelling (2026-11-04), so not sent.
            options["deepgram"] shouldBe null

            // Nothing to forward, no field at all.
            client.transcribe(TranscriptionRequest(audio(), "openai/gpt-transcribe"))
            server.takeRequest().body.readUtf8() shouldNotContain "name=\"provider\""
        }
    }

    test("a request refused with the words is sent once more without them, and only then") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(400).setBody(
                    """{"err_code":"Bad Request","err_msg":"Bad Request: Keyterm limit exceeded. The maximum number of tokens across all keyterms is 500."}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"results":{"channels":[{"alternatives":[{"transcript":"Hi"}]}]}}"""),
            )
            val client = client(server, TranscriptionApi.DEEPGRAM, "/v1/")

            client.transcribe(TranscriptionRequest(audio(), "nova-3", vocabulary = words)).text shouldBe "Hi"
            server.takeRequest().path.orEmpty() shouldContain "keyterm="
            server.takeRequest().path.orEmpty() shouldNotContain "keyterm="
        }

        // Without words there is nothing to leave out, and a wrong key is no matter of words either.
        listOf(emptyList<String>() to 400, words to 401).forEach { (vocabulary, status) ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"err_msg":"nope"}"""))
                val client = client(server, TranscriptionApi.DEEPGRAM, "/v1/")
                shouldThrow<DictateApiException> {
                    client.transcribe(TranscriptionRequest(audio(), "nova-3", vocabulary = vocabulary))
                }
                server.requestCount shouldBe 1
            }
        }
    }

    test("Gemini's transcription model gets the words as custom_vocabulary, and the prompt nowhere") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"steps":[{"type":"model_output","content":[{"type":"text","text":"Hi"}]}]}"""),
            )
            val client = client(server, TranscriptionApi.GEMINI_GENERATE_CONTENT, "/v1beta/openai/")

            client.transcribe(
                TranscriptionRequest(audio(), "gemini-3.5-transcribe", prompt = "Hello. Thank you. Siobhan, Niamh", vocabulary = words),
            ).text shouldBe "Hi"
            val body = server.takeRequest().body.readUtf8()
            body shouldContain "\"custom_vocabulary\":[\"Siobhan\",\"Niamh\",\"Anna-Lena Müller\"]"
            body shouldNotContain "Thank you"
        }
    }

    // --- streaming ---

    test("the streaming sockets that take their setup in the query carry the words there") {
        val xai = XaiRealtimeSession(OkHttpClient(), "k", "grok-voice-transcribe-2.0", null, NoCallbacks, vocabulary = words)
        URLDecoder.decode(xai.url(), "UTF-8") shouldContain "&keyterm=Siobhan&keyterm=Niamh&keyterm=Anna-Lena Müller"

        val aai = AssemblyAiRealtimeSession(OkHttpClient(), "k", "universal-3-6-pro", null, NoCallbacks, vocabulary = words)
        URLDecoder.decode(aai.url(), "UTF-8") shouldContain """&keyterms_prompt=["Siobhan","Niamh","Anna-Lena Müller"]"""

        AssemblyAiRealtimeSession(OkHttpClient(), "k", "universal-3-6-pro", null, NoCallbacks).url() shouldNotContain "keyterms"
    }

    test("Deepgram's socket takes keyterm on Nova-3 and Flux, keywords on Nova-2") {
        for ((model, field) in listOf("nova-3" to "keyterm", "flux-general-multi" to "keyterm", "nova-2" to "keywords")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) { webSocket.close(1000, null) }
                }))
                val session = DeepgramRealtimeSession(
                    client = OkHttpClient(), apiKey = "dg-test", model = model, language = null,
                    callbacks = NoCallbacks, vocabulary = words, host = "ws://${server.hostName}:${server.port}",
                )
                session.connect()
                val path = URLDecoder.decode(server.takeRequest(5, TimeUnit.SECONDS)!!.path.orEmpty(), "UTF-8")
                session.cancel()
                path shouldContain "&$field=Siobhan&$field=Niamh"
            }
        }
    }

    test("OpenAI's gpt-transcribe socket gets keywords in its session, gpt-realtime-whisper none") {
        for ((model, expected) in listOf("gpt-transcribe" to true, "gpt-realtime-whisper" to false)) {
            val received = LinkedBlockingQueue<String>()
            MockWebServer().use { server ->
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received += text
                        webSocket.close(1000, null)
                    }
                }))
                val session = RealtimeClient.open(
                    RealtimeApi.OPENAI, "k", model, "en", NoCallbacks,
                    baseUrl = server.url("/v1/").toString(), vocabulary = words + "a<b",
                )
                val update = Json.parseToJsonElement(received.poll(5, TimeUnit.SECONDS)!!).jsonObject
                session.cancel()
                val transcription = update["session"]!!.jsonObject["audio"]!!.jsonObject["input"]!!
                    .jsonObject["transcription"]!!.jsonObject
                if (expected) {
                    transcription["keywords"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly words
                } else {
                    transcription["keywords"] shouldBe null
                }
            }
        }
    }

    test("Soniox's socket gets the words as context terms in its first message") {
        val received = LinkedBlockingQueue<String>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received += text
                    webSocket.close(1000, null)
                }
            }))
            val session = RealtimeClient.open(
                RealtimeApi.SONIOX, "k", "stt-rt-v5", "en", NoCallbacks,
                baseUrl = "ws://${server.hostName}:${server.port}/transcribe-websocket", vocabulary = words,
            )
            val config = Json.parseToJsonElement(received.poll(5, TimeUnit.SECONDS)!!).jsonObject
            session.cancel()
            config["context"]!!.jsonObject["terms"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly words
        }
    }
})

private object NoCallbacks : RealtimeCallbacks {
    override fun onPartial(text: String) = Unit
    override fun onFinalSegment(text: String) = Unit
    override fun onError(t: Throwable) = Unit
    override fun onClosed() = Unit
}
