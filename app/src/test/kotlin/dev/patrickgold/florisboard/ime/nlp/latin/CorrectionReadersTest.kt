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
import kotlin.test.assertTrue

/**
 * The rules of [CorrectionReaders] on their own; `ReaderMergeEvalTest` and `PrefixSlipEvalTest` measure what
 * they are worth.
 */
class CorrectionReadersTest {

    // ── merge: the fixes of both readers (issue #381, step 3) ─────────────────────────────────────

    @Test
    fun theStringReaderGetsTheLastSlotEvenWhenTheBeamFilledAll() {
        assertEquals(listOf("growl", "heidi", "hello"), CorrectionReaders.merge(listOf("growl", "heidi", "howl"), listOf("hello", "help"), 3))
    }

    @Test
    fun theBeamKeepsTheLead() {
        assertEquals("growl", CorrectionReaders.merge(listOf("growl"), listOf("hello", "help"), 3).first())
        assertEquals(listOf("growl"), CorrectionReaders.merge(listOf("growl", "heidi"), listOf("hello"), 1))
    }

    @Test
    fun emptySlotsAreFilledFromEitherReader() {
        assertEquals(listOf("growl", "hello", "help"), CorrectionReaders.merge(listOf("growl"), listOf("hello", "help"), 3))
        assertEquals(listOf("hello", "help"), CorrectionReaders.merge(emptyList(), listOf("hello", "help"), 3))
        assertEquals(listOf("growl", "heidi"), CorrectionReaders.merge(listOf("growl", "heidi"), emptyList(), 3))
    }

    @Test
    fun aWordBothReadersFoundIsOfferedOnceAndDoesNotCostTheBeamASlot() {
        assertEquals(listOf("a", "b", "c"), CorrectionReaders.merge(listOf("a", "b", "c"), listOf("a"), 3))
        assertEquals(listOf("a", "b", "x"), CorrectionReaders.merge(listOf("a", "b", "c"), listOf("b", "x"), 3))
    }

    // ── completions of a slip inside a half-typed word (issue #381, step 2) ───────────────────────

    // Sorted, as the index requires: "beau" before "because".
    private val words = arrayOf("beau", "because", "consent", "consequence", "consequences", "vera")
    private val freqs = intArrayOf(170, 250, 200, 190, 210, 180)
    private val index = TouchBeamDecoder.PrefixIndex(words, freqs)
    private val alphabet = words.flatMapTo(HashSet()) { it.asIterable() } + 'v' + 'd'

    private fun byString(typed: String) = CorrectionReaders.completionsByString(
        folded = typed, prefixIndex = index, alphabet = alphabet, maxCount = 5, sqDistance = { _, _ -> 1f },
        contextScore = { 0.0 },
    )

    @Test
    fun aSlipInsideTheTypedPrefixStillCompletes() {
        // One key over at the start, and one tap too many at the end: neither is a prefix of anything.
        assertEquals("because", byString("vecau").first())
        assertEquals("consequences", byString("conseqd").first())
    }

    @Test
    fun onlyWordsLongerThanWhatWasTypedAreCompletions() {
        // "beau" is one edit from "veau" but no longer than it: that is a fix, not a completion.
        assertTrue("beau" !in byString("veau"), byString("veau").toString())
    }

    @Test
    fun anIndexWithoutFrequenciesOffersNoCompletions() {
        val bare = TouchBeamDecoder.PrefixIndex(words)
        assertTrue(
            CorrectionReaders.completionsByString("vecau", bare, alphabet, 5, { _, _ -> 1f }, { 0.0 }).isEmpty(),
        )
    }

    @Test
    fun completionsAlternateTheStringReaderFirst() {
        assertEquals(listOf("s1", "t1", "s2", "t2", "t3"), CorrectionReaders.completions(listOf("t1", "t2", "t3"), listOf("s1", "s2")))
        assertEquals(listOf("x", "t2"), CorrectionReaders.completions(listOf("x", "t2"), listOf("x")))
    }

    private val freqOf = mapOf("vera" to 180, "because" to 250, "beau" to 170, "vega" to 175, "rare" to 130)

    @Test
    fun aCommonerCompletionTakesTheSecondSlot() {
        assertEquals(
            listOf("vera", "because", "beau", "vega"),
            CorrectionReaders.withCompletions(listOf("vera", "beau", "vega"), listOf("because")) { freqOf[it] ?: 0 },
        )
    }

    @Test
    fun aRarerCompletionFollowsTheFixes() {
        assertEquals(
            listOf("vera", "beau", "vega", "rare"),
            CorrectionReaders.withCompletions(listOf("vera", "beau", "vega"), listOf("rare")) { freqOf[it] ?: 0 },
        )
    }

    @Test
    fun theFirstFixNeverMoves() {
        // Even a completion commoner than every fix: the first fix is the one the silent swap is about.
        assertEquals("beau", CorrectionReaders.withCompletions(listOf("beau"), listOf("because")) { freqOf[it] ?: 0 }.first())
        assertEquals(listOf("because"), CorrectionReaders.withCompletions(emptyList(), listOf("because")) { freqOf[it] ?: 0 })
    }
}
