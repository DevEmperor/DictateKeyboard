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

import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProviderRegistryTest {

    @Test
    fun cerebrasIsAvailableAsFastRewordingProvider() {
        val preset = assertNotNull(ProviderRegistry.byId("cerebras"))

        assertEquals("Cerebras", preset.displayName)
        assertEquals("https://api.cerebras.ai/v1/", preset.baseUrl)
        assertEquals("https://cloud.cerebras.ai/", preset.apiKeyUrl)
        assertTrue(preset.capabilities.chat)
        assertFalse(preset.capabilities.transcription)
        assertTrue(preset.supportsDynamicModels)
        assertEquals("gemma-4-31b", preset.defaultChatModel)
        assertTrue("gemma-4-31b" in preset.curatedChatModels)
        assertTrue(preset in ProviderRegistry.presets)
    }
}
