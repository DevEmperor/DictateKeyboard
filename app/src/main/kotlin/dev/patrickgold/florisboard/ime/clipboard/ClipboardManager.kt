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

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardHistoryDao
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardHistoryDatabase
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.AndroidClipboardManager
import org.florisboard.lib.android.AndroidClipboardManager_OnPrimaryClipChangedListener
import org.florisboard.lib.android.clearPrimaryClipAnyApi
import org.florisboard.lib.android.setOrClearPrimaryClip
import org.florisboard.lib.android.showShortToastSync
import org.florisboard.lib.android.systemService
import org.florisboard.lib.kotlin.tryOrNull

/**
 * What a callback from the system clipboard actually reports (issue #352).
 *
 * The system can deliver more than one callback for a single copy, and by content alone that is
 * indistinguishable from the user copying the same text a second time — which is why both used to be
 * dropped. Dropping the second one costs the suggestion chip: the clip keeps the timestamp of the first
 * copy, so a chip that was already used or dismissed stays retired and its timeout keeps running, for a
 * clip the user has just re-copied precisely because nothing showed up the first time.
 *
 * The discriminator is the stamp the system writes on every `setPrimaryClip`
 * ([android.content.ClipDescription.getTimestamp]): one copy event carries one stamp, however many
 * callbacks it produces. Where no stamp is available (0 on both sides) the old content comparison stands.
 */
internal enum class SystemClipEvent {
    /** The same copy event, reported again. Nothing to do. */
    DUPLICATE_CALLBACK,

    /** The same content, copied again — the clip is unchanged, only its recency is not. */
    REPEATED_COPY,

    /** Something else is on the clipboard now, or it was cleared. */
    NEW_CLIP,
}

/** The parts of a system clip that decide which [SystemClipEvent] a callback is. */
internal data class SystemClipFingerprint(
    val text: String?,
    val uri: String?,
    val timestamp: Long,
)

/** @see SystemClipEvent */
internal fun classifySystemClipEvent(
    last: SystemClipFingerprint?,
    new: SystemClipFingerprint?,
): SystemClipEvent = when {
    last == null || new == null -> SystemClipEvent.NEW_CLIP
    last.text != new.text || last.uri != new.uri -> SystemClipEvent.NEW_CLIP
    last.timestamp == new.timestamp -> SystemClipEvent.DUPLICATE_CALLBACK
    else -> SystemClipEvent.REPEATED_COPY
}

/**
 * [ClipboardManager] manages the clipboard and clipboard history.
 *
 * Also just going to document how all the classes here work.
 *
 * [ClipboardManager] handles storage and retrieval of clipboard items. All manipulation of the
 * clipboard goes through here.
 */
