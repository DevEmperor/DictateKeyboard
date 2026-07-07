/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app

import androidx.annotation.StringRes
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.util.VersionName

/**
 * The in-app changelog shown by [ChangelogDialog] after the app was updated.
 *
 * ## Announcing a new release
 * 1. Add the release notes as a string resource (one change per line), e.g. `changelog__v4_1_0`.
 * 2. Prepend a new [Entry] to [entries] with that version and string. Keep the list newest-first.
 *
 * The dialog automatically shows every entry newer than the version the user last saw, so users who
 * skip a few updates still get the full list of what changed since their installed version.
 */
object DictateChangelog {
    data class Entry(val version: String, @StringRes val notes: Int)

    /** All releases, newest first. */
    val entries: List<Entry> = listOf(
        Entry("5.0.0", R.string.changelog__v5_0_0),
        Entry("4.3.0", R.string.changelog__v4_3_0),
        Entry("4.2.0", R.string.changelog__v4_2_0),
        Entry("4.1.1", R.string.changelog__v4_1_1),
        Entry("4.1.0", R.string.changelog__v4_1_0),
        Entry("4.0.0", R.string.changelog__current),
    )

    /**
     * The entries to show given the version the user last acknowledged ([since]). Returns everything
     * strictly newer than [since]. A null/unparseable [since] (e.g. a fresh install or a debug build
     * whose version name can't be parsed) falls back to just the latest entry, so the dialog never
     * dumps the entire history on someone who has no recorded "last seen" version.
     */
    fun entriesSince(since: VersionName?): List<Entry> {
        if (since == null) return entries.take(1)
        return entries
            .filter { (VersionName.fromString(it.version) ?: VersionName.DEFAULT) > since }
            .ifEmpty { entries.take(1) }
    }
}
