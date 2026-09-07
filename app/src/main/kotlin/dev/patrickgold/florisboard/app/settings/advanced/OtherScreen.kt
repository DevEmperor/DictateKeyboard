/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.app.settings.advanced

import android.text.format.Formatter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adb
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.settings.search.settingsSearchAnchor
import dev.patrickgold.florisboard.app.AppTheme
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.enumDisplayEntriesOf
import dev.patrickgold.florisboard.ime.core.DisplayLanguageNamesIn
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.cache.AppCache
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.ColorPickerPreference
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import dev.patrickgold.jetpref.datastore.ui.isMaterialYou
import dev.patrickgold.jetpref.datastore.ui.listPrefEntries
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.AndroidVersion
import org.florisboard.lib.color.ColorMappings
import org.florisboard.lib.compose.florisDialogScroll
import org.florisboard.lib.compose.stringRes


@Composable
fun OtherScreen() = FlorisScreen {
    title = stringRes(R.string.settings__other__title)
    previewFieldVisible = false

    val navController = LocalNavController.current
    val context = LocalContext.current

    content {
        val scope = rememberCoroutineScope()
        // Walked rather than remembered: nothing counts the cache as it grows, and the walk is cheap
        // enough to redo whenever this screen opens or the cache is emptied (issue #337).
        var cacheBytes by remember { mutableStateOf(0L) }
        var cacheGeneration by remember { mutableStateOf(0) }
        var confirmClearCache by remember { mutableStateOf(false) }
        LaunchedEffect(cacheGeneration) {
            cacheBytes = withContext(Dispatchers.IO) { AppCache.sizeBytes(context) }
        }

        ListPreference(
            prefs.other.settingsTheme,
            icon = Icons.Default.Palette,
            modifier = Modifier.settingsSearchAnchor("pref__other__settings_theme__label"),
            title = stringRes(R.string.pref__other__settings_theme__label),
            entries = enumDisplayEntriesOf(AppTheme::class),
        )
        ColorPickerPreference(
            pref = prefs.other.accentColor,
            modifier = Modifier.settingsSearchAnchor("pref__other__settings_accent_color__label"),
            title = stringRes(R.string.pref__other__settings_accent_color__label),
            defaultValueLabel = stringRes(R.string.action__default),
            icon = Icons.Default.FormatColorFill,
            defaultColors = ColorMappings.colors,
            showAlphaSlider = false,
            enableAdvancedLayout = true,
            colorOverride = {
                if (it.isMaterialYou(context)) {
                    Color.Unspecified
                } else {
                    it
                }
            }
        )
        ListPreference(
            prefs.other.settingsLanguage,
            icon = Icons.Default.Language,
            modifier = Modifier.settingsSearchAnchor("pref__other__settings_language__label"),
            title = stringRes(R.string.pref__other__settings_language__label),
            entries = listPrefEntries {
                listOf(
                    "auto",
                    "ar",
                    "bg",
                    "bs",
                    "ca",
                    "ckb",
                    "cs",
                    "da",
                    "de",
                    "el",
                    "en",
                    "eo",
                    "es",
                    "fa",
                    "fi",
                    "fr",
                    "hr",
                    "hu",
                    "in",
                    "it",
                    "iw",
                    "ja",
                    "ko-KR",
                    "ku",
                    "lv-LV",
                    "mk",
                    "nds-DE",
                    "nl",
                    "no",
                    "pl",
                    "pt",
                    "pt-BR",
                    "ru",
                    "sk",
                    "sl",
                    "sr",
                    "sv",
                    "tr",
                    "uk",
                    "zgh",
                    "zh-CN",
                ).map { languageTag ->
                    if (languageTag == "auto") {
                        entry(
                            key = "auto",
                            label = stringRes(R.string.settings__system_default),
                        )
                    } else {
                        val displayLanguageNamesIn by prefs.localization.displayLanguageNamesIn.collectAsState()
                        val locale = FlorisLocale.fromTag(languageTag)
                        entry(locale.languageTag(), when (displayLanguageNamesIn) {
                            DisplayLanguageNamesIn.SYSTEM_LOCALE -> locale.displayName()
                            DisplayLanguageNamesIn.NATIVE_LOCALE -> locale.displayName(locale)
                        })
                    }
                }
            }
        )
        SwitchPreference(
            prefs.other.showAppIcon,
            icon = Icons.Default.Preview,
            modifier = Modifier.settingsSearchAnchor("pref__other__show_app_icon__label"),
            title = stringRes(R.string.pref__other__show_app_icon__label),
            summary = when {
                AndroidVersion.ATLEAST_API29_Q -> stringRes(R.string.pref__other__show_app_icon__summary_atleast_q)
                else -> null
            },
            enabledIf = { AndroidVersion.ATMOST_API28_P },
        )
        Preference(
            icon = ImageVector.vectorResource(R.drawable.ic_keyboard_keys),
            modifier = Modifier.settingsSearchAnchor("physical_keyboard__title"),
            title = stringRes(R.string.physical_keyboard__title),
            onClick = { navController.navigate(Routes.Settings.PhysicalKeyboard) },
        )
        // What the app made and can make again: a shared file on its way through, downloaded GIFs,
        // decoded images (issue #337). Emptied on every cold start anyway, so the row exists for the
        // hours in between — and it says how much there is, because a cleaning button that will not
        // tell you what it cleans is a button nobody trusts.
        Preference(
            icon = Icons.Default.CleaningServices,
            modifier = Modifier.settingsSearchAnchor("pref__other__clear_cache__label"),
            title = stringRes(R.string.pref__other__clear_cache__label),
            summary = stringRes(
                R.string.pref__other__clear_cache__summary,
                "size" to Formatter.formatShortFileSize(context, cacheBytes),
            ),
            onClick = { confirmClearCache = true },
        )
        // Developer tools (FlorisBoard debug overlays + debug-log export) are a development-only
        // leftover and must not ship in beta/release builds for a consumer keyboard (roadmap 11.5).
        if (BuildConfig.DEBUG) {
            Preference(
                icon = Icons.Default.Adb,
                modifier = Modifier.settingsSearchAnchor("devtools__title"),
                title = stringRes(R.string.devtools__title),
                onClick = { navController.navigate(Routes.Devtools.Home) },
            )
        }

        PreferenceGroup(title = stringRes(R.string.backup_and_restore__title)) {
            Preference(
                onClick = { navController.navigate(Routes.Settings.Backup) },
                icon = Icons.Default.Archive,
                modifier = Modifier.settingsSearchAnchor("backup_and_restore__back_up__title"),
                title = stringRes(R.string.backup_and_restore__back_up__title),
                summary = stringRes(R.string.backup_and_restore__back_up__summary),
            )
            Preference(
                onClick = { navController.navigate(Routes.Settings.Restore) },
                icon = Icons.Default.SettingsBackupRestore,
                modifier = Modifier.settingsSearchAnchor("backup_and_restore__restore__title"),
                title = stringRes(R.string.backup_and_restore__restore__title),
                summary = stringRes(R.string.backup_and_restore__restore__summary),
            )
        }

        if (confirmClearCache) {
            JetPrefAlertDialog(
                scrollModifier = florisDialogScroll(),
                title = stringRes(R.string.pref__other__clear_cache__label),
                confirmLabel = stringRes(R.string.pref__other__clear_cache__confirm),
                onConfirm = {
                    scope.launch {
                        withContext(Dispatchers.IO) { AppCache.clear(context) }
                        cacheGeneration++
                    }
                    confirmClearCache = false
                },
                dismissLabel = stringRes(android.R.string.cancel),
                onDismiss = { confirmClearCache = false },
            ) {
                // Says what survives, not only what goes: "clear" next to a keyboard that stores
                // dictation history and recordings is a word worth defusing.
                Text(stringRes(R.string.pref__other__clear_cache__message))
            }
        }
    }
}
