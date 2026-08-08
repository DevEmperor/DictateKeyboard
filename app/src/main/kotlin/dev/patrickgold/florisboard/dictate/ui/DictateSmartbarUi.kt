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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.animation.core.Animatable
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.AddCard
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState as collectFlowAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.dictate.DictateLanguages
import dev.patrickgold.florisboard.dictate.DictateRecordingAnimation
import dev.patrickgold.florisboard.dictate.PushToTalkPhase
import dev.patrickgold.florisboard.dictate.provider.DictateApiException
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.delay
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText
import org.florisboard.lib.snygg.ui.rememberSnyggThemeQuery

/** Recording red, shared by the indicator dot and the armed slide-to-cancel bin (#235). */
private val RecordingRed = Color(0xFFE53935)

/**
 * Gboard-style in-Smartbar dictation indicator. Rendered in the Smartbar's center area (left of the
 * sticky mic button) while [DictateController] is recording or transcribing, so the keyboard itself
 * stays fully visible instead of being replaced by a separate panel.
 *
 * - Recording: a cancel button, an audio-reactive cloud orb + an elapsed `m:ss` timer, and a pause/resume
 *   button. The sticky mic (rendered by the Smartbar) stops the recording and starts transcribing.
 * - Transcribing: a spinning icon + label, or a retry indicator while a transient failure is retried.
 * - Error: the error message, auto-cleared after a few seconds.
 */
@Composable
fun DictateSmartbarUi(state: DictateController.UiState, modifier: Modifier = Modifier) {
    val arrangement = when {
        state is DictateController.UiState.Recording -> Arrangement.SpaceBetween
        state is DictateController.UiState.Error &&
            state.action != DictateController.ErrorAction.NONE -> Arrangement.SpaceBetween
        // The interrupted-recording chip always carries send/dismiss buttons on the right.
        state is DictateController.UiState.Interrupted -> Arrangement.SpaceBetween
        else -> Arrangement.Center
    }
    SnyggRow(
        elementName = FlorisImeUi.SmartbarSharedActionsRow.elementName,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp),
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            is DictateController.UiState.Recording -> RecordingContent(state)
            is DictateController.UiState.Transcribing -> TranscribingContent(state)
            is DictateController.UiState.Rewording -> RewordingContent(state)
            is DictateController.UiState.Error -> ErrorContent(state)
            is DictateController.UiState.Interrupted -> InterruptedContent(state)
            is DictateController.UiState.Promo -> PromoContent(state.kind, state.message)
            else -> {}
        }
    }
}

