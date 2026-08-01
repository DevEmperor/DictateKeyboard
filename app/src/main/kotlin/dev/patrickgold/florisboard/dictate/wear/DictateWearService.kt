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

import android.os.PowerManager
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
import kotlinx.coroutines.delay
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

    override fun onDestroy() {
        super.onDestroy()
        // NOTE: deliberately does not cancel [tetherScope]. A WearableListenerService is torn down by the
        // system as soon as it looks idle — and onChannelOpened() returns immediately while the upload +
        // provider call keep running. Cancelling here killed transcriptions mid-flight, so the watch never
        // got an answer and sat in "Transcribing…" until its timeout (#218).
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            DictateWearProtocol.PATH_SYNC_REQUEST -> tetherScope.launch { publishSettings() }
            DictateWearProtocol.PATH_SET_STANDALONE -> {
                val enabled = event.data.firstOrNull() == 1.toByte()
                tetherScope.launch {
                    prefs.dictate.wearStandaloneEnabled.set(enabled)
                    publishSettings()
                }
            }
            DictateWearProtocol.PATH_SET_AUTO_REWORDING -> {
                val enabled = event.data.firstOrNull() == 1.toByte()
                tetherScope.launch {
                    prefs.dictate.wearAutoRewordingEnabled.set(enabled)
                    publishSettings()
                }
            }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != DictateWearProtocol.PATH_TRANSCRIBE_REQUEST) return
        tetherScope.launch { handleTranscribeChannel(channel) }
    }

    private suspend fun handleTranscribeChannel(channel: ChannelClient.Channel) {
        // Hold the CPU for the upload + provider call. Without this the phone can doze off mid-request
        // (the screen is usually off while dictating from the watch) and the watch waits for nothing.
        val wakeLock = runCatching {
            (applicationContext.getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dictate:wear-tether")
                .apply { acquire(WAKELOCK_TIMEOUT_MS) }
        }.getOrNull()
        try {
            transcribeForWatch(channel)
        } finally {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        }
    }

    private suspend fun transcribeForWatch(channel: ChannelClient.Channel) {
        val channelClient = Wearable.getChannelClient(applicationContext)
        val audio = File(cacheDir, "wear_tether_${channel.nodeId}.wav")
        var transcript = ""
        // Human-readable detail for failures, forwarded to the watch so the user sees the actual provider
        // error ("model not found", "insufficient quota", …) instead of a blanket "Transcription failed".
        var errorDetail = ""
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
            errorDetail = e.message.orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "tether: phone transcription failed", e)
            status = DictateWearProtocol.RESP_ERROR
            transcript = ""
            errorDetail = e.message.orEmpty()
        } finally {
            audio.delete()
            runCatching { channelClient.close(channel) }
            // Always answer, even on failure, so the watch never hangs waiting for a reply. Success stays
            // a raw transcript (byte-identical to older builds → a not-yet-updated watch still works); only
            // failures use the status envelope, which older watches simply render as a short error string.
            val payload = if (status == DictateWearProtocol.RESP_OK) {
                transcript.toByteArray(Charsets.UTF_8)
            } else {
                DictateWearProtocol.encodeTranscribeResponse(status, errorDetail)
            }
            sendResponseWithRetry(channel.nodeId, payload)
        }
    }

    /**
     * Delivers the transcript back to the watch, confirming it actually went out. The send used to be
     * fire-and-forget, so a momentary Bluetooth drop silently threw away a transcript the provider had
     * already produced (and billed) and left the watch waiting for its timeout (#218). Retries a few times
     * with a short backoff, which is enough to ride out a brief connection hiccup.
     */
    private suspend fun sendResponseWithRetry(nodeId: String, payload: ByteArray) {
        val messageClient = Wearable.getMessageClient(applicationContext)
        repeat(RESPONSE_SEND_ATTEMPTS) { attempt ->
            val sent = runCatching {
                messageClient.sendMessage(nodeId, DictateWearProtocol.PATH_TRANSCRIBE_RESPONSE, payload).await()
            }
            if (sent.isSuccess) {
                if (attempt > 0) Log.i(TAG, "tether: response delivered on attempt ${attempt + 1}")
                return
            }
            Log.w(TAG, "tether: response send failed (attempt ${attempt + 1})", sent.exceptionOrNull())
            if (attempt < RESPONSE_SEND_ATTEMPTS - 1) delay(RESPONSE_RETRY_DELAY_MS * (attempt + 1))
        }
        Log.e(TAG, "tether: giving up delivering the response to $nodeId")
    }

    /** Serialize the active transcription settings and put them on the Data Layer for the watch. */
    private suspend fun publishSettings() {
        DictateWearPublisher.publish(applicationContext)
    }

    private companion object {
        const val TAG = "DictateWear"

        /** How long the CPU is held for one tethered dictation before the lock times out on its own. */
        const val WAKELOCK_TIMEOUT_MS = 3 * 60 * 1000L
        const val RESPONSE_SEND_ATTEMPTS = 4
        const val RESPONSE_RETRY_DELAY_MS = 400L

        /**
         * Process-lifetime scope for tethered work. Deliberately NOT tied to the service instance: the
         * system stops a [WearableListenerService] once its callbacks return, which would otherwise cancel
         * an in-flight transcription and leave the watch hanging (#218).
         */
        val tetherScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}
