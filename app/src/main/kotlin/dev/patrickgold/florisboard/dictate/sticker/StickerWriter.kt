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
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.stringRes

/**
 * Puts images into the sticker folder and takes them out again (issue #280).
 *
 * The folder belongs to the user, not to this app, so everything here goes through the Storage Access
 * Framework on the tree they picked. That has one consequence worth stating plainly: a folder granted
 * before this existed carries read permission only, and adding or deleting needs it picked once more.
 * [canWrite] is what the UI asks before offering either.
 *
 * Where the images come from is deliberately not this class's business. The share sheet hands over
 * `content://` URIs from WhatsApp, Telegram or a gallery; the file picker hands over the same kind of
 * thing. Both end up here as a list of URIs to copy.
 */
object StickerWriter {

    /** Extensions written out for the types the panel can show; anything else is refused. */
    private val ExtensionForMime = mapOf(
        "image/png" to "png",
        "image/webp" to "webp",
        "image/gif" to "gif",
        "image/jpeg" to "jpg",
    )

    /** A single sticker larger than this is a photo, not a sticker, and would slow the grid down. */
    private const val MaxBytes = 12L * 1024L * 1024L

    data class ImportResult(
        val imported: Int,
        val skippedDuplicate: Int,
        val skippedUnsupported: Int,
        val failed: Int,
    ) {
        val total: Int get() = imported + skippedDuplicate + skippedUnsupported + failed
    }

    /**
     * Whether the persisted grant on [treeUri] allows writing.
     *
     * Asked rather than assumed: `OpenDocumentTree` returns read *and* write, but the app only started
     * taking both when importing was added, so anyone who picked a folder before that has a read-only
     * grant that no amount of retrying will widen.
     */
    fun canWrite(context: Context, treeUri: String): Boolean {
        if (treeUri.isBlank()) return false
        val uri = Uri.parse(treeUri)
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
    }

    /**
     * Copies [sources] into the root of [treeUri].
     *
     * Files whose type the panel cannot show are skipped rather than converted, and a file already
     * there under the same name and size is skipped too — sharing the same sticker twice is a normal
     * accident and should not leave two of it. Naming collisions between *different* files are left to
     * the documents provider, which appends its own suffix.
     */
    suspend fun importInto(
        context: Context,
        treeUri: Uri,
        sources: List<Uri>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return@withContext ImportResult(0, 0, 0, sources.size)
        }
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        val existing = existingNamesAndSizes(context, treeUri, rootDocId)

        var imported = 0
        var duplicate = 0
        var unsupported = 0
        var failed = 0

        for ((index, source) in sources.withIndex()) {
            // Cancelling an import has to stop the copying, not just hide the bar.
            ensureActive()
            onProgress(index, sources.size)
            val meta = describe(context, source)
            val mime = meta.mime
            val extension = ExtensionForMime[mime]
            if (extension == null || meta.size > MaxBytes) {
                unsupported++
                continue
            }
            val name = fileName(meta.displayName, extension)
            if (existing[name] == meta.size && meta.size > 0L) {
                duplicate++
                continue
            }
            try {
                val target = DocumentsContract.createDocument(resolver, rootUri, mime, name)
                if (target == null) {
                    failed++
                    continue
                }
                val copied = resolver.openInputStream(source)?.use { input ->
                    resolver.openOutputStream(target)?.use { output -> input.copyTo(output) }
                }
                if (copied == null) {
                    // Nothing was written, so the empty document it created would show as a blank cell.
                    runCatching { DocumentsContract.deleteDocument(resolver, target) }
                    failed++
                } else {
                    imported++
                    existing[name] = meta.size
                }
            } catch (e: Exception) {
                flogError { "Failed to import sticker: ${e.message}" }
                failed++
            }
        }

