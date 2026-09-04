/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.overlay

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Which side of the screen the bubble is parked at. */
@Serializable
enum class BubbleEdge {
    LEFT,
    RIGHT;
}

/**
 * Where the floating button sits, expressed as what the user meant rather than where the pixels were
 * (issue #323).
 *
 * The position used to be a raw `(x, y)` pixel pair, which is only meaningful for the exact screen it was
 * measured on. Every device that changes its geometry underneath a running overlay therefore broke it: a
 * foldable's inner screen is wider *and* often has a different density, so a bubble parked against the
 * left edge of the cover screen reappeared somewhere in the middle of the unfolded one. A plain rotation
 * was worse — the old x could exceed the new screen width, and since the overlay window carries
 * `FLAG_LAYOUT_NO_LIMITS` nothing stopped it being drawn entirely off-screen.
 *
 * An anchor survives all of that because it stores no absolute lengths at all. It is also what the app
 * already does one layer over: the floating *keyboard* window persists its placement in dp and re-derives
 * it against the live bounds every time it is read ([dev.patrickgold.florisboard.ime.window.ImeWindowProps.Floating]).
 *
 * Deliberately free of Android types so the arithmetic is a plain JVM unit test — the same reason
 * [dev.patrickgold.florisboard.ime.text.gestures.swipeCommitDirection] lives on its own.
 *
 * @property edge The side the bubble is parked at. With snap-to-edge on (the default) this alone decides
 *  the horizontal position, which is why it is stored as a side and not as a number.
 * @property xFraction Position along the horizontal travel, 0 at the left edge and 1 at the right. Only
 *  consulted when snap-to-edge is off and the bubble may be dropped anywhere.
 * @property yFraction Position along the vertical travel, 0 at the top and 1 at the bottom.
 */
@Serializable
data class BubbleAnchor(
    val edge: BubbleEdge,
    val xFraction: Float,
    val yFraction: Float,
) {
    /**
     * The horizontal pixel position on a screen whose free travel is [maxX], keeping [margin] to the edge
     * when [snapToEdge] is on.
     *
     * The margin is subtracted from the travel rather than from the screen width, so a bubble at the right
     * edge stays the same distance from it whatever the bubble's own width happens to be.
     */
    fun toX(maxX: Int, margin: Int, snapToEdge: Boolean): Int {
        val raw = when {
            !snapToEdge -> (xFraction * maxX).roundToInt()
            edge == BubbleEdge.RIGHT -> maxX - margin
            else -> margin
        }
        return raw.coerceIn(0, maxX.coerceAtLeast(0))
    }

    /** The vertical pixel position on a screen whose free travel is [maxY]. */
    fun toY(maxY: Int): Int {
        return (yFraction * maxY).roundToInt().coerceIn(0, maxY.coerceAtLeast(0))
    }

    companion object {
        /**
         * Reads the anchor back out of a placement that has just been made by hand.
         *
         * Both fractions are measured against the *free travel* — the screen size minus the bubble's own
         * size — not against the screen. That is what keeps "parked at the very bottom" at the very bottom
         * when the bubble itself changes size: switching design or size, or the pill expanding while
         * recording, all change how much room is left, and a fraction of the raw screen height would drift
         * on every one of them.
         *
         * The side is decided at the midpoint of that same travel, which is exactly the test the drag code
         * has always used to pick which way the pill expands — `x + width / 2 >= screenWidth / 2` is the
         * same inequality as `2 * x >= maxX` once `maxX = screenWidth - width` is substituted.
         */
        fun capture(x: Int, y: Int, maxX: Int, maxY: Int): BubbleAnchor {
            return BubbleAnchor(
                edge = if (2 * x >= maxX) BubbleEdge.RIGHT else BubbleEdge.LEFT,
                xFraction = fraction(x, maxX),
                yFraction = fraction(y, maxY),
            )
        }

        /**
         * The anchor a bubble carries before it has ever been placed: right edge, about 40% down.
         *
         * The real first placement is made in pixels once the bubble has been measured — it needs the
         * bubble's own size to keep its margin to the edge — and is [capture]d from there like any other
         * placement. This is what stands in until that happens, and it names the same spot, so a geometry
         * change arriving in that window puts the bubble where it was going to go anyway.
         */
        val Default = BubbleAnchor(BubbleEdge.RIGHT, xFraction = 1f, yFraction = 0.4f)

        /**
         * A position as a share of the room it had to move in. A screen with no room left (the bubble is
         * as large as the screen, or it has not been measured yet) has only one possible position, so the
         * share is 0 rather than a division by zero.
         */
        private fun fraction(value: Int, max: Int): Float {
            if (max <= 0) return 0f
            return (value.toFloat() / max.toFloat()).coerceIn(0f, 1f)
        }
    }
}
