/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.devemperor.dictate.wear.transcribe

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import dev.patrickgold.florisboard.dictate.provider.DictateRewording
import dev.patrickgold.florisboard.dictate.provider.OpenAiCompatibleClient
import dev.patrickgold.florisboard.dictate.provider.ProviderConfig
import dev.patrickgold.florisboard.dictate.provider.TranscriptionRequest
import dev.patrickgold.florisboard.dictate.sync.DictateSyncedSettings
import dev.patrickgold.florisboard.dictate.sync.DictateWearProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.devemperor.dictate.wear.R
import net.devemperor.dictate.wear.sync.WearSettingsStore
import net.devemperor.dictate.wear.sync.WearSyncClient
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Turns a recorded `.wav` into text, choosing the transport automatically (#106) so the watch works
 * as independently as possible:
 *
 *  - When a paired phone running Dictate is **reachable**, the watch **tethers**: it streams the audio
 *    to the phone, which transcribes with its own connection/credentials and sends the transcript back.
 *    This is preferred because watches are often BT-only and reach the internet through the phone.
 *  - When the phone is **out of range** (or the tether attempt fails), the watch falls back to a
 *    **standalone** call straight to the provider, using the synced key — as long as one was synced and
 *    the watch has its own internet (Wi-Fi/LTE).
 */
object WearTranscription {

    /** Thrown when no transport is currently usable (no phone reachable and no synced key to go solo). */
    class Unavailable(message: String) : Exception(message)

    /**
     * @param onRewording invoked when the transcript is in and rewording starts — the watch's own
     * (standalone) or, from a phone on protocol 1, the phone's — so the UI can show "Rewording…". Not
     * fired when nothing will be reworded. May come from a background thread.
     */
    suspend fun transcribe(context: Context, audio: File, onRewording: () -> Unit = {}): String {
        val settings = WearSettingsStore.current()
        val phoneNode = WearSyncClient.findPhoneNodeId(context)
        Log.i(TAG, "transcribe: phoneNode=${phoneNode != null}, canStandalone=${settings.canStandalone}, " +
            "provider=${settings.transcriptionProviderId}, model=${settings.model}, bytes=${audio.length()}, " +
            "protocol=${settings.tetherProtocol}")

        if (phoneNode != null) {
            // Phone in range: tether through it. Fall back to a direct call ONLY when the tether transport
            // itself fails (phone reachable over BT but e.g. has no internet) — not when the phone answers
            // with a definitive status (bad key / quota / no speech), which we surface as-is so we don't
            // silently re-run and double-charge the request.
            val response = try {
                tetherWithRetry(context, phoneNode, audio, settings, onRewording)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PhoneSilent) {
                // The phone took the audio and then went quiet. It may still be at work, so a direct call now
                // could pay for the same dictation twice; the audio is kept for a retry instead (#363).
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "tether transport failed (${e.message}); standalone=${settings.canStandalone}", e)
                return if (settings.canStandalone) standalone(settings, audio, onRewording) else throw e
            }
            return response.toTranscriptOrThrow(context)
        }

        // No phone: go solo if we can, otherwise tell the user why nothing happened.
        if (settings.canStandalone) return standalone(settings, audio, onRewording)
        throw Unavailable(context.getString(R.string.wear_err_no_transport))
    }

    private const val TAG = "WearTranscription"

    /** Direct provider call from the watch using the synced config + key, then standalone rewording. */
    private suspend fun standalone(settings: DictateSyncedSettings, audio: File, onRewording: () -> Unit): String {
        val client = OpenAiCompatibleClient(
            ProviderConfig(
                baseUrl = settings.baseUrl,
                apiKey = settings.apiKey,
                timeoutSeconds = settings.timeoutSeconds(),
                transcriptionApi = settings.transcriptionApi,
            )
        )
        val transcript = client.transcribe(
            TranscriptionRequest(
                audioFile = audio,
                model = settings.model,
                language = settings.language,
                prompt = settings.stylePrompt,
                vocabulary = settings.customWords,
                reasoningEffort = settings.transcriptionReasoningEffort,
            )
        ).text.trim()
        return maybeReword(settings, transcript, onRewording)
    }

    /**
     * Standalone auto-rewording (#130): when the phone is out of range the watch runs the same rewording
     * chain itself, using the synced rewording config + auto-apply prompts. Best-effort — any failure
     * keeps the raw transcript. The tethered path needs none of this (the phone already reworded).
     */
    private suspend fun maybeReword(
        settings: DictateSyncedSettings,
        transcript: String,
        onRewording: () -> Unit,
    ): String {
        if (transcript.isBlank()) return transcript
        if (!settings.autoRewordingEnabled || !settings.rewordingEnabled) return transcript
        if (!settings.canRewordStandalone) return transcript
        onRewording()
        val client = OpenAiCompatibleClient(
            ProviderConfig(
                baseUrl = settings.rewordingBaseUrl,
                apiKey = settings.rewordingApiKey,
                timeoutSeconds = settings.timeoutSeconds(),
                transcriptionApi = settings.rewordingApi,
            )
        )
        val prompts = settings.autoApplyPrompts.map { DictateRewording.Prompt(it.instruction, it.reasoningEffort) }
        return DictateRewording.apply(
            client = client,
            chatModel = settings.chatModel,
            transcript = transcript,
            autoFormatting = settings.autoFormattingEnabled,
            languageName = settings.languageName,
            systemPrompt = settings.systemPrompt,
            autoApplyPrompts = prompts,
            reasoningEffort = settings.rewordingReasoningEffort,
        )
    }

    /**
     * [tether] with a couple of retries. Bluetooth between watch and phone drops out for a moment far more
     * often than a phone's Wi-Fi does, and a single hiccup while opening the channel or streaming the audio
     * used to fail the whole dictation (#218). Definitive answers from the phone (bad key, quota, no speech)
     * are returned immediately and never retried, so a request is never paid for twice.
     *
     * Nor is a phone that went quiet after taking the audio ([PhoneSilent]): that is not a hiccup, and
     * sending the audio again had the phone transcribe it again — three times two minutes of silence and
     * up to three bills for one dictation (#363).
     */
    private suspend fun tetherWithRetry(
        context: Context,
        nodeId: String,
        audio: File,
        settings: DictateSyncedSettings,
        onRewording: () -> Unit,
    ): DictateWearProtocol.TranscribeResponse {
        var last: Exception? = null
        repeat(TETHER_ATTEMPTS) { attempt ->
            try {
                return tether(context, nodeId, audio, settings, onRewording)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PhoneSilent) {
                throw e
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "tether attempt ${attempt + 1}/$TETHER_ATTEMPTS failed: ${e.message}")
                if (attempt < TETHER_ATTEMPTS - 1) delay(TETHER_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw last ?: IllegalStateException("tether failed")
    }

    /** Stream the audio to the phone and await its structured response over the Data Layer. */
    private suspend fun tether(
        context: Context,
        nodeId: String,
        audio: File,
        settings: DictateSyncedSettings,
        onRewording: () -> Unit,
    ): DictateWearProtocol.TranscribeResponse {
        // Name the request when the phone understands names (#363): then its answer cannot be mistaken for
        // the late answer to an earlier dictation, and the phone can be told to stop.
        val requestId = if (settings.tetherProtocol >= DictateWearProtocol.TETHER_PROTOCOL) {
            UUID.randomUUID().toString()
        } else {
            ""
        }
        val named = requestId.isNotEmpty()
        val responsePath = if (named) DictateWearProtocol.responsePath(requestId) else DictateWearProtocol.PATH_TRANSCRIBE_RESPONSE
        val progressPath = if (named) DictateWearProtocol.progressPath(requestId) else null
        val messageClient = Wearable.getMessageClient(context)
        val result = CompletableDeferred<DictateWearProtocol.TranscribeResponse>()
        val lastHeardMs = AtomicLong(SystemClock.elapsedRealtime())
        val rewording = AtomicBoolean(false)
        val listener = MessageClient.OnMessageReceivedListener { event ->
            when (event.path) {
                responsePath -> result.complete(DictateWearProtocol.parseTranscribeResponse(event.data))
                progressPath -> {
                    lastHeardMs.set(SystemClock.elapsedRealtime())
                    if (event.data.firstOrNull() == DictateWearProtocol.STAGE_REWORDING && rewording.compareAndSet(false, true)) {
                        onRewording()
                    }
                }
            }
        }
        messageClient.addListener(listener).await()
        try {
            val channelClient = Wearable.getChannelClient(context)
            val path = if (named) DictateWearProtocol.requestPath(requestId) else DictateWearProtocol.PATH_TRANSCRIBE_REQUEST
            val channel = channelClient.openChannel(nodeId, path).await()
            try {
                val output = channelClient.getOutputStream(channel).await()
                output.use { os -> audio.inputStream().use { it.copyTo(os) } }
                Log.i(TAG, "tether: audio sent to $nodeId as ${requestId.ifEmpty { "an unnamed request" }}, awaiting response…")
                lastHeardMs.set(SystemClock.elapsedRealtime())
                val response = try {
                    if (named) awaitWhileHeard(result, lastHeardMs) else awaitFixed(result)
                } catch (e: CancellationException) {
                    if (named) callOff(context, nodeId, requestId)
                    throw e
                }
                if (response == null) {
                    if (named) callOff(context, nodeId, requestId)
                    throw PhoneSilent(context.getString(R.string.wear_err_phone_silent))
                }
                Log.i(TAG, "tether: response status=${response.status}, len=${response.text.length}")
                return response
            } finally {
                channelClient.close(channel)
            }
        } finally {
            messageClient.removeListener(listener)
        }
    }

    /**
     * Waits for the answer as long as the phone keeps reporting that it is at work (#363). A phone on
     * protocol 1 sends a sign of life every few seconds, so silence means it is gone — out of range, its
     * process killed — and the user learns that within [PHONE_SILENCE_MS] rather than after minutes, while
     * a provider that is merely slow is never cut off.
     */
    private suspend fun awaitWhileHeard(
        result: CompletableDeferred<DictateWearProtocol.TranscribeResponse>,
        lastHeardMs: AtomicLong,
    ): DictateWearProtocol.TranscribeResponse? {
        while (true) {
            withTimeoutOrNull(SILENCE_CHECK_MS) { result.await() }?.let { return it }
            val silentMs = SystemClock.elapsedRealtime() - lastHeardMs.get()
            if (silentMs > PHONE_SILENCE_MS) {
                Log.w(TAG, "tether: nothing from the phone for ${silentMs / 1000} s, giving up")
                return null
            }
        }
    }

    /** A protocol-0 phone says nothing until it answers, so all the watch can do is wait a fixed time. */
    private suspend fun awaitFixed(
        result: CompletableDeferred<DictateWearProtocol.TranscribeResponse>,
    ): DictateWearProtocol.TranscribeResponse? =
        withTimeoutOrNull(LEGACY_TRANSCRIBE_TIMEOUT_MS) { result.await() }

    /**
     * Tells the phone to drop a request nobody will read, so its provider call is not paid to the end.
     * Runs while the dictation is being cancelled, hence [NonCancellable]; best effort and short.
     */
    private suspend fun callOff(context: Context, nodeId: String, requestId: String) {
        withContext(NonCancellable) {
            withTimeoutOrNull(CALL_OFF_TIMEOUT_MS) {
                runCatching {
                    Wearable.getMessageClient(context)
                        .sendMessage(nodeId, DictateWearProtocol.cancelPath(requestId), ByteArray(0))
                        .await()
                }
            }
        }
        Log.i(TAG, "tether: called off $requestId")
    }

    /** The phone's Request timeout, so the watch's own calls give up when the phone's would (#363). */
    private fun DictateSyncedSettings.timeoutSeconds(): Long =
        requestTimeoutSeconds.takeIf { it > 0 }?.toLong() ?: ProviderConfig.DEFAULT_TIMEOUT_SECONDS

    /** Maps the phone's status to the transcript, or throws a [TetherResultError] carrying a short reason. */
    private fun DictateWearProtocol.TranscribeResponse.toTranscriptOrThrow(context: Context): String = when (status) {
        DictateWearProtocol.RESP_OK -> text.trim()
        DictateWearProtocol.RESP_NO_SPEECH -> throw TetherResultError(context.getString(R.string.wear_err_no_speech))
        DictateWearProtocol.RESP_BAD_KEY -> throw TetherResultError(context.getString(R.string.wear_err_bad_key))
        DictateWearProtocol.RESP_OFFLINE -> throw TetherResultError(context.getString(R.string.wear_err_offline))
        DictateWearProtocol.RESP_QUOTA -> throw TetherResultError(context.getString(R.string.wear_err_quota))
        // Prefer the phone's actual provider error ("model not found", "insufficient quota", …) so the
        // user can fix their setup instead of staring at a generic failure (#218).
        else -> throw TetherResultError(text.trim().ifBlank { context.getString(R.string.wear_err_transcribe_failed) })
    }

    /** A definitive phone-side failure with a ready-to-show short reason (no standalone fallback). */
    class TetherResultError(message: String) : Exception(message)

    /** The phone took the audio and then stopped answering. Not retried, not handed to standalone (#363). */
    class PhoneSilent(message: String) : Exception(message)

    /** How long a protocol-1 phone may stay silent before the watch stops waiting for it. */
    private const val PHONE_SILENCE_MS = 30_000L
    private const val SILENCE_CHECK_MS = 1_000L
    private const val CALL_OFF_TIMEOUT_MS = 3_000L

    /** The whole wait for a protocol-0 phone, which cannot say whether it is still at work. */
    private const val LEGACY_TRANSCRIBE_TIMEOUT_MS = 120_000L

    /** Retries for the watch↔phone transport (not for definitive phone answers). */
    private const val TETHER_ATTEMPTS = 3
    private const val TETHER_RETRY_DELAY_MS = 500L
}
