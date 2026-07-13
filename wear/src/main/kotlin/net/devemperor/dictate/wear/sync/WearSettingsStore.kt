/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.devemperor.dictate.wear.sync

import android.content.Context
import dev.patrickgold.florisboard.dictate.sync.DictateSyncedSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watch-local cache of the settings synced from the phone (#106). Backed by SharedPreferences so the
 * last-known config survives reboots / the phone being out of range, and exposed as a [StateFlow] so
 * the IME and the settings UI recompose when a fresh snapshot arrives over the Data Layer.
 */
object WearSettingsStore {
    private const val PREFS = "dictate_wear_sync"
    private const val KEY = "settings_json"

    private val _settings = MutableStateFlow(DictateSyncedSettings())
    val settings: StateFlow<DictateSyncedSettings> = _settings.asStateFlow()

    /** Load the persisted snapshot into memory. Call once on app/service start. */
    fun load(context: Context) {
        val raw = prefs(context).getString(KEY, null)
        DictateSyncedSettings.decode(raw)?.let { _settings.value = it }
    }

    /** Persist and publish a fresh snapshot received from the phone. */
    fun save(context: Context, settings: DictateSyncedSettings) {
        prefs(context).edit().putString(KEY, settings.encode()).apply()
        _settings.value = settings
    }

    fun current(): DictateSyncedSettings = _settings.value

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
