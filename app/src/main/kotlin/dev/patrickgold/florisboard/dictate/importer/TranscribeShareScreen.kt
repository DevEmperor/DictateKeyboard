/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.importer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.settings.dictate.AudioPlaybackRow
import dev.patrickgold.florisboard.dictate.DictateLanguages
import dev.patrickgold.florisboard.dictate.data.history.DictateHistorySource
import dev.patrickgold.florisboard.dictate.data.history.DictateHistoryStore
import dev.patrickgold.florisboard.dictate.data.prompts.PromptModel
import dev.patrickgold.florisboard.dictate.data.prompts.PromptsDatabaseHelper
import dev.patrickgold.florisboard.dictate.data.prompts.snippetBody
import dev.patrickgold.florisboard.dictate.dictateProxyConfig
import dev.patrickgold.florisboard.dictate.provider.DictateRewording
import dev.patrickgold.florisboard.dictate.provider.OpenAiCompatibleClient
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.dictate.provider.TranscriptionApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.compose.stringRes
import java.io.File

/**
 * The screen a shared voice message lands on (issue #301).
 *
 * It starts transcribing on its own. Sharing a file to a transcriber has already said what should
 * happen to it; a second button would be a toll on a decision the user has made twice by then. What
 * the screen adds over committing at a cursor is everything after the transcript: playback while
 * reading it, and a prompt from the library applied to it — for a ten-minute voice message the
 * summary is the payload, not the words.
 */
@Composable
fun TranscribeShareScreen(uris: List<Uri>, onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()

    var audio by remember { mutableStateOf<File?>(null) }
    var info by remember { mutableStateOf<SharedFileInfo?>(null) }
    var text by remember { mutableStateOf("") }
    /** The transcript before a prompt rewrote it — both stay visible, as in the history (#240). */
    var original by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var onDevice by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val prompts = remember { mutableStateOf<List<PromptModel>>(emptyList()) }

    val localAvailable = remember { ImportTranscriber.localModelReady(context, prefs) }
    val skipped = (uris.size - 1).coerceAtLeast(0)
    /** Whether the failure is one the provider settings can fix, rather than a network hiccup. */
    var needsKey by remember { mutableStateOf(false) }

    fun run(forceLocal: Boolean) {
        val file = audio ?: return
        job?.cancel()
        error = null
        needsKey = false
        val account = ImportTranscriber.accountFor(prefs, forceLocal)
        val preset = ImportTranscriber.presetFor(account)
        if (account.apiKey.isBlank() && preset.transcriptionApi != TranscriptionApi.LOCAL_ONDEVICE) {
            // Checked before the file is touched: failing at the upload would say the same thing three
            // seconds later and with a worse message.
            busy = false
            needsKey = true
            error = context.getString(R.string.dictate__error_no_api_key)
            return
        }
        busy = true
        status = context.getString(R.string.dictate__import_status_preparing)
        job = scope.launch {
            try {
                val result = ImportTranscriber.transcribe(context, prefs, file, forceLocal) { done, total ->
                    status = if (total > 1) {
                        context.getString(R.string.dictate__import_status_part, done + 1, total)
                    } else {
                        context.getString(R.string.dictate__import_status_transcribing)
                    }
                }
                text = result
                original = ""
                // Kept like every other dictation, so closing this screen does not lose the transcript.
                withContext(Dispatchers.IO) {
                    val account = ImportTranscriber.accountFor(prefs, forceLocal)
                    val preset = ImportTranscriber.presetFor(account)
                    DictateHistoryStore.record(
                        context = context,
                        prefs = prefs,
                        text = result,
                        providerId = account.providerId,
                        providerName = account.displayName.ifBlank { preset.displayName },
                        model = account.transcriptionModel.ifBlank { preset.defaultTranscriptionModel ?: "" },
                        language = prefs.dictate.activeInputLanguage.get()
                            .takeIf { it != DictateLanguages.DETECT } ?: "",
                        durationSecs = info?.durationSecs ?: 0L,
                        source = DictateHistorySource.IMPORT,
                        reworded = false,
                        audioFile = file,
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: ImportTranscriber.NoSpeechException) {
                error = context.getString(R.string.dictate__no_speech_detected)
            } catch (e: Exception) {
                error = e.message ?: context.getString(R.string.dictate__file_read_error)
            } finally {
                busy = false
                status = ""
            }
        }
    }

    // Copy the file out of the temporary grant first, then start. Both happen once per screen.
    LaunchedEffect(uris) {
        val uri = uris.firstOrNull()
        if (uri == null) {
            busy = false
            error = context.getString(R.string.dictate__import_no_file)
            return@LaunchedEffect
        }
        val copied = withContext(Dispatchers.IO) { copySharedFile(context, uri) }
        if (copied == null) {
            busy = false
            error = context.getString(R.string.dictate__file_read_error)
            return@LaunchedEffect
        }
        audio = copied.first
        info = copied.second
        prompts.value = withContext(Dispatchers.IO) {
            // Snippets insert literal text and have nothing to say about a transcript.
            PromptsDatabaseHelper.getInstance(context).getAll().filter { it.snippetBody() == null }
        }
        run(forceLocal = onDevice)
    }

    fun applyPrompt(prompt: PromptModel) {
        val body = prompt.prompt?.takeIf { it.isNotBlank() } ?: return
        job?.cancel()
        error = null
        busy = true
        status = context.getString(R.string.dictate__status_rewording)
        job = scope.launch {
            try {
                val before = text
                val result = rewordWith(context, body, before)
                original = before
                text = result
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                error = e.message ?: context.getString(R.string.dictate__notice_rewording_failed)
            } finally {
                busy = false
                status = ""
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Header(info, onDevice, localAvailable, skipped, onLocalChange = {
            onDevice = it
            run(forceLocal = it)
        })

        audio?.let { file ->
            Spacer(Modifier.height(12.dp))
            AudioPlaybackRow(path = file.absolutePath)
        }

        Spacer(Modifier.height(16.dp))

        if (busy) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text(status.ifBlank { stringRes(R.string.dictate__import_status_transcribing) })
                    }
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { job?.cancel(); busy = false; status = "" }) {
                        Text(stringRes(R.string.action__cancel))
                    }
                }
            }
        }

        error?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { run(forceLocal = onDevice) }) {
                            Text(stringRes(R.string.dictate__import_retry))
                        }
                        // A missing key is the one error the user can act on from here, and being told
                        // about it without a way out is the definition of a dead end.
                        if (needsKey) {
                            Button(onClick = { openProviderSettings(context) }) {
                                Text(stringRes(R.string.dictate__action_settings))
                            }
                        }
                    }
                }
            }
        }

        if (text.isNotEmpty() || original.isNotEmpty()) {
            if (original.isNotEmpty()) {
                Text(
                    text = stringRes(R.string.dictate__history_original),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = original, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
            }
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = text,
                onValueChange = { text = it },
                label = { Text(stringRes(R.string.dictate__import_result_label)) },
                minLines = 4,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { copyToClipboard(context, text) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringRes(R.string.dictate__history_copy))
                }
                OutlinedButton(onClick = { shareText(context, text) }) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringRes(R.string.dictate__stats_share))
                }
                OutlinedButton(onClick = { run(forceLocal = onDevice) }) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }

            if (prompts.value.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringRes(R.string.dictate__import_prompt_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (prompt in prompts.value) {
                        AssistChip(
                            onClick = { applyPrompt(prompt) },
                            enabled = !busy,
                            label = { Text(prompt.name.orEmpty()) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            TextButton(onClick = { job?.cancel(); onClose() }) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringRes(R.string.action__done))
            }
        }
    }
}

