/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.dictionary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import dev.patrickgold.jetpref.datastore.model.collectAsState as collectPrefAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.dictionary.DictionaryManager
import dev.patrickgold.florisboard.ime.dictionary.LearnedWordEntry
import dev.patrickgold.florisboard.ime.dictionary.LearnedWordsStore
import dev.patrickgold.florisboard.ime.dictionary.UserDictionaryEntry
import dev.patrickgold.florisboard.ime.nlp.latin.WordLearningGate
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.compose.stringRes

/** Frequency a promoted word is stored at, matching what a hand-added entry gets. */
private const val PROMOTED_FREQ = 255

/**
 * What the keyboard has picked up from typing, and the controls to correct it (issue #318).
 *
 * This is deliberately a normal settings screen rather than a devtools page, for two reasons. A feature
 * that quietly builds a record of what someone writes owes them a way to look at it — that is most of what
 * makes it defensible at all. And it is the only way to *test* the feature: without it, "did it learn
 * that word?" can only be answered by typing until autocorrect stops interfering.
 */
@Composable
fun LearnedWordsScreen() = FlorisScreen {
    title = stringRes(R.string.settings__learned__title)
    previewFieldVisible = false

    content {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val learningEnabled by prefs.suggestion.learnTypedWords.collectPrefAsState()
        val entries by LearnedWordsStore.flow(context).collectAsState(initial = emptyList())
        var confirmForgetAll by remember { mutableStateOf(false) }

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringRes(R.string.settings__learned__how_it_works),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!learningEnabled) {
                Text(
                    modifier = Modifier.padding(top = 8.dp),
                    text = stringRes(R.string.settings__learned__disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (entries.isEmpty()) {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                text = stringRes(R.string.settings__learned__empty),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@content
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { confirmForgetAll = true }) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null)
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = stringRes(R.string.settings__learned__forget_all),
                )
            }
        }

        for (entry in entries) {
            LearnedWordRow(
                entry = entry,
                onForget = { scope.launch { LearnedWordsStore.forget(context, entry) } },
                onPromote = { scope.launch { promoteByHand(context, entry) } },
            )
        }

        if (confirmForgetAll) {
            AlertDialog(
                onDismissRequest = { confirmForgetAll = false },
                title = { Text(stringRes(R.string.settings__learned__forget_all)) },
                text = { Text(stringRes(R.string.settings__learned__forget_all_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmForgetAll = false
                            scope.launch { LearnedWordsStore.forgetAll(context) }
                        },
                    ) { Text(stringRes(R.string.settings__learned__forget_all)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmForgetAll = false }) {
                        Text(stringRes(R.string.action__cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun LearnedWordRow(
    entry: LearnedWordEntry,
    onForget: () -> Unit,
    onPromote: () -> Unit,
) {
    val score = if (entry.promoted) {
        entry.count.toDouble()
    } else {
        WordLearningGate.decayedScore(entry.count, entry.lastUsed, System.currentTimeMillis() / 1000L)
    }
    val stage = if (entry.promoted) WordLearningGate.Stage.PROMOTED else WordLearningGate.stageOf(score)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.word,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    // Italic here for the same reason the suggestion strip uses it: this word came from
                    // you, not from the dictionary that shipped with the app.
                    fontStyle = FontStyle.Italic,
                )
                Text(
                    text = stringRes(
                        when (stage) {
                            WordLearningGate.Stage.REMEMBERED -> R.string.settings__learned__stage_remembered
                            WordLearningGate.Stage.SUGGESTED -> R.string.settings__learned__stage_suggested
                            WordLearningGate.Stage.PROMOTED -> R.string.settings__learned__stage_promoted
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringRes(
                        R.string.settings__learned__sightings,
                        "count" to entry.count,
                    ) + " · ${entry.lang}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!entry.promoted) {
                TextButton(onClick = onPromote) {
                    Text(stringRes(R.string.settings__learned__add_now))
                }
            }
            IconButton(onClick = onForget) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = stringRes(R.string.settings__learned__forget),
                )
            }
        }
    }
}

/**
 * Promotes one word on the user's say-so, skipping the sighting count entirely.
 *
 * The ladder exists to decide *without* the user; when they press the button they have decided, and
 * making them type the word two more times to confirm an explicit instruction would be silly.
 */
private suspend fun promoteByHand(context: android.content.Context, entry: LearnedWordEntry) =
    withContext(Dispatchers.IO) {
        val dao = DictionaryManager.default().also { it.loadUserDictionariesIfNecessary() }
            .florisUserDictionaryDao() ?: return@withContext
        val locale = runCatching { FlorisLocale.fromTag(entry.lang) }.getOrNull()
        val inserted = runCatching {
            if (dao.queryExactFuzzyLocale(entry.word, locale ?: FlorisLocale.default()).isEmpty()) {
                dao.insert(
                    UserDictionaryEntry(
                        id = 0,
                        word = entry.word,
                        freq = PROMOTED_FREQ,
                        locale = locale?.localeTag(),
                        shortcut = null,
                    )
                )
            }
            true
        }.getOrDefault(false)
        // Marked here too, or the row would keep decaying and offer the button again next week while the
        // dictionary entry it created sat there unexplained.
        if (inserted) LearnedWordsStore.setPromoted(context, entry.id, true, entry.lang)
    }
