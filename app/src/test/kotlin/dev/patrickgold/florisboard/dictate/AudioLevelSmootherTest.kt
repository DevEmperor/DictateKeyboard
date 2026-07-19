/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate

import dev.patrickgold.florisboard.dictate.audio.AudioLevelSmoother
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioLevelSmootherTest {
    @Test
    fun `noise floor stays still`() {
        val smoother = AudioLevelSmoother()

        repeat(10) { assertEquals(0f, smoother.update(200)) }
    }

    @Test
    fun `speech attacks faster than it releases`() {
        val smoother = AudioLevelSmoother()
        val attack = smoother.update(8000)
        val release = smoother.update(0)

        assertTrue(attack > 0.3f)
        assertTrue(release > attack / 2f)
        assertTrue(release < attack)
    }

    @Test
    fun `signal is bounded and resettable`() {
        val smoother = AudioLevelSmoother()

        repeat(20) { assertTrue(smoother.update(Int.MAX_VALUE) in 0f..1f) }
        assertEquals(0f, smoother.reset())
        assertEquals(0f, smoother.update(0))
    }
}
