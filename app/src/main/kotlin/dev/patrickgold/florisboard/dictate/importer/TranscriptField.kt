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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.florisboard.lib.compose.florisScrollbar

/**
 * The transcript, editable, with the app's own scrollbar and the search hits marked.
 *
 * Not an `OutlinedTextField`: that one scrolls internally with a bar of its own that turns into a
 * long rounded slab next to a six-minute transcript, and it gives no way at all to reach the scroll
 * position — which the search needs to move the view to a hit. A [BasicTextField] laid out at its
 * natural height inside a scrolling box hands both back: `florisScrollbar` draws the same thin bar
 * as everywhere else in the app, and the layout result says where a match sits.
 */
@Composable
fun TranscriptField(
    value: String,
    onValueChange: (String) -> Unit,
    matches: List<IntRange>,
    activeMatch: Int,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val colors = MaterialTheme.colorScheme

    // Every hit shaded, the current one solid, so the eye can both count them and find the one the
    // arrows are on.
    val highlight = remember(matches, activeMatch, colors) {
        if (matches.isEmpty()) {
            VisualTransformation.None
        } else {
            VisualTransformation { text ->
                TransformedText(
                    buildAnnotatedString {
                        append(text.text)
                        matches.forEachIndexed { index, range ->
                            val end = (range.last + 1).coerceAtMost(text.text.length)
                            if (range.first >= end) return@forEachIndexed
                            addStyle(
                                SpanStyle(
                                    background = if (index == activeMatch) colors.primary else colors.primaryContainer,
                                    color = if (index == activeMatch) colors.onPrimary else colors.onPrimaryContainer,
                                ),
                                range.first,
                                end,
                            )
                        }
                    },
                    OffsetMapping.Identity,
                )
            }
        }
    }

    // Bring the current hit into view. The text may be laid out after the match moves, so both are
    // keys — otherwise the first search on a fresh screen scrolls nowhere.
    LaunchedEffect(activeMatch, matches, layout) {
        val result = layout ?: return@LaunchedEffect
        val range = matches.getOrNull(activeMatch) ?: return@LaunchedEffect
        val top = runCatching { result.getBoundingBox(range.first).top }.getOrNull() ?: return@LaunchedEffect
        // A third of the way down rather than flush to the top: a hit at the very edge reads as if
        // there were nothing before it.
        val target = (top.toInt() - scroll.viewportSize / 3).coerceIn(0, scroll.maxValue)
        scroll.animateScrollTo(target)
    }

    Box(
        modifier = modifier
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp))
            .background(colors.surface, RoundedCornerShape(12.dp))
            .verticalScroll(scroll)
            .florisScrollbar(scroll, isVertical = true),
    ) {
        BasicTextField(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            value = value,
            onValueChange = onValueChange,
            textStyle = LocalTextStyle.current.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = highlight,
            onTextLayout = { layout = it },
        )
    }
}

/** Every occurrence of [needle] in [haystack], case-insensitive. Empty for a blank search. */
fun findMatches(haystack: String, needle: String): List<IntRange> {
    if (needle.isBlank()) return emptyList()
    val out = ArrayList<IntRange>()
    var from = 0
    while (true) {
        val at = haystack.indexOf(needle, from, ignoreCase = true)
        if (at < 0) break
        out.add(at until (at + needle.length))
        // Overlapping hits would each want their own highlight and confuse the counter; step past.
        from = at + needle.length
    }
    return out
}
