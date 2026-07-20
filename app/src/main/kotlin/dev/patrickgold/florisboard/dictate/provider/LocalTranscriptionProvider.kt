/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.provider

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.patrickgold.florisboard.dictate.audio.AudioDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device speech-to-text (issue #104), powered by a bundled Whisper model running through sherpa-onnx.
 * No audio ever leaves the device. This is the offline counterpart to [OpenAiCompatibleClient] and plugs
 * into the same [TranscriptionProvider] seam the dictation flow already uses.
 *
 * A model is a directory under [modelsRoot] containing three fixed-name files (so this class stays
 * agnostic of the specific Whisper variant):
 *
 *   <filesDir>/dictate-models/<modelId>/encoder.onnx
 *                                       /decoder.onnx
 *                                       /tokens.txt
 *
 * The native [OfflineRecognizer] is expensive to construct (it loads the model into memory), so it is
 * cached process-wide in [RecognizerCache] and reused across transcriptions; switching models releases
 * the previous one. Decoding is CPU-bound and runs on [Dispatchers.Default].
 */
class LocalTranscriptionProvider(
    private val modelDir: File,
    private val numThreads: Int = 2,
) : TranscriptionProvider {

    override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult =
        withContext(Dispatchers.Default) {
            val encoder = File(modelDir, ENCODER)
            val decoder = File(modelDir, DECODER)
            val tokens = File(modelDir, TOKENS)
            if (!encoder.exists() || !decoder.exists() || !tokens.exists()) {
                throw DictateApiException(
                    DictateApiException.Kind.UNKNOWN,
                    "On-device model '${modelDir.name}' is not installed",
                )
            }

            val samples = try {
                AudioDecode.decodeToMono16k(request.audioFile)
            } catch (t: Throwable) {
                throw DictateApiException(
                    DictateApiException.Kind.FORMAT_NOT_SUPPORTED,
                    "Could not decode the recorded audio for on-device transcription",
                    t,
                )
            }

            // Honor the user's chosen input language like the cloud providers do; null/blank → Whisper
            // auto-detect. Whisper expects the base ISO code (e.g. "de"), so drop any region suffix.
            val language = request.language?.substringBefore('-')?.takeIf { it.isNotBlank() }.orEmpty()

            val text = try {
                val recognizer = RecognizerCache.acquire(modelDir, numThreads, language)
                val vadFile = File(modelDir, VAD)
                // Whisper handles ~30 s per pass; segment longer audio at speech pauses (VAD) so the
                // tail isn't dropped. Short clips take the simple single-pass path (no VAD overhead).
                if (vadFile.exists() && samples.size > VAD_MIN_SAMPLES) {
                    transcribeSegmented(recognizer, vadFile, samples)
                } else {
                    decodeOnce(recognizer, samples)
                }
            } catch (e: DictateApiException) {
                throw e
            } catch (t: Throwable) {
                throw DictateApiException(
                    DictateApiException.Kind.UNKNOWN,
                    "On-device transcription failed",
                    t,
                )
            }

            TranscriptionResult(text.trim())
        }

    /** Single whole-buffer Whisper pass (fine for clips up to ~30 s). */
    private fun decodeOnce(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, AudioDecode.TARGET_SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    /**
     * Long-audio path: a Silero VAD splits the waveform into speech segments (each capped well under
     * Whisper's 30 s window), every segment is transcribed and the texts are joined in order. Falls back
     * to a single pass if the VAD detects no speech at all.
     */
    private fun transcribeSegmented(
        recognizer: OfflineRecognizer,
        vadFile: File,
        samples: FloatArray,
    ): String {
        val vad = Vad(
            config = VadModelConfig().apply {
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadFile.absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.25f,
                    windowSize = VAD_WINDOW,
                    maxSpeechDuration = 28f, // keep every segment safely inside Whisper's 30 s window
                )
                sampleRate = AudioDecode.TARGET_SAMPLE_RATE
                numThreads = 1
            },
        )
        val parts = StringBuilder()
        try {
            val window = FloatArray(VAD_WINDOW)
            var i = 0
            while (i < samples.size) {
                val end = minOf(i + VAD_WINDOW, samples.size)
                val chunk = if (end - i == VAD_WINDOW) {
                    samples.copyInto(window, destinationOffset = 0, startIndex = i, endIndex = end)
                    window
                } else {
                    samples.copyOfRange(i, end)
                }
                vad.acceptWaveform(chunk)
                i = end
                drainSegments(vad, recognizer, parts)
            }
            vad.flush()
            drainSegments(vad, recognizer, parts)
        } finally {
            vad.release()
        }
        return if (parts.isEmpty()) decodeOnce(recognizer, samples).trim() else parts.toString().trim()
    }

    private fun drainSegments(vad: Vad, recognizer: OfflineRecognizer, out: StringBuilder) {
        while (!vad.empty()) {
            appendDecoded(recognizer, vad.front().samples, out)
            vad.pop()
        }
    }

    /**
     * Decodes [samples], hard-capping each piece below Whisper's 30 s window. VAD normally keeps segments
     * short, but on gap-less continuous speech a segment can still exceed 30 s — without this cap Whisper
     * would silently drop everything past 30 s (the original bug).
     */
    private fun appendDecoded(recognizer: OfflineRecognizer, samples: FloatArray, out: StringBuilder) {
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + MAX_SEGMENT_SAMPLES, samples.size)
            val piece = if (offset == 0 && end == samples.size) samples else samples.copyOfRange(offset, end)
            val text = decodeOnce(recognizer, piece).trim()
            if (text.isNotEmpty()) {
                if (out.isNotEmpty()) out.append(' ')
                out.append(text)
            }
            offset = end
        }
    }

    companion object {
        /** Audio longer than this (~28 s at 16 kHz) is VAD-segmented; shorter takes the single pass. */
        private const val VAD_MIN_SAMPLES = 28 * AudioDecode.TARGET_SAMPLE_RATE
        private const val VAD_WINDOW = 512

        /** Hard ceiling per Whisper pass (~29 s) — above VAD's 28 s cut so normal segments pass whole. */
        private const val MAX_SEGMENT_SAMPLES = 29 * AudioDecode.TARGET_SAMPLE_RATE


        const val MODELS_SUBDIR = "dictate-models"
        const val ENCODER = "encoder.onnx"
        const val DECODER = "decoder.onnx"
        const val TOKENS = "tokens.txt"

        /**
         * Optional joiner for transducer models (e.g. NeMo Parakeet, issue #154). Its presence in a model
         * directory is what makes [RecognizerCache] build a transducer recognizer instead of a Whisper one.
         */
        const val JOINER = "joiner.onnx"

        /** Optional Silero VAD model, downloaded alongside each model; enables long-audio segmenting. */
        const val VAD = "vad.onnx"

        /** Root directory that holds all installed on-device models. */
        fun modelsRoot(context: Context): File = File(context.filesDir, MODELS_SUBDIR)

        /** Directory for a single model id (its `encoder.onnx`/`decoder.onnx`/`tokens.txt` live here). */
        fun modelDir(context: Context, modelId: String): File = File(modelsRoot(context), modelId)

        /** True if [modelId] has all required files present on disk. */
        fun isInstalled(context: Context, modelId: String): Boolean {
            val dir = modelDir(context, modelId)
            return File(dir, ENCODER).exists() && File(dir, DECODER).exists() && File(dir, TOKENS).exists()
        }
    }
}

