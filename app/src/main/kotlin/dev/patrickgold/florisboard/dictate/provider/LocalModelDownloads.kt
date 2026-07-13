/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.provider

import android.content.Context
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
 * App-scoped coordinator for on-device model downloads (issue #207). Unlike a download launched from a
 * screen's own coroutine scope, work started here survives the provider dialog closing or the user leaving
 * the app: the coroutine lives in an application-lifetime scope and a foreground service
 * ([ModelDownloadService]) keeps the process alive and shows an ongoing progress notification. Any UI that
 * is on screen observes [state] to render live progress, so re-opening the dialog resumes the bar at the
 * current percentage instead of losing it.
 */
object LocalModelDownloads {
    /** Progress of one model download. [percent] is 0..100; a non-null [error] marks a failed download. */
    data class State(val modelId: String, val percent: Int, val error: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()

    private val _state = MutableStateFlow<Map<String, State>>(emptyMap())
    /** modelId -> current state. Present while downloading or holding a not-yet-dismissed error. */
    val state: StateFlow<Map<String, State>> = _state.asStateFlow()

    // Bumped whenever a download finishes successfully, so the settings UI can recompute the installed set.
    private val _installedTick = MutableStateFlow(0)
    val installedTick: StateFlow<Int> = _installedTick.asStateFlow()

    /** Whether any download is currently running (errors don't count) — the service stops when none are. */
    fun hasActiveDownloads(): Boolean = _state.value.values.any { it.error == null }

    /** Starts the download for [spec] unless it is already running. Idempotent per model id. */
    fun start(context: Context, spec: LocalModelSpec) {
        val appContext = context.applicationContext
        if (jobs[spec.id]?.isActive == true) return
        _state.update { it + (spec.id to State(spec.id, 0)) }
        ModelDownloadService.start(appContext)
        jobs[spec.id] = scope.launch {
            try {
                LocalModelManager.download(appContext, spec) { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                    val cur = _state.value[spec.id]
                    if (cur != null && cur.percent != pct) {
                        _state.update { it + (spec.id to cur.copy(percent = pct)) }
                    }
                }
                _state.update { it - spec.id }
                _installedTick.update { it + 1 }
            } catch (_: CancellationException) {
                _state.update { it - spec.id }
            } catch (t: Throwable) {
                val pct = _state.value[spec.id]?.percent ?: 0
                _state.update { it + (spec.id to State(spec.id, pct, error = t.message ?: "download failed")) }
            } finally {
                jobs.remove(spec.id)
            }
        }
    }

    /** Cancels an in-flight download and clears its state (staging dir is cleaned up by the manager). */
    fun cancel(modelId: String) {
        jobs[modelId]?.cancel()
        jobs.remove(modelId)
        _state.update { it - modelId }
    }

    /** Dismisses a failed download's error entry. */
    fun clearError(modelId: String) {
        val cur = _state.value[modelId] ?: return
        if (cur.error != null) _state.update { it - modelId }
    }
}
