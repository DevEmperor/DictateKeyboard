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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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

    /** The qualities worth trying, highest first. 80 already looks clean on a sticker. */
    private val QUALITY_STEPS = intArrayOf(80, 70, 60, 50, 40, 30)

    /**
     * Where the ladder starts when the source is smaller than a sticker.
     *
     * Enlarging a 240×240 sticker to 512×512 invents every second pixel, and spending quality 80 on
     * invented detail is how a 197 KB file came back out at 412 KB — larger than the original and no
     * better to look at. Interpolated pixels are smooth, so they compress well and lose little.
     */
    private const val UPSCALE_QUALITY = 65

    /** Below this many frames the animation stops reading as motion, so frame dropping stops here. */
    private const val MIN_FRAMES = 6

    /**
     * How large a GIF is allowed to get.
     *
     * Measured: a 17-frame sticker came out at 1.35 MB from a 181 KB WebP, because a GIF stores every
     * frame whole and cannot describe one in terms of the last. No chat shows a sticker anywhere near
     * 512 px, so the picture is capped well below that first, and frames are dropped only if even
     * that is not enough — the last thing to give up is the motion this format exists for.
     */
    private const val GIF_MAX_SIZE = 384
    private const val GIF_MAX_BYTES = 1024 * 1024
    private const val GIF_MIN_FRAMES = 8

    /** How many frames are compressed to estimate what the whole animation will weigh. */
    private const val PROBE_FRAMES = 3

    /** Aim a little under the limit, since the estimate is an estimate. */
    private const val ESTIMATE_MARGIN = 0.92

    /**
     * Produces a sticker-shaped copy of [source], or null if that is not possible.
     *
     * The result is kept in [MediaCache.convertedDir] — which lives in `filesDir`, not the cache — so
     * a sticker is re-encoded at most once on a device, whether or not the copy in the user's own
     * folder could be written back.
     */
    suspend fun toStickerSpec(context: Context, source: File, animated: Boolean): File? {
        val budget = MediaFormat.waStickerBudget(animated)
        conversionOf(context, source, animated)?.let { existing ->
            MediaLog.log("transcode: reusing ${existing.name} (${existing.length()} bytes)")
            return existing
        }
        val target = File(MediaCache.convertedDir(context), "${source.nameWithoutExtension}-wa.webp")
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

    /**
     * Rewrites an animated WebP as an animated GIF, or returns null if it is not animated.
     *
     * For apps that take a moving picture and show one frame of it. Measured: Signal accepts
     * `image/webp`, and what arrives in the chat is a still. GIF is the only format in the list it
     * declares that is guaranteed to move, and Android cannot write one — hence [GifEncoder], and
     * hence the cost that comes with it: 256 colours and transparency that is either on or off, so a
     * soft outline gains a hard edge.
     *
     * Kept at the sticker's own size rather than enlarged to 512×512. That requirement is WhatsApp's,
     * and WhatsApp is not who this is for; a GIF of a 240×240 sticker is a quarter of the pixels and
     * a small fraction of the bytes.
     */
    suspend fun toAnimatedGif(context: Context, source: File): File? {
        val target = File(MediaCache.convertedDir(context), "${source.nameWithoutExtension}-anim.gif")
        if (target.exists() && target.length() > 0L) {
            MediaLog.log("gif: reusing ${target.name} (${target.length()} bytes)")
            return target
        }
        return try {
            val started = System.currentTimeMillis()
            val animation = WebPContainer.demux(source.readBytes()) ?: return null
            val frames = decodeFrames(animation) { bitmap -> withinGifBounds(bitmap) } ?: return null
            val width = frames[0].bitmap.width
            val height = frames[0].bitmap.height
            // Each frame becomes pixels and gives up its bitmap straight away: an animation held
            // twice over, once as bitmaps and once as integers, is how an IME process runs out of room.
            var encodable = frames.map { frame ->
                val pixels = IntArray(width * height)
                frame.bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                frame.bitmap.recycle()
                GifEncoder.Frame(pixels, frame.durationMs)
            }
            var bytes = GifEncoder.encode(width, height, encodable, animation.loopCount)
            while (bytes != null && bytes.size > GIF_MAX_BYTES && encodable.size > GIF_MIN_FRAMES) {
                encodable = encodable.filterIndexed { index, _ -> index % 2 == 0 }
                    .map { GifEncoder.Frame(it.pixels, it.delayMs * 2) }
                MediaLog.log("gif: halving to ${encodable.size} frames to fit $GIF_MAX_BYTES bytes")
                bytes = GifEncoder.encode(width, height, encodable, animation.loopCount)
            }
            if (bytes == null) {
                MediaLog.log("gif: failed for ${source.name}")
                return null
            }
            target.writeBytes(bytes)
            MediaLog.log(
                "gif: ${source.name} -> ${bytes.size} bytes, ${width}x$height, " +
                    "${encodable.size} frames in ${System.currentTimeMillis() - started} ms"
            )
            target.takeIf { it.length() > 0L }
        } catch (e: Exception) {
            flogError { "Failed to write a GIF of ${source.name}: ${e.message}" }
            MediaLog.log("gif: ${source.name} threw ${e.javaClass.simpleName}: ${e.message}")
            runCatching { target.delete() }
            null
        } catch (e: OutOfMemoryError) {
            flogError { "Out of memory writing a GIF of ${source.name}" }
            MediaLog.log("gif: out of memory on ${source.name}")
            runCatching { target.delete() }
            null
        }
    }

    /** A copy at its own size, or shrunk if it is larger than a chat is ever going to show. */
    private fun withinGifBounds(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= GIF_MAX_SIZE) return source.copy(Bitmap.Config.ARGB_8888, false)
        val scale = GIF_MAX_SIZE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * The conversion of [source] that has already been made, if there is one.
     *
     * Lets a caller tell "this is ready" from "this needs a second of work" without doing the work —
     * which is what the background pass ahead of the user's finger needs in order to know what is
     * left to do.
     */
    fun conversionOf(context: Context, source: File, animated: Boolean): File? =
        File(MediaCache.convertedDir(context), "${source.nameWithoutExtension}-wa.webp")
            .takeIf { it.exists() && it.length() in 1..MediaFormat.waStickerBudget(animated) }

    private class DecodedFrame(val bitmap: Bitmap, val durationMs: Int)

    /** The quality to start from, given how the source compares to a sticker. */
    private fun startQuality(sourceWidth: Int, sourceHeight: Int): Int =
        if (maxOf(sourceWidth, sourceHeight) < TARGET_SIZE) UPSCALE_QUALITY else QUALITY_STEPS[0]

    /** A single picture, fitted onto the sticker canvas and compressed until it fits [budget]. */
    private fun encodeStill(source: File, budget: Long): ByteArray? {
        val decoded = MediaFormat.decode(source) ?: return null
        val start = startQuality(decoded.width, decoded.height)
        val canvas = fitOnStickerCanvas(decoded)
        decoded.recycle()
        try {
            for (quality in QUALITY_STEPS) {
                if (quality > start) continue
                val bytes = compress(canvas, quality) ?: continue
                if (bytes.size <= budget) return bytes
            }
            return null
        } finally {
            canvas.recycle()
        }
    }

    private suspend fun encodeAnimated(source: File, budget: Long): ByteArray? {
        val animation = WebPContainer.demux(source.readBytes()) ?: return null
        val start = startQuality(animation.canvasWidth, animation.canvasHeight)
        val frames = decodeFrames(animation) ?: return null
        try {
            return encodeWithinBudget(frames, animation.loopCount, budget, start)
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
     * attempts — most of the wait went into doing the same work six times.
     */
    private fun decodeFrames(
        animation: WebPContainer.Animation,
        transform: (Bitmap) -> Bitmap = ::fitOnStickerCanvas,
    ): List<DecodedFrame>? {
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
            out += DecodedFrame(transform(canvasBitmap), frame.durationMs)
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
     * Finds a quality that fits [budget] and encodes the animation at it.
     *
     * The obvious way — encode everything at 80, then everything at 70, and so on — is what made a
     * 20-frame sticker take five seconds: each rung of the ladder compresses every frame again, so
     * the search costs six full encodes and only the last one is kept. Measured on the device, that
     * was almost all of the wait.
     *
     * So the search is done on [PROBE_FRAMES] frames instead. Three frames compress in a tenth of the
     * time of twenty and predict the whole animation closely enough, because every frame here is the
     * same size and comes from the same picture. The full encode then happens once, at the quality
     * the probe chose; if the estimate was optimistic the next rung down is tried, and only when
     * even the lowest quality will not fit does the frame rate get halved.
     */
    private suspend fun encodeWithinBudget(
        frames: List<DecodedFrame>,
        loopCount: Int,
        budget: Long,
        startQuality: Int,
    ): ByteArray? {
        var working = frames
        while (true) {
            val ladder = QUALITY_STEPS.filter { it <= startQuality }
            var next = probeQuality(working, budget, ladder)
            while (next != null) {
                val quality = next
                val encoded = encode(working, quality, loopCount)
                if (encoded != null && encoded.size <= budget) return encoded
                next = ladder.firstOrNull { it < quality }
            }
            if (working.size <= MIN_FRAMES) return null
            // Halve the frame rate and keep the total running time by doubling each duration. The
            // bitmaps are shared with [frames], which is what recycles them.
            working = working.filterIndexed { index, _ -> index % 2 == 0 }
                .map { DecodedFrame(it.bitmap, it.durationMs * 2) }
            MediaLog.log("transcode: halving to ${working.size} frames to fit $budget bytes")
        }
    }

    /** The highest quality on [ladder] whose estimated size fits [budget], from a handful of frames. */
    private suspend fun probeQuality(
        frames: List<DecodedFrame>,
        budget: Long,
        ladder: List<Int>,
    ): Int? {
        if (frames.size <= PROBE_FRAMES) return ladder.firstOrNull()
        // First, middle and last: an animation usually starts and ends quieter than its middle.
        val samples = listOf(0, frames.size / 2, frames.size - 1).map { frames[it] }
        for (quality in ladder) {
            val compressed = compressAll(samples, quality) ?: continue
            val estimate = compressed.sumOf { it.size }.toLong() * frames.size / samples.size
            if (estimate <= budget * ESTIMATE_MARGIN) return quality
        }
        return ladder.lastOrNull()
    }

    private suspend fun encode(
        frames: List<DecodedFrame>,
        quality: Int,
        loopCount: Int,
    ): ByteArray? {
        val compressed = compressAll(frames, quality) ?: return null
        val out = ArrayList<WebPContainer.Frame>(frames.size)
        for ((index, still) in compressed.withIndex()) {
            val bitstream = WebPContainer.frameBitstreamOf(still) ?: return null
            out += WebPContainer.Frame(
                x = 0,
                y = 0,
                width = TARGET_SIZE,
                height = TARGET_SIZE,
                // WhatsApp refuses a frame that claims to last no time at all.
                durationMs = frames[index].durationMs.coerceAtLeast(20),
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

    /**
     * Compresses frames across all cores.
     *
     * Frame compression is the one part of this that is both the bulk of the work and completely
     * independent per frame — each one is its own picture and knows nothing of its neighbours. The
     * encoder is native code that does not hold a lock anyone else wants, so handing the frames to
     * the default dispatcher divides the wait by roughly the number of cores.
     */
    private suspend fun compressAll(frames: List<DecodedFrame>, quality: Int): List<ByteArray>? =
        coroutineScope {
            val results = frames
                .map { frame -> async(Dispatchers.Default) { compress(frame.bitmap, quality) } }
                .awaitAll()
            if (results.any { it == null }) null else results.filterNotNull()
        }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray? {
        val out = ByteArrayOutputStream(64 * 1024)
        @Suppress("DEPRECATION")
        val ok = bitmap.compress(Bitmap.CompressFormat.WEBP, quality, out)
        return if (ok) out.toByteArray() else null
    }

    private fun writeLe(out: ByteArrayOutputStream, value: Int, count: Int) {
        for (i in 0 until count) out.write((value shr (8 * i)) and 0xFF)
    }
}
