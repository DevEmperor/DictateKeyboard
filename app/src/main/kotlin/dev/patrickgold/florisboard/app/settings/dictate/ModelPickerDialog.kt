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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.provider.DictateApiException
import dev.patrickgold.florisboard.dictate.provider.OpenAiCompatibleClient
import dev.patrickgold.florisboard.dictate.provider.ProviderPreset
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes

/** Whether the picker is choosing a speech-to-text or a chat/rewording model. */
enum class ModelKind { TRANSCRIPTION, CHAT }

/**
 * A searchable model picker. Combines the provider's curated, known-good models (offline) with the
 * live `/models` catalog fetched on demand, filtered to the relevant [kind]. The current search text
 * doubles as a free-text override, so any model id can still be entered even if the provider doesn't
 * list it. Fetched ids are reported via [onModelsFetched] so the caller can cache them in the keyring.
 */
@Composable
fun ModelPickerDialog(
    kind: ModelKind,
    preset: ProviderPreset,
    apiKey: String,
    current: String,
    cachedModels: List<String>,
    cachedAudioModels: List<String>,
    cachedTranscriptionModels: List<String>,
    onModelsFetched: (ids: List<String>, audioIds: List<String>, sttIds: List<String>) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Live catalog fetched this session, merged on top of the curated/cached list.
    var fetched by remember { mutableStateOf(cachedModels) }
    // Ids the catalog flags as audio-input chat models (transcription-capable) regardless of name (#132).
    var audioModelIds by remember { mutableStateOf(cachedAudioModels) }
    // Ids the catalog flags as dedicated STT models (audio → transcription), surfaced by modality even
    // when the name doesn't match the heuristic (issue #157).
    var transcriptionModelIds by remember { mutableStateOf(cachedTranscriptionModels) }

    // Fetches the provider's full /models catalog and caches it via [onModelsFetched]. Shared by the
    // Refresh button and the auto-load below.
    val loadModels: suspend () -> Unit = {
        loading = true
        error = null
        try {
            val models = OpenAiCompatibleClient
                .from(
                    preset, apiKey,
                    baseUrlOverride = preset.baseUrl,
                    trustUserCerts = prefs.dictate.trustUserCertificates.get(),
                )
                .listModels()
            val ids = models.map { it.id }
            val audioIds = models.filter { it.acceptsAudioInput }.map { it.id }
            val sttIds = models.filter { it.isTranscriptionModel }.map { it.id }
            fetched = ids
            audioModelIds = audioIds
            transcriptionModelIds = sttIds
            onModelsFetched(ids, audioIds, sttIds)
        } catch (e: DictateApiException) {
            error = e.message
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    // Auto-load the live catalog the first time the picker opens (no cached list yet). Without this the
    // dialog shows only the few curated ids — and nothing at all for providers that ship no curated list
    // (xAI, DeepSeek, …) until the user manually tapped Refresh (roadmap 11.13). Gated on a usable key
    // (or a keyless provider like Ollama) so we don't fire a guaranteed-401 request.
    // Also refetch (once) when we have a model list but no audio classification yet, so the 🎤 markers
    // and single-call gating have fresh modality info (issue #130/#132) without the user tapping Refresh.
    var autoLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoLoaded && (cachedModels.isEmpty() || audioModelIds.isEmpty()) &&
            preset.supportsDynamicModels && (apiKey.isNotBlank() || preset.apiKeyUrl == null)
        ) {
            autoLoaded = true
            loadModels()
        }
    }

    val curated = when (kind) {
        ModelKind.TRANSCRIPTION -> preset.curatedTranscriptionModels
        ModelKind.CHAT -> preset.curatedChatModels
    }
    val candidates = remember(curated, fetched, audioModelIds, transcriptionModelIds, query, current) {
        // Gemini transcribes via its multimodal chat models (no STT-tagged ids exist), so its live STT
        // catalog is the chat catalog rather than the keyword-filtered subset.
        val liveKind = if (preset.id == "gemini") ModelKind.CHAT else kind
        // For transcription, also include models the catalog flagged by MODALITY — both audio-input chat
        // models (#132) and dedicated STT models (#157) — so they surface even when the id doesn't match
        // the name heuristic (e.g. OpenRouter's mai-transcribe / parakeet / chirp).
        val audioForTranscription =
            if (kind == ModelKind.TRANSCRIPTION) audioModelIds + transcriptionModelIds else emptyList()
        (curated + audioForTranscription + fetched.filter { matchesKind(it, liveKind) } + current)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
    }
    val showFreeText = query.isNotBlank() && candidates.none { it.equals(query.trim(), ignoreCase = true) }

    JetPrefAlertDialog(
        title = stringRes(R.string.dictate__model_picker_title),
        dismissLabel = stringRes(R.string.action__cancel),
        onDismiss = onDismiss,
    ) {
        Column {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringRes(R.string.dictate__model_picker_search)) },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val statusText = when {
                    loading -> stringRes(R.string.dictate__model_picker_loading)
                    error != null -> error!!
                    else -> stringRes(R.string.dictate__model_picker_count, "count" to candidates.size)
                }
                Text(text = statusText, modifier = Modifier.weight(1f))
                TextButton(
                    enabled = !loading,
                    onClick = { scope.launch { loadModels() } },
                ) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(16.dp))
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    }
                    Text(stringRes(R.string.dictate__model_picker_refresh))
                }
            }

            LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                if (showFreeText) {
                    item {
                        ModelRow(
                            label = stringRes(R.string.dictate__model_picker_use_custom, "model" to query.trim()),
                            selected = false,
                            onClick = { onPick(query.trim()); onDismiss() },
                        )
                    }
                }
                items(candidates) { model ->
                    ModelRow(
                        label = model,
                        selected = model.equals(current, ignoreCase = true),
                        // Mark models the catalog reports as accepting audio input (issue #130/#132).
                        audio = audioModelIds.contains(model),
                        onClick = { onPick(model); onDismiss() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRow(label: String, selected: Boolean, audio: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        // Audio-input capable (usable for single-call multimodal); helps the user spot those models.
        if (audio) {
            Text("🎤", modifier = Modifier.padding(end = 8.dp))
        }
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = null)
        }
    }
}

/**
 * Heuristic split of a raw catalog id into transcription vs. chat, since `/models` doesn't classify
 * them. Transcription ids contain a tell-tale token; everything else that isn't an embedding/TTS/image
 * model is treated as a chat model.
 */
private fun matchesKind(id: String, kind: ModelKind): Boolean {
    val l = id.lowercase()
    // Name fallback for STT models when modality metadata is missing; the modality classification (#157)
    // is the primary signal. Covers whisper / *-transcribe / mai-transcribe / ASR / Chirp / Parakeet / …
    val isStt = l.contains("whisper") || l.contains("transcribe") || l.contains("transcription") ||
        l.contains("stt") || l.contains("asr") || l.contains("voxtral") || l.contains("chirp") ||
        l.contains("parakeet") || l.contains("speech")
    return when (kind) {
        ModelKind.TRANSCRIPTION -> isStt
        ModelKind.CHAT -> !isStt &&
            !l.contains("embedding") && !l.contains("tts") && !l.contains("dall-e") &&
            !l.contains("image") && !l.contains("moderation") && !l.contains("rerank")
    }
}
