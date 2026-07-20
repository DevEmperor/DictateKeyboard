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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

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
    private val vadMutex = Mutex()
    private var cachedVad: Vad? = null

    /**
     * Creates the native VAD session while the user is still speaking so stopping a recording does not
     * have to pay model/session setup latency. Safe to call repeatedly; only one session is retained.
     */
    suspend fun prewarm(context: Context) = withContext(Dispatchers.Default) {
        val startedNanos = System.nanoTime()
        val model = ensureVadModel(context.applicationContext) ?: return@withContext
        vadMutex.withLock {
            if (cachedVad == null) cachedVad = createVad(model)
        }
        Log.i(LOG_TAG, "speechGate prewarmMs=${elapsedMillis(startedNanos)} ready=${cachedVad != null}")
    }

    /**
     * Returns true if [audioFile] contains at least one speech segment (or if the check could not be run,
     * so a real recording is never dropped by a gate failure); false only when the VAD is confident there
     * is no speech at all. Native PCM16 mono 16 kHz WAV recordings are streamed directly; other formats
     * fall back to decoding. A short clip that begins with speech exits as soon as the first segment closes.
     */
    suspend fun hasSpeech(context: Context, audioFile: File): Boolean = withContext(Dispatchers.Default) {
        val totalStartedNanos = System.nanoTime()
        val model = ensureVadModel(context.applicationContext) ?: return@withContext true
        val streamed = runCatching {
            hasSpeechInRecordedWav(model, audioFile, totalStartedNanos)
        }.getOrElse { return@withContext true }
        if (streamed != null) return@withContext streamed

        val decodeStartedNanos = System.nanoTime()
        val samples = runCatching { AudioDecode.decodeToMono16k(audioFile) }.getOrNull()
            ?: return@withContext true
        val decodeMs = elapsedMillis(decodeStartedNanos)
        if (samples.isEmpty()) return@withContext false

        hasSpeechInDecodedSamples(model, samples, decodeMs, totalStartedNanos)
    }

    private suspend fun hasSpeechInRecordedWav(
        model: File,
        audioFile: File,
        totalStartedNanos: Long,
    ): Boolean? = vadMutex.withLock {
        val createStartedNanos = System.nanoTime()
        val vad = cachedVad ?: createVad(model)?.also { cachedVad = it }
            ?: return@withLock true
        val createMs = elapsedMillis(createStartedNanos)
        val runStartedNanos = System.nanoTime()
        try {
            vad.reset()
            var sawSamples = false
            var sawSpeech = false
            val handled = AudioDecode.streamPcm16Mono16kWav(audioFile, WINDOW) { chunk ->
                sawSamples = true
                vad.acceptWaveform(chunk)
                sawSpeech = !vad.empty()
                !sawSpeech
            }
            if (!handled) return@withLock null
            val speech = when {
                !sawSamples -> false
                sawSpeech -> true
                else -> {
                    vad.flush()
                    !vad.empty()
                }
            }
            Log.i(
                LOG_TAG,
                "speechGate streamed=true createMs=$createMs " +
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

    private suspend fun hasSpeechInDecodedSamples(
        model: File,
        samples: FloatArray,
        decodeMs: Long,
        totalStartedNanos: Long,
    ): Boolean = vadMutex.withLock {
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
                        "speechGate streamed=false decodeMs=$decodeMs createMs=$createMs " +
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
                "speechGate streamed=false decodeMs=$decodeMs createMs=$createMs " +
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
                numThreads = 2
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
