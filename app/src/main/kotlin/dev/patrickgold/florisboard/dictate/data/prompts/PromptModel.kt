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
) {
    fun isPersisted(): Boolean = id >= 0

    companion object {
        const val ID_INSTANT_PROMPT = -1
        const val ID_ADD_PROMPT = -2
        const val ID_SELECT_ALL = -3
    }
}
