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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When a space the user typed is swallowed by the punctuation mark that follows it (issue #329).
 *
 * The rule is deliberately narrow, and most of these tests are about what it must *not* touch. A wrong
 * removal is unrecoverable in the moment — the user has to notice it and type the space back — while a
 * missed one costs nothing at all, so every ambiguous case is decided against acting.
 */
class TightenPunctuationTest {

    /** What the shipped `default` punctuation rule declares. */
    private val default = ".,;:?!‽"

    /** What the shipped `french` rule declares: a space before ? ! ; : is correct French typography. */
    private val french = ".,"

    private fun tightens(char: String, textBefore: String, symbols: String = default) =
        shouldTightenSpaceBefore(char = char, textBefore = textBefore, tighteningSymbols = symbols)

    @Test
    fun `swallows the space before a comma`() {
        assertTrue(tightens(",", "hallo "))
    }

    @Test
    fun `swallows the space before every mark the rule lists`() {
        for (mark in default) {
            assertTrue(tightens(mark.toString(), "hallo "), "expected $mark to tighten")
        }
    }

    @Test
    fun `does nothing when there is no space`() {
        assertFalse(tightens(",", "hallo"))
    }

    @Test
    fun `does nothing for a character the rule does not list`() {
        // & takes a space on both sides: AT & T must not become AT& T. This is why the tightening set
        // cannot be derived from symbolsPrecedingAutoSpace, which does list &.
        assertFalse(tightens("&", "AT "))
        assertFalse(tightens("a", "hallo "))
        assertFalse(tightens("%", "50 "))
    }

    @Test
    fun `leaves a run of spaces alone`() {
        // Three spaces before a comma are somebody's intent. Taking one of them would also leave the
        // result half-corrected, which is worse than leaving it as typed.
        assertFalse(tightens(",", "hallo  "))
        assertFalse(tightens(",", "hallo   "))
    }

    @Test
    fun `leaves indentation after a line break alone`() {
        assertFalse(tightens(".", "ende\n "))
        assertFalse(tightens(".", "ende\t "))
    }

    @Test
    fun `leaves a space at the very start of the field alone`() {
        assertFalse(tightens(",", " "))
        assertFalse(tightens(",", ""))
    }

    @Test
    fun `french keeps its space before question and exclamation marks`() {
        assertFalse(tightens("?", "Bonjour ", french))
        assertFalse(tightens("!", "Bonjour ", french))
        assertFalse(tightens(":", "Bonjour ", french))
        assertFalse(tightens(";", "Bonjour ", french))
    }

    @Test
    fun `french still tightens the period and the comma`() {
        assertTrue(tightens(".", "Bonjour ", french))
        assertTrue(tightens(",", "Bonjour ", french))
    }

    @Test
    fun `an empty rule tightens nothing`() {
        assertFalse(tightens(",", "hallo ", symbols = ""))
    }

    @Test
    fun `an empty commit tightens nothing`() {
        assertFalse(tightens("", "hallo "))
    }
}
