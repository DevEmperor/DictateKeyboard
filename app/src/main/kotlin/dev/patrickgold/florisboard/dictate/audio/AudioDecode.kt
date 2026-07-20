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

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder

/**
 * Decodes a recorded or picked audio file into the raw waveform
 * that on-device speech recognition expects: a single channel of 32-bit float samples in `[-1, 1]` at
 * [TARGET_SAMPLE_RATE].
 *
 * On-device engines (sherpa-onnx for issue #104) consume `FloatArray` PCM directly via
 * `acceptWaveform(samples, sampleRate)` — they do not take compressed files like the cloud upload
 * endpoints do. The recorder already writes 16 kHz mono PCM16 WAV, which takes the direct WAV path;
 * picked compressed files still go through MediaExtractor/MediaCodec, then down-mix/resample to 16 kHz.
 *
 * Decoding is CPU-only and synchronous; run it off the main thread.
 */
object AudioDecode {

    /** Sample rate every on-device model in this app is fed at (Whisper/Zipformer all use 16 kHz). */
    const val TARGET_SAMPLE_RATE = 16_000

    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val READ_BUFFER_SIZE = 64 * 1024

    /**
     * Decodes [file] to mono float PCM at [TARGET_SAMPLE_RATE].
     *
     * @throws IllegalStateException if the file has no decodable audio track or decoding fails.
     */
    fun decodeToMono16k(file: File): FloatArray {
        require(file.exists() && file.length() > 0L) { "audio file missing or empty: ${file.absolutePath}" }
        // Fast path for the recorder's own PCM16 WAV (issue #130): MediaCodec has no decoder for raw
        // `audio/raw` WAV tracks, so parse the PCM directly instead of going through MediaExtractor.
        decodeWavOrNull(file)?.let { return it }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("no audio track in ${file.absolutePath}")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("audio track has no MIME type")

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()
                val mono = drainToMonoFloat(extractor, codec, inputFormat)
                val srcRate = mono.sampleRate
                return if (srcRate == TARGET_SAMPLE_RATE) mono.samples
                else resampleLinear(mono.samples, srcRate, TARGET_SAMPLE_RATE)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    /**
     * Streams recorder-native WAV audio (PCM16, mono, 16 kHz) in reusable float windows.
     *
     * Returns false when [file] is not exactly this shape, so callers can fall back to [decodeToMono16k].
     * [onWindow] returns false to stop reading early. Full-size windows reuse the same FloatArray.
     */
    internal fun streamPcm16Mono16kWav(
        file: File,
        windowSize: Int,
        onWindow: (FloatArray) -> Boolean,
    ): Boolean {
        require(windowSize > 0) { "windowSize must be positive" }

        RandomAccessFile(file, "r").use { input ->
            val wav = parseWav(input) ?: return false
            if (
                wav.audioFormat != 1 ||
                wav.bitsPerSample != 16 ||
                wav.channels != 1 ||
                wav.sampleRate != TARGET_SAMPLE_RATE
            ) {
                return false
            }

            var remainingSamples = wav.dataLength / 2L
            if (remainingSamples <= 0L) return true
            input.seek(wav.dataOffset)

            val bytes = ByteArray(windowSize * 2)
            val floats = FloatArray(windowSize)
            while (remainingSamples > 0L) {
                val count = minOf(windowSize.toLong(), remainingSamples).toInt()
                input.readFully(bytes, 0, count * 2)
                var byteIndex = 0
                var sampleIndex = 0
                while (sampleIndex < count) {
                    val sample = (bytes[byteIndex].toInt() and 0xff) or
                        (bytes[byteIndex + 1].toInt() shl 8)
                    floats[sampleIndex] = sample / 32768.0f
                    byteIndex += 2
                    sampleIndex++
                }
                val chunk = if (count == windowSize) floats else floats.copyOf(count)
                if (!onWindow(chunk)) return true
                remainingSamples -= count
            }
            return true
        }
    }

    /**
     * Parses a PCM WAV [file] directly into mono float samples at [TARGET_SAMPLE_RATE], or returns null
     * if it is not a (PCM) WAV — then the caller falls back to the MediaCodec path. Handles arbitrary
     * channel counts / sample rates / 16-bit (and 8-bit) PCM, down-mixing and resampling as needed.
     */
    private fun decodeWavOrNull(file: File): FloatArray? {
        return try {
            RandomAccessFile(file, "r").use { input ->
                val wav = parseWav(input) ?: return null
                if (wav.audioFormat != 1 || (wav.bitsPerSample != 16 && wav.bitsPerSample != 8)) {
                    return null
                }
                val bytesPerSample = wav.bitsPerSample / 8
                val bytesPerFrame = bytesPerSample * wav.channels
                if (bytesPerFrame <= 0) return null
                val framesLong = wav.dataLength / bytesPerFrame
                if (framesLong > Int.MAX_VALUE) return null
                val frames = framesLong.toInt()
                val mono = FloatArray(frames)
                input.seek(wav.dataOffset)
                when (wav.bitsPerSample) {
                    16 -> readPcm16Mono(input, wav.channels, frames, mono)
                    else -> readPcm8Mono(input, wav.channels, frames, mono)
                }
                if (wav.sampleRate == TARGET_SAMPLE_RATE) mono
                else resampleLinear(mono, wav.sampleRate, TARGET_SAMPLE_RATE)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private class WavInfo(
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataLength: Long,
    )

    private fun parseWav(input: RandomAccessFile): WavInfo? {
        if (input.length() < 44) return null
        val header = ByteArray(12)
        input.readFully(header)
        if (!header.hasTag(0, "RIFF") || !header.hasTag(8, "WAVE")) return null

        var audioFormat = 1
        var channels = 1
        var sampleRate = TARGET_SAMPLE_RATE
        var bitsPerSample = 16
        var hasFormat = false
        val chunkHeader = ByteArray(8)
        while (input.filePointer + chunkHeader.size <= input.length()) {
            input.readFully(chunkHeader)
            val size = chunkHeader.le32(4).toLong() and 0xffff_ffffL
            val body = input.filePointer
            when {
                chunkHeader.hasTag(0, "fmt ") -> {
                    if (size >= 16L && body + 16L <= input.length()) {
                        val bytes = ByteArray(16)
                        input.readFully(bytes)
                        audioFormat = bytes.le16(0)
                        channels = bytes.le16(2).coerceAtLeast(1)
                        sampleRate = bytes.le32(4).takeIf { it > 0 } ?: TARGET_SAMPLE_RATE
                        bitsPerSample = bytes.le16(14)
                        hasFormat = true
                    }
                }
                chunkHeader.hasTag(0, "data") -> {
                    if (!hasFormat) return null
                    val len = size.coerceAtMost(input.length() - body)
                    if (len < 0L) return null
                    return WavInfo(audioFormat, channels, sampleRate, bitsPerSample, body, len)
                }
            }
            val next = (body + size + (size and 1L)).coerceAtMost(input.length())
            input.seek(next)
        }
        return null
    }

    private fun readPcm16Mono(input: RandomAccessFile, channels: Int, frames: Int, out: FloatArray) {
        val bytesPerFrame = 2 * channels
        val framesPerChunk = maxOf(1, READ_BUFFER_SIZE / bytesPerFrame)
        val chunk = ByteArray(framesPerChunk * bytesPerFrame)
        var frame = 0
        while (frame < frames) {
            val count = minOf(framesPerChunk, frames - frame)
            input.readFully(chunk, 0, count * bytesPerFrame)
            var i = 0
            repeat(count) {
                var sum = 0
                repeat(channels) {
                    val sample = (chunk[i].toInt() and 0xff) or (chunk[i + 1].toInt() shl 8)
                    sum += sample
                    i += 2
                }
                out[frame++] = (sum / channels) / 32768.0f
            }
        }
    }

    private fun readPcm8Mono(input: RandomAccessFile, channels: Int, frames: Int, out: FloatArray) {
        val bytesPerFrame = channels
        val framesPerChunk = maxOf(1, READ_BUFFER_SIZE / bytesPerFrame)
        val chunk = ByteArray(framesPerChunk * bytesPerFrame)
        var frame = 0
        while (frame < frames) {
            val count = minOf(framesPerChunk, frames - frame)
            input.readFully(chunk, 0, count * bytesPerFrame)
            var i = 0
            repeat(count) {
                var sum = 0
                repeat(channels) {
                    sum += (chunk[i].toInt() and 0xff) - 128
                    i += 1
                }
                out[frame++] = (sum / channels) / 128.0f
            }
        }
    }

    private fun ByteArray.hasTag(offset: Int, tag: String): Boolean =
        this[offset] == tag[0].code.toByte() &&
            this[offset + 1] == tag[1].code.toByte() &&
            this[offset + 2] == tag[2].code.toByte() &&
            this[offset + 3] == tag[3].code.toByte()

    private fun ByteArray.le16(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.le32(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    private class MonoPcm(val samples: FloatArray, val sampleRate: Int)

    /** Runs the decode loop, collecting all output as mono float samples at the decoder's sample rate. */
    private fun drainToMonoFloat(
        extractor: MediaExtractor,
        codec: MediaCodec,
        inputFormat: MediaFormat,
    ): MonoPcm {
        val out = FloatArrayBuilder()
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false

        // Fall back to the demuxed track format; corrected from the real output format once it arrives.
        var channelCount = inputFormat.getIntOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
        var sampleRate = inputFormat.getIntOr(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
        var pcmEncoding = inputFormat.getIntOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(inBuf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    channelCount = f.getIntOr(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                    sampleRate = f.getIntOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                    pcmEncoding = f.getIntOr(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit // no output yet; loop and feed more input
                else -> if (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        appendMono(outBuf, channelCount, pcmEncoding, out)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                }
            }
        }
        return MonoPcm(out.toArray(), sampleRate)
    }

    /** Reads one decoded buffer, down-mixing interleaved channels to a single mono float track. */
    private fun appendMono(
        buf: java.nio.ByteBuffer,
        channelCount: Int,
        pcmEncoding: Int,
        out: FloatArrayBuilder,
    ) {
        val channels = channelCount.coerceAtLeast(1)
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = buf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val frames = fb.remaining() / channels
                repeat(frames) {
                    var sum = 0f
                    repeat(channels) { sum += fb.get() }
                    out.add(sum / channels)
                }
            }
            else -> { // ENCODING_PCM_16BIT (the AAC decoder default)
                val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val frames = sb.remaining() / channels
                repeat(frames) {
                    var sum = 0
                    repeat(channels) { sum += sb.get().toInt() }
                    out.add((sum / channels) / 32768.0f)
                }
            }
        }
    }

    /** Linear-interpolation resampler. Adequate for speech STT; avoids pulling in a DSP dependency. */
    private fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (input.isEmpty() || srcRate == dstRate) return input
        val outLen = ((input.size.toLong() * dstRate) / srcRate).toInt()
        if (outLen <= 0) return FloatArray(0)
        val output = FloatArray(outLen)
        val step = srcRate.toDouble() / dstRate
        for (i in 0 until outLen) {
            val srcPos = i * step
            val idx = srcPos.toInt()
            val frac = (srcPos - idx).toFloat()
            val a = input[idx]
            val b = if (idx + 1 < input.size) input[idx + 1] else a
            output[i] = a + (b - a) * frac
        }
        return output
    }

    private fun MediaFormat.getIntOr(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    /** Minimal growable float buffer to avoid boxing into a `MutableList<Float>`. */
    private class FloatArrayBuilder(initial: Int = 16_384) {
        private var buf = FloatArray(initial)
        private var size = 0

        fun add(value: Float) {
            if (size == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[size++] = value
        }

        fun toArray(): FloatArray = buf.copyOf(size)
    }
}
