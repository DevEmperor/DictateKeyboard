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

import android.os.SystemClock
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.dictate.DictateLanguages
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.input.InputShiftState
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard.KeyboardManager
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.text.gestures.SwipeAction
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggText
import org.florisboard.lib.snygg.ui.rememberSnyggThemeQuery

/**
 * Cross-composable state for the legacy layout. [suppressGlide] is set while the modern typing keyboard
 * is shown via the SWIPE mode so glide typing is disabled there – otherwise a horizontal glide would
 * swallow the swipe-back gesture and the user could never return to the dictation UI.
 */
object LegacyLayoutState {
    val suppressGlide = MutableStateFlow(false)

    // True while a finger is down whose initial key is space or backspace on the modern keyboard. The
    // SWIPE-mode swipe-toggle checks this and steps aside so those keys keep their own cursor-move /
    // delete swipe gestures instead of flipping back to the dictation UI (issue #188).
    val spaceOrDeleteTouch = MutableStateFlow(false)
}

/** Which full-panel overlay (if any) replaces the legacy layout. Emoji uses the app's own MEDIA panel. */
private enum class LegacyOverlay { NONE, NUMBERS }

/** Uniform key corner radius, matching the Record button, so all buttons share the same rounding. */
private val LegacyKeyShape = RoundedCornerShape(16.dp)
/** Horizontal margin around every key; adjacent keys sit [KeyMarginH] × 2 apart. */
private val KeyMarginH = 3.dp
/** Vertical margin around every key – a touch larger, so rows breathe more top-to-bottom. */
private val KeyMarginV = 5.dp
/** Height of the record and bottom rows – the keys are as tall as the Record button. */
private val SideRowHeight = 56.dp
/** Height of the editing-action row (select-all etc.) – shorter than the main key rows. */
private val EditRowHeight = 46.dp

/** The current input connection of the running IME, or null if detached. */
private fun ic(): InputConnection? = FlorisImeService.currentInputConnection()

/** Dispatch a single key code through the normal input pipeline (down + up). */
private fun KeyboardManager.tapKey(code: Int) = inputEventDispatcher.sendDownUp(TextKeyData(code = code))

/** Query attributes that make the theme resolve its `key` styling for the given key [code]. */
private fun keyAttributes(code: Int) = mapOf(
    FlorisImeUi.Attr.Code to code,
    FlorisImeUi.Attr.Mode to KeyboardMode.CHARACTERS.toString(),
    FlorisImeUi.Attr.ShiftState to InputShiftState.UNSHIFTED.toString(),
)

/**
 * A horizontal-swipe detector that flips between the legacy dictation panel and the modern typing
 * keyboard (SWIPE mode, issue #125). A clearly horizontal drag past the threshold calls [onToggle].
 *
 * @param intercept when true the gesture is handled on the Initial pass and consumed, so it wins over
 *   the keys below (used on the modern keyboard – a horizontal swipe anywhere returns to the dictation
 *   UI). When false (legacy side) it runs on the Main pass and bails the moment a child gesture (space
 *   cursor, backspace select, prompt-strip scroll) consumes the event, so those never trigger a switch.
 */
fun Modifier.legacySwipeToggle(
    intercept: Boolean = false,
    onToggle: () -> Unit,
): Modifier = this.pointerInput(intercept) {
    val thresholdPx = 56.dp.toPx()
    val pass = if (intercept) PointerEventPass.Initial else PointerEventPass.Main
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = pass)
        var totalDx = 0f
        var totalDy = 0f
        while (true) {
            val event = awaitPointerEvent(pass)
            val change = event.changes.firstOrNull() ?: break
            if (!change.pressed) break
            if (!intercept && change.isConsumed) break
            // On the modern keyboard, let space/backspace keep their own swipe gestures (issue #188): if
            // the finger came down on one of those keys, step aside instead of flipping keyboards.
            if (intercept && LegacyLayoutState.spaceOrDeleteTouch.value) break
            totalDx += change.position.x - change.previousPosition.x
            totalDy += change.position.y - change.previousPosition.y
            if (abs(totalDx) > thresholdPx && abs(totalDx) > abs(totalDy) * 1.5f) {
                change.consume()
                onToggle()
                break
            }
        }
    }
}

