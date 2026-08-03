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

import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.payments.SendCoinsTaskRunner
import de.schildbach.wallet.service.platform.sdk.SdkDeferredPayment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.PaymentIntent
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the double-submission race on the SDK deferred
 * (BIP70) path — review finding on PR #1531: two rapid [PaymentProtocolViewModel.sendPayment]
 * calls must not both take the same reservation, and the loser must not
 * rebuild a fresh reservation over the winner's success. The fix under
 * test: the single-flight [sendPayment] guard (synchronous compareAndSet,
 * so the second call is dropped BEFORE any coroutine races) and the
 * AtomicReference take of the deferred payment.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [29], manifest = Config.NONE)
class PaymentProtocolViewModelRaceTest {

    private val deferredTxid = "bb".repeat(32)

    private fun buildViewModel(runner: SendCoinsTaskRunner): PaymentProtocolViewModel {
        val wallet = mockk<Wallet>(relaxed = true)
        val walletData = mockk<WalletData>(relaxed = true) { every { this@mockk.wallet } returns wallet }
        val walletUIConfig = mockk<WalletUIConfig> {
            every { observe(WalletUIConfig.SELECTED_CURRENCY) } returns emptyFlow()
        }
        return PaymentProtocolViewModel(
            walletData,
            mockk<Configuration>(relaxed = true),
            mockk<ExchangeRatesProvider>(relaxed = true),
            runner,
            walletUIConfig
        )
    }

    /** Drive the post-cutover preview until the reservation is armed. */
    private fun armDeferredPreview(
        viewModel: PaymentProtocolViewModel,
        runner: SendCoinsTaskRunner,
        payment: SdkDeferredPayment
    ) {
        val baseIntent = mockk<PaymentIntent>(relaxed = true) {
            every { isExtendedBy(any(), any(), any()) } returns true
        }
        coEvery { runner.fetchPaymentRequest(any()) } returns mockk(relaxed = true)
        coEvery { runner.buildDeferredBip70Payment(any()) } returns payment

        viewModel.requestPaymentRequest(baseIntent)
        awaitCondition("deferred preview armed") { viewModel.deferredPayment != null }
    }

    private fun awaitCondition(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val mainLooper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(20)
            // LiveData.postValue delivers via the main looper; pump it so
            // the polled .value reads can ever observe the posts.
            mainLooper.idle()
        }
    }

    @Test
    fun `second confirm during an in-flight send is dropped and the reservation is submitted once`() {
        val runner = mockk<SendCoinsTaskRunner>(relaxed = true)
        val payment = SdkDeferredPayment(deferredTxid, byteArrayOf(1, 2, 3), 260L, null)
        val viewModel = buildViewModel(runner)
        armDeferredPreview(viewModel, runner, payment)

        // Hold the first submission open so a second confirm arrives
        // strictly while it is in flight.
        val submissionGate = CompletableDeferred<Unit>()
        val liveTx = mockk<Transaction>(relaxed = true)
        coEvery { runner.sendPrebuiltDirectPayment(any(), any()) } coAnswers {
            submissionGate.await()
            liveTx
        }

        viewModel.sendPayment() // winner: takes the reservation, blocks on the gate
        viewModel.sendPayment() // duplicate confirm: must be dropped synchronously

        // The winner's coroutine takes the reservation (async); the
        // duplicate was dropped synchronously and never touches it.
        awaitCondition("reservation taken by the winner") { viewModel.deferredPayment == null }
        submissionGate.complete(Unit)

        awaitCondition("winner completed") {
            runCatching { coVerify(exactly = 1) { runner.sendPrebuiltDirectPayment(any(), any()) } }.isSuccess &&
                viewModel.directPaymentAckLiveData.value?.data != null
        }

        // Exactly one submission, and the loser never rebuilt a fresh
        // reservation over the winner (one build total, from the preview).
        runBlocking {
            coVerify(exactly = 1) { runner.sendPrebuiltDirectPayment(payment, any()) }
            coVerify(exactly = 1) { runner.buildDeferredBip70Payment(any()) }
        }
        assertEquals(liveTx, viewModel.directPaymentAckLiveData.value?.data)
    }

    @Test
    fun `definitive nack releases the guard and rebuilds the preview for a clean retry`() {
        val runner = mockk<SendCoinsTaskRunner>(relaxed = true)
        val payment = SdkDeferredPayment(deferredTxid, byteArrayOf(1, 2, 3), 260L, null)
        val viewModel = buildViewModel(runner)
        armDeferredPreview(viewModel, runner, payment)

        // A definitive nack (server refused; nothing on the network) is
        // the ONE failure that re-arms the preview — an ambiguous
        // transport failure must not, or a retry could double-pay.
        val retryPayment = SdkDeferredPayment("cc".repeat(32), byteArrayOf(4, 5), 300L, null)
        coEvery { runner.sendPrebuiltDirectPayment(any(), any()) } throws
            org.dash.wallet.common.services.DirectPayException("Payment was not acknowledged by the server")
        coEvery { runner.buildDeferredBip70Payment(any()) } returns retryPayment

        viewModel.sendPayment()
        awaitCondition("failure surfaced and preview rebuilt") {
            viewModel.directPaymentAckLiveData.value?.exception != null &&
                viewModel.deferredPayment == retryPayment
        }

        // The guard was released by the failure: a retry is accepted and
        // submits the REBUILT reservation, not the dead one.
        val liveTx = mockk<Transaction>(relaxed = true)
        coEvery { runner.sendPrebuiltDirectPayment(any(), any()) } returns liveTx
        viewModel.sendPayment()
        awaitCondition("retry succeeded") { viewModel.directPaymentAckLiveData.value?.data != null }

        runBlocking {
            coVerify(exactly = 1) { runner.sendPrebuiltDirectPayment(retryPayment, any()) }
        }
        assertTrue(viewModel.deferredPayment == null)
    }

    @Test
    fun `ambiguous transport failure does NOT re-arm the preview`() {
        val runner = mockk<SendCoinsTaskRunner>(relaxed = true)
        val payment = SdkDeferredPayment(deferredTxid, byteArrayOf(1, 2, 3), 260L, null)
        val viewModel = buildViewModel(runner)
        armDeferredPreview(viewModel, runner, payment)

        // The runner already ran (and lost) its network-observation
        // rescue; the outcome is genuinely unknown, so the flow must NOT
        // hand the user a fresh reservation to retry with.
        coEvery { runner.sendPrebuiltDirectPayment(any(), any()) } throws okio.IOException("transport down")

        viewModel.sendPayment()
        awaitCondition("failure surfaced") { viewModel.directPaymentAckLiveData.value?.exception != null }

        assertTrue("preview must stay un-armed", viewModel.deferredPayment == null)
        assertTrue("send must be gated off", !viewModel.canSendPayment)
        runBlocking {
            // Exactly the preview build — no rebuild after the ambiguity.
            coVerify(exactly = 1) { runner.buildDeferredBip70Payment(any()) }
        }
    }
}
