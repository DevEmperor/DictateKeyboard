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
 * The one directory whose files may be handed to another app.
 *
 * Rich content is inserted by URI, not by bytes: the target app receives a `content://` URI backed by
 * this app's FileProvider and reads it afterwards, so the file has to outlive the insert and has to sit
 * under a path declared in `res/xml/file_paths.xml`. Both the GIF search and the local sticker panel
 * therefore stage their file here first.
 *
 * The name is historical — the path was introduced for GIFs and is spelled `gif-media` in
 * `file_paths.xml`. Renaming it would mean touching a declared FileProvider path for cosmetic reasons,
 * which is not worth doing to a mechanism other apps hold URIs into.
 */
object MediaCache {
    private const val DirName = "gif-media"
    private const val ConvertedDirName = "sticker-converted"

    /** Roughly a folder of stickers plus a long tail of inserted GIFs, and small enough to stay polite. */
    private const val MaxBytes = 150L * 1024L * 1024L

    /**
     * A far larger budget for converted files, because throwing one away costs a second to rebuild
     * rather than a file copy — but a budget all the same. A folder of a thousand animated stickers,
     * all of them inserted once, would otherwise sit in app storage forever at half a gigabyte, and
     * that is the kind of number people notice in the system settings and blame the app for.
     */
    private const val MaxConvertedBytes = 300L * 1024L * 1024L

    fun dir(context: Context): File = File(context.cacheDir, DirName).apply { mkdirs() }

    /**
     * Where a converted or re-encoded file is kept, and deliberately **not** the cache.
     *
     * Staging a sticker is a file copy and costs milliseconds; re-encoding an animated one costs
     * about a second of decoding, scaling and compression. Losing that to a cache eviction — Android
     * clears `cacheDir` whenever it feels the need, and [prune] does so on purpose — would mean
     * paying it again for a sticker the user sends every day. So the result lives in `filesDir`,
     * survives until the sticker itself changes, and is served through the same FileProvider (see the
     * `sticker_converted` path in `file_paths.xml`).
     */
    fun convertedDir(context: Context): File =
        File(context.filesDir, ConvertedDirName).apply { mkdirs() }

    /**
     * Drops the least recently modified files until the directory fits in [MaxBytes].
     *
     * Until this existed nothing ever deleted from here, so every GIF a user had ever sent stayed on
     * their device forever. Cheap to call — it only lists the directory, and only sorts when over
     * budget — so both callers run it after staging a file rather than on a schedule.
     */
    fun prune(context: Context) = pruneDir(dir(context), MaxBytes)

    /** The same, for the durable conversion store. */
    fun pruneConverted(context: Context) = pruneDir(convertedDir(context), MaxConvertedBytes)

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
