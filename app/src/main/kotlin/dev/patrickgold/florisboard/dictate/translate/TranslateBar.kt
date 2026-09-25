/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.translate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.dictate.translate.TranslateBarController.Side
import dev.patrickgold.florisboard.dictate.translate.TranslateBarController.Status
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard.PanelHeaderButton
import dev.patrickgold.florisboard.ime.smartbar.KeyboardFieldInput
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.rememberSnyggThemeQuery

/** Where the bar sends someone who needs a language it does not have. */
private const val SETTINGS_PATH = "settings/translation"

/**
 * The translate bar (issue #424), above the Smartbar while it is open, laid out like Gboard's: the
 * way back and the two languages on top, and below them a field the full width of the keyboard for the
 * text to translate. The translation is not shown here — it stands in the app's own text field, where
 * it is going to be sent from.
 *
 * The field is drawn, not a real text field — an input method cannot give focus to a view of its own —
 * but it behaves like one: it scrolls sideways as the text outgrows it, it has a cursor that a tap, the
 * arrow keys or a space-bar glide put anywhere, and it lets go of the keys when the app's field is
 * tapped and takes them back when it is tapped itself.
 *
 * Tapping a language opens a row of the choices in place of the language row rather than a popup: a
 * focusable popup over an IME hides the keyboard, and the choices are few enough to scroll through.
 */
