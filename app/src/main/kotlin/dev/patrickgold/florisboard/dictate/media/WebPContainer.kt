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

/**
 * Reading and writing the WebP container, by hand (issue #280).
 *
 * Android can decode animated WebP and can encode single WebP frames, but it cannot write an animated
 * one — and an animated sticker that WhatsApp will accept has to be exactly that. The missing piece is
 * only the container: the frames themselves can be produced with `Bitmap.compress`. So this takes a
 * file apart into frames and puts frames back together into a file, and the actual image compression
 * stays with the platform.
 *
 * The format is RIFF: `RIFF` + size + `WEBP`, then chunks of a four-character name, a little-endian
 * size, and a payload padded to an even length. An animation is a `VP8X` header carrying the canvas
 * size and flags, an `ANIM` chunk with the loop count, and one `ANMF` chunk per frame.
 *
 * Everything here is deliberately free of Android types so it can be tested on a plain JVM — the part
 * that is easy to get wrong by one byte is the part that must be testable.
 */
object WebPContainer {

    private const val FLAG_ANIMATION = 0x02
    private const val FLAG_ALPHA = 0x10

    /** One frame: its own bitstream chunks, where it sits on the canvas, and how long it shows. */
    data class Frame(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val durationMs: Int,
        /** True when the canvas is cleared to the background colour after this frame. */
        val disposeToBackground: Boolean,
        /** True when the frame replaces rather than blends with what is underneath. */
        val doNotBlend: Boolean,
        /** The frame's own chunks, ready to be dropped into an ANMF payload: [ALPH] + VP8/VP8L. */
        val bitstream: ByteArray,
    )

    data class Animation(
        val canvasWidth: Int,
        val canvasHeight: Int,
        val loopCount: Int,
        val backgroundColor: Int,
        val frames: List<Frame>,
    )

    /** A chunk as it sits in the file. */
    private data class Chunk(val fourCC: String, val offset: Int, val size: Int)

    private fun chunks(bytes: ByteArray, from: Int, until: Int): List<Chunk> {
        val out = ArrayList<Chunk>()
        var pos = from
        while (pos + 8 <= until) {
            val name = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = readLe(bytes, pos + 4, 4)
            val payload = pos + 8
            if (size < 0 || payload + size > until) break
            out += Chunk(name, payload, size)
            // Chunks are padded to an even length; the padding byte is not part of the size.
            pos = payload + size + (size and 1)
        }
        return out
    }

    /**
     * Takes an animated WebP apart, or returns null if it is not one.
     *
     * A still image is not an error here, just "nothing to do" — the caller has a cheaper path for it.
     */
    fun demux(bytes: ByteArray): Animation? {
        if (bytes.size < 30) return null
        if (!ascii(bytes, 0, "RIFF") || !ascii(bytes, 8, "WEBP")) return null
        val top = chunks(bytes, 12, bytes.size)
        val vp8x = top.firstOrNull { it.fourCC == "VP8X" } ?: return null
        if (vp8x.size < 10) return null
        val flags = bytes[vp8x.offset].toInt() and 0xFF
        if (flags and FLAG_ANIMATION == 0) return null
        val canvasWidth = readLe(bytes, vp8x.offset + 4, 3) + 1
        val canvasHeight = readLe(bytes, vp8x.offset + 7, 3) + 1

        val anim = top.firstOrNull { it.fourCC == "ANIM" }
        val background = if (anim != null && anim.size >= 6) readLe(bytes, anim.offset, 4) else 0
        val loopCount = if (anim != null && anim.size >= 6) readLe(bytes, anim.offset + 4, 2) else 0

        val frames = top.filter { it.fourCC == "ANMF" }.mapNotNull { chunk ->
            if (chunk.size < 17) return@mapNotNull null
            val base = chunk.offset
            // Offsets and sizes are stored in two-pixel units and minus one respectively.
            val x = readLe(bytes, base, 3) * 2
            val y = readLe(bytes, base + 3, 3) * 2
            val w = readLe(bytes, base + 6, 3) + 1
            val h = readLe(bytes, base + 9, 3) + 1
            val duration = readLe(bytes, base + 12, 3)
            val frameFlags = bytes[base + 15].toInt() and 0xFF
            Frame(
                x = x,
                y = y,
                width = w,
                height = h,
                durationMs = duration,
                disposeToBackground = frameFlags and 0x01 != 0,
                doNotBlend = frameFlags and 0x02 != 0,
                bitstream = bytes.copyOfRange(base + 16, base + chunk.size),
            )
        }
        if (frames.isEmpty()) return null
        return Animation(canvasWidth, canvasHeight, loopCount, background, frames)
    }

