/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.ui

import android.text.format.DateUtils
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.dictate.data.history.DictateHistoryEntry
import dev.patrickgold.florisboard.dictate.data.history.DictateHistoryStore
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import dev.patrickgold.jetpref.datastore.model.collectAsState as collectPrefAsState
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * The transcription-history panel (issue #140), rendered as its own [ImeUiMode.HISTORY] next to the
 * typing keyboard (see `ImeWindow`). Opened via the history QuickAction in the Smartbar (the button that
 * previously did a one-shot "re-insert last dictation"). Lists recent dictations newest-first and lets
 * the user re-insert one into the field with a tap, or re-transcribe its retained audio.
 *
 * Full management (playback, delete, export, retention settings) lives on the History settings screen;
 * this in-keyboard panel is the fast recovery/insert surface. Reuses the themed `media-*` Snygg elements
 * (compact text, large tap targets) and the prompt panel's accent scrollbar.
 */
@Composable
fun DictateHistoryLayout(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val prefs by FlorisPreferenceStore
    val accent by prefs.theme.accentColor.collectPrefAsState() // follows the user's keyboard accent.
    // null = not loaded yet (show a spinner), empty list = genuinely no history (#205).
    // Built AND collected on IO so opening the Room database never runs on the composition's main-thread
    // context. (The loading spinner freezing was caused by eagerly composing every row — see the list below.)
    val entries by remember(context) {
        flow { emitAll(DictateHistoryStore.flow(context)) }.flowOn(Dispatchers.IO)
    }.collectAsState(initial = null)

    val listState = rememberLazyListState()

    SnyggColumn(
        elementName = FlorisImeUi.Media.elementName,
        modifier = modifier
            .fillMaxWidth()
            // Lock to the normal keyboard height so opening history never changes the IME height (no jump).
            .height(FlorisImeSizing.panelUiHeight()),
    ) {
        // Header: back to the typing keyboard + panel title.
        SnyggRow(
            elementName = FlorisImeUi.MediaBottomRow.elementName,
            modifier = Modifier
                .fillMaxWidth()
                .height(FlorisImeSizing.smartbarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SnyggIconButton(
                elementName = FlorisImeUi.MediaBottomRowButton.elementName,
                onClick = { keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT },
                modifier = Modifier.size(FlorisImeSizing.smartbarHeight),
            ) {
                SnyggIcon(imageVector = Icons.AutoMirrored.Filled.ArrowBack)
            }
            SnyggText(
                elementName = FlorisImeUi.MediaEmojiSubheader.elementName,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
                text = stringRes(R.string.dictate__history_title),
            )
            // Jump straight to the full history management screen in the settings app.
            SnyggIconButton(
                elementName = FlorisImeUi.MediaBottomRowButton.elementName,
                onClick = { FlorisImeService.launchSettings("settings/dictate/history") },
                modifier = Modifier.size(FlorisImeSizing.smartbarHeight),
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                )
            }
        }

        val loadedEntries = entries
        if (loadedEntries == null || loadedEntries.isEmpty()) {
            SnyggBox(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp),
            ) {
                if (loadedEntries == null) {
                    // Still loading from disk — the same centered accent spinner as the GIF panel. It
                    // animates properly now that opening the panel no longer blocks the UI thread.
                    CircularProgressIndicator(color = accent)
                } else {
                    SnyggText(
                        elementName = FlorisImeUi.MediaEmojiSubheader.elementName,
                        text = stringRes(R.string.dictate__history_empty),
                    )
                }
            }
        } else {
            // Lazy on purpose: a full history is up to several hundred entries, and composing them all
            // eagerly blocked the UI thread for well over a second — which is what froze the loading
            // spinner (and everything else) while the panel opened. Only visible rows are composed now.
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .dictateLazyPanelScrollbar(listState, accent),
            ) {
                items(loadedEntries, key = { it.id }) { entry ->
                    HistoryPanelRow(
                        entry = entry,
                        accent = accent,
                        onInsert = {
                            DictateController.insertHistoryText(context, entry.text)
                            keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
                        },
                        // Long-press inserts the raw transcript instead, for entries a prompt rewrote
                        // (issue #240). The row is marked so this isn't a hidden gesture.
                        onInsertOriginal = {
                            DictateController.insertHistoryText(context, entry.originalText)
                            keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
                        },
                        onRetranscribe = {
                            DictateController.retranscribeHistoryEntry(context, entry)
                            keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryPanelRow(
    entry: DictateHistoryEntry,
    accent: Color,
    onInsert: () -> Unit,
    onInsertOriginal: () -> Unit,
    onRetranscribe: () -> Unit,
) {
    // Both versions exist only when a prompt actually rewrote the dictation (issue #240).
    val hasOriginal = entry.originalText.isNotEmpty() && entry.originalText != entry.text
    // Compact text, large tap targets: the transcript uses the candidate-word text size and the meta line
    // the (much smaller) secondary-candidate size, so the metadata clearly reads as a subordinate line;
    // the insert / re-transcribe buttons are generous and easy to hit.
    val buttonSize = 56.dp
    val iconSize = 32.dp
    SnyggRow(
        elementName = FlorisImeUi.MediaBottomRow.elementName,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        // A failed entry has no committed text yet — inserting is disabled until it's re-transcribed.
        clickAndSemanticsModifier = Modifier.combinedClickable(
            enabled = !entry.failed,
            onClick = { onInsert() },
            onLongClick = if (hasOriginal) onInsertOriginal else null,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.pinned) {
            Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(16.dp),
                tint = accent,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            SnyggText(
                elementName = FlorisImeUi.SmartbarCandidateWordText.elementName,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                text = historyPreview(entry.text),
            )
            SnyggText(
                elementName = FlorisImeUi.KeyHint.elementName,
                // The hint makes the long-press discoverable; without it the second version would exist
                // but nobody would know to reach for it (issue #240).
                text = if (hasOriginal) {
                    historyMetaLine(entry) + " · " + stringRes(R.string.dictate__history_hold_for_original)
                } else {
                    historyMetaLine(entry)
                },
            )
        }
        if (entry.audioPath != null) {
            SnyggIconButton(
                elementName = FlorisImeUi.MediaBottomRowButton.elementName,
                onClick = onRetranscribe,
                modifier = Modifier.size(buttonSize),
            ) {
                Icon(
                    imageVector = Icons.Default.Autorenew,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
        SnyggIconButton(
            elementName = FlorisImeUi.MediaBottomRowButton.elementName,
            onClick = onInsert,
            enabled = !entry.failed,
            modifier = Modifier.size(buttonSize),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardReturn,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** Collapses newlines so the transcript flows as prose; the two-line ellipsis is handled by SnyggText. */
private fun historyPreview(text: String): String = text.replace('\n', ' ').trim()

/** "5 min ago · OpenAI · 0:12 · 0.4 MB" — omits the parts that don't apply. */
private fun historyMetaLine(entry: DictateHistoryEntry): String {
    val parts = ArrayList<String>(4)
    parts.add(
        DateUtils.getRelativeTimeSpanString(
            entry.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    )
    if (entry.providerName.isNotBlank()) parts.add(entry.providerName)
    formatHistoryDuration(entry.durationSecs)?.let { parts.add(it) }
    formatHistorySize(entry.audioBytes)?.let { parts.add(it) }
    return parts.joinToString(" · ")
}

fun formatHistoryDuration(seconds: Long): String? = when {
    seconds <= 0L -> null
    seconds < 60L -> "0:${seconds.toString().padStart(2, '0')}"
    else -> "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

fun formatHistorySize(bytes: Long): String? = when {
    bytes <= 0L -> null
    bytes < 1_000_000L -> "${(bytes / 1000L).coerceAtLeast(1L)} KB"
    else -> String.format("%.1f MB", bytes / 1_000_000.0)
}
