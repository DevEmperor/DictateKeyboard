/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.util.AppVersionUtils
import dev.patrickgold.florisboard.lib.util.VersionName
import dev.patrickgold.florisboard.lib.util.launchUrl
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.florisDialogScroll
import org.florisboard.lib.compose.stringRes

/** PayPal donation link, kept in sync with the in-keyboard donate promo (see DictateController). */
private const val DONATE_URL = "https://paypal.me/DevEmperor"

/**
 * Temporary debug switch to preview the dialog. When true, the dialog is shown on every launch
 * regardless of the version bookkeeping (which never triggers on debug builds, since their version
 * name carries an unparseable suffix). MUST be false for any committed/shipped build.
 */
private const val DEBUG_FORCE_SHOW = false

/**
 * A "What's new" dialog shown once after the app was updated to a new version. It relies on the
 * existing [AppVersionUtils] version bookkeeping: it appears when [AppVersionUtils.shouldShowChangelog]
 * is true (i.e. the user updated rather than freshly installed) and marks the changelog as seen on
 * dismissal so it does not reappear on the next launch.
 *
 * The body lists the notes for every release newer than the version the user last saw (so skipping a
 * few updates still surfaces all changes since the installed version), plus a tappable donation
 * invite and a link to the full online changelog/releases page.
 *
 * Note: for debug/CI builds the version name carries a suffix that [AppVersionUtils] cannot parse, so
 * the dialog only surfaces on proper release builds — matching the upstream behavior. Use
 * [DEBUG_FORCE_SHOW] to preview it during development.
 */
@Composable
fun ChangelogDialog() {
    val context = LocalContext.current
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()

    var visible by rememberSaveable {
        mutableStateOf(DEBUG_FORCE_SHOW || AppVersionUtils.shouldShowChangelog(context, prefs))
    }
    if (!visible) return

    val lastSeen = VersionName.fromString(prefs.internal.versionLastChangelog.get())
    val entries = DictateChangelog.entriesSince(lastSeen)

    fun markSeenAndClose() {
        scope.launch { AppVersionUtils.updateVersionLastChangelog(context, prefs) }
        visible = false
    }

    JetPrefAlertDialog(
        scrollModifier = florisDialogScroll(),
        title = stringRes(R.string.changelog__dialog_title),
        confirmLabel = stringRes(R.string.action__ok),
        onConfirm = { markSeenAndClose() },
        neutralLabel = stringRes(R.string.changelog__view_all),
        onNeutral = {
            context.launchUrl(R.string.florisboard__changelog_url, "version" to BuildConfig.VERSION_NAME)
            markSeenAndClose()
        },
        onDismiss = { markSeenAndClose() },
    ) {
        Column {
            Text(text = stringRes(R.string.changelog__intro))
            entries.forEach { entry ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringRes(R.string.changelog__version_header, "version" to entry.version),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = stringRes(entry.notes))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { context.launchUrl(DONATE_URL) }
                    .padding(vertical = 4.dp),
                text = stringRes(R.string.changelog__donate),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
