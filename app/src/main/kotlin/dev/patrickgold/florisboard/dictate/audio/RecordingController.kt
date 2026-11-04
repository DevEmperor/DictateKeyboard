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

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import java.io.File
import java.io.RandomAccessFile

/**
 * Records microphone audio into a 16 kHz mono PCM16 **WAV** file in the app cache.
 *
 * WAV is used (rather than the previous AAC/m4a) because it is universally accepted by every
 * transcription path — including the multimodal `chat/completions` `input_audio` endpoint (issue #130),
 * which only takes wav/mp3 — and 16 kHz mono is exactly what speech models consume, so there is no
 * quality loss and the payload stays small (~1.9 MB/min). Captured via [AudioRecord]; raw PCM is streamed
 * to the file behind a placeholder header that is patched with the real sizes on [stop], so long
 * recordings never have to be held in memory.
 *
 * Requires the RECORD_AUDIO runtime permission; [start] throws if the microphone cannot be acquired.
 */
class RecordingController(private val context: Context) {

    // Read by the capture thread on every pass rather than captured once, so [swapSource] can put a
    // different microphone underneath a running recording (#411). Null means a swap is in flight.
    @Volatile private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var raf: RandomAccessFile? = null
    // Serializes access to [raf]/[pcmBytes] between the capture thread's per-frame write and a caller's
    // [rotate]/[stop], so a mid-recording segment cut can never race with a frame write.
    private val fileLock = Any()
    // Serializes handing [record] over — between a [swapSource] installing a new recorder and a [stop]
    // taking the current one away — so a swap that finishes after a stop can never leak a live mic.
    private val sourceLock = Any()
    private var segmentSeq = 0
    /** The source the running capture was acquired with; the fallback when a swap cannot be routed. */
    @Volatile private var activeSource = MediaRecorder.AudioSource.MIC
    private var bufferSize = 0
    private var captureLost: ((reason: String) -> Unit)? = null
    /** Longest unbroken stretch of all-zero frames in this recording; see the capture loop. */
    @Volatile private var longestSilentMs = 0L
    @Volatile private var recording = false
    @Volatile private var paused = false
    @Volatile private var pcmBytes = 0L
    /**
     * Samples handed to the pcmSink since [start], under [fileLock]: the clock a long-form cut position is
     * given in (see [rotate]). It counts what the sink was given, not what reached the file, so it stays the
     * splitter's count even if a write fails.
     */
    private var capturedSamples = 0L
    /** Peak |sample| (0..32767) seen since the last [maxAmplitude] call; drives the waveform. */
    @Volatile private var peak = 0

    /** The file the current/last recording was written to, or null if nothing was recorded yet. */
    var outputFile: File? = null
        private set

    val isRecording: Boolean
        get() = recording

