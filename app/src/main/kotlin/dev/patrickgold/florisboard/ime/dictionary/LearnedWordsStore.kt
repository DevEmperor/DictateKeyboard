/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.dictionary

import android.content.Context
import dev.patrickgold.florisboard.ime.nlp.latin.WordLearningGate
import kotlinx.coroutines.flow.Flow
import org.florisboard.lib.kotlin.guardedByLock

/**
 * The one read/write path for the learned vocabulary (issue #318), owning the database and the caches
 * the typing path reads.
 *
 * Split from [LearnedWords] so this file holds no policy: *whether* a word may be learned is
 * [WordLearningGate], *when* that question is asked is the keyboard, and this only stores the answer.
 * The one piece of judgement that does live here is the pruning, because it is about the size of a file
 * rather than about vocabulary.
 *
 * ### Caching
 *
 * Two caches, both keyed by language and both dropped on any write:
 *
 *  - [snapshot] — the words, sorted and decayed, for the suggestion strip.
 *  - [bigrams] — `"prev word" → count`, shaped exactly like the static bigram tables so next-word
 *    prediction can consult both without knowing they came from different places.
 *
 * Dropping the whole cache on a write is deliberately blunt. Writes happen once per finished word;
 * rebuilding is a sorted copy of a few thousand strings, and the alternative — maintaining the sorted
 * arrays incrementally — is the kind of cleverness that silently disagrees with the database after the
 * third edge case.
 */
object LearnedWordsStore {

    /**
     * Words kept per language before the least-valuable are dropped.
     *
     * Not a memory limit — ten thousand short strings is nothing. It is a limit on how far a store can
     * drift from being a *personal vocabulary*: past this point it is a log of everything ever typed,
     * and the words that matter are buried among one-off noise the decay has not caught up with yet.
     */
    const val MAX_WORDS_PER_LANG = 10_000

    /** Pairs kept per language. Higher than the word cap because pairs are inherently sparser. */
    const val MAX_BIGRAMS_PER_LANG = 20_000

    /** Below this decayed score a word is only kept if it is recent; see [prune]. */
    private const val PRUNE_MIN_SCORE = 0.5

    /** …and "recent" is this many days. */
    private const val PRUNE_MAX_AGE_DAYS = 90

    @Volatile
    private var instance: LearnedWordsDatabase? = null

    private val snapshots = guardedByLock { mutableMapOf<String, LearnedSnapshot>() }
    private val bigramCache = guardedByLock { mutableMapOf<String, Map<String, Int>>() }

    private fun db(context: Context): LearnedWordsDatabase =
        instance ?: synchronized(this) {
            instance ?: LearnedWordsDatabase.new(context.applicationContext).also { instance = it }
        }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    private suspend fun invalidate(lang: String? = null) {
        snapshots.withLock { cache -> if (lang == null) cache.clear() else cache.remove(lang) }
        bigramCache.withLock { cache -> if (lang == null) cache.clear() else cache.remove(lang) }
    }

    // ── Reading (the typing path) ────────────────────────────────────────────────────────────────

    /** The decayed, sorted view of [lang]'s learned words. Cached; cheap to call per keystroke. */
    suspend fun snapshot(context: Context, lang: String): LearnedSnapshot =
        snapshots.withLock { cache ->
            cache.getOrPut(lang) {
                LearnedSnapshot.of(lang, db(context).dao().wordsFor(lang), nowSeconds())
            }
        }

    /**
     * `"prev word" → count` for [lang], in the same shape as the downloaded bigram tables so the
     * prediction code can read one alongside the other.
     */
    suspend fun bigrams(context: Context, lang: String): Map<String, Int> =
        bigramCache.withLock { cache ->
            cache.getOrPut(lang) {
                db(context).dao().bigramsFor(lang).associate { "${it.prev} ${it.word}" to it.count }
            }
        }

    // ── Writing ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Records one sighting of [word] and returns the row as it now stands, or null if nothing was
     * written. [key] is the caller's folded lookup form; [weight] is normally 1 and
     * [WordLearningGate.REJECTION_WEIGHT] when the user took an auto-correction back.
     */
    suspend fun note(
        context: Context,
        word: String,
        key: String,
        lang: String,
        weight: Int = 1,
    ): LearnedWordEntry? {
        val entry = db(context).dao().noteWord(word, key, lang, weight, nowSeconds())
        invalidate(lang)
        return entry
    }

    /** Takes [weight] sightings back — the user deleted the word again. Promoted rows are untouched. */
    suspend fun demote(context: Context, word: String, lang: String, weight: Int = 1) {
        db(context).dao().demoteWord(word, lang, weight, nowSeconds())
        invalidate(lang)
    }

