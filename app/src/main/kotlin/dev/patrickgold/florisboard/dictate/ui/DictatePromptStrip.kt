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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShortText
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.dictate.data.prompts.PromptModel
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/** Matches the themed `smartbar-action-tile` shape (`rounded-corner(20%)`) for the highlight ring. */
private val ChipShape = RoundedCornerShape(percent = 20)

/**
 * The contextual prompt chip strip shown in the Smartbar's center area, in place of the candidates,
 * while there is an active text selection and rewording is enabled (roadmap 4.3). Each chip runs the
 * matching prompt via [DictateController.applyPrompt] on the current selection (or, for free prompts,
 * generates from the instruction and replaces it). Horizontally scrollable so any number of prompts
 * fits. Used only in the PANEL layout mode; the ROW mode shows [DictatePromptRow] above the Smartbar.
 */
@Composable
fun DictatePromptStrip(
    prompts: List<PromptModel>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    SnyggRow(
        elementName = FlorisImeUi.SmartbarSharedActionsRow.elementName,
        modifier = modifier
            .fillMaxSize()
            .horizontalScroll(scrollState)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        prompts.forEach { prompt ->
            DictatePromptChip(
                icon = dictatePromptIcon(prompt),
                text = prompt.name.orEmpty(),
                onClick = { DictateController.applyPrompt(context, prompt) },
                onLongClick = { editPromptInSettings(prompt) },
                modifier = Modifier.padding(horizontal = 3.dp),
            )
        }
    }
}

/**
 * The always-on rewording prompt row (ROW layout mode): a horizontally scrollable strip pinned above
 * the Smartbar, so every prompt is one tap away without opening a panel. The live-prompt chip is the
 * first (leftmost) entry, followed by the user's saved prompts.
 *
 * Behavior depends on the dictation state:
 *  - idle: tapping a prompt reword-applies it immediately (live chip records a spoken instruction);
 *  - recording/transcribing: tapping a prompt *queues* it (toggle) to be applied in tap order once the
 *    transcript is ready, and the queued chips are highlighted in the accent color.
 */
@Composable
fun DictatePromptRow(
    prompts: List<PromptModel>,
    modifier: Modifier = Modifier,
    rows: Int = 1,
) {
    val context = LocalContext.current
    val inputFeedbackController = LocalInputFeedbackController.current
    val dictateState by DictateController.state.collectAsState()
    val pending by DictateController.pendingPrompts.collectAsState()
    val livePromptActive by DictateController.livePromptActive.collectAsState()
    val isCapturing = dictateState is DictateController.UiState.Recording ||
        dictateState is DictateController.UiState.Transcribing
    // Slightly taller than the Smartbar and with a touch of vertical padding inside each chip, so the
    // always-on row gives the prompt buttons a more comfortable hit area than the compact Smartbar.
    val rowChipPadding = PaddingValues(horizontal = 2.dp, vertical = 5.dp)

    val liveChip: @Composable () -> Unit = {
        DictateLivePromptChip(
            onClick = { DictateController.startLivePrompt(context) },
            modifier = Modifier.padding(horizontal = 1.5.dp),
            tapPadding = rowChipPadding,
            // Compact row: show only the voice icon to leave more width for the saved-prompt chips.
            iconOnly = true,
            // Accent-highlight it while its recording runs (tap again to stop — startLivePrompt toggles).
            highlighted = livePromptActive,
        )
    }
    val promptChip: @Composable (PromptModel) -> Unit = { prompt ->
        DictatePromptChip(
            icon = dictatePromptIcon(prompt),
            text = prompt.name.orEmpty(),
            onClick = {
                // While a recording/transcription is in flight, queue the prompt instead of applying it;
                // otherwise apply it right away. Queuing is a silent state change, so give a haptic tick.
                if (isCapturing) {
                    inputFeedbackController.keyPress()
                    DictateController.togglePendingPrompt(prompt)
                } else {
                    DictateController.applyPrompt(context, prompt)
                }
            },
            onLongClick = { editPromptInSettings(prompt) },
            modifier = Modifier.padding(horizontal = 1.5.dp),
            tapPadding = rowChipPadding,
            highlighted = pending.any { it.id == prompt.id },
        )
    }

    // One shared scroll state so the (optional) second row scrolls together with the first — it's really
    // one strip that happens to wrap onto two lines (#194), not two independently-scrolling rows.
    val scrollState = rememberScrollState()
    if (rows >= 2) {
        // Reading order stays left-to-right: the live chip + first half on the top line, the rest below;
        // both lines share the one horizontal scroll.
        val half = (prompts.size + 1) / 2
        Column(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 4.dp),
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                liveChip()
                prompts.take(half).forEach { promptChip(it) }
            }
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                prompts.drop(half).forEach { promptChip(it) }
            }
        }
    } else {
        SnyggRow(
            elementName = FlorisImeUi.SmartbarSharedActionsRow.elementName,
            modifier = modifier
                .fillMaxWidth()
                .height(FlorisImeSizing.smartbarHeight * 1.25f)
                .horizontalScroll(scrollState)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            liveChip()
            prompts.forEach { promptChip(it) }
        }
    }
}

