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

/**
 * Covers where an emoji query comes from (issue #298).
 *
 * Emoji suggestions used to read the composing text and nothing else, and the composing region is
 * switched off by *Display suggestions* — a preference about **words**. So the emoji switch could sit
 * there saying "on" while `:smile` did nothing at all.
 *
 * The current word is the source that survives that: the editor determines it whether or not a composing
 * region is set.
 */
class EmojiQuerySourceTest {

    @Test
    fun `the composing text is used when there is one`() {
        // With word suggestions on the two are the same range, so this is also the no-change case.
        assertEquals(":smi", emojiQuerySource(composingText = ":smi", currentWordText = ":smi"))
    }

    @Test
    fun `without a composing region the current word answers`() {
        assertEquals(":smi", emojiQuerySource(composingText = "", currentWordText = ":smi"))
    }

    @Test
    fun `nothing typed stays nothing`() {
        // A cursor on empty text or right after a space: no word, no query, no candidates.
        assertEquals("", emojiQuerySource(composingText = "", currentWordText = ""))
    }
}
