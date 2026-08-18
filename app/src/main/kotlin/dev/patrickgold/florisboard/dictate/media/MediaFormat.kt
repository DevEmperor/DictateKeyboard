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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.io.File
import org.florisboard.lib.android.AndroidVersion

/**
 * Making a picture acceptable to the app it is being inserted into (issue #280).
 *
 * Apps declare which content types they will take, and the declarations are narrower than reality:
 * a WhatsApp sticker is a WebP, and a chat box that happily takes `image/gif` may not list
 * `image/webp` at all. Rather than give up at that point, the file is converted once into a type the
 * editor did name — which for a still image is lossless and unnoticeable.
 *
 * An animated image is the one case where that is not honest: flattening it to PNG would silently
 * turn a moving sticker into a frozen one, which is worse than saying it did not work. So animation
 * is detected from the file header and those keep their own type or go to the clipboard.
 */
object MediaFormat {

    /** The types that can be produced by re-encoding, in the order they are preferred. */
    private val ConvertibleTargets = listOf("image/png", "image/webp", "image/jpeg")

    /** Where a converted copy is stored, relative to the original's name. */
    private const val ConvertedSuffix = "-conv"

    /**
     * Whether the bytes describe a moving image.
     *
     * GIF counts as animated without looking: a still GIF is rare, and treating one as animated only
     * means it keeps its own type, which every app that takes GIFs at all accepts. WebP is the case
     * that has to be read properly — an extended WebP (`VP8X`) carries an animation flag, and a
     * plain `VP8 `/`VP8L` file is always a still.
     */
    fun isAnimated(mime: String, header: ByteArray): Boolean = when (mime) {
        "image/gif" -> true
        "image/webp" -> isAnimatedWebP(header)
        else -> false
    }

    /**
     * Reads the animation flag out of a WebP header.
     *
     * Layout: `RIFF` + 4 size bytes + `WEBP`, then chunks. Only the extended form `VP8X` can be
     * animated, and its flag byte sits 8 bytes into the chunk — bit 1 is ANIMATION. Anything shorter
     * than that, or not a WebP at all, is treated as a still: guessing "animated" would needlessly
     * block a conversion that would have worked.
     */
    internal fun isAnimatedWebP(header: ByteArray): Boolean {
        if (header.size < 21) return false
        if (!header.startsWith(0, "RIFF")) return false
        if (!header.startsWith(8, "WEBP")) return false
        if (!header.startsWith(12, "VP8X")) return false
        // Chunk header is 8 bytes (fourcc + size); the flags byte is the first payload byte.
        val flags = header[20].toInt()
        return (flags and 0x02) != 0
    }

    private fun ByteArray.startsWith(offset: Int, ascii: String): Boolean {
        if (size < offset + ascii.length) return false
        for (i in ascii.indices) {
            if (this[offset + i].toInt().toChar() != ascii[i]) return false
        }
        return true
    }

    /**
     * The type to hand the editor, given what it says it accepts.
     *
     * Returns [own] when the editor named it (or named a pattern covering it) — the common and
     * cheapest case. Returns another type only when converting into it is possible and honest.
     * Returns null when nothing fits, which is the caller's cue to try anyway and then fall back.
     *
     * An editor that declares nothing gets [own]: the declaration is not a promise, and trying costs
     * one call that answers for itself.
     */
    fun negotiate(own: String, animated: Boolean, accepted: List<String>): String? {
        if (accepted.isEmpty()) return own
        if (accepted.any { matches(own, it) }) return own
        if (animated) return null
        return ConvertibleTargets.firstOrNull { target ->
            target != own && accepted.any { matches(target, it) }
        }
    }

    /**
     * Whether a concrete type satisfies a declared one, which may be a pattern.
     *
     * The same rule as `ClipDescription.compareMimeTypes`, written out rather than called: that method
     * is framework code and throws in a plain JVM test, which would leave the one piece of logic worth
     * testing here untestable.
     */
    internal fun matches(concrete: String, declared: String): Boolean {
        if (declared == "*/*") return true
        val slash = declared.indexOf('/')
        if (slash <= 0) return false
        return if (declared.length == slash + 2 && declared[slash + 1] == '*') {
            declared.regionMatches(0, concrete, 0, slash + 1)
        } else {
            declared == concrete
        }
    }

    /**
     * Re-encodes [source] into [targetMime] next to it in the media cache and returns the new file.
     *
     * The result is kept, so inserting the same sticker into the same app a second time costs
     * nothing. Returns null if the image cannot be decoded, in which case the caller still has the
     * original and the clipboard.
     */
    fun convert(source: File, targetMime: String): File? {
        val extension = when (targetMime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/jpeg" -> "jpg"
            else -> return null
        }
        val target = File(source.parentFile, "${source.nameWithoutExtension}$ConvertedSuffix.$extension")
        if (target.exists() && target.length() > 0L) return target
        return try {
            val bitmap = decode(source) ?: return null
            val format = when (targetMime) {
                "image/png" -> Bitmap.CompressFormat.PNG
                "image/jpeg" -> Bitmap.CompressFormat.JPEG
                else -> if (AndroidVersion.ATLEAST_API30_R) {
                    Bitmap.CompressFormat.WEBP_LOSSLESS
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
            }
            target.outputStream().use { out -> bitmap.compress(format, 100, out) }
            bitmap.recycle()
            target.takeIf { it.length() > 0L }
        } catch (e: Exception) {
            flogError { "Failed to convert ${source.name} to $targetMime: ${e.message}" }
            runCatching { target.delete() }
            null
        }
    }

    private fun decode(source: File): Bitmap? = if (AndroidVersion.ATLEAST_API28_P) {
        // Software bitmap on purpose: a hardware one cannot be read back for compression.
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    } else {
        BitmapFactory.decodeFile(source.absolutePath)
    }

    /** The first bytes of a file, enough for every header check above. */
    fun readHeader(file: File, count: Int = 32): ByteArray = try {
        file.inputStream().use { input ->
            val buffer = ByteArray(count)
            val read = input.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        }
    } catch (e: Exception) {
        ByteArray(0)
    }
}
