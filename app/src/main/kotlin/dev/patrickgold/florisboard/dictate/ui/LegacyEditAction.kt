/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.ui.graphics.vector.ImageVector
import dev.patrickgold.florisboard.R

/**
 * An action that can be placed in the legacy dictation layout's configurable action row (issue #183 /
 * #194). The user arranges these freely in Settings → Dictate → Dictation layout; the row renders the
 * chosen ones left-to-right. Enum names are persisted (comma-separated) in
 * [dev.patrickgold.florisboard.app.AppPrefs.Dictate.legacyActionRow], so DON'T rename them.
 */
enum class LegacyEditAction {
    SELECT_ALL,
    UNDO,
    REDO,
    CUT,
    COPY,
    PASTE,
    EMOJI,
    NUMBERS,
    LANGUAGE,
    HISTORY,
    REINSERT,
    GIF,
    SWITCH,
    BACKSPACE;

    val icon: ImageVector
        get() = when (this) {
            SELECT_ALL -> Icons.Default.SelectAll
            UNDO -> Icons.AutoMirrored.Filled.Undo
            REDO -> Icons.AutoMirrored.Filled.Redo
            CUT -> Icons.Default.ContentCut
            COPY -> Icons.Default.ContentCopy
            PASTE -> Icons.Default.ContentPaste
            EMOJI -> Icons.Default.EmojiEmotions
            NUMBERS -> Icons.Default.Numbers
            LANGUAGE -> Icons.Default.Language
            HISTORY -> Icons.Default.History
            REINSERT -> Icons.Default.Replay
            GIF -> Icons.Outlined.Gif
            SWITCH -> Icons.Default.KeyboardHide
            BACKSPACE -> Icons.Default.Backspace
        }

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            SELECT_ALL -> R.string.dictate__legacy_action_select_all
            UNDO -> R.string.dictate__legacy_action_undo
            REDO -> R.string.dictate__legacy_action_redo
            CUT -> R.string.dictate__legacy_action_cut
            COPY -> R.string.dictate__legacy_action_copy
            PASTE -> R.string.dictate__legacy_action_paste
            EMOJI -> R.string.dictate__legacy_action_emoji
            NUMBERS -> R.string.dictate__legacy_action_numbers
            LANGUAGE -> R.string.dictate__legacy_action_language
            HISTORY -> R.string.dictate__legacy_action_history
            REINSERT -> R.string.dictate__legacy_action_reinsert
            GIF -> R.string.dictate__legacy_action_gif
            SWITCH -> R.string.dictate__legacy_action_switch
            // Reuses the existing backspace content-description string (already localised everywhere).
            BACKSPACE -> R.string.dictate__legacy_backspace
        }

    companion object {
        /** The original fixed row — the default when the user hasn't customised it. */
        val DEFAULT: List<LegacyEditAction> =
            listOf(SELECT_ALL, UNDO, REDO, CUT, COPY, PASTE, EMOJI, NUMBERS)

        /** Most action rows fit about this many keys comfortably across a phone; the editor caps here. */
        const val MAX_ROW = 8

        /** Serialises [actions] for the pref (comma-separated enum names). */
        fun serialize(actions: List<LegacyEditAction>): String = actions.joinToString(",") { it.name }

        /** Parses the pref value back into actions, ignoring unknown names; empty falls back to [DEFAULT]. */
        fun parse(raw: String): List<LegacyEditAction> {
            val parsed = raw.split(',')
                .mapNotNull { token -> entries.firstOrNull { it.name == token.trim() } }
            return parsed.ifEmpty { DEFAULT }
        }
    }
}
