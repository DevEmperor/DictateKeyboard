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

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Telling a doubled callback apart from the same thing being copied twice (issue #352).
 *
 * Both look identical by content, which is why both used to be dropped, and dropping the second one is
 * what made the clipboard suggestion look random: the clip kept the timestamp of the first copy, so a
 * chip that had been used or dismissed never came back and its timeout kept running — for a clip the
 * user was re-copying precisely because nothing had shown up.
 */
class SystemClipEventTest {
    private fun clip(text: String?, timestamp: Long, uri: String? = null) =
        SystemClipFingerprint(text = text, uri = uri, timestamp = timestamp)

    @Test
    fun `one copy reported twice is one copy`() {
        assertEquals(
            SystemClipEvent.DUPLICATE_CALLBACK,
            classifySystemClipEvent(last = clip("JM1Y6O2G2IX", 1000L), new = clip("JM1Y6O2G2IX", 1000L)),
        )
    }

    @Test
    fun `the same text copied again is a new copy event`() {
        assertEquals(
            SystemClipEvent.REPEATED_COPY,
            classifySystemClipEvent(last = clip("JM1Y6O2G2IX", 1000L), new = clip("JM1Y6O2G2IX", 9000L)),
        )
    }

    @Test
    fun `without a stamp on either side the content still decides`() {
        // Every clip that reaches us through the system clipboard is stamped, so this is the theoretical
        // case — but if it ever happens, dropping the repeat is the old behaviour and the safe one.
        assertEquals(
            SystemClipEvent.DUPLICATE_CALLBACK,
            classifySystemClipEvent(last = clip("hello", 0L), new = clip("hello", 0L)),
        )
    }

    @Test
    fun `different text is a different clip whatever the stamps say`() {
        assertEquals(
            SystemClipEvent.NEW_CLIP,
            classifySystemClipEvent(last = clip("hello", 1000L), new = clip("goodbye", 1000L)),
        )
    }

    @Test
    fun `the same text under a different uri is a different clip`() {
        assertEquals(
            SystemClipEvent.NEW_CLIP,
            classifySystemClipEvent(
                last = clip("Image", 1000L, uri = "content://media/1"),
                new = clip("Image", 1000L, uri = "content://media/2"),
            ),
        )
    }

    @Test
    fun `the first clip of a session has nothing to repeat`() {
        assertEquals(
            SystemClipEvent.NEW_CLIP,
            classifySystemClipEvent(last = null, new = clip("hello", 1000L)),
        )
    }

    @Test
    fun `a cleared clipboard is work, not a duplicate`() {
        assertEquals(
            SystemClipEvent.NEW_CLIP,
            classifySystemClipEvent(last = clip("hello", 1000L), new = null),
        )
    }
}
