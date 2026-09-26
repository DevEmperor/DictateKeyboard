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
import kotlin.test.assertNull

/**
 * The clipboard panel's groups and the header tabs that jump between them (issue #395).
 *
 * The grid and the tabs never talk to each other directly: the tabs find a group by the index its
 * header has in the grid, and light up by the index the grid has scrolled to. Both come from
 * [sections], so an index that drifted by one would send a tap to the wrong group without anything
 * failing. That is what these tests pin down.
 */
class ClipboardSectionsTest {

    private var nextId = 1L

    // Anchored to the real clock: ClipboardHistory asks System.currentTimeMillis() itself whether a clip
    // is recent, so a fixed timestamp would put every clip in "other".
    private val now = System.currentTimeMillis()

    private val minute = 60_000L

    private fun clip(pinned: Boolean = false, ageMs: Long = 0) = ClipboardItem(
        id = nextId++,
        type = ItemType.TEXT,
        text = "clip $nextId",
        uri = null,
        creationTimestampMs = now - ageMs,
        isPinned = pinned,
        mimeTypes = listOf("text/plain"),
    )

    private fun history(vararg items: ClipboardItem) = ClipboardHistory(items.toList())

    // ── Order ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `pins come last by default`() {
        val sections = history(clip(pinned = true), clip(), clip(ageMs = 30 * minute)).sections(pinnedOnTop = false)
        assertEquals(
            listOf(ClipboardSection.RECENT, ClipboardSection.OTHER, ClipboardSection.PINNED),
            sections.map { it.section },
        )
    }

    @Test
    fun `the setting puts pins back on top`() {
        val sections = history(clip(pinned = true), clip(), clip(ageMs = 30 * minute)).sections(pinnedOnTop = true)
        assertEquals(
            listOf(ClipboardSection.PINNED, ClipboardSection.RECENT, ClipboardSection.OTHER),
            sections.map { it.section },
        )
    }

    @Test
    fun `an empty group gets neither a header nor a tab`() {
        // Nothing copied in the last five minutes: the recent group, and with it its tab, is gone.
        val sections = history(clip(pinned = true), clip(ageMs = 30 * minute)).sections(pinnedOnTop = false)
        assertEquals(listOf(ClipboardSection.OTHER, ClipboardSection.PINNED), sections.map { it.section })
        assertEquals(emptyList(), history().sections(pinnedOnTop = false))
    }

    // ── Header indices ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `every header sits right after the items of the group before it`() {
        // Two recent, three other, one pinned: headers at 0, 1 + 2 and 3 + 1 + 3.
        val sections = history(
            clip(pinned = true),
            clip(), clip(ageMs = minute),
            clip(ageMs = 10 * minute), clip(ageMs = 20 * minute), clip(ageMs = 30 * minute),
        ).sections(pinnedOnTop = false)
        assertEquals(listOf(0, 3, 7), sections.map { it.headerIndex })
        assertEquals(listOf(2, 3, 1), sections.map { it.items.size })
    }

    @Test
    fun `header indices follow the order when pins are on top`() {
        val sections = history(
            clip(pinned = true), clip(pinned = true),
            clip(),
            clip(ageMs = 10 * minute),
        ).sections(pinnedOnTop = true)
        assertEquals(listOf(0, 3, 5), sections.map { it.headerIndex })
    }

    // ── Which tab is lit ─────────────────────────────────────────────────────────────────────────

    private val laidOut = listOf(
        ClipboardSectionSpan(ClipboardSection.RECENT, headerIndex = 0, items = emptyList()),
        ClipboardSectionSpan(ClipboardSection.OTHER, headerIndex = 3, items = emptyList()),
        ClipboardSectionSpan(ClipboardSection.PINNED, headerIndex = 20, items = emptyList()),
    )

    @Test
    fun `the tab follows the header that last reached the top`() {
        assertEquals(ClipboardSection.RECENT, laidOut.sectionAt(firstVisibleIndex = 0, atEnd = false))
        assertEquals(ClipboardSection.RECENT, laidOut.sectionAt(firstVisibleIndex = 2, atEnd = false))
        assertEquals(ClipboardSection.OTHER, laidOut.sectionAt(firstVisibleIndex = 3, atEnd = false))
        assertEquals(ClipboardSection.OTHER, laidOut.sectionAt(firstVisibleIndex = 19, atEnd = false))
        assertEquals(ClipboardSection.PINNED, laidOut.sectionAt(firstVisibleIndex = 20, atEnd = false))
    }

    @Test
    fun `at the end of the grid the last group is lit even if its header never reached the top`() {
        // Two pins below eighteen older clips: scrolled all the way down, the top row is still "other".
        assertEquals(ClipboardSection.PINNED, laidOut.sectionAt(firstVisibleIndex = 15, atEnd = true))
    }

    @Test
    fun `nothing is lit without a group`() {
        assertNull(emptyList<ClipboardSectionSpan>().sectionAt(firstVisibleIndex = 0, atEnd = false))
    }

    // ── When "recent" runs out ───────────────────────────────────────────────────────────────────

    @Test
    fun `the recent group next changes when its oldest clip turns five minutes old`() {
        val oldest = clip(ageMs = 4 * minute)
        val expiry = history(clip(), oldest, clip(ageMs = 2 * minute)).nextRecentExpiryMs()
        assertEquals(oldest.creationTimestampMs + ClipboardHistory.RECENT_TIMESPAN_MS, expiry)
    }

    @Test
    fun `nothing to wait for when no clip is recent`() {
        // A fresh pin is not in the recent group, so it schedules nothing either.
        assertNull(history(clip(pinned = true), clip(ageMs = 30 * minute)).nextRecentExpiryMs())
    }

    @Test
    fun `a history built after the expiry has moved the clip to other`() {
        // What the panel does when the expiry fires: build the history again. The same clip, now older
        // than the window, has to land in the other group — this is the whole of the stale-recent fix.
        val aged = clip(ageMs = ClipboardHistory.RECENT_TIMESPAN_MS.toLong())
        val sections = history(aged).sections(pinnedOnTop = false)
        assertEquals(listOf(ClipboardSection.OTHER), sections.map { it.section })
    }
}
