/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.florisboard.lib.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How a haptic tick's shape is derived from the settings (issue #325).
 *
 * The bug this pins down is silent by nature: `factor` used to scale the duration *and* the amplitude,
 * so the 0.05 channels — key repeat and the cursor sliding along the spacebar, both on by default —
 * came out as a 1 ms pulse at amplitude 1. No error, no log, just a setting that did nothing. Every
 * assertion below is about a tick staying inside what hardware can render.
 */
class VibratorTest {

    /** The stock configuration: 10 ms at 5 % strength. */
    private val duration = 10
    private val strength = 5

    /** The three weights the input feedback controller actually passes. */
    private val keyPress = 1.0
    private val longPress = 0.4
    private val streamed = 0.05

    @Test
    fun `a normal key press keeps its configured length`() {
        val (ms, _) = hapticShape(duration, strength, keyPress, hasAmplitudeControl = true)
        assertEquals(10L, ms)
    }

    /**
     * The regression. With both scalings applied this was 1 ms at amplitude 1 — a vibration no actuator
     * can produce, for the two channels that ship switched on.
     */
    @Test
    fun `the streamed channels stay above what hardware can render`() {
        val (ms, amplitude) = hapticShape(duration, strength, streamed, hasAmplitudeControl = true)
        assertTrue(ms >= MIN_PERCEPTIBLE_DURATION_MS, "duration was $ms ms")
        assertTrue(amplitude >= MIN_PERCEPTIBLE_AMPLITUDE, "amplitude was $amplitude")
    }

    @Test
    fun `a lighter tick is never stronger than a heavier one`() {
        val (_, key) = hapticShape(duration, 100, keyPress, hasAmplitudeControl = true)
        val (_, long) = hapticShape(duration, 100, longPress, hasAmplitudeControl = true)
        val (_, stream) = hapticShape(duration, 100, streamed, hasAmplitudeControl = true)
        assertTrue(key > long, "key press $key should outweigh long press $long")
        assertTrue(long > stream, "long press $long should outweigh the streamed tick $stream")
    }

    /** Amplitude, not duration, is what carries "lighter" on hardware that can vary it. */
    @Test
    fun `factor scales the amplitude and leaves the length alone`() {
        val (fullMs, full) = hapticShape(duration, 100, keyPress, hasAmplitudeControl = true)
        val (halfMs, half) = hapticShape(duration, 100, 0.5, hasAmplitudeControl = true)
        assertEquals(fullMs, halfMs)
        assertEquals(255, full)
        assertEquals(128, half)
    }

    /**
     * Without amplitude control the system fixes the strength, so length is the only lever left and
     * `factor` has to move that instead — otherwise every channel would feel identical.
     */
    @Test
    fun `without amplitude control the length carries the weight`() {
        val (fullMs, _) = hapticShape(100, strength, keyPress, hasAmplitudeControl = false)
        val (lightMs, _) = hapticShape(100, strength, longPress, hasAmplitudeControl = false)
        assertEquals(100L, fullMs)
        assertEquals(40L, lightMs)
    }

    @Test
    fun `without amplitude control a streamed tick is still long enough to feel`() {
        val (ms, _) = hapticShape(duration, strength, streamed, hasAmplitudeControl = false)
        assertTrue(ms >= MIN_PERCEPTIBLE_DURATION_MS, "duration was $ms ms")
    }

    /** The slider tops out at 100 %; nothing may push the effect past what the API accepts. */
    @Test
    fun `the strongest possible tick stays within range`() {
        val (_, amplitude) = hapticShape(100, 100, keyPress, hasAmplitudeControl = true)
        assertEquals(255, amplitude)
    }
}
