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

package dev.patrickgold.florisboard.ime.clipboard

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.provider.MediaStore
import android.util.Size
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FilterListOff
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ToggleOff
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.ContentPasteGo
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.dictate.ui.MediaAction
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard.PanelHeaderButton
import dev.patrickgold.florisboard.ime.media.KeyboardLikeButton
import dev.patrickgold.florisboard.ime.smartbar.AnimationDuration
import dev.patrickgold.florisboard.ime.smartbar.VerticalEnterTransition
import dev.patrickgold.florisboard.ime.smartbar.VerticalExitTransition
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.lib.observeAsTransformingState
import dev.patrickgold.florisboard.lib.util.NetworkUtils
import dev.patrickgold.jetpref.datastore.model.collectAsState
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.AndroidKeyguardManager
import org.florisboard.lib.android.AndroidVersion
import org.florisboard.lib.android.showShortToastSync
import org.florisboard.lib.android.systemService
import org.florisboard.lib.compose.panelScrollbar
import org.florisboard.lib.compose.LocalLocalizedDateTimeFormatter
import org.florisboard.lib.compose.autoMirrorForRtl
import org.florisboard.lib.compose.florisHorizontalScroll
import org.florisboard.lib.compose.florisVerticalScroll
import org.florisboard.lib.compose.onAccent
import org.florisboard.lib.compose.rippleClickable
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.SnyggQueryAttributes
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggButton
import org.florisboard.lib.snygg.ui.SnyggChip
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

private val ItemWidth = 200.dp
private val DialogWidth = 240.dp

const val CLIPBOARD_HISTORY_NUM_GRID_COLUMNS_AUTO: Int = 0

