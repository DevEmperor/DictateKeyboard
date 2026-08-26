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
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.io.File

/**
 * The directories whose files may be handed to another app.
 *
 * Rich content is inserted by URI, not by bytes: the target app receives a `content://` URI backed by
 * this app's FileProvider and reads it afterwards, so the file has to outlive the insert and has to sit
 * under a path declared in `res/xml/file_paths.xml`. Both the GIF search and the local sticker panel
 * therefore stage their file here first.
 *
 * Two of them, both under `cacheDir`: [dir] for the copy of whatever is being inserted, [convertedDir]
 * for a PNG or GIF made out of it. Nothing here is anything but a copy of something else, so clearing
 * the app's cache costs a little time and nothing at all.
 *
 * The first name is historical — the path was introduced for GIFs and is spelled `gif-media` in
 * `file_paths.xml`. Renaming it would mean touching a declared FileProvider path for cosmetic reasons,
 * which is not worth doing to a mechanism other apps hold URIs into.
 */
object MediaCache {
    private const val DirName = "gif-media"
    private const val ConvertedDirName = "sticker-converted"

    /** Roughly a folder of stickers plus a long tail of inserted GIFs, and small enough to stay polite. */
    private const val MaxBytes = 150L * 1024L * 1024L

    /**
     * A smaller budget for the derived files, since only apps that refuse WebP ever need one.
     */
    private const val MaxConvertedBytes = 100L * 1024L * 1024L

    fun dir(context: Context): File = File(context.cacheDir, DirName).apply { mkdirs() }

    /**
     * Where a PNG or GIF made from a sticker is kept.
     *
     * In the cache, and that is the point: these are derived files, wanted only by apps that will not
     * take a WebP, and losing one costs a second to make again. Somebody short of storage should be
     * able to clear the app's cache and have that space back, without the keyboard quietly holding a
     * second copy of their sticker collection somewhere they cannot reach it.
     *
     * Served through the FileProvider, so the matching `sticker_converted` path in `file_paths.xml`
     * has to be a `cache-path` and not a `files-path`.
     */
    fun convertedDir(context: Context): File =
        File(context.cacheDir, ConvertedDirName).apply { mkdirs() }

    /**
     * Drops the least recently modified files until the directory fits in [MaxBytes].
     *
     * Until this existed nothing ever deleted from here, so every GIF a user had ever sent stayed on
     * their device forever. Cheap to call — it only lists the directory, and only sorts when over
     * budget — so both callers run it after staging a file rather than on a schedule.
     */
    fun prune(context: Context) = pruneDir(dir(context), MaxBytes)

    /** The same, for the derived files. */
    fun pruneConverted(context: Context) {
        pruneDir(convertedDir(context), MaxConvertedBytes)
        dropLegacyConvertedStore(context)
    }

    /**
     * Removes the copy of this directory that older versions kept in `filesDir`.
     *
     * It lived there so a conversion would never have to be paid for twice, back when converting
     * happened at insert time. It does not any more, and a directory in `filesDir` is one the user
     * cannot clear from the system settings — so whatever is left of it goes. Can be deleted from
     * here once nobody is upgrading from a version that had it.
     */
    private fun dropLegacyConvertedStore(context: Context) {
        try {
            val legacy = File(context.filesDir, ConvertedDirName)
            if (!legacy.isDirectory) return
            legacy.listFiles()?.forEach { it.delete() }
            legacy.delete()
        } catch (e: Exception) {
            flogError { "Failed to remove the old converted-sticker store: ${e.message}" }
        }
    }

    private fun pruneDir(directory: File, budget: Long) {
        try {
            val files = directory.listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= budget) return
            for (file in files.sortedBy { it.lastModified() }) {
                val size = file.length()
                if (file.delete()) total -= size
                if (total <= budget) break
            }
        } catch (e: Exception) {
            flogError { "Failed to prune media cache: ${e.message}" }
        }
    }
}
