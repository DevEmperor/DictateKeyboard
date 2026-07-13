/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.search

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Cross-screen coordination for the settings search (issue #187). When a search result carries a row
 * [SettingsSearchEntry.anchor], the search screen stashes it here and navigates to the target screen;
 * the row tagged with [settingsSearchAnchor] then scrolls itself into view and briefly flashes. The
 * anchor is consumed once so re-visiting the screen normally doesn't flash it again.
 */
object SettingsSearchState {
    var pendingAnchor by mutableStateOf<String?>(null)
        private set

    /** Called by the search screen right before navigating to a result's route. */
    fun requestAnchor(key: String?) {
        pendingAnchor = key
    }

    fun consumeAnchor() {
        pendingAnchor = null
    }
}

/**
 * Tags a preference row as the landing target for a search result. When the pending anchor matches
 * [key], the row is brought into view (scrolling the enclosing [florisVerticalScroll]) and highlighted
 * with a fading accent wash. Rows without a matching pending anchor render unchanged. Attach via the
 * jetpref preference `modifier` parameter, e.g. `SwitchPreference(..., modifier =
 * Modifier.settingsSearchAnchor("typing.suggestion.enabled"))`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.settingsSearchAnchor(key: String): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(key) {
        // React whenever this row becomes the pending anchor (set just before navigating here).
        snapshotFlow { SettingsSearchState.pendingAnchor }.collectLatest { pending ->
            if (pending != key) return@collectLatest
            // The destination arrives via a slide-in transition; scrolling before it settles resolves
            // against a not-yet-final layout and does nothing. Wait it out, then bring the row into view.
            delay(450)
            runCatching { requester.bringIntoView() }
            // Two clear pulses in the accent colour so the row is unmistakable once revealed.
            flash.snapTo(1f)
            flash.animateTo(targetValue = 0f, animationSpec = tween(durationMillis = 700, easing = LinearEasing))
            flash.snapTo(1f)
            flash.animateTo(targetValue = 0f, animationSpec = tween(durationMillis = 1100, easing = LinearEasing))
            SettingsSearchState.consumeAnchor()
        }
    }
    val highlight = MaterialTheme.colorScheme.primary
    return this
        .bringIntoViewRequester(requester)
        // Draw the accent wash OVER the row (jetpref rows paint an opaque container, so drawBehind would
        // be hidden). A brief translucent overlay reads as a clear pulse without obscuring the text.
        .drawWithContent {
            drawContent()
            val a = flash.value
            if (a > 0f) drawRect(color = highlight.copy(alpha = 0.28f * a))
        }
}

/**
 * Newline-separated recent settings-search queries, newest first (issue #187), persisted in
 * [FlorisPreferenceModel.Internal.settingsSearchHistory]. Kept tiny — a handful of recent terms shown
 * as chips when the search box is empty.
 */
object SettingsSearchHistory {
    private const val MAX = 8

    fun recent(prefs: FlorisPreferenceModel): List<String> =
        prefs.internal.settingsSearchHistory.get()
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    suspend fun add(prefs: FlorisPreferenceModel, queryRaw: String) {
        val query = queryRaw.trim()
        if (query.isEmpty()) return
        val updated = buildList {
            add(query)
            addAll(recent(prefs).filterNot { it.equals(query, ignoreCase = true) })
        }.take(MAX)
        prefs.internal.settingsSearchHistory.set(updated.joinToString("\n"))
    }

    suspend fun remove(prefs: FlorisPreferenceModel, query: String) {
        val updated = recent(prefs).filterNot { it.equals(query, ignoreCase = true) }
        prefs.internal.settingsSearchHistory.set(updated.joinToString("\n"))
    }

    suspend fun clear(prefs: FlorisPreferenceModel) {
        prefs.internal.settingsSearchHistory.set("")
    }
}
