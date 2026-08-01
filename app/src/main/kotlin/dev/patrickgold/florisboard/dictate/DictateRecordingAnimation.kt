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
 * How the recording indicator moves while a dictation is running (issue #238) — the Smartbar's red dot
 * and, in the classic layout, the big record button.
 *
 * Movement in the corner of the eye is distracting for exactly as long as one is trying to concentrate
 * on speaking, so this is a user choice rather than a fixed style.
 *
 *  - [STATIC]: no movement at all. The dot stays a solid red circle, the record button keeps its size.
 *  - [PULSE]: a steady pulse at a fixed rate, as the pre-rewrite app did.
 *  - [LEVEL]: size and opacity follow the live microphone level, so the indicator doubles as feedback
 *    that the mic is actually hearing something.
 */
enum class DictateRecordingAnimation {
    STATIC,
    PULSE,
    LEVEL;
}
