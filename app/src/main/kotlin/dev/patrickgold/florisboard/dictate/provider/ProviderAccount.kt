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
 * Whether a dictation can go out as a single `chat/completions` request with the audio in it (#130).
 *
 * A **routing** question, not a judgement about what a model is good at: the on-device engine has no
 * chat endpoint at all, and Google's dedicated speech-to-text models answer on their own endpoint
 * (#292), so neither can be posted to. Everything else is offered, and whether the model actually
 * accepts audio is the user's business — see [sharedSingleCallModel].
 *
 * Read only by the dictation path. The settings dialog folds its fields on the switch alone, and the
 * rewording model follows the field the user can see; having this decide those too is what left the
 * switch looking broken (#313).
 */
fun singleCallApplies(transcriptionViaChat: Boolean, preset: ProviderPreset, model: String): Boolean =
    transcriptionViaChat &&
        preset.transcriptionApi != TranscriptionApi.LOCAL_ONDEVICE &&
        !(preset.id == ProviderRegistry.GEMINI.id && ProviderRegistry.isGeminiTranscribeModel(model))

/**
 * The model to reword with for [account] — the one the settings dialog says will be used, which is not
 * always the one [ProviderAccount.chatModel] holds (issue #313).
 *
 * With single-call on, the dialog folds the rewording field into the transcription one and labels it
 * "Transcription & rewording model". Rewording used to go on reading `chatModel` regardless, which that
 * fold leaves blank forever, so every rewording went to the preset default and no setting could change
 * it. So the merged field wins when it holds something ([sharedSingleCallModel]); otherwise the rewording
 * field's own value, and failing that the preset default — which is what an empty box has always meant.
 */
fun chatModelFor(
    account: ProviderAccount,
    preset: ProviderPreset,
    fallback: String = "gpt-4o-mini",
): String {
    sharedSingleCallModel(account)?.let { return it }
    return account.chatModel.ifBlank { preset.defaultChatModel ?: fallback }
}

/**
 * The transcription model when it is also the rewording model, else null.
 *
 * Two conditions, and neither of them is a guess about the model. The switch has to be on, because that
 * is what folds the two fields into one on screen; and something has to actually stand in that field,
 * because an empty box is not a choice — it is the preset default showing through, and each field then
 * falls back to its own. Sharing a *default* would mean a fresh Gemini account rewording with
 * `gemini-3.5-transcribe`, which fails, so nothing would work until the user typed something.
 *
 * What is emphatically not asked is whether the chosen model can do both. The app has no reliable way to
 * know — the modality data it once used was wrong often enough to mislead — and a version that decided
 * for the user refused to merge the fields and left the switch looking broken. Choosing a model that
 * serves both is the user's job here, and when it does not, the error names the model they chose.
 */
private fun sharedSingleCallModel(account: ProviderAccount): String? =
    account.transcriptionModel.takeIf { account.transcriptionViaChat && it.isNotBlank() }

/**
 * Whether [chatModelFor] answered with the built-in default rather than with anything the user chose.
 *
 * Worth asking when a rewording fails: a provider naming a model the user has never heard of reads as
 * the app sending the wrong thing, which is exactly how #313 was reported. Knowing the model came from
 * the preset turns that into one sentence about where it came from and where to change it.
 */
fun chatModelIsPresetDefault(account: ProviderAccount, preset: ProviderPreset): Boolean =
    // A model handed over by the merged field is the user's choice like any other, so it needs no
    // explanation of where it came from.
    sharedSingleCallModel(account) == null && account.chatModel.isBlank()

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
