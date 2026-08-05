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

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * A thinking orb for the floating dictation button (issue #253): coloured light moving inside a sphere,
 * in the visual language AI interfaces have converged on. Every state is the same orb at a different
 * temperament rather than a different widget — it drifts when idle, swells with the voice while
 * recording, and churns while the transcript is being worked on, so the button never has to swap in a
 * spinner to say it is busy.
 *
 * Drawn rather than composed from views: three blurred blobs orbiting inside a clipped circle is a
 * handful of drawing calls, where the same look in views would need layers, masks and a blur pass. The
 * blur wants a software layer, which is why the view asks for one.
 */
class DictateAuroraOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** How lively the orb is, which is the only thing its states change. */
    enum class Mood(val speed: Float, val churn: Float) {
        /** Barely moving — the button is only waiting to be pressed. */
        IDLE(0.25f, 0.10f),

        /** Rides the voice; the mic level adds the rest. */
        RECORDING(0.8f, 0.35f),

        /** Visibly working, which is the whole point of it standing in for a spinner. */
        THINKING(1.6f, 0.55f),
    }

    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val clip = Path()

    /** Radius of the sphere itself, which is smaller than the view: the margin is its breathing room. */
    var bodyRadius = 0f

    private var mood = Mood.IDLE
    private var tint = Color.WHITE
    private var phase = 0f
    private var level = 0f
    private var animator: ValueAnimator? = null

    init {
        // BlurMaskFilter is not supported by the hardware pipeline.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setMood(mood: Mood, color: Int) {
        this.mood = mood
        this.tint = color
        if (mood != Mood.RECORDING) level = 0f
        basePaint.color = darken(color, 0.55f)
        start()
    }

    /** Mic level, smoothed so the orb swells with the voice instead of twitching per frame. */
    fun pushLevel(value: Float) {
        level += (value.coerceIn(0f, 1f) - level) * 0.35f
    }

    private fun start() {
        if (animator != null) return
        // Motion turned off system-wide is a preference, not a suggestion: hold a still orb instead.
        if (!ValueAnimator.areAnimatorsEnabled()) {
            phase = 0.35f
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 6000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                // Its own clock rather than the animator's fraction, which restarts every cycle and
                // would jerk the orbits back whenever the mood changed their speed.
                phase = (phase + mood.speed / 60f) % 1_000f
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        // The voice swells the sphere itself a little, so a loud moment is visible even before the light
        // inside it moves.
        val radius = (bodyRadius.takeIf { it > 0f } ?: (size / 2f)) * (1f + 0.06f * level)

        clip.reset()
        clip.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawCircle(cx, cy, radius, basePaint)

        // Three blobs on slow, differently-paced orbits. The voice pushes them outwards, so a loud
        // moment reads as the light pressing against the inside of the sphere.
        val spread = radius * (0.30f + 0.22f * level + mood.churn * 0.25f)
        val blobRadius = radius * (0.62f + 0.10f * level)
        blobPaint.maskFilter = BlurMaskFilter(radius * 0.45f, BlurMaskFilter.Blur.NORMAL)
        for (i in BLOB_ANGLES.indices) {
            val angle = BLOB_ANGLES[i] + phase * BLOB_RATES[i] * FULL_TURN
            blobPaint.color = lighten(tint, BLOB_TINTS[i])
            blobPaint.alpha = 210
            canvas.drawCircle(
                cx + cos(angle) * spread,
                cy + sin(angle) * spread,
                blobRadius,
                blobPaint,
            )
        }
        blobPaint.maskFilter = null

        // A highlight near the top edge, which is what makes it read as a sphere rather than a disc.
        blobPaint.color = lighten(tint, 0.55f)
        blobPaint.alpha = 90
        canvas.drawCircle(cx - radius * 0.28f, cy - radius * 0.34f, radius * 0.42f, blobPaint)
        blobPaint.alpha = 255
        canvas.restore()
    }

    private companion object {
        /** Three blobs, started apart and orbiting at rates that never quite repeat. */
        private const val FULL_TURN = 6.2831855f
        private val BLOB_ANGLES = floatArrayOf(0f, 2.1f, 4.2f)
        private val BLOB_RATES = floatArrayOf(1f, -0.62f, 0.41f)
        private val BLOB_TINTS = floatArrayOf(0.45f, 0.18f, 0.68f)

        /** Mixes [color] towards white by [amount] (0..1) — the light inside the orb. */
        private fun lighten(color: Int, amount: Float): Int = Color.rgb(
            (Color.red(color) + (255 - Color.red(color)) * amount).toInt().coerceIn(0, 255),
            (Color.green(color) + (255 - Color.green(color)) * amount).toInt().coerceIn(0, 255),
            (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt().coerceIn(0, 255),
        )

        /** Mixes [color] towards black by [amount] — the shaded body behind that light. */
        private fun darken(color: Int, amount: Float): Int = Color.rgb(
            (Color.red(color) * (1f - amount)).toInt().coerceIn(0, 255),
            (Color.green(color) * (1f - amount)).toInt().coerceIn(0, 255),
            (Color.blue(color) * (1f - amount)).toInt().coerceIn(0, 255),
        )
    }
}
