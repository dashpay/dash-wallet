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
package de.schildbach.wallet.ui.send

import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.payments.SendCoinsTaskRunner
import de.schildbach.wallet.service.platform.sdk.SdkDeferredPayment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.PaymentIntent
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.PaymentSubmissionPendingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Restoring input locks stops a second payment reusing an uncertain one's outpoints. It does not
 * stop it being a second payment for the same invoice: a wallet with other funds simply builds one
 * from different inputs, and the payee can broadcast both. This screen keeps its own record of
 * having sent in a fragment-scoped view model, which process death takes with it and which was
 * never there when the invoice is opened again, so the stored record has to be asked.
 *
 * On this branch the preview already holds an SDK reservation for the payment, so the question is
 * asked before that reservation is submitted, and the reservation is given back when the invoice
 * is already paid for.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [29], manifest = Config.NONE)
class PaymentProtocolDuplicateSubmissionTest {

    /** The SHA-256 the parser takes over the serialized PaymentRequest. */
    private val requestHash = ByteArray(32) { it.toByte() }

    private val earlierSubmission: Sha256Hash =
        Sha256Hash.wrap("00000000000000000000000000000000000000000000000000000000000000a1")

    private val payment = SdkDeferredPayment("bb".repeat(32), byteArrayOf(1, 2, 3), 260L, null)

    private fun armedViewModel(runner: SendCoinsTaskRunner): PaymentProtocolViewModel {
        val wallet = mockk<Wallet>(relaxed = true)
        val walletData = mockk<WalletData>(relaxed = true) { every { this@mockk.wallet } returns wallet }
        val walletUIConfig = mockk<WalletUIConfig> {
            every { observe(WalletUIConfig.SELECTED_CURRENCY) } returns emptyFlow()
        }
        val viewModel = PaymentProtocolViewModel(
            walletData,
            mockk<Configuration>(relaxed = true),
            mockk<ExchangeRatesProvider>(relaxed = true),
            runner,
            walletUIConfig
        )

        // a real intent: paymentRequestHash is a field, which a mock cannot stand in for
        val address = org.bitcoinj.core.Address.fromString(
            de.schildbach.wallet.Constants.NETWORK_PARAMETERS,
            "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL"
        )
        val finalIntent = PaymentIntent(
            PaymentIntent.Standard.BIP70,
            "Test Merchant",
            null,
            arrayOf(
                PaymentIntent.Output(
                    org.dash.wallet.common.money.Coin.valueOf(25_000_000L),
                    org.bitcoinj.script.ScriptBuilder.createOutputScript(address).program
                )
            ),
            "Test payment",
            "https://merchant.example/pay",
            null,
            "https://merchant.example/request",
            requestHash,
            null,
            null
        )
        val baseIntent = mockk<PaymentIntent>(relaxed = true) {
            every { isExtendedBy(any(), any(), any()) } returns true
        }
        coEvery { runner.fetchPaymentRequest(any()) } returns finalIntent
        // nothing paid before: the preview builds a new payment
        coEvery { runner.findAbandonedSubmission(any(), any()) } returns null
        coEvery { runner.buildDeferredBip70Payment(any(), any()) } returns payment

        viewModel.requestPaymentRequest(baseIntent)
        awaitCondition("deferred preview armed") { viewModel.deferredPayment != null }
        return viewModel
    }

