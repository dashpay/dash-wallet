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
package de.schildbach.wallet.payments

import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.sdk.BridgedTxResult
import de.schildbach.wallet.service.platform.sdk.ContactAddressSource
import de.schildbach.wallet.service.platform.sdk.ContactPaymentAddress
import de.schildbach.wallet.service.platform.sdk.ContactPaymentAddressProvider
import de.schildbach.wallet.service.platform.sdk.ContactPaymentTarget
import de.schildbach.wallet.service.platform.sdk.L1_FUNDING_GATE_CLOSED_REASON
import de.schildbach.wallet.service.platform.sdk.PaidContactAddressStore
import de.schildbach.wallet.service.platform.sdk.SdkBridgedTransactionFactory
import de.schildbach.wallet.service.platform.sdk.SdkL1SendService
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.wallet.FriendChainAccess
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dashj.platform.dpp.identifier.Identifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The SendRequest funnel's `onBroadcast` signal — what the send UI uses to mark
 * a DashPay contact's payment address paid (PR #1608). It must fire on a PROVEN
 * broadcast even when a later step throws (post-cutover: the display bridge),
 * on an UNKNOWN outcome (SDK `Ambiguous`, txid null — the payment may have gone
 * out), and never on a pre-broadcast failure. The last tests run the real
 * [ContactPaymentAddressProvider.sendThenMarkUsed] around the real funnel.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [29], manifest = Config.NONE)
class SendCoinsTaskRunnerBroadcastSignalTest {

    private val networkParams: NetworkParameters = TestNet3Params.get()
    private val sdkTxid = "ab".repeat(32)
    private val recipient: Address = Address.fromKey(networkParams, ECKey())

    private lateinit var wallet: Wallet
    private lateinit var sdkL1SendService: SdkL1SendService
    private lateinit var bridgeFactory: SdkBridgedTransactionFactory
    private lateinit var runner: SendCoinsTaskRunner
    private lateinit var verifier: PendingDirectPaymentVerifier

    /** The wipe barrier the dashj send holds through its commit and broadcast. */
    private val fundingSelection = FundingSelectionLock()

    @Before
    fun setUp() {
        val bitcoinjContext = org.bitcoinj.core.Context.getOrCreate(networkParams)
        org.bitcoinj.core.Context.propagate(bitcoinjContext)

        wallet = mockk(relaxed = true)
        every { wallet.context } returns bitcoinjContext
        every { wallet.isAddressMine(any()) } returns false

        val walletData = mockk<WalletData>(relaxed = true)
        every { walletData.wallet } returns wallet
        every { walletData.networkParameters } returns networkParams

        val securityFunctions = mockk<SecurityFunctions>(relaxed = true)
        mockkStatic(SecurityGuard::class)
        val securityGuard = mockk<SecurityGuard>(relaxed = true)
        every { SecurityGuard.getInstance() } returns securityGuard
        every { securityGuard.retrievePassword() } returns "testPassword"
        every { securityFunctions.deriveKey(any(), any()) } returns mockk(relaxed = true)

        sdkL1SendService = mockk()
        bridgeFactory = mockk()

        runner = spyk(
            SendCoinsTaskRunner(
                walletData,
                mockk<WalletApplication>(relaxed = true),
                securityFunctions,
                mockk<PackageInfoProvider>(relaxed = true),
                mockk<AnalyticsService>(relaxed = true),
                mockk<BlockchainIdentityConfig>(relaxed = true),
                mockk<IdentityRepository>(relaxed = true),
                mockk(relaxed = true),
                mockk<TransactionMetadataProvider>(relaxed = true),
                sdkL1SendService,
                mockk(relaxed = true),
                mockk(relaxed = true),
                bridgeFactory,
                // the pending-payment verifier: no uncertain payment, nothing to restore
                mockk<PendingDirectPaymentVerifier>(relaxed = true).also { verifier = it },
                fundingSelection
            )
        )
        coEvery { runner.logSendTxEvent(any(), any()) } returns Unit
        // the wallet the send resolved is still the installed one, unless a test wipes it
        every { verifier.stillOwnedBy(wallet) } returns true
    }

