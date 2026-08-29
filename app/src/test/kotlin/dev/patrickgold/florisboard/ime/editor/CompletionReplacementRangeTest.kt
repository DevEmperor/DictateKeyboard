/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.editor

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers what an accepted suggestion replaces (issue #298).
 *
 * Committing used to replace the composing region or nothing, which was the same thing as long as a
 * candidate could only appear while one was set. Emoji suggestions no longer need the region, so the
 * question "is there a composing region" and the question "which word is this answering" came apart —
 * and answering the first one still would append the emoji behind the query it answers.
 */
class CompletionReplacementRangeTest {

    @Test
    fun `the composing region wins when there is one`() {
        val composing = EditorRange(4, 9)
        assertEquals(
            composing,
            completionReplacementRange(composing = composing, currentWord = EditorRange(4, 9)),
        )
    }

    @Test
    fun `without a composing region the current word is replaced`() {
        // "Display suggestions" off: `:smi` is a word but not composed. Replacing it is what keeps the
        // editor from ending up with `:smi😀`.
        val currentWord = EditorRange(4, 8)
        assertEquals(
            currentWord,
            completionReplacementRange(composing = EditorRange.Unspecified, currentWord = currentWord),
        )
    }

    @Test
    fun `neither means the text is simply committed`() {
        // The phantom-space case: the previous word is finished, and the candidate is new text that
        // belongs after the cursor, not on top of anything.
        assertEquals(
            EditorRange.Unspecified,
            completionReplacementRange(
                composing = EditorRange.Unspecified,
                currentWord = EditorRange.Unspecified,
            ),
        )
    }
}
