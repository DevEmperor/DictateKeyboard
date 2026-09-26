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

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.settings.search.settingsSearchAnchor
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.dictate.dictateProxyConfig
import dev.patrickgold.florisboard.dictate.provider.ConnectionCheck
import dev.patrickgold.florisboard.dictate.provider.ConnectionCheckSample
import dev.patrickgold.florisboard.dictate.provider.ConnectionCheckScope
import dev.patrickgold.florisboard.dictate.provider.DictateApiException
import dev.patrickgold.florisboard.dictate.provider.LocalModelCatalog
import dev.patrickgold.florisboard.dictate.provider.LocalModelManager
import dev.patrickgold.florisboard.dictate.provider.OpenAiCompatibleClient
import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderAccounts
import dev.patrickgold.florisboard.dictate.provider.ProviderListing
import dev.patrickgold.florisboard.dictate.provider.ProviderPreset
import dev.patrickgold.florisboard.dictate.provider.ProviderRegion
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.dictate.provider.TranscriptionApi
import dev.patrickgold.florisboard.dictate.provider.chatModelFor
import dev.patrickgold.florisboard.dictate.provider.singleCallApplies
import dev.patrickgold.florisboard.lib.compose.FlorisHyperlinkText
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.util.launchUrl
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.DialogSliderPreference
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialogDefaults
import kotlinx.coroutines.launch
import org.florisboard.lib.android.stringRes
import org.florisboard.lib.compose.florisDialogScroll
import org.florisboard.lib.compose.persistentVerticalScrollbar
import org.florisboard.lib.compose.stringRes

/**
 * What the setup wizard asked this screen to open as it appears (issue #273).
 *
 * Two of the four ways out of the provider step already have a screen here — a server of the user's own
 * and the full on-device model list — and rebuilding either inside the wizard would mean a second editor
 * to keep working. A flag rather than a route argument, for the same reason [DictateCloud.openedFromSetup]
 * is one: the route is also a deep link, and a deep link carrying an onboarding flag would be a way to
 * reach a half-state from outside the app.
 */
object ProviderSetupHandoff {
    /** Provider id whose editor to open, [ADD_CUSTOM] for a fresh endpoint, or null for nothing. */
    var openEditorFor: String? = null

    /** Sentinel for [openEditorFor]: mint a new custom endpoint instead of editing an existing provider. */
    const val ADD_CUSTOM = "+add-custom"
}

/**
 * The central "AI providers" manager: configure an API key and model(s) for any number of providers
 * (the built-in [ProviderRegistry] presets plus user-defined custom endpoints) and choose which one is
 * active for transcription and which for rewording. Each provider keeps its own credentials in the
 * keyring ([ProviderAccounts]), so switching the active provider never loses another's key.
 */
