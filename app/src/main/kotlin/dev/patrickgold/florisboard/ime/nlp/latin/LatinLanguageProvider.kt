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
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.ime.dictionary.LearnedSnapshot
import dev.patrickgold.florisboard.ime.dictionary.LearnedWordsStore
import dev.patrickgold.florisboard.ime.nlp.BreakIteratorGroup
import dev.patrickgold.florisboard.ime.nlp.LearnOutcome
import dev.patrickgold.florisboard.ime.nlp.LearningProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordOrigin
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.florisboard.lib.android.readText
import org.florisboard.lib.kotlin.guardedByLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln

class LatinLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider, LearningProvider {
    companion object {
        // Default user ID used for all subtypes, unless otherwise specified.
        // See `ime/core/Subtype.kt` Line 210 and 211 for the default usage
        const val ProviderId = "org.florisboard.nlp.providers.latin"

        // Language whose dictionary is assumed present when the assets cannot be listed at all.
        private const val FALLBACK_LANG = "en"

        // Sentinel for "this language has no dictionary" in the resolved-language cache, which cannot
        // store nulls. Never a real language code.
        private const val NO_DICT = ""

        // A typo is only auto-corrected when its best fix is at least this frequent (on the dictionary's
        // 128..255 scale). Rarer fixes are still offered as tap suggestions but never swapped in
        // automatically, so uncommon-but-intentional words (names, jargon) aren't mangled.
        //
        // The number itself moved to [AutoCommitGate] (issue #318): the word learner reads "a correction
        // we would have applied" as evidence that the word was mistyped, so it has to mean the same thing
        // in both places, and the evaluation harness has to measure the same rule both use.
        private const val AUTOCORRECT_MIN_FREQ = AutoCommitGate.MIN_FREQ

        // Spelling-fix suggestions (issue #212): how many corrections to surface, and how many strip slots
        // to reserve for them so prefix completions of a typo don't crowd them out.
        private const val CORRECTION_MAX = CorrectionReaders.MAX_CORRECTIONS
        private const val CORRECTION_RESERVE = 3

        // A prefix with more dictionary words than this is left to the plain completion walk (issue #381):
        // with over a thousand matches in ~64,000 words, the walk meets its eight within the first few
        // hundred, which is cheaper than collecting and sorting the matches.
        private const val DENSE_PREFIX_MATCHES = 1024

        // The one language whose apostrophe forms are rebuilt rather than looked up — see the restoration
        // block in [suggest] and [ElisionEvidence] for why French alone needs it.
        private const val ELISION_LANG = "fr"

        // Every word that can stand before an elided vowel in French. Only these: the elision is a closed
        // class, so a list is the whole rule and a wrong entry cannot invent a prefix that does not exist.
        // Longest first, so `quelqu'un` is split there rather than at the `qu` inside it.
        private val FRENCH_ELISION_PREFIXES = listOf(
            "lorsqu", "puisqu", "jusqu", "quelqu", "presqu", "qu",
            "j", "c", "d", "l", "m", "n", "s", "t",
        )

        // What an elided suffix may start with. h is in the set although only h muet elides (l'homme, not
        // "lhéros"): the corpus decides that case, and this only has to be cheap enough to run per
        // keystroke — it exists to reject `jchat` and `ltrain` before anything is looked up at all.
        private val FRENCH_ELISION_INITIALS = setOf('a', 'e', 'i', 'o', 'u', 'y', 'h')

        /**
         * The French elisions [word] could be, as prefix → suffix pairs, for a word typed without its
         * apostrophe. Says nothing about whether any of them is a real word — [ElisionEvidence] answers
         * that, and without it this produces `n'on` from `non` as readily as `j'aime` from `jaime`.
         */
        internal fun frenchElisionSplits(word: String): List<Pair<String, String>> {
            val lower = word.lowercase()
            return FRENCH_ELISION_PREFIXES.mapNotNull { prefix ->
                if (word.length <= prefix.length || !lower.startsWith(prefix)) return@mapNotNull null
                val suffix = word.substring(prefix.length)
                // Fold one character rather than the whole suffix: this runs on every keystroke, and
                // Normalizer.normalize is the expensive half of [DictFold.foldFrench].
                val initial = DictFold.foldFrench(suffix.take(1)).firstOrNull() ?: return@mapNotNull null
                if (initial in FRENCH_ELISION_INITIALS) prefix to suffix else null
            }
        }

        // Bigram context model (Tier 2): weight on ln(bigram-count+1) added to a candidate that commonly
        // follows the previous word, so context ("of the" over "of teh") re-ranks the correction.
        private const val CONTEXT_WEIGHT = 0.3

        // --- Touch-decoded corrections (issue #242) -------------------------------------------------
        // Both correction readers, their constants and the rule that merges them live in
        // [CorrectionReaders], and the prior and touch variance in [TouchScoring], because the evaluation
        // harness has to score exactly the way this does — a second copy of the formula is what made the
        // #242 numbers impossible to reproduce.
        //
        // Whether a decoded correction may be swapped in silently now lives in [AutoCommitGate], so the
        // rule can be measured against both populations that care about it — mis-taps that must be fixed
        // and correctly typed unknown words that must not be touched (issue #295).

        // Candidates are de-duplicated by their case-folded text. The typed spelling kept alongside a noun
        // capitalisation folds to the very same key as the capitalised form, so it is stored under this
        // prefix, a NUL character that no dictionary word can contain.
        private const val TYPED_WORD_KEY = "\u0000"

        // How frequent a word the user added counts as when it is ranked against the dictionary — for glide
        // candidates (issue #263) and for prefix completions in the strip (issue #264) — on the dictionary's
        // own 128..255 scale. Measured against the bundled English dictionary, 212 is its 90th percentile: a
        // personal word beats nine tenths of the vocabulary, which is what it takes for a name to win against
        // the similar-shaped rarities it actually competes with, while the words everybody writes still come
        // first. That is what keeps a single typed letter from putting a contact's name in front of "and".
        //
        // Deliberately not the frequency stored on the entry. Every word added through this app is saved at
        // the maximum (NlpManager's USER_DICTIONARY_FREQ, 255), so honouring it would put a nickname above
        // "the" — and that number was chosen to protect words from autocorrect, a different question.
        private const val USER_DICTIONARY_RANK_FREQ = 212

        // …and how frequent a word the keyboard picked up *by itself* counts as, before it has been seen
        // often enough to be promoted into that dictionary (issue #318). 187 is the 75th percentile of the
        // bundled English dictionary, measured the same way the 212 above was: it beats three quarters of
        // the vocabulary, so it surfaces once a prefix has narrowed the ordinary words away — but it loses
        // to anything the user added deliberately, which is the right order between a word someone chose
        // to teach and a word we merely noticed.
        private const val LEARNED_RANK_FREQ = 187

        // The band the rank may move inside, once it stops being a single number (see [learnedRankFor]).
        // The ceiling stays clear of USER_DICTIONARY_RANK_FREQ: however often a word is picked up by
        // itself, a word the user typed into their dictionary on purpose still comes first.
        private const val LEARNED_RANK_MIN = 178
        private const val LEARNED_RANK_MAX = 205

        // How steeply the rank climbs with usage. Logarithmic, so the first few repetitions of a new name
        // are worth a lot and the difference between the fiftieth and the hundredth is worth nothing.
        private const val LEARNED_RANK_GROWTH = 12.0

        /**
         * How frequent a word with a decayed [score] counts as (issue #318, round 3).
         *
         * A single band for everything the keyboard picked up was the honest first version — nothing
         * downstream knew how often a word had been seen — but it means a name typed every day ranks
         * exactly like one typed twice in March, and that is the difference the reporter put his finger
         * on: usage is what makes a personal vocabulary feel personal rather than merely present.
         *
         * Anchored so that a word at exactly [WordLearningGate.SIGHTINGS_FOR_SUGGESTIONS] sightings keeps
         * the rank it has always had — the day this shipped, nothing moved for anyone who had just
         * started using it.
         */
        internal fun learnedRankFor(score: Double): Int {
            if (score <= 0.0) return LEARNED_RANK_MIN
            val sightings = score / WordLearningGate.SIGHTINGS_FOR_SUGGESTIONS
            val rank = LEARNED_RANK_FREQ + LEARNED_RANK_GROWTH * ln(sightings)
            return rank.toInt().coerceIn(LEARNED_RANK_MIN, LEARNED_RANK_MAX)
        }

        // How many learned words one prefix may contribute. Small on purpose: these sit among the tail of
        // the dictionary walk, and a user with a large personal vocabulary should not find the strip made
        // entirely of their own rare words.
        private const val LEARNED_MAX = 3

        // German umlaut/ß restoration (issue #219): bound the variant generation so a long word with many
        // a/o/u doesn't explode combinatorially (2^sites). Words needing more than this are left alone.
        private const val MAX_UMLAUT_SITES = 6
        private const val MAX_GERMAN_VARIANTS = 128

        // Next-word prediction (issue #245) stops at these: past one of them the previous word belongs to a
        // sentence that is over. Only the hard enders — a comma separates clauses that still read as one
        // sentence, and the bigram across them would be worth having.
        private val SENTENCE_ENDINGS = setOf('.', '!', '?', '…')

        /**
         * Whether the cursor stands somewhere a next-word prediction is worth offering. Split out of
         * `nextWordPredictions` because it is the whole decision — the rest of that method is dictionary
         * lookup — and because it is the part with edge cases worth pinning down in a test.
         *
         * Two things finish a word: a space that was typed, and a space that was promised. Accepting a
         * suggestion leaves the second kind (issue #266) — nothing is written until the next commit needs
         * it, so the text still ends in a letter while the word is as finished as if space had been pressed.
         * Requiring a written space is what made the strip stay empty until the user pressed space.
         *
         * A sentence end stops it either way. The bigram tables know nothing about sentences, so what could
         * be offered after a full stop continues the sentence that just ended; offering nothing is the better
         * answer, and it hands the quick-action row back for the same reason an empty field does.
         *
         * That last rule changes nothing today, and is here on purpose. [previousWordOf] reads the word by
         * walking letters backwards, so it already stops at *any* punctuation — measured on a device, a full
         * stop was silent before this rule existed. But it stops there incidentally, not because anyone
         * decided sentences should end a prediction: the day that walk learns to look past a comma (worth
         * doing — the bigram context in `correctionsFor` wants exactly that), the full stop would quietly
         * start being crossed too. The decision belongs where predictions are decided.
         */
        /**
         * Which words the strip offers, out of the three places a continuation can come from, and in
         * what order. Split out of `nextWordPredictions` for the same reason [isAtPredictionPoint] was:
         * everything around it is table lookup, and this is the decision.
         *
         * The order is an argument about evidence, not about table size. [learned] comes first because
         * a pair the user has actually written outranks any corpus — that is the half of word learning
         * people recognise as the keyboard knowing them (issue #318). [deep] comes next because two
         * words of context, where they are available at all, are strictly more informative than one:
         * measured, they lift the band where 46 % of predictions are asked for by 4.6 pp while a
         * five-times-larger bigram table lifts it by nothing (issue #334). [shallow] fills what is
         * left, which is most of the strip most of the time.
         *
         * A word offered by more than one source keeps its first, best-evidenced position, and only a
         * word from [learned] is marked as the user's own.
         */
        internal fun mergePredictions(
            learned: List<String>,
            deep: List<String>,
            shallow: List<String>,
            max: Int,
        ): List<Pair<String, Boolean>> {
            val seen = LinkedHashMap<String, Boolean>() // candidate -> came from the user's own writing
            for (candidate in learned) if (candidate.isNotBlank()) seen.putIfAbsent(candidate, true)
            for (candidate in deep) if (candidate.isNotBlank()) seen.putIfAbsent(candidate, false)
            for (candidate in shallow) if (candidate.isNotBlank()) seen.putIfAbsent(candidate, false)
            return seen.entries.take(max).map { it.key to it.value }
        }

        /**
         * How many continuations are scanned for one that extends the prefix being typed (issue #334).
         *
         * Deliberately much larger than [CONTEXT_COMPLETION_MAX]: the reporter's sketch took the top three
         * continuations and *then* filtered them by the prefix, which promotes nothing whenever the three
         * most common continuations happen to start with other letters — that is most of the time, and it
         * would have made the whole item look inert. Take many, keep few.
         */
        internal const val CONTEXT_COMPLETION_SCAN = 32

        /** How many context-blessed completions may lead the strip. Small: they are claims. */
        internal const val CONTEXT_COMPLETION_MAX = 3

        /** Corpus sightings the exact triple needs before it may lead the strip mid-word. */
        internal const val IN_WORD_PREDICTION_MIN_COUNT = 3

        /**
         * The completions the sentence expects, best first — two words of context ahead of one
         * (issue #334, his §3.5 and §3.9).
         *
         * Pure so the measuring stand ranks the strip with the same rule the keyboard uses, rather than
         * with a copy of it that can drift. [deep] is the single trigram answer, [blessed] the bigram
         * ones; both are already filtered to the prefix being typed by the caller.
         */
        internal fun orderContextCompletions(deep: String?, blessed: List<String>, max: Int): List<String> {
            if (max <= 0) return emptyList()
            val out = LinkedHashSet<String>()
            if (deep != null) out.add(deep)
            for (candidate in blessed) {
                if (out.size >= max) break
                out.add(candidate)
            }
            return out.take(max)
        }

        internal fun isAtPredictionPoint(textBeforeCursor: String, phantomSpacePending: Boolean): Boolean {
            if (!textBeforeCursor.endsWith(" ") && !phantomSpacePending) return false
            val settled = textBeforeCursor.trimEnd()
            return settled.isNotEmpty() && settled.last() !in SENTENCE_ENDINGS
        }

        /**
         * Whether the dictionary has any business judging [word] at all (issue #309).
         *
         * A word with a digit in it is a version, a model number, a code — top10, covid19, mp3 — and every
         * one of those is exactly one deletion away from a very common word. So the edit-distance corrector
         * offers to delete the digit, and on the classic gate it does so silently: nothing in the dictionary
         * starts with `top1`, so `hadCandidatesBefore` is false by construction and the swap is allowed.
         * `top10` came out as `top0`, because typing the second digit is what collected the correction the
         * first digit had already earned.
         *
         * [spell] has refused these words since it was written; the same refusal belongs on the suggestion
         * side, and having it in one place is what keeps the two from drifting apart again.
         *
         * Digits, not "anything that isn't a letter": an apostrophe is part of *don't*, and correcting
         * *dont* to it is a fix worth making (issue #212).
         *
         * An address is refused for exactly the same reason, and it became urgent the moment [WordRun]
         * let one stay in one piece (issue #318): while `mail@` is being composed it is one deletion away
         * from `mail`, so the edit-distance corrector offers to throw the `@` away — and on the classic
         * gate it does so silently, because nothing in the dictionary starts with `mail@`. That is
         * `top10` → `top0` again with a separator instead of a digit. The web prefixes go with it, and
         * the same refusal keeps [spell] from underlining every address in red.
         */
        internal fun isDictionaryJudgeable(word: String): Boolean =
            word.none { it.isDigit() || it in RUN_PUNCTUATION } && !WordRun.isWebPrefixed(word)

        /** The characters that only ever hold an address together — never a word the dictionary knows. */
        private const val RUN_PUNCTUATION = "@_+:/"

        /**
         * The form [word] must take in a language that always capitalises it, or null (issue #333).
         *
         * One entry, and the list is meant to stay short: this is for words a language capitalises
         * *wherever they stand*, which is a much stronger claim than "the dictionary spells it this
         * way". English "I" qualifies. A German noun does not — it is capitalised because of what it
         * is, which the dictionary already records, and the noun path in [suggest] handles it.
         *
         * The language check is what makes the rule safe rather than merely useful. Polish writes `i`
         * for *and* and Italian uses it as a plural article; both are among the commonest words in
         * those languages, and both would be visibly wrecked by an unguarded rule.
         */
        internal fun standaloneCapitalizationIn(word: String, language: String): String? = when {
            !language.equals("en", ignoreCase = true) -> null
            word == "i" -> "I"
            else -> null
        }

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

    /**
     * The personal words that went into the glide index last built, with the locale they were read for
     * (issue #263). Written by [getListOfWords], read by [getFrequencyForWord] — which runs for every pruned
     * candidate of every gesture, so this is a plain volatile reference to an immutable map rather than
     * another lock on that path. Both always concern the active subtype: the classifier rebuilds its word
     * data whenever the subtype changes, and asks for frequencies only afterwards.
     */
    @Volatile
    private var glideUserWords: Pair<String, Map<String, Int>>? = null

    // Word→frequency dictionaries cached per language (issue #127, glide typing phase 2). Each bundled
    // ime/dict/<lang>.json maps a word to a frequency in [128,255]; languages without a bundled file fall
    // back to English.
    private val wordDataByLang = guardedByLock { mutableMapOf<String, Map<String, Int>>() }
    private val wordDataSerializer = MapSerializer(String.serializer(), Int.serializer())

    // Per-language word list sorted by frequency (descending), so prefix completion can scan the most
    // frequent words first and stop early. Built lazily from the word data and cached.
    private val rankedWordsByLang = guardedByLock { mutableMapOf<String, List<String>>() }

    // Fold keys aligned with rankedWordsByLang, for languages whose lookup spelling differs (issue #265).
    private val rankedFoldKeysByLang = guardedByLock { mutableMapOf<String, List<String>>() }

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

    /**
     * The dictionary language to use for [subtype], or null when there is none.
     *
     * This used to answer English for any language without a dictionary of its own (issue #265), which is
     * the worst of the three possible answers. Typing Arabic, every word came back unknown, the spell
     * checker underlined the entire language, and the distance-2 fallback ground through ~500,000 string
     * allocations per keystroke over the Latin alphabet to find nothing it could ever have found. Worse for
     * the languages that *are* written in Latin: an English dictionary does not merely fail to help
     * Finnish, it actively corrects Finnish into English.
     *
     * Null instead means no suggestions, no autocorrect, no red underline — a state the provider contract
     * explicitly allows (see SuggestionProvider.suggest) and the one the Han provider already uses when its
     * language pack is missing.
     */
    private fun dictLangFor(subtype: Subtype): String? {
        val subLang = normalizeLang(subtype.primaryLocale.language)
        // ConcurrentHashMap cannot hold a null value, and a language code is never blank, so the empty
        // string stands in for "looked, found nothing".
        val resolved = resolvedDictLang.getOrPut(subLang) {
            if (subLang.isNotBlank() && hasDict(subLang)) subLang else NO_DICT
        }
        return resolved.takeIf { it != NO_DICT }
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
    private suspend fun wordDataFor(subtype: Subtype): Map<String, Int> =
        dictLangFor(subtype)?.let { wordDataForLang(it) }.orEmpty()

    /**
     * Loads (and caches) the word→frequency map for a specific dictionary [lang], or an empty map if it
     * cannot be read.
     *
     * The runCatching is load-bearing since issue #265: [readDict] falls through to an asset that only
     * exists for bundled languages and throws otherwise, and it was the English fallback that used to keep
     * that from ever happening. It now can — a dictionary deleted between the resolve and the read, for
     * instance — and an empty map is the honest answer.
     */
    private suspend fun wordDataForLang(lang: String): Map<String, Int> =
        wordDataByLang.withLock { cache ->
            cache[lang] ?: run {
                val loaded = runCatching {
                    Json.decodeFromString(wordDataSerializer, readDict(lang))
                }.getOrDefault(emptyMap())
                cache[lang] = loaded
                loaded
            }
        }

    // Context model. Per-language "w1 w2" -> count and "w1 w2 w3" -> count tables, read from a
    // downloaded <lang>_bigrams.txt / <lang>_trigrams.txt or the bundled English bigram asset. A
    // language without a file gets [NgramIndex.EMPTY] and context simply doesn't apply.
    //
    // The bigram table re-ranks corrections by the previous word AND feeds prediction; the trigram
    // table (issue #334) feeds prediction only — see [nextWordPredictions] for why it goes no further.
    private val bigramsByLang = guardedByLock { mutableMapOf<String, Bigrams>() }
    private val trigramsByLang = guardedByLock { mutableMapOf<String, NgramIndex>() }

    /**
     * The bigram table and, for the one language that needs it, the per-word counts the apostrophe
     * restoration reads out of the same text ([ElisionEvidence]).
     *
     * Together rather than in two caches because the file is 2.4 MB and reading it twice to answer two
     * questions about the same sentences would be the only cost either of them has.
     */
    private class Bigrams(val index: NgramIndex, val elisions: Map<String, Int>) {
        companion object {
            val EMPTY = Bigrams(NgramIndex.EMPTY, emptyMap())
        }
    }

    private suspend fun bigramDataFor(subtype: Subtype): Bigrams {
        val lang = dictLangFor(subtype) ?: return Bigrams.EMPTY
        return bigramsByLang.withLock { cache ->
            cache[lang] ?: run {
                val text = tableText(lang, "bigrams", GlideDictionaryManager.bigramFile(appContext, lang))
                val fold = if (DictFold.hasNonTrivialFold(lang)) { key: String -> DictFold.foldKey(lang, key) } else null
                Bigrams(
                    index = text?.let { runCatching { NgramIndex.parse(it, fold) }.getOrNull() } ?: NgramIndex.EMPTY,
                    elisions = if (lang != ELISION_LANG || text == null) {
                        emptyMap()
                    } else {
                        runCatching { ElisionEvidence.parse(text) { key -> DictFold.foldKey(lang, key) } }
                            .getOrDefault(emptyMap())
                    },
                ).also { cache[lang] = it }
            }
        }
    }

    private suspend fun bigramsFor(subtype: Subtype): NgramIndex = bigramDataFor(subtype).index

    /**
     * How often each apostrophe form and its apostrophe-less spelling appear in [subtype]'s corpus.
     *
     * Empty for every language but French, and empty for French too when the bigram file never arrived —
     * it is a best-effort download ([GlideDictionaryManager.ensureDownloaded]). Both cases leave the
     * restoration exactly as it was before there was one: suggestions only, nothing swapped in.
     *
     * The language is checked before the table is touched, because this is asked on every keystroke and
     * the bigram file is otherwise only read once there is a word of context — no other language should
     * start paying for its 2.4 MB one word earlier than it used to.
     */
    private suspend fun elisionsFor(subtype: Subtype): Map<String, Int> =
        if (dictLangFor(subtype) != ELISION_LANG) emptyMap() else bigramDataFor(subtype).elisions

    private suspend fun trigramsFor(subtype: Subtype): NgramIndex {
        val lang = dictLangFor(subtype) ?: return NgramIndex.EMPTY
        return trigramsByLang.withLock { cache ->
            cache[lang] ?: loadTable(lang, "trigrams", GlideDictionaryManager.trigramFile(appContext, lang))
                .also { cache[lang] = it }
        }
    }

    /**
     * The raw text of one context table, or null when the language has none. A downloaded per-language
     * file takes precedence over a bundled asset — mirrors readDict for the unigram dictionaries. Only
     * English bundles one, and only for bigrams.
     */
    private fun tableText(lang: String, kind: String, downloaded: java.io.File): String? = runCatching {
        if (downloaded.isFile && downloaded.length() > 0) {
            downloaded.readText()
        } else {
            appContext.assets.readText("ime/dict/${lang}_$kind.txt")
        }
    }.getOrNull()

    /**
     * Read one context table.
     *
     * The file stores its keys lowercased; the lookup happens in fold space, so a language with
     * non-trivial folding has to fold the keys on the way in or no key would ever match. Folding the
     * whole key at once is safe — every fold passes the separating spaces through untouched — and
     * [NgramIndex.parse] re-sorts afterwards, because folding can reorder keys and collide them.
     */
    private fun loadTable(lang: String, kind: String, downloaded: java.io.File): NgramIndex = runCatching {
        val text = tableText(lang, kind, downloaded) ?: return NgramIndex.EMPTY
        val fold = if (DictFold.hasNonTrivialFold(lang)) { key: String -> DictFold.foldKey(lang, key) } else null
        NgramIndex.parse(text, fold)
    }.getOrDefault(NgramIndex.EMPTY)

    /**
     * The [n] words right before the one being composed, folded, oldest first — the context the
     * prediction and the corrector condition on.
     *
     * The walk stops at anything that is not a word character, so it never crosses a comma, a digit or
     * a full stop. That is deliberate and it is the same rule the corpus tables were counted under: a
     * pair the keyboard can never look up is a pair worth no bytes. Whether a *prediction* may be
     * offered at all is a separate question and stays in [isAtPredictionPoint], so that widening this
     * walk can never quietly start predicting across the end of a sentence.
     */
    private fun previousWordsOf(content: EditorContent, index: LowerIndex, n: Int): List<String> {
        var before = content.textBeforeSelection.removeSuffix(content.composingText)
        val out = ArrayList<String>(n)
        while (out.size < n) {
            before = before.trimEnd()
            val word = before.takeLastWhile { DictFold.isWordChar(it) || it == '\'' }
            if (word.isEmpty()) break
            before = before.dropLast(word.length)
            val folded = index.fold(word)
            if (folded.isEmpty()) break
            out.add(folded)
        }
        return out.asReversed()
    }

    /** The word right before the one being composed, folded — the context for the bigram model. */
    private fun previousWordOf(content: EditorContent, index: LowerIndex): String? =
        previousWordsOf(content, index, 1).firstOrNull()

    /** Context-score function for [correctionsFor]: boosts candidates that commonly follow [prevWord]. */
    private fun bigramContextScore(prevWord: String?, bigrams: NgramIndex): (String) -> Double {
        if (prevWord == null || bigrams.isEmpty) return { 0.0 }
        return { cand -> CONTEXT_WEIGHT * ln((bigrams.countOf("$prevWord $cand") + 1L).toDouble()) }
    }

    /** Frequency-sorted (descending) word list for [subtype]'s dictionary language, cached per language. */
    private suspend fun rankedWordsFor(subtype: Subtype): List<String> {
        val lang = dictLangFor(subtype) ?: return emptyList()
        val data = wordDataFor(subtype)
        return rankedWordsByLang.withLock { cache ->
            cache[lang] ?: run {
                val ranked = data.entries.sortedByDescending { it.value }.map { it.key }
                cache[lang] = ranked
                ranked
            }
        }
    }

    /**
     * The fold keys of [rankedWordsFor], positionally aligned with it, or null when the language's fold is
     * a plain lowercase and prefix matching can compare the words directly.
     *
     * Prefix completion is the one place that works on the *stored* spellings rather than the folded index,
     * so an Arabic writer typing ان or a French writer typing ho would otherwise miss أنا and hôte.
     * Precomputed once per language because folding 79,000 words on every keystroke is not an option.
     */
    private suspend fun rankedFoldKeysFor(subtype: Subtype, ranked: List<String>): List<String>? {
        val lang = dictLangFor(subtype)?.takeIf { DictFold.hasNonTrivialFold(it) } ?: return null
        return rankedFoldKeysByLang.withLock { cache ->
            // Takes the very list it will be indexed alongside, and rebuilds on a length mismatch: a
            // dictionary finishing its download between the two lookups would otherwise leave the caller
            // indexing one list by the other's positions.
            cache[lang]?.takeIf { it.size == ranked.size }
                ?: ranked.map { DictFold.foldKey(lang, it) }.also { cache[lang] = it }
        }
    }

    private val prefixOrderByLang = guardedByLock { mutableMapOf<String, Pair<List<String>, PrefixOrder>>() }

    /**
     * [ranked] sorted by spelling as well, so the completion walk can find a rare prefix's words without
     * reading the whole list (issue #381). Built once per language, for the very list it was given.
     */
    private suspend fun prefixOrderFor(subtype: Subtype, ranked: List<String>, rankedKeys: List<String>?): PrefixOrder? {
        val lang = dictLangFor(subtype) ?: return null
        return prefixOrderByLang.withLock { cache ->
            cache[lang]?.takeIf { it.first === ranked }?.second
                ?: PrefixOrder(ranked, rankedKeys).also { cache[lang] = ranked to it }
        }
    }

    // --- Spell check / autocorrect core (issue #127 follow-up) --------------------------------------

    /**
     * Folded view of a language's dictionary for spell checking / correction: [freq] maps a folded word to
     * its frequency, [canonical] to the spelling to commit, and [alphabet] holds every letter the language
     * uses (for generating edit candidates).
     *
     * "Folded" was "lowercased" until issue #265; for Arabic script it also unifies the letter forms writers
     * use interchangeably, which is what lets a word be found however it was spelled. See [DictFold].
     */
    private class LowerIndex(
        val lang: String,
        val freq: Map<String, Int>,
        val canonical: Map<String, String>,
        val alphabet: Set<Char>,
        /**
         * Fold key → every dictionary spelling sharing it, most frequent first; only the keys with more
         * than one. Empty for languages whose fold merges nothing, which is all of them but Arabic script.
         *
         * أن، إن and آن all fold to ان, and all three belong in the strip — picking one and hiding the
         * others would turn a choice the writer can make into a guess the keyboard makes for them.
         */
        val variants: Map<String, List<String>> = emptyMap(),
    ) {
        /** The key [word] is looked up under. */
        fun fold(word: String): String = DictFold.foldKey(lang, word)

        /** Every dictionary spelling that folds to [key], most frequent first. */
        fun formsOf(key: String): List<String> = variants[key] ?: listOfNotNull(canonical[key])

        companion object {
            /** For a subtype whose language has no dictionary at all — see [dictLangFor]. */
            val EMPTY = LowerIndex("", emptyMap(), emptyMap(), emptySet())
        }
    }

    private val lowerIndexByLang = guardedByLock { mutableMapOf<String, LowerIndex>() }

    // Lexicographically sorted word list per language, used by the beam decoder to prune partial paths that
    // are no longer a prefix of any real word (issue #242). Shares its strings with the LowerIndex, so this
    // costs one array of references per language and no duplicated character data.
    private val prefixIndexByLang = guardedByLock { mutableMapOf<String, TouchBeamDecoder.PrefixIndex>() }

    private suspend fun prefixIndexFor(subtype: Subtype): TouchBeamDecoder.PrefixIndex? {
        val lang = dictLangFor(subtype) ?: return null
        val index = lowerIndexFor(subtype)
        return prefixIndexByLang.withLock { cache ->
            cache[lang] ?: run {
                val words = index.freq.keys.toTypedArray().apply { sort() }
                // Each word's frequency beside it, so completions can rank a range without a lookup per entry.
                TouchBeamDecoder.PrefixIndex(words, IntArray(words.size) { index.freq.getValue(words[it]) })
            }.also { cache[lang] = it }
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
                // Also the raw word data: since #265 a failed read caches an empty map under that
                // language, and without dropping it the dictionary that just finished downloading would
                // never be seen.
                wordDataByLang.withLock { it.clear() }
                rankedWordsByLang.withLock { it.clear() }
                rankedFoldKeysByLang.withLock { it.clear() }
                prefixOrderByLang.withLock { it.clear() }
                lowerIndexByLang.withLock { it.clear() }
                bigramsByLang.withLock { it.clear() }
                trigramsByLang.withLock { it.clear() }
                prefixIndexByLang.withLock { it.clear() }
            }
        }
    }

    private suspend fun lowerIndexFor(subtype: Subtype): LowerIndex =
        dictLangFor(subtype)?.let { lowerIndexForLang(it) } ?: LowerIndex.EMPTY

    private suspend fun lowerIndexForLang(lang: String): LowerIndex {
        val data = wordDataForLang(lang)
        if (data.isEmpty()) return LowerIndex.EMPTY
        return lowerIndexByLang.withLock { cache ->
            cache[lang] ?: run {
                val freq = HashMap<String, Int>(data.size)
                val canonical = HashMap<String, String>(data.size)
                val alphabet = HashSet<Char>()
                // Only collected where the fold actually merges spellings, so nothing is paid for the
                // languages where every key has exactly one form anyway. Both folding languages need it:
                // it is what lets a typed ان or hote reach أن and hôte as a suggestion at all.
                val forms = if (DictFold.hasNonTrivialFold(lang)) HashMap<String, MutableList<String>>() else null
                for ((word, f) in data) {
                    val key = DictFold.foldKey(lang, word)
                    if ((freq[key] ?: -1) < f) {
                        freq[key] = f
                        canonical[key] = word
                    }
                    forms?.getOrPut(key) { ArrayList(1) }?.add(word)
                    // Combining marks count: Devanagari, Bengali and Tamil write their vowels that way, and
                    // an alphabet without them cannot generate a correction that inserts or fixes one.
                    for (ch in key) if (DictFold.isWordChar(ch)) alphabet.add(ch)
                }
                // Include the apostrophe so a missing-apostrophe typo of an unknown word can be corrected;
                // dictionary words like "what's"/"don't" carry it but isWordChar() drops it above. (The
                // common case — the apostrophe-less form is itself a known word, e.g. "whats" — is handled
                // by the contraction-restoration block in suggest(), issue #212.)
                alphabet.add('\'')
                val variants = forms.orEmpty().asSequence()
                    .filter { it.value.size > 1 }
                    .associate { (key, spellings) -> key to spellings.sortedByDescending { data[it] ?: 0 } }
                LowerIndex(lang, freq, canonical, alphabet, variants).also { cache[lang] = it }
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
        if (!prefs.suggestion.multilingualTyping.get()) return listOfNotNull(active)
        // A subtype without a dictionary contributes nothing rather than dragging English in (#265) —
        // otherwise turning multilingual typing on would quietly restore the fallback this removed.
        val langs = LinkedHashSet<String>().apply { active?.let { add(it) } }
        runCatching { subtypeManager.subtypes.forEach { s -> dictLangFor(s)?.let { langs.add(it) } } }
        return langs.toList()
    }

    /**
     * True if [lower] is an ordinary lowercase word in one of the user's *other* keyboard languages, so the
     * active language's noun capitalisation must stand aside (issue #190): an English "hand" typed with the
     * German subtype active should not become "Hand".
     */
    private suspend fun isLowercaseWordInAnotherLanguage(lower: String, subtype: Subtype): Boolean {
        val active = dictLangFor(subtype) ?: return false
        for (lang in acceptedDictLangs(subtype)) {
            if (lang == active) continue
            if (lowerIndexForLang(lang).canonical[lower]?.first()?.isLowerCase() == true) return true
        }
        return false
    }

    /** True if [word] is a known dictionary word in any accepted language, or in the user dictionary. */
    private suspend fun isKnownWord(word: String, subtype: Subtype): Boolean {
        for (lang in acceptedDictLangs(subtype)) {
            val index = lowerIndexForLang(lang)
            if (index.freq.containsKey(index.fold(word))) return true
        }
        // A French elision is a word the language writes constantly and the word list does not hold — 13
        // apostrophe entries in 68,605 — so asking the word list alone underlines `d'une` and `qu'il` as
        // misspellings while `c'est` passes, purely by which ones survived the generator's Hunspell pass.
        // It matters more now that the restoration commits them: correcting into a word the spell checker
        // then marks wrong would be the keyboard contradicting itself. The corpus is the same evidence the
        // restoration uses, so the two can never disagree about what a French word is.
        if (word.any { it == '\'' || it == '’' }) {
            val elisions = elisionsFor(subtype)
            if (elisions.isNotEmpty() &&
                ElisionEvidence.isAttested(elisions, ElisionEvidence.key(DictFold.foldKey(ELISION_LANG, word)))
            ) {
                return true
            }
        }
        return isInUserDictionary(word, subtype)
    }

    /** Dictionary words closest to (a misspelling of) [word], ranked by frequency. */
    private fun correctionsFor(
        word: String,
        index: LowerIndex,
        prefixIndex: TouchBeamDecoder.PrefixIndex?,
        maxCount: Int,
        allowDistance2: Boolean,
        contextScore: (cand: String) -> Double = { 0.0 },
    ): List<String> = CorrectionReaders.byEditDistance(
        folded = index.fold(word),
        freq = index.freq,
        alphabet = index.alphabet,
        prefixIndex = prefixIndex,
        maxCount = maxCount,
        allowDistance2 = allowDistance2,
        sqDistance = KeyProximityInfo::normSqDistance,
        contextScore = contextScore,
    ).map { index.canonical[it] ?: it }

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
        if (!isAtPredictionPoint(content.textBeforeSelection, content.phantomSpacePending)) return emptyList()
        val index = lowerIndexFor(subtype)
        val context = previousWordsOf(content, index, 2)
        val prevWord = context.lastOrNull() ?: return emptyList()
        val bigrams = bigramsFor(subtype)
        val prefix = "$prevWord "

        // The pairs this user has actually written, ahead of the corpus (issue #318). This is the half of
        // word learning people recognise as the keyboard knowing them: a learned single word only helps
        // while that word is being typed, but a learned *pair* answers before they have typed anything.
        //
        // Deliberately only here, and not in `bigramContextScore`, which feeds the *corrector*. Context
        // that re-ranks a silent replacement was measured to mangle 2.4–9.2 % of correctly typed words
        // even with the large corpus tables; a handful of personal pairs is far thinner evidence and the
        // failure would be a word the user never typed appearing in place of one they did. Offering a
        // prediction risks nothing — it sits in the strip until it is chosen.
        val learnedPairs = if (prefs.wordLearningIsOn) {
            dictLangFor(subtype)
                ?.let { lang -> runCatching { LearnedWordsStore.bigrams(appContext, lang) }.getOrNull() }
                .orEmpty()
                .asSequence()
                .filter { it.key.startsWith(prefix) && it.value >= WordLearningGate.SIGHTINGS_FOR_SUGGESTIONS }
                .sortedByDescending { it.value }
                .map { it.key.substring(prefix.length) }
                .filter { it.isNotBlank() }
                .toList()
        } else {
            emptyList()
        }
        // Two words of context before one (issue #334). Measured on held-out text, this is the only
        // thing that helps where most predictions are asked for: after "the", "to", "of" — 46 % of all
        // prediction points — a five-times-larger bigram table is worth *exactly nothing*, because
        // those pairs are all in it already and "the" cannot tell "in the" from "at the". The trigram
        // answers about half the time; where it does not, the bigram table is unchanged from before.
        val trigrams = trigramsFor(subtype)
        val deepPrefix = if (context.size >= 2) context.joinToString(" ") else null
        val deepPairs = if (deepPrefix != null && !trigrams.isEmpty) {
            trigrams.topContinuations(deepPrefix, maxCandidateCount)
        } else {
            emptyList()
        }
        val corpusPairs = bigrams.topContinuations(prevWord, maxCandidateCount)

        // When nothing above matched, the most common words of the language rather than an empty strip
        // (issue #334, his §3.2).
        //
        // This overturns a decision that used to be written here — "no unigram fallback on purpose,
        // the strip would fill with generic filler that carries no information". That reasoning is still
        // true about the *words*: `the` after `hi` says nothing about what is being written. What it got
        // wrong is the alternative it was measured against. The tables cover 92.2 % of prediction points,
        // so roughly every twelfth finished word left the strip blank — and a strip that empties itself
        // at unpredictable moments does not read as restraint, it reads as broken, which is exactly how
        // it was reported. Filler that is never auto-committed costs a glance; a bar that blinks out
        // costs trust in the whole row.
        //
        // Deliberately the dictionary's own frequency order rather than the user's learned words: a
        // fresh install has no learned words at all, and this exists precisely for the moments when
        // there is nothing better to say.
        val fallback = if (learnedPairs.isEmpty() && deepPairs.isEmpty() && corpusPairs.isEmpty()) {
            rankedWordsFor(subtype).take(maxCandidateCount).map { index.fold(it) }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }

        return mergePredictions(learnedPairs, deepPairs, corpusPairs + fallback, maxCandidateCount).map { (candidate, isLearned) ->
            val text = index.canonical[candidate] ?: candidate
            WordSuggestionCandidate(
                text = text,
                confidence = (index.freq[candidate] ?: 0) / 255.0,
                isEligibleForAutoCommit = false,
                sourceProvider = this,
                isLearned = isLearned,
            )
        }
    }

    // --- Touch-decoded corrections (issue #242) -----------------------------------------------------

    /**
     * Corrections decoded from where the user's fingers actually landed (see [CorrectionReaders.byTouch]),
     * or null when that is not possible — no tap evidence for this exact word, no captured key geometry,
     * or the beam found nothing.
     */
    private suspend fun touchCorrectionsFor(
        word: String,
        subtype: Subtype,
        index: LowerIndex,
        maxCount: Int,
        contextScore: (cand: String) -> Double,
    ): CorrectionReaders.TouchReading? {
        val points = TouchTrace.pointsFor(word) ?: return null
        val layout = KeyProximityInfo.snapshot() ?: return null
        val prefixIndex = prefixIndexFor(subtype) ?: return null
        val reading = CorrectionReaders.byTouch(
            points = points,
            typed = word,
            folded = index.fold(word),
            freq = index.freq,
            alphabet = index.alphabet,
            prefixIndex = prefixIndex,
            layout = layout,
            maxCount = maxCount,
            contextScore = contextScore,
        ) ?: return null
        return CorrectionReaders.TouchReading(
            words = reading.words.map { index.canonical[it] ?: it },
            topCost = reading.topCost,
        )
    }

    /**
     * Longer words a half-typed word with a slip in it probably belongs to (issue #381): from the taps where
     * there are any, and from the typed string always — see [CorrectionReaders.completions].
     */
    private suspend fun completionsFor(
        word: String,
        subtype: Subtype,
        index: LowerIndex,
        maxCount: Int,
        contextScore: (cand: String) -> Double,
    ): List<String> {
        val prefixIndex = prefixIndexFor(subtype) ?: return emptyList()
        val folded = index.fold(word)
        val points = TouchTrace.pointsFor(word)
        val layout = KeyProximityInfo.snapshot()
        val byTouch = if (points != null && layout != null) {
            CorrectionReaders.completionsByTouch(points, word, folded, prefixIndex, layout, maxCount, contextScore)
        } else {
            emptyList()
        }
        val byString = CorrectionReaders.completionsByString(
            folded, prefixIndex, index.alphabet, maxCount, KeyProximityInfo::normSqDistance, contextScore,
        )
        return CorrectionReaders.completions(byTouch, byString).map { index.canonical[it] ?: it }
    }

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

    // --- The user's own words as correction targets (issue #318 follow-up) ------------------------

    /**
     * Everything the user added by hand for one language and locale, read once and kept in memory.
     *
     * A copy rather than a query, for two reasons. The corrector asks by *edit distance* and looks up a few
     * hundred candidate spellings per keystroke. And the strip used to ask the databases themselves three
     * times per keystroke — is this word the user's own, which of their words start with it, what is stored
     * behind it as a shortcut — which on a Galaxy A55 release build cost 25–70 ms per key press (issue #381):
     * a `LIKE '%word%'` scan of the keyboard's own dictionary each time, and a content-provider call into
     * another process for the system one.
     *
     * The copy is dropped when [DictionaryManager.userVocabularyVersion] moves, which counts every write to
     * either dictionary, and on [onPersonalVocabularyChanged]. Before #381 it was dropped by the latter
     * alone, which the settings screens never call, so a word added there reached the corrector only after
     * a restart.
     */
    private class UserVocabulary(
        val version: Int,
        /** Fold key → stored spelling, for the corrector's edit-distance lookups. */
        val byFold: Map<String, String>,
        /** Every stored spelling with its fold key, in storage order, for completions. */
        val words: List<Pair<String, String>>,
        /** Every stored spelling, lowercased: is a typed word the user's own? */
        val lowercase: Set<String>,
        /** Shortcut, lowercased → what is stored behind it, in storage order. */
        val shortcuts: Map<String, List<String>>,
    )

    private val userVocabularyByLocale = guardedByLock { mutableMapOf<String, UserVocabulary>() }

    override suspend fun onPersonalVocabularyChanged() {
        userVocabularyByLocale.withLock { it.clear() }
    }

    private suspend fun userVocabularyFor(subtype: Subtype): UserVocabulary? {
        val lang = dictLangFor(subtype) ?: return null
        val dm = runCatching { DictionaryManager.default() }.getOrNull() ?: return null
        runCatching { dm.loadUserDictionariesIfNecessary() }
        // Read before the query, so a write that lands while the copy is being built makes the next
        // keystroke build it again rather than keep a copy that already missed it.
        val version = dm.userVocabularyVersion
        val key = "$lang|${subtype.primaryLocale.localeTag()}"
        return userVocabularyByLocale.withLock { cache ->
            cache[key]?.takeIf { it.version == version } ?: run {
                val entries = runCatching { dm.queryAllUserWords(subtype.primaryLocale) }.getOrDefault(emptyList())
                val byFold = HashMap<String, String>()
                val words = ArrayList<Pair<String, String>>(entries.size)
                val lowercase = HashSet<String>()
                val shortcuts = LinkedHashMap<String, MutableList<String>>()
                for (entry in entries) {
                    val word = entry.word.trim()
                    if (word.isEmpty()) continue
                    val folded = DictFold.foldKey(lang, word)
                    byFold[folded] = word
                    words.add(folded to word)
                    lowercase.add(word.lowercase())
                    entry.shortcut?.takeIf { it.isNotBlank() }?.let { shortcut ->
                        val expansions = shortcuts.getOrPut(shortcut.lowercase()) { ArrayList() }
                        if (word !in expansions) expansions.add(word)
                    }
                }
                UserVocabulary(version, byFold, words, lowercase, shortcuts)
            }.also { cache[key] = it }
        }
    }

    private suspend fun personalWordsFor(subtype: Subtype): Map<String, String> =
        userVocabularyFor(subtype)?.byFold.orEmpty()

    /**
     * The user's own words one edit away from [word] — theirs to be corrected *into*, which no amount of
     * dictionary data can do for them.
     *
     * This closes the gap the maintainer found: a personal word was offered as a prefix completion and
     * protected from autocorrect, but it was invisible to the corrector, because `correctionsFor` filters
     * candidates against the bundled dictionary index and nothing else. So typing a name slightly wrong
     * produced no offer at all, and the word was never marked or committed by space the way a dictionary
     * word is.
     *
     * Distance 1 only, and returned as ordinary correction candidates so the existing auto-commit gate
     * decides whether any of them may be swapped in silently. That gate is the whole safety argument
     * here: every word added to a correction candidate set is a word that correctly typed input can now
     * be rewritten *into* — the lesson from #242, where a bigger dictionary measured negative for exactly
     * that reason.
     */
    private suspend fun personalCorrectionsFor(
        word: String,
        subtype: Subtype,
        index: LowerIndex,
    ): List<String> {
        val personal = personalWordsFor(subtype)
        val learned = learnedSnapshotFor(subtype)
        if (personal.isEmpty() && (learned == null || learned.isEmpty)) return emptyList()
        val folded = index.fold(word)
        val minScore = WordLearningGate.scoreFloorFor(WordLearningGate.SIGHTINGS_FOR_SUGGESTIONS)
        val out = LinkedHashSet<String>()
        for (edit in EditDistance.edits1(folded, index.alphabet)) {
            if (edit == folded) continue
            personal[edit]?.let { out.add(it) }
            learned?.wordForKey(edit, minScore)?.let { out.add(it) }
            if (out.size >= CORRECTION_MAX) break
        }
        return out.toList()
    }

    /** The learned-word snapshot for [subtype], or null when learning is off / there is no dictionary. */
    private suspend fun learnedSnapshotFor(subtype: Subtype): LearnedSnapshot? =
        dictLangFor(subtype)
            ?.takeIf { prefs.wordLearningIsOn }
            ?.let { lang -> runCatching { LearnedWordsStore.snapshot(appContext, lang) }.getOrNull() }

    private suspend fun isInUserDictionary(word: String, subtype: Subtype): Boolean =
        userVocabularyFor(subtype)?.lowercase?.contains(word.trim().lowercase()) == true

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
        // Housekeeping for the learned vocabulary (issue #318) rides along here: it is the one moment per
        // language change that is already off the typing path and already expected to touch storage.
        if (prefs.wordLearningIsOn) {
            dictLangFor(subtype)?.let { lang ->
                runCatching { LearnedWordsStore.prune(appContext, lang) }
            }
        }
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
        if (trimmed.length <= 1 || !isDictionaryJudgeable(trimmed)) return SpellingResult.validWord()
        val index = lowerIndexFor(subtype)
        // No dictionary for this language (#265): say nothing rather than underline every word of it.
        if (index.freq.isEmpty()) return SpellingResult.unspecified()
        // Known in the active language OR any other configured keyboard language (multilingual, #190).
        if (isKnownWord(trimmed, subtype)) {
            return SpellingResult.validWord()
        }
        // Unknown word → typo, offering the closest dictionary words as corrections (may be empty).
        // Re-rank by the previous word (Tier 2 bigram context) when available.
        val prevWord = precedingWords.lastOrNull()
            ?.takeLastWhile { DictFold.isWordChar(it) || it == '\'' }
            ?.let { index.fold(it) }?.takeIf { it.isNotEmpty() }
        val bigrams = if (prevWord != null) bigramsFor(subtype) else NgramIndex.EMPTY
        val suggestions = correctionsFor(
            trimmed, index, prefixIndexFor(subtype), maxSuggestionCount, allowDistance2 = true,
            bigramContextScore(prevWord, bigrams),
        )
        return SpellingResult.typo(suggestions.toTypedArray())
    }

    // --- Where a word ends (issue #318, round 3) ------------------------------------------------------

    override fun continuesWord(composingWord: String, char: Char): Boolean =
        WordRun.continuesRun(composingWord, char)

    // --- Words a language always capitalises (issue #333) ---------------------------------------------

    /**
     * English writes its first-person pronoun with a capital wherever it stands, and it is the only
     * one-letter word in any of our languages that does.
     *
     * Not expressible in the dictionary, which is where every other capitalisation here comes from
     * (see the noun block in [suggest]): `en.json` stores `i` in lowercase at frequency 254 and has no
     * `I` entry at all — correctly, because a lone lowercase `i` is right in "i.e.", in a roman
     * numeral and in an identifier. The dictionary describes the word; this describes the sentence it
     * stands in, and the caller supplies that context by only asking at a space.
     *
     * Strictly English. Polish `i` means *and*, Italian `i` is a plural article; both are among the
     * commonest words in those languages, and capitalising them would be a visible regression rather
     * than a fix. The language is the active subtype's, which is also the limit of what this can know
     * while multilingual typing (issue #190) has more than one language in play.
     */
    override fun standaloneCapitalization(word: String, subtype: Subtype): String? =
        standaloneCapitalizationIn(word, subtype.primaryLocale.language)

    /**
     * The composing region, widened to keep an e-mail or web address in one piece (issue #318).
     *
     * The break iterator does the work it always did; [WordRun.runStart] then asks how far left the run
     * at the cursor really reaches. Two cases, and the second is the one that is easy to miss: when the
     * text ends on `@` or on a dot inside a domain, the iterator reports no word at all (`WORD_NONE`),
     * which would clear the composing region mid-address and throw away the word the learner is waiting
     * for. So a run found by the backward walk stands on its own, without the iterator's blessing.
     */
    override suspend fun determineLocalComposing(
        subtype: Subtype,
        textBeforeSelection: CharSequence,
        breakIterators: BreakIteratorGroup,
        localLastCommitPosition: Int,
    ): EditorRange {
        val base = super<SuggestionProvider>.determineLocalComposing(
            subtype, textBeforeSelection, breakIterators, localLastCommitPosition,
        )
        val end = textBeforeSelection.length
        val start = WordRun.runStart(textBeforeSelection, if (base.isValid) base.start else end)
        return when {
            base.isValid -> if (start < base.start) EditorRange(start, base.end) else base
            start < end -> EditorRange(start, end)
            else -> base
        }
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
        // Whether Space may swap a fix in *silently* — and nothing more. Every fix below is offered in the
        // strip either way; this only ever decides `isEligibleForAutoCommit` (issue #381). It used to gate
        // the fixes themselves, so switching autocorrect off — which the setting describes as "fix typos
        // when you type a space" — also emptied the strip on every typo: `helwo` showed nothing at all,
        // one letter away from `hello`. The people who turn it off are the ones who fix by tapping.
        val autoCorrectOn = prefs.suggestion.autoCorrect.get()

        // German umlaut/ß restoration (issue #219) runs FIRST, so the correct spelling leads the strip and a
        // non-word is auto-committed even when the ASCII form prefixes real words (fur→für). Because this
        // fills [out], the generic edit-distance autocorrect below stands aside for these words, so a plain
        // substitution never wins over the umlaut form (Madchen→Mädchen, not Machen). Dictionary-driven, so
        // only real words are produced; a validly-typed word is never swapped, only offered (schon→schön).
        // ß-restoration is off for Swiss German (de-CH), which has no ß.
        if (isGermanSubtype(subtype) && word.length >= 3) {
            val allowSharpS = !subtype.primaryLocale.country.equals("CH", ignoreCase = true)
            val variants = germanSpellingVariants(word, allowSharpS).mapNotNull { v ->
                index.freq[v.lowercase()]?.let { f -> Triple(v, f, index.canonical[v.lowercase()] ?: v) }
            }
            if (variants.isNotEmpty()) {
                val prevWord = previousWordOf(content, index)
                val bigrams = if (prevWord != null) bigramsFor(subtype) else NgramIndex.EMPTY
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
                            isEligibleForAutoCommit =
                                i == 0 && autoCorrectOn && !typedIsWord && f >= AUTOCORRECT_MIN_FREQ,
                            sourceProvider = this,
                        ),
                    )
                }
            }
        }

        // Spelling restoration for the folding languages (issue #265, extended to French in #306). The
        // base keyboard carries only ا, ي and the plain letters; أ إ آ ى ک ی hide behind long presses, so
        // people type the bare form and the review that prompted this asked for exactly that to be fixed —
        // "correcting text when there's a writing error", where in Arabic most writing errors *are* these
        // variants. French is the same shape with different letters: é è ê ç à sit behind long presses too,
        // and hote for hôte is the same kind of miss as ان for أن.
        //
        // Because the dictionary was filtered through Hunspell, it holds only correct spellings: ان is not
        // in it, أن is; hote is not, hôte is. So a typed form whose fold key exists but whose own spelling
        // does not is a spelling to restore, and every dictionary word sharing that key is a legitimate
        // reading — أن، إن، آن all go in the strip, most frequent first, rather than the keyboard guessing
        // which was meant.
        //
        // **This block is what keeps the fold from costing more than it gives**, and the French case shows
        // why it cannot be skipped: isKnownWord answers on the fold key, so hote counts as known, and the
        // correction path below — which is gated on !isKnown — never sees it. Without this, hôte would
        // appear only as an ordinary prefix completion and never be committed automatically, exactly the
        // failure the German noun block further down exists to undo.
        //
        // Correctly spelled words are untouched by construction: `forms.none { it == word }` is false for
        // ou, a, cote and every other word that is itself in the dictionary, so the block never fires on
        // them. It only ever offers something for a spelling the language does not have.
        //
        // Length 2 rather than the 3 the German and apostrophe blocks use: Arabic's most-written words are
        // two letters (من، في، ما), and فى → في is exactly the fix being asked for. It suits French as
        // well — ca → ça is worth having, while ou and a are real words and never reach here.
        if (DictFold.hasNonTrivialFold(index.lang) && word.length >= 2) {
            val key = index.fold(word)
            val forms = index.formsOf(key)
            if (forms.isNotEmpty() && forms.none { it == word }) {
                // Keep the typed spelling tappable and left-most so the restoration can be bypassed (#150).
                out[TYPED_WORD_KEY + key] = WordSuggestionCandidate(
                    text = word, confidence = 1.0, isEligibleForAutoCommit = false, sourceProvider = this,
                )
                // Each spelling reports its own frequency, not the key's — they are ordered by it, so
                // showing them all at the most frequent one's confidence would flatten that order away.
                val data = wordDataFor(subtype)
                forms.forEachIndexed { i, form ->
                    val freq = data[form] ?: index.freq[key] ?: 0
                    out.putIfAbsent(
                        form.lowercase(),
                        WordSuggestionCandidate(
                            text = cased(form),
                            confidence = freq / 255.0,
                            // Only the most frequent spelling may be swapped in silently, and only if it
                            // is common enough to be worth overriding what was actually typed.
                            isEligibleForAutoCommit =
                                i == 0 && autoCorrectOn && freq >= AUTOCORRECT_MIN_FREQ,
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
        //
        // French gets nothing at all out of that loop and needs the most: elisions are 4 % of everything
        // written in the language, and the word list holds 13 apostrophe entries in 68,605 words, because
        // the generator's Hunspell pass splits on the apostrophe and keeps only what follows it. So the
        // forms are rebuilt from a prefix and a known suffix — and rebuilding is exactly as able to produce
        // "n'on" from "non" as "j'aime" from "jaime", since "on" is a word and `n'` is a prefix. The corpus
        // is what tells the two apart, and it decides both whether a form is offered at all and whether it
        // may be taken silently; [ElisionEvidence] carries the measurements.
        if (word.length >= 3 && word.none { it == '\'' || it == '’' }) {
            val typedFreq = index.freq[index.fold(word)] ?: 0
            // Corpus key → its frequency on the dictionary's 128..255 scale and the spelling to show.
            // Insertion order is the order they reach the strip.
            val forms = LinkedHashMap<String, Pair<Int, String>>()
            (1 until word.length)
                .map { word.substring(0, it) + "'" + word.substring(it) }
                .mapNotNull { v -> index.fold(v).let { k -> index.freq[k]?.let { f -> f to (index.canonical[k] ?: v) } } }
                .filter { it.first > typedFreq }
                .sortedByDescending { it.first }
                .forEach { (freq, canonical) ->
                    forms.putIfAbsent(ElisionEvidence.key(index.fold(canonical)), freq to canonical)
                }

            // French elisions, rebuilt because the word list does not hold them, and kept only when the
            // corpus is on record as writing them. The frequency shown is the suffix's — the full form has
            // none of its own — which is also what orders them among themselves.
            val corpus = elisionsFor(subtype)
            var rebuilt = false
            if (corpus.isNotEmpty()) {
                frenchElisionSplits(word)
                    .mapNotNull { (prefix, suffix) ->
                        val suffixKey = index.fold(suffix)
                        val canonical = index.canonical[suffixKey] ?: return@mapNotNull null
                        val display = "$prefix'$canonical"
                        val key = ElisionEvidence.key(index.fold(display))
                        if (ElisionEvidence.isAttested(corpus, key)) Triple(key, index.freq[suffixKey] ?: 0, display)
                        else null
                    }
                    .sortedByDescending { it.second }
                    .forEach { (key, freq, display) ->
                        if (forms.putIfAbsent(key, freq to display) == null) rebuilt = true
                    }
            }

            // What may be swapped in silently. Only a single unambiguous reading, and only when the corpus
            // is lopsided enough to say the apostrophe-less spelling was a slip rather than a word
            // ([ElisionEvidence.DOMINANCE]). Every other language stays tap-only, because "ill", "well" and
            // "its" are exactly as common as the contractions they would be rewritten into.
            val autoCommitKey = forms.keys.singleOrNull()?.takeIf {
                autoCorrectOn && index.lang == ELISION_LANG &&
                    ElisionEvidence.mayReplace(corpus, ElisionEvidence.key(index.fold(word)), it)
            }
            if (rebuilt || autoCommitKey != null) {
                // Keep the typed spelling tappable and left-most, so an elision can be refused before it is
                // taken and a real word is never pushed out of the strip by one (issue #150). Under its own
                // plain key, like the German restoration above, so the completion walk does not add it twice.
                out.putIfAbsent(
                    word.lowercase(),
                    WordSuggestionCandidate(
                        text = word, confidence = 1.0, isEligibleForAutoCommit = false, sourceProvider = this,
                    ),
                )
            }
            forms.forEach { (key, entry) ->
                val (freq, canonical) = entry
                // English "I" contractions are stored lowercase in the dictionary; show them capitalised.
                val display = if (canonical.startsWith("i'")) "I" + canonical.substring(1) else cased(canonical)
                out.putIfAbsent(
                    display.lowercase(),
                    WordSuggestionCandidate(
                        text = display, confidence = freq / 255.0,
                        isEligibleForAutoCommit = key == autoCommitKey, sourceProvider = this,
                    ),
                )
            }
        }

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
        if (prefs.correction.autoCapitalization.get() && word.length >= 2 && word.none { it.isUpperCase() }) {
            val lower = index.fold(word)
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
                    // Not when a restoration above already claimed the slot. This rule carries no
                    // frequency threshold of its own — the dictionary's capitalisation *is* the evidence,
                    // which is the right weight for a German noun and the wrong one for a name that
                    // happens to spell an elision: fr.json holds `Jaime`, so `jaime` was capitalised into
                    // somebody's name rather than restored to `j'aime`, which the corpus attests 450 times
                    // and the name not at all. First claim wins, and the apostrophe block's is the one
                    // backed by a measurement.
                    isEligibleForAutoCommit = autoCorrectOn && out.values.none { it.isEligibleForAutoCommit },
                    sourceProvider = this,
                )
            }
        }

        // Whether the composed word is valid in any of the user's keyboard languages (multilingual, #190);
        // computed up front because it also decides whether to reserve strip slots for spelling fixes.
        val isKnown = isKnownWord(word, subtype)
        // Whether this word is a candidate for a spelling fix at all. One value rather than the same
        // conditions written twice, because the block that corrects and the slots reserved *for* correcting
        // have to agree — the digit rule (issue #309) was easy to add to one of them and forget in the other.
        val mayCorrect = !isKnown && word.length >= 3 && isDictionaryJudgeable(word)
        // Reserve a few slots for edit-distance corrections so a typo's fix isn't crowded out by prefix
        // completions of that typo (issue #212). Only when we'd actually correct.
        val completionCap = if (mayCorrect) {
            (maxCandidateCount - CORRECTION_RESERVE).coerceAtLeast(1)
        } else {
            maxCandidateCount
        }

        // The learned vocabulary, read once: the strip needs it below, and the ordering of the personal
        // words needs it right here.
        val learnedSnapshot = learnedSnapshotFor(subtype)

        // The user's own words that extend what is being typed. They used to go into the strip right here,
        // ahead of everything the dictionary had to offer, so a single typed letter put a contact's surname
        // in front of the word everybody writes (issue #264). They are merged into the ranked walk below
        // instead, at USER_DICTIONARY_RANK_FREQ — which means they surface exactly when the prefix has
        // narrowed the common words away, and never before.
        //
        // They all share that one rank, so the order *among* them used to be whatever the database
        // returned — which is how a name typed every day could sit behind one typed twice (issue #318,
        // round 3). Sorting by the sighting count fixes that without moving the band: nothing changes
        // relative to the dictionary, only the user's own words are put in the order they earned. A word
        // typed into the dictionary by hand has no count and stays at the front, because teaching a word
        // deliberately still outranks anything we merely noticed.
        val userVocabulary = userVocabularyFor(subtype)
        val foldedWord = index.fold(word)
        val personal = userVocabulary?.words.orEmpty()
            .filter { (folded, _) -> folded.startsWith(foldedWord) }
            .map { (_, stored) -> stored }
            .distinctBy { it.lowercase() }
            .sortedByDescending { text ->
                val score = learnedSnapshot?.scoreOfKey(index.fold(text)) ?: 0.0
                if (score > 0.0) score else Double.MAX_VALUE
            }

        // What the user stored behind this exact word as a *shortcut* — an e-mail address behind "mail",
        // say. Deliberately exempt from the prefix filter above and offered first, because an expansion
        // is the opposite of a completion: it looks nothing like what was typed, and typing the shortcut
        // in full is as deliberate as a user gets. Never auto-committed — "mail" is also an ordinary word,
        // and space must not swap it for an address in the middle of a sentence.
        val shortcutExpansions = userVocabulary?.shortcuts?.get(word.lowercase()).orEmpty()
        for (expansion in shortcutExpansions) {
            if (out.size >= maxCandidateCount) break
            out.putIfAbsent(
                expansion.lowercase(),
                WordSuggestionCandidate(
                    text = expansion,
                    confidence = USER_DICTIONARY_RANK_FREQ / 255.0,
                    isEligibleForAutoCommit = false,
                    sourceProvider = this,
                    isLearned = true,
                ),
            )
        }
        var personalTaken = 0

        // The words this keyboard picked up on its own (issue #318). Only from the second sighting — one
        // is remembered and nothing more — and always marked, so the strip can say where they came from.
        // A promoted word arrives through [personal] above instead, but is still marked here: it is no
        // less the user's word for having graduated into the dictionary.
        val learned = learnedSnapshot
            ?.entriesStartingWith(
                prefix = index.fold(word),
                minScore = WordLearningGate.scoreFloorFor(WordLearningGate.SIGHTINGS_FOR_SUGGESTIONS),
                limit = LEARNED_MAX,
            )
            .orEmpty()
        var learnedTaken = 0
        fun isLearnedWord(text: String): Boolean =
            learnedSnapshot != null && learnedSnapshot.scoreOfKey(index.fold(text)) > 0.0

        /** Puts the personal words in as soon as the dictionary walk has dropped to [freq] or below. */
        fun addPersonalDownTo(freq: Int) {
            while (personalTaken < personal.size && USER_DICTIONARY_RANK_FREQ >= freq && out.size < completionCap) {
                val text = personal[personalTaken++]
                out.putIfAbsent(
                    text.lowercase(),
                    WordSuggestionCandidate(
                        text = text,
                        confidence = USER_DICTIONARY_RANK_FREQ / 255.0,
                        sourceProvider = this,
                        isLearned = isLearnedWord(text),
                    ),
                )
            }
        }

        /**
         * The same for the words picked up automatically — but each at its own rank rather than at one
         * shared band, so how often a word has been typed decides where it lands (issue #318, round 3).
         * The list arrives sorted by score, so the ranks only fall and the first one that is too low ends
         * the run.
         */
        fun addLearnedDownTo(freq: Int) {
            while (learnedTaken < learned.size && out.size < completionCap) {
                val (stored, score) = learned[learnedTaken]
                val rank = learnedRankFor(score)
                if (rank < freq) break
                learnedTaken++
                val text = cased(stored)
                out.putIfAbsent(
                    text.lowercase(),
                    WordSuggestionCandidate(
                        text = text,
                        confidence = rank / 255.0,
                        sourceProvider = this,
                        isLearned = true,
                    ),
                )
            }
        }

        // The completions the sentence expects, ahead of the ones the language merely makes common
        // (issue #334, his §3.5 and §3.9). "brand ne" offers `new` before `near`, because `new` is what
        // follows `brand`; without a previous word this block is skipped and the strip is what it was.
        //
        // Promotion rather than re-sorting, and that distinction is the whole design: the walk below
        // interleaves the personal and learned words at rank boundaries *while it runs*, and the typed
        // word has to stay tappable (issue #150). Re-sorting the result would quietly break both. These
        // go in first and everything underneath keeps the order it has today, deduplicating through the
        // same `putIfAbsent`.
        //
        // None of them may be auto-committed. The space bar takes the first *eligible* candidate, not the
        // first one, so a claim about the sentence can lead the strip without ever rewriting a half-typed
        // word on its own.
        //
        // Restricted to words the dictionary knows, and that is not cosmetic: the corrector below reads
        // `out.isNotEmpty()` as "the prefix extends something", and that flag decides whether a silent
        // auto-correction is allowed at all. A continuation that only the pair table knows would set the
        // flag for a prefix the walk finds nothing for, and quietly make autocorrect more timid in a case
        // nobody measured. Keeping the promoted set a subset of what the walk could produce leaves that
        // decision exactly where it was.
        val contextWords = previousWordsOf(content, index, 2)
        val foldedTyped = index.fold(word)
        val ctxPrevWord = contextWords.lastOrNull()
        if (ctxPrevWord != null && foldedTyped.isNotEmpty()) {
            val ctxBigrams = bigramsFor(subtype)
            val ctxTrigrams = trigramsFor(subtype)
            // Two words of evidence outrank one, but only when the exact triple is common enough to be
            // a fact about the language rather than an accident of the corpus.
            val deep = if (contextWords.size >= 2 && !ctxTrigrams.isEmpty) {
                val deepPrefix = contextWords.joinToString(" ")
                ctxTrigrams.topContinuations(deepPrefix, CONTEXT_COMPLETION_SCAN)
                    .firstOrNull { it.startsWith(foldedTyped) && index.freq.containsKey(it) }
                    ?.takeIf { ctxTrigrams.countOf("$deepPrefix $it") >= IN_WORD_PREDICTION_MIN_COUNT }
            } else {
                null
            }
            val blessed = if (ctxBigrams.isEmpty) {
                emptyList()
            } else {
                ctxBigrams.topContinuations(ctxPrevWord, CONTEXT_COMPLETION_SCAN)
                    .filter { it.startsWith(foldedTyped) && index.freq.containsKey(it) }
            }
            for (continuation in orderContextCompletions(deep, blessed, CONTEXT_COMPLETION_MAX)) {
                if (out.size >= completionCap) break
                val text = cased(index.canonical[continuation] ?: continuation)
                out.putIfAbsent(
                    text.lowercase(),
                    WordSuggestionCandidate(
                        text = text,
                        confidence = (index.freq[continuation] ?: 0) / 255.0,
                        isEligibleForAutoCommit = false,
                        sourceProvider = this,
                    ),
                )
            }
        }

        // A checkpoint for the next keystroke (issue #381): NlpManager cancels this computation as soon as
        // one arrives, but the stages below are plain loops that would otherwise run to the end regardless.
        currentCoroutineContext().ensureActive()

        val data = wordDataFor(subtype)
        // Prefix matching happens on the stored spellings, so an Arabic writer typing ان or a French
        // writer typing ho would miss أنا and hôte. Where the fold changes the lookup spelling, compare
        // the precomputed fold keys instead; everywhere else this is the same comparison it always was.
        val ranked = rankedWordsFor(subtype)
        val rankedKeys = rankedFoldKeysFor(subtype, ranked)
        val foldedPrefix = if (rankedKeys != null) index.fold(word) else ""

        /** Offers the dictionary word at [rank] if it extends the prefix; false once the strip is full. */
        fun offerCompletion(rank: Int, dictWord: String): Boolean {
            if (out.size >= completionCap) return false
            val matches = if (rankedKeys != null) {
                rankedKeys[rank].startsWith(foldedPrefix)
            } else {
                dictWord.startsWith(word, ignoreCase = true)
            }
            if (!matches) return true
            val freq = data[dictWord] ?: 0
            addPersonalDownTo(freq)
            addLearnedDownTo(freq)
            if (out.size >= completionCap) return false
            val text = cased(dictWord)
            out.putIfAbsent(
                text.lowercase(),
                WordSuggestionCandidate(
                    text = text,
                    confidence = freq / 255.0,
                    sourceProvider = this,
                ),
            )
            return true
        }

        // A rare prefix — and every prefix with a slip in it is one — matches almost nothing, so walking the
        // ranked list meant reading all of it on every keystroke (issue #381). Its matches are found by
        // binary search instead, then offered in the same rank order. A common prefix keeps the walk: its
        // matches sit among the first few hundred words, and collecting thousands of them would cost more.
        val rareMatches = prefixOrderFor(subtype, ranked, rankedKeys)
            ?.ranksStartingWith(if (rankedKeys != null) foldedPrefix else word, limit = DENSE_PREFIX_MATCHES)
        if (rareMatches != null) {
            for (rank in rareMatches) if (!offerCompletion(rank, ranked[rank])) break
        } else {
            for ((rank, dictWord) in ranked.withIndex()) if (!offerCompletion(rank, dictWord)) break
        }
        // Nothing (or too little) in the dictionary extends this prefix: the user's own words are all that
        // is left to offer, so they go in rather than being dropped for want of a rank to sit at.
        addPersonalDownTo(0)
        addLearnedDownTo(0)

        // Spelling fixes for an unknown word — now surfaced even when there are prefix completions of the
        // typo (issue #212), so a missing apostrophe/hyphen or other slip is offered (whats → what's)
        // instead of only word extensions. Auto-commit stays conservative: only the top distance-1 fix and
        // only when nothing else already filled the strip, so intentional input and words-in-progress aren't
        // swapped. Distance 2 is a suggestions-only fallback when distance 1 finds nothing (too uncertain to
        // swap in silently). #190: never correct a word valid in any configured language, #309: never one
        // that carries a digit.
        if (mayCorrect) {
            currentCoroutineContext().ensureActive()
            val hadCandidatesBefore = out.isNotEmpty() // German restoration and/or prefix completions
            val prevWord = previousWordOf(content, index)
            val bigrams = if (prevWord != null) bigramsFor(subtype) else NgramIndex.EMPTY
            val ctx = bigramContextScore(prevWord, bigrams)
            // Preferred: decode from the actual tap positions (issue #242). Null whenever no usable tap
            // evidence exists — hardware keyboard, glide, pasted or dictated text, or a cursor jump that
            // desynced the trace.
            val touchCorrections = touchCorrectionsFor(word, subtype, index, CORRECTION_MAX, ctx)
            // And the reading of the string, always (issue #381). It used to run only when the beam found
            // nothing, so a beam that found *anything* hid it: every tap of `helwo` sits on a neighbour of
            // `growl`, and `hello` — one letter away — never reached the strip. It gets one slot of its own
            // but never the first, so what Space may take is decided exactly as before; distance 2 still
            // runs only where the beam had nothing, because beside a beam it costs much and finds nothing.
            val stringReading = CorrectionReaders.byString(
                folded = index.fold(word),
                freq = index.freq,
                alphabet = index.alphabet,
                prefixIndex = prefixIndexFor(subtype),
                sqDistance = KeyProximityInfo::normSqDistance,
                contextScore = ctx,
                allowDistance2 = touchCorrections == null,
            )
            val distance1Empty = stringReading.distance1Empty
            val corrections = CorrectionReaders.merge(
                touch = touchCorrections?.words.orEmpty(),
                text = stringReading.words.map { index.canonical[it] ?: it },
                maxCount = CORRECTION_MAX,
            )
            // And the longer words a half-typed word with a slip in it was probably on its way to (issue
            // #381): `dixt` is a prefix of nothing, so the walk above has nothing for `dictionary`, and every
            // fix is a whole word. Worked in behind the first fix, never eligible — an offer about a word the
            // user has not finished — and added after `hadCandidatesBefore` was read, so they cannot make
            // the corrector more timid either.
            currentCoroutineContext().ensureActive()
            val offers = CorrectionReaders.withCompletions(
                fixes = corrections,
                completions = completionsFor(word, subtype, index, maxCandidateCount, ctx),
                freqOf = { index.freq[index.fold(it)] ?: 0 },
            )
            // What may be swapped in *silently* (the strip always shows everything either way).
            val topTouchCost = touchCorrections?.topCost
            // A restoration above (umlaut, spelling, apostrophe) that already claimed the slot keeps it:
            // [NlpManager.getAutoCommitCandidate] takes the first eligible candidate, so a second one is
            // never committed — it is only drawn bold, which is a lie about what Space is going to take.
            // The dictionary fixes below already follow this rule against the personal ones.
            val slotClaimed = out.values.any { it.isEligibleForAutoCommit }
            val allowAutoCommit = autoCorrectOn && !slotClaimed && when {
                // Decoded from the taps: act only when the fingers really were near that key. This replaces
                // the `hadCandidatesBefore` gate, which suppressed 2.7 % of otherwise correct fixes merely
                // because the typo prefixed some dictionary word — while a bare "a correction exists" rule
                // would rewrite 30 % of correctly typed names.
                topTouchCost != null ->
                    AutoCommitGate.allows(topTouchCost, word.length, prefs.correction.autoCorrectStrength.get())
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
            // The user's own words go first among the corrections. They are not in the dictionary index,
            // so they carry no frequency of their own — they are ranked at the same band they occupy as
            // completions, which is what puts them ahead of a rare dictionary word one edit away.
            val personalFixes = personalCorrectionsFor(word, subtype, index)
            personalFixes.forEachIndexed { i, correction ->
                val text = cased(correction)
                out.putIfAbsent(
                    text.lowercase(),
                    WordSuggestionCandidate(
                        text = text,
                        confidence = USER_DICTIONARY_RANK_FREQ / 255.0,
                        isEligibleForAutoCommit = allowAutoCommit && i == 0,
                        sourceProvider = this,
                        isLearned = true,
                    ),
                )
            }
            val lead = corrections.firstOrNull()
            offers.forEach { correction ->
                val text = cased(correction)
                val freq = index.freq[index.fold(correction)] ?: 0
                out.putIfAbsent(
                    text.lowercase(),
                    WordSuggestionCandidate(
                        text = text,
                        confidence = freq / 255.0,
                        // Only when nothing of the user's own already took the auto-commit slot: two bold
                        // candidates would be a lie about which one space is going to take.
                        isEligibleForAutoCommit = allowAutoCommit && correction == lead &&
                            personalFixes.isEmpty() && freq >= AUTOCORRECT_MIN_FREQ,
                        sourceProvider = this,
                    ),
                )
            }
        }

        return out.values.take(maxCandidateCount)
    }

    // --- Word learning (issue #318) ------------------------------------------------------------------

    /**
     * The best word the beam reads out of [points] for [word], with the excess tap distance it cost, or
     * null when there is no usable evidence.
     *
     * Deliberately *not* [touchCorrectionsFor]: that one exists to produce a ranked correction list and
     * mixes in length-changing edit-distance candidates that carry no positional evidence at all. Here
     * only the spatial reading is wanted, and its cost has to mean "how far were the fingers off", which
     * a synthesised edit-distance entry cannot answer.
     */
    private suspend fun beamReadingOf(
        word: String,
        subtype: Subtype,
        index: LowerIndex,
        points: FloatArray,
    ): Pair<String, Float>? {
        val layout = KeyProximityInfo.snapshot() ?: return null
        val prefixIndex = prefixIndexFor(subtype) ?: return null
        val beam = TouchBeamDecoder.decode(points, word, prefixIndex, layout, CorrectionReaders.BEAM_CANDIDATES)
        var best: TouchBeamDecoder.Candidate? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (candidate in beam) {
            val freq = index.freq[candidate.word] ?: continue
            val score = TouchScoring.score(freq, candidate.cost, 0.0)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best?.let { index.canonical[it.word].orEmpty().ifEmpty { it.word } to it.cost }
    }

    override suspend fun learnTypedWord(
        subtype: Subtype,
        word: String,
        origin: WordOrigin,
        tapPoints: FloatArray?,
        isPrivateSession: Boolean,
        weight: Int,
        trustedByUser: Boolean,
    ): LearnOutcome {
        val enabled = prefs.wordLearningIsOn
        // A sentence that ends on an address hands over `jannis@example.com.` — the dot stays inside the
        // run while it is being typed (issue #318) and has nothing to do with the address afterwards.
        val trimmed = WordRun.trimTrailingPunctuation(word.trim())
        // Cheap refusals first — the common case by far is a word the dictionary already knows, and this
        // runs at every word boundary.
        if (!enabled || origin != WordOrigin.TYPED || isPrivateSession) return LearnOutcome.NOTHING
        if (!WordLearningGate.isLearnableForm(trimmed)) return LearnOutcome.NOTHING
        val lang = dictLangFor(subtype) ?: return LearnOutcome.NOTHING
        val index = lowerIndexFor(subtype)
        val folded = index.fold(trimmed)
        // A word the vocabulary already holds is normally none of our business — with one exception: the
        // ones we put into the personal dictionary ourselves. Counting used to stop at promotion (and
        // [isKnownWord] consults the personal dictionary, so it stopped here), which left a name typed
        // every day indistinguishable from one typed three times in March — and the strip with nothing to
        // order the user's own words by (issue #318, round 3). Those sightings are that record, so they
        // keep accruing. The cheap in-memory test comes first: the personal-dictionary query behind
        // [isInUserDictionary] must not run for every ordinary word.
        val ourPromotedWord = if (!isKnownWord(trimmed, subtype)) {
            false
        } else {
            val ours = (learnedSnapshotFor(subtype)?.scoreOfKey(folded) ?: 0.0) > 0.0 &&
                isInUserDictionary(trimmed, subtype)
            if (!ours) return LearnOutcome.NOTHING
            true
        }

        // An address gets no slip reasoning, and saying so out loud beats letting it run: neither witness
        // can see anything there. No dictionary word sits one edit from an eighteen-character string, and
        // the beam has no candidate to offer, so the test would answer "not a slip" for a mis-typed
        // address just as confidently as for a correct one. A gate that always says yes is not a gate —
        // what actually protects the vocabulary here is the ladder: an address typed wrong once sits at
        // one sighting, invisible to the strip, and decays away.
        // A promoted word does not face the gate again either: it passed once, it is in the user's
        // dictionary now, and re-judging it would let a stray beam reading silently drop the very
        // sightings that are supposed to record how much it is used.
        val slip = if (trustedByUser || ourPromotedWord || WordRun.isAddressLike(trimmed)) {
            false
        } else {
            val reading = tapPoints?.let { beamReadingOf(trimmed, subtype, index, it) }
            WordLearningGate.looksLikeASlip(
                typedWord = trimmed,
                hadTapEvidence = tapPoints != null,
                beamCorrection = reading?.first,
                beamCost = reading?.second,
                nearestKnownFreq = EditDistance.nearestKnownFrequency(folded, index.alphabet, index.freq),
            )
        }
        val mayLearn = WordLearningGate.shouldLearn(
            enabled = true,
            isPrivateField = false,
            origin = origin,
            word = trimmed,
            isKnownWord = false,
            cheapCorrectionExists = slip,
        )
        if (!mayLearn) return LearnOutcome.NOTHING

        val entry = LearnedWordsStore.note(appContext, trimmed, folded, lang, weight)
            ?: return LearnOutcome.NOTHING
        val score = WordLearningGate.decayedScore(entry.count, entry.lastUsed, System.currentTimeMillis() / 1000L)
        return LearnOutcome(
            learned = true,
            word = trimmed,
            lang = lang,
            entryId = entry.id,
            readyForPromotion = !entry.promoted &&
                WordLearningGate.stageOf(score) == WordLearningGate.Stage.PROMOTED,
        )
    }

    override suspend fun learnPickedWord(subtype: Subtype, word: String): LearnOutcome {
        if (!prefs.wordLearningIsOn) return LearnOutcome.NOTHING
        val trimmed = WordRun.trimTrailingPunctuation(word.trim())
        if (trimmed.isEmpty()) return LearnOutcome.NOTHING
        val lang = dictLangFor(subtype) ?: return LearnOutcome.NOTHING
        val folded = lowerIndexFor(subtype).fold(trimmed)
        // Only a word this store already holds. A pick carries no tap evidence, so the entry test has
        // nothing to judge — letting one in here would be a door around the gate rather than a sighting
        // of something already through it.
        if ((learnedSnapshotFor(subtype)?.scoreOfKey(folded) ?: 0.0) <= 0.0) return LearnOutcome.NOTHING
        val entry = LearnedWordsStore.note(appContext, trimmed, folded, lang) ?: return LearnOutcome.NOTHING
        val score = WordLearningGate.decayedScore(entry.count, entry.lastUsed, System.currentTimeMillis() / 1000L)
        return LearnOutcome(
            learned = true,
            word = trimmed,
            lang = lang,
            entryId = entry.id,
            readyForPromotion = !entry.promoted &&
                WordLearningGate.stageOf(score) == WordLearningGate.Stage.PROMOTED,
        )
    }

    override suspend fun forgetLearnedWord(subtype: Subtype, word: String): Boolean {
        val lang = dictLangFor(subtype) ?: return false
        return LearnedWordsStore.forgetWord(appContext, word.trim(), lang)?.promoted == true
    }

    override suspend fun learnWordPair(subtype: Subtype, previousWord: String, word: String) {
        if (!prefs.wordLearningIsOn) return
        val lang = dictLangFor(subtype) ?: return
        val index = lowerIndexFor(subtype)
        val prev = index.fold(previousWord.trim())
        val next = word.trim()
        if (prev.isEmpty() || next.isEmpty()) return
        LearnedWordsStore.noteBigram(appContext, prev, next, lang)
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

    /**
     * The vocabulary glide typing builds its index from: the bundled dictionary plus the words the user added
     * themselves (issue #263).
     *
     * Those two used to disagree with [isKnownWord], which does consult the personal dictionary — so a word
     * the user added was safe from autocorrect but could not be swiped, which is a strange thing to have to
     * explain. Read fresh from the database on every call, because this runs once per index build and the
     * personal dictionary is the one part of the vocabulary that changes while the app is running.
     */
    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        val bundled = wordDataFor(subtype)
        val personal = userGlideWords(subtype)
        // Remember them for getFrequencyForWord, which is asked about these very words moments later.
        glideUserWords = subtype.primaryLocale.localeTag() to personal
        flogDebug { "glide vocabulary (${subtype.primaryLocale.localeTag()}): ${bundled.size} + ${personal.size} personal" }
        if (personal.isEmpty()) return bundled.keys.toList()
        return buildList(bundled.size + personal.size) {
            addAll(bundled.keys)
            for (word in personal.keys) if (!bundled.containsKey(word)) add(word)
        }
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        val bundled = wordDataFor(subtype)[word]
        if (bundled != null) return bundled / 255.0
        val (locale, personal) = glideUserWords ?: return 0.0
        if (locale != subtype.primaryLocale.localeTag()) return 0.0
        return (personal[word] ?: 0) / 255.0
    }

    /**
     * The user's own words for [subtype], each at [USER_DICTIONARY_RANK_FREQ].
     *
     * Blank entries are dropped: the glide pruner indexes a word by its first and last character and would
     * throw on an empty one, and the system dictionary is not ours to trust for that.
     */
    private fun userGlideWords(subtype: Subtype): Map<String, Int> = runCatching {
        val dm = DictionaryManager.default()
        dm.loadUserDictionariesIfNecessary()
        buildMap {
            for (entry in dm.queryAllUserWords(subtype.primaryLocale)) {
                val word = entry.word.trim()
                if (word.isNotEmpty()) put(word, USER_DICTIONARY_RANK_FREQ)
            }
        }
    }.getOrDefault(emptyMap())

    override suspend fun destroy() {
        // Here we have the chance to de-allocate memory and finish our work. However this might never be called if
        // the app process is killed (which will most likely always be the case).
    }
}