class ClipboardManager(
    context: Context,
) : AndroidClipboardManager_OnPrimaryClipChangedListener, Closeable {
    companion object {
        // 1 minute
        private const val INTERVAL = 60 * 1000L

        /**
         * Taken from ClipboardDescription.java from the AOSP
         *
         * Helper to compare two MIME types, where one may be a pattern.
         * @param concreteType A fully-specified MIME type.
         * @param desiredType A desired MIME type that may be a pattern such as * / *.
         * @return Returns true if the two MIME types match.
         */
        fun compareMimeTypes(concreteType: String, desiredType: String): Boolean {
            val typeLength = desiredType.length
            if (typeLength == 3 && desiredType == "*/*") {
                return true
            }
            val slashpos = desiredType.indexOf('/')
            if (slashpos > 0) {
                if (typeLength == slashpos + 2 && desiredType[slashpos + 1] == '*') {
                    if (desiredType.regionMatches(0, concreteType, 0, slashpos + 1)) {
                        return true
                    }
                } else if (desiredType == concreteType) {
                    return true
                }
            }
            return false
        }
    }

    private val prefs by FlorisPreferenceStore
    private val appContext by context.appContext()
    private val editorInstance by context.editorInstance()
    private val systemClipboardManager = context.systemService(AndroidClipboardManager::class)

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cleanUpJob: Job
    private var clipHistoryDb: ClipboardHistoryDatabase? = null
    private val clipHistoryDao: ClipboardHistoryDao? get() = clipHistoryDb?.clipboardItemDao()

    val historyFlow: StateFlow<ClipboardHistory>
        field = MutableStateFlow(ClipboardHistory.EMPTY)
    val currentHistory: ClipboardHistory
        get() = historyFlow.value

    private val primaryClipLastFromCallbackGuard = Mutex(locked = false)
    private var primaryClipLastFromCallback: ClipData? = null
    val primaryClipFlow: StateFlow<ClipboardItem?>
        field = MutableStateFlow(null)
    inline var primaryClip
        get() = primaryClipFlow.value
        private set(v) {
            primaryClipFlow.value = v
        }

    init {
        systemClipboardManager.addPrimaryClipChangedListener(this)
        seedPrimaryClipFromSystem()
        cleanUpJob = ioScope.launch {
            while (isActive) {
                delay(INTERVAL)
                enforceExpiryDate(currentHistory)
            }
        }
    }

    fun initializeForContext(context: Context) {
        ioScope.launch {
            if (clipHistoryDb == null) {
                clipHistoryDb = ClipboardHistoryDatabase.new(context.applicationContext)
                withContext(Dispatchers.Main) {
                    clipHistoryDao?.getAllAsFlow()?.collect { items ->
                        updateHistory(items)
                    }
                }
            }
        }
    }

    private fun updateHistory(items: List<ClipboardItem>) {
        val itemsSorted = items.sortedByDescending { it.creationTimestampMs }
        val clipHistory = ClipboardHistory(itemsSorted)
        enforceHistoryLimit(clipHistory)
        historyFlow.value = clipHistory
    }

    /**
     * Sets the current primary clip without updating the internal clipboard history.
     */
    fun updatePrimaryClip(item: ClipboardItem?) {
        primaryClip = item
        if (prefs.clipboard.useInternalClipboard.get()) {
            val syncBehavior = prefs.clipboard.syncToSystem.get()
            val clipData = item?.toClipData(appContext)
            if (clipData != null && syncBehavior.shouldSyncSet) {
                systemClipboardManager.setPrimaryClip(clipData)
            } else if (clipData == null && syncBehavior.shouldSyncClear) {
                systemClipboardManager.clearPrimaryClipAnyApi()
            }
        } else {
            systemClipboardManager.setOrClearPrimaryClip(item?.toClipData(appContext))
        }
    }

    /**
     * Called by system clipboard when the system primary clip has changed.
     */
    override fun onPrimaryClipChanged() {
        val syncBehavior = prefs.clipboard.syncToFloris.get()
        if (!prefs.clipboard.useInternalClipboard.get() || syncBehavior != ClipboardSyncBehavior.NO_EVENTS) {
            val systemPrimaryClip = systemClipboardManager.primaryClip
            ioScope.launch {
                val event: SystemClipEvent
                primaryClipLastFromCallbackGuard.withLock {
                    event = classifySystemClipEvent(
                        last = primaryClipLastFromCallback.fingerprint(),
                        new = systemPrimaryClip.fingerprint(),
                    )
                    primaryClipLastFromCallback = systemPrimaryClip
                }
                when (event) {
                    SystemClipEvent.DUPLICATE_CALLBACK -> return@launch
                    // A repeat only needs a new timestamp. If it turns out we are not holding that clip
                    // at all — cleared away, or copied while we were not listening — it is new work after
                    // all and takes the path below.
                    SystemClipEvent.REPEATED_COPY -> if (refreshPrimaryClipRecency(systemPrimaryClip)) {
                        return@launch
                    }
                    SystemClipEvent.NEW_CLIP -> Unit
                }

                val internalPrimaryClip = primaryClip

                if (systemPrimaryClip == null) {
                    if (syncBehavior.shouldSyncClear) {
                        primaryClip = null
                    }
                    return@launch
                }

                if (systemPrimaryClip.getItemAt(0).let { it.text == null && it.uri == null }) {
                    if (syncBehavior.shouldSyncClear) {
                        primaryClip = null
                    }
                    return@launch
                }

                if (!syncBehavior.shouldSyncSet) {
                    return@launch
                }

                val isEqual = internalPrimaryClip?.isEqualTo(systemPrimaryClip) == true
                if (!isEqual) {
                    val item = ClipboardItem.fromClipData(appContext, systemPrimaryClip, cloneUri = true)
                    primaryClip = item
                    insertOrMoveBeginning(item)
                }
            }
        }
    }

    /** The parts of a [ClipData] that [classifySystemClipEvent] compares. */
    private fun ClipData?.fingerprint(): SystemClipFingerprint? {
        val data = this ?: return null
        val item = tryOrNull { data.getItemAt(0) } ?: return null
        return SystemClipFingerprint(
            // Compared as strings on purpose: the same text can come back as a different CharSequence
            // implementation, and two of those are unequal however identical they read.
            text = item.text?.toString(),
            uri = item.uri?.toString(),
            timestamp = tryOrNull { data.description?.timestamp } ?: 0L,
        )
    }

    /**
     * Restamps the current primary clip after the same content was copied a second time (issue #352).
     *
     * Only the recency changes. The item is the same one, and so is the history row it may already have —
     * moving that row back to the top would be a change to the history nobody asked for, while the chip's
     * idea of "just copied" is the part that was reported broken.
     *
     * Returns false when there is no such clip to restamp, which makes the repeat new work instead.
     */
    private fun refreshPrimaryClipRecency(systemPrimaryClip: ClipData?): Boolean {
        val current = primaryClip ?: return false
        if (!(current isEqualTo systemPrimaryClip)) return false
        primaryClip = current.copy(creationTimestampMs = System.currentTimeMillis())
        return true
    }

    /**
     * Adopts whatever is already on the system clipboard when this manager comes up (issue #352).
     *
     * The listener is the only source of clips there is, and it can only report changes that happen while
     * this process is alive. An IME process gets killed freely, so a clip copied while it was gone stayed
     * invisible until the next copy: the paste key acted as if the clipboard were empty, and no suggestion
     * appeared for something demonstrably on the clipboard.
     *
     * Its age comes from the system's own stamp rather than from now, so a clip copied an hour ago cannot
     * present itself as fresh and put a chip — possibly of something private — on the strip merely because
     * the keyboard was opened. Where there is no stamp the clip counts as old.
     *
     * Text only: the media path clones the uri into our own provider, and that is a file written for a
     * clip the user has not asked us to do anything with. The next copy event picks media up.
     */
    private fun seedPrimaryClipFromSystem() {
        ioScope.launch {
            // The preference store loads asynchronously while this manager is being constructed, and
            // seeding against a default the user has overridden would read a clipboard they asked us to
            // leave alone.
            appContext.preferenceStoreLoaded.first { it }
            if (!prefs.clipboard.syncToFloris.get().shouldSyncSet) return@launch
            val systemPrimaryClip = tryOrNull { systemClipboardManager.primaryClip } ?: return@launch
            val item = tryOrNull {
                ClipboardItem.fromClipData(appContext, systemPrimaryClip, cloneUri = false)
            } ?: return@launch
            if (item.type != ItemType.TEXT || item.text.isNullOrBlank()) return@launch
            val timestamp = tryOrNull { systemPrimaryClip.description?.timestamp } ?: 0L
            primaryClipLastFromCallbackGuard.withLock {
                // A copy that arrived while we were reading is the newer truth: this fills a gap, it never
                // overwrites.
                if (primaryClip != null || primaryClipLastFromCallback != null) return@launch
                primaryClipLastFromCallback = systemPrimaryClip
                primaryClip = item.copy(creationTimestampMs = timestamp)
            }
        }
    }

    /**
     * Change the current text on clipboard, update history (if enabled).
     */
    private fun addNewClip(item: ClipboardItem) {
        insertOrMoveBeginning(item)
        updatePrimaryClip(item)
    }

    /**
     * Wraps some plaintext in a ClipData and calls [addNewClip]
     */
    fun addNewPlaintext(newText: String) {
        val newData = ClipboardItem.text(newText)
        addNewClip(newData)
    }

    /**
     * Copies a media item (given as a readable content [uri], e.g. a downloaded GIF) into the
     * clipboard history and the system primary clip, so it can be pasted into apps that accept
     * images from the clipboard. Used as a fallback when the target editor does not support the
     * Commit Content API. Returns false if the item could not be built.
     */
    fun copyMediaToClipboard(uri: Uri, mimeType: String): Boolean {
        return try {
            val clip = ClipData(
                ClipDescription("media file", arrayOf(mimeType)),
                ClipData.Item(uri),
            )
            val item = ClipboardItem.fromClipData(appContext, clip, cloneUri = true)
            addNewClip(item)
            true
        } catch (e: Exception) {
            flogError { "Failed to copy media to clipboard: ${e.message}" }
            false
        }
    }

    /**
     * Adds a new item to the clipboard history (if enabled).
     */
    private fun insertOrMoveBeginning(newItem: ClipboardItem) {
        if (prefs.clipboard.historyEnabled.get()) {
            val historyElement = currentHistory.all.firstOrNull { item ->
                item.type == ItemType.TEXT && item.text == newItem.text && item.isSensitive == newItem.isSensitive
            }
            if (historyElement != null) {
                moveToTheBeginning(
                    oldItem = historyElement,
                    newItem = if (historyElement.isPinned) {
                        newItem.copy(isPinned = true)
                    } else {
                        newItem
                    }
                )
            } else {
                insertClip(newItem)
            }
        }
    }

    private fun enforceHistoryLimit(clipHistory: ClipboardHistory) {
        if (prefs.clipboard.historySizeLimitEnabled.get()) {
            val nonPinnedItems = clipHistory.recent + clipHistory.other
            val nToRemove = nonPinnedItems.size - prefs.clipboard.historySizeLimit.get()
            if (nToRemove > 0) {
                val itemsToRemove = nonPinnedItems.asReversed().filterIndexed { n, _ -> n < nToRemove }
                deleteWithMedia(itemsToRemove)
            }
        }
    }

    private fun enforceExpiryDate(clipHistory: ClipboardHistory) {
        val itemsToRemove = mutableSetOf<ClipboardItem>()
        if (prefs.clipboard.historyAutoCleanOldEnabled.get()) {
            val nonPinnedItems = clipHistory.recent + clipHistory.other
            val expiryTime = System.currentTimeMillis() - (prefs.clipboard.historyAutoCleanOldAfter.get() * 60 * 1000)
            itemsToRemove.addAll(nonPinnedItems.filter { it.creationTimestampMs < expiryTime })
        }
        if (prefs.clipboard.historyAutoCleanSensitiveEnabled.get()) {
            val sensitiveData = clipHistory.all.filter { it.isSensitive }
            val expiryTime = System.currentTimeMillis() - (prefs.clipboard.historyAutoCleanSensitiveAfter.get() * 1000)
            itemsToRemove.addAll(sensitiveData.filter { it.creationTimestampMs < expiryTime })
        }
        deleteWithMedia(itemsToRemove.toList())
    }

    /**
     * Removes [items] from the history and takes their media files with them.
     *
     * The two automatic clean-ups above used to delete the database row and nothing else, so every
     * image and video that aged or fell out of the size limit left its cloned file behind in the
     * app's own storage: invisible in the panel, unreachable from anywhere, and collected by
     * nothing. With the size limit on by default that is the normal path out of the history, which
     * made it the one leak that grows on its own (issue #316).
     */
    private fun deleteWithMedia(items: List<ClipboardItem>) {
        if (items.isEmpty()) return
        ioScope.launch {
            // Row first, file second. If anything goes wrong between the two, what is left over is an
            // unreferenced file — invisible and cleared by the next full wipe — rather than an entry
            // in the panel whose picture has already been deleted underneath it.
            clipHistoryDao?.delete(items)
            for (item in items) {
                item.close(appContext)
            }
        }
    }

    private fun moveToTheBeginning(oldItem: ClipboardItem, newItem: ClipboardItem) {
        ioScope.launch {
            clipHistoryDao?.delete(oldItem.id)
            clipHistoryDao?.insert(newItem)
        }
    }

    fun insertClip(item: ClipboardItem) {
        ioScope.launch {
            val id = clipHistoryDao?.insert(item)
            item.id = id ?: 0
        }
    }

    fun clearExactHistory(items: List<ClipboardItem>) {
        deleteWithMedia(items)
    }

    /**
     * Clears all unpinned items from the clipboard history.
     *
     * Only the unpinned ones are closed. Closing every item and then deleting only the unpinned rows
     * left a pinned image as an entry whose file had just been deleted underneath it — the one thing
     * pinning is supposed to prevent (issue #316).
     */
    fun clearHistory() {
        ioScope.launch {
            for (item in currentHistory.unpinned) {
                item.close(appContext)
            }
            clipHistoryDao?.deleteAllUnpinned()
        }
    }

    /**
     * Clears the full clipboard history
     */
    fun clearFullHistory() {
        ioScope.launch {
            for (item in currentHistory.all) {
                item.close(appContext)
            }
            clipHistoryDao?.deleteAll()
        }
    }


    /**
     * Restore the clipboard history from a [List]
     *
     * @param items the [ClipboardItem] list with the new items
     */
    fun restoreHistory(items: List<ClipboardItem>) {
        ioScope.launch {
            val currentHistory = currentHistory.all
            for (item in items) {
                if (!currentHistory.map { it.copy(id = 0) }.contains(item.copy(id = 0))) {
                    insertClip(item.copy(id = 0))
                }
            }
        }
    }

    fun deleteClip(item: ClipboardItem, onlyIfUnpinned: Boolean) {
        ioScope.launch {
            if (onlyIfUnpinned) {
                clipHistoryDao?.deleteIfUnpinned(item.id)
            } else {
                clipHistoryDao?.delete(item.id)
            }
            tryOrNull {
                val uri = item.uri
                if (uri != null) {
                    appContext.contentResolver.delete(uri, null, null)
                }
            }
        }
    }

    fun pinClip(item: ClipboardItem) {
        ioScope.launch {
            clipHistoryDao?.update(item.copy(isPinned = true))
        }
    }

    fun unpinClip(item: ClipboardItem) {
        ioScope.launch {
            clipHistoryDao?.update(item.copy(isPinned = false))
        }
    }

    fun pasteItem(item: ClipboardItem) {
        val editorInstance by appContext.editorInstance()
        editorInstance.commitClipboardItem(item).also { result ->
            if (!result) {
                appContext.showShortToastSync("Failed to paste item.")
            }
        }
    }

    /**
     * Returns true if the editor can accept the clip item, else false.
     */
    fun canBePasted(clipItem: ClipboardItem?): Boolean {
        if (clipItem == null) return false

        return clipItem.mimeTypes.contains("text/plain") || editorInstance.activeInfo.contentMimeTypes.any { editorType ->
            clipItem.mimeTypes.any { clipType ->
                compareMimeTypes(clipType, editorType)
            }
        }
    }

    /**
     * Cleans up.
     *
     * Unregisters the system clipboard listener, cancels clipboard clean ups.
     */
    override fun close() {
        systemClipboardManager.removePrimaryClipChangedListener(this)
        cleanUpJob.cancel()
    }
}
