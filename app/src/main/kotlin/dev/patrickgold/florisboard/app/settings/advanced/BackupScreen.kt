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

package dev.patrickgold.florisboard.app.settings.advanced

import android.content.ContentUris
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.cacheManager
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.dictate.data.history.DictateHistoryStore
import dev.patrickgold.florisboard.dictate.data.prompts.PromptsDatabaseHelper
import dev.patrickgold.florisboard.dictate.symbols.CustomSymbolsStore
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.lib.cache.CacheManager
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.ext.ExtensionManager
import dev.patrickgold.florisboard.lib.io.FileRegistry
import dev.patrickgold.florisboard.lib.io.ZipUtils
import dev.patrickgold.jetpref.datastore.runtime.AndroidAppDataStorage
import dev.patrickgold.jetpref.datastore.runtime.DataStoreWriter
import dev.patrickgold.jetpref.datastore.runtime.FileBasedStorage
import dev.patrickgold.jetpref.material.ui.JetPrefListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.florisboard.lib.android.showLongToast
import org.florisboard.lib.android.showLongToastSync
import org.florisboard.lib.android.writeFromFile
import org.florisboard.lib.compose.FlorisButtonBar
import org.florisboard.lib.compose.FlorisInfoCard
import org.florisboard.lib.compose.FlorisOutlinedBox
import org.florisboard.lib.compose.FlorisWarningCard
import org.florisboard.lib.compose.defaultFlorisOutlinedBox
import org.florisboard.lib.compose.rippleClickable
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.kotlin.io.subDir
import org.florisboard.lib.kotlin.io.subFile
import org.florisboard.lib.kotlin.io.writeJson
import dev.patrickgold.florisboard.ime.dictionary.DictionaryManager
import dev.patrickgold.florisboard.ime.dictionary.LearnedWordsStore

object Backup {
    const val FILE_PROVIDER_AUTHORITY = "${BuildConfig.APPLICATION_ID}.provider.file"
    const val METADATA_JSON_NAME = "backup_metadata.json"
    const val CLIPBOARD_TEXT_ITEMS_JSON_NAME = "clipboard_text_items.json"
    const val CLIPBOARD_IMAGES_JSON_NAME = "clipboard_images.json"
    const val CLIPBOARD_VIDEO_JSON_NAME = "clipboard_video.json"
    // Dictate-specific: the user's saved rewording prompts (the `prompts.db` table), exported as
    // JSON rather than the raw SQLite file so the archive stays WAL-/lock-/version-independent.
    const val DICTATE_PROMPTS_JSON_NAME = "dictate_prompts.json"
    // Dictate transcription history (the `dictate_history` Room table), exported as JSON (same rationale);
    // any retained audio WAVs go into DICTATE_HISTORY_AUDIO_DIR named by the entry id.
    const val DICTATE_HISTORY_JSON_NAME = "dictate_history.json"
    const val DICTATE_HISTORY_AUDIO_DIR = "dictate_history_audio"
    // The user's own vocabulary (issue #318): the words they added by hand and the ones the keyboard
    // picked up from their typing, plus the word pairs behind personal next-word prediction. Exported as
    // JSON for the same reason as everything above — the archive stays independent of SQLite versions,
    // WAL files and locks. Until this existed a device change silently threw the whole vocabulary away,
    // which is the one thing the reporter of #318 asked for by name.
    const val PERSONAL_DICTIONARY_JSON_NAME = "personal_dictionary.json"
    const val LEARNED_WORDS_JSON_NAME = "learned_words.json"
    const val LEARNED_BIGRAMS_JSON_NAME = "learned_bigrams.json"
    const val DICTIONARY_DIR = "dictionary"
    // The user's own symbol pages (issue #342). A setting, so it travels with the preferences, but a file of
    // its own beside them, as on the device; an archive without it is one whose every key was the default.
    const val CUSTOM_SYMBOLS_JSON_NAME = "custom_symbols.json"
    // The ordinary archive a password-protected one is sealed from (issue #470); deleted once it is.
    const val PLAIN_ARCHIVE_FILE_NAME = "plain_backup.zip"

