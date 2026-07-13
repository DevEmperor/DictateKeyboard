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

import dev.patrickgold.florisboard.dictate.audio.AudioConcat
import dev.patrickgold.florisboard.dictate.audio.AudioDecode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioWavTest {

    @Test
    fun decodePcm16MonoWav() {
        val dir = Files.createTempDirectory("dictate-wav-decode").toFile()
        try {
            val wav = File(dir, "sample.wav").also {
                it.writeBytes(wavBytes(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE)))
            }

            val samples = AudioDecode.decodeToMono16k(wav)

            assertEquals(3, samples.size)
            assertNear(-1.0f, samples[0])
            assertNear(0.0f, samples[1])
            assertNear(Short.MAX_VALUE / 32768.0f, samples[2])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun concatPcm16WavSegments() {
        val dir = Files.createTempDirectory("dictate-wav-concat").toFile()
        try {
            val first = File(dir, "first.wav").also {
                it.writeBytes(wavBytes(shortArrayOf(1_000, -1_000)))
            }
            val second = File(dir, "second.wav").also {
                it.writeBytes(wavBytes(shortArrayOf(500)))
            }
            val output = File(dir, "merged.wav")

            assertTrue(AudioConcat.concat(listOf(first, second), output))
            val samples = AudioDecode.decodeToMono16k(output)

            assertEquals(3, samples.size)
            assertNear(1_000 / 32768.0f, samples[0])
            assertNear(-1_000 / 32768.0f, samples[1])
            assertNear(500 / 32768.0f, samples[2])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun streamPcm16Mono16kWavWindowsMatchDecode() {
        val dir = Files.createTempDirectory("dictate-wav-stream").toFile()
        try {
            val wav = File(dir, "sample.wav").also {
                it.writeBytes(wavBytes(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE, 1_234, -1_234)))
            }
            val chunks = mutableListOf<FloatArray>()

            val handled = AudioDecode.streamPcm16Mono16kWav(wav, windowSize = 2) { chunk ->
                chunks.add(chunk.copyOf())
                true
            }

            assertTrue(handled)
            assertEquals(listOf(2, 2, 1), chunks.map { it.size })
            val streamed = FloatArray(chunks.sumOf { it.size })
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(streamed, destinationOffset = offset)
                offset += chunk.size
            }
            val decoded = AudioDecode.decodeToMono16k(wav)
            assertEquals(decoded.size, streamed.size)
            decoded.indices.forEach { i -> assertNear(decoded[i], streamed[i]) }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun streamPcm16Mono16kWavCanStopEarly() {
        val dir = Files.createTempDirectory("dictate-wav-stream-stop").toFile()
        try {
            val wav = File(dir, "sample.wav").also {
                it.writeBytes(wavBytes(shortArrayOf(1, 2, 3, 4, 5, 6)))
            }
            var calls = 0

            val handled = AudioDecode.streamPcm16Mono16kWav(wav, windowSize = 2) {
                calls++
                false
            }

            assertTrue(handled)
            assertEquals(1, calls)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun streamPcm16Mono16kWavRejectsNonNativeRateForFallback() {
        val dir = Files.createTempDirectory("dictate-wav-stream-reject").toFile()
        try {
            val wav = File(dir, "sample.wav").also {
                it.writeBytes(wavBytes(shortArrayOf(1, 2), sampleRate = 8_000))
            }
            var called = false

            val handled = AudioDecode.streamPcm16Mono16kWav(wav, windowSize = 2) {
                called = true
                true
            }

            assertFalse(handled)
            assertFalse(called)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun assertNear(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) < 0.000001f, "expected=$expected actual=$actual")
    }

    private fun wavBytes(samples: ShortArray, sampleRate: Int = AudioDecode.TARGET_SAMPLE_RATE): ByteArray {
        val dataLen = samples.size * 2
        return ByteBuffer.allocate(WAV_HEADER_SIZE + dataLen).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataLen)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataLen)
            samples.forEach { putShort(it) }
        }.array()
    }

    private companion object {
        const val WAV_HEADER_SIZE = 44
    }
}
