/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.nlp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers who gets to ask the word provider (issue #297).
 *
 * The switch used to be read nowhere near this decision: `isSuggestionOn()` is an OR across word, emoji
 * and provider-forced suggestions, so emoji suggestions — on by default — kept the word provider running
 * for someone who had turned "Display suggestions" off. And because turning it off also turns composing
 * off, the provider then fell through to next-word predictions, which check only their own preference,
 * greyed out and still stored as `true`.
 *
 * The exception is the part worth pinning down: a shape-based provider is not offering a convenience, it
 * *is* the way that language is typed.
 */
class WordSuggestionGateTest {

    @Test
    fun `the switch decides for an ordinary provider`() {
        assertTrue(wantsWordSuggestions(displaySuggestions = true, providerForcesSuggestionOn = false))
        assertFalse(wantsWordSuggestions(displaySuggestions = false, providerForcesSuggestionOn = false))
    }

    @Test
    fun `a provider that types through its candidates keeps them`() {
        // Han shape-based input: without the candidate list there is no way to enter a character at all,
        // so switching suggestions off must not take the language away.
        assertTrue(wantsWordSuggestions(displaySuggestions = false, providerForcesSuggestionOn = true))
    }

    @Test
    fun `both together is still yes`() {
        assertTrue(wantsWordSuggestions(displaySuggestions = true, providerForcesSuggestionOn = true))
    }
}