    // A protected archive says so in its name, so that whoever finds it in a folder of backups knows which
    // one asks for a password before picking it.
    fun defaultFileName(metadata: Metadata, encrypted: Boolean = false): String {
        val suffix = if (encrypted) "_encrypted" else ""
        return "backup_${metadata.packageName}_${metadata.versionCode}_${metadata.timestamp}$suffix.zip"
    }

    enum class Destination {
        FILE_SYS,
        SHARE_INTENT;
    }

    class FilesSelector {
        var jetprefDatastore by mutableStateOf(true)
        /**
         * Whether the exported preferences keep the provider credentials (issue #367).
         *
         * Not a component of its own but a modifier on [jetprefDatastore] — which is why it is deliberately
         * absent from [atLeastOneSelected]: unticking it must never be able to leave the Back up button
         * enabled with nothing behind it, nor disabled with preferences still selected. Defaults to on,
         * because the ordinary reason to make a backup is to restore it on your own next phone; the share
         * destination turns it off (see BackupScreen's destination radios).
         */
        var providerCredentials by mutableStateOf(true)
        var dictatePrompts by mutableStateOf(true)
        var dictateHistory by mutableStateOf(true)
        var personalDictionary by mutableStateOf(true)
        var imeKeyboard by mutableStateOf(true)
        var imeTheme by mutableStateOf(true)
        var clipboardTextItems by mutableStateOf(false)
        var clipboardImageItems by mutableStateOf(false)
        var clipboardVideoItems by mutableStateOf(false)

        private var _clipboardData: MutableState<ToggleableState> = mutableStateOf(ToggleableState.Off)
        val clipboardData: State<ToggleableState> = _clipboardData

        fun updateCheckboxState() {
            val newValue = if (
                !clipboardVideoItems && !clipboardImageItems && !clipboardTextItems
            ) {
                ToggleableState.Off
            } else if (
                clipboardVideoItems && clipboardImageItems && clipboardTextItems
            ) {
                ToggleableState.On
            } else {
                ToggleableState.Indeterminate
            }
            _clipboardData.value = newValue
        }

        fun provideClipboardItems(): Boolean {
            return clipboardTextItems || clipboardImageItems || clipboardVideoItems
        }

        fun atLeastOneSelected(): Boolean {
            return jetprefDatastore || dictatePrompts || dictateHistory || personalDictionary || imeKeyboard || imeTheme || clipboardTextItems || clipboardImageItems || clipboardVideoItems
        }
    }

    @Serializable
    data class Metadata(
        @SerialName("package")
        val packageName: String,
        val versionCode: Int,
        val versionName: String,
        val timestamp: Long,
    )
}

/**
 * The preference export with every credential taken out of it (issue #367).
 *
 * JetPref calls `write` exactly once with the complete document, so one pass over it is the whole job —
 * and doing it here rather than over the written file is what keeps the keys from ever existing on disk.
 */
private class RedactingWriter(private val delegate: DataStoreWriter) : DataStoreWriter {
    override suspend fun write(content: String) = delegate.write(BackupRedaction.redact(content))
}