@Composable
fun TranslateBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val controller = keyboardManager.translateBar
    val query = keyboardManager.translateQuery.collectAsState().value ?: return
    val focused by keyboardManager.translateFocused.collectAsState()
    val state by controller.state.collectAsState()

    SnyggColumn(
        elementName = FlorisImeUi.SmartbarCandidatesRow.elementName,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(FlorisImeSizing.smartbarHeight),
        ) {
            val picking = state.picking
            when {
                state.status == Status.NoLanguages -> TopRow(onBack = { keyboardManager.closeTranslate() }) {
                    Notice(
                        text = stringRes(R.string.translate__no_languages),
                        action = stringRes(R.string.translate__get_languages),
                    )
                }
                state.status == Status.UnsupportedDevice -> TopRow(onBack = { keyboardManager.closeTranslate() }) {
                    Notice(text = stringRes(R.string.translate__unsupported), action = null)
                }
                picking != null -> TopRow(onBack = { controller.openPicker(null) }) {
                    LanguagePicker(side = picking, state = state, onPick = { controller.pick(picking, it) })
                }
                else -> LanguageRow(
                    state = state,
                    controller = controller,
                    onBack = { keyboardManager.closeTranslate() },
                )
            }
        }
        // The same text line as the searches' (issue #424), the full width of the keyboard.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(FlorisImeSizing.smartbarHeight),
        ) {
            KeyboardFieldInput(
                text = query,
                placeholder = stringRes(R.string.translate__hint),
                icon = Icons.Outlined.Translate,
                focused = focused,
                onTap = { offset -> controller.focus(offset) },
                onClear = { controller.clear() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The back arrow at the start, and [content] in the rest of the row. */
@Composable
private fun TopRow(onBack: () -> Unit, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
        BackButton(onBack)
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { content() }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    PanelHeaderButton(onClick = onClick, modifier = Modifier.size(FlorisImeSizing.smartbarHeight)) {
        SnyggIcon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringRes(R.string.action__back),
            modifier = Modifier.size(FlorisImeSizing.mediaHeaderIconSize),
        )
    }
}

/**
 * Back arrow, the two languages with the swap between them, and the translation's state. The back
 * arrow and the state sit at the ends *over* the row rather than beside it, so "source ⇄ target" is
 * centred on the keyboard as one group — not the swap on the middle with each chip hanging off it.
 */
@Composable
private fun LanguageRow(state: TranslateBarController.State, controller: TranslateBarController, onBack: () -> Unit) {
    val style = rememberSnyggThemeQuery(FlorisImeUi.SmartbarCandidatesRow.elementName)
    val side = FlorisImeSizing.smartbarHeight
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
        // Each chip gets at most half of what is left between the two end slots and the swap button.
        val chipMax = ((maxWidth - side * 3) / 2).coerceAtLeast(48.dp)
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val sourceLabel = when {
                state.source != null -> chipLabel(state.source)
                state.detected != null -> withFlag(
                    state.detected,
                    stringRes(R.string.translate__detected, "language" to TranslationLanguageNames.of(state.detected)),
                )
                else -> stringRes(R.string.translate__detect)
            }
            LanguageChip(
                label = sourceLabel,
                modifier = Modifier.widthIn(max = chipMax),
                onClick = { controller.openPicker(Side.SOURCE) },
            )
            PanelHeaderButton(
                onClick = { controller.swap() },
                enabled = state.effectiveSource != null,
                modifier = Modifier.size(side),
            ) {
                SnyggIcon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = stringRes(R.string.translate__swap),
                    modifier = Modifier.size(FlorisImeSizing.mediaHeaderIconSize),
                )
            }
            LanguageChip(
                label = chipLabel(state.target),
                modifier = Modifier.widthIn(max = chipMax),
                onClick = { controller.openPicker(Side.TARGET) },
            )
        }
        Box(modifier = Modifier.align(Alignment.CenterStart)) { BackButton(onBack) }
        // The state of the current translation, in a fixed square: a spinner while it runs, and a mark
        // when it did not work — which the next keystroke retries.
        Box(modifier = Modifier.align(Alignment.CenterEnd).size(side), contentAlignment = Alignment.Center) {
            when (val status = state.status) {
                Status.Translating -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = style.foreground(),
                )
                is Status.NotInstalled -> PanelHeaderButton(
                    onClick = { FlorisImeService.launchSettings(SETTINGS_PATH) },
                    modifier = Modifier.size(side),
                ) {
                    SnyggIcon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = stringRes(
                            R.string.translate__missing,
                            "language" to status.missing.joinToString(", ") { TranslationLanguageNames.of(it) },
                        ),
                        modifier = Modifier.size(FlorisImeSizing.mediaHeaderIconSize),
                    )
                }
                Status.Failed -> SnyggIcon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = stringRes(R.string.translate__failed),
                    modifier = Modifier.size(FlorisImeSizing.mediaHeaderIconSize),
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun LanguagePicker(
    side: Side,
    state: TranslateBarController.State,
    onPick: (String?) -> Unit,
) {
    val choices: List<String?> = buildList {
        if (side == Side.SOURCE) add(null)
        addAll(TranslationLanguageNames.sorted(state.installed + TranslationCatalog.ENGLISH))
    }
    val selected = if (side == Side.SOURCE) state.source else state.target
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(choices, key = { it ?: "" }) { code ->
            LanguageChip(
                label = code?.let(::chipLabel) ?: stringRes(R.string.translate__detect),
                selected = code == selected,
                onClick = { onPick(code) },
            )
        }
        item(key = "more") {
            LanguageChip(
                label = stringRes(R.string.translate__more_languages),
                onClick = { FlorisImeService.launchSettings(SETTINGS_PATH) },
            )
        }
    }
}

@Composable
private fun LanguageChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val style = rememberSnyggThemeQuery(FlorisImeUi.SmartbarCandidatesRow.elementName)
    val inputFeedbackController = LocalInputFeedbackController.current
    Box(
        modifier = modifier
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color(0x55808080) else Color(0x22808080))
            .clickable {
                inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = style.foreground(),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A message in the language row's place, with the way out when there is one. */
@Composable
private fun Notice(text: String, action: String?) {
    val style = rememberSnyggThemeQuery(FlorisImeUi.SmartbarCandidatesRow.elementName)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = text,
            color = style.foreground(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (action != null) {
            LanguageChip(label = action, selected = true, onClick = { FlorisImeService.launchSettings(SETTINGS_PATH) })
        }
    }
}

/** A language as a chip reads it: its flag, if it has one, before its name. */
private fun chipLabel(code: String): String = withFlag(code, TranslationLanguageNames.of(code))

private fun withFlag(code: String, text: String): String =
    translationFlagOf(code)?.let { "$it $text" } ?: text
