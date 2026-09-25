/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.smartbar.quickaction

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import dev.patrickgold.compose.tooltip.PlainTooltip
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.dictate.ui.DictateHoldTargets
import dev.patrickgold.florisboard.dictate.ui.DictateHoldTouch
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard.ComputingEvaluator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Deselect
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.keyboard.computeImageVector
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import dev.patrickgold.florisboard.ime.keyboard.computeLabel
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import org.florisboard.lib.snygg.SnyggQueryAttributes
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.rememberSnyggThemeQuery
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * How long the mic must be held before it becomes push-to-talk rather than a tap (#235).
 *
 * It was 110 ms, and an ordinary deliberate tap runs 80–150: every slower tap became a hold, and its
 * release ended the recording it had just started (#422). 300 is the keyboard's own long-press default.
 */
private const val PUSH_TO_TALK_HOLD_MS = 300L

/**
 * How long, counted from the finger landing, a hold must last before its release sends (#422).
 *
 * A release between [PUSH_TO_TALK_HOLD_MS] and this is neither a tap nor a dictation — too slow for one,
 * too short to have said anything in — and is dropped without a trace, the way a voice-message button
 * drops a hold it cannot use.
 */
private const val PUSH_TO_TALK_MIN_SEND_MS = 1000L

/** How far the swollen mic travels left, and how far the finger must slide, to discard (#235). */
private val PUSH_TO_TALK_TRAVEL = 105.dp

/** Slide-left distance that arms discarding a held recording — same as the visual travel (#235). */
private val PUSH_TO_TALK_CANCEL_SLIDE = PUSH_TO_TALK_TRAVEL

/** Slide-up distance to the lock target that latches a held recording (#235). */
private val PUSH_TO_TALK_LOCK_SLIDE = 70.dp

/** How far the finger must move before it commits to an axis (#235). */
private val PUSH_TO_TALK_AXIS_COMMIT = 16.dp

/** How far back it must come before that commitment is released again (#235). */
private val PUSH_TO_TALK_AXIS_RELEASE = 6.dp

/** How long the discarded mic takes to reach the bin — slow enough to read as a throw (#235). */
private val PUSH_TO_TALK_FLIGHT_MS = DictateController.PUSH_TO_TALK_FLIGHT_MS.toInt()

/** How small the mic ends up — just small enough to sit in the bin, not gone. */
private const val PUSH_TO_TALK_LANDED_SCALE = 0.28f

/** Space kept above the mic inside its window, so neither the throw's arc nor the drag up to the lock is
 * cut off at the window edge (#235). */
private val BUBBLE_HEADROOM_TOP = 44.dp + PUSH_TO_TALK_LOCK_SLIDE

/** Space kept below it, so the shadow is not clipped either (#235). */
private val BUBBLE_HEADROOM_BOTTOM = 24.dp

/** Diameter of the swollen mic, relative to the row it grows out of (#235). */
private const val HELD_MIC_DIAMETER = 1.9f

/**
 * How big the second-action badge is next to the action's own icon (#385).
 *
 * Measured against the key's icon rather than against the hint element the badge borrows its colour
 * from: `key-hint` is `12sp` in all 20 bundled stylesheets, and the old 1.35 factor on top of that came
 * out at 16.2 dp beside an 18 dp Smartbar icon — 90 % of it. That is not a badge, it is a second icon,
 * and in a 40 dp key the two boxes overlapped by 10 × 10 dp, straight across the main glyph. At 0.6 the
 * badge is unmistakably the subordinate of the pair while still being recognisable.
 */
private const val SECOND_ACTION_BADGE_FRACTION = 0.6f

/**
 * How far the badge sits in from the key's corner, again as a fraction of the key's icon so it follows
 * the theme. The old fixed 5 dp was meant to keep the glyph off the key's edge, but in a key that is
 * only twice as wide as its icon it did the opposite: it pushed the badge *into* the icon.
 */
private const val SECOND_ACTION_BADGE_INSET_FRACTION = 0.10f

/**
 * The gap kept clear around the badge, punched out of the key's content by [secondActionGap].
 *
 * A corner badge on a centred icon always overlaps the icon's *bounding box* — what may not happen is
 * the two inks touching, because two line drawings that run into each other read as one
 * unrecognisable third shape. Most glyphs have empty corners and never come close; a box-filling one
 * (select-all, the GIF lettering) does, and loses a hair of its outline here instead.
 */
private const val SECOND_ACTION_BADGE_RING_FRACTION = 0.09f

/** Badge size for a theme that sizes the key's icon nowhere up the chain (18sp × 0.6, the stock value). */
private val SECOND_ACTION_BADGE_FALLBACK = 11.dp

/** The badge's glyph, plus how big it is and how much room it claims — all from the key's icon (#385). */
private data class SecondActionBadge(val icon: ImageVector, val size: Dp, val inset: Dp, val ring: Dp)

/**
 * The badge's geometry for the key currently being drawn.
 *
 * The size is read from the resolved style of the key's *icon* element, because that is the glyph the
 * badge has to stay subordinate to, and `font-size` is what Snygg sizes an icon from. It is asked for
 * here rather than at the call site so the query inherits from the key's own style — the same chain the
 * icon itself resolves through, which for every bundled theme ends at `smartbar`'s 18sp.
 */
@Composable
private fun rememberSecondActionBadge(
    icon: ImageVector,
    elementName: String,
    attributes: SnyggQueryAttributes,
    selector: SnyggSelector?,
): SecondActionBadge {
    val style = rememberSnyggThemeQuery("$elementName-icon", attributes, selector)
    val fontSize = style.fontSize(default = TextUnit.Unspecified)
    val iconSize = with(LocalDensity.current) {
        if (fontSize.isSp && fontSize >= 1.sp) fontSize.toDp() else null
    }
    val reference = iconSize ?: (SECOND_ACTION_BADGE_FALLBACK / SECOND_ACTION_BADGE_FRACTION)
    return SecondActionBadge(
        icon = icon,
        size = reference * SECOND_ACTION_BADGE_FRACTION,
        inset = reference * SECOND_ACTION_BADGE_INSET_FRACTION,
        ring = reference * SECOND_ACTION_BADGE_RING_FRACTION,
    )
}

/**
 * Clears a disc around the second-action badge out of everything drawn inside, so the key's own glyph
 * can never run into the badge and fuse with it into one unreadable shape.
 *
 * `BlendMode.Clear` rather than the disc in the container's colour that the settings tiles use, because
 * on the keyboard there is no colour to draw: `smartbar-action-key` is `transparent` in 16 of the 20
 * bundled stylesheets and `smartbar` never sets a background at all, so a key sits on the keyboard's own
 * background — a blurred image in the glass themes. Clearing needs no colour and is therefore right on
 * every theme.
 *
 * It belongs on a node that holds the action's own content and **nothing else**. The key's background
 * is painted by [SnyggBox] further out and is not in this layer, so the gap shows the key rather than a
 * hole in it — and the badge is drawn by a sibling, because the clear happens after everything in this
 * layer and would otherwise take the badge with it.
 */
private fun Modifier.secondActionGap(badge: SecondActionBadge): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val half = badge.size.toPx() / 2f
        val fromEdge = badge.inset.toPx() + half
        drawCircle(
            color = Color.Black,
            radius = half + badge.ring.toPx(),
            // TopEnd, so which side that is depends on the layout direction.
            center = Offset(
                x = if (layoutDirection == LayoutDirection.Ltr) size.width - fromEdge else fromEdge,
                y = fromEdge,
            ),
            blendMode = BlendMode.Clear,
        )
    }

/**
 * The swollen mic shown while the key is held for push-to-talk (#235), plus the lock target below it.
 *
 * Deliberately popups rather than a scaled key: scaling the key transforms the node that owns the
 * pointer input, and Compose maps finger positions through that transform, which silently registered as
 * a slide-to-cancel. Popups are separate windows — they animate freely, are not clipped by the Smartbar,
 * and cannot perturb the gesture.
 *
 * Positioned from [keyBounds] rather than from the popup's own anchor: this is composed outside the key,
 * so its layout placeholder sits wherever the parent puts a zero-size child — which is not the key.
 *
 * It only appears when the rewording row is on. That row sits above the Smartbar and is the only thing
 * that gives us room upwards; without it the circle could only grow down and left, which looks lopsided
 * rather than pressed, so nothing grows at all.
 */
@Composable
private fun HeldMicBubble(keyBounds: IntRect, flying: Boolean, appear: Float, visible: Boolean) {
    val prefs by FlorisPreferenceStore
    val accent = remember { prefs.theme.accentColor.get() }
    val cancelProgress by DictateController.cancelSlideProgress.collectAsState()
    val lockProgress by DictateController.lockSlideProgress.collectAsState()
    val rowHeight = FlorisImeSizing.smartbarHeight
    val diameter = rowHeight * HELD_MIC_DIAMETER
    val density = LocalDensity.current
    val diameterPx = with(density) { diameter.roundToPx() }
    val headroomPx = with(density) { BUBBLE_HEADROOM_TOP.roundToPx() }
    // The window's own geometry is fixed. It used to be sized from the bin's position, and the bin only
    // reports itself once the recording bar exists — so the window was rebuilt a frame or two into the
    // gesture, which is one of the jumps. The bin now only steers the throw, not the layout.
    val bin by DictateHoldTargets.binBounds.collectAsState()
    // The window is the screen, and the mic is placed inside it by its distance from the *left* edge. Both
    // halves of that matter: a window wider than the screen has its content clamped to the screen while its
    // position is still computed from the width that was asked for, and anything measured from the right
    // edge inherits that error — which is exactly how far the mic used to sit left of the key. Measured
    // from the left, nothing can move it, and the mic keeps the key's centre for the whole gesture. It is
    // wider than the space left of the screen edge, and sticking out over that edge is what it should do.
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val micLeftPx = keyBounds.center.x - diameterPx / 2
    // How far left it can travel before it would leave its own window.
    val flightSpanPx = micLeftPx.coerceAtLeast(0)
    // Distance to the bin, capped at the runway the window actually provides.
    val binReachPx = bin?.let { (keyBounds.center.x - it.center.x).coerceIn(0, flightSpanPx) }
        ?: flightSpanPx
    // Starts at exactly the size of the key it covers, so the hold looks like that very button growing
    // rather than a second one appearing over it.
    val startScale = if (diameterPx > 0) keyBounds.height.toFloat() / diameterPx else 1f
    val binDropY = bin?.let { (it.center.y - keyBounds.center.y).toFloat() } ?: 0f

    // The throw: one shot, unhurried, and a real arc — the mic is a thing being thrown away, so it
    // travels and lands rather than dissolving where it stands.
    val flight = remember { Animatable(0f) }
    LaunchedEffect(flying) {
        if (flying) flight.animateTo(1f, tween(PUSH_TO_TALK_FLIGHT_MS, easing = FastOutSlowInEasing))
    }

    Popup(
        properties = PopupProperties(focusable = false, clippingEnabled = false),
        popupPositionProvider = remember(keyBounds, diameterPx, headroomPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ) = IntOffset(
                    // Computed from known sizes, never from popupContentSize: that is zero on the first
                    // measure, so the window was placed wrong for one frame and the mic visibly twitched
                    // left before settling.
                    x = 0,
                    y = keyBounds.center.y - headroomPx - diameterPx / 2,
                )
            }
        },
    ) {
        // Tall enough for the throw's arc and the full drag up to the lock, so the mic is never cut off at
        // its own window edge mid-gesture.
        Box(
            modifier = Modifier.size(
                width = with(density) { screenWidthPx.toDp() },
                height = diameter + BUBBLE_HEADROOM_TOP + BUBBLE_HEADROOM_BOTTOM,
            ),
            contentAlignment = Alignment.TopStart,
        ) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(micLeftPx, 0) }
                    .padding(top = BUBBLE_HEADROOM_TOP)
                    .size(diameter)
                    .graphicsLayer {
                        val f = flight.value
                        // Rides the finger along whichever axis it committed to (the gesture feeds only
                        // one of these at a time), so it never drifts off diagonally.
                        val slideX = -cancelProgress * PUSH_TO_TALK_TRAVEL.toPx()
                        val slideY = -lockProgress * PUSH_TO_TALK_LOCK_SLIDE.toPx()
                        // Sets off from exactly where the hand let go — which is always the threshold,
                        // since that is what triggered the throw — and lands on the bin's measured
                        // centre rather than in its general direction.
                        val fromX = -PUSH_TO_TALK_TRAVEL.toPx()
                        val toX = -binReachPx.toFloat()
                        val fromY = 0f
                        val toY = binDropY
                        // Keyed on `flying`, not on the animation having started: for one frame after
                        // the discard the animation is still at zero while the slide progress is already
                        // cleared, and reading the slide there snapped the mic back to the key.
                        translationX = if (flying) fromX + f * (toX - fromX) else slideX
                        // Tossed up on the way, so it arcs into the bin instead of sliding along to it.
                        translationY =
                            if (flying) fromY + f * (toY - fromY) - (4f * f * (1f - f)) * rowHeight.toPx()
                            else slideY
                        // Shrinks on the way, but only until it is small enough to sit in the bin —
                        // shrinking to nothing looks like it evaporated rather than landed.
                        val thrown = if (flying) 1f - (1f - PUSH_TO_TALK_LANDED_SCALE) * f else 1f
                        val swell = startScale + (1f - startScale) * appear
                        scaleX = swell * thrown
                        scaleY = swell * thrown
                        // Slips out of sight as it reaches the bin. A popup is its own window and always
                        // draws above the keyboard, so it cannot actually pass *behind* the bin icon —
                        // fading over the last stretch is as close as this can get.
                        // Invisible while the window is only being warmed up: it is put on screen the
                        // moment the finger goes down, long before the hold is anything, because adding a
                        // window costs several frames — frames in which the key had already switched to
                        // its recording icon and the overlay was simply not there yet.
                        alpha = when {
                            !visible -> 0f
                            flying -> ((1f - f) / 0.18f).coerceIn(0f, 1f)
                            else -> 1f
                        }
                        shadowElevation = 12f
                        shape = CircleShape
                        clip = true
                    }
                    .background(accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(rowHeight * 0.6f),
                )
            }
        }
    }

    // Lock target above the mic, and the drag to it goes up, the way voice-message buttons everywhere do.
    // Its own window draws over the app above the keyboard, so there is as much room up there as it needs.
    // A target that is simply visible from the start explains the gesture better than one that has to be
    // guessed at.
    if (!flying) {
        val lockWidth = rowHeight * 0.9f
        val lockHeight = rowHeight * 1.25f
        val lockWidthPx = with(density) { lockWidth.roundToPx() }
        val lockHeightPx = with(density) { lockHeight.roundToPx() }
        Popup(
            properties = PopupProperties(focusable = false, clippingEnabled = false),
            popupPositionProvider = remember(keyBounds, lockWidthPx, lockHeightPx) {
                // Same as the mic above: from known sizes, never from popupContentSize, which is zero on
                // the first measure and put the target somewhere else for that frame.
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ) = IntOffset(
                        x = keyBounds.center.x - lockWidthPx / 2,
                        y = keyBounds.top - lockHeightPx * 7 / 4,
                    )
                }
            },
        ) {
            val locked = lockProgress >= 1f
            // This badge carries **white** content — the padlock, the arrow, and the white flash as it
            // catches — so it needs a dark ground, and that is the whole rule here.
            //
            // It used to be a flat 55% black. 08bc50bb moved it to `key-popup-box` so it would follow the
            // theme, which was right for the case that commit was about — a dark blob floating over a
            // translucent keyboard belongs to nothing — and wrong for every other theme: a key popup is a
            // light surface meant for dark text, so the badge became `#757575` on the default night theme
            // and `#ffffff` on Contrast, E-Ink and Sand, where a white padlock is simply not there.
            //
            // So: the themed surface only where the theme is **translucent**, which is exactly the glass
            // themes 08bc50bb was written for and nothing else. Everywhere else the old scrim is back.
            val popupSurface = rememberSnyggThemeQuery(FlorisImeUi.KeyPopupBox.elementName)
                .background(default = Color.Transparent)
            val restingFill = if (popupSurface.alpha < 1f && popupSurface.alpha > 0f) {
                popupSurface
            } else {
                Color.Black.copy(alpha = 0.55f)
            }
            // A single pop at the instant it catches — the gesture ends there, so without a beat of
            // feedback the only sign that anything happened is a lock icon you are not looking at.
            val catchPop = remember { Animatable(0f) }
            LaunchedEffect(locked) {
                if (locked) {
                    catchPop.snapTo(1f)
                    catchPop.animateTo(0f, spring(dampingRatio = Spring.DampingRatioLowBouncy))
                }
            }
            Box(
                modifier = Modifier
                    .size(width = lockWidth, height = lockHeight)
                    .graphicsLayer {
                        // Emerges from behind the growing mic and slides up into place, rather than
                        // appearing fully formed above it — it should read as coming *out of* the button
                        // the finger is on.
                        translationY = (1f - appear) * (rowHeight.toPx() * 0.6f + size.height * 0.75f)
                        alpha = if (visible) appear * (1f - cancelProgress).coerceIn(0f, 1f) else 0f
                        val lift = appear + 0.15f * lockProgress + 0.45f * catchPop.value
                        scaleX = lift
                        scaleY = lift
                    }
                    .background(
                        // Fills with the accent the closer the finger gets, so "how much further" needs
                        // no text, and flashes white for an instant as it catches.
                        lerp(
                            lerp(restingFill, accent, lockProgress),
                            Color.White,
                            catchPop.value * 0.7f,
                        ),
                        RoundedCornerShape(percent = 45),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(rowHeight * 0.38f),
                    )
                    if (!locked) {
                        // Under the padlock and pointing at it: the finger comes from below.
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(rowHeight * 0.3f),
                        )
                    }
                }
            }
        }
    }
}