@Composable
fun DictateProvidersScreen() = FlorisScreen {
    title = stringRes(R.string.dictate__providers_title)
    previewFieldVisible = true
    iconSpaceReserved = true

    val prefs by FlorisPreferenceStore

    content {
        val navController = LocalNavController.current
        val context = LocalContext.current
        val accounts by prefs.dictate.providerAccounts.collectAsState()
        val activeTranscriptionId by prefs.dictate.transcriptionProviderId.collectAsState()
        val activeRewordingId by prefs.dictate.rewordingProviderId.collectAsState()
        val scope = rememberCoroutineScope()
        val isInstalled: (String) -> Boolean = { LocalModelManager.isInstalled(context, it) }

        // The provider currently being edited in the dialog (null = closed).
        var editingId by remember { mutableStateOf<String?>(null) }
        // Set when the editor was opened by the setup wizard to *create* an endpoint. Someone who adds a
        // server while being asked how the app should transcribe means to use it, so saving it makes it
        // active instead of leaving them to go and select it by hand.
        var activateOnSave by remember { mutableStateOf(false) }
        // Set when the wizard sent the user here to pick an on-device model from the full list, which is
        // the only situation where choosing one also switches the engine (issue #343). During setup that
        // is the whole question being asked. Later it would move a working configuration underneath
        // someone who only came to download a second model — quietly, on a screen they may not look at
        // again.
        var localFromSetup by remember { mutableStateOf(false) }

        fun writeKeyring(updated: ProviderAccounts) {
            scope.launch { prefs.dictate.providerAccounts.set(updated) }
        }

        // Arriving from the setup wizard: open the requested editor straight away, and consume the
        // request so a later visit to this screen is an ordinary one.
        LaunchedEffect(Unit) {
            when (val target = ProviderSetupHandoff.openEditorFor) {
                null -> Unit
                ProviderSetupHandoff.ADD_CUSTOM -> {
                    activateOnSave = true
                    editingId = ProviderAccount.newCustomId()
                }
                else -> {
                    editingId = target
                    localFromSetup = target == ProviderRegistry.LOCAL.id
                }
            }
            ProviderSetupHandoff.openEditorFor = null
        }

        // All custom endpoints stored in the keyring (built-ins are taken from the registry).
        val customAccounts = accounts.accounts.values
            .filter { it.isCustom }
            .sortedBy { it.displayName.lowercase() }

        PreferenceGroup(title = stringRes(R.string.dictate__providers_active_group)) {
            // Custom picker (issue #104): the transcription provider list, plus an offline-fallback
            // checkbox as an extra item at the bottom of the same dialog (hidden when the chosen
            // provider is already the on-device one, where a fallback makes no sense).
            // Both pickers offer only what can do the job right now, plus the current choice (see
            // [ProviderListing.isPickable]); everything else is set up through "Add a provider" first,
            // which the pickers link to. Offering every preset meant offering the chance to pick one
            // that could only answer "no API key" at the moment of dictating.
            TranscriptionProviderPreference(
                entries = ProviderListing.transcriptionChoices(accounts, activeTranscriptionId, isInstalled),
            )
            // When the active transcription provider runs single-call multimodal (#130), rewording happens
            // inside that one call, so the rewording provider here is currently unused — surfaced as a
            // trailing info "i" on this row (same pattern as the Punctuation/Style prompt info).
            RewordingProviderPreference(
                entries = buildList {
                    ProviderRegistry.presets
                        .filter { it.capabilities.chat }
                        .filter { ProviderListing.isPickable(it, accounts, activeRewordingId, isInstalled) }
                        .forEach { add(it.id to it.displayName) }
                    customAccounts.forEach { add(it.providerId to ProviderListing.customLabel(it)) }
                },
                showInfo = accounts.getOrEmpty(activeTranscriptionId).transcriptionViaChat,
            )
        }

        PreferenceGroup(title = stringRes(R.string.dictate__providers_manage_group)) {
            val keySet = stringRes(R.string.dictate__providers_status_key_set)
            val noKey = stringRes(R.string.dictate__providers_status_no_key)
            val activeIds = setOf(activeTranscriptionId, activeRewordingId)

            // The user's own providers only, with on-device and Dictate Cloud always among them (see
            // [ProviderListing.isListed]); the other presets wait behind "Add a provider" at the end.
            // On-device first, above the cloud providers like OpenAI (issue #228); the rest keep their
            // registry display order (sortedByDescending is stable).
            val orderedPresets = ProviderRegistry.presets
                .filter { ProviderListing.isListed(it, accounts, activeIds, isInstalled) }
                .sortedByDescending { it.transcriptionApi == TranscriptionApi.LOCAL_ONDEVICE }
            fun roles(id: String): (@Composable () -> Unit)? =
                if (id in activeIds) {
                    { ActiveRoleIcons(id, activeTranscriptionId, activeRewordingId) }
                } else {
                    null
                }
            val cloudAccount = accounts.getOrEmpty(ProviderRegistry.CLOUD.id)
            val cloudNoCredit = stringRes(R.string.dictate__cloud_row_summary_none)
            val cloudBalance = stringRes(
                R.string.dictate__cloud_row_summary_balance,
                "minutes" to (cloudAccount.balanceSeconds.coerceAtLeast(0) / 60).toString(),
            )

            orderedPresets.forEach { preset ->
                // Dictate Cloud has no API key to type in — it has a balance, packs and a recovery
                // code — so its row opens its own screen instead of the credential editor.
                if (preset.id == ProviderRegistry.CLOUD.id) {
                    Preference(
                        // The service's own mark, like every other provider in this list — a
                        // generic cloud here is what the app uses for "an endpoint with no logo".
                        icon = providerIcon(preset.id),
                        modifier = Modifier.settingsSearchAnchor("dictate__cloud_title"),
                        title = preset.displayName,
                        summary = if (cloudAccount.hasWallet) cloudBalance else cloudNoCredit,
                        trailing = roles(preset.id),
                        onClick = { navController.navigate(Routes.Settings.DictateCloud) },
                    )
                    return@forEach
                }
                val account = accounts[preset.id]
                Preference(
                    icon = providerIcon(preset.id),
                    title = preset.displayName,
                    summary = providerSummary(preset, account, noKey),
                    trailing = roles(preset.id),
                    onClick = { editingId = preset.id },
                )
            }

            customAccounts.forEach { account ->
                Preference(
                    icon = Icons.Default.Dns,
                    title = ProviderListing.customLabel(account),
                    summary = if (account.hasKey || account.customBaseUrl.isNotBlank()) {
                        account.customBaseUrl.ifBlank { keySet }
                    } else {
                        stringRes(R.string.dictate__providers_status_unconfigured)
                    },
                    trailing = roles(account.providerId),
                    onClick = { editingId = account.providerId },
                )
            }

            // Every other preset, and a server of one's own, one screen further (see
            // [DictateAddProviderScreen]).
            Preference(
                icon = Icons.Default.Add,
                modifier = Modifier.settingsSearchAnchor("dictate__providers_add"),
                title = stringRes(R.string.dictate__providers_add),
                onClick = { navController.navigate(Routes.Settings.DictateProvidersAdd) },
            )
        }

        PreferenceGroup(title = stringRes(R.string.dictate__providers_network_group)) {
            val proxyEnabled by prefs.dictate.proxyEnabled.collectAsState()
            val proxyHost by prefs.dictate.proxyHost.collectAsState()
            val proxyPort by prefs.dictate.proxyPort.collectAsState()
            val proxyOff = stringRes(R.string.dictate__proxy_summary_off)
            Preference(
                icon = Icons.Default.Lan,
                modifier = Modifier.settingsSearchAnchor("dictate__proxy_title"),
                title = stringRes(R.string.dictate__proxy_title),
                summary = if (proxyEnabled && proxyHost.isNotBlank()) {
                    "$proxyHost:$proxyPort"
                } else {
                    proxyOff
                },
                onClick = { navController.navigate(Routes.Settings.DictateProxy) },
            )
            // One number for both halves of the wait (issue #337). Two minutes is right for a cloud
            // provider and this exists for the other end of the range: a model on one's own machine
            // can think for longer than that before the first byte of the answer arrives.
            DialogSliderPreference(
                pref = prefs.dictate.requestTimeout,
                icon = Icons.Default.Timer,
                modifier = Modifier.settingsSearchAnchor("dictate__request_timeout_title"),
                title = stringRes(R.string.dictate__request_timeout_title),
                summary = { stringRes(R.string.dictate__request_timeout_summary, "v" to it) },
                valueLabel = { stringRes(R.string.unit__seconds__symbol, "v" to it) },
                min = 30,
                max = 600,
                stepIncrement = 10,
            )
        }

        editingId?.let { id ->
            val preset = ProviderRegistry.byId(id)
            ProviderEditorDialog(
                preset = preset,
                account = accounts.getOrEmpty(id),
                onDismiss = {
                    editingId = null
                    activateOnSave = false
                    localFromSetup = false
                },
                onSave = { updated, makeActive ->
                    writeKeyring(accounts.put(updated))
                    // A server of the user's own speaks both halves of the OpenAI API, and someone who
                    // added one during setup meant it to be the way the app works from now on.
                    if (activateOnSave) {
                        scope.launch {
                            prefs.dictate.transcriptionProviderId.set(id)
                            prefs.dictate.rewordingProviderId.set(id)
                        }
                    } else if (makeActive && localFromSetup) {
                        // Coming out of the setup wizard, picking an on-device model *is* answering "how
                        // should this app transcribe" — the wizard's own two recommendations already work
                        // that way, and the full list, one tap further on, used to leave the user with a
                        // downloaded model and a "no API key" error (issue #343).
                        //
                        // Only there. Anywhere else this would switch a working configuration under
                        // someone who came to try a second model, on a screen they might not look at
                        // again. Rewording is left alone in any case: the on-device engine has no chat
                        // side to offer.
                        scope.launch { prefs.dictate.transcriptionProviderId.set(id) }
                    }
                    editingId = null
                    activateOnSave = false
                    localFromSetup = false
                },
                onDelete = if (preset == null) {
                    {
                        writeKeyring(accounts.remove(id))
                        editingId = null
                        activateOnSave = false
                        localFromSetup = false
                    }
                } else {
                    null
                },
            )
        }

    }
}


