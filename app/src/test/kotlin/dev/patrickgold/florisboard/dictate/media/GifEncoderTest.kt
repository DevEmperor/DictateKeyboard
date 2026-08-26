/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.media

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The GIF writer, checked by reading its output back.
 *
 * A hand-written LZW stream is either exactly right or complete noise, and nothing in between is
 * visible by inspection — so every test here decodes what was encoded with an independent reader
 * rather than asserting on bytes. The JVM has one, which is the whole reason this class was kept free
 * of Android types.
 */
class GifEncoderTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun readFrames(bytes: ByteArray): List<java.awt.image.BufferedImage> {
        val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = stream
        return (0 until reader.getNumImages(true)).map { reader.read(it) }
    }

    @Test
    fun `two frames come back as two frames of the right size`() {
        val red = argb(255, 220, 30, 30)
        val blue = argb(255, 30, 30, 220)
        val frames = listOf(
            GifEncoder.Frame(IntArray(16) { red }, delayMs = 100),
            GifEncoder.Frame(IntArray(16) { blue }, delayMs = 100),
        )
        val bytes = GifEncoder.encode(4, 4, frames)
        assertNotNull(bytes)
        assertTrue(bytes.size > 20)
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))

        val decoded = readFrames(bytes)
        assertEquals(2, decoded.size)
        assertEquals(4, decoded[0].width)
        assertEquals(4, decoded[0].height)
    }

    @Test
    fun `colours survive within the resolution of a five-bit palette`() {
        val orange = argb(255, 240, 130, 20)
        val frames = listOf(GifEncoder.Frame(IntArray(64) { orange }, delayMs = 80))
        val bytes = GifEncoder.encode(8, 8, frames)
        assertNotNull(bytes)

        val pixel = readFrames(bytes)[0].getRGB(3, 3)
        // Colours are counted in five bits per channel, so the answer is close rather than equal.
        assertTrue(abs(((pixel shr 16) and 0xFF) - 240) <= 12)
        assertTrue(abs(((pixel shr 8) and 0xFF) - 130) <= 12)
        assertTrue(abs((pixel and 0xFF) - 20) <= 12)
    }

    @Test
    fun `a transparent pixel comes back transparent`() {
        val opaque = argb(255, 10, 200, 90)
        val clear = argb(0, 0, 0, 0)
        val pixels = IntArray(16) { if (it < 8) opaque else clear }
        val bytes = GifEncoder.encode(4, 4, listOf(GifEncoder.Frame(pixels, delayMs = 60)))
        assertNotNull(bytes)

        // Asserted on the palette index rather than on the decoded pixel's alpha: what has to be true
        // is that the file names a transparent index and that the pixel uses it. Whether a reader
        // then hands back a see-through pixel or the background it was told to restore to is the
        // reader's business, and Java's does the latter.
        val image = readFrames(bytes)[0]
        val model = image.colorModel as java.awt.image.IndexColorModel
        assertTrue(model.transparentPixel >= 0, "the GIF declares no transparent index")
        assertEquals(model.transparentPixel, image.raster.getSample(0, 3, 0))
        assertTrue(image.raster.getSample(0, 0, 0) != model.transparentPixel)
    }

    @Test
    fun `more colours than a palette holds still encode`() {
        // 4096 distinct colours through one 64x64 frame: the median cut has to reduce them, and the
        // LZW stream has to stay readable while doing it.
        val pixels = IntArray(64 * 64) { i ->
            argb(255, (i * 7) % 256, (i * 13) % 256, (i * 29) % 256)
        }
        val bytes = GifEncoder.encode(64, 64, listOf(GifEncoder.Frame(pixels, delayMs = 40)))
        assertNotNull(bytes)

        val image = readFrames(bytes)[0]
        assertEquals(64, image.width)
        assertEquals(64, image.height)
    }

    @Test
    fun `a long run of one colour compresses far below its pixel count`() {
        // The point of LZW: 40000 identical pixels must not cost 40000 bytes. A stream that failed to
        // build its dictionary would still decode, so size is the only thing that catches it.
        val pixels = IntArray(200 * 200) { argb(255, 12, 34, 56) }
        val bytes = GifEncoder.encode(200, 200, listOf(GifEncoder.Frame(pixels, delayMs = 50)))
        assertNotNull(bytes)
        assertTrue(bytes.size < 4000, "expected a compressed stream, got ${bytes.size} bytes")
        assertEquals(200, readFrames(bytes)[0].width)
    }

    @Test
    fun `a pattern comes back pixel for pixel`() {
        // The test that earns its keep. Uniform pictures decode even from a stream whose code width
        // grows one entry early, because every code says the same thing; a pattern large enough to
        // fill the dictionary does not, and this caught exactly that.
        val palette = intArrayOf(
            argb(255, 200, 20, 20), argb(255, 20, 200, 20),
            argb(255, 20, 20, 200), argb(255, 200, 200, 20),
        )
        val size = 96
        val pixels = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            palette[((x / 3) + (y / 5) + (x * y) / 37) % palette.size]
        }
        val bytes = GifEncoder.encode(size, size, listOf(GifEncoder.Frame(pixels, delayMs = 40)))
        assertNotNull(bytes)

        val image = readFrames(bytes)[0]
        for (y in 0 until size) {
            for (x in 0 until size) {
                val expected = pixels[y * size + x]
                val actual = image.getRGB(x, y)
                val distance = abs(((actual shr 16) and 0xFF) - ((expected shr 16) and 0xFF)) +
                    abs(((actual shr 8) and 0xFF) - ((expected shr 8) and 0xFF)) +
                    abs((actual and 0xFF) - (expected and 0xFF))
                assertTrue(distance <= 24, "pixel $x,$y came back as ${actual.toString(16)}")
            }
        }
    }

    @Test
    fun `nothing to encode is refused rather than written`() {
        assertEquals(null, GifEncoder.encode(0, 4, listOf(GifEncoder.Frame(IntArray(0), 100))))
        assertEquals(null, GifEncoder.encode(4, 4, emptyList()))
        // A frame whose pixel count does not match the canvas is a caller's mistake, not a picture.
        assertEquals(null, GifEncoder.encode(4, 4, listOf(GifEncoder.Frame(IntArray(9), 100))))
    }
}
