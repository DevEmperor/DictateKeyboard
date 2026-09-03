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

package dev.patrickgold.florisboard.app.settings.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.EmojiSymbols
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState as collectFlowAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.settings.search.settingsSearchAnchor
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.enumDisplayEntriesOf
import dev.patrickgold.florisboard.dictate.sticker.StickerCategory
import dev.patrickgold.florisboard.dictate.sticker.StickerHistoryHelper
import dev.patrickgold.florisboard.dictate.sticker.StickerImports
import dev.patrickgold.florisboard.dictate.sticker.StickerNormalizer
import dev.patrickgold.florisboard.dictate.sticker.StickerPackSettings
import dev.patrickgold.florisboard.dictate.sticker.StickerPackSettingsHelper
import dev.patrickgold.florisboard.dictate.sticker.StickerIndex
import dev.patrickgold.florisboard.dictate.sticker.StickerScanner
import dev.patrickgold.florisboard.dictate.sticker.StickerWriter
import dev.patrickgold.florisboard.dictate.sticker.stickerImportSummary
import dev.patrickgold.florisboard.ime.media.emoji.EmojiHistory
import dev.patrickgold.florisboard.ime.media.emoji.EmojiHistoryHelper
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSkinTone
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSuggestionType
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import org.florisboard.lib.compose.icons.Sticker
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.DialogSliderPreference
import dev.patrickgold.jetpref.datastore.ui.ExperimentalJetPrefDatastoreUi
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.showLongToast
import org.florisboard.lib.android.showLongToastSync
import org.florisboard.lib.compose.pluralsRes
import org.florisboard.lib.compose.florisDialogScroll
import org.florisboard.lib.compose.stringRes
import kotlin.math.roundToInt

