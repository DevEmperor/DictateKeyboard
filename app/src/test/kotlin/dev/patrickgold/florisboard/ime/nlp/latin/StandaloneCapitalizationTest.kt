/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.nlp.latin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The English pronoun "I" (issue #333), and — the actual point of the tests — every language in which
 * the same letter must be left exactly as it was typed.
 *
 * A rule this small does not need guarding against being wrong; it needs guarding against being
 * *widened*. `i` is an ordinary, extremely common word in at least two of the languages this keyboard
 * ships layouts for, so the language check is not a refinement of the feature, it is the feature. Any
 * future edit that reaches this file should have to delete one of the tests below on purpose.
 */
class StandaloneCapitalizationTest {

    private fun cap(word: String, language: String) =
        LatinLanguageProvider.standaloneCapitalizationIn(word, language)

    @Test
    fun `English capitalises a lone i`() {
        assertEquals("I", cap("i", "en"))
        // The language tag arrives from the subtype's locale, whatever case it happens to carry.
        assertEquals("I", cap("i", "EN"))
    }

    @Test
    fun `Polish i means and, and is left alone`() {
        // "chleb i masło". Capitalising the conjunction would be wrong in every sentence it appears in,
        // and it appears in a great many of them.
        assertNull(cap("i", "pl"))
    }

    @Test
    fun `Italian i is a plural article, and is left alone`() {
        // "i libri".
        assertNull(cap("i", "it"))
    }

    @Test
    fun `no other language is touched`() {
        for (language in listOf("de", "fr", "es", "pt", "nl", "cs", "tr", "hu", "ru", "hi")) {
            assertNull(cap("i", language), "$language must not capitalise a lone i")
        }
    }

    @Test
    fun `no other word is touched`() {
        // Notably `a`, the other English one-letter word, which is not capitalised — and anything the
        // dictionary is already responsible for.
        for (word in listOf("a", "is", "it", "in", "hello", "iphone")) {
            assertNull(cap(word, "en"), "$word must be left to the dictionary")
        }
    }

    @Test
    fun `an already capital I is not rewritten`() {
        // Nothing to do, and returning "I" here would arm a backspace-undo that restores what is already
        // on screen — a keystroke that appears to do nothing.
        assertNull(cap("I", "en"))
    }
}
