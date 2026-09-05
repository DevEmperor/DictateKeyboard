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

/**
 * One-edit neighbourhoods over the dictionary — Norvig's `edits1`, in one place.
 *
 * Two very different questions read it. The corrector asks *what should this have been?* and ranks what
 * it finds ([LatinLanguageProvider.correctionsFor]). The word learner asks the opposite, *is this one
 * slip away from something ordinary?*, and treats a yes as a reason to stay out of the way (issue #318).
 *
 * They must agree on what "one edit" means, or the keyboard ends up correcting a word it has just
 * decided to remember. Hence one implementation rather than two — the same reason [TouchScoring] and
 * [AutoCommitGate] are their own objects.
 */
internal object EditDistance {

    /** All strings one edit away from [word] (delete / transpose / replace / insert). */
    fun edits1(word: String, alphabet: Set<Char>): Set<String> {
        val result = HashSet<String>()
        for (i in 0..word.length) {
            val a = word.substring(0, i)
            val b = word.substring(i)
            if (b.isNotEmpty()) {
                result.add(a + b.substring(1))                                    // delete
                if (b.length > 1) result.add(a + b[1] + b[0] + b.substring(2))    // transpose
                for (c in alphabet) result.add(a + c + b.substring(1))            // replace
            }
            for (c in alphabet) result.add(a + c + b)                             // insert
        }
        return result
    }

    /**
     * The frequency of the most common dictionary word exactly one edit from [word], or 0 when there is
     * none. [word] itself is ignored, so a word already in the dictionary does not answer about itself.
     *
     * This is the evidence the tap coordinates cannot supply. The beam decodes *where the fingers were*,
     * so it sees a finger that landed one key over — but a transposition is spatially perfect (both keys
     * were hit dead-centre, in the wrong order), and a dropped or doubled letter has no tap to be wrong
     * about at all. Measured on the shipping English dictionary, the tap evidence alone lets through
     * roughly half of all transposed words; this catches them.
     */
    fun nearestKnownFrequency(word: String, alphabet: Set<Char>, freq: Map<String, Int>): Int {
        var best = 0
        for (edit in edits1(word, alphabet)) {
            if (edit == word) continue
            val f = freq[edit] ?: continue
            if (f > best) best = f
        }
        return best
    }
}