    @After
    fun tearDown() {
        unmockkStatic(SecurityGuard::class)
    }

    private fun sendRequest(): SendRequest = SendRequest.to(recipient, Coin.CENT)

    private fun postCutover(result: SdkWriteResult<String>) {
        coEvery { sdkL1SendService.cutoverCommitted() } returns true
        coEvery { sdkL1SendService.sendToAddress(any(), any(), any(), any()) } returns result
    }

    private fun bridgeReturns(result: BridgedTxResult) {
        coEvery { bridgeFactory.bridge(any(), any(), any(), any(), any()) } returns result
    }

    /** Broadcast signals as their txid; a not-broadcast signal as [NOT_BROADCAST]. */
    private suspend fun sendCollecting(signals: MutableList<String?>): Transaction =
        runner.sendCoins(
            sendRequest(),
            checkBalanceConditions = false,
            intendedRecipient = recipient,
            onBroadcast = { signals += it },
            onNotBroadcast = { signals += NOT_BROADCAST }
        )

    private companion object {
        const val NOT_BROADCAST = "<not broadcast>"
    }

    // ── the funnel's signal ────────────────────────────────────────────

    @Test
    fun postCutover_broadcastThenBridgeFailure_signalsTheBroadcast_andStillThrows() = runTest {
        postCutover(SdkWriteResult.Broadcast(sdkTxid))
        bridgeReturns(BridgedTxResult.NotBridged("bytes never appeared"))
        val signals = mutableListOf<String?>()

        try {
            sendCollecting(signals)
            fail("a bridge failure still surfaces to the UI")
        } catch (expected: RuntimeException) {
            assertTrue(expected.message.orEmpty().contains(sdkTxid))
        }

        assertEquals(listOf(sdkTxid), signals)
    }

    @Test
    fun postCutover_broadcastAndBridged_signalsOnce() = runTest {
        postCutover(SdkWriteResult.Broadcast(sdkTxid))
        bridgeReturns(BridgedTxResult.Bridged(mockk(relaxed = true), adoptedWalletInstance = false))
        val signals = mutableListOf<String?>()

        sendCollecting(signals)

        assertEquals(listOf(sdkTxid), signals)
    }

    @Test
    fun postCutover_notBroadcast_signalsOnlyNotBroadcast() = runTest {
        postCutover(SdkWriteResult.NotBroadcast(L1_FUNDING_GATE_CLOSED_REASON))
        val signals = mutableListOf<String?>()

        try {
            sendCollecting(signals)
            fail("expected the not-synced failure")
        } catch (expected: SendEngineNotSyncedException) {
            // pre-broadcast refusal
        }

        assertEquals(listOf<String?>(NOT_BROADCAST), signals)
    }

    @Test
    fun postCutover_notSdkRoutable_signalsNotBroadcast() = runTest {
        coEvery { sdkL1SendService.cutoverCommitted() } returns true
        val signals = mutableListOf<String?>()

        try {
            runner.sendCoins(
                SendRequest.to(recipient, Coin.CENT).apply { tx.addOutput(Coin.CENT, recipient) },
                checkBalanceConditions = false,
                intendedRecipient = null,
                onBroadcast = { signals += it },
                onNotBroadcast = { signals += NOT_BROADCAST }
            )
            fail("expected the fail-closed refusal")
        } catch (expected: SendNotSdkRoutableException) {
            // nothing built or broadcast
        }

        assertEquals(listOf<String?>(NOT_BROADCAST), signals)
    }

    @Test
    fun postCutover_ambiguous_signalsAnUnknownTxid_andStillThrowsTheCause() = runTest {
        postCutover(SdkWriteResult.Ambiguous(IllegalStateException("timeout")))
        val signals = mutableListOf<String?>()

        try {
            sendCollecting(signals)
            fail("expected the ambiguous failure")
        } catch (expected: IllegalStateException) {
            // the SDK's cause, rethrown (coroutine stack recovery may copy it)
            assertEquals("timeout", expected.message)
        }

        assertEquals(listOf<String?>(null), signals)
        // never retried: exactly one SDK send attempt, and dashj never ran
        coVerify(exactly = 1) { sdkL1SendService.sendToAddress(any(), any(), any(), any()) }
        verify(exactly = 0) { wallet.sendCoinsOffline(any()) }
    }

