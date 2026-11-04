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
import kotlin.test.assertIs

class SmartTurnPausePolicyTest {
    @Test
    fun firstSpeechWindowMarksTheBufferedTurnStartOnlyOnce() {
        val policy = SmartTurnPausePolicy(analyzeAfterWindows = 1, fallbackAfterWindows = 3)

        assertEquals(SmartTurnPausePolicy.Action.BeginTurn, policy.onVadWindow(speech = true))
        assertEquals(SmartTurnPausePolicy.Action.None, policy.onVadWindow(speech = true))
        assertIs<SmartTurnPausePolicy.Action.Analyze>(policy.onVadWindow(speech = false))
        policy.onVadWindow(speech = true)
        assertEquals(SmartTurnPausePolicy.Action.None, policy.onVadWindow(speech = true))
    }

    @Test
    fun silenceBeforeSpeechDoesNothing() {
        val policy = SmartTurnPausePolicy(analyzeAfterWindows = 1, fallbackAfterWindows = 3)

        assertEquals(SmartTurnPausePolicy.Action.None, policy.onVadWindow(speech = false))
    }

    @Test
    fun completePredictionCutsAtCandidatePause() {
        val policy = SmartTurnPausePolicy(analyzeAfterWindows = 2, fallbackAfterWindows = 4)
        policy.onVadWindow(speech = true)
        assertEquals(SmartTurnPausePolicy.Action.None, policy.onVadWindow(speech = false))
        val request = assertIs<SmartTurnPausePolicy.Action.Analyze>(policy.onVadWindow(speech = false))

        assertEquals(SmartTurnPausePolicy.Action.Cut(silentWindows = 2), policy.onPrediction(request.epoch, complete = true))
        assertEquals(SmartTurnPausePolicy.Action.None, policy.onVadWindow(speech = false))
    }

    // The VAD reports speech only after 0.25 s of it, so a "complete" verdict can arrive while the next word
    // is already being said and the windows still read silent. The cut then has to know how far back the
    // pause began, or it lands in that word ("Please... Please meet me", 2026-11-04).
    @Test
    fun aLateVerdictReportsHowLongAgoThePauseBegan() {
        val policy = SmartTurnPausePolicy(analyzeAfterWindows = 2, fallbackAfterWindows = 20)
        policy.onVadWindow(speech = true)
        policy.onVadWindow(speech = false)
        val request = assertIs<SmartTurnPausePolicy.Action.Analyze>(policy.onVadWindow(speech = false))
        // Inference takes a while; the speaker goes on, but the VAD has not caught up yet.
        repeat(5) { assertEquals(SmartTurnPausePolicy.Action.None, policy.onVadWindow(speech = false)) }

        assertEquals(SmartTurnPausePolicy.Action.Cut(silentWindows = 7), policy.onPrediction(request.epoch, complete = true))
    }

    @Test
    fun incompletePredictionWaitsForSilenceFallback() {
        val policy = SmartTurnPausePolicy(analyzeAfterWindows = 1, fallbackAfterWindows = 3)
        policy.onVadWindow(speech = true)
        val request = assertIs<SmartTurnPausePolicy.Action.Analyze>(policy.onVadWindow(speech = false))

        assertEquals(SmartTurnPausePolicy.Action.None, policy.onPrediction(request.epoch, complete = false))
        assertEquals(SmartTurnPausePolicy.Action.None, policy.onVadWindow(speech = false))
        assertEquals(SmartTurnPausePolicy.Action.Cut(silentWindows = 3), policy.onVadWindow(speech = false))
    }

    @Test
    fun resumedSpeechGetsASecondAnalysisAtItsNextPause() {
        val policy = SmartTurnPausePolicy(analyzeAfterWindows = 1, fallbackAfterWindows = 4)
        policy.onVadWindow(speech = true)
        val first = assertIs<SmartTurnPausePolicy.Action.Analyze>(policy.onVadWindow(speech = false))
        assertEquals(SmartTurnPausePolicy.Action.None, policy.onPrediction(first.epoch, complete = false))

        policy.onVadWindow(speech = true)
        val second = assertIs<SmartTurnPausePolicy.Action.Analyze>(policy.onVadWindow(speech = false))

        assertEquals(SmartTurnPausePolicy.Action.Cut(silentWindows = 1), policy.onPrediction(second.epoch, complete = true))
    }

    @Test
    fun stalePredictionAfterSpeechResumesCannotCutNewTurn() {
        val policy = SmartTurnPausePolicy(analyzeAfterWindows = 1, fallbackAfterWindows = 4)
        policy.onVadWindow(speech = true)
        val first = assertIs<SmartTurnPausePolicy.Action.Analyze>(policy.onVadWindow(speech = false))

        policy.onVadWindow(speech = true)
        assertEquals(SmartTurnPausePolicy.Action.None, policy.onVadWindow(speech = false))
        val fresh = assertIs<SmartTurnPausePolicy.Action.Analyze>(
            policy.onPrediction(first.epoch, complete = true),
        )

        assertEquals(SmartTurnPausePolicy.Action.Cut(silentWindows = 1), policy.onPrediction(fresh.epoch, complete = true))
    }
}
