/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.keyboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The double-tap-space shortcut (issue #333), and the reason it was worth taking apart: for years it
 * wrote a hard-coded `". "` while a separate hard-coded regex decided when to fire. Both are the same
 * statement about a language, and Hindi — which ends a sentence with the danda `।` — was wrong in both.
 *
 * So the tests come in pairs. Whatever the rule says finishes a sentence must be what gets written,
 * *and* what stops the shortcut from firing again; a version where those two drift apart types `।. `
 * or doubles its own punctuation, and both look like nonsense to the only people who would notice.
 */
class DoubleSpaceTest {

    private val latin = ".?‽!"
    private val devanagari = "।॥.?‽!"

    // ── What gets written ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the sentence ending comes from the language, not from a literal`() {
        assertEquals(". ", DoubleSpace.replacementFor(DoubleSpaceAction.PUNCTUATION, latin))
        assertEquals("। ", DoubleSpace.replacementFor(DoubleSpaceAction.PUNCTUATION, devanagari))
    }

    @Test
    fun `a rule naming no sentence ending still writes something sane`() {
        assertEquals(". ", DoubleSpace.replacementFor(DoubleSpaceAction.PUNCTUATION, ""))
    }

    @Test
    fun `the other three write themselves, whatever the language`() {
        for (terminators in listOf(latin, devanagari)) {
            assertEquals(", ", DoubleSpace.replacementFor(DoubleSpaceAction.COMMA, terminators))
            assertEquals("\n", DoubleSpace.replacementFor(DoubleSpaceAction.NEWLINE, terminators))
            // No trailing space: the point of a slash is that it binds what stands either side of it.
            assertEquals("/", DoubleSpace.replacementFor(DoubleSpaceAction.SLASH, terminators))
        }
    }

    // ── When it fires ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a word followed by the space just typed fires`() {
        assertTrue(DoubleSpace.triggersOn("t ", latin))
        assertTrue(DoubleSpace.triggersOn("८ ", devanagari))
    }

    @Test
    fun `a sentence that is already finished is left alone`() {
        // Otherwise a third tap writes ".. ", which is the bug this guard has always existed to prevent.
        assertFalse(DoubleSpace.triggersOn(". ", latin))
        assertFalse(DoubleSpace.triggersOn("! ", latin))
        assertFalse(DoubleSpace.triggersOn("? ", latin))
    }

    @Test
    fun `the danda counts as finished in Hindi and as a word character in English`() {
        // The whole reason the terminators are passed in rather than assumed. Under the Latin rule the
        // danda is just a character, so a Hindi sentence ended by hand used to collect a full stop too.
        assertFalse(DoubleSpace.triggersOn("। ", devanagari))
        assertTrue(DoubleSpace.triggersOn("। ", latin))
    }

    @Test
    fun `two spaces in a row do not fire`() {
        assertFalse(DoubleSpace.triggersOn("  ", latin))
    }

    @Test
    fun `anything that is not exactly two characters does not fire`() {
        // What the editor hands over at the start of a field, and what a longer read would look like.
        assertFalse(DoubleSpace.triggersOn(" ", latin))
        assertFalse(DoubleSpace.triggersOn("", latin))
        assertFalse(DoubleSpace.triggersOn("at ", latin))
    }

    @Test
    fun `switching languages switches the answer, not just the first one asked`() {
        // The matcher is cached on the terminator set; a cache that only ever answers for the first
        // language asked would pass every test above and fail on the device the moment someone switches
        // keyboards mid-message.
        assertFalse(DoubleSpace.triggersOn("। ", devanagari))
        assertTrue(DoubleSpace.triggersOn("। ", latin))
        assertFalse(DoubleSpace.triggersOn("। ", devanagari))
        assertEquals(". ", DoubleSpace.replacementFor(DoubleSpaceAction.PUNCTUATION, latin))
    }
}
