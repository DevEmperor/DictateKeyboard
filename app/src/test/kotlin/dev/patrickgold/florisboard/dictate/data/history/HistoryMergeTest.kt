/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.data.history

import kotlin.test.Test
import kotlin.test.assertEquals

/** A merge restore adds the dictations the history does not have yet, and no others. */
class HistoryMergeTest {

    private fun entry(id: Long, at: Long, text: String) = DictateHistoryEntry(
        id = id, text = text, createdAt = at, providerId = "groq", providerName = "Groq", model = "whisper",
        language = "en", durationSecs = 3, audioPath = null, audioBytes = 0, source = "keyboard", reworded = false,
    )

    private val mine = listOf(entry(1, 1_000, "Hello there."), entry(2, 2_000, "See you tomorrow."))

    @Test
    fun `restoring the same backup twice adds nothing the second time`() {
        // A restore re-issues every id, so the backup's copies carry other ids than the rows on the phone.
        val backup = mine.map { it.copy(id = it.id + 50) }
        assertEquals(emptyList(), historyEntriesMissingFrom(mine, backup))
    }

    @Test
    fun `a dictation from another moment or with other words is added`() {
        val backup = listOf(entry(7, 1_000, "Hello there!"), entry(8, 3_000, "See you tomorrow."))
        assertEquals(backup, historyEntriesMissingFrom(mine, backup))
    }
}
