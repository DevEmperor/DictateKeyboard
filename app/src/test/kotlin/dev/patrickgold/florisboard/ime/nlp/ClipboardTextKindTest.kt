/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.nlp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a clipboard chip is, and therefore which end of it survives being shortened (issue #346).
 *
 * The classification also picks the chip's icon, so a change here is visible twice over — that is the
 * reason it is decided in one place instead of being asked of the text again further down.
 */
class ClipboardTextKindTest {
    @Test
    fun `an address is recognised`() {
        assertEquals(ClipboardTextKind.EMAIL, ClipboardTextKind.of("prateeksingh8997@gmail.com"))
        assertEquals(ClipboardTextKind.URL, ClipboardTextKind.of("https://github.com/DevEmperor/DictateKeyboard"))
        assertEquals(ClipboardTextKind.PHONE, ClipboardTextKind.of("+49 151 12345678"))
    }

    @Test
    fun `anything else is plain text`() {
        assertEquals(ClipboardTextKind.PLAIN, ClipboardTextKind.of("I love Dictate Keyboard"))
        assertEquals(ClipboardTextKind.PLAIN, ClipboardTextKind.of("JM1Y6O2G2IX"))
        assertEquals(ClipboardTextKind.PLAIN, ClipboardTextKind.of(""))
    }

    @Test
    fun `only an address is worth keeping the tail of`() {
        // The domain is what identifies an address, and it sits at the end — so an address that does not
        // fit loses its middle. Everything else is read from the front and keeps the ordinary ellipsis.
        assertTrue(ClipboardTextKind.EMAIL.keepsTail)
        assertTrue(ClipboardTextKind.URL.keepsTail)
        // A number is short enough to fit, and it is the country and area code at the *front* that would
        // go missing if the head were eaten.
        assertFalse(ClipboardTextKind.PHONE.keepsTail)
        assertFalse(ClipboardTextKind.PLAIN.keepsTail)
    }
}