/**
 * Long-pressing a saved prompt chip jumps straight to that prompt's editor in the settings app by
 * deep-linking to the prompts screen with the prompt id as a route argument, so the screen opens that
 * prompt's editor directly.
 */
internal fun editPromptInSettings(prompt: PromptModel) {
    FlorisImeService.launchSettings("settings/dictate/prompts?editPromptId=${prompt.id}")
}

/**
 * A single rewording prompt chip: a themed rounded pill (reusing the Smartbar action-tile element so
 * it inherits the active theme) with a small leading icon, a gap, the label and some trailing room.
 * Shared by the contextual strip, the always-on row and the prompt panel so the styling stays in sync.
 *
 * @param iconSize leading-icon size (the panel uses a larger one than the compact row).
 * @param tapPadding extra padding inside the pill to enlarge the touch target (panel only).
 * @param highlighted draws an accent tint + ring, used to mark a queued/pending prompt.
 * @param onLongClick optional long-press handler (e.g. jump to the prompt's edit screen).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DictatePromptChip(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
    tapPadding: PaddingValues = PaddingValues(0.dp),
    highlighted: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    // Icon-only variant: drops the label and adds a little side padding so the pill stays a comfortable
    // tap target while taking less width — used for the live-prompt chip in the compact row.
    iconOnly: Boolean = false,
) {
    val prefs by FlorisPreferenceStore
    val accent by prefs.theme.accentColor.collectAsState() // follows the user's keyboard accent.
    SnyggRow(
        elementName = FlorisImeUi.SmartbarActionTile.elementName,
        modifier = modifier,
        // Applied between the themed background and the themed padding: the accent fill/ring paint on
        // top of the tile background (and are clipped to the tile shape by it), the ripple stays on top.
        clickAndSemanticsModifier = Modifier
            .then(
                if (highlighted) {
                    Modifier
                        .background(accent.copy(alpha = 0.22f))
                        .border(1.5.dp, accent, ChipShape)
                } else {
                    Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(tapPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SnyggIcon(
            elementName = "${FlorisImeUi.SmartbarActionTile.elementName}-icon",
            imageVector = icon,
            // Icon-only chips hide the label, so the icon carries the accessibility name instead.
            contentDescription = if (iconOnly) text else null,
            // Slightly smaller than the theme default so the icon reads as a hint, not the focus. When
            // there's no label, a little horizontal padding keeps the pill from collapsing to a tiny square.
            modifier = Modifier
                .then(if (iconOnly) Modifier.padding(horizontal = 7.dp) else Modifier)
                .size(iconSize),
        )
        if (!iconOnly) {
            // Clear gap between the icon and the label.
            Spacer(modifier = Modifier.width(6.dp))
            SnyggText(
                elementName = "${FlorisImeUi.SmartbarActionTile.elementName}-text",
                // A touch of trailing room so the label never hugs the pill's right edge.
                modifier = Modifier.padding(end = 6.dp),
                text = text,
            )
        }
    }
}

/**
 * The live-prompt chip (voice + "Live prompt"): records a spoken instruction and hands it to the
 * rewording model. Rendered alongside the saved-prompt chips in the panel/row so the live prompt lives
 * with the other prompts instead of as a separate Smartbar button. Uses a distinct voice icon so it is
 * not confused with the free-prompt sparkle.
 */
@Composable
internal fun DictateLivePromptChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
    tapPadding: PaddingValues = PaddingValues(0.dp),
    iconOnly: Boolean = false,
    highlighted: Boolean = false,
) {
    DictatePromptChip(
        icon = Icons.Default.RecordVoiceOver,
        text = stringRes(R.string.quick_action__dictate_live_prompt),
        onClick = onClick,
        modifier = modifier,
        iconSize = iconSize,
        tapPadding = tapPadding,
        iconOnly = iconOnly,
        highlighted = highlighted,
    )
}

/**
 * Distinct icon per prompt type, matching the management screen so users learn one set:
 *  - `[snippet]` (inserted literally, no API call) → short-text glyph
 *  - "requires selection" (rewrites the highlighted/whole text) → select-all glyph
 *  - free prompt (generates from the instruction) → sparkle glyph
 */
internal fun dictatePromptIcon(prompt: PromptModel): ImageVector {
    val raw = prompt.prompt.orEmpty()
    val isSnippet = raw.length >= 2 && raw.startsWith("[") && raw.endsWith("]")
    return when {
        isSnippet -> Icons.AutoMirrored.Filled.ShortText
        prompt.requiresSelection -> Icons.Default.SelectAll
        else -> Icons.Default.AutoAwesome
    }
}
