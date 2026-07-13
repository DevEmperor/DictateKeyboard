/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.nlp.latin

import android.content.Context
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.subtypeManager
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.dictionary.DictionaryManager
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.florisboard.lib.android.readText
import org.florisboard.lib.kotlin.guardedByLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln

class LatinLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        // Default user ID used for all subtypes, unless otherwise specified.
        // See `ime/core/Subtype.kt` Line 210 and 211 for the default usage
        const val ProviderId = "org.florisboard.nlp.providers.latin"

        // Language used when the active subtype has no bundled dictionary of its own.
        private const val FALLBACK_LANG = "en"

        // A typo is only auto-corrected when its best fix is at least this frequent (on the dictionary's
        // 128..255 scale). Rarer fixes are still offered as tap suggestions but never swapped in
        // automatically, so uncommon-but-intentional words (names, jargon) aren't mangled.
        private const val AUTOCORRECT_MIN_FREQ = 170

        // Keyboard-proximity noisy-channel model (Tier 1). Distances are in key-width² units.
        private const val PROX_SIGMA2 = 1.0         // touch variance (~1 key-width std): near mis-taps cost little
        private const val NEUTRAL_SUB_SQDIST = 2.0  // fallback substitution distance² when key geometry is unknown
        private const val LENGTH_DIFF_PENALTY = -0.7 // flat log-penalty for insert/delete candidates
        private const val TRANSPOSE_PENALTY = -0.3   // adjacent-swap typo; cost independent of key distance

        // Bigram context model (Tier 2): weight on ln(bigram-count+1) added to a candidate that commonly
        // follows the previous word, so context ("of the" over "of teh") re-ranks the correction.
        private const val CONTEXT_WEIGHT = 0.3

        // Legacy ISO-639 codes that java.util.Locale still reports; map them to the modern code the
        // dictionary files use.
        private val LANG_ALIASES = mapOf("iw" to "he", "in" to "id", "ji" to "yi")

        fun normalizeLang(language: String): String {
            val l = language.lowercase()
            return LANG_ALIASES[l] ?: l
        }
    }

    private val prefs by dev.patrickgold.florisboard.app.FlorisPreferenceStore
    private val appContext by context.appContext()
    // Used to enumerate the user's configured keyboard languages for multilingual typing (issue #190).
    // Fully lazy so nothing is touched during construction (cf. issue #193).
    private val subtypeManager by lazy { appContext.subtypeManager().value }

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Word→frequency dictionaries cached per language (issue #127, glide typing phase 2). Each bundled
    // ime/dict/<lang>.json maps a word to a frequency in [128,255]; languages without a bundled file fall
    // back to English.
    private val wordDataByLang = guardedByLock { mutableMapOf<String, Map<String, Int>>() }
    private val wordDataSerializer = MapSerializer(String.serializer(), Int.serializer())

    // Per-language word list sorted by frequency (descending), so prefix completion can scan the most
    // frequent words first and stop early. Built lazily from the word data and cached.
    private val rankedWordsByLang = guardedByLock { mutableMapOf<String, List<String>>() }

    // Languages that ship a bundled ime/dict/<lang>.json (currently just English), listed once.
    private val bundledDictLangs: Set<String> by lazy {
        runCatching {
            appContext.assets.list("ime/dict")
                ?.mapNotNull { name -> name.takeIf { it.endsWith(".json") }?.removeSuffix(".json") }
                ?.toSet()
        }.getOrNull().orEmpty().ifEmpty { setOf(FALLBACK_LANG) }
    }

    // Resolved (subtype language → dictionary language) cache, so the glide classifier's per-word
    // frequency lookups don't hit the filesystem. Cleared on preload so a newly downloaded dictionary is
    // picked up on the next subtype activation.
    private val resolvedDictLang = ConcurrentHashMap<String, String>()

    /** Whether a dictionary (downloaded or bundled) exists for [lang]. */
    private fun hasDict(lang: String): Boolean =
        GlideDictionaryManager.isInstalled(appContext, lang) || lang in bundledDictLangs

    /** The dictionary language to use for [subtype] — its own if available, else English. */
    private fun dictLangFor(subtype: Subtype): String {
        val subLang = normalizeLang(subtype.primaryLocale.language)
        return resolvedDictLang.getOrPut(subLang) {
            if (subLang.isNotBlank() && hasDict(subLang)) subLang else FALLBACK_LANG
        }
    }

    /** Ensures the glide dictionary for [subtype]'s language downloads on first use (issue #127). */
    private fun maybeDownloadDict(subtype: Subtype) {
        val lang = normalizeLang(subtype.primaryLocale.language)
        if (lang.isBlank() || lang in bundledDictLangs) return
        GlideDictionaryManager.ensureDownloaded(appContext, lang)
    }

    /** Raw JSON for [lang]: a downloaded dictionary takes precedence over the bundled asset. */
    private fun readDict(lang: String): String {
        val downloaded = GlideDictionaryManager.dictFile(appContext, lang)
        return if (downloaded.isFile && downloaded.length() > 0) {
            downloaded.readText()
        } else {
            appContext.assets.readText("ime/dict/$lang.json")
        }
    }

    /** Loads (and caches) the word→frequency map for [subtype]'s resolved dictionary language. */
    private suspend fun wordDataFor(subtype: Subtype): Map<String, Int> = wordDataForLang(dictLangFor(subtype))

    /** Loads (and caches) the word→frequency map for a specific dictionary [lang]. */
    private suspend fun wordDataForLang(lang: String): Map<String, Int> =
        wordDataByLang.withLock { cache ->
            cache[lang] ?: run {
                val loaded = Json.decodeFromString(wordDataSerializer, readDict(lang))
                cache[lang] = loaded
                loaded
            }
        }

    // Bigram context model (Tier 2). Per-language "w1 w2" -> count maps loaded from the bundled
    // ime/dict/<lang>_bigrams.txt (currently English only); languages without a file get an empty map so
    // context simply doesn't apply. Used to re-rank corrections by the previous word.
    private val bigramsByLang = guardedByLock { mutableMapOf<String, Map<String, Long>>() }

    private suspend fun bigramsFor(subtype: Subtype): Map<String, Long> {
        val lang = dictLangFor(subtype)
        return bigramsByLang.withLock { cache ->
            cache[lang] ?: loadBigrams(lang).also { cache[lang] = it }
        }
    }

    private fun loadBigrams(lang: String): Map<String, Long> = runCatching {
        // A downloaded per-language bigram file (issue: per-language Tier 2) takes precedence over the
        // bundled English asset — mirrors readDict for the unigram dictionaries.
        val downloaded = GlideDictionaryManager.bigramFile(appContext, lang)
        val text = if (downloaded.isFile && downloaded.length() > 0) {
            downloaded.readText()
        } else {
            appContext.assets.readText("ime/dict/${lang}_bigrams.txt")
        }
        val map = HashMap<String, Long>(45_000)
        text.lineSequence().forEach { line ->
            val tab = line.indexOf('\t')
            if (tab > 0) {
                line.substring(tab + 1).toLongOrNull()?.let { map[line.substring(0, tab)] = it }
            }
        }
        map
    }.getOrDefault(emptyMap())

    /** The word right before the one being composed, lowercased — the context for the bigram model. */
    private fun previousWordOf(content: EditorContent): String? {
        val before = content.textBeforeSelection.removeSuffix(content.composingText).trimEnd()
        return before.takeLastWhile { it.isLetter() || it == '\'' }.lowercase().takeIf { it.isNotEmpty() }
    }

    /** Context-score function for [correctionsFor]: boosts candidates that commonly follow [prevWord]. */
    private fun bigramContextScore(prevWord: String?, bigrams: Map<String, Long>): (String) -> Double {
        if (prevWord == null || bigrams.isEmpty()) return { 0.0 }
        return { cand -> CONTEXT_WEIGHT * ln(((bigrams["$prevWord $cand"] ?: 0L) + 1L).toDouble()) }
    }

    /** Frequency-sorted (descending) word list for [subtype]'s dictionary language, cached per language. */
    private suspend fun rankedWordsFor(subtype: Subtype): List<String> {
        val lang = dictLangFor(subtype)
        val data = wordDataFor(subtype)
        return rankedWordsByLang.withLock { cache ->
            cache[lang] ?: run {
                val ranked = data.entries.sortedByDescending { it.value }.map { it.key }
                cache[lang] = ranked
                ranked
            }
        }
    }

    // --- Spell check / autocorrect core (issue #127 follow-up) --------------------------------------

    /**
     * Case-folded view of a language's dictionary for spell checking / correction: [freq] maps a lowercase
     * word to its frequency, [canonical] to its correctly-cased form, and [alphabet] holds every letter the
     * language uses (for generating edit candidates).
     */
    private class LowerIndex(
        val freq: Map<String, Int>,
        val canonical: Map<String, String>,
        val alphabet: Set<Char>,
    )

    private val lowerIndexByLang = guardedByLock { mutableMapOf<String, LowerIndex>() }

    private fun startDictionaryWatcher() {
        // When a dictionary finishes downloading, drop the resolved-language cache so the active subtype
        // starts using it immediately (issue #127). Started from create() rather than init: launching a
        // coroutine that touches this provider's fields during construction let `this` escape before the
        // object was safely published, so the IO thread could observe not-yet-initialized (null) caches
        // and crash on the first StateFlow emission (issue #193).
        ioScope.launch {
            GlideDictionaryManager.installedVersion.collect {
                resolvedDictLang.clear()
                rankedWordsByLang.withLock { it.clear() }
                lowerIndexByLang.withLock { it.clear() }
                bigramsByLang.withLock { it.clear() }
            }
        }
    }

    private suspend fun lowerIndexFor(subtype: Subtype): LowerIndex = lowerIndexForLang(dictLangFor(subtype))

    private suspend fun lowerIndexForLang(lang: String): LowerIndex {
        val data = wordDataForLang(lang)
        return lowerIndexByLang.withLock { cache ->
            cache[lang] ?: run {
                val freq = HashMap<String, Int>(data.size)
                val canonical = HashMap<String, String>(data.size)
                val alphabet = HashSet<Char>()
                for ((word, f) in data) {
                    val lower = word.lowercase()
                    if ((freq[lower] ?: -1) < f) {
                        freq[lower] = f
                        canonical[lower] = word
                    }
                    for (ch in lower) if (ch.isLetter()) alphabet.add(ch)
                }
                LowerIndex(freq, canonical, alphabet).also { cache[lang] = it }
            }
        }
    }

    /**
     * The dictionary languages a typed word is accepted from: just the active subtype's, or — when
     * multilingual typing is on (issue #190) — every configured keyboard subtype's, so a bilingual's
     * second-language words aren't flagged as typos or autocorrected into the primary language.
     */
    private fun acceptedDictLangs(subtype: Subtype): List<String> {
        val active = dictLangFor(subtype)
        if (!prefs.suggestion.multilingualTyping.get()) return listOf(active)
        val langs = LinkedHashSet<String>().apply { add(active) }
        runCatching { subtypeManager.subtypes.forEach { langs.add(dictLangFor(it)) } }
        return langs.toList()
    }

    /** True if [word] is a known dictionary word in any accepted language, or in the user dictionary. */
    private suspend fun isKnownWord(word: String, subtype: Subtype): Boolean {
        val lower = word.lowercase()
        for (lang in acceptedDictLangs(subtype)) {
            if (lowerIndexForLang(lang).freq.containsKey(lower)) return true
        }
        return isInUserDictionary(word, subtype)
    }

    /** All strings one edit away from [word] (delete / transpose / replace / insert) — Norvig's edits1. */
    private fun edits1(word: String, alphabet: Set<Char>): Set<String> {
        val result = HashSet<String>()
        for (i in 0..word.length) {
            val a = word.substring(0, i)
            val b = word.substring(i)
            if (b.isNotEmpty()) {
                result.add(a + b.substring(1))                                    // delete
                if (b.length > 1) result.add(a + b[1] + b[0] + b.substring(2))    // transpose
                for (c in alphabet) result.add(a + c + b.substring(1))            // replace
            }
            for (c in alphabet) result.add(a + c + b)                             // insert
        }
        return result
    }

    /** Dictionary words closest to (a misspelling of) [word], ranked by frequency. */
    private fun correctionsFor(
        word: String,
        index: LowerIndex,
        maxCount: Int,
        allowDistance2: Boolean,
        contextScore: (cand: String) -> Double = { 0.0 },
    ): List<String> {
        val lower = word.lowercase()
        val e1 = edits1(lower, index.alphabet)
        val known = e1.filterTo(LinkedHashSet()) { index.freq.containsKey(it) }
        if (known.isEmpty() && allowDistance2) {
            for (e in e1) for (ee in edits1(e, index.alphabet)) {
                if (index.freq.containsKey(ee)) known.add(ee)
            }
        }
        // Noisy-channel ranking (Tier 1): combine the unigram prior with a keyboard-proximity likelihood,
        // so a fat-finger substitution of an adjacent key beats a merely more frequent but far-away word,
        // instead of ranking purely by frequency.
        return known.sortedByDescending { channelScore(lower, it, index.freq[it] ?: 0, contextScore) }
            .take(maxCount)
            .map { index.canonical[it] ?: it }
    }

    /**
     * Noisy-channel score for ranking a correction candidate: log unigram prior + log likelihood that
     * [typed] is a mis-tap of [cand] given the keyboard geometry (Tier 1) + a context bonus for how often
     * [cand] follows the previous word (Tier 2 bigram). Higher is better.
     */
    private fun channelScore(typed: String, cand: String, freq: Int, contextScore: (String) -> Double): Double =
        ln((freq + 1).toDouble()) + spatialLogLikelihood(typed, cand) + contextScore(cand)

    /**
     * log P(typed | cand): near-key substitutions cost little, far ones a lot (Gaussian over key distance);
     * an adjacent transposition (finger-order slip) is a flat cost independent of distance; insert/delete
     * candidates get a flat penalty so the frequency prior orders them. Neutral when key geometry is
     * unavailable (layout not captured yet), which reduces this to frequency-only ranking.
     */
    private fun spatialLogLikelihood(typed: String, cand: String): Double {
        if (typed.length != cand.length) return LENGTH_DIFF_PENALTY
        if (isAdjacentTransposition(typed, cand)) return TRANSPOSE_PENALTY
        var cost = 0.0
        for (i in typed.indices) {
            if (typed[i] == cand[i]) continue
            val d2 = KeyProximityInfo.normSqDistance(typed[i], cand[i])?.toDouble() ?: NEUTRAL_SUB_SQDIST
            cost += d2 / (2.0 * PROX_SIGMA2)
        }
        return -cost
    }

    /** True if [b] is [a] with exactly one pair of adjacent characters swapped (a transposition). */
    private fun isAdjacentTransposition(a: String, b: String): Boolean {
        if (a.length != b.length || a.length < 2) return false
        var i = 0
        while (i < a.length && a[i] == b[i]) i++
        if (i >= a.length - 1) return false
        if (a[i] != b[i + 1] || a[i + 1] != b[i]) return false
        for (j in i + 2 until a.length) if (a[j] != b[j]) return false
        return true
    }

    private fun isInUserDictionary(word: String, subtype: Subtype): Boolean = runCatching {
        val dm = DictionaryManager.default()
        dm.loadUserDictionariesIfNecessary()
        dm.queryUserDictionary(word, subtype.primaryLocale)
            .any { it.text.toString().equals(word, ignoreCase = true) }
    }.getOrDefault(false)

    override val providerId = ProviderId

    override suspend fun create() {
        // Here we initialize our provider, set up all things which are not language dependent.
        // Start the dictionary-download watcher only now — after the provider is fully constructed and
        // safely published — so the collector never sees uninitialized caches (issue #193).
        startDictionaryWatcher()
    }

    override suspend fun preload(subtype: Subtype) = withContext(Dispatchers.IO) {
        // Here we have the chance to preload dictionaries and prepare a neural network for a specific language.
        // Is kept in sync with the active keyboard subtype of the user, however a new preload does not necessary mean
        // the previous language is not needed anymore (e.g. if the user constantly switches between two subtypes)

        // To read a file from the APK assets the following methods can be used:
        // appContext.assets.open()
        // appContext.assets.reader()
        // appContext.assets.bufferedReader()
        // appContext.assets.readText()
        // To copy an APK file/dir to the file system cache (appContext.cacheDir), the following methods are available:
        // appContext.assets.copy()
        // appContext.assets.copyRecursively()

        // The subtype we get here contains a lot of data, however we are only interested in subtype.primaryLocale and
        // subtype.secondaryLocales.

        // Re-resolve languages so a dictionary downloaded since the last activation is picked up, then
        // warm the cache for this subtype's dictionary language (used by glide typing / word lookups).
        resolvedDictLang.clear()
        maybeDownloadDict(subtype)
        wordDataFor(subtype)
        Unit
    }

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult {
        val trimmed = word.trim()
        // Don't flag single characters, numbers or words containing digits.
        if (trimmed.length <= 1 || trimmed.any { it.isDigit() }) return SpellingResult.validWord()
        val index = lowerIndexFor(subtype)
        // Known in the active language OR any other configured keyboard language (multilingual, #190).
        if (isKnownWord(trimmed, subtype)) {
            return SpellingResult.validWord()
        }
        // Unknown word → typo, offering the closest dictionary words as corrections (may be empty).
        // Re-rank by the previous word (Tier 2 bigram context) when available.
        val prevWord = precedingWords.lastOrNull()
            ?.takeLastWhile { it.isLetter() || it == '\'' }?.lowercase()?.takeIf { it.isNotEmpty() }
        val bigrams = if (prevWord != null) bigramsFor(subtype) else emptyMap()
        val suggestions = correctionsFor(
            trimmed, index, maxSuggestionCount, allowDistance2 = true,
            bigramContextScore(prevWord, bigrams),
        )
        return SpellingResult.typo(suggestions.toTypedArray())
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        // Word completion: prefix-match the word being composed against the dictionary, most frequent
        // first (issue #127 follow-up). No composing word → nothing to complete (next-word prediction
        // would need n-grams, which the unigram dictionaries don't carry).
        val word = content.composingText
        if (word.isEmpty()) return emptyList()

        val wantCapitalized = word.first().isUpperCase()
        fun cased(dictWord: String): String =
            if (wantCapitalized && dictWord.firstOrNull()?.isLowerCase() == true) {
                dictWord.replaceFirstChar { it.uppercaseChar() }
            } else {
                dictWord
            }

        // Dedup by lowercase key, preserving order: the user's personal dictionary first, then the main
        // dictionary ranked by frequency.
        val out = LinkedHashMap<String, SuggestionCandidate>()

        runCatching {
            val dm = DictionaryManager.default()
            dm.loadUserDictionariesIfNecessary()
            dm.queryUserDictionary(word, subtype.primaryLocale)
        }.getOrNull()?.forEach { candidate ->
            val text = candidate.text.toString()
            if (text.startsWith(word, ignoreCase = true)) {
                out.putIfAbsent(text.lowercase(), candidate)
            }
        }

        val data = wordDataFor(subtype)
        for (dictWord in rankedWordsFor(subtype)) {
            if (out.size >= maxCandidateCount) break
            if (!dictWord.startsWith(word, ignoreCase = true)) continue
            val text = cased(dictWord)
            out.putIfAbsent(
                text.lowercase(),
                WordSuggestionCandidate(
                    text = text,
                    confidence = (data[dictWord] ?: 0) / 255.0,
                    sourceProvider = this,
                ),
            )
        }

        // Autocorrect: when the composed word isn't a known word and nothing completes it (so it looks
        // like a finished typo rather than a word in progress), offer the closest dictionary words and mark
        // the top one for auto-commit — the editor swaps it in on the next space/punctuation. Kept
        // conservative (edit distance 1, length >= 3) to avoid mangling intentional input.
        val index = lowerIndexFor(subtype)
        // Don't autocorrect a word that's valid in any of the user's keyboard languages (multilingual, #190).
        val isKnown = isKnownWord(word, subtype)
        if (prefs.suggestion.autoCorrect.get() && out.isEmpty() && !isKnown && word.length >= 3) {
            val prevWord = previousWordOf(content)
            val bigrams = if (prevWord != null) bigramsFor(subtype) else emptyMap()
            val corrections = correctionsFor(
                word, index, maxCandidateCount, allowDistance2 = false,
                bigramContextScore(prevWord, bigrams),
            )
            // Keep the user's exact typed word in the strip (left-most) so they can tap it to bypass the
            // autocorrection and keep their spelling — never auto-committed itself (issue #150).
            if (corrections.isNotEmpty()) {
                out.putIfAbsent(
                    word.lowercase(),
                    WordSuggestionCandidate(
                        text = word,
                        confidence = 1.0,
                        isEligibleForAutoCommit = false,
                        sourceProvider = this,
                    ),
                )
            }
            corrections.forEachIndexed { i, correction ->
                val text = cased(correction)
                val freq = index.freq[correction.lowercase()] ?: 0
                out.putIfAbsent(
                    text.lowercase(),
                    WordSuggestionCandidate(
                        text = text,
                        confidence = freq / 255.0,
                        isEligibleForAutoCommit = i == 0 && freq >= AUTOCORRECT_MIN_FREQ,
                        sourceProvider = this,
                    ),
                )
            }
        }

        return out.values.take(maxCandidateCount)
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        // We can use flogDebug, flogInfo, flogWarning and flogError for debug logging, which is a wrapper for Logcat
        flogDebug { candidate.toString() }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { candidate.toString() }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        flogDebug { candidate.toString() }
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return wordDataFor(subtype).keys.toList()
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return (wordDataFor(subtype)[word] ?: 0) / 255.0
    }

    override suspend fun destroy() {
        // Here we have the chance to de-allocate memory and finish our work. However this might never be called if
        // the app process is killed (which will most likely always be the case).
    }
}
