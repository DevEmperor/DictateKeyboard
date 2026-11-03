/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.data.mappings

import dev.patrickgold.florisboard.app.PreferenceJson
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.jetpref.datastore.model.PreferenceSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * User-defined find-and-replace dictionary (issue #129): a deterministic, on-device substitution applied
 * to the finished transcript right before it is inserted — independent of the AI rewording stage, so it
 * costs no tokens and is exact. This is distinct from `customWords`, which only biases the Whisper prompt.
 *
 * Persisted as a single JetPref `custom` preference (see `AppPrefs.dictate.customMappings`) via
 * [Serializer], mirroring the `ProviderAccounts` pattern. Applied by `DictateController` through [apply].
 */
@Serializable
data class DictateMappings(
    val items: List<Mapping> = emptyList(),
) {
    /**
     * A single replacement rule: every occurrence of [from] in the transcript becomes [to].
     *
     * @param matchCase when true the match is case-sensitive; otherwise case-insensitive (default).
     * @param wholeWord when true (default) only whole-word occurrences match (word boundaries), so
     *   "cat" does not match inside "category". Turn off for phrases, symbols, or substrings.
     */
    @Serializable
    data class Mapping(
        val from: String,
        val to: String,
        val matchCase: Boolean = false,
        val wholeWord: Boolean = true,
    )

    /** Returns a copy with [mapping] appended. */
    fun add(mapping: Mapping): DictateMappings = copy(items = items + mapping)

    /** Returns a copy with the mapping at [index] replaced by [mapping] (no-op if out of range). */
    fun set(index: Int, mapping: Mapping): DictateMappings =
        if (index in items.indices) copy(items = items.toMutableList().apply { this[index] = mapping }) else this

    /** Returns a copy with the mapping at [index] removed (no-op if out of range). */
    fun removeAt(index: Int): DictateMappings =
        if (index in items.indices) copy(items = items.toMutableList().apply { removeAt(index) }) else this

    /**
     * Applies every rule to [text] in order and returns the result. Each rule is matched literally
     * (the [Mapping.from] is regex-escaped) with optional word boundaries and case sensitivity; the
     * replacement [Mapping.to] is inserted verbatim (no group/`$` interpretation). Empty `from` rules
     * and any rule whose pattern fails to compile are skipped, so a bad entry never breaks output.
     */
    fun apply(text: String): String {
        if (items.isEmpty() || text.isEmpty()) return text
        var result = text
        for (m in items) {
            if (m.from.isEmpty()) continue
            val options = if (m.matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
            val core = Regex.escape(m.from)
            val pattern = if (m.wholeWord) "\\b$core\\b" else core
            val regex = runCatching { Regex(pattern, options) }.getOrNull() ?: continue
            // Lambda form so `to` is inserted literally (no $group / backslash interpretation).
            result = regex.replace(result) { m.to }
        }
        return result
    }

    object Serializer : PreferenceSerializer<DictateMappings> {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        override fun serialize(value: DictateMappings): String =
            PreferenceJson.encode(json.encodeToString(value))

        override fun deserialize(value: String): DictateMappings = try {
            json.decodeFromString(PreferenceJson.decode(value))
        } catch (e: Exception) {
            flogError { "Failed to deserialize DictateMappings: $e" }
            Empty
        }
    }

    companion object {
        val Empty = DictateMappings(emptyList())
    }
}
