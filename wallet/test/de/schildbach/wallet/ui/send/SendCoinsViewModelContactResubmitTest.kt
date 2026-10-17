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
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.database.dao.BlockchainStateDao
import de.schildbach.wallet.database.dao.DashPayContactRequestDao
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.payments.SendCoinsTaskRunner
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.sdk.ContactAddressAlreadyPaidException
import de.schildbach.wallet.service.platform.sdk.ContactPaymentAddressProvider
import de.schildbach.wallet.service.platform.sdk.ContactPaymentNotRecordableException
import de.schildbach.wallet.service.platform.sdk.PaidContactAddressStore
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.FriendChainAccess
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.data.PaymentIntent
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import kotlin.concurrent.thread

/**
 * PR #1608 review: once a contact payment's broadcast signal fires, the
 * resolved contact intent must not be submitted again. The scenario: the SDK
 * reports Broadcast, the display bridge then fails, SendCoinsFragment.handleGo
 * shows the error and calls resetState(), and the user confirms again — that
 * second confirmation must pay a NEW address (fresh contact resolution), never
 * the one just paid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class SendCoinsViewModelContactResubmitTest {

    private val params = Constants.NETWORK_PARAMETERS
    private val ourUserId = "ourUserIdBase58"
    private val contactUserId = "contactUserIdBase58"
    private val accountReference = 0x77

    private val keys = List(3) { Address.fromKey(params, ECKey()) }

    /** dashj's SENDING chain for the contact: a mark of the current key moves it on. */
    private class FakeDashjChain(val keys: List<Address>, val advances: Boolean = true) {
        @Volatile var current = 0
        val marks = mutableListOf<Address>()

        fun next(): Address? = keys.getOrNull(current)

        @Synchronized
        fun mark(address: Address): FriendChainAccess.MarkResult {
            marks += address
            if (advances && keys.indexOf(address) == current) current++
            return FriendChainAccess.MarkResult.MARKED
        }
    }

    private class Sends {
        val recipients = mutableListOf<Address?>()

        /** Every request a dry run built (signInputs = false), in order; guarded by this. */
        private val dryRuns = mutableListOf<SendRequest>()

        @Synchronized
        fun recordDryRun(request: SendRequest) {
            dryRuns += request
        }

        @Synchronized
        fun dryRunCount(): Int = dryRuns.size

        /** Whether [request] was built by a dry run after the first [mark] ones. */
        @Synchronized
        fun isDryRunAfter(mark: Int, request: SendRequest?): Boolean =
            request != null && dryRuns.drop(mark).any { it === request }
    }

    /**
     * Holds the next send's final request build — after the send read its
     * payee and dry run, before it claims — while [armed].
     */
    private class SendBuildGate {
        val armed = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val open = CountDownLatch(1)

        fun pass() {
            if (armed.getAndSet(false)) {
                entered.countDown()
                check(open.await(10, TimeUnit.SECONDS)) { "send never released" }
            }
        }
    }

    /** Every runner builds a fresh request per call and records the dry runs' ones. */
    private fun stubCreateSendRequest(
        runner: SendCoinsTaskRunner,
        sends: Sends,
        sendBuildGate: SendBuildGate = SendBuildGate()
    ) {
        every { runner.observeCutoverCommitted() } returns flowOf(false)
        every {
            runner.createSendRequest(any<Boolean>(), any<PaymentIntent>(), any<Boolean>(), any<Boolean>())
        } answers {
            val signInputs = args[2] as Boolean
            if (signInputs) sendBuildGate.pass()
            mockk<SendRequest>(relaxed = true).also { request ->
                if (!signInputs) sends.recordDryRun(request)
            }
        }
    }

    private fun contactRequest(from: String, to: String) =
        DashPayContactRequest(from, to, accountReference, ByteArray(0), 0, 0, 1_000L, null, null)

    /** The real provider, pre-cutover: dashj serves the contact's address. */
    private fun provider(
        chain: FakeDashjChain,
        store: PaidContactAddressStore = PaidContactAddressStore(file = null)
    ) = ContactPaymentAddressProvider(
        cutoverCommitted = { false },
        sdkAccountReader = { _, _ -> null },
        dashjNextAddress = { _, _ -> chain.next() },
        dashjMarkUsed = { _, _, address -> chain.mark(address) },
        params = params,
        dashjSendingCursor = { _, _ -> chain.current },
        paidAddresses = store
    )

    /**
     * Holds the contact lookup of [initPaymentIntent] while [closed]:
     * [entered] counts down when a lookup is held, [open] releases it.
     */
    private class LookupGate {
        @Volatile var closed = false
        val entered = CountDownLatch(1)
        val open = CountDownLatch(1)

        fun pass() {
            if (closed) {
                entered.countDown()
                check(open.await(10, TimeUnit.SECONDS)) { "lookup never released" }
            }
        }
    }

    private fun buildViewModel(
        chain: FakeDashjChain,
        runner: SendCoinsTaskRunner,
        provider: ContactPaymentAddressProvider = provider(chain),
        gate: LookupGate = LookupGate(),
        dryRunFails: Boolean = false
    ): SendCoinsViewModel {
        val wallet = mockk<Wallet>(relaxed = true) {
            every { currentReceiveAddress() } returns Address.fromKey(params, ECKey())
            if (dryRunFails) {
                every { completeTx(any()) } throws InsufficientMoneyException(Coin.COIN)
            }
        }
        val walletData = mockk<WalletData>(relaxed = true) {
            every { this@mockk.wallet } returns wallet
            every { observeMaxOutputBalance() } returns emptyFlow()
        }
        val blockchainStateDao = mockk<BlockchainStateDao> {
            every { observeState() } returns emptyFlow()
        }
        val identityRepository = mockk<IdentityRepository>(relaxed = true) {
            coEvery { hasIdentity() } returns true
            coEvery { hasUsername() } returns true
            coEvery { getUsername() } returns "me"
        }
        val fromContact = contactRequest(contactUserId, ourUserId)
        val platformRepo = mockk<PlatformRepo>(relaxed = true) {
            coEvery { getLocalUserDataByUserId(any()) } returns null
            coEvery { getLocalUserDataByUserId(contactUserId) } coAnswers {
                gate.pass()
                UsernameSearchResult(
                    "contact",
                    DashPayProfile(contactUserId, "contact"),
                    toContactRequest = contactRequest(ourUserId, contactUserId),
                    fromContactRequest = fromContact
                )
            }
        }
        val contactRequestDao = mockk<DashPayContactRequestDao> {
            coEvery { loadToOthers(contactUserId) } returns listOf(fromContact)
        }
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
            platformRepo,
            contactRequestDao,
            provider
        )
    }

    /**
     * A send runner whose first send is held inside the send — before any
     * broadcast signal — until [releaseFirst], then broadcast; later sends
     * succeed at once.
     */
    private fun pausingRunner(sends: Sends, firstInSend: CountDownLatch, releaseFirst: CountDownLatch): SendCoinsTaskRunner {
        val runner = mockk<SendCoinsTaskRunner>(relaxed = true)
        coEvery { runner.isCutoverCommitted() } returns false
        stubCreateSendRequest(runner, sends)
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
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val onBroadcast = args[8] as ((String?) -> Unit)?
            val first = synchronized(sends) {
                sends.recipients += args[5] as Address?
                sends.recipients.size == 1
            }
            if (first) {
                firstInSend.countDown()
                check(releaseFirst.await(10, TimeUnit.SECONDS)) { "first send never released" }
            }
            onBroadcast?.invoke("bb".repeat(32))
            mockk<Transaction>(relaxed = true)
        }
        return runner
    }

    /** A send runner whose sends all broadcast and succeed. */
    private fun succeedingRunner(sends: Sends, sendBuildGate: SendBuildGate = SendBuildGate()): SendCoinsTaskRunner {
        val runner = mockk<SendCoinsTaskRunner>(relaxed = true)
        coEvery { runner.isCutoverCommitted() } returns false
        stubCreateSendRequest(runner, sends, sendBuildGate)
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
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val onBroadcast = args[8] as ((String?) -> Unit)?
            synchronized(sends) { sends.recipients += args[5] as Address? }
            onBroadcast?.invoke("cc".repeat(32))
            mockk<Transaction>(relaxed = true)
        }
        return runner
    }

    /**
     * A send runner whose first send is broadcast and then fails in the display
     * bridge (the broadcast signal fires, then it throws); later sends succeed.
     */
    private fun runner(sends: Sends): SendCoinsTaskRunner {
        val runner = mockk<SendCoinsTaskRunner>(relaxed = true)
        coEvery { runner.isCutoverCommitted() } returns false
        stubCreateSendRequest(runner, sends)
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
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val onBroadcast = args[8] as ((String?) -> Unit)?
            sends.recipients += args[5] as Address?
            if (sends.recipients.size == 1) {
                onBroadcast?.invoke("aa".repeat(32))
                throw RuntimeException("broadcast, but bridging into the dashj wallet failed")
            }
            mockk<Transaction>(relaxed = true)
        }
        return runner
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitCondition(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        idle()
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(20)
            idle()
        }
    }

    private fun SendCoinsViewModel.resolvedAddress(): String? =
        basePaymentIntent.getAddress(Constants.ADDRESS_NETWORK)

    /**
     * Waits until [resetState] has resolved [viewModel]'s contact payment again
     * ([SendCoinsViewModel.resetState] → refreshContactPayment → initPaymentIntent).
     * That resolution publishes the new payee first and runs its dry run last,
     * and the dry run clears dryrunSendRequest before it sets its own request —
     * so until it is done, dryrunSendRequest can still hold the PREVIOUS payee's
     * dry run. Waiting on `dryrunSendRequest != null` therefore passed early, and
     * the send then raced the dry run clearing it. The amount is not changed
     * after [open], so that dry run is the only one started after [dryRunMark]:
     * once [viewModel] holds a request built after the mark, it is done.
     */
    private fun awaitResolvedAgain(
        what: String,
        viewModel: SendCoinsViewModel,
        sends: Sends,
        dryRunMark: Int,
        address: Address
    ) = awaitCondition(what) {
        viewModel.resolvedAddress() == address.toBase58() &&
            viewModel.everythingPlausible() &&
            sends.isDryRunAfter(dryRunMark, viewModel.dryrunSendRequest)
    }

    /** Opens the send screen for the contact with an amount entered. */
    private fun open(viewModel: SendCoinsViewModel) {
        viewModel.setAmount(Coin.COIN)
        awaitCondition("amount applied") { viewModel.currentAmount == Coin.COIN }
        runBlocking { viewModel.initPaymentIntent(PaymentIntent.fromUserId(contactUserId)) }
        awaitCondition("contact payment ready to confirm") {
            viewModel.everythingPlausible() && viewModel.dryrunSendRequest != null
        }
    }

    @Test
    fun broadcastThenBridgeFailure_errorDismissed_nextConfirmationPaysANewAddress() {
        val chain = FakeDashjChain(keys)
        val sends = Sends()
        val viewModel = buildViewModel(chain, runner(sends))
        open(viewModel)
        assertEquals(keys[0].toBase58(), viewModel.resolvedAddress())

        // 1st confirmation: broadcast, then the bridge fails.
        assertThrows(RuntimeException::class.java) {
            runBlocking { viewModel.signAndSendPayment(Coin.COIN, null, true) }
        }
        assertEquals(listOf<Address?>(keys[0]), sends.recipients)
        idle()
        assertFalse("a spent contact intent cannot be confirmed", viewModel.everythingPlausible())

        // The fragment shows the error; dismissing it resets the state.
        val dryRunMark = sends.dryRunCount()
        viewModel.resetState()
        awaitResolvedAgain("contact address resolved again", viewModel, sends, dryRunMark, keys[1])

        // 2nd confirmation pays the new address.
        runBlocking { viewModel.signAndSendPayment(Coin.COIN, null, true) }
        assertEquals(listOf<Address?>(keys[0], keys[1]), sends.recipients)
    }

    @Test
    fun spentContactIntent_isNeverResent_evenWhenNoNewAddressCanBeResolved() {
        // dashj cannot be advanced past the paid key, so re-resolution finds
        // no unpaid address: sending stays blocked rather than reusing it.
        val chain = FakeDashjChain(keys, advances = false)
        val sends = Sends()
        val viewModel = buildViewModel(chain, runner(sends))
        open(viewModel)

        assertThrows(RuntimeException::class.java) {
            runBlocking { viewModel.signAndSendPayment(Coin.COIN, null, true) }
        }
        viewModel.resetState()
        awaitCondition("re-resolution attempted") { chain.marks.size >= 2 }
        idle()
        assertFalse(viewModel.everythingPlausible())

        // A confirmation that gets through anyway is refused before sending:
        // the failed re-resolution left no payee to send to.
        assertThrows(PayeeNotResolvedException::class.java) {
            runBlocking { viewModel.signAndSendPayment(Coin.COIN, null, true) }
        }
        assertEquals(listOf<Address?>(keys[0]), sends.recipients)
        assertTrue(viewModel.resolvedAddress() == keys[0].toBase58())
    }

    @Test
    fun twoSendScreens_sameContact_theSecondIsRefusedWhileTheFirstSends_thenPaysANewAddress() {
        // PR #1608 review, round 5: SendCoinsActivity allows several instances,
        // each with its own ViewModel; both resolved the same contact address.
        val chain = FakeDashjChain(keys)
        val provider = provider(chain)
        val sends = Sends()
        val firstInSend = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val runner = pausingRunner(sends, firstInSend, releaseFirst)
        val screen1 = buildViewModel(chain, runner, provider)
        val screen2 = buildViewModel(chain, runner, provider)
        open(screen1)
        open(screen2)
        assertEquals(keys[0].toBase58(), screen1.resolvedAddress())
        assertEquals(keys[0].toBase58(), screen2.resolvedAddress())

        // Screen 1 confirms; its send is held before any broadcast signal.
        val firstSend = thread { runBlocking { screen1.signAndSendPayment(Coin.COIN, null, true) } }
        assertTrue(firstInSend.await(10, TimeUnit.SECONDS))

        // Screen 2 confirms the same address: refused, nothing sent.
        assertThrows(ContactAddressAlreadyPaidException::class.java) {
            runBlocking { screen2.signAndSendPayment(Coin.COIN, null, true) }
        }
        assertEquals(listOf<Address?>(keys[0]), sends.recipients)
        idle()
        assertFalse(screen2.everythingPlausible())

        // Dismissing the error resolves screen 2 again: a new address.
        val dryRunMark = sends.dryRunCount()
        screen2.resetState()
        awaitResolvedAgain("screen 2 resolved a new address", screen2, sends, dryRunMark, keys[1])

        releaseFirst.countDown()
        firstSend.join(10_000)
        runBlocking { screen2.signAndSendPayment(Coin.COIN, null, true) }
        assertEquals(listOf<Address?>(keys[0], keys[1]), sends.recipients)
    }

    @Test
    fun reinitPending_afterAnotherScreenPaidTheAddress_nothingIsSent_untilTheNewAddressIsResolved() {
        // PR #1608 review, round 6: SendCoinsFragment re-runs initPaymentIntent
        // on every view creation — a configuration change with this retained
        // ViewModel. While that resolution is pending, the screen's previous
        // intent (address A) must not be sendable without its contact
        // metadata: another screen has already paid A.
        val chain = FakeDashjChain(keys)
        val provider = provider(chain)
        val sends = Sends()
        val runner = succeedingRunner(sends)
        val gate = LookupGate()
        val screen1 = buildViewModel(chain, runner, provider, gate)
        val screen2 = buildViewModel(chain, runner, provider)
        open(screen1)
        open(screen2)
        assertEquals(keys[0].toBase58(), screen1.resolvedAddress())

        // Screen 2 pays A.
        runBlocking { screen2.signAndSendPayment(Coin.COIN, null, true) }
        assertEquals(listOf<Address?>(keys[0]), sends.recipients)

        // Screen 1 is recreated: its re-initialization is held in the lookup.
        gate.closed = true
        val reinit = thread { runBlocking { screen1.initPaymentIntent(PaymentIntent.fromUserId(contactUserId)) } }
        assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
        idle()

        assertFalse("nothing can be confirmed while resolving", screen1.everythingPlausible())
        assertThrows(PayeeNotResolvedException::class.java) {
            runBlocking { screen1.signAndSendPayment(Coin.COIN, null, true) }
        }
        assertEquals("no send occurred", listOf<Address?>(keys[0]), sends.recipients)

        // The fragment dismisses the error; the resolution then completes with
        // a new address, which is payable.
        screen1.resetState()
        idle()
        gate.closed = false
        gate.open.countDown()
        awaitCondition("screen 1 resolved a new address") {
            !reinit.isAlive &&
                screen1.resolvedAddress() == keys[1].toBase58() &&
                screen1.everythingPlausible() &&
                screen1.dryrunSendRequest != null
        }
        runBlocking { screen1.signAndSendPayment(Coin.COIN, null, true) }
        assertEquals(listOf<Address?>(keys[0], keys[1]), sends.recipients)
    }

    @Test
    fun failedReinit_leavesNothingSendable() {
        // A re-initialization whose contact lookup fails must not leave the
        // previous intent sendable without its contact metadata.
        val chain = FakeDashjChain(keys)
        val sends = Sends()
        val viewModel = buildViewModel(chain, succeedingRunner(sends))
        open(viewModel)

        assertThrows(SendException::class.java) {
            // An unknown username: the lookup fails.
            runBlocking { viewModel.initPaymentIntent(PaymentIntent.fromUserId("unknownUserId")) }
        }

        assertFalse(viewModel.everythingPlausible())
        assertThrows(PayeeNotResolvedException::class.java) {
            runBlocking { viewModel.signAndSendPayment(Coin.COIN, null, true) }
        }
        assertTrue(sends.recipients.isEmpty())
    }

    /** The real provider, whose persisted cutover state cannot be read (it fails closed). */
    private fun unreadableCutoverProvider(chain: FakeDashjChain, failure: Exception) = ContactPaymentAddressProvider(
        cutoverCommitted = { throw failure },
        sdkAccountReader = { _, _ -> null },
        dashjNextAddress = { _, _ -> chain.next() },
        dashjMarkUsed = { _, _, address -> chain.mark(address) },
        params = params,
        dashjSendingCursor = { _, _ -> chain.current },
        paidAddresses = PaidContactAddressStore(file = null)
    )

    @Test
    fun contactScreenInit_cutoverStateUnreadable_showsTheContactError_andNothingIsSendable() {
        // PR #1608 review: the provider fails closed on an unreadable cutover
        // state, and that IOException escaped initPaymentIntent — the fragment
        // handles only SendException, so the app crashed. It is now the
        // unresolvable-contact-address error the screen shows.
        val chain = FakeDashjChain(keys)
        val sends = Sends()
        val unreadable = java.io.IOException("preferences unreadable")
        val viewModel = buildViewModel(
            chain,
            succeedingRunner(sends),
            provider = unreadableCutoverProvider(chain, unreadable)
        )

        // What SendCoinsFragment.onViewCreated does: anything but a
        // SendException is uncaught on the main thread.
        var shown: SendException? = null
        runBlocking {
            try {
                viewModel.initPaymentIntent(PaymentIntent.fromUserId(contactUserId))
            } catch (ex: SendException) {
                shown = ex
            }
        }

        val error = requireNotNull(shown) { "the screen shows the contact address error" }
        assertTrue(error.message.orEmpty().startsWith("Failed to get contact address"))
        // Coroutine stack-trace recovery may copy exceptions (the copy's cause
        // is the original): look for the read failure along the cause chain.
        assertTrue(
            "the read failure is kept as the cause",
            generateSequence(error.cause) { it.cause }.any {
                it is java.io.IOException && it.message == unreadable.message
            }
        )
        assertFalse(viewModel.everythingPlausible())
        assertThrows(PayeeNotResolvedException::class.java) {
            runBlocking { viewModel.signAndSendPayment(Coin.COIN, null, true) }
        }
        assertTrue("nothing was sent", sends.recipients.isEmpty())
        assertTrue("no address was marked", chain.marks.isEmpty())
    }

    @Test
    fun contactScreenInit_lookupCancelled_cancellationPropagates() {
        val chain = FakeDashjChain(keys)
        val viewModel = buildViewModel(
            chain,
            succeedingRunner(Sends()),
            provider = unreadableCutoverProvider(chain, kotlinx.coroutines.CancellationException("screen closed"))
        )

        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { viewModel.initPaymentIntent(PaymentIntent.fromUserId(contactUserId)) }
        }
        assertFalse(viewModel.everythingPlausible())
    }

    @Test
    fun noDryRunResult_refusesTheSend_withoutSpendingTheContactIntent() {
        // A send that finds no dry run result (one is running, or the last one
        // failed) fails cleanly — it used to throw a NullPointerException — and
        // before anything is claimed: the contact address stays payable.
        val chain = FakeDashjChain(keys)
        val sends = Sends()
        val viewModel = buildViewModel(chain, succeedingRunner(sends), dryRunFails = true)
        viewModel.setAmount(Coin.COIN)
        awaitCondition("amount applied") { viewModel.currentAmount == Coin.COIN }
        runBlocking { viewModel.initPaymentIntent(PaymentIntent.fromUserId(contactUserId)) }
        idle()
        assertTrue(viewModel.everythingPlausible())
        assertEquals(null, viewModel.dryrunSendRequest)

        assertThrows(DryRunNotReadyException::class.java) {
            runBlocking { viewModel.signAndSendPayment(Coin.COIN, null, true) }
        }
        assertTrue(sends.recipients.isEmpty())
        assertTrue(chain.marks.isEmpty())

        viewModel.resetState()
        awaitCondition("back to input") { viewModel.everythingPlausible() }
        assertEquals("not spent, not re-resolved", keys[0].toBase58(), viewModel.resolvedAddress())
    }

    @Test
    fun claimNotWritable_refusesTheSend_withoutSpendingTheIntentOrResolvingAgain() {
        // PR #1608 review, round 6: the claim cannot reach disk — nothing is
        // sent, and re-resolving would not help, so none is started.
        val blocked = java.io.File(
            java.nio.file.Files.createTempDirectory("paid-store").toFile(),
            "blocked"
        ).apply { mkdirs() }
        java.io.File(blocked, "x").writeText("")
        val chain = FakeDashjChain(keys)
        val sends = Sends()
        val viewModel = buildViewModel(
            chain,
            succeedingRunner(sends),
            provider(chain, PaidContactAddressStore(blocked))
        )
        open(viewModel)

        assertThrows(ContactPaymentNotRecordableException::class.java) {
            runBlocking { viewModel.signAndSendPayment(Coin.COIN, null, true) }
        }
        assertTrue(sends.recipients.isEmpty())
        assertTrue(chain.marks.isEmpty())

        viewModel.resetState()
        awaitCondition("back to input") { viewModel.everythingPlausible() }
        assertEquals("not re-resolved", keys[0].toBase58(), viewModel.resolvedAddress())
    }

    @Test
    fun reResolutionStartsAfterTheSendReadItsPayee_theSendIsRefused_nothingClaimedOrSent() {
        // PR #1608 review, round 9 (thread r4249347679): the send read the
        // payee and its dry run; then a re-resolution (the screen recreated)
        // cleared both. The send must not go on with its copies.
        val chain = FakeDashjChain(keys)
        val store = PaidContactAddressStore(file = null)
        val sends = Sends()
        val sendBuildGate = SendBuildGate()
        val lookupGate = LookupGate()
        val viewModel = buildViewModel(
            chain,
            succeedingRunner(sends, sendBuildGate),
            provider(chain, store),
            lookupGate
        )
        open(viewModel)
        assertEquals(keys[0].toBase58(), viewModel.resolvedAddress())

        // The send is held after it read its snapshot, before its claim.
        sendBuildGate.armed.set(true)
        var outcome: Result<Transaction>? = null
        val send = thread {
            outcome = runCatching { runBlocking { viewModel.signAndSendPayment(Coin.COIN, null, true) } }
        }
        assertTrue(sendBuildGate.entered.await(10, TimeUnit.SECONDS))

        // A re-resolution starts (it is held in its contact lookup, after it
        // cleared the payee and the dry run).
        lookupGate.closed = true
        val reinit = thread { runBlocking { viewModel.initPaymentIntent(PaymentIntent.fromUserId(contactUserId)) } }
        assertTrue(lookupGate.entered.await(10, TimeUnit.SECONDS))

        sendBuildGate.open.countDown()
        send.join(10_000)
        assertFalse(send.isAlive)

        assertTrue(
            "refused: ${outcome?.exceptionOrNull()}",
            outcome?.exceptionOrNull() is PayeeNotResolvedException
        )
        assertTrue("nothing sent", sends.recipients.isEmpty())
        assertTrue("nothing claimed", store.paid().isEmpty())
        assertTrue("nothing marked on dashj", chain.marks.isEmpty())

        // The fragment dismisses the error; the re-resolution completes with
        // the same, still unpaid address, which is then paid once.
        viewModel.resetState()
        idle()
        lookupGate.closed = false
        lookupGate.open.countDown()
        awaitCondition("resolved again") {
            !reinit.isAlive &&
                viewModel.resolvedAddress() == keys[0].toBase58() &&
                viewModel.everythingPlausible() &&
                viewModel.dryrunSendRequest != null
        }
        runBlocking { viewModel.signAndSendPayment(Coin.COIN, null, true) }
        assertEquals(listOf<Address?>(keys[0]), sends.recipients)
    }

    @Test
    fun aSendThatClaimedBeforeTheReResolution_goesOut_andTheReResolutionPicksANewAddress() {
        // The other order: the claim is made before the re-resolution starts —
        // the send goes on, and the re-resolution sees the address claimed.
        val chain = FakeDashjChain(keys)
        val store = PaidContactAddressStore(file = null)
        val sends = Sends()
        val firstInSend = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val viewModel = buildViewModel(
            chain,
            pausingRunner(sends, firstInSend, releaseFirst),
            provider(chain, store)
        )
        open(viewModel)

        val send = thread { runBlocking { viewModel.signAndSendPayment(Coin.COIN, null, true) } }
        assertTrue(firstInSend.await(10, TimeUnit.SECONDS)) // claimed, in the send
        assertTrue(keys[0].toBase58() in store.paid())

        val dryRunMark = sends.dryRunCount()
        val reinit = thread { runBlocking { viewModel.initPaymentIntent(PaymentIntent.fromUserId(contactUserId)) } }
        releaseFirst.countDown()
        send.join(10_000)
        assertFalse(send.isAlive)
        assertEquals(listOf<Address?>(keys[0]), sends.recipients)

        // The fragment shows the result and resets the state.
        viewModel.resetState()
        awaitResolvedAgain("a new address", viewModel, sends, dryRunMark, keys[1])
        awaitCondition("the re-resolution finished") { !reinit.isAlive }
    }
}
