/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.audio

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder

/**
 * Which Android audio source the recorder captures from (issue #62). The default `MIC` source runs the
 * device's voice-pipeline processing (noise suppression, automatic gain, "voice isolation" on newer
 * phones) which is tuned for telephony, not for speech-recognition models — so it can noticeably hurt
 * transcription accuracy. The alternatives reduce or skip that processing:
 *
 *  - [VOICE_RECOGNITION]: the source Android tunes for ASR (no AGC); widely supported.
 *  - [UNPROCESSED]: raw audio with no processing at all, but device-dependent — falls back to
 *    [VOICE_RECOGNITION] when the device reports no support.
 *
 * Bluetooth-SCO recording is unaffected: it always uses `VOICE_COMMUNICATION` regardless of this choice.
 */
enum class DictateAudioSource {
    DEFAULT,
    VOICE_COMMUNICATION,
    VOICE_RECOGNITION,
    UNPROCESSED;

    /** Maps to a [MediaRecorder.AudioSource], degrading [UNPROCESSED] → [VOICE_RECOGNITION] when unsupported. */
    fun resolve(context: Context): Int = when (this) {
        DEFAULT -> MediaRecorder.AudioSource.MIC
        VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        UNPROCESSED -> {
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val supported = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            if (supported) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }
}