    /**
     * Starts a new recording. A microphone that is momentarily busy is waited out (see [acquire]); this
     * throws only once it stays unavailable, and on failure everything is released so the controller
     * stays usable.
     *
     * [audioSource] defaults to the local mic; pass [MediaRecorder.AudioSource.VOICE_COMMUNICATION]
     * when recording is routed through a Bluetooth SCO headset.
     *
     * [pcmSink], if given, receives every captured mono 16-bit LE PCM frame as it arrives — used to stream
     * audio to a realtime transcription session (issue #128) while the WAV is still written in parallel
     * (so the batch path / fallback / resend flows are intact). The buffer is reused after the callback
     * returns, so consumers must synchronously copy/encode/queue the first [len] bytes if they need to keep
     * them. Called on the capture thread; keep it non-blocking (hand the bytes to a queue/socket and return).
     *
     * [onCaptureLost] is invoked (on the capture thread) when the microphone has stopped delivering real
     * audio, with the reason as `"readError"` or `"silence"` — see the capture loop for what each one
     * means and why both are needed. The owner's job is then to re-route and [swapSource], or to end the
     * dictation; see #411.
     *
     * [startRequestedAt] is the `elapsedRealtime` of the tap, so the first frame can be logged as the time
     * from the tap to the first captured sample (#447).
     */
    suspend fun start(
        audioSource: Int = MediaRecorder.AudioSource.MIC,
        pcmSink: ((pcm16: ByteArray, len: Int) -> Unit)? = null,
        onCaptureLost: ((reason: String) -> Unit)? = null,
        startRequestedAt: Long = SystemClock.elapsedRealtime(),
    ) {
        if (recording) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        require(minBuf > 0) { "AudioRecord unavailable on this device" }
        val bufferSize = minBuf * 2
        val rec = acquire(audioSource, bufferSize) ?: error("AudioRecord failed to initialize")
        val file = File(context.cacheDir, AUDIO_FILE_NAME)
        val out = try {
            RandomAccessFile(file, "rw").apply {
                setLength(0)
                write(ByteArray(WAV_HEADER_SIZE)) // placeholder; patched in stop()
            }
        } catch (t: Throwable) {
            runCatching { rec.release() }
            throw t
        }
        record = rec
        raf = out
        outputFile = file
        pcmBytes = 0
        capturedSamples = 0
        peak = 0
        paused = false
        recording = true
        activeSource = audioSource
        this.bufferSize = bufferSize
        captureLost = onCaptureLost
        longestSilentMs = 0L
        rec.startRecording()
        thread = Thread {
            val buf = ByteArray(bufferSize)
            // When the first of an unbroken run of failed reads happened, or 0 while frames are arriving.
            var failingSince = 0L
            // The same, for frames that arrive but contain nothing but zeros.
            var silentSince = 0L
            // Start of the current run of all-zero frames, for [longestSilentMs]. Unlike [silentSince]
            // this is only cleared by real audio, never by the trigger firing.
            var silentRunStart = 0L
            var firstFrameLogged = false
            while (recording) {
                val current = record
                if (current == null) {
                    // A source swap is in flight. The loop parks instead of exiting, so the WAV, the peak
                    // meter and the PCM sink all stay attached across the handover.
                    runCatching { Thread.sleep(SWAP_PARK_MS) }
                    continue
                }
                val n = runCatching { current.read(buf, 0, buf.size) }
                    .getOrDefault(AudioRecord.ERROR_INVALID_OPERATION)
                if (n < 0) {
                    // A microphone that is going away does not block, it fails — so a loop that only looks
                    // at `n > 0` spins at full speed while the recording fills with nothing. Judge it by
                    // how long the failures have lasted, never by how many there were: their rate is a
                    // property of the error path, not of the problem.
                    val now = SystemClock.elapsedRealtime()
                    if (failingSince == 0L) failingSince = now
                    runCatching { Thread.sleep(READ_ERROR_BACKOFF_MS) }
                    if (now - failingSince >= READ_ERROR_GRACE_MS) {
                        failingSince = 0L // re-arm, so a handover that also fails is reported again
                        captureLost?.let { lost -> runCatching { lost("readError") } }
                    }
                    continue
                }
                failingSince = 0L
                if (n > 0 && !firstFrameLogged) {
                    firstFrameLogged = true
                    // The frame ends now, so its first sample was captured one frame earlier (#447).
                    val frameMs = n * 1000L / (SAMPLE_RATE * 2)
                    Log.i(
                        "DictateLatency",
                        "phase=firstFrame phaseMs=${SystemClock.elapsedRealtime() - startRequestedAt - frameMs} " +
                            "frameMs=$frameMs",
                    )
                }
                // Keep reading while paused (so the mic buffer never overflows) but drop the samples.
                if (n > 0 && !paused) {
                    // Write under the lock so a concurrent rotate() sees a consistent raf/pcmBytes and the
                    // frame lands in the correct segment file (never split across a rotation).
                    synchronized(fileLock) {
                        // Counted only once written: the header is built from this, and a frame lost to a
                        // full disk would otherwise be announced as data the file does not contain.
                        runCatching { raf?.write(buf, 0, n) }.onSuccess { pcmBytes += n }
                        capturedSamples += n / 2
                    }
                    val framePeak = framePeak(buf, n)
                    if (framePeak > peak) peak = framePeak
                    if (pcmSink != null) runCatching { pcmSink(buf, n) }
                    // The failure mode a Bluetooth headset actually has (#411, measured on an A55): the
                    // route does not error and does not stop, it keeps handing over frames of **exact
                    // zeros** for as long as it takes the platform to re-route on its own — eight seconds
                    // in the case that prompted this. Nothing upstream can tell that from a quiet room,
                    // which is why the recording used to run on and the dictation lost its middle.
                    //
                    // Exact zeros are the tell. A microphone that is actually listening delivers its own
                    // noise floor: even in silence some sample is non-zero within a frame. A whole frame
                    // of zeros is a route that is not connected to a microphone at all.
                    if (framePeak == 0) {
                        val now = SystemClock.elapsedRealtime()
                        if (silentRunStart == 0L) silentRunStart = now
                        // Measured separately from the trigger below, which restarts its window each time
                        // it fires: this one only ends when real audio comes back, so it can answer
                        // "was that one dead stretch or several ordinary pauses" even when nothing fired.
                        val runMs = now - silentRunStart
                        if (runMs > longestSilentMs) longestSilentMs = runMs
                        if (silentSince == 0L) silentSince = now
                        if (now - silentSince >= DEAD_ROUTE_SILENCE_MS) {
                            silentSince = 0L
                            captureLost?.let { lost -> runCatching { lost("silence") } }
                        }
                    } else {
                        silentSince = 0L
                        silentRunStart = 0L
                    }
                } else if (paused) {
                    // A pause is not a dead route, and the samples it drops must not age the window
                    // either — without this, resuming after a long pause reports silence immediately.
                    silentSince = 0L
                }
            }
        }.also { it.start() }
    }

