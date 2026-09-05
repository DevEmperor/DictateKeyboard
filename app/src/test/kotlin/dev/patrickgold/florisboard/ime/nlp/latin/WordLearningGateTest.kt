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

import dev.patrickgold.florisboard.ime.nlp.WordOrigin
import dev.patrickgold.florisboard.ime.nlp.latin.WordLearningGate.Stage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bookkeeping half of the learning decision (issue #318): the conditions with exact answers.
 *
 * The statistical half — how well the tap and edit-distance witnesses actually separate a name from a
 * slip — is [LearningEvalTest], which measures rather than asserts. Both are needed: a rule can be
 * perfectly implemented and still be the wrong rule, and a rule can be the right one and still refuse to
 * learn anything because a flag was inverted.
 */
class WordLearningGateTest {

    private val day = 86_400L

    /** The happy path: everything is as it should be, and only the named parameter is changed per test. */
    private fun learns(
        enabled: Boolean = true,
        isPrivateField: Boolean = false,
        origin: WordOrigin = WordOrigin.TYPED,
        word: String = "klabautersteg",
        atSentenceStart: Boolean = false,
        autoCapitalizationOn: Boolean = true,
        isKnownWord: Boolean = false,
        cheapCorrectionExists: Boolean = false,
    ) = WordLearningGate.shouldLearn(
        enabled = enabled,
        isPrivateField = isPrivateField,
        origin = origin,
        word = word,
        atSentenceStart = atSentenceStart,
        autoCapitalizationOn = autoCapitalizationOn,
        isKnownWord = isKnownWord,
        cheapCorrectionExists = cheapCorrectionExists,
    )

    // ── The gates ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an unknown word typed mid-sentence is learned`() {
        assertTrue(learns())
    }

    @Test
    fun `nothing is learned while the feature is off`() {
        assertFalse(learns(enabled = false))
    }

    @Test
    fun `nothing is learned in a private field`() {
        assertFalse(learns(isPrivateField = true))
    }

    /**
     * The promise the maintainer asked for by name, pinned here so it cannot be lost to a refactor:
     * a dictation is not typing, and its spelling is the speech model's rather than the user's.
     */
    @Test
    fun `nothing is learned from dictation, a glide or the suggestion strip`() {
        assertFalse(learns(origin = WordOrigin.OTHER))
        assertFalse(learns(origin = WordOrigin.GESTURE))
        assertFalse(learns(origin = WordOrigin.CANDIDATE))
    }

    @Test
    fun `a word the dictionary already knows is not learned`() {
        assertFalse(learns(isKnownWord = true))
    }

    @Test
    fun `a word with a cheap correction is treated as a slip`() {
        assertFalse(learns(cheapCorrectionExists = true))
    }

    @Test
    fun `a sentence start is skipped only while auto-capitalisation could have caused it`() {
        assertFalse(learns(atSentenceStart = true, autoCapitalizationOn = true))
        assertTrue(learns(atSentenceStart = true, autoCapitalizationOn = false))
    }

    // ── Shape ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `too short is not vocabulary`() {
        assertFalse(WordLearningGate.isLearnableForm("ab"))
        assertTrue(WordLearningGate.isLearnableForm("abc"))
    }

    @Test
    fun `a word carrying a digit is a code, not vocabulary`() {
        // Same refusal the corrector makes for top10 and covid19 (issue #311).
        assertFalse(WordLearningGate.isLearnableForm("top10"))
        assertFalse(WordLearningGate.isLearnableForm("mp3"))
    }

    @Test
    fun `an apostrophe or a hyphen stays part of a word`() {
        assertTrue(WordLearningGate.isLearnableForm("o'brien"))
        assertTrue(WordLearningGate.isLearnableForm("well-known"))
        assertFalse(WordLearningGate.isLearnableForm("---"))
    }

    // ── The ladder ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `one sighting does nothing, two may be suggested, three are promoted`() {
        assertEquals(Stage.REMEMBERED, WordLearningGate.stageOf(1.0))
        assertEquals(Stage.SUGGESTED, WordLearningGate.stageOf(2.0))
        assertEquals(Stage.PROMOTED, WordLearningGate.stageOf(3.0))
    }

    /**
     * Found on the device: the store said "seen 2×" while the screen said "not suggested yet", and the
     * suggestion filter agreed with the screen — so the middle rung of the ladder never happened at all.
     *
     * Decay is continuous, so a word seen twice is worth exactly 2.0 only in the instant of the second
     * sighting. A `>= 2.0` threshold therefore fails one second later. Promotion only escaped it because
     * it is evaluated in the same second as the write.
     */
    @Test
    fun `a sighting still counts a second after it was made`() {
        val now = 1_000_000L
        val aSecondAgo = now - 1
        assertEquals(Stage.SUGGESTED, WordLearningGate.stageOf(WordLearningGate.decayedScore(2, aSecondAgo, now)))
        assertEquals(Stage.PROMOTED, WordLearningGate.stageOf(WordLearningGate.decayedScore(3, aSecondAgo, now)))
        // …and a week later, which is the case the ladder is actually about.
        val aWeekAgo = now - 7 * day
        assertEquals(Stage.SUGGESTED, WordLearningGate.stageOf(WordLearningGate.decayedScore(2, aWeekAgo, now)))
    }

    @Test
    fun `a word decayed most of the way down loses its rung`() {
        val now = 1_000_000L
        // Two sightings, two half-lives ago: worth 0.5, which is closer to one sighting than to two.
        val longAgo = now - (WordLearningGate.HALF_LIFE_DAYS * 2 * day).toLong()
        assertEquals(Stage.REMEMBERED, WordLearningGate.stageOf(WordLearningGate.decayedScore(2, longAgo, now)))
    }

    @Test
    fun `the score floor and the stage agree about where a rung begins`() {
        // Two ways of asking the same question; they must not drift apart.
        val floor = WordLearningGate.scoreFloorFor(WordLearningGate.SIGHTINGS_FOR_SUGGESTIONS)
        assertEquals(Stage.SUGGESTED, WordLearningGate.stageOf(floor))
        assertEquals(Stage.REMEMBERED, WordLearningGate.stageOf(floor - 0.001))
    }

    @Test
    fun `a rejected auto-correction is worth two ordinary sightings`() {
        // Not a coincidence worth losing: taking a correction back is the clearest statement the user
        // can make that they meant what they typed.
        assertEquals(Stage.SUGGESTED, WordLearningGate.stageOf(WordLearningGate.REJECTION_WEIGHT.toDouble()))
    }

    @Test
    fun `a score halves over one half-life and keeps falling`() {
        val now = 1_000_000L
        val fresh = WordLearningGate.decayedScore(4, now, now)
        val old = WordLearningGate.decayedScore(4, now - (WordLearningGate.HALF_LIFE_DAYS * day).toLong(), now)
        assertEquals(4.0, fresh, 1e-9)
        assertTrue(abs(old - 2.0) < 1e-6, "expected 2.0 after one half-life, got $old")
    }

    @Test
    fun `a sighting in the future is not worth more than one now`() {
        // Clocks move backwards — a time-zone change, a manual correction, an NTP step.
        val now = 1_000_000L
        assertEquals(1.0, WordLearningGate.decayedScore(1, now + 10 * day, now), 1e-9)
    }

    // ── The slip witnesses ───────────────────────────────────────────────────────────────────────

    private fun slip(
        typedWord: String = "klabautersteg",
        hadTapEvidence: Boolean = true,
        beamCorrection: String? = null,
        beamCost: Float? = null,
        nearestKnownFreq: Int = 0,
    ) = WordLearningGate.looksLikeASlip(
        typedWord, hadTapEvidence, beamCorrection, beamCost, nearestKnownFreq,
    )

    @Test
    fun `no witness says anything, so the word stands`() {
        assertFalse(slip())
    }

    @Test
    fun `a cheaply decoded alternative is a slip, an expensive one is a different word`() {
        val word = "eill"
        val cheap = WordLearningGate.SLIP_COST_PER_TAP * word.length * 0.5f
        val dear = WordLearningGate.SLIP_COST_PER_TAP * word.length * 3f
        assertTrue(slip(typedWord = word, beamCorrection = "will", beamCost = cheap))
        assertFalse(slip(typedWord = word, beamCorrection = "will", beamCost = dear))
    }

    @Test
    fun `the decoder answering with the typed word itself is not a correction`() {
        assertFalse(slip(typedWord = "will", beamCorrection = "will", beamCost = 0f))
        assertFalse(slip(typedWord = "Will", beamCorrection = "will", beamCost = 0f))
    }

    @Test
    fun `a common word one edit away counts against a long word but not a short one`() {
        val common = WordLearningGate.NEAR_MISS_MIN_FREQ
        assertTrue(slip(typedWord = "brothet", nearestKnownFreq = common))
        // Short words live in a neighbourhood so dense that this says nothing about them.
        assertFalse(slip(typedWord = "lars", nearestKnownFreq = common))
    }

    @Test
    fun `a rare neighbour is no evidence at all`() {
        assertFalse(slip(typedWord = "brothet", nearestKnownFreq = WordLearningGate.NEAR_MISS_MIN_FREQ - 1))
    }

    /**
     * Without taps there is only one witness left, so it is not also weakened by the length exemption.
     * Measured, keeping the exemption there let a physical keyboard remember 47 % of typos against 29 %.
     */
    @Test
    fun `without tap evidence the length exemption does not apply`() {
        assertTrue(
            slip(
                typedWord = "lars",
                hadTapEvidence = false,
                nearestKnownFreq = WordLearningGate.NEAR_MISS_MIN_FREQ,
            ),
        )
    }

    /**
     * A beam that comes back empty is a *result*, not a missing witness: no dictionary word is reachable
     * from those fingers. Reading it as "no evidence" would apply the stricter rule to precisely the
     * words that just proved themselves genuine.
     */
    @Test
    fun `an empty beam still counts as having had tap evidence`() {
        assertFalse(
            slip(
                typedWord = "lars",
                hadTapEvidence = true,
                beamCorrection = null,
                nearestKnownFreq = WordLearningGate.NEAR_MISS_MIN_FREQ,
            ),
        )
    }
}