        onProgress(sources.size, sources.size)
        if (imported > 0) StickerScanner.clearCached(context)
        ImportResult(imported, duplicate, unsupported, failed)
    }

    /**
     * Creates a pack — which is to say a subfolder, because that is what the panel already shows as a
     * tab. Returns the new folder's document id, or null if the name was taken or refused.
     */
    suspend fun createPack(context: Context, treeUri: Uri, name: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
                val created = DocumentsContract.createDocument(
                    context.contentResolver,
                    rootUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    packName(name),
                ) ?: return@withContext null
                StickerScanner.clearCached(context)
                DocumentsContract.getDocumentId(created)
            } catch (e: Exception) {
                flogError { "Failed to create pack $name: ${e.message}" }
                null
            }
        }

    /**
     * Renames a pack.
     *
     * The documents provider for shared storage derives ids from the path, so renaming a folder
     * changes the id of everything inside it. The index is therefore thrown away rather than patched,
     * and stale favourites simply stop resolving — the panel skips ids it cannot find.
     */
    suspend fun renamePack(context: Context, treeUri: Uri, docId: String, name: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                val renamed = DocumentsContract.renameDocument(
                    context.contentResolver, uri, packName(name),
                ) != null
                if (renamed) StickerScanner.clearCached(context)
                renamed
            } catch (e: Exception) {
                flogError { "Failed to rename pack $docId: ${e.message}" }
                false
            }
        }

    /** Deletes a pack **and the stickers in it** — the caller must have asked first. */
    suspend fun deletePack(context: Context, treeUri: Uri, docId: String): Boolean =
        withContext(Dispatchers.IO) {
            val deleted = delete(context, treeUri, docId)
            if (deleted) StickerScanner.clearCached(context)
            deleted
        }

    /**
     * Moves a sticker into a pack.
     *
     * Prefers the provider's own move, which is instant and keeps the document id. Not every provider
     * offers it, so a copy followed by a delete stands behind it — slower, and the sticker comes out
     * with a new id, which is why the caller drops it from the favourites and recents afterwards.
     */
    suspend fun moveToPack(
        context: Context,
        treeUri: Uri,
        docId: String,
        sourceParentDocId: String,
        targetParentDocId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (sourceParentDocId == targetParentDocId) return@withContext true
        val resolver = context.contentResolver
        val sourceUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val sourceParentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, sourceParentDocId)
        val targetParentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, targetParentDocId)
        val moved = try {
            DocumentsContract.moveDocument(resolver, sourceUri, sourceParentUri, targetParentUri) != null
        } catch (e: Exception) {
            flogError { "Move unsupported for $docId, copying instead: ${e.message}" }
            false
        }
        val ok = moved || copyThenDelete(context, sourceUri, targetParentUri)
        if (ok) StickerScanner.clearCached(context)
        ok
    }

    private fun copyThenDelete(context: Context, sourceUri: Uri, targetParentUri: Uri): Boolean {
        val resolver = context.contentResolver
        return try {
            val mime = resolver.getType(sourceUri) ?: return false
            val name = resolver.query(
                sourceUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: return false
            val target = DocumentsContract.createDocument(resolver, targetParentUri, mime, name)
                ?: return false
            val copied = resolver.openInputStream(sourceUri)?.use { input ->
                resolver.openOutputStream(target)?.use { output -> input.copyTo(output) }
            }
            if (copied == null) {
                runCatching { DocumentsContract.deleteDocument(resolver, target) }
                return false
            }
            DocumentsContract.deleteDocument(resolver, sourceUri)
        } catch (e: Exception) {
            flogError { "Failed to copy into pack: ${e.message}" }
            false
        }
    }

    /** Folder names have to survive a file system, so the same characters go as for a sticker file. */
    internal fun packName(name: String): String = name
        .replace(Regex("""[/\\:*?"<>|]"""), "_")
        .trim()
        .take(64)
        .ifBlank { "Pack" }

    /**
     * Replaces the contents of one sticker in the folder with [source].
     *
     * Used after a sticker had to be re-encoded to be insertable: rather than keep the usable version
     * in a private directory the user cannot see and re-derive it forever, the usable version becomes
     * the sticker. The folder is the user's, so what is in it should be what gets sent.
     *
     * `wt` truncates, which matters because the new file is smaller than the old one — a provider
     * that only honours `w` would leave the tail of the previous file behind and the size on disk
     * would still be the old one. Both modes are tried; a provider supporting neither keeps its file
     * unchanged, which costs nothing but the re-encode.
     */
    suspend fun overwrite(context: Context, treeUri: Uri, docId: String, source: File): Boolean =
        withContext(Dispatchers.IO) {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            // The staged copy and the index still describe the sticker as it was before the first
            // write-back, so without this the same bytes would be written again on every insert
            // until the folder is scanned afresh. Equal size means the work is already done.
            if (describe(context, uri).size == source.length()) return@withContext true
            for (mode in arrayOf("wt", "w")) {
                try {
                    val written = context.contentResolver.openOutputStream(uri, mode)?.use { out ->
                        source.inputStream().use { it.copyTo(out) }
                        true
                    } ?: false
                    if (written) return@withContext true
                } catch (e: Exception) {
                    flogError { "Failed to overwrite sticker $docId in mode $mode: ${e.message}" }
                }
            }
            false
        }

    /** Deletes one sticker from the folder. Returns false when the provider refuses. */
    suspend fun delete(context: Context, treeUri: Uri, docId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            flogError { "Failed to delete sticker $docId: ${e.message}" }
            false
        }
    }

    private data class SourceMeta(val displayName: String, val mime: String, val size: Long)

    private fun describe(context: Context, source: Uri): SourceMeta {
        val resolver = context.contentResolver
        var displayName = ""
        var size = 0L
        try {
            resolver.query(source, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex >= 0) displayName = cursor.getString(nameIndex).orEmpty()
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            // A provider is free to answer nothing; the fallbacks below cover it.
        }
        val mime = resolver.getType(source)
            ?: StickerScanner.mimeFor(displayName, null)
            ?: ""
        return SourceMeta(displayName, mime, size)
    }

    /**
     * The name the copy gets. Sharing frequently hands over a URI with no display name at all — the
     * fallback has to be *stable per share*, not a timestamp, or the duplicate check never matches.
     */
    internal fun fileName(displayName: String, extension: String): String {
        val base = StickerScanner.displayLabel(displayName)
            .replace(Regex("""[/\\:*?"<>|]"""), "_")
            .trim()
            .take(64)
            .ifBlank { "sticker" }
        return "$base.$extension"
    }

    private fun existingNamesAndSizes(
        context: Context,
        treeUri: Uri,
        rootDocId: String,
    ): MutableMap<String, Long> {
        val out = HashMap<String, Long>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    out[name] = if (cursor.isNull(1)) 0L else cursor.getLong(1)
                }
            }
        } catch (e: Exception) {
            // Worst case the duplicate check does nothing and a file is copied twice.
        }
        return out
    }

    /**
     * A best-effort starting point for the file picker, inside another app's media folder.
     *
     * Since Android 11 `Android/data` is closed to the picker but `Android/media` is not, and that is
     * where apps like WhatsApp keep what they have written to shared storage. The value is only a
     * hint — if the path does not exist, or the stickers never left the app's private database, the
     * picker simply opens where it always does. Nothing depends on it being right, which is what
     * makes it safe to offer several of them in a list.
     */
    fun mediaFolderHint(packageName: String, relativePath: String): Uri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:Android/media/$packageName/$relativePath",
    )

    /** The same, for the ordinary shared folders: `Download`, `Pictures`, `DCIM`. */
    fun publicFolderHint(name: String): Uri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:$name",
    )

    /** The URIs carried by a share, whether it was one image or several. */
    fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtraCompat(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE ->
            intent.getParcelableArrayListExtraCompat(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }
}

