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
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.database.dao.BlockchainStateDao
import de.schildbach.wallet.payments.SendCoinsTaskRunner
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.sdk.ContactPaymentAddressProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.data.PaymentIntent
import org.dash.wallet.common.services.PaymentSubmissionPendingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import kotlin.concurrent.thread

/**
 * A send whose outcome is unknown ([PaymentSubmissionPendingException]) may already be on the
 * network. The send screen must not go back to a confirmable INPUT with the same payment and its
 * dry run (after the SDK reservation expires a second send could pay twice), also after the
 * screen or process is recreated. And two confirms in flight must not both send.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class SendCoinsViewModelOutcomeUnknownTest {

    private val params = Constants.NETWORK_PARAMETERS
    private val payee = Address.fromKey(params, ECKey())
    private val amount = Coin.CENT
    private val balance = Coin.COIN.multiply(5)

    /** What each send attempt reaching the runner does: return a tx or throw. */
    private fun runner(sends: AtomicInteger, onSend: () -> Transaction): SendCoinsTaskRunner {
        val runner = mockk<SendCoinsTaskRunner>(relaxed = true)
        coEvery { runner.isCutoverCommitted() } returns true
        every { runner.observeCutoverCommitted() } returns flowOf(true)
        every {
            runner.createSendRequest(any<Boolean>(), any<PaymentIntent>(), any<Boolean>(), any<Boolean>())
        } answers { mockk<SendRequest>(relaxed = true) }
        coEvery {
            runner.sendCoins(
                any<SendRequest>(),
                any<Boolean>(),
                any<Boolean>(),
                any<Consumer<Transaction>>(),
                any<String>(),
                any<Address>(),
                any<Wallet>(),
                any<Boolean>(),
                any<(String?) -> Unit>(),
                any<() -> Unit>()
            )
        } answers {
            sends.incrementAndGet()
            onSend()
        }
        return runner
    }

    private fun buildViewModel(runner: SendCoinsTaskRunner, savedState: SavedStateHandle): SendCoinsViewModel {
        val wallet = mockk<Wallet>(relaxed = true) {
            every { currentReceiveAddress() } returns Address.fromKey(params, ECKey())
        }
        val walletData = mockk<WalletData>(relaxed = true) {
            every { this@mockk.wallet } returns wallet
            every { observeMaxOutputBalance() } returns MutableStateFlow(balance)
        }
        val blockchainStateDao = mockk<BlockchainStateDao> {
            every { observeState() } returns emptyFlow()
        }
        val identityRepository = mockk<IdentityRepository>(relaxed = true) {
            coEvery { hasIdentity() } returns false
        }
        val provider = ContactPaymentAddressProvider(
            cutoverCommitted = { false },
            sdkAccountReader = { _, _ -> null },
            dashjNextAddress = { _, _ -> null },
            dashjMarkUsed = { _, _, _ -> null },
            params = params
        )
        return SendCoinsViewModel(
            walletData,
            mockk<WalletApplication>(relaxed = true),
            blockchainStateDao,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            runner,
            mockk(relaxed = true),
            identityRepository,
            mockk(relaxed = true),
            mockk(relaxed = true),
            provider,
            savedState
        )
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Opens the send screen paying [payee] with [amount]; its dry run is done. */
    private fun open(viewModel: SendCoinsViewModel) {
        idle()
        viewModel.setAmount(amount)
        runBlocking { viewModel.initPaymentIntent(PaymentIntent.fromAddress(payee.toBase58(), null)) }
        idle()
        assertEquals(amount, viewModel.currentDryRun()?.amount)
    }

    private fun send(viewModel: SendCoinsViewModel) =
        runBlocking { viewModel.signAndSendPayment(amount, null, true) }

    private fun outcomeUnknown(): Transaction =
        throw PaymentSubmissionPendingException("unknown", IllegalStateException("timeout"))

    private fun restoredFrom(savedState: SavedStateHandle) =
        SavedStateHandle(savedState.keys().associateWith { savedState.get<Any>(it) })

    @Test
    fun outcomeUnknown_isTerminal_theScreenCannotConfirmOrSendAgain() {
        val sends = AtomicInteger()
        val viewModel = buildViewModel(runner(sends, ::outcomeUnknown), SavedStateHandle())
        open(viewModel)

        assertThrows(PaymentSubmissionPendingException::class.java) { send(viewModel) }
        assertTrue(viewModel.paymentOutcomeUnknown.value)

        // What the screen does after showing the warning: never back to a confirmable INPUT.
        viewModel.resetState()
        idle()
        assertFalse(viewModel.everythingPlausible())

        // A confirm that still got through is refused before anything reaches the runner.
        assertThrows(SendRefusedException::class.java) { send(viewModel) }
        assertEquals(1, sends.get())
    }

    @Test
    fun outcomeUnknown_survivesRestore_andStillRefuses() {
        val sends = AtomicInteger()
        val savedState = SavedStateHandle()
        val first = buildViewModel(runner(sends, ::outcomeUnknown), savedState)
        open(first)
        assertThrows(PaymentSubmissionPendingException::class.java) { send(first) }

        // A new view model over the saved state, as after the process was killed and restored:
        // the same payment is resolved and dry-run again, but cannot be confirmed or sent.
        val restored = buildViewModel(runner(sends, ::outcomeUnknown), restoredFrom(savedState))
        assertTrue(restored.paymentOutcomeUnknown.value)
        open(restored)
        assertFalse(restored.everythingPlausible())
        assertThrows(SendRefusedException::class.java) { send(restored) }
        assertEquals(1, sends.get())
    }

    @Test
    fun concurrentConfirms_onlyOneSends_andAnOrdinaryFailureStillAllowsARetry() {
        val sends = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val viewModel = buildViewModel(
            runner(sends) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "send never released" }
                throw IllegalStateException("rejected before broadcast")
            },
            SavedStateHandle()
        )
        open(viewModel)

        var firstFailure: Throwable? = null
        val first = thread { firstFailure = runCatching { send(viewModel) }.exceptionOrNull() }
        check(entered.await(10, TimeUnit.SECONDS)) { "first send never reached the runner" }

        // A second confirm while the first is still sending: refused, nothing sent.
        assertThrows(SendRefusedException::class.java) { send(viewModel) }

        release.countDown()
        first.join(10_000)
        assertTrue(firstFailure is IllegalStateException)
        assertEquals(1, sends.get())
        assertFalse(viewModel.paymentOutcomeUnknown.value)

        // An ordinary failure is retryable: once the screen is back to INPUT, a new confirm sends.
        viewModel.resetState()
        idle()
        assertTrue(viewModel.everythingPlausible())
        assertThrows(IllegalStateException::class.java) { send(viewModel) }
        assertEquals(2, sends.get())
    }
}
