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

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.settings.search.settingsSearchAnchor
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.data.mappings.DictateMappings
import dev.patrickgold.florisboard.dictate.data.mappings.DictateMappingsText
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.compose.SwitchRow
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.showLongToast
import org.florisboard.lib.compose.FlorisIconButton
import org.florisboard.lib.compose.florisDialogScroll
import org.florisboard.lib.compose.stringRes

/**
 * Manager for the custom find-and-replace dictionary (issue #129): a list of `from → to` rules applied
 * deterministically to every finished transcript before it is inserted. Distinct from "Custom words",
 * which only biases the speech model's prompt. Each rule can match whole words only and/or case-sensitively.
 */
@Composable
fun DictateMappingsScreen() = FlorisScreen {
    title = stringRes(R.string.dictate__mappings_title)
    // Show the keyboard-test field so find-and-replace rules can be tried out live (same as the other
    // dictate settings pages).
    previewFieldVisible = true
    iconSpaceReserved = true

    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mappings by prefs.dictate.customMappings.collectAsState()

    // Index of the rule being edited; -1 = adding a new one; null = dialog closed. Declared out here
    // because the overflow menu and the content share it.
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    // Rules read out of a file, waiting for the user to confirm what they would do (issue #389).
    var pendingImport by remember { mutableStateOf<DictateMappingsText.ImportReport?>(null) }

    fun write(updated: DictateMappings) {
        scope.launch { prefs.dictate.customMappings.set(updated) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                readText(context, uri)?.let { DictateMappingsText.plan(prefs.dictate.customMappings.get(), it) }
            }
            when {
                report == null -> context.showLongToast(R.string.dictate__file_unreadable)
                report.added.isEmpty() && report.alreadyKnown == 0 ->
                    context.showLongToast(R.string.dictate__mappings_import_empty)
                else -> pendingImport = report
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/tab-separated-values"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = DictateMappingsText.format(prefs.dictate.customMappings.get().items)
            val ok = withContext(Dispatchers.IO) { writeText(context, uri, text) }
            context.showLongToast(
                if (ok) R.string.dictate__file_export_done else R.string.dictate__file_export_failed,
            )
        }
    }

    actions {
        var menuExpanded by remember { mutableStateOf(false) }
        FlorisIconButton(
            onClick = { menuExpanded = true },
            icon = Icons.Default.MoreVert,
        )
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                text = { Text(stringRes(R.string.dictate__mappings_import)) },
                onClick = {
                    menuExpanded = false
                    // A rules file is tab-separated text; the octet-stream fallback is there because
                    // plenty of SAF providers hand any unfamiliar extension over as opaque bytes.
                    importLauncher.launch(arrayOf("text/*", "application/octet-stream"))
                },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.FileUpload, contentDescription = null) },
                text = { Text(stringRes(R.string.dictate__mappings_export)) },
                onClick = {
                    menuExpanded = false
                    exportLauncher.launch(context.getString(R.string.dictate__mappings_export_filename))
                },
            )
        }
    }

    content {
        PreferenceGroup(title = stringRes(R.string.dictate__mappings_group)) {
            if (mappings.items.isEmpty()) {
                Preference(
                    icon = Icons.Default.SwapHoriz,
                    modifier = Modifier.settingsSearchAnchor("dictate__mappings_empty_title"),
                    title = stringRes(R.string.dictate__mappings_empty_title),
                    summary = stringRes(R.string.dictate__mappings_empty_summary),
                    onClick = { editingIndex = -1 },
                )
            } else {
                val wholeWord = stringRes(R.string.dictate__mappings_opt_wholeword)
                val substring = stringRes(R.string.dictate__mappings_opt_substring)
                val caseSensitive = stringRes(R.string.dictate__mappings_opt_casesensitive)
                val caseInsensitive = stringRes(R.string.dictate__mappings_opt_caseinsensitive)
                mappings.items.forEachIndexed { index, m ->
                    Preference(
                        icon = Icons.Default.SwapHoriz,
                        title = "${m.from}  →  ${m.to.ifBlank { "∅" }}",
                        summary = listOf(
                            if (m.wholeWord) wholeWord else substring,
                            if (m.matchCase) caseSensitive else caseInsensitive,
                        ).joinToString(" · "),
                        onClick = { editingIndex = index },
                    )
                }
            }

            Preference(
                icon = Icons.Default.Add,
                modifier = Modifier.settingsSearchAnchor("dictate__mappings_add"),
                title = stringRes(R.string.dictate__mappings_add),
                onClick = { editingIndex = -1 },
            )
        }

        pendingImport?.let { report ->
            ImportMappingsDialog(
                report = report,
                onDismiss = { pendingImport = null },
                onConfirm = {
                    pendingImport = null
                    write(report.merged)
                    scope.launch {
                        context.showLongToast(
                            R.string.dictate__mappings_import_done,
                            "count" to report.added.size,
                        )
                    }
                },
            )
        }

        editingIndex?.let { idx ->
            val existing = mappings.items.getOrNull(idx)
            MappingEditorDialog(
                mapping = existing ?: DictateMappings.Mapping(from = "", to = ""),
                onDismiss = { editingIndex = null },
                onSave = { m ->
                    write(if (existing != null) mappings.set(idx, m) else mappings.add(m))
                    editingIndex = null
                },
                onDelete = if (existing != null) {
                    { write(mappings.removeAt(idx)); editingIndex = null }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun MappingEditorDialog(
    mapping: DictateMappings.Mapping,
    onDismiss: () -> Unit,
    onSave: (DictateMappings.Mapping) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var from by remember { mutableStateOf(mapping.from) }
    var to by remember { mutableStateOf(mapping.to) }
    var matchCase by remember { mutableStateOf(mapping.matchCase) }
    var wholeWord by remember { mutableStateOf(mapping.wholeWord) }

    JetPrefAlertDialog(
        scrollModifier = florisDialogScroll(),
        title = stringRes(R.string.dictate__mappings_editor_title),
        confirmLabel = stringRes(R.string.action__ok),
        dismissLabel = stringRes(R.string.action__cancel),
        neutralLabel = if (onDelete != null) stringRes(R.string.action__delete) else null,
        onConfirm = {
            // Empty "from" rules are skipped at apply time; an empty "to" is valid (removes the word).
            onSave(mapping.copy(from = from.trim(), to = to, matchCase = matchCase, wholeWord = wholeWord))
        },
        onDismiss = onDismiss,
        onNeutral = { onDelete?.invoke() },
    ) {
        Column {
            OutlinedTextField(
                value = from,
                onValueChange = { from = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringRes(R.string.dictate__mappings_field_from)) },
                placeholder = { Text(stringRes(R.string.dictate__mappings_field_from_placeholder)) },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = to,
                onValueChange = { to = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringRes(R.string.dictate__mappings_field_to)) },
                placeholder = { Text(stringRes(R.string.dictate__mappings_field_to_placeholder)) },
            )
            Spacer(Modifier.height(4.dp))
            SwitchRow(
                title = stringRes(R.string.dictate__mappings_wholeword_title),
                summary = stringRes(R.string.dictate__mappings_wholeword_summary),
                checked = wholeWord,
                onCheckedChange = { wholeWord = it },
            )
            SwitchRow(
                title = stringRes(R.string.dictate__mappings_matchcase_title),
                summary = stringRes(R.string.dictate__mappings_matchcase_summary),
                checked = matchCase,
                onCheckedChange = { matchCase = it },
            )
        }
    }
}

/**
 * What an imported rules file would do, before it does it.
 *
 * Rules are cheap — no tokens, no length limit — so this dialog is shorter than the word list's. It is
 * still a dialog rather than a toast, because a file can carry hundreds of rules that rewrite every
 * transcript from here on, and "undo" for that is deleting them one at a time.
 */
@Composable
private fun ImportMappingsDialog(
    report: DictateMappingsText.ImportReport,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    JetPrefAlertDialog(
        // Same width as the word-list dialogs; a rule is wider than a word and wraps badly otherwise.
        modifier = Modifier.fillMaxWidth(0.96f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        scrollModifier = florisDialogScroll(),
        title = stringRes(R.string.dictate__mappings_import_title),
        confirmLabel = stringRes(R.string.action__add),
        dismissLabel = stringRes(R.string.action__cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    ) {
        Column {
            Text(stringRes(R.string.dictate__mappings_import_message, "count" to report.added.size))
            if (report.alreadyKnown > 0) {
                Text(
                    modifier = Modifier.padding(top = 4.dp),
                    text = stringRes(R.string.dictate__mappings_import_known, "count" to report.alreadyKnown),
                )
            }
            if (report.unusable > 0) {
                Text(
                    modifier = Modifier.padding(top = 4.dp),
                    text = stringRes(R.string.dictate__mappings_import_unusable, "count" to report.unusable),
                )
            }
            if (report.added.isNotEmpty()) {
                Text(
                    modifier = Modifier.padding(top = 8.dp),
                    text = report.added.take(6).joinToString("\n") { "${it.from}  \u2192  ${it.to.ifBlank { "\u2205" }}" } +
                        if (report.added.size > 6) "\n…" else "",
                    fontStyle = FontStyle.Italic,
                )
            }
            Text(
                modifier = Modifier.padding(top = 8.dp),
                text = stringRes(R.string.dictate__mappings_import_format),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
