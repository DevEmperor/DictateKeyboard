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

        // Spelling-fix suggestions (issue #212 / distance-2 fallback): how many edit-distance corrections
        // to surface, how many strip slots to reserve for them so prefix completions of a typo don't crowd
        // them out, and the max word length for the (more expensive) distance-2 fallback.
        private const val CORRECTION_MAX = 3
        private const val CORRECTION_RESERVE = 3
        private const val MAX_DISTANCE2_LEN = 12

        // Keyboard-proximity noisy-channel model (Tier 1). Distances are in key-width² units.
        private const val PROX_SIGMA2 = 1.0         // touch variance (~1 key-width std): near mis-taps cost little
        private const val NEUTRAL_SUB_SQDIST = 2.0  // fallback substitution distance² when key geometry is unknown
        private const val LENGTH_DIFF_PENALTY = -0.7 // flat log-penalty for insert/delete candidates
        private const val TRANSPOSE_PENALTY = -0.3   // adjacent-swap typo; cost independent of key distance

        // Bigram context model (Tier 2): weight on ln(bigram-count+1) added to a candidate that commonly
        // follows the previous word, so context ("of the" over "of teh") re-ranks the correction.
        private const val CONTEXT_WEIGHT = 0.3

        // --- Touch-decoded corrections (issue #242) -------------------------------------------------
        // Used only on the path where real tap coordinates are available; the legacy ranking above keeps its
        // own constants so behaviour without a trace is bit-for-bit unchanged.
        //
        // The dictionary stores frequencies on a 128..255 scale that is already *linear in log frequency*
        // (tools/glide-dict/generate.py), so the prior is linear here rather than another ln() — taking the
        // log twice would squash the whole vocabulary into 0.69 nats and make the language model irrelevant.
        // LM_SPAN is that scale expressed in nats.
        private const val LM_SPAN = 4.0
        // Touch variance in key-width², i.e. how much an off-centre tap is allowed to cost. Tuned together
        // with LM_SPAN; accuracy varies by under 1 pp when either is doubled or halved.
        private const val TOUCH_SIGMA2 = 0.2
        // Flat cost for a candidate of a different length (a dropped or doubled letter), which the beam
        // cannot produce and which therefore comes from the edit-distance generator.
        private const val TOUCH_LENGTH_PENALTY = -5.0
        // How many words the beam returns before scoring.
        private const val BEAM_CANDIDATES = 12
        // Highest excess tap distance (key-width², summed over the word) still allowed to *silently* replace
        // what was typed. Suggestions are always offered; this only gates the automatic swap.
        //
        // Without it, any unknown word with a frequent neighbour gets rewritten — measured on German, 30% of
        // correctly typed out-of-dictionary words (names like "Sarahs"→"daraus", "Pete"→"Peter") would be
        // mangled, far worse than the 19% the old gate allowed. At 0.8 that drops to ~0% while still
        // auto-fixing 95% of genuine mis-taps, because a real slip lands near a key boundary (cheap) whereas
        // a correctly typed name needs a full key jump (expensive).
        private const val AUTO_COMMIT_MAX_TOUCH_COST = 0.8f

        // Candidates are de-duplicated by their case-folded text. The typed spelling kept alongside a noun
        // capitalisation folds to the very same key as the capitalised form, so it is stored under this
        // prefix, a NUL character that no dictionary word can contain.
        private const val TYPED_WORD_KEY = "\u0000"

        // German umlaut/ß restoration (issue #219): bound the variant generation so a long word with many
        // a/o/u doesn't explode combinatorially (2^sites). Words needing more than this are left alone.
        private const val MAX_UMLAUT_SITES = 6
        private const val MAX_GERMAN_VARIANTS = 128

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

    /**
     * Ensures the glide dictionary for [subtype]'s language downloads on first use (issue #127), and its
     * bigram file with it.
     *
     * A bundled language must not be skipped outright: only English ships bigrams as an asset, so German —
     * whose word list is bundled — used to return here and never fetch `de_bigrams.txt` at all. The context
     * model was silently missing for it, and next-word prediction (#245) had nothing to work with.
     */
    private fun maybeDownloadDict(subtype: Subtype) {
        val lang = normalizeLang(subtype.primaryLocale.language)
        if (lang.isBlank()) return
        GlideDictionaryManager.ensureDownloaded(appContext, lang, dictBundled = lang in bundledDictLangs)
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

    // Lexicographically sorted word list per language, used by the beam decoder to prune partial paths that
    // are no longer a prefix of any real word (issue #242). Shares its strings with the LowerIndex, so this
    // costs one array of references per language and no duplicated character data.
    private val prefixIndexByLang = guardedByLock { mutableMapOf<String, TouchBeamDecoder.PrefixIndex>() }

    private suspend fun prefixIndexFor(subtype: Subtype): TouchBeamDecoder.PrefixIndex {
        val lang = dictLangFor(subtype)
        val index = lowerIndexFor(subtype)
        return prefixIndexByLang.withLock { cache ->
            cache[lang] ?: TouchBeamDecoder.PrefixIndex(index.freq.keys.toTypedArray().apply { sort() })
                .also { cache[lang] = it }
        }
    }

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
                prefixIndexByLang.withLock { it.clear() }
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
                // Include the apostrophe so a missing-apostrophe typo of an unknown word can be corrected;
                // dictionary words like "what's"/"don't" carry it but isLetter() drops it above. (The
                // common case — the apostrophe-less form is itself a known word, e.g. "whats" — is handled
                // by the contraction-restoration block in suggest(), issue #212.)
                alphabet.add('\'')
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

    /**
     * True if [lower] is an ordinary lowercase word in one of the user's *other* keyboard languages, so the
     * active language's noun capitalisation must stand aside (issue #190): an English "hand" typed with the
     * German subtype active should not become "Hand".
     */
    private suspend fun isLowercaseWordInAnotherLanguage(lower: String, subtype: Subtype): Boolean {
        val active = dictLangFor(subtype)
        for (lang in acceptedDictLangs(subtype)) {
            if (lang == active) continue
            if (lowerIndexForLang(lang).canonical[lower]?.first()?.isLowerCase() == true) return true
        }
        return false
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

    // --- Next-word prediction (issue #245) ----------------------------------------------------------

    /**
     * Likely continuations of the word before the cursor, for the moment when nothing is being composed —
     * the one case where the strip used to be empty even though the bigram tables were already in memory.
     *
     * Deliberately returns nothing when there is no previous word to condition on. The Smartbar swaps
     * between candidates and the quick actions purely on whether candidates exist, so predicting on an empty
     * field would permanently hide the clipboard/GIF/history row; requiring a previous word keeps that row
     * as it is today whenever the keyboard is opened fresh.
     *
     * Never eligible for auto-commit: these are offers about a word the user has not started typing, so
     * nothing may be inserted without a tap.
     */
    private suspend fun nextWordPredictions(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
    ): List<SuggestionCandidate> {
        if (!prefs.suggestion.nextWordPrediction.get()) return emptyList()
        // Only directly after a completed word: a trailing space means the previous word is finished, while
        // a cursor sitting mid-word or right after punctuation gives nothing meaningful to continue from.
        if (!content.textBeforeSelection.endsWith(" ")) return emptyList()
        val prevWord = previousWordOf(content) ?: return emptyList()
        val bigrams = bigramsFor(subtype)
        if (bigrams.isEmpty()) return emptyList()

        val index = lowerIndexFor(subtype)
        val prefix = "$prevWord "
        // No unigram fallback on purpose: without a matching bigram the strip would fill with generic filler
        // ("the", "and", "of") that carries no information about what the user is writing.
        return bigrams.asSequence()
            .filter { it.key.startsWith(prefix) }
            .sortedByDescending { it.value }
            .take(maxCandidateCount)
            .mapNotNull { entry ->
                val candidate = entry.key.substring(prefix.length)
                if (candidate.isBlank()) return@mapNotNull null
                val text = index.canonical[candidate] ?: candidate
                WordSuggestionCandidate(
                    text = text,
                    confidence = (index.freq[candidate] ?: 0) / 255.0,
                    isEligibleForAutoCommit = false,
                    sourceProvider = this,
                )
            }
            .toList()
    }

    // --- Touch-decoded corrections (issue #242) -----------------------------------------------------

    /**
     * Corrections decoded from tap positions, plus how well the taps actually support the best one.
     *
     * [topCost] is the winning candidate's excess tap distance, or null when it came from edit distance and
     * there is therefore no positional evidence either way (a dropped or doubled letter).
     */
    private class TouchCorrections(val words: List<String>, val topCost: Float?)

    /**
     * Corrections decoded from where the user's fingers actually landed, or null when that is not possible
     * (no tap evidence for this exact word, no captured key geometry, or the beam found nothing) — in which
     * case the caller falls back to the classic edit-distance path unchanged.
     *
     * The beam contributes same-length candidates with near-perfect recall; a dropped or doubled letter
     * changes the length and cannot come out of it, so those still come from [edits1] and are scored with a
     * flat penalty. Both are then ranked on one scale: linear log-frequency prior, minus the excess tap
     * distance, plus the bigram context bonus.
     */
    private suspend fun touchCorrectionsFor(
        word: String,
        subtype: Subtype,
        index: LowerIndex,
        maxCount: Int,
        contextScore: (cand: String) -> Double,
    ): TouchCorrections? {
        val points = TouchTrace.pointsFor(word) ?: return null
        val layout = KeyProximityInfo.snapshot() ?: return null
        val beam = TouchBeamDecoder.decode(
            points = points,
            typed = word,
            index = prefixIndexFor(subtype),
            layout = layout,
            maxResults = BEAM_CANDIDATES,
        )
        if (beam.isEmpty()) return null

        val scored = HashMap<String, Double>(beam.size * 2)
        // Tap cost per beam candidate, kept so the caller can tell a near-boundary slip (trustworthy enough
        // to swap in silently) from a candidate a whole key away (offer it, but don't act on it).
        val costs = HashMap<String, Float>(beam.size)
        for (candidate in beam) {
            val freq = index.freq[candidate.word] ?: continue
            scored[candidate.word] =
                lmPrior(freq) - candidate.cost / (2.0 * TOUCH_SIGMA2) + contextScore(candidate.word)
            costs[candidate.word] = candidate.cost
        }
        // Length-changing slips (a letter dropped or typed twice) are invisible to the beam.
        val lower = word.lowercase()
        for (edit in edits1(lower, index.alphabet)) {
            if (edit.length == lower.length) continue
            val freq = index.freq[edit] ?: continue
            scored.putIfAbsent(edit, lmPrior(freq) + TOUCH_LENGTH_PENALTY + contextScore(edit))
        }
        if (scored.isEmpty()) return null
        val ranked = scored.entries.sortedByDescending { it.value }.take(maxCount)
        return TouchCorrections(
            words = ranked.map { index.canonical[it.key] ?: it.key },
            topCost = costs[ranked.first().key],
        )
    }

    /**
     * Log-probability prior for a dictionary frequency. The stored 128..255 values are already linear in log
     * frequency, so this only rescales them into nats — applying ln() again (as the legacy [channelScore]
     * does) would compress the entire vocabulary into 0.69 nats.
     */
    private fun lmPrior(freq: Int): Double = (freq - 128).coerceAtLeast(0) / 127.0 * LM_SPAN

    // --- German umlaut / ß restoration (issue #219) -------------------------------------------------

    /** True when [subtype] types German, so the umlaut/ß restoration below applies. */
    private fun isGermanSubtype(subtype: Subtype): Boolean =
        subtype.primaryLocale.language.equals("de", ignoreCase = true)

    /**
     * ASCII / umlaut-less spellings of [word] a German typist might have meant: single vowels a/o/u →
     * ä/ö/ü, the spelled-out digraphs ae/oe/ue → ä/ö/ü, and (when [allowSharpS]) ss → ß. Bounded so a long
     * word doesn't explode combinatorially. Only the caller's dictionary decides which of these are real.
     */
    private fun germanSpellingVariants(word: String, allowSharpS: Boolean): List<String> {
        val out = LinkedHashSet<String>()
        // First read ae/oe/ue as the umlaut the user spelled out (all occurrences at once), then run the
        // single-vowel + ß expansion on both that collapsed form and the raw one.
        val digraph = word
            .replace("ae", "ä").replace("Ae", "Ä").replace("AE", "Ä")
            .replace("oe", "ö").replace("Oe", "Ö").replace("OE", "Ö")
            .replace("ue", "ü").replace("Ue", "Ü").replace("UE", "Ü")
        // The collapsed digraph form itself is a candidate (ueber → über); the expansion below only adds
        // further single-vowel / ß substitutions on top of it.
        if (digraph != word) out.add(digraph)
        for (base in linkedSetOf(word, digraph)) expandGermanVariants(base, allowSharpS, out)
        out.remove(word)
        return out.toList()
    }

    /** Adds every umlaut / ß substitution combination of [base] (bounded) to [out]. */
    private fun expandGermanVariants(base: String, allowSharpS: Boolean, out: MutableSet<String>) {
        // Each site: (index, replacement, consumed length). ss consumes two chars, an umlaut vowel one.
        val sites = ArrayList<Triple<Int, String, Int>>()
        var i = 0
        while (i < base.length) {
            if (allowSharpS && i + 1 < base.length && base[i] == 's' && base[i + 1] == 's') {
                sites.add(Triple(i, "ß", 2)); i += 2; continue
            }
            when (base[i]) {
                'a' -> sites.add(Triple(i, "ä", 1))
                'o' -> sites.add(Triple(i, "ö", 1))
                'u' -> sites.add(Triple(i, "ü", 1))
                'A' -> sites.add(Triple(i, "Ä", 1))
                'O' -> sites.add(Triple(i, "Ö", 1))
                'U' -> sites.add(Triple(i, "Ü", 1))
            }
            i++
        }
        if (sites.isEmpty() || sites.size > MAX_UMLAUT_SITES) return
        val n = sites.size
        for (mask in 1 until (1 shl n)) {
            if (out.size >= MAX_GERMAN_VARIANTS) return
            val sb = StringBuilder(base)
            // Apply the highest-index sites first so earlier indices stay valid when ss (2) becomes ß (1).
            for (b in n - 1 downTo 0) {
                if ((mask shr b) and 1 == 1) {
                    val (idx, repl, len) = sites[b]
                    sb.replace(idx, idx + len, repl)
                }
            }
            out.add(sb.toString())
        }
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
        // first (issue #127 follow-up).
        val word = content.composingText
        // Nothing being composed: offer likely continuations of the previous word instead (issue #245).
        if (word.isEmpty()) return nextWordPredictions(subtype, content, maxCandidateCount)

        val wantCapitalized = word.first().isUpperCase()
        fun cased(dictWord: String): String =
            if (wantCapitalized && dictWord.firstOrNull()?.isLowerCase() == true) {
                dictWord.replaceFirstChar { it.uppercaseChar() }
            } else {
                dictWord
            }

        // Dedup by lowercase key, preserving order: German umlaut/ß restoration first (so it leads the
        // strip), then the user's personal dictionary, then the main dictionary ranked by frequency.
        val out = LinkedHashMap<String, SuggestionCandidate>()
        val index = lowerIndexFor(subtype)

        // German umlaut/ß restoration (issue #219) runs FIRST, so the correct spelling leads the strip and a
        // non-word is auto-committed even when the ASCII form prefixes real words (fur→für). Because this
        // fills [out], the generic edit-distance autocorrect below stands aside for these words, so a plain
        // substitution never wins over the umlaut form (Madchen→Mädchen, not Machen). Dictionary-driven, so
        // only real words are produced; a validly-typed word is never swapped, only offered (schon→schön).
        // ß-restoration is off for Swiss German (de-CH), which has no ß.
        if (prefs.suggestion.autoCorrect.get() && isGermanSubtype(subtype) && word.length >= 3) {
            val allowSharpS = !subtype.primaryLocale.country.equals("CH", ignoreCase = true)
            val variants = germanSpellingVariants(word, allowSharpS).mapNotNull { v ->
                index.freq[v.lowercase()]?.let { f -> Triple(v, f, index.canonical[v.lowercase()] ?: v) }
            }
            if (variants.isNotEmpty()) {
                val prevWord = previousWordOf(content)
                val bigrams = if (prevWord != null) bigramsFor(subtype) else emptyMap()
                val ctx = bigramContextScore(prevWord, bigrams)
                val ranked = variants.sortedByDescending { (v, f, _) -> ln((f + 1).toDouble()) + ctx(v.lowercase()) }
                val typedIsWord = index.freq.containsKey(word.lowercase())
                // Keep the typed word tappable, left-most, to bypass the restoration (issue #150).
                out[word.lowercase()] = WordSuggestionCandidate(
                    text = word, confidence = 1.0, isEligibleForAutoCommit = false, sourceProvider = this,
                )
                ranked.forEachIndexed { i, (_, f, canonical) ->
                    val text = cased(canonical)
                    out.putIfAbsent(
                        text.lowercase(),
                        WordSuggestionCandidate(
                            text = text,
                            confidence = f / 255.0,
                            // Auto-swap only the top variant of a NON-word; a validly typed word stays the
                            // user's choice and the variant is merely offered.
                            isEligibleForAutoCommit = i == 0 && !typedIsWord && f >= AUTOCORRECT_MIN_FREQ,
                            sourceProvider = this,
                        ),
                    )
                }
            }
        }

        // Apostrophe/contraction restoration (issue #212): "whats"→"what's", "cant"→"can't", "dont"→"don't",
        // "im"→"I'm". The apostrophe-less form is often itself a dictionary word (so the generic correction
        // path below skips it), yet the apostrophe form is usually what was meant and more common. Offered at
        // the front of the strip as a tap suggestion — not auto-committed, so a genuine "ill"/"well" is never
        // silently turned into "i'll"/"we'll".
        if (prefs.suggestion.autoCorrect.get() && word.length >= 3 && !word.contains('\'')) {
            val typedFreq = index.freq[word.lowercase()] ?: 0
            (1 until word.length)
                .map { word.substring(0, it) + "'" + word.substring(it) }
                .mapNotNull { v -> index.freq[v.lowercase()]?.let { f -> f to (index.canonical[v.lowercase()] ?: v) } }
                .filter { it.first > typedFreq }
                .sortedByDescending { it.first }
                .forEach { (freq, canonical) ->
                    // English "I" contractions are stored lowercase in the dictionary; show them capitalised.
                    val display = if (canonical.startsWith("i'")) "I" + canonical.substring(1) else cased(canonical)
                    out.putIfAbsent(
                        display.lowercase(),
                        WordSuggestionCandidate(
                            text = display, confidence = freq / 255.0,
                            isEligibleForAutoCommit = false, sourceProvider = this,
                        ),
                    )
                }
        }

        val autoCorrectOn = prefs.suggestion.autoCorrect.get()

        // Noun capitalisation (issue #242 follow-up). German capitalises every noun, but typing one
        // lowercase produced no correction at all: the case-folded index reports "haus" as a known word, so
        // the whole correction path below is skipped and "Haus" only ever appeared as an ordinary prefix
        // completion, which is never auto-committed. Roughly 31 % of typed German words are affected.
        //
        // The dictionary itself says which words these are: tools/glide-dict/generate.py stores a word
        // capitalised exactly when the case oracle rejects its lowercase spelling, i.e. for genuine nouns.
        // Words that are valid lowercase ("essen", "laufen", "recht", "sie") are stored lowercase and are
        // therefore left alone here, which is what keeps this from mangling ordinary text.
        //
        // Deliberately hangs off the existing "Auto-capitalization" preference rather than adding its own:
        // anyone who types in all-lowercase on purpose has already turned that off, since it would otherwise
        // capitalise every sentence start too.
        if (autoCorrectOn && prefs.correction.autoCapitalization.get() &&
            word.length >= 2 && word.none { it.isUpperCase() }
        ) {
            val lower = word.lowercase()
            val canonical = index.canonical[lower]
            if (canonical != null && canonical.first().isUpperCase() && canonical != word &&
                !isLowercaseWordInAnotherLanguage(lower, subtype)
            ) {
                // Keep the typed spelling tappable and left-most so the capitalisation can be bypassed
                // (issue #150). It shares its case-folded key with the capitalised form, so it goes in under
                // a key that no dictionary word can produce.
                out[TYPED_WORD_KEY + lower] = WordSuggestionCandidate(
                    text = word, confidence = 1.0, isEligibleForAutoCommit = false, sourceProvider = this,
                )
                out[lower] = WordSuggestionCandidate(
                    text = canonical,
                    confidence = (index.freq[lower] ?: 0) / 255.0,
                    isEligibleForAutoCommit = true,
                    sourceProvider = this,
                )
            }
        }

        // Whether the composed word is valid in any of the user's keyboard languages (multilingual, #190);
        // computed up front because it also decides whether to reserve strip slots for spelling fixes.
        val isKnown = isKnownWord(word, subtype)
        // Reserve a few slots for edit-distance corrections so a typo's fix isn't crowded out by prefix
        // completions of that typo (issue #212). Only when we'd actually correct (unknown word, length >= 3).
        val completionCap = if (autoCorrectOn && !isKnown && word.length >= 3) {
            (maxCandidateCount - CORRECTION_RESERVE).coerceAtLeast(1)
        } else {
            maxCandidateCount
        }

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
            if (out.size >= completionCap) break
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

        // Spelling fixes for an unknown word — now surfaced even when there are prefix completions of the
        // typo (issue #212), so a missing apostrophe/hyphen or other slip is offered (whats → what's)
        // instead of only word extensions. Auto-commit stays conservative: only the top distance-1 fix and
        // only when nothing else already filled the strip, so intentional input and words-in-progress aren't
        // swapped. Distance 2 is a suggestions-only fallback when distance 1 finds nothing (too uncertain to
        // swap in silently). #190: never correct a word valid in any configured language.
        if (autoCorrectOn && !isKnown && word.length >= 3) {
            val hadCandidatesBefore = out.isNotEmpty() // German restoration and/or prefix completions
            val prevWord = previousWordOf(content)
            val bigrams = if (prevWord != null) bigramsFor(subtype) else emptyMap()
            val ctx = bigramContextScore(prevWord, bigrams)
            // Preferred: decode from the actual tap positions (issue #242). Falls back to edit distance
            // whenever no usable tap evidence exists — hardware keyboard, glide, pasted or dictated text,
            // or a cursor jump that desynced the trace.
            val touchCorrections = touchCorrectionsFor(word, subtype, index, CORRECTION_MAX, ctx)
            var corrections = touchCorrections?.words
                ?: correctionsFor(word, index, CORRECTION_MAX, allowDistance2 = false, ctx)
            val distance1Empty = corrections.isEmpty()
            if (touchCorrections == null && distance1Empty && word.length <= MAX_DISTANCE2_LEN) {
                corrections = correctionsFor(word, index, CORRECTION_MAX, allowDistance2 = true, ctx)
            }
            // What may be swapped in *silently* (the strip always shows everything either way).
            val topTouchCost = touchCorrections?.topCost
            val allowAutoCommit = when {
                // Decoded from the taps: act only when the fingers really were near that key. This replaces
                // the `hadCandidatesBefore` gate, which suppressed 2.7 % of otherwise correct fixes merely
                // because the typo prefixed some dictionary word — while a bare "a correction exists" rule
                // would rewrite 30 % of correctly typed names.
                topTouchCost != null -> topTouchCost <= AUTO_COMMIT_MAX_TOUCH_COST
                // A dropped or doubled letter: the beam cannot see it and the taps say nothing either way,
                // so keep the conservative classic rule.
                touchCorrections != null -> !hadCandidatesBefore
                else -> !hadCandidatesBefore && !distance1Empty
            }
            // Keep the exact typed word tappable, left-most, to bypass the auto-correction (issue #150) —
            // and also when nothing will be auto-committed but the word is clearly finished rather than
            // half-typed (no dictionary word extends it). Otherwise a word like "Dads", which is left alone
            // precisely because it looks deliberate, never appears in the strip at all, so there is nothing
            // to long-press to teach it (issue #241).
            if (corrections.isNotEmpty() && (allowAutoCommit || !hadCandidatesBefore)) {
                out.putIfAbsent(
                    word.lowercase(),
                    WordSuggestionCandidate(
                        text = word, confidence = 1.0, isEligibleForAutoCommit = false, sourceProvider = this,
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
                        isEligibleForAutoCommit = allowAutoCommit && i == 0 && freq >= AUTOCORRECT_MIN_FREQ,
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