enum class QuickActionBarType {
    INTERACTIVE_BUTTON,
    INTERACTIVE_TILE,
    EDITOR_TILE;
}

@Composable
fun QuickActionButton(
    action: QuickAction,
    evaluator: ComputingEvaluator,
    modifier: Modifier = Modifier,
    type: QuickActionBarType = QuickActionBarType.INTERACTIVE_BUTTON,
    /** What holding this button runs instead of tapping it (issue #385), or null for a plain tap. */
    secondAction: QuickAction? = null,
) {
    val context = LocalContext.current
    val prefs by FlorisPreferenceStore
    val inputFeedbackController = LocalInputFeedbackController.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isEnabled = type == QuickActionBarType.EDITOR_TILE || evaluator.evaluateEnabled(action.keyData())
    val elementName = when (type) {
        QuickActionBarType.INTERACTIVE_BUTTON -> FlorisImeUi.SmartbarActionKey
        QuickActionBarType.INTERACTIVE_TILE -> FlorisImeUi.SmartbarActionTile
        QuickActionBarType.EDITOR_TILE -> FlorisImeUi.SmartbarActionsEditorTile
    }.elementName
    val attributes = mapOf(FlorisImeUi.Attr.Code to action.keyData().code)
    val selector = when {
        isPressed -> SnyggSelector.PRESSED
        !isEnabled -> SnyggSelector.DISABLED
        else -> null
    }

    // Need to manually cancel an action if this composable suddenly leaves the composition to prevent the key from
    // being stuck in the pressed state
    DisposableEffect(action, isEnabled) {
        onDispose {
            if (action is QuickAction.InsertKey) {
                action.onPointerCancel(context)
            }
        }
    }

    // The Dictate action has a dynamic icon (mic → send → hourglass) that depends on the recording state;
    // observed here so the icon recomputes on state changes (for all other actions it's a cheap, stable
    // subscription). Also drives tooltip suppression below.
    val dictateState by DictateController.state.collectAsState()
    // Suppress the tooltip while a long-press shortcut is armed on the Dictate mic, so holding it to run
    // the shortcut (pick a file when idle, or send-with-local-model while recording, #228) doesn't also
    // pop the tooltip text.
    val dictateLongPressArmed = action.keyData().code == KeyCode.IME_UI_MODE_DICTATE && (
        DictateController.canStartRecording() ||
            (prefs.dictate.longPressSendLocalModel.get() && DictateController.canLongPressLocal())
    )
    // Push-to-talk (#235): the mic swells while it is being held, the way a voice-message button does —
    // the one piece of feedback that survives the finger covering the button itself.
    val ptt by DictateController.pushToTalkVisuals.collectAsState()
    val isDictateKey = action.keyData().code == KeyCode.IME_UI_MODE_DICTATE
    val holdingMic = isDictateKey && ptt.phase.isHolding
    // Where the key actually is on screen — the popups are anchored to this rather than to their own
    // placeholder, which sits wherever the parent puts a zero-size child.
    var micKeyBounds by remember { mutableStateOf<IntRect?>(null) }
    /** True from the finger landing on the mic until it leaves, whether or not it becomes a hold. */
    val pushToTalkArmed by DictateHoldTouch.pressed.collectAsState()
    // The throw outlives the hold: the phase is already back to NONE by the time the mic starts moving.
    // A lock on the key for a moment after latching, dissolving into whatever it shows next. Appears
    // instantly and only the fade is animated, so the ordinary icon is never drawn first.
    // Read straight from the state while it is on, so the lock is already drawn in the very frame the
    // key becomes visible again. Even a snap() animation needs a frame to apply, and that frame was the
    // send icon everyone kept seeing.
    val lockFade = remember { Animatable(0f) }
    LaunchedEffect(ptt.lockFlash) {
        if (ptt.lockFlash) lockFade.snapTo(1f) else lockFade.animateTo(0f, tween(400))
    }
    val lockFlash = when {
        !isDictateKey -> 0f
        ptt.lockFlash -> 1f
        else -> lockFade.value
    }
    val flying = isDictateKey && ptt.discarding
    // Grows out of the key's own centre and then stays put. No bounce: the overshoot read as the
    // button being pressed twice.
    val appearAnim = remember { Animatable(0f) }
    LaunchedEffect(holdingMic || flying) {
        if (holdingMic || flying) {
            appearAnim.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
        } else {
            appearAnim.snapTo(0f)
        }
    }
    val appear = appearAnim.value

    val gestureActive = holdingMic || flying
    // On screen from the moment the finger lands, still invisible, purely so the window exists before it
    // is needed: adding one takes several frames, and those were the frames where the key had already
    // turned into a recording button with nothing growing out of it yet.
    val bubbleArmed = pushToTalkArmed || gestureActive
    // Frozen for the whole gesture, captured during composition rather than in an effect so it is right
    // from the first frame. The key genuinely moves once recording starts — the Smartbar swaps its
    // action row for the recording bar — and following that live dragged the overlay with it.
    val bounds = remember(bubbleArmed) { if (bubbleArmed) micKeyBounds else null }
    // Hidden for the whole gesture. The overlay starts at exactly this key's size and position, so the
    // hand-off is invisible; waiting for it to finish growing instead left both on screen side by side,
    // which is what made two circles appear at the start of a hold.
    val micHiddenByBubble = bounds != null && gestureActive
    if (bubbleArmed && bounds != null) HeldMicBubble(bounds, flying, appear, visible = gestureActive)
    PlainTooltip(
        action.computeTooltip(evaluator),
        // A button with a second action has no tooltip: the tooltip owns the same hold, appears on
        // the system's own (longer) timeout and would pop up after the action it describes has
        // already run. The mic has answered this question the same way since #228.
        enabled = type == QuickActionBarType.INTERACTIVE_BUTTON && !dictateLongPressArmed &&
            secondAction == null,
    ) {
        SnyggBox(
            elementName = elementName,
            attributes = attributes,
            selector = selector,
            // Never transformed. Compose maps pointer positions through a layer transform, so scaling
            // this node moved the reported finger position by the very amount that reads as a
            // slide-to-cancel — measured at progress 1.009 every single time. The held-state swell is a
            // separate popup instead (see HeldMicBubble), which cannot touch the gesture.
            // Hidden while the overlay stands in for it — seeing the small key peek out from under the
            // big one gives away that they are two different things. Alpha only: a transform here would
            // move the reported finger position and be read as a slide.
            modifier = modifier.alpha(if (micHiddenByBubble) 0f else 1f).onGloballyPositioned {
                if (action.keyData().code == KeyCode.IME_UI_MODE_DICTATE) {
                    micKeyBounds = it.boundsInWindow().roundToIntRect()
                }
            },
            clickAndSemanticsModifier = Modifier
                .aspectRatio(1f)
                .indication(interactionSource, LocalIndication.current)
                // secondAction belongs in the keys: without it a pairing changed in settings would
                // only take hold once the keyboard is rebuilt, which reads as the feature not working.
                .pointerInput(action, isEnabled, secondAction) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        if (isEnabled && type != QuickActionBarType.EDITOR_TILE) {
                            val press = PressInteraction.Press(down.position)
                            inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                            interactionSource.tryEmit(press)
                            // The hold is timed by the dispatcher's own coroutine, not by this
                            // gesture — see DictateHoldTouch for why nothing here may outlive a press.
                            action.onPointerDown(context) onLongPress@{
                                val second = secondAction ?: return@onLongPress false
                                inputFeedbackController.keyLongPress(TextKeyData.UNSPECIFIED)
                                second.performAsSecondAction(context)
                                true
                            }

                            // The Dictate mic supports two long-press shortcuts:
                            //  • idle: hold to pick an existing audio/video file to transcribe (#88).
                            //  • recording (opt-in, #228): hold the send button to transcribe this
                            //    recording with the on-device model instead of the cloud provider.
                            val isDictate = action.keyData().code == KeyCode.IME_UI_MODE_DICTATE
                            // Not "state is Idle": the interrupted-recording chip is also a state a hold
                            // has to work from, and a tap there already starts a recording.
                            val dictateIdle = isDictate && DictateController.canStartRecording()
                            // Holding runs the on-device model on this one dictation: while recording it
                            // sends there instead of to the cloud (#228), and while a cloud request is
                            // still running it takes the recording back from it (#270). Same preference,
                            // same button, one entry point that decides which of the two applies.
                            val dictateSendLocal = isDictate && !dictateIdle &&
                                prefs.dictate.longPressSendLocalModel.get() &&
                                DictateController.canLongPressLocal()
                            // The whole mic press is decided from the window's own touch stream, not from
                            // this coroutine: Compose ends it of its own accord part way into a press,
                            // sometimes by cancelling it outright, while the window goes on receiving the
                            // finger until the genuine release. For a hold that lost the recording (#235);
                            // for a plain tap it withdraws the key press instead of completing it, which is
                            // why the mic needed a second tap to start and a second one to stop (#261).
                            // Measured: sendDown, then waitForUpOrCancellation returning null 15 ms later
                            // with the finger still down, and no dispose in between.
                            val armed = when {
                                // Push-to-talk (#235) takes the whole gesture: hold to record, slide left
                                // to discard, slide up to latch. Opt-in, and it replaces the shortcuts.
                                dictateIdle && DictateController.isPushToTalkActive(context) -> {
                                    DictateHoldTouch.arm(
                                        context = context,
                                        action = action,
                                        feedback = inputFeedbackController,
                                        holdDelayMs = PUSH_TO_TALK_HOLD_MS,
                                        minSendMs = PUSH_TO_TALK_MIN_SEND_MS,
                                        cancelSlidePx = PUSH_TO_TALK_CANCEL_SLIDE.toPx(),
                                        lockSlidePx = PUSH_TO_TALK_LOCK_SLIDE.toPx(),
                                        commitPx = PUSH_TO_TALK_AXIS_COMMIT.toPx(),
                                        releasePx = PUSH_TO_TALK_AXIS_RELEASE.toPx(),
                                        onEnd = { interactionSource.tryEmit(PressInteraction.Release(press)) },
                                    )
                                }
                                // Otherwise a tap, with at most a long-press shortcut on it:
                                //  • idle: hold to pick an existing audio/video file to transcribe (#88).
                                //  • recording (opt-in, #228): hold the send button to transcribe with the
                                //    on-device model instead of the cloud provider.
                                isDictate -> DictateHoldTouch.armTap(
                                    context = context,
                                    action = action,
                                    feedback = inputFeedbackController,
                                    holdDelayMs = prefs.keyboard.longPressDelay.get().toLong(),
                                    onHold = when {
                                        dictateIdle -> { { DictateController.startFileTranscription(context) } }
                                        dictateSendLocal -> { { DictateController.holdForLocalModel(context) } }
                                        else -> null
                                    },
                                    onEnd = { interactionSource.tryEmit(PressInteraction.Release(press)) },
                                )
                                else -> false
                            }
                            if (!armed) {
                                // Either an ordinary action, or a mic press the window never saw land —
                                // handing that one over would leave the key held with nobody to release it.
                                handleUpOrCancel(
                                    waitForUpOrRealCancellation(), press, interactionSource, action, context,
                                )
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // Asked before anything is drawn: a pairing whose child has no icon shows no badge, and
            // then nothing needs to make room for one either.
            val badge = secondAction
                ?.let { evaluator.computeImageVector(it.keyData()) }
                ?.let { rememberSecondActionBadge(it, elementName, attributes, selector) }
            // The action's own content, in a node of its own so the badge's gap can be cleared out of
            // it. It takes the key's whole content area — the key is sized by its own `aspectRatio`,
            // never by this content — which is also the area the badge is aligned in, so the two agree
            // on where the corner is without either having to measure the other.
            Box(
                modifier = badge?.let { Modifier.fillMaxSize().secondActionGap(it) } ?: Modifier,
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Render foreground
                    when (action) {
                        is QuickAction.InsertKey -> {
                            // Uses the hoisted [dictateState] above (dynamic mic → send → hourglass icon).
                            // Select-all is a toggle (issue #152): reflect the field's selection live so the
                            // icon shows "deselect" when text is selected. distinctUntilChanged keeps this
                            // cheap for every action button — it only recomposes when selection presence flips.
                            val editorInstance by context.editorInstance()
                            val hasSelection by remember(editorInstance) {
                                editorInstance.activeContentFlow
                                    .map { it.selection.isSelectionMode }
                                    .distinctUntilChanged()
                            }.collectAsState(initial = editorInstance.activeContent.selection.isSelectionMode)
                            val (imageVector, label) = remember(action, evaluator, dictateState, hasSelection) {
                                val icon = if (action.data.code == KeyCode.CLIPBOARD_SELECT_ALL && hasSelection) {
                                    Icons.Default.Deselect
                                } else {
                                    evaluator.computeImageVector(action.data)
                                }
                                icon to evaluator.computeLabel(action.data)
                            }
                            if (imageVector != null) {
                                SnyggBox(
                                    elementName = "$elementName-icon",
                                    attributes = attributes,
                                    selector = selector,
                                ) {
                                    // Latching happens under the finger, on a target below the key, and then
                                    // the gesture simply ends. Showing the lock on the key itself for a beat
                                    // before it dissolves into the stop icon is the confirmation that the
                                    // recording is now running on its own.
                                    if (lockFlash > 0.01f) {
                                        // SnyggIcon, so it is exactly the size of the icon it replaces —
                                        // a fixed dp value made it noticeably larger than the mic.
                                        SnyggIcon(
                                            imageVector = Icons.Default.Lock,
                                            modifier = Modifier.alpha(lockFlash),
                                        )
                                    }
                                    // The Material "GIF" glyph draws small lettering inside a lot of padding;
                                    // scale it up so the "GIF" text is legible at the Smartbar icon size.
                                    val iconModifier = if (action.data.code == KeyCode.IME_UI_MODE_GIF) {
                                        Modifier.scale(1.45f)
                                    } else {
                                        Modifier
                                    }
                                    SnyggIcon(
                                        imageVector = imageVector,
                                        // Fades in underneath the lock as it fades out, so the two read as
                                        // one icon turning into the other.
                                        modifier = iconModifier.alpha(1f - lockFlash),
                                    )
                                }
                            } else if (label != null) {
                                SnyggText(
                                    elementName = "$elementName-text",
                                    attributes = attributes,
                                    selector = selector,
                                    text = label,
                                )
                            }
                        }

                        is QuickAction.InsertText -> {
                            SnyggText(
                                elementName = "$elementName-text",
                                attributes = attributes,
                                selector = selector,
                                text = action.data.firstOrNull().toString().ifBlank { "?" },
                            )
                        }
                    }

                    // Render additional info if this is a tile
                    if (type != QuickActionBarType.INTERACTIVE_BUTTON) {
                        SnyggText(
                            elementName = "$elementName-text",
                            attributes = attributes,
                            selector = selector,
                            text = action.computeDisplayName(evaluator = evaluator),
                        )
                    }
                }
            }
            // What holding this button runs, small in the corner — the same place and the same element
            // the typing keyboard puts a key's hint glyph, so it is themed everywhere already and needs
            // no new Snygg element (which a bundled stylesheet could not name anyway: BundledThemesTest
            // allows only an element and its "-icon"/"-text" children). The hint rule is borrowed for
            // its *colour*; its size is meant for one character and says nothing about the icon this
            // badge has to stay smaller than, so the size is given here instead — an explicit size on
            // a SnyggIcon wins over the themed one.
            //
            // A sibling of the node above, never a child of it: [secondActionGap] clears its disc after
            // everything in that node has been drawn, so a badge inside it was wiped out together with
            // the overlap it was supposed to survive — the pairing simply stopped being visible.
            if (badge != null) {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(badge.inset)) {
                    SnyggIcon(
                        elementName = FlorisImeUi.KeyHint.elementName,
                        modifier = Modifier.size(badge.size),
                        imageVector = badge.icon,
                    )
                }
            }
        }
    }
}

