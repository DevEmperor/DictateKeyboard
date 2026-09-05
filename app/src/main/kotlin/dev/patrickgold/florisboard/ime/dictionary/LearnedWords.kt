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
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import dev.patrickgold.florisboard.ime.nlp.latin.WordLearningGate
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

internal const val LEARNED_WORDS_TABLE = "learned_words"
internal const val LEARNED_BIGRAMS_TABLE = "learned_bigrams"

/**
 * A word the user has typed that no dictionary knew, with how often and how recently (issue #318).
 *
 * Deliberately a store of its own rather than a table inside [FlorisUserDictionaryDatabase]. That
 * database mirrors Android's `UserDictionary.Words` schema exactly — [SystemUserDictionaryDatabase]
 * implements the same DAO against the system provider — and putting something foreign in it would break
 * the symmetry the two share. Keeping them apart also means "forget everything you learned" is one file
 * deleted, and that the user's own hand-curated list carries no migration risk from this feature.
 *
 * The two stores meet exactly once: when a word reaches [WordLearningGate.SIGHTINGS_FOR_PROMOTION] it is
 * copied into the personal dictionary and [promoted] is set here, so the row survives as the record of
 * *why* that entry exists and can be un-promoted again if the user deletes it.
 */
@Serializable
@Entity(
    tableName = LEARNED_WORDS_TABLE,
    indices = [
        Index(value = ["lang", "word"], unique = true),
        Index(value = ["lang", "key"]),
    ],
)
data class LearnedWordEntry(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    /** The word exactly as it was typed, including its capitalisation. */
    val word: String,
    /**
     * The case-folded lookup form (`DictFold.foldKey`), stored rather than computed so a prefix query
     * is a range scan on an index instead of a full scan through Kotlin. This is the column the
     * suggestion strip searches on every keystroke.
     */
    val key: String,
    /** Normalised language (`LatinLanguageProvider.normalizeLang`), not a full locale tag: a personal
     * vocabulary must not fall apart across de-DE and de-AT. */
    val lang: String,
    /** Sightings. A word restored by rejecting an auto-correction counts [WordLearningGate.REJECTION_WEIGHT]. */
    val count: Int,
    /** Epoch seconds of the most recent sighting; the input to the decay. */
    val lastUsed: Long,
    /** True once the word has been copied into the personal dictionary. Promoted rows never decay. */
    val promoted: Boolean = false,
)

/**
 * A pair of words the user typed in sequence, both of them typed rather than suggested (issue #318).
 *
 * The reason this exists next to [LearnedWordEntry]: a learned single word only helps while that word is
 * being typed, but a learned *pair* makes the next-word strip personal, which is the part people
 * recognise as the keyboard knowing them. It is read alongside the static bigram tables that already
 * drive next-word prediction, so nothing new has to be built to use it.
 */
@Serializable
@Entity(
    tableName = LEARNED_BIGRAMS_TABLE,
    indices = [
        Index(value = ["lang", "prev", "word"], unique = true),
        Index(value = ["lang", "prev"]),
    ],
)
data class LearnedBigramEntry(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    /** The preceding word, case-folded — the key the prediction looks up. */
    val prev: String,
    /** The following word as typed. */
    val word: String,
    val lang: String,
    val count: Int,
    val lastUsed: Long,
)

@Dao
abstract class LearnedWordsDao {

    // ── Words ────────────────────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM $LEARNED_WORDS_TABLE WHERE lang = :lang")
    abstract suspend fun wordsFor(lang: String): List<LearnedWordEntry>

    @Query("SELECT * FROM $LEARNED_WORDS_TABLE ORDER BY promoted DESC, count DESC, lastUsed DESC")
    abstract fun allAsFlow(): Flow<List<LearnedWordEntry>>

    @Query("SELECT * FROM $LEARNED_WORDS_TABLE")
    abstract suspend fun allWords(): List<LearnedWordEntry>

    @Query("SELECT * FROM $LEARNED_WORDS_TABLE WHERE lang = :lang AND word = :word LIMIT 1")
    abstract suspend fun findWord(lang: String, word: String): LearnedWordEntry?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertWord(entry: LearnedWordEntry): Long

