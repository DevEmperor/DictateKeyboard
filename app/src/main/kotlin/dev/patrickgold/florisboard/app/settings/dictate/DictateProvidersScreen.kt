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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.settings.search.settingsSearchAnchor
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.dictate.dictateProxyConfig
import dev.patrickgold.florisboard.dictate.provider.LocalModelCatalog
import dev.patrickgold.florisboard.dictate.provider.LocalModelManager
import dev.patrickgold.florisboard.dictate.provider.OpenAiCompatibleClient
import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderAccounts
import dev.patrickgold.florisboard.dictate.provider.ProviderPreset
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.dictate.provider.TranscriptionApi
import dev.patrickgold.florisboard.dictate.provider.singleCallApplies
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import kotlinx.coroutines.launch
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
        val accounts by prefs.dictate.providerAccounts.collectAsState()
        val activeTranscriptionId by prefs.dictate.transcriptionProviderId.collectAsState()
        val scope = rememberCoroutineScope()

        // The provider currently being edited in the dialog (null = closed).
        var editingId by remember { mutableStateOf<String?>(null) }
        // Set when the editor was opened by the setup wizard to *create* an endpoint. Someone who adds a
        // server while being asked how the app should transcribe means to use it, so saving it makes it
        // active instead of leaving them to go and select it by hand.
        var activateOnSave by remember { mutableStateOf(false) }

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
                else -> editingId = target
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
            TranscriptionProviderPreference(
                entries = buildList {
                    ProviderRegistry.presets
                        .filter { it.capabilities.transcription }
                        // On-device (offline) first in the picker, above the cloud providers (issue #228).
                        .sortedByDescending { it.transcriptionApi == TranscriptionApi.LOCAL_ONDEVICE }
                        .forEach { add(it.id to it.displayName) }
                    customAccounts.forEach { add(it.providerId to customLabel(it)) }
                },
            )
            // When the active transcription provider runs single-call multimodal (#130), rewording happens
            // inside that one call, so the rewording provider here is currently unused — surfaced as a
            // trailing info "i" on this row (same pattern as the Punctuation/Style prompt info).
            RewordingProviderPreference(
                entries = buildList {
                    ProviderRegistry.presets
                        .filter { it.capabilities.chat }
                        .forEach { add(it.id to it.displayName) }
                    customAccounts.forEach { add(it.providerId to customLabel(it)) }
                },
                showInfo = accounts.getOrEmpty(activeTranscriptionId).transcriptionViaChat,
            )
        }

        PreferenceGroup(title = stringRes(R.string.dictate__providers_manage_group)) {
            val keySet = stringRes(R.string.dictate__providers_status_key_set)
            val noKey = stringRes(R.string.dictate__providers_status_no_key)

            // On-device (offline) provider first, above the cloud providers like OpenAI (issue #228);
            // the rest keep their registry display order (sortedByDescending is stable).
            val orderedPresets = ProviderRegistry.presets
                .sortedByDescending { it.transcriptionApi == TranscriptionApi.LOCAL_ONDEVICE }
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
                        onClick = { navController.navigate(Routes.Settings.DictateCloud) },
                    )
                    return@forEach
                }
                val account = accounts[preset.id]
                Preference(
                    icon = providerIcon(preset.id),
                    title = preset.displayName,
                    summary = providerSummary(preset, account, keySet, noKey),
                    onClick = { editingId = preset.id },
                )
            }

            customAccounts.forEach { account ->
                Preference(
                    icon = Icons.Default.Dns,
                    title = customLabel(account),
                    summary = if (account.hasKey || account.customBaseUrl.isNotBlank()) {
                        account.customBaseUrl.ifBlank { keySet }
                    } else {
                        stringRes(R.string.dictate__providers_status_unconfigured)
                    },
                    onClick = { editingId = account.providerId },
                )
            }

            Preference(
                icon = Icons.Default.Add,
                modifier = Modifier.settingsSearchAnchor("dictate__providers_add_custom"),
                title = stringRes(R.string.dictate__providers_add_custom),
                summary = stringRes(R.string.dictate__providers_add_custom_summary),
                onClick = { editingId = ProviderAccount.newCustomId() },
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
        }

        editingId?.let { id ->
            val preset = ProviderRegistry.byId(id)
            ProviderEditorDialog(
                preset = preset,
                account = accounts.getOrEmpty(id),
                onDismiss = {
                    editingId = null
                    activateOnSave = false
                },
                onSave = { updated ->
                    writeKeyring(accounts.put(updated))
                    // A server of the user's own speaks both halves of the OpenAI API, and someone who
                    // added one during setup meant it to be the way the app works from now on.
                    if (activateOnSave) {
                        scope.launch {
                            prefs.dictate.transcriptionProviderId.set(id)
                            prefs.dictate.rewordingProviderId.set(id)
                        }
                    }
                    editingId = null
                    activateOnSave = false
                },
                onDelete = if (preset == null) {
                    {
                        writeKeyring(accounts.remove(id))
                        editingId = null
                        activateOnSave = false
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

/** Label for a custom endpoint: its user-given name, or a generic fallback. */
private fun customLabel(account: ProviderAccount): String =
    account.displayName.ifBlank { "Custom server" }

/** One-line status for a built-in provider row: key state + its capabilities. */
@Composable
private fun providerSummary(
    preset: ProviderPreset,
    account: ProviderAccount?,
    keySet: String,
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
    val caps = buildList {
        if (preset.capabilities.transcription) {
            // Note streaming support (issue #128) right on the transcription capability.
            val stt = stringRes(R.string.dictate__providers_cap_stt)
            add(if (preset.supportsRealtime) "$stt (+ Realtime)" else stt)
        }
        if (preset.capabilities.chat) add(stringRes(R.string.dictate__providers_cap_chat))
    }.joinToString(", ")
    val keyState = if (account?.hasKey == true) keySet else noKey
    return "$keyState · $caps"
}

/**
 * Multi-field editor for a single provider. Built-in providers ([preset] != null) expose only the key
 * and the relevant model fields; custom endpoints additionally edit a display name and base URL and can
 * be deleted ([onDelete] != null). All fields are committed together on confirm.
 */
@Composable
private fun ProviderEditorDialog(
    preset: ProviderPreset?,
    account: ProviderAccount,
    onDismiss: () -> Unit,
    onSave: (ProviderAccount) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val prefs by FlorisPreferenceStore
    val isCustom = preset == null
    // A base-URL-editable built-in (e.g. Ollama, #136) also shows the base URL field, pre-filled with the
    // preset's default (localhost) so the user can point it at a LAN server.
    val allowsBaseUrl = isCustom || preset?.allowsCustomBaseUrl == true
    val showTranscription = preset?.capabilities?.transcription ?: true
    val showChat = preset?.capabilities?.chat ?: true

    var displayName by remember { mutableStateOf(account.displayName) }
    var apiKey by remember { mutableStateOf(account.apiKey) }
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
                )
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
            if (isCustom) {
                EditorField(
                    label = stringRes(R.string.dictate__providers_field_name),
                    value = displayName,
                    onValueChange = { displayName = it },
                )
            }
            if (allowsBaseUrl) {
                EditorField(
                    label = stringRes(R.string.dictate__base_url_title),
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    placeholder = stringRes(R.string.dictate__base_url_placeholder),
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
            ConnectionTestRow(preset = effectivePreset, apiKey = apiKey)
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

/**
 * A "Test connection" action with an inline result. Performs a lightweight `listModels()` call against
 * the provider's base URL with the currently entered key, so the user can verify the endpoint + key are
 * reachable before saving. A model count on success doubles as proof the catalog loads.
 */
@Composable
private fun ConnectionTestRow(preset: ProviderPreset, apiKey: String) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs by FlorisPreferenceStore
    var testing by remember { mutableStateOf(false) }
    // null = not run yet; Pair(ok, message) once a test finished.
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val okColor = MaterialTheme.colorScheme.primary
    val errColor = MaterialTheme.colorScheme.error
    val failedFallback = stringRes(R.string.dictate__providers_test_failed)
    // Resolved here (composable scope) so the background coroutine can format without touching Compose.
    val successTemplate = context.getString(R.string.dictate__providers_test_success)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        result?.let { (ok, message) ->
            Text(
                text = message,
                color = if (ok) okColor else errColor,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
        } ?: Spacer(Modifier.weight(1f))
        TextButton(
            enabled = !testing,
            onClick = {
                testing = true
                result = null
                scope.launch {
                    result = try {
                        val count = OpenAiCompatibleClient
                            .from(
                                preset, apiKey.trim(),
                                baseUrlOverride = preset.baseUrl,
                                proxy = prefs.dictate.dictateProxyConfig(),
                                trustUserCerts = prefs.dictate.trustUserCertificates.get(),
                            )
                            .listModels()
                            .size
                        true to successTemplate.replace("{count}", count.toString())
                    } catch (e: Exception) {
                        false to (e.message ?: failedFallback)
                    } finally {
                        testing = false
                    }
                }
            },
        ) {
            if (testing) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(16.dp))
            }
            Text(stringRes(R.string.dictate__providers_test))
        }
    }
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
