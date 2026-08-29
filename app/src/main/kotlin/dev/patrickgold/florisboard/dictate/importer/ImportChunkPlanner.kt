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

/**
 * Where to cut a long import into pieces small enough to upload (issue #301).
 *
 * A file over the provider's ceiling used to be refused outright. It does not have to be: the VAD
 * from #93 already says where the speech is, and a recording too big to send is almost never too big
 * without pauses in it. This decides which of those pauses to cut at.
 *
 * Deliberately pure — no Android, no files, no VAD. Everything interesting here is arithmetic and
 * edge cases, and it is the only part of the import path that can be checked without a phone.
 */
object ImportChunkPlanner {

    /**
     * Groups [segments] into as few pieces as possible, each at most [maxSamples] long.
     *
     * Cuts land **only in the silence between two speech segments**, never inside one: a word split
     * across two uploads is transcribed wrong twice rather than right once, and no amount of joining
     * afterwards repairs it. Each cut sits halfway through its gap, so a piece keeps a little air on
     * both sides instead of starting abruptly on a consonant. The pieces tile the whole file — the
     * first starts at zero and the last ends at [totalSamples] — so nothing between the speech is
     * dropped either.
     *
     * @param segments speech ranges in samples, end-inclusive, as `SpeechGate` reports them.
     * @param totalSamples length of the decoded audio.
     * @param maxSamples budget for one piece.
     * @return ascending, non-overlapping ranges. Empty when there is no speech at all: an empty
     *   upload is worse than none. **A single segment longer than the budget yields one oversized
     *   piece** — that is the one case the budget cannot be honoured without cutting through speech,
     *   and the caller is expected to notice rather than to have been quietly obeyed.
     */
    fun plan(segments: List<IntRange>, totalSamples: Int, maxSamples: Int): List<IntRange> {
        if (totalSamples <= 0 || maxSamples <= 0) return emptyList()
        val speech = segments
            .filter { it.last >= it.first && it.first < totalSamples }
            .map { it.first.coerceAtLeast(0)..it.last.coerceAtMost(totalSamples - 1) }
            .sortedBy { it.first }
        if (speech.isEmpty()) return emptyList()

        // Where each segment's piece would end if it were the last one in it: halfway into the
        // following gap, or the end of the audio for the final segment.
        val cutAfter = IntArray(speech.size) { i ->
            if (i == speech.lastIndex) totalSamples else midpoint(speech[i], speech[i + 1])
        }

        val pieces = ArrayList<IntRange>()
        var start = 0
        var heldSegments = 0
        for (i in speech.indices) {
            // Adding this segment would burst the budget — close the piece before it, unless the piece
            // is still empty, in which case there is nothing to close and the segment is oversized.
            if (heldSegments > 0 && cutAfter[i] - start > maxSamples) {
                pieces.add(start until cutAfter[i - 1])
                start = cutAfter[i - 1]
                heldSegments = 0
            }
            heldSegments++
            if (i == speech.lastIndex) pieces.add(start until totalSamples)
        }
        return pieces.filter { it.last >= it.first }
    }

    /** The middle of the silence between two speech segments — the only place a cut belongs. */
    private fun midpoint(before: IntRange, after: IntRange): Int {
        val gapStart = before.last + 1
        val gapEnd = after.first
        return if (gapEnd > gapStart) (gapStart + gapEnd) / 2 else gapStart
    }
}
