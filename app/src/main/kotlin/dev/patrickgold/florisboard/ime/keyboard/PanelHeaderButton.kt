/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import org.florisboard.lib.snygg.ui.SnyggIconButton

/**
 * A button in a panel's header row — the back arrow, the settings shortcut, the clipboard's toggles.
 *
 * It exists because eighteen of these were spelled out by hand across seven panels, each repeating the
 * same element name, and none of them asking for input feedback. The clipboard was the starkest case:
 * the panel every other panel was aligned to in #317, and the only one with no tick anywhere in it,
 * while the sticker and GIF panels ticked on every cell. Gathering them here means the element name is
 * named once and the feedback cannot be forgotten by the next panel (issue #326).
 *
 * Sizing deliberately stays with the caller. Most headers want a square the height of the Smartbar, but
 * the clipboard constrains its buttons differently and two of them mirror themselves for RTL, so a size
 * baked in here would only have to be undone.
 */
@Composable
fun PanelHeaderButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val inputFeedbackController = LocalInputFeedbackController.current
    SnyggIconButton(
        elementName = FlorisImeUi.ClipboardHeaderButton.elementName,
        modifier = modifier,
        onClick = {
            inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
            onClick()
        },
        onLongClick = onLongClick?.let {
            {
                inputFeedbackController.keyLongPress(TextKeyData.UNSPECIFIED)
                it()
            }
        },
        enabled = enabled,
        content = content,
    )
}