@Composable
private fun RecordingContent(state: DictateController.UiState.Recording) {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    // Long-form segmented dictation (#170): whether the "Next segment" button is active and how many cut
    // segments are transcribing in the background.
    val segmented by DictateController.segmentedRecording.collectFlowAsState()
    val segmentsInFlight by DictateController.segmentsInFlight.collectFlowAsState()
    // A one-shot flash of the Next button (accent tint + a brief grow) on every segment cut, so the user
    // sees a chunk was sent off (#170).
    val flushCount by DictateController.segmentFlushCount.collectFlowAsState()
    val accent by prefs.theme.accentColor.collectAsState()
    val nextFlash = remember { Animatable(0f) }
    LaunchedEffect(flushCount) {
        if (flushCount > 0) {
            nextFlash.snapTo(1f)
            nextFlash.animateTo(0f, tween(550))
        }
    }

    // Push-to-talk (#235): while the finger is still down nothing on this bar can be tapped, so the
    // buttons give way to the slide affordance — a bin that arms as you slide towards it, and the hint
    // in place of the controls the finger cannot reach anyway.
    val ptt by DictateController.pushToTalkVisuals.collectFlowAsState()
    // The bar must not change back while the discarded mic is still flying towards the bin — the target
    // it is aiming at is on this bar, and the controls returning underneath it broke the illusion.
    val holding = ptt.micShown
    val rawCancelProgress by DictateController.cancelSlideProgress.collectFlowAsState()
    val cancelProgress = if (ptt.discarding) 1f else rawCancelProgress

    // Cancel button (far left) – discards the recording. In long-form it drops only the current (uncut)
    // segment and keeps recording, so you can scrap the last utterance without losing the transcript (#183).
    // While holding it is the discard target: it reddens as the finger approaches, and reaching it drops
    // the recording immediately (see DictateController.onPushToTalkSlide) rather than on release.
    SnyggIconButton(
        elementName = FlorisImeUi.SmartbarActionKey.elementName,
        onClick = { DictateController.cancelOrDiscardSegment(context) },
        modifier = Modifier
            .fillMaxHeight()
            .aspectRatio(1f)
            // The swollen mic is drawn by the mic key and has to be thrown *here*; it can only aim at a
            // position someone measured.
            .onGloballyPositioned { DictateHoldTargets.reportBinBounds(it.boundsInWindow().roundToIntRect()) },
    ) {
        // The red goes behind the icon rather than into it. SnyggIcon takes both its size and its colour
        // from the keyboard theme, and swapping it for Material's Icon to gain a tint is exactly what
        // shrank this button before — that one is a fixed 24 dp.
        Box(contentAlignment = Alignment.Center) {
            if (holding && cancelProgress > 0f) {
                Box(
                    modifier = Modifier
                        .size(FlorisImeSizing.smartbarHeight * 0.8f)
                        .background(RecordingRed.copy(alpha = 0.85f * cancelProgress), CircleShape),
                )
            }
            SnyggIcon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringRes(R.string.dictate__action_cancel),
            )
        }
    }

    // Center: audio-reactive dot + elapsed timer.
    Row(verticalAlignment = Alignment.CenterVertically) {
        var elapsedMs by remember { mutableLongStateOf(state.accumulatedMs) }
        // Frozen once the recording has been thrown away: nothing is being captured any more, so a timer
        // still counting up and a dot still pulsing would be showing something that is not happening.
        LaunchedEffect(state.startedAtMs, state.accumulatedMs, state.paused, ptt.discarding) {
            if (state.paused || ptt.discarding) {
                if (state.paused) elapsedMs = state.accumulatedMs
            } else {
                while (true) {
                    elapsedMs = state.accumulatedMs + (SystemClock.elapsedRealtime() - state.startedAtMs)
                    delay(200L)
                }
            }
        }
        RecordingAudioDot(paused = state.paused, frozen = ptt.discarding)
        Spacer(modifier = Modifier.width(10.dp))
        SnyggText(text = formatElapsed(elapsedMs))
        // Segmented mode: how many cut segments are transcribing in the background right now.
        if (segmentsInFlight > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            SnyggIcon(imageVector = Icons.Default.Sync, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(2.dp))
            SnyggText(text = "$segmentsInFlight")
        }
    }

    // Right group: language chip + (in long-form mode) the "Next segment" button, otherwise the
    // pause/resume button — left of the sticky mic. Long-form replaces pause with Next: pausing is
    // redundant there (the Next button / auto-split already handle thought-breaks).
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (holding) {
            // Neither the chip nor pause can be reached with the finger still on the mic, so the space
            // says what the gesture will do instead of showing controls that cannot be used.
            PushToTalkAffordance(cancelProgress)
            return@Row
        }
        LanguageChip()
        if (segmented) {
            SnyggIconButton(
                elementName = FlorisImeUi.SmartbarActionKey.elementName,
                onClick = { DictateController.flushSegment(context) },
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .scale(1f + nextFlash.value * 0.35f),
            ) {
                Icon(
                    imageVector = Icons.Default.FastForward,
                    contentDescription = stringRes(R.string.dictate__action_next_segment),
                    tint = lerp(LocalContentColor.current, accent, nextFlash.value),
                )
            }
        } else {
            SnyggIconButton(
                elementName = FlorisImeUi.SmartbarActionKey.elementName,
                onClick = { DictateController.togglePause() },
                modifier = Modifier.fillMaxHeight().aspectRatio(1f),
            ) {
                SnyggIcon(
                    imageVector = if (state.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = stringRes(
                        if (state.paused) R.string.dictate__action_resume else R.string.dictate__action_pause,
                    ),
                )
            }
        }
    }
}

