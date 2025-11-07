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

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.features.exploredash.data.explore.ExploreDataSource
import org.dash.wallet.features.exploredash.data.explore.model.GeoBounds
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.data.explore.model.MerchantType
import org.dash.wallet.features.exploredash.data.explore.model.PaymentMethod
import org.dash.wallet.features.exploredash.data.explore.model.SortOption
import org.dash.wallet.features.exploredash.repository.DataSyncStatusService
import org.dash.wallet.features.exploredash.services.UserLocationStateInt
import org.dash.wallet.features.exploredash.ui.explore.DenomOption
import org.dash.wallet.features.exploredash.ui.explore.ExploreTopic
import org.dash.wallet.features.exploredash.ui.explore.ExploreViewModel
import org.dash.wallet.features.exploredash.ui.explore.FilterMode
import org.dash.wallet.features.exploredash.utils.ExploreConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.mockito.kotlin.*

@FlowPreview
@ExperimentalCoroutinesApi
class ExploreViewModelTest {
    private val merchants =
        listOf(
            Merchant(
                plusCode = "",
                addDate = "2021-09-08 11:22",
                updateDate = "2021-09-08 12:22",
                deeplink = "",
                paymentMethod = "gift card"
            ).apply {
                id = 1
                name = "Google Play"
                active = true
                address1 = "Address1 1"
                address2 = "Address2 1"
                address3 = "Address3 1"
                address4 = "Address4 1 Birmingham"
                latitude = 35.223312
                longitude = -119.130063
                territory = "Alabama"
                website = ""
                phone = ""
                type = "online"
                logoLocation = ""
            },
            Merchant(
                plusCode = "",
                addDate = "2021-09-09 11:23",
                updateDate = "2021-09-09 12:23",
                deeplink = "",
                paymentMethod = "dash"
            ).apply {
                id = 2
                name = "Amazon"
                active = true
                address1 = "Address1 2"
                address2 = "Address2 2"
                address3 = "Address3 2"
                address4 = "Address4 2 New Orleans"
                latitude = 35.223312
                longitude = -119.130063
                territory = "Louisiana"
                website = ""
                phone = ""
                type = "both"
                logoLocation = ""
            },
            Merchant(
                plusCode = "",
                addDate = "2021-09-10 11:24",
                updateDate = "2021-09-10 12:24",
                deeplink = "",
                paymentMethod = "dash"
            ).apply {
                id = 3
                name = "Bark Box"
                active = true
                address1 = "Address1 3"
                address2 = "Address2 3"
                address3 = "Address3 3"
                address4 = "Address4 3 Houston"
                latitude = 35.223312
                longitude = -119.130063
                territory = "Texas"
                website = ""
                phone = ""
                type = "physical"
                logoLocation = ""
            },
            Merchant(
                plusCode = "",
                addDate = "2021-09-11 11:25",
                updateDate = "2021-09-11 12:25",
                deeplink = "",
                paymentMethod = "gift card"
            ).apply {
                id = 4
                name = "Dunkin Donuts"
                active = true
                address1 = "Address1 4"
                address2 = "Address2 4"
                address3 = "Address3 4"
                address4 = "Address4 4 Austin"
                latitude = 35.223312
                longitude = -119.130063
                territory = "Texas"
                website = ""
                phone = ""
                type = "physical"
                logoLocation = ""
            },
            Merchant(
                plusCode = "",
                addDate = "2021-09-11 11:25",
                updateDate = "2021-09-11 12:25",
                deeplink = "",
                paymentMethod = "dash"
            ).apply {
                id = 5
                name = "Merchant 1"
                active = true
                address1 = "Address1 5"
                address2 = "Address2 5"
                address3 = "Address3 5"
                address4 = "Address4 5"
                latitude = 35.223312
                longitude = -119.130063
                territory = ""
                website = ""
                phone = ""
                type = "online"
                logoLocation = ""
            },
            Merchant(
                plusCode = "",
                addDate = "2021-09-11 11:25",
                updateDate = "2021-09-11 12:25",
                deeplink = "",
                paymentMethod = "gift card"
            ).apply {
                id = 6
                name = "Merchant 2"
                active = true
                address1 = "Address1 6"
                address2 = "Address2 6"
                address3 = "Address3 6"
                address4 = "Address4 6"
                latitude = 35.223312
                longitude = -119.130063
                territory = ""
                website = ""
                phone = ""
                type = "both"
                logoLocation = ""
            },
            Merchant(
                plusCode = "",
                addDate = "2021-09-11 11:25",
                updateDate = "2021-09-11 12:25",
                deeplink = "",
                paymentMethod = "dash"
            ).apply {
                id = 7
                name = "Merchant 3"
                active = true
                address1 = "Address1 7"
                address2 = "Address2 7"
                address3 = "Address3 7"
                address4 = "Address4 7"
                latitude = 35.223312
                longitude = -119.130063
                territory = "Louisiana"
                website = ""
                phone = ""
                type = "physical"
                logoLocation = ""
            },
            Merchant(
                plusCode = "",
                addDate = "",
                updateDate = "",
                deeplink = "https://dashspend.page.link/AMC",
                paymentMethod = "gift card"
            ).apply {
                id = 19630
                name = "AMC Theatres"
                active = true
                address1 = "3760 Princeton Lakes Pkwy."
                latitude = 33.658332
                longitude = -84.5100182
                website = "http://www.amctheatres.com"
                territory = "Georgia"
                city = "Atlanta"
                type = "both"
                logoLocation = "https://api.giftango.com/imageservice/Images/042507_logo_600x380.png"
            },
            Merchant(
                plusCode = "",
                addDate = "",
                updateDate = "",
                deeplink = "https://dashspend.page.link/Applebees",
                paymentMethod = "gift card"
            ).apply {
                id = 20826
                name = "Applebee's"
                active = true
                address1 = "New Spring Road S.E."
                latitude = 33.8846705
                longitude = -84.4731042
                website = "http://www.applebees.com"
                territory = "Georgia"
                city = "Atlanta"
                type = "both"
                logoLocation = "https://api.giftango.com/imageservice/Images/0117472_logo_600x380.png"
            }
        )

