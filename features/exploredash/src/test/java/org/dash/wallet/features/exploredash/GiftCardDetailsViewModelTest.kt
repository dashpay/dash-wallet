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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.TxId
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.money.Coin
import org.dash.wallet.common.money.TxIds
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.transactions.TransactionCategory
import org.dash.wallet.features.exploredash.data.dashspend.model.GiftCardInfo
import org.dash.wallet.features.exploredash.data.dashspend.model.GiftCardStatus
import org.dash.wallet.features.exploredash.data.explore.GiftCardDao
import org.dash.wallet.features.exploredash.repository.CTXSpendException
import org.dash.wallet.features.exploredash.repository.CTXSpendRepository
import org.dash.wallet.features.exploredash.repository.PiggyCardsRepository
import org.dash.wallet.features.exploredash.ui.dashspend.dialogs.GiftCardDetailsViewModel
import org.dash.wallet.features.exploredash.ui.dashspend.dialogs.GiftCardUIState
import org.dash.wallet.features.exploredash.ui.dashspend.redeem.RedeemLinkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class GiftCardDetailsViewModelTest {
    @get:Rule var mainCoroutineRule = MainCoroutineRule(StandardTestDispatcher())

    private val txId = TxIds.ZERO_HASH_HEX
    private val txIdBytes = TxIds.toBytes(txId)
    private val orderId = "order-1"
    private val pendingCard = GiftCard.fromHex(txId = txId, merchantName = "Target", price = 25.0, note = orderId)
    private val withoutDetails = GiftCardInfo(
        orderId,
        status = GiftCardStatus.FULFILLED,
        cardNumber = "",
        redeemUrl = "  "
    )
    private val limit = GiftCardDetailsViewModel.WAIT_LIMIT_FOR_ERROR

    private val repository = mock<CTXSpendRepository> {
        onBlocking { isUserSignedIn() } doReturn true
        onBlocking { getCTXSpendEmail() } doReturn "user@example.com"
        onBlocking { getGiftCard(orderId) } doReturn listOf(withoutDetails)
    }
    private val metadataProvider = metadataProvider(ServiceName.CTXSpend)

    private fun metadataProvider(service: String) = mock<TransactionMetadataProvider> {
        on { observeTransactionMetadata(TxId.wrap(txId)) } doReturn emptyFlow()
        onBlocking { getTransactionMetadata(TxId.wrap(txId)) } doReturn TransactionMetadata(
            TxId.wrap(txId),
            0,
            Coin.ZERO,
            TransactionCategory.Sent,
            service = service
        )
    }

    private fun TestScope.viewModel(
        savedCards: Flow<List<GiftCard>> = flowOf(listOf(pendingCard)),
        metadataProvider: TransactionMetadataProvider = this@GiftCardDetailsViewModelTest.metadataProvider,
        piggyCardsRepository: PiggyCardsRepository = mock()
    ): GiftCardDetailsViewModel {
        val giftCardDao = mock<GiftCardDao> {
            on { observeCardForTransaction(txIdBytes) } doReturn savedCards
            onBlocking { getCardForTransaction(txIdBytes) } doReturn listOf(pendingCard)
        }
        return GiftCardDetailsViewModel(
            mock(),
            CoroutineScope(coroutineContext),
            giftCardDao,
            metadataProvider,
            mock(),
            repository,
            piggyCardsRepository,
            mock(),
            mock()
        ).also { it.init(txId) }
    }

    // The fetch hops to Dispatchers.IO, so virtual time alone cannot tell when a poll has finished.
    private fun TestScope.pollUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            assertTrue("timed out waiting for the view model", System.currentTimeMillis() < deadline)
            advanceTimeBy(500)
            Thread.sleep(1)
        }
    }

    private fun TestScope.pollFor(virtualSeconds: Int) {
        repeat(virtualSeconds * 2) {
            advanceTimeBy(500)
            Thread.sleep(5)
        }
    }

    @Test
    fun fulfilledCardWithoutDetails_stopsPollingAndReportsItUnavailable() = runTest {
        val viewModel = viewModel()

        pollUntil { viewModel.uiState.value.queries >= limit - 1 }
        assertEquals(limit - 1, viewModel.uiState.value.queries)
        assertNull(viewModel.uiState.value.error)

        pollUntil { viewModel.uiState.value.error != null }

        val state = viewModel.uiState.value
        val error = state.error as CTXSpendException
        assertEquals(R.string.gift_card_details_unavailable, error.resourceString?.resourceId)
        assertEquals(GiftCardStatus.FULFILLED, state.status)
        assertTrue(state.isMissingDetails)
        assertEquals(limit, state.queries)

        pollFor(virtualSeconds = 10)
        verify(repository, times(limit)).getGiftCard(orderId)
    }

    @Test
    fun retry_pollsAgainAndSavesTheLinkOnceItArrives() = runTest {
        val viewModel = viewModel()
        pollUntil { viewModel.uiState.value.error != null }
        val link = "https://spend.ctx.com/gift-cards/order-1/redeem"
        whenever(repository.getGiftCard(orderId)).thenReturn(
            listOf(withoutDetails.copy(redeemUrl = link, redeemUrlChallenge = "A1B2C3"))
        )

        viewModel.retry()
        assertNull(viewModel.uiState.value.error)
        pollUntil { viewModel.uiState.value.queries > limit }

        val saved = argumentCaptor<GiftCard>()
        verify(metadataProvider).updateGiftCardMetadata(saved.capture())
        assertEquals(link, saved.lastValue.merchantUrl)
        assertEquals("A1B2C3", saved.lastValue.redeemUrlChallenge)
        assertNull(viewModel.uiState.value.error)
        pollFor(virtualSeconds = 5)
        verify(repository, times(limit + 1)).getGiftCard(orderId)
    }

    @Test
    fun retry_toleratesATransientFailureBeforeTheLinkArrives() = runTest {
        val viewModel = viewModel()
        pollUntil { viewModel.uiState.value.error != null }
        val link = "https://spend.ctx.com/gift-cards/order-1/redeem"
        whenever(repository.getGiftCard(orderId))
            .thenThrow(IllegalStateException("offline"))
            .thenReturn(listOf(withoutDetails.copy(redeemUrl = link)))

        viewModel.retry()
        pollUntil { viewModel.uiState.value.queries > limit || viewModel.uiState.value.error != null }

        assertNull(viewModel.uiState.value.error)
        val saved = argumentCaptor<GiftCard>()
        verify(metadataProvider).updateGiftCardMetadata(saved.capture())
        assertEquals(link, saved.lastValue.merchantUrl)
        verify(repository, times(limit + 2)).getGiftCard(orderId)
    }

    @Test
    fun piggyCardsOrderWithANumberCardAndALinkCard_savesBothAndStopsPolling() = runTest {
        val fakeNumber = "0000-FAKE-0000"
        val fakeLink = "https://claims.example.invalid/c/FAKE-CLAIM-0000"
        val piggyCards = mock<PiggyCardsRepository> {
            onBlocking { isUserSignedIn() } doReturn true
            onBlocking { getAccountEmail() } doReturn "user@example.com"
            onBlocking { getGiftCard(orderId) } doReturn listOf(
                GiftCardInfo(
                    orderId,
                    status = GiftCardStatus.FULFILLED,
                    barcodeUrl = null,
                    cardNumber = fakeNumber,
                    cardPin = "0000"
                ),
                GiftCardInfo(orderId, status = GiftCardStatus.FULFILLED, barcodeUrl = null, redeemUrl = fakeLink)
            )
        }
        val savedCards = MutableStateFlow(listOf(pendingCard, pendingCard.copy(index = 1)))
        val metadataProvider = metadataProvider(ServiceName.PiggyCards)
        whenever(metadataProvider.updateGiftCardMetadata(any())).doAnswer { call ->
            val updated = call.getArgument<GiftCard>(0)
            savedCards.update { cards -> cards.map { if (it.index == updated.index) updated else it } }
        }
        val viewModel = viewModel(savedCards, metadataProvider, piggyCards)

        pollUntil { viewModel.uiState.value.queries > 0 }
        pollFor(virtualSeconds = 5)

        val state = viewModel.uiState.value
        val numberCard = state.giftCards.single { it.index == 0 }
        val linkCard = state.giftCards.single { it.index == 1 }
        assertEquals(fakeNumber, numberCard.number)
        assertEquals("0000", numberCard.pin)
        assertNull(numberCard.merchantUrl)
        assertEquals(fakeLink, linkCard.merchantUrl)
        assertNull(linkCard.number)
        assertEquals(GiftCardStatus.FULFILLED, state.status)
        assertNull(state.error)
        assertFalse(state.copy(index = 0).isMissingDetails)
        assertFalse(state.copy(index = 1).isMissingDetails)
        verify(piggyCards, times(1)).getGiftCard(orderId)
        assertFalse(RedeemLinkPolicy.canOpenInApp(fakeLink, 36, "https://spend.ctx.com/"))
    }

    @Test
    fun missingDetails_isJudgedForTheCardOnShow() {
        val usable = pendingCard.copy(number = "6006491234", index = 1)
        val state = GiftCardUIState(giftCards = listOf(pendingCard, usable), status = GiftCardStatus.FULFILLED)

        assertTrue(state.copy(index = 0).isMissingDetails)
        assertFalse(state.copy(index = 1).isMissingDetails)
        assertFalse(state.copy(index = 0, status = GiftCardStatus.PAID).isMissingDetails)

        val blankLink = pendingCard.copy(merchantUrl = "   ")
        val link = pendingCard.copy(merchantUrl = "https://spend.ctx.com/gift-cards/order-1/redeem")
        assertTrue(state.copy(giftCards = listOf(blankLink)).isMissingDetails)
        assertFalse(state.copy(giftCards = listOf(link)).isMissingDetails)
    }
}
