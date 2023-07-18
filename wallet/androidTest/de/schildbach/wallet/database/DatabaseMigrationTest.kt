/*
 * Copyright 2021 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException


@RunWith(AndroidJUnit4::class)
open class DatabaseMigrationTest {
    companion object {
        private const val TEST_DB_NAME = "test_database"
        private const val EXCHANGE_RATE = "31438.8212"
    }

    private val migrations = arrayOf<Migration>()

    @Rule
    @JvmField
    val testHelper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrateAll() {
        // Create db and fill with data
        testHelper.createDatabase(TEST_DB_NAME, 1).apply {
            val values = ContentValues()
            values.put("currencyCode", "ARS")
            values.put("rate", EXCHANGE_RATE)
            this.insert("exchange_rates", SQLiteDatabase.CONFLICT_REPLACE, values)

            close()
        }

        // Run migrations
        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java, TEST_DB_NAME
        ).addMigrations(*migrations)
            .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
            .build()

        // Check that no data is preserved, which should be the case from 1 to 11
        runBlocking {
            val savedRate = db.exchangeRatesDao().getRateSync("ARS")
            assert(savedRate == null)
        }

        db.close()
    }
}