    /**
     * Acquires an [AudioRecord], retrying a busy microphone instead of failing the dictation on the first
     * refusal (#411).
     *
     * The audio service hands the microphone over rather than sharing it, so for a few hundred milliseconds
     * after something else lets go — another app, a call that just ended, our own previous recorder — the
     * constructor returns an object that is simply not initialized. A single attempt turns that window into
     * a failed dictation whose only remedy is tapping again, which is exactly what this does, faster and
     * without the user having to notice. Returns null once the window has clearly not been the problem.
     *
     * The uninitialized object is released before every retry: it still holds the native record, and
     * leaving it to the garbage collector is what makes the *next* attempt fail too.
     */
    @SuppressLint("MissingPermission") // caller holds RECORD_AUDIO; a null return is handled by the caller.
    private suspend fun acquire(audioSource: Int, bufferSize: Int): AudioRecord? {
        repeat(ACQUIRE_ATTEMPTS) { attempt ->
            val rec = runCatching {
                AudioRecord(audioSource, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
            }.getOrNull()
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) return rec
            runCatching { rec?.release() }
            if (attempt < ACQUIRE_ATTEMPTS - 1) delay(ACQUIRE_RETRY_MS)
        }
        return null
    }

    /**
     * Puts a different microphone underneath the running recording, without stopping it (#411).
     *
     * This works because the capture format never depends on the source: every route is read as 16 kHz
     * mono PCM16, so the frames after the handover belong in the same WAV as the ones before it, and a
     * realtime session that is being fed from [start]'s `pcmSink` keeps receiving exactly what it expects.
     * No segment cut, no join, no new stream — the only cost is the fraction of a second of speech that
     * falls between the old recorder stopping and the new one starting.
     *
     * Falls back to the source the recording is currently on when [newSource] cannot be acquired, because
     * a route that still works is better than none. Returns false only when no microphone could be
     * acquired at all; everything captured so far is intact in the WAV, and the caller is expected to end
     * the dictation rather than let it run on silence.
     */
    suspend fun swapSource(newSource: Int): Boolean {
        if (!recording) return false
        val size = bufferSize
        val previous = activeSource
        // Park the capture loop first, then take the old recorder down: a read still in flight returns as
        // soon as the native recorder stops, and the loop will not touch a released object afterwards.
        val old = synchronized(sourceLock) { record.also { record = null } }
        runCatching { old?.stop() }
        runCatching { old?.release() }
        var source = newSource
        var rec = acquire(newSource, size)
        if (rec == null && newSource != previous) {
            source = previous
            rec = acquire(previous, size)
        }
        val installed = rec ?: return false
        // Cheap pre-check before the microphone is actually opened: a stop that landed while we were
        // acquiring makes this recorder pointless, and starting it first would open the mic for the
        // moment it takes to notice. The check under the lock below is the authoritative one.
        if (!recording) {
            runCatching { installed.release() }
            return false
        }
        if (runCatching { installed.startRecording() }.isFailure) {
            runCatching { installed.release() }
            return false
        }
        return synchronized(sourceLock) {
            // A stop that landed while we were acquiring wins: install nothing and release, or the
            // microphone stays open past the end of the dictation.
            if (!recording) {
                runCatching { installed.stop() }
                runCatching { installed.release() }
                false
            } else {
                activeSource = source
                record = installed
                true
            }
        }
    }

