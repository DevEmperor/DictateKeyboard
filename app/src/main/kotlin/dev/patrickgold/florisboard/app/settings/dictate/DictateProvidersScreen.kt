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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
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
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes

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

        fun writeKeyring(updated: ProviderAccounts) {
            scope.launch { prefs.dictate.providerAccounts.set(updated) }
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
                        // Flag providers with real-time streaming support (issue #128) right in the label.
                        .forEach { add(it.id to (it.displayName + if (it.supportsRealtime) " + Realtime" else "")) }
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

            ProviderRegistry.presets.forEach { preset ->
                val account = accounts[preset.id]
                Preference(
                    icon = if (preset.transcriptionApi == TranscriptionApi.LOCAL_ONDEVICE) {
                        Icons.Default.PhoneAndroid
                    } else {
                        Icons.Default.Cloud
                    },
                    title = preset.displayName + if (preset.supportsRealtime) " + Realtime" else "",
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
                onDismiss = { editingId = null },
                onSave = { updated ->
                    writeKeyring(accounts.put(updated))
                    editingId = null
                },
                onDelete = if (preset == null) {
                    {
                        writeKeyring(accounts.remove(id))
                        editingId = null
                    }
                } else {
                    null
                },
            )
        }

    }
}

/**
 * Draws a thin, rounded scrollbar thumb on the trailing edge of a [verticalScroll]ed container so the
 * user can tell there is more content below (Compose has no built-in scrollbar). Nothing is drawn when
 * the content already fits.
 */
private fun Modifier.verticalScrollbar(state: ScrollState, color: Color, width: Dp = 3.dp): Modifier =
    drawWithContent {
        drawContent()
        val max = state.maxValue
        if (max > 0 && max != Int.MAX_VALUE) {
            val viewport = size.height
            val thumbHeight = viewport * (viewport / (viewport + max))
            val top = (viewport - thumbHeight) * (state.value.toFloat() / max)
            val w = width.toPx()
            drawRoundRect(
                color = color,
                topLeft = Offset(size.width - w, top),
                size = Size(w, thumbHeight),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
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
                    .verticalScrollbar(scrollState, scrollbarColor)
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
                        .verticalScrollbar(scrollState, scrollbarColor)
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
        // On-device provider: surface the active downloaded model instead of an API-key state.
        val context = LocalContext.current
        val spec = account?.transcriptionModel?.takeIf { it.isNotBlank() }?.let { LocalModelCatalog.byId(it) }
        return if (spec != null && LocalModelManager.isInstalled(context, spec.id)) {
            spec.displayName
        } else {
            stringRes(R.string.dictate__local_model_none_selected)
        }
    }
    val caps = buildList {
        if (preset.capabilities.transcription) add(stringRes(R.string.dictate__providers_cap_stt))
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
    var transcriptionModel by remember { mutableStateOf(account.transcriptionModel) }
    var chatModel by remember { mutableStateOf(account.chatModel) }
    // Live catalog cache, updated when the picker fetches; persisted together with the rest on confirm.
    var cachedModels by remember { mutableStateOf(account.cachedModels) }
    var cachedAudioModels by remember { mutableStateOf(account.cachedAudioModels) }
    var transcriptionViaChat by remember { mutableStateOf(account.transcriptionViaChat) }
    var pickerKind by remember { mutableStateOf<ModelKind?>(null) }

    // Effective preset to drive the model picker / connection test. Custom endpoints get a base-URL-only
    // preset; a base-URL-editable built-in (Ollama, #136) uses the edited URL over its localhost default.
    val effectivePreset = when {
        preset == null -> ProviderRegistry.custom(baseUrl)
        preset.allowsCustomBaseUrl -> preset.copy(baseUrl = baseUrl.ifBlank { preset.baseUrl })
        else -> preset
    }

    // Pre-load the model catalog so we know whether this provider has any audio-capable model — that
    // gates the single-call multimodal option (#130/#132). Populates on open for keyed accounts; for a
    // fresh account the model browser fills it in when the user picks a model. Once classified, stops.
    LaunchedEffect(effectivePreset.id, effectivePreset.baseUrl) {
        if (showTranscription && showChat && effectivePreset.supportsDynamicModels &&
            cachedAudioModels.isEmpty() &&
            (apiKey.isNotBlank() || effectivePreset.apiKeyUrl == null)
        ) {
            runCatching {
                val models = OpenAiCompatibleClient
                    .from(
                        effectivePreset, apiKey,
                        baseUrlOverride = effectivePreset.baseUrl,
                        trustUserCerts = prefs.dictate.trustUserCertificates.get(),
                    )
                    .listModels()
                cachedModels = models.map { it.id }
                cachedAudioModels = models.filter { it.acceptsAudioInput }.map { it.id }
            }
        }
    }

    JetPrefAlertDialog(
        title = preset?.displayName ?: stringRes(R.string.dictate__providers_custom_title),
        confirmLabel = stringRes(R.string.action__ok),
        dismissLabel = stringRes(R.string.action__cancel),
        neutralLabel = if (onDelete != null) stringRes(R.string.action__delete) else null,
        onConfirm = {
            onSave(
                account.copy(
                    displayName = displayName.trim(),
                    apiKey = apiKey.trim(),
                    customBaseUrl = baseUrl.trim(),
                    transcriptionModel = transcriptionModel.trim(),
                    chatModel = chatModel.trim(),
                    cachedModels = cachedModels,
                    cachedAudioModels = cachedAudioModels,
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
            LocalModelSection(
                activeModelId = transcriptionModel,
                onActiveModelChange = { transcriptionModel = it },
            )
        } else {
        Column {
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
                    // When single-call is on, this one model does both transcription and rewording (#130).
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
            }
            // Single-call multimodal (issue #130): kept at the bottom; when on, this one model transcribes
            // and formats in a single request (the rewording model above is hidden). Offered for any
            // provider with a chat endpoint (the prerequisite for input_audio) — whether a given model
            // accepts audio is only known for providers that report modalities (#132), so we don't hide
            // the toggle, but we do warn when the catalog says the selected model is not audio-capable.
            if (showTranscription && showChat) {
                // Only warn when we positively know the model isn't audio-capable (catalog has modality
                // data and the chosen model isn't in it) — never for providers that don't report it.
                val knownNotAudio = cachedAudioModels.isNotEmpty() &&
                    transcriptionModel.trim() !in cachedAudioModels
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
                            stringRes(
                                if (transcriptionViaChat && knownNotAudio) {
                                    R.string.dictate__providers_single_call_pick_audio
                                } else {
                                    R.string.dictate__providers_single_call_summary
                                },
                            ),
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
            onModelsFetched = { ids, audioIds -> cachedModels = ids; cachedAudioModels = audioIds },
            onPick = { picked ->
                if (kind == ModelKind.TRANSCRIPTION) transcriptionModel = picked else chatModel = picked
            },
            onDismiss = { pickerKind = null },
        )
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
    OutlinedTextField(
        modifier = Modifier.padding(top = 8.dp),
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isSecret) KeyboardType.Password else keyboardType,
        ),
        trailingIcon = onBrowse?.let {
            {
                IconButton(onClick = it) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringRes(R.string.dictate__model_picker_title),
                    )
                }
            }
        },
    )
}
