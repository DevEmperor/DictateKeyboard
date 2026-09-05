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
import kotlin.math.pow

/**
 * Whether a word the user just finished may be remembered, and what a remembered word is allowed to do
 * (issue #318).
 *
 * ### The problem this exists to solve
 *
 * A keyboard that remembers what you type is only useful if it remembers the right things. The failure
 * mode is not "learns too little" — it is a typo that gets learned, becomes a known word, and is
 * therefore never corrected again. That one cements itself: the more often autocorrect leaves it alone,
 * the more often it is typed, the more firmly it sits in the vocabulary.
 *
 * So the decision is split in two. **Whether to count a sighting at all** is this object's [shouldLearn],
 * deliberately strict. **What a word may do once counted** is the ladder in [Stage], deliberately slow:
 *
 * | sightings | may appear in the strip | protects itself from autocorrect |
 * |---|---|---|
 * | 1 · [Stage.REMEMBERED] | no | no |
 * | 2 · [Stage.SUGGESTED]  | yes, marked, never auto-committed | no |
 * | 3 · [Stage.PROMOTED]   | yes — it is now an ordinary personal-dictionary entry | yes |
 *
 * The shape is the one AOSP's `UserHistoryDictionary` has (its `isValidWord()` answers *false* for
 * everything it holds; only promotion into the personal dictionary makes a word valid), reached
 * independently and for the reason above rather than copied. What is genuinely ours is the entry test:
 * this keyboard records where the fingers landed (issue #242), so it can ask a question no
 * dictionary-only learner can — *did this look like a slip?*
 *
 * ### Why the tap evidence is the important gate
 *
 * Every other condition below is bookkeeping: the right field, the right origin, a plausible shape. The
 * one that separates "a name the dictionary does not know" from "a word the finger got wrong" is
 * [looksLikeASlip], which weighs the same tap evidence the corrector weighs — at a deliberately more
 * suspicious threshold, because refusing to remember something is cheap and rewriting it is not.
 */
internal object WordLearningGate {

    /** Shorter than this is noise: initials, stray letters, the tail of an interrupted word. */
    const val MIN_LENGTH = 3

    /** Sightings before a word may be offered in the suggestion strip. */
    const val SIGHTINGS_FOR_SUGGESTIONS = 2

    /** Sightings before a word is promoted into the personal dictionary. */
    const val SIGHTINGS_FOR_PROMOTION = 3

    /**
     * Days after which an un-promoted word is worth half as much.
     *
     * Long, because this is a vocabulary and not a cache: a word used twice in March and once in May is
     * exactly the kind of personal term worth keeping. Short enough that a burst of one-off words from a
     * single unusual evening does not sit in the store forever. Promoted words never decay — those
     * belong to the user, not to us.
     */
    const val HALF_LIFE_DAYS = 60.0

    /** A word restored by taking back an auto-correction counts double. See [shouldLearn]. */
    const val REJECTION_WEIGHT = 2

    /** How far up the ladder a word has climbed. */
    enum class Stage {
        /** Counted, and nothing more. Invisible to the user and to every other part of the engine. */
        REMEMBERED,

        /** May be offered in the strip, marked as learned. Never auto-committed, never "known". */
        SUGGESTED,

        /** Belongs in the personal dictionary: known to autocorrect, swipeable, in the backup. */
        PROMOTED,
    }

    /**
     * The decayed weight of a word seen [count] times, last at [lastUsedEpochSeconds].
     *
     * Exponential decay on the half-life above. Promoted entries do not pass through here at all.
     */
    fun decayedScore(count: Int, lastUsedEpochSeconds: Long, nowEpochSeconds: Long): Double {
        if (count <= 0) return 0.0
        val elapsedDays = (nowEpochSeconds - lastUsedEpochSeconds).coerceAtLeast(0L) / 86_400.0
        return count * 0.5.pow(elapsedDays / HALF_LIFE_DAYS)
    }

    /** Where [score] sits on the ladder, for a word that has not been promoted yet. */
    fun stageOf(score: Double): Stage = when {
        score >= SIGHTINGS_FOR_PROMOTION -> Stage.PROMOTED
        score >= SIGHTINGS_FOR_SUGGESTIONS -> Stage.SUGGESTED
        else -> Stage.REMEMBERED
    }

    /**
     * Whether [word] has the shape of vocabulary at all.
     *
     * Digits are refused for the same reason the corrector refuses to judge them (issue #311): `top10`,
     * `covid19` and `mp3` are codes, not words, and the dictionary has no business holding an opinion
     * about them either way. An apostrophe or hyphen may stay — *don't* and *well-known* are words.
     */
    fun isLearnableForm(word: String): Boolean {
        if (word.length < MIN_LENGTH) return false
        if (word.any { it.isDigit() }) return false
        if (word.none { it.isLetter() }) return false
        return word.all { it.isLetter() || it == '\'' || it == '’' || it == '-' }
    }

    /**
     * Excess tap distance per tap (key-width²) below which a decoded alternative counts as a slip rather
     * than as a different word.
     *
     * Larger than any budget in [AutoCommitGate], and that is the point. Those budgets answer *are we
     * sure enough to rewrite this?* and are therefore reluctant; this one answers *is there enough doubt
     * not to remember it?* and is therefore suspicious. Reusing the auto-commit budget here was the first
     * attempt and measured 67 % of typos remembered — it refuses to act unless the case is overwhelming,
     * which is exactly wrong for a filter.
     *
     * It cannot simply be "any alternative at all" either: a word the dictionary does not know can never
     * be decoded as itself, so the beam always answers with *something*, and for a four-letter word that
     * something is always close by. Measured, that rule remembers 11.6 % of correctly typed short words —
     * it rejects almost every name, which is the one thing the feature exists to learn.
     *
     * At 0.5 per tap, on the shipping English dictionary: 93.0 % of long correctly typed unknown words
     * and 60.6 % of short ones are learned, against 8.6 % of mis-typed words. See [LearningEvalTest].
     */
    const val SLIP_COST_PER_TAP = 0.5f

