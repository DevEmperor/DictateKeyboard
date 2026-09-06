/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.clipboard

import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Searching the clipboard history (issue #333).
 *
 * The matching itself is the easy half. The half worth writing down is what the search must *never*
 * return: a clip the panel already refuses to show. Clips out of password fields are stored marked
 * sensitive and displayed as a placeholder, and a search that matched their text would report that
 * text by another route — type a guess, and whether a result appears is the answer. That is the test
 * to keep.
 */
class ClipboardSearchTest {

    private var nextId = 1L

    // Read once, so every clip in a test is measured against the same instant. Anchored to the real
    // clock rather than a fixed number because ClipboardHistory.recent means "copied in the last few
    // minutes" and asks System.currentTimeMillis() itself — a timestamp from 2023 is never recent.
    private val now = System.currentTimeMillis()

    private fun clip(
        text: String?,
        type: ItemType = ItemType.TEXT,
        sensitive: Boolean = false,
        pinned: Boolean = false,
        ageMs: Long = 0,
    ) = ClipboardItem(
        id = nextId++,
        type = type,
        text = text,
        uri = null,
        creationTimestampMs = now - ageMs,
        isPinned = pinned,
        mimeTypes = listOf("text/plain"),
        isSensitive = sensitive,
    )

    private fun textsOf(items: List<ClipboardItem>) = items.map { it.text }

    // ── What it must never return ────────────────────────────────────────────────────────────────

    @Test
    fun `a clip marked sensitive is never a result`() {
        // The panel shows these as a placeholder. If the search matched them, guessing at a password
        // and watching whether a result appears would read it back out one guess at a time.
        val secret = clip("hunter2-the-actual-password", sensitive = true)
        val ordinary = clip("hunter2 is a joke about passwords")
        val items = listOf(secret, ordinary)
        assertEquals(listOf(ordinary.text), textsOf(ClipboardSearch.filter(items, "hunter2")))
        // Not even by an exact match on the whole thing.
        assertTrue(ClipboardSearch.filter(items, "hunter2-the-actual-password").none { it.isSensitive })
    }

    @Test
    fun `sensitive clips are kept out of the fallback strip too`() {
        // The strip shows pinned and recent clips before anything is typed, and a pinned password would
        // otherwise sit there permanently.
        val secret = clip("card number", sensitive = true, pinned = true)
        val ordinary = clip("home address", pinned = true)
        val history = ClipboardHistory(listOf(secret, ordinary))
        assertEquals(listOf(ordinary.text), textsOf(ClipboardSearch.fallback(history)))
    }

    @Test
    fun `images and videos are never results`() {
        // They carry no text, so no query could have been aiming at them.
        val image = clip(null, type = ItemType.IMAGE)
        val video = clip(null, type = ItemType.VIDEO)
        val text = clip("a photo of the receipt")
        assertEquals(listOf(text.text), textsOf(ClipboardSearch.filter(listOf(image, video, text), "photo")))
    }

    @Test
    fun `a blank query returns nothing rather than everything`() {
        // The strip has room for a handful of clips; filling it with the whole history before a letter
        // is typed would claim the search had done something.
        val items = listOf(clip("one"), clip("two"))
        assertEquals(emptyList(), ClipboardSearch.filter(items, ""))
        assertEquals(emptyList(), ClipboardSearch.filter(items, "   "))
    }

    // ── What it returns ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a word anywhere in the clip finds it, in any case`() {
        val items = listOf(clip("Meeting at 14:00 in the Blue Room"), clip("shopping list"))
        assertEquals(1, ClipboardSearch.filter(items, "blue").size)
        assertEquals(1, ClipboardSearch.filter(items, "MEETING").size)
        assertEquals(0, ClipboardSearch.filter(items, "kitchen").size)
    }

    @Test
    fun `several words all have to appear, in any order`() {
        // Someone half-remembering a clip types the words they remember, not the phrase as written.
        val target = clip("the invoice for the blue chair arrives monday")
        val other = clip("blue paint for the hallway")
        val items = listOf(target, other)
        assertEquals(listOf(target.text), textsOf(ClipboardSearch.filter(items, "blue invoice")))
        assertEquals(listOf(target.text), textsOf(ClipboardSearch.filter(items, "invoice blue")))
        assertEquals(emptyList(), ClipboardSearch.filter(items, "blue invoice green"))
    }

    @Test
    fun `the most recently copied match comes first`() {
        val old = clip("draft one", ageMs = 60_000)
        val newer = clip("draft two", ageMs = 10)
        assertEquals(listOf(newer.text, old.text), textsOf(ClipboardSearch.filter(listOf(old, newer), "draft")))
    }

    @Test
    fun `pinned clips lead the fallback strip`() {
        val pinned = clip("signature block", pinned = true, ageMs = 999_999)
        val recent = clip("just copied", ageMs = 10)
        val history = ClipboardHistory(listOf(recent, pinned))
        // Pinned first even though it is by far the older of the two — it was pinned to be at hand.
        assertEquals(listOf(pinned.text, recent.text), textsOf(ClipboardSearch.fallback(history)))
    }

    @Test
    fun `an old unpinned clip is not in the fallback strip`() {
        // "Recent" is a window, not a sort order: a clip from last week is reachable by typing a word
        // from it, but it does not get to sit in the strip for free.
        val stale = clip("last week's note", ageMs = 7 * 24 * 60 * 60 * 1000L)
        val history = ClipboardHistory(listOf(stale))
        assertEquals(emptyList(), ClipboardSearch.fallback(history))
        assertEquals(listOf(stale.text), textsOf(ClipboardSearch.filter(history.all, "note")))
    }
}
