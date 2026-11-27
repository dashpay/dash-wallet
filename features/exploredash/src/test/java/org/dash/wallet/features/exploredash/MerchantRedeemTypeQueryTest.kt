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
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.dash.wallet.features.exploredash.data.explore.model.MerchantType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MerchantRedeemTypeQueryTest {
    private lateinit var database: ExploreDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ExploreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val db = database.openHelper.writableDatabase
        listOf("barcode" to "'barcode'", "link" to "'url'", "unset" to "NULL").forEach { (id, redeemType) ->
            db.execSQL(
                "INSERT INTO merchant (merchantId, name, type, paymentMethod, territory, active, redeemType) " +
                    "VALUES ('$id', '$id merchant', 'online', 'gift card', 'Texas', 1, $redeemType)"
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun getByTerritory_returnsMerchantsOfEveryRedeemType() = runTest {
        val merchants = database.merchantDao().getByTerritory(
            merchantId = "",
            source = "",
            territoryFilter = "Texas",
            types = listOf(MerchantType.ONLINE),
            paymentMethod = "",
            denomType = "",
            provider = "",
            sortOption = 0,
            anchorLat = 0.0,
            anchorLng = 0.0,
            limit = 10
        )

        assertEquals(listOf("barcode", "link", "unset"), merchants.map { it.merchantId })
    }

    @Test
    fun getByTerritoryResultCount_countsMerchantsOfEveryRedeemType() = runTest {
        val count = database.merchantDao().getByTerritoryResultCount(
            territoryFilter = "Texas",
            types = listOf(MerchantType.ONLINE),
            paymentMethod = "",
            denomType = "",
            provider = ""
        )

        assertEquals(3, count)
    }

    @Test
    fun searchByTerritoryResultCount_countsMerchantsOfEveryRedeemType() = runTest {
        val count = database.merchantDao().searchByTerritoryResultCount(
            query = "merchant*",
            territoryFilter = "Texas",
            types = listOf(MerchantType.ONLINE),
            paymentMethod = "",
            denomType = "",
            provider = ""
        )

        assertEquals(3, count)
    }

    @Test
    fun getGroupedResultCount_countsMerchantsOfEveryRedeemType() = runTest {
        val count = database.merchantDao().getGroupedResultCount(
            types = listOf(MerchantType.ONLINE),
            paymentMethod = "",
            denomType = "",
            provider = ""
        )

        assertEquals(3, count)
    }
}