    @Query("UPDATE $LEARNED_WORDS_TABLE SET count = count + :weight, lastUsed = :now WHERE id = :id")
    abstract suspend fun bumpWord(id: Long, weight: Int, now: Long)

    @Query("UPDATE $LEARNED_WORDS_TABLE SET count = :count, lastUsed = :now WHERE id = :id")
    abstract suspend fun setWordCount(id: Long, count: Int, now: Long)

    @Query("UPDATE $LEARNED_WORDS_TABLE SET promoted = :promoted WHERE id = :id")
    abstract suspend fun setPromoted(id: Long, promoted: Boolean)

    @Query("DELETE FROM $LEARNED_WORDS_TABLE WHERE id = :id")
    abstract suspend fun deleteWord(id: Long)

    /** Removes [word] and reports what it was, so a promoted one can also be taken out of the dictionary. */
    @Transaction
    open suspend fun deleteWordByName(lang: String, word: String): LearnedWordEntry? {
        val existing = findWord(lang, word) ?: return null
        deleteWord(existing.id)
        return existing
    }

    @Query("DELETE FROM $LEARNED_WORDS_TABLE")
    abstract suspend fun deleteAllWords()

    /**
     * Records one sighting of [word], inserting it when new. Returns the row as it now stands.
     *
     * A single transaction because two keystroke-driven coroutines can reach the same new word at once,
     * and the unique index would otherwise turn that race into a lost sighting rather than a merged one.
     */
    @Transaction
    open suspend fun noteWord(
        word: String,
        key: String,
        lang: String,
        weight: Int,
        now: Long,
    ): LearnedWordEntry? {
        val existing = findWord(lang, word)
        if (existing != null) {
            bumpWord(existing.id, weight, now)
            return findWord(lang, word)
        }
        insertWord(
            LearnedWordEntry(word = word, key = key, lang = lang, count = weight, lastUsed = now),
        )
        return findWord(lang, word)
    }

    /**
     * Takes [weight] sightings back off [word], deleting the row when nothing is left.
     *
     * Never touches a promoted row: once a word is in the personal dictionary it is the user's, and a
     * backspace over it is about the text rather than about the vocabulary. Removing it there is the
     * long-press, or the settings list.
     */
    @Transaction
    open suspend fun demoteWord(word: String, lang: String, weight: Int, now: Long) {
        val existing = findWord(lang, word) ?: return
        if (existing.promoted) return
        val remaining = existing.count - weight
        if (remaining <= 0) deleteWord(existing.id) else setWordCount(existing.id, remaining, now)
    }

    // ── Bigrams ──────────────────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM $LEARNED_BIGRAMS_TABLE WHERE lang = :lang")
    abstract suspend fun bigramsFor(lang: String): List<LearnedBigramEntry>

    @Query("SELECT * FROM $LEARNED_BIGRAMS_TABLE")
    abstract suspend fun allBigrams(): List<LearnedBigramEntry>

    @Query("SELECT * FROM $LEARNED_BIGRAMS_TABLE WHERE lang = :lang AND prev = :prev AND word = :word LIMIT 1")
    abstract suspend fun findBigram(lang: String, prev: String, word: String): LearnedBigramEntry?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertBigram(entry: LearnedBigramEntry): Long

    @Query("UPDATE $LEARNED_BIGRAMS_TABLE SET count = count + 1, lastUsed = :now WHERE id = :id")
    abstract suspend fun bumpBigram(id: Long, now: Long)

    @Query("DELETE FROM $LEARNED_BIGRAMS_TABLE WHERE id = :id")
    abstract suspend fun deleteBigram(id: Long)

    @Query("DELETE FROM $LEARNED_BIGRAMS_TABLE")
    abstract suspend fun deleteAllBigrams()

    @Transaction
    open suspend fun noteBigram(prev: String, word: String, lang: String, now: Long) {
        val existing = findBigram(lang, prev, word)
        if (existing != null) {
            bumpBigram(existing.id, now)
            return
        }
        insertBigram(LearnedBigramEntry(prev = prev, word = word, lang = lang, count = 1, lastUsed = now))
    }
}

