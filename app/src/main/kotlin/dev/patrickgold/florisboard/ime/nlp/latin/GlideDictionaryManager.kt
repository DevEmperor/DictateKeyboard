/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.nlp.latin

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Downloads, installs and removes the per-language glide-typing dictionaries served from the project's
 * GitHub release (issue #127, phase 2). Each dictionary is a single `<lang>.json` file fetched over HTTPS
 * into the app's private storage (`filesDir/glide-dicts/`); no text or telemetry is ever sent.
 *
 * Installs are atomic: the file downloads into `<lang>.json.tmp`, is size/checksum-verified, and only
 * then replaces the real file. A failed or cancelled download leaves any previous dictionary untouched.
 * Mirrors [dev.patrickgold.florisboard.dictate.provider.LocalModelManager].
 */
object GlideDictionaryManager {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val active = Collections.synchronizedSet(mutableSetOf<String>())

    /** lang → download progress in 0..100 while a download is in flight (absent otherwise). */
    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val progress: StateFlow<Map<String, Int>> = _progress.asStateFlow()

    /** Bumped after every successful install so observers (UI, classifier caches) can react. */
    private val _installedVersion = MutableStateFlow(0)
    val installedVersion: StateFlow<Int> = _installedVersion.asStateFlow()

    /**
     * Starts a background download of the glide dictionary for [lang] if it has a catalog entry and isn't
     * already installed or downloading (issue #127). Progress is published on [progress]; [installedVersion]
     * bumps on success. Best effort — failures leave the language uninstalled for a later retry.
     */
    fun ensureDownloaded(context: Context, lang: String) {
        val code = LatinLanguageProvider.normalizeLang(lang)
        val dictSpec = GlideDictionaryCatalog.forLang(code)
        val bigramSpec = BigramCatalog.forLang(code)
        val needDict = dictSpec != null && !isInstalled(context, code)
        // The bigram file (autocorrect context, Tier 2) is fetched together with the glide dictionary when
        // an input language is added; also downloads on its own if the dict is already present from before.
        val needBigram = bigramSpec != null && !bigramInstalled(context, code)
        if (!needDict && !needBigram) return
        if (!active.add(code)) return
        val appContext = context.applicationContext
        _progress.value = _progress.value + (code to 0)
        scope.launch {
            try {
                if (needDict) {
                    download(appContext, dictSpec!!) { done, total ->
                        val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                        if (_progress.value[code] != pct) _progress.value = _progress.value + (code to pct)
                    }
                }
                if (needBigram) {
                    // Best effort — a missing bigram file just means no context model for this language.
                    runCatching {
                        downloadTo(bigramFile(appContext, code), bigramSpec!!.url, bigramSpec.sizeBytes, bigramSpec.sha256)
                    }
                }
                _installedVersion.value += 1
            } catch (_: Throwable) {
                // leave uninstalled; a later activation/add retries
            } finally {
                active.remove(code)
                _progress.value = _progress.value - code
            }
        }
    }

    fun dictsRoot(context: Context): File = File(context.filesDir, "glide-dicts")

    fun dictFile(context: Context, lang: String): File =
        File(dictsRoot(context), "${lang.lowercase()}.json")

    /** The downloaded bigram file for [lang] (autocorrect Tier 2), kept next to the glide dictionary. */
    fun bigramFile(context: Context, lang: String): File =
        File(dictsRoot(context), "${lang.lowercase()}_bigrams.txt")

    /** True if a downloaded dictionary for [lang] is present on disk. */
    fun isInstalled(context: Context, lang: String): Boolean =
        dictFile(context, lang).let { it.isFile && it.length() > 0 }

    /** True if a downloaded bigram file for [lang] is present on disk. */
    fun bigramInstalled(context: Context, lang: String): Boolean =
        bigramFile(context, lang).let { it.isFile && it.length() > 0 }

    /** Language codes of all downloaded dictionaries currently on disk. */
    fun installedLangs(context: Context): List<String> =
        dictsRoot(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") && it.length() > 0 }
            ?.map { it.name.removeSuffix(".json") }
            ?: emptyList()

    /** Removes a downloaded dictionary. Returns true if the file is gone afterwards. */
    fun delete(context: Context, lang: String): Boolean {
        val f = dictFile(context, lang)
        return !f.exists() || f.delete()
    }

    /**
     * Removes a downloaded dictionary (never the bundled ones) and notifies observers — used when the last
     * subtype of a language is removed (issue #127).
     */
    fun deleteDownloaded(context: Context, lang: String) {
        val code = LatinLanguageProvider.normalizeLang(lang)
        var changed = false
        // Remove the downloaded bigram file too (never a bundled one), so removing a language frees its
        // context data as well.
        if (code !in BigramCatalog.BUNDLED && bigramInstalled(context, code)) {
            changed = bigramFile(context, code).delete() || changed
        }
        if (code !in GlideDictionaryCatalog.BUNDLED && isInstalled(context, code) && delete(context, code)) {
            changed = true
        }
        if (changed) _installedVersion.value += 1
    }

    /**
     * Downloads and installs [spec]. [onProgress] is invoked with `(downloadedBytes, totalBytes)` as the
     * download proceeds (throttle on the UI side). Suspends until done; throws on any failure (network,
     * HTTP, size/checksum mismatch) after cleaning up the staging file.
     */
    suspend fun download(
        context: Context,
        spec: GlideDict,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Unit = downloadTo(dictFile(context, spec.lang), spec.url, spec.sizeBytes, spec.sha256, onProgress)

    /**
     * Downloads [url] into [dest] atomically (via a `.tmp` sibling), verifying the byte size and SHA-256
     * against [sizeBytes]/[sha256]. Used for both the glide dictionary and the bigram file. [onProgress] is
     * invoked with `(downloadedBytes, totalBytes)`. Suspends until done; throws on any failure after
     * cleaning up the staging file, leaving any previous [dest] untouched.
     */
    private suspend fun downloadTo(
        dest: File,
        url: String,
        sizeBytes: Long,
        sha256: String,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Unit = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        tmp.delete()
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code} downloading $url" }
                val body = response.body ?: error("empty response body for $url")
                val digest = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input ->
                    tmp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            done += read
                            onProgress(done, sizeBytes)
                        }
                    }
                }
                check(tmp.length() == sizeBytes) {
                    "size mismatch for ${dest.name}: expected $sizeBytes, got ${tmp.length()}"
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual.equals(sha256, ignoreCase = true)) { "checksum mismatch for ${dest.name}" }
            }
            dest.delete()
            check(tmp.renameTo(dest)) { "could not move ${dest.name} into place" }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }
}
