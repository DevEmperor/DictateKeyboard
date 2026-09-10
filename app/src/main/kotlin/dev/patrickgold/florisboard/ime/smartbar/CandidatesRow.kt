/*
 * Copyright (C) 2024-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.smartbar

import android.text.TextUtils
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.nlp.ClipboardSuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.EmojiSuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.NlpManager
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import kotlinx.coroutines.launch
import org.florisboard.lib.android.showShortToast
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.nlpManager
import dev.patrickgold.florisboard.subtypeManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import org.florisboard.lib.compose.conditional
import org.florisboard.lib.compose.florisHorizontalScroll
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggSpacer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import org.florisboard.lib.snygg.ui.SnyggText

val CandidatesRowScrollbarHeight = 2.dp

/**
 * How many word candidates the classic strip shows. Emoji suggested for a typed word are added beside
 * these rather than counted among them (issue #338).
 */
private const val CLASSIC_WORD_SLOTS = 3

/**
 * How many of those emoji the classic strip will take. The words share whatever width the emoji cells
 * leave, so this is not a preference but a physical limit: at the top of the "maximum candidate count"
 * slider, six square cells would squeeze three words into nothing. The scrolling display modes have no
 * such ceiling and show the full count.
 */
private const val CLASSIC_MAX_EMOJI = 3

/**
 * How far a candidate's label may shrink to fit its cell before the ellipsis takes over, as a fraction
 * of whatever size the theme and the user's font scale resolved to (issue #346).
 *
 * Measured against the case that was reported: a classic-mode cell is about a third of the strip, which
 * leaves the label roughly 94 dp once the margin and padding are paid, and "Misunderstanding" wants about
 * 116 dp at the default 14 sp — 81 % of it. Three quarters clears that with room for a wider font or a
 * raised font scale, and stops well short of the size at which a shrunk label is worse than a cut one.
 */
private const val CANDIDATE_MIN_FONT_RATIO = 0.75f

