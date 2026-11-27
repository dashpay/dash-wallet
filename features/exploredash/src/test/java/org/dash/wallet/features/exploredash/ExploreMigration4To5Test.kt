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

package org.dash.wallet.features.exploredash

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

internal fun exploreSchema(version: Int): JSONObject =
    JSONObject(File("schemas/${ExploreDatabase::class.java.name}/$version.json").readText())
        .getJSONObject("database")

/**
 * The tables as the published version 4 file declares them. The sync job fills a hand-made template,
 * not a Room export: same identity hash, but `gift_card_providers` has a nullable `id` without
 * AUTOINCREMENT and an INTEGER `sourceId`, and `merchant` orders its columns differently.
 */
internal val PUBLISHED_VERSION_4_TABLES = mapOf(
    "merchant" to "CREATE TABLE \"merchant\" (\"deeplink\" TEXT, \"plusCode\" TEXT, \"addDate\" TEXT, " +
        "\"updateDate\" TEXT, \"paymentMethod\" TEXT, \"merchantId\" TEXT, \"id\" INTEGER NOT NULL, " +
        "\"active\" INTEGER DEFAULT 1, \"name\" TEXT, \"address1\" TEXT, \"address2\" TEXT, \"address3\" TEXT, " +
        "\"address4\" TEXT, \"latitude\" REAL, \"longitude\" REAL, \"website\" TEXT, \"phone\" TEXT, " +
        "\"territory\" TEXT, \"city\" TEXT, \"source\" TEXT, \"sourceId\" TEXT, \"logoLocation\" TEXT, " +
        "\"googleMaps\" TEXT, \"coverImage\" TEXT, \"type\" TEXT, \"redeemType\" TEXT, " +
        "\"savingsPercentage\" INTEGER, \"denominationsType\" TEXT, PRIMARY KEY(\"id\" AUTOINCREMENT))",
    "atm" to "CREATE TABLE \"atm\" (\"postcode\" TEXT, \"manufacturer\" TEXT, \"id\" INTEGER NOT NULL, " +
        "\"active\" INTEGER DEFAULT 1, \"name\" TEXT, \"address1\" TEXT, \"address2\" TEXT, \"address3\" TEXT, " +
        "\"address4\" TEXT, \"latitude\" REAL, \"longitude\" REAL, \"website\" TEXT, \"phone\" TEXT, " +
        "\"territory\" TEXT, \"city\" TEXT, \"source\" TEXT, \"sourceId\" TEXT, \"logoLocation\" TEXT, " +
        "\"googleMaps\" TEXT, \"coverImage\" TEXT, \"type\" TEXT, PRIMARY KEY(\"id\" AUTOINCREMENT))",
    "gift_card_providers" to "CREATE TABLE gift_card_providers (id INTEGER PRIMARY KEY, merchantId TEXT NOT NULL, " +
        "provider TEXT NOT NULL, redeemType TEXT NOT NULL, savingsPercentage INTEGER NOT NULL, " +
        "active INTEGER NOT NULL, denominationsType TEXT NOT NULL, sourceId INTEGER NOT NULL)"
)

