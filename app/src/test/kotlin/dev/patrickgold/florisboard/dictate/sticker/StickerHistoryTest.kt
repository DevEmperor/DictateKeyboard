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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bookkeeping behind "favourites and recently used", which is per category (issue #280).
 *
 * Two rules are worth pinning. Re-using a sticker has to *move* it rather than add a second copy —
 * otherwise a favourite tapped ten times fills the whole row. And when the limit is reached the entry
 * that goes is the oldest one, never the one just used, which is the mistake that makes a recents list
 * feel broken without ever looking wrong in a screenshot.
 */
class StickerHistoryTest {

    @Test
    fun `using a sticker again moves it to the front instead of duplicating it`() {
        val list = mutableListOf("a", "b", "c")
        StickerHistoryHelper.prependCapped(list, "c", maxSize = 0)
        assertEquals(listOf("c", "a", "b"), list)
    }

    @Test
    fun `the limit drops the oldest entry, not the newest`() {
        val list = mutableListOf("a", "b", "c")
        StickerHistoryHelper.prependCapped(list, "d", maxSize = 3)
        assertEquals(listOf("d", "a", "b"), list)
    }

    @Test
    fun `a lowered limit trims from the far end`() {
        val list = mutableListOf("a", "b", "c", "d", "e")
        StickerHistoryHelper.prependCapped(list, "f", maxSize = 2)
        assertEquals(listOf("f", "a"), list)
    }

    @Test
    fun `zero means no limit`() {
        val list = mutableListOf("a", "b", "c")
        StickerHistoryHelper.prependCapped(list, "d", maxSize = 0)
        assertEquals(listOf("d", "a", "b", "c"), list)
    }

    @Test
    fun `categories keep their own lists and the combined list keeps its own order`() {
        val history = StickerHistory(
            pinned = mapOf(
                "memes" to listOf("m1"),
                StickerHistory.GLOBAL to listOf("r1", "m1"),
            ),
            recent = mapOf("memes" to listOf("m2")),
        )
        assertEquals(listOf("m1"), history.pinnedIn("memes"))
        assertEquals(listOf("r1", "m1"), history.pinnedIn(StickerHistory.GLOBAL))
        assertEquals(listOf("m2"), history.recentIn("memes"))
        assertTrue(history.isPinned("memes", "m1"))
        assertFalse(history.isPinned("memes", "r1"))
        // An unknown category is empty, not an error — a folder can be renamed away at any time.
        assertEquals(emptyList<String>(), history.recentIn("gone"))
    }

    @Test
    fun `the combined key cannot collide with a real document id`() {
        // SAF document ids come from a URI path segment, so a NUL byte can never appear in one.
        assertTrue(StickerHistory.GLOBAL.any { it.code == 0 })
    }

    /**
     * Which lists a use is written to, and why it is keyed on the folder rather than the tab (#308).
     *
     * The combined tab shows stickers from every pack. Keying a write on the tab therefore meant the
     * same action landed in different lists depending on where the user was standing: used from inside
     * the pack it reached the pack's list, used from the combined tab it did not — and the pack's
     * favourites row disagreed with the combined one about a sticker they both contain.
     */
    @Test
    fun `a pack keeps its own list and feeds the combined one`() {
        assertEquals(
            listOf("memes", StickerHistory.GLOBAL),
            StickerHistoryHelper.listKeysFor("memes"),
        )
    }

    /**
     * Loose files have no tab of their own — the combined tab is where they are shown — so for them
     * the pack list and the combined list are the same list, and writing it twice would be the only
     * effect of pretending otherwise. Older builds did write a root-keyed list; nothing ever read it.
     */
    @Test
    fun `a loose file has only the combined list`() {
        assertEquals(
            listOf(StickerHistory.GLOBAL),
            StickerHistoryHelper.listKeysFor(StickerCategory.ROOT_ID),
        )
    }

    @Test
    fun `the index finds an item across categories and reports emptiness honestly`() {
        val png = { id: String -> StickerItem(docId = id, name = id, mime = "image/png", lastModified = 0L) }
        val index = StickerIndex(
            treeUri = "content://tree",
            categories = listOf(
                StickerCategory(id = StickerCategory.ROOT_ID, name = "", items = listOf(png("loose"))),
                StickerCategory(id = "memes", name = "Memes", items = listOf(png("m1"), png("m2"))),
            ),
        )
        assertEquals(3, index.allItems.size)
        assertEquals("m2", index.findItem("m2")?.docId)
        assertEquals(null, index.findItem("nope"))
        assertFalse(index.isEmpty)
        assertTrue(StickerIndex.Empty.isEmpty)
    }
}
