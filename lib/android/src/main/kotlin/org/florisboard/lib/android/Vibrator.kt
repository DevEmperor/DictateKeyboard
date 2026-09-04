/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.florisboard.lib.android

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.roundToInt

fun Context.systemVibratorOrNull(): Vibrator? {
    return if (AndroidVersion.ATLEAST_API31_S) {
        this.systemServiceOrNull(VibratorManager::class)?.defaultVibrator
    } else {
        this.systemServiceOrNull(Vibrator::class)
    }?.takeIf { it.hasVibrator() }
}

/**
 * The shortest pulse worth asking for. A linear resonant actuator — what phones have used for years —
 * needs a handful of milliseconds just to spin up, so anything below this is a command the hardware
 * cannot carry out, not a subtler tick.
 */
internal const val MIN_PERCEPTIBLE_DURATION_MS = 8L

/**
 * The faintest amplitude worth asking for, out of 255. Below roughly a tenth of full scale the actuator
 * either does not move or moves too little to feel through a phone case.
 */
internal const val MIN_PERCEPTIBLE_AMPLITUDE = 24

/**
 * How long and how hard a single tick should be — the arithmetic on its own, so it can be tested.
 *
 * [factor] is how *light* this particular tick should be relative to a normal key press: 1.0 for a key,
 * 0.4 for a long press, 0.05 for the ones that fire in a stream (key repeat, the cursor sliding along
 * the spacebar). It used to be applied to the duration **and** the amplitude at once, which multiplied
 * out far faster than anyone reading the call sites would expect. With the stock 10 ms at 5 % strength,
 * the 0.05 channels came to a 0.5 ms pulse at amplitude 0.6 — floored to 1 ms at amplitude 1, which no
 * hardware renders. Both of those channels are on by default, so two settings had been silently doing
 * nothing (issue #325).
 *
 * Now [factor] drives whichever lever the device actually has, and only that one:
 *
 *  - **With amplitude control** it scales the amplitude, and the pulse keeps its configured length.
 *    Amplitude is the honest dimension for "lighter" — that is what the hardware varies.
 *  - **Without it** the amplitude is fixed by the system, so the length is the only thing left to vary
 *    and [factor] scales that instead. Light channels end up closer to normal ones on such devices than
 *    on ones with amplitude control; nearly the same tick beats no tick at all.
 *
 * Both results are held above the thresholds above, so no channel can be scaled out of existence again.
 */
internal fun hapticShape(
    duration: Int,
    strength: Int,
    factor: Double,
    hasAmplitudeControl: Boolean,
): Pair<Long, Int> {
    return if (hasAmplitudeControl) {
        val amplitude = (255.0 * (strength / 100.0) * factor).roundToInt()
        duration.toLong().coerceAtLeast(MIN_PERCEPTIBLE_DURATION_MS) to
            amplitude.coerceIn(MIN_PERCEPTIBLE_AMPLITUDE, 255)
    } else {
        (duration * factor).roundToInt().toLong().coerceAtLeast(MIN_PERCEPTIBLE_DURATION_MS) to
            VibrationEffect.DEFAULT_AMPLITUDE
    }
}

fun Vibrator.vibrate(duration: Int, strength: Int, factor: Double = 1.0) {
    if (duration == 0 || strength == 0) return
    val (effectiveDuration, effectiveStrength) =
        hapticShape(duration, strength, factor, this.hasAmplitudeControl())
    Log.d("Vibrator", "Perform haptic with duration=$effectiveDuration and strength=$effectiveStrength")
    val effect = VibrationEffect.createOneShot(effectiveDuration, effectiveStrength)
    this.vibrate(effect)
}
