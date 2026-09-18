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

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Coin
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProvider
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderDao
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderType
import org.dash.wallet.features.exploredash.data.dashspend.model.UpdatedMerchantDetails
import org.dash.wallet.features.exploredash.data.explore.GiftCardDao
import org.dash.wallet.features.exploredash.data.explore.MerchantDao
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.repository.DashSpendRepository
import org.dash.wallet.features.exploredash.repository.DashSpendRepositoryFactory
import org.dash.wallet.features.exploredash.ui.dashspend.DashSpendViewModel
import org.dash.wallet.features.exploredash.utils.CTXSpendConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.mockito.kotlin.*

/**
 * Covers the provider row that [DashSpendViewModel.updateMerchantDetails] refreshes.
 *
 * The purchase screen reads Fixed or Flexible from that row, so a cached type left over from the
 * explore dataset shows a range card as a pair of fixed denominations. The row is matched by
 * provider name rather than by position, which matters whenever the selected provider is not the
 * first one on the merchant.
 */
@FlowPreview
@ExperimentalCoroutinesApi
class DashSpendViewModelTest {
    @get:Rule var rule: TestRule = InstantTaskExecutorRule()
    @get:Rule var mainCoroutineRule = MainCoroutineRule()

    private val merchantId = "merchant-home-depot"

    // The explore dataset has this merchant selling fixed cards through both providers
    private val cachedCtxRow = GiftCardProvider(
        id = 1,
        merchantId = merchantId,
        provider = GiftCardProviderType.CTX.name,
        redeemType = "barcode",
        savingsPercentage = 250,
        active = true,
        denominationsType = "fixed",
        sourceId = "ctx-source"
    )
    private val cachedPiggyCardsRow = cachedCtxRow.copy(
        id = 2,
        provider = GiftCardProviderType.PiggyCards.name,
        savingsPercentage = 100,
        sourceId = "piggycards-source"
    )

    // ...while PiggyCards' API currently sells a $3 to $2,000 range
    private val liveRangeCard = UpdatedMerchantDetails(
        id = "piggycards-source",
        denominations = listOf(3.0, 2000.0),
        denominationsType = "min-max",
        savingsPercentage = 175,
        redeemType = "barcode",
        enabled = true
    )

    private fun merchant() = Merchant(
        plusCode = "",
        addDate = "2021-09-08 11:22",
        updateDate = "2021-09-08 12:22",
        deeplink = "",
        paymentMethod = "gift card"
    ).apply {
        id = 1
        this.merchantId = this@DashSpendViewModelTest.merchantId
        name = "Home Depot"
        active = true
        sourceId = "piggycards-source"
        // CTX first, so a provider matched by position would pick the wrong row
        giftCardProviders = listOf(cachedCtxRow, cachedPiggyCardsRow)
    }

    private fun viewModel(piggyCardsRepository: DashSpendRepository): DashSpendViewModel {
        val providerDao = mock<GiftCardProviderDao> {
            onBlocking {
                getProviderByMerchantId(eq(merchantId), eq(GiftCardProviderType.PiggyCards.name))
            } doReturn cachedPiggyCardsRow
        }
        val repositoryFactory = mock<DashSpendRepositoryFactory> {
            on { create(eq(GiftCardProviderType.CTX)) } doReturn mock<DashSpendRepository>()
            on { create(eq(GiftCardProviderType.PiggyCards)) } doReturn piggyCardsRepository
        }

        return DashSpendViewModel(
            mock<WalletDataProvider> { on { observeSpendableBalance() } doReturn flowOf(Coin.ZERO) },
            mock<ExchangeRatesProvider> { on { observeExchangeRate(any()) } doReturn emptyFlow() },
            mock<Configuration>(),
            mock<SendPaymentService>(),
            repositoryFactory,
            mock<TransactionMetadataProvider>(),
            mock<GiftCardDao>(),
            providerDao,
            mock<NetworkStateInt> { on { isConnected } doReturn MutableStateFlow(true) },
            mock<AnalyticsService>(),
            SavedStateHandle(),
            mock<MerchantDao>(),
            mock<CTXSpendConfig>(),
            mock<BlockchainStateProvider> { on { observeState() } doReturn emptyFlow() }
        )
    }

    @Test
    fun updateMerchantDetails_selectedProviderIsNotFirst_refreshesThatRowFromLiveData() {
        runBlocking {
            val piggyCards = mock<DashSpendRepository> {
                onBlocking { getMerchant(eq("piggycards-source")) } doReturn liveRangeCard
            }
            val viewModel = viewModel(piggyCards)
            viewModel.selectedProvider = GiftCardProviderType.PiggyCards

            val updated = viewModel.updateMerchantDetails(merchant())

            val refreshed = updated.giftCardProviders.first {
                it.provider == GiftCardProviderType.PiggyCards.name
            }
            assertEquals("min-max", refreshed.denominationsType)
            assertEquals(175, refreshed.savingsPercentage)
            assertEquals(true, refreshed.active)

            val untouched = updated.giftCardProviders.first { it.provider == GiftCardProviderType.CTX.name }
            assertEquals(cachedCtxRow, untouched)

            // The purchase screen reads the range and the mode from the merchant itself
            assertFalse(updated.fixedDenomination)
            assertEquals("min-max", updated.denominationsType)
            assertEquals(3.0, updated.minCardPurchase!!, 0.0)
            assertEquals(2000.0, updated.maxCardPurchase!!, 0.0)
        }
    }
}
