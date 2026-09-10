/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.nlp

import android.content.Context
import android.net.ConnectivityManager

/**
 * Whether the keyboard may fetch language data right now (issue #334).
 *
 * The word lists, context tables and the Pinyin pack all download **by themselves** — on adding a
 * language, and again on every activation of one whose data is missing. Nobody asks for them and
 * nobody is told they are happening, which was defensible while a language cost about two megabytes.
 * With the context tables it is four to fourteen, depending on the script: a Georgian table holds the
 * same number of entries as a Danish one but each character costs three UTF-8 bytes instead of one.
 * Three languages on a phone can therefore mean thirty megabytes arriving unannounced, and on a
 * metered connection that is somebody's money.
 *
 * So the automatic fetches wait for an unmetered connection unless the user says otherwise. This is
 * deliberately **not** applied to downloads a person started themselves — the speech models on the
 * Dictate screen have their own button and their own progress bar, and a download somebody just tapped
 * is not one to second-guess.
 *
 * A blocked download is not an error and nothing is retried in the background: `ensureDownloaded` runs
 * again on the next activation of that language, so being on Wi-Fi later is all it takes. What that
 * costs is a language whose data never arrives for somebody who is never on Wi-Fi, which is why the
 * subtype row says so rather than showing the same "will download" mark it shows when it means it.
 */
object DownloadPolicy {

    private val prefs by dev.patrickgold.florisboard.app.FlorisPreferenceStore

    /**
     * True when an automatic download may start. Answers **true** whenever the connection type cannot
     * be determined: refusing to fetch on the strength of a missing answer would leave a language
     * permanently empty on a device whose network state we simply failed to read.
     */
    fun allowsAutomaticDownload(context: Context): Boolean {
        if (!prefs.localization.downloadLanguageDataOnWifiOnly.get()) return true
        return !isMetered(context)
    }

    /** True only when the active connection is known to be metered. */
    fun isMetered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching { manager.isActiveNetworkMetered }.getOrDefault(false)
    }

    /**
     * Whether [context] is in the state the subtype rows have to explain: data is missing, and the
     * only reason it is not being fetched is the connection.
     */
    fun isWaitingForUnmeteredConnection(context: Context): Boolean =
        prefs.localization.downloadLanguageDataOnWifiOnly.get() && isMetered(context)
}
