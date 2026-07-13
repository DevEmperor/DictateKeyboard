/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.devemperor.dictate.wear.ime

/**
 * The set of editing actions the Wear keyboard UI can trigger. Implemented by [WearImeService]
 * against the active [android.view.inputmethod.InputConnection]. Keeping this as a plain holder of
 * lambdas keeps the Compose layer free of any IME/InputConnection knowledge.
 */
class WearImeActions(
    val commitText: (String) -> Unit,
    val deleteBackward: () -> Unit,
    val performEnter: () -> Unit,
    /** Toggle dictation recording on/off. The result is committed via [commitText] when it lands. */
    val toggleDictation: () -> Unit,
    /** Pause or resume an in-progress recording (mirrors the phone's pause button). */
    val togglePause: () -> Unit,
    /** Discard an in-progress recording without transcribing. */
    val cancelDictation: () -> Unit,
    /** Hide the keyboard entirely (wired to requestHideSelf), triggered by the swipe-down gesture. */
    val dismiss: () -> Unit,
)

/** High-level state of the voice page, surfaced to the UI for the record button + status line. */
enum class WearDictationState {
    IDLE,
    RECORDING,
    TRANSCRIBING,
    REWORDING,
    ERROR,
}

/**
 * Timing of the current recording, mirroring `DictateController.UiState.Recording` on the phone so the
 * watch can show the same m:ss elapsed counter across pause/resume segments.
 */
data class WearRecordingInfo(
    val startedAtMs: Long = 0L,
    val accumulatedMs: Long = 0L,
    val paused: Boolean = false,
)
