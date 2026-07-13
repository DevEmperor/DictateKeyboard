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

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.dictate.DictateLanguages
import dev.patrickgold.florisboard.dictate.data.prompts.DictatePromptDefaults
import dev.patrickgold.florisboard.dictate.data.prompts.PromptsDatabaseHelper
import dev.patrickgold.florisboard.dictate.dictateProxyConfig
import dev.patrickgold.florisboard.dictate.provider.DictateRewording
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
 * Phone-side transcription used by the tethered watch flow (#106): the watch streams audio over the
 * Data Layer and the phone transcribes it with its own active provider/credentials, mirroring the
 * normal in-app transcription path (including the on-device fallback provider).
 */
object PhoneTranscriber {

    suspend fun transcribe(context: Context, prefs: FlorisPreferenceModel, audio: File): String {
        val id = prefs.dictate.transcriptionProviderId.get()
        val account = prefs.dictate.providerAccounts.get().getOrEmpty(id)
        val preset = presetFor(account)
        val model = account.transcriptionModel.ifBlank { preset.defaultTranscriptionModel ?: "" }
        val language = prefs.dictate.activeInputLanguage.get().takeIf { it != DictateLanguages.DETECT }

        val request = TranscriptionRequest(audioFile = audio, model = model, language = language)

        val transcript = if (preset.transcriptionApi == TranscriptionApi.LOCAL_ONDEVICE) {
            // The phone is configured for on-device STT: transcribe locally, no network/key needed.
            LocalTranscriptionProvider(LocalTranscriptionProvider.modelDir(context.applicationContext, model))
                .transcribe(request).text.trim()
        } else {
            val client = OpenAiCompatibleClient.from(
                preset,
                account.apiKey,
                baseUrlOverride = if (account.isCustom || preset.allowsCustomBaseUrl) account.customBaseUrl.takeIf { it.isNotBlank() } else null,
                proxy = prefs.dictate.dictateProxyConfig(),
                trustUserCerts = prefs.dictate.trustUserCertificates.get(),
            )
            client.transcribe(request).text.trim()
        }
        // Auto-reword the tethered dictation here on the phone (#130), so the watch receives finished text
        // exactly like the phone produces — but only when the user kept auto-rewording on for the watch.
        return maybeReword(context, prefs, transcript)
    }

    /** Runs the shared rewording chain on [transcript] when the user enabled watch auto-rewording. */
    private suspend fun maybeReword(context: Context, prefs: FlorisPreferenceModel, transcript: String): String {
        if (transcript.isBlank()) return transcript
        if (!prefs.dictate.wearAutoRewordingEnabled.get()) return transcript
        if (!prefs.dictate.rewordingEnabled.get()) return transcript

        val accounts = prefs.dictate.providerAccounts.get()
        val rewordingAccount = accounts.getOrEmpty(prefs.dictate.rewordingProviderId.get())
        val rewordingPreset = presetFor(rewordingAccount)
        val transcriptionKey = accounts.getOrEmpty(prefs.dictate.transcriptionProviderId.get()).apiKey
        val apiKey = rewordingAccount.apiKey.ifBlank { transcriptionKey }
        if (apiKey.isBlank() && rewordingPreset.transcriptionApi != TranscriptionApi.LOCAL_ONDEVICE) return transcript

        val chatModel = rewordingAccount.chatModel.ifBlank { rewordingPreset.defaultChatModel ?: "gpt-4o-mini" }
        val client = OpenAiCompatibleClient.from(
            rewordingPreset,
            apiKey,
            baseUrlOverride = if (rewordingAccount.isCustom || rewordingPreset.allowsCustomBaseUrl) rewordingAccount.customBaseUrl.takeIf { it.isNotBlank() } else null,
            proxy = prefs.dictate.dictateProxyConfig(),
            trustUserCerts = prefs.dictate.trustUserCertificates.get(),
        )
        val autoApply = withContext(Dispatchers.IO) {
            PromptsDatabaseHelper.getInstance(context).getAll()
                .filter { it.autoApply }
                .mapNotNull { p -> p.prompt?.takeIf { it.isNotBlank() }?.let { DictateRewording.Prompt(it, p.requiresSelection) } }
        }
        val languageName = DictateLanguages.englishNameFor(prefs.dictate.activeInputLanguage.get())
        val systemPrompt = when (prefs.dictate.systemPromptSelection.get()) {
            DictatePromptDefaults.SELECTION_PREDEFINED -> DictatePromptDefaults.REWORDING_BE_PRECISE
            DictatePromptDefaults.SELECTION_CUSTOM -> prefs.dictate.systemPromptCustom.get()
            else -> ""
        }.takeIf { it.isNotBlank() }
        return DictateRewording.apply(
            client = client,
            chatModel = chatModel,
            transcript = transcript,
            autoFormatting = prefs.dictate.autoFormattingEnabled.get(),
            languageName = languageName,
            systemPrompt = systemPrompt,
            autoApplyPrompts = autoApply,
        )
    }

    private fun presetFor(account: ProviderAccount) = when {
        account.isCustom -> ProviderRegistry.custom(account.customBaseUrl)
        else -> ProviderRegistry.byId(account.providerId) ?: ProviderRegistry.OPENAI
    }
}
