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

import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.jetpref.datastore.model.PreferenceSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * The per-provider credential record of the keyring (see [ProviderAccounts]).
 *
 * One [ProviderAccount] holds everything needed to talk to a single provider: its API key, the chosen
 * transcription/chat models and – for user-defined endpoints – a base URL and display name. Built-in
 * providers are keyed by their [ProviderRegistry] id ("openai", "groq", …); custom endpoints get a
 * generated `custom:<uuid>` id so the user can keep several of them side by side.
 *
 * Empty model strings mean "use the provider preset's default model". [cachedModels] is the last
 * fetched `/models` catalog (see [LlmProvider.listModels]) so the model picker is instant and works
 * offline; [cachedModelsAt] is its epoch-millis timestamp for staleness checks.
 */
@Serializable
data class ProviderAccount(
    val providerId: String,
    val displayName: String = "",
    val apiKey: String = "",
    val customBaseUrl: String = "",
    val transcriptionModel: String = "",
    val chatModel: String = "",
    /**
     * Chosen real-time streaming model for this provider (issue #128); empty = use the preset's
     * defaultRealtimeModel. Additive field, defaults empty for older stored accounts. Only meaningful
     * when the provider supports realtime and global real-time mode is on.
     */
    val realtimeModel: String = "",
    val cachedModels: List<String> = emptyList(),
    val cachedModelsAt: Long = 0L,
    /**
     * Ids from [cachedModels] whose catalog entry reports audio input → transcription-capable, even when
     * the id has no whisper/transcribe-style name (issue #132). Additive field, defaults empty for older
     * stored accounts; repopulated on the next live model fetch.
     */
    val cachedAudioModels: List<String> = emptyList(),
    /**
     * Ids from [cachedModels] that are DEDICATED speech-to-text models (audio in → transcription out, e.g.
     * OpenRouter's MAI-Transcribe / Whisper / Parakeet). Surfaced in the transcription picker but kept
     * separate from [cachedAudioModels] so they're never mistaken for chat-audio (#130) models (issue #157).
     * Additive field, defaults empty; repopulated on the next live model fetch.
     */
    val cachedTranscriptionModels: List<String> = emptyList(),
    /**
     * Single-call multimodal transcription (issue #130): when on, this provider's transcription model is
     * an audio-capable chat model and dictation is sent to `chat/completions` with `input_audio` in one
     * request (transcribe + format together) instead of using the dedicated STT endpoint plus a separate
     * rewording call. Additive field, defaults off for older stored accounts.
     */
    val transcriptionViaChat: Boolean = false,
    /**
     * Self-hosted streaming (issue #249): this endpoint speaks the OpenAI realtime protocol under
     * `/v1/realtime`, so live transcription can run against it instead of a cloud provider. Nothing in an
     * HTTP catalog says whether a server does, so the user tells us. Additive field, defaults off.
     */
    val customRealtime: Boolean = false,
    /**
     * Wake-on-demand support (issue #189): send a throwaway `/models` request as soon as a rewording is
     * known to be coming, so a machine that only wakes on network traffic has the dictation's length as a
     * head start instead of the user waiting out its boot.
     *
     * A common self-hosting shape is a small always-on box in front of a GPU machine that sleeps between
     * jobs. Only ever useful for an endpoint of the user's own, and nothing about a server says whether it
     * sleeps, so this is theirs to state. Additive field, defaults off.
     */
    val customWarmUp: Boolean = false,
    /**
     * Dictate Cloud only — the credit account this device talks to. The wallet's bearer token is not
     * stored here but in [apiKey]: it is what the server authenticates, so putting it anywhere else
     * would mean teaching every call site about a second kind of credential.
     *
     * [walletRecoveryCode] is kept because the server hands it out exactly once, when the account is
     * created, and only ever stores its hash. If it is not written down here at that moment it
     * cannot be recovered later — and it is the only thing standing between a factory reset and
     * lost credit, since Google does not restore a consumed one-time product.
     */
    val walletId: String = "",
    val walletRecoveryCode: String = "",
    /**
     * Last balance the server reported, in seconds and included rewordings; -1 means never fetched.
     * A cache for display only — every request is metered server-side, so this may lag behind and
     * nothing is ever decided from it.
     */
    val balanceSeconds: Int = -1,
    val balanceRewords: Int = -1,
    val balanceCheckedAt: Long = 0L,
) {
    /** True once the user has supplied a usable key (or this is a keyless endpoint like Ollama). */
    val hasKey: Boolean
        get() = apiKey.isNotBlank()

    /** True once a Dictate Cloud credit account exists on this device (see [walletId]). */
    val hasWallet: Boolean
        get() = walletId.isNotBlank() && apiKey.isNotBlank()

    /** True for user-defined endpoints (the legacy singular "custom" id or a "custom:<uuid>" one). */
    val isCustom: Boolean
        get() = providerId == LEGACY_CUSTOM_ID || providerId.startsWith(CUSTOM_PREFIX)

    /**
     * Whether this account needs a credential before it can be used at all.
     *
     * A server of the user's own does not, and neither do Ollama or the on-device engine — the
     * `Authorization` header is simply left off. Dictate Cloud has to be named separately: it has no key
     * page, so its `apiKeyUrl` is null and it looks exactly like a keyless endpoint, but its credential
     * is the wallet token and without one there is nothing to dictate with.
     *
     * One rule for one question (issue #273). The setup wizard used to derive its own by resolving the
     * provider through [ProviderRegistry.byId], which returns null for a `custom:<uuid>` id — so a
     * working keyless self-hosted endpoint was reported as "not set up" while the runtime happily
     * dictated through it.
     */
    val requiresCredential: Boolean
        get() = !isCustom &&
            (ProviderRegistry.byId(providerId)?.apiKeyUrl != null ||
                providerId == ProviderRegistry.CLOUD.id)

    companion object {
        const val CUSTOM_PREFIX = "custom:"

        /** The single custom-endpoint id the legacy app / early fork used (pre-keyring). */
        const val LEGACY_CUSTOM_ID = "custom"

        /** Mints a fresh, unique id for a user-defined endpoint. */
        fun newCustomId(): String = CUSTOM_PREFIX + UUID.randomUUID().toString().take(8)
    }
}

