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

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechGateTest {

    @Test
    fun rejectsPcm16QuantizationNoiseBelowPeakFloor() {
        assertFalse(SpeechGate.hasSignalAboveSilenceFloor(FloatArray(16_000)))
        assertFalse(SpeechGate.hasSignalAboveSilenceFloor(floatArrayOf(15f / 32768f, -15f / 32768f)))
    }

    @Test
    fun keepsAnySignalAtOrAbovePeakFloorForSileroClassification() {
        assertTrue(SpeechGate.hasSignalAboveSilenceFloor(floatArrayOf(0f, 16f / 32768f)))
        assertTrue(SpeechGate.hasSignalAboveSilenceFloor(floatArrayOf(0f, -17f / 32768f)))
    }
}
