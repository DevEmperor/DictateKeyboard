/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.devemperor.dictate.wear.ime

import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.devemperor.dictate.wear.R
import net.devemperor.dictate.wear.emoji.WearEmojiData
import net.devemperor.dictate.wear.emoji.WearEmojiRecents
import net.devemperor.dictate.wear.ui.WearDictateTheme

/** The three input pages of the Wear keyboard, swipeable via the pager. */
private enum class WearPage(val icon: ImageVector, @StringRes val labelRes: Int) {
    // Order matters: it is the left→right pager order. Voice is the centre/start page; swiping right
    // goes to Numbers (previous), swiping left goes to Emoji (next).
    NUMBERS(Icons.Filled.Dialpad, R.string.wear_tab_numbers),
    VOICE(Icons.Filled.Mic, R.string.wear_tab_voice),
    EMOJI(Icons.Filled.Mood, R.string.wear_tab_emoji),
}

/**
 * Root of the Wear keyboard input view: a compact segmented page switcher + a swipeable pager over the
 * Voice / Numbers / Emoji surfaces, themed with the phone's accent color (synced).
 *
 * Layout is tuned for round watches: the switcher sits below the top arc and the Voice/Numbers pages
 * fit entirely on screen with no scrolling (only the much larger Emoji grid scrolls).
 */
@Composable
fun WearKeyboard(
    actions: WearImeActions,
    dictationState: WearDictationState,
    recordingInfo: WearRecordingInfo,
    errorMessage: String?,
    peakProvider: () -> Int = { 0 },
) {
    WearDictateTheme {
        val pages = WearPage.entries
        val pagerState = rememberPagerState(initialPage = WearPage.VOICE.ordinal, pageCount = { pages.size })
        val scope = rememberCoroutineScope()

        // The pager fills the whole screen so the Voice record button sits at the *true* centre (no top
        // bar pushing it down). A downward drag anywhere closes the keyboard — so the user never has to
        // reach the top edge, which would pull the system notification shade down instead. A faint handle
        // at the very top only hints at the gesture and takes no layout space.
        var swipeDown by remember { mutableFloatStateOf(0f) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { swipeDown = 0f },
                        onDragCancel = { swipeDown = 0f },
                    ) { change, dragAmount ->
                        swipeDown = if (dragAmount > 0f) swipeDown + dragAmount else 0f
                        if (swipeDown > 130f) {
                            swipeDown = 0f
                            actions.dismiss()
                        }
                        change.consume()
                    }
                },
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when (pages[index]) {
                        WearPage.VOICE ->
                            VoicePage(
                                state = dictationState,
                                recordingInfo = recordingInfo,
                                errorMessage = errorMessage,
                                actions = actions,
                                peakProvider = peakProvider,
                                onShowNumbers = {
                                    scope.launch { pagerState.animateScrollToPage(WearPage.NUMBERS.ordinal) }
                                },
                                onShowEmoji = {
                                    scope.launch { pagerState.animateScrollToPage(WearPage.EMOJI.ordinal) }
                                },
                            )
                        // Numbers/Emoji get a round-screen inset so their corner cells aren't clipped.
                        WearPage.NUMBERS -> PageInset { NumbersPage(actions) }
                        WearPage.EMOJI -> PageInset { EmojiPage(actions) }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .size(width = 22.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colors.onSurfaceVariant.copy(alpha = 0.4f)),
            )
        }
    }
}

/**
 * Insets page content away from the clipped corners on round watches; near-zero padding on square ones.
 *
 * The keypad / emoji grid fill the full rectangular width, so on a circular display their corner cells
 * are cut off by the edge (Play "Watch shapes" rejection). Padding by the [INSCRIBED_SQUARE_INSET]
 * fraction of the screen size fits the content inside the circle's inscribed square, which guarantees
 * every corner cell sits at or within the circular edge. Scaling with the actual screen size keeps it
 * correct across watch sizes (a fixed dp was far too small on larger round displays).
 */
@Composable
private fun PageInset(content: @Composable () -> Unit) {
    val config = LocalConfiguration.current
    val round = config.isScreenRound
    val hPad = if (round) (config.screenWidthDp * INSCRIBED_SQUARE_INSET).dp else 6.dp
    val vPad = if (round) (config.screenHeightDp * INSCRIBED_SQUARE_INSET).dp else 2.dp
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = hPad, vertical = vPad),
    ) {
        content()
    }
}

/**
 * Fraction of the screen to inset on round watches so full-width content fits the inscribed square:
 * `(1 − 1/√2) / 2 ≈ 0.1464` — the same factor Wear's BoxInsetLayout uses.
 */
private const val INSCRIBED_SQUARE_INSET = 0.1464f