    /** Records that [word] followed [prev] (both folded by the caller for [prev]). */
    suspend fun noteBigram(context: Context, prev: String, word: String, lang: String) {
        db(context).dao().noteBigram(prev, word, lang, nowSeconds())
        invalidate(lang)
    }

    /** Marks [id] as living in the personal dictionary from now on (or no longer). */
    suspend fun setPromoted(context: Context, id: Long, promoted: Boolean, lang: String) {
        db(context).dao().setPromoted(id, promoted)
        invalidate(lang)
    }

    // ── The settings screen ──────────────────────────────────────────────────────────────────────

    /** Every learned word, promoted first then by weight — the list the user inspects and edits. */
    fun flow(context: Context): Flow<List<LearnedWordEntry>> = db(context).dao().allAsFlow()

    suspend fun forget(context: Context, entry: LearnedWordEntry) {
        db(context).dao().deleteWord(entry.id)
        invalidate(entry.lang)
    }

    suspend fun forgetAll(context: Context) {
        db(context).dao().deleteAllWords()
        db(context).dao().deleteAllBigrams()
        invalidate()
    }

    // ── Housekeeping ─────────────────────────────────────────────────────────────────────────────

    /**
     * Drops what is no longer worth keeping for [lang]: un-promoted words whose decayed score has fallen
     * away and that have not been seen in months, then whatever still exceeds [MAX_WORDS_PER_LANG],
     * weakest first. Promoted words are never dropped — those are personal-dictionary entries.
     *
     * Called when a language is preloaded, so it costs nothing during typing.
     */
    suspend fun prune(context: Context, lang: String) {
        val dao = db(context).dao()
        val now = nowSeconds()
        val cutoff = now - PRUNE_MAX_AGE_DAYS * 86_400L
        val words = dao.wordsFor(lang)
        val scored = words.filterNot { it.promoted }
            .map { it to WordLearningGate.decayedScore(it.count, it.lastUsed, now) }
        var removed = 0
        for ((entry, score) in scored) {
            if (score < PRUNE_MIN_SCORE && entry.lastUsed < cutoff) {
                dao.deleteWord(entry.id)
                removed++
            }
        }
        val survivors = scored.filterNot { (entry, score) ->
            score < PRUNE_MIN_SCORE && entry.lastUsed < cutoff
        }
        val excess = survivors.size - MAX_WORDS_PER_LANG
        if (excess > 0) {
            for ((entry, _) in survivors.sortedBy { it.second }.take(excess)) {
                dao.deleteWord(entry.id)
                removed++
            }
        }
        val bigrams = dao.bigramsFor(lang)
        val bigramExcess = bigrams.size - MAX_BIGRAMS_PER_LANG
        if (bigramExcess > 0) {
            for (entry in bigrams.sortedWith(compareBy({ it.count }, { it.lastUsed })).take(bigramExcess)) {
                dao.deleteBigram(entry.id)
                removed++
            }
        }
        if (removed > 0) invalidate(lang)
    }

    // ── Backup ───────────────────────────────────────────────────────────────────────────────────

    suspend fun exportWords(context: Context): List<LearnedWordEntry> = db(context).dao().allWords()

    suspend fun exportBigrams(context: Context): List<LearnedBigramEntry> = db(context).dao().allBigrams()

    /**
     * Merges a backup back in: an entry already present keeps the higher count and the more recent
     * sighting rather than being overwritten.
     *
     * Merging rather than replacing because a restore is usually *adding a second device's history to a
     * vocabulary that has meanwhile grown*, and flattening that would be a silent loss the user only
     * notices weeks later when a word they had taught stops being offered.
     */
    suspend fun importAll(
        context: Context,
        words: List<LearnedWordEntry>,
        bigrams: List<LearnedBigramEntry>,
    ) {
        val dao = db(context).dao()
        for (entry in words) {
            val existing = dao.findWord(entry.lang, entry.word)
            if (existing == null) {
                dao.insertWord(entry.copy(id = 0))
            } else {
                dao.setWordCount(
                    existing.id,
                    maxOf(existing.count, entry.count),
                    maxOf(existing.lastUsed, entry.lastUsed),
                )
                if (entry.promoted && !existing.promoted) dao.setPromoted(existing.id, true)
            }
        }
        for (entry in bigrams) {
            val existing = dao.findBigram(entry.lang, entry.prev, entry.word)
            if (existing == null) dao.insertBigram(entry.copy(id = 0)) else dao.bumpBigram(existing.id, entry.lastUsed)
        }
        invalidate()
    }
}
