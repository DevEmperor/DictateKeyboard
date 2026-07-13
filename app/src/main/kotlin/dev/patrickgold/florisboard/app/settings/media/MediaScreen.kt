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

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiSymbols
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.settings.search.settingsSearchAnchor
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.enumDisplayEntriesOf
import dev.patrickgold.florisboard.ime.media.emoji.EmojiHistory
import dev.patrickgold.florisboard.ime.media.emoji.EmojiHistoryHelper
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSkinTone
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSuggestionType
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.DialogSliderPreference
import dev.patrickgold.jetpref.datastore.ui.ExperimentalJetPrefDatastoreUi
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.pluralsRes
import org.florisboard.lib.compose.stringRes

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

    content {
        // Single GIF row (no group heading — there is only one option); the on/off switch and the
        // setup walkthrough both live inside the dialog it opens.
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

        ListPreference(
            prefs.emoji.preferredSkinTone,
            modifier = Modifier.settingsSearchAnchor("prefs__media__emoji_preferred_skin_tone"),
            title = stringRes(R.string.prefs__media__emoji_preferred_skin_tone),
            entries = enumDisplayEntriesOf(EmojiSkinTone::class),
        )

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