    /**
     * The id of the input device the running capture is actually routed to, or null if the platform has
     * not settled on one yet. Used to tell a removal that concerns this recording from one that does not.
     */
    fun routedInputDeviceId(): Int? = runCatching { record?.routedDevice?.id }.getOrNull()

    /**
     * Cuts the current segment WITHOUT stopping the microphone (long-form segmented dictation, issue
     * #170): finalizes the in-progress WAV, hands it back for background transcription, and immediately
     * reopens a fresh WAV so recording continues seamlessly. Returns the finalized segment file, or null
     * if nothing usable was captured since the last cut. Safe to call off the main thread while recording.
     *
     * [cutAtSample] places the cut in the past, counted in [capturedSamples]: the segment ends there and
     * everything captured after it opens the next one, so a word begun before the decision to cut stays
     * whole (long-form auto-split, which decides a moment after the pause). Null cuts now.
     */
    fun rotate(cutAtSample: Long? = null): File? = synchronized(fileLock) {
        if (!recording) return@synchronized null
        val old = raf ?: return@synchronized null
        val carry = carryBytes(pcmBytes, capturedSamples, cutAtSample)
        // The cut lies before this segment's first sample (a manual cut came in between): nothing to cut off.
        if (carry > 0 && carry >= pcmBytes) return@synchronized null
        val tail = if (carry > 0) {
            try {
                ByteArray(carry.toInt()).also {
                    old.seek(WAV_HEADER_SIZE + pcmBytes - carry)
                    old.readFully(it)
                }
            } catch (_: Throwable) {
                null // unreadable: cut now instead, as before
            }
        } else null
        val bytes = pcmBytes - (tail?.size ?: 0)
        // How long after the pause the cut was decided: what the next segment now opens with.
        if (cutAtSample != null) Log.i("DictateLatency", "phase=segmentCut carriedMs=${(tail?.size ?: 0) * 1000L / (SAMPLE_RATE * 2)}")
        val base = outputFile
        val segment = try {
            old.seek(0)
            old.write(wavHeader(bytes))
            if (tail != null) old.setLength(WAV_HEADER_SIZE + bytes)
            old.close()
            if (bytes > 0 && base != null) {
                val seg = File(context.cacheDir, "dictate_seg_${segmentSeq++}.wav")
                seg.delete()
                if (base.renameTo(seg)) seg else null
            } else null
        } catch (_: Throwable) {
            null
        }
        // Reopen a fresh WAV (the base name is now free after the rename) for the continuing recording.
        val file = File(context.cacheDir, AUDIO_FILE_NAME)
        raf = try {
            RandomAccessFile(file, "rw").apply {
                setLength(0)
                write(ByteArray(WAV_HEADER_SIZE))
                if (tail != null) write(tail)
            }
        } catch (_: Throwable) {
            null
        }
        outputFile = file
        pcmBytes = if (raf != null) tail?.size?.toLong() ?: 0L else 0L
        segment
    }

