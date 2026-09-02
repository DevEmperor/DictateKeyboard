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
import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import android.widget.Toast
import dev.patrickgold.florisboard.app.settings.dictate.AudioPlaybackRow
import dev.patrickgold.florisboard.app.settings.dictate.rememberAudioPlayer
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
import dev.patrickgold.florisboard.dictate.provider.chatModelFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    var job by remember { mutableStateOf<Job?>(null) }
    val prompts = remember { mutableStateOf<List<PromptModel>>(emptyList()) }

    val skipped = (uris.size - 1).coerceAtLeast(0)
    /** Whether the failure is one the provider settings can fix, rather than a network hiccup. */
    var needsKey by remember { mutableStateOf(false) }
    var promptMenuOpen by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var activeMatch by remember { mutableStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    /** Recomputed from the text and the query, so an edit while searching keeps the hits honest. */
    val matches = remember(text, query) { findMatches(text, query) }

    // The copy button says so for a moment and then goes back to being a button. Long enough to be
    // read, short enough that it is never the label you find when you look again.
    LaunchedEffect(copied) {
        if (copied) {
            delay(2200)
            copied = false
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null
        }.getOrDefault(false)
        Toast.makeText(
            context,
            if (ok) R.string.dictate__import_saved else R.string.dictate__import_save_failed,
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun run() {
        val file = audio ?: return
        job?.cancel()
        error = null
        needsKey = false
        val account = ImportTranscriber.accountFor(prefs)
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
                val result = ImportTranscriber.transcribe(context, prefs, file) { done, total ->
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
                    val account = ImportTranscriber.accountFor(prefs)
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
        if (!copied.second.hasAudio) {
            // The filter accepts unknown types so voice notes get through; this is where the ones
            // that are not audio stop, before a byte leaves the phone.
            copied.first.delete()
            busy = false
            error = context.getString(R.string.dictate__import_not_audio)
            return@LaunchedEffect
        }
        audio = copied.first
        info = copied.second
        prompts.value = withContext(Dispatchers.IO) {
            // Snippets insert literal text and have nothing to say about a transcript.
            PromptsDatabaseHelper.getInstance(context).getAll().filter { it.snippetBody() == null }
        }
        run()
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

    /*
     * Two arrangements out of the same pieces.
     *
     * Portrait stacks them: header, player, transcript, footer. Landscape has the opposite problem —
     * almost no height, plenty of width — so the header, the player and the buttons move into a
     * narrow left column with the buttons two to a row, and the transcript takes the rest at full
     * height. Left to right that is still the file first and its text second, which is the order one
     * thinks in.
     *
     * The player state is made **here**, above the branch, and passed down. Made inside a branch it
     * would be disposed and released every time the phone turned, which is exactly what stopped the
     * playback on rotation.
     */
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val player = rememberAudioPlayer(audio?.absolutePath, preload = true) {
        Toast.makeText(context, R.string.dictate__history_audio_missing, Toast.LENGTH_SHORT).show()
    }

    val header = @Composable { Header(info, skipped) }
    val playerRow = @Composable { if (audio != null) AudioPlaybackRow(player) }
    val middle = @Composable { modifier: Modifier ->
        Box(modifier) {
            when {
                busy && text.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(status.ifBlank { stringRes(R.string.dictate__import_status_transcribing) })
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { job?.cancel(); busy = false; status = "" }) {
                        Text(stringRes(R.string.action__cancel))
                    }
                }
                text.isNotEmpty() -> TranscriptField(
                    value = text,
                    onValueChange = { text = it },
                    matches = matches,
                    activeMatch = activeMatch,
                    modifier = Modifier.fillMaxSize(),
                )
                error != null -> Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { run() }) {
                            Text(stringRes(R.string.dictate__import_retry))
                        }
                        // A missing key is the one error the user can act on from here, and being
                        // told about it without a way out is the definition of a dead end.
                        if (needsKey) {
                            Button(onClick = { openProviderSettings(context) }) {
                                Text(stringRes(R.string.dictate__action_settings))
                            }
                        }
                    }
                }
            }
        }
    }
    // A rewording running over a transcript that is already on screen: one line, rather than taking
    // the middle away from the text it is about to replace.
    val working = @Composable {
        if (busy && text.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = status.ifBlank { stringRes(R.string.dictate__status_rewording) },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { job?.cancel(); busy = false; status = "" }) {
                    Text(stringRes(R.string.action__cancel))
                }
            }
        }
    }
    val footer = @Composable {
        if (text.isNotEmpty()) {
            if (searching) {
                SearchRow(
                    query = query,
                    onQuery = { query = it; activeMatch = 0 },
                    hits = matches.size,
                    active = activeMatch,
                    onStep = { step ->
                        if (matches.isNotEmpty()) {
                            activeMatch = (activeMatch + step + matches.size) % matches.size
                        }
                    },
                    onClose = { searching = false; query = "" },
                )
            } else {
                Footer(
                    busy = busy,
                    twoPerRow = landscape,
                    prompts = prompts.value,
                    canRevert = original.isNotEmpty(),
                    menuOpen = promptMenuOpen,
                    copied = copied,
                    onMenu = { promptMenuOpen = it },
                    onCopy = {
                        copyToClipboard(context, text)
                        copied = true
                    },
                    onShare = { shareText(context, text) },
                    onRetry = { run() },
                    onSave = { saveLauncher.launch((info?.displayName ?: "transcript").substringBeforeLast('.') + ".txt") },
                    onSearch = { searching = true },
                    onPrompt = { applyPrompt(it) },
                    onRevert = { text = original; original = "" },
                )
            }
        }
    }

    val root = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 12.dp)
    if (landscape) {
        Row(root, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                modifier = Modifier.weight(2f).fillMaxHeight().verticalScroll(rememberScrollState()),
            ) {
                header()
                playerRow()
                working()
                footer()
            }
            middle(Modifier.weight(3f).fillMaxHeight())
        }
    } else {
        Column(root) {
            header()
            playerRow()
            middle(Modifier.weight(1f).fillMaxWidth())
            working()
            footer()
        }
    }
}

