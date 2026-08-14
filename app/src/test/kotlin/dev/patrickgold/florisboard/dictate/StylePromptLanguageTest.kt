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

import dev.patrickgold.florisboard.dictate.data.prompts.DictatePromptDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The style prompt must never be in a language other than the one being dictated (issue #275).
 *
 * Whisper reads that prompt as preceding context, so it steers the output language as much as the
 * punctuation style. Croatian reached the catalog through #252 and this map through nothing, so a
 * Croatian dictation went out as `language=hr` carrying "Hello. Thank you very much." — and came back
 * partly in English. These tests exist so the next language added to the catalog cannot repeat it.
 */
class StylePromptLanguageTest {

    @Test
    fun `no catalog language is primed with the english sentence`() {
        val offenders = DictateLanguages.all
            .filter { it.code != DictateLanguages.DETECT && it.code != "en" }
            .filter { DictatePromptDefaults.punctuationPromptFor(it.code) == DictatePromptDefaults.PUNCTUATION_CAPITALIZATION }
            .map { "${it.code} (${it.englishName})" }
        assertTrue(
            offenders.isEmpty(),
            "these languages would be sent an English style prompt: $offenders",
        )
    }

    @Test
    fun `an unmapped language sends no prompt at all rather than a wrong one`() {
        // Sending nothing costs a little punctuation bias; sending the wrong language costs the output.
        assertNull(DictatePromptDefaults.punctuationPromptFor("zzz"))
        assertNull(DictatePromptDefaults.punctuationPromptFor(""))
        assertNull(DictatePromptDefaults.punctuationPromptFor(null))
    }

    @Test
    fun `auto-detect sends no prompt, so detection is not nudged towards english`() {
        assertNull(DictatePromptDefaults.punctuationPromptFor(DictateLanguages.DETECT))
    }

    @Test
    fun `croatian gets croatian`() {
        val hr = DictatePromptDefaults.punctuationPromptFor("hr")
        assertEquals("Bok. Hvala lijepa.", hr)
        assertNotEquals(DictatePromptDefaults.PUNCTUATION_CAPITALIZATION, hr)
    }

    @Test
    fun `english still gets the shared constant`() {
        assertEquals(DictatePromptDefaults.PUNCTUATION_CAPITALIZATION, DictatePromptDefaults.punctuationPromptFor("en"))
    }

    @Test
    fun `a regional code falls back to its base language, not to english`() {
        assertEquals(DictatePromptDefaults.punctuationPromptFor("hr"), DictatePromptDefaults.punctuationPromptFor("hr-HR"))
        assertEquals(DictatePromptDefaults.punctuationPromptFor("de"), DictatePromptDefaults.punctuationPromptFor("de-AT"))
    }

    @Test
    fun `every mapped sentence looks like a greeting plus thanks`() {
        // Two sentences, no placeholders, short enough not to eat the model's context.
        for (lang in DictateLanguages.all.filter { it.code != DictateLanguages.DETECT }) {
            val prompt = DictatePromptDefaults.punctuationPromptFor(lang.code) ?: continue
            assertTrue(prompt.isNotBlank(), "${lang.code}: empty")
            assertTrue(prompt.length <= 60, "${lang.code}: suspiciously long — $prompt")
            assertTrue(!prompt.contains('{'), "${lang.code}: contains a placeholder — $prompt")
        }
    }
}
