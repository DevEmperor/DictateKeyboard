/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.importer

import android.content.Context
import android.util.Log
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.dictate.DictateLanguages
import dev.patrickgold.florisboard.dictate.audio.AudioConvert
import dev.patrickgold.florisboard.dictate.audio.AudioDecode
import dev.patrickgold.florisboard.dictate.audio.AudioWav
import dev.patrickgold.florisboard.dictate.audio.SpeechGate
import dev.patrickgold.florisboard.dictate.dictateProxyConfig
import dev.patrickgold.florisboard.dictate.provider.LocalTranscriptionProvider
import dev.patrickgold.florisboard.dictate.provider.OpenAiCompatibleClient
import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.dictate.provider.TranscriptionApi
import dev.patrickgold.florisboard.dictate.provider.TranscriptionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Transcribing a file the user shared or picked (issue #301).
 *
 * A sibling of `wear/PhoneTranscriber`, and stateless for the same reason: `DictateController` is a
 * process-wide singleton with one state and one latched output target, so a share screen running
 * through it would fight the keyboard for both — its stop button would cancel our work, and its
 * commit could land in whatever field it happens to be attached to (see #293). The screen writes
 * into no editor at all, so it needs none of that machinery.
 */
object ImportTranscriber {

    private const val LOG_TAG = "DictateImport"

    /** Header plus a little slack, so a chunk that fills its budget still fits after packaging. */
    private const val WAV_OVERHEAD_BYTES = 8 * 1024

    class NoSpeechException : Exception()

    /**
     * Transcribes [audio], splitting it first when it cannot be sent in one piece.
     *
     * [onProgress] reports `(done, total)` over the pieces so a long file can show its progress
     * rather than a spinner that never moves. Cancellation is cooperative: the coroutine is checked
     * between pieces, so stopping a ten-part job never costs more than the part in flight.
     */
    suspend fun transcribe(
        context: Context,
        prefs: FlorisPreferenceModel,
        audio: File,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val account = accountFor(prefs)
        val preset = presetFor(account)
        val onDevice = preset.transcriptionApi == TranscriptionApi.LOCAL_ONDEVICE
        val model = account.transcriptionModel.ifBlank { preset.defaultTranscriptionModel ?: "" }

        val pieces = split(appContext, audio, account.providerId, onDevice)
        onProgress(0, pieces.size)
        val parts = ArrayList<String>(pieces.size)
        try {
            for ((index, piece) in pieces.withIndex()) {
                val text = transcribeOne(appContext, prefs, account, preset, model, piece, onDevice)
                if (text.isNotBlank()) parts.add(text)
                onProgress(index + 1, pieces.size)
            }
        } finally {
            // Only the pieces we made ourselves; the original belongs to the caller, which still needs
            // it for playback and for the history entry.
            for (piece in pieces) if (piece != audio) piece.delete()
        }
        val joined = parts.joinToString(" ").trim()
        if (joined.isEmpty()) throw NoSpeechException()
        joined
    }

    /**
     * The whole file as one piece, or several written to the cache.
     *
     * Video always goes through the decoder: a provider that accepts audio has no reason to accept an
     * MP4, and the track we want is in there either way.
     */
    private suspend fun split(
        appContext: Context,
        audio: File,
        providerId: String,
        onDevice: Boolean,
    ): List<File> {
        val limit = ProviderRegistry.maxUploadBytes(providerId)
        // 0 means "unknown", never "unlimited" — the one trap in this function. An unknown limit is
        // left to the provider to enforce; the error surfaces it.
        val overLimit = limit > 0L && audio.length() > limit
        val isVideo = looksLikeVideo(audio)
        // On-device has no upload at all, so nothing has to be cut for size — but a decode is still
        // what the engine wants, and a video still has to be unpacked.
        if (!overLimit && !isVideo) return listOf(audio)
        if (onDevice && !isVideo) return listOf(audio)

        val analysis = SpeechGate.analyze(appContext, audio)
        if (analysis == null) {
            // The VAD could not run (no model, or the decode failed). Nothing is guessed here: a file
            // that is merely large is handed over as it is and the provider decides.
            Log.i(LOG_TAG, "import split: no analysis, passing through (overLimit=$overLimit)")
            return listOf(audio)
        }
        if (!analysis.hasSpeech) return emptyList()

        // Budget in samples, from the byte budget: 16 kHz mono PCM16 is two bytes a sample. Without a
        // known limit the file is only being split because it is a video, so one piece is right.
        val budgetBytes = if (limit > 0L) limit - WAV_OVERHEAD_BYTES else Long.MAX_VALUE
        val maxSamples = if (budgetBytes >= Int.MAX_VALUE.toLong() * 2) Int.MAX_VALUE
        else (budgetBytes / 2).toInt().coerceAtLeast(AudioDecode.TARGET_SAMPLE_RATE)
        val ranges = ImportChunkPlanner.plan(analysis.segments, analysis.samples.size, maxSamples)
        if (ranges.isEmpty()) return emptyList()

        val dir = File(appContext.cacheDir, "dictate_share_parts").apply { deleteRecursively(); mkdirs() }
        val out = ArrayList<File>(ranges.size)
        for ((i, range) in ranges.withIndex()) {
            val file = File(dir, "part_${i + 1}.wav")
            if (writeSlice(analysis.samples, analysis.sampleRate, range, file)) out.add(file)
        }
        Log.i(LOG_TAG, "import split: ${out.size} piece(s) from ${audio.length()} bytes, limit=$limit")
        return out.ifEmpty { listOf(audio) }
    }

    /** Writes one sample range as 16 kHz mono PCM16 WAV. */
    private fun writeSlice(samples: FloatArray, sampleRate: Int, range: IntRange, outFile: File): Boolean =
        AudioWav.write(samples, sampleRate, outFile, listOf(intArrayOf(range.first, range.last + 1)))

    private suspend fun transcribeOne(
        appContext: Context,
        prefs: FlorisPreferenceModel,
        account: ProviderAccount,
        preset: dev.patrickgold.florisboard.dictate.provider.ProviderPreset,
        model: String,
        audio: File,
        onDevice: Boolean,
    ): String {
        // The container the user brought is the whole point of this screen, so it is also where a
        // provider is most likely to be handed something it does not take (issue #322). A slice this
        // function made is already WAV and passes straight through; a file small enough to go up whole
        // is whatever the sharing app wrote. On-device decodes anything and needs no conversion.
        val converted = if (onDevice) null else {
            AudioConvert.toAccepted(appContext.cacheDir, audio, preset.acceptedAudioContainers)
        }
        try {
            return transcribeFile(appContext, prefs, account, preset, model, converted ?: audio, onDevice)
        } finally {
            converted?.let { runCatching { it.delete() } }
        }
    }

    private suspend fun transcribeFile(
        appContext: Context,
        prefs: FlorisPreferenceModel,
        account: ProviderAccount,
        preset: dev.patrickgold.florisboard.dictate.provider.ProviderPreset,
        model: String,
        audio: File,
        onDevice: Boolean,
    ): String {
        val request = TranscriptionRequest(
            audioFile = audio,
            model = model,
            language = prefs.dictate.activeInputLanguage.get().takeIf { it != DictateLanguages.DETECT },
            // The same list-shaped hint the keyboard sends (#99), so an import is recognised in the
            // languages the user actually speaks.
            expectedLanguages = DictateLanguages.expectedLanguages(
                activeCode = prefs.dictate.activeInputLanguage.get(),
                selectionRaw = prefs.dictate.inputLanguages.get(),
            ),
        )
        return if (onDevice) {
            LocalTranscriptionProvider(LocalTranscriptionProvider.modelDir(appContext, model))
                .transcribe(request).text.trim()
        } else {
            OpenAiCompatibleClient.from(
                preset,
                account.apiKey,
                baseUrlOverride = if (account.isCustom || preset.allowsCustomBaseUrl) {
                    account.customBaseUrl.takeIf { it.isNotBlank() }
                } else null,
                proxy = prefs.dictate.dictateProxyConfig(),
                trustUserCerts = prefs.dictate.trustUserCertificates.get(),
            ).transcribe(request).text.trim()
        }
    }

    /** The provider this import uses: the one configured for transcription, like every other path. */
    fun accountFor(prefs: FlorisPreferenceModel): ProviderAccount =
        prefs.dictate.providerAccounts.get().getOrEmpty(prefs.dictate.transcriptionProviderId.get())

    fun presetFor(account: ProviderAccount) = when {
        account.isCustom -> ProviderRegistry.custom(account.customBaseUrl, realtime = account.customRealtime)
        else -> ProviderRegistry.byId(account.providerId) ?: ProviderRegistry.OPENAI
    }

    private fun looksLikeVideo(file: File): Boolean =
        file.extension.lowercase() in setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi")
}
