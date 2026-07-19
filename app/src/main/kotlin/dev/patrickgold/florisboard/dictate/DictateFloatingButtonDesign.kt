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
 * Visual style of the floating dictation button (issue #88), selectable in the floating-button settings.
 *
 * - [RING]: a compact circular bubble with a thin animated ring that encodes the state (pulse while
 *   recording, spinner while transcribing/rewording).
 * - [PILL]: a circular bubble that expands into a pill while active, showing an elapsed timer and a live
 *   waveform during recording and a labelled spinner while transcribing/rewording.
 * - [ORB]: a solid orb that glows and pulses with the voice amplitude while recording and breathes
 *   gently while transcribing/rewording.
 * - [CLOUD]: an orb-ui-inspired fluid cloud surface whose internal flow and scale react to microphone
 *   input; transcribing/rewording keeps the same calmer surface with a compact activity spinner.
 */
enum class DictateFloatingButtonDesign {
    RING,
    PILL,
    ORB,
    CLOUD;
}