/**
 * The classic, keyboard-less "legacy" dictation layout (issue #125) – a faithful Compose reproduction
 * of the Dictate 3.x record-first UI. Rendered in place of the typing keyboard (the `ImeUiMode.TEXT`
 * branch in `ImeWindow`) when [dev.patrickgold.florisboard.dictate.DictateLegacyLayout] is enabled.
 *
 * Every key takes the active theme's `key` colours (including the accent the theme puts on special keys
 * like enter) but a uniform [LegacyKeyShape] rounding; the only deliberate extra accent is the Record
 * button. Rows top→bottom: the always-visible prompt strip (only when rewording is enabled), an
 * editing-action row (select-all · undo · redo · cut · copy · paste · emoji · numbers), the record row
 * and the bottom row (switch · space · enter).
 */
@Composable
fun LegacyDictateLayout(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val prefs by FlorisPreferenceStore
    val prompts by DictateController.prompts.collectAsState()
    val dictateState by DictateController.state.collectAsState()
    val accent by prefs.theme.accentColor.collectAsState()
    val rewordingEnabled by prefs.dictate.rewordingEnabled.collectAsState()
    val promptRows by prefs.dictate.legacyPromptRows.collectAsState()

    // The Smartbar (which normally loads the prompts) is replaced by this layout, so trigger the load
    // here whenever the panel appears / rewording toggles.
    LaunchedEffect(rewordingEnabled) {
        if (rewordingEnabled) DictateController.refreshPrompts(context)
    }

    var overlay by remember { mutableStateOf(LegacyOverlay.NONE) }

    Box(modifier = modifier.fillMaxWidth()) {
        when (overlay) {
            LegacyOverlay.NUMBERS -> LegacyNumberPadOverlay { overlay = LegacyOverlay.NONE }
            LegacyOverlay.NONE -> Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = KeyMarginH, vertical = KeyMarginV),
            ) {
                // Row 1: normally the prompt strip (when rewording is on), but it doubles as the status
                // surface – errors, the interrupted-recording chip and the rate/donate/milestone promos
                // reuse the same DictateSmartbarUi the main keyboard shows, so all of those work here too.
                val showStatus = dictateState is DictateController.UiState.Error ||
                    dictateState is DictateController.UiState.Interrupted ||
                    dictateState is DictateController.UiState.Promo
                if (showStatus || rewordingEnabled) {
                    // Status chips stay one row tall; the prompt strip can be one or two rows (#194).
                    val stripHeight = when {
                        showStatus -> FlorisImeSizing.smartbarHeight * 1.2f
                        promptRows >= 2 -> FlorisImeSizing.smartbarHeight * 2.4f
                        else -> FlorisImeSizing.smartbarHeight * 1.2f
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(stripHeight)
                            .padding(bottom = KeyMarginV),
                    ) {
                        if (showStatus) {
                            DictateSmartbarUi(dictateState, modifier = Modifier.fillMaxSize())
                        } else {
                            DictatePromptRow(prompts, modifier = Modifier.fillMaxSize(), rows = promptRows)
                        }
                    }
                }

                // Row 2: editing actions (select-all first, so it sits in the row below the strip).
                LegacyEditRow(
                    keyboardManager = keyboardManager,
                    onEmoji = { keyboardManager.activeState.imeUiMode = ImeUiMode.MEDIA },
                    onNumbers = { overlay = LegacyOverlay.NUMBERS },
                )

                // Row 3: the record row.
                LegacyRecordRow(
                    modifier = Modifier.fillMaxWidth().height(SideRowHeight),
                    dictateState = dictateState,
                    accent = accent,
                )

                // Row 4: switch keyboard · space · enter (same height as the record row for equal keys).
                LegacyBottomRow(
                    modifier = Modifier.fillMaxWidth().height(SideRowHeight),
                    keyboardManager = keyboardManager,
                )
            }
        }
    }
}

