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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Segment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import kotlin.math.roundToInt
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import dev.patrickgold.florisboard.dictate.DictateLongformMode
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.dictate.DictateLanguages
import dev.patrickgold.florisboard.dictate.DictateLegacyLayout
import dev.patrickgold.florisboard.dictate.audio.DictateAudioSource
import dev.patrickgold.florisboard.dictate.data.prompts.DictatePromptDefaults
import dev.patrickgold.florisboard.dictate.provider.ProviderAccounts
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import org.florisboard.lib.compose.stringRes
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.DialogSliderPreference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.listPrefEntries
import kotlinx.coroutines.launch

@Composable
fun DictateScreen() = FlorisScreen {
    title = stringRes(R.string.dictate__title)
    previewFieldVisible = true
    iconSpaceReserved = true

    val prefs by FlorisPreferenceStore

    content {
        val navController = LocalNavController.current

        // The active providers' display names, for the summary of the providers row. Show both the
        // transcription and (when rewording is on) the rewording provider, since they are configured
        // independently and often differ — the row previously surfaced only the transcription one.
        val transcriptionProviderId by prefs.dictate.transcriptionProviderId.collectAsState()
        val rewordingProviderId by prefs.dictate.rewordingProviderId.collectAsState()
        val rewordingEnabled by prefs.dictate.rewordingEnabled.collectAsState()
        val accounts by prefs.dictate.providerAccounts.collectAsState()
        val transcriptionName = remember(transcriptionProviderId, accounts) {
            providerDisplayName(transcriptionProviderId, accounts)
        }
        val rewordingName = remember(rewordingProviderId, accounts) {
            providerDisplayName(rewordingProviderId, accounts)
        }

        Preference(
            icon = Icons.Default.Insights,
            title = stringRes(R.string.dictate__stats_title),
            summary = stringRes(R.string.dictate__stats_menu_summary),
            onClick = { navController.navigate(Routes.Settings.DictateStats) },
        )
        Preference(
            icon = Icons.Default.History,
            title = stringRes(R.string.dictate__history_title),
            summary = stringRes(R.string.dictate__history_menu_summary),
            onClick = { navController.navigate(Routes.Settings.DictateHistory) },
        )

        // Hub: each row opens a dedicated sub-screen (issue #153), keeping this landing page short and
        // scannable instead of one long list of every setting.
        Preference(
            icon = Icons.Default.Cloud,
            title = stringRes(R.string.dictate__providers_title),
            summary = if (rewordingEnabled && transcriptionName != rewordingName) {
                stringRes(
                    R.string.dictate__providers_summary_both,
                    "transcription" to transcriptionName,
                    "rewording" to rewordingName,
                )
            } else {
                stringRes(R.string.dictate__providers_summary, "provider" to transcriptionName)
            },
            onClick = { navController.navigate(Routes.Settings.DictateProviders) },
        )

        val selectionRaw by prefs.dictate.inputLanguages.collectAsState()
        val detectLabel = stringRes(R.string.dictate__language_detect)
        val languagesSummary = remember(selectionRaw, detectLabel) {
            DictateLanguages.parseSelection(selectionRaw).joinToString(", ") {
                if (it.code == DictateLanguages.DETECT) detectLabel else it.displayName()
            }
        }
        Preference(
            icon = Icons.Default.Translate,
            title = stringRes(R.string.dictate__languages_title),
            summary = languagesSummary,
            onClick = { navController.navigate(Routes.Settings.DictateLanguages) },
        )

        Preference(
            icon = Icons.Default.Spellcheck,
            title = stringRes(R.string.dictate__formatting_title),
            summary = stringRes(R.string.dictate__formatting_menu_summary),
            onClick = { navController.navigate(Routes.Settings.DictateFormatting) },
        )

        Preference(
            icon = Icons.Default.AutoAwesome,
            title = stringRes(R.string.dictate__rewording_title),
            summary = stringRes(
                if (rewordingEnabled) R.string.dictate__rewording_summary_on
                else R.string.dictate__rewording_summary_off,
            ),
            onClick = { navController.navigate(Routes.Settings.DictateRewording) },
        )

        Preference(
            icon = Icons.Default.Mic,
            title = stringRes(R.string.dictate__recording_group),
            summary = stringRes(R.string.dictate__recording_menu_summary),
            onClick = { navController.navigate(Routes.Settings.DictateRecording) },
        )

        Preference(
            icon = Icons.Default.Keyboard,
            title = stringRes(R.string.dictate__output_group),
            summary = stringRes(R.string.dictate__output_menu_summary),
            onClick = { navController.navigate(Routes.Settings.DictateOutput) },
        )

        // Floating button keeps its own screen and the "New" badge until first opened (issue #88).
        val floatingHintSeen by prefs.dictate.floatingButtonHintSeen.collectAsState()
        Preference(
            icon = Icons.Default.Adjust,
            title = stringRes(R.string.dictate__floating_button_enable_title),
            summary = stringRes(R.string.dictate__floating_button_enable_summary),
            onClick = { navController.navigate(Routes.Settings.DictateFloatingButton) },
            trailing = if (!floatingHintSeen) {
                { NewBadge() }
            } else {
                null
            },
        )

        Preference(
            icon = Icons.Default.Watch,
            title = stringRes(R.string.dictate__wear_title),
            summary = stringRes(R.string.dictate__wear_summary),
            onClick = { navController.navigate(Routes.Settings.DictateWear) },
        )
    }
}

