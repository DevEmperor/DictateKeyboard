/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.sticker

import android.graphics.drawable.Animatable
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.DrawableImage
import coil3.compose.AsyncImage
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.ui.MediaAction
import dev.patrickgold.florisboard.dictate.ui.MediaActionOverlay
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.editor.EditorInstance
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard.PanelHeaderButton
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.jetpref.datastore.model.collectAsState as collectPrefAsState
import kotlinx.coroutines.launch
import org.florisboard.lib.android.showLongToast
import org.florisboard.lib.android.showShortToast
import org.florisboard.lib.compose.panelScrollbar
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * The user's own stickers, read from a folder they picked (issue #280) — its own [ImeUiMode.STICKER]
 * next to the typing keyboard, opened from the Smartbar action.
 *
 * The layout follows the variant the requester ranked highest in his own mockup: categories along the
 * top, grid below, favourites and recently used as sections above the rest. Subfolders are the
 * categories; the first tab holds the loose files of the picked folder and, above them, the combined
 * favourites and recents from every folder.
 *
 * Cells are square rather than staggered like the GIF panel: the documents provider reports no image
 * dimensions, so a staggered grid could only learn each sticker's shape by decoding it, and the whole
 * grid would visibly re-flow as images arrived.
 */
@Composable
fun StickerPanel(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val prefs by FlorisPreferenceStore
    val accent by prefs.theme.accentColor.collectPrefAsState()
    val folderUri by prefs.sticker.folderUri.collectPrefAsState()
    val thumbnailSize by prefs.sticker.thumbnailSize.collectPrefAsState()
    val history by prefs.sticker.historyData.collectPrefAsState()
    val packSettings by prefs.sticker.packSettings.collectPrefAsState()
    val scope = rememberCoroutineScope()

    var index by remember { mutableStateOf<StickerIndex?>(null) }
    var loading by remember { mutableStateOf(true) }
    var accessLost by remember { mutableStateOf(false) }
    // Bumped after a deletion so the folder is read again; nothing else invalidates while the panel
    // is open, since anything added from elsewhere arrives with the panel closed.
    var reloadToken by remember { mutableIntStateOf(0) }
    // The sticker whose file is being re-encoded right now. A second of silence reads as a broken
    // tap; a second with a ring on the cell reads as work.
    var preparingDocId by remember { mutableStateOf<String?>(null) }
    val canWrite = remember(folderUri) { StickerWriter.canWrite(context, folderUri) }
    // The long-pressed sticker, and which section it was long-pressed in — "forget this recent" only
    // makes sense for a cell that is in the recents row. Held here rather than per page so the sheet
    // covers the whole panel, tabs included, the way the clipboard's does.
    var menuItem by remember { mutableStateOf<StickerItem?>(null) }
    var menuSection by remember { mutableStateOf("") }
    // Deleting removes the user's own file, so it takes a second tap. Reset with the sheet, so
    // closing it also disarms.
    var deleteArmed by remember { mutableStateOf(false) }
    // The sheet's second face: which pack to move into. A submenu would need somewhere to hang.
    var packPickerOpen by remember { mutableStateOf(false) }
    // The favourite being moved along the row right now (issue #317). While this is set the header
    // turns into a pair of arrows and taps stop inserting, which is what makes the mode safe: nothing
    // can be sent by accident while the grid is being rearranged.
    //
    // Arrows rather than dragging, and not for want of trying: Compose loses the pointer stream
    // mid-press inside the keyboard window (#235), so a drag has to be driven from the root view's
    // own touch dispatch. That is a lot of machinery for moving a sticker one place to the left.
    var reorderDocId by remember { mutableStateOf<String?>(null) }

    fun closeMenu() {
        menuItem = null
        deleteArmed = false
        packPickerOpen = false
    }
    // An import started from the settings screen finishes while this panel may already be composed.
    val importedTick by StickerImports.importedTick.collectAsState()

    // Show whatever was scanned last straight away, then re-read the folder in the background: a
    // collection that has not changed costs nothing visible, one that has corrects itself a moment later.
    LaunchedEffect(folderUri, reloadToken, importedTick) {
        accessLost = false
        if (folderUri.isBlank()) {
            index = null
            loading = false
            return@LaunchedEffect
        }
        val cached = StickerScanner.loadCached(context, folderUri)
        index = cached
        loading = cached == null
        try {
            val scanned = StickerScanner.scan(context, folderUri.toUri())
            StickerScanner.saveCached(context, scanned)
            index = scanned
        } catch (e: StickerScanner.AccessLostException) {
            accessLost = true
        } catch (e: Exception) {
            if (cached == null) accessLost = true
        }
        loading = false
    }

    fun deleteFile(item: StickerItem) {
        val treeUri = folderUri.takeIf { it.isNotBlank() }?.toUri() ?: return
        scope.launch {
            if (StickerWriter.delete(context, treeUri, item.docId)) {
                // The sticker is gone from disk, so its entries would otherwise linger as gaps in the
                // favourites and recents rows.
                StickerHistoryHelper.forget(prefs, item.docId)
                StickerScanner.clearCached(context)
                reloadToken++
            } else {
                context.showShortToast(R.string.sticker__delete_failed)
            }
        }
    }

    fun insert(item: StickerItem, asGif: Boolean = false) {
        val treeUri = folderUri.takeIf { it.isNotBlank() }?.toUri() ?: return
        scope.launch {
            val outcome = StickerManager.insert(context, treeUri, item, asGif) { preparing ->
                preparingDocId = if (preparing) item.docId else null
            }
            when (outcome) {
                EditorInstance.MediaCommitResult.COMMITTED ->
                    keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
                EditorInstance.MediaCommitResult.COPIED_TO_CLIPBOARD -> {
                    // Name the reason rather than "this app does not accept stickers": which formats
                    // the app will take is the one fact that makes the failure actionable.
                    context.showLongToast(StickerManager.refusalReason(context, item))
                    keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
                }
                EditorInstance.MediaCommitResult.FAILED ->
                    context.showShortToast(R.string.sticker__insert_failed)
            }
        }
    }

    fun shareSticker(item: StickerItem) {
        val treeUri = folderUri.takeIf { it.isNotBlank() }?.toUri() ?: return
        scope.launch {
            if (StickerManager.share(context, treeUri, item)) {
                keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
            } else {
                context.showShortToast(R.string.sticker__insert_failed)
            }
        }
    }

    fun moveToPack(item: StickerItem, targetPackId: String) {
        val treeUri = folderUri.takeIf { it.isNotBlank() }?.toUri() ?: return
        val currentIndex = index ?: return
        val sourceCategory = currentIndex.categoryOf(item.docId) ?: return
        scope.launch {
            val rootDocId = StickerScanner.rootDocumentId(treeUri) ?: return@launch
            val from = sourceCategory.ifEmpty { rootDocId }
            val to = targetPackId.ifEmpty { rootDocId }
            if (StickerWriter.moveToPack(context, treeUri, item.docId, from, to)) {
                // A move can hand the file a new document id, so the old one has to go from the
                // favourites and recents or it would leave a hole nobody can explain.
                StickerHistoryHelper.forget(prefs, item.docId)
                StickerScanner.clearCached(context)
                reloadToken++
            } else {
                context.showShortToast(R.string.sticker__move_failed)
            }
        }
    }

    SnyggColumn(
        elementName = FlorisImeUi.Media.elementName,
        modifier = modifier
            .fillMaxWidth()
            // Taller than a normal keyboard, like the GIF panel, so a row of stickers stays readable.
            .height(FlorisImeSizing.imeUiHeight() + FlorisImeSizing.keyboardRowBaseHeight * 2),
    ) {
        // The clipboard's header, not the emoji panel's bottom row: `media-bottom-row-button` carries
        // 16 dp of vertical padding in every bundled theme, which is right for a row a whole key tall
        // and leaves about 8 dp for an icon in a row this height. That is why these buttons looked
        // shrunken next to the clipboard's, which has always used the header element (#317).
        SnyggRow(
            elementName = FlorisImeUi.ClipboardHeader.elementName,
            modifier = Modifier
                .fillMaxWidth()
                .height(FlorisImeSizing.smartbarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val movingDocId = reorderDocId
            if (movingDocId != null) {
                // The header carries the arrows rather than the grid: a button drawn on the cell
                // itself would have to sit somewhere, and every place it could sit is a place the
                // finger already means something else.
                PanelHeaderButton(
                    onClick = { scope.launch { StickerHistoryHelper.movePinned(prefs, movingDocId, -1) } },
                    modifier = Modifier.size(FlorisImeSizing.smartbarHeight),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringRes(R.string.sticker__reorder_earlier),
                        modifier = Modifier.size(FlorisImeSizing.mediaHeaderIconSize),
                    )
                }
                SnyggText(
                    elementName = FlorisImeUi.ClipboardHeaderText.elementName,
                    text = stringRes(R.string.sticker__reorder_hint),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                PanelHeaderButton(
                    onClick = { scope.launch { StickerHistoryHelper.movePinned(prefs, movingDocId, 1) } },
                    modifier = Modifier.size(FlorisImeSizing.smartbarHeight),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringRes(R.string.sticker__reorder_later),
                        modifier = Modifier.size(FlorisImeSizing.mediaHeaderIconSize),
                    )
                }
                PanelHeaderButton(
                    onClick = { reorderDocId = null },
                    modifier = Modifier.size(FlorisImeSizing.smartbarHeight),
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringRes(R.string.action__done),
                        modifier = Modifier.size(FlorisImeSizing.mediaHeaderIconSize),
                    )
                }
            } else {
                PanelHeaderButton(
                    onClick = { keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT },
                    modifier = Modifier.size(FlorisImeSizing.smartbarHeight),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(FlorisImeSizing.mediaHeaderIconSize),
                    )
                }
                SnyggText(
                    // The clipboard's title element, and no padding of its own: the 8 dp that used
                    // to be here is exactly why the gap after the back arrow was wider here than
                    // there (#317).
                    elementName = FlorisImeUi.ClipboardHeaderText.elementName,
                    text = stringRes(R.string.sticker__title),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (index?.isEmpty == false) {
                    // Typing a name means the keyboard, and the keyboard is what this panel replaced —
                    // so the search hands the screen back to it and shows its results in the strip
                    // above (#317), the same way the emoji search does.
                    PanelHeaderButton(
                        onClick = { keyboardManager.activateStickerSearch() },
                        modifier = Modifier.size(FlorisImeSizing.smartbarHeight),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringRes(R.string.sticker__search),
                            modifier = Modifier.size(FlorisImeSizing.mediaHeaderIconSize),
                        )
                    }
                }
                PanelHeaderButton(
                    onClick = { FlorisImeService.launchSettings("settings/media") },
                    modifier = Modifier.size(FlorisImeSizing.smartbarHeight),
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(FlorisImeSizing.mediaHeaderIconSize),
                    )
                }
            }
        }

        val currentIndex = index
        val categories = remember(currentIndex, packSettings) {
            StickerPackSettings.ordered(
                categories = currentIndex?.categories.orEmpty().filter { it.items.isNotEmpty() },
                order = packSettings.order,
            )
        }
        // A pack icon whose file has since been deleted or moved is dropped here rather than left to
        // draw a hole in the tab: the same courtesy the favourites row does for an id it cannot resolve.
        val packIcons = remember(currentIndex, packSettings) {
            val known = currentIndex?.allItems?.mapTo(HashSet()) { it.docId }.orEmpty()
            packSettings.icons.filterValues { it in known }
        }
        val openSettings: () -> Unit = { FlorisImeService.launchSettings("settings/media") }
        // Dimmed rather than covered, so the sticker being acted on stays visible behind the sheet.
        val panelAlpha by animateFloatAsState(targetValue = if (menuItem != null) 0.12f else 1f)

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
        Column(modifier = Modifier.fillMaxSize().alpha(panelAlpha)) {
            when {
                folderUri.isBlank() -> StickerNotice(
                    message = stringRes(R.string.sticker__setup_needed),
                    action = stringRes(R.string.sticker__setup_needed_action),
                    onAction = openSettings,
                )
                accessLost && currentIndex == null -> StickerNotice(
                    message = stringRes(R.string.sticker__access_lost),
                    action = stringRes(R.string.sticker__setup_needed_action),
                    onAction = openSettings,
                )
                loading -> StickerCentered { CircularProgressIndicator(color = accent) }
                categories.isEmpty() -> StickerNotice(
                    message = stringRes(R.string.sticker__folder_empty),
                    action = stringRes(R.string.sticker__setup_needed_action),
                    onAction = openSettings,
                )
                else -> {
                    val pagerState = rememberPagerState(pageCount = { categories.size })
                    val rootLabel = stringRes(R.string.sticker__category_all)
                    val restLabel = stringRes(R.string.sticker__section_rest)
                    val treeUri = remember(folderUri) { folderUri.toUri() }

                    if (categories.size > 1) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(FlorisImeSizing.smartbarHeight),
                            // The same margin the emoji categories keep, so the two tab rows start and
                            // end at the same place rather than each at its own.
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            itemsIndexed(categories, key = { _, category -> category.id }) { position, category ->
                                val selected = pagerState.currentPage == position
                                val iconDocId = packIcons[category.name]
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .padding(horizontal = 3.dp)
                                        .clip(RoundedCornerShape(50))
                                        // The accent marks what the user picked, everywhere else in
                                        // this panel already — the scrollbar and the ring around a
                                        // sticker waiting for its second tap. The tab pill was the
                                        // one thing left painting that same meaning in grey (#317).
                                        .background(
                                            if (selected) accent.copy(alpha = 0.28f) else Color(0x18808080)
                                        )
                                        .clickable {
                                            scope.launch { pagerState.animateScrollToPage(position) }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    if (iconDocId != null) {
                                        AsyncImage(
                                            model = StickerScanner.documentUri(treeUri, iconDocId),
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            // Stopped the moment it arrives: an animated tab icon is a
                                            // decoder running for as long as the panel is open, for an
                                            // 18 dp picture nobody is watching. Frame rate was the
                                            // whole of #308's third point.
                                            onSuccess = { state ->
                                                ((state.result.image as? DrawableImage)?.drawable
                                                    as? Animatable)?.stop()
                                            },
                                            modifier = Modifier
                                                .padding(end = 6.dp)
                                                .size(18.dp),
                                        )
                                    }
                                    SnyggText(
                                        elementName = if (selected) {
                                            FlorisImeUi.SmartbarCandidateWordText.elementName
                                        } else {
                                            FlorisImeUi.SmartbarCandidateWordSecondaryText.elementName
                                        },
                                        text = category.name.ifBlank { rootLabel },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        // Only the page being looked at. Keeping the neighbour composed made swiping
                        // between tabs seamless, but a composed page of animated stickers is a second
                        // grid of running decoders behind the first one — which is why the frame rate
                        // was reported as dropping on tab switches specifically (#308).
                        beyondViewportPageCount = 0,
                    ) { page ->
                        val category = categories[page]
                        val isRoot = category.id == StickerCategory.ROOT_ID
                        // What this tab can resolve, which is also what decides how much of the history
                        // it shows: everything on the first tab, and a pack's own files on a pack tab.
                        // The plain grid below stays the loose files of the picked folder itself.
                        val pool = if (isRoot) currentIndex!!.allItems else category.items

                        StickerCategoryPage(
                            category = category,
                            pool = pool,
                            history = history,
                            preparingDocId = preparingDocId,
                            thumbnailSize = thumbnailSize,
                            treeUri = treeUri,
                            restLabel = restLabel,
                            accent = accent,
                            reorderDocId = reorderDocId,
                            onInsert = { item -> insert(item) },
                            // Tapping another favourite hands the arrows over to it, so a row can be
                            // put in order without leaving the mode once per sticker.
                            onReorderPick = { item -> reorderDocId = item.docId },
                            onLongPress = { item, section ->
                                menuItem = item
                                menuSection = section
                                deleteArmed = false
                                packPickerOpen = false
                            },
                        )
                    }
                }
            }
        }

            val sheetItem = menuItem
            if (sheetItem != null) {
                val sheetTreeUri = remember(folderUri) { folderUri.toUri() }
                // Every pack, not just the ones with something in them: moving a sticker into a pack
                // you just created is the whole point of having created it.
                val packs = if (canWrite) {
                    currentIndex?.categories.orEmpty().filter { it.id != StickerCategory.ROOT_ID }
                } else {
                    emptyList()
                }
                val currentPack = currentIndex?.categoryOf(sheetItem.docId)
                MediaActionOverlay(
                    onDismiss = { closeMenu() },
                    preview = {
                        AsyncImage(
                            model = StickerScanner.documentUri(sheetTreeUri, sheetItem.docId),
                            contentDescription = sheetItem.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .aspectRatio(1f),
                        )
                        SnyggText(
                            elementName = FlorisImeUi.ClipboardItemTimestamp.elementName,
                            text = sheetItem.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                ) {
                    if (packPickerOpen) {
                        // The sheet's second face rather than a submenu: the same column, showing
                        // where this sticker could go instead of what could be done to it.
                        if (!currentPack.isNullOrEmpty()) {
                            MediaAction(
                                icon = Icons.Outlined.FolderOff,
                                text = stringRes(R.string.sticker__pack_none),
                            ) {
                                moveToPack(sheetItem, StickerCategory.ROOT_ID)
                                closeMenu()
                            }
                        }
                        for (pack in packs) {
                            if (pack.id == currentPack) continue
                            MediaAction(icon = Icons.Outlined.Folder, text = pack.name) {
                                moveToPack(sheetItem, pack.id)
                                closeMenu()
                            }
                        }
                        return@MediaActionOverlay
                    }
                    val isPinned = history.isPinned(sheetItem.docId)
                    MediaAction(
                        icon = if (isPinned) Icons.Outlined.PushPin else Icons.Default.PushPin,
                        text = stringRes(if (isPinned) R.string.sticker__unpin else R.string.sticker__pin),
                    ) {
                        scope.launch {
                            if (isPinned) {
                                StickerHistoryHelper.unpin(prefs, sheetItem.docId)
                            } else {
                                StickerHistoryHelper.pin(prefs, sheetItem.docId)
                            }
                        }
                        closeMenu()
                    }
                    if (isPinned) {
                        // Re-pinning already moves a sticker to the front, so this is here for the
                        // finer case: a favourite that belongs third, not first.
                        MediaAction(
                            icon = Icons.Default.SwapHoriz,
                            text = stringRes(R.string.sticker__reorder),
                        ) {
                            reorderDocId = sheetItem.docId
                            closeMenu()
                        }
                    }
                    if (packs.isNotEmpty()) {
                        MediaAction(
                            icon = Icons.Outlined.DriveFileMove,
                            text = stringRes(R.string.sticker__move_to_pack),
                        ) { packPickerOpen = true }
                    }
                    // A pack can be told apart at a glance by one of the stickers in it, which is a
                    // better label than its name once there are more tabs than fit on screen. Offered
                    // on the sticker rather than on the tab: this is the moment you are looking at the
                    // picture and can tell whether it stands for the rest.
                    val currentPackName = currentIndex?.categories
                        ?.firstOrNull { it.id == currentPack }?.name.orEmpty()
                    if (currentPackName.isNotEmpty()) {
                        val isPackIcon = packSettings.icons[currentPackName] == sheetItem.docId
                        MediaAction(
                            icon = Icons.Outlined.Label,
                            text = stringRes(
                                if (isPackIcon) R.string.sticker__pack_icon_clear
                                else R.string.sticker__pack_icon_set
                            ),
                        ) {
                            scope.launch {
                                StickerPackSettingsHelper.setIcon(
                                    prefs = prefs,
                                    pack = currentPackName,
                                    docId = if (isPackIcon) null else sheetItem.docId,
                                )
                            }
                            closeMenu()
                        }
                    }
                    if (menuSection == "recent") {
                        MediaAction(
                            icon = Icons.Default.HistoryToggleOff,
                            text = stringRes(R.string.sticker__forget_recent),
                        ) {
                            scope.launch { StickerHistoryHelper.removeRecent(prefs, sheetItem.docId) }
                            closeMenu()
                        }
                    }
                    // Offered for WebP only: a GIF is already one, and a PNG has nothing to animate.
                    // Whether this particular WebP moves is not in the index, so the entry shows and
                    // the insert falls back to the ordinary route if there is no animation in it.
                    if (sheetItem.mime == "image/webp") {
                        MediaAction(
                            icon = Icons.Outlined.Gif,
                            text = stringRes(R.string.sticker__insert_as_gif),
                        ) {
                            insert(sheetItem, asGif = true)
                            closeMenu()
                        }
                    }
                    MediaAction(
                        icon = Icons.Outlined.Share,
                        text = stringRes(R.string.sticker__share),
                    ) {
                        shareSticker(sheetItem)
                        closeMenu()
                    }
                    if (canWrite) {
                        MediaAction(
                            icon = Icons.Default.Delete,
                            text = stringRes(
                                if (deleteArmed) R.string.sticker__delete_confirm
                                else R.string.sticker__delete_file
                            ),
                        ) {
                            if (deleteArmed) {
                                deleteFile(sheetItem)
                                closeMenu()
                            } else {
                                deleteArmed = true
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StickerCategoryPage(
    category: StickerCategory,
    pool: List<StickerItem>,
    history: StickerHistory,
    preparingDocId: String?,
    thumbnailSize: Int,
    treeUri: Uri,
    restLabel: String,
    accent: Color,
    reorderDocId: String?,
    onInsert: (StickerItem) -> Unit,
    onReorderPick: (StickerItem) -> Unit,
    onLongPress: (StickerItem, String) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val prefs by FlorisPreferenceStore
    val inputFeedbackController = LocalInputFeedbackController.current
    val confirmBeforeInsert by prefs.sticker.confirmBeforeInsert.collectPrefAsState()
    // Which sticker is waiting for its confirming tap, by section and document — the same sticker can
    // appear under favourites and again in the grid, and only the one that was tapped should light up.
    // Cleared as soon as the grid moves: a sticker armed before a scroll is a sticker the user has
    // stopped looking at, and leaving it armed is how a confirmation turns into a misfire of its own.
    var armedKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) armedKey = null
        }
    }

    val byId = remember(pool) { pool.associateBy { it.docId } }
    // One history, shown through the tab (#308). Looking each id up in this tab's own items is what
    // narrows it: the combined tab resolves everything, a pack tab resolves only its own files, and an
    // id whose file has since been deleted resolves nowhere. No list is kept per tab, so no two lists
    // can disagree about what the user just did.
    val pinned = history.pinned.mapNotNull { byId[it] }
    val recent = history.recent.mapNotNull { byId[it] }
    val shown = remember(pinned, recent, category.items) {
        val used = HashSet<String>(pinned.size + recent.size)
        pinned.mapTo(used) { it.docId }
        recent.mapTo(used) { it.docId }
        category.items.filterNot { it.docId in used }
    }
    val sectionLabel = category.name.ifBlank { restLabel }

    @Composable
    fun Cell(item: StickerItem, section: String) {
        val cellKey = "$section/${item.docId}"
        // The same ring marks both states, because both mean the same thing: this is the sticker the
        // next button press acts on.
        val isMoving = section == "pinned" && reorderDocId == item.docId
        val isArmed = armedKey == cellKey || isMoving
        StickerCell(
            item = item,
            treeUri = treeUri,
            armed = isArmed,
            preparing = preparingDocId == item.docId,
            accent = accent,
            scrolling = { gridState.isScrollInProgress },
            onClick = {
                inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                when {
                    // Nothing is sent while the arrows are up. A favourite hands them over; any
                    // other sticker is not a thing the arrows could move, so the tap does nothing
                    // rather than quietly doing the one thing the mode exists to prevent.
                    reorderDocId != null -> if (section == "pinned") onReorderPick(item)
                    // With confirmation on, the first tap arms and the second sends. A quick
                    // double-tap therefore sends in one motion without a shortcut of its own, and
                    // tapping a different sticker moves the armed state rather than sending it.
                    !confirmBeforeInsert || isArmed -> {
                        armedKey = null
                        onInsert(item)
                    }
                    else -> armedKey = cellKey
                }
            },
            onLongClick = {
                inputFeedbackController.keyLongPress(TextKeyData.UNSPECIFIED)
                armedKey = null
                onLongPress(item, section)
            },
        )
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = thumbnailSize.dp),
        // The same fading scrollbar the emoji palette uses. A folder of several hundred stickers is a
        // long scroll with no other clue about where in it you are.
        modifier = Modifier
            .fillMaxSize()
            .panelScrollbar(gridState, accent),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (pinned.isNotEmpty()) {
            item(key = "header-pinned", span = { GridItemSpan(maxLineSpan) }) {
                StickerSectionHeader(stringRes(R.string.sticker__section_favorites))
            }
            items(pinned, key = { "pinned-${it.docId}" }) { item -> Cell(item, "pinned") }
        }
        if (recent.isNotEmpty()) {
            item(key = "header-recent", span = { GridItemSpan(maxLineSpan) }) {
                StickerSectionHeader(stringRes(R.string.sticker__section_recent))
            }
            items(recent, key = { "recent-${it.docId}" }) { item -> Cell(item, "recent") }
        }
        if (shown.isNotEmpty()) {
            if (pinned.isNotEmpty() || recent.isNotEmpty()) {
                item(key = "header-all", span = { GridItemSpan(maxLineSpan) }) {
                    StickerSectionHeader(sectionLabel)
                }
            }
            items(shown, key = { "all-${it.docId}" }) { item -> Cell(item, "all") }
        }
    }
}

/**
 * One sticker as it appears in any grid — the panel's own, and the search results above the keyboard.
 *
 * Shared because the busy ring is the part that would otherwise be forgotten in the second place:
 * converting a sticker on the way out takes about a second, and a second of nothing after a tap reads
 * as a broken button rather than as work.
 */
@Composable
internal fun StickerCell(
    item: StickerItem,
    treeUri: Uri,
    armed: Boolean,
    preparing: Boolean,
    accent: Color,
    scrolling: () -> Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box {
        StickerThumb(
            item = item,
            treeUri = treeUri,
            armed = armed,
            accent = accent,
            scrolling = scrolling,
            onClick = onClick,
            onLongClick = onLongClick,
        )
        if (preparing) {
            // Sized to the cell so it reads as "this one is busy" rather than "the panel is busy".
            Box(
                modifier = Modifier.matchParentSize().background(Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) {
                // Accent, not the default: with no MaterialTheme in the IME, an untinted indicator
                // comes out in Compose's built-in purple — the same reason the shared scrollbar was
                // invisible here.
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = accent,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerThumb(
    item: StickerItem,
    treeUri: Uri,
    armed: Boolean,
    accent: Color,
    scrolling: () -> Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // An animated sticker keeps decoding frames for as long as it is on screen, and a grid of them
    // does it in parallel — which is the work that has to give way while the grid is moving. Nobody
    // reads an animation mid-fling, so it is paused for the duration and resumed the moment the grid
    // settles; the sticker stays animated, it just stops competing with the scroll (#308).
    //
    // Coil hands over an AnimatedImageDrawable and starts it when the cell is composed. It is never
    // memory-cached — a running drawable cannot be shared between callers, so `Image.shareable` is
    // false and the cache refuses it — which is also why every cell that scrolls back into view pays
    // for a fresh decode. Pausing does not fix that; it stops it landing all at once.
    var animation by remember(item.docId) { mutableStateOf<Animatable?>(null) }
    LaunchedEffect(animation) {
        val running = animation ?: return@LaunchedEffect
        // snapshotFlow rather than reading the flag in composition: the cell must not recompose twice
        // per scroll gesture just to learn something only its drawable acts on.
        snapshotFlow { scrolling() }.collect { moving ->
            if (moving) running.stop() else running.start()
        }
    }
    // Waiting for its confirming tap: the accent ring and its wash mark one cell out of the grid
    // without moving anything, so the answer to "which one did I hit" needs no second look. Drawn on
    // the cell itself rather than as a popup over the panel — a focusable popup raised by an input
    // method takes focus off the field, the system hides the keyboard, and the keyboard takes the
    // popup down with it.
    val ring = if (armed) {
        Modifier
            .border(2.dp, accent, RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.16f))
    } else {
        Modifier.background(Color(0x14808080))
    }
    AsyncImage(
        model = StickerScanner.documentUri(treeUri, item.docId),
        contentDescription = item.name,
        // Fit, not Crop: a sticker cropped to a square loses exactly the part that makes it readable.
        contentScale = ContentScale.Fit,
        onSuccess = { state ->
            animation = (state.result.image as? DrawableImage)?.drawable as? Animatable
        },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .then(ring)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(3.dp),
    )
}

@Composable
private fun StickerSectionHeader(text: String) {
    SnyggText(
        elementName = FlorisImeUi.MediaEmojiSubheader.elementName,
        text = text,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun StickerCentered(content: @Composable () -> Unit) {
    SnyggBox(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) { content() }
}

@Composable
private fun StickerNotice(message: String, action: String, onAction: () -> Unit) {
    SnyggBox(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SnyggText(FlorisImeUi.MediaEmojiSubheader.elementName, text = message)
            SnyggText(
                elementName = FlorisImeUi.SmartbarCandidateWordText.elementName,
                text = action,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onAction() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
