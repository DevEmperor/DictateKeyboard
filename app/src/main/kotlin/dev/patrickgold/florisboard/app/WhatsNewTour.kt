/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app

import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.util.AppVersionUtils
import dev.patrickgold.florisboard.lib.util.VersionName
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * The version whose "What's new" tour this file describes. Kept as a constant so the auto-show gate
 * ([AppVersionUtils.shouldShowWhatsNew]) fires exactly once when a user updates into 5.0 and never
 * replays on later minor updates (those fall back to the regular [ChangelogDialog]).
 */
val WHATS_NEW_TOUR_VERSION: VersionName = VersionName(5, 0, 0)

/**
 * Lets any screen (e.g. Settings › About) re-open the tour after it was first dismissed. The tour
 * composable observes this flag; About just flips it to true.
 */
object WhatsNewTourState {
    val manualOpen = mutableStateOf(false)
    fun open() { manualOpen.value = true }
}

private enum class PageKind { INTRO, FEATURE, OUTRO }

private data class WhatsNewPage(
    val icon: ImageVector,
    @StringRes val eyebrow: Int,
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val cta: Int,
    val route: Any?,
    val highlight: Boolean = false,
    val kind: PageKind = PageKind.FEATURE,
)

private val WhatsNewPages: List<WhatsNewPage> = listOf(
    WhatsNewPage(
        icon = Icons.Filled.AutoAwesome,
        eyebrow = R.string.apptour__intro_eyebrow,
        title = R.string.apptour__intro_title,
        body = R.string.apptour__intro_body,
        cta = R.string.apptour__start,
        route = null,
        kind = PageKind.INTRO,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Gesture,
        eyebrow = R.string.apptour__glide_eyebrow,
        title = R.string.apptour__glide_title,
        body = R.string.apptour__glide_body,
        cta = R.string.apptour__glide_cta,
        route = Routes.Settings.Gestures,
        highlight = true,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Spellcheck,
        eyebrow = R.string.apptour__suggestions_eyebrow,
        title = R.string.apptour__suggestions_title,
        body = R.string.apptour__suggestions_body,
        cta = R.string.apptour__suggestions_cta,
        route = Routes.Settings.Typing,
        highlight = true,
    ),
    WhatsNewPage(
        icon = Icons.Filled.GraphicEq,
        eyebrow = R.string.apptour__realtime_eyebrow,
        title = R.string.apptour__realtime_title,
        body = R.string.apptour__realtime_body,
        cta = R.string.apptour__realtime_cta,
        route = Routes.Settings.DictateRecording,
        highlight = true,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Cloud,
        eyebrow = R.string.apptour__providers_eyebrow,
        title = R.string.apptour__providers_title,
        body = R.string.apptour__providers_body,
        cta = R.string.apptour__providers_cta,
        route = Routes.Settings.DictateProviders,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Mic,
        eyebrow = R.string.apptour__legacy_eyebrow,
        title = R.string.apptour__legacy_title,
        body = R.string.apptour__legacy_body,
        cta = R.string.apptour__legacy_cta,
        route = Routes.Settings.DictateOutput,
        highlight = true,
    ),
    WhatsNewPage(
        icon = Icons.Filled.MenuBook,
        eyebrow = R.string.apptour__library_eyebrow,
        title = R.string.apptour__library_title,
        body = R.string.apptour__library_body,
        cta = R.string.apptour__library_cta,
        route = Routes.Settings.DictatePromptLibrary,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Insights,
        eyebrow = R.string.apptour__stats_eyebrow,
        title = R.string.apptour__stats_title,
        body = R.string.apptour__stats_body,
        cta = R.string.apptour__stats_cta,
        route = Routes.Settings.DictateStats,
    ),
    WhatsNewPage(
        icon = Icons.Filled.CloudDownload,
        eyebrow = R.string.apptour__offline_eyebrow,
        title = R.string.apptour__offline_title,
        body = R.string.apptour__offline_body,
        cta = R.string.apptour__offline_cta,
        route = Routes.Settings.DictateProviders,
        highlight = true,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Celebration,
        eyebrow = R.string.apptour__outro_eyebrow,
        title = R.string.apptour__outro_title,
        body = R.string.apptour__outro_body,
        cta = R.string.apptour__done,
        route = null,
        kind = PageKind.OUTRO,
    ),
)

