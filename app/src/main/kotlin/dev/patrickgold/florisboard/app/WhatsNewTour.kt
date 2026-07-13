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
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.Segment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.navigation.compose.currentBackStackEntryAsState
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.util.AppVersionUtils
import dev.patrickgold.florisboard.lib.util.VersionName
import dev.patrickgold.florisboard.lib.util.launchUrl
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** PayPal donation link, kept in sync with the changelog dialog's donate invite. */
private const val DONATE_URL = "https://paypal.me/DevEmperor"

/**
 * One versioned "What's new" tour. The app keeps an ordered registry ([WHATS_NEW_TOURS]) so that a user
 * who skips several releases (e.g. 4.x → 5.1) is shown every tour they missed, in order, while a user who
 * already saw an earlier one only gets the newer ones. Each tour auto-shows at most once (tracked by the
 * `versionLastWhatsNew` high-water mark) and stays re-openable from Settings › About forever.
 */
internal data class WhatsNewTourDef(
    val version: VersionName,
    val pages: List<WhatsNewPage>,
)

/**
 * Lets any screen (e.g. Settings › About) re-open a specific tour after it was first dismissed. The tour
 * composable observes this; About sets the version of the tour to show (read-only, doesn't touch the
 * seen-state high-water mark).
 */
object WhatsNewTourState {
    val manualTour = mutableStateOf<VersionName?>(null)
    fun open(version: VersionName) { manualTour.value = version }
}

internal enum class PageKind { INTRO, FEATURE, OUTRO }

internal data class WhatsNewPage(
    val icon: ImageVector,
    @StringRes val eyebrow: Int,
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val cta: Int,
    val route: Any?,
    val highlight: Boolean = false,
    val kind: PageKind = PageKind.FEATURE,
)

