/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.data.prompts

import android.content.Context

/**
 * The pre-v6 record of which community-library prompts had been imported (issue #105), kept only to
 * hand its contents over to the rows themselves (issue #303).
 *
 * It was a set of library ids in SharedPreferences, written on every import and **never** removed
 * from. That made the "Added" marker a tombstone rather than a state: deleting a prompt — or
 * replacing the list via a JSON import or a restored backup — left its library entry marked as added
 * for the life of the installation, with no way back from inside the app.
 *
 * Since `PROMPTS.LIBRARY_ID` exists the mark travels with the row, so nothing writes here any more.
 * The set is still read once per library open by [PromptLibraryMarks.carryOver], which re-attaches the
 * old ids to the rows that still match them; ids whose prompt is gone are simply dropped. Once enough
 * releases have passed for that carry-over to have run everywhere, this file can go.
 */
object PromptLibraryLegacyStore {
    private const val PREFS = "prompt_library"
    private const val KEY_IDS = "installed_ids"

    /** The library ids recorded before the mark moved onto the prompt rows. Empty on a fresh install. */
    fun all(context: Context): Set<String> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_IDS, emptySet()) ?: emptySet()
}
