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
import de.schildbach.wallet.service.platform.sdk.SdkBridgedTransactionFactory
import de.schildbach.wallet.service.platform.sdk.SdkL1SendService
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
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
 * and never on a pre-broadcast failure. The last tests run the real
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
                bridgeFactory
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

    private suspend fun sendCollecting(signals: MutableList<String>): Transaction =
        runner.sendCoins(
            sendRequest(),
            checkBalanceConditions = false,
            intendedRecipient = recipient,
            onBroadcast = { signals += it }
        )

    // ── the funnel's signal ────────────────────────────────────────────

    @Test
    fun postCutover_broadcastThenBridgeFailure_signalsTheBroadcast_andStillThrows() = runTest {
        postCutover(SdkWriteResult.Broadcast(sdkTxid))
        bridgeReturns(BridgedTxResult.NotBridged("bytes never appeared"))
        val signals = mutableListOf<String>()

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
        val signals = mutableListOf<String>()

        sendCollecting(signals)

        assertEquals(listOf(sdkTxid), signals)
    }

    @Test
    fun postCutover_notBroadcast_neverSignals() = runTest {
        postCutover(SdkWriteResult.NotBroadcast(L1_FUNDING_GATE_CLOSED_REASON))
        val signals = mutableListOf<String>()

        try {
            sendCollecting(signals)
            fail("expected the not-synced failure")
        } catch (expected: SendEngineNotSyncedException) {
            // pre-broadcast refusal
        }

        assertTrue(signals.isEmpty())
    }

    @Test
    fun postCutover_ambiguous_neverSignals() = runTest {
        postCutover(SdkWriteResult.Ambiguous(IllegalStateException("timeout")))
        val signals = mutableListOf<String>()

        try {
            sendCollecting(signals)
            fail("expected the ambiguous failure")
        } catch (expected: IllegalStateException) {
            // outcome unknown: not a proven broadcast
        }

        assertTrue(signals.isEmpty())
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
        val signals = mutableListOf<String>()

        val tx = sendCollecting(signals)

        assertEquals(listOf(tx.txId.toString()), signals)
    }

    @Test
    fun preCutover_dashjSendFailure_neverSignals() = runTest {
        coEvery { sdkL1SendService.cutoverCommitted() } returns false
        every { wallet.sendCoinsOffline(any()) } throws InsufficientMoneyException(Coin.COIN)
        val signals = mutableListOf<String>()

        try {
            sendCollecting(signals)
            fail("expected InsufficientMoneyException")
        } catch (expected: InsufficientMoneyException) {
            // never committed
        }

        assertTrue(signals.isEmpty())
    }

    // ── end to end with the contact address provider ────────────────────

    private val marks = mutableListOf<Address>()

    private fun provider() = ContactPaymentAddressProvider(
        cutoverCommitted = { true },
        sdkAccountReader = { _, _ -> null },
        dashjNextAddress = { _, _ -> recipient },
        dashjMarkUsed = { _, _, address ->
            marks += address
            FriendChainAccess.MarkResult.MARKED
        },
        params = networkParams
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
            provider.sendThenMarkUsed(contactPayment) { onBroadcast ->
                runner.sendCoins(
                    sendRequest(),
                    checkBalanceConditions = false,
                    intendedRecipient = recipient,
                    onBroadcast = onBroadcast
                )
            }
            fail("the bridge failure still surfaces")
        } catch (expected: RuntimeException) {
            // the UI reports "sent, appears after the next sync"
        }

        assertEquals(listOf(recipient), marks)
    }

    @Test
    fun contactPayment_preBroadcastFailure_isNotMarked() = runTest {
        postCutover(SdkWriteResult.NotBroadcast(L1_FUNDING_GATE_CLOSED_REASON))
        val provider = provider()

        try {
            provider.sendThenMarkUsed(contactPayment) { onBroadcast ->
                runner.sendCoins(
                    sendRequest(),
                    checkBalanceConditions = false,
                    intendedRecipient = recipient,
                    onBroadcast = onBroadcast
                )
            }
            fail("expected the not-synced failure")
        } catch (expected: SendEngineNotSyncedException) {
            // nothing was broadcast
        }

        assertTrue(marks.isEmpty())
    }
}