@OptIn(ExperimentalJetPrefDatastoreUi::class)
@Composable
fun MediaScreen() = FlorisScreen {
    title = stringRes(R.string.settings__media__title)
    previewFieldVisible = true
    iconSpaceReserved = true

    val prefs by FlorisPreferenceStore

    var shouldDelete by remember { mutableStateOf<ShouldDelete?>(null) }
    var gifSetupOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    content {
        // Emojis first, then GIFs, then stickers — the order the screen title promises.
        PreferenceGroup(title = stringRes(R.string.prefs__media__emoji__title)) {
            ListPreference(
                prefs.emoji.preferredSkinTone,
                icon = Icons.Outlined.Face,
                modifier = Modifier.settingsSearchAnchor("prefs__media__emoji_preferred_skin_tone"),
                title = stringRes(R.string.prefs__media__emoji_preferred_skin_tone),
                entries = enumDisplayEntriesOf(EmojiSkinTone::class),
            )
        }

        PreferenceGroup(title = stringRes(R.string.prefs__media__emoji_history__title)) {
            SwitchPreference(
                prefs.emoji.historyEnabled,
                icon = Icons.Outlined.Schedule,
                modifier = Modifier.settingsSearchAnchor("prefs__media__emoji_history_enabled"),
                title = stringRes(R.string.prefs__media__emoji_history_enabled),
                summary = stringRes(R.string.prefs__media__emoji_history_enabled__summary),
            )
            ListPreference(
                prefs.emoji.historyPinnedUpdateStrategy,
                modifier = Modifier.settingsSearchAnchor("prefs__media__emoji_history_pinned_update_strategy"),
                title = stringRes(R.string.prefs__media__emoji_history_pinned_update_strategy),
                entries = enumDisplayEntriesOf(EmojiHistory.UpdateStrategy::class),
                enabledIf = { prefs.emoji.historyEnabled.isTrue() },
            )
            ListPreference(
                prefs.emoji.historyRecentUpdateStrategy,
                modifier = Modifier.settingsSearchAnchor("prefs__media__emoji_history_recent_update_strategy"),
                title = stringRes(R.string.prefs__media__emoji_history_recent_update_strategy),
                entries = enumDisplayEntriesOf(EmojiHistory.UpdateStrategy::class),
                enabledIf = { prefs.emoji.historyEnabled.isTrue() },
            )
            DialogSliderPreference(
                primaryPref = prefs.emoji.historyPinnedMaxSize,
                secondaryPref = prefs.emoji.historyRecentMaxSize,
                modifier = Modifier.settingsSearchAnchor("prefs__media__emoji_history_max_size"),
                title = stringRes(R.string.prefs__media__emoji_history_max_size),
                primaryLabel = stringRes(R.string.emoji__history__pinned),
                secondaryLabel = stringRes(R.string.emoji__history__recent),
                valueLabel = { maxSize ->
                    if (maxSize == EmojiHistory.MaxSizeUnlimited) {
                        stringRes(R.string.general__unlimited)
                    } else {
                        pluralsRes(R.plurals.unit__items__written, maxSize, "v" to maxSize)
                    }
                },
                min = 0,
                max = 120,
                stepIncrement = 1,
                enabledIf = { prefs.emoji.historyEnabled.isTrue() },
            )
            Preference(
                modifier = Modifier.settingsSearchAnchor("prefs__media__emoji_history_pinned_reset"),
                title = stringRes(R.string.prefs__media__emoji_history_pinned_reset),
                onClick = {
                    shouldDelete = ShouldDelete(true)
                },
                enabledIf = { prefs.emoji.historyEnabled.isTrue() },
            )
            Preference(
                modifier = Modifier.settingsSearchAnchor("prefs__media__emoji_history_reset"),
                title = stringRes(R.string.prefs__media__emoji_history_reset),
                onClick = {
                    shouldDelete = ShouldDelete(false)
                },
                enabledIf = { prefs.emoji.historyEnabled.isTrue() },
            )

        }

        PreferenceGroup(title = stringRes(R.string.prefs__media__emoji_suggestion__title)) {
            SwitchPreference(
                prefs.emoji.suggestionEnabled,
                icon = Icons.Outlined.EmojiSymbols,
                modifier = Modifier.settingsSearchAnchor("prefs__media__emoji_suggestion_enabled"),
                title = stringRes(R.string.prefs__media__emoji_suggestion_enabled),
                summary = stringRes(R.string.prefs__media__emoji_suggestion_enabled__summary),
            )
            ListPreference(
                prefs.emoji.suggestionType,
                modifier = Modifier.settingsSearchAnchor("prefs__media__emoji_suggestion_type"),
                title = stringRes(R.string.prefs__media__emoji_suggestion_type),
                entries = enumDisplayEntriesOf(EmojiSuggestionType::class),
                enabledIf = { prefs.emoji.suggestionEnabled.isTrue() },
            )
            SwitchPreference(
                prefs.emoji.suggestionUpdateHistory,
                modifier = Modifier.settingsSearchAnchor("prefs__media__emoji_suggestion_update_history"),
                title = stringRes(R.string.prefs__media__emoji_suggestion_update_history),
                summary = stringRes(R.string.prefs__media__emoji_suggestion_update_history__summary),
                enabledIf = {
                    prefs.emoji.suggestionEnabled.isTrue() && prefs.emoji.historyEnabled.isTrue()
                },
            )
            SwitchPreference(
                prefs.emoji.suggestionCandidateShowName,
                modifier = Modifier.settingsSearchAnchor("prefs__media__emoji_suggestion_candidate_show_name"),
                title = stringRes(R.string.prefs__media__emoji_suggestion_candidate_show_name),
                summary = stringRes(R.string.prefs__media__emoji_suggestion_candidate_show_name__summary),
                enabledIf = { prefs.emoji.suggestionEnabled.isTrue() },
            )
            DialogSliderPreference(
                prefs.emoji.suggestionQueryMinLength,
                modifier = Modifier.settingsSearchAnchor("prefs__media__emoji_suggestion_query_min_length"),
                title = stringRes(R.string.prefs__media__emoji_suggestion_query_min_length),
                valueLabel = { length ->
                    pluralsRes(R.plurals.unit__characters__written, length, "v" to length)
                },
                min = 1,
                max = 5,
                stepIncrement = 1,
                enabledIf = { prefs.emoji.suggestionEnabled.isTrue() },
            )
            DialogSliderPreference(
                prefs.emoji.suggestionCandidateMaxCount,
                modifier = Modifier.settingsSearchAnchor("prefs__media__emoji_suggestion_candidate_max_count"),
                title = stringRes(R.string.prefs__media__emoji_suggestion_candidate_max_count),
                valueLabel = { count ->
                    pluralsRes(R.plurals.unit__candidates__written, count, "v" to count)
                },
                min = 1,
                max = 10,
                stepIncrement = 1,
                enabledIf = { prefs.emoji.suggestionEnabled.isTrue() },
            )
        }

        // ----------------------------------------------------------------- GIFs
        PreferenceGroup(title = stringRes(R.string.prefs__media__gif__title)) {
            // One row: the on/off switch and the setup walkthrough both live inside the dialog.
            val gifKey by prefs.gif.klipyApiKey.collectAsState()
            val gifEnabled by prefs.gif.enabled.collectAsState()
            Preference(
                icon = Icons.Outlined.Gif,
                modifier = Modifier.settingsSearchAnchor("prefs__media__gif_setup__title"),
                title = stringRes(R.string.prefs__media__gif_setup__title),
                summary = when {
                    !gifEnabled -> stringRes(R.string.state__disabled)
                    gifKey.isBlank() -> stringRes(R.string.prefs__media__gif_setup__summary_unset)
                    else -> stringRes(R.string.prefs__media__gif_setup__summary_set)
                },
                onClick = { gifSetupOpen = true },
            )
        }

        // ------------------------------------------------------------- Stickers
        // Local stickers (issue #280). Everything below the folder row only matters once one is set.
        val stickerFolderUri by prefs.sticker.folderUri.collectAsState()
        val stickerFolderName by prefs.sticker.folderName.collectAsState()
        val importState by StickerImports.state.collectFlowAsState()
        var sourcePickerOpen by remember { mutableStateOf(false) }
        var packManagerOpen by remember { mutableStateOf(false) }
        var rescanning by remember { mutableStateOf(false) }
        // How far the pass has got, so the wait is a number rather than a spinning circle.
        var rescanProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        // Which folder the picker should open in. Read at launch time rather than baked into the
        // contract, so one launcher serves every source in the list.
        var pendingSource by remember { mutableStateOf<Uri?>(null) }

        val stickerFolderPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            try {
                // Without the persisted grant the folder would be unreadable again after a reboot, and
                // the panel would show "access lost" for a folder the user just picked. Write is taken
                // as well so stickers can be added and deleted from inside the app.
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                scope.launch { context.showLongToast(R.string.sticker__folder_pick_failed) }
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                releaseStickerFolder(context, prefs.sticker.folderUri.get(), keep = uri.toString())
                val name = withContext(Dispatchers.IO) {
                    StickerScanner.clearCached(context)
                    StickerScanner.folderName(context, uri)
                }
                prefs.sticker.folderUri.set(uri.toString())
                prefs.sticker.folderName.set(name)
            }
        }
        val imagePicker = rememberLauncherForActivityResult(
            remember { OpenImagesStartingAt { pendingSource } }
        ) { uris -> importStickers(context, stickerFolderUri, uris) }

        PreferenceGroup(title = stringRes(R.string.prefs__media__sticker__title)) {
            val stickerFolderUnset = stringRes(R.string.prefs__media__sticker_folder__summary_unset)
            Preference(
                icon = Icons.Outlined.Sticker,
                modifier = Modifier.settingsSearchAnchor("prefs__media__sticker_folder__title"),
                title = stringRes(R.string.prefs__media__sticker_folder__title),
                summary = rescanProgress?.let { (done, total) -> "$done / $total" }
                    ?: stickerFolderName.ifBlank { stickerFolderUnset },
                onClick = { stickerFolderPicker.launch(null) },
                trailing = {
                    // Re-reading the folder belongs to the folder, not to a row of its own — and it
                    // has to show that it is working, or a long scan looks like a dead tap. It also
                    // brings anything it finds out of shape into it: a file that arrived without
                    // going through the import is exactly the file this button is tapped about.
                    if (stickerFolderUri.isNotBlank()) {
                        if (rescanning) {
                            // Determinate as soon as the folder has been counted. Reading a few
                            // hundred stickers takes half a minute — every one of them is a separate
                            // call across to the documents provider — and half a minute of a circle
                            // going round says nothing about whether it is nearly done.
                            val progress = rescanProgress
                            if (progress == null) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                CircularProgressIndicator(
                                    progress = {
                                        if (progress.second > 0) {
                                            progress.first.toFloat() / progress.second
                                        } else {
                                            0f
                                        }
                                    },
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        } else {
                            IconButton(
                                modifier = Modifier.settingsSearchAnchor("prefs__media__sticker_rescan"),
                                onClick = {
                                    rescanning = true
                                    scope.launch {
                                        try {
                                            val treeUri = stickerFolderUri.toUri()
                                            var index = StickerScanner.scan(context, treeUri)
                                            val converted = if (StickerWriter.canWrite(context, stickerFolderUri)) {
                                                StickerNormalizer.normalizeFolder(
                                                    context, treeUri, index,
                                                ) { done, total -> rescanProgress = done to total }
                                            } else {
                                                0
                                            }
                                            // Anything rewritten carries a new size and stamp, and one
                                            // that changed type carries a new name and id, so the index
                                            // just built no longer describes the folder.
                                            if (converted > 0) index = StickerScanner.scan(context, treeUri)
                                            StickerScanner.saveCached(context, index)
                                            if (converted > 0) {
                                                context.showLongToast(
                                                    R.string.sticker__rescan_done_converted,
                                                    "n" to index.allItems.size,
                                                    "converted" to converted,
                                                )
                                            } else {
                                                context.showLongToast(
                                                    R.string.sticker__rescan_done,
                                                    "n" to index.allItems.size,
                                                )
                                            }
                                        } catch (e: Exception) {
                                            context.showLongToast(R.string.sticker__access_lost)
                                        }
                                        rescanProgress = null
                                        rescanning = false
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Refresh,
                                    contentDescription = stringRes(R.string.prefs__media__sticker_rescan),
                                )
                            }
                        }
                    }
                },
            )
            if (stickerFolderUri.isNotBlank()) {
                val running = importState
                if (running != null) {
                    // While copying, the two "add" rows become one that reports and cancels. A count
                    // rather than a bare bar: with several hundred files, "84 / 412" is the only form
                    // that says whether it is worth waiting for.
                    Preference(
                        icon = Icons.Outlined.Downloading,
                        title = stringRes(
                            R.string.sticker__import_progress,
                            "done" to running.done,
                            "total" to running.total,
                        ),
                        summary = stringRes(R.string.sticker__import_cancel),
                        onClick = { StickerImports.cancel() },
                        trailing = {
                            CircularProgressIndicator(
                                progress = { running.percent / 100f },
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        },
                    )
                } else {
                    // One row, one dialog. Browsing for files and starting in another app's folder
                    // are the same act with a different starting point, so they are one list now.
                    Preference(
                        icon = Icons.Outlined.AddPhotoAlternate,
                        modifier = Modifier.settingsSearchAnchor("prefs__media__sticker_add"),
                        title = stringRes(R.string.prefs__media__sticker_add),
                        summary = stringRes(R.string.prefs__media__sticker_add__summary),
                        onClick = { sourcePickerOpen = true },
                    )
                }
                Preference(
                    icon = Icons.Outlined.Folder,
                    modifier = Modifier.settingsSearchAnchor("prefs__media__sticker_packs"),
                    title = stringRes(R.string.prefs__media__sticker_packs),
                    summary = stringRes(R.string.prefs__media__sticker_packs__summary),
                    onClick = { packManagerOpen = true },
                )
                DialogSliderPreference(
                    prefs.sticker.thumbnailSize,
                    modifier = Modifier.settingsSearchAnchor("prefs__media__sticker_thumbnail_size"),
                    title = stringRes(R.string.prefs__media__sticker_thumbnail_size),
                    valueLabel = { size -> "$size dp" },
                    min = 56,
                    max = 160,
                    stepIncrement = 4,
                )
                DialogSliderPreference(
                    prefs.sticker.historyRecentMaxSize,
                    modifier = Modifier.settingsSearchAnchor("prefs__media__sticker_history_recent_max_size"),
                    title = stringRes(R.string.prefs__media__sticker_history_recent_max_size),
                    valueLabel = { maxSize ->
                        pluralsRes(R.plurals.unit__items__written, maxSize, "v" to maxSize)
                    },
                    min = 1,
                    max = 50,
                    stepIncrement = 1,
                )
                SwitchPreference(
                    prefs.sticker.confirmBeforeInsert,
                    modifier = Modifier.settingsSearchAnchor("prefs__media__sticker_confirm_before_insert"),
                    title = stringRes(R.string.prefs__media__sticker_confirm_before_insert),
                    summary = stringRes(R.string.prefs__media__sticker_confirm_before_insert__summary),
                )
                Preference(
                    modifier = Modifier.settingsSearchAnchor("prefs__media__sticker_pinned_reset"),
                    title = stringRes(R.string.prefs__media__sticker_pinned_reset),
                    onClick = { scope.launch { StickerHistoryHelper.clearPinned(prefs) } },
                )
                Preference(
                    modifier = Modifier.settingsSearchAnchor("prefs__media__sticker_history_reset"),
                    title = stringRes(R.string.prefs__media__sticker_history_reset),
                    onClick = { scope.launch { StickerHistoryHelper.clearRecent(prefs) } },
                )
                Preference(
                    icon = Icons.Outlined.LinkOff,
                    modifier = Modifier.settingsSearchAnchor("prefs__media__sticker_folder_clear"),
                    title = stringRes(R.string.prefs__media__sticker_folder_clear),
                    onClick = {
                        scope.launch {
                            releaseStickerFolder(context, stickerFolderUri, keep = "")
                            withContext(Dispatchers.IO) { StickerScanner.clearCached(context) }
                            prefs.sticker.folderUri.set("")
                            prefs.sticker.folderName.set("")
                        }
                    },
                )
            }
        }

        if (sourcePickerOpen) {
            StickerSourceDialog(
                onPick = { source ->
                    sourcePickerOpen = false
                    pendingSource = source
                    imagePicker.launch(StickerImportMimeTypes)
                },
                onBrowse = {
                    sourcePickerOpen = false
                    pendingSource = null
                    imagePicker.launch(StickerImportMimeTypes)
                },
                onDismiss = { sourcePickerOpen = false },
            )
        }
        if (packManagerOpen) {
            StickerPackDialog(
                folderUri = stickerFolderUri,
                onDismiss = { packManagerOpen = false },
            )
        }
    }

    DeleteEmojiHistoryConfirmDialog(
        shouldDelete = shouldDelete,
        onDismiss = {
            shouldDelete = null
        },
        onConfirm = {
            shouldDelete?.let {
                scope.launch {
                    if (it.pinned) {
                        EmojiHistoryHelper.deletePinned(prefs = prefs)
                    } else {
                        EmojiHistoryHelper.deleteHistory(prefs = prefs)
                    }
                }
                shouldDelete = null
            }
        },
    )

    if (gifSetupOpen) {
        GifSetupDialog(
            initialKey = prefs.gif.klipyApiKey.get(),
            onSave = { key ->
                scope.launch { prefs.gif.klipyApiKey.set(key.trim()) }
                gifSetupOpen = false
            },
            onDismiss = { gifSetupOpen = false },
        )
    }
}

/**
 * A short, non-technical walkthrough for setting up GIF search: explains that KLIPY is a free
 * service the user brings their own key for, links to the sign-up page, and lets them paste the key.
 */
@Composable
private fun GifSetupDialog(
    initialKey: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val enabled by prefs.gif.enabled.collectAsState()
    var key by remember { mutableStateOf(initialKey) }
    var reveal by remember { mutableStateOf(false) }
    JetPrefAlertDialog(
        scrollModifier = florisDialogScroll(),
        title = stringRes(R.string.prefs__media__gif_setup__title),
        confirmLabel = stringRes(R.string.action__save),
        dismissLabel = stringRes(R.string.action__cancel),
        onConfirm = { onSave(key) },
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // On/off switch lives here (the settings list has a single row).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringRes(R.string.prefs__media__gif_enabled),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { scope.launch { prefs.gif.enabled.set(it) } },
                )
            }
            Text(stringRes(R.string.prefs__media__gif_setup__intro))
            GifSetupStep(1, stringRes(R.string.prefs__media__gif_setup__step1))
            GifSetupStep(2, stringRes(R.string.prefs__media__gif_setup__step2))
            GifSetupStep(3, stringRes(R.string.prefs__media__gif_setup__step3))
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, "https://partner.klipy.com/api-keys".toUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(stringRes(R.string.prefs__media__gif_setup__open_klipy))
            }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringRes(R.string.prefs__media__gif_setup__key_label)) },
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(
                            if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = null,
                        )
                    }
                },
            )
            Text(
                text = stringRes(R.string.prefs__media__gif_setup__privacy_note),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun GifSetupStep(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = "$number.",
            modifier = Modifier.padding(end = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun DeleteEmojiHistoryConfirmDialog(
    shouldDelete: ShouldDelete?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    shouldDelete?.let {
        JetPrefAlertDialog(
            scrollModifier = florisDialogScroll(),
            title = stringRes(R.string.action__reset_confirm_title),
            confirmLabel = stringRes(R.string.action__yes),
            dismissLabel = stringRes(R.string.action__no),
            onDismiss = onDismiss,
            onConfirm = onConfirm,
        ) {
            if (it.pinned) {
                Text(stringRes(R.string.action__reset_confirm_message, "name" to "pinned emojis"))
            } else {
                Text(stringRes(R.string.action__reset_confirm_message, "name" to "emoji history"))
            }

        }
    }
}

data class ShouldDelete(val pinned: Boolean)

/** The image types the sticker panel can render, as the file picker wants them. */
private val StickerImportMimeTypes =
    arrayOf("image/png", "image/webp", "image/gif", "image/jpeg")

/**
 * A multi-select image picker that opens somewhere specific.
 *
 * `EXTRA_INITIAL_URI` is a hint and nothing more: if the folder is not there — no WhatsApp, WhatsApp
 * Business, stickers that never left the app's own database — the picker opens where it normally
 * would. That is the whole reason this is worth doing: at best it saves the user four taps, at worst
 * it costs nothing.
 */
private class OpenImagesStartingAt(
    private val initial: () -> Uri?,
) : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).also { intent ->
            // Read at launch time, not at construction: one launcher then serves every source in the
            // list, instead of one launcher per entry that all do the same thing.
            initial()?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
}

/**
 * Copies picked images into the sticker folder and says what happened.
 *
 * The picker grants read access for this call only, which is exactly enough: the files are copied
 * immediately and never referred to again by their original URI.
 */
private fun importStickers(
    context: Context,
    folderUri: String,
    sources: List<Uri>,
) {
    if (sources.isEmpty() || folderUri.isBlank()) return
    if (!StickerWriter.canWrite(context, folderUri)) {
        context.showLongToastSync(R.string.sticker__import_needs_write)
        return
    }
    // Handed to an application-scoped object rather than run in the screen's own scope: copying a few
    // hundred stickers takes minutes, and leaving the screen in the middle should not throw that away.
    StickerImports.start(context, folderUri.toUri(), sources) { result ->
        context.showLongToastSync(stickerImportSummary(context, result))
    }
}

/**
 * Gives back the persisted read permission on a sticker folder that is no longer in use.
 *
 * Persisted URI grants are a limited, system-wide resource and they survive until released, so picking a
 * new folder half a dozen times would otherwise leave half a dozen folders permanently readable by this
 * app. [keep] is the URI that must stay — passing the newly picked one covers re-picking the same folder,
 * where releasing first would revoke the grant that was just taken.
 */
private fun releaseStickerFolder(context: Context, previous: String, keep: String) {
    if (previous.isBlank() || previous == keep) return
    try {
        context.contentResolver.releasePersistableUriPermission(
            previous.toUri(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    } catch (e: SecurityException) {
        // Already gone — the user revoked it, or the folder was removed. Nothing to release.
    }
}

/**
 * Where stickers can be fetched from, as far as Android allows.
 *
 * Every entry is a *hint* for the file picker, never a promise: if the folder is not there the picker
 * opens where it normally would, which is exactly what "Add stickers" does anyway. That is why the
 * list can name places that may not exist without misleading anyone.
 */
private data class StickerSource(val labelRes: Int, val uri: Uri)

@Composable
private fun StickerSourceDialog(
    onPick: (Uri) -> Unit,
    onBrowse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sources = remember {
        listOf(
            StickerSource(R.string.sticker__source_whatsapp, StickerWriter.mediaFolderHint("com.whatsapp", "WhatsApp/Media/WhatsApp Stickers")),
            StickerSource(R.string.sticker__source_whatsapp_business, StickerWriter.mediaFolderHint("com.whatsapp.w4b", "WhatsApp Business/Media/WhatsApp Business Stickers")),
            StickerSource(R.string.sticker__source_telegram, StickerWriter.mediaFolderHint("org.telegram.messenger", "Telegram")),
            StickerSource(R.string.sticker__source_threema, StickerWriter.mediaFolderHint("ch.threema.app", "Threema")),
            StickerSource(R.string.sticker__source_signal, StickerWriter.mediaFolderHint("org.thoughtcrime.securesms", "Signal/Media")),
            StickerSource(R.string.sticker__source_stickers_folder, StickerWriter.publicFolderHint("Pictures/Stickers")),
            StickerSource(R.string.sticker__source_downloads, StickerWriter.publicFolderHint("Download")),
            StickerSource(R.string.sticker__source_pictures, StickerWriter.publicFolderHint("Pictures")),
            StickerSource(R.string.sticker__source_dcim, StickerWriter.publicFolderHint("DCIM")),
        )
    }
    JetPrefAlertDialog(
        scrollModifier = florisDialogScroll(),
        title = stringRes(R.string.prefs__media__sticker_add),
        dismissLabel = stringRes(R.string.action__cancel),
        onDismiss = onDismiss,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBrowse() }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                Text(
                    text = stringRes(R.string.sticker__source_browse),
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            for (source in sources) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(source.uri) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = null)
                    Text(
                        text = stringRes(source.labelRes),
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            Text(
                text = stringRes(R.string.sticker__source_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * Creating, renaming, reordering and deleting sticker packs — which are the subfolders of the chosen
 * folder.
 *
 * Lives in settings rather than in the keyboard panel for one practical reason: naming a pack means
 * typing, and the panel *is* the keyboard. Moving a sticker into an existing pack needs no text and
 * stays where it belongs, on the sticker's own long-press menu. The tab order joined it here for a
 * different reason (issue #317): it is a list of packs, which this dialog already is, where the panel
 * would have needed a mode of its own for something done once.
 *
 * Reordering is a long press and a drag, the same gesture the prompt list uses, and that is what keeps
 * the row readable: a pair of arrows would be two more buttons on a line that already could not fit a
 * name beside four of them.
 */
@Composable
private fun StickerPackDialog(
    folderUri: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by FlorisPreferenceStore
    val packSettings by prefs.sticker.packSettings.collectAsState()
    var index by remember { mutableStateOf<StickerIndex?>(null) }
    var reload by remember { mutableStateOf(0) }
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<StickerCategory?>(null) }
    var deleting by remember { mutableStateOf<StickerCategory?>(null) }
    val treeUri = remember(folderUri) { folderUri.toUri() }

    LaunchedEffect(folderUri, reload) {
        index = withContext(Dispatchers.IO) {
            runCatching { StickerScanner.scan(context, treeUri) }.getOrNull()
        }
        index?.let { withContext(Dispatchers.IO) { StickerScanner.saveCached(context, it) } }
    }

    // Shown in the order the tabs are in — a list you rearrange has to start out looking like the thing
    // it rearranges. Held in a mutable list so a dragged row can move under the finger; the preference
    // is written once, on drop.
    val packs = remember(index, packSettings) {
        StickerPackSettings
            .ordered(index?.categories.orEmpty(), packSettings.order)
            .filter { it.id != StickerCategory.ROOT_ID }
            .toMutableStateList()
    }
    // Where the dragged pack started, and how far the finger has taken it. The list itself is left
    // alone until the drop — see the comment on the rows for why that matters.
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(0) }
    // Where it would land if let go now. A function rather than a value, because the drag callbacks
    // below outlive the composition that created them: a captured value would be the one from when
    // the gesture handler started, which is none.
    fun dropTarget(): Int = if (dragFrom < 0 || rowHeight <= 0) {
        -1
    } else {
        (dragFrom + (dragOffset / rowHeight).roundToInt()).coerceIn(0, packs.lastIndex)
    }
    val dragTo = dropTarget()

    // The create/rename button belongs where every other dialog puts its action: on the button row at
    // the bottom, beside Cancel. It doubles as "rename" while a pack is being edited.
    JetPrefAlertDialog(
        scrollModifier = florisDialogScroll(),
        title = stringRes(R.string.prefs__media__sticker_packs),
        confirmLabel = stringRes(if (renaming != null) R.string.sticker__pack_rename else R.string.sticker__pack_new),
        confirmEnabled = newName.isNotBlank(),
        onConfirm = {
            val name = newName.trim()
            val target = renaming
            scope.launch {
                val ok = if (target != null) {
                    StickerWriter.renamePack(context, treeUri, target.id, name)
                } else {
                    StickerWriter.createPack(context, treeUri, name) != null
                }
                // A rename is the one moment the old and the new name are both known. Miss it and the
                // pack's place in the row and its chosen picture are simply gone, since both are kept
                // under the name — the document id is not the same one after a rename.
                if (ok && target != null) {
                    StickerPackSettingsHelper.renamed(prefs, target.name, name)
                }
                if (!ok) context.showLongToast(R.string.sticker__pack_failed)
                newName = ""
                renaming = null
                reload++
            }
        },
        dismissLabel = stringRes(R.string.action__cancel),
        onDismiss = onDismiss,
    ) {
        Column {
            if (index == null) {
                Text(text = stringRes(R.string.sticker__packs_loading))
            } else if (packs.isEmpty()) {
                Text(
                    text = stringRes(R.string.sticker__packs_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (packs.size > 1) {
                Text(
                    text = stringRes(R.string.sticker__pack_reorder_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            for ((position, pack) in packs.withIndex()) key(pack.name) {
                val isDragging = dragFrom == position
                // How far this row steps aside for the one being dragged: the rows between where it
                // started and where it would land shuffle up or down by exactly one row, and they
                // animate there, so the list opens a gap rather than snapping into a new order.
                val displaced = when {
                    isDragging || dragFrom < 0 || rowHeight <= 0 -> 0f
                    dragFrom < dragTo && position in (dragFrom + 1)..dragTo -> -rowHeight.toFloat()
                    dragFrom > dragTo && position in dragTo until dragFrom -> rowHeight.toFloat()
                    else -> 0f
                }
                val shift by animateFloatAsState(displaced, label = "packShift")
                // **The list is not touched while the finger is down.** Reordering it mid-gesture
                // changed which pack each slot rendered, which changed the key of `pointerInput`
                // below, which tore down the running gesture — the row jumped once and then went
                // dead. Only the drawing moves; the order is written on the drop.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragOffset else shift }
                        .onSizeChanged { rowHeight = it.height }
                        .pointerInput(pack.name, packs.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragFrom = position; dragOffset = 0f },
                                onDragEnd = {
                                    val from = dragFrom
                                    val to = dropTarget()
                                    dragFrom = -1
                                    dragOffset = 0f
                                    if (from >= 0 && to >= 0 && from != to) {
                                        packs.add(to, packs.removeAt(from))
                                        scope.launch {
                                            StickerPackSettingsHelper.setOrder(prefs, packs.map { it.name })
                                        }
                                    }
                                },
                                onDragCancel = {
                                    dragFrom = -1
                                    dragOffset = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    // Held to the list's own extent, so a pack cannot be dragged out
                                    // over the rest of the dialog.
                                    val limitUp = -position.toFloat() * rowHeight
                                    val limitDown = (packs.lastIndex - position).toFloat() * rowHeight
                                    dragOffset = (dragOffset + amount.y).coerceIn(limitUp, limitDown)
                                },
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (packs.size > 1) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pack.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = pluralsRes(
                                R.plurals.unit__items__written,
                                pack.items.size,
                                "v" to pack.items.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { renaming = pack; newName = pack.name }) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringRes(R.string.sticker__pack_rename))
                    }
                    IconButton(onClick = { deleting = pack }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringRes(R.string.sticker__pack_delete))
                    }
                }
            }
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = {
                    Text(stringRes(if (renaming != null) R.string.sticker__pack_rename else R.string.sticker__pack_name))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }

    deleting?.let { pack ->
        JetPrefAlertDialog(
            scrollModifier = florisDialogScroll(),
            title = stringRes(R.string.sticker__pack_delete),
            confirmLabel = stringRes(R.string.action__yes),
            dismissLabel = stringRes(R.string.action__no),
            onDismiss = { deleting = null },
            onConfirm = {
                scope.launch {
                    // Deleting a pack deletes a real folder, and the stickers in it go with it — hence
                    // the count in the question rather than a bare "are you sure".
                    if (StickerWriter.deletePack(context, treeUri, pack.id)) {
                        // Otherwise the name keeps its place in the tab order, holding a slot open for
                        // a folder that no longer exists.
                        StickerPackSettingsHelper.forget(prefs, pack.name)
                    } else {
                        context.showLongToast(R.string.sticker__pack_failed)
                    }
                    deleting = null
                    reload++
                }
            },
        ) {
            Text(
                stringRes(
                    R.string.sticker__pack_delete_confirm,
                    "name" to pack.name,
                    "n" to pack.items.size,
                )
            )
        }
    }
}
