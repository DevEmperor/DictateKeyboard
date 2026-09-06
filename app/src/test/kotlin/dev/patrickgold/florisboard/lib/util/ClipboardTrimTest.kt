/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.lib.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Trimming what goes into the clipboard (issue #335).
 *
 * The setting exists because selection handles are imprecise. The tests exist because the same
 * imprecision is what makes over-trimming dangerous: everything between the first and last visible
 * character is text the user chose, and none of it may be touched.
 */
class ClipboardTrimTest {

    @Test
    fun `spaces at both ends are dropped`() {
        assertEquals("Text", ClipboardTrim.applyTo("  Text  "))
    }

    @Test
    fun `a trailing line break is dropped`() {
        assertEquals("Letzte Zeile", ClipboardTrim.applyTo("Letzte Zeile\n"))
    }

    @Test
    fun `the inside is never touched`() {
        // Double spaces, tabs and blank lines inside the selection are part of what was copied — a table
        // pasted out of a spreadsheet stops being a table the moment this "tidies" it.
        val text = "Spalte\tWert\n\nzweiter  Absatz"
        assertEquals(text, ClipboardTrim.applyTo("  $text  "))
    }

    @Test
    fun `text without padding comes out unchanged`() {
        assertEquals("nichts zu tun", ClipboardTrim.applyTo("nichts zu tun"))
    }

    @Test
    fun `a selection of nothing but whitespace is left alone`() {
        // Selecting three spaces and copying them means those three spaces. Trimming would put an empty
        // clip on the clipboard, which is not a shorter version of that — it is a different thing.
        assertEquals("   ", ClipboardTrim.applyTo("   "))
        assertEquals("\n\n", ClipboardTrim.applyTo("\n\n"))
    }

    @Test
    fun `an empty selection stays empty`() {
        assertEquals("", ClipboardTrim.applyTo(""))
    }
}