/**
 * A single legacy key: the active theme's `key` colours for the given [code] (so special keys such as
 * enter keep their accent), rendered with the uniform [LegacyKeyShape] and a pressed/ripple state. The
 * [content] receives the themed foreground colour so icons/labels match the theme.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThemedKey(
    code: Int,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable (foreground: Color) -> Unit,
) {
    val feedback = LocalInputFeedbackController.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val style = rememberSnyggThemeQuery(
        FlorisImeUi.Key.elementName,
        keyAttributes(code),
        if (pressed) SnyggSelector.PRESSED else SnyggSelector.NONE,
    )
    val bg = style.background(default = Color.White.copy(alpha = 0.08f))
    val fg = style.foreground(default = Color.White)
    Box(
        modifier = modifier
            .padding(horizontal = KeyMarginH, vertical = KeyMarginV)
            .clip(LegacyKeyShape)
            .background(bg)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                onClick = { feedback.keyPress(); onClick() },
                onLongClick = onLongClick?.let { { feedback.keyPress(); it() } },
            ),
        contentAlignment = Alignment.Center,
    ) {
        content(fg)
    }
}

/** Convenience: a themed key showing a single icon. [tint] overrides the themed foreground (e.g. red). */
@Composable
private fun ThemedIconKey(
    code: Int,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    iconSize: Dp = 22.dp,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    ThemedKey(code = code, modifier = modifier, onLongClick = onLongClick, onClick = onClick) { fg ->
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint ?: fg, modifier = Modifier.size(iconSize))
    }
}

/**
 * Editing-action row. The buttons are user-configurable (issue #183/#194): the ordered set comes from
 * [dev.patrickgold.florisboard.app.AppPrefs.Dictate.legacyActionRow] and is arranged in Settings. The
 * default row is select-all · undo · redo · cut · copy · paste · emoji · numbers, but any of the actions
 * in [LegacyEditAction] (also language, history, reinsert, GIF) can be placed here.
 */
