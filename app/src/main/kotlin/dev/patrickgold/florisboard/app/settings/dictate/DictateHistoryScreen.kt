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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState as collectFlowAsState
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.dictate.data.history.DictateHistoryEntry
import dev.patrickgold.florisboard.dictate.data.history.DictateHistoryStore
import dev.patrickgold.florisboard.dictate.ui.formatHistoryDuration
import dev.patrickgold.florisboard.dictate.ui.formatHistorySize
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.FlorisIconButton
import org.florisboard.lib.compose.stringRes

/**
 * Transcription history / activity log (issue #140): a browsable, searchable list of recent dictations
 * (including failed ones whose audio can be recovered). Each row shares/copies/deletes; tapping it opens
 * a detail dialog with the full transcript, playback, audio export, share and pin. Pinned entries float
 * to the top, are marked, and survive pruning. Two opt-ins plus a single "storage limits" dialog with
 * free numeric input sit at the top; toolbar actions search and clear.
 *
 * The in-keyboard history panel ([dev.patrickgold.florisboard.dictate.ui.DictateHistoryLayout]) is the
 * fast insert/re-transcribe surface; this screen is the full management view.
 */
@Composable
fun DictateHistoryScreen() = FlorisScreen {
    title = stringRes(R.string.dictate__history_title)
    previewFieldVisible = false
    scrollable = false

    val context = LocalContext.current
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()

    val historyEnabled by prefs.dictate.historyEnabled.collectAsState()
    val audioRetention by prefs.dictate.historyAudioRetention.collectAsState()
    val maxEntries by prefs.dictate.historyMaxEntries.collectAsState()
    val maxAgeDays by prefs.dictate.historyMaxAgeDays.collectAsState()
    val audioBudgetMb by prefs.dictate.historyAudioBudgetMb.collectAsState()
    val entries by remember { DictateHistoryStore.flow(context) }.collectFlowAsState(initial = emptyList())

    var confirmClear by remember { mutableStateOf(false) }
    var showLimits by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // Only the id is kept, never the entry itself: the row is an immutable snapshot, so holding it would
    // freeze the dialog at the state it had when opened — pinning from inside it did not light up the icon
    // until the dialog was closed and reopened. Resolved against the live list on every recomposition.
    var detailEntryId by remember { mutableStateOf<Long?>(null) }

    val filtered = remember(entries, query) {
        val q = query.trim()
        // Searches the raw transcript too (issue #240): after a rewrite, what you remember saying is often
        // exactly the wording that only survives in the original.
        if (q.isEmpty()) {
            entries
        } else {
            entries.filter {
                it.text.contains(q, ignoreCase = true) || it.originalText.contains(q, ignoreCase = true)
            }
        }
    }

    // When searching, the app-bar title is replaced by a full-width search field (so it takes no extra
    // vertical space in the content below).
    titleContent = if (searching) {
        {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = true,
                placeholder = { Text(stringRes(R.string.dictate__history_search)) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }
    } else null

    actions {
        FlorisIconButton(
            onClick = { searching = !searching; if (!searching) query = "" },
            icon = if (searching) Icons.Default.Close else Icons.Default.Search,
        )
        FlorisIconButton(
            onClick = { if (entries.isNotEmpty()) confirmClear = true },
            icon = Icons.Default.DeleteSweep,
        )
    }

    content {
        Column(modifier = Modifier.fillMaxSize()) {
            // Two opt-ins: capture at all, and additionally keep the source audio.
            HistoryToggleRow(
                title = stringRes(R.string.dictate__history_enable_title),
                summary = stringRes(R.string.dictate__history_enable_summary),
                checked = historyEnabled,
                onToggle = { scope.launch { prefs.dictate.historyEnabled.set(it) } },
            )
            HistoryToggleRow(
                title = stringRes(R.string.dictate__history_audio_title),
                summary = stringRes(R.string.dictate__history_audio_summary),
                checked = audioRetention,
                enabled = historyEnabled,
                onToggle = { scope.launch { prefs.dictate.historyAudioRetention.set(it) } },
            )
            // All retention caps behind one row → a dialog with free numeric input.
            OptionRow(
                title = stringRes(R.string.dictate__history_retention_title),
                value = "$maxEntries · ${ageLabel(maxAgeDays)} · $audioBudgetMb MB",
                enabled = historyEnabled,
                onClick = { showLimits = true },
            )
            HorizontalDivider()

            val totalAudio = entries.sumOf { it.audioBytes }
            if (totalAudio > 0L) {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    text = stringRes(
                        R.string.dictate__history_disk_usage,
                        "size" to (formatHistorySize(totalAudio) ?: "0 KB"),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringRes(R.string.dictate__history_empty),
                        modifier = Modifier.padding(horizontal = 32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val listState = rememberLazyListState()
                val barColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .historyScrollbar(listState, barColor),
                    state = listState,
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        Column(modifier = Modifier.animateItem()) {
                            HistoryRow(
                                entry = entry,
                                onOpen = { detailEntryId = entry.id },
                                onShare = { shareText(context, entry.text) },
                                onCopy = {
                                    copyToClipboard(context, entry.text)
                                    Toast.makeText(context, R.string.dictate__history_copied, Toast.LENGTH_SHORT).show()
                                },
                                onDelete = { scope.launch { DictateHistoryStore.delete(context, entry) } },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        // Deliberately resolved from `entries` rather than the filtered list, so an active search query
        // cannot pull the dialog out from under the user. A deleted entry resolves to null and closes it.
        val detailEntry = detailEntryId?.let { id -> entries.firstOrNull { it.id == id } }
        detailEntry?.let { entry ->
            DetailDialog(
                entry = entry,
                onShare = { shareText(context, entry.text) },
                onExport = { DictateController.exportHistoryAudio(context, entry) },
                onTogglePin = { scope.launch { DictateHistoryStore.setPinned(context, entry.id, !entry.pinned) } },
                onDismiss = { detailEntryId = null },
            )
        }

        if (showLimits) {
            RetentionDialog(
                entries = maxEntries,
                ageDays = maxAgeDays,
                budgetMb = audioBudgetMb,
                onSave = { e, a, b ->
                    scope.launch {
                        prefs.dictate.historyMaxEntries.set(e)
                        prefs.dictate.historyMaxAgeDays.set(a)
                        prefs.dictate.historyAudioBudgetMb.set(b)
                    }
                    showLimits = false
                },
                onDismiss = { showLimits = false },
            )
        }

        if (confirmClear) {
            JetPrefAlertDialog(
                title = stringRes(R.string.dictate__history_clear_title),
                confirmLabel = stringRes(R.string.dictate__history_clear_confirm),
                onConfirm = {
                    scope.launch { DictateHistoryStore.clearAll(context) }
                    confirmClear = false
                },
                dismissLabel = stringRes(android.R.string.cancel),
                onDismiss = { confirmClear = false },
            ) {
                Text(stringRes(R.string.dictate__history_clear_message))
            }
        }
    }
}

@Composable
private fun ageLabel(days: Int): String =
    if (days <= 0) stringRes(R.string.dictate__history_age_never)
    else stringRes(R.string.dictate__history_age_days, "days" to days.toString())

@Composable
private fun HistoryToggleRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled)
    }
}

@Composable
private fun OptionRow(
    title: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val alpha = if (enabled) 1f else 0.38f
        Text(
            modifier = Modifier.weight(1f),
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
    }
}

@Composable
private fun HistoryRow(
    entry: DictateHistoryEntry,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (entry.failed) MaterialTheme.colorScheme.error else Color.Unspecified,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.pinned) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = stringRes(R.string.emoji__history__pinned),
                        modifier = Modifier.size(12.dp).padding(end = 2.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = DateUtils.getRelativeDateTimeString(
                        context, entry.createdAt, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0,
                    ).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val meta = historyRowMeta(entry)
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Uniform 44dp buttons with 24dp icons so share / copy / delete all read the same size. Share and
        // copy are disabled for a failed entry (no committed text yet) until it is re-transcribed.
        IconButton(onClick = onShare, enabled = !entry.failed, modifier = Modifier.size(44.dp)) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = stringRes(R.string.dictate__stats_share),
                modifier = Modifier.size(24.dp),
            )
        }
        IconButton(onClick = onCopy, enabled = !entry.failed, modifier = Modifier.size(44.dp)) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = stringRes(R.string.dictate__history_copy),
                modifier = Modifier.size(24.dp),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
            Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = stringRes(R.string.dictate__history_delete),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DetailDialog(
    entry: DictateHistoryEntry,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onTogglePin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // A self-contained player scoped to this dialog: released when it closes.
    val playerRef = remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    fun stop() {
        playerRef.value?.let { runCatching { it.stop() }; runCatching { it.release() } }
        playerRef.value = null
        playing = false
    }
    fun toggle() {
        if (playing) { stop(); return }
        val path = entry.audioPath ?: return
        val p = runCatching {
            MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
        }.getOrNull()
        if (p != null) { playerRef.value = p; playing = true }
        else Toast.makeText(context, R.string.dictate__history_audio_missing, Toast.LENGTH_SHORT).show()
    }
    DisposableEffect(Unit) { onDispose { stop() } }
    LaunchedEffect(playing) {
        while (playing) {
            val p = playerRef.value ?: break
            val dur = runCatching { p.duration }.getOrDefault(0)
            val pos = runCatching { p.currentPosition }.getOrDefault(0)
            progress = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            delay(120)
        }
        if (!playing) progress = 0f
    }

    JetPrefAlertDialog(
        title = DateUtils.getRelativeDateTimeString(
            context, entry.createdAt, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0,
        ).toString(),
        dismissLabel = stringRes(android.R.string.ok),
        onDismiss = { stop(); onDismiss() },
    ) {
        Column {
            // When a prompt rewrote the dictation, both versions are shown (issue #240): the raw transcript
            // is otherwise unrecoverable, since re-transcribing runs the whole prompt chain again. Driven by
            // originalText rather than entry.reworded, which is only set for live prompts and would miss
            // auto-formatting and auto-apply prompts.
            val hasOriginal = entry.originalText.isNotEmpty() && entry.originalText != entry.text
            SelectionContainer {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (hasOriginal) 320.dp else 260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (hasOriginal) {
                        Column {
                            TranscriptSection(
                                label = stringRes(R.string.dictate__history_original),
                                text = entry.originalText,
                                onCopy = { copyToClipboard(context, entry.originalText) },
                            )
                            Spacer(Modifier.height(12.dp))
                            TranscriptSection(
                                label = stringRes(R.string.dictate__history_result),
                                text = entry.text,
                                onCopy = { copyToClipboard(context, entry.text) },
                            )
                        }
                    } else {
                        Text(
                            text = entry.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (entry.failed) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                    }
                }
            }
            val meta = historyRowMeta(entry)
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (entry.audioPath != null) {
                    Box(contentAlignment = Alignment.Center) {
                        if (playing) {
                            CircularProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.size(40.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        IconButton(onClick = { toggle() }, modifier = Modifier.size(44.dp)) {
                            Icon(
                                imageVector = if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = stringRes(R.string.dictate__history_play),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    IconButton(onClick = onExport, modifier = Modifier.size(44.dp)) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = stringRes(R.string.action__export),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                IconButton(onClick = onShare, enabled = !entry.failed, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = stringRes(R.string.dictate__stats_share),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onTogglePin, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = stringRes(R.string.emoji__history__pinned),
                        modifier = Modifier.size(24.dp),
                        tint = if (entry.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * One labelled block of the detail dialog when a dictation exists in two versions (issue #240): a small
 * caption, its own copy button, and the text itself. Kept inside the dialog's single scroll container so
 * the two versions scroll together and stay comparable.
 */
@Composable
private fun TranscriptSection(
    label: String,
    text: String,
    onCopy: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            // Outside the SelectionContainer's text flow, so copying a whole version stays one tap even
            // though the text itself is also selectable by hand.
            IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringRes(R.string.dictate__legacy_action_copy),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RetentionDialog(
    entries: Int,
    ageDays: Int,
    budgetMb: Int,
    onSave: (Int, Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var e by remember { mutableStateOf(entries.toString()) }
    var a by remember { mutableStateOf(ageDays.toString()) }
    var b by remember { mutableStateOf(budgetMb.toString()) }
    // Reuse the translated "{days} days" string to get the localized bare day-unit word for the label.
    val dayUnit = stringRes(R.string.dictate__history_age_days, "days" to "").trim()

    JetPrefAlertDialog(
        title = stringRes(R.string.dictate__history_retention_title),
        confirmLabel = stringRes(android.R.string.ok),
        onConfirm = {
            onSave(
                e.toIntOrNull()?.coerceIn(1, 100_000) ?: entries,
                a.toIntOrNull()?.coerceIn(0, 100_000) ?: ageDays,
                b.toIntOrNull()?.coerceIn(0, 1_000_000) ?: budgetMb,
            )
        },
        dismissLabel = stringRes(android.R.string.cancel),
        onDismiss = onDismiss,
    ) {
        Column {
            NumberField(
                label = stringRes(R.string.dictate__history_max_entries_title),
                value = e,
                onChange = { e = it },
            )
            Spacer(Modifier.height(8.dp))
            NumberField(
                label = "${stringRes(R.string.dictate__history_max_age_title)} ($dayUnit)",
                value = a,
                onChange = { a = it },
                supporting = "0 = ${stringRes(R.string.dictate__history_age_never)}",
            )
            Spacer(Modifier.height(8.dp))
            NumberField(
                label = "${stringRes(R.string.dictate__history_audio_budget_title)} (MB)",
                value = b,
                onChange = { b = it },
            )
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    supporting: String? = null,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }.take(7)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = supporting?.let { s -> { Text(s) } },
    )
}

/** "OpenAI · gpt-4o-mini-transcribe · 0:12 · 0.4 MB" — omits empty parts. */
private fun historyRowMeta(entry: DictateHistoryEntry): String {
    val parts = ArrayList<String>(4)
    if (entry.providerName.isNotBlank()) parts.add(entry.providerName)
    if (entry.model.isNotBlank()) parts.add(entry.model)
    formatHistoryDuration(entry.durationSecs)?.let { parts.add(it) }
    formatHistorySize(entry.audioBytes)?.let { parts.add(it) }
    return parts.joinToString(" · ")
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Dictate", text))
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, null))
}

/**
 * A correct, always-on thin scrollbar for a variable-height [LazyColumn] list. Unlike the shared
 * `florisScrollbar(LazyListState)` (which approximates every item as `viewport / itemCount` tall and so
 * mis-positions the thumb for tall rows), this estimates content height from the *actual* average size
 * of the currently-visible items, so the thumb tracks the real scroll fraction and reaches the bottom.
 */
private fun Modifier.historyScrollbar(state: LazyListState, color: Color, width: Dp = 4.dp): Modifier =
    drawWithContent {
        drawContent()
        val info = state.layoutInfo
        val total = info.totalItemsCount
        val visible = info.visibleItemsInfo
        if (total == 0 || visible.isEmpty()) return@drawWithContent
        val avgItem = visible.sumOf { it.size }.toFloat() / visible.size
        val viewport = size.height
        val content = avgItem * total
        if (content <= viewport) return@drawWithContent // nothing to scroll
        val first = visible.first()
        val scrolled = (first.index * avgItem - first.offset).coerceAtLeast(0f)
        val maxScroll = content - viewport
        val fraction = (scrolled / maxScroll).coerceIn(0f, 1f)
        val w = width.toPx()
        val thumb = (viewport * viewport / content).coerceIn(w * 6f, viewport)
        val y = fraction * (viewport - thumb)
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - w, y),
            size = Size(w, thumb),
            cornerRadius = CornerRadius(w / 2f, w / 2f),
        )
    }
