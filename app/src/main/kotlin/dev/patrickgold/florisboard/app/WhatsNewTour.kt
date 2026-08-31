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
import androidx.compose.ui.viewinterop.AndroidView
import dev.patrickgold.florisboard.dictate.cloud.DictateCloudPack
import dev.patrickgold.florisboard.dictate.ui.AudioReactiveCloudOrbView
import dev.patrickgold.florisboard.dictate.ui.DictateAuroraOrbView
import dev.patrickgold.florisboard.dictate.ui.DictateWaveform
import dev.patrickgold.florisboard.dictate.ui.DictateLatticeSphereView
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Translate
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
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
import kotlin.math.abs
import kotlin.math.floor
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
    /** Render live artwork instead of a static icon; null shows the [icon]. */
    val art: TourArt? = null,
)

/**
 * The live previews a page can show in place of its icon — the real views, not a picture of them.
 *
 * Every one of these is drawn at runtime rather than shipped as an image, and the reason is colour
 * and language: they derive from the theme accent, so they follow whatever the user has set, and
 * the ones carrying text let the system font render it. A drawable would need a light and a dark
 * variant, and the three with writing in them would need twenty-one.
 */
internal enum class TourArt {
    /** The audio-reactive cloud orb (5.2). */
    CLOUD_ORB,

    /** Aurora and Lattice side by side, both idle, as the button actually sits there (5.3). */
    DESIGN_ORBS,

    /** A credit pack filling up, minutes counting on (6.0). */
    CREDIT_METER,

    /** One recording drawn twice, the second squeezed — what speeding up does to the bill (6.0). */
    WAVE_COMPRESS,

    /** Pinyin typing itself out and picking a candidate (6.0). */
    PINYIN_STRIP,

    /** A suggestion strip wandering through the newly supported scripts (6.0). */
    SCRIPT_CAROUSEL,

    /** The emoji search finding the same emoji in three languages (6.0). */
    EMOJI_SEARCH,

    /** A folder of pictures becoming tabs and tiles on the keyboard (6.1). */
    STICKER_GRID,

    /** A voice message going from the share sheet to readable text on its own (6.1). */
    SHARE_TO_TEXT,

    /** The strip marking the word space will take, and the word being swapped for it (6.1). */
    CORRECTION_PILL,
}

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


private val WhatsNewPages52: List<WhatsNewPage> = listOf(
    WhatsNewPage(
        icon = Icons.Filled.AutoAwesome,
        eyebrow = R.string.apptour52__intro_eyebrow,
        title = R.string.apptour52__intro_title,
        body = R.string.apptour52__intro_body,
        cta = R.string.apptour__start,
        route = null,
        kind = PageKind.INTRO,
    ),
    WhatsNewPage(
        icon = Icons.Filled.RecordVoiceOver,
        eyebrow = R.string.apptour52__voiceinput_eyebrow,
        title = R.string.apptour52__voiceinput_title,
        body = R.string.apptour52__voiceinput_body,
        cta = R.string.apptour52__cta_try,
        route = Routes.Settings.Dictate,
        highlight = true,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Bolt,
        eyebrow = R.string.apptour52__liveprompt_eyebrow,
        title = R.string.apptour52__liveprompt_title,
        body = R.string.apptour52__liveprompt_body,
        cta = R.string.apptour52__cta_try,
        route = Routes.Settings.DictateFloatingButton,
        highlight = true,
    ),
    WhatsNewPage(
        icon = Icons.Filled.PhoneAndroid,
        eyebrow = R.string.apptour52__ondevice_eyebrow,
        title = R.string.apptour52__ondevice_title,
        body = R.string.apptour52__ondevice_body,
        cta = R.string.apptour52__cta_try,
        route = Routes.Settings.DictateProviders,
    ),
    WhatsNewPage(
        icon = Icons.Filled.ContentCut,
        eyebrow = R.string.apptour52__trim_eyebrow,
        title = R.string.apptour52__trim_title,
        body = R.string.apptour52__trim_body,
        cta = R.string.apptour52__cta_try,
        route = Routes.Settings.DictateRecording,
    ),
    WhatsNewPage(
        icon = Icons.AutoMirrored.Filled.Segment,
        eyebrow = R.string.apptour52__paragraphs_eyebrow,
        title = R.string.apptour52__paragraphs_title,
        body = R.string.apptour52__paragraphs_body,
        cta = R.string.apptour52__cta_try,
        route = Routes.Settings.DictateOutput,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Psychology,
        eyebrow = R.string.apptour52__smartturn_eyebrow,
        title = R.string.apptour52__smartturn_title,
        body = R.string.apptour52__smartturn_body,
        cta = R.string.apptour52__cta_try,
        route = Routes.Settings.DictateRecording,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Cloud,
        eyebrow = R.string.apptour52__orb_eyebrow,
        title = R.string.apptour52__orb_title,
        body = R.string.apptour52__orb_body,
        cta = R.string.apptour52__cta_try,
        route = Routes.Settings.DictateFloatingButton,
        highlight = true,
        art = TourArt.CLOUD_ORB,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Celebration,
        eyebrow = R.string.apptour52__outro_eyebrow,
        title = R.string.apptour52__outro_title,
        body = R.string.apptour52__outro_body,
        cta = R.string.apptour__done,
        route = null,
        kind = PageKind.OUTRO,
    ),
)

