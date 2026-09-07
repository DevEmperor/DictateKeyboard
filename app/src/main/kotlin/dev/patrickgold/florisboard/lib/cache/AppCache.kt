/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.lib.cache

import android.content.Context
import org.florisboard.lib.kotlin.io.deleteContentsRecursively
import java.io.File

/**
 * The app's scratch space, measured and emptied on request (issue #337).
 *
 * Everything the app makes and can make again lives in `cacheDir`: the copy of a shared file, the
 * pieces a long one was cut into, downloaded GIFs, decoded images, imported stickers on their way in.
 * It is not permanent — `FlorisApplication` empties the whole thing every time the process starts
 * cold — but within a session a shared video can leave a hundred megabytes in there, and the system's
 * own storage screen shows a number with no way to act on it from here.
 *
 * Emptying it never loses anything the user typed, recorded or saved: dictation history, its retained
 * audio, themes, dictionaries and every setting live in `filesDir` or the database, which this does
 * not touch.
 */
object AppCache {

    /** Bytes currently held in the cache directory, walked fresh — there is no counter to read. */
    fun sizeBytes(context: Context): Long = sizeOf(context.applicationContext.cacheDir)

    /**
     * Empties the cache directory, keeping the directory itself.
     *
     * A recording or an import running at this moment loses the file it is writing, which is why this
     * is behind a confirmation rather than a plain button.
     */
    fun clear(context: Context) {
        runCatching { context.applicationContext.cacheDir?.deleteContentsRecursively() }
    }

    private fun sizeOf(file: File?): Long {
        file ?: return 0L
        // The tree is several levels deep — extensions unpack into subtrees of their own — and it is
        // being read while other parts of the app write into it, so a file that vanishes mid-walk is
        // normal rather than an error. A number that is a few kilobytes stale is fine; a crash is not.
        return runCatching {
            file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }
}