internal fun createExploreDatabaseVersion4(
    file: File,
    tables: Map<String, String> = mapOf(),
    fill: (SQLiteDatabase) -> Unit
) {
    val schema = exploreSchema(4)
    file.parentFile!!.mkdirs()

    SQLiteDatabase.openOrCreateDatabase(file, null).use { v4 ->
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            v4.execSQL(tables[table] ?: entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.getJSONArray("indices")
            for (j in 0 until indices.length()) {
                v4.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        val setupQueries = schema.getJSONArray("setupQueries")
        for (i in 0 until setupQueries.length()) {
            v4.execSQL(setupQueries.getString(i))
        }
        fill(v4)
        v4.version = 4
    }
}

/**
 * Version 4 is rebuilt from its exported schema. Room validates every table against the entities
 * when it opens the migrated file, so a migration that left the schema different would fail here.
 */
@RunWith(RobolectricTestRunner::class)
class ExploreMigration4To5Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "explore-migration-4-5"
    private var db: ExploreDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        context.deleteDatabase(dbName)
    }

    private fun createVersion4(tables: Map<String, String> = mapOf()) {
        createExploreDatabaseVersion4(context.getDatabasePath(dbName), tables) { v4 ->
            v4.execSQL(
                "INSERT INTO merchant (merchantId, name, type, paymentMethod, territory, active, redeemType) " +
                    "VALUES ('card', 'Card merchant', 'online', 'gift card', 'Texas', 1, 'barcode')"
            )
            v4.execSQL(
                "INSERT INTO merchant (merchantId, name, type, paymentMethod, territory, active, redeemType) " +
                    "VALUES ('dash', 'Dash merchant', 'physical', 'dash', 'Texas', 1, 'barcode')"
            )
            v4.execSQL("INSERT INTO atm (name, territory) VALUES ('An atm', 'Texas')")
            v4.execSQL(
                "INSERT INTO gift_card_providers " +
                    "(id, merchantId, provider, redeemType, savingsPercentage, active, denominationsType, sourceId) " +
                    "VALUES (7, 'card', 'CTX', 'barcode', 250, 1, 'fixed', 1234)"
            )
        }
    }

    private fun openMigrated(): ExploreDatabase =
        Room.databaseBuilder(context, ExploreDatabase::class.java, dbName)
            .addMigrations(ExploreDatabaseMigrations.migration4To5)
            .allowMainThreadQueries()
            .build()
            .also { db = it }

    @Test
    fun migration4To5_keepsEveryRowAndLeavesCountryAndCurrencyUnset() = keepsEveryRow(mapOf())

    @Test
    fun migration4To5_publishedLayout_keepsEveryRowAndLeavesCountryAndCurrencyUnset() =
        keepsEveryRow(PUBLISHED_VERSION_4_TABLES)

    private fun keepsEveryRow(tables: Map<String, String>) = runBlocking {
        createVersion4(tables)

        val migrated = openMigrated()

        assertEquals(2, migrated.merchantDao().getCount())
        val card = migrated.merchantDao().getMerchantById("card")!!
        assertEquals("Card merchant", card.name)
        assertEquals("gift card", card.paymentMethod)
        assertNull(card.country)
        assertNull(card.currency)
        assertEquals("Dash merchant", migrated.merchantDao().getMerchantById("dash")!!.name)
        assertEquals(1, migrated.atmDao().getCount())

        val provider = migrated.giftCardProviderDao().getProvidersByMerchantId("card").single()
        assertEquals("CTX", provider.provider)
        assertEquals(250, provider.savingsPercentage)
        assertEquals(7L, provider.id)
        assertEquals("1234", provider.sourceId)
        assertNull(provider.currency)

        // AUTOINCREMENT continues after the copied ids
        migrated.openHelper.writableDatabase.execSQL(
            "INSERT INTO gift_card_providers " +
                "(merchantId, provider, redeemType, savingsPercentage, active, denominationsType, sourceId) " +
                "VALUES ('card', 'PiggyCards', 'barcode', 100, 1, 'fixed', '99')"
        )
        assertEquals(listOf(7L, 8L), migrated.giftCardProviderDao().getProvidersByMerchantId("card").map { it.id })
    }

    @Test
    fun migration4To5_producesTheExportedVersion5Schema() = producesVersion5(mapOf())

    @Test
    fun migration4To5_publishedLayout_producesTheExportedVersion5Schema() =
        producesVersion5(PUBLISHED_VERSION_4_TABLES)

    private fun producesVersion5(tables: Map<String, String>) {
        createVersion4(tables)

        val migrated = openMigrated().openHelper.readableDatabase

        assertEquals(5, migrated.version)
        migrated.query("SELECT identity_hash FROM room_master_table").use { cursor ->
            cursor.moveToFirst()
            assertEquals(exploreSchema(5).getString("identityHash"), cursor.getString(0))
        }
        assertEquals("c3dc9fe1663d683de1446c9666ea80b9", exploreSchema(5).getString("identityHash"))
    }

    @Test
    fun migratedRows_showUnderEveryCountry() = runBlocking {
        createVersion4()

        val dao = openMigrated().merchantDao()
        val types = listOf("online", "physical", "both")

        assertEquals(2, dao.getByTerritoryResultCount("", types, "", "", "", "US"))
        assertEquals(2, dao.getByTerritoryResultCount("", types, "", "", "", "GB"))
        assertEquals(listOf<String>(), dao.getCountries())
    }
}
