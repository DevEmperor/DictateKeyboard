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

    /** Roughly a folder of stickers plus a long tail of inserted GIFs, and small enough to stay polite. */
    private const val MaxBytes = 150L * 1024L * 1024L

    fun dir(context: Context): File = File(context.cacheDir, DirName).apply { mkdirs() }

    /**
     * Drops the least recently modified files until the directory fits in [MaxBytes].
     *
     * Until this existed nothing ever deleted from here, so every GIF a user had ever sent stayed on
     * their device forever. Cheap to call — it only lists the directory, and only sorts when over
     * budget — so both callers run it after staging a file rather than on a schedule.
     */
    fun prune(context: Context) {
        try {
            val files = dir(context).listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= MaxBytes) return
            for (file in files.sortedBy { it.lastModified() }) {
                val size = file.length()
                if (file.delete()) total -= size
                if (total <= MaxBytes) break
            }
        } catch (e: Exception) {
            flogError { "Failed to prune media cache: ${e.message}" }
        }
    }
}
