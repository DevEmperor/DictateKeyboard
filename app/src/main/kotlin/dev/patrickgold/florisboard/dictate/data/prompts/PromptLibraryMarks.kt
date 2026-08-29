/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.data.prompts

/**
 * Which community-library entries count as "already added" (issues #105, #303).
 *
 * Everything here is derived from the user's own prompt rows, so a prompt that is gone stops being
 * marked the moment it is gone — no matter how it left (deleted by hand, replaced by a JSON import,
 * overwritten by a restored backup). That is the whole point: the previous implementation kept a
 * separate set of ids in SharedPreferences that had no remove(), so the mark was a tombstone and the
 * reporter of #303 could never add his deleted prompts back.
 *
 * Deliberately free of `Context`, Compose and SQLite so the rule can be tested off-device.
 */
object PromptLibraryMarks {

    /** Ids and content keys of what the user currently has. Both are read from the same row list. */
    data class Installed(
        /** [PromptModel.libraryId] of every row that carries one — the primary, edit-proof signal. */
        val ids: Set<String>,
        /** name+prompt keys, the fallback for prompts imported before the column existed. */
        val keys: Set<String>,
    )

    /** Normalised name+prompt identity of a prompt, used for content matching. */
    fun keyOf(name: String?, prompt: String?): String =
        "${name.orEmpty().trim().lowercase()} ${prompt.orEmpty().trim().lowercase()}"

    fun installedIn(rows: List<PromptModel>): Installed = Installed(
        ids = rows.mapNotNull { it.libraryId?.takeIf { id -> id.isNotBlank() } }.toSet(),
        keys = rows.map { keyOf(it.name, it.prompt) }.toSet(),
    )

    /**
     * True if [entry] is in the user's list. The id branch survives renaming and editing a prompt; the
     * content branch covers prompts imported before v6, which carry no id. Both branches come from the
     * rows, so a deletion clears them together.
     */
    fun isAdded(installed: Installed, entry: PromptLibraryEntry): Boolean =
        entry.id in installed.ids || keyOf(entry.name, entry.prompt) in installed.keys

    /**
     * Carries the pre-v6 SharedPreferences set ([PromptLibraryLegacyStore]) over onto the rows: returns
     * `row id → library id` for every row that can still be attributed to a library entry.
     *
     * A row qualifies when it has no [PromptModel.libraryId] yet, its content matches a catalog entry
     * exactly, **and** that entry's id is in [legacyIds]. All three conditions are needed. The content
     * match alone would be wrong — the bundled example prompts share their text with library entries,
     * so every fresh install would claim to have imported them. The legacy id alone is not enough
     * either: it says some row *used to* match, never which one, which is exactly why that set could
     * not simply be corrected in place.
     *
     * Ids in [legacyIds] with no matching row are dropped, and that is the fix: they are the prompts
     * the user deleted.
     */
    fun carryOver(
        rows: List<PromptModel>,
        entries: List<PromptLibraryEntry>,
        legacyIds: Set<String>,
    ): Map<Int, String> {
        if (legacyIds.isEmpty() || entries.isEmpty()) return emptyMap()
        val idByKey = entries.associateBy({ keyOf(it.name, it.prompt) }, { it.id })
        val result = LinkedHashMap<Int, String>()
        for (row in rows) {
            if (!row.libraryId.isNullOrBlank()) continue
            val id = idByKey[keyOf(row.name, row.prompt)] ?: continue
            if (id in legacyIds) result[row.id] = id
        }
        return result
    }
}
