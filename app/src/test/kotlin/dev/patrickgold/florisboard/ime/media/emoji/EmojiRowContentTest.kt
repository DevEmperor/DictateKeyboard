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
 * What ends up in the recent-emoji row (issues #340, #394): pinned first, then the history's own order,
 * each emoji once — and all of it, because the row scrolls.
 */
class EmojiRowContentTest {

    private fun emoji(value: String) = Emoji(value, value, emptyList())

    private val pinned = listOf(emoji("❤"), emoji("😂"))
    private val recent = listOf(emoji("🔥"), emoji("👍"), emoji("😅"), emoji("🙏"), emoji("✨"))

    @Test
    fun `pinned emojis come first, then the recently used`() {
        val picked = EmojiRowContent.pick(pinned, recent)
        assertEquals(listOf("❤", "😂", "🔥", "👍", "😅", "🙏", "✨"), picked.map { it.value })
    }

    /** Issue #394: the row used to stop at one screenful, which hid most of a long history. */
    @Test
    fun `a long history is kept whole`() {
        val long = (0 until 90).map { emoji("e$it") }
        assertEquals(90, EmojiRowContent.pick(emptyList(), long).size)
    }

    /** Pinning moves an emoji out of the recents, but a history written by an older version may not. */
    @Test
    fun `an emoji in both lists is shown once, where it is pinned`() {
        val picked = EmojiRowContent.pick(pinned, listOf(emoji("😂")) + recent)
        assertEquals(listOf("❤", "😂", "🔥", "👍", "😅", "🙏", "✨"), picked.map { it.value })
    }

    @Test
    fun `an empty history yields an empty row`() {
        assertTrue(EmojiRowContent.pick(emptyList(), emptyList()).isEmpty())
    }
}
