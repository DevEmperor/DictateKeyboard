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

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.dictate.data.prompts.PromptLibraryEntry
import dev.patrickgold.florisboard.dictate.data.prompts.PromptLibraryInstalled
import dev.patrickgold.florisboard.dictate.data.prompts.PromptLibraryManager
import dev.patrickgold.florisboard.dictate.data.prompts.PromptsDatabaseHelper
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.compose.FlorisIconButton
import org.florisboard.lib.compose.florisScrollbar
import org.florisboard.lib.compose.florisDialogScroll
import org.florisboard.lib.compose.stringRes

/**
 * Browse the community prompt library and import prompts into the user's own list (issue #105).
 *
 * The library is a static JSON file hosted on the project's own repository ([PromptLibraryCatalog]);
 * it is fetched on first open and cached on disk (see [PromptLibraryManager]), so re-opening is instant
 * and works offline. Adding a prompt writes it into the same `prompts.db` the [DictatePromptsScreen]
 * manages, so imported prompts immediately show up in the user's list and on the keyboard chips.
 */
@Composable
fun DictatePromptLibraryScreen() = FlorisScreen {
    title = stringRes(R.string.dictate__prompt_library_title)
    previewFieldVisible = false
    scrollable = false

    val context = LocalContext.current
    val db = remember { PromptsDatabaseHelper.getInstance(context) }
    val scope = rememberCoroutineScope()

    val entries = remember { mutableStateListOf<PromptLibraryEntry>() }
    // Normalised name+prompt keys of prompts already in the user's db, so the browser can mark entries
    // that have been added. Rebuilt on every (re)load and after each add.
    val installedKeys = remember { mutableStateListOf<String>() }
    // Library ids the user has imported — the primary "Added" signal (survives edits). installedKeys
    // (name+prompt) stays as a fallback for prompts imported before id-tracking existed.
    val installedIds = remember { mutableStateListOf<String>() }
    // Full-screen spinner: only while we have nothing to show yet. The background network refresh uses
    // [refreshing] (a thin top bar) instead, so a warm cache never blanks the screen on open.
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var activeCategory by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<PromptLibraryEntry?>(null) }

    fun keyOf(name: String?, prompt: String?): String =
        "${name.orEmpty().trim().lowercase()} ${prompt.orEmpty().trim().lowercase()}"

    suspend fun refreshInstalledKeys() {
        val keys = withContext(Dispatchers.IO) { db.getAll().map { keyOf(it.name, it.prompt) } }
        installedKeys.clear()
        installedKeys.addAll(keys)
        val ids = withContext(Dispatchers.IO) { PromptLibraryInstalled.all(context) }
        installedIds.clear()
        installedIds.addAll(ids)
    }

    fun isAdded(entry: PromptLibraryEntry): Boolean =
        entry.id in installedIds || keyOf(entry.name, entry.prompt) in installedKeys

    // Always fetches from the network (used on open and by the refresh button). Keeps whatever is
    // already shown visible while it runs; only swaps the list in once a result arrives.
    suspend fun refreshFromNetwork() {
        refreshing = true
        val result = PromptLibraryManager.load(context, forceRefresh = true)
        if (result.entries.isNotEmpty() || entries.isEmpty()) {
            entries.clear()
            entries.addAll(result.entries)
        }
        refreshInstalledKeys()
        loadError = entries.isEmpty() && result.error != null
        loading = false
        refreshing = false
    }

    // On open: paint the cached library instantly (no spinner) if we have one, then always refresh in
    // the background so the page is up to date every time it is opened.
    suspend fun openLoad() {
        val cached = PromptLibraryManager.cachedOnly(context) ?: PromptLibraryManager.bundled(context)
        if (cached != null) {
            entries.clear()
            entries.addAll(cached)
            refreshInstalledKeys()
            loading = false
        }
        refreshFromNetwork()
    }

    fun addEntry(entry: PromptLibraryEntry) {
        scope.launch {
            withContext(Dispatchers.IO) {
                db.add(entry.toPromptModel(db.count()))
                PromptLibraryInstalled.add(context, entry.id)
            }
            refreshInstalledKeys()
            DictateController.refreshPrompts(context)
            Toast.makeText(context, R.string.dictate__prompt_library_added, Toast.LENGTH_SHORT).show()
        }
    }

    actions {
        FlorisIconButton(
            onClick = { scope.launch { refreshFromNetwork() } },
            icon = Icons.Default.Refresh,
        )
    }

    content {
        LaunchedEffect(Unit) { openLoad() }

        val categories = remember(entries.toList()) {
            entries.mapNotNull { it.category?.takeIf { c -> c.isNotBlank() } }.distinct().sorted()
        }
        val filtered = remember(entries.toList(), query, activeCategory) {
            val q = query.trim().lowercase()
            entries.filter { e ->
                (activeCategory == null || e.category == activeCategory) &&
                    (q.isEmpty() ||
                        e.name.lowercase().contains(q) ||
                        e.description?.lowercase()?.contains(q) == true ||
                        e.prompt.lowercase().contains(q))
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Thin bar while a background refresh runs over already-shown content (not the first load).
            if (refreshing && !loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text(stringRes(R.string.dictate__prompt_library_search)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )

            if (categories.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = activeCategory == null,
                        onClick = { activeCategory = null },
                        label = { Text(stringRes(R.string.dictate__prompt_library_all)) },
                    )
                    categories.forEach { category ->
                        FilterChip(
                            selected = activeCategory == category,
                            onClick = { activeCategory = if (activeCategory == category) null else category },
                            label = { Text(category) },
                        )
                    }
                }
            }

            when {
                loading -> CenteredBox { CircularProgressIndicator() }
                loadError -> CenteredBox {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.CloudOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringRes(R.string.dictate__prompt_library_error),
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { scope.launch { refreshFromNetwork() } }) {
                            Text(stringRes(R.string.dictate__prompt_library_retry))
                        }
                    }
                }
                filtered.isEmpty() -> CenteredBox {
                    Text(
                        text = stringRes(R.string.dictate__prompt_library_empty),
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
                else -> {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .florisScrollbar(listState, isVertical = true),
                        state = listState,
                    ) {
                        items(filtered, key = { it.id }) { entry ->
                            val added = isAdded(entry)
                            LibraryRow(
                                entry = entry,
                                added = added,
                                onClick = { preview = entry },
                                onAdd = { addEntry(entry) },
                            )
                        }
                    }
                }
            }
        }

        val current = preview
        if (current != null) {
            val added = isAdded(current)
            PreviewDialog(
                entry = current,
                added = added,
                onDismiss = { preview = null },
                onAdd = {
                    addEntry(current)
                    preview = null
                },
            )
        }
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun LibraryRow(
    entry: PromptLibraryEntry,
    added: Boolean,
    onClick: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Three stacked lines: title with the category pill right beside it, then the author, then the
        // short description (falling back to the raw prompt).
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f, fill = false),
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.category?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.width(8.dp))
                    CategoryTag(it)
                }
            }
            entry.author?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringRes(R.string.dictate__prompt_library_author, "author" to it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = entry.description?.takeIf { it.isNotBlank() } ?: entry.prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (entry.requiresSelection) {
            PromptTypeBadge(Icons.Default.SelectAll)
        }
        if (entry.autoApply) {
            Spacer(Modifier.width(4.dp))
            PromptTypeBadge(Icons.Default.Autorenew)
        }
        Spacer(Modifier.width(8.dp))
        if (added) {
            Icon(
                modifier = Modifier.padding(end = 16.dp).size(22.dp),
                imageVector = Icons.Default.Check,
                contentDescription = stringRes(R.string.dictate__prompt_library_added),
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            TextButton(onClick = onAdd, modifier = Modifier.padding(end = 8.dp)) {
                Text(stringRes(R.string.dictate__prompt_library_add))
            }
        }
    }
}

