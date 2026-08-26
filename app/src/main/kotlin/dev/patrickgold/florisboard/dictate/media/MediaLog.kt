/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.media

import android.util.Log

/**
 * A log channel for inserting pictures that also speaks in a release build.
 *
 * The project's own `flog*` functions are installed with `isFloggingEnabled = BuildConfig.DEBUG`
 * (`FlorisApplication.kt`), so in the APK people actually run they evaluate to nothing. That is the
 * right default for a keyboard — but it means the one path that has to be diagnosed from a user's
 * device, where a picture is handed to a third-party app that may quietly refuse it, is silent
 * exactly where it matters.
 *
 * So this writes at INFO under a tag of its own: `adb logcat -s DictateMedia` shows one block per
 * insert — what the file is, what the app said it takes, what was offered, how long any re-encoding
 * took, and whether it was accepted. A few lines per tap, only while a picture is being inserted.
 */
object MediaLog {
    const val TAG = "DictateMedia"

    fun log(message: String) {
        Log.i(TAG, message)
    }

    /** Runs [block], logs how long it took under [label], and returns its result. */
    inline fun <T> timed(label: String, block: () -> T): T {
        val started = System.currentTimeMillis()
        val result = block()
        log("$label took ${System.currentTimeMillis() - started} ms")
        return result
    }
}
