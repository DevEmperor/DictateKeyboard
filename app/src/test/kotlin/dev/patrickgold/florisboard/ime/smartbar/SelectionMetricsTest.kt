/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.smartbar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Counting the selection (issue #335).
 *
 * The number is on screen next to the text it describes, so anyone can check it at a glance — which is
 * exactly why the two easy shortcuts are wrong: `length` counts an emoji twice, and splitting at spaces
 * counts a Chinese sentence as one word. Both are pinned down here.
 */
class SelectionMetricsTest {

    // ── Characters ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `plain text counts the way anyone would count it`() {
        val counts = SelectionMetrics.of("Hallo Welt")
        assertEquals(2, counts.words)
        assertEquals(10, counts.chars)
    }

    @Test
    fun `an emoji is one character, not two`() {
        // "👋" is a single symbol on screen but two UTF-16 units in memory. Reporting 5 for "Hi 👋"
        // would be a number the user can see is wrong just by looking at their own text.
        assertEquals(4, SelectionMetrics.of("Hi 👋").chars)
    }

    @Test
    fun `an emoji built from several is still one character`() {
        // A family emoji is three people joined by zero-width joiners — eight UTF-16 units, one glyph.
        assertEquals(1, SelectionMetrics.of("👨‍👩‍👧").chars)
    }

    @Test
    fun `a letter with a combining accent counts once`() {
        // "e" followed by a combining acute renders as é and is one character to the person reading it.
        assertEquals(1, SelectionMetrics.of("é").chars)
    }

    // ── Words ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `punctuation and spaces are not words`() {
        val counts = SelectionMetrics.of("Ja, wirklich!")
        assertEquals(2, counts.words)
    }

    @Test
    fun `a script written without spaces still counts, and its characters count exactly`() {
        // Chinese carries no spaces, so the character count is the number that means something there —
        // and it is exact. The word count is the platform's job: on the phone these iterators are
        // ICU-backed and split the sentence by dictionary, while the plain JVM this test runs on returns
        // the whole run as one word. So this pins the half that is ours and asks of the other half only
        // that it never reports zero words for a sentence full of them.
        val counts = SelectionMetrics.of("今天天气很好")
        assertEquals(6, counts.chars)
        assertTrue(counts.words!! >= 1, "expected at least one word, got ${counts.words}")
    }

    @Test
    fun `numbers count as words`() {
        assertEquals(3, SelectionMetrics.of("bis 30 Uhr").words)
    }

    @Test
    fun `line breaks separate words`() {
        val counts = SelectionMetrics.of("erste Zeile\nzweite Zeile")
        assertEquals(4, counts.words)
    }

    // ── The edges ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `nothing selected is zero, not a crash`() {
        assertEquals(SelectionMetrics.Empty, SelectionMetrics.of(""))
    }

    @Test
    fun `a selection of only spaces has characters but no words`() {
        val counts = SelectionMetrics.of("   ")
        assertEquals(0, counts.words)
        assertEquals(3, counts.chars)
    }

    @Test
    fun `a selection whose text we never got reports characters alone`() {
        // Some apps answer getSelectedText with nothing, and a sweep selection only ever reports its
        // size. Guessing a word count from a length would be inventing the number outright.
        val counts = SelectionMetrics.charsOnly(42)
        assertNull(counts.words)
        assertEquals(42, counts.chars)
    }

    @Test
    fun `a negative length cannot produce a negative count`() {
        assertEquals(0, SelectionMetrics.charsOnly(-1).chars)
    }

    @Test
    fun `a selection past the cap still answers, and answers plausibly`() {
        // Select-all in a long document. Above the cap the exact walk gives way to the cheap count; the
        // result must stay usable, which for evenly shaped text means exact.
        val text = "wort ".repeat(5_000)
        assertTrue(text.length > SelectionMetrics.MaxAnalyzedChars)
        val counts = SelectionMetrics.of(text)
        assertEquals(5_000, counts.words)
        assertEquals(25_000, counts.chars)
    }
}
