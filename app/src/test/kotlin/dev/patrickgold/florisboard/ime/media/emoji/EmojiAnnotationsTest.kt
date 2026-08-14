/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.media.emoji

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integrity of the shipped annotation files (issue #274).
 *
 * Issue #274 was a data defect that no amount of reading the search code would have revealed, so these
 * checks are aimed at the files: that they exist for the languages the keyboard offers, that their keys
 * line up with the inventory, and that they actually contain text. `tools/emoji-annotations/generate.py`
 * regenerates them from CLDR.
 */
class EmojiAnnotationsTest {

    /** Languages that must never lose their file: the app's own UI languages. */
    private val uiLanguages = listOf(
        "ar", "bg", "ca", "cs", "de", "en", "es", "fr", "hu", "id", "it", "ja",
        "lv", "nl", "pl", "pt", "ru", "tr", "uk", "zh",
    )

    @Test
    fun `an annotation file ships for every ui language`() {
        for (language in uiLanguages) {
            assertContains(EmojiAssets.languages, language)
        }
    }

    @Test
    fun `english is complete, since every other language leans on it`() {
        val english = EmojiAssets.annotations(EmojiAnnotations.FallbackLanguage)
        val missing = EmojiAssets.inventoryValues.filter { it !in english }
        assertEquals(emptyList(), missing, "English must name every emoji in the inventory")
    }

    @Test
    fun `every annotation belongs to an emoji in the inventory`() {
        // A key that is not in `root.txt` can never be reached — most likely a variation-selector
        // mismatch, which is precisely how 364 emojis lost their annotations while the files looked fine.
        val inventory = EmojiAssets.inventoryValues.toSet()
        for (language in EmojiAssets.languages) {
            val strays = EmojiAssets.annotations(language).keys.filterNot { it in inventory }
            assertEquals(emptyList(), strays, "$language annotates emojis the inventory does not have")
        }
    }

    @Test
    fun `no entry is empty`() {
        for (language in EmojiAssets.languages) {
            val annotations = EmojiAssets.annotations(language)
            assertTrue(annotations.isNotEmpty(), "$language has no annotations at all")
            val blank = annotations.filterValues { it.name.isBlank() && it.keywords.isEmpty() }
            assertEquals(emptyMap(), blank, "$language has entries without any text")
        }
    }

    @Test
    fun `the ui languages name nearly every emoji`() {
        // Some CLDR locales are thin (ast, ku, kab); those are shipped anyway because a partial local
        // index still beats none, and English covers the rest. The languages the app itself speaks are
        // held to a higher bar.
        val total = EmojiAssets.inventoryValues.size
        for (language in uiLanguages) {
            val covered = EmojiAssets.annotations(language).size
            assertTrue(covered >= total * 95 / 100, "$language covers only $covered of $total emojis")
        }
    }

    @Test
    fun `the inventory itself carries no names`() {
        // Documents the split the fix rests on: `root.txt` says which emojis exist, the annotation files
        // say what they are called. If names ever reappear here, the search would silently prefer them.
        val named = EmojiAssets.inventory.byCategory.values.flatten()
            .map { it.emojis.first() }
            .filter { it.name.isNotBlank() }
        assertEquals(emptyList(), named)
    }
}