/**
 * The row of actions.
 *
 * Copy is the only filled button and so the only thing wearing the accent: on a screen whose whole
 * point is a piece of text, taking that text is the action and the rest are alternatives to it. The
 * others are icons — six labelled buttons do not fit across a phone — and in landscape, where the
 * column is narrow instead of short, they wrap two to a row.
 *
 * There is no finish button. Nothing here needs confirming, and back closes the screen; a checkmark
 * would only be a second way to do what the system already does.
 */
@Composable
private fun Footer(
    busy: Boolean,
    twoPerRow: Boolean,
    prompts: List<PromptModel>,
    canRevert: Boolean,
    menuOpen: Boolean,
    copied: Boolean,
    onMenu: (Boolean) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onSave: () -> Unit,
    onSearch: () -> Unit,
    onPrompt: (PromptModel) -> Unit,
    onRevert: () -> Unit,
) {
    val copy = @Composable { modifier: Modifier ->
        Button(onClick = onCopy, modifier = modifier) {
            Icon(
                imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(8.dp))
            // The label never changes. A word that grows to "Copied!" and shrinks back resizes the
            // button under the finger that just pressed it, and the row shifts with it; the tick is
            // the same confirmation without moving anything.
            Text(stringRes(R.string.dictate__import_copy))
        }
    }
    @Composable
    fun iconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            Icon(icon, contentDescription = stringRes(label), modifier = Modifier.size(20.dp))
        }
    }

    val share = @Composable { m: Modifier -> iconButton(Icons.Default.Share, R.string.dictate__stats_share, true, m) { onShare() } }
    val save = @Composable { m: Modifier -> iconButton(Icons.Default.SaveAlt, R.string.dictate__import_save, true, m) { onSave() } }
    val retry = @Composable { m: Modifier -> iconButton(Icons.Default.Refresh, R.string.dictate__import_retry, !busy, m) { onRetry() } }
    val search = @Composable { m: Modifier -> iconButton(Icons.Default.Search, R.string.dictate__import_search, true, m) { onSearch() } }
    val reword = @Composable { m: Modifier ->
        Box(m) {
            iconButton(
                Icons.Default.AutoFixHigh, R.string.dictate__import_prompt_button,
                !busy && prompts.isNotEmpty(), Modifier.fillMaxWidth(),
            ) { onMenu(true) }
            PromptMenu(menuOpen, prompts, canRevert, onMenu, onPrompt, onRevert)
        }
    }

    /*
     * Six controls never fit across a phone. The first attempt put them in one row and squeezed the
     * icons out of existence — a button so narrow that its icon is clipped is worse than a second
     * row. Portrait therefore takes three and three, which leaves Copy room for its word; the
     * landscape column is narrower still, so there it is two and two and two.
     */
    val rows: List<List<@Composable (Modifier) -> Unit>> = if (twoPerRow) {
        listOf(listOf(copy, share), listOf(retry, save), listOf(reword, search))
    } else {
        listOf(listOf(copy, share, save), listOf(retry, reword, search))
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (cell in row) cell(Modifier.weight(1f))
            }
        }
    }
}

