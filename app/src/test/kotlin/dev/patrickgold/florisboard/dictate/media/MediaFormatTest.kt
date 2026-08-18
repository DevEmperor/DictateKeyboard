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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two decisions that stand between a sticker and the chat it is meant to land in.
 *
 * Both are made without a screen and both are easy to get wrong in a way no screenshot would reveal:
 * calling a moving sticker still would silently flatten it to a single frame, and offering a type the
 * app never named sends it to the clipboard instead of the conversation.
 */
class MediaFormatTest {

    /** `RIFF` + size + `WEBP` + a chunk header, which is all the sniffing looks at. */
    private fun webp(chunk: String, flags: Int = 0): ByteArray {
        val out = ByteArray(32)
        "RIFF".forEachIndexed { i, c -> out[i] = c.code.toByte() }
        "WEBP".forEachIndexed { i, c -> out[8 + i] = c.code.toByte() }
        chunk.forEachIndexed { i, c -> out[12 + i] = c.code.toByte() }
        out[20] = flags.toByte()
        return out
    }

    @Test
    fun `an extended WebP with the animation flag is animated`() {
        assertTrue(MediaFormat.isAnimatedWebP(webp("VP8X", flags = 0x02)))
        // Other flags set (alpha, ICC, EXIF) must not be mistaken for animation.
        assertTrue(MediaFormat.isAnimatedWebP(webp("VP8X", flags = 0x12)))
        assertFalse(MediaFormat.isAnimatedWebP(webp("VP8X", flags = 0x10)))
    }

    @Test
    fun `a plain WebP is a still, whatever the trailing bytes say`() {
        assertFalse(MediaFormat.isAnimatedWebP(webp("VP8 ", flags = 0x02)))
        assertFalse(MediaFormat.isAnimatedWebP(webp("VP8L", flags = 0x02)))
    }

    @Test
    fun `a header that is not a WebP, or is cut short, counts as a still`() {
        // Guessing "animated" would block a conversion that would have worked, so the doubt goes the
        // other way.
        assertFalse(MediaFormat.isAnimatedWebP(ByteArray(0)))
        assertFalse(MediaFormat.isAnimatedWebP(webp("VP8X", flags = 0x02).copyOf(12)))
        assertFalse(MediaFormat.isAnimatedWebP("not an image at all".toByteArray()))
    }

    @Test
    fun `GIF is taken as animated without reading it, PNG never is`() {
        assertTrue(MediaFormat.isAnimated("image/gif", ByteArray(0)))
        assertFalse(MediaFormat.isAnimated("image/png", ByteArray(0)))
        assertTrue(MediaFormat.isAnimated("image/webp", webp("VP8X", flags = 0x02)))
        assertFalse(MediaFormat.isAnimated("image/webp", webp("VP8 ")))
    }

    @Test
    fun `a type the editor named is used as it is`() {
        assertEquals(
            "image/webp",
            MediaFormat.negotiate("image/webp", animated = false, accepted = listOf("image/png", "image/webp")),
        )
        // A pattern counts as naming it.
        assertEquals(
            "image/webp",
            MediaFormat.negotiate("image/webp", animated = true, accepted = listOf("image/*")),
        )
    }

    @Test
    fun `an editor that declares nothing is tried with the original`() {
        // The declaration is not a promise, and the attempt answers for itself.
        assertEquals("image/webp", MediaFormat.negotiate("image/webp", animated = false, accepted = emptyList()))
        assertEquals("image/gif", MediaFormat.negotiate("image/gif", animated = true, accepted = emptyList()))
    }

    @Test
    fun `a still falls back to a type the editor did name, PNG first`() {
        assertEquals(
            "image/png",
            MediaFormat.negotiate("image/webp", animated = false, accepted = listOf("image/gif", "image/jpeg", "image/png")),
        )
        // Only JPEG on offer: lossy, but a visible sticker beats an invisible one.
        assertEquals(
            "image/jpeg",
            MediaFormat.negotiate("image/webp", animated = false, accepted = listOf("image/jpeg")),
        )
    }

    @Test
    fun `a moving image is never flattened, and an impossible ask returns nothing`() {
        // WhatsApp taking only GIFs plus an animated WebP: converting would freeze it, so the caller
        // is told there is nothing to try and puts it on the clipboard instead.
        assertNull(MediaFormat.negotiate("image/webp", animated = true, accepted = listOf("image/gif")))
        // Nothing convertible on offer either.
        assertNull(MediaFormat.negotiate("image/webp", animated = false, accepted = listOf("video/mp4")))
    }
}
