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
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import dev.patrickgold.florisboard.dictate.sync.DictateSyncedSettings
import dev.patrickgold.florisboard.dictate.sync.DictateWearProtocol
import kotlinx.coroutines.tasks.await

/**
 * Thin helper around the Data Layer clients for the watch -> phone direction (#106): finding the
 * paired phone that runs Dictate and asking it to (re)publish the current settings.
 */
object WearSyncClient {

    /**
     * The id of a connected node that runs the phone Dictate app, or null if none is currently
     * reachable (-> the watch falls back to its cached settings and to standalone transcription).
     *
     * Primary signal is the advertised capability; if Play Services hasn't propagated it yet we fall
     * back to any directly-connected node, since the phone's listener service answers regardless.
     */
    suspend fun findPhoneNodeId(context: Context): String? {
        runCatching {
            val info = Wearable.getCapabilityClient(context)
                .getCapability(DictateWearProtocol.CAPABILITY_PHONE_APP, CapabilityClient.FILTER_REACHABLE)
                .await()
            info.nodes.firstOrNull { it.isNearby }?.id ?: info.nodes.firstOrNull()?.id
        }.getOrNull()?.let { return it }

        // Capability not seen — fall back to a nearby connected node.
        return runCatching {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            nodes.firstOrNull { it.isNearby }?.id ?: nodes.firstOrNull()?.id
        }.getOrNull()
    }

    /** Ask the phone to push a fresh settings snapshot. No-op if no phone node is reachable. */
    suspend fun requestSettingsSync(context: Context) {
        val nodeId = findPhoneNodeId(context) ?: return
        Wearable.getMessageClient(context)
            .sendMessage(nodeId, DictateWearProtocol.PATH_SYNC_REQUEST, ByteArray(0))
            .await()
    }

    /** Toggle "sync key to watch" on the phone from the watch (#106). No-op if no phone is reachable. */
    suspend fun setStandalone(context: Context, enabled: Boolean) {
        val nodeId = findPhoneNodeId(context) ?: return
        Wearable.getMessageClient(context)
            .sendMessage(nodeId, DictateWearProtocol.PATH_SET_STANDALONE, byteArrayOf(if (enabled) 1 else 0))
            .await()
    }

    /** Toggle auto-rewording of watch dictations on the phone from the watch (#130). */
    suspend fun setAutoRewording(context: Context, enabled: Boolean) {
        val nodeId = findPhoneNodeId(context) ?: return
        Wearable.getMessageClient(context)
            .sendMessage(nodeId, DictateWearProtocol.PATH_SET_AUTO_REWORDING, byteArrayOf(if (enabled) 1 else 0))
            .await()
    }

    /**
     * Read the latest settings snapshot the phone published to the Data Layer. Unlike [requestSettingsSync]
     * this needs no live round-trip — the Data Layer replicates the phone's [DictateWearProtocol.PATH_SETTINGS]
     * DataItem to the watch and caches it, so the most recent values are available even right after a flaky
     * reconnect. Returns null if the phone has never published. Call this on every settings/keyboard open so
     * the watch always reflects the phone (accent color, provider, prompt, …).
     */
    suspend fun fetchPublishedSettings(context: Context): DictateSyncedSettings? {
        val items = runCatching { Wearable.getDataClient(context).dataItems.await() }.getOrNull() ?: return null
        try {
            val item = items.firstOrNull { it.uri.path == DictateWearProtocol.PATH_SETTINGS } ?: return null
            val json = DataMapItem.fromDataItem(item).dataMap.getString(DictateWearProtocol.KEY_SETTINGS_JSON)
            return DictateSyncedSettings.decode(json)
        } finally {
            items.release()
        }
    }
}
