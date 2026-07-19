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

import dev.patrickgold.florisboard.dictate.audio.Pcm16Resampler
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Pcm16ResamplerTest {

    @Test
    fun resample16kTo24kMatchesReferenceInterpolation() {
        val pcm = deterministicPcm16(sampleCount = 513)

        for (sampleCount in listOf(1, 2, 3, 7, 32, 257, 513)) {
            val len = sampleCount * 2
            assertPcm16Near(
                referenceResample(pcm, len, srcRate = 16_000, dstRate = 24_000),
                Pcm16Resampler.resample(pcm, len, srcRate = 16_000, dstRate = 24_000),
                maxDelta = 1,
            )
        }
    }

    @Test
    fun genericResamplingPathMatchesReferenceInterpolation() {
        val pcm = deterministicPcm16(sampleCount = 257)

        assertContentEquals(
            referenceResample(pcm, len = 257 * 2, srcRate = 16_000, dstRate = 8_000),
            Pcm16Resampler.resample(pcm, len = 257 * 2, srcRate = 16_000, dstRate = 8_000),
        )
    }

    @Test
    fun sameRateResamplingUsesOnlyProvidedLength() {
        val pcm = deterministicPcm16(sampleCount = 8)

        assertContentEquals(
            pcm.copyOfRange(0, 6),
            Pcm16Resampler.resample(pcm, len = 6, srcRate = 16_000, dstRate = 16_000),
        )
    }

    private fun deterministicPcm16(sampleCount: Int): ByteArray {
        val out = ByteArray(sampleCount * 2)
        var state = 0x13579bdf
        for (i in 0 until sampleCount) {
            state = state * 1103515245 + 12345
            val value = when (i % 17) {
                0 -> Short.MIN_VALUE.toInt()
                1 -> Short.MAX_VALUE.toInt()
                else -> (state shr 16).toShort().toInt()
            }
            out[i * 2] = (value and 0xff).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun referenceResample(pcm: ByteArray, len: Int, srcRate: Int, dstRate: Int): ByteArray {
        if (srcRate == dstRate) return if (len == pcm.size) pcm else pcm.copyOf(len)
        val inSamples = len / 2
        if (inSamples <= 0) return ByteArray(0)
        val outSamples = (inSamples.toLong() * dstRate / srcRate).toInt().coerceAtLeast(1)
        val out = ByteArray(outSamples * 2)
        for (outIndex in 0 until outSamples) {
            val srcPos = outIndex.toDouble() * srcRate / dstRate
            val i0 = srcPos.toInt()
            val frac = srcPos - i0
            val s0 = pcmSampleAt(pcm, i0, inSamples)
            val s1 = pcmSampleAt(pcm, i0 + 1, inSamples)
            val v = (s0 + (s1 - s0) * frac).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[outIndex * 2] = (v and 0xff).toByte()
            out[outIndex * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun assertPcm16Near(expected: ByteArray, actual: ByteArray, maxDelta: Int) {
        assertEquals(expected.size, actual.size)
        var i = 0
        while (i + 1 < expected.size) {
            val expectedSample = pcmSampleAt(expected, i / 2, expected.size / 2)
            val actualSample = pcmSampleAt(actual, i / 2, actual.size / 2)
            assertTrue(
                kotlin.math.abs(expectedSample - actualSample) <= maxDelta,
                "sample=${i / 2} expected=$expectedSample actual=$actualSample",
            )
            i += 2
        }
    }

    private fun pcmSampleAt(pcm: ByteArray, idx: Int, count: Int): Int {
        val i = idx.coerceIn(0, count - 1)
        val lo = pcm[i * 2].toInt() and 0xff
        val hi = pcm[i * 2 + 1].toInt()
        return (hi shl 8) or lo
    }
}
