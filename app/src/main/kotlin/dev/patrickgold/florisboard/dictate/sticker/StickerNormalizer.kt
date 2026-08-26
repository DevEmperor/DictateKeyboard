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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

    /**
     * How many files are asked about at once.
     *
     * Eight is enough to keep the documents provider busy without flooding the binder threads it
     * answers on; the calls are waiting, not working, so there is nothing here that competes for a
     * processor.
     */
    private const val ParallelChecks = 8

    /**
     * The normalized form of [file], or null when there is nothing to do or nothing can be done.
     *
     * Null is the common and cheap answer: a sticker that is already in shape stays exactly as it is,
     * bit for bit, and a second pass over a normalized folder therefore costs one header read per
     * file. Works for PNG and JPEG as much as for WebP — the transcoder decodes whatever the platform
     * can decode and always writes WebP.
     */
    suspend fun normalize(file: File, mime: String): ByteArray? = withContext(Dispatchers.IO) {
        if (mime == "image/gif") return@withContext null
        val info = MediaFormat.inspect(file, mime)
        if (MediaFormat.qualifiesAsWhatsAppSticker(info)) return@withContext null
        WebPTranscoder.toStickerSpec(file, info.animated)
    }

    /**
     * Brings anything in [index] that is out of shape into it, and answers how many that was.
     *
     * Hangs off re-reading the folder, because the two questions are the same question: a file that
     * appeared without going through the import — dropped in with a file manager, synced in from
     * somewhere — is exactly the file that is not yet a sticker, and "read the folder again" is when
     * the user is asking about such files.
     *
     * The checking runs [ParallelChecks] at a time, and that is where the time went: every file is a
     * separate call across to the documents provider, and such a call spends nearly all of its life
     * waiting for an answer rather than doing anything. Measured on a folder with nothing left to
     * convert, one at a time took about half a minute for a few hundred stickers.
     *
     * A file that has to change its type also has to change its name, or the next app is told it is
     * a PNG and treats it as a photograph. The rename goes first, so the bytes land under the name
     * they belong to; a rename the provider refuses leaves that file alone rather than mislabelled.
     */
    suspend fun normalizeFolder(
        context: Context,
        treeUri: Uri,
        index: StickerIndex,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        val items = index.allItems
        val outOfShape = ArrayList<StickerItem>()
        var checked = 0
        for (batch in items.chunked(ParallelChecks)) {
            currentCoroutineContext().ensureActive()
            val verdicts = coroutineScope {
                batch.map { item ->
                    async(Dispatchers.IO) { item to isAlreadyInShape(context, treeUri, item) }
                }.awaitAll()
            }
            verdicts.filterNot { it.second }.mapTo(outOfShape) { it.first }
            checked += batch.size
            onProgress(checked, items.size)
        }
        if (outOfShape.isEmpty()) return 0

        // Converting is the opposite kind of work — all processor, no waiting — and it already uses
        // every core inside the encoder, so it goes one sticker at a time.
        var changed = 0
        for ((position, item) in outOfShape.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress(position, outOfShape.size)
            val staged = StickerManager.materialize(context, treeUri, item) ?: continue
            val normalized = try {
                normalize(staged, item.mime)
            } catch (e: Exception) {
                flogError { "Failed to normalize ${item.name}: ${e.message}" }
                null
            } ?: continue

            var docId = item.docId
            if (item.mime != "image/webp") {
                docId = StickerWriter.renameTo(context, treeUri, docId, "${item.name}.webp") ?: run {
                    MediaLog.log("normalize: could not rename \"${item.name}\" to .webp, left as is")
                    continue
                }
            }
            if (StickerWriter.overwrite(context, treeUri, docId, normalized)) {
                changed++
                MediaLog.log(
                    "normalize: \"${item.name}\" ${item.mime} -> image/webp, ${normalized.size} B"
                )
            }
        }
        onProgress(outOfShape.size, outOfShape.size)
        if (changed > 0) withContext(Dispatchers.IO) { StickerScanner.clearCached(context) }
        return changed
    }

    /**
     * Whether [item] can be left alone, decided from its header and its length alone.
     *
     * A GIF is always left alone. Anything unreadable answers "no" and falls through to the ordinary
     * path, which will fail there in its own way rather than here in a way nobody can see.
     */
    private fun isAlreadyInShape(context: Context, treeUri: Uri, item: StickerItem): Boolean {
        if (item.mime == "image/gif") return true
        return try {
            val uri = StickerScanner.documentUri(treeUri, item.docId)
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                val header = ByteArray(32)
                val read = descriptor.createInputStream().use { it.read(header) }
                if (read <= 0) return false
                val info = MediaFormat.inspect(header.copyOf(read), descriptor.length, item.mime)
                MediaFormat.qualifiesAsWhatsAppSticker(info)
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
}
