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

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The 44-byte PCM WAV header every audio file this app writes starts with.
 *
 * Four places need it — the recorder ([RecordingController]), the splice of a continued recording
 * ([AudioConcat]), the silence trimmer ([SpeechGate]) and the speed-up ([AudioSpeedUp]) — and each one
 * had grown its own private copy of the same 15 lines. One copy, one place to be wrong.
 */
internal object AudioWav {

    const val HEADER_SIZE = 44

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
