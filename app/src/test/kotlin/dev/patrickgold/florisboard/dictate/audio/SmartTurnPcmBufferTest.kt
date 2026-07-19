/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals

class SmartTurnPcmBufferTest {
    @Test
    fun shortTurnIsLeftPadded() {
        val buffer = SmartTurnPcmBuffer(capacity = 5)

        buffer.append(shortArrayOf(16_384, -16_384))

        assertContentEquals(floatArrayOf(0f, 0f, 0f, 0.5f, -0.5f), buffer.snapshotNormalizedLeftPadded())
    }

    @Test
    fun rollingWindowKeepsNewestSamplesInOrder() {
        val buffer = SmartTurnPcmBuffer(capacity = 4)

        buffer.append(shortArrayOf(1, 2, 3))
        buffer.append(shortArrayOf(4, 5, 6))

        assertContentEquals(
            floatArrayOf(3 / 32768f, 4 / 32768f, 5 / 32768f, 6 / 32768f),
            buffer.snapshotNormalizedLeftPadded(),
        )
    }

    @Test
    fun clearStartsANewTurn() {
        val buffer = SmartTurnPcmBuffer(capacity = 4)
        buffer.append(shortArrayOf(1, 2, 3, 4))

        buffer.clear()
        buffer.append(shortArrayOf(8))

        assertContentEquals(floatArrayOf(0f, 0f, 0f, 8 / 32768f), buffer.snapshotNormalizedLeftPadded())
    }

    @Test
    fun oversizedChunkKeepsOnlyItsTail() {
        val buffer = SmartTurnPcmBuffer(capacity = 3)

        buffer.append(shortArrayOf(1, 2, 3, 4, 5))

        assertContentEquals(
            floatArrayOf(3 / 32768f, 4 / 32768f, 5 / 32768f),
            buffer.snapshotNormalizedLeftPadded(),
        )
    }

    @Test
    fun keepNewestDropsOldPreSpeechAudio() {
        val buffer = SmartTurnPcmBuffer(capacity = 5)
        buffer.append(shortArrayOf(1, 2, 3, 4))

        buffer.keepNewest(2)

        assertContentEquals(
            floatArrayOf(0f, 0f, 0f, 3 / 32768f, 4 / 32768f),
            buffer.snapshotNormalizedLeftPadded(),
        )
    }
}