/**
 * Sub-screen (issue #153): input formatting & vocabulary — the style prompt, custom words appended to the
 * transcription prompt, and the deterministic find-and-replace mappings.
 */
@Composable
fun DictateFormattingScreen() = FlorisScreen {
    title = stringRes(R.string.dictate__formatting_title)
    previewFieldVisible = true
    iconSpaceReserved = true

    val prefs by FlorisPreferenceStore

    content {
        val navController = LocalNavController.current
        val styleSelection by prefs.dictate.stylePromptSelection.collectAsState()
        val activeLang by prefs.dictate.activeInputLanguage.collectAsState()
        PromptSelectionPreference(
            pref = prefs.dictate.stylePromptSelection,
            icon = Icons.Default.Spellcheck,
            title = stringRes(R.string.dictate__style_prompt_title),
            entries = promptSelectionEntries(),
            infoTitle = stringRes(R.string.dictate__style_prompt_info_title),
            infoDescription = stringRes(R.string.dictate__style_prompt_info_description),
            infoPromptText = DictatePromptDefaults.punctuationPromptFor(activeLang),
        )
        if (styleSelection == DictatePromptDefaults.SELECTION_CUSTOM) {
            TextInputPreference(
                pref = prefs.dictate.stylePromptCustom,
                icon = Icons.Default.Edit,
                title = stringRes(R.string.dictate__style_prompt_custom_title),
                placeholder = stringRes(R.string.dictate__style_prompt_custom_placeholder),
                multiline = true,
            )
        }
        TextInputPreference(
            pref = prefs.dictate.customWords,
            icon = Icons.Default.MenuBook,
            title = stringRes(R.string.dictate__custom_words_title),
            placeholder = stringRes(R.string.dictate__custom_words_placeholder),
            multiline = true,
            notSetSummary = stringRes(R.string.dictate__custom_words_summary_empty),
        )
        Preference(
            icon = Icons.Default.SwapHoriz,
            title = stringRes(R.string.dictate__mappings_title),
            summary = stringRes(R.string.dictate__mappings_entry_summary),
            onClick = { navController.navigate(Routes.Settings.DictateMappings) },
        )
    }
}

/**
 * Sub-screen (issue #153): recording & audio — real-time streaming, audio input source/focus, bluetooth
 * mic, keep-awake, skip-silent, and instant recording.
 */
