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
 * Native Android port of orb-ui's current default Cloud theme, extended so the same surface carries every
 * dictation stage in one cohesive look:
 *
 * - [Mode.IDLE]: a calm, static cloud used as the tappable button symbol before recording.
 * - [Mode.LISTENING]: the cloud reacts to the microphone level — it pulls inward, its flow speeds up and
 *   its field turbulence/brightness rise with the voice, so the reaction is clearly visible.
 * - [Mode.THINKING]: a calmer flow with a small activity spinner while transcribing/rewording.
 * - [Mode.SUCCESS] / [Mode.ERROR]: the whole cloud field tints green / red so the terminal feedback still
 *   looks like the same cloud instead of a detached colored circle.
 *
 * Android 13+ renders the procedural cloud field as an AGSL [RuntimeShader]; older versions keep the same
 * palette and audio motion with cached Canvas gradients. Both paths animate only while the orb is visible
 * in an animating state; the microphone level itself still arrives at 20 Hz.
 */
internal class AudioReactiveCloudOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { HIDDEN, IDLE, LISTENING, THINKING, SUCCESS, ERROR }

    private var mode = Mode.HIDDEN
    private var targetLevel = 0f
    private var currentLevel = 0f
    private var currentScale = IDLE_SCALE
    private var flowTime = 0f
    private var currentTint = 0f
    private var tintR = SUCCESS_TINT[0]
    private var tintG = SUCCESS_TINT[1]
    private var tintB = SUCCESS_TINT[2]
    private var lastFrameNanos = 0L
    private var paused = false

    private val cloudRenderer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching { RuntimeCloudRenderer() }.getOrNull()
    } else {
        null
    }

    private val fallbackBasePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackMistPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackTintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
        when (value) {
            Mode.SUCCESS -> setTintColor(SUCCESS_TINT)
            Mode.ERROR -> setTintColor(ERROR_TINT)
            Mode.IDLE -> currentLevel = 0f
            else -> Unit
        }
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
        currentScale = IDLE_SCALE
        currentTint = 0f
        lastFrameNanos = 0L
        visibility = INVISIBLE
    }

    private fun setTintColor(rgb: FloatArray) {
        tintR = rgb[0]; tintG = rgb[1]; tintB = rgb[2]
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
            cloudRenderer.draw(canvas, width, height, flowTime, activity(), tintR, tintG, tintB, currentTint)
        } else {
            drawFallback(canvas, cx, cy)
            if (currentTint > 0.01f) drawFallbackTint(canvas, cx, cy)
        }
        canvas.restore()

        if (mode == Mode.THINKING) drawSpinner(canvas, cx, cy, motionEnabled)

        // IDLE draws a single static frame (a still cloud symbol) to spare the battery while the button
        // just sits there; every other state — or a tint still fading out — keeps animating.
        val animating = motionEnabled &&
            (mode == Mode.LISTENING || mode == Mode.THINKING || mode == Mode.SUCCESS ||
                mode == Mode.ERROR || currentTint > 0.01f ||
                (mode == Mode.IDLE && abs(currentScale - IDLE_SCALE) > 0.004f))
        if (animating) {
            if (cloudRenderer != null) postInvalidateOnAnimation()
            else postInvalidateDelayed(FALLBACK_FRAME_MS)
        }
    }

    override fun onDetachedFromWindow() {
        lastFrameNanos = 0L
        super.onDetachedFromWindow()
    }

    private fun updateMotion(deltaSeconds: Float, motionEnabled: Boolean) {
        val tintTarget = if (mode == Mode.SUCCESS || mode == Mode.ERROR) 1f else 0f
        if (!motionEnabled) {
            currentLevel = 0f
            currentScale = scaleTargetFor()
            currentTint = tintTarget // reduced-motion / paused: still show the terminal tint, just static
            return
        }

        val levelRate = if (targetLevel > currentLevel) LEVEL_ATTACK_RATE else LEVEL_RELEASE_RATE
        currentLevel = damp(currentLevel, targetLevel, levelRate, deltaSeconds)
        currentTint = damp(currentTint, tintTarget, TINT_RATE, deltaSeconds)

        // Recording grows the whole cloud and keeps it grown through transcribing; it shrinks back to the
        // idle size only when we return to idle / a terminal flash. The voice shows mainly through the
        // field turbulence, with only a small extra grow — no strong inward oscillation.
        val scaleTarget = scaleTargetFor()
        val scaleRate = if (scaleTarget > currentScale) SCALE_ATTACK_RATE else SCALE_RELEASE_RATE
        currentScale = damp(currentScale, scaleTarget, scaleRate, deltaSeconds)
        flowTime += deltaSeconds * flowSpeed()
    }

    private fun scaleTargetFor(): Float = when (mode) {
        Mode.LISTENING -> RECORD_SCALE + currentLevel * LISTEN_VOICE_GROW
        Mode.THINKING -> THINK_SCALE
        else -> IDLE_SCALE
    }

    private fun shouldAnimate(): Boolean =
        mode != Mode.HIDDEN && !paused && isShown && ValueAnimator.areAnimatorsEnabled()

    private fun flowSpeed(): Float = when (mode) {
        // Speed up strongly with the voice so the churn is obvious.
        Mode.LISTENING -> 0.85f + currentLevel * 2.60f
        Mode.THINKING -> 0.24f
        Mode.SUCCESS, Mode.ERROR -> 0.30f
        Mode.IDLE -> 0f
        Mode.HIDDEN -> 0f
    }

    private fun activity(): Float = when (mode) {
        // Higher activity = more field turbulence + more milky highlights in the shader, so the cloud
        // visibly churns and brightens as the voice gets louder.
        Mode.LISTENING -> 0.35f + currentLevel * 1.05f
        Mode.THINKING -> 0.10f
        Mode.SUCCESS, Mode.ERROR -> 0.14f
        Mode.IDLE -> 0.12f
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

    private fun drawFallbackTint(canvas: Canvas, cx: Float, cy: Float) {
        val radius = min(width, height) / 2f
        val a = (currentTint * 210f).toInt().coerceIn(0, 255)
        fallbackTintPaint.color = Color.argb(
            a,
            (tintR * 255f).toInt().coerceIn(0, 255),
            (tintG * 255f).toInt().coerceIn(0, 255),
            (tintB * 255f).toInt().coerceIn(0, 255),
        )
        canvas.drawCircle(cx, cy, radius, fallbackTintPaint)
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

        fun draw(
            canvas: Canvas,
            width: Int,
            height: Int,
            time: Float,
            activity: Float,
            tintR: Float,
            tintG: Float,
            tintB: Float,
            tintStrength: Float,
        ) {
            shader.setFloatUniform("u_resolution", width.toFloat(), height.toFloat())
            shader.setFloatUniform("u_time", time)
            shader.setFloatUniform("u_activity", activity)
            shader.setFloatUniform("u_tint", tintR, tintG, tintB)
            shader.setFloatUniform("u_tintStrength", tintStrength)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    private companion object {
        // Idle vs. active sizing (as a fraction of the 64dp button): the cloud is small at idle and grows
        // clearly while recording, stays grown through transcribing, and shrinks back only on return to
        // idle / a terminal flash. Recording is shown mainly by turbulence, with a small extra grow from
        // the voice rather than inward oscillation.
        private const val IDLE_SCALE = 0.70f
        private const val RECORD_SCALE = 0.85f
        private const val THINK_SCALE = 0.88f
        private const val LISTEN_VOICE_GROW = 0.14f
        private const val LEVEL_ATTACK_RATE = 13f
        private const val LEVEL_RELEASE_RATE = 6f
        private const val SCALE_ATTACK_RATE = 13f
        private const val SCALE_RELEASE_RATE = 6f
        private const val TINT_RATE = 7f
        private const val MAX_DELTA_SECONDS = 0.05f
        private const val PAUSED_ALPHA = 0.45f
        private const val FALLBACK_FRAME_MS = 33L

        private val SUCCESS_TINT = floatArrayOf(0.24f, 0.80f, 0.46f)
        private val ERROR_TINT = floatArrayOf(0.94f, 0.30f, 0.28f)

        private const val DEEP_PERIWINKLE = 0xFF5C63FB.toInt()
        private const val UPPER_PERIWINKLE = 0xFF7A8FFB.toInt()
        private const val LOWER_LAVENDER = 0xFFB8C7F9.toInt()
        private const val MILK = 0xFFE3EBFE.toInt()

        /** AGSL port of orb-ui's MIT-licensed CloudTheme fragment shader, plus a tint pass. */
        private const val CLOUD_SHADER = """
            uniform float2 u_resolution;
            uniform float u_time;
            uniform float u_activity;
            uniform float3 u_tint;
            uniform float u_tintStrength;

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
                    (warp - 0.5) * (1.10 + u_activity * 0.75) +
                    curl * (0.035 + u_activity * 0.18);
                float broad = fbm(warped * 0.92 + float2(t * 0.14, -t * 0.18));
                float folded = fbm(warped * 1.66 + float2(-t * 0.23, t * 0.19) + 5.2);
                float field = mix(broad, folded, 0.3 + u_activity * 0.30);

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
                // Milk comes only from the horizon band now (no voice-driven brightening) so the churning
                // field structure stays readable while speaking instead of washing out.
                float milkAmount = clamp(band * (0.38 + cloud * 0.55), 0.0, 0.82);
                color = mix(color, milk, milkAmount);
                float lowerMist = (1.0 - upper) * smoothstep(0.58, 0.9, broad) * 0.18;
                color = mix(color, milk, lowerMist);
                float grain = (noise(fragCoord * 0.64) - 0.5) / 255.0;
                color += grain;

                // Terminal feedback: tint the whole field toward green (success) / red (error) so it still
                // reads as the same cloud instead of a detached colored circle.
                color = mix(color, u_tint, clamp(u_tintStrength, 0.0, 1.0) * 0.62);

                return half4(color * edge, edge);
            }
        """
    }
}
