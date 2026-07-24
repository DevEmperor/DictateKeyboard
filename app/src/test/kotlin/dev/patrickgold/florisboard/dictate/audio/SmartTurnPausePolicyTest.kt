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

        assertEquals(SmartTurnPausePolicy.Action.Cut, policy.onPrediction(request.epoch, complete = true))
        assertEquals(SmartTurnPausePolicy.Action.None, policy.onVadWindow(speech = false))
    }

    @Test
    fun incompletePredictionWaitsForSilenceFallback() {
        val policy = SmartTurnPausePolicy(analyzeAfterWindows = 1, fallbackAfterWindows = 3)
        policy.onVadWindow(speech = true)
        val request = assertIs<SmartTurnPausePolicy.Action.Analyze>(policy.onVadWindow(speech = false))

        assertEquals(SmartTurnPausePolicy.Action.None, policy.onPrediction(request.epoch, complete = false))
        assertEquals(SmartTurnPausePolicy.Action.None, policy.onVadWindow(speech = false))
        assertEquals(SmartTurnPausePolicy.Action.Cut, policy.onVadWindow(speech = false))
    }

    @Test
    fun resumedSpeechGetsASecondAnalysisAtItsNextPause() {
        val policy = SmartTurnPausePolicy(analyzeAfterWindows = 1, fallbackAfterWindows = 4)
        policy.onVadWindow(speech = true)
        val first = assertIs<SmartTurnPausePolicy.Action.Analyze>(policy.onVadWindow(speech = false))
        assertEquals(SmartTurnPausePolicy.Action.None, policy.onPrediction(first.epoch, complete = false))

        policy.onVadWindow(speech = true)
        val second = assertIs<SmartTurnPausePolicy.Action.Analyze>(policy.onVadWindow(speech = false))

        assertEquals(SmartTurnPausePolicy.Action.Cut, policy.onPrediction(second.epoch, complete = true))
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

        assertEquals(SmartTurnPausePolicy.Action.Cut, policy.onPrediction(fresh.epoch, complete = true))
    }
}
