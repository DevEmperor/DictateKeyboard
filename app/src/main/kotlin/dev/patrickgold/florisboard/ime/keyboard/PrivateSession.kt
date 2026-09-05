/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.keyboard

import android.content.Context
import dev.patrickgold.florisboard.keyboardManager

/**
 * The one place that answers "is the keyboard currently attached to a field that should leave no
 * trace?" for code outside the NLP stack (issue #329).
 *
 * The suggestion providers have been asking this question for a long time — they take it as an
 * `isPrivateSession` parameter (see [dev.patrickgold.florisboard.ime.nlp.SuggestionProvider.suggest]).
 * The media panels never asked it at all: incognito paused word learning while emoji, sticker and GIF
 * use, and the *typed GIF search terms*, kept being written to disk.
 *
 * Deliberately not a `KeyboardState` extension: the callers are `object` helpers and composables that
 * have a [Context] but no keyboard state, and the point of a single named function is that the answer
 * cannot quietly differ between them.
 */
object PrivateSession {
    /**
     * True while the active editor is in incognito mode.
     *
     * Returns `false` when there is no keyboard session to ask — the media panels also render inside
     * the settings app, where a missing [KeyboardManager] is a normal state and not a reason to throw.
     * "Not incognito" is the right answer there: nothing is being typed into anyone's field.
     */
    fun isActive(context: Context): Boolean = runCatching {
        context.keyboardManager().value.activeState.isIncognitoMode
    }.getOrDefault(false)
}
