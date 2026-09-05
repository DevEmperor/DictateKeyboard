/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.clipboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How large a clipboard preview is allowed to get before it is decoded (issue #316).
 *
 * The panel used to decode every image at full resolution, which for a phone screenshot is a bitmap
 * of tens of megabytes — per visible cell, on the composing thread. These two functions are the part
 * of the fix that can be reasoned about without a device, so they are the part that gets tested.
 */
class ThumbnailSamplingTest {

    private val max = 1024

    @Test
    fun `leaves an image that already fits alone`() {
        assertEquals(1, thumbnailSampleSize(800, 600, max))
        assertEquals(1, thumbnailSampleSize(max, max, max))
    }

    @Test
    fun `shrinks a phone screenshot to something a grid cell can hold`() {
        // A 1080x2340 screenshot, the format both #316 screenshots were taken in.
        val sampleSize = thumbnailSampleSize(1080, 2340, max)
        assertEquals(4, sampleSize)
        assertTrue(2340 / sampleSize <= max)
    }

    @Test
    fun `always returns a power of two`() {
        for (edge in listOf(1025, 2048, 4000, 8000, 12_000)) {
            val sampleSize = thumbnailSampleSize(edge, edge, max)
            assertEquals(0, sampleSize and (sampleSize - 1), "expected a power of two for $edge")
        }
    }

    @Test
    fun `never shrinks more than it has to`() {
        // Halving once more would waste detail the cell can still show.
        val sampleSize = thumbnailSampleSize(4000, 3000, max)
        assertTrue(4000 / (sampleSize / 2) > max, "sample size $sampleSize is one step too coarse")
    }

    @Test
    fun `asks for no shrinking when the bounds could not be read`() {
        // BitmapFactory reports 0 for a file it cannot decode. Guessing a factor there would only
        // make the failure that follows quieter.
        assertEquals(1, thumbnailSampleSize(0, 0, max))
        assertEquals(1, thumbnailSampleSize(-1, -1, max))
    }

    @Test
    fun `keeps the aspect ratio when sizing a video thumbnail`() {
        val (width, height) = thumbnailSize(1920, 1080, max)
        assertEquals(max, width)
        assertEquals(576, height)
    }

    @Test
    fun `leaves a small video at its own size`() {
        assertEquals(640 to 480, thumbnailSize(640, 480, max))
    }

    @Test
    fun `never sizes an extreme aspect ratio down to nothing`() {
        // A 12000x1 panorama scaled to fit 1024 would round the short edge to zero pixels, and
        // createVideoThumbnail throws on a zero dimension.
        val (width, height) = thumbnailSize(12_000, 1, max)
        assertTrue(width in 1..max)
        assertTrue(height >= 1)
    }

    @Test
    fun `falls back to a square when the video reports no size`() {
        assertEquals(max to max, thumbnailSize(0, 0, max))
    }
}