/**
 * A small pill pinned to a screen edge on the Voice page: an outward chevron + the target page's icon,
 * making it clear there is more input to the sides and acting as a tap shortcut. Numbers sits on the
 * left, Emoji on the right (matching the pager order: swipe right → Numbers, swipe left → Emoji).
 */
@Composable
private fun EdgeChip(
    page: WearPage,
    chevronOnStart: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        if (chevronOnStart) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = null, modifier = Modifier.size(15.dp))
        }
        Icon(imageVector = page.icon, contentDescription = stringResource(page.labelRes), modifier = Modifier.size(14.dp))
        if (!chevronOnStart) {
            Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun VoicePage(
    state: WearDictationState,
    recordingInfo: WearRecordingInfo,
    errorMessage: String?,
    actions: WearImeActions,
    peakProvider: () -> Int,
    onShowNumbers: () -> Unit,
    onShowEmoji: () -> Unit,
) {
    val recording = state == WearDictationState.RECORDING
    val transcribing = state == WearDictationState.TRANSCRIBING
    val rewording = state == WearDictationState.REWORDING
    val busy = transcribing || rewording
    val paused = recordingInfo.paused

    // Rolling window of recent mic levels (0..1) feeding the live waveform; polled from the recorder
    // while actively recording, frozen on pause, cleared once recording ends.
    val levels = remember { mutableStateListOf<Float>() }
    LaunchedEffect(recording, paused) {
        if (recording && !paused) {
            while (true) {
                val lvl = kotlin.math.sqrt((peakProvider() / 32767f).coerceIn(0f, 1f))
                levels.add(lvl)
                if (levels.size > WAVEFORM_BARS) levels.removeAt(0)
                delay(70L)
            }
        } else if (!recording) {
            levels.clear()
        }
    }

    // Subtle breathing pulse on the record button while recording — scales in place only, so the button
    // never changes position. Gentle enough to leave the larger pause/stop controls below room.
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "pulse",
    )
    val scale = if (recording && !paused) pulse else 1f

    // The record button is pinned to the exact centre and never moves; the timer/waveform sit above it
    // and the controls/status below, both offset from centre so they don't push the button. The page
    // navigation chips (Numbers / Emoji) live on the side edges, vertically centred — only here.
    Box(modifier = Modifier.fillMaxSize()) {
        EdgeChip(
            page = WearPage.NUMBERS,
            chevronOnStart = true,
            onClick = onShowNumbers,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        EdgeChip(
            page = WearPage.EMOJI,
            chevronOnStart = false,
            onClick = onShowEmoji,
            modifier = Modifier.align(Alignment.CenterEnd),
        )

        Button(
            onClick = actions.toggleDictation,
            enabled = !busy,
            modifier = Modifier.align(Alignment.Center).size(64.dp).scale(scale),
            colors = ButtonDefaults.primaryButtonColors(),
        ) {
            when {
                busy -> CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 3.dp)
                recording -> Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.wear_cd_stop), modifier = Modifier.size(28.dp))
                else -> Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.wear_cd_dictate), modifier = Modifier.size(28.dp))
            }
        }

        // Timer + waveform above the button (only while recording).
        if (recording) {
            Column(
                modifier = Modifier.align(Alignment.Center).offset(y = (-58).dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ElapsedTimer(recordingInfo)
                Waveform(levels)
            }
        }

        // Controls (recording) / status line (otherwise) below the button.
        Box(
            modifier = Modifier.align(Alignment.Center).offset(y = 56.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (recording) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    SmallAction(Icons.Filled.Close, stringResource(R.string.wear_cd_cancel), actions.cancelDictation)
                    SmallAction(
                        if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        if (paused) stringResource(R.string.wear_cd_resume) else stringResource(R.string.wear_cd_pause),
                        actions.togglePause,
                    )
                }
            } else {
                Text(
                    text = when (state) {
                        WearDictationState.TRANSCRIBING -> stringResource(R.string.wear_status_transcribing)
                        WearDictationState.REWORDING -> stringResource(R.string.wear_status_rewording)
                        WearDictationState.ERROR -> errorMessage ?: stringResource(R.string.wear_status_error)
                        else -> stringResource(R.string.wear_status_tap)
                    },
                    style = MaterialTheme.typography.caption2,
                    color = if (state == WearDictationState.ERROR) MaterialTheme.colors.error
                    else MaterialTheme.colors.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

/** m:ss elapsed counter, ticking every 200ms while running and frozen while paused (phone parity). */
@Composable
private fun ElapsedTimer(info: WearRecordingInfo) {
    var elapsedMs by remember { mutableLongStateOf(info.accumulatedMs) }
    LaunchedEffect(info.startedAtMs, info.accumulatedMs, info.paused) {
        if (info.paused) {
            elapsedMs = info.accumulatedMs
        } else {
            while (true) {
                elapsedMs = info.accumulatedMs + (SystemClock.elapsedRealtime() - info.startedAtMs)
                delay(200L)
            }
        }
    }
    val totalSec = elapsedMs / 1000L
    Text(
        text = "%d:%02d".format(totalSec / 60L, totalSec % 60L),
        style = MaterialTheme.typography.title3,
        color = if (info.paused) MaterialTheme.colors.onSurfaceVariant else MaterialTheme.colors.primary,
    )
}

/** Number of bars in the live recording waveform. */
private const val WAVEFORM_BARS = 19

/**
 * A compact live level meter shown under the timer while recording: [WAVEFORM_BARS] thin accent bars,
 * the most recent level on the right, scrolling left as new samples arrive. Stays small so it never
 * crowds the round screen; if there are no samples yet it is a flat baseline.
 */
@Composable
private fun Waveform(levels: List<Float>) {
    // Pad the left with silence so the bars fill from the right (newest sample) as the buffer fills.
    val padded = List((WAVEFORM_BARS - levels.size).coerceAtLeast(0)) { 0f } + levels.takeLast(WAVEFORM_BARS)
    Row(
        modifier = Modifier.height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        padded.forEach { level ->
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = (3f + level * 13f).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colors.primary),
            )
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        colors = ButtonDefaults.secondaryButtonColors(),
    ) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun NumbersPage(actions: WearImeActions) {
    // A 3x4 keypad whose keys expand (via weight) to fill the whole input area — big, easy targets,
    // no scrolling. Rounded-rectangle keys look like a proper keypad and work on round and square.
    // Bottom row is backspace / 0 / enter.
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("⌫", "0", "⏎"),
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colors.surface)
                            .clickable {
                                when (key) {
                                    "⌫" -> actions.deleteBackward()
                                    "⏎" -> actions.performEnter()
                                    else -> actions.commitText(key)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        when (key) {
                            "⌫" -> Icon(Icons.AutoMirrored.Filled.Backspace, stringResource(R.string.wear_cd_backspace), modifier = Modifier.size(22.dp))
                            "⏎" -> Icon(Icons.AutoMirrored.Filled.KeyboardReturn, stringResource(R.string.wear_cd_enter), modifier = Modifier.size(22.dp))
                            else -> Text(key, style = MaterialTheme.typography.title2, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmojiPage(actions: WearImeActions) {
    val context = LocalContext.current
    val categories = remember { WearEmojiData.categories(context) }
    var recents by remember { mutableStateOf(WearEmojiRecents.get(context)) }
    var selectedTab by remember { mutableIntStateOf(0) }

    // Tabs the user can pick from: a recents tab first (only when non-empty), then each category.
    // selectedTab indexes straight into this list (clamped) so adding/removing the recents tab is safe.
    val tabs = buildList {
        if (recents.isNotEmpty()) add("🕘" to recents)
        categories.forEach { add(it.label to it.emojis) }
    }
    val activeTab = selectedTab.coerceIn(0, tabs.lastIndex)

    Column(
        // Push the whole emoji page (the category tabs first of all) down from the narrow top of a round
        // screen, so the outer category tabs are easy to tap.
        modifier = Modifier.fillMaxSize().padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            tabs.forEachIndexed { index, (label, _) ->
                EmojiTab(label = label, selected = activeTab == index) { selectedTab = index }
            }
        }

        RotaryGrid(items = tabs[activeTab].second, columns = 4) { emoji ->
            Button(
                onClick = {
                    actions.commitText(emoji)
                    WearEmojiRecents.add(context, emoji)
                    recents = WearEmojiRecents.get(context)
                },
                modifier = Modifier.size(36.dp),
                colors = ButtonDefaults.secondaryButtonColors(),
            ) {
                Text(emoji, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun EmojiTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(28.dp),
        colors = if (selected) ButtonDefaults.primaryButtonColors() else ButtonDefaults.secondaryButtonColors(),
    ) {
        Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2)
    }
}

/**
 * A [LazyVerticalGrid] wired for the watch's rotary crown/bezel: rotating scrolls the grid. The grid
 * grabs focus so it receives rotary events even though there is no text field on the page. Used for the
 * emoji picker, which is too large to fit on screen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun <T> RotaryGrid(
    items: List<T>,
    columns: Int,
    item: @Composable (T) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .onRotaryScrollEvent { event ->
                scope.launch { gridState.scrollBy(event.verticalScrollPixels) }
                true
            }
            .focusRequester(focusRequester)
            .focusable(),
        // Small top gap below the category tabs; the bulk of the top inset is above the tabs row instead.
        contentPadding = PaddingValues(top = 4.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items) { item(it) }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
