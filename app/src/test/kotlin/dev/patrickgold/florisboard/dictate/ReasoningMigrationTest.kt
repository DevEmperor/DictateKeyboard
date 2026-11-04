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

import dev.patrickgold.florisboard.dictate.provider.PromptReasoning
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The old global "Reasoning effort" moving into the accounts (2026-11-04), and a prompt's level surviving the
 * storage every older version reads.
 */
class ReasoningMigrationTest {

    private val openAi = ProviderRegistry.byId("openai")!!
    private val mistral = ProviderRegistry.byId("mistral")!!
    private val ovh = ProviderRegistry.byId("ovhcloud")!!
    private val anthropic = ProviderRegistry.byId("anthropic")!!

    private fun migrated(global: DictateReasoningEffort, preset: String, model: String, custom: String = "") =
        migratedChatReasoning(global, custom, ProviderRegistry.byId(preset)!!, model)

    @Test
    fun `off stays the default everywhere`() {
        assertEquals("", migratedChatReasoning(DictateReasoningEffort.OFF, "high", openAi, "gpt-6.1-sol"))
        assertEquals("", migrated(DictateReasoningEffort.CUSTOM, "openai", "gpt-6.1-sol", custom = "  "))
    }

    @Test
    fun `a word the model takes is kept`() {
        assertEquals("high", migrated(DictateReasoningEffort.HIGH, "openai", "gpt-6.1-sol"))
        assertEquals("xhigh", migrated(DictateReasoningEffort.CUSTOM, "openai", "gpt-6.1-sol", custom = " xhigh "))
        assertEquals("none", migratedChatReasoning(DictateReasoningEffort.NONE, "", mistral, "mistral-small-latest"))
    }

    @Test
    fun `a word the model refuses becomes the nearest it takes, the lower one on a tie`() {
        // Sol takes low to xhigh.
        assertEquals("low", migrated(DictateReasoningEffort.MINIMAL, "openai", "gpt-6.1-sol"))
        assertEquals("low", migrated(DictateReasoningEffort.NONE, "openai", "gpt-6.1-sol"))
        // Mistral names only none and high.
        assertEquals("high", migratedChatReasoning(DictateReasoningEffort.MEDIUM, "", mistral, "mistral-small-latest"))
        // OVH's Qwen has medium and xhigh around a missing high.
        assertEquals("medium", migratedChatReasoning(DictateReasoningEffort.HIGH, "", ovh, "Qwen3.8-27B"))
    }

    @Test
    fun `a model without a reasoning setting gets the default`() {
        assertEquals("", migratedChatReasoning(DictateReasoningEffort.HIGH, "", anthropic, "claude-sonnet-5-5"))
    }

    @Test
    fun `a custom word no model on the scale knows is dropped for a catalog model`() {
        assertEquals("", migrated(DictateReasoningEffort.CUSTOM, "openai", "gpt-6.1-sol", custom = "turbo"))
    }

    @Test
    fun `a model the catalog does not know keeps the word as it was sent`() {
        assertEquals("medium", migrated(DictateReasoningEffort.MEDIUM, "openai", "ft:gpt-6-luna:own"))
        assertEquals("turbo", migrated(DictateReasoningEffort.CUSTOM, "openai", "ft:gpt-6-luna:own", custom = "turbo"))
    }

    @Test
    fun `every level is stored under a name older versions know and reads back as itself`() {
        val known = setOf(null, "NONE", "LOW", "HIGH")
        for (level in PromptReasoning.entries) {
            val stored = DictateReasoningEffort.storedFor(level)?.name
            assert(stored in known) { "$level is stored as $stored" }
            assertEquals(level, PromptReasoning.fromStored(stored))
        }
    }
}
