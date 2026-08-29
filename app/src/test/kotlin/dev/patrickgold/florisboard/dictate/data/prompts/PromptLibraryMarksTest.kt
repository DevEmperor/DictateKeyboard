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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the community library may call "Added" (issue #303).
 *
 * The mark has to answer to the prompt list and to nothing else: it must survive an edit — that is why
 * it exists at all — and it must disappear the moment the prompt does, however it left. The old
 * SharedPreferences set only managed the first half, which is how the reporter ended up with five
 * prompts marked as added and none of them in his list.
 */
class PromptLibraryMarksTest {

    private fun entry(id: String, name: String, prompt: String) =
        PromptLibraryEntry(id = id, name = name, prompt = prompt)

    private fun row(id: Int, name: String, prompt: String, libraryId: String? = null) =
        PromptModel(
            id = id,
            pos = id,
            name = name,
            prompt = prompt,
            requiresSelection = true,
            autoApply = false,
            libraryId = libraryId,
        )

    private val fixGrammar = entry("fix-grammar", "Fix Grammar", "Fix the grammar of the text.")

    @Test
    fun `a deleted prompt loses its mark`() {
        val rows = listOf(row(1, "Fix Grammar", "Fix the grammar of the text.", "fix-grammar"))
        assertTrue(PromptLibraryMarks.isAdded(PromptLibraryMarks.installedIn(rows), fixGrammar))

        // The reporter's step: he deleted it on purpose and expected to be able to add it back.
        val afterDeletion = PromptLibraryMarks.installedIn(emptyList())
        assertFalse(PromptLibraryMarks.isAdded(afterDeletion, fixGrammar))
    }

    @Test
    fun `renaming and rewriting a prompt keeps its mark`() {
        val rows = listOf(row(1, "Corrige la grammaire", "Corrige la grammaire du texte.", "fix-grammar"))
        assertTrue(PromptLibraryMarks.isAdded(PromptLibraryMarks.installedIn(rows), fixGrammar))
    }

    @Test
    fun `a prompt imported before the column still matches by content`() {
        val rows = listOf(row(1, "Fix Grammar", "  fix the grammar of the TEXT.  "))
        assertTrue(PromptLibraryMarks.isAdded(PromptLibraryMarks.installedIn(rows), fixGrammar))
    }

    @Test
    fun `the carry-over reattaches only the ids whose prompt still exists`() {
        val entries = listOf(
            fixGrammar,
            entry("simplify", "Simplify", "Simplify the text."),
        )
        // The user kept "Fix Grammar" and deleted "Simplify"; both ids are stuck in the legacy set.
        val rows = listOf(row(1, "Fix Grammar", "Fix the grammar of the text."))
        val carried = PromptLibraryMarks.carryOver(rows, entries, setOf("fix-grammar", "simplify"))

        assertEquals(mapOf(1 to "fix-grammar"), carried)
    }

    @Test
    fun `the carry-over leaves rows that already know where they came from`() {
        val rows = listOf(row(1, "Fix Grammar", "Fix the grammar of the text.", "fix-grammar"))
        assertEquals(
            emptyMap(),
            PromptLibraryMarks.carryOver(rows, listOf(fixGrammar), setOf("fix-grammar")),
        )
    }

    @Test
    fun `without the legacy set nothing is claimed for the library`() {
        // The bundled example prompts share their text with library entries, so a content match alone
        // must never stamp a row as imported — a fresh install would otherwise "own" the whole library.
        val rows = listOf(row(1, "Fix Grammar", "Fix the grammar of the text."))
        assertEquals(emptyMap(), PromptLibraryMarks.carryOver(rows, listOf(fixGrammar), emptySet()))
    }
}
