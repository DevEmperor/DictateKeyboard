/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.translate

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

/**
 * Downloads, installs and removes the on-device translator's language packs (issue #424).
 *
 * A language is both of its directions (to and from English), fetched as `.gz` release assets, checked
 * against the catalog's size and SHA-256 *as served*, and unpacked under the names Marian expects. Like
 * [dev.patrickgold.florisboard.dictate.provider.LocalModelManager], an install is atomic: everything
 * lands in a staging directory first, and a failed or cancelled download leaves nothing behind.
 *
 * Unlike it, the download runs in a scope of its own rather than the screen's, so leaving the settings
 * page does not cancel a 70 MB download, and [downloads] and [installed] are flows so that the page and
 * the keyboard's translate bar both see the same state.
 */
object TranslationModelManager {
    private const val ROOT = "translation-models"
    private const val TAG = "TranslationModels"

    /** Progress of a running download, in bytes of the `.gz` files. */
    data class Progress(val downloaded: Long, val total: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()

    private val _downloads = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val downloads: StateFlow<Map<String, Progress>> = _downloads.asStateFlow()

    /** The last download that failed, by language, until the next attempt or a dismissal. */
    private val _failures = MutableStateFlow<Map<String, String>>(emptyMap())
    val failures: StateFlow<Map<String, String>> = _failures.asStateFlow()

    private val _installed = MutableStateFlow<Set<String>>(emptySet())
    @Volatile private var scanned = false

    /** Installed language codes; read from disk on first use, then kept current by this object. */
    fun installed(context: Context): StateFlow<Set<String>> {
        if (!scanned) refresh(context)
        return _installed.asStateFlow()
    }

    fun root(context: Context): File = File(context.applicationContext.filesDir, ROOT)

    fun directionDir(context: Context, direction: TranslationDirection): File = File(root(context), direction.id)

    fun isInstalled(context: Context, direction: TranslationDirection): Boolean {
        val dir = directionDir(context, direction)
        return direction.files.all { File(dir, it.fileName).length() == it.installedBytes }
    }

    fun isInstalled(context: Context, language: TranslationLanguage): Boolean =
        language.directions.all { isInstalled(context, it) }

    /** Bytes the installed languages take on disk. */
    fun installedBytes(context: Context): Long =
        installed(context).value.sumOf { code -> TranslationCatalog.language(code)?.installedBytes ?: 0L }

    fun refresh(context: Context) {
        _installed.value = TranslationCatalog.languages
            .filter { isInstalled(context, it) }
            .mapTo(mutableSetOf()) { it.code }
        scanned = true
    }

    /** Starts downloading [language] unless it is already running. */
    fun download(context: Context, language: TranslationLanguage) {
        val appContext = context.applicationContext
        synchronized(jobs) {
            if (jobs[language.code]?.isActive == true) return
            _failures.update { it - language.code }
            _downloads.update { it + (language.code to Progress(0, language.downloadBytes)) }
            jobs[language.code] = scope.launch {
                try {
                    install(appContext, language)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "Download of ${language.code} failed", e)
                    _failures.update { it + (language.code to (e.message ?: e.javaClass.simpleName)) }
                } finally {
                    _downloads.update { it - language.code }
                    synchronized(jobs) { jobs.remove(language.code) }
                    refresh(appContext)
                }
            }
        }
    }

    fun cancel(language: TranslationLanguage) {
        synchronized(jobs) { jobs[language.code]?.cancel() }
    }

    fun dismissFailure(language: TranslationLanguage) {
        _failures.update { it - language.code }
    }

    fun delete(context: Context, language: TranslationLanguage) {
        language.directions.forEach { directionDir(context, it).deleteRecursively() }
        refresh(context)
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun install(context: Context, language: TranslationLanguage) {
        val staging = File(root(context), ".tmp-${language.code}")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "could not create ${staging.absolutePath}" }
        try {
            var done = 0L
            for (direction in language.directions) {
                val dir = File(staging, direction.id).apply { mkdirs() }
                for (file in direction.files) {
                    fetch(file, File(dir, file.fileName)) { read ->
                        _downloads.update { it + (language.code to Progress(done + read, language.downloadBytes)) }
                    }
                    done += file.downloadBytes
                }
            }
            for (direction in language.directions) {
                val target = directionDir(context, direction)
                target.deleteRecursively()
                val from = File(staging, direction.id)
                if (!from.renameTo(target)) {
                    from.copyRecursively(target, overwrite = true)
                }
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Streams one `.gz` asset, hashing the bytes as served and unpacking them on the fly into [target].
     * [onRead] gets the compressed bytes read so far.
     */
    private suspend fun fetch(file: TranslationModelFile, target: File, onRead: (Long) -> Unit) {
        val request = Request.Builder().url(file.url).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} for ${file.url}" }
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            val counted = object : InputStream() {
                val source = response.body.byteStream()
                override fun read(): Int = source.read().also {
                    if (it >= 0) {
                        digest.update(it.toByte())
                        read++
                    }
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, len).also {
                    if (it > 0) {
                        digest.update(b, off, it)
                        read += it
                        onRead(read)
                    }
                }
                override fun close() = source.close()
            }
            GZIPInputStream(counted, 64 * 1024).use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                }
                // GZIPInputStream stops at the end of the member and need not have seen the body's end;
                // whatever follows still belongs in the hash. Inside `use`: closing the GZIPInputStream
                // closes the body under it, and reading it afterwards failed every download at the end
                // of its first file.
                val rest = ByteArray(8 * 1024)
                while (counted.read(rest, 0, rest.size) >= 0) Unit
            }
            check(read == file.downloadBytes) { "size mismatch for ${file.fileName}: $read of ${file.downloadBytes}" }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            check(sha256 == file.sha256) { "checksum mismatch for ${file.fileName}" }
            check(target.length() == file.installedBytes) { "unpacked size mismatch for ${file.fileName}" }
        }
    }
}
