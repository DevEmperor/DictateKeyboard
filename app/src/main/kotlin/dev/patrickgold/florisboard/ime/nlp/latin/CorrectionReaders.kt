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

    /** How many of a prefix reading's most frequent words are considered as completions of it. */
    private const val COMPLETIONS_PER_READING = 2

    /**
     * Longer words the taps may be the *beginning* of, as folded keys, best first (issue #381).
     *
     * Everything else here reads the taps as a whole word, and the completion walk in `suggest()` reads
     * the typed string as a prefix exactly as it stands. So a slip inside a half-typed word — `dixt` on
     * the way to `dictionary` — reached neither: the walk has nothing starting with `dixt`, and no word
     * of four letters is `dictionary`. The beam already holds the answer, though: `dict` survives its last
     * tap as a range of dictionary entries. This offers the most frequent words of the cheapest such
     * ranges, scored like a beam candidate (prior − tap cost + context).
     *
     * The reading that is exactly what was typed is skipped: its words are the walk's ordinary
     * completions, and offering them twice would only crowd the strip.
     */
    fun completionsByTouch(
        points: FloatArray,
        typed: String,
        folded: String,
        prefixIndex: TouchBeamDecoder.PrefixIndex,
        layout: KeyProximityInfo.Layout,
        maxCount: Int,
        contextScore: (cand: String) -> Double,
    ): List<String> {
        val words = prefixIndex.words
        val freqs = prefixIndex.freqs
        if (freqs.size != words.size) return emptyList()
        val taps = points.size / 2
        val scored = HashMap<String, Double>()
        val bestAt = IntArray(COMPLETIONS_PER_READING)
        for (reading in TouchBeamDecoder.decodePrefixes(points, typed, prefixIndex, layout)) {
            if (folded.length == taps && words[reading.lo].startsWith(folded)) continue
            mostFrequent(prefixIndex, reading.lo, reading.hi, longerThan = taps, into = bestAt)
            for (i in bestAt) {
                if (i < 0) continue
                val word = words[i]
                val score = TouchScoring.score(freqs[i], reading.cost, contextScore(word))
                if (score > (scored[word] ?: Double.NEGATIVE_INFINITY)) scored[word] = score
            }
        }
        return scored.entries.sortedByDescending { it.value }.take(maxCount).map { it.key }
    }

    /**
     * The same question asked of the typed string instead of the taps: longer words that begin with
     * something one edit away from [folded], as folded keys, best first (issue #381).
     *
     * It covers what the beam cannot see, for the same reason [byEditDistance] does for whole words: a
     * transposition inside the prefix (both keys hit dead-centre, in the wrong order), a letter dropped or
     * typed twice (no tap to be wrong about), and a key nowhere near the right one. `conseqd` is
     * `conseq` with one tap too many, and `vecau` is `becau` one key over — neither is a prefix of anything
     * as typed.
     *
     * Each variant is ranked by the frequency of the word it leads to and by how plausible the edit is,
     * on the same terms [byEditDistance] uses for whole words.
     */
    fun completionsByString(
        folded: String,
        prefixIndex: TouchBeamDecoder.PrefixIndex,
        alphabet: Set<Char>,
        maxCount: Int,
        sqDistance: (Char, Char) -> Float?,
        contextScore: (cand: String) -> Double,
    ): List<String> {
        val words = prefixIndex.words
        val freqs = prefixIndex.freqs
        if (freqs.size != words.size || folded.length < MIN_COMPLETION_PREFIX) return emptyList()
        val scored = HashMap<String, Double>()
        val bestAt = IntArray(COMPLETIONS_PER_READING)
        for (variant in EditDistance.edits1(folded, alphabet)) {
            // The typed string itself is the walk's business; anything shorter than a real prefix would
            // complete to half the dictionary.
            if (variant == folded || variant.length < MIN_COMPLETION_PREFIX) continue
            val range = prefixIndex.rangeOf(variant)
            if (range < 0) continue
            mostFrequent(
                prefixIndex, (range ushr 32).toInt(), (range and 0xFFFFFFFFL).toInt(),
                longerThan = maxOf(variant.length, folded.length), into = bestAt,
            )
            val edit = spatialLogLikelihood(folded, variant, sqDistance)
            for (i in bestAt) {
                if (i < 0) continue
                val word = words[i]
                val score = TouchScoring.lmPrior(freqs[i]) + edit + contextScore(word)
                if (score > (scored[word] ?: Double.NEGATIVE_INFINITY)) scored[word] = score
            }
        }
        return scored.entries.sortedByDescending { it.value }.take(maxCount).map { it.key }
    }

    /** Shortest prefix a completion is offered for; below it a prefix is too short to say anything. */
    private const val MIN_COMPLETION_PREFIX = 3

    /**
     * Both readers' completions as one list: alternating, the string reader's first. It sees the slips the
     * beam is blind to (a transposition, a dropped or doubled letter, a far key), and leading with it put
     * the intended word into the first three offers 26.0 % of the time against 20.5 % the other way round
     * (`PrefixSlipEvalTest`).
     */
    fun completions(byTouch: List<String>, byString: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (i in 0 until maxOf(byTouch.size, byString.size)) {
            byString.getOrNull(i)?.let { out.add(it) }
            byTouch.getOrNull(i)?.let { out.add(it) }
        }
        return out.toList()
    }

    /**
     * The fixes with the completions worked in (issue #381): the best completion takes the second slot
     * when it is a commoner word than the fix it would displace, and otherwise follows the fixes like the
     * rest. The first fix never moves, since that is the word the silent swap is decided about.
     *
     * The frequency test is what makes a completion safe to promote. Unconditionally in second place, the
     * intended word of a slip inside a half-typed word is among the first three offers 26.0 % of the time
     * — against 0.4 % before and 15.1 % with the completions merely appended — but a finished word with a
     * slip loses its right fix from the first three 4.1 % of the time. With the test it is 22.1 % against
     * 1.9 %; `PrefixSlipEvalTest` has the whole table.
     */
    fun withCompletions(fixes: List<String>, completions: List<String>, freqOf: (String) -> Int): List<String> {
        val best = completions.firstOrNull { it !in fixes } ?: return fixes
        val displaced = fixes.getOrNull(1)
        val promoted = displaced == null || freqOf(best) > freqOf(displaced)
        val out = LinkedHashSet<String>()
        if (promoted) {
            out.addAll(fixes.take(1))
            out.add(best)
        }
        out.addAll(fixes)
        out.addAll(completions)
        return out.toList()
    }

    /**
     * Fills [into] with the positions of the most frequent entries of `[lo, hi)` longer than [longerThan]
     * characters, most frequent first, -1 where there are fewer.
     */
    private fun mostFrequent(index: TouchBeamDecoder.PrefixIndex, lo: Int, hi: Int, longerThan: Int, into: IntArray) {
        val words = index.words
        val freqs = index.freqs
        into.fill(-1)
        for (i in lo until hi) {
            if (words[i].length <= longerThan) continue
            var slot = -1
            for (k in into.indices) {
                if (into[k] < 0 || freqs[i] > freqs[into[k]]) {
                    slot = k
                    break
                }
            }
            if (slot < 0) continue
            for (k in into.size - 1 downTo slot + 1) into[k] = into[k - 1]
            into[slot] = i
        }
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
        prefixIndex: TouchBeamDecoder.PrefixIndex?,
        sqDistance: (Char, Char) -> Float?,
        contextScore: (cand: String) -> Double,
        allowDistance2: Boolean,
    ): StringReading {
        val near = byEditDistance(folded, freq, alphabet, prefixIndex, MAX_CORRECTIONS, false, sqDistance, contextScore)
        if (near.isNotEmpty() || !allowDistance2 || folded.length > MAX_DISTANCE2_LEN) {
            return StringReading(near, near.isEmpty())
        }
        return StringReading(
            words = byEditDistance(folded, freq, alphabet, prefixIndex, MAX_CORRECTIONS, true, sqDistance, contextScore),
            distance1Empty = true,
        )
    }

    /**
     * Dictionary words closest to (a misspelling of) [folded], as folded keys, best first. Distance 2 only
     * when [allowDistance2] and distance 1 found nothing, and only with a [prefixIndex] to search — its
     * words are [freq]'s keys, so without one there is nothing to find anyway.
     *
     * [sqDistance] is the squared distance between two keys in key-width², or null when the geometry does
     * not know one of them — which reduces the ranking to frequency alone.
     */
    fun byEditDistance(
        folded: String,
        freq: Map<String, Int>,
        alphabet: Set<Char>,
        prefixIndex: TouchBeamDecoder.PrefixIndex?,
        maxCount: Int,
        allowDistance2: Boolean,
        sqDistance: (Char, Char) -> Float?,
        contextScore: (cand: String) -> Double = { 0.0 },
    ): List<String> {
        var known: Set<String> = EditDistance.edits1(folded, alphabet).filterTo(HashSet()) { freq.containsKey(it) }
        if (known.isEmpty() && allowDistance2 && prefixIndex != null) {
            known = distance2(folded, alphabet, prefixIndex)
        }
        // Noisy-channel ranking (Tier 1): combine the unigram prior with a keyboard-proximity likelihood,
        // so a fat-finger substitution of an adjacent key beats a merely more frequent but far-away word,
        // instead of ranking purely by frequency. Ties go alphabetically, so the order never depends on
        // how the candidates happened to be generated.
        val score = known.associateWith { channelScore(folded, it, freq[it] ?: 0, sqDistance, contextScore) }
        return known.sortedWith(compareByDescending<String> { score.getValue(it) }.thenBy { it }).take(maxCount)
    }

    /**
     * Every word of [index] two edits from [folded] — the same words to the last one as building every
     * edit of every edit ([EditDistance.edits1] twice) and looking each up (issue #381).
     *
     * That was the old way, and it built ~(54·n)² strings per keystroke: 40 ms for a three-letter typo and
     * 281 ms for a nine-letter one on a Galaxy A55 release build, on every key press of a word neither the
     * beam nor distance 1 could read. Here one depth-first walk goes through the sorted index with a budget
     * of two edits: a shared start is walked once, a branch ends the moment its prefix is no longer the
     * start of any word, and replacements and insertions only try characters that actually follow there.
     *
     * "Every edit of every edit" is the Damerau–Levenshtein distance with the edits applied one after the
     * other, and that allows one thing a left-to-right alignment does not see: two letters swapped with a
     * third inserted between them (`ab` → `bca`) or dropped from between them (`acb` → `ba`). Within a
     * budget of two those are the only such cases (Lowrance and Wagner's generalised transposition), so
     * they are the two extra moves below. `Distance2EquivalenceTest` checks the result against the old way,
     * word for word.
     */
    fun distance2(folded: String, alphabet: Set<Char>, index: TouchBeamDecoder.PrefixIndex): Set<String> {
        val out = HashSet<String>()
        if (index.words.isNotEmpty()) Distance2Walk(folded, alphabet, index, out).walk(0, 0, index.words.size, 0, 2)
        return out
    }

    /** The walk behind [distance2]: [typed] is read at `pos`, the index is narrowed to `[lo, hi)` at `depth`. */
    private class Distance2Walk(
        val typed: String,
        val alphabet: Set<Char>,
        val index: TouchBeamDecoder.PrefixIndex,
        val out: MutableSet<String>,
    ) {
        fun walk(pos: Int, lo: Int, hi: Int, depth: Int, budget: Int) {
            val n = typed.length
            // Everything typed has been read: the entry exactly this long, if there is one, is a word.
            if (pos == n) index.exactWord((lo.toLong() shl 32) or (hi.toLong() and 0xFFFFFFFFL), depth)?.let { out.add(it) }
            // The typed character as it is.
            if (pos < n) index.narrow(lo, hi, depth, typed[pos]).let { if (it >= 0) walk(pos + 1, lo(it), hi(it), depth + 1, budget) }
            if (budget == 0) return
            // It was typed by mistake.
            if (pos < n) walk(pos + 1, lo, hi, depth, budget - 1)
            // It and the next one came out the wrong way round.
            if (pos + 1 < n) {
                val first = index.narrow(lo, hi, depth, typed[pos + 1])
                if (first >= 0) {
                    val second = index.narrow(lo(first), hi(first), depth + 1, typed[pos])
                    if (second >= 0) walk(pos + 2, lo(second), hi(second), depth + 2, budget - 1)
                }
            }
            index.forEachChild(lo, hi, depth) { ch, childLo, childHi ->
                if (ch in alphabet) {
                    // It should have been ch.
                    if (pos < n && ch != typed[pos]) walk(pos + 1, childLo, childHi, depth + 1, budget - 1)
                    // ch was left out here.
                    walk(pos, childLo, childHi, depth + 1, budget - 1)
                }
            }
            if (budget < 2) return
            // Swapped, with a letter left out between them: typed `ab`, meant `b?a`.
            if (pos + 1 < n) {
                val first = index.narrow(lo, hi, depth, typed[pos + 1])
                if (first >= 0) {
                    index.forEachChild(lo(first), hi(first), depth + 1) { ch, childLo, childHi ->
                        if (ch in alphabet) {
                            val third = index.narrow(childLo, childHi, depth + 2, typed[pos])
                            if (third >= 0) walk(pos + 2, lo(third), hi(third), depth + 3, 0)
                        }
                    }
                }
            }
            // Swapped, with a stray letter typed between them: typed `a?b`, meant `ba`.
            if (pos + 2 < n) {
                val first = index.narrow(lo, hi, depth, typed[pos + 2])
                if (first >= 0) {
                    val second = index.narrow(lo(first), hi(first), depth + 1, typed[pos])
                    if (second >= 0) walk(pos + 3, lo(second), hi(second), depth + 2, 0)
                }
            }
        }
    }

    private fun lo(packed: Long): Int = (packed ushr 32).toInt()

    private fun hi(packed: Long): Int = (packed and 0xFFFFFFFFL).toInt()

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
