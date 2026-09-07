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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What ends up in the recent-emoji row (issue #340).
 *
 * The two halves the reporter asked for are exactly the two things worth testing without a phone:
 * "fill the row, don't count it" — nowhere is there a number saying how many emojis a row holds, it
 * follows from the screen — and the order, which is pinned first and then the history's own.
 */
class EmojiRowContentTest {

    private fun emoji(value: String) = Emoji(value, value, emptyList())

    private val pinned = listOf(emoji("❤"), emoji("😂"))
    private val recent = listOf(emoji("🔥"), emoji("👍"), emoji("😅"), emoji("🙏"), emoji("✨"))

    // Roughly a 360 dp phone with a 40 dp Smartbar, which is where the reporter's "≈9–12" comes from.
    private val phoneDp = 360f
    private val cellDp = 40f

    @Test
    fun `a phone row holds about nine emojis beside the panel button`() {
        assertEquals(8, EmojiRowContent.cellCount(phoneDp, cellDp))
    }

    @Test
    fun `a wider screen simply holds more`() {
        val phone = EmojiRowContent.cellCount(phoneDp, cellDp)
        val tablet = EmojiRowContent.cellCount(1024f, cellDp)
        assertTrue(tablet > phone, "a tablet should fit more than a phone, got $tablet vs $phone")
        assertEquals(24, tablet)
    }

    /** No screen is narrow enough to justify a row of zero — that is the visibility check's call. */
    @Test
    fun `an absurdly narrow screen still asks for one cell`() {
        assertEquals(1, EmojiRowContent.cellCount(40f, cellDp))
        assertEquals(1, EmojiRowContent.cellCount(0f, cellDp))
        assertEquals(1, EmojiRowContent.cellCount(phoneDp, 0f))
    }

    @Test
    fun `pinned emojis come first, then the recently used`() {
        val picked = EmojiRowContent.pick(pinned, recent, cells = 5)
        assertEquals(listOf("❤", "😂", "🔥", "👍", "😅"), picked.map { it.value })
    }

    /** Pinning moves an emoji out of the recents, but a history written by an older version may not. */
    @Test
    fun `an emoji in both lists is shown once`() {
        val picked = EmojiRowContent.pick(pinned, listOf(emoji("😂")) + recent, cells = 4)
        assertEquals(listOf("❤", "😂", "🔥", "👍"), picked.map { it.value })
    }

    @Test
    fun `fewer emojis than places is not padded`() {
        val picked = EmojiRowContent.pick(pinned, emptyList(), cells = 8)
        assertEquals(2, picked.size)
    }

    @Test
    fun `an empty history yields an empty row`() {
        assertTrue(EmojiRowContent.pick(emptyList(), emptyList(), cells = 8).isEmpty())
        assertTrue(EmojiRowContent.pick(pinned, recent, cells = 0).isEmpty())
    }
}
