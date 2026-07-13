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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.settings.search.settingsSearchAnchor
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.data.mappings.DictateMappings
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import kotlinx.coroutines.launch
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

    content {
        val mappings by prefs.dictate.customMappings.collectAsState()
        val scope = rememberCoroutineScope()

        // Index of the rule being edited; -1 = adding a new one; null = dialog closed.
        var editingIndex by remember { mutableStateOf<Int?>(null) }

        fun write(updated: DictateMappings) {
            scope.launch { prefs.dictate.customMappings.set(updated) }
        }

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
            Spacer(Modifier.height(12.dp))
            MappingSwitchRow(
                title = stringRes(R.string.dictate__mappings_wholeword_title),
                summary = stringRes(R.string.dictate__mappings_wholeword_summary),
                checked = wholeWord,
                onCheckedChange = { wholeWord = it },
            )
            MappingSwitchRow(
                title = stringRes(R.string.dictate__mappings_matchcase_title),
                summary = stringRes(R.string.dictate__mappings_matchcase_summary),
                checked = matchCase,
                onCheckedChange = { matchCase = it },
            )
        }
    }
}

@Composable
private fun MappingSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
