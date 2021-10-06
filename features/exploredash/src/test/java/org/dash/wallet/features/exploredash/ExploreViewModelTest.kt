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
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock


class ExploreViewModelTest {
    private val merchants = listOf(
        Merchant("", "2021-09-08 11:22", "2021-09-08 12:22", "Address1 1", "Address2 1", "Address3 1",	"Address4 1 Birmingham", 35.223312,	-119.130063, "Alabama", "", "online", "", "gift card").apply { id = 1; name = "Google Play"; active = true },
        Merchant("", "2021-09-09 11:23", "2021-09-09 12:23", "Address1 2", "Address2 2", "Address3 2",	"Address4 2 New Orleans", 35.223312,	-119.130063, "Louisiana", "", "both", "", "dash").apply { id = 2; name = "Amazon"; active = true },
        Merchant("", "2021-09-10 11:24", "2021-09-10 12:24", "Address1 3", "Address2 3", "Address3 3",	"Address4 3 Houston", 35.223312,	-119.130063, "Texas", "", "physical", "", "dash").apply { id = 3; name = "Bark Box"; active = false },
        Merchant("", "2021-09-11 11:25", "2021-09-11 12:25", "Address1 4", "Address2 4", "Address3 4",	"Address4 4 Austin", 35.223312,	-119.130063, "Texas", "", "physical", "", "gift card").apply { id = 4; name = "Dunkin Donuts"; active = true },
        Merchant("", "2021-09-11 11:25", "2021-09-11 12:25", "Address1 5", "Address2 5", "Address3 5",	"Address4 5", 35.223312,	-119.130063, "", "", "online", "", "dash").apply { id = 5; name = "Merchant 1"; active = true },
        Merchant("", "2021-09-11 11:25", "2021-09-11 12:25", "Address1 6", "Address2 6", "Address3 6",	"Address4 6", 35.223312,	-119.130063, "", "", "both", "", "gift card").apply { id = 6; name = "Merchant 2"; active = true },
        Merchant("", "2021-09-11 11:25", "2021-09-11 12:25", "Address1 7", "Address2 7", "Address3 7",	"Address4 7", 35.223312,	-119.130063, "Louisiana", "", "physical", "", "dash").apply { id = 7; name = "Merchant 3"; active = true },
    )

    private val repositoryMock = mock(MerchantRepository::class.java)
    private val daoMock = mock(MerchantDao::class.java)

    @Test
    fun filterByTerritoryIsCorrect() {
        runBlocking {
            val territory = "Texas"
            `when`(daoMock.observe(territory)).thenReturn(flow { emit(merchants.filter { it.territory == territory }) })

            val viewModel = ExploreViewModel(repositoryMock, daoMock)
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

            val viewModel = ExploreViewModel(repositoryMock, daoMock)
            viewModel.setFilterMode(ExploreViewModel.FilterMode.Online)
            viewModel.submitSearchQuery(query)

            // Should return a header and active online merchants matching query
            val expected = listOf(SearchResult("".hashCode(), true, "")) +
                    merchants.filter {
                        it.name?.lowercase()?.startsWith(query) ?: false &&
                                (it.type == "online" || it.type == "both") && it.active != false
                    }
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

            val viewModel = ExploreViewModel(repositoryMock, daoMock)
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