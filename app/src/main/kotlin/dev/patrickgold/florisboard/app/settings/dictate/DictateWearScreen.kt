/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.dictate

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Shop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.settings.search.settingsSearchAnchor
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.util.launchUrl
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import org.florisboard.lib.compose.stringRes

/**
 * Wear OS companion settings (#106/#130): the two watch-related options that are also mirrored on the
 * watch's own Dictate app. Changing either here re-publishes the settings snapshot to the watch, and
 * the watch can flip the same toggles back (the change syncs to the phone via the Data Layer).
 *
 *  - **Standalone / sync key**: ship the API key to the watch so it can dictate when the phone is out of
 *    range (off → the watch is tether-only and the key never leaves the phone).
 *  - **Auto-rewording on the watch**: apply the same auto-rewording to watch dictations as on the phone
 *    (tethered ones are reworded by the phone, standalone ones by the watch).
 */
@Composable
fun DictateWearScreen() = FlorisScreen {
    title = stringRes(R.string.dictate__wear_title)
    previewFieldVisible = true
    iconSpaceReserved = true

    val prefs by FlorisPreferenceStore

    content {
        val context = LocalContext.current
        // Discovery helper: some users can't find the Wear app on their watch. Opening the phone's Play
        // Store listing lets them pick their paired watch as the install target (the watch must run
        // Wear OS 3+ / API 30 — older Tizen Galaxy watches can't run Wear OS apps at all).
        Preference(
            icon = Icons.Default.Shop,
            modifier = Modifier.settingsSearchAnchor("dictate__wear_install_title"),
            title = stringRes(R.string.dictate__wear_install_title),
            summary = stringRes(R.string.dictate__wear_install_summary),
            onClick = {
                context.launchUrl("https://play.google.com/store/apps/details?id=net.devemperor.dictate")
            },
        )
        SwitchPreference(
            prefs.dictate.wearStandaloneEnabled,
            icon = Icons.Default.Key,
            modifier = Modifier.settingsSearchAnchor("dictate__wear_standalone_title"),
            title = stringRes(R.string.dictate__wear_standalone_title),
            summary = stringRes(R.string.dictate__wear_standalone_summary),
        )
        SwitchPreference(
            prefs.dictate.wearAutoRewordingEnabled,
            icon = Icons.Default.AutoFixHigh,
            modifier = Modifier.settingsSearchAnchor("dictate__wear_auto_rewording_title"),
            title = stringRes(R.string.dictate__wear_auto_rewording_title),
            summary = stringRes(R.string.dictate__wear_auto_rewording_summary),
        )
    }
}
