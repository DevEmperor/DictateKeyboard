/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.sticker

import android.content.Context
import android.net.Uri
import dev.patrickgold.florisboard.dictate.media.MediaFormat
import dev.patrickgold.florisboard.dictate.media.MediaLog
import dev.patrickgold.florisboard.dictate.media.WebPTranscoder
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Brings every sticker in the folder into one shape (issue #280).
 *
 * The shape is WhatsApp's: WebP, exactly 512×512, at most 100 KB still or 500 KB animated. It is the
 * strictest of the formats any chat app asks for, so a file that satisfies it satisfies all of them —
 * which is the entire point. A folder in one shape needs no decisions at insert time.
 *
 * **Why this exists at all.** Earlier the conversion happened when a sticker was tapped: negotiate,
 * re-encode, cache, write back, and a background pass to hide the wait. Five mechanisms for a problem
 * that arises once per file. Gboard and the Samsung keyboard have none of them, and not because they
 * are cleverer — their stickers come from curated catalogues and are already 512×512 WebP. Doing the
 * work once, when a sticker arrives, puts this folder in the same position.
 *
 * **GIFs are left alone, deliberately.** Stepping an animated GIF frame by frame is awkward on
 * Android, WhatsApp accepts `image/gif` anyway, and there are few of them in a sticker collection.
 */
object StickerNormalizer {

    /** What a pass over a folder did. */
    data class Result(
        val changed: Int,
        val alreadyFine: Int,
        val failed: Int,
    ) {
        val total: Int get() = changed + alreadyFine + failed
    }

    /**
     * The normalized form of [file], or null when there is nothing to do or nothing can be done.
     *
     * Null is the common and cheap answer: a sticker that is already in shape stays exactly as it is,
     * bit for bit, and a second pass over a normalized folder therefore costs one header read per
     * file. Works for PNG and JPEG as much as for WebP — the transcoder decodes whatever the platform
     * can decode and always writes WebP.
     */
    suspend fun normalize(context: Context, file: File, mime: String): File? {
        if (mime == "image/gif") return null
        val info = withContext(Dispatchers.IO) { MediaFormat.inspect(file, mime) }
        if (MediaFormat.qualifiesAsWhatsAppSticker(info)) return null
        return withContext(Dispatchers.IO) {
            WebPTranscoder.toStickerSpec(context, file, info.animated)
        }
    }

    /**
     * Walks the whole folder and rewrites what is not yet in shape.
     *
     * A file that has to change its type also has to change its name — a WebP called `.png` would be
     * offered to the next app as a PNG, and the whole point is that the folder says what it holds. The
     * rename happens first, so the bytes are written to the document under the name they belong to; a
     * rename the provider refuses leaves that file untouched rather than mislabelled.
     */
    suspend fun normalizeFolder(
        context: Context,
        treeUri: Uri,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        val index = try {
            StickerScanner.scan(context, treeUri)
        } catch (e: Exception) {
            flogError { "Cannot normalize, the folder could not be read: ${e.message}" }
            return Result(0, 0, 0)
        }
        val items = index.allItems
        var changed = 0
        var alreadyFine = 0
        var failed = 0

        for ((position, item) in items.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress(position, items.size)
            val staged = StickerManager.materialize(context, treeUri, item)
            if (staged == null) {
                failed++
                continue
            }
            val normalized = try {
                normalize(context, staged, item.mime)
            } catch (e: Exception) {
                flogError { "Failed to normalize ${item.name}: ${e.message}" }
                null
            }
            if (normalized == null) {
                // Either it was already in shape, or it is something the platform cannot decode. The
                // difference matters to no one here: in both cases the file stays as it is.
                alreadyFine++
                continue
            }
            var docId = item.docId
            if (item.mime != "image/webp") {
                val renamed = StickerWriter.renameTo(context, treeUri, docId, "${item.name}.webp")
                if (renamed == null) {
                    MediaLog.log("normalize: could not rename \"${item.name}\" to .webp, left as is")
                    failed++
                    continue
                }
                docId = renamed
            }
            if (StickerWriter.overwrite(context, treeUri, docId, normalized)) {
                changed++
                MediaLog.log(
                    "normalize: \"${item.name}\" ${item.mime} -> image/webp, ${normalized.length()} B"
                )
            } else {
                failed++
            }
        }
        onProgress(items.size, items.size)
        withContext(Dispatchers.IO) { StickerScanner.clearCached(context) }
        MediaLog.log("normalize: $changed changed, $alreadyFine already fine, $failed failed")
        return Result(changed, alreadyFine, failed)
    }
}
