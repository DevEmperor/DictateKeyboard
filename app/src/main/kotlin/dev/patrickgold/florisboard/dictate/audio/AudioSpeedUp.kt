/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.audio

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Plays a recording faster before it is uploaded, without raising its pitch (issue #272).
 *
 * Nearly every speech-to-text API bills by audio duration, so a dictation sent at 1.5× is billed as two
 * thirds of what was spoken. Two features already work that side of the problem and both remove *dead
 * time* — the silence gate (#93) and the pause trimmer (#232); this shortens the speech itself.
 *
 * **Why not simply resample.** Rewriting the WAV header (or resampling and claiming the original rate)
 * is two lines and plays faster — but it raises the pitch with it, and every Whisper-family model was
 * trained on speech at natural pitch. That is the input they are worst at, which would trade money for
 * exactly the thing the user came for.
 *
 * So this is WSOLA: cut the waveform into overlapping windows, slide each one by up to [SEARCH] samples
 * to where it continues the previous window most smoothly, and overlap-add them closer together than
 * they were taken. Periods repeat less often; their length — the pitch — is untouched. The search is
 * what separates it from plain overlap-add, whose fixed cut points land mid-period and buzz.
 *
 * Beginning and end are copied verbatim rather than left under a half-risen window, so nothing fades in,
 * nothing fades out, and the final consonant of a dictation survives.
 */
object AudioSpeedUp {

    /** 1.0× (off) and the fastest offered; above 2× the words start running into each other. */
    const val MIN_PERCENT = 100
    const val MAX_PERCENT = 200

    /**
     * From here on the settings screen stops promising a free lunch and says what the speed can cost.
     *
     * Measured, not guessed (issue #272): across Groq's whisper-large-v3-turbo and the two on-device
     * Parakeet models, word error rates up to 1.5× are indistinguishable from the unsped recording, and
     * the first system to break — Parakeet's multilingual model on German — does so at 1.75×, where its
     * error rate goes from 13.8 % to 23.9 %. To be revisited if those numbers ever are.
     */
    const val CAUTION_PERCENT = 175

    /** One overlap-add window: 32 ms at 16 kHz — long enough to hold a pitch period of even a low voice. */
    private const val FRAME = 512

    /** Synthesis hop. Half the frame, so exactly two windows overlap everywhere and a Hann sums to 1. */
    private const val HOP = FRAME / 2

    /** How far a window may slide from its nominal position to find a smoother join (±8 ms). */
    private const val SEARCH = 128

    /** Samples compared when judging that join. */
    private const val CORR = 128

    /** Coarse search step; the winner is then refined by ±1. Halves the correlation work. */
    private const val COARSE_STEP = 2

    /** Shorter than this there is nothing to gain and no room for a single window. */
    private const val MIN_SAMPLES = FRAME * 4

    /** Below this much saved audio the re-encode is not worth it — same rule as the trimmer's MIN_TRIM_MS. */
    private const val MIN_SAVED_MS = 500

    private const val LOG_TAG = "DictateLatency"

    private val window: FloatArray by lazy {
        // Periodic Hann: w[i] + w[i + HOP] == 1, which is what makes the overlap-add sum to unity.
        FloatArray(FRAME) { i -> 0.5f * (1f - cos(2.0 * Math.PI * i / FRAME).toFloat()) }
    }

    /**
     * Writes a [rate]× faster copy of [input] to [output] and returns it, or null when there is nothing
     * worth sending: the rate is 1.0×, the audio is too short, it cannot be decoded, or the whole exercise
     * would save less than [MIN_SAVED_MS]. A null answer means "upload what you already have".
     *
     * Output is 16 kHz mono PCM16 WAV — the format the recorder itself produces.
     */
    suspend fun process(input: File, output: File, rate: Float): File? = withContext(Dispatchers.Default) {
        if (rate <= 1f) return@withContext null
        val startedNanos = System.nanoTime()
        val samples = runCatching { AudioDecode.decodeToMono16k(input) }.getOrNull() ?: return@withContext null
        if (samples.size < MIN_SAMPLES) return@withContext null
        val sped = stretch(samples, rate)
        val rateHz = AudioDecode.TARGET_SAMPLE_RATE
        val savedMs = (samples.size - sped.size).toLong() * 1000L / rateHz
        if (savedMs < MIN_SAVED_MS) return@withContext null
        val written = runCatching { writeWav(sped, rateHz, output) }.getOrElse {
            runCatching { output.delete() }
            return@withContext null
        }
        Log.i(
            LOG_TAG,
            "speedUp rate=$rate inMs=${samples.size.toLong() * 1000L / rateHz} " +
                "outMs=${sped.size.toLong() * 1000L / rateHz} savedMs=$savedMs " +
                "tookMs=${(System.nanoTime() - startedNanos) / 1_000_000L}",
        )
        written
    }

    /**
     * The algorithm itself: [samples] played [rate]× faster at unchanged pitch. Pure arithmetic, no files
     * and no Android — everything about the sound quality can be judged from a unit test.
     *
     * Returns [samples] untouched for a rate of 1.0× or audio too short to hold a window.
     */
    fun stretch(samples: FloatArray, rate: Float): FloatArray {
        if (rate <= 1f || samples.size < MIN_SAMPLES) return samples
        val w = window
        val lastStart = samples.size - FRAME
        // Never longer than the input, since we only ever speed up.
        val out = FloatArray(samples.size + FRAME)
        val analysisHop = HOP * rate.toDouble()
        var nominal = 0.0
        var outPos = 0
        var chosen = 0
        var first = true
        while (true) {
            val center = nominal.roundToInt()
            if (center > lastStart) break
            val start = if (first) center.coerceIn(0, lastStart) else bestMatch(samples, chosen + HOP, center, lastStart)
            for (i in 0 until FRAME) out[outPos + i] += samples[start + i] * w[i]
            chosen = start
            outPos += HOP
            nominal += analysisHop
            first = false
        }
        // The first window rises from zero and the last one falls to it. Both halves are completed with the
        // samples they were taken from, so the dictation neither fades in nor fades out: for the head that
        // is samples[i], for the tail it is the continuation of the last window — the same samples it would
        // have covered, at natural speed. Together at most ~70 ms, at the two places nobody is speaking.
        for (i in 0 until HOP) out[i] += samples[i] * (1f - w[i])
        val tailStart = chosen + HOP
        val rest = (samples.size - tailStart).coerceAtMost(out.size - outPos)
        for (i in 0 until rest) {
            // Under the last window's falling half only the missing share is added (w[i] == 1 - w[HOP + i]);
            // past it the output is still empty and the samples go in as they are.
            out[outPos + i] += if (i < HOP) samples[tailStart + i] * w[i] else samples[tailStart + i]
        }
        return out.copyOf(outPos + rest)
    }

    /**
     * Picks where the next window is read from: near [center], but shifted by up to [SEARCH] samples to
     * wherever it continues [templateStart] — the samples that would have followed the previous window at
     * natural speed — most closely. That similarity is what keeps the periods aligned across the join.
     *
     * Correlation is normalised by the candidate's own energy, so a loud stretch of audio cannot win the
     * comparison merely by being loud.
     */
    private fun bestMatch(x: FloatArray, templateStart: Int, center: Int, lastStart: Int): Int {
        val lo = (center - SEARCH).coerceAtLeast(0)
        val hi = (center + SEARCH).coerceAtMost(lastStart)
        if (hi <= lo || templateStart + CORR > x.size) return center.coerceIn(0, lastStart)
        var bestPos = center.coerceIn(lo, hi)
        var bestScore = -Float.MAX_VALUE
        var cand = lo
        while (cand <= hi) {
            val score = similarity(x, templateStart, cand)
            if (score > bestScore) {
                bestScore = score
                bestPos = cand
            }
            cand += COARSE_STEP
        }
        // The coarse step can only ever be one sample off the local optimum; check both neighbours.
        for (neighbour in intArrayOf(bestPos - 1, bestPos + 1)) {
            if (neighbour < lo || neighbour > hi) continue
            val score = similarity(x, templateStart, neighbour)
            if (score > bestScore) {
                bestScore = score
                bestPos = neighbour
            }
        }
        return bestPos
    }

    private fun similarity(x: FloatArray, templateStart: Int, candidate: Int): Float {
        var dot = 0f
        var energy = 0f
        for (i in 0 until CORR) {
            val v = x[candidate + i]
            dot += v * x[templateStart + i]
            energy += v * v
        }
        return dot / sqrt(energy + 1e-9f)
    }

    /** Writes [samples] as 16 kHz mono PCM16 WAV. */
    private fun writeWav(samples: FloatArray, sampleRate: Int, output: File): File {
        check(AudioWav.write(samples, sampleRate, output)) { "could not write ${output.name}" }
        return output
    }
}
