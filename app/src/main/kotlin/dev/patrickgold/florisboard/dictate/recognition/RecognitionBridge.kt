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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single hand-off point between the transcription pipeline and whichever voice-input entry point is
 * currently active (issue #67): the system [DictateRecognitionService] (`RecognitionService` API) or the
 * [RecognitionActivity] (`ACTION_RECOGNIZE_SPEECH` popup). Only one recognition runs at a time, so exactly
 * one [RecognitionSession] is registered as the receiver.
 *
 * [RecognitionSink] forwards the committed transcript here, and [DictateController] forwards the terminal
 * outcome — both without knowing which surface (service vs activity) is receiving it.
 */
object RecognitionBridge {

    @Volatile
    private var receiver: RecognitionSession? = null

    private val _active = MutableStateFlow(false)

    /** True while a recognition is in progress (either voice-input surface) — reactive, so e.g. the
     *  floating button can hide itself while another keyboard drives a system voice-input session (#67). */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun register(session: RecognitionSession) {
        receiver = session
        _active.value = true
    }

    fun unregister(session: RecognitionSession) {
        if (receiver === session) {
            receiver = null
            _active.value = false
        }
    }

    /** True while a recognition is in progress (either voice-input surface). */
    fun isActive(): Boolean = receiver != null

    /** The transcript, as [RecognitionSink] commits it. */
    fun appendResult(text: String) {
        receiver?.onResultText(text)
    }

    /** Interim/streaming text (unused by the current batch flow, wired for a future streaming path). */
    fun deliverPartial(text: String) {
        receiver?.onPartialText(text)
    }

    /** The terminal outcome of the transcription (`success` / `noSpeech` / `promptEcho` / error kinds). */
    fun completeOutcome(outcome: String) {
        receiver?.onOutcome(outcome)
    }
}