private val WhatsNewPages53: List<WhatsNewPage> = listOf(
    WhatsNewPage(
        icon = Icons.Filled.AutoAwesome,
        eyebrow = R.string.apptour53__intro_eyebrow,
        title = R.string.apptour53__intro_title,
        body = R.string.apptour53__intro_body,
        cta = R.string.apptour__start,
        route = null,
        kind = PageKind.INTRO,
    ),
    WhatsNewPage(
        icon = Icons.Filled.TouchApp,
        eyebrow = R.string.apptour53__pushtotalk_eyebrow,
        title = R.string.apptour53__pushtotalk_title,
        body = R.string.apptour53__pushtotalk_body,
        cta = R.string.apptour53__cta_try,
        route = Routes.Settings.Dictate,
        highlight = true,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Spellcheck,
        eyebrow = R.string.apptour53__autocorrect_eyebrow,
        title = R.string.apptour53__autocorrect_title,
        body = R.string.apptour53__autocorrect_body,
        cta = R.string.apptour53__cta_try,
        route = Routes.Settings.Typing,
        highlight = true,
    ),
    WhatsNewPage(
        icon = Icons.Filled.CloudOff,
        eyebrow = R.string.apptour53__offline_eyebrow,
        title = R.string.apptour53__offline_title,
        body = R.string.apptour53__offline_body,
        cta = R.string.apptour53__cta_try,
        route = Routes.Settings.DictateProviders,
        highlight = true,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Translate,
        eyebrow = R.string.apptour53__languages_eyebrow,
        title = R.string.apptour53__languages_title,
        body = R.string.apptour53__languages_body,
        cta = R.string.apptour53__cta_try,
        route = Routes.Settings.DictateLanguages,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Brush,
        eyebrow = R.string.apptour53__designs_eyebrow,
        title = R.string.apptour53__designs_title,
        body = R.string.apptour53__designs_body,
        cta = R.string.apptour53__cta_try,
        route = Routes.Settings.DictateFloatingButton,
        highlight = true,
        art = TourArt.DESIGN_ORBS,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Lightbulb,
        eyebrow = R.string.apptour53__prediction_eyebrow,
        title = R.string.apptour53__prediction_title,
        body = R.string.apptour53__prediction_body,
        cta = R.string.apptour53__cta_try,
        route = Routes.Settings.Typing,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Dns,
        eyebrow = R.string.apptour53__ownserver_eyebrow,
        title = R.string.apptour53__ownserver_title,
        body = R.string.apptour53__ownserver_body,
        cta = R.string.apptour53__cta_try,
        route = Routes.Settings.DictateProviders,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Celebration,
        eyebrow = R.string.apptour53__outro_eyebrow,
        title = R.string.apptour53__outro_title,
        body = R.string.apptour53__outro_body,
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
/**
 * The 6.0 tour. Shorter than 5.0's ten pages on purpose: the release is wide, but only a handful of
 * the changes are things a user has to be *told* about rather than simply run into.
 */
private val WhatsNewPages60: List<WhatsNewPage> = listOf(
    WhatsNewPage(
        icon = Icons.Filled.AutoAwesome,
        eyebrow = R.string.apptour60__intro_eyebrow,
        title = R.string.apptour60__intro_title,
        body = R.string.apptour60__intro_body,
        cta = R.string.apptour__start,
        route = null,
        kind = PageKind.INTRO,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Cloud,
        eyebrow = R.string.apptour60__cloud_eyebrow,
        title = R.string.apptour60__cloud_title,
        body = R.string.apptour60__cloud_body,
        cta = R.string.apptour60__cta_try,
        route = Routes.Settings.DictateCloud,
        highlight = true,
        art = TourArt.CREDIT_METER,
    ),
    WhatsNewPage(
        icon = Icons.Filled.ContentCut,
        eyebrow = R.string.apptour60__speedup_eyebrow,
        title = R.string.apptour60__speedup_title,
        body = R.string.apptour60__speedup_body,
        cta = R.string.apptour60__cta_try,
        route = Routes.Settings.DictateRecording,
        highlight = true,
        art = TourArt.WAVE_COMPRESS,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Language,
        eyebrow = R.string.apptour60__chinese_eyebrow,
        title = R.string.apptour60__chinese_title,
        body = R.string.apptour60__chinese_body,
        cta = R.string.apptour60__cta_try,
        route = Routes.Settings.Localization,
        highlight = true,
        art = TourArt.PINYIN_STRIP,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Search,
        eyebrow = R.string.apptour60__emoji_eyebrow,
        title = R.string.apptour60__emoji_title,
        body = R.string.apptour60__emoji_body,
        cta = R.string.apptour60__cta_try,
        route = Routes.Settings.Localization,
        art = TourArt.EMOJI_SEARCH,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Spellcheck,
        eyebrow = R.string.apptour60__languages_eyebrow,
        title = R.string.apptour60__languages_title,
        body = R.string.apptour60__languages_body,
        cta = R.string.apptour60__cta_try,
        route = Routes.Settings.Localization,
        art = TourArt.SCRIPT_CAROUSEL,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Bolt,
        eyebrow = R.string.apptour60__more_eyebrow,
        title = R.string.apptour60__more_title,
        body = R.string.apptour60__more_body,
        cta = R.string.apptour__next,
        route = null,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Celebration,
        eyebrow = R.string.apptour60__outro_eyebrow,
        title = R.string.apptour60__outro_title,
        body = R.string.apptour60__outro_body,
        cta = R.string.apptour__done,
        route = null,
        kind = PageKind.OUTRO,
    ),
)

private val WhatsNewPages61: List<WhatsNewPage> = listOf(
    WhatsNewPage(
        icon = Icons.Filled.AutoAwesome,
        eyebrow = R.string.apptour61__intro_eyebrow,
        title = R.string.apptour61__intro_title,
        body = R.string.apptour61__intro_body,
        cta = R.string.apptour__start,
        route = null,
        kind = PageKind.INTRO,
    ),
    WhatsNewPage(
        icon = Icons.Filled.EmojiEmotions,
        eyebrow = R.string.apptour61__stickers_eyebrow,
        title = R.string.apptour61__stickers_title,
        body = R.string.apptour61__stickers_body,
        cta = R.string.apptour61__cta_try,
        route = Routes.Settings.Media,
        highlight = true,
        art = TourArt.STICKER_GRID,
    ),
    WhatsNewPage(
        icon = Icons.Filled.IosShare,
        eyebrow = R.string.apptour61__share_eyebrow,
        title = R.string.apptour61__share_title,
        body = R.string.apptour61__share_body,
        cta = R.string.apptour__next,
        // No route: the feature lives in every other app's share sheet, not on a settings screen,
        // and a button that opened the wrong place would teach the wrong gesture.
        route = null,
        highlight = true,
        art = TourArt.SHARE_TO_TEXT,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Spellcheck,
        eyebrow = R.string.apptour61__autocorrect_eyebrow,
        title = R.string.apptour61__autocorrect_title,
        body = R.string.apptour61__autocorrect_body,
        cta = R.string.apptour61__cta_try,
        route = Routes.Settings.Typing,
        highlight = true,
        art = TourArt.CORRECTION_PILL,
    ),
    WhatsNewPage(
        icon = Icons.Filled.ContactPage,
        eyebrow = R.string.apptour61__contacts_eyebrow,
        title = R.string.apptour61__contacts_title,
        body = R.string.apptour61__contacts_body,
        cta = R.string.apptour61__cta_try,
        route = Routes.Settings.Dictionary,
    ),
    WhatsNewPage(
        icon = Icons.Filled.RecordVoiceOver,
        eyebrow = R.string.apptour61__models_eyebrow,
        title = R.string.apptour61__models_title,
        body = R.string.apptour61__models_body,
        cta = R.string.apptour61__cta_try,
        route = Routes.Settings.DictateProviders,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Bolt,
        eyebrow = R.string.apptour61__more_eyebrow,
        title = R.string.apptour61__more_title,
        body = R.string.apptour61__more_body,
        cta = R.string.apptour__next,
        route = null,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Celebration,
        eyebrow = R.string.apptour61__outro_eyebrow,
        title = R.string.apptour61__outro_title,
        body = R.string.apptour61__outro_body,
        cta = R.string.apptour__done,
        route = null,
        kind = PageKind.OUTRO,
    ),
)

internal val WHATS_NEW_TOURS: List<WhatsNewTourDef> = listOf(
    WhatsNewTourDef(VersionName(5, 0, 0), WhatsNewPages50),
    WhatsNewTourDef(VersionName(5, 1, 0), WhatsNewPages51),
    WhatsNewTourDef(VersionName(5, 2, 0), WhatsNewPages52),
    WhatsNewTourDef(VersionName(5, 3, 0), WhatsNewPages53),
    WhatsNewTourDef(VersionName(6, 0, 0), WhatsNewPages60),
    WhatsNewTourDef(VersionName(6, 1, 0), WhatsNewPages61),
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

                DictateWaveform(
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

/**
 * A live preview of the cloud orb skin (issue #231 follow-up / 5.2 tour). Drives a slow synthetic level
 * so the orb visibly pulses the way it does while dictating, instead of sitting still.
 */
@Composable
private fun TourCloudOrb() {
    val transition = rememberInfiniteTransition(label = "orb-preview")
    val level by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "orb-level",
    )
    Box(modifier = Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                AudioReactiveCloudOrbView(ctx).apply {
                    setMode(AudioReactiveCloudOrbView.Mode.LISTENING)
                }
            },
            update = { it.setLevel(level) },
            modifier = Modifier.size(132.dp),
        )
    }
}

/**
 * Aurora and Lattice side by side, exactly as they sit on the floating button when nothing is happening:
 * the aurora drifting, the dot sphere wiring itself together. Both are the shipping views rather than a
 * screenshot, so what the tour shows is what the user gets — and both follow the accent colour.
 */
@Composable
private fun TourDesignOrbs() {
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AndroidView(
            factory = { ctx ->
                DictateAuroraOrbView(ctx).apply { setMood(DictateAuroraOrbView.Mood.IDLE, accent) }
            },
            update = { it.setMood(DictateAuroraOrbView.Mood.IDLE, accent) },
            modifier = Modifier.size(96.dp),
        )
        AndroidView(
            factory = { ctx ->
                DictateLatticeSphereView(ctx).apply {
                    setMode(DictateLatticeSphereView.Mode.WEB, accent)
                }
            },
            update = { it.setMode(DictateLatticeSphereView.Mode.WEB, accent) },
            modifier = Modifier.size(96.dp),
        )
    }
}

