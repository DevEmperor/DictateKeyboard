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

import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.jetpref.datastore.model.PreferenceSerializer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Favourites and recently used stickers, kept **per category** (issues #280, #308).
 *
 * The request asked for each folder to remember its own favourites and its own recents, and for the
 * combined tab to keep a separate order of its own — so both maps are keyed by category id, with
 * [GLOBAL] holding the combined lists. Writing to two lists on every use is what makes the orders
 * independent: reordering a favourite inside one folder leaves the combined view alone, which is exactly
 * what was asked for and would be impossible if the combined list were derived on read.
 *
 * **Every write is keyed on the folder the sticker lives in, never on the tab it was tapped from.** The
 * combined tab shows stickers from every pack, so keying on the tab meant a sticker used from there
 * never reached its own pack's list, while the same sticker used from inside the pack did — the two
 * lists disagreed about the same action depending on where you were standing when you performed it.
 *
 * Stored as document ids rather than whole items: the index already knows the rest, and an id that no
 * longer resolves is simply skipped when the rows are built.
 */
@Serializable
data class StickerHistory(
    val pinned: Map<String, List<String>> = emptyMap(),
    val recent: Map<String, List<String>> = emptyMap(),
) {
    fun pinnedIn(categoryId: String): List<String> = pinned[categoryId].orEmpty()

    fun recentIn(categoryId: String): List<String> = recent[categoryId].orEmpty()

    fun isPinned(categoryId: String, docId: String): Boolean = docId in pinnedIn(categoryId)

    object Serializer : PreferenceSerializer<StickerHistory> {
        override fun serialize(value: StickerHistory): String {
            return Json.encodeToString(value)
        }

        override fun deserialize(value: String): StickerHistory {
            return try {
                Json.decodeFromString(value)
            } catch (e: Exception) {
                flogError { "Failed to deserialize StickerHistory: $e" }
                Empty
            }
        }
    }

    companion object {
        val Empty = StickerHistory()

        /**
         * Key of the combined lists shown on the "All" tab. A NUL character cannot appear in a SAF
         * document id, so this can never collide with a real category.
         */
        const val GLOBAL = "\u0000all"
    }
}

/**
 * All mutations of [StickerHistory], serialized behind one lock the way [dev.patrickgold.florisboard
 * .ime.media.emoji.EmojiHistoryHelper] is — two stickers tapped in quick succession would otherwise
 * read the same list and one of the two writes would be lost.
 */
object StickerHistoryHelper {
    private val guard = Mutex(locked = false)

    private suspend fun edit(
        prefs: FlorisPreferenceModel,
        block: (pinned: MutableMap<String, MutableList<String>>, recent: MutableMap<String, MutableList<String>>) -> Unit,
    ) = guard.withLock {
        val current = prefs.sticker.historyData.get()
        val pinned = current.pinned.mapValuesTo(HashMap()) { it.value.toMutableList() }
        val recent = current.recent.mapValuesTo(HashMap()) { it.value.toMutableList() }
        block(pinned, recent)
        // A list under the root id was written by older builds and is read by nothing: loose files have
        // no tab of their own, so their history is the combined one. Dropping it on the next write
        // rather than in a migration keeps the stored blob honest without a version to check.
        pinned.remove(StickerCategory.ROOT_ID)
        recent.remove(StickerCategory.ROOT_ID)
        prefs.sticker.historyData.set(
            StickerHistory(
                pinned = pinned.filterValues { it.isNotEmpty() }.mapValues { it.value.toList() },
                recent = recent.filterValues { it.isNotEmpty() }.mapValues { it.value.toList() },
            )
        )
    }

    /**
     * The history lists a sticker owned by [categoryId] belongs in.
     *
     * A pack keeps its own list *and* feeds the combined one. Loose files in the picked folder have no
     * tab of their own — the combined tab is where they are shown — so for them the two are the same
     * list and there is only one to write.
     */
    internal fun listKeysFor(categoryId: String): List<String> =
        if (categoryId == StickerCategory.ROOT_ID) {
            listOf(StickerHistory.GLOBAL)
        } else {
            listOf(categoryId, StickerHistory.GLOBAL)
        }

    private fun MutableMap<String, MutableList<String>>.prepend(key: String, docId: String, maxSize: Int) {
        val list = getOrPut(key) { mutableListOf() }
        prependCapped(list, docId, maxSize)
    }

    /**
     * Moves [docId] to the front of [list] and trims the tail to [maxSize] (0 meaning no limit).
     *
     * Split out so the rule can be tested without a preference store behind it: using a sticker again
     * must move it rather than duplicate it, and lowering the limit must drop from the far end, not
     * from the end the user just touched.
     */
    internal fun prependCapped(list: MutableList<String>, docId: String, maxSize: Int) {
        list.remove(docId)
        list.add(0, docId)
        while (maxSize > 0 && list.size > maxSize) {
            list.removeAt(list.size - 1)
        }
    }

    /**
     * Records a use of a sticker owned by [categoryId], in that pack's list and in the combined one.
     * Pinned stickers stay where they are — a favourite is not demoted to a recent by being used.
     */
    suspend fun markUsed(prefs: FlorisPreferenceModel, categoryId: String, docId: String) {
        val maxSize = prefs.sticker.historyRecentMaxSize.get()
        edit(prefs) { pinned, recent ->
            for (key in listKeysFor(categoryId)) {
                if (docId !in pinned[key].orEmpty()) {
                    recent.prepend(key, docId, maxSize)
                }
            }
        }
    }

    /** Pins in the sticker's own pack and in the combined list, and drops it from both recents. */
    suspend fun pin(prefs: FlorisPreferenceModel, categoryId: String, docId: String) = edit(prefs) { pinned, recent ->
        for (key in listKeysFor(categoryId)) {
            pinned.prepend(key, docId, maxSize = 0)
            recent[key]?.remove(docId)
        }
    }

    suspend fun unpin(prefs: FlorisPreferenceModel, categoryId: String, docId: String) = edit(prefs) { pinned, _ ->
        for (key in listKeysFor(categoryId)) {
            pinned[key]?.remove(docId)
        }
    }

    suspend fun removeRecent(prefs: FlorisPreferenceModel, categoryId: String, docId: String) = edit(prefs) { _, recent ->
        for (key in listKeysFor(categoryId)) {
            recent[key]?.remove(docId)
        }
    }

    /**
     * Drops a sticker from every list it appears in, whatever the category.
     *
     * Used when the file itself is deleted: the panel skips ids it cannot resolve, so leaving them
     * behind would not show a broken image, but it would silently shorten the favourites row and make
     * "keep 16 recents" mean something else.
     */
    suspend fun forget(prefs: FlorisPreferenceModel, docId: String) = edit(prefs) { pinned, recent ->
        for (list in pinned.values) list.remove(docId)
        for (list in recent.values) list.remove(docId)
    }

    suspend fun clearRecent(prefs: FlorisPreferenceModel) = edit(prefs) { _, recent ->
        recent.clear()
    }

    suspend fun clearPinned(prefs: FlorisPreferenceModel) = edit(prefs) { pinned, _ ->
        pinned.clear()
    }
}
