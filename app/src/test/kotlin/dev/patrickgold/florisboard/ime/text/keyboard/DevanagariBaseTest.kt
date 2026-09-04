/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.text.keyboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deciding when the Devanagari vowel row turns into a matra row (issue #315).
 *
 * The rule has to be right in both directions: miss a pending consonant and the promised का कि की never
 * appears, keep one a moment too long and the row offers a second vowel sign where none may follow.
 */
class DevanagariBaseTest {

    private val ka = 0x0915 // क
    private val ha = 0x0939 // ह
    private val qa = 0x0958 // क़, precomposed
    private val ya = 0x095F // य़, precomposed

    @Test
    fun `a consonant right before the cursor is the pending base`() {
        assertEquals(ka, DevanagariBase.of("क"))
        assertEquals(ha, DevanagariBase.of("ह"))
    }

    @Test
    fun `only the last character counts`() {
        // Mid-word: नमस् followed by क — the क is what the next vowel sign would attach to.
        assertEquals(ka, DevanagariBase.of("नमस्क"))
        assertEquals(0x092E /* म */, DevanagariBase.of("हिन्दी नम"))
    }

    @Test
    fun `a vowel sign ends the pending base`() {
        // A second matra never follows a first, so after कि the row must show vowels again.
        assertEquals(DevanagariBase.NONE, DevanagariBase.of("कि"))
        assertEquals(DevanagariBase.NONE, DevanagariBase.of("का"))
        assertEquals(DevanagariBase.NONE, DevanagariBase.of("कौ"))
    }

    @Test
    fun `signs and virama end the pending base`() {
        assertEquals(DevanagariBase.NONE, DevanagariBase.of("कं")) // anusvara
        assertEquals(DevanagariBase.NONE, DevanagariBase.of("कँ")) // chandrabindu
        assertEquals(DevanagariBase.NONE, DevanagariBase.of("कः")) // visarga
        assertEquals(DevanagariBase.NONE, DevanagariBase.of("क्")) // virama: a consonant follows, not a vowel
    }

    @Test
    fun `an independent vowel is not a base`() {
        assertEquals(DevanagariBase.NONE, DevanagariBase.of("अ"))
        assertEquals(DevanagariBase.NONE, DevanagariBase.of("औ"))
        assertEquals(DevanagariBase.NONE, DevanagariBase.of("ऋ"))
    }

    @Test
    fun `a nukta is skipped so the consonant under it still counts`() {
        assertEquals(ka, DevanagariBase.of("क़")) // क + nukta, decomposed
        assertEquals(qa, DevanagariBase.of("क़")) // precomposed क़
        assertEquals(DevanagariBase.NONE, DevanagariBase.of("़")) // a lone nukta is not a base
    }

    @Test
    fun `everything that is not Devanagari yields none`() {
        assertEquals(DevanagariBase.NONE, DevanagariBase.of(""))
        assertEquals(DevanagariBase.NONE, DevanagariBase.of("hello"))
        assertEquals(DevanagariBase.NONE, DevanagariBase.of("क "))
        assertEquals(DevanagariBase.NONE, DevanagariBase.of("क।"))
        assertEquals(DevanagariBase.NONE, DevanagariBase.of("क1"))
        assertEquals(DevanagariBase.NONE, DevanagariBase.of("क१"))
        assertEquals(DevanagariBase.NONE, DevanagariBase.of("😀")) // an emoji, i.e. a surrogate pair
    }

    @Test
    fun `every consonant in the block is recognised and nothing else is`() {
        for (codePoint in ka..ha) {
            assertTrue(DevanagariBase.isConsonant(codePoint), "U+%04X should be a consonant".format(codePoint))
        }
        for (codePoint in qa..ya) {
            assertTrue(DevanagariBase.isConsonant(codePoint), "U+%04X should be a consonant".format(codePoint))
        }
        // Independent vowels, vowel signs, the signs, and the digits all sit outside those ranges.
        for (codePoint in 0x0900..0x0914) {
            assertFalse(DevanagariBase.isConsonant(codePoint), "U+%04X should not be a consonant".format(codePoint))
        }
        for (codePoint in 0x093A..0x0957) {
            assertFalse(DevanagariBase.isConsonant(codePoint), "U+%04X should not be a consonant".format(codePoint))
        }
        for (codePoint in 0x0966..0x096F) {
            assertFalse(DevanagariBase.isConsonant(codePoint), "U+%04X should not be a consonant".format(codePoint))
        }
    }
}
