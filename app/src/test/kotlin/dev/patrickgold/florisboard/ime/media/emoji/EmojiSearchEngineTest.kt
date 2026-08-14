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

import dev.patrickgold.florisboard.ime.media.emoji.EmojiAssets.top
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The emoji search, exercised against the real shipped annotation files (issue #274).
 *
 * The reporter used a Hungarian layout, where the search returned nothing for any query in any
 * language, so Hungarian is the language under test throughout: it had no annotation file at all, and
 * the code fell back to `root.txt`, which carries no names or keywords whatsoever.
 */
class EmojiSearchEngineTest {

    private val hungarian by lazy { EmojiAssets.index("hu") }
    private val english by lazy { EmojiAssets.index("en") }

    @Test
    fun `the reported query finds the kissing emojis`() {
        // The exact comparison from the issue: GBoard offers 😘 💋 for "csók", Dictate offered nothing.
        val results = hungarian.top("csók", count = 12)
        assertContains(results, "😘")
        assertContains(results, "💋")
    }

    @Test
    fun `the query is matched regardless of case`() {
        // Both screenshots show the query in capitals — the keyboard auto-capitalises the first letter
        // and the reporter typed on: "LOVE", "CSÓK".
        assertEquals(hungarian.top("csók", count = 12), hungarian.top("CSÓK", count = 12))
        assertContains(english.top("LOVE", count = 12), "🥰")
    }

    @Test
    fun `accents may be left off`() {
        assertContains(hungarian.top("csok", count = 12), "😘")
        // German too, where the umlaut is the awkward key: "grün" typed as "grun".
        assertContains(EmojiAssets.index("de").top("grun", count = 20), "💚")
    }

    @Test
    fun `english terms work on a non-english layout`() {
        // The second index is what makes a Hungarian layout answer to English words. "unicorn" appears
        // nowhere in the Hungarian file (it is "egyszarvú" there), so a hit can only have come through
        // the English fallback.
        assertTrue(EmojiAssets.annotations("hu").values.none { annotation ->
            (annotation.name + annotation.keywords.joinToString()).contains("unicorn", ignoreCase = true)
        })
        assertContains(hungarian.top("unicorn", count = 5), "🦄")
        assertContains(hungarian.top("egyszarvú", count = 5), "🦄")
    }

    @Test
    fun `the local language outranks english`() {
        // "arc" is Hungarian for face and also an English keyword (rainbow, bow). On a Hungarian layout
        // the Hungarian reading has to win, or the fallback would drown out the user's own language.
        val results = hungarian.top("arc", count = 5)
        val hungarianFaces = EmojiAssets.annotations("hu")
        assertTrue(results.all { hungarianFaces[it]?.keywords?.contains("arc") == true }, "got $results")
    }

    @Test
    fun `every token has to match`() {
        val results = english.top("smiling face", count = 5)
        assertTrue(results.isNotEmpty())
        val names = results.map { EmojiAssets.annotations("en")[it]?.name.orEmpty() }
        assertTrue(names.all { it.contains("smil") }, "got $names")
        // A second token that matches nothing removes the results rather than being ignored.
        assertEquals(emptyList(), english.top("smiling zzzz"))
    }

    @Test
    fun `an exact name beats a longer one containing it`() {
        // The old matcher used a plain `contains`, which ranked "face with tears of joy" above "joy".
        assertEquals("😹", english.top("cat with tears of joy", count = 1).single())
        assertTrue(
            EmojiSearchIndex.scoreTerm("joy", "joy") >
                EmojiSearchIndex.scoreTerm("face with tears of joy", "joy"),
        )
    }

    @Test
    fun `word starts rank above matches inside a word`() {
        assertTrue(
            EmojiSearchIndex.scoreTerm("face with tears of joy", "tear") >
                EmojiSearchIndex.scoreTerm("stearate", "tear"),
        )
    }

    @Test
    fun `nothing typed and nothing matching both yield nothing`() {
        assertEquals(emptyList(), hungarian.top(""))
        assertEquals(emptyList(), hungarian.top("   "))
        assertEquals(emptyList(), hungarian.top("qwertzuiop"))
    }

    @Test
    fun `an inventory without annotations is what shipped, and finds nothing`() {
        // The state issue #274 reported: `root.txt` alone, every line `😀;;`. Kept as a test so the
        // fallback can never quietly revert to it — the failure looked like a broken search box, not
        // like missing data.
        val bare = EmojiSearchIndex.build(
            data = EmojiAssets.inventory,
            annotations = emptyMap(),
            fallbackAnnotations = emptyMap(),
            isSupported = { true },
        )
        assertEquals(emptyList(), bare.top("csók"))
        assertEquals(emptyList(), bare.top("love"))
    }

    @Test
    fun `results are capped and ordered stably`() {
        val results = english.search("face")
        assertTrue(results.size <= EmojiSearchIndex.DefaultLimit)
        assertEquals(results.map { it.emojis.first().value }, english.search("face").map { it.emojis.first().value })
    }

    @Test
    fun `normalization folds case and accents but keeps words apart`() {
        assertEquals("csok", EmojiSearchIndex.normalize("CSÓK"))
        assertEquals("grun", EmojiSearchIndex.normalize("Grün"))
        assertEquals("smiling face", EmojiSearchIndex.normalize("  Smiling   FACE  "))
    }
}