/** The prompts, plus the way back out of the last one applied. */
@Composable
private fun PromptMenu(
    open: Boolean,
    prompts: List<PromptModel>,
    canRevert: Boolean,
    onMenu: (Boolean) -> Unit,
    onPrompt: (PromptModel) -> Unit,
    onRevert: () -> Unit,
) {
    DropdownMenu(expanded = open, onDismissRequest = { onMenu(false) }) {
        // Undo first and set apart: it is the way back out of the last thing chosen here.
        if (canRevert) {
            DropdownMenuItem(
                text = { Text(stringRes(R.string.dictate__import_revert)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null) },
                onClick = { onMenu(false); onRevert() },
            )
            HorizontalDivider()
        }
        for (prompt in prompts) {
            DropdownMenuItem(
                text = { Text(prompt.name.orEmpty()) },
                onClick = { onMenu(false); onPrompt(prompt) },
            )
        }
    }
}

/**
 * The action row, become a search bar.
 *
 * It replaces the buttons rather than sitting above them: in portrait every fixed line is height the
 * transcript does not get, and a search field on a twenty-second voice message is a line nobody
 * needs. Opening it is one tap, and closing it puts the buttons straight back.
 */
@Composable
private fun SearchRow(
    query: String,
    onQuery: (String) -> Unit,
    hits: Int,
    active: Int,
    onStep: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        OutlinedTextField(
            modifier = Modifier.weight(1f).focusRequester(focus),
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text(stringRes(R.string.dictate__import_search)) },
        )
        Text(
            text = if (hits == 0) {
                stringRes(R.string.dictate__import_search_none)
            } else {
                stringRes(R.string.dictate__import_search_hits, "current" to (active + 1).toString(), "total" to hits.toString())
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // The counter belongs to the field, not against it: it sat flush on the border.
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        // Smaller and tighter than the default 48dp target, so the three of them together take about
        // as much room as one button and the field keeps the width.
        IconButton(onClick = { onStep(-1) }, enabled = hits > 0, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(22.dp))
        }
        IconButton(onClick = { onStep(1) }, enabled = hits > 0, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(22.dp))
        }
        IconButton(onClick = onClose, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Default.Close, contentDescription = stringRes(R.string.action__cancel), modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun Header(info: SharedFileInfo?, skipped: Int) {
    val context = LocalContext.current
    val prefs by FlorisPreferenceStore
    val providerName = remember {
        val account = ImportTranscriber.accountFor(prefs)
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
        chatModel = chatModelFor(account, preset),
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
