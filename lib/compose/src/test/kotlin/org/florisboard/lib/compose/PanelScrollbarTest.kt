/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.florisboard.lib.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scrollbar's row arithmetic (issue #308).
 *
 * A grid with section headers is not a uniform grid, and the old model assumed it was: rows counted
 * as `items / columns`, pitch taken from the first gap between visible rows. Both are wrong when a
 * full-span header is on screen, and the visible result was a thumb that filled the track at the top
 * of a sectioned grid and then sat short and stranded up there once the user had scrolled to the
 * bottom. These cases are the arithmetic that must not drift back.
 */
class PanelScrollbarTest {

    // --- the pitch ---------------------------------------------------------------------------------

    /**
     * The failure this whole change is about. A header row is shorter than a row of cells, so with a
     * header on screen the *first* gap is the header's height — and taking it makes the content
     * measure as a fraction of its true height, which is what inflates the thumb.
     */
    @Test
    fun `a short header row does not become the pitch`() {
        // header at 0 (40 tall), then cell rows every 200
        val rowTops = listOf(0, 40, 240, 440, 640)
        assertEquals(200f, rowPitchOf(rowTops, tallest = 200))
    }

    @Test
    fun `an even grid measures its own pitch`() {
        assertEquals(200f, rowPitchOf(listOf(0, 200, 400, 600), tallest = 190))
    }

    /** Two sections on screen means two header gaps; the cell pitch is still the common one. */
    @Test
    fun `two headers still leave the cell pitch in the majority`() {
        val rowTops = listOf(0, 40, 240, 440, 480, 680, 880)
        assertEquals(200f, rowPitchOf(rowTops, tallest = 200))
    }

    /**
     * With one header and one row of cells the two gaps are equally common. The tie goes to the
     * larger, because the cell pitch is the one that describes the content — the alternative is the
     * balloon this test exists to prevent.
     */
    @Test
    fun `a tie goes to the larger gap`() {
        assertEquals(200f, rowPitchOf(listOf(0, 40, 240), tallest = 200))
    }

    @Test
    fun `a single visible row falls back to its height`() {
        assertEquals(160f, rowPitchOf(listOf(120), tallest = 160))
        assertEquals(0f, rowPitchOf(emptyList(), tallest = 0))
    }

    // --- the geometry ------------------------------------------------------------------------------

    @Test
    fun `an even grid measures exactly`() {
        // 20 items, 4 columns, 5 rows of 100; showing rows 0-1, not scrolled.
        val m = rowMetrics(
            totalItems = 20, columns = 4,
            firstRow = 0, firstOffset = 0, lastRow = 1, lastIndex = 7, pitch = 100f,
        )!!
        assertEquals(500f, m.contentHeight)
        assertEquals(0f, m.scrolled)
    }

    /**
     * The row of the last laid-out item is known exactly, so the headers *above* the fold are already
     * paid for. Only what is below is extrapolated, and counting those items as cells is the estimate
     * — good to within a fraction of a row.
     */
    @Test
    fun `headers above the fold are counted, not guessed`() {
        // A header, then 8 cells in 4 columns: rows 0 (header), 1, 2. Last visible = index 8, row 2.
        val m = rowMetrics(
            totalItems = 9, columns = 4,
            firstRow = 0, firstOffset = 0, lastRow = 2, lastIndex = 8, pitch = 100f,
        )!!
        assertEquals(300f, m.contentHeight)
    }

    /** Scrolled to the end: the thumb must be able to reach the bottom of the track. */
    @Test
    fun `at the bottom the progress reaches one`() {
        val viewport = 400f
        // 5 rows of 100 = 500 tall in a 400 viewport; the last row starts at 400.
        val m = rowMetrics(
            totalItems = 20, columns = 4,
            firstRow = 1, firstOffset = 0, lastRow = 4, lastIndex = 19, pitch = 100f,
        )!!
        assertEquals(500f, m.contentHeight)
        assertEquals(100f, m.scrolled)
        assertEquals(1f, m.scrolled / (m.contentHeight - viewport))
    }

    @Test
    fun `the offset within a row moves the thumb between rows`() {
        val m = rowMetrics(
            totalItems = 20, columns = 4,
            firstRow = 2, firstOffset = 37, lastRow = 4, lastIndex = 19, pitch = 100f,
        )!!
        assertEquals(237f, m.scrolled)
    }

    @Test
    fun `nothing to draw is reported as nothing`() {
        assertNull(rowMetrics(0, 4, 0, 0, 0, 0, 100f))
        assertNull(rowMetrics(20, 4, 0, 0, 1, 7, 0f))
    }

    /** A column count of zero would divide by zero; an unmeasured grid must not crash the draw. */
    @Test
    fun `an unmeasured column count is treated as one`() {
        val m = rowMetrics(
            totalItems = 3, columns = 0,
            firstRow = 0, firstOffset = 0, lastRow = 0, lastIndex = 0, pitch = 100f,
        )!!
        assertTrue(m.contentHeight > 0f)
    }
}