/**
 * A credit pack filling up: the minutes count on and the bar follows.
 *
 * The figure is [DictateCloudPack.PRO]'s own, read from the enum the shop reads, so the picture
 * cannot drift away from what is actually on sale. Deliberately no "x % cheaper" badge: that
 * number is worked out from Play's own prices at runtime and differs by country, so a fixed one
 * painted into an illustration would be a claim the tour cannot keep.
 */
@Composable
private fun TourCreditMeter() {
    val accent = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "credit")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "credit-cycle",
    )
    // Fills over the first third and then rests, so someone arriving mid-animation still sees it
    // happen rather than a bar that is simply already full.
    val fill = (cycle / 0.34f).coerceAtMost(1f)
    Surface(shape = RoundedCornerShape(20.dp), color = accent.copy(alpha = 0.12f)) {
        Column(modifier = Modifier.width(200.dp).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = (DictateCloudPack.PRO.minutes * fill).toInt().toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringRes(R.string.apptour60__art_minutes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_dictate_cloud),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
                drawRoundRect(
                    color = accent.copy(alpha = 0.22f),
                    cornerRadius = CornerRadius(size.height / 2f),
                )
                drawRoundRect(
                    color = accent,
                    size = Size(size.width * fill, size.height),
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            }
        }
    }
}