@Composable
fun BackupScreen() = FlorisScreen {
    title = stringRes(R.string.backup_and_restore__back_up__title)
    previewFieldVisible = false

    val navController = LocalNavController.current
    val context = LocalContext.current
    val cacheManager by context.cacheManager()
    val scope = rememberCoroutineScope()

    var backupDestination by remember { mutableStateOf(Backup.Destination.FILE_SYS) }
    val backupFilesSelector = remember { Backup.FilesSelector() }
    var backupWorkspace: CacheManager.BackupAndRestoreWorkspace? = null
    // Plain remember, never rememberSaveable: saved state can be written to disk, and a password must not.
    var passwordProtected by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var passwordRepeat by remember { mutableStateOf("") }
    val passwordAccepted = password == passwordRepeat &&
        password.codePointCount(0, password.length) >= BackupEncryption.MIN_PASSWORD_LENGTH

    val backUpToFileSystemLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
        onResult = { uri ->
            if (uri == null) {
                // User can modify checkboxes between cancellation and second
                // trigger, so we make sure to clear out the previous workspace
                backupWorkspace?.close()
                backupWorkspace = null
                return@rememberLauncherForActivityResult
            }
            runCatching {
                context.contentResolver.writeFromFile(uri, backupWorkspace!!.zipFile)
                backupWorkspace!!.close()
            }.onSuccess {
                context.showLongToastSync(R.string.backup_and_restore__back_up__success)
                navController.popBackStack()
            }.onFailure { error ->
                flogError { error.stackTraceToString() }
                context.showLongToastSync(R.string.backup_and_restore__back_up__failure, "error_message" to error.message)
                backupWorkspace = null
            }
        },
    )

    suspend fun prepareBackupWorkspace() {
        val workspace = cacheManager.backupAndRestore.new()
        if (backupFilesSelector.jetprefDatastore) {
            val fileBasedStorage = workspace.inputDir
                .subDir(AndroidAppDataStorage.JETPREF_DIR_NAME)
                .subFile("${FlorisPreferenceModel.NAME}.${AndroidAppDataStorage.JETPREF_FILE_EXT}")
                .let { FileBasedStorage(it.path) }
            // Without the credentials, the export goes through the redacting writer instead, so the keys
            // never reach the disk at all — not even for the moment a rewrite-afterwards would need. With
            // them, the writer is the plain one and the file stays byte-for-byte what it has always been.
            val writer = if (backupFilesSelector.providerCredentials) {
                fileBasedStorage
            } else {
                RedactingWriter(fileBasedStorage)
            }
            FlorisPreferenceStore.export(writer).getOrThrow()
            CustomSymbolsStore.backupText(context)?.let { text ->
                workspace.inputDir.subDir("dictate").let { dir ->
                    dir.mkdirs()
                    dir.subFile(Backup.CUSTOM_SYMBOLS_JSON_NAME).writeText(text)
                }
            }
        }
        if (backupFilesSelector.dictatePrompts) {
            val prompts = PromptsDatabaseHelper.getInstance(context).getAll()
            // subDir() only builds a File handle, it does not create the directory. Without this mkdirs()
            // the writeJson() below throws FileNotFoundException (no such dir), which aborted the whole
            // backup whenever the prompts component was selected (issue #112). The clipboard branch below
            // already creates its dir for the same reason.
            workspace.inputDir.subDir("dictate").let { dir ->
                dir.mkdirs()
                dir.subFile(Backup.DICTATE_PROMPTS_JSON_NAME).writeJson(prompts)
            }
        }
        if (backupFilesSelector.dictateHistory) {
            // Export the transcription-history table as JSON, plus any retained audio (named by id, and
            // since #322 keeping the extension of what is actually inside — writing `.wav` over an
            // imported voice note here would put the same lie back into the backup).
            val entries = DictateHistoryStore.exportAll(context)
            workspace.inputDir.subDir("dictate").let { dir ->
                dir.mkdirs()
                dir.subFile(Backup.DICTATE_HISTORY_JSON_NAME).writeJson(entries)
                val audioDir = dir.subDir(Backup.DICTATE_HISTORY_AUDIO_DIR)
                var audioDirMade = false
                for (entry in entries) {
                    val src = entry.audioPath?.let { java.io.File(it) } ?: continue
                    if (src.exists() && src.length() > 0L) {
                        if (!audioDirMade) { audioDir.mkdirs(); audioDirMade = true }
                        val extension = src.extension.ifEmpty { "wav" }
                        src.copyTo(audioDir.subFile("${entry.id}.$extension"), overwrite = true)
                    }
                }
            }
        }
        if (backupFilesSelector.personalDictionary) {
            // Both halves of the user's vocabulary (issue #318): the entries they curated themselves and
            // the ones the keyboard learned. Kept as separate files rather than merged, because a restore
            // has to be able to tell them apart — a hand-added word is not up for pruning.
            val dm = DictionaryManager.default().also { it.loadUserDictionariesIfNecessary() }
            val personal = runCatching { dm.florisUserDictionaryDao()?.queryAll() }.getOrNull().orEmpty()
            val learned = runCatching { LearnedWordsStore.exportWords(context) }.getOrDefault(emptyList())
            val pairs = runCatching { LearnedWordsStore.exportBigrams(context) }.getOrDefault(emptyList())
            workspace.inputDir.subDir(Backup.DICTIONARY_DIR).let { dir ->
                dir.mkdirs()
                dir.subFile(Backup.PERSONAL_DICTIONARY_JSON_NAME).writeJson(personal)
                dir.subFile(Backup.LEARNED_WORDS_JSON_NAME).writeJson(learned)
                dir.subFile(Backup.LEARNED_BIGRAMS_JSON_NAME).writeJson(pairs)
            }
        }
        val workspaceFilesDir = workspace.inputDir.subDir("files")
        if (backupFilesSelector.imeKeyboard) {
            // copyRecursively() throws NoSuchFileException if the source dir is absent (e.g. no custom
            // keyboard extension was ever imported), which would likewise abort the entire backup. Only
            // copy when the dir actually exists.
            context.filesDir.subDir(ExtensionManager.IME_KEYBOARD_PATH).let { dir ->
                if (dir.exists()) {
                    dir.copyRecursively(workspaceFilesDir.subDir(ExtensionManager.IME_KEYBOARD_PATH))
                }
            }
        }
        if (backupFilesSelector.imeTheme) {
            context.filesDir.subDir(ExtensionManager.IME_THEME_PATH).let { dir ->
                if (dir.exists()) {
                    dir.copyRecursively(workspaceFilesDir.subDir(ExtensionManager.IME_THEME_PATH))
                }
            }
        }

        if (backupFilesSelector.provideClipboardItems()) {
            val clipboardManager by context.clipboardManager()
            val clipboardHistory = clipboardManager.currentHistory.all
            val clipboardFilesDir = workspace.inputDir.subDir("clipboard")
            clipboardFilesDir.mkdir()
            if (backupFilesSelector.clipboardTextItems) {
                clipboardFilesDir.subFile(Backup.CLIPBOARD_TEXT_ITEMS_JSON_NAME)
                    .writeJson(clipboardHistory.filter { it.type == ItemType.TEXT })
            }
            if (backupFilesSelector.clipboardImageItems) {
                clipboardFilesDir.subFile(Backup.CLIPBOARD_IMAGES_JSON_NAME)
                    .writeJson(clipboardHistory.filter { it.type == ItemType.IMAGE })
                for (item in clipboardHistory.filter { it.type == ItemType.IMAGE }) {
                    val id = ContentUris.parseId(item.uri!!)
                    ClipboardFileStorage.getFileForId(context, id).copyTo(
                        clipboardFilesDir.subFile("${ClipboardFileStorage.CLIPBOARD_FILES_PATH}/$id")
                    )
                }
            }
            if (backupFilesSelector.clipboardVideoItems) {
                clipboardFilesDir.subFile(Backup.CLIPBOARD_VIDEO_JSON_NAME)
                    .writeJson(clipboardHistory.filter { it.type == ItemType.VIDEO })
                for (item in clipboardHistory.filter { it.type == ItemType.VIDEO }) {
                    val id = ContentUris.parseId(item.uri!!)
                    ClipboardFileStorage.getFileForId(context, id).copyTo(
                        clipboardFilesDir.subFile("${ClipboardFileStorage.CLIPBOARD_FILES_PATH}/$id")
                    )
                }
            }
        }
        workspace.metadata = Backup.Metadata(
            packageName = BuildConfig.APPLICATION_ID,
            versionCode = BuildConfig.VERSION_CODE,
            versionName = BuildConfig.VERSION_NAME,
            timestamp = System.currentTimeMillis(),
        )
        workspace.inputDir.subFile(Backup.METADATA_JSON_NAME).writeJson(workspace.metadata)
        val protectWith = password.takeIf { passwordProtected }
        workspace.zipFile = workspace.outputDir.subFile(
            Backup.defaultFileName(workspace.metadata, encrypted = protectWith != null),
        )
        if (protectWith == null) {
            ZipUtils.zip(workspace.inputDir, workspace.zipFile)
        } else {
            // With a password (issue #470), the sealed archive is the only copy left: the plain one it was
            // sealed from and the files that went into it are deleted at once. The share destination never
            // closes its workspace, so otherwise they would sit in the cache with the keys readable.
            val plainArchive = workspace.outputDir.subFile(Backup.PLAIN_ARCHIVE_FILE_NAME)
            try {
                ZipUtils.zip(workspace.inputDir, plainArchive)
                BackupEncryption.encrypt(plainArchive, workspace.zipFile, protectWith)
            } finally {
                plainArchive.delete()
                workspace.inputDir.deleteRecursively()
            }
        }
        backupWorkspace = workspace
    }

    suspend fun prepareAndPerformBackup() {
        runCatching {
            // Always build a fresh archive. A reused one is an archive assembled from an older set of
            // checkboxes, and since issue #367 that is a leak and not just a surprise: the share
            // destination never closes its workspace, so sharing once with the credentials in and then
            // unticking them and sharing again would hand out the very keys that were just excluded.
            // Re-zipping a cancelled backup costs a second; getting this wrong costs someone's API key.
            backupWorkspace?.close()
            backupWorkspace = null
            // Off the main thread: assembling the archive copies history audio and zips the lot, which is
            // long enough to be felt now that it happens on every press rather than once.
            withContext(Dispatchers.IO) { prepareBackupWorkspace() }
            when (backupDestination) {
                Backup.Destination.FILE_SYS -> {
                    backUpToFileSystemLauncher.launch(backupWorkspace!!.zipFile.name)
                }

                Backup.Destination.SHARE_INTENT -> {
                    val uri =
                        FileProvider.getUriForFile(context, Backup.FILE_PROVIDER_AUTHORITY, backupWorkspace!!.zipFile)
                    val shareIntent = ShareCompat.IntentBuilder(context)
                        .setStream(uri)
                        .setType(FileRegistry.BackupArchive.mediaType)
                        .createChooserIntent()
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(shareIntent)
                }
            }
        }.onFailure { error ->
            flogError { error.stackTraceToString() }
            context.showLongToast(R.string.backup_and_restore__back_up__failure, "error_message" to error.message)
            backupWorkspace = null
        }
    }

    bottomBar {
        FlorisButtonBar {
            ButtonBarSpacer()
            ButtonBarTextButton(
                onClick = {
                    backupWorkspace?.close()
                    navController.popBackStack()
                },
                text = stringRes(R.string.action__cancel),
            )
            ButtonBarButton(
                onClick = {
                    scope.launch { prepareAndPerformBackup() }
                },
                text = stringRes(R.string.action__back_up),
                enabled = backupFilesSelector.atLeastOneSelected() && (!passwordProtected || passwordAccepted),
            )
        }
    }

    content {
        // What is actually in the archive, said before the user picks where to send it (issue #367).
        //
        // Three wordings, one card. Only the preferences carry credentials, so that is the one state that
        // warns — but an archive of nothing but the history is transcripts and recordings of someone's
        // voice, and saying nothing at all about that would be the same mistake one level down. The third
        // state is for exactly that case: personal content, no credentials.
        //
        // A password (issue #470) puts all of that behind it, keys or not, so it gets its own wording. What
        // that note has to say instead is that the password is the only way back in.
        val credentialsInArchive = backupFilesSelector.jetprefDatastore &&
            backupFilesSelector.providerCredentials && !passwordProtected
        val contentNoticeId = when {
            passwordProtected && backupFilesSelector.atLeastOneSelected() ->
                R.string.backup_and_restore__back_up__encrypted_note
            credentialsInArchive -> R.string.backup_and_restore__back_up__credentials_warning
            backupFilesSelector.jetprefDatastore ->
                R.string.backup_and_restore__back_up__credentials_excluded_note
            backupFilesSelector.atLeastOneSelected() ->
                R.string.backup_and_restore__back_up__personal_content_note
            else -> null
        }
        if (contentNoticeId != null) {
            // Only one of the three is a warning, and only it gets the warning card. The other two say the
            // archive is safe to hand on, and saying that on a yellow alarm background contradicts itself —
            // the colour is read before the sentence is.
            if (credentialsInArchive) {
                FlorisWarningCard(
                    modifier = Modifier.defaultFlorisOutlinedBox(),
                    text = stringRes(contentNoticeId),
                )
            } else {
                FlorisInfoCard(
                    modifier = Modifier.defaultFlorisOutlinedBox(),
                    text = stringRes(contentNoticeId),
                )
            }
        }
        FlorisOutlinedBox(
            modifier = Modifier.defaultFlorisOutlinedBox(),
            title = stringRes(R.string.backup_and_restore__back_up__destination),
        ) {
            // The destination sets the starting point for the credentials, because it is the destination
            // that says whether this archive is going to someone else. Only on an actual change, though:
            // tapping the radio that is already selected has to stay the no-op it looks like, or it would
            // quietly undo a deliberate choice made after it.
            RadioListItem(
                onClick = {
                    if (backupDestination != Backup.Destination.FILE_SYS) {
                        backupDestination = Backup.Destination.FILE_SYS
                        backupFilesSelector.providerCredentials = true
                    }
                },
                selected = backupDestination == Backup.Destination.FILE_SYS,
                text = stringRes(R.string.backup_and_restore__back_up__destination_file_sys),
            )
            RadioListItem(
                onClick = {
                    if (backupDestination != Backup.Destination.SHARE_INTENT) {
                        backupDestination = Backup.Destination.SHARE_INTENT
                        // Sharing hands the archive to someone else, so it starts from the variant that is
                        // safe to hand over. Still a checkbox and still the user's call — the card above
                        // says what re-ticking it means.
                        backupFilesSelector.providerCredentials = false
                    }
                },
                selected = backupDestination == Backup.Destination.SHARE_INTENT,
                text = stringRes(R.string.backup_and_restore__back_up__destination_share_intent),
            )
        }
        BackupFilesSelector(
            filesSelector = backupFilesSelector,
            title = stringRes(R.string.backup_and_restore__back_up__files),
        )
        BackupPasswordBox(
            enabled = passwordProtected,
            onEnabledChange = { passwordProtected = it },
            password = password,
            onPasswordChange = { password = it },
            repeat = passwordRepeat,
            onRepeatChange = { passwordRepeat = it },
        )
    }
}

