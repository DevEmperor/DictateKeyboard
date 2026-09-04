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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.rememberSnyggThemeQuery

/** Half a second on, half a second off — the familiar text-cursor cadence. */
private const val CaretBlinkMillis = 1000

/**
 * The search bar shared by the emoji search and the GIF search (issue #274).
 *
 * Neither search owns a real text field: the query is typed on the keyboard below and intercepted in
 * the input pipeline (see `KeyboardManager.handleEmojiSearchKey`), so this bar only *renders* a query
 * that lives elsewhere. That is exactly why it carries a blinking caret — without one, a keyboard whose
 * keystrokes no longer reach the app looks broken rather than busy.
 *
 * It replaces two near-identical hand-rolled rows that had drifted apart: the emoji one squeezed the
 * query into 120 dp beside its results and had no way to clear it, the GIF one had no caret and a
 * different pill.
 *
 * Leaving the search belongs in [leading] and is drawn as a back arrow, never a second ✕ — the ✕ in
 * the field means "empty the query", and two of them side by side read as the same button twice.
 */
@Composable
fun KeyboardSearchBar(
    query: String,
    placeholder: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable (RowScope.() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val style = rememberSnyggThemeQuery(FlorisImeUi.SmartbarCandidatesRow.elementName)
    val inputFeedbackController = LocalInputFeedbackController.current
    SnyggRow(
        elementName = FlorisImeUi.SmartbarCandidatesRow.elementName,
        modifier = modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.smartbarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke(this)
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 6.dp, vertical = 5.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0x22808080))
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SnyggIcon(
                imageVector = Icons.Default.Search,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(18.dp),
            )
            // The caret marks where the next character lands, so with nothing typed it belongs *before*
            // the placeholder, not after it. The text takes only the width it needs (fill = false) so
            // the caret sits against it; the row around them holds the remaining space open.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (query.isEmpty()) {
                    Caret(color = style.foreground())
                    Text(
                        modifier = Modifier.padding(start = 6.dp),
                        text = placeholder,
                        color = style.foreground().copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        modifier = Modifier.weight(1f, fill = false),
                        text = query,
                        color = style.foreground(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Caret(color = style.foreground())
                }
            }
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = {
                            inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                            onClear()
                        })
                        .padding(6.dp),
                ) {
                    SnyggIcon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringRes(R.string.action__clear),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        trailing?.invoke(this)
    }
}

/** A blinking text cursor, marking where the next keystroke lands. */
@Composable
private fun Caret(color: Color) {
    val transition = rememberInfiniteTransition(label = "search-caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        // Hard on/off rather than a fade: a fading caret reads as a loading indicator.
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = CaretBlinkMillis
                1f at 0 using LinearEasing
                1f at CaretBlinkMillis / 2 using LinearEasing
                0f at CaretBlinkMillis / 2 + 1 using LinearEasing
                0f at CaretBlinkMillis
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "search-caret-alpha",
    )
    Box(
        modifier = Modifier
            .padding(start = 2.dp)
            .alpha(alpha)
            .width(2.dp)
            .height(18.dp)
            .background(color),
    )
}
