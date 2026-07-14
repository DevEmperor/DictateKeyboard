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
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress

class OpenAiCompatibleClientNetworkTest : FunSpec({
    test("batch clients prefer IPv4 and bound only the connect timeout") {
        val client = OpenAiCompatibleClient(
            ProviderConfig(
                baseUrl = "https://example.test/v1/",
                apiKey = "test",
                timeoutSeconds = 120,
            ),
        ).buildClient()

        client.dns shouldBe Ipv4FirstDns
        client.connectTimeoutMillis shouldBe 8_000
        client.callTimeoutMillis shouldBe 120_000
        client.readTimeoutMillis shouldBe 120_000
        client.writeTimeoutMillis shouldBe 120_000
    }

    test("IPv4-first DNS preserves resolver order within each address family") {
        val ipv6First = InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })
        val ipv4First = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))
        val ipv6Second = InetAddress.getByAddress(ByteArray(16).also { it[15] = 2 })
        val ipv4Second = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 2))

        Ipv4FirstDns.prioritizeIpv4(listOf(ipv6First, ipv4First, ipv6Second, ipv4Second))
            .shouldContainExactly(ipv4First, ipv4Second, ipv6First, ipv6Second)
    }

    test("OpenRouter transcription policy never replays a billable POST") {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(503).setBody("""{"error":{"message":"busy"}}"""),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"text":"duplicate"}"""),
            )

            val client = OpenAiCompatibleClient(
                ProviderConfig(baseUrl = server.url("/").toString(), apiKey = "test"),
            )
            val request = Request.Builder()
                .url(server.url("/audio/transcriptions"))
                .post("{}".toRequestBody())
                .build()

            val error = shouldThrow<DictateApiException> {
                client.executeForBody(
                    request = request,
                    maxRetries = OpenAiCompatibleClient.OPENROUTER_TRANSCRIPTION_MAX_RETRIES,
                )
            }

            error.kind shouldBe DictateApiException.Kind.SERVER_ERROR
            server.requestCount shouldBe 1
        }
    }
})
