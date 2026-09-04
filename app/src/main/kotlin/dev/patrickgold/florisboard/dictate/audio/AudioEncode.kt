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

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Packs a recording into AAC/m4a for the upload (issue #281).
 *
 * Recording itself stays 16 kHz mono PCM WAV and must: since #130 the microphone is read through
 * `AudioRecord` rather than `MediaRecorder`, and that raw stream is what feeds live transcription
 * (#128), the silence gate (#93), Smart Turn and the level meter. `AudioConcat`, `AudioSpeedUp`, the
 * history and the on-device engine all work on the same PCM. None of that changes here — only the
 * *copy that goes over the wire* is compressed.
 *
 * The reason it must: WAV is 32 kB per second, so a 25 MB provider limit is reached after 13 minutes
 * 39 seconds, and a 14-minute dictation is simply rejected. Before #130 the recording was AAC at
 * 64 kbit/s and the same limit was an hour away. That is the bitrate used here too — proven in
 * production for a year, and applied to a source with less than half the bandwidth it had then
 * (16 kHz instead of 44.1), so if anything it is the easier job.
 *
 * **Never required.** Every failure path returns `null`, and the caller then uploads the WAV exactly
 * as before. Compression is an optimisation, not a dependency.
 */
object AudioEncode {

    /** What shipped before #130. Ample for 16 kHz mono speech. */
    private const val BIT_RATE = 64_000

    /**
     * How long to wait on the codec once there is nothing left to feed it. While input is still
     * pending the output is polled with a zero timeout instead — blocking there is what makes this
     * slow, and measurably so: draining with a 10 ms wait per round held a Galaxy A55 to six times
     * realtime, slower than a software encoder in an emulator, because almost every round paid the
     * full wait for output that was not due yet.
     */
    private const val DRAIN_TIMEOUT_US = 10_000L

    /**
     * Encodes the PCM WAV [input] into an AAC/m4a file at [output], returning it — or `null` if
     * anything at all goes wrong, if the input is not a PCM WAV, or if the result would not actually
     * be smaller.
     */
    suspend fun toM4a(input: File, output: File): File? = withContext(Dispatchers.Default) {
        val startedNanos = System.nanoTime()
        val pcm = runCatching { readWavPcm(input) }.getOrNull() ?: return@withContext null
        if (pcm.samples.isEmpty()) return@withContext null

        val encoded = runCatching { encode(pcm, output) }.getOrElse { error ->
            Log.w(TAG, "AAC encode failed, uploading the WAV instead: $error")
            runCatching { output.delete() }
            return@withContext null
        }
        if (!encoded) {
            runCatching { output.delete() }
            return@withContext null
        }
        // A compressed file that is not smaller is not worth the risk of a provider disliking it.
        if (output.length() <= 0L || output.length() >= input.length()) {
            runCatching { output.delete() }
            return@withContext null
        }
        val ms = (System.nanoTime() - startedNanos) / 1_000_000
        Log.i(
            TAG,
            "encoded ${input.length() / 1024} kB WAV to ${output.length() / 1024} kB m4a in $ms ms",
        )
        output
    }

    /**
     * Encodes **any** decodable container into AAC/m4a (issue #322) — not just the recorder's own WAV.
     *
     * The two halves of this have been in the tree since #104 and #281 and were never joined:
     * [AudioDecode.decodeToMono16k] turns anything MediaCodec can open into 16 kHz mono float samples,
     * and [encode] wants exactly that as PCM16. Going straight from one to the other means no
     * intermediate WAV on disk, which for a long voice note is the difference between one temporary
     * file and two.
     *
     * Unlike [toM4a] this does **not** refuse a result that came out larger. It is used where the
     * provider will not take the container at all, and a bigger file that is accepted beats a smaller
     * one that is rejected. Returns null on any failure, and the caller then sends what it already had.
     */
    suspend fun transcodeToM4a(input: File, output: File): File? = withContext(Dispatchers.Default) {
        val startedNanos = System.nanoTime()
        val samples = runCatching { AudioDecode.decodeToMono16k(input) }.getOrElse { error ->
            Log.w(TAG, "transcode decode failed for ${input.name}: $error")
            return@withContext null
        }
        if (samples.isEmpty()) return@withContext null
        val pcm = Pcm(AudioWav.pcm16(samples), AudioDecode.TARGET_SAMPLE_RATE, channels = 1)
        val encoded = runCatching { encode(pcm, output) }.getOrElse { error ->
            Log.w(TAG, "transcode encode failed for ${input.name}: $error")
            runCatching { output.delete() }
            return@withContext null
        }
        if (!encoded || output.length() <= 0L) {
            runCatching { output.delete() }
            return@withContext null
        }
        Log.i(
            TAG,
            "transcoded ${input.length() / 1024} kB ${input.extension} to ${output.length() / 1024} kB " +
                "m4a in ${(System.nanoTime() - startedNanos) / 1_000_000} ms",
        )
        output
    }

    /**
     * The same conversion, stopping at PCM: any decodable container written back out as the 16 kHz mono
     * WAV that every provider takes. Used where the target does not accept m4a either — the single-call
     * multimodal path takes wav or mp3 and nothing else (#130).
     */
    suspend fun transcodeToWav(input: File, output: File): File? = withContext(Dispatchers.Default) {
        val samples = runCatching { AudioDecode.decodeToMono16k(input) }.getOrElse { error ->
            Log.w(TAG, "transcode decode failed for ${input.name}: $error")
            return@withContext null
        }
        if (samples.isEmpty()) return@withContext null
        if (!AudioWav.write(samples, AudioDecode.TARGET_SAMPLE_RATE, output)) return@withContext null
        output
    }

    private class Pcm(val samples: ByteArray, val sampleRate: Int, val channels: Int)

    /** Reads the recorder's own WAV without decoding it — it is already the PCM the encoder wants. */
    private fun readWavPcm(file: File): Pcm? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() <= AudioWav.HEADER_SIZE) return null
            val header = ByteArray(AudioWav.HEADER_SIZE)
            raf.readFully(header)
            if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(header, 8, 4, Charsets.US_ASCII) != "WAVE"
            ) {
                return null
            }
            val buffer = ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val format = buffer.getShort(20).toInt()
            val channels = buffer.getShort(22).toInt()
            val sampleRate = buffer.getInt(24)
            val bits = buffer.getShort(34).toInt()
            // Only the shape the recorder writes; anything else is left to the caller's WAV upload.
            if (format != 1 || bits != 16 || channels !in 1..2 || sampleRate <= 0) return null
            val data = ByteArray((raf.length() - AudioWav.HEADER_SIZE).toInt())
            raf.readFully(data)
            return Pcm(data, sampleRate, channels)
        }
    }

    private fun encode(pcm: Pcm, output: File): Boolean {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, pcm.sampleRate, pcm.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            // No KEY_MAX_INPUT_SIZE: the codec's own buffer is far larger than a hand-picked chunk, and
            // every buffer handed over is one fewer round through the drain loop.
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val bytesPerFrame = 2 * pcm.channels
            var offset = 0
            var sawInputEos = false
            var sawOutputEos = false
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val index = codec.dequeueInputBuffer(DRAIN_TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val length = minOf(buffer.capacity(), pcm.samples.size - offset)
                        // Presentation time from the sample position, so the muxer writes a sane duration.
                        val timeUs = 1_000_000L * (offset / bytesPerFrame) / pcm.sampleRate
                        if (length <= 0) {
                            codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            buffer.clear()
                            buffer.put(pcm.samples, offset, length)
                            codec.queueInputBuffer(index, 0, length, timeUs, 0)
                            offset += length
                        }
                    }
                }
                // Poll while there is still PCM to hand over; only once everything is in does waiting
                // for output make sense.
                val outTimeout = if (sawInputEos) DRAIN_TIMEOUT_US else 0L
                when (val index = codec.dequeueOutputBuffer(info, outTimeout)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxing) {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxing = true
                        }
                    }
                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)!!
                        // The codec-config buffer went into addTrack already; writing it as a sample
                        // would corrupt the track.
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0 && muxing) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    }
                }
            }
            return muxing
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxing) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private const val TAG = "DictateLatency"
}
