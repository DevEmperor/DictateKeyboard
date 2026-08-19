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
import android.provider.DocumentsContract
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Reads the folder the user picked and turns it into a [StickerIndex] (issue #280).
 *
 * Deliberately does **not** use `DocumentFile`: its `listFiles()` runs one query per folder and then one
 * more per file to answer `getName()` / `getType()`, so a collection of three hundred stickers costs
 * roughly nine hundred round trips to the documents provider — long enough to be felt as a stall when the
 * panel opens. Querying [DocumentsContract] directly asks for every column at once, which makes it one
 * query per folder, full stop.
 *
 * The result is cached as JSON next to the app's own files. Opening the panel shows that cache
 * immediately and rescans in the background, so a folder that has not changed costs nothing visible and
 * one that has changed corrects itself a moment later.
 */
object StickerScanner {
    /**
     * What counts as a sticker. JPEG is in the list because plenty of collections contain them, even
     * though no chat app treats them as stickers — the alternative is silently hiding files the user can
     * see in their own folder, which reads as a bug.
     */
    private val SupportedMimes = setOf("image/png", "image/webp", "image/gif", "image/jpeg")

    private val ExtensionMimes = mapOf(
        "png" to "image/png",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
    )

    /**
     * Upper bound on how many files are indexed. Not a limit anyone should hit with a sticker folder —
     * it exists so that pointing the picker at, say, the whole camera roll degrades into "the first two
     * thousand" instead of an unresponsive keyboard.
     */
    const val MaxItems = 2000

    private const val IndexDirName = "stickers"
    private const val IndexFileName = "index.json"

    private val JSON = Json { ignoreUnknownKeys = true }

    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    /** Thrown when the tree can no longer be read — the user revoked access, or the folder is gone. */
    class AccessLostException(cause: Throwable?) : Exception(cause)

    /**
     * Builds the openable URI of a document inside [treeUri]. Coil loads this directly; there is no need
     * to copy anything to display a sticker (only to insert one, see [StickerManager]).
     */
    fun documentUri(treeUri: Uri, docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    /**
     * The document id of the picked folder itself.
     *
     * The root category carries [StickerCategory.ROOT_ID] (an empty string) rather than this, because
     * the index has to stay readable after the tree URI changes. Anything that talks to the documents
     * provider — moving a sticker out of a pack, creating one — needs the real id, and gets it here.
     */
    fun rootDocumentId(treeUri: Uri): String? = try {
        DocumentsContract.getTreeDocumentId(treeUri)
    } catch (e: Exception) {
        null
    }

    /**
     * The folder's own name, so settings can say which folder is in use without re-reading the tree
     * every time the screen opens. Falls back to the last path segment, which is ugly but never empty.
     */
    fun folderName(context: Context, treeUri: Uri): String {
        val docUri = try {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        } catch (e: Exception) {
            return treeUri.lastPathSegment.orEmpty()
        }
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return try {
            context.contentResolver.query(docUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: treeUri.lastPathSegment.orEmpty()
        } catch (e: Exception) {
            treeUri.lastPathSegment.orEmpty()
        }
    }

    /** Whether a scanned row describes a file this panel can show. */
    internal fun mimeFor(displayName: String, reportedMime: String?): String? {
        if (reportedMime != null && reportedMime in SupportedMimes) return reportedMime
        // Some providers answer `application/octet-stream` for everything; fall back to the extension
        // rather than dropping files the user can plainly see in their folder.
        val extension = displayName.substringAfterLast('.', "").lowercase()
        return ExtensionMimes[extension]
    }

    /** File name without its extension, which is what the panel shows and sorts by. */
    internal fun displayLabel(fileName: String): String {
        val withoutExtension = fileName.substringBeforeLast('.', fileName)
        return withoutExtension.ifBlank { fileName }
    }

    internal fun toItem(docId: String, displayName: String, reportedMime: String?, lastModified: Long): StickerItem? {
        if (displayName.isBlank()) return null
        val mime = mimeFor(displayName, reportedMime) ?: return null
        return StickerItem(
            docId = docId,
            name = displayLabel(displayName),
            mime = mime,
            lastModified = lastModified,
        )
    }

    /** Case-insensitive by name, so `Apple.png` and `apple.png` do not end up on opposite ends. */
    internal fun sorted(items: List<StickerItem>): List<StickerItem> =
        items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    /**
     * Walks [treeUri] one level deep and returns what it found.
     *
     * @throws AccessLostException when the persisted permission no longer holds.
     */
    suspend fun scan(context: Context, treeUri: Uri): StickerIndex = withContext(Dispatchers.IO) {
        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            throw AccessLostException(e)
        }

        val rootRows = queryChildren(context, treeUri, rootDocId)
        val rootItems = ArrayList<StickerItem>()
        val folders = ArrayList<Pair<String, String>>() // docId to name
        var budget = MaxItems

        for (row in rootRows) {
            if (row.isDirectory) {
                folders += row.docId to row.displayName
            } else if (budget > 0) {
                toItem(row.docId, row.displayName, row.mimeType, row.lastModified)?.let {
                    rootItems += it
                    budget--
                }
            }
        }

        val categories = ArrayList<StickerCategory>()
        categories += StickerCategory(
            id = StickerCategory.ROOT_ID,
            name = "",
            items = sorted(rootItems),
        )

        folders.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })
        for ((folderId, folderName) in folders) {
            if (budget <= 0) break
            val items = ArrayList<StickerItem>()
            for (row in queryChildren(context, treeUri, folderId)) {
                if (row.isDirectory || budget <= 0) continue
                toItem(row.docId, row.displayName, row.mimeType, row.lastModified)?.let {
                    items += it
                    budget--
                }
            }
            // Empty subfolders stay in the index. A pack the user has just created has nothing in it
            // yet, and dropping it here would make it invisible in the pack manager and impossible to
            // move a sticker into — the panel is the one that hides empty tabs, not the scan.
            categories += StickerCategory(id = folderId, name = folderName, items = sorted(items))
        }

        StickerIndex(treeUri = treeUri.toString(), categories = categories)
    }

    private data class Row(
        val docId: String,
        val displayName: String,
        val mimeType: String?,
        val lastModified: Long,
        val isDirectory: Boolean,
    )

    private fun queryChildren(context: Context, treeUri: Uri, parentDocId: String): List<Row> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val rows = ArrayList<Row>()
        try {
            context.contentResolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(2)
                    rows += Row(
                        docId = cursor.getString(0) ?: continue,
                        displayName = cursor.getString(1) ?: "",
                        mimeType = mime,
                        lastModified = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    )
                }
            } ?: throw AccessLostException(null)
        } catch (e: SecurityException) {
            throw AccessLostException(e)
        } catch (e: IllegalArgumentException) {
            // The tree itself is gone (removed SD card, deleted folder).
            throw AccessLostException(e)
        }
        return rows
    }

    private fun indexFile(context: Context): File =
        File(File(context.filesDir, IndexDirName).apply { mkdirs() }, IndexFileName)

    /**
     * The cached index, or null when there is none or it belongs to a different folder than [treeUri].
     * Kept out of JetPref on purpose: the datastore rewrites the whole value on every change, and an
     * index of a few hundred stickers is tens of kilobytes that have no business being a preference.
     */
    fun loadCached(context: Context, treeUri: String): StickerIndex? {
        if (treeUri.isBlank()) return null
        return try {
            val file = indexFile(context)
            if (!file.exists()) return null
            val index = JSON.decodeFromString<StickerIndex>(file.readText())
            index.takeIf { it.treeUri == treeUri }
        } catch (e: Exception) {
            flogError { "Failed to read sticker index: ${e.message}" }
            null
        }
    }

    fun saveCached(context: Context, index: StickerIndex) {
        try {
            indexFile(context).writeText(JSON.encodeToString(index))
        } catch (e: Exception) {
            flogError { "Failed to write sticker index: ${e.message}" }
        }
    }

    fun clearCached(context: Context) {
        try {
            indexFile(context).delete()
        } catch (e: Exception) {
            flogError { "Failed to delete sticker index: ${e.message}" }
        }
    }
}
