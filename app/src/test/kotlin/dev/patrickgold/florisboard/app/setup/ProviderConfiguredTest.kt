/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.setup

import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderAccounts
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the setup wizard may consider the user finished with the provider step (issue #273).
 *
 * The bug that prompted this: a paying customer had a working keyless self-hosted endpoint, dictated
 * through it happily, and the wizard still told them nothing was set up — two rules answering one
 * question. The rule now lives on the account and is shared with the dictation flow.
 */
class ProviderConfiguredTest {

    private fun accountsOf(vararg accounts: ProviderAccount) =
        ProviderAccounts(accounts.associateBy { it.providerId })

    @Test
    fun `a keyless server of the user's own counts as set up`() {
        val id = ProviderAccount.newCustomId()
        val account = ProviderAccount(providerId = id, customBaseUrl = "http://192.168.1.5:8000/v1/")
        assertFalse(account.requiresCredential, "a custom endpoint must not be asked for a key")
        assertTrue(isProviderConfigured(accountsOf(account), id, emptySet()))
    }

    @Test
    fun `the legacy single custom id is treated the same`() {
        val account = ProviderAccount(
            providerId = ProviderAccount.LEGACY_CUSTOM_ID,
            customBaseUrl = "http://localhost:8000/v1/",
        )
        assertTrue(isProviderConfigured(accountsOf(account), ProviderAccount.LEGACY_CUSTOM_ID, emptySet()))
    }

    @Test
    fun `a provider with a key page is not set up until it has one`() {
        val groq = ProviderRegistry.GROQ.id
        assertTrue(ProviderAccount(providerId = groq).requiresCredential)
        assertFalse(isProviderConfigured(accountsOf(), groq, emptySet()))
        assertTrue(
            isProviderConfigured(accountsOf(ProviderAccount(providerId = groq, apiKey = "gsk_x")), groq, emptySet()),
        )
    }

    @Test
    fun `Ollama needs no key`() {
        val ollama = ProviderRegistry.OLLAMA.id
        assertFalse(ProviderAccount(providerId = ollama).requiresCredential)
        assertTrue(isProviderConfigured(accountsOf(), ollama, emptySet()))
    }

    /**
     * Dictate Cloud has no key page either, so on `apiKeyUrl` alone it looks exactly like Ollama. Its
     * credential is the wallet token, and without one there is nothing to dictate with.
     */
    @Test
    fun `Dictate Cloud without a wallet is not set up`() {
        val cloud = ProviderRegistry.CLOUD.id
        assertTrue(ProviderAccount(providerId = cloud).requiresCredential)
        assertFalse(isProviderConfigured(accountsOf(), cloud, emptySet()))
        assertTrue(
            isProviderConfigured(accountsOf(ProviderAccount(providerId = cloud, apiKey = "token")), cloud, emptySet()),
        )
    }

    /**
     * On-device is the case the shared credential rule cannot answer: it needs no key at all, but with
     * nothing downloaded it cannot transcribe a word.
     */
    @Test
    fun `on-device counts only once a model is on disk`() {
        val local = ProviderRegistry.LOCAL.id
        val default = ProviderRegistry.LOCAL.defaultTranscriptionModel!!
        assertFalse(isProviderConfigured(accountsOf(), local, emptySet()))
        assertTrue(isProviderConfigured(accountsOf(), local, setOf(default)))

        // A deliberate pick is what counts, not just "something is installed": the dictation path would
        // load exactly this model and fail if it is gone.
        val picked = ProviderAccount(providerId = local, transcriptionModel = "sense-voice-small")
        assertFalse(isProviderConfigured(accountsOf(picked), local, setOf(default)))
        assertTrue(isProviderConfigured(accountsOf(picked), local, setOf("sense-voice-small")))
    }
}
