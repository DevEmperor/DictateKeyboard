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
import kotlin.test.assertEquals

/**
 * Where a long-form auto-split cuts (issue #170): in the pause, not where the verdict arrived. A cut made
 * at the moment of the decision fell into the next word whenever the speaker went on before the VAD had
 * noticed, and that word came out twice ("Please... Please meet me") or not at all (a lost "I").
 */
class LongformCutTest {

    private val window = SpeechGate.VAD_WINDOW.toLong()

    @Test
    fun theCutGoesIntoTheQuarterSecondTheVadNeededToCallSilence() {
        // Five seconds in, the VAD turned silent ten windows ago; it does so 0.25 s after the speech stopped.
        val processed = 16_000L * 5
        val speechStopped = processed - 10 * window - 4_000
        val cut = LiveSpeechSplitter.cutPosition(processed, silentWindows = 10)

        assertEquals(speechStopped + 2_000, cut)
    }

    @Test
    fun aCutNeverGoesBeforeTheRecordingStarted() {
        assertEquals(0L, LiveSpeechSplitter.cutPosition(processed = 1_000, silentWindows = 10))
    }

    @Test
    fun aCutInThePastHandsWhatFollowsItToTheNextSegment() {
        // 3 s written; the pause was 0.6 s ago.
        assertEquals(19_200L, RecordingController.carryBytes(pcmBytes = 96_000, capturedSamples = 48_000, cutAtSample = 38_400))
    }

    @Test
    fun aCutNowOrInTheFutureCarriesNothing() {
        assertEquals(0L, RecordingController.carryBytes(pcmBytes = 96_000, capturedSamples = 48_000, cutAtSample = null))
        assertEquals(0L, RecordingController.carryBytes(pcmBytes = 96_000, capturedSamples = 48_000, cutAtSample = 50_000))
    }

    @Test
    fun theCarryIsNeverMoreThanTheSegmentHolds() {
        // A cut behind this segment's start (a manual cut came in between) would carry it all; rotate()
        // then leaves the file alone.
        assertEquals(96_000L, RecordingController.carryBytes(pcmBytes = 96_000, capturedSamples = 200_000, cutAtSample = 10))
        // A frame lost to a failed write leaves an odd byte count; the carry stays whole samples.
        assertEquals(95_000L, RecordingController.carryBytes(pcmBytes = 95_001, capturedSamples = 200_000, cutAtSample = 10))
    }
}
