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
 * The bookkeeping behind "favourites and recently used" (issues #280, #308).
 *
 * Two rules are worth pinning. Re-using a sticker has to *move* it rather than add a second copy —
 * otherwise a favourite tapped ten times fills the whole row. And when the limit is reached the entry
 * that goes is the oldest one, never the one just used, which is the mistake that makes a recents list
 * feel broken without ever looking wrong in a screenshot.
 *
 * The third is newer: there is one list, and a tab shows the part of it that it can resolve. That is
 * what makes a pack's row and the combined row incapable of disagreeing, which two lists were not.
 */
class StickerHistoryTest {

    /** The key 6.1 stored the combined lists under; a NUL cannot occur in a SAF document id. */
    private val NUL = 0.toChar()

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

    /**
     * How a tab narrows the one history, and why it is a lookup rather than a stored subset.
     *
     * The panel resolves each id against the items of the tab it is drawing. The combined tab knows
     * every sticker, so it shows the whole list; a pack knows its own files, so it shows those. An id
     * that resolves nowhere — a file the user has deleted since — falls out of both, which the rows
     * needed anyway and which is why no separate filtering step exists.
     */
    @Test
    fun `a tab shows the part of the history it can resolve`() {
        val history = StickerHistory(pinned = listOf("loose", "m1"), recent = listOf("m2", "loose"))
        val everything = setOf("loose", "m1", "m2")
        val memes = setOf("m1", "m2")

        assertEquals(listOf("loose", "m1"), history.pinned.filter { it in everything })
        assertEquals(listOf("m1"), history.pinned.filter { it in memes })
        assertEquals(listOf("m2"), history.recent.filter { it in memes })
        assertTrue(history.isPinned("m1"))
        assertFalse(history.isPinned("m2"))
    }

    /**
     * A sticker that no longer exists must not shorten a row into a gap. Ids are kept as ids precisely
     * so an unresolvable one costs nothing.
     */
    @Test
    fun `an id whose file is gone simply does not appear`() {
        val history = StickerHistory(pinned = listOf("m1", "deleted", "m2"))
        assertEquals(listOf("m1", "m2"), history.pinned.filter { it in setOf("m1", "m2") })
    }

    /**
     * Reading the blob 6.1 wrote. It held a map keyed by category, and only the combined entry was
     * ever shown to anyone — the per-pack entries were half-filled by construction, because writes
     * were keyed on the tab rather than on the sticker's folder. Keeping the combined entry and
     * dropping the rest loses nothing that was ever true, and losing someone's favourites on update
     * would be the one unacceptable outcome.
     */
    @Test
    fun `the per-category blob from 6_1 is read into the single list`() {
        val combined = NUL + "all"
        val legacy = """{"pinned":{"$combined":["a","b"],"memes":["b"]},""" +
            """"recent":{"$combined":["c"],"memes":["c","d"]}}"""
        val history = StickerHistory.Serializer.deserialize(legacy)
        assertEquals(listOf("a", "b"), history.pinned)
        assertEquals(listOf("c"), history.recent)
    }

    @Test
    fun `a legacy blob with no combined entry reads as empty rather than failing`() {
        val legacy = """{"pinned":{"memes":["b"]},"recent":{}}"""
        val history = StickerHistory.Serializer.deserialize(legacy)
        assertEquals(StickerHistory.Empty, history)
    }

    @Test
    fun `the current shape survives a round trip`() {
        val history = StickerHistory(pinned = listOf("a"), recent = listOf("b", "c"))
        val roundTripped = StickerHistory.Serializer.deserialize(
            StickerHistory.Serializer.serialize(history)
        )
        assertEquals(history, roundTripped)
    }

    @Test
    fun `an unreadable blob falls back to empty instead of throwing`() {
        assertEquals(StickerHistory.Empty, StickerHistory.Serializer.deserialize("not json"))
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
