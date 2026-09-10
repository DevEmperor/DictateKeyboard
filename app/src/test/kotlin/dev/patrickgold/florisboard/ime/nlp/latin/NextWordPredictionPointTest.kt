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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers where next-word prediction is allowed to speak (issues #245, #266).
 *
 * The gate reads two things that disagree with each other on purpose: the text, which after an accepted
 * suggestion still ends in a letter, and the pending phantom space, which says that word is nevertheless
 * finished. Trusting only the text is what kept the strip empty until the user pressed space.
 */
class NextWordPredictionPointTest {

    private fun atPoint(textBefore: String, phantomSpacePending: Boolean = false) =
        LatinLanguageProvider.isAtPredictionPoint(textBefore, phantomSpacePending)

    @Test
    fun `a typed space finishes a word`() {
        assertTrue(atPoint("das ist "))
    }

    @Test
    fun `a promised space finishes it just as well`() {
        // What tapping a suggestion leaves behind: the word is committed, the space is not written yet.
        assertTrue(atPoint("das ist", phantomSpacePending = true))
    }

    @Test
    fun `mid-word there is nothing to continue`() {
        assertFalse(atPoint("das ist wund"))
    }

    @Test
    fun `an empty field offers nothing, so the quick actions stay`() {
        assertFalse(atPoint(""))
        assertFalse(atPoint("   "))
        assertFalse(atPoint("", phantomSpacePending = true))
    }

    @Test
    fun `a finished sentence is not continued`() {
        assertFalse(atPoint("das ist gut. "))
        assertFalse(atPoint("wirklich? "))
        assertFalse(atPoint("nein! "))
        assertFalse(atPoint("und so weiter… "))
    }

    @Test
    fun `a promised space does not smuggle the sentence end past the gate`() {
        // Committing something that ends in a full stop still ends the sentence.
        assertFalse(atPoint("das ist gut.", phantomSpacePending = true))
    }

    @Test
    fun `a comma is not a sentence end`() {
        // This gate lets a comma through; whether a prediction then appears is decided further on by the
        // previous-word lookup, which today stops at any punctuation. So nothing is offered after a comma
        // either — but for a different reason, and one that could sensibly change.
        assertTrue(atPoint("ich denke, "))
        assertTrue(atPoint("erstens; "))
    }

    @Test
    fun `several spaces are looked past`() {
        assertTrue(atPoint("das ist   "))
        assertFalse(atPoint("das ist gut.   "))
    }

    // ── What the strip ends up showing (issue #334) ──────────────────────────────────────────────

    private fun merge(learned: List<String>, deep: List<String>, shallow: List<String>, max: Int = 3) =
        LatinLanguageProvider.mergePredictions(learned, deep, shallow, max)

    @Test
    fun `the user's own pairs come before any corpus`() {
        val out = merge(listOf("Dario"), listOf("world", "end"), listOf("same", "first"))
        assertEquals(listOf("Dario" to true, "world" to false, "end" to false), out)
    }

    /**
     * The whole point of the second word of context: where it has an answer it goes in front of the
     * one-word table, because it was measured to be right more often exactly there.
     */
    @Test
    fun `two words of context outrank one`() {
        val out = merge(emptyList(), listOf("world", "end"), listOf("same", "first", "most"))
        assertEquals(listOf("world" to false, "end" to false, "same" to false), out)
    }

    /** With no trigram evidence the strip is what it was before the tier existed. */
    @Test
    fun `without deep evidence nothing changes`() {
        val out = merge(emptyList(), emptyList(), listOf("same", "first", "most", "other"))
        assertEquals(listOf("same" to false, "first" to false, "most" to false), out)
    }

    /**
     * A word both tables offer must not take two slots — and it keeps the mark of the best evidence
     * that produced it, so a learned word stays italic even when the corpus suggests it too.
     */
    @Test
    fun `a word offered twice keeps its first position`() {
        val out = merge(listOf("end"), listOf("end", "world"), listOf("end", "same"))
        assertEquals(listOf("end" to true, "world" to false, "same" to false), out)
    }

    @Test
    fun `blank candidates never reach the strip`() {
        val out = merge(listOf(""), listOf(" ", "world"), listOf("", "same"))
        assertEquals(listOf("world" to false, "same" to false), out)
    }
}
