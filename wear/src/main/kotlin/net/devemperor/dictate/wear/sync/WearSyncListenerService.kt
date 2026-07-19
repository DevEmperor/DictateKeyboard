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

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import dev.patrickgold.florisboard.dictate.sync.DictateSyncedSettings
import dev.patrickgold.florisboard.dictate.sync.DictateWearProtocol

/**
 * Receives settings snapshots the phone publishes to [DictateWearProtocol.PATH_SETTINGS] and writes
 * them into [WearSettingsStore]. Registered in the manifest for the Data Layer DATA_CHANGED action.
 */
class WearSyncListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != DictateWearProtocol.PATH_SETTINGS) continue

            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            val raw = dataMap.getString(DictateWearProtocol.KEY_SETTINGS_JSON)
            DictateSyncedSettings.decode(raw)?.let { settings ->
                WearSettingsStore.save(applicationContext, settings)
            }
        }
    }
}
