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
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.TxId
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.money.Coin
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.money.TxIds
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.UnresolvedPaymentsProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.transactions.TransactionCategory
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProvider
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderDao
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderType
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.GiftCardResponse
import org.dash.wallet.features.exploredash.data.dashspend.model.UpdatedMerchantDetails
import org.dash.wallet.features.exploredash.data.explore.GiftCardDao
import org.dash.wallet.features.exploredash.data.explore.MerchantDao
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.network.service.ctxspend.CTXSpendApi
import org.dash.wallet.features.exploredash.repository.CTXSpendRepository
import org.dash.wallet.features.exploredash.repository.DashSpendRepository
import org.dash.wallet.features.exploredash.repository.DashSpendRepositoryFactory
import org.dash.wallet.features.exploredash.ui.dashspend.DashSpendViewModel
import org.dash.wallet.features.exploredash.ui.dashspend.GiftCardShoppingCart
import org.dash.wallet.features.exploredash.ui.dashspend.dialogs.GiftCardDetailsViewModel
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
    private val scheduler = TestCoroutineScheduler()

    @get:Rule var mainCoroutineRule = MainCoroutineRule(UnconfinedTestDispatcher(scheduler))

    private companion object {
        const val POLL_START_MILLIS = 2_000L
        const val IO_WAIT_MILLIS = 500L
    }

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

    private fun viewModel(
        piggyCardsRepository: DashSpendRepository,
        ctxRepository: DashSpendRepository = mock(),
        providers: List<GiftCardProvider> = listOf(cachedCtxRow, cachedPiggyCardsRow),
        giftCardDao: GiftCardDao = mock()
    ): DashSpendViewModel {
        val providerDao = mock<GiftCardProviderDao> {
            onBlocking {
                getProviderByMerchantId(eq(merchantId), eq(GiftCardProviderType.PiggyCards.name))
            } doReturn cachedPiggyCardsRow
            onBlocking { getProvidersByMerchantId(eq(merchantId)) } doReturn providers
        }
        val repositoryFactory = mock<DashSpendRepositoryFactory> {
            on { create(eq(GiftCardProviderType.CTX)) } doReturn ctxRepository
            on { create(eq(GiftCardProviderType.PiggyCards)) } doReturn piggyCardsRepository
        }

        return DashSpendViewModel(
            mock<WalletDataProvider> { on { observeTotalBalance() } doReturn flowOf(Dash.ZERO) },
            mock<ExchangeRatesProvider> { on { observeExchangeRate(any()) } doReturn emptyFlow() },
            mock<Configuration>(),
            mock<SendPaymentService>(),
            repositoryFactory,
            mock<TransactionMetadataProvider>(),
            giftCardDao,
            providerDao,
            mock<NetworkStateInt> { on { isConnected } doReturn MutableStateFlow(true) },
            mock<AnalyticsService>(),
            SavedStateHandle(),
            mock<MerchantDao>(),
            mock<CTXSpendConfig>(),
            mock<BlockchainStateProvider> {
                on { observeState() } doReturn emptyFlow()
                on { observeFirstSyncDone() } doReturn emptyFlow()
            },
            // nothing outstanding: these tests are about merchant details, and the view model
            // refuses to start a purchase until this says so explicitly
            mock<UnresolvedPaymentsProvider> {
                on { observeUnresolvedGiftCardPurchase() } doReturn flowOf(false)
            }
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

    @Test
    fun updateMerchantDetailsForAllProviders_liveTypeReplacesTheCachedOne() {
        // Regression test: the list and details subtitles read denominationsType from the
        // provider row this refresh writes. It used to carry only savings and availability, so a
        // merchant that had switched between fixed cards and a range still showed the explore
        // dataset's stale type while the purchase screen showed the live one.
        runBlocking {
            val ctx = mock<DashSpendRepository> {
                onBlocking { isUserSignedIn() } doReturn true
                onBlocking { getMerchant(eq("ctx-source")) } doReturn liveRangeCard.copy(id = "ctx-source")
            }
            // Not signed in, so this provider has no live data and must keep what it cached
            val piggyCards = mock<DashSpendRepository> {
                onBlocking { isUserSignedIn() } doReturn false
            }
            val viewModel = viewModel(piggyCards, ctxRepository = ctx)

            val updated = viewModel.updateMerchantDetailsForAllProviders(merchant())

            val refreshed = updated.giftCardProviders.first { it.provider == GiftCardProviderType.CTX.name }
            assertEquals("min-max", refreshed.denominationsType)
            assertEquals(175, refreshed.savingsPercentage)
            assertEquals(true, refreshed.active)

            val untouched = updated.giftCardProviders.first {
                it.provider == GiftCardProviderType.PiggyCards.name
            }
            assertEquals(cachedPiggyCardsRow, untouched)
        }
    }

    @Test
    fun ctxCheckout_keepsTheChallengeForTheReopenedCard() {
        runBlocking {
            val txId = TxIds.ZERO_HASH_HEX
            val link = "https://spend.ctx.com/gift-cards/order-1/redeem"
            val api = mock<CTXSpendApi> {
                onBlocking { purchaseGiftCard(any()) } doReturn GiftCardResponse(
                    id = "order-1",
                    status = "unpaid",
                    redeemUrl = link,
                    redeemUrlChallenge = "A1B2C3",
                    fiatAmount = "25.00"
                )
            }
            val config = mock<CTXSpendConfig> {
                onBlocking { getSecuredData(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN) } doReturn "token"
                onBlocking { getSecuredData(CTXSpendConfig.PREFS_KEY_CTX_PAY_EMAIL) } doReturn "user@example.com"
            }
            val repository = CTXSpendRepository(api, config, mock())
            val giftCardDao = mock<GiftCardDao>()

            val ordered = repository.orderGiftcard("DASH", "USD", GiftCardShoppingCart(25.0, 1), "ctx-source")
            viewModel(mock(), giftCardDao = giftCardDao).saveGiftCardDummy(txId, ordered)

            val saved = argumentCaptor<List<GiftCard>>()
            verify(giftCardDao).insertGiftCards(saved.capture())
            assertEquals(1, saved.firstValue.size)
            whenever(giftCardDao.observeCardForTransaction(TxIds.toBytes(txId))).thenReturn(flowOf(saved.firstValue))
            whenever(giftCardDao.getCardForTransaction(TxIds.toBytes(txId))).thenReturn(saved.firstValue)
            val metadataProvider = mock<TransactionMetadataProvider> {
                on { observeTransactionMetadata(TxId.wrap(txId)) } doReturn emptyFlow()
                onBlocking { getTransactionMetadata(TxId.wrap(txId)) } doReturn TransactionMetadata(
                    TxId.wrap(txId),
                    0,
                    Coin.ZERO,
                    TransactionCategory.Sent,
                    service = ServiceName.CTXSpend
                )
            }
            val reopened = GiftCardDetailsViewModel(
                mock(),
                mock(),
                giftCardDao,
                metadataProvider,
                mock(),
                repository,
                mock(),
                mock(),
                mock()
            )
            reopened.init(txId)

            val card = reopened.uiState.value.giftCard
            assertEquals(link, card?.merchantUrl)
            assertEquals("A1B2C3", card?.redeemUrlChallenge)
            // A saved link stops polling, so nothing else would deliver the challenge later.
            scheduler.advanceTimeBy(POLL_START_MILLIS)
            // A poll would reach the API from Dispatchers.IO, which virtual time does not wait for.
            verify(api, after(IO_WAIT_MILLIS).never()).getGiftCardByOrderId(any())
            verify(api, never()).getGiftCardByTxId(any())
        }
    }
}
