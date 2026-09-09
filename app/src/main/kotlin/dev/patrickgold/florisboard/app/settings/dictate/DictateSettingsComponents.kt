/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.dictate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.dictate.DictateReasoningEffort
import dev.patrickgold.jetpref.datastore.model.PreferenceData
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.florisboard.dictate.data.prompts.DictatePromptDefaults
import dev.patrickgold.jetpref.datastore.ui.ListPreferenceEntry
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceUiScope
import dev.patrickgold.jetpref.datastore.ui.listPrefEntries
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import org.florisboard.lib.compose.florisDialogScroll
import org.florisboard.lib.compose.stringRes
import kotlinx.coroutines.launch

/**
 * The shared none/predefined/custom choice for the system and style prompts, keyed by the integer
 * selection values in [DictatePromptDefaults] (0/1/2) used by the rewording engine.
 */
@Composable
internal fun promptSelectionEntries(): List<ListPreferenceEntry<Int>> = listPrefEntries {
    entry(
        key = DictatePromptDefaults.SELECTION_NONE,
        label = stringRes(R.string.dictate__prompt_selection_none),
    )
    entry(
        key = DictatePromptDefaults.SELECTION_PREDEFINED,
        label = stringRes(R.string.dictate__prompt_selection_predefined),
    )
    entry(
        key = DictatePromptDefaults.SELECTION_CUSTOM,
        label = stringRes(R.string.dictate__prompt_selection_custom),
    )
}

/**
 * A none/predefined/custom prompt selector that behaves like a [ListPreference] (tap the row to pick
 * an option in a radio dialog) but additionally shows a trailing info "i" button whenever the
 * *predefined* option is selected. Tapping it reveals the built-in prompt text behind that option —
 * useful both to understand what it sends and as a starting point for a custom prompt. The reveal
 * dialog text is selectable so it can be copied.
 *
 * Reimplemented on top of the lower-level [Preference] (rather than using [ListPreference] directly)
 * because JetPref's `ListPreference` reserves its trailing slot for an optional switch and does not
 * expose it to callers.
 *
 * @param infoPromptText the built-in prompt revealed by the info button; for per-language prompts the
 *  caller passes the resolved text for the active language.
 */
