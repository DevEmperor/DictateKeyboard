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

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dev.patrickgold.florisboard.app.FlorisPreferenceStore

/**
 * Optional haptic feedback for the dictation lifecycle (issue #166), so the user can tell blindly when a
 * step is done and it's worth looking at the screen again. Gated on `prefs.dictate.hapticFeedback`.
 *
 * Patterns (the reporter's suggestion): a short buzz on record start/stop, a double-short when the
 * transcription is ready, and a longer single buzz when a rewording/LLM prompt has been applied. The
 * start/stop buzz is skipped whenever the press that caused it has already buzzed by itself — see
 * `DictateController.pressAlreadyBuzzed`; the other two always fire, and they are the ones that carry
 * news the user has no other way of getting. The
 * amplitude uses [VibrationEffect.DEFAULT_AMPLITUDE], which honours the system's haptic-intensity setting
 * — that is the intensity control, so there's no separate slider. Driven from [DictateController]'s state
 * transitions; the watch mirrors the same signals in `WearImeService`.
 */
object DictateHaptics {
    private val prefs by FlorisPreferenceStore

    private const val SHORT_MS = 25L
    private const val MEDIUM_MS = 90L
    private const val GAP_MS = 90L

    /** Record start/stop. */
    fun short(context: Context) = vibrate(context, VibrationEffect.createOneShot(SHORT_MS, VibrationEffect.DEFAULT_AMPLITUDE))

    /** Transcription ready. */
    fun double(context: Context) =
        vibrate(context, VibrationEffect.createWaveform(longArrayOf(0L, SHORT_MS, GAP_MS, SHORT_MS), -1))

    /** Rewording / LLM prompt applied. */
    fun medium(context: Context) = vibrate(context, VibrationEffect.createOneShot(MEDIUM_MS, VibrationEffect.DEFAULT_AMPLITUDE))

    private fun vibrate(context: Context, effect: VibrationEffect) {
        if (!prefs.dictate.hapticFeedback.get()) return
        runCatching {
            val vibrator = vibratorOf(context)
            if (vibrator.hasVibrator()) vibrator.vibrate(effect)
        }
    }

    private fun vibratorOf(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
}
