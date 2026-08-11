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

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dev.patrickgold.florisboard.dictate.audio.AudioDecode
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device **live** speech-to-text (issue #233): the streaming counterpart to the one-shot
 * [LocalTranscriptionProvider]. Audio is pushed in as the microphone captures it and transcript pieces
 * come back while the user is still speaking, so the text types itself into the field — the same
 * experience the cloud realtime providers give (#128), except nothing leaves the device.
 *
 * This plugs into the very same [RealtimeSession] seam the WebSocket clients use, so the dictation
 * engine does not need to know whether it is talking to a socket or to a local neural network.
 *
 * Only models flagged [LocalModelSpec.isStreaming] work here: an offline model such as Whisper cannot
 * emit anything until it has seen the whole utterance. Streaming models are transducers whose encoder
 * consumes audio in fixed chunks, which is why partial results arrive in steps rather than continuously.
 *
 * **Threading.** [sendAudio] is called from the recorder's audio thread and must never block it, so the
 * PCM is only converted there and the neural network runs on a private worker thread. Model loading
 * (hundreds of milliseconds) happens on that worker too; audio captured meanwhile simply queues up and
 * is decoded once the model is ready — nothing is lost, the first partial just appears a little later.
 */
class LocalRealtimeSession(
    private val modelDir: File,
    private val callbacks: RealtimeCallbacks,
    private val numThreads: Int = 2,
) : RealtimeSession {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "stt-live").apply { isDaemon = true }
    }

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

    /** Text of the segment currently being decoded; only a change is worth reporting upwards. */
    private var lastPartial = ""

    /** Samples fed into the current segment — drives the [MAX_SEGMENT_SAMPLES] safety cut. */
    private var segmentSamples = 0L

    /** Frames queued but not yet decoded; lets [sendAudio] shed load if decoding falls behind. */
    private val pending = AtomicInteger(0)

    /** Set once [finish] or [cancel] ran, so a second call (or a late frame) is ignored. */
    @Volatile private var ended = false

    /** Set by [cancel] only: makes every already-queued frame a no-op instead of decoding it. */
    @Volatile private var cancelled = false

    @Volatile private var failed = false

    init {
        worker.execute {
            try {
                val rec = OnlineRecognizerCache.acquire(modelDir, numThreads)
                recognizer = rec
                stream = rec.createStream()
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    override fun sendAudio(pcm16: ByteArray, len: Int) {
        if (ended || failed) return
        // Drop rather than pile up without bound: if decoding ever falls behind realtime (a slow device,
        // a big model), an unbounded queue would keep growing for the whole recording and delay the
        // final transcript by minutes. Losing a frame degrades this partial; the batch fallback on stop
        // still transcribes the untouched WAV in full.
        if (pending.get() >= MAX_PENDING_CHUNKS) return
        val samples = toFloatSamples(pcm16, len)
        if (samples.isEmpty()) return
        pending.incrementAndGet()
        try {
            worker.execute {
                pending.decrementAndGet()
                feed(samples)
            }
        } catch (t: Throwable) {
            pending.decrementAndGet() // executor already shut down (finish/cancel raced us)
        }
    }

    /** Decodes everything the encoder has enough audio for, then reports what changed. */
    private fun feed(samples: FloatArray) {
        val rec = recognizer ?: return
        val s = stream ?: return
        if (cancelled || failed) return
        try {
            s.acceptWaveform(samples, AudioDecode.TARGET_SAMPLE_RATE)
            segmentSamples += samples.size
            while (rec.isReady(s)) rec.decode(s)
            val text = rec.getResult(s).text
            // A speech pause ends the segment: settle the text so the engine can append it and let the
            // decoder start fresh, which also keeps its internal context (and CPU cost) bounded.
            // Continuous speech never triggers this, hence the length cut as a backstop.
            if (rec.isEndpoint(s) || segmentSamples >= MAX_SEGMENT_SAMPLES) {
                finalizeSegment(rec, s)
            } else if (text != lastPartial) {
                lastPartial = text
                if (text.isNotBlank()) callbacks.onPartial(text)
            }
        } catch (t: Throwable) {
            fail(t)
        }
    }

    private fun finalizeSegment(rec: OnlineRecognizer, s: OnlineStream) {
        val text = rec.getResult(s).text
        rec.reset(s)
        segmentSamples = 0
        lastPartial = ""
        if (text.isNotBlank()) callbacks.onFinalSegment(text)
    }

    override fun finish() {
        if (ended) return
        ended = true
        // Queued frames still decode (the executor is FIFO and only `cancelled` short-circuits them),
        // so audio captured in the last moments before the stop button still makes it into the text.
        worker.execute {
            val rec = recognizer
            val s = stream
            if (rec != null && s != null && !failed) {
                try {
                    // Tail padding: the encoder needs a little audio past the last word before it will
                    // emit it, so without this the final word of the dictation is regularly swallowed.
                    s.acceptWaveform(FloatArray(TAIL_PAD_SAMPLES), AudioDecode.TARGET_SAMPLE_RATE)
                    s.inputFinished()
                    while (rec.isReady(s)) rec.decode(s)
                    val text = rec.getResult(s).text
                    if (text.isNotBlank()) callbacks.onFinalSegment(text)
                } catch (t: Throwable) {
                    failed = true
                    callbacks.onError(t)
                }
            }
            release()
            callbacks.onClosed()
        }
        worker.shutdown()
    }

    override fun cancel() {
        if (ended) return
        ended = true
        cancelled = true
        // Queued frames now short-circuit, so this returns quickly. The release still runs *on* the
        // worker so native objects are never freed underneath a decode that is already in flight.
        worker.execute {
            release()
            callbacks.onClosed()
        }
        worker.shutdown()
    }

    private fun fail(t: Throwable) {
        if (failed) return
        failed = true
        callbacks.onError(t)
    }

    /** Frees the per-session stream and returns the (cached, shared) recognizer. Worker thread only. */
    private fun release() {
        runCatching { stream?.release() }
        stream = null
        if (recognizer != null) {
            recognizer = null
            OnlineRecognizerCache.endUse()
        }
    }

    private fun toFloatSamples(pcm16: ByteArray, len: Int): FloatArray {
        val count = (len.coerceAtMost(pcm16.size)) / 2
        val out = FloatArray(count)
        var i = 0
        while (i < count) {
            val lo = pcm16[i * 2].toInt() and 0xFF
            val hi = pcm16[i * 2 + 1].toInt() // signed: carries the sign of the sample
            out[i] = ((hi shl 8) or lo) / 32768f
            i++
        }
        return out
    }

    companion object {
        /** ~0.4 s of silence appended on [finish] so the last word makes it out of the encoder. */
        private const val TAIL_PAD_SAMPLES = (0.4 * AudioDecode.TARGET_SAMPLE_RATE).toInt()

        /**
         * Hard cut for a segment that never hits a speech pause (reading aloud, dictating without
         * breathing room). Without it the decoder would accumulate one ever-growing partial for the
         * whole recording.
         */
        private const val MAX_SEGMENT_SAMPLES = 45L * AudioDecode.TARGET_SAMPLE_RATE

        /** ~6 s of backlog (frames are ~100 ms) before [sendAudio] starts dropping. */
        private const val MAX_PENDING_CHUNKS = 60
    }
}

/**
 * Process-wide cache of the streaming [OnlineRecognizer], mirroring the offline `RecognizerCache` in
 * [LocalTranscriptionProvider]: building one loads the model into native memory, so it is kept alive
 * between recordings and freed on idle or on Android memory pressure — never while a decode is running.
 */
internal object OnlineRecognizerCache {
    private var key: String? = null
    private var recognizer: OnlineRecognizer? = null

    private var activeUsers = 0
    private var releasePending = false

    @Volatile
    var idleUnloadMillis: Long = 0L
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "stt-live-idle-unload").apply { isDaemon = true }
    }
    private var idleFuture: ScheduledFuture<*>? = null

    @Synchronized
    fun acquire(modelDir: File, numThreads: Int): OnlineRecognizer {
        idleFuture?.cancel(false)
        idleFuture = null

        val encoder = File(modelDir, LocalTranscriptionProvider.ENCODER)
        val decoder = File(modelDir, LocalTranscriptionProvider.DECODER)
        val joiner = File(modelDir, LocalTranscriptionProvider.JOINER)
        val tokens = File(modelDir, LocalTranscriptionProvider.TOKENS)
        if (!encoder.exists() || !decoder.exists() || !joiner.exists() || !tokens.exists()) {
            throw DictateApiException(
                DictateApiException.Kind.UNKNOWN,
                "On-device live model '${modelDir.name}' is not installed",
            )
        }

        val cacheKey = modelDir.absolutePath
        val existing = recognizer
        val rec = if (existing != null && cacheKey == key) {
            existing
        } else {
            existing?.release()
            recognizer = null
            key = null
            build(encoder, decoder, joiner, tokens, numThreads).also {
                recognizer = it
                key = cacheKey
            }
        }
        releasePending = false
        activeUsers++
        return rec
    }

    @Synchronized
    fun endUse() {
        if (activeUsers > 0) activeUsers--
        if (activeUsers == 0) {
            if (releasePending) freeNow() else armIdle()
        }
    }

    @Synchronized
    fun unload() {
        if (activeUsers > 0) {
            releasePending = true
            return
        }
        freeNow()
    }

    private fun freeNow() {
        idleFuture?.cancel(false)
        idleFuture = null
        recognizer?.release()
        recognizer = null
        key = null
        releasePending = false
    }

    private fun armIdle() {
        idleFuture?.cancel(false)
        idleFuture = null
        val delay = idleUnloadMillis
        if (delay > 0 && recognizer != null) {
            idleFuture = scheduler.schedule({ unload() }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun build(
        encoder: File,
        decoder: File,
        joiner: File,
        tokens: File,
        numThreads: Int,
    ): OnlineRecognizer {
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = AudioDecode.TARGET_SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    joiner = joiner.absolutePath,
                ),
                tokens = tokens.absolutePath,
                numThreads = numThreads,
                // Left empty on purpose: sherpa-onnx reads the architecture ("zipformer2") out of the
                // encoder's ONNX metadata. Hard-coding it here would break the moment a model of a
                // different streaming family is added to the catalog.
                modelType = "",
            ),
            // Endpointing turns a speech pause into a settled segment (see LocalRealtimeSession.feed).
            // rule1 = silence with nothing decoded yet, rule2 = silence after speech, rule3 = utterance
            // length cap; the values match sherpa-onnx's defaults for dictation-style input.
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                rule2 = EndpointRule(true, 1.2f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 300f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        return OnlineRecognizer(config = config)
    }
}
