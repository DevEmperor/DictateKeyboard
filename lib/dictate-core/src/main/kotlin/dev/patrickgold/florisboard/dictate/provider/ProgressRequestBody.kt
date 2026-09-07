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

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.util.concurrent.TimeUnit

/**
 * A request body that says how much of itself has gone out (issue #337).
 *
 * Wrapped around the audio part of a transcription upload so a shared file can show "65 %" instead of a
 * spinner that never moves. Everything the wrapped body declares — its type, its length, whether it may
 * be sent only once — is **delegated, not recomputed**: a wrapper that lost the content length would
 * silently turn a sized upload into a chunked one, and some of these endpoints refuse those.
 *
 * The count is of bytes handed to the socket, which on a slow link is what the user is waiting for. It
 * reaches 100 % a moment before the provider has finished reading them, and well before it has answered
 * — which is why the transcribing step that follows is a step of its own.
 */
internal class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun isOneShot(): Boolean = delegate.isOneShot()

    override fun isDuplex(): Boolean = delegate.isDuplex()

    override fun writeTo(sink: BufferedSink) {
        val total = runCatching { delegate.contentLength() }.getOrDefault(-1L)
        val counting = object : ForwardingSink(sink) {
            private var sent = 0L
            private var reportedAtNanos = 0L

            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                sent += byteCount
                // Okio hands the sink 8 kB at a time; a 20 MB upload is well over two thousand writes,
                // and every one of them would otherwise redraw the screen. The last byte always reports,
                // so the bar arrives at full rather than stopping just short of it.
                val now = System.nanoTime()
                if (sent == total || now - reportedAtNanos >= REPORT_INTERVAL_NANOS) {
                    reportedAtNanos = now
                    onProgress(sent, total)
                }
            }
        }
        val buffered = counting.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
    }

    private companion object {
        val REPORT_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(80)
    }
}