/**
 * The bar heights both waveforms are drawn from.
 *
 * One profile, used twice at two widths — that is the whole point of the picture. Two different
 * shapes would read as two different recordings, which is exactly the thing speeding up does not do.
 */
private val TOUR_WAVE: List<Float> = List(30) { i ->
    (0.30f + 0.70f * abs(sin(i * 1.9f)) * (0.55f + 0.45f * abs(sin(i * 0.7f + 1f))))
        .coerceIn(0.18f, 1f)
}

@Composable
private fun TourWaveBars(color: androidx.compose.ui.graphics.Color, fraction: Float) {
    Canvas(modifier = Modifier.fillMaxWidth().height(30.dp)) {
        val usable = size.width * fraction
        val gap = usable / TOUR_WAVE.size
        val barWidth = max(2f, gap * 0.5f)
        TOUR_WAVE.forEachIndexed { i, height ->
            val barHeight = size.height * height
            drawRoundRect(
                color = color,
                topLeft = Offset(i * gap + (gap - barWidth) / 2f, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

/** The same recording above and below, the lower one squeezing itself shorter. */
@Composable
private fun TourWaveCompress() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "compress")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3800, easing = LinearEasing), RepeatMode.Restart),
        label = "compress-cycle",
    )
    val squeeze = 1f - 0.34f * (cycle / 0.45f).coerceAtMost(1f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(148.dp)) {
                TourWaveBars(color = accent.copy(alpha = 0.30f), fraction = 1f)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text("3:00", style = MaterialTheme.typography.labelMedium, color = muted)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Surface(shape = RoundedCornerShape(percent = 50), color = accent.copy(alpha = 0.14f)) {
            Text(
                text = stringRes(R.string.apptour60__art_speed),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(148.dp)) {
                TourWaveBars(color = accent, fraction = squeeze)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "2:00",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
        }
    }
}

private const val TOUR_PINYIN = "nihao"
private val TOUR_CANDIDATES = listOf("你好", "尼豪", "泥")

/**
 * Pinyin typing itself out, the candidates arriving, the first one landing in the text.
 *
 * Real text in real composables rather than a drawing, because these glyphs have to come from the
 * system font — a picture of them would be a picture of *our* font at *our* size.
 */
@Composable
private fun TourPinyinStrip() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "pinyin")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5400, easing = LinearEasing), RepeatMode.Restart),
        label = "pinyin-cycle",
    )
    val typed = ((cycle / 0.40f).coerceAtMost(1f) * TOUR_PINYIN.length).toInt()
    val candidatesIn = cycle > 0.44f
    val picked = cycle > 0.66f

    Column(
        modifier = Modifier.width(216.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.height(38.dp), contentAlignment = Alignment.Center) {
            Text(
                text = if (picked) TOUR_CANDIDATES.first() else "",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(muted.copy(alpha = 0.25f)),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = TOUR_PINYIN.take(typed) + if (typed < TOUR_PINYIN.length) "▌" else "",
            style = MaterialTheme.typography.bodyLarge,
            color = accent,
            letterSpacing = 2.sp,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TOUR_CANDIDATES.forEachIndexed { index, candidate ->
                val chosen = picked && index == 0
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (chosen) accent else accent.copy(alpha = 0.12f),
                ) {
                    Text(
                        text = if (candidatesIn) candidate else " ",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (chosen) MaterialTheme.colorScheme.onPrimary else accent,
                    )
                }
            }
        }
    }
}

