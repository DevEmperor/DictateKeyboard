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
import dev.patrickgold.florisboard.dictate.TranscriptJoin
import dev.patrickgold.florisboard.dictate.audio.AudioConvert
import dev.patrickgold.florisboard.dictate.audio.AudioDecode
import dev.patrickgold.florisboard.dictate.audio.AudioWav
import dev.patrickgold.florisboard.dictate.audio.AudioSpeedUp
import dev.patrickgold.florisboard.dictate.audio.SpeechGate
import dev.patrickgold.florisboard.dictate.dictateProxyConfig
import dev.patrickgold.florisboard.dictate.provider.LocalTranscriptionProvider
import dev.patrickgold.florisboard.dictate.provider.OpenAiCompatibleClient
import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.dictate.provider.TranscriptionApi
import dev.patrickgold.florisboard.dictate.provider.TranscriptionRequest
import dev.patrickgold.florisboard.dictate.transcriptTighteningSymbols
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

    /**
     * Whole-call budget for one piece of an import (issue #337).
     *
     * Fifteen minutes, against two for a dictation, and the difference is not a guess about how slow
     * providers are: a piece may be 25 MB of audio, the per-operation timeouts still catch a dead
     * connection in two minutes, and this screen has visible progress and a cancel button — the
     * reasons a keyboard needs a short leash all point the other way here.
     */
    private const val IMPORT_CALL_TIMEOUT_SECONDS = 900L

    /**
     * How long a provider may stay silent during an import before the connection counts as dead.
     *
     * The call budget above is not enough on its own: while a model works through a piece of audio it
     * sends nothing at all, and that silence is a single read. At the dictation's two minutes, a ten
     * minute piece would be cut off mid-thought however generous the whole-call budget was. Five
     * minutes covers every cloud provider measured so far with room to spare; a self-hosted engine on
     * a slow machine can still outlast it, which is an argument for letting that be configurable
     * rather than for guessing higher here.
     */
    private const val IMPORT_OPERATION_TIMEOUT_SECONDS = 300L

    /** Cache directory holding the copy of the shared file, out of reach of the expiring grant. */
    const val SHARE_DIR = "dictate_share"

    /** Cache directory holding the pieces a long file was cut into. */
    const val PARTS_DIR = "dictate_share_parts"

    class NoSpeechException : Exception()

    /**
     * Throws away everything an import left in the cache.
     *
     * Called when the screen is finished with, because the copy is only ever for this screen —
     * playback, a retry, and the history entry, which keeps a copy of its own in `filesDir`. A shared
     * video can be a hundred megabytes; leaving it lying around until the next cold start (when
     * `FlorisApplication` wipes the cache anyway) is a lot of somebody's storage for nothing.
     */
    fun clearCache(context: Context) {
        runCatching { File(context.cacheDir, SHARE_DIR).deleteRecursively() }
        runCatching { File(context.cacheDir, PARTS_DIR).deleteRecursively() }
    }

    /**
     * Transcribes [audio], splitting it first when it cannot be sent in one piece.
     *
     * [onProgress] names the stage the import is in, so the screen can say which of them is running
     * instead of calling all of it "Preparing…" (issue #337). The stages reported here are the ones
     * this function owns — preparing, uploading, waiting for the answer; the copy before it and the
     * history entry after it belong to the caller. Cancellation is cooperative: the coroutine is
     * checked between pieces, so stopping a ten-part job never costs more than the part in flight.
     */
    suspend fun transcribe(
        context: Context,
        prefs: FlorisPreferenceModel,
        audio: File,
        onProgress: (ImportProgress) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val account = accountFor(prefs)
        val preset = presetFor(account)
        val onDevice = preset.transcriptionApi == TranscriptionApi.LOCAL_ONDEVICE
        val model = account.transcriptionModel.ifBlank { preset.defaultTranscriptionModel ?: "" }

        // Only announced when it is really about to happen: a short voice note has nothing to unpack,
        // and naming a step that takes no time is how "Preparing…" got its reputation.
        if (preparesFor(audio, account.providerId, onDevice)) {
            onProgress(ImportProgress(ImportStage.PREPARE))
        }
        val pieces = split(appContext, audio, account.providerId, onDevice)
        val parts = ArrayList<String>(pieces.size)
        try {
            for ((index, piece) in pieces.withIndex()) {
                val part = index + 1
                val partCount = pieces.size
                // Reporting the upload as it goes, and the moment its last byte is out the wait for the
                // answer begins — which is the other half of the honesty here: 100 % uploaded is not
                // 100 % transcribed, and saying so is the difference between waiting and wondering.
                val onUpload: ((Long, Long) -> Unit)? = if (onDevice) null else { sent, total ->
                    val stage = if (total > 0L && sent >= total) ImportStage.TRANSCRIBE else ImportStage.UPLOAD
                    onProgress(
                        ImportProgress(
                            stage = stage,
                            fraction = if (stage == ImportStage.UPLOAD && total > 0L) {
                                (sent.toFloat() / total).coerceIn(0f, 1f)
                            } else {
                                null
                            },
                            part = part,
                            partCount = partCount,
                        )
                    )
                }
                onProgress(
                    ImportProgress(
                        stage = if (onDevice) ImportStage.TRANSCRIBE else ImportStage.UPLOAD,
                        fraction = if (onDevice) null else 0f,
                        part = part,
                        partCount = partCount,
                    )
                )
                val text = transcribeOne(
                    appContext, prefs, account, preset, model, piece, onDevice, onUpload,
                )
                if (text.isNotBlank()) parts.add(text)
            }
        } finally {
            // Only the pieces we made ourselves; the original belongs to the caller, which still needs
            // it for playback and for the history entry.
            for (piece in pieces) if (piece != audio) piece.delete()
        }
        // A cut runs through the middle of a sentence, so the next chunk can perfectly well open with the
        // mark that closes the previous one (issue #356).
        val tightening = appContext.transcriptTighteningSymbols()
        val joined = parts
            .fold(StringBuilder()) { acc, part -> TranscriptJoin.appendPiece(acc, part, tightening) }
            .toString()
            .trim()
        if (joined.isEmpty()) throw NoSpeechException()
        joined
    }

    /**
     * Whether [audio] gets decoded and cut before it goes to [providerId] — the preparing step.
     *
     * Lives here rather than in the screen because the conditions are this object's: the upload cap it
     * asks the registry for, and the rule in [split] that decides whether anything has to be unpacked
     * or cut at all.
     */
    fun preparesFor(audio: File, providerId: String, onDevice: Boolean): Boolean = ImportStages.prepares(
        isVideo = ImportStages.looksLikeVideo(audio.name),
        sizeBytes = audio.length(),
        uploadLimitBytes = ProviderRegistry.maxUploadBytes(providerId),
        onDevice = onDevice,
    )

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
        val isVideo = ImportStages.looksLikeVideo(audio.name)
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

        val dir = File(appContext.cacheDir, PARTS_DIR).apply { deleteRecursively(); mkdirs() }
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
        onUpload: ((sent: Long, total: Long) -> Unit)?,
    ): String {
        // Time compression (issue #272), on the user's own setting and nothing else. It belongs here for
        // the same reason it belongs in the keyboard — every provider bills by duration — except that an
        // import is where the minutes actually add up: a dictation is a sentence, a shared recording is
        // a meeting. It stays off unless it was switched on, because of the three ways this app shortens
        // an upload it is the only one that changes what the model hears.
        //
        // Per piece rather than over the whole file: a piece is bounded by the upload limit, and this
        // decodes what it is given into memory. On-device is skipped exactly as in the dictation path —
        // nothing is billed there, and the trade would be accuracy for nothing.
        val speedPercent = prefs.dictate.audioSpeedUpPercent.get()
        val spedUp = if (!onDevice && speedPercent > AudioSpeedUp.MIN_PERCENT) {
            AudioSpeedUp.process(
                audio,
                File(appContext.cacheDir, "spd_${audio.nameWithoutExtension}.wav"),
                speedPercent / 100f,
            )
        } else {
            null
        }
        val source = spedUp ?: audio
        // The container the user brought is the whole point of this screen, so it is also where a
        // provider is most likely to be handed something it does not take (issue #322). A slice this
        // function made is already WAV and passes straight through; a file small enough to go up whole
        // is whatever the sharing app wrote. On-device decodes anything and needs no conversion.
        val converted = if (onDevice) null else {
            AudioConvert.toAccepted(appContext.cacheDir, source, preset.acceptedAudioContainers)
        }
        try {
            return transcribeFile(
                appContext, prefs, account, preset, model, converted ?: source, onDevice, onUpload,
            )
        } finally {
            converted?.let { runCatching { it.delete() } }
            // Never the original: it is the caller's, and the history entry is made from it.
            spedUp?.let { runCatching { it.delete() } }
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
        onUpload: ((sent: Long, total: Long) -> Unit)?,
    ): String {
        val request = TranscriptionRequest(
            audioFile = audio,
            model = model,
            onUpload = onUpload,
            language = prefs.dictate.activeInputLanguage.get().takeIf { it != DictateLanguages.DETECT },
            // The same list-shaped hint the keyboard sends (#99), so an import is recognised in the
            // languages the user actually speaks.
            expectedLanguages = DictateLanguages.expectedLanguages(
                activeCode = prefs.dictate.activeInputLanguage.get(),
                selectionRaw = prefs.dictate.inputLanguages.get(),
            ),
        )
        return if (onDevice) {
            LocalTranscriptionProvider(
                LocalTranscriptionProvider.modelDir(appContext, model),
                // Bounded like every other on-device decode (#354), on the same upwards-only terms as the
                // cloud branch below: a piece of a shared file is a bigger job than a dictation, so the
                // default two minutes would cut short work that is going perfectly well.
                timeoutMillis = maxOf(
                    IMPORT_CALL_TIMEOUT_SECONDS,
                    prefs.dictate.requestTimeout.get().toLong(),
                ) * 1000L,
                tighteningSymbols = appContext.transcriptTighteningSymbols(),
            ).transcribe(request).text.trim()
        } else {
            OpenAiCompatibleClient.from(
                preset,
                account.apiKey,
                baseUrlOverride = if (account.isCustom || preset.allowsCustomBaseUrl) {
                    account.customBaseUrl.takeIf { it.isNotBlank() }
                } else null,
                proxy = prefs.dictate.dictateProxyConfig(),
                trustUserCerts = prefs.dictate.trustUserCertificates.get(),
                // The user's own limit applies here too, but only upwards: someone who raised it for a
                // slow machine of their own means it here as well, while the default two minutes is
                // shorter than a file this size can honestly need.
                timeoutSeconds = maxOf(
                    IMPORT_OPERATION_TIMEOUT_SECONDS,
                    prefs.dictate.requestTimeout.get().toLong(),
                ),
                callTimeoutSeconds = maxOf(
                    IMPORT_CALL_TIMEOUT_SECONDS,
                    prefs.dictate.requestTimeout.get().toLong(),
                ),
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
}
