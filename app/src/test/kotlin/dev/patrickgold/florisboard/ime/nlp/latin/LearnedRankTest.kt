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
 * Where a word the keyboard picked up by itself lands in the strip (issue #318, round 3).
 *
 * It used to be one number for everything learned, which meant a name typed every day ranked exactly
 * like one typed twice in March. The rank now grows with the decayed sighting count — but it has to grow
 * *between* two fixed points, and both of them are load-bearing: at exactly two sightings nothing may
 * move, or the day this shipped every existing user's strip would have reshuffled for no reason; and no
 * amount of usage may reach the band of the words the user typed into their dictionary on purpose.
 */
class LearnedRankTest {

    private fun rank(score: Double) = LatinLanguageProvider.learnedRankFor(score)

    /** What a word the user added by hand is worth. Nothing learned automatically may reach it. */
    private val userDictionaryBand = 212

    @Test
    fun `two sightings keep the rank they always had`() {
        assertEquals(187, rank(WordLearningGate.SIGHTINGS_FOR_SUGGESTIONS.toDouble()))
    }

    @Test
    fun `usage moves the rank up, monotonically`() {
        var previous = rank(WordLearningGate.scoreFloorFor(WordLearningGate.SIGHTINGS_FOR_SUGGESTIONS))
        for (score in listOf(2.0, 3.0, 5.0, 10.0, 25.0, 100.0, 1000.0)) {
            val current = rank(score)
            assertTrue(current >= previous, "rank fell from $previous to $current at score $score")
            previous = current
        }
        assertTrue(rank(20.0) > rank(2.0), "twenty sightings must outrank two")
    }

    @Test
    fun `a hand-added word always comes first`() {
        for (score in listOf(2.0, 50.0, 5_000.0, Double.MAX_VALUE)) {
            assertTrue(rank(score) < userDictionaryBand, "score $score reached the personal-dictionary band")
        }
    }

    @Test
    fun `a decaying word does not fall out of the world`() {
        // Below the suggestion floor the rank is still a number, because the corrector asks about words
        // the strip would not offer. It just has to stay a low one.
        assertTrue(rank(0.0) in 170..187)
        assertTrue(rank(1.0) < rank(2.0))
    }
}
