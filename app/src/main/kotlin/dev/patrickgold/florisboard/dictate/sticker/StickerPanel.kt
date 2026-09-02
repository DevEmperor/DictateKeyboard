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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.DrawableImage
import coil3.compose.AsyncImage
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.editor.EditorInstance
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
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
import org.florisboard.lib.snygg.ui.SnyggIconButton
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

    /**
     * The folder [item] actually lives in, which is what every history write is keyed on — not the tab
     * it was tapped from. The combined tab shows stickers from every pack, so the two differ there, and
     * keying on the tab is what made a pack's own list disagree with the combined one (#308).
     */
    fun ownerOf(item: StickerItem): String =
        index?.categoryOf(item.docId) ?: StickerCategory.ROOT_ID

    fun insert(item: StickerItem, categoryId: String, asGif: Boolean = false) {
        val treeUri = folderUri.takeIf { it.isNotBlank() }?.toUri() ?: return
        scope.launch {
            val outcome = StickerManager.insert(context, treeUri, item, categoryId, asGif) { preparing ->
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
        SnyggRow(
            elementName = FlorisImeUi.MediaBottomRow.elementName,
            modifier = Modifier
                .fillMaxWidth()
                .height(FlorisImeSizing.smartbarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SnyggIconButton(
                elementName = FlorisImeUi.MediaBottomRowButton.elementName,
                onClick = { keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT },
                modifier = Modifier.size(FlorisImeSizing.smartbarHeight),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                )
            }
            SnyggText(
                elementName = FlorisImeUi.SmartbarCandidateWordText.elementName,
                text = stringRes(R.string.sticker__title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            SnyggIconButton(
                elementName = FlorisImeUi.MediaBottomRowButton.elementName,
                onClick = { FlorisImeService.launchSettings("settings/media") },
                modifier = Modifier.size(FlorisImeSizing.smartbarHeight),
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                )
            }
        }

        val currentIndex = index
        val categories = remember(currentIndex) {
            currentIndex?.categories.orEmpty().filter { it.items.isNotEmpty() }
        }
        val openSettings: () -> Unit = { FlorisImeService.launchSettings("settings/media") }

        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
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
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            itemsIndexed(categories, key = { _, category -> category.id }) { position, category ->
                                val selected = pagerState.currentPage == position
                                SnyggText(
                                    elementName = if (selected) {
                                        FlorisImeUi.SmartbarCandidateWordText.elementName
                                    } else {
                                        FlorisImeUi.SmartbarCandidateWordSecondaryText.elementName
                                    },
                                    text = category.name.ifBlank { rootLabel },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .padding(horizontal = 3.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(if (selected) Color(0x33808080) else Color(0x18808080))
                                        .clickable {
                                            scope.launch { pagerState.animateScrollToPage(position) }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
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
                        // The first tab aggregates: its favourites and recents span every folder, while
                        // the plain grid below stays the loose files of the picked folder itself.
                        val historyKey = if (isRoot) StickerHistory.GLOBAL else category.id
                        val pool = if (isRoot) currentIndex!!.allItems else category.items

                        StickerCategoryPage(
                            category = category,
                            pool = pool,
                            historyKey = historyKey,
                            history = history,
                            preparingDocId = preparingDocId,
                            thumbnailSize = thumbnailSize,
                            treeUri = treeUri,
                            restLabel = restLabel,
                            accent = accent,
                            canDelete = canWrite,
                            // Every pack, not just the ones with something in them: moving a sticker
                            // into a pack you just created is the whole point of having created it.
                            packs = if (canWrite) {
                                currentIndex!!.categories.filter { it.id != StickerCategory.ROOT_ID }
                            } else {
                                emptyList()
                            },
                            packOf = { docId -> currentIndex!!.categoryOf(docId) },
                            onInsert = { item -> insert(item, ownerOf(item)) },
                            onDelete = { item -> deleteFile(item) },
                            onShare = { item -> shareSticker(item) },
                            onInsertAsGif = { item -> insert(item, ownerOf(item), asGif = true) },
                            onMoveToPack = { item, packId -> moveToPack(item, packId) },
                            onPin = { item ->
                                scope.launch { StickerHistoryHelper.pin(prefs, ownerOf(item), item.docId) }
                            },
                            onUnpin = { item ->
                                scope.launch { StickerHistoryHelper.unpin(prefs, ownerOf(item), item.docId) }
                            },
                            onForget = { item ->
                                scope.launch { StickerHistoryHelper.removeRecent(prefs, ownerOf(item), item.docId) }
                            },
                        )
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
    historyKey: String,
    history: StickerHistory,
    preparingDocId: String?,
    thumbnailSize: Int,
    treeUri: Uri,
    restLabel: String,
    accent: Color,
    canDelete: Boolean,
    packs: List<StickerCategory>,
    packOf: (String) -> String?,
    onInsert: (StickerItem) -> Unit,
    onDelete: (StickerItem) -> Unit,
    onShare: (StickerItem) -> Unit,
    onInsertAsGif: (StickerItem) -> Unit,
    onMoveToPack: (StickerItem, String) -> Unit,
    onPin: (StickerItem) -> Unit,
    onUnpin: (StickerItem) -> Unit,
    onForget: (StickerItem) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val prefs by FlorisPreferenceStore
    val inputFeedbackController = LocalInputFeedbackController.current
    val confirmBeforeInsert by prefs.sticker.confirmBeforeInsert.collectPrefAsState()
    var menuFor by remember { mutableStateOf<String?>(null) }
    // Which sticker is waiting for its confirming tap, by the same section/doc key the menu uses — the
    // same sticker can appear under favourites and again in the grid, and only the one that was tapped
    // should light up. Cleared as soon as the grid moves: a sticker armed before a scroll is a sticker
    // the user has stopped looking at, and leaving it armed is how a confirmation turns into a misfire
    // of its own.
    var armedKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) armedKey = null
        }
    }
    // Deleting removes the user's own file, so it takes a second tap. Held per menu rather than per
    // sticker so closing the menu also cancels the armed confirmation.
    var deleteArmed by remember { mutableStateOf(false) }
    // The same menu, second page: which pack to move into. A submenu would need somewhere to hang,
    // and a long-pressed grid cell has no room for one.
    var packPickerOpen by remember { mutableStateOf(false) }

    val byId = remember(pool) { pool.associateBy { it.docId } }
    // Every tab carries its own favourites and recents (#308). They were once shown on the first tab
    // alone, on the argument that inside a pack every sticker is equal — but a pack of two hundred is
    // exactly where "the six I actually use" is worth the two rows it costs, and it is what the folder
    // feature was described as doing.
    val pinned = history.pinnedIn(historyKey).mapNotNull { byId[it] }
    val recent = history.recentIn(historyKey).mapNotNull { byId[it] }
    val shown = remember(pinned, recent, category.items) {
        val used = HashSet<String>(pinned.size + recent.size)
        pinned.mapTo(used) { it.docId }
        recent.mapTo(used) { it.docId }
        category.items.filterNot { it.docId in used }
    }
    val sectionLabel = category.name.ifBlank { restLabel }

    @Composable
    fun Cell(item: StickerItem, section: String) {
        val menuKey = "$section/${item.docId}"
        val isPinned = history.isPinned(historyKey, item.docId)
        val isArmed = armedKey == menuKey
        Box {
            StickerThumb(
                item = item,
                treeUri = treeUri,
                armed = isArmed,
                accent = accent,
                scrolling = { gridState.isScrollInProgress },
                onClick = {
                    inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                    // With confirmation on, the first tap arms and the second sends. A quick
                    // double-tap therefore sends in one motion without a shortcut of its own, and
                    // tapping a different sticker moves the armed state rather than sending it.
                    if (!confirmBeforeInsert || isArmed) {
                        armedKey = null
                        onInsert(item)
                    } else {
                        armedKey = menuKey
                    }
                },
                onLongClick = {
                    inputFeedbackController.keyLongPress(TextKeyData.UNSPECIFIED)
                    armedKey = null
                    menuFor = menuKey
                    deleteArmed = false
                    packPickerOpen = false
                },
            )
            if (preparingDocId == item.docId) {
                // Sized to the cell so it reads as "this one is busy" rather than "the panel is busy".
                Box(
                    modifier = Modifier.matchParentSize().background(Color(0x66000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    // Accent, not the default: with no MaterialTheme in the IME, an untinted
                    // indicator comes out in Compose's built-in purple — the same reason the shared
                    // scrollbar was invisible here.
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = accent,
                    )
                }
            }
            DropdownMenu(
                expanded = menuFor == menuKey,
                onDismissRequest = { menuFor = null; deleteArmed = false; packPickerOpen = false },
                // Not focusable, and that is load-bearing. A focusable popup takes window focus off
                // the keyboard, the target app re-attaches its editor, and onStartInputView resets
                // imeUiMode to TEXT — which looked like the menu flashing and the panel closing by
                // itself.
                properties = PopupProperties(focusable = false),
            ) {
                if (packPickerOpen) {
                    val currentPack = packOf(item.docId)
                    if (!currentPack.isNullOrEmpty()) {
                        DropdownMenuItem(
                            text = { Text(stringRes(R.string.sticker__pack_none)) },
                            leadingIcon = { Icon(Icons.Outlined.FolderOff, contentDescription = null) },
                            onClick = {
                                onMoveToPack(item, StickerCategory.ROOT_ID)
                                menuFor = null
                                packPickerOpen = false
                            },
                        )
                    }
                    for (pack in packs) {
                        if (pack.id == currentPack) continue
                        DropdownMenuItem(
                            text = { Text(pack.name) },
                            leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                            onClick = {
                                onMoveToPack(item, pack.id)
                                menuFor = null
                                packPickerOpen = false
                            },
                        )
                    }
                    return@DropdownMenu
                }
                DropdownMenuItem(
                    text = {
                        Text(stringRes(if (isPinned) R.string.sticker__unpin else R.string.sticker__pin))
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (isPinned) Icons.Outlined.PushPin else Icons.Default.PushPin,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        if (isPinned) onUnpin(item) else onPin(item)
                        menuFor = null
                    },
                )
                if (packs.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringRes(R.string.sticker__move_to_pack)) },
                        leadingIcon = { Icon(Icons.Outlined.DriveFileMove, contentDescription = null) },
                        onClick = { packPickerOpen = true },
                    )
                }
                if (section == "recent") {
                    DropdownMenuItem(
                        text = { Text(stringRes(R.string.sticker__forget_recent)) },
                        leadingIcon = { Icon(Icons.Default.HistoryToggleOff, contentDescription = null) },
                        onClick = {
                            onForget(item)
                            menuFor = null
                        },
                    )
                }
                // Offered for WebP only: a GIF is already one, and a PNG has nothing to animate.
                // Whether this particular WebP moves is not in the index, so the entry shows and the
                // insert falls back to the ordinary route if there is no animation in the file.
                if (item.mime == "image/webp") DropdownMenuItem(
                    text = { Text(stringRes(R.string.sticker__insert_as_gif)) },
                    leadingIcon = { Icon(Icons.Outlined.Gif, contentDescription = null) },
                    onClick = {
                        onInsertAsGif(item)
                        menuFor = null
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringRes(R.string.sticker__share)) },
                    leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                    onClick = {
                        onShare(item)
                        menuFor = null
                    },
                )
                if (canDelete) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringRes(
                                    if (deleteArmed) R.string.sticker__delete_confirm
                                    else R.string.sticker__delete_file
                                ),
                                color = if (deleteArmed) MaterialTheme.colorScheme.error else Color.Unspecified,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = if (deleteArmed) MaterialTheme.colorScheme.error else LocalContentColor.current,
                            )
                        },
                        onClick = {
                            if (deleteArmed) {
                                onDelete(item)
                                menuFor = null
                                deleteArmed = false
                            } else {
                                deleteArmed = true
                            }
                        },
                    )
                }
            }
        }
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
