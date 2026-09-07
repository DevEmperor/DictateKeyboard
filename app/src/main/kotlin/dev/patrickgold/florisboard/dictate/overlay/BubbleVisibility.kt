/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.overlay

import dev.patrickgold.florisboard.dictate.DictateController

/**
 * When the floating dictation button is on screen — the whole rule, as one function that can be asked
 * without a phone (issue #339).
 *
 * It used to live inline in the controller's flow collector, where its second clause read "the state is
 * not idle". That was meant as *a dictation is running*, and for a while the two were the same thing.
 * They stopped being the same when the state machine gained resting states that merely carry an offer:
 * a rate/donate nudge, a recording the screen going dark interrupted, a failure with the audio kept for
 * a retry. All of those are Smartbar chips — they can only be seen and dismissed on the keyboard — so
 * for someone using the button with a different keyboard, one of them parked the state off idle with
 * nothing in the overlay world able to clear it again, and the button stayed on screen over every app
 * and over the home screen, for good.
 */
object BubbleVisibility {

    /**
     * Whether [state] is work in flight, i.e. a reason to keep the button on screen even though no text
     * field holds focus.
     *
     * Only what is actually running counts. A recording has to survive leaving the app it is going into
     * — the microphone is open and the user is still talking (#293) — and the same is true while its
     * transcription or rewording is in the air. Everything else is a resting state: whatever it offers,
     * it offers on the keyboard, and it must not keep a button floating over the home screen.
     *
     * That includes [DictateController.UiState.Error]: nothing is lost by letting the button go. The
     * message is a toast, which is a window of its own, the text is on the clipboard, and the kept audio
     * stays kept — so the moment a field is focused again the button is back, still in the error state,
     * and one tap re-sends the recording (#160).
     */
    fun pinsBubble(state: DictateController.UiState): Boolean = when (state) {
        is DictateController.UiState.Recording,
        is DictateController.UiState.Transcribing,
        is DictateController.UiState.Rewording,
        -> true
        else -> false
    }

    /**
     * Whether the button should be on screen right now.
     *
     * [hiddenByOwnKeyboard] is the Dictate keyboard being up without the user having asked for both (it
     * has a mic key of its own), [recognitionActive] is another app driving a system voice-input session
     * (#67), and [screenOn] is the display being interactive — the button's window layer deliberately
     * outlives the keyguard, so nobody takes it away for us and an always-on display would happily draw
     * it on a phone its owner believes to be off (#269).
     */
    fun shouldShow(
        enabled: Boolean,
        focused: Boolean,
        state: DictateController.UiState,
        hiddenByOwnKeyboard: Boolean,
        recognitionActive: Boolean,
        screenOn: Boolean,
    ): Boolean = enabled &&
        (focused || pinsBubble(state)) &&
        !hiddenByOwnKeyboard &&
        !recognitionActive &&
        screenOn
}