    @get:Rule var rule: TestRule = InstantTaskExecutorRule()

    private val networkState = mock<NetworkStateInt> {
        on { isConnected } doReturn MutableStateFlow(true)
    }

    private val mockPreferences = mock<ExploreConfig>().stub {
        onBlocking { get<Long>(any()) }.doReturn(-1L)
    }

    @Test
    fun filterByTerritoryIsCorrect() {
        runBlocking {
            val territory = "Texas"
            val dataSource =
                mock<ExploreDataSource> {
                    on { observePhysicalMerchants(eq(""), eq(territory), eq(""), any(), any(), any()) } doReturn
                        flow { emit(merchants.filter { it.territory == territory }) }
                }

            val locationState =
                mock<UserLocationStateInt> {
                    on { getRadiusBounds(eq(0.0), eq(0.0), any()) } doReturn GeoBounds.noBounds
                }

            val dataSyncStatus =
                mock<DataSyncStatusService> {
                    on { getSyncProgressFlow() } doReturn flow { emit(Resource.loading(50.0)) }
                    on { hasObservedLastError() } doReturn flow { emit(false) }
                }
            val analyticsService = mock<AnalyticsService>()

            val viewModel = ExploreViewModel(
                dataSource,
                locationState,
                dataSyncStatus,
                networkState,
                mockPreferences,
                analyticsService
            )
            viewModel.init(ExploreTopic.Merchants)
            viewModel.setFilterMode(FilterMode.All) // Set filter mode before other operations
            viewModel.setFilters("", territory, 5, SortOption.Name, DenomOption.Both)
            viewModel.searchBounds = GeoBounds.noBounds

            // Allow flows to emit by waiting briefly
            kotlinx.coroutines.delay(100)

            // Should return active Texas merchants
            val expected =
                merchants.filter { it.territory == territory && it.active != false && it.type != MerchantType.ONLINE }
            val actual = viewModel.boundedSearchFlow.first()

            assertEquals(expected, actual)
            verify(dataSource).observePhysicalMerchants("", territory, "", DenomOption.Both, "", GeoBounds.noBounds)
            verify(locationState).getRadiusBounds(0.0, 0.0, viewModel.radius)
            verifyNoMoreInteractions(dataSource)
        }
    }

