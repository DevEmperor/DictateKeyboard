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

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * The user's own sticker collection, as read from a folder they picked (issue #280).
 *
 * Everything here is a plain description of what is on disk — no URIs, no Android types — so the index
 * can be written to a JSON file and read back on the next keyboard start without touching the Storage
 * Access Framework at all. The actual [android.net.Uri] is rebuilt on demand from [StickerIndex.treeUri]
 * plus [StickerItem.docId], which is also what makes a stale index harmless: a document that has since
 * been deleted simply fails to load its thumbnail instead of corrupting anything.
 */
@Serializable
data class StickerItem(
    /** SAF document id, unique within the picked tree and stable across renames of the tree itself. */
    val docId: String,
    /** File name without its extension — shown as the accessibility label and used for sorting. */
    val name: String,
    /** MIME type as reported by the provider, or derived from the extension when it reports none. */
    val mime: String,
    /** Last-modified stamp, part of the cache file name so a replaced file is not served from cache. */
    val lastModified: Long,
)

/**
 * One tab in the panel: either the loose files in the picked folder itself ([id] empty, the "All" tab)
 * or one of its subfolders. Only one level deep — a sticker collection two folders down is a filing
 * system, not a keyboard, and supporting it would mean carrying navigation state through the panel.
 */
@Serializable
data class StickerCategory(
    /** Document id of the subfolder, or [ROOT_ID] for the loose files directly in the picked folder. */
    val id: String,
    /** Folder name, shown on the tab. Empty for the root category, which uses a translated label. */
    val name: String,
    val items: List<StickerItem>,
) {
    companion object {
        const val ROOT_ID = ""
    }
}

/**
 * The scanned collection. [treeUri] is stored alongside so a stale index belonging to a folder the user
 * has since replaced can be detected and thrown away rather than pointing at documents of another tree.
 */
@Serializable
data class StickerIndex(
    val treeUri: String,
    val categories: List<StickerCategory>,
) {
    /**
     * Every sticker across all categories, in tab order. Backs the aggregated rows of the "All" tab.
     *
     * Computed once at construction rather than on each read: the panel keys its per-page memoization on
     * this list, and a fresh list on every access would defeat that and rebuild the lookup on every frame.
     */
    @Transient
    val allItems: List<StickerItem> = categories.flatMap { it.items }

    val isEmpty: Boolean
        get() = categories.all { it.items.isEmpty() }

    /**
     * Which category a sticker actually lives in — not the tab it happens to be shown on.
     *
     * The first tab aggregates favourites and recents from every folder, so a sticker visible there
     * may belong to any pack. Moving it needs its real parent, and this is where that is known.
     */
    fun categoryOf(docId: String): String? =
        categories.firstOrNull { category -> category.items.any { it.docId == docId } }?.id

    fun findItem(docId: String): StickerItem? {
        for (category in categories) {
            val hit = category.items.firstOrNull { it.docId == docId }
            if (hit != null) return hit
        }
        return null
    }

    companion object {
        val Empty = StickerIndex(treeUri = "", categories = emptyList())
    }
}
