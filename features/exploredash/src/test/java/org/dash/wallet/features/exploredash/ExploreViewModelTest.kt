///*
// * Copyright 2021 Dash Core Group.
// *
// * This program is free software: you can redistribute it and/or modify
// * it under the terms of the GNU General Public License as published by
// * the Free Software Foundation, either version 3 of the License, or
// * (at your option) any later version.
// *
// * This program is distributed in the hope that it will be useful,
// * but WITHOUT ANY WARRANTY; without even the implied warranty of
// * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// * GNU General Public License for more details.
// *
// * You should have received a copy of the GNU General Public License
// * along with this program.  If not, see <http://www.gnu.org/licenses/>.
// */
//
//package org.dash.wallet.features.exploredash
//
//import kotlinx.coroutines.flow.first
//import kotlinx.coroutines.flow.flow
//import kotlinx.coroutines.runBlocking
//import org.dash.wallet.features.exploredash.data.AtmDao
//import org.dash.wallet.features.exploredash.data.MerchantDao
//import org.dash.wallet.features.exploredash.data.model.Merchant
//import org.dash.wallet.features.exploredash.services.UserLocationState
//import org.dash.wallet.features.exploredash.ui.ExploreViewModel
//import org.dash.wallet.features.exploredash.ui.FilterMode
//import org.junit.Assert.assertEquals
//import org.junit.Test
//import org.mockito.Mockito.`when`
//import org.mockito.Mockito.mock
//
//
//class ExploreViewModelTest {
//    private val merchants = listOf(
//        Merchant(plusCode = "", addDate = "2021-09-08 11:22", updateDate = "2021-09-08 12:22", deeplink = "", paymentMethod= "gift card").apply { id = 1; name = "Google Play"; active = true; address1 = "Address1 1"; address2 = "Address2 1"; address3 = "Address3 1"; address4 = "Address4 1 Birmingham"; latitude = 35.223312;	longitude = -119.130063; territory = "Alabama"; website = ""; phone = ""; type = "online"; logoLocation = "" },
//        Merchant(plusCode = "", addDate = "2021-09-09 11:23", updateDate ="2021-09-09 12:23", deeplink = "", paymentMethod = "dash").apply { id = 2; name = "Amazon"; active = true; address1 = "Address1 2"; address2 = "Address2 2"; address3 = "Address3 2"; address4 = "Address4 2 New Orleans"; latitude = 35.223312; longitude = -119.130063; territory = "Louisiana"; website =""; phone =""; type="both"; logoLocation="" },
//        Merchant(plusCode = "", addDate = "2021-09-10 11:24", updateDate ="2021-09-10 12:24", deeplink="", paymentMethod = "dash").apply { id = 3; name = "Bark Box"; active = false; address1 = "Address1 3"; address2 = "Address2 3"; address3 = "Address3 3"; address4 = "Address4 3 Houston"; latitude = 35.223312;	longitude = -119.130063; territory = "Texas"; website =""; phone=""; type="physical"; logoLocation = "" },
//        Merchant(plusCode = "", addDate = "2021-09-11 11:25", updateDate ="2021-09-11 12:25", deeplink="", paymentMethod = "gift card").apply { id = 4; name = "Dunkin Donuts"; active = true; address1 = "Address1 4"; address2 = "Address2 4"; address3 = "Address3 4"; address4 = "Address4 4 Austin"; latitude = 35.223312;	longitude = -119.130063; territory = "Texas"; website =""; phone=""; type="physical"; logoLocation = "" },
//        Merchant(plusCode = "", addDate = "2021-09-11 11:25", updateDate ="2021-09-11 12:25", deeplink="", paymentMethod = "dash").apply { id = 5; name = "Merchant 1"; active = true; address1 = "Address1 5"; address2 = "Address2 5"; address3 = "Address3 5"; address4 = "Address4 5"; latitude = 35.223312; longitude = -119.130063; territory = ""; website =""; phone=""; type="online"; logoLocation = "" },
//        Merchant(plusCode = "", addDate = "2021-09-11 11:25", updateDate ="2021-09-11 12:25", deeplink = "", paymentMethod = "gift card").apply { id = 6; name = "Merchant 2"; active = true; address1 = "Address1 6"; address2 = "Address2 6"; address3 = "Address3 6"; address4 = "Address4 6"; latitude = 35.223312;	longitude = -119.130063; territory = ""; website =""; phone=""; type="both"; logoLocation = "" },
//        Merchant(plusCode = "", addDate = "2021-09-11 11:25", updateDate ="2021-09-11 12:25", deeplink = "", paymentMethod = "dash").apply { id = 7; name = "Merchant 3"; active = true; address1 = "Address1 7"; address2 = "Address2 7"; address3 = "Address3 7"; address4 = "Address4 7"; latitude = 35.223312; longitude = -119.130063; territory = "Louisiana"; website =""; phone=""; type="physical"; logoLocation = ""  },
//    )
//
//    private val merchantDaoMock = mock(MerchantDao::class.java)
//    private val atmDaoMock = mock(AtmDao::class.java)
//    private val locationState = mock(UserLocationState::class.java)
//
//    @Test
//    fun filterByTerritoryIsCorrect() {
//        runBlocking {
//            val territory = "Texas"
//            `when`(merchantDaoMock.observePhysical(territory)).thenReturn(flow { emit(merchants.filter { it.territory == territory }) })
//
//            val viewModel = ExploreViewModel(merchantDaoMock, atmDaoMock, locationState)
//            viewModel.selectedTerritory = territory
//            viewModel.setFilterMode(FilterMode.All)
//
//            // Should return a header and active Texas merchants
//            val expected = merchants.filter { it.territory == territory && it.active != false }
//            val actual = viewModel.boundedSearchFlow.first()
//
//            assertEquals(expected, actual)
//        }
//    }
//
//    @Test
//    fun filterByQueryAndOnlineTypeIsCorrect() {
//        runBlocking {
//            val query = "merch"
//            `when`(merchantDaoMock.observeSearchResults("\"$query*\"", "")).thenReturn(flow { emit(merchants.filter {
//                it.name?.lowercase()?.startsWith(query) ?: false
//            }) })
//
//            val viewModel = ExploreViewModel(merchantDaoMock, atmDaoMock, locationState)
//            viewModel.setFilterMode(FilterMode.Online)
//            viewModel.submitSearchQuery(query)
//
//            // Should return active online merchants matching query
//            val expected = merchants
//                .filter { it.name?.lowercase()?.startsWith(query) ?: false }
//                .filter { (it.type == "online" || it.type == "both") }
//                .filter { it.active != false }
//            val actual = viewModel.boundedSearchFlow.first()
//
//            assertEquals(expected, actual)
//        }
//    }
//
//    @Test
//    fun filterByQueryAndTerritoryIsCorrect() {
//        runBlocking {
//            val query = "mer"
//            val territory = "Louisiana"
//
//            `when`(merchantDaoMock.observeSearchResults("\"$query*\"", territory)).thenReturn(flow { emit(merchants.filter {
//                (it.name?.lowercase()?.startsWith(query) ?: false) && it.territory == territory
//            }) })
//
//            val viewModel = ExploreViewModel(merchantDaoMock, atmDaoMock, locationState)
//            viewModel.selectedTerritory = territory
//            viewModel.submitSearchQuery(query)
//            viewModel.setFilterMode(FilterMode.All)
//
//            // Should return active merchants matching query and territory
//            val expected = merchants.filter {
//                it.name?.lowercase()?.startsWith(query) ?: false &&
//                        it.territory == territory && it.active != false
//            }
//            val actual = viewModel.boundedSearchFlow.first()
//
//            assertEquals(expected, actual)
//        }
//    }
//}