@Composable
private fun LegacyEditRow(
    keyboardManager: KeyboardManager,
    onEmoji: () -> Unit,
    onNumbers: () -> Unit,
) {
    val context = LocalContext.current
    val prefs by FlorisPreferenceStore
    val editorInstance by context.editorInstance()
    val content by editorInstance.activeContentFlow.collectAsState()
    val hasSelection = content.selection.isSelectionMode

    val actionRaw by prefs.dictate.legacyActionRow.collectAsState()
    val actions = remember(actionRaw) { LegacyEditAction.parse(actionRaw) }
    if (actions.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(EditRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val keyMod = Modifier.weight(1f).fillMaxHeight()
        actions.forEachIndexed { index, action ->
            key(index, action) {
                LegacyActionKey(
                    action = action,
                    modifier = keyMod,
                    keyboardManager = keyboardManager,
                    hasSelection = hasSelection,
                    onEmoji = onEmoji,
                    onNumbers = onNumbers,
                )
            }
        }
    }
}

/** Renders a single [LegacyEditAction] as a themed key with the right icon and behaviour. */
@Composable
private fun LegacyActionKey(
    action: LegacyEditAction,
    modifier: Modifier,
    keyboardManager: KeyboardManager,
    hasSelection: Boolean,
    onEmoji: () -> Unit,
    onNumbers: () -> Unit,
) {
    val context = LocalContext.current
    val label = stringRes(action.labelRes)
    when (action) {
        // Select-all toggles: with a selection it becomes "deselect" and collapses the cursor.
        LegacyEditAction.SELECT_ALL -> ThemedIconKey(
            code = KeyCode.CLIPBOARD_SELECT_ALL,
            icon = if (hasSelection) Icons.Default.Deselect else Icons.Default.SelectAll,
            contentDescription = label,
            modifier = modifier,
        ) {
            if (hasSelection) {
                ic()?.let { c ->
                    val et = c.getExtractedText(ExtractedTextRequest(), 0)
                    if (et != null) c.setSelection(et.selectionEnd, et.selectionEnd)
                }
            } else {
                keyboardManager.tapKey(KeyCode.CLIPBOARD_SELECT_ALL)
            }
        }
        LegacyEditAction.LANGUAGE -> LegacyLanguageKey(modifier)
        LegacyEditAction.UNDO -> ThemedIconKey(KeyCode.UNDO, action.icon, label, modifier) { keyboardManager.tapKey(KeyCode.UNDO) }
        LegacyEditAction.REDO -> ThemedIconKey(KeyCode.REDO, action.icon, label, modifier) { keyboardManager.tapKey(KeyCode.REDO) }
        LegacyEditAction.CUT -> ThemedIconKey(KeyCode.CLIPBOARD_CUT, action.icon, label, modifier) { keyboardManager.tapKey(KeyCode.CLIPBOARD_CUT) }
        LegacyEditAction.COPY -> ThemedIconKey(KeyCode.CLIPBOARD_COPY, action.icon, label, modifier) { keyboardManager.tapKey(KeyCode.CLIPBOARD_COPY) }
        LegacyEditAction.PASTE -> ThemedIconKey(KeyCode.CLIPBOARD_PASTE, action.icon, label, modifier) { keyboardManager.tapKey(KeyCode.CLIPBOARD_PASTE) }
        LegacyEditAction.EMOJI -> ThemedIconKey(KeyCode.IME_UI_MODE_MEDIA, action.icon, label, modifier, onClick = onEmoji)
        LegacyEditAction.NUMBERS -> ThemedIconKey(KeyCode.VIEW_NUMERIC, action.icon, label, modifier, onClick = onNumbers)
        LegacyEditAction.HISTORY -> ThemedIconKey(KeyCode.NOOP, action.icon, label, modifier) {
            keyboardManager.activeState.imeUiMode = ImeUiMode.HISTORY
        }
        LegacyEditAction.GIF -> ThemedIconKey(KeyCode.NOOP, action.icon, label, modifier) {
            keyboardManager.activeState.imeUiMode = ImeUiMode.GIF
        }
        LegacyEditAction.REINSERT -> ThemedIconKey(KeyCode.NOOP, action.icon, label, modifier) {
            DictateController.reinsertLastDictation(context)
        }
    }
}

/**
 * The language action: shows the active dictation language (globe for auto-detect, else the short code).
 * Tap cycles through the selected languages; long-press opens a picker — mirrors the Smartbar chip.
 */
@Composable
private fun LegacyLanguageKey(modifier: Modifier) {
    val prefs by FlorisPreferenceStore
    val activeCode by prefs.dictate.activeInputLanguage.collectAsState()
    val selectionRaw by prefs.dictate.inputLanguages.collectAsState()
    val selection = remember(selectionRaw) { DictateLanguages.parseSelection(selectionRaw) }
    val active = remember(activeCode) { DictateLanguages.of(activeCode) }
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        ThemedKey(
            code = KeyCode.NOOP,
            modifier = Modifier.fillMaxSize(),
            onLongClick = { if (selection.size > 1) menuOpen = true },
            onClick = { DictateController.cycleLanguage() },
        ) { fg ->
            if (active.code == DictateLanguages.DETECT) {
                Icon(Icons.Default.Language, contentDescription = stringRes(R.string.dictate__language_detect), tint = fg, modifier = Modifier.size(22.dp))
            } else {
                Text(active.shortCode, color = fg, fontWeight = FontWeight.SemiBold)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            selection.forEach { lang ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (lang.code == DictateLanguages.DETECT) stringRes(R.string.dictate__language_detect)
                            else lang.displayName(),
                        )
                    },
                    onClick = { DictateController.setLanguage(lang.code); menuOpen = false },
                )
            }
        }
    }
}