/**
 * Whether single-call multimodal (issue #130) actually applies to [account] — the switch being on is not
 * enough on its own.
 *
 * The route posts audio to `chat/completions`, so it needs a chat model that accepts audio. The on-device
 * engine has no chat surface at all, and a *dedicated* speech-to-text model answers on its own endpoint
 * (Google's, issue #292) — with either of those chosen, the switch has to yield to the model.
 *
 * Asked in one place because two very different questions turn on it: whether a dictation goes out as one
 * request, and — since #313 — whether the transcription model is also the rewording model. When those two
 * drifted apart, the dialog said one model did both while the rewording quietly used something else.
 */
fun singleCallApplies(transcriptionViaChat: Boolean, preset: ProviderPreset, model: String): Boolean =
    transcriptionViaChat &&
        preset.transcriptionApi != TranscriptionApi.LOCAL_ONDEVICE &&
        !(preset.id == ProviderRegistry.GEMINI.id && ProviderRegistry.isGeminiTranscribeModel(model))

/**
 * The model to reword with for [account] — the one the settings dialog says will be used, which is not
 * always the one [ProviderAccount.chatModel] holds (issue #313).
 *
 * Three answers, in order:
 *
 *  1. **An explicit choice wins.** A rewording model the user picked is used verbatim, and if the provider
 *     has since retired it they get an error naming the model they chose, which is the honest outcome.
 *  2. **Otherwise the single-call promise is kept.** With that switch on, the dialog hides the rewording
 *     field and relabels the remaining one "Transcription & rewording model" — so that field is what the
 *     user was told does both, and rewording has to read it. It did not: `chatModel` stayed blank forever
 *     and every rewording went to the preset default instead, which is the bug #313 reports. Only when
 *     [singleCallApplies], because a dedicated speech-to-text model cannot reword at all.
 *  3. **Otherwise the preset default**, which is what "no model chosen" has always meant. Kept as an empty
 *     stored string on purpose, so a later app update can move a provider off a retired model — the
 *     mechanism that failed here not because it is wrong but because nobody moved the models.
 */
fun chatModelFor(
    account: ProviderAccount,
    preset: ProviderPreset,
    fallback: String = "gpt-4o-mini",
): String {
    account.chatModel.takeIf { it.isNotBlank() }?.let { return it }
    val shared = account.transcriptionModel
    if (shared.isNotBlank() && singleCallApplies(account.transcriptionViaChat, preset, shared)) return shared
    return preset.defaultChatModel ?: fallback
}

/**
 * Whether [chatModelFor] answered with the built-in default rather than with anything the user chose.
 *
 * Worth asking when a rewording fails: a provider naming a model the user has never heard of reads as
 * the app sending the wrong thing, which is exactly how #313 was reported. Knowing the model came from
 * the preset turns that into one sentence about where it came from and where to change it.
 */
fun chatModelIsPresetDefault(account: ProviderAccount, preset: ProviderPreset): Boolean =
    account.chatModel.isBlank() &&
        !(account.transcriptionModel.isNotBlank() &&
            singleCallApplies(account.transcriptionViaChat, preset, account.transcriptionModel))

/**
 * The provider keyring: every configured [ProviderAccount] keyed by provider id. Persisted as a single
 * JetPref `custom` preference (see `AppPrefs.dictate.providerAccounts`) using [Serializer], mirroring
 * the `EmojiHistory` pattern. Switching the active transcription/rewording provider is just a change of
 * the `active*ProviderId` pointer – each provider keeps its own key and models here.
 */
@Serializable
data class ProviderAccounts(
    val accounts: Map<String, ProviderAccount> = emptyMap(),
) {
    operator fun get(providerId: String): ProviderAccount? = accounts[providerId]

    /** Returns the stored account or a fresh empty one for [providerId] (never null). */
    fun getOrEmpty(providerId: String): ProviderAccount =
        accounts[providerId] ?: ProviderAccount(providerId = providerId)

    /** Returns a copy with [account] inserted/replaced under its own id. */
    fun put(account: ProviderAccount): ProviderAccounts =
        copy(accounts = accounts + (account.providerId to account))

    /** Returns a copy with [providerId] removed (used to delete a custom endpoint). */
    fun remove(providerId: String): ProviderAccounts =
        copy(accounts = accounts - providerId)

    /** In-place style edit helper: apply [block] to the account (existing or empty) and store it. */
    fun edit(providerId: String, block: (ProviderAccount) -> ProviderAccount): ProviderAccounts =
        put(block(getOrEmpty(providerId)))

    object Serializer : PreferenceSerializer<ProviderAccounts> {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        override fun serialize(value: ProviderAccounts): String =
            json.encodeToString(value)

        override fun deserialize(value: String): ProviderAccounts = try {
            json.decodeFromString(value)
        } catch (e: Exception) {
            flogError { "Failed to deserialize ProviderAccounts: $e" }
            Empty
        }
    }

    companion object {
        val Empty = ProviderAccounts(emptyMap())
    }
}