@Composable
private fun RewordingProviderPreference(entries: List<Pair<String, String>>, showInfo: Boolean) {
    val prefs by FlorisPreferenceStore
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val selectedId by prefs.dictate.rewordingProviderId.collectAsState()
    var open by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }

    Preference(
        icon = Icons.Default.SmartToy,
        modifier = Modifier.settingsSearchAnchor("dictate__providers_active_rewording"),
        title = stringRes(R.string.dictate__providers_active_rewording),
        summary = entries.firstOrNull { it.first == selectedId }?.second ?: selectedId,
        // Trailing info "i" (only while single-call is active), mirroring the Punctuation/Style prompt.
        trailing = if (showInfo) {
            {
                IconButton(onClick = { infoOpen = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = stringRes(R.string.dictate__providers_rewording_single_call_note),
                    )
                }
            }
        } else {
            null
        },
        onClick = { open = true },
    )

    if (open) {
        var sel by remember { mutableStateOf(selectedId) }
        JetPrefAlertDialog(
            title = stringRes(R.string.dictate__providers_active_rewording),
            confirmLabel = stringRes(R.string.action__ok),
            dismissLabel = stringRes(R.string.action__cancel),
            onConfirm = {
                scope.launch { prefs.dictate.rewordingProviderId.set(sel) }
                open = false
            },
            onDismiss = { open = false },
        ) {
            val scrollState = rememberScrollState()
            val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .persistentVerticalScrollbar(scrollState, scrollbarColor)
                    .verticalScroll(scrollState)
                    .padding(end = 6.dp),
            ) {
                entries.forEach { (id, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { sel = id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = sel == id, onClick = { sel = id })
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                AddProviderPickerRow(onClick = {
                    open = false
                    navController.navigate(Routes.Settings.DictateProvidersAdd)
                })
            }
        }
    }

    if (infoOpen) {
        JetPrefAlertDialog(
            scrollModifier = florisDialogScroll(),
            title = stringRes(R.string.dictate__providers_active_rewording),
            confirmLabel = stringRes(R.string.action__ok),
            onConfirm = { infoOpen = false },
            onDismiss = { infoOpen = false },
        ) {
            Text(stringRes(R.string.dictate__providers_rewording_single_call_note))
        }
    }
}

/**
 * Active-transcription-provider picker (issue #104). Opens a dialog listing the transcription-capable
 * providers as radio options, with the **offline fallback** toggle as an extra checkbox item at the
 * bottom of the same dialog. The checkbox is hidden when the chosen provider is the on-device one (a
 * local fallback is meaningless there). Both the selection and the toggle are committed on confirm.
 */
