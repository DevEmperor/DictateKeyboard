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

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Process-wide Smart Turn v3.2 CPU session. The first prediction initializes the model; subsequent
 * recordings reuse the optimized session so auto-segmentation does not repeatedly pay model-load cost.
 *
 * The bundled model is the official Pipecat quantized CPU classifier with Pipecat's Whisper feature
 * extractor prepended by `tools/build-smart-turn-model.py`. It accepts exactly eight seconds of 16 kHz
 * mono float PCM, left-padded with zeroes as required by Smart Turn.
 */
internal object SmartTurnModel {
    const val SAMPLE_COUNT = AudioDecode.TARGET_SAMPLE_RATE * 8

    private const val ASSET_PATH = "dictate/smart-turn-v3.2-cpu.onnx"
    private const val MODEL_FILE_NAME = "smart-turn-v3.2-cpu-dictate.onnx"
    private const val MODEL_BYTES = 8_840_701L
    private const val COMPLETE_THRESHOLD = 0.5f

    @Volatile private var holder: SessionHolder? = null
    private val lock = Any()

    /** Returns null when the local model/runtime cannot be used, allowing the silence fallback to win. */
    fun predictsComplete(context: Context, audio: FloatArray): Boolean? {
        if (audio.size != SAMPLE_COUNT) return null
        val active = holder ?: synchronized(lock) {
            holder ?: createSession(context.applicationContext)?.also { holder = it }
        } ?: return null
        return synchronized(active) { active.predict(audio) }
    }

    private fun createSession(context: Context): SessionHolder? = runCatching {
        val model = ensureModel(context) ?: return null
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setInterOpNumThreads(1)
            setIntraOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        try {
            try {
                SessionHolder(environment, environment.createSession(model.absolutePath, options))
            } catch (t: Throwable) {
                // A same-sized but corrupted extracted model must not poison every future recording.
                model.delete()
                throw t
            }
        } finally {
            options.close()
        }
    }.getOrNull()

    private fun ensureModel(context: Context): File? {
        val directory = File(context.filesDir, "smart-turn").apply { mkdirs() }
        val destination = File(directory, MODEL_FILE_NAME)
        if (destination.isFile && destination.length() == MODEL_BYTES) return destination
        return runCatching {
            val temporary = File(directory, "$MODEL_FILE_NAME.tmp")
            context.assets.open(ASSET_PATH).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporary.length() == MODEL_BYTES) { "invalid Smart Turn model size" }
            destination.delete()
            check(temporary.renameTo(destination)) { "could not move Smart Turn model into place" }
            destination
        }.getOrNull()
    }

    private class SessionHolder(
        private val environment: OrtEnvironment,
        private val session: OrtSession,
    ) {
        private val inputBuffer = ByteBuffer.allocateDirect(SAMPLE_COUNT * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        fun predict(audio: FloatArray): Boolean? = runCatching {
            inputBuffer.clear()
            inputBuffer.put(audio)
            inputBuffer.rewind()
            OnnxTensor.createTensor(environment, inputBuffer, longArrayOf(1, SAMPLE_COUNT.toLong())).use { input ->
                session.run(mapOf("audio" to input)).use { result ->
                    val probability = (result.get(0) as OnnxTensor).floatBuffer.get(0)
                    probability > COMPLETE_THRESHOLD
                }
            }
        }.getOrNull()
    }
}

/** Fixed-size rolling turn buffer matching Pipecat's last-eight-seconds + left-padding contract. */
internal class SmartTurnPcmBuffer(private val capacity: Int = SmartTurnModel.SAMPLE_COUNT) {
    private val samples = ShortArray(capacity)
    private var writeIndex = 0
    private var size = 0

    fun append(chunk: ShortArray) {
        if (chunk.size >= capacity) {
            chunk.copyInto(samples, startIndex = chunk.size - capacity)
            writeIndex = 0
            size = capacity
            return
        }
        chunk.forEach { sample ->
            samples[writeIndex] = sample
            writeIndex = (writeIndex + 1) % capacity
            if (size < capacity) size++
        }
    }

    fun clear() {
        writeIndex = 0
        size = 0
    }

    /** Drops old pre-roll while preserving the newest [count] samples in their existing ring positions. */
    fun keepNewest(count: Int) {
        size = size.coerceAtMost(count.coerceIn(0, capacity))
    }

    fun snapshotNormalizedLeftPadded(): FloatArray {
        val output = FloatArray(capacity)
        val destinationStart = capacity - size
        var source = (writeIndex - size + capacity) % capacity
        for (i in 0 until size) {
            output[destinationStart + i] = samples[source] / 32768f
            source = (source + 1) % capacity
        }
        return output
    }
}