@Composable
fun ClipboardInputLayout(
    modifier: Modifier = Modifier,
) {
    val prefs by FlorisPreferenceStore
    val accentColor by prefs.theme.accentColor.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager by context.clipboardManager()
    val keyboardManager by context.keyboardManager()
    val inputFeedbackController = LocalInputFeedbackController.current
    val androidKeyguardManager = remember { context.systemService(AndroidKeyguardManager::class) }

    val deviceLocked = androidKeyguardManager.let { it.isDeviceLocked || it.isKeyguardLocked }
    val historyEnabled by prefs.clipboard.historyEnabled.collectAsState()
    val pinnedOnTop by prefs.clipboard.historyPinnedOnTop.collectAsState()

    var isFilterRowShown by remember { mutableStateOf(false) }
    val activeFilterTypes = remember { mutableStateSetOf<ItemType>() }

    // Rebuilt rather than taken from the manager as it is: its copy splits recent from other against the
    // clock of the last database change, see nextRecentExpiryMs. Opening the panel and every expiry below
    // move this clock, and with it the split.
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val unfilteredHistory by clipboardManager.historyFlow.collectAsState()
    val filteredHistory = remember(unfilteredHistory, activeFilterTypes.toSet(), clock) {
        if (activeFilterTypes.isEmpty()) {
            ClipboardHistory(unfilteredHistory.all)
        } else {
            unfilteredHistory.all
                .filter { activeFilterTypes.contains(it.type) }
                .let { ClipboardHistory(it) }
        }
    }
    LaunchedEffect(filteredHistory.recent, clock) {
        val expiry = filteredHistory.nextRecentExpiryMs() ?: return@LaunchedEffect
        delay((expiry - System.currentTimeMillis()).coerceAtLeast(0L))
        clock = System.currentTimeMillis()
    }
    // Keyed on the clock as well: ClipboardHistory compares by its items alone, so a copy rebuilt after an
    // expiry is "equal" to the one before it while its recent and other lists are not.
    val sections = remember(filteredHistory, clock, pinnedOnTop) {
        filteredHistory.sections(pinnedOnTop)
    }

    val gridState = rememberLazyStaggeredGridState()
    var popupItem by remember(filteredHistory) { mutableStateOf<ClipboardItem?>(null) }
    var showClearAllHistory by remember { mutableStateOf(false) }

    fun isPopupSurfaceActive() = popupItem != null || showClearAllHistory

    LaunchedEffect(isFilterRowShown) {
        delay(AnimationDuration.toLong())
        if (!isFilterRowShown) {
            activeFilterTypes.clear()
        }
    }

    LaunchedEffect(activeFilterTypes.toSet()) {
        gridState.scrollToItem(0)
    }

    @Composable
    fun HeaderRow() {
        SnyggRow(FlorisImeUi.ClipboardHeader.elementName,
            modifier = Modifier
                .fillMaxWidth()
                .height(FlorisImeSizing.smartbarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val sizeModifier = Modifier
                .sizeIn(maxHeight = FlorisImeSizing.smartbarHeight)
                .aspectRatio(1f)
            PanelHeaderButton(
                onClick = { keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT },
                modifier = sizeModifier,
            ) {
                SnyggIcon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                )
            }
            // The tabs take the title's place rather than a row of their own (issue #395): the free width
            // beside the buttons was unused, and the panel's height is fixed, so a new row would only
            // have been paid for in cards.
            if (!deviceLocked && historyEnabled && sections.size > 1) {
                ClipboardSectionTabs(
                    sections = sections,
                    gridState = gridState,
                    accent = accentColor,
                    enabled = !isPopupSurfaceActive(),
                    modifier = Modifier.weight(1f),
                )
            } else {
                SnyggText(
                    elementName = FlorisImeUi.ClipboardHeaderText.elementName,
                    modifier = Modifier.weight(1f),
                    text = stringRes(R.string.clipboard__header_title),
                )
            }
            PanelHeaderButton(
                onClick = { scope.launch { prefs.clipboard.historyEnabled.set(!historyEnabled) } },
                modifier = sizeModifier.autoMirrorForRtl(),
                enabled = !deviceLocked && !isPopupSurfaceActive(),
            ) {
                SnyggIcon(
                    imageVector = if (historyEnabled) {
                        Icons.Default.ToggleOn
                    } else {
                        Icons.Default.ToggleOff
                    },
                )
            }
            PanelHeaderButton(
                onClick = { showClearAllHistory = true },
                modifier = sizeModifier.autoMirrorForRtl(),
                enabled = !deviceLocked && historyEnabled && filteredHistory.all.isNotEmpty() && !isPopupSurfaceActive(),
            ) {
                SnyggIcon(
                    imageVector = Icons.Default.DeleteSweep,
                )
            }
            // Searching leaves this panel behind — the keyboard has to come back to type the query with
            // (issue #333) — so it sits next to the filter rather than inside the grid, and under the
            // same conditions: a locked device or a switched-off history has nothing to search.
            PanelHeaderButton(
                onClick = { keyboardManager.activateClipboardSearch() },
                modifier = sizeModifier,
                enabled = !deviceLocked && historyEnabled && unfilteredHistory.all.isNotEmpty() && !isPopupSurfaceActive(),
            ) {
                SnyggIcon(
                    imageVector = Icons.Default.Search,
                )
            }
            PanelHeaderButton(
                onClick = { isFilterRowShown = !isFilterRowShown },
                modifier = sizeModifier,
                enabled = !deviceLocked && historyEnabled && unfilteredHistory.all.isNotEmpty() && !isPopupSurfaceActive(),
            ) {
                SnyggIcon(
                    imageVector = if (!isFilterRowShown) {
                        Icons.Default.FilterList
                    } else {
                        Icons.Default.FilterListOff
                    },
                )
            }
            KeyboardLikeButton(
                modifier = sizeModifier,
                inputEventDispatcher = keyboardManager.inputEventDispatcher,
                keyData = TextKeyData.DELETE,
                elementName = FlorisImeUi.ClipboardHeaderButton.elementName,
            ) {
                SnyggIcon(imageVector = Icons.AutoMirrored.Outlined.Backspace)
            }
        }
    }

    @Composable
    fun ClipItemView(
        elementName: String,
        item: ClipboardItem,
        contentScrollInsteadOfClip: Boolean,
        modifier: Modifier = Modifier,
    ) {
        val attributes = remember(item) {
            mapOf("type" to item.type.toString().lowercase())
        }
        SnyggBox(
            elementName = elementName,
            attributes = attributes,
            modifier = modifier.fillMaxWidth(),
            clickAndSemanticsModifier = Modifier.combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                enabled = popupItem == null,
                onLongClick = {
                    inputFeedbackController.keyLongPress(TextKeyData.UNSPECIFIED)
                    popupItem = item
                },
                onClick = {
                    inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                    clipboardManager.pasteItem(item)
                },
            ),
        ) {
            if (item.type == ItemType.IMAGE || item.type == ItemType.VIDEO) {
                val id = ContentUris.parseId(item.uri!!)
                val thumbnail = rememberMediaThumbnail(context, id, item.type)
                when {
                    thumbnail == null -> {
                        // Still decoding. An empty cell for a moment, where the panel used to freeze.
                        Spacer(modifier = Modifier.fillMaxWidth())
                    }
                    thumbnail.isSuccess -> {
                        Image(
                            modifier = Modifier.fillMaxWidth(),
                            bitmap = thumbnail.getOrThrow(),
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                        )
                        if (item.type == ItemType.VIDEO) {
                            Icon(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(start = 4.dp, bottom = 4.dp)
                                    .background(Color.White, CircleShape),
                                imageVector = Icons.Default.Videocam,
                                contentDescription = null,
                                tint = Color.Black,
                            )
                        }
                    }
                    else -> {
                        SnyggText(
                            modifier = Modifier.fillMaxWidth(),
                            text = thumbnail.exceptionOrNull()?.message ?: "Unknown error",
                        )
                    }
                }
            } else {
                val text = item.stringRepresentation()
                Column {
                    ClipTextItemDescription(
                        elementName = FlorisImeUi.ClipboardItemDescription.elementName,
                        attributes = attributes,
                        text = text,
                    )
                    SnyggText(
                        modifier = Modifier
                            .fillMaxWidth()
                            .run { if (contentScrollInsteadOfClip) this.florisVerticalScroll() else this },
                        text = item.displayText(),
                    )
                }
            }
        }
    }

    @Composable
    fun HistoryMainView() {
        SnyggBox(FlorisImeUi.ClipboardContent.elementName,
            modifier = Modifier.fillMaxSize(),
        ) {
            val historyAlpha by animateFloatAsState(targetValue = if (isPopupSurfaceActive()) 0.12f else 1f)
            val staggeredGridCells by prefs.clipboard.historyNumGridColumns()
                .observeAsTransformingState { numGridColumns ->
                    if (numGridColumns == CLIPBOARD_HISTORY_NUM_GRID_COLUMNS_AUTO) {
                        StaggeredGridCells.Adaptive(160.dp)
                    } else {
                        StaggeredGridCells.Fixed(numGridColumns)
                    }
                }

            fun LazyStaggeredGridScope.clipboardItems(
                items: List<ClipboardItem>,
                key: String,
                @StringRes title: Int,
            ) {
                if (items.isNotEmpty()) {
                    item(key, span = StaggeredGridItemSpan.FullLine) {
                        ClipCategoryTitle(text = stringRes(title))
                    }
                    items(items) { item ->
                        ClipItemView(
                            elementName = FlorisImeUi.ClipboardItem.elementName,
                            item = item,
                            contentScrollInsteadOfClip = false,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .matchParentSize()
                    .alpha(historyAlpha),
            ) {
                AnimatedVisibility(
                    visible = isFilterRowShown,
                    enter = VerticalEnterTransition,
                    exit = VerticalExitTransition,
                ) {
                    SnyggRow(
                        elementName = FlorisImeUi.ClipboardFilterRow.elementName,
                        modifier = Modifier.fillMaxWidth(),
                        clickAndSemanticsModifier = Modifier.florisHorizontalScroll(),
                    ) {
                        @Composable
                        fun FilterChip(
                            imageVector: ImageVector,
                            text: String,
                            itemType: ItemType,
                        ) {
                            val active = activeFilterTypes.contains(itemType)
                            val attributes = remember(active) {
                                mapOf(
                                    "state" to if (active) "active" else "inactive",
                                    "type" to itemType.toString().lowercase(),
                                )
                            }
                            SnyggChip(
                                elementName = FlorisImeUi.ClipboardFilterChip.elementName,
                                attributes = attributes,
                                onClick = {
                                    if (!activeFilterTypes.add(itemType)) {
                                        activeFilterTypes.remove(itemType)
                                    }
                                },
                                imageVector = imageVector,
                                text = text,
                            )
                        }

                        FilterChip(
                            imageVector = Icons.Default.TextFields,
                            text = "Text",
                            itemType = ItemType.TEXT,
                        )
                        FilterChip(
                            imageVector = Icons.Default.Image,
                            text = "Images",
                            itemType = ItemType.IMAGE,
                        )
                        FilterChip(
                            imageVector = Icons.Default.Movie,
                            text = "Videos",
                            itemType = ItemType.VIDEO,
                        )
                    }
                }
                SnyggBox(FlorisImeUi.ClipboardGrid.elementName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    LazyVerticalStaggeredGrid(
                        modifier = Modifier
                            .fillMaxSize()
                            .panelScrollbar(gridState, accentColor),
                        state = gridState,
                        columns = staggeredGridCells,
                    ) {
                        for (span in sections) {
                            clipboardItems(
                                items = span.items,
                                key = span.section.headerKey,
                                title = span.section.titleRes,
                            )
                        }
                    }
                }
            }

            if (popupItem != null) {
                SnyggRow(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { popupItem = null }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceAround,
                ) {
                    SnyggColumn(modifier = Modifier.weight(0.5f)) {
                        ClipItemView(
                            elementName = FlorisImeUi.ClipboardItemPopup.elementName,
                            modifier = Modifier
                                .widthIn(max = ItemWidth)
                                .weight(1f, fill = false),
                            item = popupItem!!,
                            contentScrollInsteadOfClip = true,
                        )
                        SnyggBox(FlorisImeUi.ClipboardItemTimestamp.elementName) {
                            val formatter = LocalLocalizedDateTimeFormatter.current
                            SnyggText(
                                modifier = Modifier.fillMaxWidth(),
                                text = formatter.format(Instant.ofEpochMilli(popupItem!!.creationTimestampMs)),
                            )
                        }
                    }
                    SnyggColumn(modifier = Modifier.weight(0.5f)) {
                        SnyggColumn(FlorisImeUi.ClipboardItemActions.elementName) {
                            PopupAction(
                                icon = Icons.Outlined.PushPin,
                                text = stringRes(if (popupItem!!.isPinned) {
                                    R.string.clip__unpin_item
                                } else {
                                    R.string.clip__pin_item
                                }),
                            ) {
                                if (popupItem!!.isPinned) {
                                    clipboardManager.unpinClip(popupItem!!)
                                } else {
                                    clipboardManager.pinClip(popupItem!!)
                                }
                                popupItem = null
                            }
                            PopupAction(
                                icon = Icons.Default.Delete,
                                text = stringRes(R.string.clip__delete_item),
                            ) {
                                clipboardManager.deleteClip(popupItem!!, onlyIfUnpinned = false)
                                popupItem = null
                            }
                            PopupAction(
                                icon = Icons.Outlined.ContentPasteGo,
                                text = stringRes(R.string.clip__paste_item),
                            ) {
                                clipboardManager.pasteItem(popupItem!!)
                                popupItem = null
                            }
                        }
                    }
                }
            }

            if (showClearAllHistory) {
                SnyggRow(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { showClearAllHistory = false }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceAround,
                ) {
                    SnyggColumn(
                        elementName = FlorisImeUi.ClipboardClearAllDialog.elementName,
                        modifier = Modifier
                            .width(DialogWidth)
                            .pointerInput(Unit) {
                                detectTapGestures { /* Do nothing */ }
                            },
                    ) {
                        SnyggText(
                            elementName = FlorisImeUi.ClipboardClearAllDialogMessage.elementName,
                            text = stringRes(
                                if (isFilterRowShown) {
                                    R.string.clipboard__confirm_clear_filtered_history__message
                                } else {
                                    R.string.clipboard__confirm_clear_unfiltered_history__message
                                }
                            ),
                        )
                        SnyggRow(FlorisImeUi.ClipboardClearAllDialogButtons.elementName) {
                            Spacer(modifier = Modifier.weight(1f))
                            SnyggButton(
                                elementName = FlorisImeUi.ClipboardClearAllDialogButton.elementName,
                                attributes = mapOf("action" to "no"),
                                onClick = {
                                    showClearAllHistory = false
                                },
                            ) {
                                SnyggText(
                                    text = stringRes(R.string.action__no),
                                )
                            }
                            SnyggButton(
                                elementName = FlorisImeUi.ClipboardClearAllDialogButton.elementName,
                                attributes = mapOf("action" to "yes"),
                                onClick = {
                                    clipboardManager.clearExactHistory(filteredHistory.unpinned)
                                    context.showShortToastSync(R.string.clipboard__cleared_history)
                                    showClearAllHistory = false
                                },
                            ) {
                                SnyggText(
                                    text = stringRes(R.string.action__yes),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun HistoryEmptyView() {
        SnyggColumn(FlorisImeUi.ClipboardContent.elementName,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnyggText(
                text = stringRes(R.string.clipboard__empty__title),
            )
            SnyggText(
                text = stringRes(R.string.clipboard__empty__message),
            )
        }
    }

    @Composable
    fun HistoryDisabledView() {
        SnyggColumn(FlorisImeUi.ClipboardContent.elementName,
            modifier = Modifier.fillMaxSize(),
        ) {
            SnyggText(
                elementName = FlorisImeUi.ClipboardHistoryDisabledTitle.elementName,
                modifier = Modifier.padding(bottom = 8.dp),
                text = stringRes(R.string.clipboard__disabled__title),
            )
            SnyggText(
                elementName = FlorisImeUi.ClipboardHistoryDisabledMessage.elementName,
                text = stringRes(R.string.clipboard__disabled__message),
            )
            SnyggButton(FlorisImeUi.ClipboardHistoryDisabledButton.elementName,
                onClick = { scope.launch { prefs.clipboard.historyEnabled.set(true) } },
                modifier = Modifier.align(Alignment.End),
            ) {
                SnyggText(
                    text = stringRes(R.string.clipboard__disabled__enable_button),
                )
            }
        }
    }

    @Composable
    fun HistoryLockedView() {
        SnyggColumn(FlorisImeUi.ClipboardContent.elementName,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnyggText(
                elementName = FlorisImeUi.ClipboardHistoryLockedTitle.elementName,
                text = stringRes(R.string.clipboard__locked__title),
            )
            SnyggText(
                elementName = FlorisImeUi.ClipboardHistoryLockedMessage.elementName,
                text = stringRes(R.string.clipboard__locked__message),
            )
        }
    }

    SnyggColumn(
        modifier = modifier
            .fillMaxWidth()
            // Lock to the normal keyboard height so opening the clipboard never changes the IME height.
            .height(FlorisImeSizing.panelUiHeight()),
    ) {
        HeaderRow()
        if (deviceLocked) {
            HistoryLockedView()
        } else {
            if (historyEnabled) {
                if (filteredHistory.all.isNotEmpty() || !activeFilterTypes.isEmpty()) {
                    HistoryMainView()
                } else {
                    HistoryEmptyView()
                }
            } else {
                HistoryDisabledView()
            }
        }
    }
}

@Composable
private fun ClipCategoryTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    SnyggText(FlorisImeUi.ClipboardSubheader.elementName,
        modifier = modifier.fillMaxWidth(),
        text = text.uppercase(),
    )
}

private val ClipboardSection.headerKey: String
    get() = when (this) {
        ClipboardSection.PINNED -> "pinned-header"
        ClipboardSection.RECENT -> "recent-header"
        ClipboardSection.OTHER -> "other-header"
    }

@get:StringRes
private val ClipboardSection.titleRes: Int
    get() = when (this) {
        ClipboardSection.PINNED -> R.string.clipboard__group_pinned
        ClipboardSection.RECENT -> R.string.clipboard__group_recent
        ClipboardSection.OTHER -> R.string.clipboard__group_other
    }

private val SectionTabShape = RoundedCornerShape(6.dp)

/**
 * One tab per non-empty group, lit for the group the grid is scrolled to; a tap scrolls to that group's
 * header (issue #395). Compact on purpose — small type, a tight box around it — because they share the
 * header row with the panel's buttons and a group's name is all they have to show.
 */
@Composable
private fun ClipboardSectionTabs(
    sections: List<ClipboardSectionSpan>,
    gridState: LazyStaggeredGridState,
    accent: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val inputFeedbackController = LocalInputFeedbackController.current

    // A tapped tab stays lit until the grid is dragged by hand. The jump to the last group ends where the
    // grid runs out, not with that group's header at the top, and the scroll position alone would then
    // name whichever group is still above it.
    var tapped by remember { mutableStateOf<ClipboardSection?>(null) }
    val isDragged by gridState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragged) {
        if (isDragged) tapped = null
    }
    val scrolledTo by remember(sections) {
        derivedStateOf {
            sections.sectionAt(
                firstVisibleIndex = gridState.firstVisibleItemIndex,
                atEnd = !gridState.canScrollForward && gridState.canScrollBackward,
            )
        }
    }
    val selected = tapped?.takeIf { section -> sections.any { it.section == section } } ?: scrolledTo

    // Three names can outgrow the width beside the buttons in some languages, so the row scrolls and
    // keeps the lit tab in view.
    val rowState = rememberLazyListState()
    LaunchedEffect(selected) {
        val index = sections.indexOfFirst { it.section == selected }
        if (index >= 0) rowState.animateScrollToItem(index)
    }
    LazyRow(
        modifier = modifier.fillMaxHeight(),
        state = rowState,
        contentPadding = PaddingValues(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(sections, key = { it.section.name }) { span ->
            val isSelected = span.section == selected
            // The whole height of the header row takes the tap, only the text's own box is painted: a
            // target as small as the label would be a hard one to hit on a moving keyboard.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.Tab,
                        interactionSource = null,
                        indication = null,
                        onClick = {
                            inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                            tapped = span.section
                            scope.launch { gridState.animateScrollToItem(span.headerIndex) }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                SnyggText(
                    elementName = FlorisImeUi.ClipboardHeaderText.elementName,
                    modifier = Modifier
                        .clip(SectionTabShape)
                        // The accent at full opacity, as on the sticker tabs: a washed one reads as a
                        // disabled control rather than as the colour the user picked.
                        .background(if (isSelected) accent else Color.Transparent)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .run { if (isSelected) this else alpha(0.6f) },
                    fontSizeMultiplier = 0.8f,
                    color = if (isSelected) accent.onAccent() else null,
                    text = stringRes(span.section.titleRes),
                )
            }
        }
    }
}

@Composable
private fun ClipTextItemDescription(
    elementName: String,
    attributes: SnyggQueryAttributes,
    text: String,
    modifier: Modifier = Modifier,
): Unit = with(LocalDensity.current) {
    val icon: ImageVector?
    val description: String?
    when {
        NetworkUtils.isEmailAddress(text) -> {
            icon = Icons.Outlined.Email
            description = stringRes(R.string.clipboard__item_description_email)
        }
        NetworkUtils.isUrl(text) -> {
            icon = Icons.Default.Link
            description = stringRes(R.string.clipboard__item_description_url)
        }
        NetworkUtils.isPhoneNumber(text) -> {
            icon = Icons.Default.Phone
            description = stringRes(R.string.clipboard__item_description_phone)
        }
        else -> {
            icon = null
            description = null
        }
    }
    if (icon != null && description != null) {
        SnyggRow(
            elementName = elementName,
            attributes = attributes,
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SnyggIcon(
                imageVector = icon,
            )
            SnyggText(
                modifier = Modifier.weight(1f),
                text = description,
            )
        }
    }
}

/**
 * One row of the item popup. The implementation moved to [MediaAction] when the sticker and GIF
 * panels adopted this surface; the alias stays so the call sites here still read as their own thing.
 */
@Composable
private fun PopupAction(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    MediaAction(icon = icon, text = text, modifier = modifier, onClick = onClick)
}

/** The longest edge a clipboard thumbnail is decoded to. A grid cell is never wider than this. */
private const val MaxThumbnailPx = 1024

/**
 * The decoded thumbnail for the media item [id], or `null` while it is still being decoded.
 *
 * Both media branches used to decode on the composing thread and at full resolution: a 12 MP
 * screenshot is a bitmap of roughly 29 MB, and the panel builds one of those per visible cell, which
 * is how a clipboard with a handful of images stalled the keyboard and eventually took the process
 * with it (issue #316). Sampling is the half that keeps the memory down, the dispatcher the half
 * that keeps the panel moving while it opens.
 */
@Composable
private fun rememberMediaThumbnail(context: Context, id: Long, type: ItemType): Result<ImageBitmap>? {
    return produceState<Result<ImageBitmap>?>(initialValue = null, id) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeMediaThumbnail(context, id, type) }
        }
    }.value
}

private fun decodeMediaThumbnail(context: Context, id: Long, type: ItemType): ImageBitmap {
    val file = ClipboardFileStorage.getFileForId(context, id)
    check(file.exists()) { "Unable to resolve media at ${file.absolutePath}" }
    val bitmap = when (type) {
        ItemType.IMAGE -> {
            val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val options = BitmapFactory.Options().also {
                it.inSampleSize = thumbnailSampleSize(bounds.outWidth, bounds.outHeight)
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        }
        ItemType.VIDEO -> {
            if (AndroidVersion.ATLEAST_API29_Q) {
                val retriever = MediaMetadataRetriever()
                val size = try {
                    retriever.setDataSource(file.absolutePath)
                    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    thumbnailSize(width?.toIntOrNull() ?: 0, height?.toIntOrNull() ?: 0)
                } finally {
                    // Never released before, so every video preview leaked a native decoder.
                    retriever.release()
                }
                ThumbnailUtils.createVideoThumbnail(file, Size(size.first, size.second), null)
            } else {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)
            }
        }
        ItemType.TEXT -> null
    }
    checkNotNull(bitmap) { "Unable to decode media at ${file.absolutePath}" }
    return bitmap.asImageBitmap()
}

/**
 * The power of two [BitmapFactory] should shrink an image of [width] by [height] by, so that neither
 * edge exceeds [max]. Unknown bounds (a decode that failed, reported as 0) ask for no shrinking —
 * the decode is about to fail anyway, and a guessed factor would only make the failure quieter.
 */
internal fun thumbnailSampleSize(width: Int, height: Int, max: Int = MaxThumbnailPx): Int {
    var sampleSize = 1
    while (width / sampleSize > max || height / sampleSize > max) {
        sampleSize *= 2
    }
    return sampleSize
}

/**
 * [width] by [height] scaled down to fit inside [max] on both edges, keeping the aspect ratio and
 * never going below one pixel. A video whose size cannot be read falls back to [max] square.
 */
internal fun thumbnailSize(width: Int, height: Int, max: Int = MaxThumbnailPx): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return max to max
    if (width <= max && height <= max) return width to height
    // Integer arithmetic on purpose: scaling by a double leaves the long edge at max - 1 for the
    // common 16:9 case, and createVideoThumbnail throws on a zero-pixel edge.
    val longestEdge = maxOf(width, height).toLong()
    val scaledWidth = (width.toLong() * max / longestEdge).toInt()
    val scaledHeight = (height.toLong() * max / longestEdge).toInt()
    return maxOf(1, scaledWidth) to maxOf(1, scaledHeight)
}
