/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.data.prompts

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.dictate.DictateReasoningEffort

/**
 * Storage for user-defined rewording prompts.
 *
 * IMPORTANT (data contract – see `docs/COMPATIBILITY.md`):
 * Database name (`prompts.db`) and the legacy `PROMPTS` columns are FROZEN so that existing Dictate
 * users keep their prompts after the in-place app update. New columns may only be ADDED via a bumped
 * version + an `onUpgrade` `ALTER TABLE … ADD COLUMN` (as done for `AUTO_APPLY` in v2 and
 * `REASONING_EFFORT` in v3). Do not migrate this to Room without a deliberately tested migration –
 * Room's strict type-affinity validation would reject the legacy `BOOLEAN`/`INTEGER` columns of an
 * existing user database.
 *
 * Lifecycle (issue #138): obtain via [getInstance] and NEVER call `close()` on the returned
 * [SQLiteDatabase]. `SQLiteOpenHelper` hands out one shared, reference-counted database whose
 * connection pool is meant to stay open for the process lifetime. Closing it per query disposed the
 * pool out from under a concurrent, in-flight cursor (overlapping `reload` on the Prompts screen) →
 * `IllegalStateException: connection pool has been closed`. The single process-wide instance is safe
 * to use from multiple threads/coroutines; SQLite's own connection pool serialises access.
 */