    @Test
    fun postCutover_aThrowingCallback_doesNotFailTheSentPayment() = runTest {
        postCutover(SdkWriteResult.Broadcast(sdkTxid))
        val bridged = mockk<Transaction>(relaxed = true)
        bridgeReturns(BridgedTxResult.Bridged(bridged, adoptedWalletInstance = false))

        val tx = runner.sendCoins(
            sendRequest(),
            checkBalanceConditions = false,
            intendedRecipient = recipient,
            onBroadcast = { throw IllegalStateException("boom") }
        )

        assertTrue(tx === bridged)
    }

    @Test
    fun postCutover_aWipeDuringTheSdkSend_refusesTheBroadcast_evenWithoutANamedOwner() = runTest {
        // the caller named no origin wallet: the reroute must still bind the send to the wallet it
        // resolved, so a wipe landing inside the SDK call cannot have the replacement pay
        coEvery { sdkL1SendService.cutoverCommitted() } returns true
        every { verifier.stillOwnedBy(wallet) } returns true
        coEvery { sdkL1SendService.sendToAddress(any(), any(), any(), any()) } coAnswers {
            every { verifier.stillOwnedBy(wallet) } returns false // the wipe lands
            arg<suspend () -> Unit>(3).invoke() // the pre-broadcast hook
            SdkWriteResult.Broadcast(sdkTxid)
        }
        bridgeReturns(BridgedTxResult.Bridged(mockk(relaxed = true), adoptedWalletInstance = false))
        val signals = mutableListOf<String?>()

        try {
            sendCollecting(signals)
            fail("a payment confirmed on a wiped wallet was broadcast from its replacement")
        } catch (expected: IllegalStateException) {
            // refused before the broadcast
        }

        coVerify(exactly = 0) { bridgeFactory.bridge(any(), any(), any(), any(), any()) }
    }

    @Test
    fun postCutover_typedOverload_aWipeDuringTheSdkSend_refusesTheBroadcast_evenWithoutANamedOwner() = runTest {
        // the typed overload resolves and checks the installed wallet when the caller names none:
        // its SDK reroute must be bound to that resolved wallet, not to the null it was handed
        coEvery { sdkL1SendService.cutoverCommitted() } returns true
        every { verifier.stillOwnedBy(wallet) } returns true
        coEvery { sdkL1SendService.sendToAddress(any(), any(), any(), any()) } coAnswers {
            every { verifier.stillOwnedBy(wallet) } returns false // the wipe lands
            arg<suspend () -> Unit>(3).invoke() // the pre-broadcast hook
            SdkWriteResult.Broadcast(sdkTxid)
        }
        bridgeReturns(BridgedTxResult.Bridged(mockk(relaxed = true), adoptedWalletInstance = false))

        try {
            runner.sendCoins(recipient, Coin.CENT, checkBalanceConditions = false, originWallet = null)
            fail("a payment confirmed on a wiped wallet was broadcast from its replacement")
        } catch (expected: IllegalStateException) {
            // refused before the broadcast
        }

        coVerify(exactly = 0) { bridgeFactory.bridge(any(), any(), any(), any(), any()) }
    }

