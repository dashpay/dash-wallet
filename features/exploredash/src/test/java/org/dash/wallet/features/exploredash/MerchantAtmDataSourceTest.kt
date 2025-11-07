/*
 * Copyright 2021 Dash Core Group.
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

import androidx.paging.PagingSource
import androidx.paging.PagingState
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderDao
import org.dash.wallet.features.exploredash.data.explore.AtmDao
import org.dash.wallet.features.exploredash.data.explore.MerchantAtmDataSource
import org.dash.wallet.features.exploredash.data.explore.MerchantDao
import org.dash.wallet.features.exploredash.data.explore.model.GeoBounds
import org.dash.wallet.features.exploredash.data.explore.model.MerchantInfo
import org.dash.wallet.features.exploredash.data.explore.model.MerchantType
import org.dash.wallet.features.exploredash.data.explore.model.SortOption
import org.dash.wallet.features.exploredash.ui.explore.DenomOption
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.*

class TestPagingSource : PagingSource<Int, MerchantInfo>() {
    override fun getRefreshKey(state: PagingState<Int, MerchantInfo>): Int? {
        return 0
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MerchantInfo> {
        return LoadResult.Page(listOf(), 0, 1)
    }
}

class MerchantDaoTest {
    private val atmDaoMock = mock<AtmDao>()
    private val merchantDaoMock =
        mock<MerchantDao> {
            on { pagingGetGrouped(any(), any(), any(), any(), any(), any(), any()) } doReturn TestPagingSource()
            on { pagingGetByTerritory(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } doReturn
                TestPagingSource()
            on {
                pagingGetByCoordinates(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } doReturn TestPagingSource()

            on {
                pagingSearchGrouped(any(), any(), any(), any(), any(), any(), any(), any())
            } doReturn TestPagingSource()
            on {
                pagingSearchByTerritory(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
                )
            } doReturn
                TestPagingSource()
            on {
                pagingSearchByCoordinates(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any()
                )
            } doReturn TestPagingSource()
        }
    private val giftCardProvidersMock = mock<GiftCardProviderDao>()
    private val dataSource = MerchantAtmDataSource(merchantDaoMock, atmDaoMock, giftCardProvidersMock)

    @Test
    fun observeAllPagingWithOnlineType_correctMethodCalled_querySanitized() {
        val dataSourceSpy = spy(dataSource)
        whenever(dataSourceSpy.sanitizeQuery(anyString())).thenCallRealMethod()

        val query = "querr"
        val sanitizedQuery = dataSource.sanitizeQuery(query)
        val requiredOnlineTypes = listOf(MerchantType.ONLINE, MerchantType.BOTH)

        // --- Online type with no query should call pagingGetGrouped method ---
        reset(dataSourceSpy)
        reset(merchantDaoMock)

        dataSourceSpy.observeMerchantsPaging(
            "",
            "",
            MerchantType.ONLINE,
            "",
            DenomOption.Both,
            "",
            GeoBounds.noBounds,
            SortOption.Name,
            0.0,
            0.0,
            false
        )

        verify(merchantDaoMock).pagingGetGrouped(requiredOnlineTypes, "", "", "", false, 0.0, 0.0)
        verifyNoMoreInteractions(merchantDaoMock)

        // --- Online type with query should call sanitizeQuery and pagingSearchGrouped methods ---
        reset(dataSourceSpy)
        reset(merchantDaoMock)

        dataSourceSpy.observeMerchantsPaging(
            query,
            "",
            MerchantType.ONLINE,
            "",
            DenomOption.Both,
            "",
            GeoBounds.noBounds,
            SortOption.Name,
            0.0,
            0.0,
            false
        )

        verify(dataSourceSpy).sanitizeQuery(query)
        verify(merchantDaoMock).pagingSearchGrouped(sanitizedQuery, requiredOnlineTypes, "", "", "", false, 0.0, 0.0)
        verifyNoMoreInteractions(merchantDaoMock)
    }

    @Test
    fun observeAllPagingWithPhysicalType_correctMethodCalled_querySanitized() {
        val dataSourceSpy = spy(dataSource)
        whenever(dataSourceSpy.sanitizeQuery(anyString())).thenCallRealMethod()

        val query = "querr"
        val sanitizedQuery = dataSource.sanitizeQuery(query)
        val requiredPhysicalTypes = listOf(MerchantType.PHYSICAL, MerchantType.BOTH)
        val requiredAllTypes = listOf(MerchantType.PHYSICAL, MerchantType.ONLINE, MerchantType.BOTH)

        // --- Physical type with bounds, no query and blank territory should call
        // pagingGetByCoordinates method ---
        val bounds = GeoBounds(20.0, 21.0, 22.0, 23.0, 0.0, 0.0)
        dataSourceSpy.observeMerchantsPaging(
            "", "", MerchantType.PHYSICAL, "", DenomOption.Both, "", bounds, SortOption.Name, 0.0, 0.0, false
        )

        verify(merchantDaoMock)
            .pagingGetByCoordinates(
                requiredPhysicalTypes,
                "",
                "",
                "",
                bounds.northLat,
                bounds.eastLng,
                bounds.southLat,
                bounds.westLng,
                0,
                0.0,
                0.0
            )
        verifyNoMoreInteractions(merchantDaoMock)

        // --- Physical type with bounds, query and blank territory should call sanitizeQuery and
        // pagingSearchByCoordinates methods ---
        reset(dataSourceSpy)
        reset(merchantDaoMock)

        dataSourceSpy.observeMerchantsPaging(
            query, "", MerchantType.PHYSICAL, "", DenomOption.Both, "", bounds, SortOption.Name, 0.0, 0.0, false
        )

        verify(dataSourceSpy).sanitizeQuery(query)
        verify(merchantDaoMock)
            .pagingSearchByCoordinates(
                sanitizedQuery,
                requiredPhysicalTypes,
                "",
                "",
                "",
                bounds.northLat,
                bounds.eastLng,
                bounds.southLat,
                bounds.westLng,
                0,
                0.0,
                0.0
            )
        verifyNoMoreInteractions(merchantDaoMock)

        // --- Both type with bounds, no query and territory should call pagingGetByTerritory method
        // ---
        reset(dataSourceSpy)
        reset(merchantDaoMock)
        val territory = "Kansas"

        dataSourceSpy.observeMerchantsPaging(
            "", territory, MerchantType.BOTH, "", DenomOption.Both, "", bounds, SortOption.Name, 0.0, 0.0, false
        )

        verify(merchantDaoMock).pagingGetByTerritory(territory, requiredAllTypes, "", "", "", 0, 0.0, 0.0, 1, 1)
        verifyNoMoreInteractions(merchantDaoMock)

        // --- Physical type with bounds, query and territory should call pagingSearchByTerritory
        // method ---
        reset(dataSourceSpy)
        reset(merchantDaoMock)

        dataSourceSpy.observeMerchantsPaging(
            query,
            territory,
            MerchantType.PHYSICAL,
            "",
            DenomOption.Both,
            "",
            bounds,
            SortOption.Name,
            0.0,
            0.0,
            false
        )

        verify(dataSourceSpy).sanitizeQuery(query)
        verify(merchantDaoMock)
            .pagingSearchByTerritory(sanitizedQuery, territory, requiredPhysicalTypes, "", "", "", 0, 0.0, 0.0, 2, 1)
        verifyNoMoreInteractions(merchantDaoMock)
    }

    @Test
    fun observeAllPagingWithBothType_correctMethodCalled_querySanitized() {
        val dataSourceSpy = spy(dataSource)
        whenever(dataSourceSpy.sanitizeQuery(anyString())).thenCallRealMethod()

        val query = "querr"
        val sanitizedQuery = dataSource.sanitizeQuery(query)
        val requiredAllTypes = listOf(MerchantType.PHYSICAL, MerchantType.ONLINE, MerchantType.BOTH)

        // --- All type with no query should call pagingGetByTerritory method ---
        reset(dataSourceSpy)
        reset(merchantDaoMock)

        dataSourceSpy.observeMerchantsPaging(
            "", "", MerchantType.BOTH, "", DenomOption.Both, "", GeoBounds.noBounds,
            SortOption.Name, 0.0, 0.0, false
        )

        verify(merchantDaoMock).pagingGetByTerritory("", requiredAllTypes, "", "", "", 0, 0.0, 0.0, 1, 1)
        verifyNoMoreInteractions(merchantDaoMock)

        // --- All type with query should call pagingGetByTerritory method ---
        reset(dataSourceSpy)
        reset(merchantDaoMock)

        dataSourceSpy.observeMerchantsPaging(
            query,
            "",
            MerchantType.BOTH,
            "",
            DenomOption.Both,
            "",
            GeoBounds.noBounds,
            SortOption.Name,
            0.0,
            0.0,
            false
        )

        verify(dataSourceSpy).sanitizeQuery(query)
        verify(merchantDaoMock).pagingSearchByTerritory(
            sanitizedQuery,
            "",
            requiredAllTypes,
            "",
            "",
            "",
            0,
            0.0,
            0.0,
            1,
            1
        )
        verifyNoMoreInteractions(merchantDaoMock)
    }
}