/**
 * What the bar shows while a finger is holding the mic (#235): the slide-to-cancel hint, with a chevron
 * and the sweeping highlight voice-message UIs use to say "this is a gesture, not a label". It fades out
 * as the finger nears the discard target, so it never competes with the bin for attention. The lock is
 * not here — it is a target above the mic that the finger drags into (see QuickActionButton).
 */
@Composable
private fun RowScope.PushToTalkAffordance(cancelProgress: Float) {
    // The swollen mic reaches leftwards out of the key, so the hint is pushed clear of it — otherwise the
    // words sit underneath the bubble and cannot be read at the moment they matter.
    val clearance = FlorisImeSizing.smartbarHeight * 0.6f
    val hintAlpha = (1f - cancelProgress).coerceIn(0f, 1f)
    val muted = LocalContentColor.current.copy(alpha = 0.55f * hintAlpha)

    // A chevron pointing the way, nudging left in time with the sweep below it.
    val bob by rememberInfiniteTransition(label = "pttArrow").animateFloat(
        initialValue = 0f,
        targetValue = -3f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pttArrowBob",
    )
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
        contentDescription = null,
        tint = muted,
        modifier = Modifier
            .size(16.dp)
            .graphicsLayer { translationX = bob * density },
    )
    Spacer(modifier = Modifier.width(2.dp))

    // The hint text, swept by a moving highlight rather than animated as a whole — the sweep is what
    // reads as "keep going in this direction".
    val sweep by rememberInfiniteTransition(label = "pttSweep").animateFloat(
        initialValue = 1f,
        targetValue = -1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
        label = "pttSweepX",
    )
    val highlight = LocalContentColor.current.copy(alpha = hintAlpha)
    Text(
        text = stringRes(R.string.dictate__push_to_talk_slide_cancel),
        fontSize = 11.sp,
        style = LocalTextStyle.current.copy(
            brush = Brush.horizontalGradient(
                0f to muted,
                (sweep - 0.18f).coerceIn(0f, 1f) to muted,
                sweep.coerceIn(0f, 1f) to highlight,
                (sweep + 0.18f).coerceIn(0f, 1f) to muted,
                1f to muted,
            ),
        ),
        maxLines = 1,
        modifier = Modifier.padding(end = clearance),
    )
}

/**
 * Small recording indicator for the Smartbar: a red dot that, depending on the user's choice
 * ([DictateRecordingAnimation], issue #238), follows the shared 20 Hz microphone level, pulses at a
 * fixed rate like the pre-rewrite app, or simply sits still. The level mode is the default because it
 * doubles as proof that the mic is actually hearing you — without the heavier cloud-orb surface, which
 * stays an opt-in floating-button skin.
 *
 * While paused the dot is always still and dimmed, whatever the mode: there is nothing to react to.
 */
/** The 4.0 pulse, kept as named constants so the classic layout can beat in time with the dot. */
internal const val PULSE_MIN_SCALE = 0.65f
internal const val PULSE_MAX_SCALE = 1.15f
internal const val PULSE_DURATION_MS = 650

@Composable
private fun RecordingAudioDot(paused: Boolean, frozen: Boolean = false) {
    val prefs by FlorisPreferenceStore
    val animation by prefs.dictate.recordingAnimation.collectAsState()
    // Frozen differs from paused: it stops every motion but keeps the dot at full strength, because the
    // bar is only still on screen for the discard animation and dimming it would be a second change on
    // top of the one being shown.
    val still = paused || frozen
    // Only collected in LEVEL mode, so the other modes don't recompose at 20 Hz for nothing.
    val level = if (animation == DictateRecordingAnimation.LEVEL && !still) {
        DictateController.audioLevel.collectFlowAsState().value
    } else {
        0f
    }
    // The original 4.0 heartbeat, restored: the dot drops well *below* its resting size and swells past
    // it, which reads as a beat. Growing from full size instead only throbs, and that turned out to be
    // the whole difference in feel. Both ends collapse to 1f when nothing should move.
    val pulsing = animation == DictateRecordingAnimation.PULSE && !still
    val pulse by rememberInfiniteTransition(label = "recordingDot").animateFloat(
        initialValue = if (pulsing) PULSE_MIN_SCALE else 1f,
        targetValue = if (pulsing) PULSE_MAX_SCALE else 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_DURATION_MS), RepeatMode.Reverse),
        label = "recordingDotPulse",
    )
    val scale = when {
        still -> 1f
        animation == DictateRecordingAnimation.LEVEL -> 0.85f + 0.5f * level
        else -> pulse // PULSE animates, STATIC stays at 1f because its target never leaves 1f
    }
    val alpha = when {
        paused -> 0.4f
        frozen -> 1f
        animation == DictateRecordingAnimation.LEVEL -> 0.55f + 0.45f * level
        else -> 1f
    }
    Spacer(
        modifier = Modifier
            .size(12.dp)
            .scale(scale)
            .alpha(alpha)
            .clip(CircleShape)
            .background(RecordingRed),
    )
}

