/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where a long import may be cut (issue #301).
 *
 * Two properties carry the whole feature. A cut must never fall inside speech — a word split across
 * two uploads comes back wrong twice, and joining the halves afterwards cannot repair it. And the
 * pieces must tile the file exactly, because anything between them is audio nobody will ever hear
 * again: it was silently not sent.
 */
class ImportChunkPlannerTest {

    /** Asserts the two invariants that hold for every plan, whatever the input. */
    private fun assertWellFormed(pieces: List<IntRange>, segments: List<IntRange>, total: Int) {
        if (pieces.isEmpty()) return
        assertEquals(0, pieces.first().first, "the first piece must start at the beginning")
        assertEquals(total - 1, pieces.last().last, "the last piece must run to the end")
        pieces.zipWithNext { a, b ->
            assertEquals(a.last + 1, b.first, "pieces must tile without a gap or an overlap")
        }
        for (seg in segments) {
            val holder = pieces.count { seg.first >= it.first && seg.last <= it.last }
            assertEquals(1, holder, "segment $seg must sit whole inside exactly one piece")
        }
    }

    @Test
    fun `a file that fits stays in one piece`() {
        val segments = listOf(0..999, 2000..2999, 4000..4999)
        val pieces = ImportChunkPlanner.plan(segments, totalSamples = 6000, maxSamples = 10_000)
        assertEquals(listOf(0 until 6000), pieces)
        assertWellFormed(pieces, segments, 6000)
    }

    @Test
    fun `a cut lands in the middle of the silence, not in a word`() {
        // Two seconds of speech, a long pause, two more. Budget forces exactly one cut.
        val segments = listOf(0..1999, 8000..9999)
        val pieces = ImportChunkPlanner.plan(segments, totalSamples = 10_000, maxSamples = 6000)
        // The gap runs 2000..7999 — half of it is 5000, and that is where the cut belongs.
        assertEquals(listOf(0 until 5000, 5000 until 10_000), pieces)
        assertWellFormed(pieces, segments, 10_000)
    }

    @Test
    fun `many segments are packed into as few pieces as the budget allows`() {
        val segments = (0 until 10).map { it * 1000..it * 1000 + 499 }
        val pieces = ImportChunkPlanner.plan(segments, totalSamples = 10_000, maxSamples = 3000)
        assertTrue(pieces.size in 4..5, "expected about four pieces, got ${pieces.size}: $pieces")
        for (p in pieces.dropLast(1)) {
            assertTrue(p.last - p.first + 1 <= 3000, "piece $p is over budget")
        }
        assertWellFormed(pieces, segments, 10_000)
    }

    @Test
    fun `a single segment over the budget becomes one oversized piece rather than being cut`() {
        // Nothing can be honoured here without cutting through speech, so the budget loses — and the
        // caller gets one piece it can recognise as too large instead of a mangled transcript.
        val segments = listOf(0..9999)
        val pieces = ImportChunkPlanner.plan(segments, totalSamples = 10_000, maxSamples = 3000)
        assertEquals(listOf(0 until 10_000), pieces)
        assertWellFormed(pieces, segments, 10_000)
    }

    @Test
    fun `silence alone produces nothing to send`() {
        assertEquals(emptyList(), ImportChunkPlanner.plan(emptyList(), 10_000, 3000))
    }

    @Test
    fun `nonsense bounds produce nothing rather than a crash`() {
        assertEquals(emptyList(), ImportChunkPlanner.plan(listOf(0..99), totalSamples = 0, maxSamples = 3000))
        assertEquals(emptyList(), ImportChunkPlanner.plan(listOf(0..99), totalSamples = 1000, maxSamples = 0))
    }

    @Test
    fun `segments arriving out of order or past the end are still tiled correctly`() {
        val segments = listOf(6000..6999, 0..999, 3000..3999, 9000..12_000)
        val pieces = ImportChunkPlanner.plan(segments, totalSamples = 10_000, maxSamples = 4000)
        // The last segment is clamped to the file, so it is 9000..9999 for the purposes of the plan.
        assertWellFormed(pieces, listOf(0..999, 3000..3999, 6000..6999, 9000..9999), 10_000)
    }
}
