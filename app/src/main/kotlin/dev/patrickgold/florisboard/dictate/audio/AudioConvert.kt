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

import android.util.Log
import dev.patrickgold.florisboard.dictate.provider.AudioContainer
import java.io.File

/**
 * The one question asked before a file is uploaded: will this provider take this container, and if not,
 * what do we hand it instead (issue #322).
 *
 * Until this existed the answer arrived as a provider error after the bytes had already gone up — a
 * shared WhatsApp voice note reached Gemini as `application/octet-stream` and was refused. The
 * information to prevent that was always available locally: the container is in the file's first bytes
 * ([AudioContainer]) and the accepted list is in the preset
 * ([dev.patrickgold.florisboard.dictate.provider.ProviderPreset.acceptedAudioContainers]).
 *
 * **Doing nothing is the common case and the safe one.** A provider that publishes no list gets its
 * file untouched, and so does a container already on the list. Conversion only ever happens where a
 * refusal was otherwise certain.
 */
object AudioConvert {

    private const val LOG_TAG = "DictateAudioConvert"

    /**
     * A converted copy of [input] in [cacheDir], or **null when nothing needs to change** — which also
     * covers "nothing can be done", so the caller simply uploads what it already had.
     *
     * The caller owns the returned file and must delete it. [accepted] is the target's own list; an
     * empty one means the target never said, and an unknown container is not a reason to re-encode
     * someone's audio on a guess.
     *
     * m4a is preferred over wav when both are accepted, because the alternative to a small file here is
     * a WAV several times its size going over a mobile connection.
     */
    suspend fun toAccepted(cacheDir: File, input: File, accepted: Set<AudioContainer>): File? {
        if (accepted.isEmpty()) return null
        val container = AudioContainer.of(input)
        if (container in accepted) return null

        val stem = "cnv_${input.nameWithoutExtension.take(48)}"
        val converted = when {
            AudioContainer.M4A in accepted ->
                AudioEncode.transcodeToM4a(input, File(cacheDir, "$stem.m4a"))
            AudioContainer.WAV in accepted ->
                AudioEncode.transcodeToWav(input, File(cacheDir, "$stem.wav"))
            // A list that holds neither is not one this app can satisfy; let the provider speak.
            else -> null
        }
        if (converted == null) {
            Log.w(LOG_TAG, "could not convert ${container.name} for a target accepting $accepted")
        } else {
            Log.i(LOG_TAG, "converted ${container.name} to ${converted.extension} before upload")
        }
        return converted
    }
}