    /**
     * Stops the recording and returns the finished WAV file, or null if nothing usable was captured. The
     * recorder is always released.
     */
    fun stop(): File? {
        if (!recording) return null
        recording = false
        captureLost = null // nothing to rescue any more; a failing read must not reach the owner now
        // Logged here rather than by the owner because every stop path goes through this one method.
        // The tag is DictateController's, deliberately: this belongs in the same stream as the rest of a
        // dictation's diagnostics, and a second tag would mean grepping two logs to reconstruct one run.
        Log.i("DictateLatency", "phase=captureSummary longestSilentMs=$longestSilentMs source=$activeSource")
        // AudioRecord.read(byte[], …) is blocking. Stop the native recorder first so a read waiting for
        // microphone frames returns before we join the capture thread. On the normal path, release only
        // after the reader exits; if stop fails, release early so the microphone still cannot leak.
        // Under the lock, and after `recording` is already false, so a [swapSource] racing this stop
        // either hands its recorder over here or finds the recording finished and releases it itself.
        val rec = synchronized(sourceLock) { record.also { record = null } }
        val stopped = rec != null && runCatching { rec.stop() }.isSuccess
        if (!stopped) runCatching { rec?.release() }
        runCatching { thread?.join() }
        thread = null
        if (stopped) runCatching { rec?.release() }
        return synchronized(fileLock) {
            val out = raf ?: return@synchronized null
            raf = null
            val bytes = pcmBytes
            val file = outputFile
            try {
                out.seek(0)
                out.write(wavHeader(bytes))
                out.close()
                if (bytes > 0) file else { file?.delete(); null }
            } catch (_: Throwable) {
                runCatching { out.close() }
                file?.delete()
                null
            }
        }
    }

    /** The peak microphone amplitude (0..32767) since the previous call, or 0 when not recording. */
    fun maxAmplitude(): Int {
        val p = peak
        peak = 0
        return p
    }

    /** Pauses the in-progress recording (samples are dropped until [resume]). No-op if not recording. */
    fun pause() {
        paused = true
    }

    /** Resumes a previously paused recording. No-op if not recording. */
    fun resume() {
        paused = false
    }

    /** Stops and discards the current recording without returning it. */
    fun cancel() {
        stop()
        outputFile?.delete()
    }

    /**
     * Peak |sample| of this frame alone (0..32767). Per frame rather than accumulated into [peak],
     * because a frame's own peak of exactly 0 is what identifies a route that has stopped carrying
     * audio, and an accumulated maximum would hide it behind the last frame that still had speech.
     */
    private fun framePeak(buf: ByteArray, length: Int): Int {
        var max = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8) // little-endian PCM16
            val abs = if (sample < 0) -sample else sample
            if (abs > max) max = abs
            i += 2
        }
        return max.coerceAtMost(32767)
    }

    private fun wavHeader(dataLen: Long): ByteArray =
        AudioWav.header(SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE, dataLen)

    companion object {
        private const val AUDIO_FILE_NAME = "dictate_audio.wav"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val WAV_HEADER_SIZE = AudioWav.HEADER_SIZE

        /**
         * How many bytes at the end of the current segment belong to the next one when it is cut at
         * [cutAtSample] while [capturedSamples] have been captured and [pcmBytes] written: none for a cut
         * now (null) or one in the future, never more than the segment holds, always whole samples.
         */
        internal fun carryBytes(pcmBytes: Long, capturedSamples: Long, cutAtSample: Long?): Long {
            if (cutAtSample == null) return 0L
            val after = (capturedSamples - cutAtSample).coerceAtLeast(0L) * 2
            return after.coerceAtMost(pcmBytes - pcmBytes % 2)
        }
        /** Roughly half a second of patience for a busy microphone, then the start genuinely failed. */
        private const val ACQUIRE_ATTEMPTS = 5
        private const val ACQUIRE_RETRY_MS = 120L
        /** How long the capture loop sleeps while a source swap installs the next recorder. */
        private const val SWAP_PARK_MS = 5L
        /** Failing reads return instantly, so the loop has to slow itself down rather than spin. */
        private const val READ_ERROR_BACKOFF_MS = 20L
        /** Long enough that a hiccup between two frames is not mistaken for a microphone that is gone. */
        private const val READ_ERROR_GRACE_MS = 400L
        /**
         * How long a route may deliver nothing but exact zeros before it is treated as disconnected.
         * Generous on purpose: the cost of being wrong is a sub-second gap in the recording, but the
         * cost of being trigger-happy would be a handover in the middle of a deliberate pause.
         */
        private const val DEAD_ROUTE_SILENCE_MS = 1_500L
    }
}