class PromptsDatabaseHelper private constructor(
    private val context: Context,
) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE PROMPTS (ID INTEGER PRIMARY KEY, POS INTEGER, NAME TEXT, PROMPT TEXT, " +
                "REQUIRES_SELECTION BOOLEAN, AUTO_APPLY BOOLEAN DEFAULT 0, REASONING_EFFORT TEXT, " +
                "REASONING_EFFORT_CUSTOM TEXT)"
        )
        // Seed the example prompts for fresh installs only (existing users skip onCreate). These are
        // the same defaults the legacy Dictate app shipped, resolved from string resources so they are
        // localized (e.g. German) just like the original.
        defaultSeeds().forEachIndexed { index, seed ->
            val cv = ContentValues().apply {
                put("POS", index)
                put("NAME", context.getString(seed.nameRes))
                put("PROMPT", context.getString(seed.promptRes))
                put("REQUIRES_SELECTION", if (seed.requiresSelection) 1 else 0)
                put("AUTO_APPLY", 0)
            }
            db.insert("PROMPTS", null, cv)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE PROMPTS ADD COLUMN AUTO_APPLY BOOLEAN DEFAULT 0")
        }
        if (oldVersion < 3) {
            // Per-prompt reasoning-effort override (issue #155). Nullable TEXT holding the enum name;
            // NULL = fall back to the global reasoning setting.
            db.execSQL("ALTER TABLE PROMPTS ADD COLUMN REASONING_EFFORT TEXT")
        }
        if (oldVersion < 4) {
            // Custom wire value used when REASONING_EFFORT is CUSTOM (issue #186).
            db.execSQL("ALTER TABLE PROMPTS ADD COLUMN REASONING_EFFORT_CUSTOM TEXT")
        }
    }

    fun add(model: PromptModel): Int {
        val db = writableDatabase
        val cv = model.toContentValues()
        return db.insert("PROMPTS", null, cv).toInt()
    }

    fun addAll(models: List<PromptModel>?) {
        if (models.isNullOrEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            models.forEach { db.insert("PROMPTS", null, it.toContentValues()) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun replaceAll(models: List<PromptModel>?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("PROMPTS", null, null)
            models?.forEach { db.insert("PROMPTS", null, it.toContentValues()) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun update(model: PromptModel) {
        val db = writableDatabase
        db.update("PROMPTS", model.toContentValues(), "ID = ?", arrayOf(model.id.toString()))
    }

    fun delete(id: Int) {
        val db = writableDatabase
        db.delete("PROMPTS", "ID = ?", arrayOf(id.toString()))
    }

    fun get(id: Int): PromptModel? {
        val db = readableDatabase
        return db.rawQuery("SELECT * FROM PROMPTS WHERE ID = ?", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) cursor.toPromptModel() else null
        }
    }

    fun getAll(): List<PromptModel> {
        val db = readableDatabase
        val models = ArrayList<PromptModel>()
        db.rawQuery("SELECT * FROM PROMPTS ORDER BY POS ASC", null).use { cursor ->
            if (cursor.moveToFirst()) {
                do { models.add(cursor.toPromptModel()) } while (cursor.moveToNext())
            }
        }
        return models
    }

    /**
     * Returns all persisted prompts plus the synthetic UI buttons in display order:
     * instant prompt, select-all, [persisted prompts…], add prompt.
     */
    fun getAllForKeyboard(): List<PromptModel> {
        val persisted = getAll()
        val result = ArrayList<PromptModel>(persisted.size + 3)
        result.add(PromptModel(PromptModel.ID_INSTANT_PROMPT, Int.MIN_VALUE, null, null, false, false))
        result.add(PromptModel(PromptModel.ID_SELECT_ALL, Int.MIN_VALUE + 1, null, null, false, false))
        result.addAll(persisted)
        result.add(PromptModel(PromptModel.ID_ADD_PROMPT, Int.MAX_VALUE, null, null, false, false))
        return result
    }

    fun getAutoApplyIds(): List<Int> {
        val db = readableDatabase
        val ids = ArrayList<Int>()
        db.rawQuery("SELECT ID FROM PROMPTS WHERE AUTO_APPLY = 1 ORDER BY POS ASC", null).use { cursor ->
            if (cursor.moveToFirst()) {
                do { ids.add(cursor.getInt(0)) } while (cursor.moveToNext())
            }
        }
        return ids
    }

    fun count(): Int {
        val db = readableDatabase
        return db.rawQuery("SELECT COUNT(*) FROM PROMPTS", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }

    private fun PromptModel.toContentValues() = ContentValues().apply {
        put("POS", pos)
        put("NAME", name)
        put("PROMPT", prompt)
        put("REQUIRES_SELECTION", if (requiresSelection) 1 else 0)
        put("AUTO_APPLY", if (autoApply) 1 else 0)
        put("REASONING_EFFORT", reasoningEffort?.name)
        put("REASONING_EFFORT_CUSTOM", reasoningEffortCustom?.takeIf { it.isNotBlank() })
    }

    private fun android.database.Cursor.toPromptModel(): PromptModel {
        // REASONING_EFFORT (v3) is read defensively: an unknown/old value maps back to null (= global).
        val reasoningIdx = getColumnIndex("REASONING_EFFORT")
        val reasoning = if (reasoningIdx >= 0 && !isNull(reasoningIdx)) {
            runCatching { DictateReasoningEffort.valueOf(getString(reasoningIdx)) }.getOrNull()
        } else null
        val customIdx = getColumnIndex("REASONING_EFFORT_CUSTOM")
        val reasoningCustom = if (customIdx >= 0 && !isNull(customIdx)) getString(customIdx) else null
        return PromptModel(
            id = getInt(getColumnIndexOrThrow("ID")),
            pos = getInt(getColumnIndexOrThrow("POS")),
            name = getString(getColumnIndexOrThrow("NAME")),
            prompt = getString(getColumnIndexOrThrow("PROMPT")),
            requiresSelection = getInt(getColumnIndexOrThrow("REQUIRES_SELECTION")) == 1,
            autoApply = getInt(getColumnIndexOrThrow("AUTO_APPLY")) == 1,
            reasoningEffort = reasoning,
            reasoningEffortCustom = reasoningCustom,
        )
    }

    private data class Seed(val nameRes: Int, val promptRes: Int, val requiresSelection: Boolean)

    companion object {
        const val DATABASE_NAME = "prompts.db"
        const val DATABASE_VERSION = 4

        @Volatile
        private var instance: PromptsDatabaseHelper? = null

        /**
         * The single process-wide helper (issue #138). All callers share one open database instead of
         * each opening — and formerly closing — their own connection, which is what caused overlapping
         * queries to race on a disposed pool. Always built with the application context so it outlives
         * any Activity/Composable that requested it.
         */
        fun getInstance(context: Context): PromptsDatabaseHelper =
            instance ?: synchronized(this) {
                instance ?: PromptsDatabaseHelper(context.applicationContext).also { instance = it }
            }

        // A useful starter set covering all three prompt types: six rewrite-the-selection editors
        // (requiresSelection = true), two free generators and two literal `[snippet]` prompts (both
        // requiresSelection = false). Resolved lazily so the strings pick up the device locale at seed
        // time.
        private fun defaultSeeds() = listOf(
            Seed(R.string.dictate__example_prompt_fix_grammar_name, R.string.dictate__example_prompt_fix_grammar_prompt, true),
            Seed(R.string.dictate__example_prompt_improve_name, R.string.dictate__example_prompt_improve_prompt, true),
            Seed(R.string.dictate__example_prompt_formal_name, R.string.dictate__example_prompt_formal_prompt, true),
            Seed(R.string.dictate__example_prompt_friendly_name, R.string.dictate__example_prompt_friendly_prompt, true),
            Seed(R.string.dictate__example_prompt_shorter_name, R.string.dictate__example_prompt_shorter_prompt, true),
            Seed(R.string.dictate__example_prompt_translate_name, R.string.dictate__example_prompt_translate_prompt, true),
            Seed(R.string.dictate__example_prompt_quote_name, R.string.dictate__example_prompt_quote_prompt, false),
            Seed(R.string.dictate__example_prompt_funfact_name, R.string.dictate__example_prompt_funfact_prompt, false),
            Seed(R.string.dictate__example_prompt_shrug_name, R.string.dictate__example_prompt_shrug_prompt, false),
            Seed(R.string.dictate__example_prompt_signoff_name, R.string.dictate__example_prompt_signoff_prompt, false),
        )
    }
}