/**
 * The seven languages that gained a word list, shown as the suggestion strip they now fill.
 *
 * Written in their own scripts rather than named in the user's, because "Tamil" says nothing about
 * what changed and தமிழ் says all of it.
 */
private val TOUR_SCRIPTS = listOf("العربية", "বাংলা", "suomi", "हिन्दी", "Indonesia", "தமிழ்", "اردو")

@Composable
private fun TourScriptCarousel() {
    val accent = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "scripts")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = TOUR_SCRIPTS.size.toFloat(),
        animationSpec = infiniteRepeatable(
            tween(TOUR_SCRIPTS.size * 1600, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "scripts-cycle",
    )
    val step = cycle.toInt() % TOUR_SCRIPTS.size
    val frac = cycle - floor(cycle)
    // A short dip at each hand-over, so the words change rather than blink.
    val fade = when {
        frac < 0.12f -> frac / 0.12f
        frac > 0.88f -> (1f - frac) / 0.12f
        else -> 1f
    }
    Row(
        modifier = Modifier.width(260.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (slot in 0..2) {
            val word = TOUR_SCRIPTS[(step + slot) % TOUR_SCRIPTS.size]
            val middle = slot == 1
            Surface(
                modifier = Modifier.weight(1f).alpha(fade),
                shape = RoundedCornerShape(10.dp),
                color = if (middle) accent.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent,
            ) {
                Text(
                    text = word,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (middle) FontWeight.Bold else FontWeight.Normal,
                    color = if (middle) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The same emoji found under three languages.
 *
 * The words are deliberately not translated: the point of the picture is that these are *different*
 * languages, which a locale-swapped trio would hide rather than show.
 */
private val TOUR_EMOJI_WORDS = listOf("heart", "心", "قلب")
private val TOUR_EMOJI = listOf("❤️", "💖", "💘")

@Composable
private fun TourEmojiSearch() {
    val accent = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "emoji")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = TOUR_EMOJI_WORDS.size.toFloat(),
        animationSpec = infiniteRepeatable(
            tween(TOUR_EMOJI_WORDS.size * 2600, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "emoji-cycle",
    )
    val word = TOUR_EMOJI_WORDS[cycle.toInt() % TOUR_EMOJI_WORDS.size]
    val frac = cycle - floor(cycle)
    val typed = word.take(((frac / 0.40f).coerceAtMost(1f) * word.length).toInt())
    val hits = if (frac < 0.48f) 0 else (((frac - 0.48f) / 0.13f).toInt() + 1).coerceAtMost(TOUR_EMOJI.size)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(percent = 50), color = accent.copy(alpha = 0.12f)) {
            Row(
                modifier = Modifier.width(180.dp).padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = typed + if (typed.length < word.length) "▌" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TOUR_EMOJI.forEachIndexed { index, emoji ->
                Text(
                    text = emoji,
                    fontSize = 30.sp,
                    modifier = Modifier.alpha(if (index < hits) 1f else 0f),
                )
            }
        }
    }
}

/** Tiles for the sticker grid: shapes rather than pictures, so nothing has to ship with the app. */
private val TOUR_STICKER_TABS = 3
private val TOUR_STICKER_TILES = 6

/**
 * A folder becoming a keyboard panel.
 *
 * Deliberately abstract: the tiles are rounded shapes in the accent colour, not stickers, because a
 * picture would have to be shipped, licensed and drawn twice for light and dark. What the page has
 * to convey is the shape of the feature — tabs across the top, a grid below, one of them kept — and
 * that survives without a single real image.
 */
@Composable
private fun TourStickerGrid() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "stickers")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4600, easing = LinearEasing), RepeatMode.Restart),
        label = "sticker-cycle",
    )
    // The tiles arrive one after another rather than all at once: a folder being read, not a picture
    // of a full grid.
    val shown = ((cycle / 0.55f).coerceAtMost(1f) * TOUR_STICKER_TILES).toInt()
    val starred = cycle > 0.72f

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.width(196.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringRes(R.string.apptour61__art_folder),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Subfolders as tabs, the first one active — the one thing about the panel worth showing.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(TOUR_STICKER_TABS) { index ->
                Box(
                    modifier = Modifier
                        .size(width = if (index == 0) 34.dp else 26.dp, height = 6.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(if (index == 0) accent else accent.copy(alpha = 0.22f)),
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(2) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { column ->
                        val index = row * 3 + column
                        val here = index < shown
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    accent.copy(alpha = if (here) 0.10f + 0.05f * (index % 3) else 0.04f),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (here) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(if (index % 2 == 0) 50 else 28))
                                        .background(accent.copy(alpha = 0.55f)),
                                )
                            }
                            // One of them kept, because favourites are half of what a folder is for.
                            if (index == 1 && starred) {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(13.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A voice message arriving from another app and turning into text by itself.
 *
 * The three beats are the whole feature: it comes from a share sheet, it starts without being asked,
 * and what you get back is text you can read. The lines are bars rather than words so the picture
 * needs no translation — the one label on it is the share sheet's own.
 */
@Composable
private fun TourShareToText() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "share")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5000, easing = LinearEasing), RepeatMode.Restart),
        label = "share-cycle",
    )
    val handedOver = cycle > 0.30f
    // Four lines of transcript, filling in one after another once the file has been handed over.
    val lines = if (!handedOver) 0 else (((cycle - 0.34f) / 0.12f).toInt() + 1).coerceIn(0, 4)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(200.dp)) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = accent.copy(alpha = if (handedOver) 0.22f else 0.10f),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(
                    text = stringRes(R.string.apptour61__art_share),
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
                Spacer(modifier = Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = "Dictate",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(7.dp))
        Icon(
            imageVector = Icons.Filled.ArrowDownward,
            contentDescription = null,
            tint = accent.copy(alpha = if (handedOver) 1f else 0.30f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.height(7.dp))
        Box(modifier = Modifier.fillMaxWidth().height(22.dp)) {
            TourWaveBars(
                color = accent.copy(alpha = if (handedOver) 0.45f else 0.18f),
                fraction = 1f,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        // The transcript. Bars, not words: nothing here needs translating, and a fake sentence in
        // English on a page shown in twenty languages would read as an oversight.
        Column(
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            listOf(1f, 0.92f, 0.97f, 0.55f).forEachIndexed { index, width ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth(width)
                        .height(5.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(
                            MaterialTheme.colorScheme.onSurface
                                .copy(alpha = if (index < lines) 0.55f else 0.08f),
                        ),
                )
            }
        }
    }
}

/**
 * The suggestion strip marking what space will take, and then taking it.
 *
 * Both halves of the autocorrect work are in one picture: the middle candidate wears the accent pill
 * that #295 asked for, and the word in the field above is swapped for it. The typed word and its
 * correction are strings rather than literals, so a translator can pick a slip that is a real one in
 * their language — "teh" means nothing outside English.
 */
@Composable
private fun TourCorrectionPill() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val typedWord = stringRes(R.string.apptour61__art_typed)
    val fixedWord = stringRes(R.string.apptour61__art_fixed)
    val transition = rememberInfiniteTransition(label = "correction")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "correction-cycle",
    )
    val pilled = cycle > 0.34f
    val committed = cycle > 0.62f

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(216.dp)) {
        Box(modifier = Modifier.height(40.dp), contentAlignment = Alignment.Center) {
            Text(
                text = if (committed) fixedWord else typedWord,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (committed) accent else MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(muted.copy(alpha = 0.25f)),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The typed word stays on the left and stays tappable — the correction is never the only
            // way out (#150), and the picture should not suggest otherwise.
            Text(
                text = typedWord,
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = if (pilled) accent else Color.Transparent,
            ) {
                Text(
                    text = fixedWord,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (pilled) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Text(
                text = "…",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
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
        if (page.art != null) {
            // Show the real thing instead of an icon, so the user can see the new design right here.
            when (page.art) {
                TourArt.CLOUD_ORB -> TourCloudOrb()
                TourArt.DESIGN_ORBS -> TourDesignOrbs()
                TourArt.CREDIT_METER -> TourCreditMeter()
                TourArt.WAVE_COMPRESS -> TourWaveCompress()
                TourArt.PINYIN_STRIP -> TourPinyinStrip()
                TourArt.SCRIPT_CAROUSEL -> TourScriptCarousel()
                TourArt.EMOJI_SEARCH -> TourEmojiSearch()
                TourArt.STICKER_GRID -> TourStickerGrid()
                TourArt.SHARE_TO_TEXT -> TourShareToText()
                TourArt.CORRECTION_PILL -> TourCorrectionPill()
            }
        } else {
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

// The waveform moved to dictate/ui/DictateWaveform.kt so the setup wizard can open with the same
// picture. One definition, because two copies of an animation drift.
