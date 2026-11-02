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
    fun `a picked word replaces the rest of the word behind the cursor too`() {
        // The reported recording: "the default behavio|r works", "behaviour" tapped, "behaviour r" written.
        val text = "the default behavior works"
        val cursor = text.indexOf("r works")
        val composing = EditorRange(text.indexOf("behavio"), cursor)
        assertEquals(
            EditorRange(composing.start, cursor + 1),
            completionReplacementRange(
                composing = composing,
                currentWord = composing,
                selection = EditorRange.cursor(cursor),
                textAfterSelection = text.substring(cursor),
            ),
        )
    }

    @Test
    fun `without a cursor to look behind, the range stays where it was`() {
        // The auto-correction path passes no selection: a space typed inside a word splits it.
        val composing = EditorRange(12, 19)
        assertEquals(
            composing,
            completionReplacementRange(composing = composing, currentWord = composing, textAfterSelection = "r works"),
        )
        // A selection, or a range that does not end at the cursor, is not a word with a tail either.
        assertEquals(
            composing,
            completionReplacementRange(composing, composing, EditorRange(12, 19), "r works"),
        )
        assertEquals(
            composing,
            completionReplacementRange(composing, composing, EditorRange.cursor(25), "r works"),
        )
    }

    @Test
    fun `what counts as the rest of a word`() {
        assertEquals(1, wordTailLength("r works"))
        assertEquals(0, wordTailLength(" works"))
        assertEquals(0, wordTailLength(""))
        // An apostrophe inside a word belongs to it; a closing quote does not.
        assertEquals(3, wordTailLength("n't go"))
        assertEquals(0, wordTailLength("' and"))
        // A hyphen ends a word, as everywhere else in the keyboard.
        assertEquals(0, wordTailLength("-based"))
        assertEquals(3, wordTailLength("ere-based"))
        // Digits, and the vowel signs some scripts write after the consonant (Devanagari "ि").
        assertEquals(2, wordTailLength("10 items"))
        assertEquals(2, wordTailLength("िन "))
        // An emoji is not a letter, whichever way its two chars are counted.
        assertEquals(0, wordTailLength("😀x"))
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
