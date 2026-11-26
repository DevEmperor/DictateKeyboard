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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.Segment
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.Watch
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
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
import kotlin.math.cos
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
    /** Headed lists under the body, for the page that collects the small changes (6.4). */
    val sections: List<TourListSection> = emptyList(),
)

/** One area of the small-changes list: a heading and one line per change. */
internal data class TourListSection(
    @StringRes val heading: Int,
    val items: List<Int>,
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

    /** A word typed often enough to be kept, climbing the three stages into the strip (6.2). */
    LEARN_LADDER,

    /** App content passing behind a key grid — the glass lives in the gaps, not on the keys (6.2). */
    GLASS_KEYS,

    /** A row of keys breaking into two runs, with both thumbs reaching their own half (6.2). */
    SPLIT_ROW,

    /** A typed word finding its emoji, which rides along instead of taking a suggestion's seat (6.2). */
    EMOJI_RIDE,

    /** The symbol layer held open, slid across, and handed back to the letters (6.2). */
    LAYER_PEEK,

    /** Light passing once across the keyboard, leaving a few keys awake — the opening page (6.2). */
    KEYBOARD_WAKE,

    /** A typed sum getting its answer back in the suggestion strip (6.2). */
    INLINE_ANSWER,

    /** A selection sweeping across two lines while the row counts what it holds (6.2). */
    SELECTION_COUNT,

    /** A search narrowing three clips down to the one that contains it (6.2). */
    CLIP_SEARCH,

    /** A finished dictation dropping into a folder as a file (6.2). */
    FOLDER_DROP,

    /** Four rows settling into place one after another — the "also" page (6.2). */
    SMALL_THINGS,

    /** A ring closing on a check — the outro (6.2). */
    DONE_RING,

    /** Three panels rising out of the keys one after another — the opening page (6.3). */
    TOOL_RISE,

    /** A photo of printed lines, swept, one line lifting out of it into a field (6.3). */
    SCAN_TEXT,

    /** Eleven scattered keys drifting together into one tidy panel of tiles (6.3). */
    EDIT_PANEL,

    /** One Smartbar button held until its second action takes the same seat (6.3). */
    SECOND_ACTION,

    /** The floating button held, a short menu fanning out beside it (6.3). */
    HOLD_MENU,

    /** A model too big for the phone, shrinking until it fits inside it (6.3). */
    MODEL_FITS,

    /** A few keys turning over to show what the release added — the opening page (6.4). */
    GLYPH_TURN,

    /** Typing into the field above the Smartbar while the app's field fills with the translation (6.4). */
    TRANSLATE_BAR,

    /** A symbol key taking a new symbol and its long presses being dragged into order (6.4). */
    SYMBOL_EDIT,

    /** The user's own short provider list, and three new providers arriving behind its add row (6.4). */
    PROVIDER_ARRIVE,

    /** A recording whose first word lights up, and the selected text being rewritten (6.4). */
    COMMAND_WORD,

    /** A dictation into a password field that never reaches the history below it (6.4). */
    PRIVATE_FIELD,

    /** The mic, and one mark per page of the release arriving around it — the opening page (6.5). */
    ICON_RING,

    /** A new model arriving at the top of the on-device list and downloading (6.5). */
    MODEL_DOWNLOAD,

    /** A dictation that finds the home server gone and takes the detour to the second provider (6.5). */
    FALLBACK_DETOUR,

    /** The floating button with Next beside it, each cut adding a part to the caption above (6.5). */
    BUBBLE_SEGMENTS,

    /** Two providers' model chips turning over to the newer model, the custom words following (6.5). */
    MODEL_TURN,

    /** SayOVA's store banner, the one picture in the tours that is not drawn at runtime (6.5). */
    SAYOVA_BANNER,
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

private val WhatsNewPages62: List<WhatsNewPage> = listOf(
    WhatsNewPage(
        icon = Icons.Filled.AutoAwesome,
        eyebrow = R.string.apptour62__intro_eyebrow,
        title = R.string.apptour62__intro_title,
        body = R.string.apptour62__intro_body,
        cta = R.string.apptour__start,
        route = null,
        kind = PageKind.INTRO,
        art = TourArt.KEYBOARD_WAKE,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Psychology,
        eyebrow = R.string.apptour62__learning_eyebrow,
        title = R.string.apptour62__learning_title,
        body = R.string.apptour62__learning_body,
        // This release flips the default to on *and* migrates existing users onto it, overwriting a
        // deliberate "off" that nothing can tell apart from an untouched default. So the button is
        // the way back out, not the way in: the page has to say plainly that it is on and where the
        // switch is, which is what makes this page a release blocker rather than an announcement.
        cta = R.string.apptour62__cta_manage,
        route = Routes.Settings.Dictionary,
        highlight = true,
        art = TourArt.LEARN_LADDER,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Brush,
        eyebrow = R.string.apptour62__themes_eyebrow,
        title = R.string.apptour62__themes_title,
        body = R.string.apptour62__themes_body,
        cta = R.string.apptour62__cta_try,
        route = Routes.Settings.Theme,
        highlight = true,
        art = TourArt.GLASS_KEYS,
    ),
    WhatsNewPage(
        icon = Icons.Filled.VerticalSplit,
        eyebrow = R.string.apptour62__split_eyebrow,
        title = R.string.apptour62__split_title,
        body = R.string.apptour62__split_body,
        // The split is switched on by a Smartbar quick action, so the settings screen that matters is
        // the one where the quick actions are arranged, not a keyboard-layout page.
        cta = R.string.apptour62__cta_try,
        route = Routes.Settings.Smartbar,
        art = TourArt.SPLIT_ROW,
    ),
    WhatsNewPage(
        icon = Icons.Filled.EmojiEmotions,
        eyebrow = R.string.apptour62__emoji_eyebrow,
        title = R.string.apptour62__emoji_title,
        body = R.string.apptour62__emoji_body,
        cta = R.string.apptour62__cta_try,
        route = Routes.Settings.Media,
        art = TourArt.EMOJI_RIDE,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Tune,
        eyebrow = R.string.apptour62__helpers_eyebrow,
        title = R.string.apptour62__helpers_title,
        body = R.string.apptour62__helpers_body,
        cta = R.string.apptour62__cta_try,
        route = Routes.Settings.Typing,
        art = TourArt.INLINE_ANSWER,
    ),
    WhatsNewPage(
        icon = Icons.Filled.SelectAll,
        eyebrow = R.string.apptour62__selection_eyebrow,
        title = R.string.apptour62__selection_title,
        body = R.string.apptour62__selection_body,
        cta = R.string.apptour62__cta_try,
        route = Routes.Settings.Smartbar,
        art = TourArt.SELECTION_COUNT,
    ),
    WhatsNewPage(
        icon = Icons.Filled.ContentPaste,
        eyebrow = R.string.apptour62__clipboard_eyebrow,
        title = R.string.apptour62__clipboard_title,
        body = R.string.apptour62__clipboard_body,
        cta = R.string.apptour62__cta_try,
        route = Routes.Settings.Clipboard,
        art = TourArt.CLIP_SEARCH,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Gesture,
        eyebrow = R.string.apptour62__gestures_eyebrow,
        title = R.string.apptour62__gestures_title,
        body = R.string.apptour62__gestures_body,
        cta = R.string.apptour62__cta_try,
        route = Routes.Settings.Gestures,
        art = TourArt.LAYER_PEEK,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Folder,
        eyebrow = R.string.apptour62__export_eyebrow,
        title = R.string.apptour62__export_title,
        body = R.string.apptour62__export_body,
        cta = R.string.apptour62__cta_try,
        route = Routes.Settings.DictateHistory,
        art = TourArt.FOLDER_DROP,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Bolt,
        eyebrow = R.string.apptour62__more_eyebrow,
        title = R.string.apptour62__more_title,
        body = R.string.apptour62__more_body,
        cta = R.string.apptour__next,
        route = null,
        art = TourArt.SMALL_THINGS,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Celebration,
        eyebrow = R.string.apptour62__outro_eyebrow,
        title = R.string.apptour62__outro_title,
        body = R.string.apptour62__outro_body,
        cta = R.string.apptour__done,
        route = null,
        kind = PageKind.OUTRO,
        art = TourArt.DONE_RING,
    ),
)

private val WhatsNewPages63: List<WhatsNewPage> = listOf(
    WhatsNewPage(
        icon = Icons.Filled.AutoAwesome,
        eyebrow = R.string.apptour63__intro_eyebrow,
        title = R.string.apptour63__intro_title,
        body = R.string.apptour63__intro_body,
        cta = R.string.apptour__start,
        route = null,
        kind = PageKind.INTRO,
        art = TourArt.TOOL_RISE,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Search,
        eyebrow = R.string.apptour63__scan_eyebrow,
        title = R.string.apptour63__scan_title,
        body = R.string.apptour63__scan_body,
        // Scanning is reached from a Smartbar action, so the screen that matters is the one where
        // the actions are arranged — there is no scan settings page, and inventing a deep link to
        // the keyboard itself would land the user somewhere they cannot act.
        cta = R.string.apptour63__cta_try,
        route = Routes.Settings.Smartbar,
        highlight = true,
        art = TourArt.SCAN_TEXT,
    ),
    WhatsNewPage(
        icon = Icons.Filled.SelectAll,
        eyebrow = R.string.apptour63__editing_eyebrow,
        title = R.string.apptour63__editing_title,
        body = R.string.apptour63__editing_body,
        cta = R.string.apptour63__cta_try,
        route = Routes.Settings.Smartbar,
        highlight = true,
        art = TourArt.EDIT_PANEL,
    ),
    WhatsNewPage(
        icon = Icons.Filled.TouchApp,
        eyebrow = R.string.apptour63__second_eyebrow,
        title = R.string.apptour63__second_title,
        body = R.string.apptour63__second_body,
        cta = R.string.apptour63__cta_try,
        route = Routes.Settings.SmartbarSecondActions,
        art = TourArt.SECOND_ACTION,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Mic,
        eyebrow = R.string.apptour63__floating_eyebrow,
        title = R.string.apptour63__floating_title,
        body = R.string.apptour63__floating_body,
        cta = R.string.apptour63__cta_try,
        route = Routes.Settings.DictateFloatingButton,
        art = TourArt.HOLD_MENU,
    ),
    WhatsNewPage(
        icon = Icons.Filled.CloudOff,
        eyebrow = R.string.apptour63__offline_eyebrow,
        title = R.string.apptour63__offline_title,
        body = R.string.apptour63__offline_body,
        cta = R.string.apptour63__cta_try,
        route = Routes.Settings.DictateProviders,
        art = TourArt.MODEL_FITS,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Bolt,
        eyebrow = R.string.apptour63__more_eyebrow,
        title = R.string.apptour63__more_title,
        body = R.string.apptour63__more_body,
        cta = R.string.apptour__next,
        route = null,
        art = TourArt.SMALL_THINGS,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Celebration,
        eyebrow = R.string.apptour63__outro_eyebrow,
        title = R.string.apptour63__outro_title,
        body = R.string.apptour63__outro_body,
        cta = R.string.apptour__done,
        route = null,
        kind = PageKind.OUTRO,
        art = TourArt.DONE_RING,
    ),
)

/**
 * 6.4: five pages for what changes how Dictate is used, and one list for everything else.
 *
 * The first draft gave the typing fixes, glide, the dictation fixes and the clipboard their own pages,
 * twelve in all. Jannis cut them back: those are repairs and small UI turns a reader can do nothing
 * with on a page of their own. They live on the "also" page now, as a list per area that can be read
 * or skipped — including the defaults that changed under people (pins last, no language swipe), which
 * have to be said somewhere even when they are not worth a page.
 */
private val WhatsNewPages64: List<WhatsNewPage> = listOf(
    WhatsNewPage(
        icon = Icons.Filled.AutoAwesome,
        eyebrow = R.string.apptour64__intro_eyebrow,
        title = R.string.apptour64__intro_title,
        body = R.string.apptour64__intro_body,
        cta = R.string.apptour__start,
        route = null,
        kind = PageKind.INTRO,
        art = TourArt.GLYPH_TURN,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Translate,
        eyebrow = R.string.apptour64__translate_eyebrow,
        title = R.string.apptour64__translate_title,
        body = R.string.apptour64__translate_body,
        // The languages page rather than the keyboard: nothing translates until one is downloaded,
        // and that page is also where each download's size is shown before it is fetched.
        cta = R.string.apptour64__cta_languages,
        route = Routes.Settings.Translation,
        highlight = true,
        art = TourArt.TRANSLATE_BAR,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Dialpad,
        eyebrow = R.string.apptour64__symbols_eyebrow,
        title = R.string.apptour64__symbols_title,
        body = R.string.apptour64__symbols_body,
        cta = R.string.apptour63__cta_try,
        route = Routes.Settings.CustomSymbols,
        highlight = true,
        art = TourArt.SYMBOL_EDIT,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Dns,
        eyebrow = R.string.apptour64__providers_eyebrow,
        title = R.string.apptour64__providers_title,
        body = R.string.apptour64__providers_body,
        // The providers list, because both halves of the page end there: the new providers behind
        // "Add a provider", and the on-device row, where Parakeet v3 is swapped for Ultra.
        cta = R.string.apptour64__cta_providers,
        route = Routes.Settings.DictateProviders,
        art = TourArt.PROVIDER_ARRIVE,
    ),
    WhatsNewPage(
        icon = Icons.Filled.RecordVoiceOver,
        eyebrow = R.string.apptour64__command_eyebrow,
        title = R.string.apptour64__command_title,
        body = R.string.apptour64__command_body,
        cta = R.string.apptour63__cta_try,
        route = Routes.Settings.DictateRewording,
        art = TourArt.COMMAND_WORD,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Lock,
        eyebrow = R.string.apptour64__privacy_eyebrow,
        title = R.string.apptour64__privacy_title,
        body = R.string.apptour64__privacy_body,
        // No screen to send anyone to: the audit changed behaviour, not settings.
        cta = R.string.apptour__next,
        route = null,
        art = TourArt.PRIVATE_FIELD,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Bolt,
        eyebrow = R.string.apptour64__more_eyebrow,
        title = R.string.apptour64__more_title,
        body = R.string.apptour64__more_body,
        cta = R.string.apptour__next,
        route = null,
        art = TourArt.SMALL_THINGS,
        sections = listOf(
            TourListSection(
                R.string.apptour64__more_typing,
                listOf(
                    R.string.apptour64__more_typing_slips,
                    R.string.apptour64__more_typing_autocorrect,
                    R.string.apptour64__more_typing_glide,
                    R.string.apptour64__more_typing_swipe,
                    R.string.apptour64__more_typing_punctuation,
                    R.string.apptour64__more_typing_calculator,
                    R.string.apptour64__more_typing_size,
                    R.string.apptour64__more_typing_languages,
                ),
            ),
            TourListSection(
                R.string.apptour64__more_dictation,
                listOf(
                    R.string.apptour64__more_dictation_stop,
                    R.string.apptour64__more_dictation_timeout,
                    R.string.apptour64__more_dictation_live,
                    R.string.apptour64__more_dictation_hold,
                    R.string.apptour64__more_dictation_talkback,
                ),
            ),
            TourListSection(
                R.string.apptour64__more_clipboard,
                listOf(
                    R.string.apptour64__more_clipboard_pins,
                    R.string.apptour64__more_clipboard_chips,
                    R.string.apptour64__more_clipboard_emoji,
                ),
            ),
            TourListSection(
                R.string.apptour64__more_elsewhere,
                listOf(
                    R.string.apptour64__more_elsewhere_bubble,
                    R.string.apptour64__more_elsewhere_watch,
                ),
            ),
        ),
    ),
    WhatsNewPage(
        icon = Icons.Filled.Celebration,
        eyebrow = R.string.apptour64__outro_eyebrow,
        title = R.string.apptour64__outro_title,
        body = R.string.apptour64__outro_body,
        cta = R.string.apptour__done,
        route = null,
        kind = PageKind.OUTRO,
        art = TourArt.DONE_RING,
    ),
)

/**
 * 6.5: four pages for what changes how Dictate is used, the list for everything else, and SayOVA.
 *
 * The encrypted backups were a page of their own in the first draft; Jannis moved them into the list,
 * and gave the freed place to SayOVA, just before the outro. That page is the only one that is about
 * another app, so it shows that app's own store picture rather than a drawing, and its button opens
 * More apps, where the Play and website links are, instead of leaving for the store from inside a tour.
 */
private val WhatsNewPages65: List<WhatsNewPage> = listOf(
    WhatsNewPage(
        icon = Icons.Filled.AutoAwesome,
        eyebrow = R.string.apptour65__intro_eyebrow,
        title = R.string.apptour65__intro_title,
        body = R.string.apptour65__intro_body,
        cta = R.string.apptour__start,
        route = null,
        kind = PageKind.INTRO,
        art = TourArt.ICON_RING,
    ),
    WhatsNewPage(
        icon = Icons.Filled.CloudOff,
        eyebrow = R.string.apptour65__pro_eyebrow,
        title = R.string.apptour65__pro_title,
        body = R.string.apptour65__pro_body,
        // The on-device models are a row of the providers list, so that is where the download is.
        cta = R.string.apptour64__cta_providers,
        route = Routes.Settings.DictateProviders,
        highlight = true,
        art = TourArt.MODEL_DOWNLOAD,
    ),
    WhatsNewPage(
        icon = Icons.AutoMirrored.Filled.AltRoute,
        eyebrow = R.string.apptour65__fallback_eyebrow,
        title = R.string.apptour65__fallback_title,
        body = R.string.apptour65__fallback_body,
        // The fallback is chosen in the Transcription dialog at the top of the providers list.
        cta = R.string.apptour64__cta_providers,
        route = Routes.Settings.DictateProviders,
        highlight = true,
        art = TourArt.FALLBACK_DETOUR,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Mic,
        eyebrow = R.string.apptour65__floating_eyebrow,
        title = R.string.apptour65__floating_title,
        body = R.string.apptour65__floating_body,
        cta = R.string.apptour63__cta_try,
        route = Routes.Settings.DictateFloatingButton,
        art = TourArt.BUBBLE_SEGMENTS,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Dns,
        eyebrow = R.string.apptour65__providers_eyebrow,
        title = R.string.apptour65__providers_title,
        body = R.string.apptour65__providers_body,
        cta = R.string.apptour64__cta_providers,
        route = Routes.Settings.DictateProviders,
        art = TourArt.MODEL_TURN,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Bolt,
        eyebrow = R.string.apptour64__more_eyebrow,
        title = R.string.apptour65__more_title,
        body = R.string.apptour64__more_body,
        cta = R.string.apptour__next,
        route = null,
        art = TourArt.SMALL_THINGS,
        sections = listOf(
            TourListSection(
                R.string.apptour64__more_dictation,
                listOf(
                    R.string.apptour65__more_dictation_midsentence,
                    R.string.apptour65__more_dictation_fullstop,
                    R.string.apptour65__more_dictation_ondevice,
                    R.string.apptour65__more_dictation_share,
                    R.string.apptour65__more_dictation_voiceinput,
                ),
            ),
            TourListSection(
                R.string.apptour65__more_providers,
                listOf(
                    R.string.apptour65__more_providers_reasoning,
                    R.string.apptour65__more_providers_luna,
                    R.string.apptour65__more_providers_gemini,
                    R.string.apptour65__more_providers_thinking,
                    R.string.apptour65__more_providers_models,
                    R.string.apptour65__more_providers_azure,
                    R.string.apptour65__more_providers_network,
                    R.string.apptour65__more_providers_ownserver,
                ),
            ),
            TourListSection(
                R.string.apptour65__more_prompts,
                listOf(
                    R.string.apptour65__more_prompts_instruction,
                    R.string.apptour65__more_prompts_question,
                    R.string.apptour65__more_prompts_field,
                ),
            ),
            TourListSection(
                R.string.apptour64__more_typing,
                listOf(
                    R.string.apptour65__more_typing_polish,
                    R.string.apptour65__more_typing_addresses,
                    R.string.apptour65__more_typing_inside,
                    R.string.apptour65__more_typing_enter,
                ),
            ),
            TourListSection(
                R.string.apptour65__more_settings,
                listOf(
                    R.string.apptour65__more_settings_backup,
                    R.string.apptour65__more_settings_prompts,
                    R.string.apptour65__more_settings_help,
                    R.string.apptour65__more_settings_chinese,
                ),
            ),
        ),
    ),
    WhatsNewPage(
        icon = Icons.Filled.Watch,
        eyebrow = R.string.apptour65__sayova_eyebrow,
        title = R.string.apptour65__sayova_title,
        body = R.string.apptour65__sayova_body,
        cta = R.string.dictate__promo_sayova_action,
        route = Routes.Settings.MoreApps,
        art = TourArt.SAYOVA_BANNER,
    ),
    WhatsNewPage(
        icon = Icons.Filled.Celebration,
        eyebrow = R.string.apptour65__outro_eyebrow,
        title = R.string.apptour65__outro_title,
        body = R.string.apptour65__outro_body,
        cta = R.string.apptour__done,
        route = null,
        kind = PageKind.OUTRO,
        art = TourArt.DONE_RING,
    ),
)

internal val WHATS_NEW_TOURS: List<WhatsNewTourDef> = listOf(
    WhatsNewTourDef(VersionName(5, 0, 0), WhatsNewPages50),
    WhatsNewTourDef(VersionName(5, 1, 0), WhatsNewPages51),
    WhatsNewTourDef(VersionName(5, 2, 0), WhatsNewPages52),
    WhatsNewTourDef(VersionName(5, 3, 0), WhatsNewPages53),
    WhatsNewTourDef(VersionName(6, 0, 0), WhatsNewPages60),
    WhatsNewTourDef(VersionName(6, 1, 0), WhatsNewPages61),
    WhatsNewTourDef(VersionName(6, 2, 0), WhatsNewPages62),
    WhatsNewTourDef(VersionName(6, 3, 0), WhatsNewPages63),
    WhatsNewTourDef(VersionName(6, 4, 0), WhatsNewPages64),
    WhatsNewTourDef(VersionName(6, 5, 0), WhatsNewPages65),
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

/**
 * A word being typed often enough to be kept, then arriving in the strip as a suggestion.
 *
 * The three rungs are the three stages a word climbs before it counts as known, and the counter is
 * digits rather than words, so only the example word itself needs translating — and it has to be
 * translated, because a name that looks unknown in English is an ordinary word elsewhere.
 */
@Composable
private fun TourLearnLadder() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val word = stringRes(R.string.apptour62__art_word)
    val transition = rememberInfiniteTransition(label = "learning")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart),
        label = "learn-cycle",
    )
    // Three typings, then the word is known and the strip offers it.
    val typed = ((cycle / 0.20f).toInt() + 1).coerceIn(1, 3)
    val rungs = if (cycle > 0.62f) 3 else typed
    val learned = cycle > 0.68f

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(216.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = word,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (learned) accent else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "×$typed",
                style = MaterialTheme.typography.labelMedium,
                color = muted,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 6.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(if (index < rungs) accent else accent.copy(alpha = 0.16f)),
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(muted.copy(alpha = 0.25f)),
        )
        Spacer(modifier = Modifier.height(12.dp))
        // The strip: the learned word takes a seat there, next to the ordinary suggestions.
        Row(
            modifier = Modifier.fillMaxWidth().height(28.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 38.dp, height = 7.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(muted.copy(alpha = 0.25f)),
            )
            if (learned) {
                Surface(shape = RoundedCornerShape(percent = 50), color = accent) {
                    Text(
                        text = word,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(width = 52.dp, height = 7.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(muted.copy(alpha = 0.25f)),
                )
            }
            Box(
                modifier = Modifier
                    .size(width = 38.dp, height = 7.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(muted.copy(alpha = 0.25f)),
            )
        }
    }
}

/**
 * App content drifting past behind a grid of keys.
 *
 * This is the one thing about the glass themes worth showing and the one thing a screenshot of them
 * gets wrong: the keys are nearly solid — they have to be, or foreign text reads straight through
 * them — and the translucency lives in the gaps between them. Carries no text at all, so it needs no
 * translation, and every colour derives from the accent.
 */
@Composable
private fun TourGlassKeys() {
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val foreign = MaterialTheme.colorScheme.onSurface
    val transition = rememberInfiniteTransition(label = "glass")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "glass-drift",
    )

    Box(
        modifier = Modifier
            .size(width = 216.dp, height = 132.dp)
            .clip(RoundedCornerShape(20.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = accent.copy(alpha = 0.30f))
            // Bands of "app" passing behind: one accent, two neutral lines of foreign writing.
            for (i in 0 until 3) {
                val y = size.height * (0.16f + 0.28f * i)
                val x = ((drift + i * 0.34f) % 1f) * (size.width * 1.6f) - size.width * 0.5f
                drawRoundRect(
                    color = if (i == 1) accent.copy(alpha = 0.65f) else foreign.copy(alpha = 0.32f),
                    topLeft = Offset(x, y),
                    size = Size(size.width * 0.52f, size.height * 0.12f),
                    cornerRadius = CornerRadius(size.height * 0.06f, size.height * 0.06f),
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(3) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repeat(4) { column ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clip(RoundedCornerShape(9.dp))
                                // 94 %: the threshold at which foreign writing stops reading through
                                // a key. The last row's right-hand key is the accent one.
                                .background(
                                    if (row == 2 && column == 3) accent.copy(alpha = 0.94f)
                                    else surface.copy(alpha = 0.94f),
                                ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A keyboard coming apart in the middle, with a thumb arriving at each half.
 *
 * Bars rather than letters, because the split is geometry: it falls where the accumulated key width
 * first reaches half the row, which is why it works for every layout and not just QWERTY.
 */
@Composable
private fun TourSplitRow() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "split")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4600, easing = LinearEasing), RepeatMode.Restart),
        label = "split-cycle",
    )
    // Open, hold, close again — so the picture says it is a toggle, not a fixed layout.
    val open = when {
        cycle < 0.22f -> cycle / 0.22f
        cycle < 0.80f -> 1f
        else -> 1f - (cycle - 0.80f) / 0.20f
    }
    val gap = 4f + 40f * open

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(216.dp),
    ) {
        repeat(3) { row ->
            Row(
                modifier = Modifier.fillMaxWidth().height(22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    repeat(if (row == 2) 3 else 4) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clip(RoundedCornerShape(6.dp))
                                .background(accent.copy(alpha = 0.16f)),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(gap.dp))
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    repeat(if (row == 2) 3 else 4) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clip(RoundedCornerShape(6.dp))
                                .background(accent.copy(alpha = 0.16f)),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
        Spacer(modifier = Modifier.height(6.dp))
        // Two thumbs, reaching in from the edges they actually rest on.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            repeat(2) { side ->
                Box(
                    modifier = Modifier
                        .offset(x = ((if (side == 0) 1f else -1f) * 26f * open).dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (open > 0.5f) accent.copy(alpha = 0.55f) else muted.copy(alpha = 0.3f)),
                )
            }
        }
    }
}

/**
 * A typed word bringing its emoji along.
 *
 * The point of the picture is what does *not* happen: the two ordinary suggestions keep their seats
 * and the emoji arrives beside them. Word and emoji are both strings, because the word has to be one
 * the language really writes and the emoji has to be one that word really means.
 */
@Composable
private fun TourEmojiRide() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val word = stringRes(R.string.apptour62__art_emoji_word)
    val emoji = stringRes(R.string.apptour62__art_emoji)
    val transition = rememberInfiniteTransition(label = "emoji-ride")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4400, easing = LinearEasing), RepeatMode.Restart),
        label = "emoji-cycle",
    )
    val letters = ((cycle / 0.30f) * word.length).toInt().coerceIn(0, word.length)
    val ride = ((cycle - 0.34f) / 0.16f).coerceIn(0f, 1f)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(216.dp)) {
        Box(modifier = Modifier.height(40.dp), contentAlignment = Alignment.Center) {
            Text(
                text = word.take(letters),
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
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = word,
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
            Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 7.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(muted.copy(alpha = 0.25f)),
            )
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = accent.copy(alpha = 0.16f * ride),
                modifier = Modifier
                    .alpha(ride)
                    .offset(y = ((1f - ride) * 10f).dp),
            ) {
                Text(
                    text = emoji,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    fontSize = 19.sp,
                )
            }
        }
    }
}

