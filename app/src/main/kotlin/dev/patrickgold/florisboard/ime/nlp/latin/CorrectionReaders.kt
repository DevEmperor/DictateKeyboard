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

import kotlin.math.ln

/**
 * The two ways a word the dictionary does not know is read back into words it does.
 *
 * - [byTouch] asks where the fingers were aiming: a beam over the taps (issue #242). It sees a finger
 *   that slid onto the next key better than anything else, and nothing else.
 * - [byEditDistance] asks which dictionary words the typed *string* almost is. It is blind to the taps,
 *   and so it is not fooled by them either.
 *
 * They fail on opposite inputs, which is the reason [merge] exists (issue #381). `helwo` meant as `hello`
 * has every tap on a neighbour of `growl`, so the beam reads `growl` and is done; the string is one
 * letter from `hello`, which only the edit-distance reader can see. For as long as a beam result
 * replaced the other reader outright, that word never reached the strip.
 *
 * Pure functions over plain maps, so the evaluation harness drives exactly what the keyboard runs —
 * the same reason [TouchScoring] is its own object.
 */
internal object CorrectionReaders {

    // Keyboard-proximity noisy-channel model of the edit-distance reader (Tier 1). Distances are in
    // key-width² units. Its own constants, kept apart from [TouchScoring], so the ranking without a trace
    // is bit-for-bit what it was before the touch path existed.
    private const val PROX_SIGMA2 = 1.0         // touch variance (~1 key-width std): near mis-taps cost little
    private const val NEUTRAL_SUB_SQDIST = 2.0  // fallback substitution distance² when key geometry is unknown
    private const val LENGTH_DIFF_PENALTY = -0.7 // flat log-penalty for insert/delete candidates
    private const val TRANSPOSE_PENALTY = -0.3   // adjacent-swap typo; cost independent of key distance

    /** Flat cost for a candidate of a different length (a dropped or doubled letter) on the touch path. */
    private const val TOUCH_LENGTH_PENALTY = -5.0

    /** How many words the beam returns before scoring. */
    const val BEAM_CANDIDATES = 12

    /** How many spelling fixes the strip offers for one word (issue #212). */
    const val MAX_CORRECTIONS = 3

    /** Longest word the distance-2 search runs for; it grows with the square of the length. */
    const val MAX_DISTANCE2_LEN = 12

    /**
     * Corrections decoded from tap positions, plus how well the taps actually support the best one.
     *
     * [topCost] is the winning candidate's excess tap distance, or null when it came from edit distance and
     * there is therefore no positional evidence either way (a dropped or doubled letter).
     */
    class TouchReading(val words: List<String>, val topCost: Float?)

    /**
     * Corrections decoded from where the fingers landed, as folded dictionary keys — or null when the beam
     * finds nothing at all.
     *
     * The beam contributes same-length candidates; a dropped or doubled letter changes the length and
     * cannot come out of it, so those still come from [EditDistance.edits1] and are scored with a flat
     * penalty. Both are then ranked on one scale: linear log-frequency prior, minus the excess tap
     * distance, plus the bigram context bonus.
     */
    fun byTouch(
        points: FloatArray,
        typed: String,
        folded: String,
        freq: Map<String, Int>,
        alphabet: Set<Char>,
        prefixIndex: TouchBeamDecoder.PrefixIndex,
        layout: KeyProximityInfo.Layout,
        maxCount: Int,
        contextScore: (cand: String) -> Double,
    ): TouchReading? {
        val beam = TouchBeamDecoder.decode(
            points = points,
            typed = typed,
            index = prefixIndex,
            layout = layout,
            maxResults = BEAM_CANDIDATES,
        )
        if (beam.isEmpty()) return null

        val scored = HashMap<String, Double>(beam.size * 2)
        // Tap cost per beam candidate, kept so the caller can tell a near-boundary slip (trustworthy enough
        // to swap in silently) from a candidate a whole key away (offer it, but don't act on it).
        val costs = HashMap<String, Float>(beam.size)
        for (candidate in beam) {
            val f = freq[candidate.word] ?: continue
            scored[candidate.word] = TouchScoring.score(f, candidate.cost, contextScore(candidate.word))
            costs[candidate.word] = candidate.cost
        }
        // Length-changing slips (a letter dropped or typed twice) are invisible to the beam.
        for (edit in EditDistance.edits1(folded, alphabet)) {
            if (edit.length == folded.length) continue
            val f = freq[edit] ?: continue
            scored.putIfAbsent(edit, TouchScoring.lmPrior(f) + TOUCH_LENGTH_PENALTY + contextScore(edit))
        }
        if (scored.isEmpty()) return null
        val ranked = scored.entries.sortedByDescending { it.value }.take(maxCount)
        return TouchReading(words = ranked.map { it.key }, topCost = costs[ranked.first().key])
    }

    /**
     * What the edit-distance reader offers the strip: [words] as folded keys, best first, and whether
     * distance 1 found nothing — in which case [words] came from distance 2, which is never trusted enough
     * to be swapped in silently.
     */
    class StringReading(val words: List<String>, val distance1Empty: Boolean)

