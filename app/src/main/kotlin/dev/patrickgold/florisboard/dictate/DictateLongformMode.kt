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
 * How long-form segmented dictation (issue #170) behaves. [OFF] is the normal one-shot-at-the-end
 * transcription; [MANUAL] shows the "Next" button so the user cuts a segment themselves; [AUTO]
 * additionally auto-cuts at speech pauses (Silero VAD) while keeping the manual Next button available.
 */
enum class DictateLongformMode {
    OFF,
    MANUAL,
    AUTO;

    val isEnabled: Boolean get() = this != OFF
}