@Composable
private fun TranscriptionProviderPreference(entries: List<Pair<String, String>>) {
    val prefs by FlorisPreferenceStore
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val selectedId by prefs.dictate.transcriptionProviderId.collectAsState()
    val fallbackEnabled by prefs.dictate.localFallbackEnabled.collectAsState()
    var open by remember { mutableStateOf(false) }

    Preference(
        icon = Icons.Default.Mic,
        modifier = Modifier.settingsSearchAnchor("dictate__providers_active_transcription"),
        title = stringRes(R.string.dictate__providers_active_transcription),
        summary = entries.firstOrNull { it.first == selectedId }?.second ?: selectedId,
        onClick = { open = true },
    )

    if (open) {
        var sel by remember { mutableStateOf(selectedId) }
        var fb by remember { mutableStateOf(fallbackEnabled) }
        val selectionIsLocal =
            ProviderRegistry.byId(sel)?.transcriptionApi == TranscriptionApi.LOCAL_ONDEVICE
        JetPrefAlertDialog(
            title = stringRes(R.string.dictate__providers_active_transcription),
            confirmLabel = stringRes(R.string.action__ok),
            dismissLabel = stringRes(R.string.action__cancel),
            onConfirm = {
                scope.launch {
                    prefs.dictate.transcriptionProviderId.set(sel)
                    prefs.dictate.localFallbackEnabled.set(fb)
                }
                open = false
            },
            onDismiss = { open = false },
        ) {
            val scrollState = rememberScrollState()
            val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            Column {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .persistentVerticalScrollbar(scrollState, scrollbarColor)
                        .verticalScroll(scrollState)
                        .padding(end = 6.dp),
                ) {
                    entries.forEach { (id, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { sel = id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = sel == id, onClick = { sel = id })
                            Text(label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    AddProviderPickerRow(onClick = {
                        open = false
                        navController.navigate(Routes.Settings.DictateProvidersAdd)
                    })
                }
                // Extra item at the bottom: offline fallback (only when the choice isn't already local).
                if (!selectionIsLocal) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { fb = !fb }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = fb, onCheckedChange = { fb = it })
                        Column(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
                            Text(stringRes(R.string.dictate__local_fallback_title))
                            Text(
                                text = stringRes(R.string.dictate__local_fallback_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The job icons at the end of a provider row: a microphone where it transcribes, the rewording icon where
 * it rewords — the same two icons the active-provider rows at the top of the screen carry, so the pair
 * reads as a pointer back up there. Nothing at all on a provider that is set up but not in use.
 */
@Composable
private fun ActiveRoleIcons(id: String, transcriptionId: String, rewordingId: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val tint = MaterialTheme.colorScheme.onSurfaceVariant
        if (id == transcriptionId) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = stringRes(R.string.dictate__providers_active_transcription),
                modifier = Modifier.size(20.dp),
                tint = tint,
            )
        }
        if (id == rewordingId) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = stringRes(R.string.dictate__providers_active_rewording),
                modifier = Modifier.size(20.dp),
                tint = tint,
            )
        }
    }
}

/**
 * The "Add a provider" entry at the foot of both active-provider pickers, which since those pickers stopped
 * offering unconfigured presets is the way to one that is not set up yet. Indented to line up with the
 * radio buttons above it.
 */
@Composable
private fun AddProviderPickerRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Text(stringRes(R.string.dictate__providers_add), modifier = Modifier.padding(start = 8.dp))
    }
}

/** What a provider does, for rows that say nothing more specific: transcription (and realtime), rewording. */
@Composable
internal fun providerCapabilities(preset: ProviderPreset): String = buildList {
    if (preset.capabilities.transcription) {
        // Note streaming support (issue #128) right on the transcription capability.
        val stt = stringRes(R.string.dictate__providers_cap_stt)
        add(if (preset.supportsRealtime) "$stt (+ Realtime)" else stt)
    }
    if (preset.capabilities.chat) add(stringRes(R.string.dictate__providers_cap_chat))
}.joinToString(", ")

/**
 * One-line status for a built-in provider row on the providers screen.
 *
 * Every row there is a provider the user has — or the active one, or one of the two pinned — so "key set"
 * would be said of nearly all of them and tell nobody anything. The row names the models it will use
 * instead, which is the thing people come back to look up; the missing key is still said outright, because
 * that is the row somebody has to fix. Capabilities remain the fallback for a provider with no model to name.
 */
@Composable
private fun providerSummary(
    preset: ProviderPreset,
    account: ProviderAccount?,
    noKey: String,
): String {
    if (preset.transcriptionApi == TranscriptionApi.LOCAL_ONDEVICE) {
        // On-device provider: surface the active downloaded models instead of an API-key state. There can
        // be two — a one-shot and a live/streaming one (#233) — and both are worth showing, otherwise the
        // row claims only half of what is set up.
        val context = LocalContext.current
        fun installedName(id: String?): String? = id?.takeIf { it.isNotBlank() }
            ?.let { LocalModelCatalog.byId(it) }
            ?.takeIf { LocalModelManager.isInstalled(context, it.id) }
            ?.displayName
        val names = listOfNotNull(
            installedName(account?.transcriptionModel),
            installedName(account?.realtimeModel),
        ).distinct() // a pre-split setup can have the same streaming model in both slots
        return if (names.isEmpty()) {
            stringRes(R.string.dictate__local_model_none_selected)
        } else {
            names.joinToString(" · ")
        }
    }
    val stored = account ?: ProviderAccount(providerId = preset.id)
    if (stored.requiresCredential && !stored.hasKey) return noKey
    // Resolved the way a dictation resolves them: an empty field is the preset default showing through,
    // and the rewording model follows the merged single-call field when that is on (#313).
    val models = buildList {
        if (preset.capabilities.transcription) {
            add(stored.transcriptionModel.ifBlank { preset.defaultTranscriptionModel.orEmpty() })
        }
        if (preset.capabilities.chat) add(chatModelFor(stored, preset, fallback = ""))
    }.filter { it.isNotBlank() }.distinct()
    return models.joinToString(" · ").ifBlank { providerCapabilities(preset) }
}

/**
 * Multi-field editor for a single provider. Built-in providers ([preset] != null) expose only the key
 * and the relevant model fields; custom endpoints additionally edit a display name and base URL and can
 * be deleted ([onDelete] != null). All fields are committed together on confirm.
 */
/**
 * Where an Azure Speech resource comes from, in the order someone without one needs them (#384).
 *
 * Deliberately three links and not one. [ProviderPreset.apiKeyUrl] already points at the resource
 * creation blade, which is the right target for the setup wizard's single button but assumes both an
 * account and a subscription; the reporter's frustration was about everything that has to exist before
 * a key does. The guide is Microsoft's own prerequisites page for MAI-Transcribe, which is also where
 * the region list this dialog summarises is kept current.
 *
 * None carries a locale segment, so Microsoft serves each in the reader's own language (checked
 * 2026-09-16).
 */
private const val AZURE_SIGNUP_URL = "https://azure.microsoft.com/free/"
private const val AZURE_PORTAL_URL = "https://portal.azure.com/"
private const val AZURE_GUIDE_URL = "https://learn.microsoft.com/azure/ai-services/speech-service/mai-transcribe"

@Composable
internal fun ProviderEditorDialog(
    preset: ProviderPreset?,
    account: ProviderAccount,
    onDismiss: () -> Unit,
    /**
     * [makeActive] means the user chose an on-device model in this dialog (issue #343), which is a
     * decision about who transcribes and not only about which model — the caller acts on it.
     */
    onSave: (account: ProviderAccount, makeActive: Boolean) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val isCustom = preset == null
    // A base-URL-editable built-in (e.g. Ollama, #136) also shows the base URL field, pre-filled with the
    // preset's default (localhost) so the user can point it at a LAN server.
    val allowsBaseUrl = isCustom || preset?.allowsCustomBaseUrl == true
    // Data residency (#403): where the provider publishes regional addresses, the base URL is chosen from
    // that list rather than typed. Empty for everyone who serves the world from one address.
    val regions = preset?.regions.orEmpty()
    val showTranscription = preset?.capabilities?.transcription ?: true
    val showChat = preset?.capabilities?.chat ?: true

    var displayName by remember { mutableStateOf(account.displayName) }
    var apiKey by remember { mutableStateOf(account.apiKey) }
    // Whether an on-device model was actually chosen in here, as opposed to merely looked at or deleted
    // (issue #343). Reported on confirm, because that is when the choice of model is stored too — the
    // two are one decision and must not be able to land separately.
    var chosenOnDevice by remember { mutableStateOf(false) }
    var baseUrl by remember {
        mutableStateOf(
            account.customBaseUrl.ifBlank { if (preset?.allowsCustomBaseUrl == true) preset.baseUrl else "" },
        )
    }
    // Model fields hold what the *user* chose and nothing else; the preset default appears greyed out
    // behind an empty one, which says what is running without pretending someone picked it. Filling the
    // default in as a value was the older answer to the same question, and it cost more than it gave: it
    // came back after the user cleared the field, and it made the single-call switch look broken, because
    // Gemini's default transcription model is a speech-to-text model that cannot serve rewording.
    //
    // So blank means what it has always meant in storage — follow the preset — and it now means the same
    // on screen. Nothing converts a value back to blank on confirm any more: what stands in the box is
    // what gets stored, including a deliberate pick of the model that is currently the default, which is
    // then pinned and no longer moves with an app update. That is the trade this way round, and it is the
    // one #313 asked for: what you choose is what is used.
    //
    // On-device: a streaming model stored in the one-shot slot predates the two-slot split (#233) — move
    // it across on open so the dialog shows it under "Live" where it belongs, instead of as the one-shot
    // pick it was never meant to be.
    val isLocalProvider = preset?.transcriptionApi == TranscriptionApi.LOCAL_ONDEVICE
    val legacyStreamingPick = isLocalProvider && LocalModelCatalog.isStreaming(account.transcriptionModel)
    var transcriptionModel by remember {
        mutableStateOf(
            when {
                legacyStreamingPick -> ""
                // The one field that is a list rather than a text box, so it has no placeholder to grey
                // out: blank there would read as "no model selected" instead of "the default applies".
                isLocalProvider -> account.transcriptionModel.ifBlank { preset?.defaultTranscriptionModel.orEmpty() }
                else -> account.transcriptionModel
            },
        )
    }
    var chatModel by remember { mutableStateOf(account.chatModel) }
    var realtimeModel by remember {
        mutableStateOf(if (legacyStreamingPick) account.transcriptionModel else account.realtimeModel)
    }
    var showRealtimePicker by remember { mutableStateOf(false) }
    // Live catalog cache, updated when the picker fetches; persisted together with the rest on confirm.
    var cachedModels by remember { mutableStateOf(account.cachedModels) }
    var cachedAudioModels by remember { mutableStateOf(account.cachedAudioModels) }
    var cachedTranscriptionModels by remember { mutableStateOf(account.cachedTranscriptionModels) }
    var transcriptionViaChat by remember { mutableStateOf(account.transcriptionViaChat) }
    // Self-hosted streaming (#249): whether this endpoint speaks the OpenAI realtime protocol. Nothing in
    // a catalog reveals that, so the user says so.
    var customRealtime by remember { mutableStateOf(account.customRealtime) }
    // Wake-on-demand (#189): whether this endpoint sits in front of a machine that sleeps between jobs.
    var customWarmUp by remember { mutableStateOf(account.customWarmUp) }
    var pickerKind by remember { mutableStateOf<ModelKind?>(null) }

    // Effective preset to drive the model picker / connection test. Custom endpoints get a base-URL-only
    // preset; a base-URL-editable built-in (Ollama, #136) uses the edited URL over its localhost default.
    val effectivePreset = when {
        preset == null -> ProviderRegistry.custom(baseUrl, realtime = customRealtime)
        preset.allowsCustomBaseUrl -> preset.copy(baseUrl = baseUrl.ifBlank { preset.baseUrl })
        else -> preset
    }

    // Nothing here asks what a model can do. Which fields are shown is the switch's business alone, and
    // what belongs in the merged field is the user's — the app cannot know, and a version that guessed
    // both refused to merge and said nothing about why (#313). There used to be a catalog fetch on open
    // whose only job was that guess; the picker loads the catalog itself when it is opened, so the
    // dialog no longer sends a request nobody asked for.

    JetPrefAlertDialog(
        title = preset?.displayName ?: stringRes(R.string.dictate__providers_custom_title),
        // The whole body scrolls as one — the on-device model list makes this dialog the tallest in the
        // app, and pinning the intro/checkbox/slider while only the list moved read as two panes.
        scrollModifier = florisDialogScroll(),
        // The on-device list is the one body in this dialog made of *rows* rather than full-width
        // fields — a radio, two lines of text and an action button inside roughly 280 dp — so it gets
        // four dp back at each side. A small nudge on purpose: the rows won their room from the radio
        // slot, and taking much more from here would push them out of line with every other dialog.
        // Vertical stays untouched; the default is 0 either way.
        contentPadding = if (preset?.transcriptionApi == TranscriptionApi.LOCAL_ONDEVICE) {
            PaddingValues(horizontal = 20.dp)
        } else {
            JetPrefAlertDialogDefaults.ContentPadding
        },
        // The way to the provider's key page, next to the provider's name (#410). The key field below is
        // where someone with no key gets stuck, and until now the only place in the app that said where a
        // key comes from was the setup wizard — so anyone who changed provider later, added a second one
        // for rewording, or skipped the wizard was left to guess. A reporter read OpenAI's verbatim
        // "Missing bearer authentication in header" as a broken app and asked for a refund.
        //
        // Shown on `apiKeyUrl` alone: not gated on the field being empty, because replacing an expired key
        // is the same errand, and a control that appears and disappears with the text is worse than one
        // that is simply there. Absent for Dictate Cloud, Ollama, on-device and custom endpoints, which
        // have no key page at all — that absence is the honest answer, not a gap.
        //
        // No tooltip: PlainTooltip consumes the release on the Initial pass (#257/#261), and this sits in
        // the title row of a dialog. The content description carries the label.
        trailingIconTitle = {
            preset?.apiKeyUrl?.let { url ->
                IconButton(
                    onClick = { context.launchUrl(url) },
                    modifier = Modifier.offset(x = 12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Key,
                        contentDescription = stringRes(R.string.dictate__providers_get_key),
                    )
                }
            }
        },
        confirmLabel = stringRes(R.string.action__ok),
        dismissLabel = stringRes(R.string.action__cancel),
        neutralLabel = if (onDelete != null) stringRes(R.string.action__delete) else null,
        onConfirm = {
            onSave(
                account.copy(
                    displayName = displayName.trim(),
                    apiKey = apiKey.trim(),
                    customBaseUrl = baseUrl.trim(),
                    customRealtime = customRealtime,
                    customWarmUp = customWarmUp,
                    transcriptionModel = transcriptionModel.trim(),
                    chatModel = chatModel.trim(),
                    realtimeModel = realtimeModel.trim(),
                    cachedModels = cachedModels,
                    cachedAudioModels = cachedAudioModels,
                    cachedTranscriptionModels = cachedTranscriptionModels,
                    transcriptionViaChat = transcriptionViaChat,
                    cachedModelsAt = if (cachedModels != account.cachedModels) {
                        System.currentTimeMillis()
                    } else {
                        account.cachedModelsAt
                    },
                ),
                chosenOnDevice,
            )
        },
        onDismiss = onDismiss,
        onNeutral = { onDelete?.invoke() },
    ) {
        if (preset?.transcriptionApi == TranscriptionApi.LOCAL_ONDEVICE) {
            // On-device provider: no key/remote model — manage downloadable models instead (#104).
            // Two independent picks (#233): the one-shot model lives in `transcriptionModel`, the live
            // streaming one in `realtimeModel` — which is otherwise unused for this provider and means
            // exactly that. Both stay selected at once, so choosing a live model never drops the one-shot.
            LocalModelSection(
                activeModelId = transcriptionModel,
                activeStreamingModelId = realtimeModel,
                onActiveModelChange = { transcriptionModel = it },
                onActiveStreamingModelChange = { realtimeModel = it },
                onModelChosen = { chosenOnDevice = true },
            )
        } else {
        Column {
            // Ollama is chat-only, and correctly so — it serves no /v1/audio/transcriptions. But someone
            // who already has a local host answering for rewording reasonably expects dictation to follow,
            // and the reporter of #273 read the dead end as "the app cannot do self-hosted transcription".
            // The answer is a second server, or no server at all, and it belongs where the confusion is.
            if (preset?.id == ProviderRegistry.OLLAMA.id) {
                Text(
                    text = stringRes(R.string.dictate__providers_ollama_no_stt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            // Azure is the one provider whose address is the user's own (#349): a Speech resource answers
            // on its own hostname, so the field below starts empty with the shape as its hint. The
            // regions matter as much as the URL — a resource outside the six that host MAI-Transcribe
            // answers the endpoint but not with this model, and that failure looks like a typo.
            if (preset?.id == ProviderRegistry.AZURE.id) {
                Text(
                    text = stringRes(R.string.dictate__providers_azure_endpoint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                // Azure is the one provider here where getting as far as this dialog is the easy part:
                // an account, a subscription, a Speech resource in one of the six regions, and only then
                // a key. Someone who has spent that afternoon and is told "connection failed" needs the
                // way back to Microsoft, not just a verdict — so the three pages that matter stand right
                // where the endpoint and the key are typed (#384). Locale-free URLs: Microsoft redirects
                // each of them to the reader's own language.
                ProvideTextStyle(MaterialTheme.typography.bodySmall) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(bottom = 12.dp),
                    ) {
                        FlorisHyperlinkText(
                            text = stringRes(R.string.dictate__providers_azure_link_signup),
                            url = AZURE_SIGNUP_URL,
                        )
                        FlorisHyperlinkText(
                            text = stringRes(R.string.dictate__providers_azure_link_portal),
                            url = AZURE_PORTAL_URL,
                        )
                        FlorisHyperlinkText(
                            text = stringRes(R.string.dictate__providers_azure_link_guide),
                            url = AZURE_GUIDE_URL,
                        )
                    }
                }
            }
            if (isCustom) {
                EditorField(
                    label = stringRes(R.string.dictate__providers_field_name),
                    value = displayName,
                    onValueChange = { displayName = it },
                )
            }
            // A provider with data-residency regions gets the list instead of the text box (#403). Same
            // stored field, and deliberately not both: the valid addresses are published and short, and a
            // typo in one of them does not fail — it quietly sends the audio to another continent.
            if (allowsBaseUrl && regions.isNotEmpty()) {
                RegionField(
                    regions = regions,
                    baseUrl = baseUrl,
                    onRegionChange = { baseUrl = it.baseUrl },
                )
            } else if (allowsBaseUrl) {
                EditorField(
                    label = stringRes(R.string.dictate__base_url_title),
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    // Azure's shape is unlike every other endpoint here — the resource's own name is
                    // part of the hostname — so the hint shows one rather than a generic server (#349).
                    placeholder = stringRes(
                        if (preset?.id == ProviderRegistry.AZURE.id) {
                            R.string.dictate__base_url_placeholder_azure
                        } else {
                            R.string.dictate__base_url_placeholder
                        },
                    ),
                    keyboardType = KeyboardType.Uri,
                )
            }
            EditorField(
                label = stringRes(R.string.dictate__api_key_title),
                value = apiKey,
                onValueChange = { apiKey = it },
                placeholder = stringRes(R.string.dictate__api_key_placeholder),
                isSecret = true,
            )
            // Keyed on everything a check could have been about, so that changing any of it does not
            // merely stale the verdict but removes it, together with the coroutine that was still
            // fetching one (#384). A result from the previous configuration must never be read as a
            // verdict on this one — that is half of what the reporter ran into.
            key(
                effectivePreset.id,
                effectivePreset.baseUrl,
                apiKey.trim(),
                transcriptionModel.trim(),
                transcriptionViaChat,
            ) {
                ProviderCheckSection(
                    preset = effectivePreset,
                    apiKey = apiKey,
                    transcriptionModel = transcriptionModel,
                    transcriptionViaChat = transcriptionViaChat,
                    showTranscription = showTranscription,
                )
            }
            if (showTranscription) {
                EditorField(
                    // When single-call is on, this one model does both transcription and rewording (#130),
                    // and since #313 the rewording path reads this field rather than its own. Whether the
                    // model in it can do both is the user's call: the app has no reliable way to know, and
                    // the version that tried to work it out refused to merge the fields and left the
                    // switch looking broken.
                    label = stringRes(
                        if (transcriptionViaChat) {
                            R.string.dictate__providers_field_transcription_rewording_model
                        } else {
                            R.string.dictate__providers_field_transcription_model
                        },
                    ),
                    value = transcriptionModel,
                    onValueChange = { transcriptionModel = it },
                    placeholder = preset?.defaultTranscriptionModel
                        ?: stringRes(R.string.dictate__model_placeholder),
                    onBrowse = { pickerKind = ModelKind.TRANSCRIPTION },
                )
                // Streaming runs over a different endpoint and protocol than batch STT, so it gets its own
                // field rather than being folded into the picker above (#248, based on #243). It used to be
                // hidden on the assumption that each provider has exactly one usable streaming model, which
                // stopped being true once OpenAI shipped a second generation of them — and until now the
                // stored realtimeModel had no way of ever being set.
                if (preset?.supportsRealtime == true && preset.curatedRealtimeModels.isNotEmpty()) {
                    EditorField(
                        label = stringRes(R.string.dictate__providers_field_realtime_model),
                        value = realtimeModel,
                        onValueChange = { realtimeModel = it },
                        placeholder = preset.defaultRealtimeModel
                            ?: stringRes(R.string.dictate__model_placeholder),
                        onBrowse = { showRealtimePicker = true },
                    )
                }
                // A server of the user's own can stream too (#249), if it speaks the OpenAI realtime
                // protocol under /v1/realtime — which several self-hosted transcription servers do. There
                // is no way to tell without connecting, so this is a switch rather than a guess.
                if (isCustom) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { customRealtime = !customRealtime }
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = stringRes(R.string.dictate__providers_custom_realtime),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = stringRes(R.string.dictate__providers_custom_realtime_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = customRealtime, onCheckedChange = { customRealtime = it })
                    }
                    // Optional: many self-hosted servers serve whatever model they were started with, so a
                    // blank box means "whatever you have".
                    if (customRealtime) {
                        EditorField(
                            label = stringRes(R.string.dictate__providers_field_realtime_model),
                            value = realtimeModel,
                            onValueChange = { realtimeModel = it },
                            placeholder = stringRes(R.string.dictate__model_placeholder),
                        )
                    }
                }
            }
            // Rewording model is unused while single-call multimodal is on (one model does both, #130).
            if (showChat && !transcriptionViaChat) {
                EditorField(
                    label = stringRes(R.string.dictate__providers_field_chat_model),
                    value = chatModel,
                    onValueChange = { chatModel = it },
                    placeholder = preset?.defaultChatModel
                        ?: stringRes(R.string.dictate__model_placeholder),
                    onBrowse = { pickerKind = ModelKind.CHAT },
                )
                // Wake-on-demand (#189): a GPU box that sleeps between jobs only starts waking when
                // something reaches it, so the first rewording otherwise pays for the whole boot. Offered
                // only for endpoints of the user's own — nowhere else is there a machine to wake.
                if (isCustom) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { customWarmUp = !customWarmUp }
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = stringRes(R.string.dictate__providers_custom_warm_up),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = stringRes(R.string.dictate__providers_custom_warm_up_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = customWarmUp, onCheckedChange = { customWarmUp = it })
                    }
                }
            }
            // Single-call multimodal (issue #130): kept at the bottom; when on, this one model transcribes
            // and formats in a single request and the rewording field above is folded into it. Offered for
            // any provider with a chat endpoint, which is the prerequisite for input_audio.
            //
            // It used to warn when the catalog said the chosen model accepted no audio, and the model list
            // marked the audio-capable ones. Both are gone: the classification was wrong often enough to
            // mislead, and a wrong warning about a model that works is worse than none. Picking a model
            // that can do both is the user's job here, and it is one the app cannot do for them.
            if (showTranscription && showChat) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringRes(R.string.dictate__providers_single_call_title),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringRes(R.string.dictate__providers_single_call_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = transcriptionViaChat,
                        onCheckedChange = { transcriptionViaChat = it },
                    )
                }
            }
        }
        }
    }

    pickerKind?.let { kind ->
        ModelPickerDialog(
            kind = kind,
            preset = effectivePreset,
            apiKey = apiKey,
            current = if (kind == ModelKind.TRANSCRIPTION) transcriptionModel else chatModel,
            cachedModels = cachedModels,
            cachedAudioModels = cachedAudioModels,
            cachedTranscriptionModels = cachedTranscriptionModels,
            onModelsFetched = { ids, audioIds, sttIds ->
                cachedModels = ids; cachedAudioModels = audioIds; cachedTranscriptionModels = sttIds
            },
            onPick = { picked ->
                if (kind == ModelKind.TRANSCRIPTION) transcriptionModel = picked else chatModel = picked
            },
            onDismiss = { pickerKind = null },
        )
    }

    if (showRealtimePicker && preset != null) {
        RealtimeModelPickerDialog(
            models = preset.curatedRealtimeModels,
            default = preset.defaultRealtimeModel,
            current = realtimeModel,
            onPick = { realtimeModel = it },
            onDismiss = { showRealtimePicker = false },
        )
    }
}

