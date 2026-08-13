/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate

import dev.patrickgold.florisboard.dictate.audio.AudioSpeedUp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The speed-up (issue #272) judged on the two things that decide whether it is worth uploading: it must
 * actually shorten the audio, and it must not touch the pitch while doing so.
 */
class AudioSpeedUpTest {

    private val sampleRate = 16_000

    @Test
    fun shortensAudioByTheChosenRate() {
        val input = speechLikeTone(seconds = 4f)
        for (rate in listOf(1.25f, 1.5f, 2.0f)) {
            val out = AudioSpeedUp.stretch(input, rate)
            val expected = input.size / rate
            // One window of slack: the tail is copied at natural speed so the last words keep their shape.
            assertTrue(
                abs(out.size - expected) < 1024,
                "rate=$rate expected≈${expected.toInt()} samples, got ${out.size}",
            )
        }
    }

    /**
     * The whole point of WSOLA over a resample: 200 Hz in, 200 Hz out. A resample at 1.5× would report
     * 300 Hz here — this test is what rules that shortcut out.
     */
    @Test
    fun keepsThePitch() {
        val input = tone(frequency = 200.0, seconds = 3f)
        val before = dominantFrequency(input)
        assertTrue(abs(before - 200.0) < 2.0, "test signal itself is off: $before Hz")
        for (rate in listOf(1.25f, 1.5f, 2.0f)) {
            val after = dominantFrequency(AudioSpeedUp.stretch(input, rate))
            assertTrue(abs(after - 200.0) < 6.0, "rate=$rate shifted the pitch to $after Hz")
        }
    }

    @Test
    fun rateOfOnePassesThrough() {
        val input = speechLikeTone(seconds = 2f)
        assertTrue(AudioSpeedUp.stretch(input, 1.0f) === input, "1.0x must not re-synthesise anything")
    }

    @Test
    fun tooShortToSpeedUpIsReturnedAsIs() {
        val input = FloatArray(64) { 0.5f }
        assertTrue(AudioSpeedUp.stretch(input, 1.5f) === input)
    }

    @Test
    fun silenceStaysSilent() {
        val out = AudioSpeedUp.stretch(FloatArray(sampleRate * 2), 1.5f)
        assertEquals(0f, out.maxOfOrNull { abs(it) } ?: 0f)
    }

    /**
     * Overlap-add goes wrong audibly: a badly chosen join shows up as a step in the waveform, which a
     * recogniser hears as a click. A smooth 200 Hz tone can move at most ~0.08 per sample, so anything
     * near a tenth is a discontinuity and not the signal.
     */
    @Test
    fun joinsWithoutSteps() {
        val out = AudioSpeedUp.stretch(tone(frequency = 200.0, seconds = 3f), 1.5f)
        var worst = 0f
        for (i in 1 until out.size) {
            val step = abs(out[i] - out[i - 1])
            if (step > worst) worst = step
        }
        assertTrue(worst < 0.1f, "largest sample-to-sample step was $worst")
    }

    /** Amplitude must survive the windowing: overlap-add that doesn't sum to one quietens the speech. */
    @Test
    fun keepsTheLevel() {
        val input = tone(frequency = 200.0, seconds = 3f)
        val out = AudioSpeedUp.stretch(input, 1.5f)
        val peak = out.maxOf { abs(it) }
        assertTrue(peak in 0.45f..0.55f, "peak drifted to $peak (input peak is 0.5)")
    }

    /** A plain sine, the signal every pitch claim in here is measured on. */
    private fun tone(frequency: Double, seconds: Float): FloatArray =
        FloatArray((sampleRate * seconds).toInt()) { i ->
            (0.5 * sin(2.0 * PI * frequency * i / sampleRate)).toFloat()
        }

    /** A voice-ish signal: a 140 Hz "pitch" with two harmonics, so the search has real periods to match. */
    private fun speechLikeTone(seconds: Float): FloatArray =
        FloatArray((sampleRate * seconds).toInt()) { i ->
            val t = i.toDouble() / sampleRate
            val v = 0.5 * sin(2.0 * PI * 140.0 * t) +
                0.25 * sin(2.0 * PI * 280.0 * t) +
                0.12 * sin(2.0 * PI * 560.0 * t)
            (v * 0.6).toFloat()
        }

    /**
     * Frequency from zero crossings, which is all a single clean sine needs — and it stays honest about
     * what it measures, unlike an FFT bin that could hide a shifted partial.
     */
    private fun dominantFrequency(samples: FloatArray): Double {
        var crossings = 0
        for (i in 1 until samples.size) {
            if (samples[i - 1] <= 0f && samples[i] > 0f) crossings++
        }
        val seconds = samples.size.toDouble() / sampleRate
        return crossings / seconds
    }
}
