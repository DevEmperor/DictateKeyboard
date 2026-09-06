/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.smartbar

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.florisboard.lib.compose.pluralsRes
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * How many words and characters are selected, shown in the Smartbar (issue #335).
 *
 * It takes the suggestion strip's place because a selection means nothing is being typed, so the strip is
 * standing empty at exactly the moment this has something to say. That also keeps the whole feature out of
 * the way of everything else: no extra row, no window over the app, and it is gone the instant the
 * selection is.
 *
 * Renders nothing at all unless there is a selection, so the caller can compose it unconditionally.
 */
@Composable
fun SelectionCounterPill(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val editorInstance by context.editorInstance()

    // One subscription for both halves, and it only wakes the composable when one of them actually
    // changes — the content flow itself emits on every keystroke.
    val initial = remember(editorInstance) {
        editorInstance.activeContent.let { it.selection.length to it.selectedText }
    }
    val snapshot by remember(editorInstance) {
        editorInstance.activeContentFlow
            .map { it.selection.length to it.selectedText }
            .distinctUntilChanged()
    }.collectAsState(initial = initial)

    val (length, text) = snapshot
    if (length <= 0) return

    // Counted off the main thread: selecting a whole document hands us the whole document, and the walk
    // over it must not land in a frame.
    var counts by remember { mutableStateOf<SelectionMetrics.Counts?>(null) }
    LaunchedEffect(length, text) {
        counts = withContext(Dispatchers.Default) {
            // An empty text with a non-empty selection is not a contradiction: a selection made in one
            // sweep, or an app that refuses getSelectedText, leaves us the size and nothing else.
            if (text.isEmpty()) SelectionMetrics.charsOnly(length) else SelectionMetrics.of(text)
        }
    }
    val shown = counts ?: return

    val chars = pluralsRes(R.plurals.unit__characters__written, shown.chars, "v" to shown.chars)
    val label = when (val words = shown.words) {
        null -> chars
        else -> "${pluralsRes(R.plurals.unit__words__written, words, "v" to words)} · $chars"
    }

    SnyggRow(
        elementName = FlorisImeUi.SmartbarCandidateClip.elementName,
        modifier = modifier
            .fillMaxHeight()
            .wrapContentWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SnyggText(
            elementName = FlorisImeUi.SmartbarCandidateClipText.elementName,
            text = label,
        )
    }
}
