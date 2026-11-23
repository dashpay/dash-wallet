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
import org.dash.wallet.common.services.SendEngineNotSyncedException
import de.schildbach.wallet.service.platform.sdk.SdkDeferredPayment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.PaymentIntent
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A preview refused because the wallet was still syncing can be built again from the confirm
 * button. Before, the refusal stored when the screen opened was shown on every confirm, so a
 * wallet that finished syncing seconds later still could not pay until the invoice was reopened
 * (Joel, 12.0.1-beta, 2026-11-17).
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [29], manifest = Config.NONE)
class PaymentProtocolViewModelRetryPreviewTest {

    private val payment = SdkDeferredPayment("bb".repeat(32), byteArrayOf(1, 2, 3), 260L, null)

    private fun buildViewModel(runner: SendCoinsTaskRunner): PaymentProtocolViewModel {
        coEvery { runner.findUnresolvedSubmission(any<PaymentIntent>()) } returns null
        coEvery { runner.fetchPaymentRequest(any()) } returns mockk(relaxed = true)
        coEvery { runner.findAbandonedSubmission(any(), any()) } returns null
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

    private fun openInvoice(viewModel: PaymentProtocolViewModel) {
        val baseIntent = mockk<PaymentIntent>(relaxed = true) {
            every { isExtendedBy(any(), any(), any()) } returns true
        }
        viewModel.requestPaymentRequest(baseIntent)
    }

    private fun awaitCondition(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val mainLooper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(20)
            // LiveData.postValue delivers via the main looper
            mainLooper.idle()
        }
    }

    @Test
    fun `a preview refused for syncing is built again on retry and arms the payment`() {
        val runner = mockk<SendCoinsTaskRunner>(relaxed = true)
        coEvery { runner.buildDeferredBip70Payment(any(), any()) } throws
            SendEngineNotSyncedException("L1 funding gate closed: scan short of the chain tip") andThen payment
        val viewModel = buildViewModel(runner)

        openInvoice(viewModel)
        awaitCondition("the refusal is stored") { viewModel.sendRequestLiveData.value?.exception != null }
        assertTrue("a syncing refusal can be retried", viewModel.canRetryPreview)
        assertFalse(viewModel.canSendPayment)

        viewModel.retryPreview()
        awaitCondition("the rebuilt preview is armed") { viewModel.deferredPayment != null }

        assertEquals(payment, viewModel.deferredPayment)
        assertTrue(viewModel.canSendPayment)
        assertFalse("nothing left to retry", viewModel.canRetryPreview)
        runBlocking { coVerify(exactly = 2) { runner.buildDeferredBip70Payment(any(), any()) } }
    }

    /**
     * thepastaclaw on #1714. The fragment that receives a retry's failure can be a new one (a
     * rotation during the rebuild), so the ViewModel remembers that the user asked for it.
     */
    @Test
    fun `a retry that is refused again is reported once as the answer to a retry`() {
        val runner = mockk<SendCoinsTaskRunner>(relaxed = true)
        coEvery { runner.buildDeferredBip70Payment(any(), any()) } throws
            SendEngineNotSyncedException("L1 funding gate closed: scan short of the chain tip")
        val viewModel = buildViewModel(runner)

        openInvoice(viewModel)
        awaitCondition("the refusal is stored") { viewModel.sendRequestLiveData.value?.exception != null }
        assertFalse("the first build is not a retry", viewModel.consumeRetryResult())

        viewModel.retryPreview()
        awaitCondition("the second refusal is stored") { viewModel.sendRequestLiveData.value?.exception != null }

        assertTrue("the result answers a retry", viewModel.consumeRetryResult())
        assertFalse("reported once", viewModel.consumeRetryResult())
    }

    @Test
    fun `a preview that failed for another reason is not built again`() {
        val runner = mockk<SendCoinsTaskRunner>(relaxed = true)
        coEvery { runner.buildDeferredBip70Payment(any(), any()) } throws IllegalStateException("Insufficient funds")
        val viewModel = buildViewModel(runner)

        openInvoice(viewModel)
        awaitCondition("the failure is stored") { viewModel.sendRequestLiveData.value?.exception != null }
        assertFalse("only a syncing refusal is retried", viewModel.canRetryPreview)

        viewModel.retryPreview()

        assertTrue("the stored failure stays", viewModel.sendRequestLiveData.value?.exception is IllegalStateException)
        runBlocking { coVerify(exactly = 1) { runner.buildDeferredBip70Payment(any(), any()) } }
    }

    /**
     * thepastaclaw on #1714. Backing out while the rebuild runs: the screen's teardown finds no
     * preview to release yet, so the build that finishes afterwards must release its own.
     */
    @Test
    fun `a rebuilt preview that finishes after the screen closed is released, not kept`() {
        val runner = mockk<SendCoinsTaskRunner>(relaxed = true)
        val buildStarted = CompletableDeferred<Unit>()
        val finishBuild = CompletableDeferred<Unit>()
        var builds = 0
        coEvery { runner.buildDeferredBip70Payment(any(), any()) } coAnswers {
            if (builds++ == 0) {
                throw SendEngineNotSyncedException("L1 funding gate closed: scan short of the chain tip")
            }
            buildStarted.complete(Unit)
            // The real build has no cancellation point once the inputs are reserved.
            withContext(NonCancellable) { finishBuild.await() }
            payment
        }
        val viewModel = buildViewModel(runner)
        val store = androidx.lifecycle.ViewModelStore().apply { put("invoice", viewModel) }

        openInvoice(viewModel)
        awaitCondition("the refusal is stored") { viewModel.sendRequestLiveData.value?.exception != null }
        viewModel.retryPreview()
        runBlocking { buildStarted.await() }

        store.clear()
        finishBuild.complete(Unit)

        awaitCondition("the late preview is released") {
            runCatching {
                runBlocking { coVerify(exactly = 1) { runner.releaseDeferredPayment(payment, any()) } }
            }.isSuccess
        }
        assertEquals("nothing stays armed on a closed screen", null, viewModel.deferredPayment)
    }
}
