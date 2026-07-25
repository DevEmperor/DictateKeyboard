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
 * every provider even when no on-device model is installed. As a *gate* ([hasSpeech]) it never clips the
 * audio. As an optional *trimmer* ([analyze] + [writeTrimmedWav], issue #232) it can additionally cut long
 * internal pauses out of the recording before upload — keeping every speech segment plus a short pad, and
 * only collapsing silences longer than the threshold — so a dictation with big gaps uploads (and costs)
 * less without losing a word. Trimming is opt-in and only used outside long-form mode (which has its own
 * segment-cutting).
 *
 * Fails open on purpose: if the model can't be prepared or the VAD errors, [hasSpeech] returns true (and
 * [analyze] returns null → no trim) so a genuine recording is never silently dropped or mangled by a
 * gate malfunction.
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

        vadMutex.withLock {
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

    /**
     * The result of a full VAD pass over a recording (issue #232): the decoded 16 kHz mono [samples] and
     * the sample ranges that contain speech ([segments], each `start until endExclusive`). Unlike
     * [hasSpeech] this does not early-exit — it collects every segment so the caller can both decide
     * "is there speech?" ([hasSpeech]) and reconstruct a silence-trimmed clip ([writeTrimmedWav]).
     */
    internal class SpeechAnalysis(
        val samples: FloatArray,
        val sampleRate: Int,
        val segments: List<IntRange>,
    ) {
        val hasSpeech: Boolean get() = segments.isNotEmpty()
    }

    /**
     * Runs the local VAD over [audioFile] and returns its speech segments (issue #232), or null if the
     * check could not run (model unavailable, decode failure, or a native fault) — in which case the
     * caller should treat the audio as speech and leave it untrimmed. A successfully-analysed but
     * speechless clip returns an [SpeechAnalysis] with no segments. Runs on [Dispatchers.Default].
     */
    internal suspend fun analyze(context: Context, audioFile: File): SpeechAnalysis? = withContext(Dispatchers.Default) {
        val totalStartedNanos = System.nanoTime()
        val model = ensureVadModel(context.applicationContext) ?: return@withContext null
        val samples = runCatching { AudioDecode.decodeToMono16k(audioFile) }.getOrNull()
            ?: return@withContext null
        if (samples.isEmpty()) {
            return@withContext SpeechAnalysis(samples, AudioDecode.TARGET_SAMPLE_RATE, emptyList())
        }
        vadMutex.withLock {
            val vad = cachedVad ?: createVad(model)?.also { cachedVad = it } ?: return@withLock null
            try {
                vad.reset()
                val segments = ArrayList<IntRange>()
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
                    drainSegments(vad, segments)
                }
                // Speech that ran up to the very end hasn't closed a segment yet: flush, then collect it.
                vad.flush()
                drainSegments(vad, segments)
                Log.i(
                    LOG_TAG,
                    "speechGate analyze segments=${segments.size} totalMs=${elapsedMillis(totalStartedNanos)}",
                )
                SpeechAnalysis(samples, AudioDecode.TARGET_SAMPLE_RATE, segments)
            } catch (_: Throwable) {
                // A native session that faulted is not reused. Fail open (null → caller leaves audio as is).
                cachedVad = null
                runCatching { vad.release() }
                null
            }
        }
    }

    /** Pulls every closed segment out of the VAD queue as `start until endExclusive` sample ranges. */
    private fun drainSegments(vad: Vad, out: MutableList<IntRange>) {
        while (!vad.empty()) {
            val seg = vad.front()
            val len = seg.samples.size
            if (len > 0 && seg.start >= 0) out.add(seg.start until (seg.start + len))
            vad.pop()
        }
    }

    /**
     * Writes a silence-trimmed copy of the analysed audio to [outFile] and returns it (issue #232), or null
     * if there was nothing worth trimming (in which case the caller keeps the untouched original, so a clip
     * without long pauses is never needlessly re-encoded down to 16 kHz). Every speech segment is kept in
     * full; a silence gap longer than [maxSilenceMs] is collapsed to [keepSilenceMs] (split as a short pad
     * on each side of the cut), while shorter, natural pauses are left intact. Output is 16 kHz mono PCM16.
     */
    internal suspend fun writeTrimmedWav(
        analysis: SpeechAnalysis,
        outFile: File,
        maxSilenceMs: Int,
        keepSilenceMs: Int,
    ): File? = withContext(Dispatchers.Default) {
        val samples = analysis.samples
        val total = samples.size
        val sr = analysis.sampleRate
        if (analysis.segments.isEmpty() || total == 0 || sr <= 0) return@withContext null
        val maxSilence = (maxSilenceMs.toLong() * sr / 1000L).toInt()
        val keepSilence = (keepSilenceMs.toLong() * sr / 1000L).toInt()
        val pad = keepSilence / 2
        // Kept ranges as [startInclusive, endExclusive]; contiguous entries are fine (written back to back).
        val kept = ArrayList<IntArray>()
        fun keep(startIncl: Int, endExcl: Int) {
            val s = startIncl.coerceIn(0, total)
            val e = endExcl.coerceIn(0, total)
            if (e > s) kept.add(intArrayOf(s, e))
        }
        var cursor = 0
        for (seg in analysis.segments) {
            val segStart = seg.first
            val segEnd = seg.last + 1 // range is inclusive; convert back to exclusive end
            val gap = segStart - cursor
            if (gap > maxSilence) {
                keep(cursor, cursor + pad)         // short tail after the previous speech
                keep(segStart - pad, segStart)     // short lead-in before the next speech
            } else {
                keep(cursor, segStart)             // natural pause: keep it
            }
            keep(segStart, segEnd)                 // the speech itself, always in full
            cursor = maxOf(cursor, segEnd)
        }
        val trailing = total - cursor
        if (trailing > maxSilence) keep(cursor, cursor + pad) else keep(cursor, total)

        val keptCount = kept.sumOf { it[1] - it[0] }
        val removed = total - keptCount
        // Not worth re-encoding (and downsampling to 16 kHz) if we'd barely shave anything off.
        if (removed < (MIN_TRIM_MS.toLong() * sr / 1000L).toInt()) return@withContext null

        runCatching {
            outFile.outputStream().buffered().use { os ->
                os.write(wavHeader(sr, channels = 1, bitsPerSample = 16, dataLen = keptCount.toLong() * 2))
                val buf = ByteArray(8192) // even size: two bytes per sample
                var bi = 0
                for (r in kept) {
                    var i = r[0]
                    val e = r[1]
                    while (i < e) {
                        val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
                        buf[bi++] = (v and 0xff).toByte()
                        buf[bi++] = ((v shr 8) and 0xff).toByte()
                        if (bi == buf.size) {
                            os.write(buf, 0, bi)
                            bi = 0
                        }
                        i++
                    }
                }
                if (bi > 0) os.write(buf, 0, bi)
            }
            Log.i(
                LOG_TAG,
                "speechGate trim removedMs=${removed * 1000L / sr} keptMs=${keptCount * 1000L / sr}",
            )
            outFile
        }.getOrElse {
            runCatching { outFile.delete() }
            null
        }
    }

    /** Minimal 44-byte PCM WAV header (little-endian). */
    private fun wavHeader(sampleRate: Int, channels: Int, bitsPerSample: Int, dataLen: Long): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        return java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + dataLen).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)              // PCM subchunk size
            putShort(1)             // audio format = PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort()) // block align
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataLen.toInt())
        }.array()
    }

    /** Below this much removed silence, trimming isn't worth the re-encode (issue #232). */
    private const val MIN_TRIM_MS = 500

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
