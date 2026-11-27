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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.transactions.TransactionCategory
import org.dash.wallet.features.exploredash.data.dashspend.model.GiftCardInfo
import org.dash.wallet.features.exploredash.data.dashspend.model.GiftCardStatus
import org.dash.wallet.features.exploredash.data.explore.GiftCardDao
import org.dash.wallet.features.exploredash.repository.CTXSpendException
import org.dash.wallet.features.exploredash.repository.CTXSpendRepository
import org.dash.wallet.features.exploredash.ui.dashspend.dialogs.GiftCardDetailsViewModel
import org.dash.wallet.features.exploredash.ui.dashspend.dialogs.GiftCardUIState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class GiftCardDetailsViewModelTest {
    @get:Rule var mainCoroutineRule = MainCoroutineRule(StandardTestDispatcher())

    private val txId = Sha256Hash.ZERO_HASH
    private val orderId = "order-1"
    private val pendingCard = GiftCard(txId = txId, merchantName = "Target", price = 25.0, note = orderId)
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
    private val metadataProvider = mock<TransactionMetadataProvider> {
        on { observeTransactionMetadata(txId) } doReturn emptyFlow()
        onBlocking { getTransactionMetadata(txId) } doReturn TransactionMetadata(
            txId,
            0,
            Coin.ZERO,
            TransactionCategory.Sent,
            service = ServiceName.CTXSpend
        )
    }

    private fun TestScope.viewModel(): GiftCardDetailsViewModel {
        val giftCardDao = mock<GiftCardDao> {
            on { observeCardForTransaction(txId) } doReturn flowOf(listOf(pendingCard))
            onBlocking { getCardForTransaction(txId) } doReturn listOf(pendingCard)
        }
        return GiftCardDetailsViewModel(
            mock(),
            CoroutineScope(coroutineContext),
            giftCardDao,
            metadataProvider,
            mock(),
            repository,
            mock(),
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
