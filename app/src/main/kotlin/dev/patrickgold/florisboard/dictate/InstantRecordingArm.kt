/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate

import android.content.Context

/**
 * The one bit that says "the user has just come to Dictate from another keyboard", so instant recording
 * can fire on that and on nothing else (issue #224).
 *
 * The Android IME API never reports how a keyboard was opened, which is why "only start when I switch
 * to Dictate" looked impossible. Two things stand in for it, and both simply set this bit:
 *
 *  - **The service being created.** Android creates a keyboard service when it becomes the selected
 *    input method and destroys it when another takes over, so a fresh service *is* a switch-in — no
 *    matter how the user made it, our own globe key, the system picker or the notification shade. A
 *    plain tap into another field never creates one, which is exactly the case this mode excludes.
 *  - **Our own switch-away key**, for the case where the service outlives the round trip.
 *
 * **Why this does not live in the app's preferences.** Both halves of the round trip race the
 * preference store, and losing either one makes the feature simply never fire:
 *
 *  - *Writing.* The flag is set in the moment the IME hands control to another keyboard. The
 *    preference store's write is a suspending call on a scope tied to this service's lifecycle, and
 *    that lifecycle is exactly what is being torn down — the coroutine can be cancelled before
 *    anything reaches disk.
 *  - *Reading.* Coming back can start a fresh process, and the preference store is loaded
 *    asynchronously at startup ([dev.patrickgold.florisboard.FlorisApplication.init]). The first
 *    `onStartInputView` can easily run before that load finishes, and would then be told the flag is
 *    unset because that is the *default*, not because it was never written.
 *
 * `SharedPreferences` answers both: [android.content.SharedPreferences.Editor.commit] returns only
 * once the value is on disk, and the first read blocks until the file has been parsed. One bit, one
 * file, no shared state with anything else — the cost is a few milliseconds on a keyboard switch,
 * which is a keypress the user is already waiting on.
 */
object InstantRecordingArm {
    private const val FILE_NAME = "dictate_instant_recording_arm"
    private const val KEY_ARMED = "armed"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Records that the user is leaving through our own switch key. Deliberately synchronous: the point
     * of this write is that it survives what happens immediately after it.
     */
    fun arm(context: Context) {
        runCatching { prefs(context).edit().putBoolean(KEY_ARMED, true).commit() }
    }

    /**
     * Whether this open is a return trip — and clears the flag either way.
     *
     * Clearing unconditionally is what keeps a switch from going off much later on an unrelated field:
     * if the return landed somewhere instant recording does not apply (a number field, a dictation
     * already running), the intention is spent all the same.
     */
    fun consume(context: Context): Boolean = runCatching {
        val prefs = prefs(context)
        val armed = prefs.getBoolean(KEY_ARMED, false)
        if (armed) prefs.edit().putBoolean(KEY_ARMED, false).apply()
        armed
    }.getOrDefault(false)
}
