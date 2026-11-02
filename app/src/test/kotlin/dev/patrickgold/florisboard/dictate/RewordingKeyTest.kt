/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate

import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.dictate.provider.RewordingBlocker
import dev.patrickgold.florisboard.dictate.provider.rewordingBlocker
import dev.patrickgold.florisboard.dictate.provider.rewordingKeyFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which key a rewording is sent with, and what stops one from being sent.
 *
 * The transcription key used to stand in for any rewording provider without one of its own. Pointed at a
 * keyless Ollama box or a server of the user's own, that handed the OpenAI key to a machine that never
 * needed it, possibly over plain http.
 */
class RewordingKeyTest {

    private val openAi = ProviderAccount(providerId = "openai", apiKey = "sk-transcription")

    @Test
    fun `an account with its own key always uses it`() {
        val groq = ProviderAccount(providerId = "groq", apiKey = "gsk-own")
        assertEquals("gsk-own", rewordingKeyFor(groq, openAi))
    }

    @Test
    fun `another provider never gets the transcription key`() {
        assertEquals("", rewordingKeyFor(ProviderAccount(providerId = "ollama"), openAi))
        assertEquals("", rewordingKeyFor(ProviderAccount(providerId = "groq"), openAi))
        val ownServer = ProviderAccount(providerId = "custom:abc", customBaseUrl = "http://box.lan:8000/v1/")
        assertEquals("", rewordingKeyFor(ownServer, openAi))
    }

    @Test
    fun `two servers of the user's own at the same address share the key`() {
        val transcribe = ProviderAccount(
            providerId = "custom:aaa", apiKey = "local-key", customBaseUrl = "https://box.lan:8000/v1/",
        )
        val reword = ProviderAccount(providerId = "custom:bbb", customBaseUrl = "https://BOX.lan:8000/v1")
        assertEquals("local-key", rewordingKeyFor(reword, transcribe))
        val elsewhere = reword.copy(customBaseUrl = "https://other.lan/v1/")
        assertEquals("", rewordingKeyFor(elsewhere, transcribe))
    }

    @Test
    fun `a keyless server is held up by a missing model, not by a missing key`() {
        val ollama = ProviderAccount(providerId = "ollama")
        assertEquals(RewordingBlocker.NO_MODEL, rewordingBlocker(ollama, ProviderRegistry.OLLAMA, ""))
        assertNull(rewordingBlocker(ollama.copy(chatModel = "llama3.3"), ProviderRegistry.OLLAMA, ""))
    }

    @Test
    fun `a provider that wants a key is held up by its absence first`() {
        val mistral = ProviderAccount(providerId = "mistral")
        assertEquals(RewordingBlocker.NO_KEY, rewordingBlocker(mistral, ProviderRegistry.MISTRAL, ""))
        assertEquals(RewordingBlocker.NO_MODEL, rewordingBlocker(mistral, ProviderRegistry.MISTRAL, "key"))
        val groq = ProviderAccount(providerId = "groq")
        assertNull(rewordingBlocker(groq, ProviderRegistry.GROQ, "key")) // Groq has a default of its own
    }
}
