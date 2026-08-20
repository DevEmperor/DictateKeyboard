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

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The byte-level half of re-encoding an animated sticker.
 *
 * This is where a mistake is invisible until a chat shows an empty frame: every field in the WebP
 * container is stored in a slightly awkward way — offsets in units of two pixels, sizes minus one,
 * durations in 24 bits, chunks padded to an even length. Writing and reading them back is therefore
 * pinned here rather than discovered on a device.
 */
class WebPContainerTest {

    /** A frame payload shaped like the real thing: a named chunk with a body. */
    private fun bitstream(fourCC: String, body: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(fourCC.toByteArray(Charsets.US_ASCII))
        for (i in 0 until 4) out.write((body.size shr (8 * i)) and 0xFF)
        out.write(body)
        if (body.size and 1 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun animation(vararg frames: WebPContainer.Frame) = WebPContainer.Animation(
        canvasWidth = 512,
        canvasHeight = 512,
        loopCount = 0,
        backgroundColor = 0,
        frames = frames.toList(),
    )

    @Test
    fun `an animation survives being written and read back`() {
        val first = WebPContainer.Frame(
            x = 0, y = 0, width = 512, height = 512, durationMs = 120,
            disposeToBackground = false, doNotBlend = true,
            bitstream = bitstream("VP8L", ByteArray(7) { it.toByte() }),
        )
        val second = WebPContainer.Frame(
            x = 4, y = 8, width = 256, height = 128, durationMs = 999,
            disposeToBackground = true, doNotBlend = false,
            bitstream = bitstream("VP8 ", ByteArray(4) { (it + 9).toByte() }),
        )
        val bytes = WebPContainer.mux(animation(first, second))
        val back = WebPContainer.demux(bytes)!!

        assertEquals(512, back.canvasWidth)
        assertEquals(512, back.canvasHeight)
        assertEquals(2, back.frames.size)

        assertEquals(0, back.frames[0].x)
        assertEquals(512, back.frames[0].width)
        assertEquals(120, back.frames[0].durationMs)
        assertTrue(back.frames[0].doNotBlend)
        assertContentEquals(first.bitstream, back.frames[0].bitstream)

        // Offsets are stored in two-pixel units, so an odd offset could not survive — 4 and 8 can.
        assertEquals(4, back.frames[1].x)
        assertEquals(8, back.frames[1].y)
        assertEquals(256, back.frames[1].width)
        assertEquals(128, back.frames[1].height)
        // A duration of 999 ms exercises the 24-bit field beyond one byte.
        assertEquals(999, back.frames[1].durationMs)
        assertTrue(back.frames[1].disposeToBackground)
        assertContentEquals(second.bitstream, back.frames[1].bitstream)
    }

    @Test
    fun `an odd-sized frame is padded and the next chunk is still found`() {
        // Five bytes of payload forces the padding byte; without it the reader loses its place and
        // every frame after the first disappears.
        val odd = WebPContainer.Frame(
            x = 0, y = 0, width = 8, height = 8, durationMs = 40,
            disposeToBackground = false, doNotBlend = false,
            bitstream = bitstream("VP8 ", ByteArray(5)),
        )
        val even = odd.copy(durationMs = 60, bitstream = bitstream("VP8 ", ByteArray(6)))
        val back = WebPContainer.demux(WebPContainer.mux(animation(odd, even)))!!
        assertEquals(listOf(40, 60), back.frames.map { it.durationMs })
    }

    @Test
    fun `the alpha flag is set when a frame carries one`() {
        val withAlpha = WebPContainer.Frame(
            x = 0, y = 0, width = 512, height = 512, durationMs = 40,
            disposeToBackground = false, doNotBlend = true,
            bitstream = bitstream("ALPH", ByteArray(4)) + bitstream("VP8 ", ByteArray(4)),
        )
        val bytes = WebPContainer.mux(animation(withAlpha))
        // VP8X payload starts 20 bytes in: RIFF(4) + size(4) + WEBP(4) + fourcc(4) + size(4).
        val flags = bytes[20].toInt() and 0xFF
        assertTrue(flags and 0x10 != 0, "alpha flag must be set")
        assertTrue(flags and 0x02 != 0, "animation flag must be set")
    }

    @Test
    fun `a still image is not an animation`() {
        val still = java.io.ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            repeat(4) { write(0) }
            write("WEBP".toByteArray(Charsets.US_ASCII))
            write(bitstream("VP8 ", ByteArray(16)))
        }.toByteArray()
        assertNull(WebPContainer.demux(still))
        assertNull(WebPContainer.demux(ByteArray(4)))
        assertNull(WebPContainer.demux("this is not an image file at all".toByteArray()))
    }

    @Test
    fun `a frame bitstream keeps alpha and the picture, and drops the rest`() {
        val alph = bitstream("ALPH", ByteArray(3) { 7 })
        val vp8 = bitstream("VP8 ", ByteArray(4) { 9 })
        val exif = bitstream("EXIF", ByteArray(8))
        val still = java.io.ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            repeat(4) { write(0) }
            write("WEBP".toByteArray(Charsets.US_ASCII))
            write(bitstream("VP8X", ByteArray(10)))
            write(exif)
            write(alph)
            write(vp8)
        }.toByteArray()

        val extracted = WebPContainer.frameBitstreamOf(still)!!
        // Alpha first, then the picture, and no trace of the metadata or the still header.
        assertContentEquals(alph + vp8, extracted)
    }

    @Test
    fun `a file with no picture chunk yields no bitstream`() {
        val onlyAlpha = java.io.ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            repeat(4) { write(0) }
            write("WEBP".toByteArray(Charsets.US_ASCII))
            write(bitstream("ALPH", ByteArray(4)))
        }.toByteArray()
        assertNull(WebPContainer.frameBitstreamOf(onlyAlpha))
    }
}
