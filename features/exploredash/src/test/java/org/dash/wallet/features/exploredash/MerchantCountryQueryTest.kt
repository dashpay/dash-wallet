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
import kotlinx.coroutines.runBlocking
import org.dash.wallet.features.exploredash.data.explore.MerchantAtmDataSource
import org.dash.wallet.features.exploredash.data.explore.model.GeoBounds
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.data.explore.model.MerchantInfo
import org.dash.wallet.features.exploredash.data.explore.model.MerchantType
import org.dash.wallet.features.exploredash.data.explore.model.SortOption
import org.dash.wallet.features.exploredash.ui.explore.DenomOption
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * Gift card rows carry a country; pay-with-Dash rows and rows from a database older than
 * version 5 have none (NULL or blank) and must show under every country.
 */
@RunWith(RobolectricTestRunner::class)
class MerchantCountryQueryTest {
    private lateinit var database: ExploreDatabase
    private lateinit var dataSource: MerchantAtmDataSource

    private val noCountry = listOf("blank-online", "blank-store", "dash-online", "dash-store", "legacy-online")
    private val usOnly = listOf("us-online", "us-store") + noCountry
    private val everything = usOnly + listOf("gb-online", "gb-store")

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ExploreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dataSource = MerchantAtmDataSource(
            database.merchantDao(),
            database.atmDao(),
            database.giftCardProviderDao()
        )
        insert("us-online", "online", "gift card", "'US'", "'USD'", "Texas")
        insert("us-store", "physical", "gift card", "'US'", "'USD'", "Texas")
        insert("dash-online", "online", "dash", "NULL", "NULL", "Texas")
        insert("dash-store", "physical", "dash", "NULL", "NULL", "Texas")
        insert("legacy-online", "online", "gift card", "NULL", "NULL", "Texas")
        insert("blank-online", "online", "gift card", "''", "''", "Texas")
        insert("blank-store", "physical", "gift card", "' '", "''", "Texas")
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun insert(
        id: String,
        type: String,
        paymentMethod: String,
        country: String,
        currency: String,
        territory: String,
        name: String = "$id merchant"
    ) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO merchant " +
                "(merchantId, name, type, paymentMethod, territory, active, redeemType, latitude, longitude, " +
                "country, currency) " +
                "VALUES ('$id', '$name', '$type', '$paymentMethod', '$territory', 1, 'barcode', 10.0, 10.0, " +
                "$country, $currency)"
        )
    }

    private fun insertBritishMerchants() {
        insert("gb-online", "online", "gift card", "'GB'", "'GBP'", "London")
        insert("gb-store", "physical", "gift card", "'GB'", "'GBP'", "London")
    }

    private suspend fun PagingSource<Int, MerchantInfo>.ids(): List<String> {
        val page = load(PagingSource.LoadParams.Refresh(null, 100, false)) as PagingSource.LoadResult.Page
        return page.data.map { it.merchant!!.merchantId!! }.sorted()
    }

    private suspend fun listed(type: String, query: String, country: String): List<String> =
        dataSource.observeMerchantsPaging(
            query,
            "",
            type,
            "",
            DenomOption.Both,
            "",
            GeoBounds.noBounds,
            SortOption.Name,
            0.0,
            0.0,
            false,
            country
        ).ids()

    private suspend fun counted(type: String, query: String, country: String): Int =
        dataSource.getMerchantsResultCount(query, "", type, "", DenomOption.Both, "", GeoBounds.noBounds, country)

    private suspend fun PagingSource<Int, MerchantInfo>.sameNamedInOrder(): List<String> {
        val page = load(PagingSource.LoadParams.Refresh(null, 100, false)) as PagingSource.LoadResult.Page
        return page.data.map { it.merchant!!.merchantId!! }.filter { it.endsWith("-same") }
    }

    private fun insertSameNamedMerchants(type: String) {
        for (country in listOf("FR", "US", "GB", "DE")) {
            insert("${country.lowercase()}-same", type, "gift card", "'$country'", "''", "Texas", "Same")
        }
        insert("none-same", type, "gift card", "NULL", "NULL", "Texas", "Same")
    }

    private suspend fun sameNamedListed(
        type: String,
        query: String,
        sortOption: SortOption,
        bounds: GeoBounds = GeoBounds.noBounds,
        territory: String = ""
    ): List<String> =
        dataSource.observeMerchantsPaging(
            query,
            territory,
            type,
            "",
            DenomOption.Both,
            "",
            bounds,
            sortOption,
            10.0,
            10.0,
            false,
            ""
        ).sameNamedInOrder()

    private fun <T> withDeviceCountry(country: String, block: suspend () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale("en", country))
        return try {
            runBlocking { block() }
        } finally {
            Locale.setDefault(previous)
        }
    }

    private val britishDeviceOrder = listOf("gb-same", "us-same", "none-same", "de-same", "fr-same")
    private val otherDeviceOrder = listOf("us-same", "none-same", "de-same", "fr-same", "gb-same")

    @Test
    fun sameNamedMerchants_listTheDeviceCountryFirstThenUsThenByCode() {
        insertSameNamedMerchants("online")
        for (query in listOf("", "Same")) {
            for (type in listOf(MerchantType.ONLINE, "")) {
                for (sortOption in listOf(SortOption.Name, SortOption.Discount)) {
                    val case = "query '$query', type '$type', $sortOption"
                    assertEquals(
                        case,
                        britishDeviceOrder,
                        withDeviceCountry("GB") { sameNamedListed(type, query, sortOption) }
                    )
                    assertEquals(
                        case,
                        otherDeviceOrder,
                        withDeviceCountry("JP") { sameNamedListed(type, query, sortOption) }
                    )
                }
                assertEquals(
                    "the count must match the list for query '$query', type '$type'",
                    runBlocking { listed(type, query, "") }.size,
                    runBlocking { counted(type, query, "") }
                )
            }
        }
    }

    @Test
    fun sameNamedStores_keepThatOrderNearbyAndInATerritory() {
        insertSameNamedMerchants("physical")
        val around = GeoBounds(11.0, 11.0, 9.0, 9.0, 10.0, 10.0)
        for (query in listOf("", "Same")) {
            assertEquals(
                britishDeviceOrder,
                withDeviceCountry("GB") { sameNamedListed(MerchantType.PHYSICAL, query, SortOption.Name, around) }
            )
            assertEquals(
                otherDeviceOrder,
                withDeviceCountry("JP") { sameNamedListed(MerchantType.PHYSICAL, query, SortOption.Name, around) }
            )
            assertEquals(
                britishDeviceOrder,
                withDeviceCountry("GB") { sameNamedListed("", query, SortOption.Name, territory = "Texas") }
            )
        }
    }

    private fun onlineOf(ids: List<String>) = ids.filter { it.endsWith("-online") }.sorted()

    @Test
    fun onlineTab_listsTheSelectedCountryAndRowsWithoutOne() = runBlocking {
        insertBritishMerchants()

        for (query in listOf("", "merchant")) {
            assertEquals(onlineOf(usOnly), listed(MerchantType.ONLINE, query, "US"))
            assertEquals(onlineOf(noCountry + "gb-online"), listed(MerchantType.ONLINE, query, "GB"))
            assertEquals(4, counted(MerchantType.ONLINE, query, "US"))
            assertEquals(4, counted(MerchantType.ONLINE, query, "GB"))
        }
    }

    @Test
    fun allTab_listsTheSelectedCountryAndRowsWithoutOne() = runBlocking {
        insertBritishMerchants()

        for (query in listOf("", "merchant")) {
            assertEquals(usOnly.sorted(), listed(MerchantType.BOTH, query, "US"))
            assertEquals(
                (noCountry + listOf("gb-online", "gb-store")).sorted(),
                listed(MerchantType.BOTH, query, "GB")
            )
            assertEquals(7, counted(MerchantType.BOTH, query, "US"))
            assertEquals(7, counted(MerchantType.BOTH, query, "GB"))
        }
    }

    @Test
    fun aCountryWithNoMerchants_stillListsRowsWithoutACountry() = runBlocking {
        insertBritishMerchants()

        for (query in listOf("", "merchant")) {
            assertEquals(onlineOf(noCountry), listed(MerchantType.ONLINE, query, "FR"))
            assertEquals(3, counted(MerchantType.ONLINE, query, "FR"))
            assertEquals(noCountry, listed(MerchantType.BOTH, query, "FR"))
            assertEquals(5, counted(MerchantType.BOTH, query, "FR"))
        }
        assertEquals(listOf("blank-store", "dash-store"), physicalOnMap("FR"))
    }

    @Test
    fun usOnlyDatabase_usFilterChangesNothing() = runBlocking {
        for (type in listOf(MerchantType.ONLINE, MerchantType.BOTH)) {
            for (query in listOf("", "merchant")) {
                assertEquals(listed(type, query, ""), listed(type, query, "US"))
                assertEquals(counted(type, query, ""), counted(type, query, "US"))
            }
        }
        assertEquals(onlineOf(usOnly), listed(MerchantType.ONLINE, "", "US"))
        assertEquals(usOnly.sorted(), listed(MerchantType.BOTH, "", "US"))
        assertEquals(physicalOnMap(""), physicalOnMap("US"))
    }

    @Test
    fun noCountry_listsEveryCountry() = runBlocking {
        insertBritishMerchants()

        assertEquals(onlineOf(everything), listed(MerchantType.ONLINE, "", ""))
        assertEquals(everything.sorted(), listed(MerchantType.BOTH, "", ""))
        assertEquals(everything.size, counted(MerchantType.BOTH, "", ""))
    }

    @Test
    fun nearby_isNotFilteredByCountry() = runBlocking {
        insertBritishMerchants()
        val around = GeoBounds(11.0, 11.0, 9.0, 9.0, 10.0, 10.0)
        val dao = database.merchantDao()
        val types = listOf(MerchantType.PHYSICAL, MerchantType.BOTH)
        val stores = listOf("blank-store", "dash-store", "gb-store", "us-store")

        assertEquals(
            stores,
            dao.pagingGetByCoordinates(types, "", "", "", 11.0, 11.0, 9.0, 9.0, 0, 10.0, 10.0, "").ids()
        )
        assertEquals(
            stores,
            dao.pagingSearchByCoordinates("merchant*", types, "", "", "", 11.0, 11.0, 9.0, 9.0, 0, 10.0, 10.0, "").ids()
        )
        assertEquals(4, dao.getByCoordinatesResultCount(types, "", "", "", 11.0, 11.0, 9.0, 9.0))
        assertEquals(4, dao.searchByCoordinatesResultCount("merchant*", types, "", "", "", 11.0, 11.0, 9.0, 9.0))
        assertEquals(
            4,
            dataSource.getMerchantsResultCount("", "", MerchantType.PHYSICAL, "", DenomOption.Both, "", around, "GB")
        )
        assertEquals(
            stores,
            dataSource.observeMerchantsPaging(
                "",
                "",
                MerchantType.PHYSICAL,
                "",
                DenomOption.Both,
                "",
                around,
                SortOption.Name,
                10.0,
                10.0,
                false,
                "GB"
            ).ids()
        )
        assertEquals(stores, physicalOnMap("", around))
    }

    private suspend fun physicalOnMap(country: String, bounds: GeoBounds = GeoBounds.noBounds): List<String> =
        dataSource.observePhysicalMerchants("", "", "", DenomOption.Both, "", bounds, "", country)
            .first()
            .map { it.merchantId!! }
            .sorted()

    @Test
    fun mapMarkers_followTheCountryAndKeepRowsWithoutOne() = runBlocking {
        insertBritishMerchants()

        assertEquals(listOf("blank-store", "dash-store", "us-store"), physicalOnMap("US"))
        assertEquals(listOf("blank-store", "dash-store", "gb-store"), physicalOnMap("GB"))
        assertEquals(listOf("blank-store", "dash-store", "gb-store", "us-store"), physicalOnMap(""))
    }

    @Test
    fun getCountries_returnsEachNonBlankCountryOnce() = runBlocking {
        assertEquals(listOf("US"), dataSource.getMerchantCountries())

        insertBritishMerchants()

        assertEquals(listOf("GB", "US"), dataSource.getMerchantCountries().sorted())
    }

    @Test
    fun getCountries_isEmptyForADatabaseWithoutCountries() = runBlocking {
        database.openHelper.writableDatabase.execSQL("UPDATE merchant SET country = NULL")

        assertEquals(listOf<String>(), dataSource.getMerchantCountries())
    }

    @Test
    fun everyProjection_readsCountryAndCurrency() = runBlocking {
        insertBritishMerchants()
        val dao = database.merchantDao()
        val read = mutableListOf<Merchant>()

        read += dao.getMerchantById("gb-online")!!
        read += dao.getByTerritory("gb-online", "", "", listOf(MerchantType.ONLINE), "", "", "", 0, 0.0, 0.0, 1)
        read += dao.observeByTerritory("gb-online", "", "London", "", "", "", "", 1).first()
        read += dao.searchByTerritory("gb*", "London", MerchantType.ONLINE, "", "", "").first()
        read += dao.observe("gb-store", "", "", "", "", "", 11.0, 11.0, 9.0, 9.0, 1).first()
        read += dao.observeSearchResults("gb*", MerchantType.ONLINE, "", "", "", 11.0, 11.0, 9.0, 9.0).first()
        val page = dataSource.observeMerchantsPaging(
            "gb",
            "",
            MerchantType.BOTH,
            "",
            DenomOption.Both,
            "",
            GeoBounds.noBounds,
            SortOption.Name,
            0.0,
            0.0,
            false,
            "GB"
        ).load(PagingSource.LoadParams.Refresh(null, 100, false)) as PagingSource.LoadResult.Page
        read += page.data.map { it.merchant!! }

        assertEquals(8, read.size)
        read.forEach { merchant ->
            assertEquals(merchant.merchantId, "GB", merchant.country)
            assertEquals(merchant.merchantId, "GBP", merchant.currency)
        }

        val legacy = dao.getMerchantById("legacy-online")!!
        assertNull(legacy.country)
        assertNull(legacy.currency)
    }
}
