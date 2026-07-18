/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * The cloud shader and its visual mapping are adapted from orb-ui's CloudTheme:
 * Copyright (c) 2026 Alexander Chen, licensed under the MIT License.
 * https://github.com/alexanderqchen/orb-ui/blob/main/src/themes/cloud/CloudTheme.tsx
 */

package dev.patrickgold.florisboard.dictate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Native Android port of orb-ui's current default Cloud theme.
 *
 * Android 13+ renders the original procedural cloud field as an AGSL [RuntimeShader]. Older Android
 * versions keep the same palette and audio motion with cached Canvas gradients. Both paths animate only
 * while the orb is visible in an active state; the microphone level itself still arrives at 20 Hz.
 */
internal class AudioReactiveCloudOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { HIDDEN, LISTENING, THINKING }

    private var mode = Mode.HIDDEN
    private var targetLevel = 0f
    private var currentLevel = 0f
    private var currentScale = 1f
    private var flowTime = 0f
    private var lastFrameNanos = 0L
    private var paused = false

    private val cloudRenderer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching { RuntimeCloudRenderer() }.getOrNull()
    } else {
        null
    }

    private val fallbackBasePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackMistPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x71, 0x78, 0xF5)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setMode(value: Mode) {
        if (mode == value) return
        mode = value
        lastFrameNanos = 0L
        visibility = if (value == Mode.HIDDEN) INVISIBLE else VISIBLE
        invalidate()
    }

    fun setLevel(value: Float) {
        val next = value.coerceIn(0f, 1f)
        if (targetLevel == next) return
        targetLevel = next
        if (!shouldAnimate()) invalidate()
    }

    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        alpha = if (value) PAUSED_ALPHA else 1f
        lastFrameNanos = 0L
        invalidate()
    }

    fun stop() {
        mode = Mode.HIDDEN
        targetLevel = 0f
        currentLevel = 0f
        currentScale = 1f
        lastFrameNanos = 0L
        visibility = INVISIBLE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        fallbackBasePaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            h.toFloat(),
            intArrayOf(DEEP_PERIWINKLE, UPPER_PERIWINKLE, MILK, LOWER_LAVENDER),
            floatArrayOf(0f, 0.34f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        fallbackMistPaint.shader = RadialGradient(
            w * 0.42f,
            h * 0.53f,
            min(w, h) * 0.48f,
            intArrayOf(0xDDE3EBFE.toInt(), 0x70E3EBFE, Color.TRANSPARENT),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP,
        )
        spinnerPaint.strokeWidth = min(w, h) * 0.035f
    }

    override fun onDraw(canvas: Canvas) {
        if (mode == Mode.HIDDEN || width == 0 || height == 0) return

        val now = System.nanoTime()
        val deltaSeconds = if (lastFrameNanos == 0L) {
            0f
        } else {
            min((now - lastFrameNanos) / 1_000_000_000f, MAX_DELTA_SECONDS)
        }
        lastFrameNanos = now

        val motionEnabled = shouldAnimate()
        updateMotion(deltaSeconds, motionEnabled)

        val cx = width / 2f
        val cy = height / 2f
        canvas.save()
        canvas.scale(currentScale, currentScale, cx, cy)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && cloudRenderer != null) {
            cloudRenderer.draw(canvas, width, height, flowTime, activity())
        } else {
            drawFallback(canvas, cx, cy)
        }
        canvas.restore()

        if (mode == Mode.THINKING) drawSpinner(canvas, cx, cy, motionEnabled)
        if (motionEnabled) {
            // Keep the exact display-rate flow on the GPU path; spare older devices from a 60 Hz
            // software paint loop while retaining smooth-enough movement in the fallback.
            if (cloudRenderer != null) postInvalidateOnAnimation()
            else postInvalidateDelayed(FALLBACK_FRAME_MS)
        }
    }

    override fun onDetachedFromWindow() {
        lastFrameNanos = 0L
        super.onDetachedFromWindow()
    }

    private fun updateMotion(deltaSeconds: Float, motionEnabled: Boolean) {
        if (!motionEnabled) {
            currentLevel = 0f
            currentScale = 1f
            return
        }

        val levelRate = if (targetLevel > currentLevel) LEVEL_ATTACK_RATE else LEVEL_RELEASE_RATE
        currentLevel = damp(currentLevel, targetLevel, levelRate, deltaSeconds)

        val scaleTarget = if (mode == Mode.LISTENING) {
            // CloudTheme communicates microphone input by pulling inward as the voice gets louder.
            1f - currentLevel * LISTEN_SHRINK
        } else {
            1f
        }
        val scaleRate = if (abs(scaleTarget - 1f) > abs(currentScale - 1f)) {
            SCALE_ATTACK_RATE
        } else {
            SCALE_RELEASE_RATE
        }
        currentScale = damp(currentScale, scaleTarget, scaleRate, deltaSeconds)
        flowTime += deltaSeconds * flowSpeed()
    }

    private fun shouldAnimate(): Boolean =
        mode != Mode.HIDDEN && !paused && isShown && ValueAnimator.areAnimatorsEnabled()

    private fun flowSpeed(): Float = when (mode) {
        Mode.LISTENING -> 0.72f + currentLevel * 0.78f
        Mode.THINKING -> 0.24f
        Mode.HIDDEN -> 0f
    }

    private fun activity(): Float = when (mode) {
        Mode.LISTENING -> 0.28f + currentLevel * 0.32f
        Mode.THINKING -> 0.10f
        Mode.HIDDEN -> 0f
    }

    private fun drawFallback(canvas: Canvas, cx: Float, cy: Float) {
        val radius = min(width, height) / 2f
        canvas.save()
        canvas.rotate(sin(flowTime * 0.8f) * 5f, cx, cy)
        canvas.drawCircle(cx, cy, radius, fallbackBasePaint)
        canvas.drawCircle(cx, cy, radius, fallbackMistPaint)
        canvas.restore()
    }

    private fun drawSpinner(canvas: Canvas, cx: Float, cy: Float, motionEnabled: Boolean) {
        val radius = min(width, height) * 0.075f
        val angle = if (motionEnabled) (flowTime * 260f) % 360f else 45f
        canvas.drawArc(
            cx - radius,
            cy - radius,
            cx + radius,
            cy + radius,
            angle,
            245f,
            false,
            spinnerPaint,
        )
    }

    private fun damp(current: Float, target: Float, rate: Float, deltaSeconds: Float): Float =
        current + (target - current) * (1f - exp(-rate * deltaSeconds))

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private class RuntimeCloudRenderer {
        private val shader = RuntimeShader(CLOUD_SHADER)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = this@RuntimeCloudRenderer.shader }

        fun draw(canvas: Canvas, width: Int, height: Int, time: Float, activity: Float) {
            shader.setFloatUniform("u_resolution", width.toFloat(), height.toFloat())
            shader.setFloatUniform("u_time", time)
            shader.setFloatUniform("u_activity", activity)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    private companion object {
        // Exact CloudTheme listening response and damping constants.
        private const val LISTEN_SHRINK = 0.204f
        private const val LEVEL_ATTACK_RATE = 11f
        private const val LEVEL_RELEASE_RATE = 6f
        private const val SCALE_ATTACK_RATE = 12f
        private const val SCALE_RELEASE_RATE = 6f
        private const val MAX_DELTA_SECONDS = 0.05f
        private const val PAUSED_ALPHA = 0.45f
        private const val FALLBACK_FRAME_MS = 33L

        private const val DEEP_PERIWINKLE = 0xFF5C63FB.toInt()
        private const val UPPER_PERIWINKLE = 0xFF7A8FFB.toInt()
        private const val LOWER_LAVENDER = 0xFFB8C7F9.toInt()
        private const val MILK = 0xFFE3EBFE.toInt()

        /** AGSL port of orb-ui's MIT-licensed CloudTheme fragment shader. */
        private const val CLOUD_SHADER = """
            uniform float2 u_resolution;
            uniform float u_time;
            uniform float u_activity;

            float hash(float2 p) {
                p = fract(p * float2(123.34, 456.21));
                p += dot(p, p + 45.32);
                return fract(p.x * p.y);
            }

            float noise(float2 p) {
                float2 i = floor(p);
                float2 f = fract(p);
                float2 u = f * f * (3.0 - 2.0 * f);
                return mix(
                    mix(hash(i), hash(i + float2(1.0, 0.0)), u.x),
                    mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x),
                    u.y
                );
            }

            float fbm(float2 p) {
                float value = 0.0;
                float amplitude = 0.52;
                float2x2 rotation = float2x2(0.80, 0.60, -0.60, 0.80);
                for (int octave = 0; octave < 5; octave++) {
                    value += amplitude * noise(p);
                    p = rotation * p * 1.92 + float2(9.7, 4.3);
                    amplitude *= 0.5;
                }
                return value;
            }

            half4 main(float2 fragCoord) {
                // AGSL uses a top-left origin; flip Y to match WebGL's gl_FragCoord.
                float2 uv = float2(fragCoord.x, u_resolution.y - fragCoord.y) / u_resolution;
                float2 centered = uv - 0.5;
                float radius = length(centered);
                float edge = 1.0 - smoothstep(0.488, 0.5, radius);

                float2 p = centered * 2.0;
                float t = u_time;
                float2 warp = float2(
                    fbm(p * 1.02 + float2(t * 0.34, -t * 0.24)),
                    fbm(p * 1.08 + float2(-t * 0.27, t * 0.32) + float2(6.7, 2.9))
                );
                float2 curl = float2(
                    sin(p.y * 2.4 + t * 0.68 + warp.y * 3.2),
                    cos(p.x * 2.1 - t * 0.61 + warp.x * 3.0)
                );
                float2 warped =
                    p +
                    (warp - 0.5) * (1.18 + u_activity * 0.38) +
                    curl * (0.035 + u_activity * 0.07);
                float broad = fbm(warped * 0.92 + float2(t * 0.14, -t * 0.18));
                float folded = fbm(warped * 1.66 + float2(-t * 0.23, t * 0.19) + 5.2);
                float field = mix(broad, folded, 0.3 + u_activity * 0.14);

                float horizon =
                    0.46 +
                    0.08 * sin((uv.x + warp.x * 0.2) * 5.4 + t * 0.42) +
                    0.16 * (broad - 0.5);
                float upper = smoothstep(horizon - 0.12, horizon + 0.08, uv.y);
                float band = exp(-pow((uv.y - horizon) * (5.2 + u_activity * 0.8), 2.0));
                float cloud = smoothstep(0.24, 0.79, field);

                float3 deepPeriwinkle = float3(0.36, 0.39, 0.985);
                float3 upperPeriwinkle = float3(0.48, 0.56, 0.985);
                float3 lowerLavender = float3(0.72, 0.78, 0.975);
                float3 milk = float3(0.89, 0.92, 0.995);

                float3 color = mix(lowerLavender, upperPeriwinkle, upper);
                float upperDepth = upper * (0.14 + smoothstep(0.42, 0.78, folded) * 0.5);
                color = mix(color, deepPeriwinkle, upperDepth);
                float milkAmount = clamp(band * (0.42 + cloud * 0.62), 0.0, 0.88);
                color = mix(color, milk, milkAmount);
                float lowerMist = (1.0 - upper) * smoothstep(0.58, 0.9, broad) * 0.18;
                color = mix(color, milk, lowerMist);
                float grain = (noise(fragCoord * 0.64) - 0.5) / 255.0;
                color += grain;

                return half4(color * edge, edge);
            }
        """
    }
}
