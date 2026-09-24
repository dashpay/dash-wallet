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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.PaymentSubmissionPendingException
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.UnresolvedPaymentsProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderDao
import org.dash.wallet.features.exploredash.data.explore.GiftCardDao
import org.dash.wallet.features.exploredash.data.explore.MerchantDao
import org.dash.wallet.features.exploredash.repository.DashSpendRepositoryFactory
import org.dash.wallet.features.exploredash.ui.dashspend.DashSpendViewModel
import org.dash.wallet.features.exploredash.ui.dashspend.DuplicateGiftCardSubmissionException
import org.dash.wallet.features.exploredash.ui.dashspend.GiftCardSubmissionState
import org.dash.wallet.features.exploredash.utils.CTXSpendConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Covers the two things that must stop a second order being placed for a payment the merchant may
 * already hold: the in-memory state a new purchase screen inherits, and the record on disk that is
 * the only thing left once the process has died.
 */
@ExperimentalCoroutinesApi
class DashSpendSubmissionStateTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    private val paymentUri = "dash:yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL?amount=1"

    /** Stands in for what the wallet has stored about payments that outlived their screen. */
    private val unresolvedOnDisk = MutableStateFlow(false)

    private lateinit var sendPaymentService: SendPaymentService
    private lateinit var unresolvedPayments: UnresolvedPaymentsProvider

    @Before
    fun setUp() {
        sendPaymentService = mockk(relaxed = true)
        unresolvedPayments = mockk(relaxed = true)
        every { unresolvedPayments.observeUnresolvedGiftCardPurchase() } returns unresolvedOnDisk
        coEvery { unresolvedPayments.hasUnresolvedGiftCardPurchase() } answers { unresolvedOnDisk.value }
    }

    private fun createViewModel(): DashSpendViewModel {
        val walletDataProvider = mockk<WalletDataProvider>(relaxed = true)
        every { walletDataProvider.observeSpendableBalance() } returns MutableStateFlow(Coin.COIN)

        val exchangeRates = mockk<ExchangeRatesProvider>(relaxed = true)
        every { exchangeRates.observeExchangeRate(any()) } returns emptyFlow()

        val networkState = mockk<NetworkStateInt>(relaxed = true)
        every { networkState.isConnected } returns MutableStateFlow(true)

        val blockchainStateProvider = mockk<BlockchainStateProvider>(relaxed = true)
        every { blockchainStateProvider.observeState() } returns emptyFlow()

        return DashSpendViewModel(
            walletDataProvider,
            exchangeRates,
            mockk<Configuration>(relaxed = true),
            sendPaymentService,
            mockk<DashSpendRepositoryFactory>(relaxed = true),
            mockk<TransactionMetadataProvider>(relaxed = true),
            mockk<GiftCardDao>(relaxed = true),
            mockk<GiftCardProviderDao>(relaxed = true),
            networkState,
            mockk<AnalyticsService>(relaxed = true),
            SavedStateHandle(),
            mockk<MerchantDao>(relaxed = true),
            mockk<CTXSpendConfig>(relaxed = true),
            blockchainStateProvider,
            unresolvedPayments
        )
    }

    /** Submits a payment whose result never came back, leaving the purchase unresolved. */
    private suspend fun submitAmbiguously(viewModel: DashSpendViewModel) {
        coEvery { sendPaymentService.payWithDashUrl(any(), any(), any(), any()) } throws
            PaymentSubmissionPendingException(Sha256Hash.ZERO_HASH, null)

        try {
            viewModel.payAndRecordOrder(paymentUri, emptyList())
            fail("an ambiguous submission was reported as successful")
        } catch (expected: PaymentSubmissionPendingException) {
            // what an ambiguous submission looks like to the screen
        }
    }

    @Test
    fun `opening the purchase screen again does not clear an unresolved submission`() = runBlocking {
        val viewModel = createViewModel()
        submitAmbiguously(viewModel)
        assertEquals(GiftCardSubmissionState.PENDING, viewModel.submissionState.value)

        // an ambiguous submission dismisses the flow, so the next screen is always a new instance
        viewModel.beginNewPurchase()

        assertEquals(GiftCardSubmissionState.PENDING, viewModel.submissionState.value)
        assertFalse(
            "a new purchase screen handed back the right to submit a second order",
            viewModel.tryStartSubmission()
        )
    }

    @Test
    fun `a completed purchase still lets the next screen start a new one`() = runBlocking {
        val viewModel = createViewModel()
        val transaction = mockk<Transaction>(relaxed = true)
        every { transaction.txId } returns Sha256Hash.ZERO_HASH
        coEvery { sendPaymentService.payWithDashUrl(any(), any(), any(), any()) } returns transaction

        viewModel.payAndRecordOrder(paymentUri, emptyList())
        assertEquals(GiftCardSubmissionState.COMPLETED, viewModel.submissionState.value)

        viewModel.beginNewPurchase()

        assertEquals(GiftCardSubmissionState.IDLE, viewModel.submissionState.value)
        assertTrue(viewModel.tryStartSubmission())
    }

    @Test
    fun `a purchase left unresolved by an earlier process blocks a rebuilt view model`() = runBlocking {
        // nothing in memory survived the restart; only the stored record says a payment is out
        unresolvedOnDisk.value = true

        val viewModel = createViewModel()

        assertEquals(GiftCardSubmissionState.PENDING, viewModel.submissionState.value)
        assertFalse(viewModel.tryStartSubmission())
        viewModel.beginNewPurchase()
        assertEquals(GiftCardSubmissionState.PENDING, viewModel.submissionState.value)
    }

    @Test
    fun `resolving the payment lets the user buy gift cards again`() = runBlocking {
        unresolvedOnDisk.value = true
        val viewModel = createViewModel()
        assertEquals(GiftCardSubmissionState.PENDING, viewModel.submissionState.value)

        // committed once the network showed it, or released once it was judged never sent: either
        // way the record goes, and the block must go with it rather than outliving the payment
        unresolvedOnDisk.value = false

        assertEquals(GiftCardSubmissionState.IDLE, viewModel.submissionState.value)
        assertTrue(viewModel.tryStartSubmission())
    }

    @Test
    fun `a claim taken before the store was read still cannot pay twice`() = runBlocking {
        // the observed value has not caught up with the store, which is where a view model built
        // moments ago sits, so the claim below is granted on no knowledge at all
        every { unresolvedPayments.observeUnresolvedGiftCardPurchase() } returns MutableStateFlow(false)
        coEvery { unresolvedPayments.hasUnresolvedGiftCardPurchase() } returns true

        val viewModel = createViewModel()
        assertTrue(viewModel.tryStartSubmission())

        try {
            viewModel.payAndRecordOrder(paymentUri, emptyList())
            fail("a second payment was submitted for an order that may already be paid")
        } catch (expected: DuplicateGiftCardSubmissionException) {
            assertEquals(GiftCardSubmissionState.PENDING, expected.state)
        }

        coVerify(exactly = 0) { sendPaymentService.payWithDashUrl(any(), any(), any(), any()) }
    }
}
