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
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.sync.DictateWearProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.florisboard.lib.kotlin.collectIn

/**
 * Publishes the phone's Dictate settings to the Wearable Data Layer (#106).
 *
 * Two callers:
 *  - [DictateWearService] re-publishes on demand when the watch asks ([DictateWearProtocol.PATH_SYNC_REQUEST]).
 *  - [startPublishingOnChange] (from `FlorisApplication`) watches the relevant phone settings and
 *    re-publishes whenever any of them changes, so the replicated DataItem on the watch is always current
 *    — the watch then reflects accent/provider/key/prompt changes the moment it next reads them, instead
 *    of only after an explicit round-trip request.
 */
object DictateWearPublisher {

    private val prefs by FlorisPreferenceStore

    /** Serialize the current settings and put them on the Data Layer for the watch. */
    suspend fun publish(context: Context) {
        val settings = PhoneWearSettingsResolver.resolve(context, prefs)
        val request = PutDataMapRequest.create(DictateWearProtocol.PATH_SETTINGS).run {
            dataMap.putString(DictateWearProtocol.KEY_SETTINGS_JSON, settings.encode())
            // Vary a field every push so an identical payload still produces a DATA_CHANGED event on the
            // watch (used by the explicit re-request path).
            dataMap.putLong("published_at", System.currentTimeMillis())
            asPutDataRequest().setUrgent()
        }
        Wearable.getDataClient(context).putDataItem(request)
    }

    /**
     * Re-publish whenever a watch-relevant setting changes. Call once after the preference store is
     * loaded. Debounced so a burst of edits (e.g. typing custom words) collapses into a single push.
     */
    fun startPublishingOnChange(context: Context, scope: CoroutineScope) {
        merge(
            prefs.theme.accentColor.asFlow().map {},
            prefs.dictate.transcriptionProviderId.asFlow().map {},
            prefs.dictate.providerAccounts.asFlow().map {},
            prefs.dictate.activeInputLanguage.asFlow().map {},
            prefs.dictate.stylePromptSelection.asFlow().map {},
            prefs.dictate.stylePromptCustom.asFlow().map {},
            prefs.dictate.customWords.asFlow().map {},
            prefs.dictate.wearStandaloneEnabled.asFlow().map {},
        ).debounce(500L).collectIn(scope) {
            runCatching { publish(context) }
        }
    }
}