/**
 * The record row. Idle: settings · big Record button (tap = start, long-press = pick an audio file) ·
 * backspace. Recording: cancel (red) · Record button showing a pulsing dot + elapsed timer (tap = stop)
 * · pause/resume – so the prompt strip above stays put, matching the old UI. Transcribing/rewording: a
 * spinner + "Transcribing…".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LegacyRecordRow(
    modifier: Modifier,
    dictateState: DictateController.UiState,
    accent: Color,
) {
    val context = LocalContext.current
    val recording = dictateState as? DictateController.UiState.Recording
    val rewording = dictateState as? DictateController.UiState.Rewording
    // The button is non-interactive while the audio is being transcribed or reworded.
    val busy = dictateState is DictateController.UiState.Transcribing || rewording != null
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    val sideKey = Modifier.fillMaxHeight().aspectRatio(1f)

    // Long-form segmented dictation (#170): whether the "Next segment" button replaces pause and how many
    // cut segments are transcribing in the background; plus a one-shot flash of the Next button on each cut.
    val segmented by DictateController.segmentedRecording.collectAsState()
    val segmentsInFlight by DictateController.segmentsInFlight.collectAsState()
    val flushCount by DictateController.segmentFlushCount.collectAsState()
    val nextFlash = remember { Animatable(0f) }
    LaunchedEffect(flushCount) {
        if (flushCount > 0) {
            nextFlash.snapTo(1f)
            nextFlash.animateTo(0f, tween(550))
        }
    }
    // Realtime streaming (#128): tapping the record button ends the live stream — hint that with a send glyph.
    val realtime = recording != null && DictateController.isRealtimeRecording()

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left slot: settings when idle, cancel (red) while recording.
        if (recording != null) {
            ThemedIconKey(
                code = KeyCode.NOOP,
                icon = Icons.Default.Delete,
                contentDescription = stringRes(R.string.dictate__action_cancel),
                modifier = sideKey,
                tint = Color(0xFFE53935),
                // In long-form this drops only the current (uncut) segment and keeps recording (#183).
                onClick = { DictateController.cancelOrDiscardSegment(context) },
            )
        } else {
            ThemedIconKey(
                code = KeyCode.SETTINGS,
                icon = Icons.Default.Settings,
                contentDescription = stringRes(R.string.dictate__action_settings),
                modifier = sideKey,
                onClick = { FlorisImeService.launchSettings("settings/dictate") },
            )
        }

        // Center: the big Record button – the one deliberate accent element (like the Smartbar mic).
        val pulse by rememberInfiniteTransition(label = "legacyRecord").animateFloat(
            initialValue = 1f,
            targetValue = if (recording != null && !recording.paused) 1.03f else 1f,
            animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
            label = "recordPulse",
        )
        val interaction = remember { MutableInteractionSource() }
        val feedback = LocalInputFeedbackController.current
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = KeyMarginH, vertical = KeyMarginV)
                .scale(pulse)
                .clip(LegacyKeyShape)
                .background(accent)
                .then(
                    if (busy) {
                        // Transcribing/rewording: a tap now cancels the in-flight request (issue #192),
                        // matching the Smartbar stop button. No long-press (file transcription) while busy.
                        Modifier.clickable(
                            interactionSource = interaction,
                            indication = ripple(),
                        ) { feedback.keyPress(); DictateController.onMicClick(context) }
                    } else {
                        Modifier.combinedClickable(
                            interactionSource = interaction,
                            indication = ripple(),
                            onClick = { feedback.keyPress(); DictateController.onMicClick(context) },
                            onLongClick = { feedback.keyPress(); DictateController.startFileTranscription(context) },
                        )
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    recording != null -> {
                        var elapsedMs by remember { mutableLongStateOf(recording.accumulatedMs) }
                        LaunchedEffect(recording.startedAtMs, recording.accumulatedMs, recording.paused) {
                            if (recording.paused) {
                                elapsedMs = recording.accumulatedMs
                            } else {
                                while (true) {
                                    elapsedMs = recording.accumulatedMs +
                                        (SystemClock.elapsedRealtime() - recording.startedAtMs)
                                    delay(200L)
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .alpha(if (recording.paused) 0.4f else 1f)
                                .clip(CircleShape)
                                .background(Color(0xFFD32F2F)),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = formatElapsed(elapsedMs), color = onAccent, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        // Long-form: how many cut segments are transcribing in the background right now (#170).
                        if (segmentsInFlight > 0) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.Default.Sync, contentDescription = null, tint = onAccent, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(text = "$segmentsInFlight", color = onAccent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                        // Realtime (#128): tapping finalizes the live stream — show a send glyph as the hint.
                        if (realtime) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = onAccent, modifier = Modifier.size(18.dp))
                        }
                    }
                    rewording != null -> {
                        // Reworded, not transcribed: show the rewording label (prompt name / "Rewording…").
                        // A trailing stop icon signals a tap cancels the request (issue #192).
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = onAccent, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = rewording.label.ifBlank { stringRes(R.string.dictate__status_rewording) }, color = onAccent)
                        Spacer(modifier = Modifier.width(10.dp))
                        Icon(Icons.Default.Stop, contentDescription = null, tint = onAccent, modifier = Modifier.size(20.dp))
                    }
                    busy -> {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = onAccent, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = stringRes(R.string.dictate__status_transcribing), color = onAccent)
                        Spacer(modifier = Modifier.width(10.dp))
                        Icon(Icons.Default.Stop, contentDescription = null, tint = onAccent, modifier = Modifier.size(20.dp))
                    }
                    else -> {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = onAccent, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = stringRes(R.string.dictate__legacy_record), color = onAccent, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.width(10.dp))
                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = onAccent, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // Right slot: backspace when idle; while recording, the "Next segment" cut button in long-form
        // mode (#170, replacing pause — pausing is redundant there), otherwise pause/resume.
        when {
            recording != null && segmented -> {
                ThemedKey(
                    code = KeyCode.NOOP,
                    modifier = sideKey.scale(1f + nextFlash.value * 0.3f),
                    onClick = { DictateController.flushSegment(context) },
                ) { fg ->
                    Icon(
                        imageVector = Icons.Default.FastForward,
                        contentDescription = stringRes(R.string.dictate__action_next_segment),
                        tint = lerp(fg, accent, nextFlash.value),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            recording != null -> {
                ThemedIconKey(
                    code = KeyCode.NOOP,
                    icon = if (recording.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = stringRes(
                        if (recording.paused) R.string.dictate__action_resume else R.string.dictate__action_pause,
                    ),
                    modifier = sideKey,
                    onClick = { DictateController.togglePause() },
                )
            }
            else -> LegacyBackspaceKey(modifier = sideKey)
        }
    }
}

/** Bottom row: switch-keyboard · space (cursor-move swipe) · enter. */
@Composable
private fun LegacyBottomRow(
    modifier: Modifier,
    keyboardManager: KeyboardManager,
) {
    val sideKey = Modifier.fillMaxHeight().aspectRatio(1f)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemedIconKey(
            code = KeyCode.SYSTEM_PREV_INPUT_METHOD,
            icon = Icons.Default.KeyboardHide,
            contentDescription = stringRes(R.string.dictate__legacy_switch_keyboard),
            modifier = sideKey,
            onLongClick = { keyboardManager.tapKey(KeyCode.SYSTEM_INPUT_METHOD_PICKER) },
            onClick = { keyboardManager.tapKey(KeyCode.SYSTEM_PREV_INPUT_METHOD) },
        )

        LegacySpaceKey(
            keyboardManager = keyboardManager,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )

        // Enter carries the ENTER code so the theme paints it with its usual accent (as on the keyboard).
        ThemedIconKey(
            code = KeyCode.ENTER,
            icon = Icons.AutoMirrored.Filled.KeyboardReturn,
            contentDescription = stringRes(R.string.dictate__legacy_enter),
            modifier = sideKey,
            onClick = { keyboardManager.tapKey(KeyCode.ENTER) },
        )
    }
}