/**
 * The optional password (issue #470). Asked twice, because a mistyped password on an archive nobody can
 * open is a lost backup. Each field says what is wrong with it only once there is something to judge.
 */
@Composable
private fun BackupPasswordBox(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    repeat: String,
    onRepeatChange: (String) -> Unit,
) {
    var reveal by remember { mutableStateOf(false) }
    FlorisOutlinedBox(
        modifier = Modifier.defaultFlorisOutlinedBox(),
        title = stringRes(R.string.backup_and_restore__back_up__password),
    ) {
        CheckboxListItem(
            onClick = { onEnabledChange(!enabled) },
            checked = enabled,
            text = stringRes(R.string.backup_and_restore__back_up__password_enabled),
        )
        if (enabled) {
            val min = BackupEncryption.MIN_PASSWORD_LENGTH
            val tooShort = password.isNotEmpty() && password.codePointCount(0, password.length) < min
            val mismatch = repeat.isNotEmpty() && repeat != password
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BackupPasswordField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = stringRes(R.string.backup_and_restore__password),
                    reveal = reveal,
                    onRevealChange = { reveal = it },
                    error = if (tooShort) stringRes(R.string.backup_and_restore__back_up__password_too_short, "count" to min) else null,
                )
                BackupPasswordField(
                    value = repeat,
                    onValueChange = onRepeatChange,
                    label = stringRes(R.string.backup_and_restore__password_repeat),
                    reveal = reveal,
                    onRevealChange = { reveal = it },
                    error = if (mismatch) stringRes(R.string.backup_and_restore__back_up__password_mismatch) else null,
                    imeAction = ImeAction.Done,
                )
            }
        }
    }
}