    @Test
    fun filterByQueryAndDashPaymentTypeIsCorrect() {
        runBlocking {
            val query = "merch"
            val bounds = GeoBounds.noBounds.apply { zoomLevel = ExploreViewModel.MIN_ZOOM_LEVEL + 1 }

            val dataSource =
                mock<ExploreDataSource> {
                    on {
                        observePhysicalMerchants(eq(query), eq(""), eq(PaymentMethod.DASH), any(), any(), any())
                    } doReturn
                        flow {
                            emit(
                                merchants
                                    .filter { it.name?.lowercase()?.startsWith(query) ?: false }
                                    .filter { it.type != MerchantType.ONLINE }
                                    .filter { it.paymentMethod == PaymentMethod.DASH }
                            )
                        }
                }

            val locationState =
                mock<UserLocationStateInt> {
                    on { getRadiusBounds(eq(0.0), eq(0.0), any()) } doReturn GeoBounds.noBounds
                }

            val dataSyncStatus =
                mock<DataSyncStatusService> {
                    on { getSyncProgressFlow() } doReturn flow { emit(Resource.loading(50.0)) }
                    on { hasObservedLastError() } doReturn flow { emit(false) }
                }
            val analyticsService = mock<AnalyticsService>()

            val viewModel = ExploreViewModel(
                dataSource,
                locationState,
                dataSyncStatus,
                networkState,
                mockPreferences,
                analyticsService
            )
            viewModel.init(ExploreTopic.Merchants)
            viewModel.setFilterMode(FilterMode.Nearby)
            viewModel.searchBounds = bounds
            viewModel.setFilters(PaymentMethod.DASH, "", 20, SortOption.Name, DenomOption.Both)
            viewModel.submitSearchQuery(query)

            // Allow flows to emit by waiting briefly
            kotlinx.coroutines.delay(100)

            // Should return active physical merchants matching query and Dash payment method
            val expected =
                merchants
                    .filter { it.name?.lowercase()?.startsWith(query) ?: false }
                    .filter { (it.type == MerchantType.PHYSICAL || it.type == MerchantType.BOTH) }
                    .filter { it.active != false }
                    .filter { it.paymentMethod == PaymentMethod.DASH }
            val actual = viewModel.boundedSearchFlow.first()

            assertEquals(expected, actual)
            verify(dataSource).observePhysicalMerchants(query, "", PaymentMethod.DASH, DenomOption.Both, "", bounds)
            verify(locationState).getRadiusBounds(0.0, 0.0, viewModel.radius)
            verifyNoMoreInteractions(dataSource)
        }
    }

    @Test
    fun filterByQueryAndTerritoryIsCorrect() {
        runBlocking {
            val query = "mer"
            val territory = "Louisiana"

            val dataSource =
                mock<ExploreDataSource> {
                    on { observePhysicalMerchants(eq(query), eq(territory), eq(""), any(), any(), any()) } doReturn
                        flow {
                            emit(
                                merchants.filter {
                                    (it.name?.lowercase()?.startsWith(query) ?: false) && it.territory == territory
                                }
                            )
                        }
                }

            val locationState =
                mock<UserLocationStateInt> {
                    on { getRadiusBounds(eq(0.0), eq(0.0), any()) } doReturn GeoBounds.noBounds
                }

            val dataSyncStatus =
                mock<DataSyncStatusService> {
                    on { getSyncProgressFlow() } doReturn flow { emit(Resource.loading(50.0)) }
                    on { hasObservedLastError() } doReturn flow { emit(false) }
                }
            val analyticsService = mock<AnalyticsService>()

            val viewModel = ExploreViewModel(
                dataSource,
                locationState,
                dataSyncStatus,
                networkState,
                mockPreferences,
                analyticsService
            )
            viewModel.init(ExploreTopic.Merchants)
            viewModel.setFilterMode(FilterMode.All)
            viewModel.setFilters("", territory, 5, SortOption.Name, DenomOption.Both)
            viewModel.searchBounds = GeoBounds.noBounds
            viewModel.submitSearchQuery(query)

            // Allow flows to emit by waiting briefly
            kotlinx.coroutines.delay(100)

            // Should return active merchants matching query and territory
            val expected =
                merchants.filter {
                    it.name?.lowercase()?.startsWith(query) ?: false && it.territory == territory && it.active != false
                }
            val actual = viewModel.boundedSearchFlow.first()

            assertEquals(expected, actual)
            verify(dataSource).observePhysicalMerchants(query, territory, "", DenomOption.Both, "", GeoBounds.noBounds)
            verify(locationState).getRadiusBounds(0.0, 0.0, viewModel.radius)
            verifyNoMoreInteractions(dataSource)
        }
    }