@Composable
fun DictateRecordingScreen() = FlorisScreen {
    title = stringRes(R.string.dictate__recording_group)
    previewFieldVisible = true
    iconSpaceReserved = true

    val prefs by FlorisPreferenceStore

    content {
        SwitchPreference(
            prefs.dictate.realtimeTranscription,
            icon = Icons.Default.GraphicEq,
            title = stringRes(R.string.dictate__realtime_title),
            summary = stringRes(R.string.dictate__realtime_summary),
        )
        // All long-form settings live behind one entry that opens a single dialog (#170).
        val longformMode by prefs.dictate.longformMode.collectAsState()
        val longformSeconds by prefs.dictate.longformAutoSplitSeconds.collectAsState()
        var showLongformDialog by remember { mutableStateOf(false) }
        Preference(
            icon = Icons.Default.Segment,
            title = stringRes(R.string.dictate__longform_title),
            summary = longformModeSummary(longformMode, longformSeconds),
            onClick = { showLongformDialog = true },
        )
        if (showLongformDialog) {
            LongformDialog(
                mode = longformMode,
                seconds = longformSeconds,
                onDismiss = { showLongformDialog = false },
            )
        }
        SwitchPreference(
            prefs.dictate.audioFocus,
            icon = Icons.Default.VolumeOff,
            title = stringRes(R.string.dictate__audio_focus_title),
            summary = stringRes(R.string.dictate__audio_focus_summary),
        )
        SwitchPreference(
            prefs.dictate.useBluetoothMic,
            icon = Icons.Default.Bluetooth,
            title = stringRes(R.string.dictate__bluetooth_mic_title),
            summary = stringRes(R.string.dictate__bluetooth_mic_summary),
        )
        ListPreference(
            prefs.dictate.audioInputSource,
            icon = Icons.Default.GraphicEq,
            title = stringRes(R.string.dictate__audio_source_title),
            entries = listPrefEntries {
                entry(
                    DictateAudioSource.DEFAULT,
                    stringRes(R.string.dictate__audio_source_default),
                    stringRes(R.string.dictate__audio_source_default_summary),
                )
                entry(
                    DictateAudioSource.VOICE_RECOGNITION,
                    stringRes(R.string.dictate__audio_source_voice_recognition),
                    stringRes(R.string.dictate__audio_source_voice_recognition_summary),
                )
                entry(
                    DictateAudioSource.UNPROCESSED,
                    stringRes(R.string.dictate__audio_source_unprocessed),
                    stringRes(R.string.dictate__audio_source_unprocessed_summary),
                )
                entry(
                    DictateAudioSource.VOICE_COMMUNICATION,
                    stringRes(R.string.dictate__audio_source_voice_communication),
                    stringRes(R.string.dictate__audio_source_voice_communication_summary),
                )
            },
        )
        SwitchPreference(
            prefs.dictate.keepScreenAwake,
            icon = Icons.Default.BrightnessHigh,
            title = stringRes(R.string.dictate__keep_screen_awake_title),
            summary = stringRes(R.string.dictate__keep_screen_awake_summary),
        )
        SwitchPreference(
            prefs.dictate.skipSilentRecordings,
            icon = Icons.Default.VolumeOff,
            title = stringRes(R.string.dictate__skip_silent_title),
            summary = stringRes(R.string.dictate__skip_silent_summary),
        )
        SwitchPreference(
            prefs.dictate.instantRecording,
            icon = Icons.Default.Bolt,
            title = stringRes(R.string.dictate__instant_recording_title),
            summary = stringRes(R.string.dictate__instant_recording_summary),
        )
        SwitchPreference(
            prefs.dictate.instantRecordingSkipNumeric,
            icon = Icons.Default.Dialpad,
            title = stringRes(R.string.dictate__instant_recording_skip_numeric_title),
            summary = stringRes(R.string.dictate__instant_recording_skip_numeric_summary),
            enabledIf = { prefs.dictate.instantRecording.isTrue() },
        )
        // Inform the user once, when they switch instant recording on, that it disables the
        // interrupted-recording recovery (mutually exclusive — issue #120).
        val instantRecordingEnabled by prefs.dictate.instantRecording.collectAsState()
        var showInstantRecordingInfo by remember { mutableStateOf(false) }
        var prevInstantRecording by remember { mutableStateOf(instantRecordingEnabled) }
        LaunchedEffect(instantRecordingEnabled) {
            if (instantRecordingEnabled && !prevInstantRecording) {
                showInstantRecordingInfo = true
            }
            prevInstantRecording = instantRecordingEnabled
        }
        if (showInstantRecordingInfo) {
            AlertDialog(
                onDismissRequest = { showInstantRecordingInfo = false },
                title = { Text(stringRes(R.string.dictate__instant_recording_info_title)) },
                text = { Text(stringRes(R.string.dictate__instant_recording_info_message)) },
                confirmButton = {
                    TextButton(onClick = { showInstantRecordingInfo = false }) {
                        Text(stringRes(R.string.action__ok))
                    }
                },
            )
        }
    }
}

/**
 * Sub-screen (issue #153): output & insertion — how the finished text is written (instant vs typed),
 * auto-enter, the resend button, and remembering the last dictation.
 */
@Composable
fun DictateOutputScreen() = FlorisScreen {
    title = stringRes(R.string.dictate__output_group)
    previewFieldVisible = true
    iconSpaceReserved = true

    val prefs by FlorisPreferenceStore

    content {
        // Classic keyboard-less dictation layout (issue #125): a compact record-first UI, optionally
        // with a swipe back to the modern typing keyboard.
        ListPreference(
            prefs.dictate.legacyLayout,
            icon = Icons.Default.Dialpad,
            title = stringRes(R.string.dictate__legacy_layout_title),
            entries = listPrefEntries {
                entry(
                    key = DictateLegacyLayout.OFF,
                    label = stringRes(R.string.dictate__legacy_layout_off_label),
                )
                entry(
                    key = DictateLegacyLayout.LOCKED,
                    label = stringRes(R.string.dictate__legacy_layout_locked_label),
                )
                entry(
                    key = DictateLegacyLayout.SWIPE,
                    label = stringRes(R.string.dictate__legacy_layout_swipe_label),
                )
            },
        )
        SwitchPreference(
            prefs.dictate.autoEnter,
            icon = Icons.AutoMirrored.Filled.KeyboardReturn,
            title = stringRes(R.string.dictate__auto_enter_title),
            summary = stringRes(R.string.dictate__auto_enter_summary),
        )
        SwitchPreference(
            prefs.dictate.instantOutput,
            icon = Icons.Default.Keyboard,
            title = stringRes(R.string.dictate__instant_output_title),
            summary = stringRes(R.string.dictate__instant_output_summary),
        )
        DialogSliderPreference(
            prefs.dictate.outputSpeed,
            icon = Icons.Default.Speed,
            title = stringRes(R.string.dictate__output_speed_title),
            valueLabel = { stringRes(R.string.dictate__output_speed_value, "v" to it) },
            min = 1,
            max = 10,
            stepIncrement = 1,
            enabledIf = { prefs.dictate.instantOutput isEqualTo false },
        )
        SwitchPreference(
            prefs.dictate.resendButton,
            icon = Icons.Default.Replay,
            title = stringRes(R.string.dictate__resend_button_title),
            summary = stringRes(R.string.dictate__resend_button_summary),
        )
        SwitchPreference(
            prefs.dictate.hapticFeedback,
            icon = Icons.Default.Vibration,
            title = stringRes(R.string.dictate__haptic_feedback_title),
            summary = stringRes(R.string.dictate__haptic_feedback_summary),
        )
        SwitchPreference(
            prefs.dictate.rememberLastDictation,
            icon = Icons.Default.History,
            title = stringRes(R.string.dictate__remember_last_dictation_title),
            summary = stringRes(R.string.dictate__remember_last_dictation_summary),
        )
    }
}

