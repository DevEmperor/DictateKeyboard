/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.gif

import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.jetpref.datastore.model.PreferenceSerializer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persisted quick-access history for the GIF panel: the most recent search terms (shown as tappable
 * chips) and the most recently inserted GIFs (shown as a quick-repeat row). Newest first.
 */
@Serializable
data class GifHistory(
    val recentSearches: List<String> = emptyList(),
    val recentGifs: List<GifItem> = emptyList(),
) {
    object Serializer : PreferenceSerializer<GifHistory> {
        override fun serialize(value: GifHistory): String {
            return Json.encodeToString(value)
        }

        override fun deserialize(value: String): GifHistory {
            return try {
                Json.decodeFromString(value)
            } catch (e: Exception) {
                flogError { "Failed to deserialize GifHistory: $e" }
                Empty
            }
        }
    }

    companion object {
        val Empty = GifHistory()
        const val MAX_SEARCHES = 12
        const val MAX_GIFS = 24
    }
}

/** Mutations of the GIF history; all suspend and serialized behind a single mutex. */
object GifHistoryHelper {
    private val guard = Mutex(locked = false)

    /** Records a search term (trimmed), moving/prepending it and capping the list. Blanks are ignored. */
    suspend fun addSearch(prefs: FlorisPreferenceModel, term: String): Unit = guard.withLock {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return
        val current = prefs.gif.history.get()
        val updated = buildList {
            add(trimmed)
            addAll(current.recentSearches.filterNot { it.equals(trimmed, ignoreCase = true) })
        }.take(GifHistory.MAX_SEARCHES)
        prefs.gif.history.set(current.copy(recentSearches = updated))
    }

    /** Records an inserted GIF, moving/prepending it (deduped by id) and capping the list. */
    suspend fun addInsertedGif(prefs: FlorisPreferenceModel, gif: GifItem): Unit = guard.withLock {
        val current = prefs.gif.history.get()
        val updated = buildList {
            add(gif)
            addAll(current.recentGifs.filterNot { it.id == gif.id })
        }.take(GifHistory.MAX_GIFS)
        prefs.gif.history.set(current.copy(recentGifs = updated))
    }

    /** Removes a single recent search term (long-press to delete). */
    suspend fun removeSearch(prefs: FlorisPreferenceModel, term: String): Unit = guard.withLock {
        val current = prefs.gif.history.get()
        prefs.gif.history.set(current.copy(recentSearches = current.recentSearches.filterNot { it == term }))
    }

    /** Removes a single recently used GIF by id (long-press to delete). */
    suspend fun removeGif(prefs: FlorisPreferenceModel, id: String): Unit = guard.withLock {
        val current = prefs.gif.history.get()
        prefs.gif.history.set(current.copy(recentGifs = current.recentGifs.filterNot { it.id == id }))
    }

    suspend fun clear(prefs: FlorisPreferenceModel): Unit = guard.withLock {
        prefs.gif.history.set(GifHistory.Empty)
    }
}