    @Test
    fun postCutover_aWipeAfterTheBroadcast_neverBridges_andReportsThePaymentPending() = runTest {
        // Reset Wallet starts once the SDK broadcast went out and before the bridge: the bridge
        // must not commit into the wallet being detached, and the payment is sent-or-unknown
        // (pending, never retried), not "not sent" and not a silent cancellation
        coEvery { sdkL1SendService.cutoverCommitted() } returns true
        coEvery { sdkL1SendService.sendToAddress(any(), any(), any(), any()) } coAnswers {
            arg<suspend () -> Unit>(3).invoke() // the pre-broadcast ownership check
            fundingSelection.raiseWipeBarrier() // the wipe starts as the broadcast returns
            SdkWriteResult.Broadcast(sdkTxid)
        }
        bridgeReturns(BridgedTxResult.Bridged(mockk(relaxed = true), adoptedWalletInstance = false))
        val signals = mutableListOf<String?>()

        try {
            sendCollecting(signals)
            fail("a send the wipe cut short after its broadcast was reported as completed")
        } catch (expected: WalletWipedDuringSubmissionException) {
            assertTrue(expected is org.dash.wallet.common.services.PaymentSubmissionPendingException)
            assertEquals("pending under its txid", sdkTxid, expected.txId)
        } finally {
            fundingSelection.lowerWipeBarrier()
        }

        coVerify(exactly = 0) { bridgeFactory.bridge(any(), any(), any(), any(), any()) }
        assertFalse("reported as not sent: $signals", signals.contains(NOT_BROADCAST))
    }

    @Test
    fun postCutover_theBroadcastAndTheBridgeShareOneHold_soAWipeWaitsForTheBridge() = runBlocking<Unit> {
        // the wipe raised while the bridge runs must not drain (and detach the wallet) before the
        // bridge has returned: the broadcast and the bridge are one hold, not two
        coEvery { sdkL1SendService.cutoverCommitted() } returns true
        coEvery { sdkL1SendService.sendToAddress(any(), any(), any(), any()) } returns SdkWriteResult.Broadcast(sdkTxid)
        val inBridge = java.util.concurrent.CountDownLatch(1)
        val releaseBridge = java.util.concurrent.CountDownLatch(1)
        coEvery { bridgeFactory.bridge(any(), any(), any(), any(), any()) } answers {
            inBridge.countDown()
            releaseBridge.await() // a commit that ignores cancellation
            BridgedTxResult.Bridged(mockk(relaxed = true), adoptedWalletInstance = false)
        }
        try {
            val send = async(Dispatchers.IO) { runCatching { sendCollecting(mutableListOf()) } }
            assertTrue(inBridge.await(5, java.util.concurrent.TimeUnit.SECONDS))
            fundingSelection.raiseWipeBarrier()
            val drained = async(Dispatchers.IO) { fundingSelection.awaitWipeDrained() }
            delay(200)
            assertFalse("the wipe drained while the bridge ran", drained.isCompleted)
            releaseBridge.countDown()
            withTimeout(5_000) { drained.await() }
            withTimeout(5_000) { send.await() }
        } finally {
            releaseBridge.countDown()
            fundingSelection.lowerWipeBarrier()
        }
    }

    @Test
    fun postCutover_aWipeUnderWayBeforeTheSend_refusesIt_asNotSent() = runTest {
        postCutover(SdkWriteResult.Broadcast(sdkTxid))
        fundingSelection.raiseWipeBarrier()
        val signals = mutableListOf<String?>()

        try {
            sendCollecting(signals)
            fail("a send admitted during a wipe")
        } catch (expected: IllegalStateException) {
            assertFalse(
                "refused before anything went out is not pending",
                expected is org.dash.wallet.common.services.PaymentSubmissionPendingException
            )
        } finally {
            fundingSelection.lowerWipeBarrier()
        }

        coVerify(exactly = 0) { sdkL1SendService.sendToAddress(any(), any(), any(), any()) }
        assertEquals(listOf(NOT_BROADCAST), signals)
    }

    @Test
    fun postCutover_typedOverload_bridgesIntoTheResolvedWallet() = runTest {
        // the bridge commits into the wallet the send resolved, never re-resolving it afterwards
        postCutover(SdkWriteResult.Broadcast(sdkTxid))
        bridgeReturns(BridgedTxResult.Bridged(mockk(relaxed = true), adoptedWalletInstance = false))
        every { verifier.stillOwnedBy(wallet) } returns true

        runner.sendCoins(recipient, Coin.CENT, checkBalanceConditions = false, originWallet = null)

        coVerify(exactly = 1) { bridgeFactory.bridge(sdkTxid, any(), any(), any(), originWallet = wallet) }
    }

