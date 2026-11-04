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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Data residency (issue #403): which address an account actually talks to, and — the part that made this
 * more than a base URL field — whether the streaming host moves with it.
 *
 * The failure this guards against does not look like a failure. A region that moved only the REST host
 * would go on streaming to the default region while every batch request honoured the user's choice, and
 * nothing on screen would say so.
 */
class ProviderRegionTest : FunSpec({

    val soniox = ProviderRegistry.SONIOX
    val openRouter = ProviderRegistry.OPENROUTER

    test("no stored URL is the default region, not 'no region'") {
        // What every account written before regions existed holds, and what a fresh one still holds.
        val region = ProviderRegistry.regionOf(soniox, "")
        region.shouldNotBeNull()
        region.id shouldBe "us"
        region.baseUrl shouldBe soniox.baseUrl
    }

    test("a region moves the streaming host with the REST one") {
        val eu = ProviderRegistry.regionOf(soniox, "https://api.eu.soniox.com/v1/")
        eu.shouldNotBeNull()
        eu.id shouldBe "eu"
        // The point of the whole type: this host is a sibling of the REST one, not a path under it, so a
        // base URL alone could never have carried it.
        eu.realtimeUrl shouldBe "wss://stt-rt.eu.soniox.com/transcribe-websocket"
    }

    test("every Soniox region names both of its hosts, and no two share one") {
        soniox.regions.forEach { region ->
            region.realtimeUrl.shouldNotBeNull()
            region.baseUrl shouldNotBe null
        }
        // A copy-paste that left two regions pointing at the same host is precisely how a residency
        // promise gets broken quietly, so it is worth a check rather than a careful read.
        soniox.regions.map { it.baseUrl }.toSet().size shouldBe soniox.regions.size
        soniox.regions.map { it.realtimeUrl }.toSet().size shouldBe soniox.regions.size
    }

    test("a trailing slash or a capital letter is not a different region") {
        ProviderRegistry.regionOf(soniox, "https://api.eu.soniox.com/v1")?.id shouldBe "eu"
        ProviderRegistry.regionOf(soniox, "HTTPS://API.EU.SONIOX.COM/v1/")?.id shouldBe "eu"
    }

    test("an address that is none of the regions is left where it was pointed") {
        // Someone who typed a proxy in before regions existed keeps it, rather than being snapped onto a
        // region they never chose — and, for realtime, keeps the vendor's default address.
        ProviderRegistry.regionOf(soniox, "https://soniox.example.com/v1/") shouldBe null
    }

    test("a provider without regions has none to resolve") {
        ProviderRegistry.regionOf(ProviderRegistry.OPENAI, "") shouldBe null
        ProviderRegistry.regionOf(ProviderRegistry.OPENAI, "https://api.openai.com/v1/") shouldBe null
    }

    test("OpenRouter regions move the address only, because there is no stream to move") {
        openRouter.supportsRealtime shouldBe false
        ProviderRegistry.regionOf(openRouter, "")?.baseUrl shouldBe openRouter.baseUrl
        val eu = ProviderRegistry.regionOf(openRouter, "https://eu.openrouter.ai/api/v1/")
        eu.shouldNotBeNull()
        eu.realtimeUrl shouldBe null
        // The wire format is what the region had to keep: a custom endpoint would have dropped to plain
        // OpenAI multipart and lost every dedicated speech-to-text model from the picker (#321).
        openRouter.transcriptionApi shouldBe TranscriptionApi.OPENROUTER_MULTIPART
    }

    test("the first region is the preset's own address") {
        // The editor shows the first entry for an account that stored nothing, so it has to be the one the
        // preset already used — otherwise opening the dialog would silently re-point an account.
        soniox.regions.first().baseUrl shouldBe soniox.baseUrl
        openRouter.regions.first().baseUrl shouldBe openRouter.baseUrl
    }

    test("SiliconFlow transcribes in China and not on its international host") {
        val siliconFlow = ProviderRegistry.SILICONFLOW
        siliconFlow.regions.first().baseUrl shouldBe siliconFlow.baseUrl
        // Every account stored before the regions holds a blank URL, the China host, and keeps transcribing.
        ProviderRegistry.transcribesAt(siliconFlow, "") shouldBe true
        ProviderRegistry.transcribesAt(siliconFlow, "https://api.siliconflow.cn/v1/") shouldBe true
        // An international account typed its host in by hand before; that is now the region without speech.
        ProviderRegistry.regionOf(siliconFlow, "https://api.siliconflow.com/v1")?.id shouldBe "global"
        ProviderRegistry.transcribesAt(siliconFlow, "https://api.siliconflow.com/v1") shouldBe false
        // Chat is the same in both.
        siliconFlow.capabilities.chat shouldBe true
    }

    test("an address that is no region keeps the provider's answer, and a provider without speech has none") {
        ProviderRegistry.transcribesAt(ProviderRegistry.SILICONFLOW, "https://proxy.example.com/v1/") shouldBe true
        ProviderRegistry.transcribesAt(soniox, "https://api.eu.soniox.com/v1/") shouldBe true
        ProviderRegistry.transcribesAt(ProviderRegistry.ANTHROPIC, "") shouldBe false
    }

    test("a region is only offered where the account may carry its own base URL") {
        // Every resolution site in the app asks allowsCustomBaseUrl before it reads the account's URL, so
        // a preset with regions and without the flag would show a list that changes nothing.
        ProviderRegistry.presets.filter { it.regions.isNotEmpty() }.forEach { preset ->
            preset.allowsCustomBaseUrl shouldBe true
        }
    }
})