/**
 * Waits for the finger to leave the key, and reports a finished tap even when the release arrives
 * already consumed.
 *
 * Compose's own `waitForUpOrCancellation` cannot, because `changedToUp()` is defined as
 * `!isConsumed && previousPressed && !pressed`: a consumed release is not recognised as a release at
 * all, so a completed tap comes back as a cancellation. That withdraws the key press instead of
 * finishing it, and then costs the *next* tap as well — the dispatcher still believes the key to be
 * held and drops the following down (#257, #261).
 *
 * The release genuinely does arrive consumed here, and the consumer is [PlainTooltip]. It wraps this
 * button, listens on [PointerEventPass.Initial] — parent before child — and while its tooltip is on
 * screen it consumes every change of the release event, so that dismissing a tooltip does not also
 * press the button underneath it. The tooltip lingers after a press, so the tap that follows one
 * lands inside that window and loses its release; the tooltip then hides and the tap after that
 * works. That is the cleanly alternating dead tap #257 measured, and why it was never reproducible
 * on an emulator, where a mouse drives the tooltip through hover instead. Read out of tooltip
 * 0.2.0-rc02's bytecode on 2026-09-14 — its single `consume()` sits in the Release branch behind an
 * `isTooltipShowing` guard. Nothing in this app consumes the release.
 *
 * So a consumed change is a cancellation only while the finger is still **down** — that is the real
 * "another handler took over the drag" case, and the only one worth abandoning a press for. A
 * consumed *release* is a finished tap.
 *
 * Sliding off a key still aborts it, which is an expectation people bring to every button, but with
 * one touch slop of margin so a thumb rolling a few pixels does not cost the press. That check runs
 * only while pressed too, so it can never turn a release near the edge back into a cancellation —
 * the very failure this function exists to remove.
 *
 * The mic does not come through here on its normal path (it is driven from the window's own touch
 * stream, see [DictateHoldTouch]) — but its fallback does, for a press the window never saw land.
 */
