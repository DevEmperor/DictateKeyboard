/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.wear

import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.audio.SpeechGate
import dev.patrickgold.florisboard.dictate.provider.DictateApiException
import dev.patrickgold.florisboard.dictate.sync.DictateWearProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * Phone-side endpoint of the Wear OS Data Layer (#106).
 *
 * Handles requests coming from the watch:
 *  - [DictateWearProtocol.PATH_SYNC_REQUEST]: publish a fresh settings snapshot the watch can cache.
 *  - [DictateWearProtocol.PATH_SET_STANDALONE]: store the standalone opt-in and re-publish settings
 *    (the API key is only included while standalone is on).
 *  - [DictateWearProtocol.PATH_TRANSCRIBE_REQUEST] (ChannelClient): receive recorded audio, transcribe
 *    it with the phone's active provider and send the transcript back.
 *
 * The phone advertises the [DictateWearProtocol.CAPABILITY_PHONE_APP] capability (res/values/wear.xml)
 * so the watch's CapabilityClient can discover it.
 */
class DictateWearService : WearableListenerService() {

    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            DictateWearProtocol.PATH_SYNC_REQUEST -> scope.launch { publishSettings() }
            DictateWearProtocol.PATH_SET_STANDALONE -> {
                val enabled = event.data.firstOrNull() == 1.toByte()
                scope.launch {
                    prefs.dictate.wearStandaloneEnabled.set(enabled)
                    publishSettings()
                }
            }
            DictateWearProtocol.PATH_SET_AUTO_REWORDING -> {
                val enabled = event.data.firstOrNull() == 1.toByte()
                scope.launch {
                    prefs.dictate.wearAutoRewordingEnabled.set(enabled)
                    publishSettings()
                }
            }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != DictateWearProtocol.PATH_TRANSCRIBE_REQUEST) return
        scope.launch { handleTranscribeChannel(channel) }
    }

    private suspend fun handleTranscribeChannel(channel: ChannelClient.Channel) {
        val channelClient = Wearable.getChannelClient(applicationContext)
        val audio = File(cacheDir, "wear_tether_${channel.nodeId}.wav")
        var transcript = ""
        // Report the real reason to the watch instead of collapsing every failure into an empty result
        // (which the watch could only blame on the phone key). Defaults to a generic error until we know.
        var status = DictateWearProtocol.RESP_ERROR
        try {
            // Drain the watch's audio into a temp file.
            channelClient.getInputStream(channel).await().use { input ->
                audio.outputStream().use { input.copyTo(it) }
            }
            Log.i(TAG, "tether: received ${audio.length()} bytes from ${channel.nodeId}, transcribing…")
            // Communicate the phone's silence gate to the watch (#93): skip the upload for silent clips
            // and tell the watch "no speech" rather than letting the provider echo an empty result.
            if (prefs.dictate.skipSilentRecordings.get() && !SpeechGate.hasSpeech(applicationContext, audio)) {
                status = DictateWearProtocol.RESP_NO_SPEECH
            } else {
                transcript = PhoneTranscriber.transcribe(applicationContext, prefs, audio)
                status = if (transcript.isBlank()) {
                    DictateWearProtocol.RESP_NO_SPEECH
                } else {
                    DictateWearProtocol.RESP_OK
                }
            }
            Log.i(TAG, "tether: status=$status, transcript length=${transcript.length}")
        } catch (e: DictateApiException) {
            Log.e(TAG, "tether: phone transcription failed (${e.kind})", e)
            status = when (e.kind) {
                DictateApiException.Kind.INVALID_API_KEY -> DictateWearProtocol.RESP_BAD_KEY
                DictateApiException.Kind.QUOTA_EXCEEDED -> DictateWearProtocol.RESP_QUOTA
                DictateApiException.Kind.NETWORK,
                DictateApiException.Kind.TIMEOUT,
                DictateApiException.Kind.SERVER_ERROR -> DictateWearProtocol.RESP_OFFLINE
                else -> DictateWearProtocol.RESP_ERROR
            }
            transcript = ""
        } catch (e: Exception) {
            Log.e(TAG, "tether: phone transcription failed", e)
            status = DictateWearProtocol.RESP_ERROR
            transcript = ""
        } finally {
            audio.delete()
            channelClient.close(channel)
            // Always answer, even on failure, so the watch never hangs waiting for a reply. Success stays
            // a raw transcript (byte-identical to older builds → a not-yet-updated watch still works); only
            // failures use the status envelope, which older watches simply render as a short error string.
            val payload = if (status == DictateWearProtocol.RESP_OK) {
                transcript.toByteArray(Charsets.UTF_8)
            } else {
                DictateWearProtocol.encodeTranscribeResponse(status)
            }
            Wearable.getMessageClient(applicationContext).sendMessage(
                channel.nodeId,
                DictateWearProtocol.PATH_TRANSCRIBE_RESPONSE,
                payload,
            )
        }
    }

    /** Serialize the active transcription settings and put them on the Data Layer for the watch. */
    private suspend fun publishSettings() {
        DictateWearPublisher.publish(applicationContext)
    }

    private companion object {
        const val TAG = "DictateWear"
    }
}