/**
 * A password field for a backup archive, on both screens. The password keyboard type is what tells a
 * keyboard, ours included, not to suggest, correct or learn what is typed here.
 */
@Composable
internal fun BackupPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    reveal: Boolean,
    onRevealChange: (Boolean) -> Unit,
    error: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = imeAction,
        ),
        keyboardActions = onDone?.let { done ->
            KeyboardActions(onDone = { defaultKeyboardAction(ImeAction.Done); done() })
        } ?: KeyboardActions.Default,
        trailingIcon = {
            IconButton(onClick = { onRevealChange(!reveal) }) {
                Icon(
                    imageVector = if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = null,
                )
            }
        },
    )
}

@Composable
internal fun BackupFilesSelector(
    modifier: Modifier = Modifier,
    filesSelector: Backup.FilesSelector,
    title: String,
    // Backup only (issue #367). Leaving the keys out of an archive that has them is a question for the
    // person writing it; restoring selectively around them is a different feature and not this one.
    showCredentials: Boolean = true,
) {
    FlorisOutlinedBox(
        modifier = modifier.defaultFlorisOutlinedBox(),
        title = title,
    ) {
        CheckboxListItem(
            onClick = { filesSelector.jetprefDatastore = !filesSelector.jetprefDatastore },
            checked = filesSelector.jetprefDatastore,
            text = stringRes(R.string.backup_and_restore__back_up__files_jetpref_datastore),
        )
        // Gone entirely while the preferences are not being backed up, rather than greyed out: there is
        // nothing for it to modify then, and a disabled box that is still ticked reads as "the keys are
        // coming along" — the same ambiguity that made issue #297 a bug report.
        if (showCredentials && filesSelector.jetprefDatastore) {
            CheckboxListItem(
                onClick = { filesSelector.providerCredentials = !filesSelector.providerCredentials },
                checked = filesSelector.providerCredentials,
                text = stringRes(R.string.backup_and_restore__back_up__files_provider_credentials),
                isSecondaryListItem = true,
            )
        }
        CheckboxListItem(
            onClick = { filesSelector.dictatePrompts = !filesSelector.dictatePrompts },
            checked = filesSelector.dictatePrompts,
            text = stringRes(R.string.backup_and_restore__back_up__files_dictate_prompts),
        )
        CheckboxListItem(
            onClick = { filesSelector.dictateHistory = !filesSelector.dictateHistory },
            checked = filesSelector.dictateHistory,
            text = stringRes(R.string.dictate__history_title),
        )
        CheckboxListItem(
            onClick = { filesSelector.personalDictionary = !filesSelector.personalDictionary },
            checked = filesSelector.personalDictionary,
            text = stringRes(R.string.backup_and_restore__back_up__files_personal_dictionary),
        )
        CheckboxListItem(
            onClick = { filesSelector.imeKeyboard = !filesSelector.imeKeyboard },
            checked = filesSelector.imeKeyboard,
            text = stringRes(R.string.backup_and_restore__back_up__files_ime_keyboard),
        )
        CheckboxListItem(
            onClick = { filesSelector.imeTheme = !filesSelector.imeTheme },
            checked = filesSelector.imeTheme,
            text = stringRes(R.string.backup_and_restore__back_up__files_ime_theme),
        )

        TriStateCheckboxListItem(
            onClick = {
                if (
                    filesSelector.clipboardData.value == ToggleableState.Off ||
                    filesSelector.clipboardData.value == ToggleableState.Indeterminate
                ) {
                    filesSelector.clipboardImageItems = true
                    filesSelector.clipboardVideoItems = true
                    filesSelector.clipboardTextItems = true
                } else {
                    filesSelector.clipboardImageItems = false
                    filesSelector.clipboardVideoItems = false
                    filesSelector.clipboardTextItems = false
                }
                filesSelector.updateCheckboxState()
            },
            state = filesSelector.clipboardData.value,
            text = stringRes(R.string.backup_and_restore__back_up__files_clipboard_history),
        )


        CheckboxListItem(
            onClick = {
                filesSelector.clipboardTextItems = !filesSelector.clipboardTextItems
                filesSelector.updateCheckboxState()
            },
            checked = filesSelector.clipboardTextItems,
            text = stringRes(R.string.backup_and_restore__back_up__files_clipboard_history__clipboard_text_items),
            isSecondaryListItem = true,
        )
        CheckboxListItem(
            onClick = {
                filesSelector.clipboardImageItems = !filesSelector.clipboardImageItems
                filesSelector.updateCheckboxState()
            },
            checked = filesSelector.clipboardImageItems,
            text = stringRes(R.string.backup_and_restore__back_up__files_clipboard_history__clipboard_image_items),
            isSecondaryListItem = true,
        )
        CheckboxListItem(
            onClick = {
                filesSelector.clipboardVideoItems = !filesSelector.clipboardVideoItems
                filesSelector.updateCheckboxState()
            },
            checked = filesSelector.clipboardVideoItems,
            text = stringRes(R.string.backup_and_restore__back_up__files_clipboard_history__clipboard_video_items),
            isSecondaryListItem = true,
        )

    }
}