@Composable
private fun Header(
    info: SharedFileInfo?,
    onDevice: Boolean,
    localAvailable: Boolean,
    skipped: Int,
    onLocalChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val prefs by FlorisPreferenceStore
    val providerName = remember(onDevice) {
        val account = ImportTranscriber.accountFor(prefs, forceLocal = onDevice)
        account.displayName.ifBlank { ImportTranscriber.presetFor(account).displayName }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = info?.displayName ?: stringRes(R.string.dictate__import_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = listOfNotNull(
                    info?.durationSecs?.takeIf { it > 0 }?.let { formatDuration(it) },
                    info?.sizeBytes?.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
                    providerName.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (skipped > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    // Said plainly rather than swallowed: doing part of a job silently is worse than
                    // doing one part of it out loud.
                    text = stringRes(R.string.dictate__import_only_first, "count" to skipped.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (localAvailable) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringRes(R.string.dictate__import_on_device), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringRes(R.string.dictate__import_on_device_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = onDevice, onCheckedChange = onLocalChange)
                }
            }
        }
    }
}

/** Runs one library prompt over [transcript] using the configured rewording provider. */
private suspend fun rewordWith(context: Context, promptBody: String, transcript: String): String {
    val prefs by FlorisPreferenceStore
    val accounts = prefs.dictate.providerAccounts.get()
    val account = accounts.getOrEmpty(prefs.dictate.rewordingProviderId.get())
    val preset = when {
        account.isCustom -> ProviderRegistry.custom(account.customBaseUrl, realtime = account.customRealtime)
        else -> ProviderRegistry.byId(account.providerId) ?: ProviderRegistry.OPENAI
    }
    val apiKey = account.apiKey.ifBlank {
        accounts.getOrEmpty(prefs.dictate.transcriptionProviderId.get()).apiKey
    }
    if (apiKey.isBlank() && preset.transcriptionApi != TranscriptionApi.LOCAL_ONDEVICE) {
        throw IllegalStateException(context.getString(R.string.dictate__error_no_api_key))
    }
    val client = OpenAiCompatibleClient.from(
        preset,
        apiKey,
        baseUrlOverride = if (account.isCustom || preset.allowsCustomBaseUrl) {
            account.customBaseUrl.takeIf { it.isNotBlank() }
        } else null,
        proxy = prefs.dictate.dictateProxyConfig(),
        trustUserCerts = prefs.dictate.trustUserCertificates.get(),
    )
    return DictateRewording.apply(
        client = client,
        chatModel = account.chatModel.ifBlank { preset.defaultChatModel ?: "gpt-4o-mini" },
        transcript = transcript,
        // The chosen prompt is the whole job here — no auto-formatting and no auto-apply chain, which
        // the transcript already went through if the user wanted them.
        autoFormatting = false,
        languageName = DictateLanguages.englishNameFor(prefs.dictate.activeInputLanguage.get()),
        systemPrompt = null,
        autoApplyPrompts = listOf(DictateRewording.Prompt(promptBody, requiresSelection = true)),
    )
}

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "%d:%02d".format(m, s) else "0:%02d".format(s)
}

/**
 * Opens the provider settings.
 *
 * Its own copy rather than `DictateController.openProviderSettings`, whose `clearError()` reaches
 * into the keyboard's state — the thing this whole screen exists not to touch.
 */
private fun openProviderSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("ui://florisboard/settings/dictate/providers"))
                // BROWSABLE is required, or FlorisAppActivity treats the intent as an extension
                // import and lands on the wrong screen.
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Dictate", text))
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
