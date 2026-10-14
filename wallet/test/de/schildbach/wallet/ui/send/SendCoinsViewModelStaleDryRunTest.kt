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
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.data.PaymentIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import kotlin.concurrent.thread

/**
 * PR #1608 review (CodeRabbit, SendCoinsViewModel.signAndSendPayment): a send
 * must only use a dry run of THAT send. After a "Max" amount was edited down,
 * the Max dry run stayed published until the edit's debounced dry run
 * started; a confirmation in that window took it, and post-cutover its
 * send-all flag made the smaller amount a send-all — the whole balance. The
 * same holds for a payee change and for a send-all whose balance changed.
 *
 * Deterministic: Robolectric's main-looper clock does not advance on its own,
 * so the amount's 150 ms debounce fires only when a test advances it; dry runs
 * a test must catch mid-way are held in a gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class SendCoinsViewModelStaleDryRunTest {

    private val params = Constants.NETWORK_PARAMETERS
    private val payee1 = Address.fromKey(params, ECKey())
    private val payee2 = Address.fromKey(params, ECKey())
    private val balance = Coin.COIN.multiply(5)

    /** What reached the send runner. */
    private class Sends {
        /** The payment intents of the final (signing) requests, in order. */
        val finalIntents = mutableListOf<PaymentIntent>()

        /** The requests passed to sendCoins, in order, with their recipients. */
        val sent = mutableListOf<SendRequest>()
        val recipients = mutableListOf<Address?>()
    }

    /** Holds the next dry run inside createSendRequest while [armed]. */
    private class DryRunGate {
        val armed = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val open = CountDownLatch(1)

        fun pass() {
            if (armed.getAndSet(false)) {
                entered.countDown()
                check(open.await(10, TimeUnit.SECONDS)) { "dry run never released" }
            }
        }
    }

    private fun runner(sends: Sends, gate: DryRunGate, cutover: Boolean): SendCoinsTaskRunner {
        val runner = mockk<SendCoinsTaskRunner>(relaxed = true)
        coEvery { runner.isCutoverCommitted() } returns cutover
        every {
            runner.createSendRequest(any<Boolean>(), any<PaymentIntent>(), any<Boolean>(), any<Boolean>())
        } answers {
            val signInputs = args[2] as Boolean
            if (signInputs) {
                synchronized(sends) { sends.finalIntents += args[1] as PaymentIntent }
            } else {
                gate.pass()
            }
            mockk<SendRequest>(relaxed = true)
        }
        coEvery {
            runner.sendCoins(
                any<SendRequest>(),
                any<Boolean>(),
                any<Boolean>(),
                any<Consumer<Transaction>>(),
                any<String>(),
                any<Address>(),
                any<(String?) -> Unit>(),
                any<() -> Unit>()
            )
        } coAnswers {
            synchronized(sends) {
                sends.sent += args[0] as SendRequest
                sends.recipients += args[5] as Address?
            }
            mockk<Transaction>(relaxed = true)
        }
        return runner
    }

    private fun buildViewModel(
        runner: SendCoinsTaskRunner,
        maxOutput: MutableStateFlow<Coin>
    ): SendCoinsViewModel {
        val wallet = mockk<Wallet>(relaxed = true) {
            every { currentReceiveAddress() } returns Address.fromKey(params, ECKey())
        }
        val walletData = mockk<WalletData>(relaxed = true) {
            every { this@mockk.wallet } returns wallet
            every { observeMaxOutputBalance() } returns maxOutput
        }
        val blockchainStateDao = mockk<BlockchainStateDao> {
            every { observeState() } returns emptyFlow()
        }
        // No identity: a plain address payment, no contact lookup.
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
            provider
        )
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Fires the amount's debounced dry run (the main looper's clock is frozen otherwise). */
    private fun fireDebounce() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))

    private fun awaitCondition(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        idle()
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(20)
            idle()
        }
    }

    /** Waits for [latch], running the main looper meanwhile (dry runs hop through it). */
    private fun awaitLatch(what: String, latch: CountDownLatch) {
        val deadline = System.currentTimeMillis() + 10_000
        idle()
        while (!latch.await(20, TimeUnit.MILLISECONDS)) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            idle()
        }
    }

    private fun send(viewModel: SendCoinsViewModel, amount: Coin) =
        runBlocking { viewModel.signAndSendPayment(amount, null, true) }

    /** Opens the send screen paying [payee] with [amount] entered; its dry run is done. */
    private fun open(viewModel: SendCoinsViewModel, payee: Address, amount: Coin) {
        idle() // the balance feed reaches maxOutputAmount
        viewModel.setAmount(amount)
        runBlocking { viewModel.initPaymentIntent(PaymentIntent.fromAddress(payee.toBase58(), null)) }
        idle()
        assertEquals(amount, viewModel.currentDryRun()?.amount)
        assertEquals(true, viewModel.dryRunSuccessful.value)
    }

    @Test
    fun maxAmount_isSentAsSendAll() {
        // The baseline the fix must keep: an unedited Max is a send-all.
        val sends = Sends()
        val viewModel = buildViewModel(runner(sends, DryRunGate(), cutover = true), MutableStateFlow(balance))
        open(viewModel, payee1, balance)
        assertTrue(viewModel.currentDryRun()?.emptyWallet == true)

        send(viewModel, balance)

        assertEquals(1, sends.sent.size)
        assertTrue("an unedited Max is a send-all", sends.sent[0].emptyWallet)
    }

    @Test
    fun maxEditedDown_confirmBeforeTheNewDryRun_isRefused_thenSendsExactlyTheSmallerAmount() {
        val sends = Sends()
        val viewModel = buildViewModel(runner(sends, DryRunGate(), cutover = true), MutableStateFlow(balance))
        open(viewModel, payee1, balance)
        assertTrue("the Max dry run is a send-all", viewModel.currentDryRun()?.emptyWallet == true)

        // The user edits Max down to 1 DASH; its dry run is debounced and has
        // not started — the Max dry run is still the published one.
        viewModel.setAmount(Coin.COIN)
        idle()

        // The screen cannot continue...
        assertNull("no dry run of 1 DASH yet", viewModel.currentDryRun())
        assertNull(viewModel.dryrunSendRequest)
        assertEquals(false, viewModel.dryRunSuccessful.value)
        // ...and a confirmation that gets through anyway sends nothing: the
        // Max dry run is not carried onto 1 DASH as a send-all.
        assertThrows(DryRunOutdatedException::class.java) { send(viewModel, Coin.COIN) }
        assertTrue("nothing was sent", sends.sent.isEmpty())
        assertTrue("no send request was even built", sends.finalIntents.isEmpty())

        // The screen dismisses the error (resetState), which checks the
        // payment again: the 1 DASH dry run. (The edit's debounced dry run is
        // left unfired so this is the only one running.)
        viewModel.resetState()
        awaitCondition("the 1 DASH dry run") { viewModel.currentDryRun()?.amount == Coin.COIN }
        assertFalse(viewModel.currentDryRun()?.emptyWallet ?: true)
        assertEquals(true, viewModel.dryRunSuccessful.value)

        send(viewModel, Coin.COIN)

        assertEquals(1, sends.sent.size)
        assertFalse("1 DASH is not a send-all", sends.sent[0].emptyWallet)
        assertEquals("exactly 1 DASH", Coin.COIN.value, sends.finalIntents.single().amount?.value)
        assertEquals(listOf<Address?>(payee1), sends.recipients)
    }

    @Test
    fun maxEditedDown_theNewDryRunIsNotASendAll_evenWhenItsSendIsTheOnlyOne() {
        // Same edit, confirmed only once the edit's dry run is done: a plain
        // send of exactly the smaller amount (no refusal on the way).
        val sends = Sends()
        val viewModel = buildViewModel(runner(sends, DryRunGate(), cutover = true), MutableStateFlow(balance))
        open(viewModel, payee1, balance)

        viewModel.setAmount(Coin.COIN)
        fireDebounce()
        awaitCondition("the 1 DASH dry run") { viewModel.currentDryRun()?.amount == Coin.COIN }

        send(viewModel, Coin.COIN)

        assertFalse(sends.sent.single().emptyWallet)
        assertEquals(Coin.COIN.value, sends.finalIntents.single().amount?.value)
    }

    @Test
    fun sendAll_balanceChanged_isCheckedAgain_andSentAsAPlainSend() {
        // A Max decided against a 5 DASH balance; 1 DASH arrives before the
        // confirmation. 5 DASH is no longer everything: it must not drain 6.
        val sends = Sends()
        val gate = DryRunGate()
        val maxOutput = MutableStateFlow(balance)
        val viewModel = buildViewModel(runner(sends, gate, cutover = true), maxOutput)
        open(viewModel, payee1, balance)

        gate.armed.set(true)
        maxOutput.value = balance.add(Coin.COIN)
        awaitLatch("the payment checked again", gate.entered)

        assertNull(viewModel.currentDryRun())
        assertEquals(false, viewModel.dryRunSuccessful.value)
        assertThrows(DryRunNotReadyException::class.java) { send(viewModel, balance) }
        assertTrue(sends.sent.isEmpty())

        // Dismissing the error checks the payment again (the held dry run is
        // released only after that one is done: it is superseded, and dropped).
        viewModel.resetState()
        awaitCondition("the new balance's dry run") { viewModel.currentDryRun() != null }
        gate.open.countDown()
        assertFalse(viewModel.currentDryRun()?.emptyWallet ?: true)

        send(viewModel, balance)

        assertFalse("5 of 6 DASH is not a send-all", sends.sent.single().emptyWallet)
        assertEquals(balance.value, sends.finalIntents.single().amount?.value)
    }

    /**
     * Opens the send screen paying [payee1] with [amount] entered, holding its
     * dry run after it read the balance; returns the resolution's thread.
     */
    private fun openWithHeldDryRun(viewModel: SendCoinsViewModel, gate: DryRunGate, amount: Coin): Thread {
        idle() // the balance feed reaches maxOutputAmount
        viewModel.setAmount(amount)
        gate.armed.set(true)
        val init = thread {
            runBlocking { viewModel.initPaymentIntent(PaymentIntent.fromAddress(payee1.toBase58(), null)) }
        }
        awaitLatch("the dry run read the balance", gate.entered)
        return init
    }

    @Test
    fun maxDryRunRunning_balanceRises_itsStaleSendAllIsReplaced_andSentAsAPlainSend() {
        // PR #1608 review: the Max dry run read 5 DASH; 1 DASH arrived while it
        // was still running, so the balance observer saw no outcome to check
        // again. The dry run then published a send-all of 5 that the screen
        // must not use (the balance is 6) — and nothing checked the payment
        // again: Continue stayed blocked until the user edited the amount.
        val sends = Sends()
        val gate = DryRunGate()
        val maxOutput = MutableStateFlow(balance)
        val viewModel = buildViewModel(runner(sends, gate, cutover = true), maxOutput)
        val init = openWithHeldDryRun(viewModel, gate, balance)

        maxOutput.value = balance.add(Coin.COIN)
        idle() // the balance observer runs while the Max dry run is held
        assertNull(viewModel.currentDryRun())

        gate.open.countDown()
        init.join(10_000)
        assertFalse("the resolution finished", init.isAlive)

        // Its send-all of 5 is stale; a replacement checks 5 of 6 DASH.
        awaitCondition("the replacement dry run") { viewModel.currentDryRun() != null }
        assertFalse("5 of 6 DASH is not a send-all", viewModel.currentDryRun()?.emptyWallet ?: true)
        assertEquals(true, viewModel.dryRunSuccessful.value)

        send(viewModel, balance)

        assertFalse(sends.sent.single().emptyWallet)
        assertEquals(balance.value, sends.finalIntents.single().amount?.value)
        assertEquals(listOf<Address?>(payee1), sends.recipients)
    }

    @Test
    fun dryRunRunning_balanceRisesToTheAmount_itsStaleFailureIsReplaced_bySendAll() {
        // The reverse: 6 DASH entered against a 5 DASH balance (unaffordable);
        // 1 DASH arrives while that dry run runs. 6 DASH is now everything:
        // the replacement makes it a send-all, without the user editing.
        val sends = Sends()
        val gate = DryRunGate()
        val maxOutput = MutableStateFlow(balance)
        val viewModel = buildViewModel(runner(sends, gate, cutover = true), maxOutput)
        val newBalance = balance.add(Coin.COIN)
        val init = openWithHeldDryRun(viewModel, gate, newBalance)

        maxOutput.value = newBalance
        idle()

        gate.open.countDown()
        init.join(10_000)
        assertFalse("the resolution finished", init.isAlive)

        awaitCondition("the replacement dry run") { viewModel.currentDryRun() != null }
        assertTrue("6 of 6 DASH is a send-all", viewModel.currentDryRun()?.emptyWallet == true)
        assertFalse(viewModel.isInsufficientFunds)
        assertEquals(true, viewModel.dryRunSuccessful.value)

        send(viewModel, newBalance)

        assertTrue(sends.sent.single().emptyWallet)
        assertEquals(listOf<Address?>(payee1), sends.recipients)
    }

    @Test
    fun payeeChanged_thePreviousPayeesDryRunIsNotUsed() {
        // The screen re-resolves its payee (a contact's re-resolution after a
        // send, or a re-initialization) while the previous payee's dry run is
        // published: until the new payee's dry run is done, nothing is sent.
        val sends = Sends()
        val gate = DryRunGate()
        val viewModel = buildViewModel(runner(sends, gate, cutover = false), MutableStateFlow(balance))
        open(viewModel, payee1, Coin.COIN)
        val previousDryRun = viewModel.currentDryRun()

        gate.armed.set(true)
        val reinit = thread {
            runBlocking { viewModel.initPaymentIntent(PaymentIntent.fromAddress(payee2.toBase58(), null)) }
        }
        awaitLatch("the new payee's dry run started", gate.entered)

        // The new payee is published; its dry run is held.
        assertEquals(payee2.toBase58(), viewModel.basePaymentIntent.getAddress(Constants.ADDRESS_NETWORK))
        assertTrue(viewModel.everythingPlausible())
        assertNull("payee 1's dry run is not payee 2's", viewModel.currentDryRun())
        assertEquals(false, viewModel.dryRunSuccessful.value)
        assertThrows(DryRunNotReadyException::class.java) { send(viewModel, Coin.COIN) }
        assertTrue(sends.sent.isEmpty())

        gate.open.countDown()
        awaitCondition("payee 2's dry run") { !reinit.isAlive && viewModel.currentDryRun() != null }
        assertNotEquals(previousDryRun?.request, viewModel.currentDryRun()?.request)

        send(viewModel, Coin.COIN)
        assertEquals(listOf<Address?>(payee2), sends.recipients)
    }
}
