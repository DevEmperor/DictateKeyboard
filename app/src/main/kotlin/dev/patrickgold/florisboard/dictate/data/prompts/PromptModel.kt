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

import dev.patrickgold.florisboard.dictate.DictateReasoningEffort
import kotlinx.serialization.Serializable

/**
 * A single user-defined rewording prompt.
 *
 * Ported 1:1 from the original Dictate `PromptModel` so the on-disk representation in
 * `prompts.db` stays identical (see [PromptsDatabaseHelper] and `docs/COMPATIBILITY.md`).
 *
 * [Serializable] so the prompt list can be exported to / imported from a Backup & Restore
 * archive as JSON (see `BackupScreen`/`RestoreScreen`).
 *
 * Special synthetic ids used by the keyboard UI (never persisted):
 *  - [ID_INSTANT_PROMPT]  (-1) live/instant prompt button
 *  - [ID_ADD_PROMPT]      (-2) "add new prompt" button
 *  - [ID_SELECT_ALL]      (-3) "select all / deselect" toggle button
 */
@Serializable
data class PromptModel(
    var id: Int,
    var pos: Int,
    var name: String?,
    var prompt: String?,
    var requiresSelection: Boolean,
    var autoApply: Boolean,
    // Per-prompt reasoning-effort override (issue #155); null = use the global rewording setting.
    var reasoningEffort: DictateReasoningEffort? = null,
    // The custom wire value used when [reasoningEffort] is CUSTOM (issue #186); null/blank = omitted.
    var reasoningEffortCustom: String? = null,
    // Optional typed shortcut that expands this snippet while typing (issue #283); null/blank = none.
    // Only ever honoured for `[snippet]` prompts — see [snippetBody] and `SnippetTriggers`.
    var trigger: String? = null,
    // Where this prompt came from: the community-library entry id it was imported from, or null for a
    // prompt the user wrote themselves (issue #303). Says nothing about what the prompt does — it is
    // what lets the library show "Added" for a prompt that is actually still there, and stop showing it
    // the moment the row is deleted. Deliberately part of the row: three call sites already forgot to
    // keep the old parallel set in sync.
    var libraryId: String? = null,
) {
    fun isPersisted(): Boolean = id >= 0

    companion object {
        const val ID_INSTANT_PROMPT = -1
        const val ID_ADD_PROMPT = -2
        const val ID_SELECT_ALL = -3
    }
}

/** The literal text of this prompt if it is a snippet, else `null` — see [snippetBodyOf]. */
fun PromptModel.snippetBody(): String? = snippetBodyOf(prompt)

/**
 * The prompts of a backup that a merge restore should add: [incoming] minus every prompt that [existing]
 * already holds in the same form. Ids and positions are not compared — they are where a prompt sits, not
 * what it is.
 *
 * A merge used to append the whole backup, so restoring the same archive twice (or one taken from this
 * very phone, which is the usual way to try a restore) left every prompt in the list twice.
 */
fun promptsMissingFrom(existing: List<PromptModel>, incoming: List<PromptModel>): List<PromptModel> {
    val have = existing.mapTo(HashSet()) { it.contentKey() }
    return incoming.filter { have.add(it.contentKey()) }
}

private fun PromptModel.contentKey() = listOf(
    name.orEmpty(),
    prompt.orEmpty(),
    requiresSelection,
    autoApply,
    reasoningEffort,
    reasoningEffortCustom?.takeIf { it.isNotBlank() },
    trigger?.takeIf { it.isNotBlank() },
    libraryId?.takeIf { it.isNotBlank() },
)
