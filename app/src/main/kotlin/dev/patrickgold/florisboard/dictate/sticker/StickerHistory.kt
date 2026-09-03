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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Favourites and recently used stickers: **one list each, for the whole collection** (issues #280, #308).
 *
 * A tab shows the part of these lists that it can resolve. The combined tab can resolve every sticker,
 * so it shows all of them; a pack tab only knows its own files, so it shows the ones that live in it.
 * The filtering is not a step anywhere — the panel looks each id up in the tab's own items and drops
 * what it does not find, which it has to do regardless for stickers whose file has since disappeared.
 *
 * This replaces a per-category map, and the reason is worth keeping. Two sets of lists have to be kept
 * in agreement by whoever writes them, and they were not: the write was keyed on the tab the sticker
 * was tapped from, but the combined tab is not a folder, so the same action landed in different lists
 * depending on where the user happened to be standing. Keying the write on the sticker's own folder
 * fixed that instance and left the shape that produced it. One list cannot disagree with itself.
 *
 * What is given up is a per-pack *order* — a pack could arrange its favourites differently from the
 * combined view. Nothing ever showed that difference to anyone.
 *
 * Stored as document ids rather than whole items: the index already knows the rest, and an id that no
 * longer resolves is simply skipped when the rows are built.
 */
@Serializable
data class StickerHistory(
    val pinned: List<String> = emptyList(),
    val recent: List<String> = emptyList(),
) {
    fun isPinned(docId: String): Boolean = docId in pinned

    object Serializer : PreferenceSerializer<StickerHistory> {
        override fun serialize(value: StickerHistory): String {
            return Json.encodeToString(value)
        }

        /**
         * Reads the current shape, and the per-category one that shipped in 6.1.
         *
         * The old blob held a map of lists keyed by category, of which the combined entry was the only
         * one a user had ever seen — the per-pack entries were half-filled by construction. Taking the
         * combined entry and dropping the rest is therefore not data loss: it is the only version of
         * that data that was ever true.
         */
        override fun deserialize(value: String): StickerHistory {
            return try {
                val root = Json.parseToJsonElement(value).jsonObject
                if (root["pinned"] is JsonObject || root["recent"] is JsonObject) {
                    StickerHistory(
                        pinned = root.legacyCombined("pinned"),
                        recent = root.legacyCombined("recent"),
                    )
                } else {
                    Json.decodeFromJsonElement(root)
                }
            } catch (e: Exception) {
                flogError { "Failed to deserialize StickerHistory: $e" }
                Empty
            }
        }

        private fun JsonObject.legacyCombined(field: String): List<String> =
            (this[field] as? JsonObject)?.get(LEGACY_COMBINED_KEY)
                ?.jsonArray?.map { it.jsonPrimitive.content }
                .orEmpty()
    }

    companion object {
        val Empty = StickerHistory()

        /**
         * Key the 6.1 blob stored the combined lists under. Only [Serializer] still needs it, to read
         * an installation that has not been written since. A NUL character cannot appear in a SAF
         * document id, which is why it could never collide with a real category.
         */
        private const val LEGACY_COMBINED_KEY = "\u0000all"
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
        block: (pinned: MutableList<String>, recent: MutableList<String>) -> Unit,
    ) = guard.withLock {
        val current = prefs.sticker.historyData.get()
        val pinned = current.pinned.toMutableList()
        val recent = current.recent.toMutableList()
        block(pinned, recent)
        prefs.sticker.historyData.set(StickerHistory(pinned = pinned.toList(), recent = recent.toList()))
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
     * Reorders the favourites (issue #317).
     *
     * Re-pinning already moves a sticker to the front, so the gap this fills is the finer one: putting
     * a favourite *somewhere in particular* without taking the whole row apart and rebuilding it in
     * order, which is what the requester was doing.
     */
    suspend fun movePinned(prefs: FlorisPreferenceModel, docId: String, delta: Int) =
        edit(prefs) { pinned, _ -> moveWithin(pinned, docId, delta) }

    /**
     * Records a use. Pinned stickers stay where they are — a favourite is not demoted to a recent by
     * being used.
     */
    suspend fun markUsed(prefs: FlorisPreferenceModel, docId: String) {
        val maxSize = prefs.sticker.historyRecentMaxSize.get()
        edit(prefs) { pinned, recent ->
            if (docId !in pinned) {
                prependCapped(recent, docId, maxSize)
            }
        }
    }

    /** Pins a sticker and drops it from the recents, where it would otherwise appear twice. */
    suspend fun pin(prefs: FlorisPreferenceModel, docId: String) = edit(prefs) { pinned, recent ->
        prependCapped(pinned, docId, maxSize = 0)
        recent.remove(docId)
    }

    suspend fun unpin(prefs: FlorisPreferenceModel, docId: String) = edit(prefs) { pinned, _ ->
        pinned.remove(docId)
    }

    suspend fun removeRecent(prefs: FlorisPreferenceModel, docId: String) = edit(prefs) { _, recent ->
        recent.remove(docId)
    }

    /**
     * Drops a sticker from both lists.
     *
     * Used when the file itself is deleted: the panel skips ids it cannot resolve, so leaving them
     * behind would not show a broken image, but it would silently shorten the favourites row and make
     * "keep 16 recents" mean something else.
     */
    suspend fun forget(prefs: FlorisPreferenceModel, docId: String) = edit(prefs) { pinned, recent ->
        pinned.remove(docId)
        recent.remove(docId)
    }

    suspend fun clearRecent(prefs: FlorisPreferenceModel) = edit(prefs) { _, recent ->
        recent.clear()
    }

    suspend fun clearPinned(prefs: FlorisPreferenceModel) = edit(prefs) { pinned, _ ->
        pinned.clear()
    }
}
