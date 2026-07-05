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

/** Linear mono PCM16 resampling for realtime transcription provider sample-rate conversion. */
object Pcm16Resampler {

    fun resample(pcm: ByteArray, len: Int, srcRate: Int, dstRate: Int): ByteArray {
        require(len in 0..pcm.size) { "len must be within pcm bounds" }
        require(srcRate > 0 && dstRate > 0) { "sample rates must be positive" }
        if (srcRate == dstRate) return if (len == pcm.size) pcm else pcm.copyOfRange(0, len)
        if (srcRate == 16_000 && dstRate == 24_000) return resample16kTo24k(pcm, len)
        return resampleLinear(pcm, len, srcRate, dstRate)
    }

    private fun resample16kTo24k(pcm: ByteArray, len: Int): ByteArray {
        val inSamples = len / 2
        if (inSamples <= 0) return ByteArray(0)
        val outSamples = (inSamples.toLong() * 3 / 2).toInt().coerceAtLeast(1)
        val out = ByteArray(outSamples * 2)
        for (outIndex in 0 until outSamples) {
            val scaled = outIndex * 2
            val i0 = scaled / 3
            val rem = scaled - (i0 * 3)
            val s0 = pcmSampleAt(pcm, i0, inSamples)
            val s1 = pcmSampleAt(pcm, i0 + 1, inSamples)
            val v = ((s0 * 3) + ((s1 - s0) * rem)) / 3
            writeSample(out, outIndex, v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
        }
        return out
    }

    private fun resampleLinear(pcm: ByteArray, len: Int, srcRate: Int, dstRate: Int): ByteArray {
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
            writeSample(out, outIndex, v)
        }
        return out
    }

    private fun pcmSampleAt(pcm: ByteArray, idx: Int, count: Int): Int {
        val i = idx.coerceIn(0, count - 1)
        val lo = pcm[i * 2].toInt() and 0xff
        val hi = pcm[i * 2 + 1].toInt()
        return (hi shl 8) or lo
    }

    private fun writeSample(out: ByteArray, idx: Int, value: Int) {
        out[idx * 2] = (value and 0xff).toByte()
        out[idx * 2 + 1] = ((value shr 8) and 0xff).toByte()
    }
}