    /**
     * The edit-distance reading the strip asks for: distance 1, and distance 2 where that found nothing —
     * but only when [allowDistance2], which the caller passes only when the beam had nothing to say.
     *
     * Distance 2 is what makes that restriction necessary. It walks every edit of every edit, measured at
     * 84 ms per word on a desktop JVM (p90 144 ms) against 0.19 ms for distance 1, and this runs on every
     * keystroke. Running it beside a beam that had already spoken bought nothing: in `ReaderMergeEvalTest`
     * the fixes found were the same to the word, with or without it.
     */
    fun byString(
        folded: String,
        freq: Map<String, Int>,
        alphabet: Set<Char>,
        sqDistance: (Char, Char) -> Float?,
        contextScore: (cand: String) -> Double,
        allowDistance2: Boolean,
    ): StringReading {
        val near = byEditDistance(folded, freq, alphabet, MAX_CORRECTIONS, false, sqDistance, contextScore)
        if (near.isNotEmpty() || !allowDistance2 || folded.length > MAX_DISTANCE2_LEN) {
            return StringReading(near, near.isEmpty())
        }
        return StringReading(
            words = byEditDistance(folded, freq, alphabet, MAX_CORRECTIONS, true, sqDistance, contextScore),
            distance1Empty = true,
        )
    }

    /**
     * Dictionary words closest to (a misspelling of) [folded], as folded keys, best first. Distance 2 only
     * when [allowDistance2] and distance 1 found nothing.
     *
     * [sqDistance] is the squared distance between two keys in key-width², or null when the geometry does
     * not know one of them — which reduces the ranking to frequency alone.
     */
    fun byEditDistance(
        folded: String,
        freq: Map<String, Int>,
        alphabet: Set<Char>,
        maxCount: Int,
        allowDistance2: Boolean,
        sqDistance: (Char, Char) -> Float?,
        contextScore: (cand: String) -> Double = { 0.0 },
    ): List<String> {
        val e1 = EditDistance.edits1(folded, alphabet)
        val known = e1.filterTo(LinkedHashSet()) { freq.containsKey(it) }
        if (known.isEmpty() && allowDistance2) {
            for (e in e1) for (ee in EditDistance.edits1(e, alphabet)) {
                if (freq.containsKey(ee)) known.add(ee)
            }
        }
        // Noisy-channel ranking (Tier 1): combine the unigram prior with a keyboard-proximity likelihood,
        // so a fat-finger substitution of an adjacent key beats a merely more frequent but far-away word,
        // instead of ranking purely by frequency.
        return known.sortedByDescending { channelScore(folded, it, freq[it] ?: 0, sqDistance, contextScore) }
            .take(maxCount)
    }

    /**
     * The corrections the strip offers when both readers have spoken: the beam's best, then the string
     * reader's best that is not already there, then whatever is left of either.
     *
     * The beam keeps the lead because the silent swap is decided from *its* top candidate's tap cost — the
     * word at index 0 has to be the word that evidence is about. The string reader is guaranteed one slot
     * rather than only the ones the beam left empty, because the beam rarely leaves any: on a
     * transposition it fills all three with wrong words half the time. Measured in `ReaderMergeEvalTest`
     * over 2,425 slips, the intended word is offered 94.1 % of the time against 85.7 % before, and 89.9 %
     * had the string reader only filled empty slots. The price is the beam's third offer: 16 of those
     * slips had their intended word there and lose it, against 219 that gain one.
     */
    fun merge(touch: List<String>, text: List<String>, maxCount: Int): List<String> {
        val out = LinkedHashSet<String>(maxCount * 2)
        for (w in touch) if (out.size < (maxCount - 1).coerceAtLeast(1)) out.add(w)
        text.firstOrNull { it !in out }?.let { if (out.size < maxCount) out.add(it) }
        for (w in touch) if (out.size < maxCount) out.add(w)
        for (w in text) if (out.size < maxCount) out.add(w)
        return out.toList()
    }

    /**
     * Noisy-channel score for ranking a correction candidate: log unigram prior + log likelihood that
     * [typed] is a mis-tap of [cand] given the keyboard geometry (Tier 1) + a context bonus for how often
     * [cand] follows the previous word (Tier 2 bigram). Higher is better.
     */
    private fun channelScore(
        typed: String,
        cand: String,
        freq: Int,
        sqDistance: (Char, Char) -> Float?,
        contextScore: (String) -> Double,
    ): Double = ln((freq + 1).toDouble()) + spatialLogLikelihood(typed, cand, sqDistance) + contextScore(cand)

    /**
     * log P(typed | cand): near-key substitutions cost little, far ones a lot (Gaussian over key distance);
     * an adjacent transposition (finger-order slip) is a flat cost independent of distance; insert/delete
     * candidates get a flat penalty so the frequency prior orders them. Neutral when key geometry is
     * unavailable (layout not captured yet), which reduces this to frequency-only ranking.
     */
    private fun spatialLogLikelihood(typed: String, cand: String, sqDistance: (Char, Char) -> Float?): Double {
        if (typed.length != cand.length) return LENGTH_DIFF_PENALTY
        if (isAdjacentTransposition(typed, cand)) return TRANSPOSE_PENALTY
        var cost = 0.0
        for (i in typed.indices) {
            if (typed[i] == cand[i]) continue
            val d2 = sqDistance(typed[i], cand[i])?.toDouble() ?: NEUTRAL_SUB_SQDIST
            cost += d2 / (2.0 * PROX_SIGMA2)
        }
        return -cost
    }

    /** True if [b] is [a] with exactly one pair of adjacent characters swapped (a transposition). */
    private fun isAdjacentTransposition(a: String, b: String): Boolean {
        if (a.length != b.length || a.length < 2) return false
        var i = 0
        while (i < a.length && a[i] == b[i]) i++
        if (i >= a.length - 1) return false
        if (a[i] != b[i + 1] || a[i + 1] != b[i]) return false
        for (j in i + 2 until a.length) if (a[j] != b[j]) return false
        return true
    }
}
