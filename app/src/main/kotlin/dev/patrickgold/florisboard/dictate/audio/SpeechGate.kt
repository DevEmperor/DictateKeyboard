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

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Local voice-activity gate that answers one question before a recording is sent for transcription:
 * *does this audio actually contain speech?* (issue #93).
 *
 * Generative STT models (Whisper, Groq) hallucinate on silence — emitting "ghost text" like
 * "Thanks for watching" — which wastes API credits and dumps garbage into the user's field. Running a
 * Silero VAD locally first lets us skip the upload entirely when the recording is just silence/noise.
 *
 * This reuses the sherpa-onnx runtime and the Silero VAD already bundled for on-device STT (issue #104);
 * the only addition is the ~640 KB `silero_vad.onnx` shipped in the APK assets so the gate works for
 * every provider even when no on-device model is installed. It is a *gate only* — the full original audio
 * is still what gets transcribed, so nothing is ever clipped.
 *
 * Fails open on purpose: if the model can't be prepared or the VAD errors, [hasSpeech] returns true so a
 * genuine recording is never silently dropped by a gate malfunction.
 */
object SpeechGate {

    private const val ASSET_PATH = "dictate/silero_vad.onnx"
    private const val VAD_MODEL_BYTES = 643_854L
    /** Silero v5 processes fixed 512-sample windows at 16 kHz. */
    private const val WINDOW = 512
    private const val LOG_TAG = "DictateLatency"
    private val vadLock = ReentrantLock()
    private var cachedVad: Vad? = null

    /**
     * Creates the native VAD session while the user is still speaking so stopping a recording does not
     * have to pay model/session setup latency. Safe to call repeatedly; only one session is retained.
     */
    suspend fun prewarm(context: Context) = withContext(Dispatchers.Default) {
        val startedNanos = System.nanoTime()
        val model = ensureVadModel(context.applicationContext) ?: return@withContext
        val ready = vadLock.withLock {
            if (cachedVad == null) cachedVad = createVad(model)
            cachedVad != null
        }
        Log.i(LOG_TAG, "speechGate prewarmMs=${elapsedMillis(startedNanos)} ready=$ready")
    }

    /**
     * Starts a silence-gate session that consumes the recorder's existing 16 kHz mono PCM tap while the
     * user is speaking. The native inference runs on its own worker; [LiveSession.feed] only copies and
     * queues a frame, so it never blocks [android.media.AudioRecord]. Once speech is confirmed, later
     * frames are ignored. This moves the normal gate off the stop-to-request critical path without
     * clipping or otherwise changing the WAV uploaded to the provider.
     */
    fun startLive(context: Context): LiveSession =
        LiveSession(context.applicationContext).also { it.start() }

    /** A single recording's live gate. Create through [startLive]. */
    class LiveSession internal constructor(private val appContext: Context) {
        private val queue = LinkedBlockingQueue<ByteArray>(MAX_QUEUED_PCM_FRAMES)
        private val started = AtomicBoolean(false)
        private val completed = AtomicBoolean(false)
        private val done = CountDownLatch(1)
        @Volatile private var accepting = false
        @Volatile private var result = true // fail open until the worker proves silence
        private var worker: Thread? = null

        internal fun start() {
            if (!started.compareAndSet(false, true)) return
            accepting = true
            worker = Thread({ runWorker() }, "dictate-vad-gate").also {
                it.isDaemon = true
                it.start()
            }
        }

        /** Called on the recorder thread. Copies one frame and returns immediately. */
        fun feed(pcm16: ByteArray, len: Int) {
            if (!accepting || completed.get() || len < 2) return
            val copy = pcm16.copyOf(len)
            if (!queue.offer(copy)) {
                // Never back-pressure the microphone. A saturated gate becomes permissive instead of
                // risking dropped capture frames or a false "no speech" result.
                accepting = false
                queue.clear()
                queue.offer(END_OF_INPUT)
                complete(true)
                worker?.interrupt()
                Log.w(LOG_TAG, "speechGate liveQueueFull failOpen=true")
            }
        }

        /**
         * Stops accepting PCM, drains the already-queued tail and returns the live result. Usually this is
         * immediate because a closed speech segment was detected during recording. A stuck native worker
         * times out permissively so dictation can never be lost because of the local gate.
         */
        suspend fun finish(): Boolean = withContext(Dispatchers.Default) {
            val waitStartedNanos = System.nanoTime()
            accepting = false
            while (!completed.get() && !queue.offer(END_OF_INPUT, 100, TimeUnit.MILLISECONDS)) {
                // Wait off the UI thread only until the worker makes room or has already completed.
            }
            val finished = done.await(FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                complete(true)
                worker?.interrupt()
            }
            val speech = if (finished) result else true
            Log.i(
                LOG_TAG,
                "speechGate liveFinishWaitMs=${elapsedMillis(waitStartedNanos)} speech=$speech " +
                    "timedOut=${!finished}",
            )
            speech
        }

        /** Stops an unused session (cancel, realtime path, invalid recording). Idempotent and fail-open. */
        fun cancel() {
            accepting = false
            queue.clear()
            queue.offer(END_OF_INPUT)
            complete(true)
            worker?.interrupt()
        }

        private fun runWorker() {
            val model = ensureVadModel(appContext)
            if (model == null) {
                complete(true)
                return
            }
            try {
                vadLock.withLock {
                    val vad = cachedVad ?: createVad(model)?.also { cachedVad = it }
                    if (vad == null) {
                        complete(true)
                        return@withLock
                    }
                    try {
                        vad.reset()
                        complete(process(vad))
                    } catch (_: InterruptedException) {
                        complete(true)
                    } catch (_: Throwable) {
                        cachedVad = null
                        runCatching { vad.release() }
                        complete(true)
                    }
                }
            } catch (_: InterruptedException) {
                complete(true)
            } catch (_: Throwable) {
                complete(true)
            } finally {
                queue.clear()
            }
        }

        @Throws(InterruptedException::class)
        private fun process(vad: Vad): Boolean {
            val window = FloatArray(WINDOW)
            var filled = 0
            while (true) {
                val chunk = queue.take()
                if (chunk === END_OF_INPUT) break
                var offset = 0
                while (offset + 1 < chunk.size) {
                    val sample = ((chunk[offset].toInt() and 0xff) or
                        (chunk[offset + 1].toInt() shl 8)).toShort()
                    window[filled++] = sample / 32768f
                    offset += 2
                    if (filled != WINDOW) continue
                    vad.acceptWaveform(window)
                    filled = 0
                    if (!vad.empty()) return true
                }
            }
            // Speech that runs right up to stop may never close a segment until the stream is flushed.
            if (filled > 0) vad.acceptWaveform(window.copyOf(filled))
            vad.flush()
            return !vad.empty()
        }

        private fun complete(value: Boolean) {
            if (!completed.compareAndSet(false, true)) return
            result = value
            accepting = false
            done.countDown()
        }

        private companion object {
            private val END_OF_INPUT = ByteArray(0)
            private const val MAX_QUEUED_PCM_FRAMES = 128
            private const val FINISH_TIMEOUT_SECONDS = 15L
        }
    }

    /**
     * Returns true if [audioFile] contains at least one speech segment (or if the check could not be run,
     * so a real recording is never dropped by a gate failure); false only when the VAD is confident there
     * is no speech at all. Decoding + VAD run on [Dispatchers.Default]; a short clip that begins with
     * speech exits as soon as the first segment closes.
     */
    suspend fun hasSpeech(context: Context, audioFile: File): Boolean = withContext(Dispatchers.Default) {
        val totalStartedNanos = System.nanoTime()
        val model = ensureVadModel(context.applicationContext) ?: return@withContext true
        val decodeStartedNanos = System.nanoTime()
        val samples = runCatching { AudioDecode.decodeToMono16k(audioFile) }.getOrNull()
            ?: return@withContext true
        val decodeMs = elapsedMillis(decodeStartedNanos)
        if (samples.isEmpty()) return@withContext false

        vadLock.withLock {
            val createStartedNanos = System.nanoTime()
            val vad = cachedVad ?: createVad(model)?.also { cachedVad = it }
                ?: return@withLock true
            val createMs = elapsedMillis(createStartedNanos)
            val runStartedNanos = System.nanoTime()
            try {
                vad.reset()
                val window = FloatArray(WINDOW)
                var i = 0
                while (i < samples.size) {
                    val end = minOf(i + WINDOW, samples.size)
                    val chunk = if (end - i == WINDOW) {
                        samples.copyInto(window, destinationOffset = 0, startIndex = i, endIndex = end)
                        window
                    } else {
                        samples.copyOfRange(i, end)
                    }
                    vad.acceptWaveform(chunk)
                    i = end
                    if (!vad.empty()) {
                        Log.i(
                            LOG_TAG,
                            "speechGate decodeMs=$decodeMs createMs=$createMs " +
                                "runMs=${elapsedMillis(runStartedNanos)} totalMs=${elapsedMillis(totalStartedNanos)} speech=true",
                        )
                        return@withLock true
                    }
                }
                // No segment closed mid-stream (e.g. speech ran right up to the end): flush and re-check.
                vad.flush()
                val speech = !vad.empty()
                Log.i(
                    LOG_TAG,
                    "speechGate decodeMs=$decodeMs createMs=$createMs " +
                        "runMs=${elapsedMillis(runStartedNanos)} totalMs=${elapsedMillis(totalStartedNanos)} speech=$speech",
                )
                speech
            } catch (_: Throwable) {
                // A native session that faulted is not reused. The next recording gets a fresh one.
                cachedVad = null
                runCatching { vad.release() }
                true // fail open
            }
        }
    }

    private fun createVad(model: File): Vad? = runCatching {
        Vad(
            config = VadModelConfig().apply {
                sileroVadModelConfig = SileroVadModelConfig(
                    model = model.absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.25f,
                    windowSize = WINDOW,
                    maxSpeechDuration = 20f,
                )
                sampleRate = AudioDecode.TARGET_SAMPLE_RATE
                numThreads = 1
            },
        )
    }.getOrNull()

    private fun elapsedMillis(startedNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

    /** The fixed Silero window size (samples), reused by the live splitter (issue #170). */
    internal const val VAD_WINDOW = WINDOW

    /**
     * Extracts the bundled Silero VAD model to a stable file path (sherpa-onnx needs a filesystem path,
     * not an asset stream) and returns it, or null if extraction fails. Copied once; re-copied only if the
     * on-disk size doesn't match the expected model. Internal so the live splitter ([LiveSpeechSplitter])
     * can reuse the same bundled model.
     */
    internal fun ensureVadModel(appContext: Context): File? {
        val dest = File(File(appContext.filesDir, "vad").apply { mkdirs() }, "silero_vad.onnx")
        if (dest.isFile && dest.length() == VAD_MODEL_BYTES) return dest
        return runCatching {
            val tmp = File(dest.parentFile, "silero_vad.onnx.tmp")
            appContext.assets.open(ASSET_PATH).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            dest.delete()
            check(tmp.renameTo(dest)) { "could not move VAD model into place" }
            dest
        }.getOrNull()
    }
}
