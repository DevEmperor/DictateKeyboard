/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * An ambient recording-style equaliser, tinted by the theme accent.
 *
 * Dictate's most general picture of itself: it says "this app listens" without committing to any one
 * of the floating button's skins, and it follows the user's accent colour rather than a fixed
 * palette. That is why both the welcome step and the "What's new" tour open with it.
 *
 * Drawn on a Canvas rather than animated views — 44 bars at 60 fps would be 44 recompositions a
 * frame, while one Canvas is one draw call.
 */
@Composable
internal fun DictateWaveform(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "waveform")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(modifier = modifier) {
        val bars = 44
        val gap = size.width / bars
        val barWidth = max(2.5f, gap * 0.42f)
        val midY = size.height / 2f
        val twoPi = (2.0 * PI).toFloat()
        for (i in 0 until bars) {
            // A bell-shaped envelope so the ends taper — a flat block of bars reads as a chart,
            // a tapered one reads as sound.
            val envelope = 0.35f + 0.65f * sin(i / (bars - 1f) * PI.toFloat())
            val osc = (sin(phase * twoPi + i * 0.55f) * 0.5f + 0.5f)
            val h = max(3f, envelope * osc * size.height * 0.85f)
            val x = i * gap + gap / 2f
            drawRoundRect(
                color = accent.copy(alpha = 0.45f + 0.45f * envelope),
                topLeft = Offset(x - barWidth / 2f, midY - h / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
