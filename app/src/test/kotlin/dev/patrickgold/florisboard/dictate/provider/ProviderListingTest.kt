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

import dev.patrickgold.florisboard.dictate.provider.ProviderListing.Filter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which providers the settings list, and which the active-provider pickers offer — the rules that shrank
 * a column of two dozen presets to the user's own.
 */
class ProviderListingTest {

    private fun accountsOf(vararg accounts: ProviderAccount) =
        ProviderAccounts(accounts.associateBy { it.providerId })

    private val nothingInstalled: (String) -> Boolean = { false }

    private fun listed(accounts: ProviderAccounts, active: Set<String> = emptySet()) =
        ProviderRegistry.presets
            .filter { ProviderListing.isListed(it, accounts, active, nothingInstalled) }
            .map { it.id }

    @Test
    fun `a fresh install lists the two pinned providers and nothing else`() {
        assertEquals(listOf(ProviderRegistry.CLOUD.id, ProviderRegistry.LOCAL.id), listed(accountsOf()))
    }

    @Test
    fun `a provider joins the list with its key, not before`() {
        val groq = ProviderRegistry.GROQ.id
        assertFalse(groq in listed(accountsOf(ProviderAccount(providerId = groq))))
        assertTrue(groq in listed(accountsOf(ProviderAccount(providerId = groq, apiKey = "gsk_x"))))
    }

    // The one row somebody has to be able to find to fix it: a fresh install's default provider, say.
    @Test
    fun `the active provider stays listed even without a key`() {
        val openai = ProviderRegistry.OPENAI.id
        assertTrue(openai in listed(accountsOf(), active = setOf(openai)))
    }

    // The setup wizard counts Ollama as set up from the first launch, because it needs no key. Here that
    // would put it on everyone's list.
    @Test
    fun `a keyless preset is listed once it has been saved`() {
        val ollama = ProviderRegistry.OLLAMA.id
        assertFalse(ollama in listed(accountsOf()))
        assertTrue(
            ollama in listed(accountsOf(ProviderAccount(providerId = ollama, customBaseUrl = "http://nas:11434/v1/"))),
        )
    }

    @Test
    fun `the on-device engine is set up when either of its models is on disk`() {
        val local = ProviderRegistry.LOCAL
        val streamingOnly = accountsOf(ProviderAccount(providerId = local.id, realtimeModel = "kroko-de"))
        assertFalse(ProviderListing.isSetUp(local, streamingOnly, nothingInstalled))
        assertTrue(ProviderListing.isSetUp(local, streamingOnly) { it == "kroko-de" })
        // A blank one-shot pick is the preset default, as it is when dictating.
        assertTrue(ProviderListing.isSetUp(local, accountsOf()) { it == local.defaultTranscriptionModel })
    }

    @Test
    fun `a picker offers what works, and never loses the ticked option`() {
        val groq = ProviderRegistry.GROQ
        val openai = ProviderRegistry.OPENAI
        val accounts = accountsOf(ProviderAccount(providerId = groq.id, apiKey = "gsk_x"))

        assertTrue(ProviderListing.isPickable(groq, accounts, selectedId = "", nothingInstalled))
        assertFalse(ProviderListing.isPickable(openai, accounts, selectedId = groq.id, nothingInstalled))
        assertTrue(ProviderListing.isPickable(openai, accounts, selectedId = openai.id, nothingInstalled))
    }

    // Pinned is about the providers screen: a picker still offers Dictate Cloud only once there is credit.
    @Test
    fun `Dictate Cloud is always listed but only pickable with a wallet`() {
        val cloud = ProviderRegistry.CLOUD
        assertTrue(ProviderListing.isListed(cloud, accountsOf(), emptySet(), nothingInstalled))
        assertFalse(ProviderListing.isPickable(cloud, accountsOf(), selectedId = "", nothingInstalled))
        val funded = accountsOf(ProviderAccount(providerId = cloud.id, apiKey = "wallet-token"))
        assertTrue(ProviderListing.isPickable(cloud, funded, selectedId = "", nothingInstalled))
    }

    // Issue #431: the keyboard's picker and the settings dialog offer one and the same list.
    @Test
    fun `the transcription choices are the pickable ones, on-device first, own endpoints last`() {
        val groq = ProviderRegistry.GROQ.id
        val local = ProviderRegistry.LOCAL
        val accounts = accountsOf(
            ProviderAccount(providerId = groq, apiKey = "gsk_x"),
            ProviderAccount(providerId = "custom:b", displayName = "Zeta box"),
            ProviderAccount(providerId = "custom:a", displayName = "alpha box"),
        )
        val choices = ProviderListing.transcriptionChoices(accounts, selectedId = groq) {
            it == local.defaultTranscriptionModel
        }
        assertEquals(
            listOf(local.id, groq, "custom:a", "custom:b"),
            choices.map { it.first },
        )
        assertEquals(local.displayName, choices.first().second)
        // A provider that could only answer "no API key" is not on offer, and neither is a rewording-only one.
        assertFalse(choices.any { it.first == ProviderRegistry.OPENAI.id })
        assertFalse(choices.any { it.first == ProviderRegistry.ANTHROPIC.id })
    }

    @Test
    fun `a provider is named by its preset, an own endpoint by its user`() {
        val accounts = accountsOf(
            ProviderAccount(providerId = "custom:a", displayName = "Home server"),
            ProviderAccount(providerId = "custom:b"),
        )
        assertEquals(ProviderRegistry.GROQ.displayName, ProviderListing.displayNameOf(ProviderRegistry.GROQ.id, accounts))
        assertEquals("Home server", ProviderListing.displayNameOf("custom:a", accounts))
        assertEquals("Custom server", ProviderListing.displayNameOf("custom:b", accounts))
        assertEquals("custom:gone", ProviderListing.displayNameOf("custom:gone", accounts))
    }

    @Test
    fun `the EU filter finds the EU-hosted providers and the EU regions`() {
        val eu = ProviderRegistry.presets.filter { ProviderListing.matches(it, "", setOf(Filter.EU)) }.map { it.id }
        assertEquals(
            setOf("openrouter", "mistral", "soniox", "scaleway", "ovhcloud"),
            eu.toSet(),
        )
    }

    @Test
    fun `filters narrow together, and the search ignores case`() {
        val euTranscribingLive = ProviderRegistry.presets
            .filter { ProviderListing.matches(it, "", setOf(Filter.EU, Filter.REALTIME)) }
            .map { it.id }
        assertEquals(listOf("soniox"), euTranscribingLive)

        assertTrue(ProviderListing.matches(ProviderRegistry.ANTHROPIC, "claude", emptySet()))
        assertTrue(ProviderListing.matches(ProviderRegistry.OVHCLOUD, " ovh ", emptySet()))
        assertFalse(ProviderListing.matches(ProviderRegistry.ANTHROPIC, "claude", setOf(Filter.TRANSCRIPTION)))
    }
}
