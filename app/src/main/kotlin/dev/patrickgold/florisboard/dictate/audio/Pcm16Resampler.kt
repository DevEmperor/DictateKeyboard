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
        val evenLen = len and -2
        if (evenLen == 0) return ByteArray(0)
        if (srcRate == dstRate) return if (evenLen == pcm.size) pcm else pcm.copyOfRange(0, evenLen)
        if (srcRate == 16_000 && dstRate == 24_000) return resample16kTo24k(pcm, evenLen)
        return resampleLinear(pcm, evenLen, srcRate, dstRate)
    }

    fun outputLengthBytes(len: Int, srcRate: Int, dstRate: Int): Int {
        require(len >= 0) { "len must be non-negative" }
        require(srcRate > 0 && dstRate > 0) { "sample rates must be positive" }
        val evenLen = len and -2
        if (evenLen == 0 || srcRate == dstRate) return evenLen
        val inSamples = evenLen / 2
        val outSamples = (inSamples.toLong() * dstRate / srcRate).toInt().coerceAtLeast(1)
        return outSamples * 2
    }

    fun resampleInto(
        pcm: ByteArray,
        len: Int,
        srcRate: Int,
        dstRate: Int,
        out: ByteArray,
    ): Int {
        require(len in 0..pcm.size) { "len must be within pcm bounds" }
        val outLen = outputLengthBytes(len, srcRate, dstRate)
        require(out.size >= outLen) { "out buffer too small: need $outLen bytes, got ${out.size}" }
        val evenLen = len and -2
        if (outLen == 0) return 0
        if (srcRate == dstRate) {
            pcm.copyInto(out, destinationOffset = 0, startIndex = 0, endIndex = evenLen)
            return outLen
        }
        return if (srcRate == 16_000 && dstRate == 24_000) {
            resample16kTo24kInto(pcm, evenLen, out)
        } else {
            resampleLinearInto(pcm, evenLen, srcRate, dstRate, out)
        }
    }

    private fun resample16kTo24k(pcm: ByteArray, len: Int): ByteArray {
        val out = ByteArray(outputLengthBytes(len, 16_000, 24_000))
        resample16kTo24kInto(pcm, len, out)
        return out
    }

    private fun resample16kTo24kInto(pcm: ByteArray, len: Int, out: ByteArray): Int {
        val inSamples = len / 2
        if (inSamples <= 0) return 0
        val outSamples = (inSamples.toLong() * 3 / 2).toInt().coerceAtLeast(1)
        for (outIndex in 0 until outSamples) {
            val scaled = outIndex * 2
            val i0 = scaled / 3
            val rem = scaled - (i0 * 3)
            val s0 = pcmSampleAt(pcm, i0, inSamples)
            val s1 = pcmSampleAt(pcm, i0 + 1, inSamples)
            val v = ((s0 * 3) + ((s1 - s0) * rem)) / 3
            writeSample(out, outIndex, v)
        }
        return outSamples * 2
    }

    private fun resampleLinear(pcm: ByteArray, len: Int, srcRate: Int, dstRate: Int): ByteArray {
        val out = ByteArray(outputLengthBytes(len, srcRate, dstRate))
        resampleLinearInto(pcm, len, srcRate, dstRate, out)
        return out
    }

    private fun resampleLinearInto(pcm: ByteArray, len: Int, srcRate: Int, dstRate: Int, out: ByteArray): Int {
        val inSamples = len / 2
        if (inSamples <= 0) return 0
        val outSamples = (inSamples.toLong() * dstRate / srcRate).toInt().coerceAtLeast(1)
        for (outIndex in 0 until outSamples) {
            val srcPos = outIndex.toDouble() * srcRate / dstRate
            val i0 = srcPos.toInt()
            val frac = srcPos - i0
            val s0 = pcmSampleAt(pcm, i0, inSamples)
            val s1 = pcmSampleAt(pcm, i0 + 1, inSamples)
            val v = (s0 + (s1 - s0) * frac).toInt()
            writeSample(out, outIndex, v)
        }
        return outSamples * 2
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