@Database(
    entities = [LearnedWordEntry::class, LearnedBigramEntry::class],
    version = 1,
    exportSchema = true,
)
abstract class LearnedWordsDatabase : RoomDatabase() {
    abstract fun dao(): LearnedWordsDao

    companion object {
        const val DB_FILE_NAME = "dictate_learned_words"

        fun new(context: Context): LearnedWordsDatabase =
            Room.databaseBuilder(context, LearnedWordsDatabase::class.java, DB_FILE_NAME)
                // Only reachable if no migration path exists at all. Losing a learned vocabulary is bad;
                // an input method that crash-loops on open is worse, and the user could not even switch
                // keyboards to fix it. Same trade as the dictation history.
                .fallbackToDestructiveMigration()
                .build()
    }
}

/**
 * An immutable, already-decayed view of one language's learned words, built for the typing path.
 *
 * The suggestion strip asks a question of this on **every keystroke**, so it must not be a database
 * query. The existing personal-dictionary lookup is `LIKE '%word%'` — a full scan that is harmless for
 * fifty hand-added words and would not be for ten thousand learned ones, and #222 is the standing
 * reminder of what per-keystroke work costs here.
 *
 * Scores are decayed once, when the snapshot is built, rather than per lookup: the decay moves on the
 * scale of weeks and the snapshot lives for minutes.
 */
class LearnedSnapshot internal constructor(
    val lang: String,
    /** Fold keys, lexicographically sorted. Parallel to [words] and [scores]. */
    private val keys: Array<String>,
    private val words: Array<String>,
    private val scores: DoubleArray,
) {
    val size: Int get() = keys.size

    val isEmpty: Boolean get() = keys.isEmpty()

    /**
     * Words whose fold key starts with [prefix] and whose score is at least [minScore], best first,
     * at most [limit] of them. [prefix] must already be folded by the caller's language rules.
     */
    fun startingWith(prefix: String, minScore: Double, limit: Int): List<String> {
        if (keys.isEmpty() || limit <= 0) return emptyList()
        var lo = lowerBound(prefix)
        val out = ArrayList<Pair<String, Double>>()
        while (lo < keys.size && keys[lo].startsWith(prefix)) {
            if (scores[lo] >= minScore) out.add(words[lo] to scores[lo])
            lo++
        }
        if (out.isEmpty()) return emptyList()
        out.sortByDescending { it.second }
        return out.take(limit).map { it.first }
    }

    /** The decayed score of the exact folded [key], or 0.0 when it is not held. */
    fun scoreOfKey(key: String): Double {
        var lo = lowerBound(key)
        var best = 0.0
        while (lo < keys.size && keys[lo] == key) {
            if (scores[lo] > best) best = scores[lo]
            lo++
        }
        return best
    }

    private fun lowerBound(prefix: String): Int {
        var a = 0
        var b = keys.size
        while (a < b) {
            val mid = (a + b) ushr 1
            if (keys[mid] < prefix) a = mid + 1 else b = mid
        }
        return a
    }

    companion object {
        val EMPTY = LearnedSnapshot("", emptyArray(), emptyArray(), DoubleArray(0))

        internal fun of(lang: String, entries: List<LearnedWordEntry>, now: Long): LearnedSnapshot {
            if (entries.isEmpty()) return LearnedSnapshot(lang, emptyArray(), emptyArray(), DoubleArray(0))
            val sorted = entries.sortedBy { it.key }
            val keys = Array(sorted.size) { sorted[it].key }
            val words = Array(sorted.size) { sorted[it].word }
            val scores = DoubleArray(sorted.size) { i ->
                val e = sorted[i]
                // A promoted word is no longer ours to forget — it is a personal-dictionary entry now,
                // and it is read from there. Its score here is only the record of how it got in.
                if (e.promoted) e.count.toDouble()
                else WordLearningGate.decayedScore(e.count, e.lastUsed, now)
            }
            return LearnedSnapshot(lang, keys, words, scores)
        }
    }
}
