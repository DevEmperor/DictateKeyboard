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
import android.content.Intent
import android.net.Uri
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import androidx.core.content.FileProvider
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.dictate.media.MediaCache
import dev.patrickgold.florisboard.dictate.media.MediaFormat
import dev.patrickgold.florisboard.dictate.media.WebPTranscoder
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.editor.EditorInstance
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.io.File
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.stringRes

/**
 * Inserts a sticker from the user's own folder into the current editor (issue #280).
 *
 * The panel shows stickers straight from their `content://` URI, but inserting one cannot use that URI:
 * it belongs to the system's documents provider, and the read permission this app holds on it is not
 * ours to hand on to a third app. So the file is copied into [MediaCache] first and inserted from there
 * through the same [EditorInstance.commitMedia] path the GIF search uses — including its fallback of
 * putting the sticker on the clipboard when the target app refuses rich content of that type.
 */
object StickerManager {
    private val prefs by FlorisPreferenceStore

    private fun extensionFor(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/jpeg" -> "jpg"
        else -> "img"
    }

    /**
     * Copies [item] into the media cache and returns the file, or null if it could not be read.
     *
     * The cache name carries the modification stamp, so replacing a sticker with a new file of the same
     * name inserts the new one instead of silently re-sending the copy made before the edit.
     */
    private suspend fun materialize(context: Context, treeUri: Uri, item: StickerItem): File? =
        withContext(Dispatchers.IO) {
            try {
                val stamp = item.lastModified.toString()
                val safeName = item.docId.hashCode().absoluteValue.toString(16)
                val file = File(
                    MediaCache.dir(context),
                    "sticker-$safeName-$stamp.${extensionFor(item.mime)}",
                )
                if (file.exists() && file.length() > 0L) return@withContext file
                val source = StickerScanner.documentUri(treeUri, item.docId)
                context.contentResolver.openInputStream(source)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext null
                if (file.length() > 0L) file else null
            } catch (e: Exception) {
                flogError { "Failed to stage sticker ${item.docId}: ${e.message}" }
                null
            }
        }

    /**
     * Stages and inserts [item]. On success it is recorded as recently used in [categoryId] and in the
     * combined list.
     *
     * Decides before it acts, which is the opposite of what this did at first. Offering the file's own
     * type, watching it bounce and only then converting meant the clipboard had already been written
     * to — and on Android 13+ the system announces every clipboard write, so the user was told
     * "Copied" a second before the sticker they wanted appeared anyway. Now the editor is asked what
     * it takes, the file is prepared for that answer, and one attempt is made. The clipboard is the
     * last resort it was always meant to be.
     *
     * [onPreparing] is called when the file has to be converted or re-encoded first, which is the only
     * case that takes long enough to need saying.
     */
    suspend fun insert(
        context: Context,
        treeUri: Uri,
        item: StickerItem,
        categoryId: String,
        onPreparing: (Boolean) -> Unit = {},
    ): EditorInstance.MediaCommitResult {
        val file = materialize(context, treeUri, item) ?: return EditorInstance.MediaCommitResult.FAILED
        val editorInstance by context.editorInstance()
        val description = item.name.ifBlank { "Sticker" }

        val accepted = withContext(Dispatchers.Main) { editorInstance.acceptedMediaMimeTypes() }
        val info = withContext(Dispatchers.IO) { MediaFormat.inspect(file, item.mime) }
        val target = MediaFormat.negotiate(info, accepted)

        var committed = false
        if (target != null) {
            val needsWork = target != item.mime
            if (needsWork) onPreparing(true)
            val payload = try {
                prepare(file, info, target)
            } finally {
                if (needsWork) onPreparing(false)
            }
            if (payload != null) {
                committed = withContext(Dispatchers.Main) {
                    editorInstance.tryCommitMedia(payload, target, description)
                }
            }
        }

        // Nothing was negotiated, or the negotiated form was refused after all. The declaration is a
        // hint, not a contract, so the file's own type still deserves one attempt.
        if (!committed && target != item.mime) {
            committed = withContext(Dispatchers.Main) {
                editorInstance.tryCommitMedia(file, item.mime, description)
            }
        }

        val result = when {
            committed -> EditorInstance.MediaCommitResult.COMMITTED
            withContext(Dispatchers.Main) { editorInstance.copyMediaToClipboard(file, item.mime) } ->
                EditorInstance.MediaCommitResult.COPIED_TO_CLIPBOARD
            else -> EditorInstance.MediaCommitResult.FAILED
        }

        if (result != EditorInstance.MediaCommitResult.FAILED) {
            StickerHistoryHelper.markUsed(prefs, categoryId, item.docId)
        }
        withContext(Dispatchers.IO) { MediaCache.prune(context) }
        return result
    }

    /**
     * The bytes to hand over for [target]: the file itself, a relabelling, a re-encode, or a PNG.
     */
    private suspend fun prepare(
        file: File,
        info: MediaFormat.ImageInfo,
        target: String,
    ): File? = when {
        target == info.mime -> file
        // A vendor name for our own format is a relabelling, not a conversion — except when the file
        // misses the receiving app's sticker bounds. WhatsApp takes an oversized animation and then
        // refuses it with an empty frame and "Couldn't share", so it is re-encoded to 512×512 under
        // 500 KB first. Measured: inside those bounds the same sticker goes straight into the chat.
        target == MediaFormat.WA_STICKER && MediaFormat.qualifiesAsWhatsAppSticker(info) -> file
        target == MediaFormat.WA_STICKER && info.animated ->
            withContext(Dispatchers.IO) { WebPTranscoder.toStickerSpec(file) }
        target == MediaFormat.WA_STICKER -> file
        else -> withContext(Dispatchers.IO) { MediaFormat.convert(file, target) }
    }

    /**
     * Hands the sticker to the system share sheet instead of to the editor.
     *
     * The way out when an app will not take rich content from a keyboard at all: the share sheet goes
     * through that app's ordinary import path, which is usually far more forgiving than what it
     * declares to an input method. EweSticker, the established open-source sticker keyboard, uses the
     * same route as its last resort.
     *
     * Costs the user the chat picker, so it is an explicit choice in the long-press menu rather than
     * an automatic fallback.
     */
    suspend fun share(context: Context, treeUri: Uri, item: StickerItem): Boolean {
        val file = materialize(context, treeUri, item) ?: return false
        return try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.provider.file", file,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = item.mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            withContext(Dispatchers.Main) { context.startActivity(chooser) }
            true
        } catch (e: Exception) {
            flogError { "Failed to share sticker ${item.docId}: ${e.message}" }
            false
        }
    }

    /**
     * Why an insert ended up on the clipboard, in the words of the app that refused it.
     *
     * Worth saying out loud rather than hiding behind "this app does not accept stickers": the reason
     * is almost always a format the app never listed, and knowing which one turns a mystery into a
     * fact — for the user and for the next bug report.
     */
    fun refusalReason(context: Context, item: StickerItem): String {
        val editorInstance by context.editorInstance()
        val accepted = editorInstance.acceptedMediaMimeTypes()
        return if (accepted.isEmpty()) {
            context.stringRes(R.string.sticker__refused_declares_nothing)
        } else {
            context.stringRes(
                R.string.sticker__refused_accepts_only,
                "accepted" to accepted.joinToString(", "),
                "own" to item.mime,
            )
        }
    }
}