private val WhatsNewPages50: List<WhatsNewPage> = listOf(
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

/** The 5.1 tour: the release's headline features, with the smaller changes folded into one closing page. */
private val WhatsNewPages51: List<WhatsNewPage> = listOf(
    WhatsNewPage(
        icon = Icons.Filled.AutoAwesome,
        eyebrow = R.string.apptour51__intro_eyebrow,
        title = R.string.apptour51__intro_title,
        body = R.string.apptour51__intro_body,
        cta = R.string.apptour__start,
        route = null,
        kind = PageKind.INTRO,
    ),
    WhatsNewPage(
        icon = Icons.Filled.History,
        eyebrow = R.string.apptour51__history_eyebrow,
        title = R.string.apptour51__history_title,
        body = R.string.apptour51__history_body,
        cta = R.string.apptour51__cta_try,
        route = Routes.Settings.DictateHistory,
        highlight = true,
    ),
    WhatsNewPage(
        icon = Icons.AutoMirrored.Filled.Segment,
        eyebrow = R.string.apptour51__longform_eyebrow,
        title = R.string.apptour51__longform_title,
        body = R.string.apptour51__longform_body,
        cta = R.string.apptour51__cta_try,
        route = Routes.Settings.DictateRecording,
        highlight = true,
    ),
    WhatsNewPage(
        icon = Icons.Outlined.Gif,
        eyebrow = R.string.apptour51__gif_eyebrow,
        title = R.string.apptour51__gif_title,
        body = R.string.apptour51__gif_body,
        cta = R.string.apptour__next,
        route = null,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Dialpad,
        eyebrow = R.string.apptour51__classic_eyebrow,
        title = R.string.apptour51__classic_title,
        body = R.string.apptour51__classic_body,
        cta = R.string.apptour51__cta_try,
        route = Routes.Settings.DictateLayout,
        highlight = true,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Search,
        eyebrow = R.string.apptour51__search_eyebrow,
        title = R.string.apptour51__search_title,
        body = R.string.apptour51__search_body,
        cta = R.string.apptour51__cta_try,
        route = Routes.Settings.Search,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Language,
        eyebrow = R.string.apptour51__models_eyebrow,
        title = R.string.apptour51__models_title,
        body = R.string.apptour51__models_body,
        cta = R.string.apptour51__cta_try,
        route = Routes.Settings.DictateProviders,
        highlight = true,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Tune,
        eyebrow = R.string.apptour51__more_eyebrow,
        title = R.string.apptour51__more_title,
        body = R.string.apptour51__more_body,
        cta = R.string.apptour__next,
        route = null,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Celebration,
        eyebrow = R.string.apptour51__outro_eyebrow,
        title = R.string.apptour51__outro_title,
        body = R.string.apptour51__outro_body,
        cta = R.string.apptour__done,
        route = null,
        kind = PageKind.OUTRO,
    ),
)

/**
 * The ordered registry of all "What's new" tours (ascending by version). The auto-show logic queues every
 * tour a user hasn't seen yet; Settings › About lists them all for re-viewing. Append the next release's
 * tour here.
 */
internal val WHATS_NEW_TOURS: List<WhatsNewTourDef> = listOf(
    WhatsNewTourDef(VersionName(5, 0, 0), WhatsNewPages50),
    WhatsNewTourDef(VersionName(5, 1, 0), WhatsNewPages51),
)

/**
 * A full-screen, swipeable "What's new" tour. Auto-shows every tour a user hasn't seen yet (see
 * [WHATS_NEW_TOURS]), one after another — so a 4.x → 5.1 jumper gets 5.0 then 5.1, while a 5.0 → 5.1
 * updater only gets 5.1 — and stays re-openable per version from Settings › About via [WhatsNewTourState].
 * Each feature page has an "Ausprobieren" button that deep-links into the relevant settings screen,
 * turning the announcement into immediate use. All accents derive from the app's theme accent.
 *
 * @param autoQueue the ascending list of unseen tour versions to auto-show now, computed once by the
 *   caller (via [AppVersionUtils.pendingTourVersions]) so it can suppress the regular [ChangelogDialog].
 */
@Composable
fun WhatsNewTour(autoQueue: List<VersionName>) {
    val context = LocalContext.current
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current

    // Resolve the unseen queue (auto mode) and any manually re-opened tour (Settings › About).
    val autoTours = remember(autoQueue) {
        autoQueue.mapNotNull { v -> WHATS_NEW_TOURS.firstOrNull { it.version == v } }
    }
    val manualVersion by WhatsNewTourState.manualTour
    val manualTour = manualVersion?.let { v -> WHATS_NEW_TOURS.firstOrNull { it.version == v } }

    // Auto mode walks the queue by index; manual mode shows one re-opened tour without touching seen-state.
    var queueIndex by rememberSaveable { mutableIntStateOf(0) }
    val isManual = manualTour != null
    val activeTour = when {
        manualTour != null -> manualTour
        autoTours.isNotEmpty() && queueIndex <= autoTours.lastIndex -> autoTours[queueIndex]
        else -> null
    } ?: return
    val pages = activeTour.pages
    // Another unseen tour after this one → an "Weiter zu X" bridge on the outro instead of "Fertig".
    val nextAutoTour = if (!isManual && queueIndex < autoTours.lastIndex) autoTours[queueIndex + 1] else null

    fun closeManual() { WhatsNewTourState.manualTour.value = null }

    // Finish the current tour: auto mode remembers progress per-tour and moves to the next queued tour or
    // ends (marking everything seen + suppressing the changelog); manual mode just closes.
    fun finishTour() {
        if (isManual) { closeManual(); return }
        if (queueIndex < autoTours.lastIndex) {
            scope.launch { AppVersionUtils.markWhatsNewSeen(context, prefs, activeTour.version) }
            queueIndex += 1
        } else {
            scope.launch {
                AppVersionUtils.updateVersionLastWhatsNew(context, prefs)
                AppVersionUtils.updateVersionLastChangelog(context, prefs)
            }
            queueIndex = autoTours.size // past the end → nothing auto-shows
        }
    }

    // Skip the whole thing: auto mode marks all tours seen so none nag again; manual mode just closes.
    fun skip() {
        if (isManual) { closeManual(); return }
        scope.launch {
            AppVersionUtils.updateVersionLastWhatsNew(context, prefs)
            AppVersionUtils.updateVersionLastChangelog(context, prefs)
        }
        queueIndex = autoTours.size
    }

    // Fresh pager per tour, so advancing from 5.0 to 5.1 starts back on the intro page.
    val pagerState = key(activeTour.version, isManual) { rememberPagerState(pageCount = { pages.size }) }

    // "Try it" navigation hides the tour without marking it seen, then restores it (on the same page)
    // once the user navigates back — so new users can explore a feature and still finish the tour by
    // pressing back, instead of losing it after the first tap (issue #178). We remember the back stack
    // entry the tour was launched over and re-show the tour once that same entry is on top again.
    var exploring by rememberSaveable { mutableStateOf(false) }
    var originEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    val currentEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentEntry?.id, exploring) {
        if (exploring && originEntryId != null && currentEntry?.id == originEntryId) {
            exploring = false
            originEntryId = null
        }
    }
    // While exploring a feature, keep this composable alive (so the observer above still runs) but
    // don't render the dialog over the settings screen the user went to try.
    if (exploring) return

    Dialog(
        onDismissRequest = { skip() },
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
                            text = activeTour.version.toString().substringBeforeLast(".0"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { skip() }) {
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

                // Primary action: deep-link into the feature, advance, or finish (→ next tour or close).
                val page = pages[pagerState.currentPage]
                val isLast = pagerState.currentPage == pages.lastIndex
                // On the outro, if another tour is queued, the button bridges over to it ("Weiter zu 5.1").
                val buttonText = if (isLast && nextAutoTour != null) {
                    stringRes(R.string.apptour51__continue_to)
                        .replace("{version}", nextAutoTour.version.toString().substringBeforeLast(".0"))
                } else {
                    stringRes(page.cta)
                }
                Button(
                    onClick = {
                        when {
                            page.route != null -> {
                                // Hide (don't dismiss) the tour and remember where to come back to, so
                                // pressing back returns to this very page instead of losing the tour.
                                originEntryId = navController.currentBackStackEntry?.id
                                exploring = true
                                navController.navigate(page.route)
                            }
                            isLast -> finishTour()
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
                    Text(text = buttonText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                // Secondary "continue" only for feature pages that deep-link somewhere (so trying is
                // optional); a no-route info page's primary button already advances, so no duplicate.
                val showContinue = page.kind == PageKind.FEATURE && !isLast && page.route != null
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
        // A gentle donation invite on the closing page — for users who enjoyed the app and the update.
        if (page.kind == PageKind.OUTRO) {
            DonateInvite()
        }
    }
}

@Composable
private fun DonateInvite() {
    val context = LocalContext.current
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = stringRes(R.string.changelog__donate),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .clickable { context.launchUrl(DONATE_URL) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
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
