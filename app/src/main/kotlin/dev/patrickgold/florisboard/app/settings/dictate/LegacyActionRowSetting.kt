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

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.ui.LegacyEditAction
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes
import kotlin.math.roundToInt

/**
 * Drag-and-drop editor for the classic dictation layout's action row (issue #183/#194). Shows the
 * currently assigned buttons as a reorderable list (long-press the ⣿ handle to drag), with a ✕ to remove
 * each and a palette of the remaining actions to add. Everything is persisted to
 * [dev.patrickgold.florisboard.app.AppPrefs.Dictate.legacyActionRow] on every change.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LegacyActionRowSetting() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val raw by prefs.dictate.legacyActionRow.collectAsState()

    // Local editable copy. Keyed on the pref value so it re-seeds if changed elsewhere (e.g. reset from a
    // fresh entry); within a session this editor is the only writer, so it just tracks the live edits.
    val items = remember(raw) { mutableStateListOf<LegacyEditAction>().apply { addAll(LegacyEditAction.parse(raw)) } }
    fun persist() {
        scope.launch { prefs.dictate.legacyActionRow.set(LegacyEditAction.serialize(items.toList())) }
    }

    val density = LocalDensity.current
    val itemHeight = 52.dp
    val hPx = with(density) { itemHeight.toPx() }
    var dragging by remember { mutableStateOf<LegacyEditAction?>(null) }
    var startIndex by remember { mutableIntStateOf(0) }
    var accum by remember { mutableFloatStateOf(0f) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringRes(R.string.dictate__legacy_action_row_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringRes(R.string.dictate__legacy_action_row_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        items.forEach { action ->
            key(action) {
                val index = items.indexOf(action)
                val isDragging = dragging == action
                val translation = if (isDragging) accum - (index - startIndex) * hPx else 0f
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = translation },
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize().padding(vertical = 3.dp),
                        shape = RoundedCornerShape(10.dp),
                        tonalElevation = if (isDragging) 8.dp else 2.dp,
                        shadowElevation = if (isDragging) 6.dp else 0.dp,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(start = 14.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(action.icon, contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.size(14.dp))
                            Text(text = stringRes(action.labelRes), modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                val i = items.indexOf(action)
                                if (i >= 0) { items.removeAt(i); persist() }
                            }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringRes(R.string.dictate__legacy_action_row_remove),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.DragIndicator,
                                contentDescription = stringRes(R.string.dictate__legacy_action_row_drag),
                                modifier = Modifier
                                    .size(28.dp)
                                    .padding(end = 6.dp)
                                    .pointerInput(Unit) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                dragging = action
                                                startIndex = items.indexOf(action)
                                                accum = 0f
                                            },
                                            onDrag = { change, delta ->
                                                change.consume()
                                                accum += delta.y
                                                val cur = items.indexOf(action)
                                                val target = (startIndex + (accum / hPx).roundToInt())
                                                    .coerceIn(0, items.size - 1)
                                                if (cur >= 0 && target != cur) {
                                                    items.add(target, items.removeAt(cur))
                                                }
                                            },
                                            onDragEnd = { dragging = null; persist() },
                                            onDragCancel = { dragging = null; persist() },
                                        )
                                    },
                            )
                        }
                    }
                }
            }
        }

        // Palette of actions not yet in the row — always shown, so the user can see what else is available.
        // When the row is full (MAX_ROW) the chips are greyed out and tapping one explains (via a tooltip)
        // that a button must be removed first, rather than silently doing nothing.
        val available = LegacyEditAction.entries.filter { it !in items }
        val full = items.size >= LegacyEditAction.MAX_ROW
        if (available.isNotEmpty()) {
            Text(
                text = stringRes(R.string.dictate__legacy_action_row_add),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                available.forEach { action ->
                    if (full) {
                        val tooltipState = rememberTooltipState()
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text(stringRes(R.string.dictate__legacy_action_row_full)) } },
                            state = tooltipState,
                        ) {
                            AssistChip(
                                onClick = { scope.launch { tooltipState.show() } },
                                label = { Text(stringRes(action.labelRes)) },
                                leadingIcon = {
                                    Icon(action.icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    leadingIconContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                ),
                            )
                        }
                    } else {
                        AssistChip(
                            onClick = { if (items.size < LegacyEditAction.MAX_ROW) { items.add(action); persist() } },
                            label = { Text(stringRes(action.labelRes)) },
                            leadingIcon = {
                                Icon(action.icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                            },
                        )
                    }
                }
            }
        }

        TextButton(
            onClick = { items.clear(); items.addAll(LegacyEditAction.DEFAULT); persist() },
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(stringRes(R.string.dictate__legacy_action_row_reset))
        }
    }
}
