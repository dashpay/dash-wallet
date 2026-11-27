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
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.dash.wallet.features.exploredash.data.explore.model.MerchantInfo
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
    private val everyRedeemType = listOf("barcode", "link", "unset")

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ExploreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val db = database.openHelper.writableDatabase
        listOf("barcode" to "'barcode'", "link" to "'url'", "unset" to "NULL").forEach { (id, redeemType) ->
            db.execSQL(
                "INSERT INTO merchant " +
                    "(merchantId, name, type, paymentMethod, territory, active, latitude, longitude, redeemType) " +
                    "VALUES ('$id', '$id merchant', 'online', 'gift card', 'Texas', 1, 30.0, -97.0, $redeemType)"
            )
        }
    }

    private suspend fun PagingSource<Int, MerchantInfo>.firstPageIds(): List<String?> {
        val page = load(PagingSource.LoadParams.Refresh(null, 10, false)) as PagingSource.LoadResult.Page
        return page.data.map { it.merchant?.merchantId }
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

        assertEquals(everyRedeemType, merchants.map { it.merchantId })
    }

    @Test
    fun pagingGetByTerritory_listsMerchantsOfEveryRedeemType() = runTest {
        val source = database.merchantDao().pagingGetByTerritory(
            territoryFilter = "Texas",
            types = listOf(MerchantType.ONLINE),
            paymentMethod = "",
            denomType = "",
            provider = "",
            sortOption = 0,
            anchorLat = 0.0,
            anchorLng = 0.0,
            onlineOrder = 0,
            physicalOrder = 2
        )

        assertEquals(everyRedeemType, source.firstPageIds())
    }

    @Test
    fun pagingSearchByTerritory_listsMerchantsOfEveryRedeemType() = runTest {
        val source = database.merchantDao().pagingSearchByTerritory(
            query = "merchant*",
            territoryFilter = "Texas",
            types = listOf(MerchantType.ONLINE),
            paymentMethod = "",
            denomType = "",
            provider = "",
            sortOption = 0,
            anchorLat = 0.0,
            anchorLng = 0.0,
            onlineOrder = 0,
            physicalOrder = 2
        )

        assertEquals(everyRedeemType, source.firstPageIds())
    }

    @Test
    fun pagingGetGrouped_listsMerchantsOfEveryRedeemType() = runTest {
        val source = database.merchantDao().pagingGetGrouped(
            types = listOf(MerchantType.ONLINE),
            paymentMethod = "",
            denomType = "",
            provider = "",
            sortByDiscount = false,
            anchorLat = 0.0,
            anchorLng = 0.0
        )

        assertEquals(everyRedeemType, source.firstPageIds())
    }

    @Test
    fun pagingSearchGrouped_listsMerchantsOfEveryRedeemType() = runTest {
        val source = database.merchantDao().pagingSearchGrouped(
            query = "merchant*",
            types = listOf(MerchantType.ONLINE),
            paymentMethod = "",
            denomType = "",
            provider = "",
            sortByDiscount = false,
            anchorLat = 0.0,
            anchorLng = 0.0
        )

        assertEquals(everyRedeemType, source.firstPageIds())
    }

    @Test
    fun pagingGetByCoordinates_listsMerchantsOfEveryRedeemType() = runTest {
        val source = database.merchantDao().pagingGetByCoordinates(
            types = listOf(MerchantType.ONLINE),
            paymentMethod = "",
            denomType = "",
            provider = "",
            northLat = 31.0,
            eastLng = -96.0,
            southLat = 29.0,
            westLng = -98.0,
            sortOption = 0,
            anchorLat = 0.0,
            anchorLng = 0.0
        )

        assertEquals(everyRedeemType, source.firstPageIds())
    }

    @Test
    fun pagingSearchByCoordinates_listsMerchantsOfEveryRedeemType() = runTest {
        val source = database.merchantDao().pagingSearchByCoordinates(
            query = "merchant*",
            types = listOf(MerchantType.ONLINE),
            paymentMethod = "",
            denomType = "",
            provider = "",
            northLat = 31.0,
            eastLng = -96.0,
            southLat = 29.0,
            westLng = -98.0,
            sortOption = 0,
            anchorLat = 0.0,
            anchorLng = 0.0
        )

        assertEquals(everyRedeemType, source.firstPageIds())
    }

    @Test
    fun observe_emitsMerchantsOfEveryRedeemTypeForTheMap() = runTest {
        val merchants = database.merchantDao().observe(
            merchantId = "",
            source = "",
            excludeType = "",
            paymentMethod = "",
            denomType = "",
            provider = "",
            northLat = 31.0,
            eastLng = -96.0,
            southLat = 29.0,
            westLng = -98.0,
            limit = 10
        ).first()

        assertEquals(everyRedeemType, merchants.map { it.merchantId }.sortedBy { it })
    }

    @Test
    fun observeSearchResults_emitsMerchantsOfEveryRedeemTypeForTheMap() = runTest {
        val merchants = database.merchantDao().observeSearchResults(
            query = "merchant*",
            excludeType = "",
            paymentMethod = "",
            denomType = "",
            provider = "",
            northLat = 31.0,
            eastLng = -96.0,
            southLat = 29.0,
            westLng = -98.0
        ).first()

        assertEquals(everyRedeemType, merchants.map { it.merchantId }.sortedBy { it })
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
