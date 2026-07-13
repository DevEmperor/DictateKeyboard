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
import androidx.compose.ui.graphics.toArgb
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.dictate.DictateLanguages
import dev.patrickgold.florisboard.dictate.data.prompts.DictatePromptDefaults
import dev.patrickgold.florisboard.dictate.data.prompts.PromptsDatabaseHelper
import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.dictate.sync.DictateSyncedSettings
import dev.patrickgold.florisboard.dictate.sync.SyncedPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Translates the phone's live Dictate settings into the [DictateSyncedSettings] snapshot pushed to the
 * watch (#106). Mirrors the active-account resolution in `DictateController` (transcriptionProviderId ->
 * keyring account -> registry preset) but only exposes what the watch needs.
 *
 * The watch tethers through the phone whenever it is reachable; the API key is also shipped (unless the
 * user turned the [wearStandaloneEnabled] master toggle off) so the watch can still transcribe on its
 * own when the phone is out of range. The phone accent color is shipped so the watch UI matches.
 */
object PhoneWearSettingsResolver {

    suspend fun resolve(context: Context, prefs: FlorisPreferenceModel): DictateSyncedSettings {
        val id = prefs.dictate.transcriptionProviderId.get()
        val account = prefs.dictate.providerAccounts.get().getOrEmpty(id)
        val preset = presetFor(account)

        val model = account.transcriptionModel.ifBlank { preset.defaultTranscriptionModel ?: "" }
        val baseUrl = if ((account.isCustom || preset.allowsCustomBaseUrl) && account.customBaseUrl.isNotBlank()) account.customBaseUrl else preset.baseUrl
        val language = prefs.dictate.activeInputLanguage.get().takeIf { it != DictateLanguages.DETECT }

        // Ship the key so the watch can work standalone when the phone is away, unless the user opted out
        // of syncing the secret (then the watch is tether-only and won't dictate without the phone).
        val syncKey = prefs.dictate.wearStandaloneEnabled.get()

        // Rewording config for the watch's standalone rewording chain (#130). Mirrors DictateController's
        // rewordingAccount()/rewordingApiKey()/systemPrompt() so the watch reproduces the phone exactly.
        val rewordingId = prefs.dictate.rewordingProviderId.get()
        val rewordingAccount = prefs.dictate.providerAccounts.get().getOrEmpty(rewordingId)
        val rewordingPreset = presetFor(rewordingAccount)
        val chatModel = rewordingAccount.chatModel.ifBlank { rewordingPreset.defaultChatModel ?: "" }
        val rewordingBaseUrl = if ((rewordingAccount.isCustom || rewordingPreset.allowsCustomBaseUrl) && rewordingAccount.customBaseUrl.isNotBlank()) rewordingAccount.customBaseUrl else rewordingPreset.baseUrl
        val rewordingKey = rewordingAccount.apiKey.ifBlank { account.apiKey }
        val autoApply = withContext(Dispatchers.IO) {
            PromptsDatabaseHelper.getInstance(context).getAll()
                .filter { it.autoApply }
                .mapNotNull { p -> p.prompt?.takeIf { it.isNotBlank() }?.let { SyncedPrompt(it, p.requiresSelection) } }
        }

        return DictateSyncedSettings(
            transcriptionProviderId = id,
            providerLabel = preset.displayName,
            transcriptionApi = preset.transcriptionApi,
            baseUrl = baseUrl,
            model = model,
            apiKey = if (syncKey) account.apiKey else "",
            keySyncEnabled = syncKey,
            language = language,
            languageName = DictateLanguages.englishNameFor(prefs.dictate.activeInputLanguage.get()),
            stylePrompt = stylePrompt(prefs),
            // The watch is a keyboard, so mirror the *keyboard* accent (theme.accentColor, set on the
            // Theme screen) — not the separate settings-app accent (other.accentColor) — so the watch
            // matches what the user actually sees on the phone keyboard.
            accentColorArgb = prefs.theme.accentColor.get().toArgb(),
            autoRewordingEnabled = prefs.dictate.wearAutoRewordingEnabled.get(),
            rewordingEnabled = prefs.dictate.rewordingEnabled.get(),
            autoFormattingEnabled = prefs.dictate.autoFormattingEnabled.get(),
            chatModel = chatModel,
            rewordingBaseUrl = rewordingBaseUrl,
            // Rewording key only travels under the same opt-in as the transcription key.
            rewordingApiKey = if (syncKey) rewordingKey else "",
            rewordingApi = rewordingPreset.transcriptionApi,
            systemPrompt = systemPrompt(prefs),
            autoApplyPrompts = autoApply,
            hapticFeedback = prefs.dictate.hapticFeedback.get(),
        )
    }

    /** The rewording system prompt (be-precise / custom), mirroring `DictateController.systemPrompt()`. */
    private fun systemPrompt(prefs: FlorisPreferenceModel): String? = when (prefs.dictate.systemPromptSelection.get()) {
        DictatePromptDefaults.SELECTION_PREDEFINED -> DictatePromptDefaults.REWORDING_BE_PRECISE
        DictatePromptDefaults.SELECTION_CUSTOM -> prefs.dictate.systemPromptCustom.get()
        else -> ""
    }.takeIf { it.isNotBlank() }

    private fun presetFor(account: ProviderAccount) = when {
        account.isCustom -> ProviderRegistry.custom(account.customBaseUrl)
        else -> ProviderRegistry.byId(account.providerId) ?: ProviderRegistry.OPENAI
    }

    /**
     * The style/punctuation prompt the watch should send with a standalone transcription. Mirrors
     * `DictateController.transcriptionStylePrompt()` so the watch biases recognition exactly like the
     * phone (predefined punctuation prompt or the user's custom one, plus the custom-words list).
     */
    private fun stylePrompt(prefs: FlorisPreferenceModel): String? {
        val base = when (prefs.dictate.stylePromptSelection.get()) {
            DictatePromptDefaults.SELECTION_PREDEFINED ->
                DictatePromptDefaults.punctuationPromptFor(prefs.dictate.activeInputLanguage.get())
            DictatePromptDefaults.SELECTION_CUSTOM ->
                prefs.dictate.stylePromptCustom.get().takeIf { it.isNotBlank() }
            else -> null
        }
        return DictatePromptDefaults.appendCustomWords(base, prefs.dictate.customWords.get())
    }
}

