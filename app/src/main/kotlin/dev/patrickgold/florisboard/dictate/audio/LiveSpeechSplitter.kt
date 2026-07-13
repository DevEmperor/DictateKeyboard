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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Streams live microphone PCM through the Silero VAD and fires [onPause] when a long enough speech pause
 * is detected, so long-form segmented dictation (issue #170) can auto-cut a segment without the user
 * tapping "Next". Reuses the bundled Silero model + sherpa-onnx runtime from [SpeechGate] (issue #104).
 *
 * The VAD inference runs on a dedicated worker thread — [feed] (called on the recorder's capture thread)
 * only copies the frame into a queue and returns immediately, so the microphone is never blocked. Fails
 * silent: if the model can't be prepared or the VAD errors, it simply never fires and manual cutting is
 * unaffected.
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
    private val queue = LinkedBlockingQueue<ShortArray>()
    @Volatile private var running = false
    // Require fresh speech since the last cut before firing again, so one long silence yields one cut.
    @Volatile private var hadSpeechSinceCut = false
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
        queue.offer(shorts)
    }

    /** After a cut (manual or auto), require fresh speech before the next auto-cut can fire. */
    fun notifyCut() {
        hadSpeechSinceCut = false
    }

    fun release() {
        running = false
        worker?.interrupt()
        worker = null
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
        var filled = 0
        var silentWindows = 0
        // Each window is 512/16000 ≈ 32 ms of audio; convert the pause threshold to a window count.
        val windowMs = SpeechGate.VAD_WINDOW * 1000 / AudioDecode.TARGET_SAMPLE_RATE
        val silentWindowsToFire = (pauseThresholdMs / windowMs).coerceAtLeast(1)
        try {
            while (running) {
                val chunk = try {
                    queue.poll(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                } ?: continue
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
                    if (speech) {
                        hadSpeechSinceCut = true
                        silentWindows = 0
                    } else {
                        silentWindows++
                        if (hadSpeechSinceCut && silentWindows >= silentWindowsToFire) {
                            hadSpeechSinceCut = false
                            silentWindows = 0
                            runCatching { onPause() }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Fail silent — manual cutting still works.
        } finally {
            runCatching { vad.release() }
        }
    }
}
