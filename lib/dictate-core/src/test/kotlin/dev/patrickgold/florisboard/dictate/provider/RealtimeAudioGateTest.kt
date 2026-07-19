/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.provider

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class RealtimeAudioGateTest {

    @Test
    fun buffersDefensiveCopiesThenPassesLiveAudioThrough() {
        val gate = RealtimeAudioGate()
        val sent = mutableListOf<ByteArray>()
        val reusedCaptureBuffer = byteArrayOf(1, 2, 3, 4)
        val sender = { pcm16: ByteArray, len: Int -> sent += pcm16.copyOf(len) }

        gate.sendAudio(reusedCaptureBuffer, 3, sender)
        reusedCaptureBuffer.fill(9)
        assertEquals(0, sent.size)

        gate.markReady(sender) { error("unexpected finish") }
        gate.sendAudio(byteArrayOf(5, 6), 2, sender)

        assertEquals(2, sent.size)
        assertContentEquals(byteArrayOf(1, 2, 3), sent[0])
        assertContentEquals(byteArrayOf(5, 6), sent[1])
    }

    @Test
    fun finishBeforeReadyFlushesAudioBeforeEndMarker() {
        val gate = RealtimeAudioGate()
        val events = mutableListOf<String>()

        gate.sendAudio(byteArrayOf(1, 2), 2) { _, _ -> events += "audio" }
        gate.finish { events += "finish" }
        assertEquals(emptyList(), events)

        gate.markReady(
            send = { _, _ -> events += "audio" },
            finish = { events += "finish" },
        )
        gate.sendAudio(byteArrayOf(3, 4), 2) { _, _ -> events += "late audio" }
        gate.finish { events += "late finish" }

        assertEquals(listOf("audio", "finish"), events)
    }

    @Test
    fun bufferIsBoundedAndPreservesTheEarliestFrames() {
        val gate = RealtimeAudioGate(maxBufferedBytes = 4)
        val sent = mutableListOf<ByteArray>()
        val sender = { pcm16: ByteArray, len: Int -> sent += pcm16.copyOf(len) }

        gate.sendAudio(byteArrayOf(1, 2, 3), 3, sender)
        gate.sendAudio(byteArrayOf(4, 5), 2, sender)
        gate.markReady(sender) { error("unexpected finish") }

        assertEquals(1, sent.size)
        assertContentEquals(byteArrayOf(1, 2, 3), sent.single())
    }

    @Test
    fun closeDropsPendingAudioAndIgnoresLateSetup() {
        val gate = RealtimeAudioGate()
        val events = mutableListOf<String>()

        gate.sendAudio(byteArrayOf(1, 2), 2) { _, _ -> events += "audio" }
        gate.finish { events += "finish" }
        gate.close()
        gate.markReady(
            send = { _, _ -> events += "audio" },
            finish = { events += "finish" },
        )

        assertEquals(emptyList(), events)
    }
}
