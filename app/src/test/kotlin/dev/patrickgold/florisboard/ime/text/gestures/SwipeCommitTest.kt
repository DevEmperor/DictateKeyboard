/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.text.gestures

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * When a swipe on a character key may be acted on without waiting for the finger to lift (issue #327).
 *
 * Travel arrives in quarter-thresholds, so with the stock 32 dp setting one unit is 8 dp and the commit
 * distance of 6 units is 48 dp — wider than a key. The tests below are about the two ways this rule can
 * be wrong: firing on a drift that was never meant as a swipe, and refusing one that plainly was.
 */
class SwipeCommitTest {

    @Test
    fun `a short drift commits to nothing`() {
        assertNull(swipeCommitDirection(3, 0))
        assertNull(swipeCommitDirection(0, -5))
    }

    @Test
    fun `travelling far enough sideways commits to that side`() {
        assertEquals(SwipeGesture.Direction.RIGHT, swipeCommitDirection(6, 0))
        assertEquals(SwipeGesture.Direction.LEFT, swipeCommitDirection(-7, 1))
    }

    @Test
    fun `travelling far enough up or down commits to that direction`() {
        assertEquals(SwipeGesture.Direction.DOWN, swipeCommitDirection(0, 6))
        assertEquals(SwipeGesture.Direction.UP, swipeCommitDirection(-2, -9))
    }

    /**
     * The one that keeps a reach across the keyboard from switching the language. A diagonal is exactly
     * the shape of a thumb stretching for a far key, and the eight-sector rule used at lift-off would
     * happily call this one a LEFT.
     */
    @Test
    fun `a diagonal commits to nothing however far it goes`() {
        assertNull(swipeCommitDirection(20, 20))
        assertNull(swipeCommitDirection(-30, 25))
    }

    /** Right on the boundary the axis still has to win by the full margin, not merely be ahead. */
    @Test
    fun `being slightly ahead on one axis is not enough`() {
        assertNull(swipeCommitDirection(7, 6))
        assertEquals(SwipeGesture.Direction.RIGHT, swipeCommitDirection(9, 6))
    }

    /**
     * The commit distance has to sit above the lift-off path's own distance gate — 4 units, one whole
     * threshold. Committing at or below that would make the slower, unchecked path fire on everything
     * the faster, speed-checked one was meant to judge.
     */
    @Test
    fun `the commit distance stays above the lift-off distance gate`() {
        assertNull(swipeCommitDirection(4, 0))
        assertNull(swipeCommitDirection(5, 0))
        assertEquals(SwipeGesture.Direction.RIGHT, swipeCommitDirection(SWIPE_COMMIT_UNITS, 0))
    }

    /** Nothing moved at all is the commonest case of all — an ordinary tap. */
    @Test
    fun `a still finger commits to nothing`() {
        assertNull(swipeCommitDirection(0, 0))
    }
}
