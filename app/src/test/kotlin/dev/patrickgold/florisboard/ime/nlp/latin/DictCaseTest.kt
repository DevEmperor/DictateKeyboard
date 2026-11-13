/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.nlp.latin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which word lists are trusted on case (#480), at the shares the shipped lists have. */
class DictCaseTest {

    /** [size] words, [capitals] of them with a capital first letter. */
    private fun list(size: Int, capitals: Int) = List(size) { i -> if (i < capitals) "Name$i" else "word$i" }

    @Test
    fun `a list cased at all is trusted, down to the lowest share any language has`() {
        assertTrue(listKnowsCase(list(65_982, 350)))   // Spanish
        assertTrue(listKnowsCase(list(3_243, 21)))     // Armenian, a small list
        assertTrue(listKnowsCase(list(64_470, 31_446))) // German
    }

    @Test
    fun `a handful of capitals caught from a corpus is not casing`() {
        assertFalse(listKnowsCase(list(78_742, 15)))   // Estonian
        assertFalse(listKnowsCase(list(13_314, 5)))    // Vietnamese
    }

    @Test
    fun `a list without capitals, or without words, knows nothing about case`() {
        assertFalse(listKnowsCase(list(100_000, 0)))   // Finnish, Icelandic
        assertFalse(listKnowsCase(emptyList()))
    }
}