    @Test
    fun neutralOverload_aWipeBeforeTheBroadcast_refusesIt_evenWithoutASession() = runTest {
        // no session: the wallet installed when the send began is the one it is bound to; a
        // replacement installed by a wipe before the broadcast must not be the one asked
        val replacement = mockk<Wallet>(relaxed = true)
        val walletData = mockk<WalletData>(relaxed = true)
        every { walletData.wallet } returns wallet
        every { walletData.networkParameters } returns networkParams
        val neutral = SendCoinsTaskRunner(
            walletData,
            mockk<WalletApplication>(relaxed = true),
            mockk<SecurityFunctions>(relaxed = true),
            mockk<PackageInfoProvider>(relaxed = true),
            mockk<AnalyticsService>(relaxed = true),
            mockk<BlockchainIdentityConfig>(relaxed = true),
            mockk<IdentityRepository>(relaxed = true),
            mockk(relaxed = true),
            mockk<TransactionMetadataProvider>(relaxed = true),
            sdkL1SendService,
            mockk(relaxed = true),
            mockk(relaxed = true),
            bridgeFactory,
            verifier,
            fundingSelection = fundingSelection
        )
        every { verifier.stillOwnedBy(wallet) } returns true
        every { verifier.stillOwnedBy(replacement) } returns true
        coEvery { sdkL1SendService.cutoverCommitted() } returns true
        coEvery { sdkL1SendService.sendToAddress(any(), any(), any(), any()) } coAnswers {
            // the wipe lands: the old wallet is retired and a replacement installed
            every { verifier.stillOwnedBy(wallet) } returns false
            every { walletData.wallet } returns replacement
            arg<suspend () -> Unit>(3).invoke()
            SdkWriteResult.Broadcast(sdkTxid)
        }

        try {
            neutral.sendCoins(recipient.toBase58(), org.dash.wallet.common.money.Dash(1_000_000), false, false, null)
            fail("a payment begun on a wiped wallet was let through on its replacement")
        } catch (expected: IllegalStateException) {
            // refused before the broadcast
        }
    }

    @Test
    fun preCutover_dashjSend_signalsAfterCommit() = runTest {
        coEvery { sdkL1SendService.cutoverCommitted() } returns false
        every { verifier.stillOwnedBy(wallet) } returns true
        val signals = mutableListOf<String?>()

        val tx = sendCollecting(signals)

        assertEquals(listOf(tx.txId.toString()), signals)
    }

    @Test
    fun preCutover_dashjSend_refusedByAWipeBeforeTheCommit_isNotSent() = runTest {
        coEvery { sdkL1SendService.cutoverCommitted() } returns false
        every { verifier.stillOwnedBy(wallet) } returns true
        // Reset Wallet has started: the barrier is up, the old wallet still installed
        fundingSelection.raiseWipeBarrier()
        val signals = mutableListOf<String?>()
        try {
            try {
                sendCollecting(signals)
                fail("a dashj send went ahead while its wallet was being wiped")
            } catch (expected: IllegalStateException) {
                // (coroutines may rethrow a copy: compare the message)
                assertTrue(expected.message.orEmpty().contains("is being wiped"))
            }
        } finally {
            fundingSelection.lowerWipeBarrier()
        }

        // definitely not sent: nothing committed or announced
        assertEquals(listOf<String?>(NOT_BROADCAST), signals)
        verify(exactly = 0) { wallet.sendCoinsOffline(any()) }
        verify(exactly = 0) { wallet.maybeCommitTx(any()) }
    }