/**
 * A full-screen, swipeable "What's new" tour shown once after updating into [WHATS_NEW_TOUR_VERSION]
 * (and re-openable from Settings › About via [WhatsNewTourState]). Each feature page has an
 * "Ausprobieren" button that deep-links into the relevant settings screen, turning the announcement
 * into immediate use. All accents (waveform header, icon, dots, buttons) derive from the app's theme
 * accent (`colorScheme.primary`).
 *
 * @param autoShow whether the version gate wants the tour on this launch — computed once by the caller
 *   so it can suppress the regular [ChangelogDialog] for the same update.
 */
@Composable
fun WhatsNewTour(autoShow: Boolean) {
    val context = LocalContext.current
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current

    var autoVisible by rememberSaveable { mutableStateOf(autoShow) }
    val manualVisible by WhatsNewTourState.manualOpen
    if (!autoVisible && !manualVisible) return

    fun dismiss() {
        // Mark the tour seen and also suppress the changelog dialog for this same version, so the two
        // "what changed" surfaces never both appear for one update.
        scope.launch {
            AppVersionUtils.updateVersionLastWhatsNew(context, prefs)
            AppVersionUtils.updateVersionLastChangelog(context, prefs)
        }
        autoVisible = false
        WhatsNewTourState.manualOpen.value = false
    }

    val pages = WhatsNewPages
    val pagerState = rememberPagerState(pageCount = { pages.size })

    Dialog(
        onDismissRequest = { dismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                // Header row: wordmark + version chip + skip.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringRes(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = WHATS_NEW_TOUR_VERSION.toString().substringBeforeLast(".0"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { dismiss() }) {
                        Text(text = stringRes(R.string.apptour__skip))
                    }
                }

                WaveformHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 6.dp)
                        .height(58.dp),
                )

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { index ->
                    PageContent(pages[index])
                }

                // Page indicator dots.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (i in pages.indices) {
                        val active = pagerState.currentPage == i
                        val width by animateFloatAsState(if (active) 20f else 7f, label = "dot")
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .height(7.dp)
                                .width(width.dp)
                                .clip(CircleShape)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                                ),
                        )
                    }
                }

                // Primary action: deep-link into the feature, advance, or finish.
                val page = pages[pagerState.currentPage]
                val isLast = pagerState.currentPage == pages.lastIndex
                Button(
                    onClick = {
                        when {
                            page.route != null -> { navController.navigate(page.route); dismiss() }
                            isLast -> dismiss()
                            else -> scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text(text = stringRes(page.cta), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                // Secondary "continue" for feature pages (so trying a feature is optional).
                val showContinue = page.kind == PageKind.FEATURE && !isLast
                TextButton(
                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
                    enabled = showContinue,
                ) {
                    Text(
                        text = if (showContinue) stringRes(R.string.apptour__next) else "",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun PageContent(page: WhatsNewPage) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (page.kind == PageKind.FEATURE) {
                Badge(
                    text = stringRes(R.string.dictate__floating_button_badge_new),
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                )
            }
            if (page.highlight) {
                Spacer(modifier = Modifier.width(6.dp))
                Badge(
                    text = stringRes(R.string.apptour__badge_highlight),
                    container = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    content = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = stringRes(page.eyebrow).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.5.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringRes(page.title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringRes(page.body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Badge(text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(container)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(text = text, color = content, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

/** An ambient recording-style equaliser, tinted by the theme accent, echoing Dictate's record UI. */
@Composable
private fun WaveformHeader(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "waveform")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(modifier = modifier) {
        val bars = 44
        val gap = size.width / bars
        val barWidth = max(2.5f, gap * 0.42f)
        val midY = size.height / 2f
        val twoPi = (2.0 * PI).toFloat()
        for (i in 0 until bars) {
            val envelope = 0.35f + 0.65f * sin(i / (bars - 1f) * PI.toFloat())
            val osc = (sin(phase * twoPi + i * 0.55f) * 0.5f + 0.5f)
            val h = max(3f, envelope * osc * size.height * 0.85f)
            val x = i * gap + gap / 2f
            drawRoundRect(
                color = accent.copy(alpha = 0.45f + 0.45f * envelope),
                topLeft = Offset(x - barWidth / 2f, midY - h / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
