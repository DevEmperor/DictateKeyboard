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

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Makes Dictate a system-wide speech recognizer via the standard Android [RecognitionService] API
 * (issue #67) — the same mechanism FUTO Voice Input uses. Once selected as the device's recognition
 * service, any app/keyboard that speaks the standard voice-input protocol can dictate through Dictate's
 * engine (local sherpa/Whisper or a cloud provider) and insert the result itself.
 *
 * All the actual work (recording, transcription, endpointing) lives in the shared [RecognitionSession];
 * this class only bridges its [RecognitionSession.Host] callbacks to the caller's [Callback].
 *
 * Note: some keyboards (notably Gboard) hardwire their mic to Google and won't use a third-party
 * recognizer — this works with apps/keyboards that honour the system default recognition service. The
 * `ACTION_RECOGNIZE_SPEECH` popup path is handled separately by [RecognitionActivity].
 */
class DictateRecognitionService : RecognitionService() {

    private var session: RecognitionSession? = null

    /**
     * Android 15 binds a third-party recognition service with nothing but BIND_AUTO_CREATE (AOSP "Restrict
     * RecognitionService process capabilities"; Android 14 still passed the caller's capabilities on). Unless
     * something else keeps Dictate's process in the foreground — the floating button's accessibility service
     * does — it may not use the microphone, and the framework's own RECORD_AUDIO check after this method
     * answers the caller with ERROR_INSUFFICIENT_PERMISSIONS (#489, reproduced on an Android 15 emulator).
     *
     * Deliberately not done: making the caller's attribution context (`createContext` with
     * `setNextAttributionSource`) and recording under it. The framework then skips its check, but the audio
     * policy still silences the capture by Dictate's own process state, so the caller got a silent recording
     * and "no speech" instead of an honest permission error (measured on the same emulator).
     */
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        session?.cancel()
        session = RecognitionSession(applicationContext, hostFor(listener)).also { it.start() }
    }

    override fun onStopListening(listener: Callback) {
        session?.stop()
    }

    override fun onCancel(listener: Callback) {
        session?.cancel()
        session = null
    }

    override fun onDestroy() {
        session?.cancel()
        session = null
        super.onDestroy()
    }

    private fun hostFor(listener: Callback) = object : RecognitionSession.Host {
        override fun onReadyForSpeech() {
            runCatching { listener.readyForSpeech(Bundle()) }
        }

        override fun onBeginningOfSpeech() {
            runCatching { listener.beginningOfSpeech() }
        }

        override fun onEndOfSpeech() {
            runCatching { listener.endOfSpeech() }
        }

        override fun onRmsChanged(rmsdB: Float) {
            runCatching { listener.rmsChanged(rmsdB) }
        }

        override fun onPartial(text: String) {
            runCatching { listener.partialResults(resultsBundle(text)) }
        }

        override fun onResults(text: String) {
            runCatching { listener.results(resultsBundle(text)) }
            session = null
        }

        override fun onError(code: Int) {
            runCatching { listener.error(code) }
            session = null
        }
    }

    private companion object {
        private fun resultsBundle(text: String): Bundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        }
    }
}
