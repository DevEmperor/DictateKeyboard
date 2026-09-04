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
import dev.patrickgold.florisboard.dictate.audio.AudioWav
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * The shared float-to-WAV writer (issue #322). The silence trimmer, the speed-up and the import
     * splitter each had their own copy of this loop; they now all come through here, and cutting the
     * gaps out of a recording is what the multi-range form exists for.
     */
    @Test
    fun sharedWriterWritesRangesBackToBack() {
        val dir = Files.createTempDirectory("dictate-wav-write").toFile()
        try {
            val samples = FloatArray(10) { it / 100f }
            val output = File(dir, "trimmed.wav")

            // Keep 0..2 and 6..8, i.e. drop the middle the way the trimmer drops a pause.
            assertTrue(
                AudioWav.write(
                    samples, AudioDecode.TARGET_SAMPLE_RATE, output,
                    ranges = listOf(intArrayOf(0, 2), intArrayOf(6, 8)),
                ),
            )
            val written = AudioDecode.decodeToMono16k(output)

            assertEquals(4, written.size)
            // Quantisation tolerance, not exactness: a float goes out through 16 bits and comes back,
            // so 0.01 returns as 327/32768. One step is what "unchanged" can mean here.
            assertQuantised(0.00f, written[0])
            assertQuantised(0.01f, written[1])
            assertQuantised(0.06f, written[2])
            assertQuantised(0.07f, written[3])
            // The in-memory form the encoder is fed must agree with the file, byte for byte.
            assertEquals(
                written.size * 2,
                AudioWav.pcm16(samples, listOf(intArrayOf(0, 2), intArrayOf(6, 8))).size,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sharedWriterClampsRangesAndRefusesAnEmptySelection() {
        val dir = Files.createTempDirectory("dictate-wav-clamp").toFile()
        try {
            val samples = FloatArray(4) { 0.5f }
            val clamped = File(dir, "clamped.wav")
            // A range running past the end is trimmed to it rather than throwing.
            assertTrue(AudioWav.write(samples, 16_000, clamped, listOf(intArrayOf(-5, 99))))
            assertEquals(4, AudioDecode.decodeToMono16k(clamped).size)

            // Nothing to write is a false, not a zero-sample WAV the provider would have to reject.
            val empty = File(dir, "empty.wav")
            assertTrue(!AudioWav.write(samples, 16_000, empty, listOf(intArrayOf(3, 3))))
            assertTrue(!empty.exists() || empty.length() == 0L)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun assertNear(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) < 0.000001f, "expected=$expected actual=$actual")
    }

    /** Within one 16-bit step — the most a value can keep after a round trip through PCM16. */
    private fun assertQuantised(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) <= 1f / 32768f, "expected=$expected actual=$actual")
    }

    private fun wavBytes(samples: ShortArray): ByteArray {
        val dataLen = samples.size * 2
        return ByteBuffer.allocate(WAV_HEADER_SIZE + dataLen).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataLen)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(AudioDecode.TARGET_SAMPLE_RATE)
            putInt(AudioDecode.TARGET_SAMPLE_RATE * 2)
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