@Composable
internal fun PreferenceUiScope<FlorisPreferenceModel>.PromptSelectionPreference(
    pref: PreferenceData<Int>,
    title: String,
    icon: ImageVector,
    entries: List<ListPreferenceEntry<Int>>,
    infoTitle: String,
    infoDescription: String,
    infoPromptText: String,
) {
    val scope = rememberCoroutineScope()
    val value by pref.collectAsState()
    var selectionDialogOpen by remember { mutableStateOf(false) }
    var infoDialogOpen by remember { mutableStateOf(false) }

    Preference(
        icon = icon,
        title = title,
        summary = entries.find { it.key == value }?.label,
        trailing = if (value == DictatePromptDefaults.SELECTION_PREDEFINED) {
            {
                IconButton(onClick = { infoDialogOpen = true }) {
                    Icon(imageVector = Icons.Outlined.Info, contentDescription = infoTitle)
                }
            }
        } else {
            null
        },
        onClick = { selectionDialogOpen = true },
    )

    if (selectionDialogOpen) {
        var tmpValue by remember(value) { mutableStateOf(value) }
        JetPrefAlertDialog(
            scrollModifier = florisDialogScroll(),
            title = title,
            confirmLabel = stringRes(R.string.action__ok),
            onConfirm = {
                scope.launch { pref.set(tmpValue) }
                selectionDialogOpen = false
            },
            dismissLabel = stringRes(R.string.action__cancel),
            onDismiss = { selectionDialogOpen = false },
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Column {
                for (entry in entries) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = entry.key == tmpValue,
                                onClick = { tmpValue = entry.key },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = entry.key == tmpValue,
                            onClick = null,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Text(text = entry.label)
                    }
                }
            }
        }
    }

    if (infoDialogOpen) {
        JetPrefAlertDialog(
            scrollModifier = florisDialogScroll(),
            title = infoTitle,
            confirmLabel = stringRes(R.string.action__ok),
            onConfirm = { infoDialogOpen = false },
            onDismiss = { infoDialogOpen = false },
        ) {
            SelectionContainer {
                Column {
                    Text(text = infoDescription)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = infoPromptText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * A [Preference] row that edits a string preference through a text dialog. Shared across the Dictate
 * settings screens (API key, model, base URL, custom prompts). Secret fields are masked while typing;
 * pass [multiline] for longer free-text values such as custom prompts.
 */
@Composable
internal fun PreferenceUiScope<FlorisPreferenceModel>.TextInputPreference(
    pref: PreferenceData<String>,
    title: String,
    icon: ImageVector? = null,
    placeholder: String = "",
    isSecret: Boolean = false,
    multiline: Boolean = false,
    notSetSummary: String = stringRes(R.string.dictate__value_not_set),
    summaryProvider: (String) -> String = { it.ifBlank { notSetSummary } },
) {
    val value by pref.collectAsState()
    val scope = rememberCoroutineScope()
    var dialogOpen by remember { mutableStateOf(false) }
    val summary = summaryProvider(value)

    Preference(
        icon = icon,
        title = title,
        summary = summary,
        onClick = { dialogOpen = true },
    )

    if (dialogOpen) {
        var text by remember(value) { mutableStateOf(value) }
        JetPrefAlertDialog(
            scrollModifier = florisDialogScroll(),
            title = title,
            confirmLabel = stringRes(R.string.action__ok),
            dismissLabel = stringRes(R.string.action__cancel),
            onConfirm = {
                scope.launch { pref.set(text.trim()) }
                dialogOpen = false
            },
            onDismiss = { dialogOpen = false },
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .padding(top = 4.dp)
                    // Cap a multiline field so long text scrolls inside it instead of stretching the
                    // whole dialog into a scroll (issue #149).
                    .then(if (multiline) Modifier.heightIn(min = 120.dp, max = 220.dp) else Modifier),
                value = text,
                onValueChange = { text = it },
                singleLine = !multiline,
                placeholder = { Text(placeholder) },
                visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when {
                        isSecret -> KeyboardType.Password
                        multiline -> KeyboardType.Text
                        else -> KeyboardType.Uri
                    },
                ),
            )
        }
    }
}

/**
 * A radio-button picker dialog for [DictateReasoningEffort] with an inline text field for the CUSTOM
 * value (issue #186). Shared by the global rewording setting and the per-prompt override. When
 * [includeUseGlobal] is set, a leading "use the global setting" option (value null) is offered.
 */
@Composable
internal fun ReasoningEffortDialog(
    initialEffort: DictateReasoningEffort?,
    initialCustom: String,
    includeUseGlobal: Boolean,
    onConfirm: (effort: DictateReasoningEffort?, custom: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(initialEffort) }
    var custom by remember { mutableStateOf(initialCustom) }
    val options = buildList {
        if (includeUseGlobal) add(null)
        add(DictateReasoningEffort.OFF)
        add(DictateReasoningEffort.MINIMAL)
        add(DictateReasoningEffort.LOW)
        add(DictateReasoningEffort.MEDIUM)
        add(DictateReasoningEffort.HIGH)
        add(DictateReasoningEffort.CUSTOM)
    }
    JetPrefAlertDialog(
        scrollModifier = florisDialogScroll(),
        title = stringRes(R.string.dictate__reasoning_effort_title),
        confirmLabel = stringRes(R.string.action__apply),
        dismissLabel = stringRes(R.string.action__cancel),
        onConfirm = { onConfirm(selected, custom.trim()) },
        onDismiss = onDismiss,
    ) {
        Column {
            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selected = option }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected == option, onClick = { selected = option })
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(reasoningEffortDialogLabel(option))
                        if (option == DictateReasoningEffort.CUSTOM && selected == DictateReasoningEffort.CUSTOM) {
                            OutlinedTextField(
                                value = custom,
                                onValueChange = { custom = it },
                                singleLine = true,
                                placeholder = { Text("minimal") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun reasoningEffortDialogLabel(effort: DictateReasoningEffort?): String = stringRes(
    when (effort) {
        null -> R.string.dictate__prompt_reasoning_default
        DictateReasoningEffort.OFF -> R.string.dictate__reasoning_effort_off
        DictateReasoningEffort.MINIMAL -> R.string.dictate__reasoning_effort_minimal
        DictateReasoningEffort.LOW -> R.string.dictate__reasoning_effort_low
        DictateReasoningEffort.MEDIUM -> R.string.dictate__reasoning_effort_medium
        DictateReasoningEffort.HIGH -> R.string.dictate__reasoning_effort_high
        DictateReasoningEffort.CUSTOM -> R.string.dictate__reasoning_effort_custom
    },
)

/**
 * Instant recording as one row instead of two switches (issue #224).
 *
 * The feature has three states a user can actually name — off, on for every field the keyboard opens
 * on, and on only when they have just switched *to* Dictate — plus one detail that only matters while
 * it is on, whether number-only fields count. As two independent switches that read as a puzzle; as one
 * row with a dialog it reads as a question with an answer.
 *
 * The three states are stored as the two booleans they have always been ([enabled] and [afterSwitchOnly]),
 * so nobody's existing setting needs migrating; only the presentation is unified.
 */
@Composable
internal fun PreferenceUiScope<FlorisPreferenceModel>.InstantRecordingPreference(
    enabled: PreferenceData<Boolean>,
    afterSwitchOnly: PreferenceData<Boolean>,
    skipNumeric: PreferenceData<Boolean>,
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onTurnedOn: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isEnabled by enabled.collectAsState()
    val isAfterSwitchOnly by afterSwitchOnly.collectAsState()
    val isSkipNumeric by skipNumeric.collectAsState()
    var dialogOpen by remember { mutableStateOf(false) }

    val modeOff = stringRes(R.string.dictate__instant_recording_mode_off)
    val modeAlways = stringRes(R.string.dictate__instant_recording_mode_always)
    val modeAfterSwitch = stringRes(R.string.dictate__instant_recording_mode_after_switch)

    Preference(
        icon = icon,
        modifier = modifier,
        title = title,
        summary = when {
            !isEnabled -> modeOff
            isAfterSwitchOnly -> modeAfterSwitch
            else -> modeAlways
        },
        onClick = { dialogOpen = true },
    )

    if (dialogOpen) {
        // 0 = off, 1 = every time the keyboard opens, 2 = only after switching to Dictate.
        val current = when {
            !isEnabled -> 0
            isAfterSwitchOnly -> 2
            else -> 1
        }
        var tmpMode by remember(current) { mutableStateOf(current) }
        var tmpInNumeric by remember(isSkipNumeric) { mutableStateOf(!isSkipNumeric) }
        JetPrefAlertDialog(
            scrollModifier = florisDialogScroll(),
            title = title,
            confirmLabel = stringRes(R.string.action__ok),
            onConfirm = {
                val wasOff = !isEnabled
                scope.launch {
                    enabled.set(tmpMode != 0)
                    afterSwitchOnly.set(tmpMode == 2)
                    skipNumeric.set(!tmpInNumeric)
                }
                dialogOpen = false
                // The recovery offer for an interrupted recording is mutually exclusive with this
                // (issue #120), and that is worth saying once, at the moment it starts applying.
                if (wasOff && tmpMode != 0) onTurnedOn()
            },
            dismissLabel = stringRes(R.string.action__cancel),
            onDismiss = { dialogOpen = false },
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Column {
                listOf(modeOff, modeAlways, modeAfterSwitch).forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = index == tmpMode, onClick = { tmpMode = index })
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = index == tmpMode,
                            onClick = null,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Text(text = label)
                    }
                }
                // Only meaningful while the feature is on, so it is disabled rather than hidden: a
                // checkbox that vanishes takes its own explanation with it.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = tmpInNumeric,
                            enabled = tmpMode != 0,
                            onClick = { tmpInNumeric = !tmpInNumeric },
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = tmpInNumeric,
                        onCheckedChange = null,
                        enabled = tmpMode != 0,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    Text(
                        text = stringRes(R.string.dictate__instant_recording_in_numeric),
                        color = if (tmpMode != 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Real-time transcription as one row with three answers instead of a switch (issue #345).
 *
 * Streaming used to be a yes/no question, and "yes" carried a second decision nobody was asked: the
 * provisional words were typed into the field as they arrived. That is the right answer for people who
 * want to watch the transcript build, and the wrong one for people who only want the speed — the
 * constant re-writing reads as stuttering, and a half-finished sentence in a chat box is a distraction.
 * Both wants are now nameable: stream and show it, or stream and stay quiet until the recording stops.
 *
 * Stored as the two booleans they are ([enabled] and [hidePreview]), so an existing on/off choice keeps
 * meaning what it meant — anyone who had realtime on lands on "show while speaking", which is what they
 * already had.
 */
@Composable
internal fun PreferenceUiScope<FlorisPreferenceModel>.RealtimeTranscriptionPreference(
    enabled: PreferenceData<Boolean>,
    hidePreview: PreferenceData<Boolean>,
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val isEnabled by enabled.collectAsState()
    val isHidePreview by hidePreview.collectAsState()
    var dialogOpen by remember { mutableStateOf(false) }

    val modeOff = stringRes(R.string.dictate__realtime_mode_off)
    val modeLive = stringRes(R.string.dictate__realtime_mode_live)
    val modeAtEnd = stringRes(R.string.dictate__realtime_mode_at_end)

    Preference(
        icon = icon,
        modifier = modifier,
        title = title,
        summary = when {
            !isEnabled -> modeOff
            isHidePreview -> modeAtEnd
            else -> modeLive
        },
        onClick = { dialogOpen = true },
    )

    if (dialogOpen) {
        // 0 = off, 1 = stream and show the words as they arrive, 2 = stream but only show the result.
        val current = when {
            !isEnabled -> 0
            isHidePreview -> 2
            else -> 1
        }
        var tmpMode by remember(current) { mutableStateOf(current) }
        JetPrefAlertDialog(
            scrollModifier = florisDialogScroll(),
            title = title,
            confirmLabel = stringRes(R.string.action__ok),
            onConfirm = {
                scope.launch {
                    enabled.set(tmpMode != 0)
                    // Only meaningful while streaming is on, but written unconditionally so the choice
                    // survives switching the feature off and on again.
                    if (tmpMode != 0) hidePreview.set(tmpMode == 2)
                }
                dialogOpen = false
            },
            dismissLabel = stringRes(R.string.action__cancel),
            onDismiss = { dialogOpen = false },
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Column {
                // What the feature is, kept where the row used to say it — the summary now names the
                // chosen mode, so without this the explanation would have nowhere left to live.
                Text(
                    text = stringRes(R.string.dictate__realtime_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                listOf(modeOff, modeLive, modeAtEnd).forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = index == tmpMode, onClick = { tmpMode = index })
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = index == tmpMode,
                            onClick = null,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Text(text = label)
                    }
                }
            }
        }
    }
}
