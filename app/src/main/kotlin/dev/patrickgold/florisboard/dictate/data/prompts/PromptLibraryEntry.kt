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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single prompt from the community prompt library (issue #105).
 *
 * This is a superset of the user-facing [PromptModel]: it carries the same four fields Dictate
 * persists in `prompts.db` (name/prompt/requiresSelection/autoApply) plus browse-only metadata
 * ([id], [category], [language], [author], [description]) that never gets written to the database.
 *
 * The on-the-wire shape is the library JSON hosted on the project's own repository (see
 * [PromptLibraryCatalog]); it is intentionally a strict extension of the prompt export format
 * (`{"version":1,"prompts":[…]}`) so an exported user file could be dropped straight into the library
 * with only metadata added.
 */
@Serializable
data class PromptLibraryEntry(
    /** Stable, unique slug (e.g. "fix-grammar"). Used as the de-dup / "already added" key. */
    val id: String,
    val name: String,
    val prompt: String,
    @SerialName("requiresSelection") val requiresSelection: Boolean = true,
    @SerialName("autoApply") val autoApply: Boolean = false,
    /** Free-form grouping shown as a filter chip, e.g. "Editing", "Tone", "Fun". Optional. */
    val category: String? = null,
    /** BCP-47-ish language code the prompt is written for/in, e.g. "en". null = language-agnostic. */
    val language: String? = null,
    /** Attribution shown in the preview, e.g. a GitHub handle. Optional. */
    val author: String? = null,
    /** One-line description shown under the name in the list. Optional. */
    val description: String? = null,
) {
    /**
     * Converts this library entry into a persistable [PromptModel]. [pos] is the target list position;
     * [id]/[category]/[language]/[author]/[description] are dropped — they are catalog metadata only.
     *
     * No typed trigger either (issue #283), deliberately: a prompt from the community library must
     * never arrive holding a shortcut that starts rewriting what the recipient types.
     */
    fun toPromptModel(pos: Int): PromptModel = PromptModel(
        id = 0,
        pos = pos,
        name = name,
        prompt = prompt,
        requiresSelection = requiresSelection,
        autoApply = autoApply,
    )
}

/** Root document of the hosted library file: `{"version":1,"prompts":[…]}`. */
@Serializable
data class PromptLibraryDocument(
    val version: Int = 1,
    val prompts: List<PromptLibraryEntry> = emptyList(),
)
