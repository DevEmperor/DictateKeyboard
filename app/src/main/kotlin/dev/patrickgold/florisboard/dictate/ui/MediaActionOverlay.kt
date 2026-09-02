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

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import org.florisboard.lib.compose.rippleClickable
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * The long-press menu of a media panel: a preview on one side, the actions on the other, over the
 * dimmed panel.
 *
 * This is the clipboard's popup surface, generalised so the sticker and GIF panels can use the same
 * one. It replaces a Material `DropdownMenu`, for two reasons that both matter inside a keyboard.
 *
 * A `DropdownMenu` is a **popup window**, and a popup raised by an input method is a problem even when
 * it is not focusable: it animates in on its own schedule, which is what a user sees as a menu that
 * arrives half-transparent and then fills in (#308), and a focusable one takes window focus off the
 * editor entirely, which closes the keyboard under it. Drawing inside the panel's own composition has
 * neither failure mode, needs no back-key interception to dismiss, and gives the room for a preview
 * large enough to tell two similar stickers apart.
 *
 * The Snygg elements are the clipboard's own (`clipboard-item-action…`). They describe an action row
 * in a media panel rather than anything about clipboards, and every bundled theme already styles
 * them — inventing parallel `sticker-item-action` names would mean the same rows, restyled in every
 * stylesheet, to look identical.
 *
 * @param onDismiss called when the user taps beside the sheet. Tapping the backdrop is the only way
 *   out, which is why the caller must not leave an action unreachable behind a scroll.
 */
@Composable
fun MediaActionOverlay(
    onDismiss: () -> Unit,
    preview: @Composable ColumnScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
) {
    SnyggRow(
        modifier = Modifier
            .fillMaxSize()
            // Consumes the tap so it never reaches the grid underneath, which would insert a sticker
            // on the way out of a menu the user was only closing.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        SnyggColumn(
            modifier = Modifier.weight(0.5f),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = preview,
        )
        SnyggColumn(modifier = Modifier.weight(0.5f)) {
            SnyggColumn(
                elementName = FlorisImeUi.ClipboardItemActions.elementName,
                // More actions than the clipboard's three can appear here — a WebP sticker in a
                // collection with packs offers six — and the panel is not always tall enough for all
                // of them on a short keyboard.
                modifier = Modifier.verticalScroll(rememberScrollState()),
                content = actions,
            )
        }
    }
}

/** One row of [MediaActionOverlay]: an icon, a label, and the whole row as the target. */
@Composable
fun MediaAction(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    SnyggRow(
        elementName = FlorisImeUi.ClipboardItemAction.elementName,
        modifier = modifier
            .fillMaxWidth()
            .rippleClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SnyggIcon(
            elementName = FlorisImeUi.ClipboardItemActionIcon.elementName,
            imageVector = icon,
        )
        SnyggText(
            elementName = FlorisImeUi.ClipboardItemActionText.elementName,
            modifier = Modifier.weight(1f),
            text = text,
        )
    }
}
