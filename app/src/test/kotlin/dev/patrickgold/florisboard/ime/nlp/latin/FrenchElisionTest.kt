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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The language-specific safety boundary around automatic French apostrophe restoration. */
class FrenchElisionTest {

    private fun splits(word: String) = LatinLanguageProvider.frenchElisionSplits(word)

    private fun commits(typedFrequency: Int, candidates: List<Int>, suffix: String) =
        LatinLanguageProvider.shouldAutoCommitFrenchElision(typedFrequency, candidates, suffix)

    @Test
    fun `common productive elisions are reconstructed`() {
        for ((word, expected) in mapOf(
            "jaime" to ("j" to "aime"),
            "cest" to ("c" to "est"),
            "daccord" to ("d" to "accord"),
            "quon" to ("qu" to "on"),
            "quelquun" to ("quelqu" to "un"),
        )) {
            assertTrue(expected in splits(word), "$word -> ${splits(word)}")
        }
    }

    @Test
    fun `a consonant cannot start an elided suffix`() {
        assertTrue(splits("jchat").isEmpty())
        assertTrue(splits("ltrain").isEmpty())
    }

    @Test
    fun `one frequent elision of a non-word can be automatic`() {
        assertTrue(commits(typedFrequency = 0, candidates = listOf(219), suffix = "aime"))
    }

    @Test
    fun `an existing word is never silently rewritten`() {
        // dune/d'une, lame/l'âme and quelle/qu'elle are all real pairs in French.
        assertFalse(commits(typedFrequency = 153, candidates = listOf(244), suffix = "une"))
        assertFalse(commits(typedFrequency = 180, candidates = listOf(196), suffix = "âme"))
        assertFalse(commits(typedFrequency = 213, candidates = listOf(237), suffix = "elle"))
    }

    @Test
    fun `ambiguous and uncommon candidates remain tap suggestions`() {
        assertFalse(commits(typedFrequency = 0, candidates = listOf(219, 190), suffix = "aime"))
        assertFalse(commits(typedFrequency = 0, candidates = listOf(160), suffix = "aime"))
    }

    @Test
    fun `h remains manual because the dictionary cannot tell mute from aspirated`() {
        assertFalse(commits(typedFrequency = 0, candidates = listOf(219), suffix = "homme"))
    }
}