/**
 * Resolves a provider id to a human-readable name for the providers-row summary: built-ins come from
 * the [ProviderRegistry], user-defined endpoints from their stored display name in the keyring, falling
 * back to the raw id if neither is available.
 */
private fun providerDisplayName(id: String, accounts: ProviderAccounts): String {
    ProviderRegistry.byId(id)?.let { return it.displayName }
    return accounts[id]?.displayName?.takeIf { it.isNotBlank() } ?: id
}

/** A small accent "New" pill used to point users at a recently added settings entry. */
@Composable
private fun NewBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringRes(R.string.dictate__floating_button_badge_new),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** One-line summary of the current long-form mode for the settings entry (issue #170). */
@Composable
private fun longformModeSummary(mode: DictateLongformMode, seconds: Int): String = when (mode) {
    DictateLongformMode.OFF -> stringRes(R.string.dictate__longform_mode_off)
    DictateLongformMode.MANUAL -> stringRes(R.string.dictate__longform_mode_manual)
    DictateLongformMode.AUTO -> stringRes(R.string.dictate__longform_autosplit_title) + " · " +
        stringRes(R.string.dictate__longform_autosplit_value, "seconds" to "$seconds")
}

/** The single dialog holding all long-form settings: the mode, plus the pause length when auto (#170). */
@Composable
private fun LongformDialog(
    mode: DictateLongformMode,
    seconds: Int,
    onDismiss: () -> Unit,
) {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    JetPrefAlertDialog(
        title = stringRes(R.string.dictate__longform_title),
        dismissLabel = stringRes(android.R.string.ok),
        onDismiss = onDismiss,
    ) {
        Column {
            LongformModeRow(
                value = DictateLongformMode.OFF, selected = mode,
                titleRes = R.string.dictate__longform_mode_off,
                summaryRes = R.string.dictate__longform_mode_off_summary,
            ) { scope.launch { prefs.dictate.longformMode.set(it) } }
            LongformModeRow(
                value = DictateLongformMode.MANUAL, selected = mode,
                titleRes = R.string.dictate__longform_mode_manual,
                summaryRes = R.string.dictate__longform_mode_manual_summary,
            ) { scope.launch { prefs.dictate.longformMode.set(it) } }
            LongformModeRow(
                value = DictateLongformMode.AUTO, selected = mode,
                titleRes = R.string.dictate__longform_autosplit_title,
                summaryRes = R.string.dictate__longform_autosplit_summary,
            ) { scope.launch { prefs.dictate.longformMode.set(it) } }
            if (mode == DictateLongformMode.AUTO) {
                var sliderValue by remember(seconds) { mutableStateOf(seconds.toFloat()) }
                Text(
                    modifier = Modifier.padding(top = 12.dp, start = 8.dp),
                    text = stringRes(R.string.dictate__longform_autosplit_threshold_title) + ": " +
                        stringRes(R.string.dictate__longform_autosplit_value, "seconds" to "${sliderValue.roundToInt()}"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = {
                        scope.launch { prefs.dictate.longformAutoSplitSeconds.set(sliderValue.roundToInt()) }
                    },
                    valueRange = 2f..8f,
                    steps = 5,
                )
            }
        }
    }
}

@Composable
private fun LongformModeRow(
    value: DictateLongformMode,
    selected: DictateLongformMode,
    titleRes: Int,
    summaryRes: Int,
    onSelect: (DictateLongformMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(value) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == selected, onClick = { onSelect(value) })
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = stringRes(titleRes), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringRes(summaryRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