    /**
     * The chunks of a still WebP that belong in an animation frame: an optional `ALPH` and the
     * `VP8 `/`VP8L` bitstream. Everything else — the still file's own header, EXIF, ICC — is dropped.
     */
    fun frameBitstreamOf(stillWebP: ByteArray): ByteArray? {
        if (stillWebP.size < 20 || !ascii(stillWebP, 0, "RIFF") || !ascii(stillWebP, 8, "WEBP")) return null
        val top = chunks(stillWebP, 12, stillWebP.size)
        val wanted = top.filter { it.fourCC == "ALPH" || it.fourCC == "VP8 " || it.fourCC == "VP8L" }
        if (wanted.none { it.fourCC != "ALPH" }) return null
        val out = ByteArrayBuilder()
        for (chunk in wanted.sortedBy { if (it.fourCC == "ALPH") 0 else 1 }) {
            out.chunk(chunk.fourCC, stillWebP, chunk.offset, chunk.size)
        }
        return out.toByteArray()
    }

    /**
     * Writes an animated WebP.
     *
     * The alpha flag is set whenever any frame carries an `ALPH` chunk or is lossless, because a
     * sticker without it would be composited onto an opaque rectangle by the receiving app.
     */
    fun mux(animation: Animation): ByteArray {
        val body = ByteArrayBuilder()

        val hasAlpha = animation.frames.any { frame ->
            val names = chunks(frame.bitstream, 0, frame.bitstream.size).map { it.fourCC }
            "ALPH" in names || "VP8L" in names
        }
        val vp8x = ByteArrayBuilder()
        vp8x.byte(FLAG_ANIMATION or (if (hasAlpha) FLAG_ALPHA else 0))
        vp8x.byte(0); vp8x.byte(0); vp8x.byte(0)
        vp8x.le(animation.canvasWidth - 1, 3)
        vp8x.le(animation.canvasHeight - 1, 3)
        body.chunk("VP8X", vp8x.toByteArray())

        val anim = ByteArrayBuilder()
        anim.le(animation.backgroundColor, 4)
        anim.le(animation.loopCount, 2)
        body.chunk("ANIM", anim.toByteArray())

        for (frame in animation.frames) {
            val payload = ByteArrayBuilder()
            payload.le(frame.x / 2, 3)
            payload.le(frame.y / 2, 3)
            payload.le(frame.width - 1, 3)
            payload.le(frame.height - 1, 3)
            payload.le(frame.durationMs, 3)
            payload.byte(
                (if (frame.disposeToBackground) 0x01 else 0) or (if (frame.doNotBlend) 0x02 else 0)
            )
            payload.raw(frame.bitstream)
            body.chunk("ANMF", payload.toByteArray())
        }

        val bodyBytes = body.toByteArray()
        val file = ByteArrayBuilder()
        file.ascii("RIFF")
        file.le(4 + bodyBytes.size, 4)
        file.ascii("WEBP")
        file.raw(bodyBytes)
        return file.toByteArray()
    }

    private fun ascii(bytes: ByteArray, offset: Int, text: String): Boolean {
        if (bytes.size < offset + text.length) return false
        for (i in text.indices) {
            if (bytes[offset + i].toInt().toChar() != text[i]) return false
        }
        return true
    }

    private fun readLe(bytes: ByteArray, offset: Int, count: Int): Int {
        var value = 0
        for (i in 0 until count) {
            value = value or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i))
        }
        return value
    }

    /** A grow-as-you-go byte buffer with just the writes this format needs. */
    private class ByteArrayBuilder {
        private val out = java.io.ByteArrayOutputStream(1024)

        fun byte(value: Int) = out.write(value and 0xFF)

        fun le(value: Int, count: Int) {
            for (i in 0 until count) out.write((value shr (8 * i)) and 0xFF)
        }

        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))

        fun raw(bytes: ByteArray) = out.write(bytes)

        fun chunk(fourCC: String, payload: ByteArray) = chunk(fourCC, payload, 0, payload.size)

        fun chunk(fourCC: String, source: ByteArray, offset: Int, size: Int) {
            ascii(fourCC)
            le(size, 4)
            out.write(source, offset, size)
            if (size and 1 == 1) out.write(0)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
