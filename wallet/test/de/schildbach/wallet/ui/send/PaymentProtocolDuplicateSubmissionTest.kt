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

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import de.schildbach.wallet.Constants
import de.schildbach.wallet.payments.SendCoinsTaskRunner
import de.schildbach.wallet.util.viewModels.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.PaymentIntent
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.PaymentSubmissionPendingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
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
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class PaymentProtocolDuplicateSubmissionTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    private lateinit var viewModel: PaymentProtocolViewModel
    private lateinit var sendCoinsTaskRunner: SendCoinsTaskRunner

    private val address: Address =
        Address.fromString(Constants.NETWORK_PARAMETERS, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
    private val amount: Coin = Coin.parseCoin("0.25")

    /** The SHA-256 the parser takes over the serialized PaymentRequest. */
    private val requestHash = ByteArray(32) { it.toByte() }

    private val earlierSubmission: Sha256Hash =
        Sha256Hash.wrap("00000000000000000000000000000000000000000000000000000000000000a1")

    @Before
    fun setUp() {
        Context.propagate(Context.getOrCreate(Constants.NETWORK_PARAMETERS))

        val wallet = mockk<Wallet>(relaxed = true)
        every { wallet.context } returns Context.get()

        val walletData = mockk<WalletDataProvider>(relaxed = true)
        every { walletData.wallet } returns wallet

        val walletUIConfig = mockk<WalletUIConfig>(relaxed = true)
        every { walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY) } returns emptyFlow()

        sendCoinsTaskRunner = mockk(relaxed = true)
        coEvery { sendCoinsTaskRunner.awaitPaymentReadiness() } returns Unit
        every {
            sendCoinsTaskRunner.createSendRequest(any(), any<PaymentIntent>(), any(), any())
        } returns SendRequest.to(address, amount)
        coEvery { sendCoinsTaskRunner.sendDirectPayment(any(), any(), any(), any()) } returns
            Transaction(Constants.NETWORK_PARAMETERS)

        viewModel = PaymentProtocolViewModel(
            walletData,
            mockk<Configuration>(relaxed = true),
            mockk<ExchangeRatesProvider>(relaxed = true),
            sendCoinsTaskRunner,
            walletUIConfig
        )

        val intent = PaymentIntent(
            PaymentIntent.Standard.BIP70,
            null, null,
            arrayOf(PaymentIntent.Output(amount, org.bitcoinj.script.ScriptBuilder.createOutputScript(address))),
            null,
            "https://merchant.example/pay",
            null,
            "https://merchant.example/request",
            requestHash,
            null, null
        )
        // initPaymentIntent kicks off the request fetch on its own scope, and that lands on
        // finalPaymentIntent when it finishes. Let it settle before planting the values this test
        // depends on, or it overwrites them part way through.
        coEvery { sendCoinsTaskRunner.fetchPaymentRequest(any()) } returns intent
        runBlocking {
            viewModel.initPaymentIntent(intent)
            withTimeout(5_000) {
                while (viewModel.sendRequestLiveData.value == null) {
                    delay(10)
                }
            }
        }
        viewModel.finalPaymentIntent = intent
        viewModel.baseSendRequest = SendRequest.to(address, amount)
    }

    private fun awaitAck(): Resource<Transaction> = runBlocking {
        withTimeout(5_000) {
            var value = viewModel.directPaymentAckLiveData.value
            while (value == null || value.status == Status.LOADING) {
                delay(10)
                value = viewModel.directPaymentAckLiveData.value
            }
            value
        }
    }

    @Test
    fun `an invoice with a submission still outstanding is not paid again`() = runBlocking {
        coEvery { sendCoinsTaskRunner.findUnresolvedSubmission(requestHash) } returns earlierSubmission

        viewModel.sendPayment()
        val ack = awaitAck()

        // the screen shows the outcome that is already outstanding rather than starting another
        assertEquals(Status.ERROR, ack.status)
        assertTrue(
            "expected the pending outcome, got ${ack.exception}",
            ack.exception is PaymentSubmissionPendingException
        )
        assertEquals(earlierSubmission, (ack.exception as PaymentSubmissionPendingException).txId)

        // nothing was built, so there is no second transaction for the payee to broadcast
        coVerify(exactly = 0) { sendCoinsTaskRunner.sendDirectPayment(any(), any(), any(), any()) }
    }

    @Test
    fun `an invoice with nothing outstanding is paid as usual`() = runBlocking {
        coEvery { sendCoinsTaskRunner.findUnresolvedSubmission(any()) } returns null

        viewModel.sendPayment()
        val ack = awaitAck()

        assertEquals(Status.SUCCESS, ack.status)
        coVerify(exactly = 1) { sendCoinsTaskRunner.sendDirectPayment(any(), any(), any(), any()) }
    }

    @Test
    fun `the invoice is checked before any transaction is built`() = runBlocking {
        coEvery { sendCoinsTaskRunner.findUnresolvedSubmission(requestHash) } returns earlierSubmission

        viewModel.sendPayment()
        awaitAck()

        // coin selection is what puts a second payment on different inputs, so the question has to
        // be asked before the request is created, not after. Matched on signInputs = true: the
        // screen's own amount preview builds an unsigned request and is deliberately not gated.
        verify(exactly = 0) {
            sendCoinsTaskRunner.createSendRequest(any(), any<PaymentIntent>(), eq(true), any())
        }
    }
}
