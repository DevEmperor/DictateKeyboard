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

/**
 * Which providers the settings show where, now that there are more than twenty of them.
 *
 * The providers screen used to list every preset, set up or not, so the two or three a person actually
 * uses were somewhere in a column of two dozen, and both active-provider pickers offered every one of them
 * too — including all the ones that could only ever answer "no API key" at the moment of dictating. Now the
 * screen lists the user's own and keeps the rest one tap away behind "Add a provider", and the pickers
 * offer only what works.
 *
 * Answered here rather than in the composables so the rules can be tested without Android.
 */
object ProviderListing {

    /**
     * Always on the providers screen, set up or not: the two ways to dictate without an account anywhere
     * else. Dictate Cloud is bought inside the app and the on-device engine needs only a download, so
     * hiding them until they were set up would hide them from exactly the people they are for.
     */
    val pinnedIds: Set<String> = setOf(ProviderRegistry.LOCAL.id, ProviderRegistry.CLOUD.id)

    /**
     * Whether [preset] can be used right now.
     *
     * Stricter than the setup wizard's rule for one kind of provider. Ollama needs no key, so to the
     * question "does the active provider work" it counts as set up from the first launch — right there,
     * and wrong here, where it would put Ollama on everyone's list. A keyless preset counts once its
     * account has been saved, which is the only sign that someone meant to use it.
     *
     * The on-device engine counts when either of its two models is on disk, because the dictation path
     * falls back from one to the other.
     */
    fun isSetUp(
        preset: ProviderPreset,
        accounts: ProviderAccounts,
        isModelInstalled: (String) -> Boolean,
    ): Boolean {
        if (preset.transcriptionApi == TranscriptionApi.LOCAL_ONDEVICE) {
            val account = accounts.getOrEmpty(preset.id)
            val oneShot = account.transcriptionModel.ifBlank { preset.defaultTranscriptionModel.orEmpty() }
            return listOf(oneShot, account.realtimeModel).any { it.isNotBlank() && isModelInstalled(it) }
        }
        val account = accounts[preset.id] ?: return false
        return if (account.requiresCredential) account.hasKey else true
    }

    /**
     * Whether [preset]'s row stands on the providers screen rather than behind "Add a provider".
     *
     * A provider that is active for either job stays listed even when it is not set up: that is the one
     * row somebody has to be able to find to fix it — a fresh install's default provider without a key,
     * say.
     */
    fun isListed(
        preset: ProviderPreset,
        accounts: ProviderAccounts,
        activeIds: Set<String>,
        isModelInstalled: (String) -> Boolean,
    ): Boolean = preset.id in pinnedIds || preset.id in activeIds || isSetUp(preset, accounts, isModelInstalled)

    /**
     * Whether an active-provider picker offers [preset]: set up, or already the choice.
     *
     * The second half is not a courtesy. A picker whose ticked option has vanished would have nothing
     * ticked, and confirming it would quietly keep a provider nobody can see.
     */
    fun isPickable(
        preset: ProviderPreset,
        accounts: ProviderAccounts,
        selectedId: String,
        isModelInstalled: (String) -> Boolean,
    ): Boolean = preset.id == selectedId || isSetUp(preset, accounts, isModelInstalled)

    /** The chips above the add-provider list. Several at once narrow the list further, never widen it. */
    enum class Filter { TRANSCRIPTION, REWORDING, REALTIME, EU }

    /** Whether [preset] matches the search [query] (by name, any case) and every one of [filters]. */
    fun matches(preset: ProviderPreset, query: String, filters: Set<Filter>): Boolean {
        val q = query.trim()
        if (q.isNotEmpty() && !preset.displayName.contains(q, ignoreCase = true)) return false
        return filters.all { filter ->
            when (filter) {
                Filter.TRANSCRIPTION -> preset.capabilities.transcription
                Filter.REWORDING -> preset.capabilities.chat
                Filter.REALTIME -> preset.supportsRealtime
                Filter.EU -> preset.servesFromEu
            }
        }
    }
}
