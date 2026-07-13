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
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

    /**
     * Returns true if [audioFile] contains at least one speech segment (or if the check could not be run,
     * so a real recording is never dropped by a gate failure); false only when the VAD is confident there
     * is no speech at all. Decoding + VAD run on [Dispatchers.Default]; a short clip that begins with
     * speech exits as soon as the first segment closes.
     */
    suspend fun hasSpeech(context: Context, audioFile: File): Boolean = withContext(Dispatchers.Default) {
        val model = ensureVadModel(context.applicationContext) ?: return@withContext true
        val streamed = runCatching { hasSpeechInRecordedWav(model, audioFile) }
            .getOrElse { return@withContext true }
        if (streamed != null) return@withContext streamed

        val samples = runCatching { AudioDecode.decodeToMono16k(audioFile) }.getOrNull()
            ?: return@withContext true
        if (samples.isEmpty()) return@withContext false

        hasSpeechInDecodedSamples(model, samples)
    }

    private fun hasSpeechInRecordedWav(model: File, audioFile: File): Boolean? {
        val vad = newVad(model) ?: return true
        var sawSamples = false
        var sawSpeech = false

        return try {
            val handled = AudioDecode.streamPcm16Mono16kWav(audioFile, WINDOW) { chunk ->
                sawSamples = true
                vad.acceptWaveform(chunk)
                sawSpeech = !vad.empty()
                !sawSpeech
            }
            when {
                !handled -> null
                !sawSamples -> false
                sawSpeech -> true
                else -> {
                    vad.flush()
                    !vad.empty()
                }
            }
        } catch (_: Throwable) {
            true // fail open
        } finally {
            runCatching { vad.release() }
        }
    }

    private fun hasSpeechInDecodedSamples(model: File, samples: FloatArray): Boolean {
        val vad = newVad(model) ?: return true
        return try {
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
                if (!vad.empty()) return true // a speech segment closed → speech present
            }
            // No segment closed mid-stream (e.g. speech ran right up to the end): flush and re-check.
            vad.flush()
            !vad.empty()
        } catch (_: Throwable) {
            true // fail open
        } finally {
            runCatching { vad.release() }
        }
    }

    private fun newVad(model: File): Vad? = runCatching {
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
