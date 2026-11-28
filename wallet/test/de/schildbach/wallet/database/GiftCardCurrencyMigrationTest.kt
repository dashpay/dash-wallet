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
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.data.TxId
import org.dash.wallet.common.data.entity.GiftCard
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Version 21 is rebuilt from its exported schema because room-testing's MigrationTestHelper is
 * only on the androidTest classpath. Room still validates every table when it opens the migrated
 * file, so a migration that left the schema different from the entities would fail here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [29], manifest = Config.NONE)
class GiftCardCurrencyMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "gift-card-currency-migration"
    private val legacyTxId: TxId =
        TxId.wrap("00000000000000000000000000000000000000000000000000000000000000a1")
    private val newTxId: TxId =
        TxId.wrap("00000000000000000000000000000000000000000000000000000000000000b2")
    private var db: AppDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        context.deleteDatabase(dbName)
    }

    private fun createVersion21WithOneGiftCard() {
        val schema = JSONObject(File("schemas/${AppDatabase::class.java.name}/21.json").readText())
            .getJSONObject("database")
        val file = context.getDatabasePath(dbName)
        file.parentFile!!.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(file, null).use { v21 ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                v21.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) {
                    v21.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setupQueries = schema.getJSONArray("setupQueries")
            for (i in 0 until setupQueries.length()) {
                v21.execSQL(setupQueries.getString(i))
            }
            v21.execSQL(
                "INSERT INTO `gift_cards` (`txId`, `merchantName`, `price`, `number`, `note`, `index`) " +
                    "VALUES (?, 'Acme', 23.75, '1234', 'order-1', 0)",
                arrayOf(legacyTxId.bytes)
            )
            v21.version = 21
        }
    }

    private fun openMigrated(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabaseMigrations.migration21to22, AppDatabaseMigrations.migration22to23)
            .allowMainThreadQueries()
            .build()
            .also { db = it }

    @Test
    fun migrationTo23_keepsGiftCardsAndLeavesTheirCurrencyUnset() = runBlocking {
        createVersion21WithOneGiftCard()

        val dao = openMigrated().giftCardDao()

        val legacy = dao.getCardForTransaction(legacyTxId.bytes).single()
        assertEquals("Acme", legacy.merchantName)
        assertEquals(23.75, legacy.price, 0.0)
        assertEquals("1234", legacy.number)
        assertEquals("order-1", legacy.note)
        assertNull("a card from before the migration reads as USD", legacy.currency)
    }

    @Test
    fun currency_roundTripsAndBackfillOnlyFillsCardsWithoutOne() = runBlocking {
        createVersion21WithOneGiftCard()
        val dao = openMigrated().giftCardDao()
        dao.insertGiftCard(GiftCard(txId = newTxId, merchantName = "Acme UK", price = 19.0, currency = "GBP"))

        assertEquals("GBP", dao.getCardForTransaction(newTxId.bytes).single().currency)

        assertEquals(1, dao.backfillCurrency(legacyTxId.bytes, "EUR"))
        assertEquals(0, dao.backfillCurrency(newTxId.bytes, "EUR"))

        assertEquals("EUR", dao.getCardForTransaction(legacyTxId.bytes).single().currency)
        assertEquals("GBP", dao.getCardForTransaction(newTxId.bytes).single().currency)
    }
}