/**
 * A themed surface (theme `key` colours + uniform corners) that hosts a custom pointer gesture instead
 * of a click – used by the space and backspace keys.
 */
@Composable
private fun GestureKey(
    code: Int,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier,
    gesture: Modifier,
) {
    val style = rememberSnyggThemeQuery(FlorisImeUi.Key.elementName, keyAttributes(code))
    val bg = style.background(default = Color.White.copy(alpha = 0.08f))
    val fg = style.foreground(default = Color.White)
    Box(
        modifier = modifier.padding(horizontal = KeyMarginH, vertical = KeyMarginV).clip(LegacyKeyShape).background(bg).then(gesture),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = fg, modifier = Modifier.size(22.dp))
    }
}

/**
 * Space key with the legacy cursor-move gesture: tap inserts a space, swiping horizontally moves the
 * caret via arrow-key events (never inserting or deleting). Small ~10dp steps make it responsive.
 */
@Composable
private fun LegacySpaceKey(
    keyboardManager: KeyboardManager,
    modifier: Modifier,
) {
    val feedback = LocalInputFeedbackController.current
    val gesture = Modifier.pointerInput(Unit) {
        val stepPx = 10.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown()
            var lastX = down.position.x
            var swiped = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: break
                if (change.pressed) {
                    val dx = change.position.x - lastX
                    if (dx > stepPx) {
                        keyboardManager.tapKey(KeyCode.ARROW_RIGHT)
                        lastX = change.position.x
                        swiped = true
                        feedback.keyPress()
                        change.consume()
                    } else if (dx < -stepPx) {
                        keyboardManager.tapKey(KeyCode.ARROW_LEFT)
                        lastX = change.position.x
                        swiped = true
                        feedback.keyPress()
                        change.consume()
                    }
                } else {
                    if (!swiped) {
                        keyboardManager.tapKey(KeyCode.SPACE)
                        feedback.keyPress()
                    }
                    break
                }
            }
        }
    }
    GestureKey(KeyCode.SPACE, Icons.Default.SpaceBar, stringRes(R.string.dictate__legacy_space), modifier, gesture)
}