    @Test
    fun filterByGeoBoundsIsCorrect() {
        runBlocking {
            val userLat = 33.712711
            val userLng = -84.4951037
            val bounds =
                GeoBounds(
                    northLat = 34.002174157200685,
                    eastLng = -84.14712188964452,
                    southLat = 33.423247842799306,
                    westLng = -84.8430855103555,
                    centerLat = userLat,
                    centerLng = userLng,
                    zoomLevel = ExploreViewModel.MIN_ZOOM_LEVEL + 1
                )

            val dataSource =
                mock<ExploreDataSource> {
                    on { observePhysicalMerchants(eq(""), eq(""), eq(""), any(), any(), any()) } doReturn
                        flow {
                            emit(
                                merchants
                                    .filter { it.type != MerchantType.ONLINE }
                                    .filter {
                                        (it.latitude ?: 0.0) < bounds.northLat &&
                                            (it.latitude ?: 0.0) > bounds.southLat &&
                                            (it.longitude ?: 0.0) < bounds.eastLng &&
                                            (it.longitude ?: 0.0) > bounds.westLng
                                    }
                            )
                        }
                }

            val locationMock =
                mock<UserLocationStateInt> { on { getRadiusBounds(eq(userLat), eq(userLng), any()) } doReturn bounds }
            val dataSyncStatus =
                mock<DataSyncStatusService> {
                    on { getSyncProgressFlow() } doReturn flow { emit(Resource.loading(50.0)) }
                    on { hasObservedLastError() } doReturn flow { emit(false) }
                }
            val analyticsService = mock<AnalyticsService>()

            val viewModel = ExploreViewModel(
                dataSource,
                locationMock,
                dataSyncStatus,
                networkState,
                mockPreferences,
                analyticsService
            )
            viewModel.init(ExploreTopic.Merchants)
            viewModel.setFilterMode(FilterMode.Nearby)
            viewModel.searchBounds =
                GeoBounds(90.0, 180.0, -90.0, -180.0, userLat, userLng).apply {
                    zoomLevel = ExploreViewModel.MIN_ZOOM_LEVEL + 1
                }

            // Allow flows to emit by waiting briefly
            kotlinx.coroutines.delay(100)

            val expected =
                merchants
                    .filter { (it.type == MerchantType.PHYSICAL || it.type == MerchantType.BOTH) }
                    .filter { it.active != false }
                    .filter {
                        (it.latitude ?: 0.0) < bounds.northLat &&
                            (it.latitude ?: 0.0) > bounds.southLat &&
                            (it.longitude ?: 0.0) < bounds.eastLng &&
                            (it.longitude ?: 0.0) > bounds.westLng
                    }
            val actual = viewModel.boundedSearchFlow.first()

            assertEquals(expected, actual)
            verify(dataSource).observePhysicalMerchants("", "", "", DenomOption.Both, "", bounds)
            verify(locationMock).getRadiusBounds(userLat, userLng, viewModel.radius)
            verifyNoMoreInteractions(dataSource)
        }
    }

    @Test
    fun onMapMarkerSelected_CorrectSelectedItem() {
        runBlocking {
            val locationMock = mock<UserLocationStateInt>()
            val dataSource = mock<ExploreDataSource>()
            val dataSyncStatus = mock<DataSyncStatusService> {
                on { getSyncProgressFlow() } doReturn flow { emit(Resource.loading(50.0)) }
                on { hasObservedLastError() } doReturn flow { emit(false) }
            }
            val analyticsService = mock<AnalyticsService>()

            val viewModel = ExploreViewModel(
                dataSource,
                locationMock,
                dataSyncStatus,
                networkState,
                mockPreferences,
                analyticsService
            )
            viewModel.setPhysicalResults(merchants)
            viewModel.onMapMarkerSelected(5)

            val expected = merchants.first { it.id == 5 }
            val actual = viewModel.selectedItem.value as Merchant

            assertEquals(expected, actual)

            verifyNoMoreInteractions(dataSource)
            verifyNoMoreInteractions(locationMock)
        }
    }
}
