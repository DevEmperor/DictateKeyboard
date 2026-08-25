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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * Re-encodes a WebP into one WhatsApp will accept as a sticker (issue #280).
 *
 * Measured on a real device: a sticker of exactly 512×512 within WhatsApp's size budget goes straight
 * into the chat. One that misses those bounds is taken by WhatsApp and then refused by its own
 * validator — an empty sticker frame appears and "Couldn't share" follows. Since stickers received in
 * WhatsApp are routinely 256×256 or 950 KB, most of a collection is on the wrong side of that line,
 * and the only way across is to re-encode.
 *
 * This applies to **still** stickers just as much as to animated ones, which the first version of this
 * class got wrong: it re-encoded animations and let stills through untouched, so an ordinary 256×256
 * sticker was offered under WhatsApp's own type and bounced with the same empty frame. The rule now
 * has no exceptions — nothing is offered as a sticker that is not shaped like one.
 *
 * There is no animated-WebP encoder on Android, but there does not need to be: the platform can encode
 * single frames, and [WebPContainer] can write the file around them. So an animation is decoded frame
 * by frame, each one composed onto the canvas as the format prescribes and scaled, and then quality is
 * lowered — and, if that is not enough, frames are dropped — until it fits.
 *
 * Transparency survives all of it, which is the reason this route was chosen over the two obvious
 * alternatives: a looping MP4 has no alpha channel at all, and GIF has only a single transparent
 * colour, which turns every soft edge into a jagged one.
 */
object WebPTranscoder {

    /** WhatsApp's rules for a sticker, which are the whole point of this class. */
    private const val TARGET_SIZE = MediaFormat.WA_STICKER_SIZE

    /** Tried in order until the result fits. 80 already looks clean on a sticker. */
    private val QUALITY_STEPS = intArrayOf(80, 70, 60, 50, 40, 30)

    /** Below this many frames the animation stops reading as motion, so frame dropping stops here. */
    private const val MIN_FRAMES = 6

    /**
     * Produces a sticker-shaped copy of [source], or null if that is not possible.
     *
     * The result is kept in [MediaCache.convertedDir], so a second insert of the same sticker costs
     * nothing even before the copy in the user's own folder has been written back.
     */
    fun toStickerSpec(context: Context, source: File, animated: Boolean): File? {
        val budget = MediaFormat.waStickerBudget(animated)
        val target = File(MediaCache.convertedDir(context), "${source.nameWithoutExtension}-wa.webp")
        if (target.exists() && target.length() in 1..budget) {
            MediaLog.log("transcode: reusing ${target.name} (${target.length()} bytes)")
            return target
        }
        return try {
            val started = System.currentTimeMillis()
            val encoded = if (animated) {
                encodeAnimated(source, budget)
            } else {
                encodeStill(source, budget)
            }
            if (encoded == null) {
                MediaLog.log("transcode: failed for ${source.name} (animated=$animated)")
                return null
            }
            target.writeBytes(encoded)
            MediaLog.log(
                "transcode: ${source.name} -> ${encoded.size} bytes in " +
                    "${System.currentTimeMillis() - started} ms (animated=$animated)"
            )
            target.takeIf { it.length() in 1..budget }
        } catch (e: Exception) {
            flogError { "Failed to transcode ${source.name}: ${e.message}" }
            MediaLog.log("transcode: ${source.name} threw ${e.javaClass.simpleName}: ${e.message}")
            runCatching { target.delete() }
            null
        } catch (e: OutOfMemoryError) {
            // A long animation at full canvas size is the one realistic way to run out of memory in
            // an IME process. Losing the conversion is fine; taking the keyboard down is not.
            flogError { "Out of memory transcoding ${source.name}" }
            MediaLog.log("transcode: out of memory on ${source.name}")
            runCatching { target.delete() }
            null
        }
    }

    private class DecodedFrame(val bitmap: Bitmap, val durationMs: Int)

    /** A single picture, fitted onto the sticker canvas and compressed until it fits [budget]. */
    private fun encodeStill(source: File, budget: Long): ByteArray? {
        val decoded = MediaFormat.decode(source) ?: return null
        val canvas = fitOnStickerCanvas(decoded)
        decoded.recycle()
        try {
            for (quality in QUALITY_STEPS) {
                val out = ByteArrayOutputStream(64 * 1024)
                @Suppress("DEPRECATION")
                if (!canvas.compress(Bitmap.CompressFormat.WEBP, quality, out)) continue
                val bytes = out.toByteArray()
                if (bytes.size <= budget) return bytes
            }
            return null
        } finally {
            canvas.recycle()
        }
    }

    private fun encodeAnimated(source: File, budget: Long): ByteArray? {
        val animation = WebPContainer.demux(source.readBytes()) ?: return null
        val frames = decodeFrames(animation) ?: return null
        try {
            return encodeWithinBudget(frames, animation.loopCount, budget)
        } finally {
            frames.forEach { it.bitmap.recycle() }
        }
    }

    /**
     * Renders every frame, already fitted to the sticker canvas.
     *
     * A WebP frame is only the rectangle that changed, and it either blends onto or replaces what is
     * below it; after showing, the canvas may be cleared back to transparent. Composing here rather
     * than passing the sub-rectangles through means the re-encoded file is a plain sequence of full
     * frames — larger before compression, but immune to a receiver that handles disposal differently.
     *
     * The scaling happens in the same step as the composing, on purpose. It used to happen inside the
     * quality ladder instead, which meant every frame was scaled again for each of the six quality
     * attempts — most of the second the user was waiting went into doing the same work six times.
     */
    private fun decodeFrames(animation: WebPContainer.Animation): List<DecodedFrame>? {
        val canvasBitmap = Bitmap.createBitmap(
            animation.canvasWidth, animation.canvasHeight, Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(canvasBitmap)
        val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        val out = ArrayList<DecodedFrame>(animation.frames.size)

        for (frame in animation.frames) {
            val still = stillWebPOf(frame) ?: continue
            val piece = BitmapFactory.decodeByteArray(still, 0, still.size) ?: continue
            val destination = Rect(frame.x, frame.y, frame.x + frame.width, frame.y + frame.height)
            // "Do not blend" means the frame's own pixels win outright, transparency included.
            canvas.drawBitmap(piece, null, destination, if (frame.doNotBlend) clearPaint else null)
            piece.recycle()
            out += DecodedFrame(fitOnStickerCanvas(canvasBitmap), frame.durationMs)
            if (frame.disposeToBackground) {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            }
        }
        canvasBitmap.recycle()
        return out.takeIf { it.isNotEmpty() }
    }

    /**
     * Draws [source] centred on a transparent 512×512 canvas, keeping its proportions.
     *
     * Stretching to a square would be simpler and is what this did at first, but a sticker that is
     * not square — and plenty are — came out visibly squashed. WhatsApp wants exactly 512×512; it
     * does not want the picture distorted to get there, and transparent margins cost nothing.
     */
    private fun fitOnStickerCanvas(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(TARGET_SIZE, TARGET_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val scale = minOf(
            TARGET_SIZE.toFloat() / source.width,
            TARGET_SIZE.toFloat() / source.height,
        )
        val width = (source.width * scale).roundToInt().coerceIn(1, TARGET_SIZE)
        val height = (source.height * scale).roundToInt().coerceIn(1, TARGET_SIZE)
        val left = (TARGET_SIZE - width) / 2
        val top = (TARGET_SIZE - height) / 2
        canvas.drawBitmap(
            source,
            null,
            Rect(left, top, left + width, top + height),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        return out
    }

    /** Wraps one animation frame's bitstream back into a still file the platform decoder accepts. */
    private fun stillWebPOf(frame: WebPContainer.Frame): ByteArray? {
        val out = ByteArrayOutputStream(frame.bitstream.size + 32)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        val body = ByteArrayOutputStream(frame.bitstream.size + 24)
        body.write("WEBP".toByteArray(Charsets.US_ASCII))
        // A frame carrying a separate alpha chunk needs the extended header to go with it.
        val hasAlpha = frame.bitstream.size > 4 &&
            String(frame.bitstream, 0, 4, Charsets.US_ASCII) == "ALPH"
        if (hasAlpha) {
            body.write("VP8X".toByteArray(Charsets.US_ASCII))
            writeLe(body, 10, 4)
            body.write(0x10) // alpha
            repeat(3) { body.write(0) }
            writeLe(body, frame.width - 1, 3)
            writeLe(body, frame.height - 1, 3)
        }
        body.write(frame.bitstream)
        val bodyBytes = body.toByteArray()
        writeLe(out, bodyBytes.size, 4)
        out.write(bodyBytes)
        return out.toByteArray().takeIf { it.size > 12 }
    }

    /**
     * Encodes the frames, lowering quality and finally dropping frames until it fits [budget].
     *
     * Quality first, because a slightly softer sticker is far less noticeable than a jerky one.
     */
    private fun encodeWithinBudget(
        frames: List<DecodedFrame>,
        loopCount: Int,
        budget: Long,
    ): ByteArray? {
        var working = frames
        while (true) {
            for (quality in QUALITY_STEPS) {
                val encoded = encode(working, quality, loopCount) ?: continue
                if (encoded.size <= budget) return encoded
            }
            if (working.size <= MIN_FRAMES) return null
            // Halve the frame rate and keep the total running time by doubling each duration. The
            // bitmaps are shared with [frames], which is what recycles them.
            working = working.filterIndexed { index, _ -> index % 2 == 0 }
                .map { DecodedFrame(it.bitmap, it.durationMs * 2) }
        }
    }

    private fun encode(frames: List<DecodedFrame>, quality: Int, loopCount: Int): ByteArray? {
        val out = ArrayList<WebPContainer.Frame>(frames.size)
        for (frame in frames) {
            val still = ByteArrayOutputStream(64 * 1024)
            @Suppress("DEPRECATION")
            val ok = frame.bitmap.compress(Bitmap.CompressFormat.WEBP, quality, still)
            if (!ok) return null
            val bitstream = WebPContainer.frameBitstreamOf(still.toByteArray()) ?: return null
            out += WebPContainer.Frame(
                x = 0,
                y = 0,
                width = TARGET_SIZE,
                height = TARGET_SIZE,
                // WhatsApp refuses a frame that claims to last no time at all.
                durationMs = frame.durationMs.coerceAtLeast(20),
                disposeToBackground = false,
                doNotBlend = true,
                bitstream = bitstream,
            )
        }
        return WebPContainer.mux(
            WebPContainer.Animation(
                canvasWidth = TARGET_SIZE,
                canvasHeight = TARGET_SIZE,
                // 0 means loop forever, which is what a sticker does.
                loopCount = if (loopCount == 0) 0 else loopCount,
                backgroundColor = 0,
                frames = out,
            )
        )
    }

    private fun writeLe(out: ByteArrayOutputStream, value: Int, count: Int) {
        for (i in 0 until count) out.write((value shr (8 * i)) and 0xFF)
    }
}
