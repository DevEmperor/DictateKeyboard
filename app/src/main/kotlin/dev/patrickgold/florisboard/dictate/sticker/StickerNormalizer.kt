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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
     * One pass over the folder, [ParallelChecks] files at a time. That number is about waiting, not
     * about work: every file is a separate call across to the documents provider, and such a call
     * spends nearly all of its life waiting for an answer. Converting, which is the opposite — all
     * processor, and the encoder already uses every core — happens under [converting], one sticker at
     * a time, so eight encoders never fight over the same cores.
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
        val gate = Semaphore(ParallelChecks)
        val converting = Mutex()
        val done = AtomicInteger()
        // Eight coroutines finish in whatever order the provider answers them, so a later count can
        // arrive before an earlier one. Reporting only what moves the number forward keeps the bar
        // from twitching backwards.
        val reported = AtomicInteger()
        val outOfShape = AtomicInteger()
        val changed = AtomicInteger()

        coroutineScope {
            items.map { item ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        if (!isAlreadyInShape(context, treeUri, item)) {
                            outOfShape.incrementAndGet()
                            if (converting.withLock { convert(context, treeUri, item) }) {
                                changed.incrementAndGet()
                            }
                        }
                    }
                    val count = done.incrementAndGet()
                    if (reported.getAndUpdate { maxOf(it, count) } < count) {
                        onProgress(count, items.size)
                    }
                }
            }.awaitAll()
        }

        MediaLog.log(
            "rescan: ${items.size} checked, ${outOfShape.get()} out of shape, " +
                "${changed.get()} converted"
        )
        if (changed.get() > 0) StickerScanner.clearCached(context)
        return changed.get()
    }

    /** Re-encodes one sticker in place. Returns whether the folder actually changed. */
    private suspend fun convert(context: Context, treeUri: Uri, item: StickerItem): Boolean {
        val staged = StickerManager.materialize(context, treeUri, item) ?: return false
        val normalized = try {
            normalize(staged, item.mime)
        } catch (e: Exception) {
            flogError { "Failed to normalize ${item.name}: ${e.message}" }
            null
        } ?: return false

        var docId = item.docId
        if (item.mime != "image/webp") {
            docId = StickerWriter.renameTo(context, treeUri, docId, "${item.name}.webp") ?: run {
                MediaLog.log("normalize: could not rename \"${item.name}\" to .webp, left as is")
                return false
            }
        }
        if (!StickerWriter.overwrite(context, treeUri, docId, normalized)) return false
        MediaLog.log("normalize: \"${item.name}\" ${item.mime} -> image/webp, ${normalized.size} B")
        return true
    }

    /**
     * Whether [item] can be left alone.
     *
     * The size comes from the folder listing, which the scan already asked for — the file itself is
     * only opened for its header, and only when the size does not already rule it out. Both of those
     * details were wrong once and cost a round: the size was taken from the file descriptor, which
     * may honestly answer "unknown", and the header from a single `read`, which over a provider
     * stream may honestly answer with four bytes. Either one made every sticker look misshapen.
     *
     * A GIF is always left alone. Anything unreadable answers "no" and goes down the ordinary path,
     * which will fail there in its own way rather than here in a way nobody can see.
     */
    private fun isAlreadyInShape(context: Context, treeUri: Uri, item: StickerItem): Boolean {
        if (item.mime == "image/gif") return true
        if (item.mime != "image/webp") return false
        // Too large for even an animation is too large full stop, and needs no reading to know.
        if (item.size > MediaFormat.waStickerBudget(animated = true)) return false
        return try {
            val uri = StickerScanner.documentUri(treeUri, item.docId)
            val header = context.contentResolver.openInputStream(uri)?.use { input ->
                MediaFormat.readHeader(input)
            } ?: return false
            val size = if (item.size > 0L) item.size else header.size.toLong()
            MediaFormat.qualifiesAsWhatsAppSticker(MediaFormat.inspect(header, size, item.mime))
        } catch (e: Exception) {
            false
        }
    }
}
