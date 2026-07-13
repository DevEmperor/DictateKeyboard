/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.gif

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.editor.EditorInstance
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Ties the GIF backend together for the UI: builds the (provider-agnostic) [GifProvider] from the
 * user's settings, downloads a chosen GIF to a cache the app's FileProvider can serve, and inserts it
 * into the current editor via [EditorInstance.commitMedia] (with a clipboard fallback). Also records
 * usage in [GifHistory] and pings the provider for ranking.
 */
object GifManager {
    private val prefs by FlorisPreferenceStore

    /** The active provider. Reads the API key / customer id lazily on each call, so it stays valid
     *  even after the user changes their key in settings. */
    val provider: GifProvider = KlipyGifProvider(
        apiKeyProvider = { prefs.gif.klipyApiKey.get() },
        customerIdProvider = { prefs.gif.customerId.get() },
    )

    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Generates and persists a stable per-install id for KLIPY relevance/localization, if not set yet. */
    suspend fun ensureCustomerId() {
        if (prefs.gif.customerId.get().isBlank()) {
            prefs.gif.customerId.set(UUID.randomUUID().toString())
        }
    }

    /**
     * Downloads [item]'s full GIF into `cacheDir/gif-media/` (reusing an existing file if present) and
     * returns it, or null on failure.
     */
    private suspend fun download(context: Context, item: GifItem): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "gif-media").apply { mkdirs() }
            val safeName = item.id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                .take(64).ifBlank { "gif" }
            val file = File(dir, "$safeName.gif")
            if (file.exists() && file.length() > 0L) return@withContext file
            val request = Request.Builder().url(item.fullGifUrl).build()
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                file.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            if (file.length() > 0L) file else null
        } catch (e: Exception) {
            flogError { "Failed to download GIF ${item.id}: ${e.message}" }
            null
        }
    }

    /**
     * Downloads [item] and inserts it into the current editor. On success (inserted or copied to the
     * clipboard) the GIF is recorded in the history and a best-effort share ping is sent.
     */
    suspend fun insertGif(context: Context, item: GifItem): EditorInstance.MediaCommitResult {
        val file = download(context, item) ?: return EditorInstance.MediaCommitResult.FAILED
        val editorInstance by context.editorInstance()
        // Committing rich content talks to the InputConnection — do it on the main thread.
        val result = withContext(Dispatchers.Main) {
            editorInstance.commitMedia(file, MIME_GIF, item.title?.ifBlank { "GIF" } ?: "GIF")
        }
        if (result != EditorInstance.MediaCommitResult.FAILED) {
            GifHistoryHelper.addInsertedGif(prefs, item)
            provider.registerShare(item.id)
        }
        return result
    }

    const val MIME_GIF = "image/gif"
}
