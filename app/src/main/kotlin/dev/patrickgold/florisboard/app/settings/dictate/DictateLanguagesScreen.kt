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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.DictateLanguages
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.material.ui.JetPrefListItem
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.florisScrollbar
import org.florisboard.lib.compose.stringRes

/**
 * Lets the user pick which dictation languages appear in the recording bar's quick cycle. Selection
 * is stored comma-separated in [prefs.dictate.inputLanguages]; catalog order is preserved and at
 * least one language always stays selected.
 */
@Composable
fun DictateLanguagesScreen() = FlorisScreen {
    title = stringRes(R.string.dictate__languages_title)
    previewFieldVisible = true
    scrollable = false

    val prefs by FlorisPreferenceStore

    content {
        val scope = rememberCoroutineScope()
        val selectionRaw by prefs.dictate.inputLanguages.collectAsState()
        val selectedCodes = remember(selectionRaw) {
            DictateLanguages.parseSelection(selectionRaw).map { it.code }.toSet()
        }
        val state = rememberLazyListState()

        fun toggle(code: String, checked: Boolean) {
            val next = if (checked) selectedCodes + code else selectedCodes - code
            // Preserve catalog order and never allow an empty selection.
            val ordered = DictateLanguages.all.filter { it.code in next }
                .ifEmpty { listOf(DictateLanguages.of(DictateLanguages.DETECT)) }
            scope.launch {
                prefs.dictate.inputLanguages.set(DictateLanguages.serializeSelection(ordered))
                // Keep the active language consistent: if it's no longer selected (e.g. the user just
                // disabled auto-detect while it was active), snap to the first entry — otherwise a stale
                // "detect" would silently keep dictation on auto-detect and show a phantom globe.
                val active = prefs.dictate.activeInputLanguage.get()
                if (ordered.none { it.code == active }) {
                    prefs.dictate.activeInputLanguage.set(ordered.first().code)
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                text = stringRes(R.string.dictate__languages_summary),
                color = LocalContentColor.current.copy(alpha = 0.7f),
            )
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .florisScrollbar(state, isVertical = true),
                state = state,
            ) {
                items(DictateLanguages.all) { lang ->
                    val checked = lang.code in selectedCodes
                    val label = if (lang.code == DictateLanguages.DETECT) {
                        stringRes(R.string.dictate__language_detect)
                    } else {
                        lang.displayName()
                    }
                    JetPrefListItem(
                        modifier = Modifier.clickable { toggle(lang.code, !checked) },
                        text = label,
                        secondaryText = if (lang.code == DictateLanguages.DETECT) null else lang.shortCode,
                        trailing = {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { toggle(lang.code, it) },
                            )
                        },
                    )
                }
            }
        }
    }
}