/**
 * One line the user can act on, shared by the share sheet and the settings screen.
 *
 * Counts that are zero are left out: "3 hinzugefügt" is the whole story most of the time, and naming
 * every category of non-event would bury the one that matters.
 */
fun stickerImportSummary(context: Context, result: StickerWriter.ImportResult): String {
    val parts = ArrayList<String>(4)
    if (result.imported > 0) {
        parts += context.stringRes(R.string.sticker__import_added, "n" to result.imported)
    }
    if (result.skippedDuplicate > 0) {
        parts += context.stringRes(R.string.sticker__import_duplicate, "n" to result.skippedDuplicate)
    }
    if (result.skippedUnsupported > 0) {
        parts += context.stringRes(R.string.sticker__import_unsupported, "n" to result.skippedUnsupported)
    }
    if (result.failed > 0) {
        parts += context.stringRes(R.string.sticker__import_failed, "n" to result.failed)
    }
    return parts.joinToString(" · ").ifBlank { context.stringRes(R.string.sticker__import_nothing) }
}

@Suppress("DEPRECATION")
private fun Intent.getParcelableExtraCompat(name: String): Uri? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, Uri::class.java)
    } else {
        getParcelableExtra(name)
    }

@Suppress("DEPRECATION")
private fun Intent.getParcelableArrayListExtraCompat(name: String): ArrayList<Uri>? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(name, Uri::class.java)
    } else {
        getParcelableArrayListExtra(name)
    }
