/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.data.history

import android.content.Context
import android.provider.BaseColumns
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.io.File

private const val DICTATE_HISTORY_TABLE = "dictate_history"

/** Where a stored dictation originated — persisted verbatim in [DictateHistoryEntry.source]. */
object DictateHistorySource {
    const val KEYBOARD = "keyboard"
    const val OVERLAY = "overlay"
    const val REALTIME = "realtime"
    const val IMPORT = "import"
}

/**
 * One stored dictation (issue #140): the finished transcript plus enough metadata to browse, re-insert,
 * re-transcribe and manage it later. [audioPath] is the absolute path of a WAV copied into the app's
 * private history dir when audio retention is on; it is nullable because retention is opt-in and some
 * paths (e.g. realtime after a clean finish, or a text-only re-insert) leave no audio to keep.
 */
@Serializable
@Entity(tableName = DICTATE_HISTORY_TABLE)
data class DictateHistoryEntry(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = BaseColumns._ID, index = true)
    val id: Long = 0,
    /** The final committed transcript (post rewording/formatting/mappings), exactly what the user got. */
    val text: String,
    /** Wall-clock creation time in epoch millis. */
    val createdAt: Long,
    /** Provider id of the transcription account (e.g. "openai"); for grouping/debugging. */
    val providerId: String,
    /** Human-readable provider label shown in the UI (falls back to the id when blank). */
    val providerName: String,
    /** Resolved transcription/realtime model name. */
    val model: String,
    /** Input language code, or "" for auto-detect. */
    val language: String,
    /** Recorded audio length in seconds (0 when unknown). */
    val durationSecs: Long,
    /** Absolute path of the retained WAV in the private history dir, or null when no audio is kept. */
    val audioPath: String?,
    /** Size of the retained WAV in bytes (0 when no audio). */
    val audioBytes: Long,
    /** Where the dictation came from: "keyboard", "overlay", "realtime" or "import". */
    val source: String,
    /** True when an AI rewording/live-prompt pass produced the text (vs. a plain transcript). */
    val reworded: Boolean,
    /** Pinned entries are shown with a marker and are never dropped by pruning. */
    val pinned: Boolean = false,
    /** True for a dictation whose transcription failed; [text] holds a placeholder and the retained audio
     * can be re-transcribed to recover it (issue #140 safety net). Cleared on a successful re-transcribe. */
    val failed: Boolean = false,
)

@Dao
interface DictateHistoryDao {
    // Pinned entries float to the top; otherwise newest first.
    @Query("SELECT * FROM $DICTATE_HISTORY_TABLE ORDER BY pinned DESC, createdAt DESC")
    fun getAllAsFlow(): Flow<List<DictateHistoryEntry>>

    @Query("SELECT * FROM $DICTATE_HISTORY_TABLE ORDER BY createdAt DESC")
    suspend fun getAllNewestFirst(): List<DictateHistoryEntry>

    @Query("SELECT * FROM $DICTATE_HISTORY_TABLE WHERE ${BaseColumns._ID} = :id")
    suspend fun getById(id: Long): DictateHistoryEntry?

    @Insert
    suspend fun insert(entry: DictateHistoryEntry): Long

    // A successful re-transcribe also clears the failed flag (the recovery succeeded).
    @Query("UPDATE $DICTATE_HISTORY_TABLE SET text = :text, failed = 0 WHERE ${BaseColumns._ID} = :id")
    suspend fun updateText(id: Long, text: String)

    @Query("UPDATE $DICTATE_HISTORY_TABLE SET pinned = :pinned WHERE ${BaseColumns._ID} = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE $DICTATE_HISTORY_TABLE SET audioPath = :path, audioBytes = :bytes WHERE ${BaseColumns._ID} = :id")
    suspend fun setAudio(id: Long, path: String?, bytes: Long)

    @Query("UPDATE $DICTATE_HISTORY_TABLE SET audioPath = NULL, audioBytes = 0 WHERE ${BaseColumns._ID} = :id")
    suspend fun clearAudio(id: Long)