/**
 * Backspace key with the legacy gestures: a tap deletes one character, holding auto-repeats the delete,
 * and swiping left progressively selects text (whole words or single characters) which is deleted on
 * release. Whether the swipe works by words or characters follows the same global setting the modern
 * keyboard uses (Settings → Gestures → "Delete key swipe left"), so both layouts behave identically.
 */
@Composable
private fun LegacyBackspaceKey(modifier: Modifier) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val prefs by FlorisPreferenceStore
    val feedback = LocalInputFeedbackController.current
    val gesture = Modifier.pointerInput(Unit) {
        val activationPx = 12.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown()
            val startX = down.position.x
            // Word- vs character-granularity for the swipe, shared with the modern keyboard's setting.
            val wordMode = when (prefs.gestures.deleteKeySwipeLeft.get()) {
                SwipeAction.DELETE_WORD,
                SwipeAction.DELETE_WORDS_PRECISELY,
                SwipeAction.SELECT_WORDS_PRECISELY -> true
                else -> false
            }
            val stepPx = (if (wordMode) 24.dp else 12.dp).toPx()
            var mode = 0 // 0 = undecided, 1 = swipe-select, 2 = hold-repeat

            while (mode == 0) {
                val event = withTimeoutOrNull(350L) { awaitPointerEvent() }
                if (event == null) {
                    mode = 2
                    break
                }
                val change = event.changes.firstOrNull() ?: return@awaitEachGesture
                if (!change.pressed) {
                    keyboardManager.tapKey(KeyCode.DELETE)
                    feedback.keyPress()
                    return@awaitEachGesture
                }
                if (change.position.x - startX < -activationPx) {
                    mode = 1
                    change.consume()
                }
            }

            if (mode == 2) {
                keyboardManager.tapKey(KeyCode.DELETE)
                feedback.keyPress()
                while (true) {
                    val event = withTimeoutOrNull(55L) { awaitPointerEvent() }
                    if (event == null) {
                        keyboardManager.tapKey(KeyCode.DELETE)
                        feedback.keyPress()
                        continue
                    }
                    val change = event.changes.firstOrNull() ?: return@awaitEachGesture
                    if (!change.pressed) return@awaitEachGesture
                }
            }

            // mode == 1: swipe-select (whole words or single characters, per [wordMode]); delete on release.
            var base = -1
            var boundaries: List<Int> = emptyList()
            var steps = 0
            ic()?.let { conn ->
                val et = conn.getExtractedText(ExtractedTextRequest(), 0)
                val text = et?.text
                if (text != null) {
                    base = maxOf(et.selectionStart, et.selectionEnd)
                    boundaries = if (wordMode) {
                        computeWordBoundaries(text.subSequence(0, base).toString())
                    } else {
                        // One boundary per character back to the start, so each step selects one more char.
                        (base downTo 0).toList()
                    }
                }
            }
            if (boundaries.isEmpty()) {
                boundaries = listOf(0)
                base = 0
            }
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: break
                val dx = change.position.x - startX
                if (!change.pressed) {
                    if (steps > 0) {
                        ic()?.commitText("", 1)
                        feedback.keyPress()
                    } else if (base >= 0) {
                        ic()?.setSelection(base, base)
                    }
                    break
                }
                val maxSteps = boundaries.size - 1
                val s = ((-dx) / stepPx).toInt().coerceIn(0, maxSteps)
                if (s != steps) {
                    steps = s
                    ic()?.setSelection(boundaries[s], base)
                    feedback.keyPress()
                }
                change.consume()
            }
        }
    }
    GestureKey(KeyCode.DELETE, Icons.Default.Backspace, stringRes(R.string.dictate__legacy_backspace), modifier, gesture)
}

