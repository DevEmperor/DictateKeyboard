/*
 * Copyright (C) 2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.nlp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.ext.Extension
import dev.patrickgold.florisboard.lib.ext.ExtensionComponent
import dev.patrickgold.florisboard.lib.ext.ExtensionEditor
import dev.patrickgold.florisboard.lib.ext.ExtensionMeta
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.florisboard.lib.kotlin.io.FsDir
import org.florisboard.lib.kotlin.io.subFile

@Serializable
class LanguagePackComponent(
    override val id: String,
    override val label: String,
    override val authors: List<String>,
    val locale: FlorisLocale = FlorisLocale.fromTag(id),
    val hanShapeBasedKeyCode: String = "abcdefghijklmnopqrstuvwxyz",
) : ExtensionComponent {
    @Transient var parent: LanguagePackExtension? = null

    @SerialName("hanShapeBasedTable")
    private val _hanShapeBasedTable: String? = null  // Allows overriding the sqlite3 table to query in the json
    val hanShapeBasedTable
        get() = _hanShapeBasedTable ?: locale.variant
}

@SerialName(LanguagePackExtension.SERIAL_TYPE)
@Serializable
class LanguagePackExtension( // FIXME: how to make this support multiple types of language packs, and selectively load?
    override val meta: ExtensionMeta,
    override val dependencies: List<String>? = null,
    val items: List<LanguagePackComponent> = listOf(),
    val hanShapeBasedSQLite: String = "han.sqlite3",
) : Extension() {

    override fun components(): List<ExtensionComponent> = items

    override fun edit(): ExtensionEditor {
        TODO("LOL LMAO")
    }

    companion object {
        const val SERIAL_TYPE = "ime.extension.languagepack"
    }

    override fun serialType() = SERIAL_TYPE

    @Transient var hanShapeBasedSQLiteDatabase: SQLiteDatabase = SQLiteDatabase.create(null)

    override fun onAfterLoad(context: Context, cacheDir: FsDir) {
        // FIXME: this is loading language packs of all subtypes when they load.
        super.onAfterLoad(context, cacheDir)

        val databasePath = workingDir?.subFile(hanShapeBasedSQLite)?.path
        if (databasePath == null) {
            flogError { "Han shape-based language pack not found or loaded" }
        } else try {
            // TODO: use lock on database?
            hanShapeBasedSQLiteDatabase.takeIf { it.isOpen }?.close()
            createCodeIndexes(databasePath)
            hanShapeBasedSQLiteDatabase =
                SQLiteDatabase.openDatabase(databasePath, null, SQLiteDatabase.OPEN_READONLY);
        } catch (e: SQLiteException) {
            flogError { "SQLiteException in openDatabase: path=$databasePath, error='${e}'" }
        }
    }

    /**
     * Indexes every code table for the prefix lookup the suggestion provider runs on each keystroke
     * (issue #262). The shipped databases carry no indexes at all, which turns that lookup into a full
     * scan plus a sort of a six-figure table — measurably the most expensive thing that happens while
     * typing Chinese.
     *
     * Built here rather than shipped pre-indexed because [load] deletes and re-unzips the cache copy
     * anyway: the index would have to travel inside the archive on every install and download, roughly
     * doubling it, to save work that costs well under a second once per process — and this runs on the
     * provider's background scope, before the first lookup can reach it.
     *
     * Failure is not fatal: a database that cannot be opened for writing simply stays unindexed and the
     * provider falls back to the slow-but-correct path.
     */
    private fun createCodeIndexes(databasePath: String) {
        try {
            SQLiteDatabase.openDatabase(databasePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                val tables = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
                    null,
                ).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                for (table in tables) {
                    // Not every table here is a code table: opening the database for writing makes Android
                    // add an `android_metadata` table of its own, and a future pack may carry something
                    // else. Ask what the columns are rather than trying and logging a failure each time.
                    val columns = db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
                        val nameColumn = cursor.getColumnIndex("name")
                        buildSet { while (cursor.moveToNext()) add(cursor.getString(nameColumn)) }
                    }
                    if (!columns.containsAll(listOf("code", "weight"))) continue
                    // Ordered exactly like the query's ORDER BY, so the index satisfies the sort as well
                    // as the range and SQLite can stop at the LIMIT instead of collecting every match.
                    try {
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `idx_${table}_code` " +
                                "ON `$table`(`code` ASC, `weight` DESC)"
                        )
                    } catch (e: SQLiteException) {
                        flogError { "Could not index table '$table': $e" }
                    }
                }
            }
        } catch (e: SQLiteException) {
            flogError { "Could not open $databasePath for indexing: $e" }
        }
    }

    override fun onBeforeUnload(context: Context, cacheDir: FsDir) {
        super.onBeforeUnload(context, cacheDir)
        hanShapeBasedSQLiteDatabase.takeIf { it.isOpen }?.close()
    }
}
