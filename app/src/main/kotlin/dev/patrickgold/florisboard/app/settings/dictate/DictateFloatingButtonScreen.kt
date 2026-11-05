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
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoSizeSelectSmall
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.settings.search.settingsSearchAnchor
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.dictate.DictateFloatingButtonAppScope
import dev.patrickgold.florisboard.dictate.overlay.BubbleMenuAction
import dev.patrickgold.florisboard.dictate.overlay.BubbleSideButton
import dev.patrickgold.florisboard.dictate.overlay.DictateAccessibilityService
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.PreferenceData
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import org.florisboard.lib.compose.rippleClickable
import dev.patrickgold.florisboard.dictate.DictateFloatingButtonDesign
import dev.patrickgold.florisboard.dictate.DictateFloatingButtonShowWhen
import dev.patrickgold.florisboard.dictate.DictateFloatingButtonSize
import kotlinx.coroutines.launch
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
        val navController = LocalNavController.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val enabled by prefs.dictate.floatingButtonEnabled.collectAsState()
        val appScope by prefs.dictate.floatingButtonAppScope.collectAsState()
        val apps by prefs.dictate.floatingButtonApps.collectAsState()
        val appCount = apps.packages.size

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
        var showMenuDialog by remember { mutableStateOf(false) }
        var showSideButtonsDialog by remember { mutableStateOf(false) }

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
            // When it appears (issue #439), one choice where there used to be two switches about keyboards.
            ListPreference(
                prefs.dictate.floatingButtonShowWhen,
                icon = Icons.Default.Keyboard,
                modifier = Modifier.settingsSearchAnchor("dictate__floating_button_show_when_title"),
                title = stringRes(R.string.dictate__floating_button_show_when_title),
                entries = listPrefEntries {
                    entry(
                        DictateFloatingButtonShowWhen.FIELD_SELECTED,
                        stringRes(R.string.dictate__floating_button_show_when_field),
                    )
                    entry(
                        DictateFloatingButtonShowWhen.KEYBOARD_OPEN,
                        stringRes(R.string.dictate__floating_button_show_when_keyboard),
                    )
                    entry(
                        DictateFloatingButtonShowWhen.ALSO_WITH_DICTATE_KEYBOARD,
                        stringRes(R.string.dictate__floating_button_show_when_dictate_keyboard),
                    )
                },
            )

            // Where it may appear at all (issue #392), directly under the other "where" switch: a
            // banking app that objects to overlays, or an app that already has a microphone of its own.
            Preference(
                icon = Icons.Default.Apps,
                modifier = Modifier.settingsSearchAnchor("dictate__floating_button_apps_title"),
                title = stringRes(R.string.dictate__floating_button_apps_title),
                summary = when (appScope) {
                    DictateFloatingButtonAppScope.ALL ->
                        stringRes(R.string.dictate__floating_button_apps_summary_all)
                    DictateFloatingButtonAppScope.ONLY_SELECTED ->
                        stringRes(R.string.dictate__floating_button_apps_summary_only, "n" to appCount)
                    DictateFloatingButtonAppScope.EXCEPT_SELECTED ->
                        stringRes(R.string.dictate__floating_button_apps_summary_except, "n" to appCount)
                },
                onClick = { navController.navigate(Routes.Settings.DictateFloatingButtonApps) },
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
                    entry(
                        DictateFloatingButtonDesign.CLOUD,
                        stringRes(R.string.dictate__floating_button_design_cloud),
                        stringRes(R.string.dictate__floating_button_design_cloud_summary),
                    )
                    entry(
                        DictateFloatingButtonDesign.AURORA,
                        stringRes(R.string.dictate__floating_button_design_aurora),
                        stringRes(R.string.dictate__floating_button_design_aurora_summary),
                    )
                    entry(
                        DictateFloatingButtonDesign.LATTICE,
                        stringRes(R.string.dictate__floating_button_design_lattice),
                        stringRes(R.string.dictate__floating_button_design_lattice_summary),
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

            // The small buttons beside the bubble — cancel and pause while it records, undo after it — as one
            // list, the way the hold menu below is one. They used to be a switch for undo and nothing at all
            // for the other two. Vibration on a tap is not here any more: it follows Sounds & Vibration.
            val sideButtons = BubbleSideButton.entries.filter { it.pref(prefs).collectAsState().value }
            Preference(
                icon = Icons.Default.SmartButton,
                modifier = Modifier.settingsSearchAnchor("dictate__floating_button_side_buttons_title"),
                title = stringRes(R.string.dictate__floating_button_side_buttons_title),
                summary = if (sideButtons.isEmpty()) {
                    stringRes(R.string.quick_action__noop)
                } else {
                    sideButtons.map { stringRes(it.labelRes) }.joinToString()
                },
                onClick = { showSideButtonsDialog = true },
            )

            SwitchPreference(
                prefs.dictate.floatingButtonCopyToClipboard,
                icon = Icons.Default.ContentCopy,
                modifier = Modifier.settingsSearchAnchor("dictate__floating_button_copy_to_clipboard_title"),
                title = stringRes(R.string.dictate__floating_button_copy_to_clipboard_title),
                summaryOn = stringRes(R.string.dictate__floating_button_copy_to_clipboard_summary_on),
                summaryOff = stringRes(R.string.dictate__floating_button_copy_to_clipboard_summary_off),
            )

            // What holding the button offers besides the prompts (issue #408). The hold is the only free
            // gesture the bubble has (#357), so this menu is where anything that is not the microphone
            // lives — each entry off by default, since for a Gboard + bubble user this menu is their only
            // route to the prompts and nothing should join it unasked. One row rather than a switch each:
            // these are the contents of a single list, and the summary is the list as it stands.
            val menuActions = BubbleMenuAction.entries.filter { it.pref(prefs).collectAsState().value }
            Preference(
                icon = Icons.Default.TouchApp,
                modifier = Modifier.settingsSearchAnchor("dictate__floating_button_menu_group"),
                title = stringRes(R.string.dictate__floating_button_menu_group),
                summary = if (menuActions.isEmpty()) {
                    stringRes(R.string.quick_action__noop)
                } else {
                    menuActions.map { stringRes(it.labelRes) }.joinToString()
                },
                onClick = { showMenuDialog = true },
            )
        }

        if (showMenuDialog) {
            CheckboxListDialog(
                title = stringRes(R.string.dictate__floating_button_menu_group),
                rows = BubbleMenuAction.entries.map { CheckboxRow(it.iconRes, it.labelRes, it.pref(prefs)) },
                onDismiss = { showMenuDialog = false },
            )
        }

        if (showSideButtonsDialog) {
            CheckboxListDialog(
                title = stringRes(R.string.dictate__floating_button_side_buttons_title),
                rows = BubbleSideButton.entries.map { CheckboxRow(it.iconRes, it.labelRes, it.pref(prefs)) },
                onDismiss = { showSideButtonsDialog = false },
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

/** One line of a [CheckboxListDialog]: what it is, and the switch its box ticks. */
private class CheckboxRow(
    @DrawableRes val iconRes: Int,
    @StringRes val labelRes: Int,
    val pref: PreferenceData<Boolean>,
)

/**
 * A list of independent switches in one dialog — the hold menu's entries, the buttons beside the bubble.
 *
 * Rows of our own rather than JetPrefListItem: that one paints itself in the theme's `surface`, which inside
 * a dialog (a lighter container) shows up as a black block behind the list. Ticking takes effect at once,
 * as it does in the app-visibility list — there is nothing here that a second confirmation could protect.
 */
@Composable
private fun CheckboxListDialog(title: String, rows: List<CheckboxRow>, onDismiss: () -> Unit) {
    JetPrefAlertDialog(
        title = title,
        confirmLabel = stringRes(R.string.action__ok),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        val scope = rememberCoroutineScope()
        Column {
            rows.forEach { row ->
                val checked by row.pref.collectAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .rippleClickable { scope.launch { row.pref.set(!checked) } }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(row.iconRes),
                        contentDescription = null,
                    )
                    Text(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 16.dp),
                        text = stringRes(row.labelRes),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Checkbox(checked = checked, onCheckedChange = null)
                }
            }
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