/**
 * Picker for a provider's curated realtime models — a short radio list rather than the searchable
 * catalogue used for batch models, because streaming models are few and never appear in `/models`.
 *
 * Always writes the chosen id into the field, including for the default, so the box never sits empty
 * while a model is in fact running. Turning that back into an empty stored value is [modelToStore]'s job
 * on confirm.
 */
@Composable
private fun RealtimeModelPickerDialog(
    models: List<String>,
    default: String?,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    JetPrefAlertDialog(
        scrollModifier = florisDialogScroll(),
        title = stringRes(R.string.dictate__providers_field_realtime_model),
        dismissLabel = stringRes(R.string.action__cancel),
        onDismiss = onDismiss,
    ) {
        Column {
            models.forEach { model ->
                val isDefault = model == default
                val pick = { onPick(model); onDismiss() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = pick)
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = current == model || (current.isBlank() && isDefault),
                        onClick = pick,
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(model, style = MaterialTheme.typography.bodyLarge)
                        if (isDefault) {
                            Text(
                                stringRes(R.string.dictate__providers_realtime_model_default),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A finished check, in the two lines the section shows: a verdict and the evidence or the way out. */
private data class CheckOutcome(val ok: Boolean, val headline: String, val detail: String?)

/**
 * The two checks under the key field, and the rule that each says only what it established (issue #384).
 *
 * There used to be one button. It counted whatever `listModels()` returned and called any number a
 * success — which for Azure, ElevenLabs and AssemblyAI is a list compiled into the app, so the reporter
 * got "Connected · 2 models" from an endpoint that does not exist, with a key nothing had looked at, and
 * went hunting for the fault somewhere else entirely. A check that cannot fail is worse than no check:
 * it spends the user's trust and answers a question they did not ask.
 *
 * Hence two actions with two different claims. **Test connection** makes one authenticated request per
 * provider ([OpenAiCompatibleClient.checkCredentials]) and reports the key and the endpoint — and says
 * in the same breath that transcription was not tested, because it was not. **Test transcription** sends
 * [ConnectionCheckSample] through the selected model and is the only one that can say dictation works.
 *
 * That it costs something is carried by the separate, named button alone. There was a sentence under the
 * two of them explaining the sample and the billing; it was three lines in a dialog that is already the
 * tallest in the app, for a fact a labelled button that nothing presses on its own already makes plain.
 * Removed on Jannis's call — the requirement was that a billed check be explicit and user-triggered, and
 * a button of its own is both.
 *
 * The whole section is keyed on the configuration by its caller, which is what makes the second half of
 * the report true: editing the key, the endpoint or the model does not merely grey the old verdict out,
 * it takes the section out of composition — the result is gone and [rememberCoroutineScope] cancels a
 * test still in flight, so an answer about the previous configuration can never land on the new one.
 */
@Composable
private fun ProviderCheckSection(
    preset: ProviderPreset,
    apiKey: String,
    transcriptionModel: String,
    transcriptionViaChat: Boolean,
    showTranscription: Boolean,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs by FlorisPreferenceStore
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CheckOutcome?>(null) }
    // Green and red, not the scheme's primary and error. A verdict is the one place in this app where
    // the colour carries meaning rather than decoration, and `primary` is the user's own accent (#387) —
    // on a blue or purple theme "passed" read as a link. Which pair applies is decided by the surface's
    // luminance rather than by the system's dark mode, because the app theme can be forced either way
    // and AMOLED is darker still.
    val onDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val okColor = if (onDark) Color(0xFF81C784) else Color(0xFF2E7D32)
    val errColor = if (onDark) Color(0xFFE57373) else Color(0xFFC62828)

    // Same rule the dictation path follows: what stands in the field, else the preset's default.
    val model = transcriptionModel.trim().ifBlank { preset.defaultTranscriptionModel.orEmpty() }
    // A provider with a key page wants a key; one without (Ollama, a server of one's own) is keyless by
    // design. The same question [ProviderAccount.isConfigured] asks, and answering it here turns a
    // confusing 401 — or ElevenLabs' "workspace 1anonymous1 not found" — into the one sentence that helps.
    val needsKey = preset.apiKeyUrl != null && apiKey.isBlank()

    fun startCheck(transcription: Boolean, check: suspend (OpenAiCompatibleClient) -> ConnectionCheck) {
        if (needsKey) {
            result = CheckOutcome(false, context.stringRes(R.string.dictate__providers_check_no_key), null)
            return
        }
        running = true
        result = null
        scope.launch {
            result = try {
                // Left on the default two minutes even when the user raised their own limit (#337): the
                // point of a test button is a quick verdict, and one that can sit there for ten minutes
                // answers a different question than the one being asked.
                val client = OpenAiCompatibleClient.from(
                    preset, apiKey.trim(),
                    baseUrlOverride = preset.baseUrl,
                    proxy = prefs.dictate.dictateProxyConfig(),
                    useChatAudio = transcriptionViaChat,
                    trustUserCerts = prefs.dictate.trustUserCertificates.get(),
                )
                checkOutcome(context, check(client))
            } catch (e: Exception) {
                failureOutcome(context, e, transcription)
            } finally {
                running = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(16.dp))
            }
            TextButton(
                enabled = !running,
                onClick = { startCheck(transcription = false) { it.checkCredentials() } },
            ) {
                Text(stringRes(R.string.dictate__providers_test))
            }
            if (showTranscription) {
                TextButton(
                    enabled = !running,
                    onClick = {
                        startCheck(transcription = true) { client ->
                            client.checkTranscription(
                                sample = ConnectionCheckSample.file(context),
                                model = model,
                                language = ConnectionCheckSample.LANGUAGE,
                            )
                        }
                    },
                ) {
                    Text(stringRes(R.string.dictate__providers_test_transcribe))
                }
            }
        }
        result?.let { outcome ->
            Text(
                text = outcome.headline,
                color = if (outcome.ok) okColor else errColor,
                style = MaterialTheme.typography.bodyMedium,
            )
            outcome.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Turns a passed check into the two lines shown, claiming its scope and nothing above it. */
private fun checkOutcome(context: Context, check: ConnectionCheck): CheckOutcome = when (check.scope) {
    ConnectionCheckScope.TRANSCRIPTION -> CheckOutcome(
        ok = true,
        headline = context.stringRes(R.string.dictate__providers_check_transcription_ok),
        // The transcript is evidence, not a score: the sample is synthesised speech and a model is free
        // to mishear it. Showing what came back lets the user judge that for themselves; an empty answer
        // is a different result and says so, because the request went through but nothing was understood.
        detail = check.transcript?.takeIf { it.isNotBlank() }
            ?.let { context.stringRes(R.string.dictate__providers_check_transcription_text, "text" to it) }
            ?: context.stringRes(R.string.dictate__providers_check_transcription_empty),
    )
    ConnectionCheckScope.CREDENTIALS -> CheckOutcome(
        ok = true,
        headline = check.liveModelCount
            ?.let { context.stringRes(R.string.dictate__providers_check_credentials_ok, "count" to it.toString()) }
            ?: context.stringRes(R.string.dictate__providers_check_credentials_ok_nocount),
        detail = context.stringRes(R.string.dictate__providers_check_credentials_untested),
    )
    // Keyless by design, so there is nothing to verify and the honest verdict is smaller, not bigger.
    ConnectionCheckScope.ENDPOINT -> CheckOutcome(
        ok = true,
        headline = context.stringRes(
            R.string.dictate__providers_check_endpoint_ok,
            "count" to (check.liveModelCount ?: 0),
        ),
        detail = context.stringRes(R.string.dictate__providers_check_credentials_untested),
    )
}

/**
 * Turns a failed check into a verdict plus the next thing to do.
 *
 * The headline names the step that failed, because the two buttons fail for overlapping reasons and the
 * user has to know which one they are reading. The detail is the app's own sentence about this kind of
 * failure followed by the provider's verbatim words — neither is enough alone: ours says what to change,
 * theirs names the model or the region that ours cannot know about.
 */
private fun failureOutcome(context: Context, e: Exception, transcription: Boolean): CheckOutcome {
    val kind = (e as? DictateApiException)?.kind ?: DictateApiException.Kind.UNKNOWN
    val advice = context.stringRes(
        when (kind) {
            DictateApiException.Kind.INVALID_API_KEY -> R.string.dictate__providers_check_err_auth
            DictateApiException.Kind.QUOTA_EXCEEDED -> R.string.dictate__providers_check_err_quota
            DictateApiException.Kind.NETWORK -> R.string.dictate__providers_check_err_network
            DictateApiException.Kind.TIMEOUT -> R.string.dictate__providers_check_err_timeout
            DictateApiException.Kind.SERVER_ERROR -> R.string.dictate__providers_check_err_server
            DictateApiException.Kind.FORMAT_NOT_SUPPORTED,
            DictateApiException.Kind.CONTENT_SIZE_LIMIT,
            -> R.string.dictate__providers_check_err_format
            else -> R.string.dictate__providers_check_err_unknown
        },
    )
    val raw = e.message?.takeIf { it.isNotBlank() && it != advice }
    return CheckOutcome(
        ok = false,
        headline = context.stringRes(
            if (transcription) {
                R.string.dictate__providers_check_failed_transcription
            } else {
                R.string.dictate__providers_check_failed_connection
            },
        ),
        detail = listOfNotNull(advice, raw).joinToString(" "),
    )
}

/**
 * A single labeled text field inside the provider editor dialog. When [onBrowse] is set, a trailing
 * button opens the model picker (the field still accepts free-text input).
 */
@Composable
private fun EditorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    isSecret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onBrowse: (() -> Unit)? = null,
) {
    // Secret fields (API keys) start masked but can be revealed with the eye toggle (issue #195).
    var reveal by remember { mutableStateOf(false) }
    OutlinedTextField(
        modifier = Modifier.padding(top = 8.dp),
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        visualTransformation = if (isSecret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isSecret) KeyboardType.Password else keyboardType,
        ),
        trailingIcon = when {
            isSecret -> {
                {
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(
                            imageVector = if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                        )
                    }
                }
            }
            onBrowse != null -> {
                {
                    IconButton(onClick = onBrowse) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringRes(R.string.dictate__model_picker_title),
                        )
                    }
                }
            }
            else -> null
        },
    )
}

/**
 * The data-residency region an account talks to (issue #403).
 *
 * A list rather than the base URL box the other editable endpoints get, because these addresses are the
 * provider's and not the user's: every valid one is published, there are three or four of them, and a
 * mistyped residency host is the one kind of address error that does not announce itself — it answers,
 * and it answers from the wrong continent.
 *
 * The chosen region is stored as the account's base URL, so nothing downstream had to learn a new field.
 * A stored URL that matches none of the regions is shown as it stands instead of being snapped onto one:
 * it was typed deliberately, before this list existed or with something else in mind.
 */
@Composable
private fun RegionField(
    regions: List<ProviderRegion>,
    baseUrl: String,
    onRegionChange: (ProviderRegion) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val wanted = baseUrl.trim().trimEnd('/')
    val selected = when {
        wanted.isEmpty() -> regions.first()
        else -> regions.firstOrNull { it.baseUrl.trimEnd('/').equals(wanted, ignoreCase = true) }
    }
    Box {
        OutlinedTextField(
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
            value = selected?.let { stringRes(regionLabelOf(it.id)) } ?: baseUrl,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringRes(R.string.dictate__providers_region_title)) },
            // The host stays in the open list and nowhere else: it is what tells two regions apart while
            // choosing, and clutter once the choice is made and the name already says it.
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        // A read-only text field still consumes the tap that was meant to open the list, so the opener is
        // a transparent layer of its own over the field. No ripple: the field is what the eye is on.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            regions.forEach { region ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringRes(regionLabelOf(region.id)))
                            Text(
                                text = hostOf(region.baseUrl),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onRegionChange(region)
                    },
                )
            }
        }
    }
}

/**
 * Translated name for a [ProviderRegion] id. Only ids from [ProviderRegistry] reach here, and the host
 * stands under the name in any case — so an id added there without a string of its own reads as the
 * worldwide entry point rather than as nothing at all.
 */
@StringRes
private fun regionLabelOf(id: String): Int = when (id) {
    "us" -> R.string.dictate__providers_region_us
    "eu" -> R.string.dictate__providers_region_eu
    "jp" -> R.string.dictate__providers_region_jp
    "in" -> R.string.dictate__providers_region_in
    else -> R.string.dictate__providers_region_global
}

/** The host of a base URL, which is the whole of what a region actually changes. */
private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/')
