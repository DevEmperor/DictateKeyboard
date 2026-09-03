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
 * Moves [docId] [delta] places along [list] and says whether anything changed.
 *
 * Clamped at both ends rather than wrapping: an arrow that sends the first entry to the far end reads
 * as a bug every time it happens by accident. Shared by the favourites row and the pack tabs, which
 * are the same operation seen from two places (issue #317).
 */
internal fun moveWithin(list: MutableList<String>, docId: String, delta: Int): Boolean {
    val from = list.indexOf(docId)
    if (from < 0) return false
    val to = (from + delta).coerceIn(0, list.size - 1)
    if (to == from) return false
    list.removeAt(from)
    list.add(to, docId)
    return true
}

/**
 * What the user has decided about their packs beyond what the folder itself says (issue #317): the
 * order the tabs sit in, and which sticker stands for each pack.
 *
 * **Keyed by pack name, not by document id, and that is not an oversight.**
 * `DocumentsContract.renameDocument` hands back a *new* URI, and the common storage provider builds a
 * document id out of the path — so a renamed pack has a different id, and anything anchored to the old
 * one would quietly disappear the first time someone corrected a typo. The name is what survives, and
 * a rename is a moment we are present for, so the entry can be carried across (see
 * [StickerPackSettingsHelper.renamed]).
 *
 * Both fields are sparse. A pack nobody has moved is not in [order]; a pack with no picture chosen is
 * not in [icons]. That is what makes the defaults free: an untouched installation stores an empty
 * object and the folder's own alphabetical order stands.
 */
@Serializable
data class StickerPackSettings(
    /** Pack names in the order their tabs should appear. Packs not named here follow, alphabetically. */
    val order: List<String> = emptyList(),
    /** Pack name to the document id of the sticker that stands for it on the tab. */
    val icons: Map<String, String> = emptyMap(),
) {
    object Serializer : PreferenceSerializer<StickerPackSettings> {
        override fun serialize(value: StickerPackSettings): String = Json.encodeToString(value)

        override fun deserialize(value: String): StickerPackSettings = try {
            Json.decodeFromString<StickerPackSettings>(value)
        } catch (e: Exception) {
            flogError { "Failed to deserialize StickerPackSettings: $e" }
            Empty
        }
    }

    companion object {
        val Empty = StickerPackSettings()

        /**
         * [categories] in the order the tabs should be drawn.
         *
         * Three rules, in this order: the combined tab is not a pack and always comes first; a pack the
         * user has placed sits where they placed it; a pack they have not stays behind those, in the
         * folder's own alphabetical order — which survives because the sort is stable.
         */
        fun ordered(
            categories: List<StickerCategory>,
            order: List<String>,
        ): List<StickerCategory> {
            if (order.isEmpty()) return categories
            return categories.sortedBy { category ->
                when {
                    category.id == StickerCategory.ROOT_ID -> -1
                    else -> order.indexOf(category.name).takeIf { it >= 0 } ?: Int.MAX_VALUE
                }
            }
        }
    }
}

/**
 * Every write to [StickerPackSettings], behind one lock — the same reason [StickerHistoryHelper] has
 * one: two taps on an arrow in quick succession would otherwise read the same list and lose a write.
 */
object StickerPackSettingsHelper {
    private val guard = Mutex(locked = false)

    private suspend fun edit(
        prefs: FlorisPreferenceModel,
        block: (StickerPackSettings) -> StickerPackSettings,
    ) = guard.withLock {
        prefs.sticker.packSettings.set(block(prefs.sticker.packSettings.get()))
    }

    /**
     * Records the tab row as the user has just arranged it.
     *
     * The whole row, not the one pack that moved: most of the order is usually implied by the alphabet
     * rather than written down, and writing it all out is also what stops a later rename or a new pack
     * from shuffling the rest around afterwards.
     */
    suspend fun setOrder(prefs: FlorisPreferenceModel, names: List<String>) = edit(prefs) {
        it.copy(order = names.toList())
    }

    /** Picks the sticker that stands for [pack] on its tab, or drops it when [docId] is null. */
    suspend fun setIcon(prefs: FlorisPreferenceModel, pack: String, docId: String?) = edit(prefs) {
        val icons = it.icons.toMutableMap()
        if (docId == null) icons.remove(pack) else icons[pack] = docId
        it.copy(icons = icons.toMap())
    }

    /** Carries a pack's place and picture across a rename, which is the whole reason for keying by name. */
    suspend fun renamed(prefs: FlorisPreferenceModel, from: String, to: String) = edit(prefs) { current ->
        val icons = current.icons.toMutableMap()
        icons.remove(from)?.let { icons[to] = it }
        current.copy(
            order = current.order.map { if (it == from) to else it },
            icons = icons.toMap(),
        )
    }

    /** Forgets a deleted pack, so its name does not sit in the order holding a place for nothing. */
    suspend fun forget(prefs: FlorisPreferenceModel, name: String) = edit(prefs) { current ->
        current.copy(
            order = current.order.filterNot { it == name },
            icons = current.icons.filterKeys { it != name },
        )
    }
}
