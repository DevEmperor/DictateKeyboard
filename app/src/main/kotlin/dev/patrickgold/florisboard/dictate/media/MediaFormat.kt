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

    /** What a file is, as far as the decision needs to know. */
    data class ImageInfo(
        val mime: String,
        val animated: Boolean,
        val width: Int,
        val height: Int,
        val bytes: Long,
    )

    /**
     * WhatsApp's private sticker type, which it declares instead of plain `image/webp`.
     *
     * Named here rather than inferred, because the rules attached to it are its own and are checked
     * in [qualifiesAsWhatsAppSticker].
     */
    const val WA_STICKER = "image/webp.wasticker"

    /** The edge length WhatsApp insists on, exactly, for anything sent under [WA_STICKER]. */
    const val WA_STICKER_SIZE = 512

    /**
     * How large a sticker may be, still and animated.
     *
     * WhatsApp publishes these and enforces them on arrival, with an error dialog rather than a
     * silent refusal — so a file that breaks them *looks* worse than one that was never offered.
     */
    fun waStickerBudget(animated: Boolean): Long =
        if (animated) 500L * 1024L else 100L * 1024L

    /**
     * Whether a file is already within WhatsApp's sticker bounds and can be handed over untouched.
     *
     * Measured on a real collection: stickers received in WhatsApp are frequently 900 KB and sized
     * 256×256 or 498×498. WhatsApp displays them happily — it simply will not take them back, which
     * is why anything outside these bounds is re-encoded before it is offered under [WA_STICKER].
     */
    internal fun qualifiesAsWhatsAppSticker(info: ImageInfo): Boolean {
        if (info.mime != "image/webp") return false
        if (info.width != WA_STICKER_SIZE || info.height != WA_STICKER_SIZE) return false
        return info.bytes in 1..waStickerBudget(info.animated)
    }

    /**
     * Whether the file may be handed over under a vendor sticker name at all.
     *
     * Deliberately looser than [qualifiesAsWhatsAppSticker], and the distinction is the whole lesson
     * of this feature: the published 512×512 / 500 KB limits govern *sticker packs*, not what
     * `commitContent` accepts. Gboard and the Samsung keyboard insert oversized animated stickers into
     * WhatsApp perfectly well — WhatsApp simply asks the user afterwards whether to send it as a
     * sticker or as a GIF. Refusing those files, as an earlier version of this did, threw away the
     * case that works.
     */
    internal fun mayUseVendorStickerType(info: ImageInfo): Boolean = info.mime == "image/webp"

    /**
     * Reads type, animation, size and dimensions out of a WebP header.
     *
     * Three container forms carry the dimensions in three different places: the extended `VP8X`
     * (24-bit canvas size), lossy `VP8 ` and lossless `VP8L`. Anything unreadable comes back as 0×0,
     * which simply means no vendor sticker type is offered for it.
     */
    internal fun webpDimensions(header: ByteArray): Pair<Int, Int> {
        if (header.size < 30) return 0 to 0
        if (!header.startsWith(0, "RIFF") || !header.startsWith(8, "WEBP")) return 0 to 0
        return when {
            header.startsWith(12, "VP8X") -> {
                val w = le(header, 24, 3) + 1
                val h = le(header, 27, 3) + 1
                w to h
            }
            header.startsWith(12, "VP8 ") -> {
                val w = le(header, 26, 2) and 0x3FFF
                val h = le(header, 28, 2) and 0x3FFF
                w to h
            }
            header.startsWith(12, "VP8L") -> {
                val bits = le(header, 21, 4)
                val w = (bits and 0x3FFF) + 1
                val h = ((bits shr 14) and 0x3FFF) + 1
                w to h
            }
            else -> 0 to 0
        }
    }

    private fun le(bytes: ByteArray, offset: Int, count: Int): Int {
        var value = 0
        for (i in 0 until count) {
            value = value or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i))
        }
        return value
    }

    /** Everything the decision needs about a staged file. */
    fun inspect(file: File, mime: String): ImageInfo {
        val header = readHeader(file)
        val (width, height) = if (mime == "image/webp") webpDimensions(header) else 0 to 0
        return ImageInfo(
            mime = mime,
            animated = isAnimated(mime, header),
            width = width,
            height = height,
            bytes = file.length(),
        )
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
    /**
     * Apps that accept a moving picture and then show one frame of it.
     *
     * Measured, not guessed, and that is the only thing that may put an entry here: Signal declares
     * `image/webp`, takes an animated one without complaint, and puts a still into the chat. Its
     * declaration cannot say that, and no rule derived from the declaration can either — an app that
     * lists `image/webp` may animate it (a sticker keyboard's main audience does) or may not.
     *
     * So this is a list of observations rather than a policy, it is allowed to be incomplete, and
     * being wrong about an app costs only a little colour depth. For anything not on it there is the
     * long-press menu, which offers the same conversion by hand.
     */
    private val FlattensAnimation = setOf("org.thoughtcrime.securesms")

    /** The animated interchange format: an app that takes a GIF at all is expecting it to move. */
    const val GIF = "image/gif"

    fun negotiate(info: ImageInfo, accepted: List<String>, editorPackage: String? = null): String? {
        val own = info.mime
        if (accepted.isEmpty()) return own
        // WhatsApp's sticker type for any WebP it will take. Not gated on its published sticker
        // dimensions: those govern sticker packs, and gating on them refused exactly the animated
        // files that other keyboards insert without trouble.
        if (accepted.contains(WA_STICKER) && mayUseVendorStickerType(info)) return WA_STICKER
        if (
            info.animated && own != GIF && editorPackage in FlattensAnimation &&
            accepted.any { matches(GIF, it) }
        ) {
            return GIF
        }
        if (accepted.any { matches(own, it) }) return own
        if (info.animated) return null
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
    fun convert(context: Context, source: File, targetMime: String): File? {
        val extension = when (targetMime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/jpeg" -> "jpg"
            else -> return null
        }
        val target = File(MediaCache.convertedDir(context), "${source.nameWithoutExtension}$ConvertedSuffix.$extension")
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

    internal fun decode(source: File): Bitmap? = if (AndroidVersion.ATLEAST_API28_P) {
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