/**
 * Compact dictation-language selector shown on the recording bar. Tap cycles through the user's
 * selected languages ([prefs.dictate.inputLanguages]); long-press opens a quick picker of that same
 * subset. Auto-detect is shown as a globe; any other language as its short code (DE, EN, …). The
 * choice only matters at transcription time, so switching mid-recording is fine.
 */
@Composable
private fun LanguageChip() {
    val prefs by FlorisPreferenceStore
    val activeCode by prefs.dictate.activeInputLanguage.collectAsState()
    val selectionRaw by prefs.dictate.inputLanguages.collectAsState()
    val selection = remember(selectionRaw) { DictateLanguages.parseSelection(selectionRaw) }
    val active = remember(activeCode) { DictateLanguages.of(activeCode) }
    var menuOpen by remember { mutableStateOf(false) }

    // Use the same SnyggIconButton as the cancel/pause buttons so the press ripple and footprint
    // are identical; the dropdown is anchored to the wrapping box.
    Box(modifier = Modifier.fillMaxHeight()) {
        SnyggIconButton(
            elementName = FlorisImeUi.SmartbarActionKey.elementName,
            onClick = { DictateController.cycleLanguage() },
            onLongClick = { if (selection.size > 1) menuOpen = true },
            modifier = Modifier.fillMaxHeight().aspectRatio(1f),
        ) {
            if (active.code == DictateLanguages.DETECT) {
                SnyggIcon(
                    imageVector = Icons.Default.Language,
                    contentDescription = stringRes(R.string.dictate__language_detect),
                    modifier = Modifier.size(20.dp),
                )
            } else {
                SnyggText(text = active.shortCode)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            selection.forEach { lang ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (lang.code == DictateLanguages.DETECT) {
                                stringRes(R.string.dictate__language_detect)
                            } else {
                                lang.displayName()
                            }
                        )
                    },
                    onClick = {
                        DictateController.setLanguage(lang.code)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

@Composable
private fun TranscribingContent(state: DictateController.UiState.Transcribing) {
    val transition = rememberInfiniteTransition(label = "transcribing")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900)),
        label = "spin",
    )
    val retrying = state.attempt > 1
    SnyggIcon(
        imageVector = if (retrying) Icons.Default.CloudOff else Icons.Default.Sync,
        modifier = Modifier
            .size(18.dp)
            .then(if (retrying) Modifier else Modifier.rotate(rotation)),
    )
    Spacer(modifier = Modifier.width(10.dp))
    SnyggText(
        text = if (retrying) {
            stringRes(R.string.dictate__status_retrying, "attempt" to state.attempt)
        } else {
            stringRes(R.string.dictate__status_transcribing)
        },
    )
}

/**
 * Shown while a rewording/GPT request runs (auto-formatting, auto-apply or live prompt): a spinning
 * icon plus the active prompt's label. Mirrors [TranscribingContent] visually.
 */
@Composable
private fun RewordingContent(state: DictateController.UiState.Rewording) {
    val transition = rememberInfiniteTransition(label = "rewording")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900)),
        label = "spin",
    )
    SnyggIcon(
        imageVector = Icons.Default.AutoAwesome,
        modifier = Modifier
            .size(18.dp)
            .rotate(rotation),
    )
    Spacer(modifier = Modifier.width(10.dp))
    SnyggText(text = state.label.ifBlank { stringRes(R.string.dictate__status_rewording) })
}

