/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.sticker

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The state of a running sticker import, so the settings screen can show it (issue #280).
 *
 * Copying a few hundred stickers out of WhatsApp takes minutes, and until this existed the screen
 * simply sat there — the only way to tell it was working at all was to keep re-reading the folder and
 * watch the count climb. Built like [dev.patrickgold.florisboard.dictate.provider.LocalModelDownloads]:
 * the work lives in an application-scoped object rather than in the composable that started it, so
 * leaving the screen and coming back resumes the same progress instead of losing it.
 *
 * Deliberately without a foreground service, unlike model downloads. An import is something the user
 * starts and watches; a notification channel and a service for it would be machinery nobody asked for.
 * Leaving the app cancels the copy, and everything copied up to that point stays.
 */
object StickerImports {
    /** Progress of the running import. [done] counts files handled, not just files copied. */
    data class State(val done: Int, val total: Int) {
        val percent: Int
            get() = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<State?>(null)
    /** Non-null exactly while an import is running. */
    val state: StateFlow<State?> = _state.asStateFlow()

    // Bumped when an import finishes, so the settings screen and the panel know the folder changed.
    private val _importedTick = MutableStateFlow(0)
    val importedTick: StateFlow<Int> = _importedTick.asStateFlow()

    val isRunning: Boolean
        get() = job?.isActive == true

    /**
     * Copies [sources] into [treeUri], reporting progress, and hands the outcome to [onFinished].
     *
     * Does nothing if an import is already running — two of them writing into the same folder would
     * race over the duplicate check and produce copies of copies.
     */
    fun start(
        context: Context,
        treeUri: Uri,
        sources: List<Uri>,
        onFinished: (StickerWriter.ImportResult) -> Unit,
    ) {
        if (isRunning || sources.isEmpty()) return
        val appContext = context.applicationContext
        _state.value = State(done = 0, total = sources.size)
        job = scope.launch {
            try {
                val result = StickerWriter.importInto(appContext, treeUri, sources) { done, total ->
                    val current = _state.value
                    if (current == null || current.done != done) {
                        _state.value = State(done = done, total = total)
                    }
                }
                _state.value = null
                _importedTick.update { it + 1 }
                onFinished(result)
            } catch (e: CancellationException) {
                // Cancelling keeps what was already copied; the caller says so in its message.
                _state.value = null
                _importedTick.update { it + 1 }
                throw e
            } finally {
                job = null
            }
        }
    }

    /** Stops the running import. What has already been copied stays in the folder. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = null
    }
}