@Composable
fun CandidatesRow(modifier: Modifier = Modifier) {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val nlpManager by context.nlpManager()
    val subtypeManager by context.subtypeManager()

    val scope = rememberCoroutineScope()
    val displayMode by prefs.suggestion.displayMode.collectAsState()
    // Off by default — the strip belongs to the suggestions, and this only borrows it (issue #335).
    val showSelectionCounter = rememberSelectionCounterVisible()
    val candidates by nlpManager.activeCandidatesFlow.collectAsState()
    // Read once per composition instead of a synchronous pref get() per candidate on every keystroke
    // (the candidates row recomposes on each character — issue: typing jank).
    val longPressDelay by prefs.keyboard.longPressDelay.collectAsState()

    // The strip runs in the *typed* language's direction, not the phone's (issue #265). LocalLayoutDirection
    // follows the system locale, so writing Arabic on a German phone laid the candidates out left to right
    // while the words inside them ran right to left — the best suggestion ended up on the far side from
    // where the writing does. Also fixes he, fa, ur and ckb, which had it too.
    val activeSubtype by subtypeManager.activeSubtypeFlow.collectAsState()
    val layoutDirection = remember(activeSubtype.primaryLocale) {
        when (TextUtils.getLayoutDirectionFromLocale(activeSubtype.primaryLocale.base)) {
            View.LAYOUT_DIRECTION_RTL -> LayoutDirection.Rtl
            else -> LayoutDirection.Ltr
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
    SnyggRow(
        elementName = FlorisImeUi.SmartbarCandidatesRow.elementName,
        modifier = modifier
            .fillMaxSize()
            .conditional(displayMode == CandidatesDisplayMode.DYNAMIC_SCROLLABLE && candidates.size > 1) {
                florisHorizontalScroll(scrollbarHeight = CandidatesRowScrollbarHeight)
            },
        horizontalArrangement = if (candidates.size > 1) {
            Arrangement.Start
        } else {
            Arrangement.Center
        },
    ) {
        // First in the row, and composed unconditionally: it draws nothing unless something is selected,
        // and while something is selected there is no word being typed, so it usually has the row alone.
        if (showSelectionCounter) {
            SelectionCounterPill()
        }
        if (candidates.isNotEmpty()) {
            // An emoji suggested for a typed word annexes the row rather than taking a word's place
            // (issue #338): the three word slots stay three, and the emoji ride along in narrow cells
            // of their own. Only when there are words to protect — a colon *search* returns nothing but
            // emoji, and those go on filling the row the way they always have.
            val words = candidates.filterNot { it is EmojiSuggestionCandidate }
            val annexedEmojis = if (words.isEmpty()) {
                emptyList()
            } else {
                candidates.filterIsInstance<EmojiSuggestionCandidate>()
            }
            val list = when {
                annexedEmojis.isEmpty() && displayMode == CandidatesDisplayMode.CLASSIC ->
                    candidates.subList(0, 3.coerceAtMost(candidates.size))
                annexedEmojis.isEmpty() -> candidates
                // The classic strip renders what it is given, so the words are cut to their three
                // slots here; the scrolling modes keep every word and simply carry the emoji at a
                // position where they are visible without scrolling.
                displayMode == CandidatesDisplayMode.CLASSIC ->
                    words.take(CLASSIC_WORD_SLOTS) + annexedEmojis.take(CLASSIC_MAX_EMOJI)
                else ->
                    words.take(CLASSIC_WORD_SLOTS) + annexedEmojis + words.drop(CLASSIC_WORD_SLOTS)
            }
            // A square cell for an annexed emoji, the same shape the emoji row uses, so it costs the
            // words a sliver of width instead of a whole slot. Read out here: it comes from a
            // composition local and cannot be asked for from inside a plain helper function.
            val emojiCellSize = FlorisImeSizing.smartbarHeight
            // One dismiss button for the whole clipboard offer, carried by the last chip of it (issue
            // #360): the address, link or number chips beside a clip are that same clip, so they go
            // together, and a button at the end reads as closing the offer rather than one item of it.
            val lastClipIndex = list.indexOfLast { it is ClipboardSuggestionCandidate }
            for ((n, candidate) in list.withIndex()) {
                // Held in a local: the row is no longer a prefix of [candidates] once an emoji annexes
                // it, so an index back into that list would commit the wrong thing.
                val item = candidate
                // No hairline between two clipboard chips: they are pills with outlines of their own, and
                // a clip that yields several chips (the clip plus the address, link or number pulled out of
                // it) is one offer, not a list of alternatives to be ruled off from each other.
                if (n > 0 && candidate !is ClipboardSuggestionCandidate) {
                    SnyggSpacer(
                        elementName = FlorisImeUi.SmartbarCandidateSpacer.elementName,
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight(0.6f)
                            .align(Alignment.CenterVertically),
                    )
                }
                val itemModifier = when {
                    candidates.size == 1 -> Modifier
                        .fillMaxHeight()
                        .weight(1f, fill = false)
                    item in annexedEmojis -> Modifier
                        .fillMaxHeight()
                        .width(emojiCellSize)
                    displayMode == CandidatesDisplayMode.CLASSIC -> Modifier
                        .fillMaxHeight()
                        .weight(1f)
                    else -> Modifier
                        .fillMaxHeight()
                        .wrapContentWidth()
                        .widthIn(max = 160.dp)
                }
                CandidateItem(
                    modifier = itemModifier,
                    candidate = candidate,
                    displayMode = displayMode,
                    onDismiss = if (n == lastClipIndex) {
                        {
                            FlorisImeService.inputFeedbackController()?.keyPress()
                            nlpManager.removeSuggestion(subtypeManager.activeSubtype, item)
                        }
                    } else {
                        null
                    },
                    onClick = {
                        FlorisImeService.inputFeedbackController()?.keyPress()
                        keyboardManager.commitCandidate(item)
                    },
                    onLongPress = {
                        val candidateItem = item
                        when {
                            // Clipboard suggestions keep their existing "long-press to forget" behaviour.
                            candidateItem is ClipboardSuggestionCandidate -> {
                                nlpManager.removeSuggestion(subtypeManager.activeSubtype, candidateItem)
                            }
                            // A word the keyboard picked up by itself is already in the vocabulary, so the
                            // gesture means the opposite there: forget it (issue #318). Without this, the
                            // only way to take back something it learned would be the settings screen —
                            // and the moment you want it undone is the moment you see it suggested.
                            candidateItem.isLearned -> {
                                FlorisImeService.inputFeedbackController()?.keyLongPress()
                                nlpManager.forgetLearnedWord(subtypeManager.activeSubtype, candidateItem)
                                scope.launch {
                                    context.showShortToast(
                                        R.string.suggestion__forgot_word,
                                        "word" to candidateItem.text.toString(),
                                    )
                                }
                                true
                            }
                            // For words the gesture teaches the personal dictionary instead (issue #241).
                            // It used to call removeSuggestion(), which every word provider answers with
                            // false, so long-pressing a word did nothing at all.
                            else -> {
                                val subtype = subtypeManager.activeSubtype
                                val result = nlpManager.addToUserDictionary(subtype, candidateItem)
                                val message = when (result) {
                                    NlpManager.AddToDictionaryResult.ADDED ->
                                        R.string.suggestion__added_to_dictionary
                                    NlpManager.AddToDictionaryResult.ALREADY_PRESENT ->
                                        R.string.suggestion__already_in_dictionary
                                    NlpManager.AddToDictionaryResult.UNAVAILABLE -> null
                                }
                                if (message != null) {
                                    // Haptic as well as the toast: Android suppresses toasts entirely when
                                    // the user has turned notifications off for the app, and a silent
                                    // long-press would look broken.
                                    FlorisImeService.inputFeedbackController()?.keyLongPress()
                                    scope.launch {
                                        context.showShortToast(
                                            message,
                                            "word" to candidateItem.text.toString(),
                                        )
                                    }
                                }
                                result != NlpManager.AddToDictionaryResult.UNAVAILABLE
                            }
                        }
                    },
                    longPressDelay = longPressDelay.toLong(),
                )
            }
        }
    }
    }
}

@Composable
private fun CandidateItem(
    candidate: SuggestionCandidate,
    displayMode: CandidatesDisplayMode,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = { },
    onLongPress: () -> Boolean = { false },
    onDismiss: (() -> Unit)? = null,
    longPressDelay: Long,
) = with(LocalDensity.current) {
    var isPressed by remember { mutableStateOf(false) }

    val elementName = if (candidate is ClipboardSuggestionCandidate) {
        FlorisImeUi.SmartbarCandidateClip
    } else {
        FlorisImeUi.SmartbarCandidateWord
    }.elementName
    // Remembered so recomposing the row on each keystroke doesn't allocate a fresh map (which, as an
    // unstable arg to the Snygg composables below, would also defeat their skipping) — reduces the
    // per-keystroke recomposition + GC churn behind the typing jank.
    val autoCommit = candidate.isEligibleForAutoCommit
    val attributes = remember(autoCommit) { mapOf(FlorisImeUi.Attr.AutoCommit to if (autoCommit) 1 else 0) }
    val selector = if (isPressed) SnyggSelector.PRESSED else SnyggSelector.NONE

    // The gesture block below is keyed on `Unit` on purpose — restarting it whenever this cell
    // recomposes would cancel a press that is still in progress, which is the failure
    // [dev.patrickgold.florisboard.ime.text.keyboard.TextKeyboardLayout] already works around. But a
    // block that never restarts also never sees a new [onClick], and a candidate cell is reused: slot
    // one holds "und" while a word is being typed and "Kuchen" a moment later, and the frozen lambda
    // went on committing "und" — the word before the one on screen. These three are read through
    // [rememberUpdatedState] so the long-lived block always calls the current ones.
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentLongPressDelay by rememberUpdatedState(longPressDelay)

    val isClip = candidate is ClipboardSuggestionCandidate

    // The chip is the pill: background, shape, margin and padding all come off [elementName], and the
    // dismiss button below is a child of it so it sits *inside* the pill the way Desh's does. What the
    // finger commits is only the inner row — the press gesture must not cover the button, because
    // [CandidateItem]'s awaitEachGesture block consumes down and up unconditionally and a second
    // pointer consumer nested inside that is the dispatch arrangement that has already cost this
    // keyboard a working gesture. Siblings, both inside the fill.
    SnyggRow(
        elementName = elementName,
        attributes = attributes,
        selector = selector,
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        // The clipboard chip has to read as one thing: icon, label and × sit next to each other and the
        // group is centred (issue #346). Words keep the default, because a word is centred in its own
        // cell by the weight on the column below.
        horizontalArrangement = if (isClip) Arrangement.Center else Arrangement.Start,
    ) {
        Row(
            // `fill = false` for the clip, and that is the whole of what used to push its icon to the far
            // edge of the strip: a filled weight forces the chip's row out to the width it was offered, so
            // in the classic display mode the icon ended up against the left edge with the text centred a
            // finger's width away, looking like two unrelated things. Words still fill — a word is meant
            // to be centred in its third — and the scrolling modes never weighted this at all.
            modifier = when {
                isClip -> Modifier.weight(1f, fill = false)
                displayMode == CandidatesDisplayMode.CLASSIC -> Modifier.weight(1f)
                else -> Modifier
            }
                .fillMaxHeight()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        isPressed = true
                        if (down.pressed != down.previousPressed) down.consume()
                        var upOrCancel: PointerInputChange? = null
                        try {
                            upOrCancel = withTimeout(currentLongPressDelay) {
                                waitForUpOrCancellation()
                            }
                            upOrCancel?.let { if (it.pressed != it.previousPressed) it.consume() }
                        } catch (_: PointerEventTimeoutCancellationException) {
                            if (currentOnLongPress()) {
                                upOrCancel = null
                                isPressed = false
                            }
                            waitForUpOrCancellation()?.let {
                                if (it.pressed != it.previousPressed) it.consume()
                            }
                        }
                        if (upOrCancel != null) {
                            currentOnClick()
                        }
                        isPressed = false
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (candidate.icon != null) {
                SnyggBox(
                    elementName = "$elementName-icon",
                    attributes = attributes,
                    selector = selector,
                ) {
                    SnyggIcon(imageVector = candidate.icon!!)
                }
            }
            SnyggColumn(
                modifier = if (!isClip && displayMode == CandidatesDisplayMode.CLASSIC) {
                    Modifier.weight(1f)
                } else {
                    Modifier
                },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SnyggText(
                    elementName = "$elementName-text",
                    attributes = attributes,
                    selector = selector,
                    // Gboard-style: bold the suggestion that will be auto-applied (autocorrect), so it's clear
                    // what will replace the typed word; other suggestions stay normal weight (issue #150).
                    fontWeight = if (autoCommit) FontWeight.Bold else null,
                    // Italic marks a word from the user's own vocabulary rather than the bundled dictionary
                    // (issue #318), so it is visible where a suggestion came from — and so the feature can be
                    // seen working at all without waiting for autocorrect to stop interfering.
                    fontStyle = if (candidate.isLearned) FontStyle.Italic else null,
                    // A cell is a third of the strip, which is about thirty dp short of "Misunderstanding" at
                    // the themed size — so the word was cut off with space still visible beside it (issue #346).
                    // Shrinking to fit is what every other keyboard does before it gives up; the floor keeps a
                    // long clipboard paragraph from turning into something nobody can read, and past it the
                    // ellipsis takes over as before.
                    autoSizeMinRatio = CANDIDATE_MIN_FONT_RATIO,
                    // Past the floor, an address loses its middle instead of its tail: what identifies
                    // prateeksingh8997@gmail.com is the domain, not the leading half of the name.
                    overflow = if (candidate.keepsTailWhenShortened) TextOverflow.MiddleEllipsis else null,
                    text = candidate.text.toString(),
                )
                if (candidate.secondaryText != null) {
                    SnyggText(
                        elementName = "$elementName-secondary-text",
                        attributes = attributes,
                        selector = selector,
                        text = candidate.secondaryText!!.toString(),
                    )
                }
            }
        }
        // A visible way out of the clipboard offer (issue #360). Dismissing it was a long-press and
        // nothing else, which nobody finds — and it stopped being a detail the moment the chip began
        // holding the strip whenever there is nothing else to show instead of vanishing at the first
        // keystroke. It carries no press state of its own into the pill: the ripple and the round
        // background are the whole feedback, and pressing it must not light the chip up as if the
        // clip were about to be pasted.
        if (onDismiss != null) {
            SnyggIconButton(
                elementName = FlorisImeUi.SmartbarCandidateClipDismiss.elementName,
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f),
            ) {
                SnyggIcon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringRes(R.string.dictate__action_dismiss),
                )
            }
        }
    }
}
