/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.sync

import dev.patrickgold.florisboard.dictate.provider.TranscriptionApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The contract shared by the phone app and the Wear OS app for the Wearable Data Layer (#106).
 *
 * Lives in `:lib:dictate-core` so both sides compile against the exact same paths and the exact same
 * serialized settings schema — the single biggest source of "works on phone, silently no-ops on watch"
 * bugs in Data Layer code is the two ends disagreeing on a path string or a JSON field.
 */
object DictateWearProtocol {
    /** DataClient item path: the phone publishes the current settings snapshot here. */
    const val PATH_SETTINGS = "/dictate/settings"

    /** MessageClient path: the watch asks the phone to (re)publish [PATH_SETTINGS] right now. */
    const val PATH_SYNC_REQUEST = "/dictate/sync/request"

    /** MessageClient/ChannelClient path: the watch sends recorded audio for the phone to transcribe. */
    const val PATH_TRANSCRIBE_REQUEST = "/dictate/transcribe/request"

    /** MessageClient path: the phone returns the transcript (or an error) for a request. */
    const val PATH_TRANSCRIBE_RESPONSE = "/dictate/transcribe/response"

    /**
     * The tether exchange this build speaks, published as [DictateSyncedSettings.tetherProtocol] (#363).
     *
     * **0** is the original: one fixed request path and one fixed response path. An answer cannot be told
     * from the answer to an earlier dictation, the watch cannot call a request off, and it can only wait a
     * fixed time, not knowing whether the phone is still at work.
     *
     * **1** names every request: the watch opens its channel on [requestPath] with an id of its own; the
     * phone answers on [responsePath] for that id, reports on [progressPath] while it works, and drops the
     * work when [cancelPath] names it. A phone and a watch update separately (each has its own Play track),
     * so either side may still be on 0: the watch only uses ids once the phone's snapshot says 1, and the
     * phone answers a request on the path it came in on.
     */
    const val TETHER_PROTOCOL = 1

    /** Stage byte of a [progressPath] message: the phone is transcribing. */
    const val STAGE_TRANSCRIBING: Byte = 'T'.code.toByte()

    /** Stage byte of a [progressPath] message: the transcript is in and the phone is rewording it. */
    const val STAGE_REWORDING: Byte = 'R'.code.toByte()

    private const val PATH_TRANSCRIBE_PROGRESS = "/dictate/transcribe/progress"
    private const val PATH_TRANSCRIBE_CANCEL = "/dictate/transcribe/cancel"

    fun requestPath(requestId: String): String = "$PATH_TRANSCRIBE_REQUEST/$requestId"
    fun responsePath(requestId: String): String = "$PATH_TRANSCRIBE_RESPONSE/$requestId"
    fun progressPath(requestId: String): String = "$PATH_TRANSCRIBE_PROGRESS/$requestId"
    fun cancelPath(requestId: String): String = "$PATH_TRANSCRIBE_CANCEL/$requestId"

    /**
     * The request id a channel was opened for: `""` for the unnamed protocol-0 path, null when the channel
     * is not a transcription request at all.
     */
    fun requestIdOf(channelPath: String): String? = when (channelPath) {
        PATH_TRANSCRIBE_REQUEST -> ""
        else -> idAfter(PATH_TRANSCRIBE_REQUEST, channelPath)
    }

    /** The request id a [cancelPath] message names, or null for any other path. */
    fun cancelledIdOf(messagePath: String): String? = idAfter(PATH_TRANSCRIBE_CANCEL, messagePath)

    private fun idAfter(base: String, path: String): String? =
        path.removePrefix("$base/").takeIf { it != path && it.isNotEmpty() && '/' !in it }

    /**
     * MessageClient path: the watch toggles standalone transcription on/off. Payload is a single byte
     * (1 = on, 0 = off). The phone stores it and re-publishes [PATH_SETTINGS] (the key is only included
     * in the snapshot while standalone is on).
     */
    const val PATH_SET_STANDALONE = "/dictate/set_standalone"

    /**
     * MessageClient path: the watch toggles auto-rewording on/off (#130). Payload is a single byte
     * (1 = on, 0 = off). The phone stores it and re-publishes [PATH_SETTINGS].
     */
    const val PATH_SET_AUTO_REWORDING = "/dictate/set_auto_rewording"

    /** CapabilityClient capability the phone app advertises so the watch can detect a usable peer. */
    const val CAPABILITY_PHONE_APP = "dictate_phone_transcriber"

    /** DataMap key under [PATH_SETTINGS] holding the JSON of [DictateSyncedSettings]. */
    const val KEY_SETTINGS_JSON = "settings_json"

    /**
     * [PATH_TRANSCRIBE_RESPONSE] envelope: the phone used to collapse every failure into an empty
     * transcript, so the watch could only guess ("check phone key"). Now byte 0 == [RESP_MARKER] flags
     * a structured reply — byte 1 is a [RESP_OK]/[RESP_NO_SPEECH]/… status and bytes 2.. are the UTF-8
     * transcript (only meaningful for [RESP_OK]). A message without the marker is a legacy plain
     * transcript (phone on an older build), treated as [RESP_OK].
     */
    private const val RESP_MARKER: Byte = 0x1F
    const val RESP_OK = '0'
    const val RESP_NO_SPEECH = '1'
    const val RESP_BAD_KEY = '2'
    const val RESP_OFFLINE = '3'
    const val RESP_QUOTA = '4'
    const val RESP_ERROR = '5'

    fun encodeTranscribeResponse(status: Char, text: String = ""): ByteArray {
        val payload = text.toByteArray(Charsets.UTF_8)
        val out = ByteArray(payload.size + 2)
        out[0] = RESP_MARKER
        out[1] = status.code.toByte()
        payload.copyInto(out, 2)
        return out
    }

    /** [status] plus the transcript ([text] is only set for [RESP_OK]). */
    data class TranscribeResponse(val status: Char, val text: String)

    fun parseTranscribeResponse(data: ByteArray): TranscribeResponse =
        if (data.size >= 2 && data[0] == RESP_MARKER) {
            TranscribeResponse(data[1].toInt().toChar(), String(data, 2, data.size - 2, Charsets.UTF_8))
        } else {
            // Legacy phone: the whole payload is the plain transcript.
            TranscribeResponse(RESP_OK, String(data, Charsets.UTF_8))
        }

    /** Lenient JSON used on both ends; unknown keys are ignored so the two apps can version independently. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

/**
 * The phone's Dictate settings the watch needs to mirror the phone experience (#106).
 *
 * The watch picks its transport at dictation time (see `WearTranscription`): it tethers through the
 * phone when one is reachable, and otherwise falls back to calling the provider itself. So it needs
 * both the non-secret config (provider/model/language/prompt) and, for the offline fallback,
 * [baseUrl]/[apiKey]. The key is only populated when the phone-side master toggle allows it
 * (`wearStandaloneEnabled`, default on); it is stored private to the watch app.
 *
 * [accentColorArgb] carries the phone's accent color so the watch UI matches the phone's look.
 */
@Serializable
data class DictateSyncedSettings(
    val transcriptionProviderId: String = "openai",
    /** Human-readable provider name for the watch UI (e.g. "Groq", "xAI (Grok)"). */
    val providerLabel: String = "",
    val transcriptionApi: TranscriptionApi = TranscriptionApi.OPENAI_MULTIPART,
    val baseUrl: String = "",
    val model: String = "",
    /** Populated unless the phone master toggle forbids syncing the key; empty -> watch can only tether. */
    val apiKey: String = "",
    /** Mirrors the phone's "sync key to watch" toggle so the watch UI can show/flip the same state. */
    val keySyncEnabled: Boolean = true,
    /** ISO language code, or null to let the provider auto-detect. */
    val language: String? = null,
    /** Readable language name (e.g. "German") for the rewording auto-formatting hint; computed on phone. */
    val languageName: String? = null,
    /** Style/punctuation prompt biasing recognition (already includes the custom-words glossary), or null. */
    val stylePrompt: String? = null,
    /** Phone accent color as a packed ARGB int, so the watch UI themes itself like the phone. */
    val accentColorArgb: Int = DEFAULT_ACCENT_ARGB,
    /**
     * Auto-rewording (issue #130): when true, watch dictations are auto-reworded. Settable on the watch
     * too; mirrored back to the phone. The fields below carry everything the watch needs to run the
     * rewording chain itself in the standalone path ([DictateRewording]); for tethered dictations the
     * phone rewords before sending the transcript back, so the watch ignores them.
     */
    val autoRewordingEnabled: Boolean = true,
    /** Phone master "rewording enabled" toggle — when false nothing is reworded regardless of the above. */
    val rewordingEnabled: Boolean = false,
    /** Phone "auto-formatting" toggle (spoken cues → Markdown), the first rewording step. */
    val autoFormattingEnabled: Boolean = false,
    /** Rewording chat model + endpoint/key/api (may differ from transcription); empty → no standalone reword. */
    val chatModel: String = "",
    val rewordingBaseUrl: String = "",
    val rewordingApiKey: String = "",
    val rewordingApi: TranscriptionApi = TranscriptionApi.OPENAI_MULTIPART,
    /**
     * `reasoning_effort` for the standalone rewording requests, resolved on the phone from the user's
     * setting as the keyboard resolves it (#464; before, only the preset's value for its default model);
     * null omits it. Optional both ways: an older watch ignores the key, and an older phone leaves it out.
     */
    val rewordingReasoningEffort: String? = null,
    /** Rewording system prompt (be-precise / custom), appended to each auto-apply prompt. */
    val systemPrompt: String? = null,
    /** The user's auto-apply prompts, in order, for the standalone rewording chain. */
    val autoApplyPrompts: List<SyncedPrompt> = emptyList(),
    /** Mirror the phone's dictation haptic feedback on the watch (issue #166). */
    val hapticFeedback: Boolean = false,
    /**
     * The phone's Request timeout in seconds, so a standalone call from the watch gives up when the
     * phone's own would (#363). 0 = a phone too old to send it; the watch then keeps its default.
     */
    val requestTimeoutSeconds: Int = 0,
    /** [DictateWearProtocol.TETHER_PROTOCOL] of the phone that published this; 0 for a phone too old to say. */
    val tetherProtocol: Int = 0,
) {
    /** True when the watch can transcribe on its own (a key is present), i.e. works without the phone. */
    val canStandalone: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank()

    /** True when the watch has everything it needs to run rewording itself (standalone). */
    val canRewordStandalone: Boolean
        get() = rewordingApiKey.isNotBlank() && rewordingBaseUrl.isNotBlank() && chatModel.isNotBlank()

    fun encode(): String = DictateWearProtocol.json.encodeToString(this)

    companion object {
        /** Dictate light blue — the app's default accent, used until a real sync arrives. */
        const val DEFAULT_ACCENT_ARGB: Int = 0xFF30B7E6.toInt()

        fun decode(raw: String?): DictateSyncedSettings? =
            raw?.let { runCatching { DictateWearProtocol.json.decodeFromString<DictateSyncedSettings>(it) }.getOrNull() }
    }
}

/** One auto-apply rewording prompt, synced to the watch for the standalone rewording chain (#130). */
@Serializable
data class SyncedPrompt(
    val instruction: String,
    /**
     * No longer read: every automatic prompt works on the dictation ([DictateRewording]). Still sent, and
     * true for every automatic prompt that is not a snippet, for watches that read it.
     */
    val requiresSelection: Boolean = false,
    /**
     * The prompt's own `reasoning_effort` ([DictateRewording.Prompt.reasoningEffort]): null follows
     * [DictateSyncedSettings.rewordingReasoningEffort], which is also what an older phone sends, and blank
     * leaves the field out.
     */
    val reasoningEffort: String? = null,
)
