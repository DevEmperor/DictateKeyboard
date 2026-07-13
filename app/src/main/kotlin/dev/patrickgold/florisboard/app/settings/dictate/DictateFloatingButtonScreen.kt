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

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoSizeSelectSmall
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.settings.search.settingsSearchAnchor
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.overlay.DictateAccessibilityService
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.florisboard.dictate.DictateFloatingButtonDesign
import dev.patrickgold.florisboard.dictate.DictateFloatingButtonSize
import org.florisboard.lib.color.ColorMappings
import dev.patrickgold.jetpref.datastore.ui.ColorPickerPreference
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import dev.patrickgold.jetpref.datastore.ui.listPrefEntries
import org.florisboard.lib.compose.stringRes

/**
 * Settings for the floating dictation button (issue #88). The in-app master toggle
 * ([dev.patrickgold.florisboard.app.AppPrefs.Dictate.floatingButtonEnabled]) hides/shows the bubble; the
 * bubble only actually appears once the [DictateAccessibilityService] is also enabled in the system
 * accessibility settings, which the user reaches from here after a prominent disclosure of what the
 * service does. The service-enabled status is re-read on every resume so it reflects changes made in the
 * system settings.
 */
@Composable
fun DictateFloatingButtonScreen() = FlorisScreen {
    title = stringRes(R.string.dictate__floating_button_title)
    previewFieldVisible = true
    iconSpaceReserved = true

    val prefs by FlorisPreferenceStore

    content {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val enabled by prefs.dictate.floatingButtonEnabled.collectAsState()

        // Opening this screen clears the "New" badge on the Dictate settings entry.
        LaunchedEffect(Unit) { prefs.dictate.floatingButtonHintSeen.set(true) }

        var serviceEnabled by remember { mutableStateOf(isOverlayServiceEnabled(context)) }
        var micGranted by remember { mutableStateOf(isMicGranted(context)) }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    serviceEnabled = isOverlayServiceEnabled(context)
                    micGranted = isMicGranted(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val requestMic = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> micGranted = granted }

        var showDisclosure by remember { mutableStateOf(false) }

        SwitchPreference(
            prefs.dictate.floatingButtonEnabled,
            icon = Icons.Default.Adjust,
            modifier = Modifier.settingsSearchAnchor("dictate__floating_button_enable_title"),
            title = stringRes(R.string.dictate__floating_button_enable_title),
            summary = stringRes(R.string.dictate__floating_button_enable_summary),
        )

        if (enabled) {
            // Setup first, right under the master toggle: the accessibility service (and mic) must be on
            // before any of the display/behavior options matter, so those are only shown once it is enabled.
            PreferenceGroup(title = stringRes(R.string.dictate__floating_button_permission_group)) {
                Preference(
                    icon = Icons.Default.Accessibility,
                    modifier = Modifier.settingsSearchAnchor("dictate__floating_button_service_title"),
                    title = stringRes(R.string.dictate__floating_button_service_title),
                    summary = if (serviceEnabled) {
                        stringRes(R.string.dictate__floating_button_service_enabled)
                    } else {
                        stringRes(R.string.dictate__floating_button_service_disabled)
                    },
                    onClick = {
                        if (serviceEnabled) {
                            openAccessibilitySettings(context)
                        } else {
                            showDisclosure = true
                        }
                    },
                )
                // The bubble records through the same pipeline as the keyboard, so it needs the mic
                // permission. Usually already granted via the keyboard onboarding, but surface it here in
                // case the user enabled the bubble without ever recording.
                if (!micGranted) {
                    Preference(
                        icon = Icons.Default.Mic,
                        modifier = Modifier.settingsSearchAnchor("dictate__floating_button_mic_title"),
                        title = stringRes(R.string.dictate__floating_button_mic_title),
                        summary = stringRes(R.string.dictate__floating_button_mic_summary),
                        onClick = { requestMic.launch(Manifest.permission.RECORD_AUDIO) },
                    )
                }
            }
        }

        if (enabled && serviceEnabled) {
            SwitchPreference(
                prefs.dictate.floatingButtonShowWithDictateKeyboard,
                icon = Icons.Default.Keyboard,
                modifier = Modifier.settingsSearchAnchor("dictate__floating_button_show_with_keyboard_title"),
                title = stringRes(R.string.dictate__floating_button_show_with_keyboard_title),
                summaryOn = stringRes(R.string.dictate__floating_button_show_with_keyboard_summary_on),
                summaryOff = stringRes(R.string.dictate__floating_button_show_with_keyboard_summary_off),
            )

            ListPreference(
                prefs.dictate.floatingButtonDesign,
                icon = Icons.Default.Brush,
                modifier = Modifier.settingsSearchAnchor("dictate__floating_button_design_title"),
                title = stringRes(R.string.dictate__floating_button_design_title),
                entries = listPrefEntries {
                    entry(
                        DictateFloatingButtonDesign.PILL,
                        stringRes(R.string.dictate__floating_button_design_pill),
                        stringRes(R.string.dictate__floating_button_design_pill_summary),
                    )
                    entry(
                        DictateFloatingButtonDesign.RING,
                        stringRes(R.string.dictate__floating_button_design_ring),
                        stringRes(R.string.dictate__floating_button_design_ring_summary),
                    )
                    entry(
                        DictateFloatingButtonDesign.ORB,
                        stringRes(R.string.dictate__floating_button_design_orb),
                        stringRes(R.string.dictate__floating_button_design_orb_summary),
                    )
                },
            )

            ListPreference(
                prefs.dictate.floatingButtonSize,
                icon = Icons.Default.PhotoSizeSelectSmall,
                modifier = Modifier.settingsSearchAnchor("dictate__floating_button_size_title"),
                title = stringRes(R.string.dictate__floating_button_size_title),
                entries = listPrefEntries {
                    entry(
                        DictateFloatingButtonSize.SMALL,
                        stringRes(R.string.dictate__floating_button_size_small),
                    )
                    entry(
                        DictateFloatingButtonSize.MEDIUM,
                        stringRes(R.string.dictate__floating_button_size_medium),
                    )
                    entry(
                        DictateFloatingButtonSize.LARGE,
                        stringRes(R.string.dictate__floating_button_size_large),
                    )
                },
            )

            SwitchPreference(
                prefs.dictate.floatingButtonSnapToEdge,
                icon = Icons.Default.PushPin,
                modifier = Modifier.settingsSearchAnchor("dictate__floating_button_snap_title"),
                title = stringRes(R.string.dictate__floating_button_snap_title),
                summaryOn = stringRes(R.string.dictate__floating_button_snap_summary_on),
                summaryOff = stringRes(R.string.dictate__floating_button_snap_summary_off),
            )

            ColorPickerPreference(
                prefs.dictate.floatingButtonColor,
                icon = Icons.Default.ColorLens,
                modifier = Modifier.settingsSearchAnchor("dictate__floating_button_color_title"),
                title = stringRes(R.string.dictate__floating_button_color_title),
                defaultValueLabel = stringRes(R.string.action__default),
                showAlphaSlider = false,
                defaultColors = ColorMappings.colors,
                // Enable the full RGB/HSV picker (not just the preset palette), matching the accent color.
                enableAdvancedLayout = true,
            )

            SwitchPreference(
                prefs.dictate.floatingButtonAutoDim,
                icon = Icons.Default.BlurOn,
                modifier = Modifier.settingsSearchAnchor("dictate__floating_button_auto_dim_title"),
                title = stringRes(R.string.dictate__floating_button_auto_dim_title),
                summaryOn = stringRes(R.string.dictate__floating_button_auto_dim_summary_on),
                summaryOff = stringRes(R.string.dictate__floating_button_auto_dim_summary_off),
            )

            SwitchPreference(
                prefs.dictate.floatingButtonRememberPosition,
                icon = Icons.Default.PinDrop,
                modifier = Modifier.settingsSearchAnchor("dictate__floating_button_remember_position_title"),
                title = stringRes(R.string.dictate__floating_button_remember_position_title),
                summaryOn = stringRes(R.string.dictate__floating_button_remember_position_summary_on),
                summaryOff = stringRes(R.string.dictate__floating_button_remember_position_summary_off),
            )

            SwitchPreference(
                prefs.dictate.floatingButtonHaptic,
                icon = Icons.Default.Vibration,
                modifier = Modifier.settingsSearchAnchor("dictate__floating_button_haptic_title"),
                title = stringRes(R.string.dictate__floating_button_haptic_title),
                summaryOn = stringRes(R.string.dictate__floating_button_haptic_summary_on),
                summaryOff = stringRes(R.string.dictate__floating_button_haptic_summary_off),
            )

            SwitchPreference(
                prefs.dictate.floatingButtonUndoEnabled,
                icon = Icons.AutoMirrored.Filled.Undo,
                modifier = Modifier.settingsSearchAnchor("dictate__floating_button_undo_title"),
                title = stringRes(R.string.dictate__floating_button_undo_title),
                summaryOn = stringRes(R.string.dictate__floating_button_undo_summary_on),
                summaryOff = stringRes(R.string.dictate__floating_button_undo_summary_off),
            )
        }

        if (showDisclosure) {
            AlertDialog(
                onDismissRequest = { showDisclosure = false },
                title = { Text(stringRes(R.string.dictate__floating_button_disclosure_title)) },
                text = { Text(stringRes(R.string.dictate__floating_button_disclosure_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        showDisclosure = false
                        openAccessibilitySettings(context)
                    }) {
                        Text(stringRes(R.string.dictate__floating_button_disclosure_continue))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDisclosure = false }) {
                        Text(stringRes(android.R.string.cancel))
                    }
                },
            )
        }
    }
}

/** Whether the Dictate accessibility service is currently enabled in the system accessibility settings. */
internal fun isOverlayServiceEnabled(context: Context): Boolean {
    val flattened = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    // ComponentName picks up the running package (incl. the .debug suffix) automatically.
    val component = ComponentName(context, DictateAccessibilityService::class.java).flattenToString()
    return flattened.split(':').any { it.equals(component, ignoreCase = true) }
}

private fun isMicGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

internal fun openAccessibilitySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
