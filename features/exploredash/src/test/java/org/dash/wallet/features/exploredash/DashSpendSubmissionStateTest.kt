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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.PaymentSubmissionPendingException
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.UnresolvedPaymentsProvider
import org.dash.wallet.common.services.WalletSession
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.transactions.TxInfo
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderDao
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderType
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
    private lateinit var walletDataProvider: WalletDataProvider
    private val transactionMetadata = mockk<TransactionMetadataProvider>(relaxed = true)

    @Before
    fun setUp() {
        sendPaymentService = mockk(relaxed = true)
        unresolvedPayments = mockk(relaxed = true)
        every { unresolvedPayments.observeUnresolvedGiftCardPurchase() } returns unresolvedOnDisk
        coEvery { unresolvedPayments.hasUnresolvedGiftCardPurchase() } answers { unresolvedOnDisk.value }
    }

    private fun createViewModel(): DashSpendViewModel {
        walletDataProvider = mockk(relaxed = true)
        every { walletDataProvider.observeTotalBalance() } returns MutableStateFlow(Dash.valueOf(100_000_000L))

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
            transactionMetadata,
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
        coEvery { sendPaymentService.payWithDashUrl(any(), any(), any(), any(), any()) } answers {
            // the verifier quarantines and persists the payment before the submission fails, so
            // the record is already on disk by the time the screen hears about it
            unresolvedOnDisk.value = true
            throw PaymentSubmissionPendingException(ZERO_TX_ID, null)
        }

        try {
            viewModel.payAndRecordOrder(paymentUri, emptyList())
            fail("an ambiguous submission was reported as successful")
        } catch (expected: PaymentSubmissionPendingException) {
            // what an ambiguous submission looks like to the screen
        }
    }

    @Test
    fun `a wallet replaced while the purchase waits is never the one that pays`() = runBlocking {
        // review 4304575003: the purchase suspends in the unresolved-payment lookup (and in the IO
        // dispatch) before it reaches the payment service. Wallet A, on which the user confirmed,
        // is wiped and B installed meanwhile; the purchase must be refused, not paid from B.
        val walletA = WalletSession("wallet A")
        val walletB = WalletSession("wallet B")
        var installed: WalletSession? = walletA
        every { sendPaymentService.currentWalletSession() } answers { installed }
        coEvery { unresolvedPayments.hasUnresolvedGiftCardPurchase() } coAnswers {
            installed = walletB
            false
        }
        val paidFrom = mutableListOf<WalletSession?>()
        // the payment service's contract: a session whose wallet is no longer installed is refused
        coEvery { sendPaymentService.payWithDashUrl(any(), any(), any(), any(), any()) } answers {
            val session = arg<WalletSession?>(3)
            if (session == null || session !== installed) {
                throw IllegalStateException("the wallet this payment was confirmed on has been wiped")
            }
            paidFrom += session
            mockk<TxInfo>(relaxed = true) { every { txId } returns ZERO_TX_ID }
        }
        val viewModel = createViewModel()

        try {
            viewModel.payAndRecordOrder(paymentUri, emptyList())
            fail("a purchase confirmed on wallet A was paid after A was replaced")
        } catch (expected: IllegalStateException) {
            // refused: nothing was sent
        }

        assertTrue("nothing was paid from the replacement wallet: $paidFrom", paidFrom.isEmpty())
        assertEquals(GiftCardSubmissionState.IDLE, viewModel.submissionState.value)
    }

    @Test
    fun `a purchase claimed at the tap pays from the wallet it was confirmed on, or not at all`() = runBlocking {
        // the dialog claims at the confirm tap, then creates the order at the merchant (and may
        // show the minimum-balance dialog) before payAndRecordOrder; a wipe in that window must
        // not have the replacement pay for the order
        val walletA = WalletSession("wallet A")
        val walletB = WalletSession("wallet B")
        var installed: WalletSession? = walletA
        every { sendPaymentService.currentWalletSession() } answers { installed }
        val paidFrom = mutableListOf<WalletSession?>()
        coEvery { sendPaymentService.payWithDashUrl(any(), any(), any(), any(), any()) } answers {
            val session = arg<WalletSession?>(3)
            if (session == null || session !== installed) {
                throw IllegalStateException("the wallet this payment was confirmed on has been wiped")
            }
            paidFrom += session
            mockk<TxInfo>(relaxed = true) { every { txId } returns ZERO_TX_ID }
        }
        val viewModel = createViewModel()

        assertTrue(viewModel.tryStartSubmission())
        installed = walletB // the order is being created at the merchant; the wallet is replaced

        try {
            viewModel.payAndRecordOrder(paymentUri, emptyList())
            fail("a purchase confirmed on wallet A was paid after A was replaced")
        } catch (expected: IllegalStateException) {
            // refused: nothing was sent
        }
        assertTrue("nothing was paid from the replacement wallet: $paidFrom", paidFrom.isEmpty())
    }

    @Test
    fun `a purchase is attributed to the provider it was confirmed with`() = runBlocking {
        // review 4307041834: a recreated dialog can be dismissed while the NonCancellable purchase
        // waits, and another provider picked; the purchase must keep the one it was confirmed with
        val viewModel = createViewModel()
        viewModel.selectedProvider = GiftCardProviderType.CTX
        coEvery { unresolvedPayments.hasUnresolvedGiftCardPurchase() } coAnswers {
            viewModel.selectedProvider = GiftCardProviderType.PiggyCards
            false
        }
        val transaction = mockk<TxInfo>(relaxed = true)
        every { transaction.txId } returns ZERO_TX_ID
        coEvery { sendPaymentService.payWithDashUrl(any(), any(), any(), any(), any()) } returns transaction

        viewModel.payAndRecordOrder(paymentUri, emptyList())

        val ctx = GiftCardProviderType.CTX.serviceName
        coVerify(exactly = 1) { sendPaymentService.payWithDashUrl(any(), ctx, any(), any(), any()) }
        coVerify(exactly = 0) {
            sendPaymentService.payWithDashUrl(any(), GiftCardProviderType.PiggyCards.serviceName, any(), any(), any())
        }
        coVerify(exactly = 1) { transactionMetadata.markGiftCardTransaction(any(), ctx, any()) }
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
        val transaction = mockk<TxInfo>(relaxed = true)
        every { transaction.txId } returns ZERO_TX_ID
        coEvery { sendPaymentService.payWithDashUrl(any(), any(), any(), any(), any()) } returns transaction

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
    fun `the same screen may buy again once its own unresolved payment settles`() = runBlocking {
        val viewModel = createViewModel()
        submitAmbiguously(viewModel)
        assertEquals(GiftCardSubmissionState.PENDING, viewModel.submissionState.value)

        // the verifier committed the transaction, or released it, and dropped the record; this
        // view model is scoped to the navigation graph and outlives that by a long way
        unresolvedOnDisk.value = false
        viewModel.beginNewPurchase()

        assertEquals(GiftCardSubmissionState.IDLE, viewModel.submissionState.value)
        assertTrue(
            "the screen stayed dead after its payment settled",
            viewModel.tryStartSubmission()
        )
    }

    @Test
    fun `a settled payment does not clear a purchase that is in flight`() = runBlocking {
        val viewModel = createViewModel()
        assertTrue(viewModel.tryStartSubmission())
        assertEquals(GiftCardSubmissionState.IN_PROGRESS, viewModel.submissionState.value)

        // an older payment settling says nothing about the one being submitted right now
        viewModel.beginNewPurchase()

        assertEquals(GiftCardSubmissionState.IN_PROGRESS, viewModel.submissionState.value)
        assertFalse(viewModel.tryStartSubmission())
    }

    @Test
    fun `a store that has not been read yet blocks rather than permits`() = runBlocking {
        // never emits: the first read is still in flight, so nothing is known either way
        every { unresolvedPayments.observeUnresolvedGiftCardPurchase() } returns emptyFlow()

        val viewModel = createViewModel()

        assertEquals(GiftCardSubmissionState.PENDING, viewModel.submissionState.value)
        assertFalse(
            "a claim was granted before the store had been read",
            viewModel.tryStartSubmission()
        )
    }

    @Test
    fun `a store that cannot be read blocks rather than permits`() = runBlocking {
        every { unresolvedPayments.observeUnresolvedGiftCardPurchase() } returns
            flow { throw IllegalStateException("pending payments could not be decoded") }

        val viewModel = createViewModel()

        assertEquals(GiftCardSubmissionState.PENDING, viewModel.submissionState.value)
        assertFalse(
            "a claim was granted from a store that could not be read",
            viewModel.tryStartSubmission()
        )
    }

    @Test
    fun `a store read that fails once is retried, and purchases are allowed again once it recovers`() = runBlocking {
        // 4297952501: a failed read used to complete the observation for good, leaving every later
        // purchase blocked for the view model's lifetime even after storage recovered
        var collections = 0
        every { unresolvedPayments.observeUnresolvedGiftCardPurchase() } returns flow {
            if (collections++ == 0) throw IllegalStateException("pending payments could not be read")
            emitAll(MutableStateFlow(false))
        }
        DashSpendViewModel.unresolvedObservationRetryDelayMs = 0L
        try {
            val viewModel = createViewModel()

            assertTrue("the failed read was retried", collections >= 2)
            assertEquals(GiftCardSubmissionState.IDLE, viewModel.submissionState.value)
            assertTrue(viewModel.tryStartSubmission())
        } finally {
            DashSpendViewModel.unresolvedObservationRetryDelayMs =
                DashSpendViewModel.DEFAULT_UNRESOLVED_OBSERVATION_RETRY_MS
        }
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

        coVerify(exactly = 0) { sendPaymentService.payWithDashUrl(any(), any(), any(), any(), any()) }
    }

    private companion object {
        val ZERO_TX_ID = "00".repeat(32)
    }
}