    private fun awaitCondition(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val mainLooper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(20)
            mainLooper.idle()
        }
    }

    private fun awaitOutcome(viewModel: PaymentProtocolViewModel) = awaitCondition("payment outcome") {
        val status = viewModel.directPaymentAckLiveData.value?.status
        status == Status.SUCCESS || status == Status.ERROR
    }

    @Test
    fun `an invoice with a submission still outstanding is not paid again`() {
        val runner = mockk<SendCoinsTaskRunner>(relaxed = true)
        coEvery { runner.findUnresolvedSubmission(requestHash) } returns earlierSubmission
        val viewModel = armedViewModel(runner)

        viewModel.sendPayment()
        awaitOutcome(viewModel)

        // the screen shows the outcome that is already outstanding rather than starting another
        val ack = viewModel.directPaymentAckLiveData.value!!
        assertEquals(Status.ERROR, ack.status)
        assertTrue("expected the pending outcome, got ${ack.exception}", ack.exception is PaymentSubmissionPendingException)
        assertEquals(earlierSubmission.toString(), (ack.exception as PaymentSubmissionPendingException).txId)

        runBlocking {
            // nothing was submitted, so there is no second transaction for the payee to broadcast,
            // and the preview's reservation was given back
            coVerify(exactly = 0) { runner.sendPrebuiltDirectPayment(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { runner.releaseDeferredPayment(payment) }
        }
    }

    @Test
    fun `an invoice with nothing outstanding is paid as usual`() {
        val runner = mockk<SendCoinsTaskRunner>(relaxed = true)
        coEvery { runner.findUnresolvedSubmission(any()) } returns null
        val liveTx = mockk<Transaction>(relaxed = true)
        coEvery { runner.sendPrebuiltDirectPayment(any(), any(), any()) } returns liveTx
        val viewModel = armedViewModel(runner)

        viewModel.sendPayment()
        awaitOutcome(viewModel)

        assertEquals(Status.SUCCESS, viewModel.directPaymentAckLiveData.value?.status)
        runBlocking {
            coVerify(exactly = 1) { runner.sendPrebuiltDirectPayment(payment, any(), any()) }
            coVerify(exactly = 0) { runner.releaseDeferredPayment(any()) }
        }
    }

    @Test
    fun `the taken reservation is released when the outstanding-submission lookup fails`() {
        // The preview's reservation has already left deferredPaymentRef when the store is read, so
        // onCleared() can no longer reach it: this screen is its only owner until the runner takes
        // it over, and a failed read must give it back rather than strand its locked inputs.
        val runner = mockk<SendCoinsTaskRunner>(relaxed = true)
        coEvery { runner.findUnresolvedSubmission(any()) } throws IllegalStateException("store unreadable")
        val viewModel = armedViewModel(runner)

        viewModel.sendPayment()
        awaitOutcome(viewModel)

        assertEquals(Status.ERROR, viewModel.directPaymentAckLiveData.value?.status)
        runBlocking {
            coVerify(exactly = 0) { runner.sendPrebuiltDirectPayment(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { runner.releaseDeferredPayment(payment) }
        }
    }

    // --- review 9fa90c30ccf1: an abandoned invoice is paid again with T1 itself ----------------

    @Test
    fun `reopening an invoice whose earlier payment was released resubmits it and builds nothing`() {
        val runner = mockk<SendCoinsTaskRunner>(relaxed = true)
        val earlier = de.schildbach.wallet.data.PendingDirectPayment(
            earlierSubmission, byteArrayOf(1, 2, 3), "https://merchant.example/pay", "Merchant",
            System.currentTimeMillis(), abandoned = true
        )
        coEvery { runner.findAbandonedSubmission(any(), any()) } returns earlier
        coEvery { runner.knownFeeOf(any(), any()) } returns null
        val liveTx = mockk<Transaction>(relaxed = true)
        coEvery { runner.resubmitAbandonedPayment(any(), any()) } returns liveTx
        val viewModel = resubmissionViewModel(runner)

        assertTrue(viewModel.isResubmission)
        assertTrue(viewModel.canSendPayment)
        viewModel.sendPayment()
        awaitOutcome(viewModel)

        assertEquals(Status.SUCCESS, viewModel.directPaymentAckLiveData.value?.status)
        runBlocking {
            coVerify(exactly = 0) { runner.buildDeferredBip70Payment(any(), any()) }
            coVerify(exactly = 0) { runner.sendPrebuiltDirectPayment(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { runner.resubmitAbandonedPayment(any(), any()) }
        }
    }

    private fun resubmissionViewModel(runner: SendCoinsTaskRunner): PaymentProtocolViewModel {
        val wallet = mockk<Wallet>(relaxed = true)
        val walletData = mockk<WalletData>(relaxed = true) { every { this@mockk.wallet } returns wallet }
        val walletUIConfig = mockk<WalletUIConfig> {
            every { observe(WalletUIConfig.SELECTED_CURRENCY) } returns emptyFlow()
        }
        val viewModel = PaymentProtocolViewModel(
            walletData,
            mockk<Configuration>(relaxed = true),
            mockk<ExchangeRatesProvider>(relaxed = true),
            runner,
            walletUIConfig
        )
        val address = org.bitcoinj.core.Address.fromString(
            de.schildbach.wallet.Constants.NETWORK_PARAMETERS,
            "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL"
        )
        val finalIntent = PaymentIntent(
            PaymentIntent.Standard.BIP70, "Test Merchant", null,
            arrayOf(
                PaymentIntent.Output(
                    org.dash.wallet.common.money.Coin.valueOf(25_000_000L),
                    org.bitcoinj.script.ScriptBuilder.createOutputScript(address).program
                )
            ),
            "Test payment", "https://merchant.example/pay", null, "https://merchant.example/request",
            requestHash, null, null
        )
        val baseIntent = mockk<PaymentIntent>(relaxed = true) {
            every { isExtendedBy(any(), any(), any()) } returns true
        }
        coEvery { runner.fetchPaymentRequest(any()) } returns finalIntent
        viewModel.requestPaymentRequest(baseIntent)
        awaitCondition("resubmission armed") { viewModel.isResubmission }
        return viewModel
    }
}
