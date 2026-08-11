/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package org.florisboard.lib.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One page of the wizard.
 *
 * [icon] and [art] belong to the step but are drawn by the *layout*, above the title. That is the
 * whole point of them being here: an illustration supplied through [content] can only ever appear
 * after the heading and any header text, which puts it in the middle of the page's prose — where it
 * reads as something that lost its place rather than as the page's opening image.
 *
 * [art] wins over [icon] when both are given, for steps that want something livelier than a glyph.
 */
data class FlorisStep(
    val id: Int,
    val title: String,
    val icon: ImageVector? = null,
    val art: (@Composable () -> Unit)? = null,
    val content: @Composable FlorisStepLayoutScope.() -> Unit,
)

class FlorisStepLayoutScope(
    columnScope: ColumnScope,
    private val primaryColor: Color,
    private val scrollState: ScrollState? = null,
) : ColumnScope by columnScope {

    /**
     * Sends the page back to the top whenever [key] changes.
     *
     * A step that swaps its content in place — a fork the user has just answered, a branch that
     * opens — is still the same step to the layout, so it keeps the scroll position it had and the
     * new content arrives already scrolled past its own heading. The scroll state belongs to the
     * layout, which is why a step cannot reach it without this.
     */
    @Composable
    fun ScrollToTopOn(key: Any?) {
        val state = scrollState ?: return
        LaunchedEffect(key) { state.scrollTo(0) }
    }

    /**
     * Body text of a step.
     *
     * Centred, and deliberately not justified. Justification on a phone-width column stretches word
     * spacing into the rivers of white that were visible on every step — it needs a measure this
     * layout will never have.
     */
    @Composable
    fun StepText(
        text: String,
        modifier: Modifier = Modifier,
        fontStyle: FontStyle = FontStyle.Normal,
    ) {
        Text(
            modifier = modifier,
            text = text,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = fontStyle,
        )
    }

    @Composable
    fun StepButton(
        label: String,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        Button(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = primaryColor,
            ),
            onClick = onClick,
        ) {
            Text(text = label)
        }
    }
}

@Suppress("unused")
class FlorisStepState private constructor(
    private val currentAuto: MutableState<Int>,
    private val currentManual: MutableState<Int> = mutableIntStateOf(-1),
) {
    companion object {
        fun new(init: Int) = FlorisStepState(mutableIntStateOf(init))

        val Saver = Saver<FlorisStepState, ArrayList<Int>>(
            save = {
                arrayListOf(it.currentAuto.value, it.currentManual.value)
            },
            restore = {
                FlorisStepState(mutableIntStateOf(it[0]), mutableIntStateOf(it[1]))
            },
        )
    }

    fun getCurrent(): State<Int> {
        return if (currentManual.value >= 0 && currentAuto.value >= currentManual.value) {
            currentManual
        } else {
            currentAuto
        }
    }

    fun getCurrentAuto(): State<Int> = currentAuto

    fun getCurrentManual(): State<Int> = currentManual

    fun setCurrentAuto(value: Int) {
        currentAuto.value = value
    }

    fun setCurrentManual(value: Int) {
        if (currentAuto.value == value) {
            currentManual.value = -1
        } else {
            currentManual.value = value
        }
    }
}

/**
 * A paged setup wizard: each step gets a full page of its own instead of being crammed into a shared
 * accordion. A segmented progress bar at the top shows where the user is, the current step slides in
 * horizontally, and a bottom bar lets the user move back (and forward through already-reached steps).
 * Forward progress is still driven automatically by [FlorisStepState.setCurrentAuto] as prerequisites
 * are fulfilled, so the wizard advances on its own once a permission is granted etc.
 *
 * [header] is shown once, above the first step's content (e.g. an intro line); [footer] stays pinned at
 * the bottom on every page (e.g. privacy/repository links that must remain reachable during setup).
 */
