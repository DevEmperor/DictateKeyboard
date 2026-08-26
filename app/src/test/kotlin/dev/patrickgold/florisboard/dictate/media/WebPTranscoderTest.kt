/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.media

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one rule in the transcoder that is arithmetic rather than pixels — and that was wrong twice.
 *
 * When a sticker will not fit its budget, the last thing given up is the frame rate, and there is a
 * floor below which an animation stops reading as motion. Both places that halved frames checked the
 * floor against the count they *had* rather than the count they would be **left with**, so an
 * animation just above the floor fell straight through it.
 */
class WebPTranscoderTest {

    @Test
    fun `halving is refused when what is left would be under the floor`() {
        // Eight frames is the floor for a GIF. Nine may not be halved, because nine halves to four.
        assertFalse(WebPTranscoder.canHalve(9))
        assertFalse(WebPTranscoder.canHalve(8))
        assertFalse(WebPTranscoder.canHalve(15))
        // Sixteen may: eight is still the floor, not below it.
        assertTrue(WebPTranscoder.canHalve(16))
        assertTrue(WebPTranscoder.canHalve(17))
        assertTrue(WebPTranscoder.canHalve(80))
    }

    @Test
    fun `an animation too short to halve is left alone entirely`() {
        assertFalse(WebPTranscoder.canHalve(0))
        assertFalse(WebPTranscoder.canHalve(1))
        assertFalse(WebPTranscoder.canHalve(2))
    }
}
