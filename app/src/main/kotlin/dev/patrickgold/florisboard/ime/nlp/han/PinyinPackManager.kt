/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.nlp.han

import android.content.Context
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.ext.ExtensionManager
import dev.patrickgold.florisboard.lib.io.FlorisRef
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * Fetches the Chinese Pinyin language pack on demand (issue #262).
 *
 * Pinyin needs a reading table of its own — roughly 170,000 rows, 3 MB packed — and Chinese is a minority
 * of installs, so it is downloaded rather than carried in the APK by everyone. The same trade the glide
 * dictionaries and the on-device models already make; [dev.patrickgold.florisboard.ime.nlp.latin
 * .GlideDictionaryManager] is the model this follows, down to the atomic install.
 *
 * Installing is only a matter of putting the file in the right place. [ExtensionManager.ExtensionIndex]
 * watches the internal language-pack directory with a `FileObserver` and reindexes whatever appears there,
 * and [HanShapeBasedLanguageProvider] notices its set of active packs changed on the next keystroke. So a
 * download that finishes while the keyboard is open takes effect without a restart, with no extra wiring.
 *
 * The staging file is named `.flex.tmp` deliberately: the index only picks up names ending in `.flex`, so
 * a half-written pack can never be indexed, and a failed download leaves any previous one untouched.
 */
object PinyinPackManager {

    const val EXTENSION_ID = "net.devemperor.dictate.pinyin"

    private const val FILE_NAME = "$EXTENSION_ID.flex"

    /**
     * Project-hosted release holding downloadable keyboard language packs — named for the category, not
     * for this one file, so a traditional-Chinese or Japanese pack later joins it instead of adding
     * another tag. Bump the `-v1` when a pack is rebuilt rather than replacing an asset in place: an
     * installed app verifies against the size and hash baked in below, and would reject a changed file.
     */
    private const val RELEASE =
        "https://github.com/DevEmperor/DictateKeyboard/releases/download/language-packs-v1"
    private const val URL = "$RELEASE/$FILE_NAME"
    private const val SIZE_BYTES = 3_030_654L
    private const val SHA256 = "861309d3c2f5461808b3f1dcb28eeb284e9a67c27b2130d03233d927b0eb0159"

    /**
     * Locale tags this pack provides an input method for. Only Simplified Chinese for now; a traditional
     * table would be a second entry here and a second table in the same file.
     */
    private val LOCALE_TAGS = setOf("zh_CN_pinyin")

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val downloading = AtomicBoolean(false)

    /** Download progress in 0..100 while a download is in flight, null otherwise. */
    private val _progress = MutableStateFlow<Int?>(null)
    val progress: StateFlow<Int?> = _progress.asStateFlow()

    /** True if [locale] is a subtype whose input method lives in this pack. */
    fun handles(locale: FlorisLocale): Boolean = locale.localeTag() in LOCALE_TAGS

    /** Where the index looks for user-installed language packs. */
    private fun packDir(context: Context): File =
        FlorisRef.internal(ExtensionManager.IME_LANGUAGEPACK_PATH).absoluteFile(context)

    fun packFile(context: Context): File = File(packDir(context), FILE_NAME)

    fun isInstalled(context: Context): Boolean {
        val file = packFile(context)
        // Size is checked, not just existence: a pack left half-written by an older build (or a version
        // that has since been replaced upstream) has to be fetched again rather than trusted.
        return file.exists() && file.length() == SIZE_BYTES
    }

    /**
     * Starts a background download of the pack if [locale] needs it and it is not already installed or in
     * flight. Best effort: a failure leaves it uninstalled, and adding or activating the subtype again
     * retries. Safe to call often — it is a no-op in every other case.
     */
    fun ensureDownloaded(context: Context, locale: FlorisLocale) {
        if (!handles(locale) || isInstalled(context)) return
        if (!downloading.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        _progress.value = 0
        scope.launch {
            try {
                download(appContext) { done, total ->
                    val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                    if (_progress.value != pct) _progress.value = pct
                }
            } catch (_: Throwable) {
                // Leave it uninstalled; the next activation of the subtype tries again.
            } finally {
                downloading.set(false)
                _progress.value = null
            }
        }
    }

    /** Removes an installed pack. Returns true if it is gone afterwards. */
    fun delete(context: Context): Boolean {
        val file = packFile(context)
        return !file.exists() || file.delete()
    }

    /**
     * Downloads the pack into place atomically, verifying size and SHA-256 before it is moved. Throws on
     * any failure after cleaning up the staging file.
     */
    private suspend fun download(
        context: Context,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Unit = withContext(Dispatchers.IO) {
        val dest = packFile(context)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        tmp.delete()
        try {
            client.newCall(Request.Builder().url(URL).build()).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code} downloading $URL" }
                val body = response.body ?: error("empty response body for $URL")
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
                            onProgress(done, SIZE_BYTES)
                        }
                    }
                }
                check(tmp.length() == SIZE_BYTES) {
                    "size mismatch for $FILE_NAME: expected $SIZE_BYTES, got ${tmp.length()}"
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual.equals(SHA256, ignoreCase = true)) { "checksum mismatch for $FILE_NAME" }
            }
            dest.delete()
            check(tmp.renameTo(dest)) { "could not move $FILE_NAME into place" }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }
}
