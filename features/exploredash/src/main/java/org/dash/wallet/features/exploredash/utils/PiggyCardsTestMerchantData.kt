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

package org.dash.wallet.features.exploredash.utils

data class PiggyCardsTestMerchantData(
    val merchantId: String,
    val name: String,
    val sourceId: String,
    val merchantSavingsPercentage: Int,
    val merchantDenominationsType: String,
    val providerSavingsPercentage: Int,
    val providerDenominationsType: String,
    val logoLocation: String,
    val website: String,
    val type: String = "online",
    val redeemType: String = "online",
    val paymentMethod: String = "gift card",
    val provider: String = "PiggyCards",
    val territory: String? = null,
    val city: String? = null
)

object PiggyCardsTestMerchants {
    const val PIGGY_CARDS_TEST_FIXED_MERCHANT_ID = "2e393eee-4508-47fe-954d-66209333fc96"
    const val PIGGY_CARDS_TEST_FLEXIBLE_MERCHANT_ID = "2e393fff-4508-47fe-954d-66209333fc96"
    const val APPLE_TEST_FLEXIBLE_MERCHANT_ID = "2e393ddd-4508-47fe-954d-66209333fc96"
    const val DOMINOS_TEST_FLEXIBLE_MERCHANT_ID = "2e393ccc-4508-47fe-954d-66209333fc96"
    const val HOME_DEPOT_TEST_FLEXIBLE_MERCHANT_ID = "2e393aaa-4508-47fe-954d-66209333fc96"

    val ALL: List<PiggyCardsTestMerchantData> = listOf(
        PiggyCardsTestMerchantData(
            merchantId = PIGGY_CARDS_TEST_FIXED_MERCHANT_ID,
            name = "Piggy Cards Test Merchant",
            sourceId = "177",
            merchantSavingsPercentage = -250,
            merchantDenominationsType = "Fixed",
            providerSavingsPercentage = 100,
            providerDenominationsType = "fixed",
            logoLocation = "https://piggy.cards/image/catalog/piggycards/logo2023_mobile.png",
            website = "https://piggy.cards",
            territory = "MA",
            city = "Boston"
        ),
        PiggyCardsTestMerchantData(
            merchantId = PIGGY_CARDS_TEST_FLEXIBLE_MERCHANT_ID,
            name = "Piggy Cards Flexible Test Merchant",
            sourceId = "177",
            merchantSavingsPercentage = -250,
            merchantDenominationsType = "min-max",
            providerSavingsPercentage = -250,
            providerDenominationsType = "min-max",
            logoLocation = "https://piggy.cards/image/catalog/piggycards/logo2023_mobile.png",
            website = "https://piggy.cards",
            territory = "MA",
            city = "Boston"
        ),
        PiggyCardsTestMerchantData(
            merchantId = HOME_DEPOT_TEST_FLEXIBLE_MERCHANT_ID,
            name = "Home Depot [Flexible]",
            sourceId = "74",
            merchantSavingsPercentage = 100,
            merchantDenominationsType = "min-max",
            providerSavingsPercentage = -50,
            providerDenominationsType = "min-max",
            logoLocation = "https://piggy.cards/image/catalog/piggycards/Home_Depot_Copy.jpg",
            website = "https://www.homedepot.com"
        ),
        PiggyCardsTestMerchantData(
            merchantId = APPLE_TEST_FLEXIBLE_MERCHANT_ID,
            name = "Apple [Flexible]",
            sourceId = "13",
            merchantSavingsPercentage = 100,
            merchantDenominationsType = "min-max",
            providerSavingsPercentage = 100,
            providerDenominationsType = "min-max",
            logoLocation = "https://piggy.cards/image/catalog/incenti/8aaa3d5d-logo.png",
            website = "https://www.apple.com"
        ),
        PiggyCardsTestMerchantData(
            merchantId = DOMINOS_TEST_FLEXIBLE_MERCHANT_ID,
            name = "Dominos [Flexible]",
            sourceId = "45",
            merchantSavingsPercentage = 100,
            merchantDenominationsType = "min-max",
            providerSavingsPercentage = 150,
            providerDenominationsType = "min-max",
            logoLocation = "https://piggy.cards/image/catalog/incenti/68ea431c-logo.png",
            website = "https://www.dominos.com"
        )
    )
}