    @Test
    fun preCutover_dashjSend_aWipeDuringTheCommit_waitsForTheBroadcast() = runBlocking {
        coEvery { sdkL1SendService.cutoverCommitted() } returns false
        every { verifier.stillOwnedBy(wallet) } returns true
        val committing = java.util.concurrent.CountDownLatch(1)
        val commitMayFinish = java.util.concurrent.CountDownLatch(1)
        every { wallet.sendCoinsOffline(any()) } answers {
            committing.countDown()
            // a commit that takes a while: bounded, so a broken barrier cannot hang the suite
            commitMayFinish.await(10, java.util.concurrent.TimeUnit.SECONDS)
            mockk(relaxed = true)
        }
        val signals = java.util.Collections.synchronizedList(mutableListOf<String?>())
        try {
            val send = async(Dispatchers.IO) { sendCollecting(signals) }
            assertTrue(committing.await(5, java.util.concurrent.TimeUnit.SECONDS))

            // Reset Wallet starts while the commit is under way
            fundingSelection.raiseWipeBarrier()
            val drained = async(Dispatchers.IO) { fundingSelection.awaitWipeDrained() }
            delay(200)
            assertFalse("the wipe waits for the commit and the broadcast", drained.isCompleted)
            assertTrue("nothing announced yet", signals.isEmpty())

            commitMayFinish.countDown()
            val tx = withTimeout(5_000) { send.await() }
            withTimeout(5_000) { drained.await() }

            // committed, then announced: never reported unsent
            assertEquals(listOf(tx.txId.toString()), signals)
        } finally {
            commitMayFinish.countDown()
            fundingSelection.lowerWipeBarrier()
        }
    }

    @Test
    fun preCutover_dashjCompleteTxFailure_signalsOnlyNotBroadcast() = runTest {
        coEvery { sdkL1SendService.cutoverCommitted() } returns false
        every { wallet.sendCoinsOffline(any()) } throws InsufficientMoneyException(Coin.COIN)
        val signals = mutableListOf<String?>()

        try {
            sendCollecting(signals)
            fail("expected InsufficientMoneyException")
        } catch (expected: InsufficientMoneyException) {
            // never committed
        }

        assertEquals(listOf<String?>(NOT_BROADCAST), signals)
    }

    @Test
    fun preCutover_dashjSendAllAndDustAdjustmentFailures_signalOnlyNotBroadcast() = runTest {
        // PR #1608 review, round 9: completeTx's send-all ("could not adjust
        // downwards") and dust refusals happen before the commit.
        coEvery { sdkL1SendService.cutoverCommitted() } returns false
        for (failure in listOf(Wallet.CouldNotAdjustDownwards(), Wallet.DustySendRequested())) {
            every { wallet.sendCoinsOffline(any()) } throws failure
            val signals = mutableListOf<String?>()

            try {
                sendCollecting(signals)
                fail("expected ${failure.javaClass.simpleName}")
            } catch (expected: Wallet.CompletionException) {
                // (coroutines may rethrow a copy: compare the type)
                assertEquals(failure.javaClass, expected.javaClass)
            }

            assertEquals(failure.javaClass.simpleName, listOf<String?>(NOT_BROADCAST), signals)
        }
    }

    @Test
    fun completeTxFailures_includeTheSendAllAndDustAdjustments() {
        assertTrue(isCompleteTxFailure(Wallet.CouldNotAdjustDownwards()))
        assertTrue(isCompleteTxFailure(Wallet.DustySendRequested()))
        assertTrue(isCompleteTxFailure(InsufficientMoneyException(Coin.COIN)))
        assertFalse(isCompleteTxFailure(IllegalStateException("commit failed")))
    }

    @Test
    fun preCutover_dashjCommitFailure_isNotProvenUnsent() = runTest {
        // A failure of the commit itself: the transaction may be in the wallet,
        // which rebroadcasts pending transactions — no proof nothing went out.
        coEvery { sdkL1SendService.cutoverCommitted() } returns false
        every { wallet.sendCoinsOffline(any()) } throws IllegalStateException("commit failed")
        val signals = mutableListOf<String?>()

        try {
            sendCollecting(signals)
            fail("expected the commit failure")
        } catch (expected: IllegalStateException) {
            // surfaced as before
        }

        assertTrue(signals.isEmpty())
    }

    // ── end to end with the contact address provider ────────────────────

    private val marks = mutableListOf<Address>()

    private val paidStore = PaidContactAddressStore(file = null)

    private fun provider() = ContactPaymentAddressProvider(
        cutoverCommitted = { true },
        sdkAccountReader = { _, _ -> null },
        dashjNextAddress = { _, _ -> recipient },
        dashjMarkUsed = { _, _, address ->
            marks += address
            FriendChainAccess.MarkResult.MARKED
        },
        params = networkParams,
        paidAddresses = paidStore
    )