/** A small pill showing a prompt's category (issue #105). */
@Composable
private fun CategoryTag(category: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            text = category,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun PromptTypeBadge(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        modifier = Modifier.size(18.dp),
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    )
}

@Composable
private fun PreviewDialog(
    entry: PromptLibraryEntry,
    added: Boolean,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
) {
    JetPrefAlertDialog(
        scrollModifier = florisDialogScroll(),
        title = entry.name,
        confirmLabel = if (added) {
            stringRes(R.string.dictate__prompt_library_added)
        } else {
            stringRes(R.string.dictate__prompt_library_add)
        },
        onConfirm = { if (!added) onAdd() },
        dismissLabel = stringRes(R.string.dictate__prompt_library_close),
        onDismiss = onDismiss,
        allowOutsideDismissal = true,
    ) {
        Column {
            entry.category?.takeIf { it.isNotBlank() }?.let {
                CategoryTag(it)
                Spacer(Modifier.height(8.dp))
            }
            if (!entry.description.isNullOrBlank()) {
                Text(text = entry.description, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = entry.prompt,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (entry.requiresSelection) {
                    Text(
                        text = stringRes(R.string.dictate__prompt_badge_selection),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (entry.autoApply) {
                    Text(
                        text = stringRes(R.string.dictate__prompt_badge_auto),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (!entry.author.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringRes(R.string.dictate__prompt_library_author, "author" to entry.author),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
