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
import kotlinx.coroutines.test.runTest
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
                mockk(relaxed = true)
            )
        )
        coEvery { runner.logSendTxEvent(any(), any()) } returns Unit
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
        coEvery { bridgeFactory.bridge(any(), any(), any(), any()) } returns result
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
    fun preCutover_dashjSend_signalsAfterCommit() = runTest {
        coEvery { sdkL1SendService.cutoverCommitted() } returns false
        val signals = mutableListOf<String?>()

        val tx = sendCollecting(signals)

        assertEquals(listOf(tx.txId.toString()), signals)
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