/**
 * Process-wide cache of the single most-recently-used [OfflineRecognizer]. Building one loads the model
 * into native memory (~1s+ for Whisper tiny), so we keep it alive between transcriptions and only rebuild
 * when the model directory changes. Access is serialized; the app transcribes one clip at a time.
 */
private object RecognizerCache {
    private var key: String? = null
    private var recognizer: OfflineRecognizer? = null

    @Synchronized
    fun acquire(modelDir: File, numThreads: Int, language: String): OfflineRecognizer {
        val encoder = File(modelDir, LocalTranscriptionProvider.ENCODER)
        val decoder = File(modelDir, LocalTranscriptionProvider.DECODER)
        val tokens = File(modelDir, LocalTranscriptionProvider.TOKENS)
        val joiner = File(modelDir, LocalTranscriptionProvider.JOINER)
        val isTransducer = joiner.exists()

        // Language is baked into the Whisper config at build time, so it is part of the cache key
        // (switching the input language rebuilds the recognizer; ~1s). A transducer decodes the audio
        // as-is and ignores the language, so it stays out of the key for those.
        val cacheKey = modelDir.absolutePath + "|" + numThreads + "|" + (if (isTransducer) "" else language)
        val existing = recognizer
        if (existing != null && cacheKey == key) return existing

        existing?.release()
        val modelConfig = if (isTransducer) {
            // NeMo Parakeet TDT (issue #154): encoder/decoder/joiner transducer, model-type per sherpa-onnx.
            OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    joiner = joiner.absolutePath,
                ),
                tokens = tokens.absolutePath,
                numThreads = numThreads,
                modelType = "nemo_transducer",
            )
        } else {
            OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    // "" lets Whisper auto-detect; a base ISO code forces that language.
                    language = language,
                    task = "transcribe",
                ),
                tokens = tokens.absolutePath,
                numThreads = numThreads,
                modelType = "whisper",
            )
        }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = AudioDecode.TARGET_SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
        )
        // assetManager defaults to null → the model is read from the absolute file paths above.
        return OfflineRecognizer(config = config).also {
            recognizer = it
            key = cacheKey
        }
    }
}
