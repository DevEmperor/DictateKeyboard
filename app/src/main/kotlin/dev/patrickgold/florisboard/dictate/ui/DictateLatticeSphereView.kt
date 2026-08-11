/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * The orb modes (globe, wave, rubik, web and ring), their shared projection and painter, and the shipped
 * 64 px tunings they are drawn with are ported from thinking-orbs:
 * Copyright (c) 2026 Jakub Antalik, licensed under the MIT License.
 * https://github.com/Jakubantalik/thinking-orbs/tree/main/src/engine
 */

package dev.patrickgold.florisboard.dictate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A dot orb for the floating dictation button (issue #253), ported one to one from Jakub Antalik's
 * thinking-orbs: honestly 3D dot fields — rotated, depth-shaded and z-sorted — where depth is carried by
 * dot size and ink weight alone, with no blur, gradient or shader anywhere.
 *
 * The library ships each of its modes as a named state, and the button borrows the one whose meaning
 * matches: `connecting` while it waits and — the same constellation, recoloured and wound up to full
 * tempo — while recording, `listening` while the transcript comes back, `solving` while it is being
 * reworded. The state is therefore carried by the *motion* rather than by a badge or a spinner, which is
 * the whole idea behind the original. The remaining modes are ported too — the family only makes sense
 * whole, and moving one onto a state is then one word.
 *
 * Everything is laid out in the library's own square frame ([FRAME] units) and the canvas is scaled to the
 * button, so its tuning survives every button size; only the dot radii are re-derived per size, sub-linearly,
 * the way the library keeps a small mark legible instead of turning it into dust.
 *
 * @param sizeScale the button-size multiplier the skin is drawn at, which only the dot radii need to know.
 */
class DictateLatticeSphereView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val sizeScale: Float = 1f,
) : View(context, attrs) {

    /**
     * Which mode is on screen. [speed] multiplies the shared clock and is the library's shipped tuning,
     * except where noted.
     */
    enum class Mode(val speed: Float) {
        /** `globe`: a lat/long field with a scan meridian sweeping it — the library's "searching". */
        GLOBE(2.015f),

        /** `wave`: a waveform rolls through the rings — the library's "listening". */
        WAVE(4.388f),

        /** `rubik`: bands twist in quarter turns, scramble then solve — the library's "solving". */
        RUBIK(1.82f),

        /**
         * `web`: a constellation wires itself, packets running along the edges — the library's
         * "connecting". This is the face the button wears all day, so its own speed is a sixth of the
         * shipped 3.315: noticeable, never distracting. Recording winds it back up to full tempo through
         * `speedScale`, which is why the shipped figure is the one to scale from.
         */
        WEB(0.55f),

        /** `ring`: a face-on ring whose radius undulates — the library's "breathing", labelled "Thinking…". */
        RING(3.24f),

        /** `ribbon`: an undulating sash of strands rides a fixed band — the library's "composing". */
        RIBBON(2.34f),
    }

    /**
     * Diameter of the orb's frame in px. The rest of the view is the breathing room the glow designs keep
     * for their halo, so every button design is the same object at rest.
     */
    var bodyDiameter = 0f

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val proj = Projection()
    private val turned = TurnedPoint()
    private val solve = FloatArray(RUBIK_MOVES.size)
    private val nodes = FloatArray(WEB_NODE_N * 3)

    private var pool = arrayOfNulls<Dot>(512)
    private var used = 0

    /**
     * The radius multiplier for this button size. A dot radius lives in frame units, so matching the library
     * at the size actually rendered means undoing the linear part of the canvas scale: `(48·s / 300)^0.6 ·
     * 48 / (48·s)` reduces to the base scale times `s^-0.4`.
     */
    private val radiusScale = BASE_RADIUS_SCALE * sizeScale.pow(-0.4f)

    private var mode = Mode.WEB
    private var tint = Color.WHITE
    private var speedScale = 1f
    private var clock = 0f
    private var level = 0f
    private var paused = false
    private var lastFrameMs = 0L
    private var animator: ValueAnimator? = null

    /**
     * Switches mode and tint; the orb itself never changes shape, only its temperament and colour.
     *
     * [speedScale] multiplies the mode's own tempo, which is how one mode can serve two states: the same
     * motion, wound up, is a different thing to look at without becoming a different object.
     */
    fun setMode(mode: Mode, color: Int, speedScale: Float = 1f) {
        if (this.mode != mode) {
            // A mode change gets a fresh clock: the solver has to start from solved and the band from calm,
            // and a continuing clock would drop either of them in halfway through. Staying on the same mode
            // deliberately keeps its clock, so a recolour or a change of tempo never jumps.
            this.mode = mode
            clock = 0f
        }
        // Whatever the voice last pushed in belongs to the state being left, and nothing re-feeds it unless
        // the new one is a recording.
        level = 0f
        this.speedScale = speedScale
        tint = color
        // The dots are drawn in the library's dark-theme reading, where the near ones are the bright ones,
        // so they need something dark behind them whatever app is underneath. Kept translucent and short of
        // black on purpose: a solid near-black puck read as a hole punched into a light-coloured app.
        bodyPaint.color = ColorUtils.blendARGB(color, Color.BLACK, 0.6f)
        bodyPaint.alpha = 185
        start()
        invalidate()
    }

    /** Freezes the motion without tearing down the animator — the ring standing still reads as paused. */
    fun setPaused(value: Boolean) {
        paused = value
    }

    /** Mic level, smoothed so the orb swells with the voice instead of twitching per frame. */
    fun pushLevel(value: Float) {
        level += (value.coerceIn(0f, 1f) - level) * 0.35f
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    private fun start() {
        if (animator != null) return
        // Motion turned off system-wide is a preference, not a suggestion: hold the library's own static
        // representative frame instead.
        if (!ValueAnimator.areAnimatorsEnabled()) {
            clock = 0.6f
            invalidate()
            return
        }
        lastFrameMs = SystemClock.uptimeMillis()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 6000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                // Wall-clock deltas rather than the animator's fraction, which restarts every cycle and
                // would jerk the orb back whenever a mode changed its speed.
                val now = SystemClock.uptimeMillis()
                val dt = ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.05f)
                lastFrameMs = now
                // The voice drives the clock itself, so a loud moment visibly hurries the motion along
                // rather than only changing what one part of it looks like.
                if (!paused) clock += dt * mode.speed * speedScale * (1f + LEVEL_SPEED_BOOST * level)
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val body = bodyDiameter.takeIf { it > 0f } ?: minOf(width, height).toFloat()
        if (body <= 0f) return
        canvas.save()
        canvas.translate((width - body) / 2f, (height - body) / 2f)
        canvas.scale(body / FRAME, body / FRAME)
        val c = FRAME / 2f
        canvas.drawCircle(c, c, c, bodyPaint)
        used = 0
        when (mode) {
            Mode.GLOBE -> buildGlobe(clock)
            Mode.WAVE -> buildWave(clock)
            Mode.RUBIK -> buildRubik(clock)
            Mode.WEB -> buildWeb(canvas, clock)
            Mode.RING -> buildBand(
                clock, faceOn = true, ghostN = 0, lanes = RING_LANES, segs = RING_SEGS,
                rBase = RING_R_BASE, rDepth = RING_R_DEPTH, wobMul = RING_WOB_MUL,
            )
            Mode.RIBBON -> buildBand(
                clock, faceOn = false, ghostN = RIBBON_GHOST_N, lanes = RIBBON_LANES, segs = RIBBON_SEGS,
                rBase = RIBBON_R_BASE, rDepth = RIBBON_R_DEPTH, wobMul = RIBBON_WOB_MUL,
            )
        }
        paintDots(canvas)
        canvas.restore()
    }

    /** Globe: a lat/long field with a meridian sweeping across it, read as a size ripple, not a shine. */
    private fun buildGlobe(t: Float) {
        val spin = 0.5f
        val c = FRAME / 2f
        proj.set(t * spin, 0.4f + 0.06f * sin(t * 0.35f), c, c, c * 0.82f)
        // the scan sweeps relative to the spin; SCAN_MUL scales that relative rate
        val scan = t * (spin + (1.7f - spin) * GLOBE_SCAN_MUL)
        for (li in 0..GLOBE_RINGS) {
            val lat = -HALF_TURN / 2f + (li.toFloat() / GLOBE_RINGS) * HALF_TURN
            val cosLat = cos(lat)
            val sinLat = sin(lat)
            val lonCount = max(1, (abs(cosLat) * GLOBE_LON_DENSITY).roundToInt())
            for (lj in 0 until lonCount) {
                val lon = (lj.toFloat() / lonCount) * FULL_TURN
                proj.project(cosLat * cos(lon), sinLat, cosLat * sin(lon))
                val depth = (proj.pz + 1f) / 2f
                val d = angleDelta(lon + t * spin, scan)
                val boost = exp(-(d * d) / 0.18f) * max(0f, proj.pz)
                val dot = nextDot()
                dot.x = proj.px
                dot.y = proj.py
                dot.z = proj.pz
                dot.r = (GLOBE_R_BASE + GLOBE_R_DEPTH * depth + boost) * radiusScale
                dot.ink = INK_FAR - INK_SPAN * depth
                // dimBase < 1 fades un-scanned dots so the meridian reads clearly
                dot.alpha = GLOBE_DIM_BASE + (1f - GLOBE_DIM_BASE) * min(1f, boost)
            }
        }
    }

    /** Wave: two waves at different tempi roll through the rings, so it never quite repeats. */
    private fun buildWave(t: Float) {
        val c = FRAME / 2f
        // 0.76 base × 1.15: the undulation pulls the sphere inward, so wave read ~15 % smaller than the
        // other lattice modes and the library scales it back up to match them.
        val r = c * 0.874f
        proj.set(t * 0.18f, 0.38f, c, c, 1f)
        for (ri in 0..WAVE_RINGS) {
            val lat = -HALF_TURN / 2f + (ri.toFloat() / WAVE_RINGS) * HALF_TURN
            val cosLat = cos(lat)
            val sinLat = sin(lat)
            val w = 0.62f * sin(t * 2.1f - ri * 0.52f) + 0.38f * sin(t * 1.27f + ri * 0.83f)
            val rr = r * (0.88f + 0.105f * w)
            val lonCount = max(1, (abs(cosLat) * WAVE_LON_DENSITY).roundToInt())
            for (lj in 0 until lonCount) {
                val lon = (lj.toFloat() / lonCount) * FULL_TURN
                proj.project(cosLat * cos(lon) * rr, sinLat * rr, cosLat * sin(lon) * rr)
                val depth = (proj.pz / r + 1f) / 2f
                val crest = max(0f, w)
                val dot = nextDot()
                dot.x = proj.px
                dot.y = proj.py
                dot.z = proj.pz
                dot.r = (WAVE_R_BASE + WAVE_R_DEPTH * depth) * (1f + 0.4f * crest) * radiusScale
                dot.ink = 0.66f - 0.56f * depth - 0.1f * crest
                dot.alpha = 1f
            }
        }
    }

    /** Rubik: quarter-turn bands scramble the sphere, then replay in reverse until it clicks back. */
    private fun buildRubik(t: Float) {
        val c = FRAME / 2f
        proj.set(t * 0.55f, 0.35f + 0.1f * sin(t * 0.9f), c, c, c * 0.82f)
        val active = solveCycle(t)
        for (li in 0..RUBIK_RINGS) {
            val lat = -HALF_TURN / 2f + (li.toFloat() / RUBIK_RINGS) * HALF_TURN
            val cosLat = cos(lat)
            val sinLat = sin(lat)
            val lonCount = max(1, (abs(cosLat) * RUBIK_LON_DENSITY).roundToInt())
            for (lj in 0 until lonCount) {
                val lon = (lj.toFloat() / lonCount) * FULL_TURN
                applyMoves(cosLat * cos(lon), sinLat, cosLat * sin(lon), active)
                proj.project(turned.x, turned.y, turned.z)
                val depth = (proj.pz + 1f) / 2f
                val dot = nextDot()
                dot.x = proj.px
                dot.y = proj.py
                dot.z = proj.pz
                dot.r = (RUBIK_R_BASE + RUBIK_R_DEPTH * depth +
                    if (turned.inActive) RUBIK_R_ACTIVE else 0f) * radiusScale
                // the band being turned inks a touch darker — the "hand"
                dot.ink = INK_FAR - INK_SPAN * depth - if (turned.inActive) 0.14f else 0f
                dot.alpha = 1f
            }
        }
    }

    /**
     * Web: nodes drift on the sphere under slow value noise, any pair closer than [WEB_THRESHOLD] grows an
     * edge, and bright packets run along randomly re-picked pairs. The edges are stroked straight away
     * because they belong under every dot; only the dots are z-sorted.
     */
    private fun buildWeb(canvas: Canvas, t: Float) {
        val c = FRAME / 2f
        val r = c * 0.8f
        // the projector carries the radius as its scale, so node vectors stay unit-length and the distances
        // below are in unit-sphere space
        proj.set(t * 0.12f, 0.32f, c, c, r)
        for (i in 0 until WEB_NODE_N) {
            fibDir(i, WEB_NODE_N)
            val x = fib[0] + 0.3f * (vnoise(i * 0.31f + 9f, t * 0.24f) - 0.5f) * 2f
            val y = fib[1] + 0.3f * (vnoise(i * 0.53f + 27f, t * 0.21f) - 0.5f) * 2f
            val z = fib[2] + 0.3f * (vnoise(i * 0.77f + 55f, t * 0.27f) - 0.5f) * 2f
            val l = sqrt(x * x + y * y + z * z)
            nodes[i * 3] = x / l
            nodes[i * 3 + 1] = y / l
            nodes[i * 3 + 2] = z / l
        }

        // edges between close neighbours, alpha by proximity + depth
        linePaint.strokeWidth = max(0.6f, WEB_LINE_W * radiusScale)
        for (i in 0 until WEB_NODE_N) {
            for (j in i + 1 until WEB_NODE_N) {
                val dx = nodes[i * 3] - nodes[j * 3]
                val dy = nodes[i * 3 + 1] - nodes[j * 3 + 1]
                val dz = nodes[i * 3 + 2] - nodes[j * 3 + 2]
                val dist = sqrt(dx * dx + dy * dy + dz * dz)
                if (dist >= WEB_THRESHOLD) continue
                proj.project(nodes[i * 3], nodes[i * 3 + 1], nodes[i * 3 + 2])
                val x1 = proj.px
                val y1 = proj.py
                val z1 = proj.pz
                proj.project(nodes[j * 3], nodes[j * 3 + 1], nodes[j * 3 + 2])
                val depth = ((z1 + proj.pz) / 2f + 1f) / 2f
                linePaint.color = inkColor(0.42f)
                linePaint.alpha = alphaOf((1f - dist / WEB_THRESHOLD) * (0.3f + 0.55f * depth))
                canvas.drawLine(x1, y1, proj.px, proj.py, linePaint)
            }
        }

        for (i in 0 until WEB_NODE_N) {
            proj.project(nodes[i * 3], nodes[i * 3 + 1], nodes[i * 3 + 2])
            val depth = (proj.pz + 1f) / 2f
            // the nodes' own heartbeat, with the voice swelling every one of them on top of it
            val pulse = 1f + 0.25f * sin(t * 1.4f + i * 2.7f) + 0.5f * level
            val dot = nextDot()
            dot.x = proj.px
            dot.y = proj.py
            dot.z = proj.pz
            dot.r = (WEB_NODE_R + WEB_NODE_R_DEPTH * depth) * pulse * radiusScale
            dot.ink = 0.55f - 0.45f * depth
            dot.alpha = 1f
        }

        // signals: bright packets running between paired nodes
        for (s in 0 until WEB_SIGNALS) {
            val walk = t * 0.55f + s * 7.31f
            val seg = floor(walk).toInt()
            val a = floor(hashD(seg, s * 3.1 + 1.7) * WEB_NODE_N).toInt()
            val b = floor(hashD(seg, s * 5.7 + 4.2) * WEB_NODE_N).toInt()
            if (a == b) continue
            val f = walk - seg
            val x = nodes[a * 3] + (nodes[b * 3] - nodes[a * 3]) * f
            val y = nodes[a * 3 + 1] + (nodes[b * 3 + 1] - nodes[a * 3 + 1]) * f
            val z = nodes[a * 3 + 2] + (nodes[b * 3 + 2] - nodes[a * 3 + 2]) * f
            val l = max(1e-6f, sqrt(x * x + y * y + z * z))
            proj.project(x / l, y / l, z / l)
            val depth = (proj.pz + 1f) / 2f
            val dot = nextDot()
            dot.x = proj.px
            dot.y = proj.py
            dot.z = proj.pz
            dot.r = (WEB_NODE_R * 1.5f + WEB_NODE_R_DEPTH * depth) * radiusScale
            dot.ink = 0.05f
            dot.alpha = 0.5f + 0.5f * depth
        }
    }

    /**
     * The band painter behind two modes, exactly as the library shares it.
     *
     * As `ribbon` ("composing") it is an undulating sash of parallel strands riding a great circle over a
     * faint ghost sphere; the tuned preset freezes the 3D tumble, so only the travelling undulation moves.
     * With [faceOn] it becomes `ring` ("breathing", the one the library labels "Thinking…"): the camera
     * tilt is cancelled and the undulation moves onto the radius, so it reads as a ring slowly morphing
     * rather than a sash in orbit.
     *
     * The voice deepens the undulation — the one thing added to the library's tuning, and the reason a band
     * mode sits on the recording state.
     */
    private fun buildBand(
        t: Float,
        faceOn: Boolean,
        ghostN: Int,
        lanes: Int,
        segs: Int,
        rBase: Float,
        rDepth: Float,
        wobMul: Float,
    ) {
        val c = FRAME / 2f
        val r = c * 0.78f
        // spin = 0 in both presets, so the projection and the band plane below are both frozen
        proj.set(0f, CAM_TILT, c, c, 1f)
        val wob = wobMul * (1f + 1.2f * level)

        // the ghost sphere the sash rides on; the ring has none, which is what its ghostN = 0 says
        for (i in 0 until ghostN) {
            fibDir(i, ghostN)
            proj.project(fib[0] * r, fib[1] * r, fib[2] * r)
            val dot = nextDot()
            dot.x = proj.px
            dot.y = proj.py
            dot.z = proj.pz
            dot.r = 0.8f * radiusScale
            dot.ink = 0.78f
            dot.alpha = 0.1f + 0.22f * ((proj.pz / r + 1f) / 2f)
        }

        // The band plane. The projection squashes its great circle vertically by cos(ta + camTilt);
        // face-on sets ta = -camTilt so that term is 1 and the band reads as a true circle rather than
        // ribbon's tilted ellipse. With u = (1, 0, 0) the plane normal n = u × v falls out as below.
        val ta = if (faceOn) -CAM_TILT else BAND_TILT
        val vy = cos(ta)
        val vz = sin(ta)
        val ny = -vz
        val nz = vy
        // Radial lobes swell past r, so pull the base radius in by (most of) the wobble amplitude: the
        // silhouette then stays inside the frame however far the deformation is pushed. Only face-on needs
        // it — the sash's wobble is out of plane and can only ever pull dots inward.
        val baseR = if (faceOn) r / (1f + 0.85f * (0.23f * wob)) else r
        val half = (lanes - 1) / 2f
        for (w in 0 until lanes) {
            val laneOff = (w - half) * 0.075f
            val edge = abs(w - half) / max(1f, half)
            for (k in 0 until segs) {
                val a = (k.toFloat() / segs) * FULL_TURN
                // the undulation: two traveling waves along the band
                val amount =
                    (0.16f * sin(a * 3f - t * 1.7f + w * 0.22f) + 0.07f * sin(a * 5f + t * 1.1f)) * wob
                // Face-on modulates the in-plane RADIUS, so lobes genuinely swell outward and pinch
                // inward; ribbon keeps the original out-of-plane sash wobble.
                val off = if (faceOn) laneOff else laneOff + amount
                val x = cos(a)
                val y = vy * sin(a) + ny * off
                val z = vz * sin(a) + nz * off
                val l = sqrt(x * x + y * y + z * z)
                val rr = baseR * if (faceOn) 1f + amount else 1f
                proj.project(x / l * rr, y / l * rr, z / l * rr)
                val depth = (proj.pz / r + 1f) / 2f
                val dot = nextDot()
                dot.x = proj.px
                dot.y = proj.py
                dot.z = proj.pz
                dot.r = (rBase + rDepth * depth) * (1f - 0.25f * edge) * radiusScale
                dot.ink = 0.52f - 0.44f * depth + 0.18f * edge
                dot.alpha = 0.4f + 0.6f * depth
            }
        }
    }

    /**
     * The solver heartbeat: rapid eased moves scramble the sphere, then replay in reverse (a palindrome) so
     * everything clicks back to solved, rests, and repeats. Fills [solve] with each move's progress and
     * returns the move being turned right now, or -1 during the rest.
     */
    private fun solveCycle(t: Float): Int {
        val count = RUBIK_MOVES.size
        solve.fill(0f)
        val turning = 2f * count * RUBIK_SLOT_DUR
        val tc = t % (turning + RUBIK_REST)
        if (tc >= turning) return -1
        val slot = (tc / RUBIK_SLOT_DUR).toInt()
        val p = (tc - slot * RUBIK_SLOT_DUR) / RUBIK_SLOT_DUR
        val cl = min(1f, p / 0.7f)
        val ep = 1f - (1f - cl) * (1f - cl) * (1f - cl) // machine ease-out
        if (slot < count) {
            for (i in 0 until slot) solve[i] = 1f
            solve[slot] = ep
            return slot
        }
        val u = 2 * count - 1 - slot
        for (i in 0 until u) solve[i] = 1f
        solve[u] = 1f - ep
        return u
    }

    /** Turns a point through every band currently in play; the result lands in [turned]. */
    private fun applyMoves(x0: Float, y0: Float, z0: Float, active: Int) {
        var x = x0
        var y = y0
        var z = z0
        var inActive = false
        for (i in RUBIK_MOVES.indices) {
            val amount = solve[i]
            if (amount <= 0f) continue
            val mv = RUBIK_MOVES[i]
            val coord = when (mv.axis) {
                0 -> x
                1 -> y
                else -> z
            }
            if (coord < mv.lo || coord >= mv.lo + 0.5f) continue
            if (i == active) inActive = true
            val a = mv.ang * amount
            val ca = cos(a)
            val sa = sin(a)
            when (mv.axis) {
                0 -> {
                    val y2 = y * ca - z * sa
                    z = y * sa + z * ca
                    y = y2
                }
                1 -> {
                    val x2 = x * ca + z * sa
                    z = -x * sa + z * ca
                    x = x2
                }
                else -> {
                    val x2 = x * ca - y * sa
                    y = x * sa + y * ca
                    x = x2
                }
            }
        }
        turned.x = x
        turned.y = y
        turned.z = z
        turned.inActive = inActive
    }

    /** Painter: far to near, so the near side covers the far one. */
    private fun paintDots(canvas: Canvas) {
        java.util.Arrays.sort(pool, 0, used, Z_ORDER)
        for (i in 0 until used) {
            val dot = pool[i] ?: continue
            if (dot.alpha < 0.02f) continue
            dotPaint.color = inkColor(dot.ink)
            dotPaint.alpha = alphaOf(dot.alpha)
            canvas.drawCircle(dot.x, dot.y, max(R_MIN, dot.r), dotPaint)
        }
    }

    /**
     * The ink is mirrored, as the library does on a dark substrate: near dots read bright. Its grayscale
     * becomes a black → accent → white ramp so the button still carries its colour.
     */
    private fun inkColor(ink: Float): Int {
        val bright = (1f - ink).coerceIn(0f, 1f)
        return if (bright <= 0.5f) {
            ColorUtils.blendARGB(Color.BLACK, tint, bright * 2f)
        } else {
            ColorUtils.blendARGB(tint, Color.WHITE, (bright - 0.5f) * 2f)
        }
    }

    private fun alphaOf(value: Float): Int = (value * 255f).toInt().coerceIn(0, 255)

    private fun nextDot(): Dot {
        if (used == pool.size) pool = pool.copyOf(pool.size * 2)
        val dot = pool[used] ?: Dot().also { pool[used] = it }
        used++
        return dot
    }

    /** Shortest signed angular distance, wrapped to (-π, π]. */
    private fun angleDelta(a: Float, b: Float): Float = atan2(sin(a - b), cos(a - b))

    /** Stable directions on a unit sphere (Fibonacci lattice); the result lands in [fib]. */
    private val fib = FloatArray(3)

    private fun fibDir(i: Int, n: Int) {
        val y = 1f - (2f * (i + 0.5f)) / n
        val rad = sqrt(max(0f, 1f - y * y))
        val a = i * GOLDEN_ANGLE
        fib[0] = rad * cos(a)
        fib[1] = y
        fib[2] = rad * sin(a)
    }

    /** Value noise on a 2D lattice — smooth, deterministic, cheap. */
    private fun vnoise(x: Float, y: Float): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        var fx = x - xi
        var fy = y - yi
        fx = fx * fx * (3f - 2f * fx)
        fy = fy * fy * (3f - 2f * fy)
        val a = hashD(xi, yi.toDouble()).toFloat()
        val b = hashD(xi + 1, yi.toDouble()).toFloat()
        val c = hashD(xi, (yi + 1).toDouble()).toFloat()
        val d = hashD(xi + 1, (yi + 1).toDouble()).toFloat()
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy
    }

    /** One projected dot, pooled per frame so a 60 fps redraw allocates nothing. */
    private class Dot {
        var x = 0f
        var y = 0f
        var z = 0f
        var r = 0f

        /** Ink value: 0 is the darkest ink on paper, mirrored here because the body is dark. */
        var ink = 0f
        var alpha = 1f
    }

    /** One quarter-turn band of the solver: every point inside `lo`..`lo + 0.5` on [axis] turns by [ang]. */
    private class Move(val axis: Int, val lo: Float, val ang: Float)

    /** Shared spin + tilt + orthographic projection; the result is left in [px]/[py]/[pz]. */
    private class Projection {
        private var st = 0f
        private var ct = 0f
        private var sy = 0f
        private var cw = 0f
        private var ox = 0f
        private var oy = 0f
        private var scale = 1f
        var px = 0f
        var py = 0f
        var pz = 0f

        fun set(yaw: Float, tilt: Float, cx: Float, cy: Float, scale: Float) {
            st = sin(tilt)
            ct = cos(tilt)
            sy = sin(yaw)
            cw = cos(yaw)
            ox = cx
            oy = cy
            this.scale = scale
        }

        fun project(x: Float, y: Float, z: Float) {
            val x1 = x * cw + z * sy
            val z1 = -x * sy + z * cw
            val y1 = y * ct - z1 * st
            pz = y * st + z1 * ct
            px = ox + x1 * scale
            py = oy - y1 * scale
        }
    }

    /** A point after the solver's turns, plus whether it sits in the band being turned right now. */
    private class TurnedPoint {
        var x = 0f
        var y = 0f
        var z = 0f
        var inActive = false
    }

    private companion object {
        private const val FULL_TURN = 6.2831855f
        private const val HALF_TURN = 3.14159265f
        private const val GOLDEN_ANGLE = 2.3999632f // π (3 − √5)

        /** The square frame every mode is laid out in, in the units its tuning was made for. */
        private const val FRAME = 48f

        /**
         * Dot radii were tuned for a 300 pt frame and scale sub-linearly from there, which is what keeps a
         * small mark legible.
         */
        private val BASE_RADIUS_SCALE = (FRAME / 300f).pow(0.6f)

        /** Ink at the far pole and the span consumed on the way to the near one (globe and rubik). */
        private const val INK_FAR = 0.62f
        private const val INK_SPAN = 0.54f

        /** Below this a dot stops being a disc and starts flickering, so it is held here. */
        private const val R_MIN = 0.3f

        /** How much a full-scale voice hurries the shared clock along, on top of the mode's own tempo. */
        private const val LEVEL_SPEED_BOOST = 1.6f

        // Every count below is the base profile already multiplied by its preset's `count` (both sides of a
        // lattice pair by its square root), and every radius by the preset's `size`.

        private const val GLOBE_RINGS = 11
        private const val GLOBE_LON_DENSITY = 29
        private const val GLOBE_R_BASE = 0.69f
        private const val GLOBE_R_DEPTH = 1.955f

        /** How much faster than the sphere the scan meridian sweeps, and how far un-scanned dots fade. */
        private const val GLOBE_SCAN_MUL = 4.08f
        private const val GLOBE_DIM_BASE = 0.45f

        private const val WAVE_RINGS = 9
        private const val WAVE_LON_DENSITY = 23
        private const val WAVE_R_BASE = 0.6f
        private const val WAVE_R_DEPTH = 1.7f

        private const val RUBIK_RINGS = 9
        private const val RUBIK_LON_DENSITY = 24
        private const val RUBIK_R_BASE = 0.63f
        private const val RUBIK_R_DEPTH = 1.785f

        /** Extra radius on the band the solver is turning right now, and its slot/rest timing. */
        private const val RUBIK_R_ACTIVE = 0.315f
        private const val RUBIK_SLOT_DUR = 0.42f
        private const val RUBIK_REST = 1.2f

        private const val WEB_NODE_N = 41
        private const val WEB_SIGNALS = 7
        private const val WEB_THRESHOLD = 0.72f
        private const val WEB_NODE_R = 1.33f
        private const val WEB_NODE_R_DEPTH = 1.71f
        private const val WEB_LINE_W = 0.8f

        /** The band painter's camera tilt, and the fixed plane tilt the sash rides at. */
        private const val CAM_TILT = 0.3f
        private const val BAND_TILT = 0.55f

        /** Lane counts are already multiplied by each preset's bandMul. */
        private const val RING_LANES = 11
        private const val RING_SEGS = 44
        private const val RING_R_BASE = 1.0516f
        private const val RING_R_DEPTH = 1.6252f
        private const val RING_WOB_MUL = 0.368f

        private const val RIBBON_LANES = 12
        private const val RIBBON_SEGS = 44
        private const val RIBBON_GHOST_N = 38
        private const val RIBBON_R_BASE = 0.935f
        private const val RIBBON_R_DEPTH = 1.445f
        private const val RIBBON_WOB_MUL = 1f

        /** The solver's scramble, drawn once from the library's hash so every run is the same sequence. */
        private val RUBIK_MOVES = Array(14) { i ->
            Move(
                axis = min(2, floor(hashD(i, 2.3) * 3).toInt()),
                lo = (-1.0 + 0.5 * min(3, floor(hashD(i, 5.9) * 4).toInt())).toFloat(),
                ang = ((if (hashD(i, 7.7) < 0.5) 1 else -1) * Math.PI / 2).toFloat(),
            )
        }

        private val Z_ORDER = Comparator<Dot?> { a, b -> (a?.z ?: 0f).compareTo(b?.z ?: 0f) }

        /** Deterministic hash in [0, 1) — the library's, kept in double so it draws the same scramble. */
        private fun hashD(a: Int, b: Double): Double {
            val h = sin(a * 12.9898 + b * 78.233) * 43758.5453
            return h - floor(h)
        }
    }
}