/**
 * Specific-error variant (roadmap 1.12): a kind-specific icon + a short localized headline, plus the
 * contextual action — resend the kept audio, open the provider settings (e.g. bad API key) or nothing.
 * Tapping the icon/message reveals the full raw provider detail in a popup. Errors that offer an action
 * stay until the user reacts; purely informational ones auto-clear after a few seconds.
 */
@Composable
private fun RowScope.ErrorContent(state: DictateController.UiState.Error) {
    val context = LocalContext.current
    var detailOpen by remember(state) { mutableStateOf(false) }
    val hasDetail = !state.detail.isNullOrBlank()
    val hasAction = state.action != DictateController.ErrorAction.NONE
    // Real errors are tinted a distinct red so they stand out; informational notices (e.g. "no speech
    // detected", issue #93) use the normal themed Smartbar foreground so they don't look like a failure.
    val rowStyle = rememberSnyggThemeQuery(FlorisImeUi.SmartbarSharedActionsRow.elementName)
    val errorColor = if (state.neutral) rowStyle.foreground() else Color(0xFFE53935)

    // Icon + message. Tappable when a raw provider detail is available, opening the detail popup below.
    Box(modifier = if (hasAction) Modifier.weight(1f) else Modifier) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (hasDetail) {
                        Modifier.clickable { detailOpen = true }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = errorIcon(state.kind, state.action),
                contentDescription = null,
                tint = errorColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = state.message,
                color = errorColor,
                fontWeight = FontWeight.Medium,
            )
        }
        if (detailOpen && hasDetail) {
            ErrorDetailPopup(detail = state.detail.orEmpty(), onDismiss = { detailOpen = false })
        }
    }

    when (state.action) {
        DictateController.ErrorAction.RESEND -> {
            SendButton(onClick = { DictateController.sendRetainedAudio(context) })
            DismissButton()
        }
        DictateController.ErrorAction.OPEN_SETTINGS -> {
            SnyggIconButton(
                elementName = FlorisImeUi.SmartbarActionKey.elementName,
                onClick = { DictateController.openProviderSettings(context) },
                modifier = Modifier.fillMaxHeight().aspectRatio(1f),
            ) {
                SnyggIcon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringRes(R.string.dictate__action_settings),
                )
            }
            DismissButton()
        }
        DictateController.ErrorAction.TOP_UP -> {
            SnyggIconButton(
                elementName = FlorisImeUi.SmartbarActionKey.elementName,
                onClick = { DictateController.openCloudSettings(context) },
                modifier = Modifier.fillMaxHeight().aspectRatio(1f),
            ) {
                SnyggIcon(
                    imageVector = Icons.Default.AddCard,
                    contentDescription = stringRes(R.string.dictate__action_top_up),
                )
            }
            DismissButton()
        }
        DictateController.ErrorAction.SAVE_AUDIO -> {
            SnyggIconButton(
                elementName = FlorisImeUi.SmartbarActionKey.elementName,
                onClick = { DictateController.saveRetainedAudio(context) },
                modifier = Modifier.fillMaxHeight().aspectRatio(1f),
            ) {
                SnyggIcon(
                    imageVector = Icons.Default.SaveAlt,
                    contentDescription = stringRes(R.string.dictate__action_save_audio),
                )
            }
            DismissButton()
        }
        DictateController.ErrorAction.NONE -> {
            // Purely informational: auto-clear, but pause the timer while the detail popup is open.
            LaunchedEffect(state, detailOpen) {
                if (!detailOpen) {
                    delay(4000L)
                    DictateController.clearError()
                }
            }
        }
    }
}

/**
 * Shared "send the kept audio" (↻) button, used by both the error-resend chip and the interrupted-
 * recording chip (unified resend path). Both route through [DictateController.sendRetainedAudio].
 */
