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

package org.dash.wallet.features.exploredash

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.dash.wallet.features.exploredash.data.MerchantDao
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.data.model.SearchResult
import org.dash.wallet.features.exploredash.repository.MerchantRepository
import org.dash.wallet.features.exploredash.ui.ExploreViewModel
import org.dash.wallet.features.exploredash.ui.UserLocationState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock


class ExploreViewModelTest {
    private val merchants = listOf(
        Merchant(plusCode = "", addDate = "2021-09-08 11:22", updateDate = "2021-09-08 12:22", address1 = "Address1 1", address2 = "Address2 1", address3 = "Address3 1", address4 = "Address4 1 Birmingham", latitude = 35.223312,	longitude = -119.130063, territory = "Alabama", website = "", deeplink = "", phone = "", type = "online", logoLocation = "", paymentMethod= "gift card").apply { id = 1; name = "Google Play"; active = true },
        Merchant(plusCode = "", addDate = "2021-09-09 11:23", updateDate ="2021-09-09 12:23", address1 = "Address1 2", address2 = "Address2 2", address3 = "Address3 2", address4 = "Address4 2 New Orleans", latitude = 35.223312,	longitude = -119.130063, territory = "Louisiana",  website ="", deeplink = "", phone ="",type="both", logoLocation="", paymentMethod = "dash").apply { id = 2; name = "Amazon"; active = true },
        Merchant(plusCode = "", addDate = "2021-09-10 11:24", updateDate ="2021-09-10 12:24", address1 = "Address1 3", address2 = "Address2 3", address3 = "Address3 3", address4 = "Address4 3 Houston", latitude = 35.223312,	longitude = -119.130063, territory = "Texas",  website ="", deeplink="",phone="", type="physical", logoLocation = "", paymentMethod = "dash").apply { id = 3; name = "Bark Box"; active = false },
        Merchant(plusCode = "", addDate = "2021-09-11 11:25", updateDate ="2021-09-11 12:25", address1 = "Address1 4", address2 = "Address2 4", address3 = "Address3 4", address4 = "Address4 4 Austin", latitude = 35.223312,	longitude = -119.130063, territory = "Texas",  website ="", deeplink="",phone="",type="physical", logoLocation = "", paymentMethod = "gift card").apply { id = 4; name = "Dunkin Donuts"; active = true },
        Merchant(plusCode = "", addDate = "2021-09-11 11:25", updateDate ="2021-09-11 12:25", address1 = "Address1 5", address2 = "Address2 5", address3 = "Address3 5", address4 = "Address4 5", latitude = 35.223312,	longitude = -119.130063, territory = "",  website ="", deeplink="",phone="",type="online", logoLocation = "", paymentMethod = "dash").apply { id = 5; name = "Merchant 1"; active = true },
        Merchant(plusCode = "", addDate = "2021-09-11 11:25", updateDate ="2021-09-11 12:25", address1 = "Address1 6", address2 = "Address2 6", address3 = "Address3 6", address4 = "Address4 6", latitude = 35.223312,	longitude = -119.130063, territory = "",  website ="", deeplink = "",phone="",type="both", logoLocation = "", paymentMethod = "gift card").apply { id = 6; name = "Merchant 2"; active = true },
        Merchant(plusCode = "", addDate = "2021-09-11 11:25", updateDate ="2021-09-11 12:25", address1 = "Address1 7", address2 = "Address2 7", address3 = "Address3 7", address4 = "Address4 7", latitude = 35.223312,	longitude = -119.130063, territory = "Louisiana",  website ="", deeplink = "",phone="",type="physical", logoLocation = "", paymentMethod = "dash").apply { id = 7; name = "Merchant 3"; active = true },
    )

    private val repositoryMock = mock(MerchantRepository::class.java)
    private val daoMock = mock(MerchantDao::class.java)
    private val locationState = mock(UserLocationState::class.java)
    @Test
    fun filterByTerritoryIsCorrect() {
        runBlocking {
            val territory = "Texas"
            `when`(daoMock.observe(territory)).thenReturn(flow { emit(merchants.filter { it.territory == territory }) })

            val viewModel = ExploreViewModel(repositoryMock, daoMock, locationState)
            viewModel.pickedTerritory = territory
            viewModel.setFilterMode(ExploreViewModel.FilterMode.All)

            // Should return a header and active Texas merchants
            val expected = listOf(SearchResult(territory.hashCode(), true, territory)) +
                    merchants.filter { it.territory == territory && it.active != false }
            val actual = viewModel.searchFilterFlow.first()

            assertEquals(expected, actual)
        }
    }

    @Test
    fun filterByQueryAndOnlineTypeIsCorrect() {
        runBlocking {
            val query = "merch"
            `when`(daoMock.observeSearchResults("\"$query*\"", "")).thenReturn(flow { emit(merchants.filter {
                it.name?.lowercase()?.startsWith(query) ?: false
            }) })

            val viewModel = ExploreViewModel(repositoryMock, daoMock, locationState)
            viewModel.setFilterMode(ExploreViewModel.FilterMode.Online)
            viewModel.submitSearchQuery(query)

            // Should return a header and active online merchants matching query
            val headers = listOf(SearchResult("".hashCode(), true, ""))
            val f1 = merchants.filter {
                        it.name?.lowercase()?.startsWith(query) ?: false
                    }
            val f2 = f1.filter { (it.type == "online" || it.type == "both") }
            val f3 = f2.filter { it.active != false }
            val expected = headers + f3
            val actual = viewModel.searchFilterFlow.first()

            assertEquals(expected, actual)
        }
    }

    @Test
    fun filterByQueryAndTerritoryIsCorrect() {
        runBlocking {
            val query = "mer"
            val territory = "Louisiana"

            `when`(daoMock.observeSearchResults("\"$query*\"", territory)).thenReturn(flow { emit(merchants.filter {
                (it.name?.lowercase()?.startsWith(query) ?: false) && it.territory == territory
            }) })

            val viewModel = ExploreViewModel(repositoryMock, daoMock, locationState)
            viewModel.pickedTerritory = territory
            viewModel.submitSearchQuery(query)
            viewModel.setFilterMode(ExploreViewModel.FilterMode.All)

            // Should return a header and active merchants matching query and territory
            val expected = listOf(SearchResult(territory.hashCode(), true, territory)) +
                    merchants.filter {
                        it.name?.lowercase()?.startsWith(query) ?: false &&
                                it.territory == territory && it.active != false
                    }
            val actual = viewModel.searchFilterFlow.first()

            assertEquals(expected, actual)
        }
    }
}