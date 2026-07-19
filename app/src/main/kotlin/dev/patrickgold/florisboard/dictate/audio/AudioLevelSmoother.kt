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

import kotlin.math.sqrt

/**
 * Turns PCM16 peaks into a stable 0..1 UI signal. A small noise gate keeps a quiet microphone still;
 * the square-root curve makes normal speech visible without requiring clipping-level input. Attack is
 * intentionally quicker than release so the visual feels responsive without flickering between samples.
 */
internal class AudioLevelSmoother {
    private var level = 0f

    fun update(amplitude: Int): Float {
        val normalized = (amplitude.coerceIn(0, PCM16_MAX) / VISUAL_FULL_SCALE).coerceIn(0f, 1f)
        val gated = ((normalized - NOISE_GATE) / (1f - NOISE_GATE)).coerceIn(0f, 1f)
        val curved = sqrt(gated)
        val rate = if (curved > level) ATTACK else RELEASE
        level += (curved - level) * rate
        if (level < REST_EPSILON && curved == 0f) level = 0f
        return level
    }

    fun reset(): Float {
        level = 0f
        return level
    }

    private companion object {
        private const val PCM16_MAX = 32767
        // Speech rarely reaches PCM16 full scale. This retains the overlay's proven visual calibration.
        private const val VISUAL_FULL_SCALE = 16000f
        private const val NOISE_GATE = 0.025f
        private const val ATTACK = 0.55f
        private const val RELEASE = 0.20f
        private const val REST_EPSILON = 0.005f
    }
}
