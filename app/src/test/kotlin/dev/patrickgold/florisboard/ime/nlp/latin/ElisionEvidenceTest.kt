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
 * The corpus rule behind French apostrophe restoration (issue #212, French follow-up).
 *
 * [FrenchElisionEvalTest] measures what the rule is worth against the shipped data; this one pins the
 * behaviour that measurement depends on, so it still fails on a checkout where the corpora are absent.
 */
class ElisionEvidenceTest {

    private fun fr(word: String) = DictFold.foldKey("fr", word)
    private fun key(word: String) = ElisionEvidence.key(fr(word))

    private val corpus = ElisionEvidence.parse(
        """
        |c'est un	600
        |cest un	5
        |qu’elle a	160
        |quelle est	80
        |de la	9000
        """.trimMargin(),
    ) { fr(it) }

    // --- the table -------------------------------------------------------------------------------

    @Test
    fun `only apostrophe forms and their bare spellings are kept`() {
        assertEquals(setOf("c'est", "cest", "qu'elle", "quelle"), corpus.keys)
    }

    @Test
    fun `a token is counted in every n-gram it appears in`() {
        assertEquals(600, corpus["c'est"])
        assertEquals(160, corpus["qu'elle"])
    }

    @Test
    fun `the typographic apostrophe is the same word as the straight one`() {
        // The corpus writes qu’elle and the reconstruction writes qu'elle; DictFold leaves both alone,
        // because an apostrophe is punctuation rather than a diacritic.
        assertEquals("qu'elle", key("qu’elle"))
        assertTrue(ElisionEvidence.isAttested(corpus, key("qu'elle")))
    }

    @Test
    fun `an accent is folded away on both sides, so the corpus spelling is found`() {
        assertEquals(key("l'année"), key("l'annee"))
    }

    // --- the rule --------------------------------------------------------------------------------

    @Test
    fun `a form the corpus never writes is not a word`() {
        // "on" is a word and `n'` is a prefix, so the reconstruction offers n'on — and running French
        // never contains it. This is the whole guard against inventing words.
        assertFalse(ElisionEvidence.isAttested(corpus, key("n'on")))
        assertTrue(("n" to "on") in LatinLanguageProvider.frenchElisionSplits("non"))
    }

    @Test
    fun `a lopsided pair is a slip and may be replaced`() {
        // cest appears 5 times against c'est 600 — 120x, well past the threshold.
        assertTrue(ElisionEvidence.mayReplace(corpus, key("cest"), key("c'est")))
    }

    @Test
    fun `a close pair is two real words and may only be offered`() {
        // quelle 80 against qu'elle 160 — twice as common is not evidence of a typo.
        assertFalse(ElisionEvidence.mayReplace(corpus, key("quelle"), key("qu'elle")))
    }

    @Test
    fun `an unseen spelling counts as one sighting, not as none`() {
        val sparse = ElisionEvidence.parse("l'or est\t${ElisionEvidence.DOMINANCE - 1}\n") { fr(it) }
        assertFalse(ElisionEvidence.mayReplace(sparse, key("lor"), key("l'or")))
        val enough = ElisionEvidence.parse("l'or est\t${ElisionEvidence.DOMINANCE}\n") { fr(it) }
        assertTrue(ElisionEvidence.mayReplace(enough, key("lor"), key("l'or")))
    }

    @Test
    fun `nothing is replaced when the corpus is missing`() {
        assertFalse(ElisionEvidence.mayReplace(emptyMap(), key("cest"), key("c'est")))
    }

    // --- the splits the rule is asked about ------------------------------------------------------

    @Test
    fun `productive elisions are proposed`() {
        for ((word, split) in mapOf(
            "jaime" to ("j" to "aime"),
            "cest" to ("c" to "est"),
            "daccord" to ("d" to "accord"),
            "quon" to ("qu" to "on"),
            "quelquun" to ("quelqu" to "un"),
            "lhomme" to ("l" to "homme"),
        )) {
            assertTrue(split in LatinLanguageProvider.frenchElisionSplits(word), "$word")
        }
    }

    @Test
    fun `an accented suffix is recognised by its base letter`() {
        assertTrue(("l" to "état") in LatinLanguageProvider.frenchElisionSplits("létat"))
    }

    @Test
    fun `a consonant cannot start an elided suffix`() {
        assertTrue(LatinLanguageProvider.frenchElisionSplits("jchat").isEmpty())
        assertTrue(LatinLanguageProvider.frenchElisionSplits("ltrain").isEmpty())
    }

    @Test
    fun `a word shorter than its prefix is not a split of it`() {
        assertTrue(LatinLanguageProvider.frenchElisionSplits("qu").isEmpty())
    }
}