@Composable
private fun RowScope.SendButton(onClick: () -> Unit) {
    SnyggIconButton(
        elementName = FlorisImeUi.SmartbarActionKey.elementName,
        onClick = onClick,
        modifier = Modifier.fillMaxHeight().aspectRatio(1f),
    ) {
        SnyggIcon(
            imageVector = Icons.Default.Refresh,
            contentDescription = stringRes(R.string.dictate__action_resend),
        )
    }
}

/** Shared dismiss (✗) button for the resend chips: drops the kept audio and returns to idle. */
@Composable
private fun RowScope.DismissButton() {
    SnyggIconButton(
        elementName = FlorisImeUi.SmartbarActionKey.elementName,
        onClick = { DictateController.dismissRetainedAudio() },
        modifier = Modifier.fillMaxHeight().aspectRatio(1f),
    ) {
        SnyggIcon(
            imageVector = Icons.Default.Close,
            contentDescription = stringRes(R.string.dictate__action_dismiss),
        )
    }
}

/**
 * Interrupted-recording chip: shown on the next keyboard open after a recording was finalized because
 * the keyboard closed mid-recording. Neutral (not an error): a mic glyph + "recording interrupted"
 * headline with the captured length, then the shared send (↻) and dismiss (✗) buttons. Sending runs
 * the same resend path as the error chip.
 */
@Composable
private fun RowScope.InterruptedContent(state: DictateController.UiState.Interrupted) {
    val context = LocalContext.current
    val rowStyle = rememberSnyggThemeQuery(FlorisImeUi.SmartbarSharedActionsRow.elementName)
    Row(
        modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.GraphicEq,
            contentDescription = null,
            tint = rowStyle.foreground(),
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        // Plain Text (not SnyggText) so we can force a single line + a slightly smaller font: the themed
        // Smartbar font wrapped onto two lines next to the send/dismiss buttons (looked cramped).
        Text(
            text = stringRes(
                R.string.dictate__interrupted_recording,
                "time" to formatElapsed(state.seconds * 1000L),
            ),
            color = rowStyle.foreground(),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    // Continue (🎙) resumes recording on top of the interrupted audio; send (↻) transcribes it as-is.
    SnyggIconButton(
        elementName = FlorisImeUi.SmartbarActionKey.elementName,
        onClick = { DictateController.continueInterruptedRecording(context) },
        modifier = Modifier.fillMaxHeight().aspectRatio(1f),
    ) {
        SnyggIcon(
            imageVector = Icons.Default.Mic,
            contentDescription = stringRes(R.string.dictate__action_continue_recording),
        )
    }
    SendButton(onClick = { DictateController.sendRetainedAudio(context) })
    DismissButton()
}

/** Popup with the full, unabbreviated provider error text (tap-to-expand from the error chip). */
@Composable
private fun ErrorDetailPopup(detail: String, onDismiss: () -> Unit) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringRes(R.string.dictate__error_details_title),
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text(text = detail)
        }
    }
}

/** Kind-specific icon for the error chip; the open-settings action gets a key icon regardless of kind. */
private fun errorIcon(kind: DictateApiException.Kind?, action: DictateController.ErrorAction): ImageVector = when {
    action == DictateController.ErrorAction.TOP_UP -> Icons.Default.AddCard
    action == DictateController.ErrorAction.OPEN_SETTINGS -> Icons.Default.VpnKey
    kind == DictateApiException.Kind.QUOTA_EXCEEDED -> Icons.Default.DataUsage
    kind == DictateApiException.Kind.CONTENT_SIZE_LIMIT -> Icons.Default.WarningAmber
    kind == DictateApiException.Kind.FORMAT_NOT_SUPPORTED -> Icons.Default.GraphicEq
    kind == DictateApiException.Kind.TIMEOUT -> Icons.Default.Schedule
    kind == DictateApiException.Kind.NETWORK -> Icons.Default.CloudOff
    kind == DictateApiException.Kind.SERVER_ERROR -> Icons.Default.CloudOff
    else -> Icons.Default.ErrorOutline
}

/**
 * One-time Smartbar nudge: a tinted icon, a short message and accept (✓) / decline (✗) buttons.
 * Accepting opens the Play Store (rate, 9.7), PayPal (donate, 9.8) or the in-app "What's new" dialog
 * (changelog after an update, 11.9); both buttons mark the nudge as handled so it never reappears.
 * Shown only when idle, replacing the normal Smartbar.
 */
