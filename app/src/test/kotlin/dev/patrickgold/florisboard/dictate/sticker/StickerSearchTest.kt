/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.sticker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Finding a sticker by file name (issue #317).
 *
 * The rules worth holding still are the ones a typist relies on without thinking: the separator in a
 * file name must not have to be typed, the words may come in any order, and a name that *starts* with
 * what was typed is the more likely answer.
 */
class StickerSearchTest {

    private fun sticker(name: String) =
        StickerItem(docId = name, name = name, mime = "image/webp", lastModified = 0L)

    // Alphabetical, the way the scanner hands them over.
    private val folder = listOf(
        "angry_cat", "cat_laugh", "cat_sleep", "dog_wave", "laughing_dog",
    ).map(::sticker)

    private fun names(query: String) = StickerSearch.filter(folder, query).map { it.name }

    @Test
    fun `a word matches anywhere in the name, whatever the case`() {
        assertEquals(listOf("cat_laugh", "cat_sleep", "angry_cat"), names("CAT"))
    }

    /**
     * The reason the separator never has to be typed: two words are two independent contains-checks,
     * so `cat_laugh` answers to "cat laugh" without anyone knowing it is an underscore.
     */
    @Test
    fun `two words both have to appear, in any order`() {
        assertEquals(listOf("cat_laugh"), names("cat laugh"))
        assertEquals(listOf("cat_laugh"), names("laugh cat"))
    }

    /**
     * Prefix first, and alphabetical within each group — the sort is stable, so the folder's own order
     * survives the ranking instead of being scrambled by it.
     */
    @Test
    fun `names that start with the word come before names that merely contain it`() {
        assertEquals(listOf("laughing_dog", "cat_laugh"), names("laugh"))
    }

    @Test
    fun `an empty query matches nothing rather than everything`() {
        assertTrue(names("").isEmpty())
        assertTrue(names("   ").isEmpty())
    }

    @Test
    fun `extra spaces between words are not a word of their own`() {
        assertEquals(listOf("cat_laugh"), names("  cat   laugh  "))
    }

    @Test
    fun `a word nothing carries returns nothing`() {
        assertTrue(names("giraffe").isEmpty())
    }
}
