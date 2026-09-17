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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Downloads, installs and removes the on-device transcription models consumed by
 * [LocalTranscriptionProvider] (issue #104). A model is a small set of files ([LocalModelSpec]) fetched
 * over HTTPS into the app's private storage; no audio or telemetry is ever sent.
 *
 * Installs are atomic: files download into a `.tmp-<id>` staging dir, are size/checksum-verified, and
 * only then replace the real model dir. A failed or cancelled download leaves any previously installed
 * model untouched. All work runs on [Dispatchers.IO] and honours coroutine cancellation.
 */
object LocalModelManager {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS) // model files are large; no overall cap on the download
            .build()
    }

    /** True if [modelId] is fully installed (all required files present). */
    fun isInstalled(context: Context, modelId: String): Boolean =
        LocalTranscriptionProvider.isInstalled(context, modelId)

    /** Ids of all catalog models currently installed on disk. */
    fun installedIds(context: Context): List<String> =
        LocalModelCatalog.all.map { it.id }.filter { isInstalled(context, it) }

    /** Removes an installed model. Returns true if the directory is gone afterwards. */
    fun delete(context: Context, modelId: String): Boolean {
        val dir = LocalTranscriptionProvider.modelDir(context, modelId)
        if (!dir.exists()) return true
        return dir.deleteRecursively()
    }

    /** On-disk size of an installed model in bytes (0 if not installed). */
    fun installedSizeBytes(context: Context, modelId: String): Long {
        val dir = LocalTranscriptionProvider.modelDir(context, modelId)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Downloads and installs [spec]. [onProgress] is invoked with `(downloadedBytes, totalBytes)` as the
     * download proceeds (frequently — throttle on the UI side). Suspends until done; throws on any
     * failure (network, HTTP, size/checksum mismatch) after cleaning up the staging dir.
     */
    suspend fun download(
        context: Context,
        spec: LocalModelSpec,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Unit = withContext(Dispatchers.IO) {
        val root = LocalTranscriptionProvider.modelsRoot(context)
        val finalDir = LocalTranscriptionProvider.modelDir(context, spec.id)
        val tmpDir = File(root, ".tmp-${spec.id}")
        tmpDir.deleteRecursively()
        check(tmpDir.mkdirs()) { "could not create staging dir ${tmpDir.absolutePath}" }
        try {
            val total = spec.totalBytes
            var completed = 0L
            for (f in spec.files) {
                downloadFile(f, File(tmpDir, f.destName), completed, total, onProgress)
                completed += f.sizeBytes
                onProgress(completed, total)
            }
            verifyDownloadManifest(spec, tmpDir)
            // Swap staging dir into place atomically (same filesystem); copy-fallback if rename fails.
            finalDir.deleteRecursively()
            finalDir.parentFile?.mkdirs()
            if (!tmpDir.renameTo(finalDir)) {
                tmpDir.copyRecursively(finalDir, overwrite = true)
                tmpDir.deleteRecursively()
            }
        } catch (t: Throwable) {
            tmpDir.deleteRecursively()
            throw t
        }
    }

    private suspend fun downloadFile(
        file: LocalModelFile,
        dest: File,
        baseCompleted: Long,
        total: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        val request = Request.Builder().url(file.url).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} downloading ${file.url}" }
            val body = response.body ?: error("empty response body for ${file.url}")
            val digest = if (file.sha256 != null) MessageDigest.getInstance("SHA-256") else null
            body.byteStream().use { input ->
                dest.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var fileDone = 0L
                    while (true) {
                        coroutineContext.ensureActive() // cancellation-aware
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest?.update(buffer, 0, read)
                        fileDone += read
                        onProgress(baseCompleted + fileDone, total)
                    }
                }
            }
            check(dest.length() == file.sizeBytes) {
                "size mismatch for ${file.destName}: expected ${file.sizeBytes}, got ${dest.length()}"
            }
            if (digest != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual.equals(file.sha256, ignoreCase = true)) {
                    "checksum mismatch for ${file.destName}"
                }
            }
        }
    }
}