@Composable
fun FlorisStepLayout(
    stepState: FlorisStepState,
    steps: List<FlorisStep>,
    backLabel: String,
    nextLabel: String,
    modifier: Modifier = Modifier,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    header: @Composable FlorisStepLayoutScope.() -> Unit = { },
    footer: @Composable FlorisStepLayoutScope.() -> Unit = { },
) {
    val currentStepId by stepState.getCurrent()
    val autoStepId by stepState.getCurrentAuto()

    fun indexOfId(id: Int): Int = steps.indexOfFirst { it.id == id }.coerceAtLeast(0)
    val currentIndex = indexOfId(currentStepId)
    val autoIndex = indexOfId(autoStepId)
    val canGoBack = currentIndex > 0
    val canGoForward = currentIndex < autoIndex

    Column(modifier = modifier.fillMaxSize()) {
        // Progress: one segment per step, filled up to and including the current one.
        StepProgressBar(
            stepCount = steps.size,
            currentIndex = currentIndex,
            primaryColor = primaryColor,
        )
        val animSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow)
        AnimatedContent(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            targetState = currentStepId,
            transitionSpec = {
                val forward = indexOfId(targetState) >= indexOfId(initialState)
                val dir = if (forward) 1 else -1
                val slideSpec = spring(
                    stiffness = Spring.StiffnessMediumLow,
                    visibilityThreshold = IntOffset.VisibilityThreshold,
                )
                (slideInHorizontally(slideSpec) { w -> dir * w } + fadeIn(animSpec)) togetherWith
                    (slideOutHorizontally(slideSpec) { w -> -dir * w } + fadeOut(animSpec))
            },
            label = "setup-step",
        ) { stepId ->
            val step = steps.firstOrNull { it.id == stepId } ?: return@AnimatedContent
            val isFirst = indexOfId(stepId) == 0
            val index = indexOfId(stepId)
            // Hoisted rather than left inside the modifier, so a step can send its own page back to
            // the top when it replaces its content without the layout ever changing step.
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Outside the scrolling area on purpose. Inside it, the counter sat wherever the
                // page happened to start — centred on a short step, jammed against the progress bar
                // on a tall one — so it appeared to jump as you moved through the wizard. It still
                // slides with the page, because the whole block is what animates.
                //
                // Digits rather than a worded "step n of m": no translation needed and read the
                // same in every language this app ships in.
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "${index + 1} / ${steps.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.4.sp,
                )
                Spacer(modifier = Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .florisVerticalScroll(scrollState),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    // Centred while the page fits, top-anchored once it has to scroll. A short step
                    // otherwise clings to the top and leaves the lower half empty.
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (step.art != null) {
                        step.art.invoke()
                    } else if (step.icon != null) {
                        StepIconPlate(step.icon)
                    }
                    if (step.art != null || step.icon != null) {
                        Spacer(modifier = Modifier.height(20.dp))
                    }

                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Column(
                        // A measure the eye can follow. Centred text past roughly 45 characters a
                        // line stops being scannable and starts being a wall.
                        modifier = Modifier.widthIn(max = 360.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val scope = FlorisStepLayoutScope(this, primaryColor, scrollState)
                        if (isFirst) {
                            header(scope)
                        }
                        step.content(scope)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        // Bottom navigation: back to a previous step, or forward through already-reached ones. The
        // step's own primary button (and auto-advance) handles getting past the current requirement.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canGoBack) {
                TextButton(onClick = { stepState.setCurrentManual(steps[currentIndex - 1].id) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(backLabel)
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (canGoForward) {
                TextButton(onClick = { stepState.setCurrentManual(steps[currentIndex + 1].id) }) {
                    Text(nextLabel)
                    Spacer(modifier = Modifier.size(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        footer(FlorisStepLayoutScope(this, primaryColor))
    }
}

/**
 * The tinted plate a step opens with.
 *
 * Same shape and tint as the "What's new" tour's pages — the two are the same kind of screen, and
 * looking alike is the point.
 */
@Composable
private fun StepIconPlate(icon: ImageVector) {
    var shown by remember(icon) { mutableStateOf(false) }
    LaunchedEffect(icon) { shown = true }
    val appear by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "step-art",
    )
    Box(
        modifier = Modifier
            .graphicsLayer {
                alpha = appear
                translationY = (1f - appear) * 12f
                scaleX = 0.94f + 0.06f * appear
                scaleY = 0.94f + 0.06f * appear
            }
            .size(96.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(46.dp),
        )
    }
}

@Composable
private fun StepProgressBar(
    stepCount: Int,
    currentIndex: Int,
    primaryColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (i in 0 until stepCount) {
            val reached = i <= currentIndex
            val color by animateColorAsState(
                targetValue = if (reached) primaryColor else primaryColor.copy(alpha = 0.18f),
                label = "step-segment",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
