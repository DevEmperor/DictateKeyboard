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
import dev.patrickgold.florisboard.ime.nlp.han.PinyinPackManager
import dev.patrickgold.florisboard.ime.nlp.latin.BigramCatalog
import dev.patrickgold.florisboard.ime.nlp.latin.GlideDictionaryCatalog
import dev.patrickgold.florisboard.ime.nlp.latin.GlideDictionaryManager
import dev.patrickgold.florisboard.ime.nlp.latin.LatinLanguageProvider
import dev.patrickgold.florisboard.ime.nlp.latin.TrigramCatalog
import dev.patrickgold.florisboard.lib.FlorisLocale
import kotlin.math.roundToInt

/**
 * How much a language still has to fetch, so somebody can be told before it happens (issue #334).
 *
 * Adding a language pulls down its word list, its two context tables and — for Chinese — a reading
 * pack, and until now all of that simply began. That was a couple of megabytes when the context data
 * was a 60,000-pair table. It is now four to fourteen, and the spread is script rather than
 * vocabulary: the tables hold the same number of entries in every language, but a Georgian or Tamil
 * character costs three UTF-8 bytes where a Latin one costs one.
 *
 * The keyboard has no way to tell a metered connection from an unmetered one — that needs
 * `ACCESS_NETWORK_STATE`, a permission this app deliberately does not ask for — so it cannot decide on
 * anyone's behalf when a download is affordable. What it can do is say the number and wait to be told,
 * which is the better answer anyway: the person holding the phone knows what their connection costs.
 */
object LanguageDataDownload {

    /**
     * Bytes [locale] would fetch right now, counting only what is actually missing. Zero means the
     * language is either fully installed or has no data to fetch, and nothing needs to be asked.
     */
    fun pendingBytes(context: Context, locale: FlorisLocale): Long {
        val lang = LatinLanguageProvider.normalizeLang(locale.language)
        var bytes = 0L
        if (lang !in GlideDictionaryCatalog.BUNDLED && !GlideDictionaryManager.isInstalled(context, lang)) {
            bytes += GlideDictionaryCatalog.forLang(lang)?.sizeBytes ?: 0L
        }
        if (lang !in BigramCatalog.BUNDLED && !GlideDictionaryManager.bigramInstalled(context, lang)) {
            bytes += BigramCatalog.forLang(lang)?.sizeBytes ?: 0L
        }
        if (!GlideDictionaryManager.trigramInstalled(context, lang)) {
            bytes += TrigramCatalog.forLang(lang)?.sizeBytes ?: 0L
        }
        if (PinyinPackManager.handles(locale) && !PinyinPackManager.isInstalled(context)) {
            bytes += PinyinPackManager.SIZE_BYTES
        }
        return bytes
    }

    /**
     * [bytes] as a short megabyte figure for a dialog, e.g. `4.5 MB`.
     *
     * Megabytes in the sense a download counter uses — a million bytes, not 1,048,576 — because that
     * is the number a mobile plan is written in, and this exists to be compared against one. One
     * decimal place: the difference between 4.1 and 13.7 is the whole point, and the digit after it
     * is not.
     */
    fun formatMegabytes(bytes: Long): String {
        val mb = (bytes / 1e5).roundToInt() / 10.0
        return "$mb MB"
    }
}
