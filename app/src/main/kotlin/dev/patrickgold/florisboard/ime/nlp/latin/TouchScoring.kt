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
 * How a touch-decoded candidate is scored against the others (issue #242).
 *
 * A noisy-channel score in nats: how likely the word is at all, minus how much tap evidence had to be
 * explained away to read it, plus what the previous word suggests.
 *
 * Sits in its own object because [LatinLanguageProvider] and `AutocorrectEvalTest` must agree on it to
 * the last constant. The measurements behind #242 were made by a throwaway Python reimplementation, and
 * a reimplementation is a copy that drifts — the numbers could not be reproduced afterwards, and the one
 * population that mattered had been left out of them without anyone being able to see it.
 */
internal object TouchScoring {

    /**
     * The dictionary's 128..255 range expressed in nats.
     *
     * Those values are already *linear in log frequency* (`tools/glide-dict/generate.py`), so the prior
     * rescales rather than takes another logarithm — applying `ln()` again, as the legacy `channelScore`
     * does, compresses the entire vocabulary into 0.69 nats and makes the language model irrelevant.
     */
    const val LM_SPAN = 4.0

    /**
     * Touch variance in key-width²: how much an off-centre tap is allowed to cost. Tuned together with
     * [LM_SPAN]; accuracy moves by under a percentage point when either is doubled or halved.
     */
    const val TOUCH_SIGMA2 = 0.2

    /** Log-probability prior for a dictionary frequency on the stored 128..255 scale. */
    fun lmPrior(freq: Int): Double = (freq - 128).coerceAtLeast(0) / 127.0 * LM_SPAN

    /**
     * Full score for a candidate the beam produced: its prior, the tap evidence against it ([cost] is the
     * excess squared distance in key-width²), and the bigram [context] bonus.
     */
    fun score(freq: Int, cost: Float, context: Double): Double =
        lmPrior(freq) - cost / (2.0 * TOUCH_SIGMA2) + context
}
