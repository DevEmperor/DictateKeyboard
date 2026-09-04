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

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The 44-byte PCM WAV header every audio file this app writes starts with, and the writer that puts
 * decoded samples behind it.
 *
 * Four places need the header — the recorder ([RecordingController]), the splice of a continued
 * recording ([AudioConcat]), the silence trimmer ([SpeechGate]) and the speed-up ([AudioSpeedUp]) — and
 * each one had grown its own private copy of the same 15 lines. One copy, one place to be wrong.
 *
 * [write] is the same story one level up (issue #322): the trimmer, the speed-up and the import
 * splitter each carried their own float-to-PCM16 loop, differing only in how many ranges they walked.
 */
internal object AudioWav {

    const val HEADER_SIZE = 44

    /** The whole of [samples], as the single range [write] and [pcm16] take by default. */
    fun whole(samples: FloatArray): List<IntArray> = listOf(intArrayOf(0, samples.size))

    /**
     * Writes [ranges] of [samples] — each `[startInclusive, endExclusive]` — as one mono PCM16 WAV at
     * [sampleRate]. Ranges are written back to back, which is what makes cutting silence out of a
     * recording a single pass. Returns false and removes the partial file on any failure.
     */
    fun write(
        samples: FloatArray,
        sampleRate: Int,
        output: File,
        ranges: List<IntArray> = whole(samples),
    ): Boolean {
        val clamped = clamp(ranges, samples.size)
        val count = clamped.sumOf { it[1] - it[0] }
        if (count <= 0 || sampleRate <= 0) return false
        return runCatching {
            output.outputStream().buffered().use { os ->
                os.write(header(sampleRate, channels = 1, bitsPerSample = 16, dataLen = count.toLong() * 2))
                val buf = ByteArray(8192) // even size: two bytes per sample
                var bi = 0
                for (range in clamped) {
                    for (i in range[0] until range[1]) {
                        val v = pcm16Sample(samples[i])
                        buf[bi++] = (v and 0xff).toByte()
                        buf[bi++] = ((v shr 8) and 0xff).toByte()
                        if (bi == buf.size) {
                            os.write(buf, 0, bi)
                            bi = 0
                        }
                    }
                }
                if (bi > 0) os.write(buf, 0, bi)
            }
            true
        }.getOrElse {
            runCatching { output.delete() }
            false
        }
    }

    /** The same conversion into memory, for a caller that feeds an encoder rather than a file. */
    fun pcm16(samples: FloatArray, ranges: List<IntArray> = whole(samples)): ByteArray {
        val clamped = clamp(ranges, samples.size)
        val count = clamped.sumOf { it[1] - it[0] }
        if (count <= 0) return ByteArray(0)
        val out = ByteArray(count * 2)
        var bi = 0
        for (range in clamped) {
            for (i in range[0] until range[1]) {
                val v = pcm16Sample(samples[i])
                out[bi++] = (v and 0xff).toByte()
                out[bi++] = ((v shr 8) and 0xff).toByte()
            }
        }
        return out
    }

    private fun clamp(ranges: List<IntArray>, size: Int): List<IntArray> = ranges.mapNotNull { r ->
        val start = r[0].coerceIn(0, size)
        val end = r[1].coerceIn(0, size)
        if (end > start) intArrayOf(start, end) else null
    }

    private fun pcm16Sample(sample: Float): Int = (sample.coerceIn(-1f, 1f) * 32767f).toInt()

    /**
     * Builds the header for [dataLen] bytes of raw little-endian PCM.
     *
     * A WAV header states the data length up front, so a writer that does not yet know it writes
     * `HEADER_SIZE` placeholder bytes first and patches this over them at the end (see [AudioConcat]).
     */
    fun header(sampleRate: Int, channels: Int, bitsPerSample: Int, dataLen: Long): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + dataLen).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)              // PCM subchunk size
            putShort(1)             // audio format = PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort()) // block align
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataLen.toInt())
        }.array()
    }
}
