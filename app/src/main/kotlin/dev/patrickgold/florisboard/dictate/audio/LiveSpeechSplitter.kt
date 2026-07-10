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
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Pipecat-compatible Smart Turn v3 pipeline for long-form segmentation (issue #170): Silero VAD first
 * detects a short speech pause, then Smart Turn classifies the latest eight seconds of the current turn.
 * A complete prediction cuts immediately; an incomplete prediction keeps buffering until speech resumes
 * or [pauseThresholdMs] expires as the safety fallback.
 *
 * VAD and Smart Turn run on separate single-thread workers. [feed] only copies into a queue, so neither
 * model blocks microphone capture. Prediction results are posted back through the same ordered event queue:
 * if speech resumed during inference, that audio is processed first and the stale result is discarded.
 * If Smart Turn is unavailable, the VAD silence fallback and manual Next button remain functional.
 *
 * @param pauseThresholdMs how long a pause must last (after speech) before a cut is fired.
 * @param onPause invoked (on the worker thread) when a qualifying pause is detected; must be cheap and
 *   thread-safe (e.g. it hands off to a coroutine).
 */
class LiveSpeechSplitter(
    context: Context,
    private val pauseThresholdMs: Int,
    private val onPause: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val queue = LinkedBlockingQueue<Event>()
    private val inferenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dictate-smart-turn").apply { isDaemon = true }
    }
    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = Thread({ loop() }, "dictate-vad-split").also {
            it.isDaemon = true
            it.start()
        }
    }

    /** Called on the capture thread: copies the frame and hands it to the worker. Must stay non-blocking. */
    fun feed(pcm16: ByteArray, len: Int) {
        if (!running) return
        val shorts = ShortArray(len / 2)
        var i = 0
        var j = 0
        while (i + 1 < len) {
            shorts[j++] = ((pcm16[i].toInt() and 0xff) or (pcm16[i + 1].toInt() shl 8)).toShort()
            i += 2
        }
        queue.offer(Event.Audio(shorts))
    }

    /** After a cut (manual or auto), require fresh speech before the next auto-cut can fire. */
    fun notifyCut() {
        queue.offer(Event.Reset)
    }

    fun release() {
        running = false
        worker?.interrupt()
        worker = null
        inferenceExecutor.shutdownNow()
        queue.clear()
    }

    private fun loop() {
        val model = SpeechGate.ensureVadModel(appContext) ?: return
        val vad = runCatching {
            Vad(
                config = VadModelConfig().apply {
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = model.absolutePath,
                        threshold = 0.5f,
                        minSilenceDuration = 0.25f,
                        minSpeechDuration = 0.25f,
                        windowSize = SpeechGate.VAD_WINDOW,
                        maxSpeechDuration = 20f,
                    )
                    sampleRate = AudioDecode.TARGET_SAMPLE_RATE
                    numThreads = 1
                },
            )
        }.getOrNull() ?: return

        val window = FloatArray(SpeechGate.VAD_WINDOW)
        val turnBuffer = SmartTurnPcmBuffer()
        var filled = 0
        // Pipecat invokes Smart Turn after Silero reports 0.2 seconds of silence. An incomplete turn then
        // falls back to stop_secs (the user-facing maximum-pause slider, 2–8 seconds in Dictate).
        val windowMs = SpeechGate.VAD_WINDOW * 1000 / AudioDecode.TARGET_SAMPLE_RATE
        val smartTurnWindows = ((SMART_TURN_VAD_STOP_MS + windowMs - 1) / windowMs).coerceAtLeast(1)
        val fallbackWindows = ((pauseThresholdMs + windowMs - 1) / windowMs).coerceAtLeast(smartTurnWindows)
        val policy = SmartTurnPausePolicy(smartTurnWindows, fallbackWindows)

        fun resetDetector() {
            turnBuffer.clear()
            filled = 0
            runCatching { vad.reset() }
        }

        fun handle(action: SmartTurnPausePolicy.Action) {
            when (action) {
                SmartTurnPausePolicy.Action.None -> Unit
                SmartTurnPausePolicy.Action.BeginTurn -> {
                    // Match Pipecat's 500 ms pre-speech buffer plus the VAD's 200 ms start confirmation.
                    turnBuffer.keepNewest(PRE_SPEECH_SAMPLES)
                }
                SmartTurnPausePolicy.Action.Cut -> {
                    resetDetector()
                    runCatching { onPause() }
                }
                is SmartTurnPausePolicy.Action.Analyze -> {
                    val audio = turnBuffer.snapshotNormalizedLeftPadded()
                    runCatching {
                        inferenceExecutor.execute {
                            val complete = if (running) SmartTurnModel.predictsComplete(appContext, audio) else null
                            if (running) queue.offer(Event.Prediction(action.epoch, complete))
                        }
                    }.onFailure {
                        queue.offer(Event.Prediction(action.epoch, null))
                    }
                }
            }
        }

        try {
            while (running) {
                val event = try {
                    queue.poll(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                } ?: continue
                if (event is Event.Reset) {
                    policy.reset()
                    resetDetector()
                    continue
                }
                if (event is Event.Prediction) {
                    handle(policy.onPrediction(event.epoch, event.complete))
                    continue
                }
                val chunk = (event as Event.Audio).samples
                turnBuffer.append(chunk)
                var idx = 0
                while (idx < chunk.size) {
                    val take = minOf(SpeechGate.VAD_WINDOW - filled, chunk.size - idx)
                    var k = 0
                    while (k < take) {
                        window[filled + k] = chunk[idx + k] / 32768f
                        k++
                    }
                    filled += take
                    idx += take
                    if (filled < SpeechGate.VAD_WINDOW) continue
                    filled = 0
                    runCatching { vad.acceptWaveform(window) }
                    val speech = runCatching { vad.isSpeechDetected() }.getOrDefault(false)
                    handle(policy.onVadWindow(speech))
                }
            }
        } catch (_: Throwable) {
            // Fail silent — manual cutting still works.
        } finally {
            runCatching { vad.release() }
        }
    }

    private sealed interface Event {
        data class Audio(val samples: ShortArray) : Event
        data class Prediction(val epoch: Long, val complete: Boolean?) : Event
        data object Reset : Event
    }

    private companion object {
        const val SMART_TURN_VAD_STOP_MS = 200
        const val PRE_SPEECH_MS = 500 + 200
        const val PRE_SPEECH_SAMPLES = AudioDecode.TARGET_SAMPLE_RATE * PRE_SPEECH_MS / 1000
    }
}
