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
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.media.MediaCache
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.editor.EditorInstance
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.io.File
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
     */
    suspend fun insert(
        context: Context,
        treeUri: Uri,
        item: StickerItem,
        categoryId: String,
    ): EditorInstance.MediaCommitResult {
        val file = materialize(context, treeUri, item) ?: return EditorInstance.MediaCommitResult.FAILED
        val editorInstance by context.editorInstance()
        // Committing rich content talks to the InputConnection — do it on the main thread.
        val result = withContext(Dispatchers.Main) {
            editorInstance.commitMedia(file, item.mime, item.name.ifBlank { "Sticker" })
        }
        if (result != EditorInstance.MediaCommitResult.FAILED) {
            StickerHistoryHelper.markUsed(prefs, categoryId, item.docId)
        }
        withContext(Dispatchers.IO) { MediaCache.prune(context) }
        return result
    }
}
