/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.nlp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Who gets the suggestion strip when both the clipboard and the word provider have something (issue #360).
 *
 * The reported failure is the third case below: the clip was reachable only in an empty field, so the
 * first character typed took the offer away and nothing brought it back — not a space, not a new line,
 * not the clip sitting unused on the clipboard for a minute. The strip simply stayed empty.
 *
 * The mid-word case is the other half of the decision and the one worth pinning down, because it is a
 * judgement rather than a rule: falling back there would show the chip for exactly as long as the
 * dictionary has nothing for the letters typed so far, which is a chip blinking in and out under the
 * user's hands.
 */
class ClipboardFallbackTest {
    private val words = listOf("his", "him", "high")
    private val clip = listOf("clip")

    @Test
    fun `an empty field is what the clipboard offer is for`() {
        // Unchanged from before: the paste offer is the whole point of the empty field, and a generic
        // prediction must not push it out.
        assertEquals(
            clip,
            chooseStripCandidates(words = words, clip = clip, isFieldBlank = true, isAtWordBoundary = true),
        )
    }

    @Test
    fun `without a clip the empty field falls through to the words`() {
        assertEquals(
            words,
            chooseStripCandidates(words = words, clip = emptyList(), isFieldBlank = true, isAtWordBoundary = true),
        )
    }

    @Test
    fun `words are never displaced once there is text`() {
        assertEquals(
            words,
            chooseStripCandidates(words = words, clip = clip, isFieldBlank = false, isAtWordBoundary = true),
        )
        assertEquals(
            words,
            chooseStripCandidates(words = words, clip = clip, isFieldBlank = false, isAtWordBoundary = false),
        )
    }

    @Test
    fun `the clip comes back when a finished word leaves the strip empty`() {
        // The bug as reported: after a space, or on a fresh line, there is no word to suggest anything
        // for and the clipboard still holds what was copied.
        assertEquals(
            clip,
            chooseStripCandidates(words = emptyList(), clip = clip, isFieldBlank = false, isAtWordBoundary = true),
        )
    }

    @Test
    fun `mid-word the strip stays empty rather than flickering`() {
        assertEquals(
            emptyList<String>(),
            chooseStripCandidates(words = emptyList(), clip = clip, isFieldBlank = false, isAtWordBoundary = false),
        )
    }

    @Test
    fun `nothing to show is still nothing to show`() {
        assertEquals(
            emptyList<String>(),
            chooseStripCandidates(
                words = emptyList<String>(),
                clip = emptyList(),
                isFieldBlank = false,
                isAtWordBoundary = true,
            ),
        )
        assertEquals(
            emptyList<String>(),
            chooseStripCandidates(
                words = emptyList<String>(),
                clip = emptyList(),
                isFieldBlank = true,
                isAtWordBoundary = true,
            ),
        )
    }
}
