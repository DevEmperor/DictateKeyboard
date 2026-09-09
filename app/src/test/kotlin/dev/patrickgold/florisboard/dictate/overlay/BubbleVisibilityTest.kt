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
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the floating button is on screen (issue #339).
 *
 * The reported failure is the first test: the button stood on the home screen after a finished
 * dictation, because the rate/donate nudge armed at the end of it left the state machine off idle — and
 * "not idle" was being read as "a dictation is running". The second test is the behaviour that must not
 * be traded away for it (#293): a recording keeps the button even after the user has left the app,
 * because the microphone is still open.
 */
class BubbleVisibilityTest {

    private val recording = DictateController.UiState.Recording(startedAtMs = 0L)
    private val transcribing = DictateController.UiState.Transcribing(startedAtMs = 0L)
    private val rewording = DictateController.UiState.Rewording("Formal")
    private val nudge = DictateController.UiState.Promo(DictateController.PromoKind.RATE)
    private val interrupted = DictateController.UiState.Interrupted(seconds = 12L)
    private val failed = DictateController.UiState.Error(
        message = "No internet connection",
        action = DictateController.ErrorAction.RESEND,
    )

    private fun shown(
        state: DictateController.UiState = DictateController.UiState.Idle,
        focused: Boolean = false,
        enabled: Boolean = true,
        hiddenByOwnKeyboard: Boolean = false,
        recognitionActive: Boolean = false,
        screenOn: Boolean = true,
    ) = BubbleVisibility.shouldShow(
        enabled = enabled,
        focused = focused,
        state = state,
        hiddenByOwnKeyboard = hiddenByOwnKeyboard,
        recognitionActive = recognitionActive,
        screenOn = screenOn,
    )

    @Test
    fun `a parked nudge does not keep the button on a screen with no field`() {
        assertFalse(shown(state = nudge))
        assertFalse(shown(state = interrupted))
        assertFalse(shown(state = failed))
        assertFalse(shown(state = DictateController.UiState.Idle))
    }

    @Test
    fun `work in flight keeps the button even after leaving the app`() {
        assertTrue(shown(state = recording))
        assertTrue(shown(state = transcribing))
        assertTrue(shown(state = rewording))
    }

    @Test
    fun `a focused field is enough on its own`() {
        assertTrue(shown(focused = true))
        assertTrue(shown(state = nudge, focused = true))
        assertTrue(shown(state = failed, focused = true))
    }

    @Test
    fun `only in-flight work pins the button`() {
        assertTrue(BubbleVisibility.pinsBubble(recording))
        assertTrue(BubbleVisibility.pinsBubble(transcribing))
        assertTrue(BubbleVisibility.pinsBubble(rewording))
        assertFalse(BubbleVisibility.pinsBubble(DictateController.UiState.Idle))
        assertFalse(BubbleVisibility.pinsBubble(nudge))
        assertFalse(BubbleVisibility.pinsBubble(interrupted))
        assertFalse(BubbleVisibility.pinsBubble(failed))
    }

    /** Each of the three suppressors wins over both reasons to show, including a live recording. */
    @Test
    fun `the suppressors win over everything`() {
        assertFalse(shown(state = recording, focused = true, enabled = false))
        assertFalse(shown(state = recording, focused = true, hiddenByOwnKeyboard = true))
        assertFalse(shown(state = recording, focused = true, recognitionActive = true))
        assertFalse(shown(state = recording, focused = true, screenOn = false))
    }
}
