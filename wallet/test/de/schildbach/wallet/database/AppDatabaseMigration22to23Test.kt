/*
 * Copyright 2026 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.schildbach.wallet.database.entity.TxDisplayCacheEntry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Schema 22 → 23 (`tx_display_cache.source`). Builds a REAL version-22 database
 * from the exported `schemas/…/22.json` (every table, index and the identity
 * hash), puts an existing L1 row in it, then opens it with Room at the current
 * version and only [AppDatabaseMigrations.migration22to23] registered: Room
 * itself validates the migrated schema against the entities (a mismatch throws
 * on open), and the old row must survive as an L1 row. Instrumented coverage of
 * the same step lives in `androidTest/.../DatabaseMigrationTest.migrate22to23`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [29], manifest = Config.NONE)
class AppDatabaseMigration22to23Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-22-23-test.db"

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun schemaJson(version: Int): JSONObject {
        val relative = "schemas/de.schildbach.wallet.database.AppDatabase/$version.json"
        val file = listOf(File(relative), File("wallet/$relative")).firstOrNull { it.isFile }
            ?: error("exported Room schema $version.json not found")
        return JSONObject(file.readText())
    }

    private fun createVersion22() {
        val schema = schemaJson(22).getJSONObject("database")
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName).also { it.parentFile?.mkdirs() }, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL(
                "INSERT INTO tx_display_cache (rowId, title, valueSatoshis, iconType, iconBgType, statusText, " +
                    "comment, transactionAmount, time, hasErrors, filterFlags) " +
                    "VALUES ('${"ab".repeat(32)}', 'Received', 100, 0, 0, '', '', 1, 500, 0, 2)"
            )
            db.version = 22
        } finally {
            db.close()
        }
    }

    @Test
    fun migration_isTheSingleAddColumnStatement() {
        assertEquals(22, AppDatabaseMigrations.migration22to23.startVersion)
        assertEquals(23, AppDatabaseMigrations.migration22to23.endVersion)
        assertEquals(
            "ALTER TABLE `tx_display_cache` ADD COLUMN `source` INTEGER NOT NULL DEFAULT 0",
            AppDatabaseMigrations.SQL_22_TO_23_ADD_SOURCE
        )
        // The exported v23 schema declares the same column, so a fresh install and
        // a migrated install end up identical.
        val txCache = schemaJson(23).getJSONObject("database").getJSONArray("entities").let { entities ->
            (0 until entities.length()).map { entities.getJSONObject(it) }
                .single { it.getString("tableName") == "tx_display_cache" }
        }
        assertTrue(txCache.getString("createSql").contains("`source` INTEGER NOT NULL DEFAULT 0"))
    }

    @Test
    fun migrate22to23_roomValidatesTheSchema_andKeepsExistingRowsAsL1() = runBlocking {
        createVersion22()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabaseMigrations.migration22to23)
            .allowMainThreadQueries()
            .build()
        try {
            val rows = db.txDisplayCacheDao().getAll()
            assertEquals(1, rows.size)
            assertEquals(TxDisplayCacheEntry.SOURCE_L1, rows.single().source)
            assertEquals(1, db.txDisplayCacheDao().getCount())
            assertEquals(23, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }
}
