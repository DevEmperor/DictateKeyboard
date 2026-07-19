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

/** Pipecat's VAD -> one Smart Turn analysis -> silence-timeout policy, expressed in VAD windows. */
internal class SmartTurnPausePolicy(
    private val analyzeAfterWindows: Int,
    private val fallbackAfterWindows: Int,
) {
    init {
        require(analyzeAfterWindows > 0)
        require(fallbackAfterWindows >= analyzeAfterWindows)
    }

    private var hadSpeech = false
    private var speechDetected = false
    private var silentWindows = 0
    private var epoch = 0L
    private var analyzedEpoch = -1L
    private var pendingEpoch: Long? = null

    fun onVadWindow(speech: Boolean): Action {
        if (speech) {
            val startsTurn = !hadSpeech
            if (!speechDetected) {
                epoch++
                analyzedEpoch = -1L
            }
            speechDetected = true
            hadSpeech = true
            silentWindows = 0
            return if (startsTurn) Action.BeginTurn else Action.None
        }
        speechDetected = false
        if (!hadSpeech) return Action.None
        silentWindows++
        return nextAction()
    }

    fun onPrediction(requestEpoch: Long, complete: Boolean?): Action {
        if (pendingEpoch == requestEpoch) pendingEpoch = null
        if (requestEpoch == epoch) {
            analyzedEpoch = epoch
            if (complete == true && hadSpeech && !speechDetected) return cut()
        }
        // A stale result can arrive after speech resumed and paused again. In that case, request a fresh
        // analysis immediately instead of waiting for another VAD window.
        return nextAction()
    }

    fun reset() {
        hadSpeech = false
        speechDetected = false
        silentWindows = 0
        analyzedEpoch = -1L
        epoch++
    }

    private fun nextAction(): Action {
        if (silentWindows >= fallbackAfterWindows) return cut()
        if (
            hadSpeech && !speechDetected && silentWindows >= analyzeAfterWindows &&
            pendingEpoch == null && analyzedEpoch != epoch
        ) {
            pendingEpoch = epoch
            return Action.Analyze(epoch)
        }
        return Action.None
    }

    private fun cut(): Action {
        reset()
        return Action.Cut
    }

    sealed interface Action {
        data object None : Action
        data object BeginTurn : Action
        data class Analyze(val epoch: Long) : Action
        data object Cut : Action
    }
}
