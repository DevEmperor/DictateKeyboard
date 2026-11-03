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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * The real-time socket leaves through the user's proxy, like every batch request already did. The mock
 * server plays the proxy: an HTTP proxy is asked to CONNECT to the vendor's host, so the request line
 * proves where the socket went without anything reaching the internet.
 */
class RealtimeProxyTest : FunSpec({

    class Recorder : RealtimeCallbacks {
        override fun onPartial(text: String) = Unit
        override fun onFinalSegment(text: String) = Unit
        override fun onError(t: Throwable) = Unit
        override fun onClosed() = Unit
    }

    test("every real-time socket gets the small send buffer, through a proxy too") {
        // #447: with the platform's own buffer the backlog of a slow uplink hides in the kernel.
        val direct = RealtimeClient.clientFor(null, trustUserCerts = false).socketFactory
        val proxied = RealtimeClient.clientFor(ProxyConfig(Proxy.Type.HTTP, "127.0.0.1", 1), false).socketFactory
        (direct is SendBufferSocketFactory) shouldBe true
        (proxied is SendBufferSocketFactory) shouldBe true
        // Linux reports the buffer it asked for doubled, other systems as asked.
        val size = direct.createSocket().use { it.sendBufferSize }
        (size == RealtimeClient.SEND_BUFFER_BYTES || size == 2 * RealtimeClient.SEND_BUFFER_BYTES) shouldBe true
    }

    test("a real-time session asks the proxy for the vendor's host") {
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setResponseCode(403))

            val session = RealtimeClient.open(
                RealtimeApi.XAI, "key", "grok-stt", null, Recorder(),
                proxy = ProxyConfig(Proxy.Type.HTTP, proxy.hostName, proxy.port),
            )

            proxy.takeRequest(5, TimeUnit.SECONDS)!!.requestLine shouldStartWith "CONNECT api.x.ai:443"
            session.cancel()
        }
    }

    test("the tunnel to the vendor carries the user's proxy login") {
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setResponseCode(403))

            val session = RealtimeClient.open(
                RealtimeApi.XAI, "key", "grok-stt", null, Recorder(),
                proxy = ProxyConfig(Proxy.Type.HTTP, proxy.hostName, proxy.port, "user", "secret"),
            )

            // OkHttp asks the authenticator up front for a tunnel, so the very first CONNECT has the login.
            proxy.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Proxy-Authorization") shouldBe
                Credentials.basic("user", "secret")
            session.cancel()
        }
    }

    test("without a proxy or a user CA the shared socket client is reused") {
        RealtimeClient.clientFor(null, trustUserCerts = false) shouldBe RealtimeClient.clientFor(null, false)
        RealtimeClient.clientFor(ProxyConfig(Proxy.Type.HTTP, "proxy.local", 3128), false).proxy shouldNotBe null
    }
})