    /**
     * From this length up, "one edit from a reasonably common word" counts as evidence of a slip.
     *
     * Below it, it counts as nothing. In a 48 000-word dictionary almost every four-letter string is one
     * edit from something common, so the test is true of names and typos alike and separates neither;
     * from six letters up it is a real coincidence. Measured on top of the tap witness: it costs 4.4 pp
     * of long correctly typed words and 0.3 pp of short ones, and takes typos remembered from 25.6 % to
     * 8.6 % — transpositions alone from 76 % to 14 %.
     */
    const val NEAR_MISS_MIN_LENGTH = 6

    /** How common the one-edit neighbour has to be to count. On the dictionary's 128..255 scale. */
    const val NEAR_MISS_MIN_FREQ = 150

    /**
     * Whether the evidence says [typedWord] was a slip rather than a word the user meant.
     *
     * Two witnesses, because they are blind to different things and neither is enough alone:
     *
     *  - **Where the fingers landed.** [beamCorrection] is what the touch decoder read out of the taps
     *    (null when it had nothing to say) and [beamCost] the excess squared distance that reading cost.
     *    This sees a finger that landed one key over — the ordinary slip — and is worth more than any
     *    amount of dictionary reasoning, because it is evidence about *this* typing rather than about
     *    the language.
     *  - **Whether the dictionary holds something one edit away**, via
     *    [EditDistance.nearestKnownFrequency] passed in as [nearestKnownFreq]. Needed because the taps
     *    are blind to a transposition by construction: both keys were hit dead-centre, only in the wrong
     *    order, so the spatial evidence says the typing was perfect. Measured, the tap witness alone lets
     *    through 65 % of transposed words and this brings it to 7 %.
     *
     * On a hardware keyboard there are no taps at all, so only the second witness speaks — and then it
     * speaks at *every* length, [NEAR_MISS_MIN_LENGTH] notwithstanding. A single witness must not also
     * be weakened: with the length exemption in place, a physical keyboard remembered 47 % of typos
     * against 29 % without it. The exemption exists to stop the dictionary drowning out the tap
     * evidence on short words, and where there is no tap evidence there is nothing to drown out.
     *
     * The cost is that short unknown words are rarely learned on a physical keyboard (measured 25 %),
     * which is the honest trade — those users still have the long-press (issue #241).
     */
    fun looksLikeASlip(
        typedWord: String,
        hadTapEvidence: Boolean,
        beamCorrection: String?,
        beamCost: Float?,
        nearestKnownFreq: Int,
    ): Boolean {
        if (beamCorrection != null && !beamCorrection.equals(typedWord, ignoreCase = true)) {
            if (beamCost == null || beamCost <= SLIP_COST_PER_TAP * typedWord.length) return true
        }
        if (nearestKnownFreq < NEAR_MISS_MIN_FREQ) return false
        // [hadTapEvidence] is about whether the taps were *recorded*, not about whether the beam found
        // anything in them. A beam that comes back empty is a positive result — no dictionary word is
        // reachable from these fingers — and treating that as "no evidence" would apply the stricter
        // rule to exactly the words that just proved themselves genuine.
        return !hadTapEvidence || typedWord.length >= NEAR_MISS_MIN_LENGTH
    }

    /**
     * Whether the word that was just finished should count as a sighting.
     *
     * Every parameter is a fact about the moment the word ended, so that this whole decision can be
     * tested without a keyboard — the surrounding path cannot be driven under instrumentation, which is
     * the same reason `fieldContentFrom` (issue #314) and `isAtPredictionPoint` (issue #245) are shaped
     * this way.
     *
     * @param enabled the user switched word learning on. Off by default.
     * @param isPrivateField incognito, a password field, or a field that asked for no suggestions.
     * @param origin how the word reached the editor; only [WordOrigin.TYPED] may be learned.
     * @param word the word as it was typed, trimmed of its trailing separator.
     * @param atSentenceStart the word opened a sentence.
     * @param autoCapitalizationOn the setting that would have capitalised it there.
     * @param isKnownWord any configured language's dictionary knows it, or it is already personal.
     * @param cheapCorrectionExists the verdict of [correctionLooksLikeASlip].
     */
    fun shouldLearn(
        enabled: Boolean,
        isPrivateField: Boolean,
        origin: WordOrigin,
        word: String,
        atSentenceStart: Boolean,
        autoCapitalizationOn: Boolean,
        isKnownWord: Boolean,
        cheapCorrectionExists: Boolean,
    ): Boolean {
        if (!enabled) return false
        if (isPrivateField) return false
        if (origin != WordOrigin.TYPED) return false
        if (!isLearnableForm(word)) return false
        if (isKnownWord) return false
        if (cheapCorrectionExists) return false
        // At a sentence start with auto-capitalisation on, there is no way to tell whether the capital
        // was the user's decision or ours — and a wrongly capitalised entry is exactly the kind of debris
        // that makes a personal dictionary worse than none. The word is learned the next time it appears
        // mid-sentence; at three sightings that costs almost nothing.
        if (atSentenceStart && autoCapitalizationOn) return false
        return true
    }
}
