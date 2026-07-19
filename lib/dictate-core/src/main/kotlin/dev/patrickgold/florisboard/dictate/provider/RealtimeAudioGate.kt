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

import java.util.ArrayDeque

/**
 * Preserves microphone audio while a realtime provider completes a mandatory handshake.
 *
 * Some providers require a config/setup exchange before the first audio frame. The microphone is already
 * running by then, so dropping those early frames cuts off the first word on an ordinary network and can
 * lose several words on a slow connection. This gate copies the reused capture buffers until [markReady],
 * then flushes them in order before allowing live frames through.
 *
 * The buffer is deliberately bounded. One MiB holds more than 30 seconds of 16 kHz mono PCM16, comfortably
 * beyond the WebSocket connect timeout; if a provider never completes its setup, the parallel WAV remains
 * the lossless batch fallback without letting this queue grow for the whole recording.
 */
internal class RealtimeAudioGate(
    private val maxBufferedBytes: Int = DEFAULT_MAX_BUFFERED_BYTES,
) {
    private val pendingAudio = ArrayDeque<ByteArray>()
    private var bufferedBytes = 0
    private var ready = false
    private var finishRequested = false
    private var finished = false
    private var closed = false

    init {
        require(maxBufferedBytes > 0) { "maxBufferedBytes must be positive" }
    }

    /** Queues a defensive copy before setup, or sends synchronously once setup has completed. */
    fun sendAudio(pcm16: ByteArray, len: Int, send: (ByteArray, Int) -> Unit) {
        val safeLen = len.coerceIn(0, pcm16.size)
        if (safeLen == 0) return
        synchronized(this) {
            if (closed || finished) return
            if (ready) {
                send(pcm16, safeLen)
            } else if (bufferedBytes + safeLen <= maxBufferedBytes) {
                pendingAudio.addLast(pcm16.copyOf(safeLen))
                bufferedBytes += safeLen
            }
        }
    }

    /** Flushes pending frames in capture order, then honors an end request made during setup. */
    fun markReady(send: (ByteArray, Int) -> Unit, finish: () -> Unit) {
        synchronized(this) {
            if (closed || ready) return
            ready = true
            while (pendingAudio.isNotEmpty()) {
                val pcm16 = pendingAudio.removeFirst()
                send(pcm16, pcm16.size)
            }
            bufferedBytes = 0
            if (finishRequested) {
                finished = true
                finish()
            }
        }
    }

    /** Sends the provider's end marker now, or defers it until after setup and buffered audio. */
    fun finish(sendFinish: () -> Unit) {
        synchronized(this) {
            if (closed || finished) return
            if (ready) {
                finished = true
                sendFinish()
            } else {
                finishRequested = true
            }
        }
    }

    /** Discards pending audio and ignores late setup callbacks after cancellation/failure/close. */
    fun close() {
        synchronized(this) {
            closed = true
            pendingAudio.clear()
            bufferedBytes = 0
        }
    }

    private companion object {
        const val DEFAULT_MAX_BUFFERED_BYTES = 1024 * 1024
    }
}
