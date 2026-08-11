/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate

/**
 * Stage of a hold-to-record gesture (issue #235), modelled on the voice-message interaction people
 * already know from messengers.
 *
 * While the finger is down nothing else on screen can be tapped — not the language chip, not pause,
 * not a prompt. That is what [LOCKED] is for: sliding down into the lock target latches the recording
 * so the ordinary recording bar takes over and everything becomes reachable again. Down rather than up
 * because above the Smartbar is the app behind the keyboard, where nothing of ours can be drawn.
 *
 *  - [NONE]: no hold in progress (tap-toggle, or nothing recording).
 *  - [HOLDING]: finger down, releasing will send.
 *  - [CANCEL_ARMED]: slid far enough towards the cancel target; releasing now discards the recording.
 *  - [LOCKED]: slid down into the lock — the recording continues after release and is ended by the
 *    stop button.
 */
enum class PushToTalkPhase {
    NONE,
    HOLDING,
    CANCEL_ARMED,
    LOCKED;

    /** True while a finger is still on the button, i.e. the slide affordances apply. */
    val isHolding: Boolean get() = this == HOLDING || this == CANCEL_ARMED
}
