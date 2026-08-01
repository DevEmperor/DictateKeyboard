/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.recognition

import android.content.Context
import android.speech.SpeechRecognizer
import dev.patrickgold.florisboard.dictate.DictateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One voice-input request (issue #67), shared by both entry points — the system [DictateRecognitionService]
 * and the [RecognitionActivity]. It drives the normal recording/transcription pipeline via
 * [DictateController] (which routes output through [RecognitionSink] → [RecognitionBridge]) and adds the
 * one thing those entry points need on top: **endpointing** — deciding when to stop recording.
 *
 * Recording stops on the first of:
 *  - the user going quiet for [END_SILENCE_MS] after speech was detected (amplitude-based, via
 *    [DictateController.audioLevel]),
 *  - the caller calling [stop] (e.g. a "done" button / `onStopListening`),
 *  - [NO_SPEECH_TIMEOUT_MS] with no speech at all (→ [SpeechRecognizer.ERROR_SPEECH_TIMEOUT]),
 *  - the [MAX_RECORDING_MS] hard cap.
 *
 * All host callbacks are invoked on the main thread. Terminal outcomes ([Host.onResults]/[Host.onError])
 * fire exactly once.
 */
class RecognitionSession(
    private val appContext: Context,
    private val host: Host,
) {
    /** Receives lifecycle + result callbacks; the service maps these to its `RecognitionService.Callback`,
     *  the activity updates its UI and returns an activity result. Only [onResults]/[onError] are required. */
    interface Host {
        fun onBeginningOfSpeech() {}
        fun onEndOfSpeech() {}
        fun onRmsChanged(rmsdB: Float) {}
        fun onPartial(text: String) {}
        fun onResults(text: String)
        fun onError(code: Int)
    }

    private val buffer = StringBuilder()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private var watchdog: Job? = null
    private var beginningSent = false
    private var stopping = false
    private var completed = false

    fun start() {
        RecognitionBridge.register(this)
        DictateController.startRecognition(appContext)
        watchdog = scope.launch {
            val startedMs = System.currentTimeMillis()
            var speechStarted = false
            var lastLoudMs = 0L
            // Poll the level rather than collecting it: audioLevel is a StateFlow, and once the user goes
            // quiet it settles on a constant value (exactly 0f) and stops emitting — so a collect-driven
            // check would freeze exactly when the silence timer needs to run.
            while (isActive && !completed && !stopping) {
                val level = DictateController.audioLevel.value
                val now = System.currentTimeMillis()
                host.onRmsChanged(level * 10f - 2f) // rough dB-ish for the caller's level meter
                if (level >= SPEECH_LEVEL) {
                    if (!speechStarted) {
                        speechStarted = true
                        markBeginning()
                    }
                    lastLoudMs = now
                }
                val elapsed = now - startedMs
                when {
                    speechStarted && now - lastLoudMs >= END_SILENCE_MS -> { stop(); return@launch }
                    !speechStarted && elapsed >= NO_SPEECH_TIMEOUT_MS -> {
                        failAndCancel(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                        return@launch
                    }
                    elapsed >= MAX_RECORDING_MS -> { stop(); return@launch }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Stop capturing and transcribe; the outcome arrives asynchronously via [onOutcome]. */
    fun stop() {
        if (stopping || completed) return
        stopping = true
        watchdog?.cancel()
        host.onEndOfSpeech()
        DictateController.stopRecognition(appContext)
    }

    /** Abandon the recording without transcribing (the caller cancelled / went away). */
    fun cancel() {
        if (completed) return
        completed = true
        watchdog?.cancel()
        scope.cancel()
        RecognitionBridge.unregister(this)
        DictateController.cancelRecognition()
    }

    // --- RecognitionBridge callbacks -------------------------------------------------------------

    internal fun onResultText(text: String) {
        buffer.append(text)
    }

    internal fun onPartialText(text: String) {
        if (completed) return
        markBeginning()
        host.onPartial(text)
    }

    internal fun onOutcome(outcome: String) {
        if (completed) return
        completed = true
        watchdog?.cancel()
        scope.cancel()
        RecognitionBridge.unregister(this)
        when (outcome) {
            "success" -> {
                val text = buffer.toString().trim()
                if (text.isNotEmpty()) host.onResults(text) else host.onError(SpeechRecognizer.ERROR_NO_MATCH)
            }
            "noSpeech", "promptEcho" -> host.onError(SpeechRecognizer.ERROR_NO_MATCH)
            "apiError" -> host.onError(SpeechRecognizer.ERROR_NETWORK)
            "cancelled" -> Unit // the caller aborted; nothing to deliver
            else -> host.onError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private fun markBeginning() {
        if (!beginningSent) {
            beginningSent = true
            host.onBeginningOfSpeech()
        }
    }

    private fun failAndCancel(code: Int) {
        if (completed) return
        completed = true
        watchdog?.cancel()
        scope.cancel()
        RecognitionBridge.unregister(this)
        DictateController.cancelRecognition()
        host.onError(code)
    }

    companion object {
        /** How often the endpointing watchdog samples the level (the controller updates it every 50 ms). */
        private const val POLL_INTERVAL_MS = 100L

        /** Audio level (0..1, from [DictateController.audioLevel]) above which we count "speech". */
        private const val SPEECH_LEVEL = 0.10f

        /** Auto-stop this long after the user stops speaking (endpointing). */
        private const val END_SILENCE_MS = 3_000L

        /** If nothing is ever said, give up after this long. */
        private const val NO_SPEECH_TIMEOUT_MS = 8_000L

        /** Hard cap on a single recognition recording (3 minutes). */
        private const val MAX_RECORDING_MS = 180_000L
    }
}
