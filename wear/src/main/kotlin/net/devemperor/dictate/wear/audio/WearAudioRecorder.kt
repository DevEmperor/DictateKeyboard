/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.devemperor.dictate.wear.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal microphone recorder for the watch (#106). Captures 16 kHz mono 16-bit PCM via [AudioRecord]
 * and writes a `.wav` file — the format the OpenAI-compatible transcription endpoints accept directly
 * (see `guessAudioMediaType`) and the smallest practical payload to ship to the phone in tether mode.
 *
 * Caller must hold RECORD_AUDIO (the watch settings screen requests it). [start] throws if the mic is
 * unavailable; [stop] always releases the recorder.
 */
class WearAudioRecorder(private val context: Context) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var raf: RandomAccessFile? = null
    @Volatile private var recording = false
    @Volatile private var paused = false
    @Volatile private var pcmBytes = 0L
    /** Peak |sample| (0..32767) seen since the last [maxAmplitude] call; drives the live waveform. */
    private val peak = AtomicInteger(0)
    @Volatile private var recordingError: Throwable? = null
    private var outputFile: File? = null

    val isRecording: Boolean get() = recording

    /** Peak microphone amplitude (0..32767) since the previous call, then resets. 0 when not recording. */
    fun maxAmplitude(): Int = peak.getAndSet(0)

    @SuppressLint("MissingPermission") // caller guarantees RECORD_AUDIO; init failure is handled below.
    fun start() {
        if (recording) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        require(minBuf > 0) { "AudioRecord unavailable on this device" }
        val bufferSize = minBuf * 2
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bufferSize,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            error("AudioRecord failed to initialize")
        }
        val file = File(context.cacheDir, "wear_dictation_${System.currentTimeMillis()}.wav")
        val out = try {
            RandomAccessFile(file, "rw").apply {
                setLength(0)
                write(ByteArray(WAV_HEADER_SIZE))
            }
        } catch (t: Throwable) {
            runCatching { recorder.release() }
            throw t
        }
        peak.set(0)
        recordingError = null
        pcmBytes = 0L
        outputFile = file
        raf = out
        record = recorder
        paused = false
        try {
            recorder.startRecording()
        } catch (t: Throwable) {
            record = null
            raf = null
            outputFile = null
            runCatching { out.close() }
            file.delete()
            runCatching { recorder.release() }
            throw t
        }
        recording = true
        thread = Thread {
            val buf = ByteArray(bufferSize)
            while (recording) {
                val read = recorder.read(buf, 0, buf.size)
                if (!recording) break
                // Keep draining the mic while paused (so the buffer never overflows) but drop the audio,
                // so paused time contributes no samples — matching the phone's pause behavior.
                if (read < 0) {
                    recordingError = IOException("AudioRecord.read failed: $read")
                    recording = false
                } else if (read > 0 && !paused) {
                    try {
                        out.write(buf, 0, read)
                        pcmBytes += read
                        updatePeak(buf, read)
                    } catch (t: Throwable) {
                        recordingError = t
                        recording = false
                    }
                }
            }
        }.also { it.start() }
    }

    private fun updatePeak(buf: ByteArray, length: Int) {
        var max = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8) // little-endian PCM16
            val abs = if (sample < 0) -sample else sample
            if (abs > max) max = abs
            i += 2
        }
        val capped = max.coerceAtMost(32767)
        while (true) {
            val current = peak.get()
            val next = if (capped > current) capped else current
            if (peak.compareAndSet(current, next)) return
        }
    }

    /** Pause capture: the mic keeps running but recorded samples are dropped until [resume]. */
    fun pause() { paused = true }

    /** Resume capture after a [pause]. */
    fun resume() { paused = false }

    /** Stops capture, releases the recorder and returns the recorded audio as a `.wav` file. */
    fun stop(): File {
        recording = false
        val recorder = record
        runCatching { recorder?.stop() }
        runCatching { thread?.join() }
        thread = null
        runCatching { recorder?.release() }
        record = null

        val out = checkNotNull(raf) { "Recorder was not started" }
        raf = null
        val file = checkNotNull(outputFile) { "Recorder output file missing" }
        val failure = recordingError
        recordingError = null
        if (failure != null) {
            runCatching { out.close() }
            file.delete()
            outputFile = null
            pcmBytes = 0L
            peak.set(0)
            throw failure
        }
        return try {
            out.seek(0)
            out.write(wavHeader(pcmBytes))
            out.close()
            file
        } catch (t: Throwable) {
            runCatching { out.close() }
            file.delete()
            throw t
        } finally {
            outputFile = null
            pcmBytes = 0L
            peak.set(0)
        }
    }

    fun cancel() {
        recording = false
        val recorder = record
        runCatching { recorder?.stop() }
        runCatching { thread?.join() }
        thread = null
        runCatching { recorder?.release() }
        record = null
        recordingError = null
        runCatching { raf?.close() }
        raf = null
        outputFile?.delete()
        outputFile = null
        pcmBytes = 0L
        peak.set(0)
    }

    private fun wavHeader(dataLen: Long): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        return ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + dataLen).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)                 // PCM subchunk size
            putShort(1)                // audio format = PCM
            putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort()) // block align
            putShort(BITS_PER_SAMPLE.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataLen.toInt())
        }.array()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val WAV_HEADER_SIZE = 44
    }
}