/** Sentinel tags for the non-character number-pad keys. */
private const val NUMPAD_SPACE = " space"
private const val NUMPAD_DELETE = " delete"
private const val NUMPAD_ENTER = " enter"

private val NUMPAD_ROWS = listOf(
    listOf("1", "2", "3", "-"),
    listOf("4", "5", "6", NUMPAD_SPACE),
    listOf("7", "8", "9", NUMPAD_DELETE),
    listOf(",", "0", ".", NUMPAD_ENTER),
)

/** Full-panel 4×4 number pad overlay, reproducing `[1 2 3 −][4 5 6 ␣][7 8 9 ⌫][, 0 . ✓]`. */
@Composable
private fun LegacyNumberPadOverlay(onClose: () -> Unit) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    SnyggColumn(
        elementName = FlorisImeUi.Media.elementName,
        modifier = Modifier.fillMaxWidth().height(FlorisImeSizing.imeUiHeight()).padding(horizontal = KeyMarginH, vertical = KeyMarginV),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(FlorisImeSizing.smartbarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SnyggText(
                elementName = FlorisImeUi.MediaEmojiSubheader.elementName,
                modifier = Modifier.weight(1f).padding(start = KeyMarginH * 2),
                text = stringRes(R.string.dictate__legacy_numbers_title),
            )
            ThemedIconKey(
                code = KeyCode.NOOP,
                icon = Icons.Default.Close,
                contentDescription = stringRes(R.string.dictate__legacy_close),
                modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                onClick = onClose,
            )
        }
        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
            NUMPAD_ROWS.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    row.forEach { key ->
                        val keyMod = Modifier.weight(1f).fillMaxHeight()
                        when (key) {
                            NUMPAD_SPACE -> ThemedIconKey(KeyCode.SPACE, Icons.Default.SpaceBar, stringRes(R.string.dictate__legacy_space), keyMod) { keyboardManager.tapKey(KeyCode.SPACE) }
                            NUMPAD_DELETE -> ThemedIconKey(KeyCode.DELETE, Icons.Default.Backspace, stringRes(R.string.dictate__legacy_backspace), keyMod) { keyboardManager.tapKey(KeyCode.DELETE) }
                            NUMPAD_ENTER -> ThemedIconKey(KeyCode.ENTER, Icons.AutoMirrored.Filled.KeyboardReturn, stringRes(R.string.dictate__legacy_enter), keyMod) { keyboardManager.tapKey(KeyCode.ENTER) }
                            else -> ThemedKey(code = key[0].code, modifier = keyMod, onClick = { ic()?.commitText(key, 1) }) { fg ->
                                Text(text = key, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Computes absolute caret indices (0..cursor) for the backspace swipe-select, ported verbatim from the
 * legacy `DictateInputMethodService`: `boundaries[0]` = cursor, each further entry moves the selection
 * start back by one "space + word", so N swipe steps select N words.
 */
private fun computeWordBoundaries(before: String): List<Int> {
    val res = ArrayList<Int>()
    var pos = before.length
    res.add(pos)
    while (pos > 0) {
        var i = pos
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isLetterOrDigit() && !before[i - 1].isWhitespace()) i--
        while (i > 0 && before[i - 1].isLetterOrDigit()) i--
        while (i > 0 && before[i - 1].isWhitespace()) i--
        if (i == pos) i--
        pos = i
        res.add(pos)
    }
    return res
}

private fun formatElapsed(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(totalSec / 60L, totalSec % 60L)
}
