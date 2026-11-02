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

/** A merge restore adds what is new and leaves out what the list already holds. */
class PromptMergeTest {

    private fun prompt(id: Int, pos: Int, name: String, text: String, autoApply: Boolean = false) =
        PromptModel(id, pos, name, text, requiresSelection = true, autoApply = autoApply)

    private val mine = listOf(
        prompt(1, 0, "Fix Grammar", "Fix the grammar."),
        prompt(2, 1, "Improve Writing", "Improve the writing."),
    )

    @Test
    fun `restoring the same backup twice adds nothing the second time`() {
        val backup = mine.map { it.copy(id = it.id + 40, pos = it.pos + 7) }
        assertEquals(emptyList(), promptsMissingFrom(mine, backup))
    }

    @Test
    fun `a prompt that differs in anything the user set is kept`() {
        val backup = listOf(
            prompt(9, 0, "Fix Grammar", "Fix the grammar.", autoApply = true),
            prompt(10, 1, "Translate", "Translate to German."),
        )
        assertEquals(backup, promptsMissingFrom(mine, backup))
    }

    @Test
    fun `a backup that holds one prompt twice adds it once`() {
        val twice = listOf(prompt(5, 0, "Shorten", "Make it shorter."), prompt(6, 1, "Shorten", "Make it shorter."))
        assertEquals(listOf(twice.first()), promptsMissingFrom(mine, twice))
    }
}