private suspend fun AwaitPointerEventScope.waitForUpOrRealCancellation(): PointerInputChange? {
    val slop = viewConfiguration.touchSlop
    val bounds = Size(extendedTouchPadding.width + slop, extendedTouchPadding.height + slop)
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.all { it.changedToUpIgnoreConsumed() }) {
            return event.changes[0]
        }
        if (event.changes.any { it.pressed && (it.isConsumed || it.isOutOfBounds(size, bounds)) }) {
            return null
        }
        // The Final pass of this same event, so a handler further out that takes the drag after us is
        // still seen. Again only while the finger is down: this is Compose's own check, narrowed.
        val afterOthers = awaitPointerEvent(PointerEventPass.Final)
        if (afterOthers.changes.any { it.pressed && it.isConsumed }) {
            return null
        }
    }
}

/** Finishes a pointer gesture: a non-null [up] is a normal release (click), null is a cancellation. */
private fun handleUpOrCancel(
    up: PointerInputChange?,
    press: PressInteraction.Press,
    interactionSource: MutableInteractionSource,
    action: QuickAction,
    context: Context,
) {
    if (up != null) {
        up.consume()
        interactionSource.tryEmit(PressInteraction.Release(press))
        action.onPointerUp(context)
    } else {
        interactionSource.tryEmit(PressInteraction.Cancel(press))
        action.onPointerCancel(context)
    }
}