@Composable
internal fun CheckboxListItem(
    onClick: () -> Unit,
    checked: Boolean,
    text: String,
    isSecondaryListItem: Boolean = false
) {
    JetPrefListItem(
        modifier = Modifier.rippleClickable(onClick = onClick),
        icon = {
            Row {
                if (isSecondaryListItem) {
                    Spacer(modifier = Modifier.width(40.dp))
                }
                Checkbox(
                    checked = checked,
                    onCheckedChange = null,
                )
            }
        },
        text = text,
    )
}

@Composable
internal fun TriStateCheckboxListItem(
    onClick: () -> Unit,
    state: ToggleableState,
    text: String,
    isSecondaryListItem: Boolean = false,
) {
    JetPrefListItem(
        modifier = Modifier.rippleClickable(onClick = onClick),
        icon = {
            Row {
                if (isSecondaryListItem) {
                    Spacer(modifier = Modifier.width(40.dp))
                }
                TriStateCheckbox(
                    state = state,
                    onClick = null,
                )
            }
        },
        text = text,
    )
}

@Composable
internal fun RadioListItem(
    onClick: () -> Unit,
    selected: Boolean,
    text: String,
    secondaryText: String? = null,
) {
    JetPrefListItem(
        modifier = Modifier.rippleClickable(onClick = onClick),
        icon = {
            RadioButton(
                selected = selected,
                onClick = null,
            )
        },
        text = text,
        secondaryText = secondaryText,
    )
}