/**
 * The symbol layer held open with one finger and handed straight back.
 *
 * Three beats, which is the whole gesture: hold, slide onto a character, let go — the character is in
 * the field and the letters are already back. The symbols carry no language, so the picture needs no
 * translation; "?123" is the key's own label from the layout file.
 */
@Composable
private fun TourLayerPeek() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val symbols = listOf("%", "&", "#", "@", "+")
    val target = 2
    val transition = rememberInfiniteTransition(label = "peek")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5000, easing = LinearEasing), RepeatMode.Restart),
        label = "peek-cycle",
    )
    val held = cycle > 0.14f && cycle < 0.72f
    val peek = when {
        cycle < 0.14f -> 0f
        cycle < 0.26f -> (cycle - 0.14f) / 0.12f
        cycle < 0.72f -> 1f
        cycle < 0.80f -> 1f - (cycle - 0.72f) / 0.08f
        else -> 0f
    }
    val onTarget = cycle > 0.46f && cycle < 0.72f
    val inserted = cycle > 0.72f

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(216.dp)) {
        Box(modifier = Modifier.height(34.dp), contentAlignment = Alignment.Center) {
            Text(
                text = if (inserted) symbols[target] else "",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(muted.copy(alpha = 0.25f)),
        )
        Spacer(modifier = Modifier.height(14.dp))
        // The layer that peeks in, and the letters waiting underneath it.
        Box(modifier = Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier.fillMaxWidth().alpha(1f - peek),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(5) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(muted.copy(alpha = 0.18f)),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(peek)
                    .offset(y = ((1f - peek) * -10f).dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                symbols.forEachIndexed { index, symbol ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(
                                if (onTarget && index == target) accent
                                else accent.copy(alpha = 0.14f),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = symbol,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (onTarget && index == target) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        // The key being held, and the finger on its way across to the character.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(7.dp),
                color = if (held) accent else muted.copy(alpha = 0.18f),
            ) {
                Text(
                    text = "?123",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (held) MaterialTheme.colorScheme.onPrimary else muted,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(22.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(muted.copy(alpha = 0.18f)),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth().height(16.dp)) {
            Box(
                modifier = Modifier
                    .offset(x = (14f + 104f * peek * (if (onTarget) 1f else 0.45f)).dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (held) 0.55f else 0f)),
            )
        }
    }
}

/**
 * A selection sweeping across the text while the row above counts what it holds.
 *
 * Lines are bars and the count is digits, so nothing here needs translating. The number climbing as
 * the selection grows is the whole point: it is the row that used to stand empty.
 */
@Composable
private fun TourSelectionCount() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "selection")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4800, easing = LinearEasing), RepeatMode.Restart),
        label = "selection-cycle",
    )
    val grown = ((cycle - 0.12f) / 0.45f).coerceIn(0f, 1f)
    val words = (grown * 12).toInt()
    val chars = (grown * 68).toInt()

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(216.dp)) {
        Box(modifier = Modifier.height(28.dp), contentAlignment = Alignment.Center) {
            if (words > 0) {
                Surface(shape = RoundedCornerShape(percent = 50), color = accent.copy(alpha = 0.16f)) {
                    Text(
                        text = "$words · $chars",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        // Two lines of text; the selection fills the first and then part of the second.
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            repeat(2) { line ->
                val fill = (grown * 2f - line).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .size(width = 200.dp, height = 11.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(muted.copy(alpha = 0.22f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(if (line == 1) fill * 0.6f else fill)
                            .clip(RoundedCornerShape(3.dp))
                            .background(accent.copy(alpha = 0.45f)),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        // The actions row, stepping aside once the selection is under way.
        Row(
            modifier = Modifier.fillMaxWidth().alpha(if (grown > 0.25f) 0.25f else 1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(5) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(muted.copy(alpha = 0.20f)),
                )
            }
        }
    }
}

/**
 * Three clips narrowing to the one that contains what was searched for.
 *
 * The query is a growing bar rather than a word, and the match is a highlighted run inside a clip —
 * that is what searching *content* looks like, and it carries no language.
 */
@Composable
private fun TourClipSearch() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "clips")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart),
        label = "clip-cycle",
    )
    val typed = ((cycle - 0.08f) / 0.25f).coerceIn(0f, 1f)
    val filtered = cycle > 0.45f

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(216.dp)) {
        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = muted.copy(alpha = 0.14f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = muted,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(7.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(typed * 0.55f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(accent.copy(alpha = 0.55f)),
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            repeat(3) { index ->
                // Only the middle clip survives the filter.
                val kept = index == 1 || !filtered
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (index == 1 && filtered) accent.copy(alpha = 0.10f) else muted.copy(alpha = 0.10f),
                    modifier = Modifier.fillMaxWidth().alpha(if (kept) 1f else 0.12f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 34.dp, height = 8.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(muted.copy(alpha = 0.40f)),
                        )
                        // The run that matched, lit in the accent.
                        Box(
                            modifier = Modifier
                                .size(width = 44.dp, height = 8.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(
                                    if (index == 1 && filtered) accent else muted.copy(alpha = 0.40f),
                                ),
                        )
                        Box(
                            modifier = Modifier
                                .size(width = 26.dp, height = 8.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(muted.copy(alpha = 0.40f)),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A finished dictation dropping into a folder as a file, and the folder filling up.
 *
 * The beat that matters is *automatic*: nothing is tapped between the waveform stopping and the file
 * landing. Wordless by construction.
 */
@Composable
private fun TourFolderDrop() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "export")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5000, easing = LinearEasing), RepeatMode.Restart),
        label = "export-cycle",
    )
    val spoken = (cycle / 0.30f).coerceIn(0f, 1f)
    val fall = ((cycle - 0.34f) / 0.28f).coerceIn(0f, 1f)
    val landed = cycle > 0.62f

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(216.dp)) {
        // The dictation: bars rising while it is spoken, then still.
        Row(
            modifier = Modifier.height(26.dp).alpha(1f - fall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            repeat(9) { index ->
                val reach = (spoken * 9f - index).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .size(width = 4.dp, height = (6 + 18 * reach * (0.5f + 0.5f * sin(index.toFloat()))).dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(accent.copy(alpha = 0.55f)),
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        // The file on its way down.
        Box(modifier = Modifier.height(34.dp), contentAlignment = Alignment.TopCenter) {
            if (fall > 0f && !landed) {
                Box(
                    modifier = Modifier
                        .offset(y = (fall * 22f).dp)
                        .size(width = 22.dp, height = 28.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(accent.copy(alpha = 0.75f)),
                )
            }
        }
        // The folder, with what has already arrived in it.
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = accent.copy(alpha = if (landed) 0.9f else 0.45f),
                modifier = Modifier.size(64.dp),
            )
            Row(
                modifier = Modifier.offset(y = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                repeat(3) { index ->
                    Box(
                        modifier = Modifier
                            .size(width = 7.dp, height = 10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                MaterialTheme.colorScheme.surface.copy(
                                    alpha = if (landed || index < 2) 0.92f else 0.25f,
                                ),
                            ),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 5.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(muted.copy(alpha = 0.18f)),
        )
    }
}

/**
 * A ring closing on a check — the last page.
 *
 * Smaller than the other artwork on purpose: the outro also carries the donation invite, and a
 * full-height picture would push it off the screen on a short phone.
 */
@Composable
private fun TourDoneRing() {
    val accent = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "done")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "done-cycle",
    )
    val sweep = (cycle / 0.42f).coerceIn(0f, 1f)
    val tick = ((cycle - 0.44f) / 0.18f).coerceIn(0f, 1f)

    Canvas(modifier = Modifier.size(96.dp)) {
        val stroke = size.minDimension * 0.07f
        val inset = stroke / 2f + size.minDimension * 0.06f
        drawCircle(
            color = accent.copy(alpha = 0.14f),
            radius = size.minDimension / 2f - inset,
            center = Offset(size.width / 2f, size.height / 2f),
            style = Stroke(width = stroke),
        )
        drawArc(
            color = accent,
            startAngle = -90f,
            sweepAngle = 360f * sweep,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2f, size.height - inset * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        // The check, drawn in two strokes so it can be written rather than appear.
        val a = Offset(size.width * 0.32f, size.height * 0.52f)
        val b = Offset(size.width * 0.44f, size.height * 0.65f)
        val c = Offset(size.width * 0.70f, size.height * 0.38f)
        if (tick > 0f) {
            val first = (tick / 0.4f).coerceIn(0f, 1f)
            drawLine(
                color = accent,
                start = a,
                end = Offset(a.x + (b.x - a.x) * first, a.y + (b.y - a.y) * first),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            if (tick > 0.4f) {
                val second = ((tick - 0.4f) / 0.6f).coerceIn(0f, 1f)
                drawLine(
                    color = accent,
                    start = b,
                    end = Offset(b.x + (c.x - b.x) * second, b.y + (c.y - b.y) * second),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/**
 * The opening page: light passing once across the keyboard, leaving a few keys awake.
 *
 * The first version of this page was chips flying out of an app tile, and it read as decoration
 * rather than as anything — abstract motion with nothing behind it. This shows the thing the release
 * is about instead: a keyboard, waking up. One slow sweep, no text.
 */
@Composable
private fun TourKeyboardWake() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "wake")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5600, easing = LinearEasing), RepeatMode.Restart),
        label = "wake-sweep",
    )
    // Which keys stay awake after the light has gone past — fixed, not random, so the picture is the
    // same one every time it is looked at.
    val awake = remember { setOf(2, 7, 11, 15, 19) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.width(212.dp),
    ) {
        repeat(3) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                repeat(7) { column ->
                    val index = row * 7 + column
                    // The sweep runs left to right and a little downwards, so the rows light in turn.
                    val own = column / 7f * 0.7f + row * 0.1f
                    val near = 1f - (abs(sweep - own) / 0.16f).coerceIn(0f, 1f)
                    val lit = if (sweep > own) 0.30f else 0f
                    Box(
                        modifier = Modifier
                            .size(width = 22.dp, height = 26.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(
                                accent.copy(
                                    alpha = 0.10f + 0.65f * near +
                                        (if (index in awake) lit * 0.5f else 0f),
                                ),
                            ),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        // The space bar, so the shape reads as a keyboard rather than as a grid of tiles.
        Box(
            modifier = Modifier
                .size(width = 118.dp, height = 20.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(
                    accent.copy(alpha = 0.10f + 0.55f * (1f - (abs(sweep - 0.82f) / 0.16f).coerceIn(0f, 1f))),
                ),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 4.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(muted.copy(alpha = 0.18f)),
        )
    }
}

/**
 * One helper shown properly instead of four shown in passing: a sum getting its answer.
 *
 * The first version cycled through all four utilities in one small frame and none of them landed —
 * four seconds each is not long enough to work out what you are being shown. The calculator is the
 * one of the four that can be read at a glance, and the page's own text names the other three. Big
 * type, one beat, digits only.
 */
@Composable
private fun TourInlineAnswer() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "calculator")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4600, easing = LinearEasing), RepeatMode.Restart),
        label = "calc-cycle",
    )
    // Typed, then answered, then held long enough to be read.
    val typedChars = ((cycle / 0.34f) * 6f).toInt().coerceIn(0, 6)
    val typed = "7 × 8 =".take(if (typedChars >= 6) 7 else typedChars)
    val answered = cycle > 0.42f
    val accepted = cycle > 0.68f

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(216.dp)) {
        // The field, with what was typed and — once taken — the answer behind it.
        Box(modifier = Modifier.height(52.dp), contentAlignment = Alignment.Center) {
            Text(
                text = if (accepted) "7 × 8 = 56" else typed,
                style = MaterialTheme.typography.headlineMedium,
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
        Spacer(modifier = Modifier.height(14.dp))
        // The strip: the answer arrives as a suggestion, which is the whole feature.
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 34.dp, height = 7.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(muted.copy(alpha = 0.22f)),
            )
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = if (answered) accent else Color.Transparent,
                modifier = Modifier.alpha(if (answered) 1f else 0f),
            ) {
                Text(
                    text = "56",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Box(
                modifier = Modifier
                    .size(width = 34.dp, height = 7.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(muted.copy(alpha = 0.22f)),
            )
        }
    }
}

/**
 * Four rows settling into place, one after another — the "and a lot of small things" page.
 *
 * Replaces a grid of two dozen dots pulsing in a diagonal wave, which was busy enough to pull the
 * eye off the text it was standing next to. A page that says "many small things" should not itself
 * be the loudest thing on the screen: same statement, four beats, slow.
 */
@Composable
private fun TourSmallThings() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "small-things")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7200, easing = LinearEasing), RepeatMode.Restart),
        label = "small-cycle",
    )
    val settled = (cycle / 0.19f).toInt()

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.width(196.dp),
    ) {
        repeat(4) { row ->
            val done = row < settled
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (done) accent else accent.copy(alpha = 0.16f)),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(width = (150 - row * 18).dp, height = 8.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(if (done) muted.copy(alpha = 0.38f) else muted.copy(alpha = 0.16f)),
                )
            }
        }
    }
}

/** Smoothstep, so a rise arrives instead of stopping dead. Used by the 6.3 artwork. */
private fun ease(t: Float): Float = t * t * (3f - 2f * t)

/**
 * The opening page: three panels rising out of the keys, one after another.
 *
 * 6.3 is a release about reaching more things without leaving the keyboard — a scanner, an editing
 * panel, a second action under a button — so the opening picture is the keyboard handing them up.
 * Wordless, and slow enough that all three have arrived before the eye moves to the text.
 */
@Composable
private fun TourToolRise() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "tools")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6600, easing = LinearEasing), RepeatMode.Restart),
        label = "tools-cycle",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(210.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.BottomCenter) {
            repeat(3) { index ->
                val rise = ease(((cycle - (0.08f + index * 0.17f)) / 0.24f).coerceIn(0f, 1f))
                Box(
                    modifier = Modifier
                        .offset(y = (-(16 + index * 21)).dp * rise)
                        .alpha(rise)
                        .size(width = (116 + index * 28).dp, height = 20.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(accent.copy(alpha = 0.14f + 0.09f * index)),
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        // The keys they come out of.
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            repeat(3) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(if (row == 2) 6 else 8) {
                        Box(
                            modifier = Modifier
                                .size(width = 18.dp, height = 12.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(muted.copy(alpha = 0.16f)),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A photo of printed lines being swept, and one of them lifting out of it into a field.
 *
 * The beat that matters is the last one: the text does not end up *in a picture viewer*, it ends up
 * in the field you were typing in. Bars rather than words, so no language has to be picked and
 * nothing here needs translating.
 */
@Composable
private fun TourScanText() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "scan")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
        label = "scan-cycle",
    )
    val sweep = (cycle / 0.34f).coerceIn(0f, 1f)
    val lift = ((cycle - 0.40f) / 0.24f).coerceIn(0f, 1f)
    val landed = cycle > 0.64f

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(206.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(muted.copy(alpha = 0.10f)),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repeat(4) { line ->
                    val read = sweep * 4f > line + 0.5f
                    val taken = line == 1
                    Box(
                        modifier = Modifier
                            .alpha(if (taken && lift > 0f && !landed) 1f - lift else 1f)
                            .size(width = (166 - line * 24).dp, height = 8.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(
                                when {
                                    taken && read -> accent
                                    read -> muted.copy(alpha = 0.45f)
                                    else -> muted.copy(alpha = 0.20f)
                                },
                            ),
                    )
                }
            }
            if (sweep < 1f) {
                Box(
                    modifier = Modifier
                        .offset(y = (sweep * 92f).dp)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(accent.copy(alpha = 0.75f)),
                )
            }
        }
        // The line on its way down into the field.
        Box(modifier = Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.TopCenter) {
            if (lift > 0f && !landed) {
                Box(
                    modifier = Modifier
                        .offset(y = (lift * 16f).dp)
                        .size(width = 142.dp, height = 8.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(accent),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(muted.copy(alpha = 0.10f))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (landed) {
                    Box(
                        modifier = Modifier
                            .size(width = 142.dp, height = 8.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(accent),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                }
                Box(
                    modifier = Modifier
                        .size(width = 2.dp, height = 16.dp)
                        .background(accent.copy(alpha = if (landed) 0.9f else 0.35f)),
                )
            }
        }
    }
}

/** Where the scattered keys start from, before they gather into the panel. */
private val TOUR_EDIT_SCATTER = listOf(
    Offset(-84f, -38f), Offset(60f, -44f), Offset(-18f, -50f), Offset(86f, -8f),
    Offset(-92f, 16f), Offset(32f, 34f), Offset(-44f, 44f), Offset(94f, 38f),
    Offset(6f, -20f), Offset(-64f, -6f), Offset(72f, 14f),
)

/**
 * Eleven keys scattered across the keyboard drifting together into one panel.
 *
 * This is the literal statement of the change: the cursor keys, the selection keys and the
 * clipboard keys all existed, in eleven places you had to know about. The picture is them arriving
 * in one. Four of the tiles pick up an icon once they have landed, so the panel reads as an editing
 * panel rather than as a grid of blanks.
 */
@Composable
private fun TourEditPanel() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "editing")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6800, easing = LinearEasing), RepeatMode.Restart),
        label = "editing-cycle",
    )
    val gathered = ease(((cycle - 0.08f) / 0.36f).coerceIn(0f, 1f))
    val settled = gathered > 0.88f
    val iconFade = ((gathered - 0.88f) / 0.12f).coerceIn(0f, 1f)

    Box(modifier = Modifier.size(width = 204.dp, height = 122.dp), contentAlignment = Alignment.Center) {
        // The panel they end up in, arriving behind them rather than before them.
        Box(
            modifier = Modifier
                .alpha(((gathered - 0.55f) / 0.35f).coerceIn(0f, 1f) * 0.9f)
                .size(width = 180.dp, height = 110.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(accent.copy(alpha = 0.09f)),
        )
        repeat(11) { index ->
            val from = TOUR_EDIT_SCATTER[index]
            val toX = (index % 4 - 1.5f) * 40f
            val toY = (index / 4 - 1f) * 32f
            Box(
                modifier = Modifier
                    .offset(
                        x = (from.x + (toX - from.x) * gathered).dp,
                        y = (from.y + (toY - from.y) * gathered).dp,
                    )
                    .size(width = 34.dp, height = 26.dp)
                    .clip(RoundedCornerShape(if (settled) 7.dp else 5.dp))
                    .background(if (settled) accent.copy(alpha = 0.26f) else muted.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                val icon = when (index) {
                    1 -> Icons.Filled.SelectAll
                    4 -> Icons.Filled.ContentCut
                    6 -> Icons.Filled.ContentPaste
                    9 -> Icons.Filled.Dialpad
                    else -> null
                }
                if (icon != null && iconFade > 0f) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(15.dp).alpha(iconFade),
                    )
                }
            }
        }
    }
}

/**
 * One Smartbar button held until its second action takes the same seat.
 *
 * Only five or six buttons are ever visible at once (see issue #402), which is the whole reason this
 * feature exists — so the picture has to show the row staying the same length while the button does
 * two jobs. The dot in the corner is the badge the real button wears.
 */
@Composable
private fun TourSecondAction() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "second")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart),
        label = "second-cycle",
    )
    val hold = ((cycle - 0.16f) / 0.30f).coerceIn(0f, 1f)
    val swapped = cycle > 0.50f && cycle < 0.90f

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(76.dp),
    ) {
        repeat(4) { index ->
            val target = index == 1
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(if (target) 44.dp else 34.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (target) accent.copy(alpha = 0.16f + 0.16f * hold)
                            else muted.copy(alpha = 0.12f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (target) {
                        Icon(
                            imageVector = if (swapped) Icons.Filled.ContentPaste else Icons.Filled.Mic,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(13.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(muted.copy(alpha = 0.30f)),
                        )
                    }
                }
                // Always composed, even while there is nothing to draw: this canvas is the widest
                // child of the Box, so composing it only while the finger is down would shrink the
                // Box by twelve pixels between holds and make the whole row twitch once a cycle.
                if (target) {
                    Canvas(modifier = Modifier.size(56.dp)) {
                        if (hold <= 0f || hold >= 1f) return@Canvas
                        val stroke = 3.dp.toPx()
                        drawArc(
                            color = accent,
                            startAngle = -90f,
                            sweepAngle = 360f * hold,
                            useCenter = false,
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                }
                if (target) {
                    Box(
                        modifier = Modifier
                            .offset(x = 16.dp, y = (-16).dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(accent),
                    )
                }
            }
        }
    }
}

/**
 * The floating button held, and a short menu unrolling away from it.
 *
 * Away from it, not out of it: the rows arrive bottom-first so the menu reads as growing out of the
 * button under the thumb. A hold rather than a gesture is the point of the feature (issue #408), so
 * the ring has to be visible before anything opens.
 */
@Composable
private fun TourHoldMenu() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "holdmenu")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5600, easing = LinearEasing), RepeatMode.Restart),
        label = "holdmenu-cycle",
    )
    val hold = ((cycle - 0.10f) / 0.26f).coerceIn(0f, 1f)
    val open = ((cycle - 0.38f) / 0.30f).coerceIn(0f, 1f)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(200.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.height(82.dp),
        ) {
            repeat(3) { row ->
                val step = ease((((open - (2 - row) * 0.16f)) / 0.36f).coerceIn(0f, 1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .alpha(step)
                        .offset(y = (14.dp * (1f - step)))
                        .size(width = 150.dp, height = 22.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(accent.copy(alpha = 0.70f)),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(width = (92 - row * 16).dp, height = 6.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(muted.copy(alpha = 0.40f)),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.20f + 0.18f * hold)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            // Composed unconditionally for the same reason as in [TourSecondAction]: it is the
            // widest child here, and dropping it between holds would move the button under it.
            Canvas(modifier = Modifier.size(58.dp)) {
                if (hold <= 0f || open >= 1f) return@Canvas
                val stroke = 3.dp.toPx()
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 360f * hold,
                    useCenter = false,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/**
 * A model too big for the phone, shrinking until it sits inside it.
 *
 * The catalog's problem was never that the models were bad, it was that the only ones on offer were
 * 670 MB (issue #406). So the picture is size, and nothing else: the block starts wider than the
 * phone it is supposed to live on — deliberately drawn *over* the phone rather than inside it, or
 * the overhang would be clipped away and there would be nothing to see — and ends inside it with
 * the cloud struck out, because what fits is what can work offline.
 */
@Composable
private fun TourModelFits() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "modelfit")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
        label = "modelfit-cycle",
    )
    val shrink = ease(((cycle - 0.12f) / 0.38f).coerceIn(0f, 1f))
    val fitted = cycle > 0.56f

    Box(modifier = Modifier.size(width = 196.dp, height = 128.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(width = 78.dp, height = 126.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(muted.copy(alpha = 0.14f)),
        )
        val width = 152f + (58f - 152f) * shrink
        val height = 84f + (66f - 84f) * shrink
        Box(
            modifier = Modifier
                .size(width = width.dp, height = height.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.24f + 0.16f * shrink)),
            contentAlignment = Alignment.Center,
        ) {
            if (fitted) {
                Icon(
                    imageVector = Icons.Filled.CloudOff,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    repeat(3) { line ->
                        Box(
                            modifier = Modifier
                                .size(
                                    width = (width * 0.5f - line * 10f).coerceAtLeast(14f).dp,
                                    height = 5.dp,
                                )
                                .clip(RoundedCornerShape(percent = 50))
                                .background(accent.copy(alpha = 0.55f)),
                        )
                    }
                }
            }
        }
    }
}

/** Which keys of [TourGlyphTurn] turn over, and what each one shows: one per feature page of 6.4. */
private val TOUR_TURNED_KEYS: List<Pair<Int, Any>> = listOf(
    3 to Icons.Filled.Translate,
    9 to "✓",
    12 to Icons.Filled.Dns,
    16 to Icons.Filled.RecordVoiceOver,
    19 to Icons.Filled.Lock,
)

/**
 * The opening page: a quiet keyboard on which five keys turn over, one after another.
 *
 * Each face is one of the pages that follow — translation, a symbol of one's own, the providers, the
 * command word, privacy — so the picture is a table of contents rather than decoration. Turned,
 * where 6.2's opening lit the keys and 6.3's lifted panels out of them, so the three do not read as
 * the same picture. The turn is a horizontal squeeze rather than a 3D rotation: the face swaps at
 * the narrowest point, and there is no mirrored glyph to correct on the way.
 */
@Composable
private fun TourGlyphTurn() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "turn")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart),
        label = "turn-cycle",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.width(212.dp),
    ) {
        repeat(3) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                repeat(7) { column ->
                    val index = row * 7 + column
                    val order = TOUR_TURNED_KEYS.indexOfFirst { it.first == index }
                    // 0 is the plain side up, 1 the face. Out one after another, back all together.
                    val turn = if (order < 0) {
                        0f
                    } else {
                        val out = ((cycle - (0.08f + order * 0.11f)) / 0.10f).coerceIn(0f, 1f)
                        val back = ((cycle - 0.86f) / 0.10f).coerceIn(0f, 1f)
                        (out - back).coerceIn(0f, 1f)
                    }
                    val faceUp = turn > 0.5f
                    Box(
                        modifier = Modifier
                            .graphicsLayer { scaleX = abs(cos(PI.toFloat() * turn)) }
                            .size(width = 22.dp, height = 26.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (faceUp) accent.copy(alpha = 0.24f) else muted.copy(alpha = 0.13f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (faceUp) {
                            when (val face = TOUR_TURNED_KEYS[order].second) {
                                is ImageVector -> Icon(
                                    imageVector = face,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(14.dp),
                                )
                                is String -> Text(
                                    text = face,
                                    color = accent,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .size(width = 118.dp, height = 20.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(muted.copy(alpha = 0.13f)),
        )
    }
}

/** Widths of the three typed pieces and of their translations — different, as two languages are. */
private val TOUR_TYPED: List<Float> = listOf(34f, 50f, 28f)
private val TOUR_TRANSLATED: List<Float> = listOf(42f, 30f, 46f)

/**
 * Typing into the keyboard's own field above the Smartbar while the app's field fills with the
 * translation, a beat behind; then Enter, and the finished message goes out.
 *
 * The last beat is the one people ask about: Enter writes the translation before it sends, so a
 * message never leaves half translated. Bars rather than words, because any pair of real words would
 * be the wrong pair for most readers.
 */
@Composable
private fun TourTranslateBar() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "translate")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7200, easing = LinearEasing), RepeatMode.Restart),
        label = "translate-cycle",
    )
    fun grow(start: Float) = ease(((cycle - start) / 0.09f).coerceIn(0f, 1f))
    val enter = cycle in 0.58f..0.65f
    val sent = cycle > 0.64f
    val bubble = ease(((cycle - 0.64f) / 0.10f).coerceIn(0f, 1f)) * (1f - ((cycle - 0.92f) / 0.08f).coerceIn(0f, 1f))

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(214.dp)) {
        // The conversation the message goes into.
        Box(modifier = Modifier.fillMaxWidth().height(30.dp), contentAlignment = Alignment.CenterEnd) {
            Row(
                modifier = Modifier
                    .alpha(bubble)
                    .offset(y = 8.dp * (1f - bubble))
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.22f))
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TOUR_TRANSLATED.forEach { width ->
                    Box(
                        modifier = Modifier
                            .size(width = (width * 0.8f).dp, height = 6.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(accent),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        // The app's field, where the translation stands while it is being written.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(muted.copy(alpha = 0.10f))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!sent) {
                TOUR_TRANSLATED.forEachIndexed { index, width ->
                    val step = grow(0.10f + index * 0.15f)
                    if (step > 0f) {
                        Box(
                            modifier = Modifier
                                .size(width = (width * step).dp, height = 7.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(muted.copy(alpha = 0.50f)),
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // The keyboard's own field above the Smartbar, where the typing happens.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(accent.copy(alpha = 0.08f))
                .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Translate,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(7.dp))
            if (!sent) {
                TOUR_TYPED.forEachIndexed { index, width ->
                    val step = grow(0.03f + index * 0.15f)
                    if (step > 0f) {
                        Box(
                            modifier = Modifier
                                .size(width = (width * step).dp, height = 7.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(accent),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(width = 2.dp, height = 15.dp)
                    .background(accent.copy(alpha = 0.8f)),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Two rows of keys, the last one Enter, lighting when the message goes.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            repeat(2) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(9) { column ->
                        val isEnter = row == 1 && column == 8
                        Box(
                            modifier = Modifier
                                .size(width = 18.dp, height = 12.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    when {
                                        isEnter && enter -> accent
                                        isEnter -> accent.copy(alpha = 0.30f)
                                        else -> muted.copy(alpha = 0.16f)
                                    },
                                ),
                        )
                    }
                }
            }
        }
    }
}

/** The symbol page as the artwork draws it: three rows of seven, the bottom one holding the key that is edited. */
private val TOUR_SYMBOL_ROWS: List<String> = listOf("@#€%&-+", "()*\"':;", "!?,./=~")

/** The edited key's long presses; the last is dragged to the front. */
private val TOUR_SYMBOL_POPUP: List<String> = listOf("✔", "☑", "✗")

/**
 * A key of the symbol page taking a symbol of the user's choosing, its long presses opening above it
 * and the last of them being dragged to the front — then the key settling with the mark the editor
 * puts on a key that has been changed.
 *
 * That is the whole editor in one beat: tap a key, set what it types, order what its long press
 * offers. Symbols are the one kind of text a picture here may carry, since they read the same in
 * every language.
 */
@Composable
private fun TourSymbolEdit() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val transition = rememberInfiniteTransition(label = "symbols")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7400, easing = LinearEasing), RepeatMode.Restart),
        label = "symbols-cycle",
    )
    val reset = ((cycle - 0.92f) / 0.08f).coerceIn(0f, 1f)
    val lift = ease(((cycle - 0.08f) / 0.08f).coerceIn(0f, 1f)) * (1f - ease(((cycle - 0.72f) / 0.08f).coerceIn(0f, 1f)))
    val newSymbol = ((cycle - 0.18f) / 0.08f).coerceIn(0f, 1f) * (1f - reset)
    val popup = ease(((cycle - 0.28f) / 0.10f).coerceIn(0f, 1f)) * (1f - ease(((cycle - 0.68f) / 0.08f).coerceIn(0f, 1f)))
    val move = ease(((cycle - 0.44f) / 0.16f).coerceIn(0f, 1f))
    val changed = ((cycle - 0.76f) / 0.06f).coerceIn(0f, 1f) * (1f - reset)
    val targetRow = 2
    val targetColumn = 3

    Box(modifier = Modifier.size(width = 204.dp, height = 100.dp)) {
        TOUR_SYMBOL_ROWS.forEachIndexed { row, keys ->
            keys.forEachIndexed { column, symbol ->
                val target = row == targetRow && column == targetColumn
                Box(
                    modifier = Modifier
                        .offset(
                            x = (column * 30).dp,
                            y = (4 + row * 32).dp - (if (target) 4.dp * lift else 0.dp),
                        )
                        .size(width = 24.dp, height = 26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (target) accent.copy(alpha = 0.14f + 0.18f * max(lift, changed))
                            else muted.copy(alpha = 0.12f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (target) {
                        Text(
                            text = symbol.toString(),
                            color = muted,
                            fontSize = 13.sp,
                            modifier = Modifier.alpha(1f - newSymbol),
                        )
                        Text(
                            text = "✓",
                            color = accent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.alpha(newSymbol),
                        )
                    } else {
                        Text(text = symbol.toString(), color = muted.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                }
            }
        }
        // The mark the editor's page preview puts on a key the user has changed.
        Box(
            modifier = Modifier
                .offset(x = (targetColumn * 30 + 19).dp, y = (4 + targetRow * 32 - 3).dp)
                .alpha(changed)
                .size(7.dp)
                .clip(CircleShape)
                .background(accent),
        )
        // The long-press popup, centred over the key: three cells of 28 with gaps of 2 inside 92.
        if (popup > 0f) {
            Box(
                modifier = Modifier
                    .offset(
                        x = (targetColumn * 30 + 12 - 46).dp,
                        y = (4 + targetRow * 32 - 42).dp + 6.dp * (1f - popup),
                    )
                    .alpha(popup)
                    .size(width = 92.dp, height = 34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(surface)
                    .background(accent.copy(alpha = 0.16f)),
            ) {
                TOUR_SYMBOL_POPUP.forEachIndexed { index, symbol ->
                    val dragged = index == TOUR_SYMBOL_POPUP.lastIndex
                    // The dragged one travels from the last slot to the first; the others step right.
                    val slot = if (dragged) 2f - 2f * move else index + move
                    val carried = if (dragged) sin(PI.toFloat() * move) else 0f
                    Box(
                        modifier = Modifier
                            .offset(x = (2f + slot * 30f).dp, y = 3.dp - 3.dp * carried)
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (dragged) accent.copy(alpha = 0.10f + 0.25f * carried) else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = symbol,
                            color = if (dragged) accent else MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

/** One row of the providers list in [TourProviderArrive]: a mark and two bars standing for its name and models. */
@Composable
private fun TourProviderRow(icon: ImageVector, name: Float, models: Float) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(width = name.dp, height = 7.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(muted.copy(alpha = 0.40f)),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(width = models.dp, height = 7.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(muted.copy(alpha = 0.20f)),
        )
    }
}

/**
 * The providers screen as it is now: the user's own two rows and the add row under them — then,
 * behind that row, the three providers this release brings.
 *
 * Their real marks rather than bars, because a mark is what people recognise a provider by. The
 * on-device row is one of the two on purpose: it is where the Parakeet swap the text describes
 * happens, even though the picture leaves it alone.
 */
@Composable
private fun TourProviderArrive() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "providers")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart),
        label = "providers-cycle",
    )
    val pressed = cycle in 0.20f..0.32f
    val leave = ((cycle - 0.92f) / 0.08f).coerceIn(0f, 1f)
    val marks = listOf(R.drawable.ic_provider_xai, R.drawable.ic_provider_scaleway, R.drawable.ic_provider_ovhcloud)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(210.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(muted.copy(alpha = 0.08f))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
                TourProviderRow(Icons.Filled.PhoneAndroid, name = 74f, models = 46f)
            }
            Box(modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
                TourProviderRow(ImageVector.vectorResource(R.drawable.ic_provider_openai), name = 58f, models = 62f)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (pressed) accent.copy(alpha = 0.16f) else Color.Transparent)
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(width = 84.dp, height = 7.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(accent.copy(alpha = 0.55f)),
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.height(54.dp),
        ) {
            marks.forEachIndexed { index, mark ->
                val arrive = ease(((cycle - (0.36f + index * 0.10f)) / 0.12f).coerceIn(0f, 1f)) * (1f - leave)
                Box(
                    modifier = Modifier
                        .offset(y = 10.dp * (1f - arrive))
                        .alpha(arrive)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(mark),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/**
 * Text with part of it selected, and a recording below whose first word lights up — the command
 * word — after which the selected part is rewritten.
 *
 * The order is the feature: the word is heard first, and only then does anything happen to the text.
 * The recording is drawn from the same bar profile as the 6.0 waveform, so it reads as speech.
 */
@Composable
private fun TourCommandWord() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "command")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7200, easing = LinearEasing), RepeatMode.Restart),
        label = "command-cycle",
    )
    val reset = ((cycle - 0.92f) / 0.08f).coerceIn(0f, 1f)
    val recorded = (cycle / 0.34f).coerceIn(0f, 1f)
    val word = ease(((cycle - 0.38f) / 0.08f).coerceIn(0f, 1f)) * (1f - reset)
    val rewrite = ease(((cycle - 0.54f) / 0.14f).coerceIn(0f, 1f)) * (1f - reset)
    val wordBars = 5

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(212.dp)) {
        // The text being worked on, its middle part selected.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(muted.copy(alpha = 0.10f))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 7.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(muted.copy(alpha = 0.38f)),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(accent.copy(alpha = 0.20f))
                        .padding(horizontal = 4.dp, vertical = 5.dp),
                ) {
                    // What was selected, and what it is rewritten into: a different length, in the accent.
                    Box(
                        modifier = Modifier
                            .alpha(1f - rewrite)
                            .size(width = 84.dp, height = 7.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(muted.copy(alpha = 0.55f)),
                    )
                    Box(
                        modifier = Modifier
                            .alpha(rewrite)
                            .size(width = 108.dp, height = 7.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(accent),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(width = 124.dp, height = 7.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(muted.copy(alpha = 0.38f)),
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        // The dictation, whose first word is the command word.
        Canvas(modifier = Modifier.fillMaxWidth().height(36.dp)) {
            val count = 24
            val gap = size.width / count
            val barWidth = max(2f, gap * 0.5f)
            if (word > 0f) {
                drawRoundRect(
                    color = accent.copy(alpha = 0.18f * word),
                    topLeft = Offset(0f, 0f),
                    size = Size(gap * wordBars, size.height),
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            }
            for (i in 0 until count) {
                if (i.toFloat() / count > recorded) break
                val height = size.height * 0.80f * TOUR_WAVE[i]
                val color = if (i < wordBars) lerp(muted.copy(alpha = 0.45f), accent, word) else muted.copy(alpha = 0.45f)
                drawRoundRect(
                    color = color.copy(alpha = color.alpha * (1f - reset)),
                    topLeft = Offset(i * gap + (gap - barWidth) / 2f, (size.height - height) / 2f),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
        }
    }
}

/**
 * A dictation into a password field, and the history beneath it that it never reaches.
 *
 * The audit's promise is an absence, which is hard to draw: so the dictation is shown setting off
 * towards the history and stopping at a lock, while the history keeps the two entries it had.
 */
@Composable
private fun TourPrivateField() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val transition = rememberInfiniteTransition(label = "private")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6800, easing = LinearEasing), RepeatMode.Restart),
        label = "private-cycle",
    )
    val reset = ((cycle - 0.92f) / 0.08f).coerceIn(0f, 1f)
    val dots = if (cycle < 0.06f) 0 else (((cycle - 0.06f) / 0.30f) * 6f).toInt().coerceIn(0, 6)
    val fall = ((cycle - 0.42f) / 0.18f).coerceIn(0f, 1f)
    val locked = ease(((cycle - 0.54f) / 0.10f).coerceIn(0f, 1f)) * (1f - reset)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(210.dp)) {
        // The password field being dictated into.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(muted.copy(alpha = 0.10f))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = accent.copy(alpha = if (cycle < 0.38f) 0.9f else 0.35f),
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            repeat(6) { index ->
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(ink.copy(alpha = if (index < dots) 0.70f * (1f - reset) else 0f)),
                )
            }
        }
        // The dictation on its way down to the history, stopped at the lock.
        Box(modifier = Modifier.fillMaxWidth().height(50.dp), contentAlignment = Alignment.TopCenter) {
            if (fall > 0f && fall < 1f) {
                Box(
                    modifier = Modifier
                        .offset(y = (4f + 24f * fall).dp)
                        .alpha(1f - ((fall - 0.55f) / 0.45f).coerceIn(0f, 1f))
                        .size(width = 64.dp, height = 7.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(accent),
                )
            }
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .offset(y = 25.dp)
                    .size(18.dp)
                    .alpha(locked),
            )
        }
        // The history, which keeps the two entries it had.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(muted.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = null,
                    tint = muted.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(width = 54.dp, height = 7.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(muted.copy(alpha = 0.32f)),
                )
            }
            repeat(2) { index ->
                Box(
                    modifier = Modifier
                        .size(width = (138 - index * 34).dp, height = 7.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(muted.copy(alpha = 0.22f)),
                )
            }
        }
    }
}

/** Between two points, [t] of the way. */
private fun between(from: Offset, to: Offset, t: Float): Offset =
    Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)

/**
 * The opening page of 6.5: the mic, and around it the marks of the pages that follow arriving one by one —
 * the offline model, the detour, Next, the providers, the small things, the watch.
 *
 * A table of contents again, as on every opening page since 6.2, but laid around the thing all of it
 * serves rather than on keys: 6.2 lit keys, 6.3 lifted panels out of them, 6.4 turned them over.
 */
@Composable
private fun TourIconRing() {
    val accent = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "ring")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart),
        label = "ring-cycle",
    )
    val marks = listOf(
        Icons.Filled.CloudOff,
        Icons.AutoMirrored.Filled.AltRoute,
        ImageVector.vectorResource(R.drawable.ic_dictate_overlay_next),
        Icons.Filled.Dns,
        Icons.Filled.Bolt,
        Icons.Filled.Watch,
    )
    val back = ((cycle - 0.88f) / 0.10f).coerceIn(0f, 1f)
    val breath = 0.5f + 0.5f * sin(cycle * 6f * PI.toFloat())

    Box(modifier = Modifier.size(width = 212.dp, height = 150.dp), contentAlignment = Alignment.Center) {
        marks.forEachIndexed { index, mark ->
            // From the top, clockwise, on an ellipse that suits the wide frame.
            val angle = -PI / 2 + index * 2 * PI / marks.size
            val arrive = ease(((cycle - (0.08f + index * 0.10f)) / 0.10f).coerceIn(0f, 1f)) * (1f - back)
            Box(
                modifier = Modifier
                    .offset(x = (cos(angle) * 78).toFloat().dp, y = (sin(angle) * 54).toFloat().dp)
                    .graphicsLayer {
                        scaleX = 0.6f + 0.4f * arrive
                        scaleY = 0.6f + 0.4f * arrive
                        alpha = arrive
                    }
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = mark, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
        }
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.18f + 0.10f * breath)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = Icons.Filled.Mic, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
        }
    }
}

/**
 * The on-device list with Fluid Speech Pro arriving above Parakeet Ultra and downloading, while Ultra
 * stays where it was.
 *
 * The page's instruction is the picture: download the new one in that list, the old one keeps working.
 * The names are the models' own and stay as they are in every language.
 */
@Composable
private fun TourModelDownload() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "download")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6500, easing = LinearEasing), RepeatMode.Restart),
        label = "download-cycle",
    )
    val reset = ((cycle - 0.90f) / 0.10f).coerceIn(0f, 1f)
    val arrive = ease((cycle / 0.14f).coerceIn(0f, 1f)) * (1f - reset)
    val progress = ((cycle - 0.22f) / 0.42f).coerceIn(0f, 1f)
    val done = cycle > 0.66f

    Column(
        modifier = Modifier
            .width(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(muted.copy(alpha = 0.08f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Always composed, only faded: a row that came and went would move Ultra up and down.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(arrive)
                .offset(y = (-8).dp * (1f - arrive))
                .clip(RoundedCornerShape(8.dp))
                .background(accent.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Filled.PhoneAndroid, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Fluid Speech Pro",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
                Box(
                    modifier = Modifier
                        .size(width = 70.dp, height = 5.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(accent.copy(alpha = 0.30f)),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Box(modifier = Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(26.dp)) {
                    val stroke = 2.5.dp.toPx()
                    drawArc(
                        color = accent.copy(alpha = 0.20f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = stroke),
                    )
                    drawArc(
                        color = accent,
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
                Icon(
                    imageVector = if (done) Icons.Filled.Check else Icons.Filled.ArrowDownward,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Filled.PhoneAndroid, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Parakeet Ultra", style = MaterialTheme.typography.labelLarge, color = muted)
                Box(
                    modifier = Modifier
                        .size(width = 62.dp, height = 5.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(muted.copy(alpha = 0.22f)),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Box(modifier = Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                Icon(imageVector = Icons.Filled.Check, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * A dictation setting off for the home server, finding it gone, and taking the detour to the second
 * provider — then the text arriving under it.
 *
 * The detour mark appears beside the mic at the moment of the switch, as the Smartbar shows it. The slow
 * and the failing server are not drawn: they keep the dictation, and a picture cannot say "not this case".
 */
@Composable
private fun TourFallbackDetour() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "detour")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6800, easing = LinearEasing), RepeatMode.Restart),
        label = "detour-cycle",
    )
    val reset = ((cycle - 0.90f) / 0.10f).coerceIn(0f, 1f)
    val toServer = ((cycle - 0.06f) / 0.20f).coerceIn(0f, 1f)
    val down = ((cycle - 0.26f) / 0.06f).coerceIn(0f, 1f) * (1f - reset)
    val badge = ease(((cycle - 0.34f) / 0.06f).coerceIn(0f, 1f)) * (1f - reset)
    val toCloud = ((cycle - 0.40f) / 0.20f).coerceIn(0f, 1f)
    val arrived = ease(((cycle - 0.60f) / 0.06f).coerceIn(0f, 1f)) * (1f - reset)
    val written = ((cycle - 0.66f) / 0.18f).coerceIn(0f, 1f) * (1f - reset)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(212.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(116.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val mic = Offset(24.dp.toPx(), size.height / 2f)
                val server = Offset(size.width - 20.dp.toPx(), 20.dp.toPx())
                val cloud = Offset(size.width - 20.dp.toPx(), size.height - 20.dp.toPx())
                // Each path from rim to rim, so it does not show through the translucent circles.
                fun path(to: Offset, color: Color) {
                    val dx = to.x - mic.x
                    val dy = to.y - mic.y
                    val length = kotlin.math.sqrt(dx * dx + dy * dy)
                    val start = between(mic, to, 26.dp.toPx() / length)
                    val end = between(mic, to, 1f - 22.dp.toPx() / length)
                    drawLine(
                        color = color,
                        start = start,
                        end = end,
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                    )
                }
                path(server, muted.copy(alpha = 0.30f))
                path(cloud, lerp(muted.copy(alpha = 0.30f), accent.copy(alpha = 0.70f), badge))
                val dot = 5.dp.toPx()
                if (toServer in 0.001f..0.999f) {
                    drawCircle(color = accent, radius = dot, center = between(mic, server, toServer * 0.80f))
                }
                if (toCloud in 0.001f..0.999f) {
                    drawCircle(color = accent, radius = dot, center = between(mic, cloud, toCloud * 0.85f))
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = Icons.Filled.Mic, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
            }
            // The detour mark, at the mic's shoulder.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = 34.dp, y = (-22).dp)
                    .alpha(badge)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.AltRoute,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
            // The home server, struck through once it does not answer.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(lerp(accent.copy(alpha = 0.16f), muted.copy(alpha = 0.10f), down)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Dns,
                    contentDescription = null,
                    tint = lerp(accent, muted.copy(alpha = 0.45f), down),
                    modifier = Modifier.size(20.dp),
                )
                Canvas(modifier = Modifier.size(26.dp)) {
                    if (down > 0f) {
                        drawLine(
                            color = muted,
                            start = Offset(0f, 0f),
                            end = Offset(size.width * down, size.height * down),
                            strokeWidth = 2.5.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
            // The second provider, lit once the dictation reaches it.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(lerp(muted.copy(alpha = 0.10f), accent.copy(alpha = 0.22f), arrived)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Cloud,
                    contentDescription = null,
                    tint = lerp(muted.copy(alpha = 0.55f), accent, arrived),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        // The text the second provider sent back, written into the field.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.height(8.dp)) {
            listOf(46f, 64f, 38f).forEachIndexed { index, width ->
                val grow = ((written - index * 0.28f) / 0.30f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .size(width = (width * grow).dp, height = 7.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(accent.copy(alpha = 0.70f)),
                )
            }
        }
    }
}

/**
 * The floating button recording, with Next beside it in the pause's seat: each press cuts a part, and
 * the part appears in the caption above while the speaking goes on. At the stop the whole text is
 * taken, which is when the caption turns the accent colour.
 */
@Composable
private fun TourBubbleSegments() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val transition = rememberInfiniteTransition(label = "segments")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7200, easing = LinearEasing), RepeatMode.Restart),
        label = "segments-cycle",
    )
    val reset = ((cycle - 0.90f) / 0.10f).coerceIn(0f, 1f)
    val cuts = listOf(0.20f, 0.40f, 0.60f)
    val stopped = cycle > 0.72f
    val taken = ease(((cycle - 0.72f) / 0.08f).coerceIn(0f, 1f)) * (1f - reset)
    // A press is a quick shrink and an overshoot, as pulseNext() does it on the real button.
    val press = cuts.maxOf { at ->
        val t = ((cycle - at) / 0.06f).coerceIn(0f, 1f)
        if (t <= 0f || t >= 1f) 0f else sin(t * PI.toFloat())
    }
    val breath = if (stopped) 0f else 0.5f + 0.5f * sin(cycle * 10f * PI.toFloat())

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(212.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(muted.copy(alpha = 0.10f))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(150f, 112f, 168f).forEachIndexed { index, width ->
                val shown = ease(((cycle - cuts[index] - 0.04f) / 0.08f).coerceIn(0f, 1f)) * (1f - reset)
                Box(
                    modifier = Modifier
                        .alpha(shown)
                        .size(width = width.dp, height = 7.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(lerp(muted.copy(alpha = 0.40f), accent, taken)),
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = 1f - 0.18f * press
                        scaleY = 1f - 0.18f * press
                        alpha = 1f - taken
                    }
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(lerp(muted.copy(alpha = 0.16f), accent.copy(alpha = 0.30f), press)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_dictate_overlay_next),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Box(modifier = Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                // The recording halo, always composed so the row never shifts.
                Canvas(modifier = Modifier.size(60.dp)) {
                    if (breath > 0f) {
                        drawCircle(color = accent.copy(alpha = 0.10f + 0.10f * breath), radius = size.minDimension / 2f * (0.84f + 0.16f * breath))
                    }
                }
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (stopped && reset < 1f) Icons.Filled.Check else ImageVector.vectorResource(R.drawable.ic_dictate_overlay_mic),
                        contentDescription = null,
                        tint = onAccent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/** The providers on [TourModelTurn], each with the model it had and the one it has now. */
private val TOUR_MODEL_TURNS: List<Triple<Int, String, String>> = listOf(
    Triple(R.drawable.ic_provider_elevenlabs, "scribe_v2", "scribe_v3"),
    Triple(R.drawable.ic_provider_assemblyai, "universal-3-5-pro", "dictation"),
)

/**
 * Two providers whose model chip turns over to the new one — Scribe v3 and AssemblyAI's Dictation —
 * and then a few custom words rising into the list, since they now reach every provider.
 *
 * The chips carry the real model ids, which are what the model field shows and stay the same in every
 * language. The words are bars: any real word would be the wrong word for most readers.
 */
@Composable
private fun TourModelTurn() {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "modelturn")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart),
        label = "modelturn-cycle",
    )
    val back = ((cycle - 0.88f) / 0.10f).coerceIn(0f, 1f)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(220.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(muted.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TOUR_MODEL_TURNS.forEachIndexed { index, (mark, before, after) ->
                val out = ((cycle - (0.12f + index * 0.18f)) / 0.10f).coerceIn(0f, 1f)
                val turn = (out - back).coerceIn(0f, 1f)
                val faceUp = turn > 0.5f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = ImageVector.vectorResource(mark),
                        contentDescription = null,
                        tint = muted,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .size(width = (48 - index * 10).dp, height = 7.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(muted.copy(alpha = 0.40f)),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .graphicsLayer { scaleX = abs(cos(PI.toFloat() * turn)) }
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (faceUp) accent.copy(alpha = 0.20f) else muted.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = if (faceUp) after else before,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (faceUp) FontWeight.Bold else FontWeight.Normal,
                            color = if (faceUp) accent else muted,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        // The custom words, rising into the list.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(30.dp)) {
            listOf(38f, 52f, 30f).forEachIndexed { index, width ->
                val rise = ease(((cycle - (0.54f + index * 0.08f)) / 0.12f).coerceIn(0f, 1f)) * (1f - back)
                Box(
                    modifier = Modifier
                        .offset(y = 12.dp * (1f - rise))
                        .alpha(rise)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(accent.copy(alpha = 0.16f))
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = width.dp, height = 6.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(accent.copy(alpha = 0.70f)),
                    )
                }
            }
        }
    }
}

/**
 * SayOVA's store banner (6.5). The one picture in the tours that is not drawn here: the page is about
 * another app, and its own picture is what people will meet in the store. English, like the banner on
 * the More apps page — Jannis's rule for SayOVA's pictures; the text around it is translated.
 */
@Composable
private fun TourSayovaBanner() {
    Image(
        painter = painterResource(R.drawable.sayova_feature_graphic),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1024f / 500f)
            .clip(RoundedCornerShape(16.dp)),
    )
}

/**
 * The lists under the "also" page's text (6.4): a card per area, one line per change.
 *
 * Until 6.3 the small things were one paragraph, which had to pick its handful and leave the rest
 * out. This time all of them are there, for whoever wants to read them and easy to skip for whoever
 * does not — so they are skimmable lines, left-aligned under the centred text, since a centred list
 * would have no edge to read down.
 */
@Composable
private fun TourSections(sections: List<TourListSection>) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (section in sections) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.06f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringRes(section.heading),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
                for (item in section.items) {
                    Row {
                        Box(
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(accent.copy(alpha = 0.7f)),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringRes(item),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageContent(page: WhatsNewPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // A page has to survive a long body on a short screen, and both halves of that happen:
            // German and French run about 15 % longer than the English these texts are written in,
            // the font scale can be turned up, and the pager only ever gets what the header, the
            // dots and the two buttons leave behind. Until now the overflow was simply cut off —
            // the Column centred its children and clipped whatever did not fit, with nothing to
            // scroll and no sign that there was more.
            //
            // [fillMaxSize] stays *outside* the scroll on purpose. The scroll modifier lifts only
            // the maximum height to infinity and passes the minimum through, so a page whose
            // content fits is still measured against the full viewport and still centres exactly as
            // it did before. Only a page that overruns grows past the viewport, and that is the one
            // that becomes scrollable.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 8.dp),
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
                TourArt.LEARN_LADDER -> TourLearnLadder()
                TourArt.GLASS_KEYS -> TourGlassKeys()
                TourArt.SPLIT_ROW -> TourSplitRow()
                TourArt.EMOJI_RIDE -> TourEmojiRide()
                TourArt.LAYER_PEEK -> TourLayerPeek()
                TourArt.KEYBOARD_WAKE -> TourKeyboardWake()
                TourArt.INLINE_ANSWER -> TourInlineAnswer()
                TourArt.SELECTION_COUNT -> TourSelectionCount()
                TourArt.CLIP_SEARCH -> TourClipSearch()
                TourArt.FOLDER_DROP -> TourFolderDrop()
                TourArt.SMALL_THINGS -> TourSmallThings()
                TourArt.DONE_RING -> TourDoneRing()
                TourArt.TOOL_RISE -> TourToolRise()
                TourArt.SCAN_TEXT -> TourScanText()
                TourArt.EDIT_PANEL -> TourEditPanel()
                TourArt.SECOND_ACTION -> TourSecondAction()
                TourArt.HOLD_MENU -> TourHoldMenu()
                TourArt.MODEL_FITS -> TourModelFits()
                TourArt.GLYPH_TURN -> TourGlyphTurn()
                TourArt.TRANSLATE_BAR -> TourTranslateBar()
                TourArt.SYMBOL_EDIT -> TourSymbolEdit()
                TourArt.PROVIDER_ARRIVE -> TourProviderArrive()
                TourArt.COMMAND_WORD -> TourCommandWord()
                TourArt.PRIVATE_FIELD -> TourPrivateField()
                TourArt.ICON_RING -> TourIconRing()
                TourArt.MODEL_DOWNLOAD -> TourModelDownload()
                TourArt.FALLBACK_DETOUR -> TourFallbackDetour()
                TourArt.BUBBLE_SEGMENTS -> TourBubbleSegments()
                TourArt.MODEL_TURN -> TourModelTurn()
                TourArt.SAYOVA_BANNER -> TourSayovaBanner()
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
        if (page.sections.isNotEmpty()) {
            TourSections(page.sections)
        }
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
