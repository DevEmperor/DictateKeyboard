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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.ui.parseEnterChars
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes

/**
 * Editor for the characters offered by the classic layout's Enter-key long-press popup (issue #196). The
 * user types up to 8 characters into a text field; holding Enter on the classic layout then shows them in
 * a strip to swipe through. Persisted to
 * [dev.patrickgold.florisboard.app.AppPrefs.Dictate.enterLongPressChars]; clearing the field disables the
 * popup so Enter just inserts a newline.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnterLongPressCharsSetting() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val stored by prefs.dictate.enterLongPressChars.collectAsState()

    // Local text seeded from the pref; the sanitised value is persisted on every edit.
    var text by remember(stored) { mutableStateOf(stored) }
    val preview = remember(text) { parseEnterChars(text) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringRes(R.string.dictate__legacy_enter_chars_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringRes(R.string.dictate__legacy_enter_chars_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { new ->
                // At most 8 characters, no line breaks — the popup shows single glyphs.
                val sanitised = new.replace("\n", "").take(8)
                text = sanitised
                scope.launch { prefs.dictate.enterLongPressChars.set(sanitised) }
            },
            singleLine = true,
            label = { Text(stringRes(R.string.dictate__legacy_enter_chars_label)) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (preview.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                preview.forEach { ch ->
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = ch,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
