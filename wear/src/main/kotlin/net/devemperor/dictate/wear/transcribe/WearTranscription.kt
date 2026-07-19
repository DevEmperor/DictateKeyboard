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
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import dev.patrickgold.florisboard.dictate.provider.DictateRewording
import dev.patrickgold.florisboard.dictate.provider.OpenAiCompatibleClient
import dev.patrickgold.florisboard.dictate.provider.ProviderConfig
import dev.patrickgold.florisboard.dictate.provider.TranscriptionRequest
import dev.patrickgold.florisboard.dictate.sync.DictateSyncedSettings
import dev.patrickgold.florisboard.dictate.sync.DictateWearProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import net.devemperor.dictate.wear.R
import net.devemperor.dictate.wear.sync.WearSettingsStore
import net.devemperor.dictate.wear.sync.WearSyncClient
import java.io.File

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
     * @param onRewording invoked (on a background thread) when the watch is about to **standalone-reword**
     * the transcript, so the UI can show a "Rewording…" state. Not fired for tethered dictations (the
     * phone rewords those) or when nothing will be reworded.
     */
    suspend fun transcribe(context: Context, audio: File, onRewording: () -> Unit = {}): String {
        val settings = WearSettingsStore.current()
        val phoneNode = WearSyncClient.findPhoneNodeId(context)
        Log.i(TAG, "transcribe: phoneNode=${phoneNode != null}, canStandalone=${settings.canStandalone}, " +
            "provider=${settings.transcriptionProviderId}, model=${settings.model}, bytes=${audio.length()}")

        if (phoneNode != null) {
            // Phone in range: tether through it. Fall back to a direct call ONLY when the tether transport
            // itself fails (phone reachable over BT but e.g. has no internet) — not when the phone answers
            // with a definitive status (bad key / quota / no speech), which we surface as-is so we don't
            // silently re-run and double-charge the request.
            val response = try {
                tether(context, phoneNode, audio)
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
                transcriptionApi = settings.transcriptionApi,
            )
        )
        val transcript = client.transcribe(
            TranscriptionRequest(
                audioFile = audio,
                model = settings.model,
                language = settings.language,
                prompt = settings.stylePrompt,
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
                transcriptionApi = settings.rewordingApi,
            )
        )
        val prompts = settings.autoApplyPrompts.map {
            DictateRewording.Prompt(it.instruction, it.requiresSelection)
        }
        return DictateRewording.apply(
            client = client,
            chatModel = settings.chatModel,
            transcript = transcript,
            autoFormatting = settings.autoFormattingEnabled,
            languageName = settings.languageName,
            systemPrompt = settings.systemPrompt,
            autoApplyPrompts = prompts,
        )
    }

    /** Stream the audio to the phone and await its structured response over the Data Layer. */
    private suspend fun tether(context: Context, nodeId: String, audio: File): DictateWearProtocol.TranscribeResponse {
        val messageClient = Wearable.getMessageClient(context)
        val result = CompletableDeferred<DictateWearProtocol.TranscribeResponse>()
        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path == DictateWearProtocol.PATH_TRANSCRIBE_RESPONSE) {
                result.complete(DictateWearProtocol.parseTranscribeResponse(event.data))
            }
        }
        messageClient.addListener(listener).await()
        try {
            val channelClient = Wearable.getChannelClient(context)
            val channel = channelClient.openChannel(nodeId, DictateWearProtocol.PATH_TRANSCRIBE_REQUEST).await()
            try {
                val output = channelClient.getOutputStream(channel).await()
                output.use { os -> audio.inputStream().use { it.copyTo(os) } }
                Log.i(TAG, "tether: audio sent to $nodeId, awaiting response…")
                val response = withTimeout(TRANSCRIBE_TIMEOUT_MS) { result.await() }
                Log.i(TAG, "tether: response status=${response.status}, len=${response.text.length}")
                return response
            } finally {
                channelClient.close(channel)
            }
        } finally {
            messageClient.removeListener(listener)
        }
    }

    /** Maps the phone's status to the transcript, or throws a [TetherResultError] carrying a short reason. */
    private fun DictateWearProtocol.TranscribeResponse.toTranscriptOrThrow(context: Context): String = when (status) {
        DictateWearProtocol.RESP_OK -> text.trim()
        DictateWearProtocol.RESP_NO_SPEECH -> throw TetherResultError(context.getString(R.string.wear_err_no_speech))
        DictateWearProtocol.RESP_BAD_KEY -> throw TetherResultError(context.getString(R.string.wear_err_bad_key))
        DictateWearProtocol.RESP_OFFLINE -> throw TetherResultError(context.getString(R.string.wear_err_offline))
        DictateWearProtocol.RESP_QUOTA -> throw TetherResultError(context.getString(R.string.wear_err_quota))
        else -> throw TetherResultError(context.getString(R.string.wear_err_transcribe_failed))
    }

    /** A definitive phone-side failure with a ready-to-show short reason (no standalone fallback). */
    class TetherResultError(message: String) : Exception(message)

    private const val TRANSCRIBE_TIMEOUT_MS = 120_000L
}
