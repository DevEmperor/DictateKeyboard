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

import android.content.ClipDescription
import android.net.Uri
import androidx.core.content.FileProvider

/**
 * The app's file provider, taught to answer what receiving apps actually ask.
 *
 * `androidx.core.content.FileProvider` implements `getType()` from the file extension but leaves
 * `getStreamTypes()` at the `ContentProvider` default, which answers `null`. That matters: an app
 * handed a URI through the Commit Content API is entitled to ask which streams it can read as an
 * image, and a `null` there reads as "none" — the image is simply dropped, with no
 * error anywhere. The clipboard provider in this app has always implemented it
 * ([dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardMediaProvider]), which is one reason
 * pasting an image worked in apps where inserting a sticker did not.
 *
 * Nothing else changes: same authority, same declared paths, same `getUriForFile` call sites. The
 * backup export shares this provider and simply gains a correct answer to the same question.
 */
class MediaFileProvider : FileProvider() {

    /**
     * The types this URI can be read as, filtered by what the caller asked for.
     *
     * Derived from `getType()` rather than kept in a table, so it can never drift from what the file
     * actually is.
     */
    override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? {
        val type = getType(uri) ?: return null
        // Every WebP is also offered under WhatsApp's own name for that format, so the answer here
        // matches the type the file was committed under. Narrowing this to WhatsApp's published
        // sticker dimensions would break the handover for exactly the animated files that work.
        val types = if (type == "image/webp") {
            arrayOf(type, MediaFormat.WA_STICKER)
        } else {
            arrayOf(type)
        }
        val matching = types.filter { ClipDescription.compareMimeTypes(it, mimeTypeFilter) }
        return matching.takeIf { it.isNotEmpty() }?.toTypedArray()
    }
}
