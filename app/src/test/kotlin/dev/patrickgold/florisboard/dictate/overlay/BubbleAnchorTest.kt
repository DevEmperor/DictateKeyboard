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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Carrying the floating button's position from one screen geometry to another (issue #323).
 *
 * The measurements are the ones from the report: a foldable whose three working layouts are a narrow
 * cover screen, a wide inner screen, and that inner screen turned sideways. The bubble is 140 px and
 * parks 48 px from the edge, both roughly what the stock settings produce.
 */
class BubbleAnchorTest {

    private val bubble = 140
    private val margin = 48

    /** Free horizontal travel on a screen [width] px wide. */
    private fun maxX(width: Int) = width - bubble

    /** Free vertical travel on a screen [height] px tall. */
    private fun maxY(height: Int) = height - bubble

    private val coverW = 1248
    private val coverH = 1972
    private val innerW = 1848
    private val innerH = 2448

    /**
     * The reported failure. A bubble parked against the right edge of the narrow cover screen kept that
     * exact x on the much wider inner screen, which put it near the middle — the position is only
     * meaningful for the screen it was measured on.
     */
    @Test
    fun `a bubble parked at an edge stays at that edge on a wider screen`() {
        val parkedX = maxX(coverW) - margin
        val anchor = BubbleAnchor.capture(parkedX, 700, maxX(coverW), maxY(coverH))

        assertEquals(BubbleEdge.RIGHT, anchor.edge)
        // What the old pixel pair did: 1060 on a 1848 px screen is the middle, not the edge.
        assertTrue(parkedX in (innerW / 3)..(innerW * 2 / 3), "precondition: the old value lands mid-screen")
        assertEquals(maxX(innerW) - margin, anchor.toX(maxX(innerW), margin, snapToEdge = true))
    }

    /** The same in the direction the reporter actually wants: left stays left, however wide the screen. */
    @Test
    fun `the left edge survives every geometry`() {
        val anchor = BubbleAnchor.capture(margin, 700, maxX(coverW), maxY(coverH))

        assertEquals(BubbleEdge.LEFT, anchor.edge)
        assertEquals(margin, anchor.toX(maxX(innerW), margin, snapToEdge = true))
        assertEquals(margin, anchor.toX(maxX(innerH), margin, snapToEdge = true))
    }

    /**
     * The failure nobody reported: turning a landscape screen upright leaves an x that is larger than the
     * new width, and the overlay window has FLAG_LAYOUT_NO_LIMITS — so the bubble was simply drawn off the
     * side of the screen and stayed there. An anchor cannot express a position that is not on the screen.
     */
    @Test
    fun `a landscape position never lands off a portrait screen`() {
        val landscapeMaxX = maxX(innerH) // the long side is now the width
        val parkedX = landscapeMaxX - margin
        val anchor = BubbleAnchor.capture(parkedX, 400, landscapeMaxX, maxY(innerW))

        assertTrue(parkedX > maxX(innerW), "precondition: the old value is off the upright screen")
        val x = anchor.toX(maxX(innerW), margin, snapToEdge = true)
        assertTrue(x in 0..maxX(innerW), "expected an on-screen x, got $x")
    }

    /** Top, middle and bottom are the placements a user can name, so they have to survive the change. */
    @Test
    fun `vertical placement is carried over proportionally`() {
        val top = BubbleAnchor.capture(0, 0, maxX(coverW), maxY(coverH))
        val bottom = BubbleAnchor.capture(0, maxY(coverH), maxX(coverW), maxY(coverH))
        val middle = BubbleAnchor.capture(0, maxY(coverH) / 2, maxX(coverW), maxY(coverH))

        assertEquals(0, top.toY(maxY(innerH)))
        assertEquals(maxY(innerH), bottom.toY(maxY(innerH)))
        assertNear(maxY(innerH) / 2, middle.toY(maxY(innerH)))
    }

    /**
     * Re-applying an anchor on the screen it was taken from must not move the bubble. Geometry events
     * arrive in bursts on a foldable — several configuration changes for one hinge movement — and a rule
     * that drifts by a pixel each time would walk the bubble across the screen.
     */
    @Test
    fun `capturing and re-applying on the same screen is a no-op`() {
        for (x in listOf(0, 137, 604, maxX(innerW))) {
            for (y in listOf(0, 91, 1203, maxY(innerH))) {
                val anchor = BubbleAnchor.capture(x, y, maxX(innerW), maxY(innerH))
                assertEquals(x, anchor.toX(maxX(innerW), margin, snapToEdge = false))
                assertEquals(y, anchor.toY(maxY(innerH)))
            }
        }
    }

    /** With snapping on the side wins outright; with it off the bubble keeps its share of the width. */
    @Test
    fun `snap-to-edge decides whether the horizontal share is consulted`() {
        val anchor = BubbleAnchor.capture(maxX(coverW) / 4, 0, maxX(coverW), maxY(coverH))

        assertEquals(BubbleEdge.LEFT, anchor.edge)
        assertEquals(margin, anchor.toX(maxX(innerW), margin, snapToEdge = true))
        assertNear(maxX(innerW) / 4, anchor.toX(maxX(innerW), margin, snapToEdge = false))
    }

    /**
     * A bubble that has not been measured yet has no travel to be a fraction of. This is reachable in
     * practice: the window is added before the first layout pass, so a geometry change can arrive while
     * the view still has zero size.
     */
    @Test
    fun `a screen with no room left is not a division by zero`() {
        val anchor = BubbleAnchor.capture(0, 0, 0, 0)

        assertEquals(0f, anchor.xFraction)
        assertEquals(0f, anchor.yFraction)
        assertEquals(0, anchor.toX(0, margin, snapToEdge = true))
        assertEquals(0, anchor.toY(0))
    }

    /** The unplaced default is the old hard-coded spot: right edge, about 40% down. */
    @Test
    fun `the default anchor is the right edge at two fifths height`() {
        assertEquals(BubbleEdge.RIGHT, BubbleAnchor.Default.edge)
        assertEquals(maxX(innerW) - margin, BubbleAnchor.Default.toX(maxX(innerW), margin, snapToEdge = true))
        assertNear((maxY(innerH) * 0.4f).toInt(), BubbleAnchor.Default.toY(maxY(innerH)))
    }
}

/**
 * Equality within a pixel. The anchor rounds a float back to a pixel, so a value that is conceptually
 * "half way down" can land one either side of the exact half — kotlin.test only offers a tolerance for
 * floating point, and comparing the floats instead would test the arithmetic rather than the placement.
 */
private fun assertNear(expected: Int, actual: Int) {
    assertTrue(kotlin.math.abs(expected - actual) <= 1, "expected $expected ± 1, got $actual")
}