@Composable
private fun PromoContent(kind: DictateController.PromoKind, message: String? = null) {
    val context = LocalContext.current
    val prefs by FlorisPreferenceStore
    val rowStyle = rememberSnyggThemeQuery(FlorisImeUi.SmartbarSharedActionsRow.elementName)
    val accent by prefs.theme.accentColor.collectAsState() // follows the user's keyboard accent.
    val leadingIcon = when (kind) {
        DictateController.PromoKind.RATE -> Icons.Default.Star
        DictateController.PromoKind.DONATE -> Icons.Default.Favorite
        DictateController.PromoKind.CHANGELOG -> Icons.Default.NewReleases
        DictateController.PromoKind.FLOATING_BUTTON -> Icons.Default.Adjust
        DictateController.PromoKind.MILESTONE -> Icons.Default.EmojiEvents
        // The mark of what is running low, not of the action — the accent pill beside it already
        // says "top up".
        DictateController.PromoKind.LOW_CREDIT -> ImageVector.vectorResource(R.drawable.ic_dictate_cloud)
    }
    // Milestone text is dynamic (which milestone), so it arrives via [message]; the rest map to a res.
    val messageRes = when (kind) {
        DictateController.PromoKind.RATE -> R.string.dictate__promo_rate_message
        DictateController.PromoKind.DONATE -> R.string.dictate__promo_donate_message
        DictateController.PromoKind.CHANGELOG -> R.string.dictate__promo_changelog_message
        DictateController.PromoKind.FLOATING_BUTTON -> R.string.dictate__promo_floating_button_message
        DictateController.PromoKind.MILESTONE -> R.string.dictate__stats_milestone_title
        DictateController.PromoKind.LOW_CREDIT -> R.string.dictate__promo_low_credit_message
    }
    val actionRes = when (kind) {
        DictateController.PromoKind.RATE -> R.string.dictate__promo_rate_action
        DictateController.PromoKind.DONATE -> R.string.dictate__promo_donate_action
        DictateController.PromoKind.CHANGELOG -> R.string.dictate__promo_changelog_action
        DictateController.PromoKind.FLOATING_BUTTON -> R.string.dictate__promo_floating_button_action
        DictateController.PromoKind.MILESTONE -> R.string.dictate__promo_milestone_action
        DictateController.PromoKind.LOW_CREDIT -> R.string.dictate__promo_low_credit_action
    }

    // Gentle pop-in (fade + slight scale) on top of the Smartbar's own slide transition.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val appear by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(220),
        label = "promoAppear",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxHeight()
            .padding(horizontal = 4.dp)
            .graphicsLayer {
                alpha = appear
                val s = 0.92f + 0.08f * appear
                scaleX = s
                scaleY = s
            },
    ) {
        // Tinted leading icon (star = rate, heart = donate, badge = update/changelog).
        Icon(
            imageVector = leadingIcon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        // Plain Text (not SnyggText) constrained with weight + single line + ellipsis: long localized
        // messages (e.g. German "Dictate unterstützen?") must shrink/ellipsize instead of wrapping and
        // pushing the accept/dismiss controls off-screen, which would leave the nudge un-dismissable.
        // fill = false keeps the row compact and centered for short locales.
        Text(
            text = message ?: stringRes(messageRes),
            color = rowStyle.foreground(),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.width(10.dp))
        // Filled accent pill = primary action (opens Play Store / PayPal / the in-app changelog).
        Text(
            text = stringRes(actionRes),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(accent)
                .clickable { DictateController.acceptPromo(context) }
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
        Spacer(modifier = Modifier.width(2.dp))
        // Subtle dismiss.
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable { DictateController.declinePromo() }
                .padding(6.dp),
        ) {
            SnyggIcon(
                imageVector = Icons.Default.Close,
                contentDescription = stringRes(R.string.dictate__action_no),
                modifier = Modifier.size(18.dp).alpha(0.6f),
            )
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(totalSec / 60L, totalSec % 60L)
}