    @Query("DELETE FROM $DICTATE_HISTORY_TABLE WHERE ${BaseColumns._ID} = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM $DICTATE_HISTORY_TABLE")
    suspend fun deleteAll()
}

@Database(entities = [DictateHistoryEntry::class], version = 2, exportSchema = false)
abstract class DictateHistoryDatabase : RoomDatabase() {
    abstract fun dao(): DictateHistoryDao

    companion object {
        fun new(context: Context): DictateHistoryDatabase {
            return Room
                .databaseBuilder(context, DictateHistoryDatabase::class.java, DICTATE_HISTORY_TABLE)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}

/**
 * Single write/read path for the dictation history (issue #140), mirroring [dev.patrickgold.florisboard
 * .dictate.data.stats.DictateStats]. Owns the Room database and the private audio directory so the
 * keyboard, the floating button and the settings/panel UIs all share one store.
 *
 * Retained audio lives in `filesDir/dictate_history/` (never cacheDir, which is wiped on every process
 * start) and is copied in on [record] before the caller deletes the original cache WAV. Pruning enforces
 * three independent caps: newest-N entries, a max age, and a total audio byte budget (oldest audio is
 * dropped first while the transcript text is kept).
 */
object DictateHistoryStore {

    @Volatile
    private var instance: DictateHistoryDatabase? = null

    private fun db(context: Context): DictateHistoryDatabase =
        instance ?: synchronized(this) {
            instance ?: DictateHistoryDatabase.new(context.applicationContext).also { instance = it }
        }

    /** Private directory holding the retained WAVs (survives the cacheDir wipe). */
    fun audioDir(context: Context): File = File(context.applicationContext.filesDir, "dictate_history")

    /** Reactive stream of all entries, newest first — for the settings screen and the in-keyboard panel. */
    fun flow(context: Context): Flow<List<DictateHistoryEntry>> = db(context).dao().getAllAsFlow()

    suspend fun getById(context: Context, id: Long): DictateHistoryEntry? = db(context).dao().getById(id)

    /**
     * Records one finished dictation: inserts the transcript row, copies the audio in when retention is on,
     * then prunes to the configured caps. Blank text is ignored (nothing was produced). Returns the new
     * row id, or null when nothing was stored.
     */
    suspend fun record(
        context: Context,
        prefs: FlorisPreferenceModel,
        text: String,
        providerId: String,
        providerName: String,
        model: String,
        language: String,
        durationSecs: Long,
        source: String,
        reworded: Boolean,
        audioFile: File?,
        failed: Boolean = false,
        // Keep the audio regardless of the audio-retention pref (used for failed entries, whose audio is
        // the only recovery path).
        forceAudio: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): Long? {
        if (text.isBlank()) return null
        val dao = db(context).dao()
        val id = dao.insert(
            DictateHistoryEntry(
                text = text,
                createdAt = nowMs,
                providerId = providerId,
                providerName = providerName.ifBlank { providerId },
                model = model,
                language = language,
                durationSecs = durationSecs.coerceAtLeast(0L),
                audioPath = null,
                audioBytes = 0L,
                source = source,
                reworded = reworded,
                failed = failed,
            )
        )
        if ((forceAudio || prefs.dictate.historyAudioRetention.get()) && audioFile != null &&
            audioFile.exists() && audioFile.length() > 0L
        ) {
            val dest = File(audioDir(context).apply { mkdirs() }, "$id.wav")
            runCatching { audioFile.copyTo(dest, overwrite = true) }
                .onSuccess { dao.setAudio(id, dest.absolutePath, dest.length()) }
        }
        prune(context, prefs)
        return id
    }

    /** Overwrites an existing entry's transcript in place (used when re-transcribing its audio). */
    suspend fun updateText(context: Context, id: Long, text: String) {
        if (text.isBlank()) return
        db(context).dao().updateText(id, text)
    }

    /** Pins or unpins an entry; pinned entries survive pruning and are marked in the UI. */
    suspend fun setPinned(context: Context, id: Long, pinned: Boolean) {
        db(context).dao().setPinned(id, pinned)
    }

    /** Deletes one entry and its retained audio file (if any). */
    suspend fun delete(context: Context, entry: DictateHistoryEntry) {
        entry.audioPath?.let { runCatching { File(it).delete() } }
        db(context).dao().delete(entry.id)
    }

    /** Clears the whole history and every retained audio file. */
    suspend fun clearAll(context: Context) {
        db(context).dao().deleteAll()
        runCatching { audioDir(context).deleteRecursively() }
    }

    /** All entries (newest first) — for a backup export. */
    suspend fun exportAll(context: Context): List<DictateHistoryEntry> = db(context).dao().getAllNewestFirst()

    /**
     * Restores backed-up [entries] (backup/restore). Each is re-inserted with a fresh id; when
     * [backupAudioDir] contains a WAV named by the entry's ORIGINAL id (as written by the backup), it is
     * copied into the history audio dir and linked, otherwise the entry restores text-only. [replace]
     * clears the store first (the Erase restore strategy); otherwise entries are appended (Merge).
     */
    suspend fun importEntries(
        context: Context,
        entries: List<DictateHistoryEntry>,
        backupAudioDir: File?,
        replace: Boolean,
    ) {
        val dao = db(context).dao()
        if (replace) clearAll(context)
        val destDir = audioDir(context)
        for (entry in entries) {
            if (entry.text.isBlank()) continue
            val newId = dao.insert(entry.copy(id = 0, audioPath = null, audioBytes = 0L))
            val srcWav = backupAudioDir?.let { File(it, "${entry.id}.wav") }
            if (srcWav != null && srcWav.exists() && srcWav.length() > 0L) {
                val dest = File(destDir.apply { mkdirs() }, "$newId.wav")
                runCatching { srcWav.copyTo(dest, overwrite = true) }
                    .onSuccess { dao.setAudio(newId, dest.absolutePath, dest.length()) }
            }
        }
    }

    /** Total bytes currently used by retained audio (for the settings-screen disk-usage readout). */
    suspend fun totalAudioBytes(context: Context): Long =
        db(context).dao().getAllNewestFirst().sumOf { it.audioBytes }

    /**
     * Enforces the three caps, newest-first: drop entries beyond [historyMaxEntries] or older than
     * [historyMaxAgeDays] entirely (row + audio), then walk the survivors and drop the *audio only* of the
     * oldest ones once the [historyAudioBudgetMb] byte budget is exceeded (their text is preserved).
     */
    private suspend fun prune(context: Context, prefs: FlorisPreferenceModel, nowMs: Long = System.currentTimeMillis()) {
        val d = prefs.dictate
        val dao = db(context).dao()
        val maxEntries = d.historyMaxEntries.get().coerceAtLeast(1)
        val maxAgeMs = d.historyMaxAgeDays.get().toLong().coerceAtLeast(0L) * 86_400_000L
        val budgetBytes = d.historyAudioBudgetMb.get().toLong().coerceAtLeast(0L) * 1_000_000L
        val all = dao.getAllNewestFirst()
        val ageCutoff = if (maxAgeMs > 0L) nowMs - maxAgeMs else Long.MIN_VALUE

        // Pinned entries are never evicted and are not counted against the entry cap.
        val survivors = ArrayList<DictateHistoryEntry>(all.size)
        var unpinned = 0
        all.forEach { entry ->
            if (entry.pinned) {
                survivors.add(entry)
                return@forEach
            }
            unpinned++
            if (unpinned > maxEntries || entry.createdAt < ageCutoff) {
                entry.audioPath?.let { runCatching { File(it).delete() } }
                dao.delete(entry.id)
            } else {
                survivors.add(entry)
            }
        }
        var used = 0L
        for (entry in survivors) {
            val path = entry.audioPath ?: continue
            if (entry.pinned || used + entry.audioBytes <= budgetBytes) {
                // Pinned audio is always kept; it still counts toward the running total.
                used += entry.audioBytes
            } else {
                runCatching { File(path).delete() }
                dao.clearAudio(entry.id)
            }
        }
    }
}