    private val contactPayment = ContactPaymentAddress(
        ContactPaymentTarget(
            Identifier.from(ByteArray(32) { 1 }).toString(),
            Identifier.from(ByteArray(32) { 2 }).toString(),
            accountReference = 7
        ),
        recipient,
        ContactAddressSource.DASHJ
    )

    @Test
    fun contactPayment_broadcastThenBridgeFailure_isMarkedPaid() = runTest {
        postCutover(SdkWriteResult.Broadcast(sdkTxid))
        bridgeReturns(BridgedTxResult.NotBridged("bytes never appeared"))
        val provider = provider()

        try {
            provider.sendThenMarkUsed(contactPayment) { signals ->
                runner.sendCoins(
                    sendRequest(),
                    checkBalanceConditions = false,
                    intendedRecipient = recipient,
                    onBroadcast = signals::broadcast,
                    onNotBroadcast = signals::notBroadcast
                )
            }
            fail("the bridge failure still surfaces")
        } catch (expected: RuntimeException) {
            // the UI reports "sent, appears after the next sync"
        }

        assertEquals(listOf(recipient), marks)
    }

    @Test
    fun contactPayment_ambiguousOutcome_isMarkedPaid_andStillThrows() = runTest {
        postCutover(SdkWriteResult.Ambiguous(IllegalStateException("timeout")))
        val provider = provider()

        try {
            provider.sendThenMarkUsed(contactPayment) { signals ->
                runner.sendCoins(
                    sendRequest(),
                    checkBalanceConditions = false,
                    intendedRecipient = recipient,
                    onBroadcast = signals::broadcast,
                    onNotBroadcast = signals::notBroadcast
                )
            }
            fail("expected the ambiguous failure")
        } catch (expected: IllegalStateException) {
            // the UI still reports the failure; the address is not reused
        }

        assertEquals(listOf(recipient), marks)
        assertTrue(recipient.toBase58() in paidStore.paid())
    }

    @Test
    fun contactPayment_dashjSendAllOrDustAdjustmentFailure_releasesTheClaim() = runTest {
        // The claim of a contact address whose dashj send failed in completeTx
        // (send-all adjustment, dust) is given back: nothing was committed.
        coEvery { sdkL1SendService.cutoverCommitted() } returns false
        val provider = provider()
        for (failure in listOf(Wallet.CouldNotAdjustDownwards(), Wallet.DustySendRequested())) {
            every { wallet.sendCoinsOffline(any()) } throws failure

            try {
                provider.sendThenMarkUsed(contactPayment) { signals ->
                    runner.sendCoins(
                        SendRequest.emptyWallet(recipient),
                        checkBalanceConditions = false,
                        intendedRecipient = recipient,
                        onBroadcast = signals::broadcast,
                        onNotBroadcast = signals::notBroadcast
                    )
                }
                fail("expected ${failure.javaClass.simpleName}")
            } catch (expected: Wallet.CompletionException) {
                // nothing was committed
            }

            assertTrue(failure.javaClass.simpleName, marks.isEmpty())
            assertTrue("the claim was released after ${failure.javaClass.simpleName}", paidStore.paid().isEmpty())
            assertFalse(provider.isClaimedOrPaid(contactPayment))
        }
    }

    @Test
    fun contactPayment_preBroadcastFailure_isNotMarked() = runTest {
        postCutover(SdkWriteResult.NotBroadcast(L1_FUNDING_GATE_CLOSED_REASON))
        val provider = provider()

        try {
            provider.sendThenMarkUsed(contactPayment) { signals ->
                runner.sendCoins(
                    sendRequest(),
                    checkBalanceConditions = false,
                    intendedRecipient = recipient,
                    onBroadcast = signals::broadcast,
                    onNotBroadcast = signals::notBroadcast
                )
            }
            fail("expected the not-synced failure")
        } catch (expected: SendEngineNotSyncedException) {
            // nothing was broadcast
        }

        assertTrue(marks.isEmpty())
        assertTrue(paidStore.paid().isEmpty())
    }
}
