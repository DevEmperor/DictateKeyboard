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
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate

/**
 * Mutual TLS (#498): the key manager that presents a KeyChain alias's certificate, and the socket client that
 * carries it. KeyChain itself is Android's; here a resolver stands in for it.
 */
class ClientCertificateTest : FunSpec({

    val key = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().private
    val certificate = ClientCertificate(key, arrayOf<X509Certificate>())

    test("the alias is offered and its key handed over, fetched once and only when a server asks") {
        var calls = 0
        val manager = AliasKeyManager("cf-access") { alias -> calls++; certificate.takeIf { alias == "cf-access" } }

        calls shouldBe 0 // nothing at construction: KeyChain must not be asked on whatever thread builds a client
        manager.chooseClientAlias(arrayOf("RSA"), null, null) shouldBe "cf-access"
        manager.getPrivateKey("cf-access") shouldBeSameInstanceAs key
        manager.getCertificateChain("cf-access") shouldBe certificate.chain
        manager.getPrivateKey("another") shouldBe null
        calls shouldBe 1
    }

    test("a certificate that cannot be had means none is presented, and a later handshake asks again") {
        var calls = 0
        val manager = AliasKeyManager("gone") { calls++; null }

        manager.chooseClientAlias(arrayOf("RSA"), null, null) shouldBe null
        manager.getClientAliases("RSA", null) shouldBe null
        manager.chooseClientAlias(arrayOf("RSA"), null, null) shouldBe null
        calls shouldBe 3 // every ask tries again: nothing is remembered about a failure
        // A client never serves.
        manager.chooseServerAlias("RSA", null, null) shouldBe null
    }

    test("a socket with a client certificate gets a client of its own, and none keeps the shared one") {
        RealtimeClient.clientFor(null, trustUserCerts = false, clientCertAlias = "") shouldBeSameInstanceAs
            RealtimeClient.clientFor(null, trustUserCerts = false)
        RealtimeClient.clientFor(null, trustUserCerts = false, clientCertAlias = "cf-access") shouldNotBe
            RealtimeClient.clientFor(null, trustUserCerts = false)
    }
})
