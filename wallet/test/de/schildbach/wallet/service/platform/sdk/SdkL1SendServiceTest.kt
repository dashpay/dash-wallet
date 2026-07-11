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

package de.schildbach.wallet.service.platform.sdk

import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.money.Dash
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Host-JVM tests for the Phase 5b L1 send service: the flag/preflight/
 * evidence-gate orchestration and the core-send no-double-broadcast
 * decision table. No native calls; the SDK send surface is faked via
 * [SdkL1SendSource].
 *
 * Invariant under test: the dashj fallback (a [SdkWriteResult.NotBroadcast]
 * return) is produced ONLY on outcomes where the SDK provably submitted
 * nothing; every unprovable failure is [SdkWriteResult.Ambiguous].
 */
class SdkL1SendServiceTest {

    private val walletId = "cd".repeat(32)
    private val validAddress = "yTsGq4wV8WF5GKLaYV2C43zrkr2sfTtysT"
    private val txid = "aa".repeat(32)
    private val amount = Dash(1_000_000)

    private class FakeSource(
        var boundWalletId: () -> String? = { null },
        var onSend: (String, String, Long) -> String = { _, _, _ -> "00".repeat(32) }
    ) : SdkL1SendSource {
        var boundCalls = 0
        var sendCalls = 0
        var lastWalletId: String? = null
        var lastAddress: String? = null
        var lastAmountDuffs: Long? = null

        override suspend fun boundWalletIdOrNull(): String? {
            boundCalls++
            return boundWalletId()
        }

        override suspend fun sendToAddress(
            walletIdHex: String,
            addressBase58: String,
            amountDuffs: Long
        ): String {
            sendCalls++
            lastWalletId = walletIdHex
            lastAddress = addressBase58
            lastAmountDuffs = amountDuffs
            return onSend(walletIdHex, addressBase58, amountDuffs)
        }
    }

    private fun config(enabled: Boolean?): DashPayConfig = mockk {
        if (enabled == null) {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_L1_SEND) } throws
                IllegalStateException("datastore unavailable")
        } else {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_L1_SEND) } returns enabled
        }
    }

    private val now = 1_000_000_000L

    /** A fresh, synced, fully matching parity report — the open-gate evidence. */
    private fun matchingParity(timestampMs: Long = now) = buildParityReport(
        sdkConfirmedDuffs = 5_000_000,
        sdkUnconfirmedDuffs = 0,
        dashjEstimatedDuffs = 5_000_000,
        dashjAvailableDuffs = 5_000_000,
        sdkTxCount = 10,
        dashjTxCount = 12,
        sdkSynced = true,
        timestampMs = timestampMs
    )

    private var selfSpendMarks = 0
    private val bridgedTxids = mutableListOf<String>()

    private fun service(
        source: FakeSource,
        enabled: Boolean? = true,
        parity: () -> ParityReport? = { matchingParity() },
        addressValid: (String) -> Boolean = { it == validAddress },
        bridgeAfterBroadcast: (String) -> Unit = { bridgedTxids += it }
    ) = SdkL1SendService(
        source = source,
        dashPayConfig = config(enabled),
        isValidAddress = addressValid,
        l1Parity = parity,
        onSelfSpendBroadcast = { selfSpendMarks++ },
        bridgeAfterBroadcast = bridgeAfterBroadcast,
        nowMs = { now }
    )

    /** A source in the fully-ready state: wallet bound, send succeeds. */
    private fun readySource() = FakeSource(
        boundWalletId = { walletId },
        onSend = { _, _, _ -> txid }
    )

    // ── classifyCoreSendFailure: the send-specific decision table ─────────

    @Test
    fun classify_buildAndFundingFailures_areNotBroadcast() {
        // Insufficient funds surfaces here: key-wallet's coin selection fails
        // inside build_signed, wrapped as "transaction build failed: …".
        val preBroadcast = listOf(
            DashSdkError.PlatformWallet.WalletOperation(
                "transaction build failed: no spendable inputs available on BIP44 account 0"
            ),
            DashSdkError.PlatformWallet.WalletOperation("set_funding failed: wallet not found")
        )
        for (error in preBroadcast) {
            val result = classifyCoreSendFailure(error)
            assertTrue(
                "${error.message} must be NotBroadcast",
                result is SdkWriteResult.NotBroadcast
            )
            assertSame(error, (result as SdkWriteResult.NotBroadcast).cause)
        }
    }

    @Test
    fun classify_otherWalletOperationMessages_stayAmbiguous() {
        // The bare type is NOT enough — only the two provably pre-broadcast
        // FFI message prefixes may downgrade a WalletOperation.
        val result = classifyCoreSendFailure(
            DashSdkError.PlatformWallet.WalletOperation("something unexpected happened")
        )
        assertTrue(result is SdkWriteResult.Ambiguous)
    }

    @Test
    fun classify_builderValidation_isNotBroadcast() {
        // ErrorInvalidParameter (platform-wallet code 2) has no dedicated
        // Kotlin type; within the send flow it is only raised by the
        // pre-funding builder steps (addOutput network mismatch etc.).
        val result = classifyCoreSendFailure(
            DashSdkError.PlatformWallet.Generic(
                PWFFI_ERROR_INVALID_PARAMETER,
                "output address network mismatch: address is for a different network"
            )
        )
        assertTrue(result is SdkWriteResult.NotBroadcast)
    }

    @Test
    fun classify_definitiveBroadcastRejection_isNotBroadcast() {
        // BroadcastError::Rejected — by the broadcaster contract the bytes
        // provably never reached any peer, and the UTXO reservation was
        // released. Crosses the FFI as ErrorUnknown (99) with the typed
        // Display prefix.
        val result = classifyCoreSendFailure(
            DashSdkError.PlatformWallet.Generic(
                PWFFI_ERROR_UNKNOWN,
                "Transaction broadcast failed: no connected peers"
            )
        )
        assertTrue(result is SdkWriteResult.NotBroadcast)
    }

    @Test
    fun classify_unknownCodeWithoutRejectionPrefix_staysAmbiguous() {
        // Code 99 is also the generic fallthrough — without the rejection
        // prefix nothing is proven.
        val result = classifyCoreSendFailure(
            DashSdkError.PlatformWallet.Generic(PWFFI_ERROR_UNKNOWN, "some internal error")
        )
        assertTrue(result is SdkWriteResult.Ambiguous)
    }

    @Test
    fun classify_broadcastUnconfirmed_isAmbiguous() {
        // The SDK's own "may already be on the network, inputs stay
        // reserved, do NOT retry" — the exact case a dashj fallback would
        // turn into a potential double pay.
        val result = classifyCoreSendFailure(
            DashSdkError.PlatformWallet.TransactionBroadcastUnconfirmed("timeout after send")
        )
        assertTrue(result is SdkWriteResult.Ambiguous)
    }

    @Test
    fun classify_defersToTheSharedTable() {
        // Shared pre-broadcast rules still apply…
        assertTrue(
            classifyCoreSendFailure(DashSdkError.InvalidParameter("bad")) is SdkWriteResult.NotBroadcast
        )
        assertTrue(
            classifyCoreSendFailure(RuntimeException("boom: no private key stored"))
                is SdkWriteResult.NotBroadcast
        )
        // …and so do the shared ambiguous rules.
        assertTrue(classifyCoreSendFailure(DashSdkError.NetworkError("conn reset")) is SdkWriteResult.Ambiguous)
        assertTrue(classifyCoreSendFailure(DashSdkError.Timeout("deadline")) is SdkWriteResult.Ambiguous)
        assertTrue(classifyCoreSendFailure(RuntimeException("boom")) is SdkWriteResult.Ambiguous)
    }

    // ── Preflight decision table ──────────────────────────────────────────

    @Test
    fun flagOff_isNotBroadcast_andTouchesNothing() = runBlocking {
        val source = readySource()
        val result = service(source, enabled = false)
            .sendToAddress(validAddress, amount, emptyWallet = false)
        assertEquals(SdkWriteResult.NotBroadcast("flag off"), result)
        // The inertness contract: no SDK call of any kind while OFF.
        assertEquals(0, source.boundCalls)
        assertEquals(0, source.sendCalls)
        assertEquals(0, selfSpendMarks)
    }

    @Test
    fun flagReadFailure_isNotBroadcast_andTouchesNothing() = runBlocking {
        val source = readySource()
        val result = service(source, enabled = null)
            .sendToAddress(validAddress, amount, emptyWallet = false)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.boundCalls + source.sendCalls)
    }

    @Test
    fun emptyWallet_isNotBroadcast_beforeAnySdkCall() = runBlocking {
        // The SDK send surface exposes no send-all; dashj keeps handling it.
        val source = readySource()
        val result = service(source).sendToAddress(validAddress, amount, emptyWallet = true)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.boundCalls + source.sendCalls)
    }

    @Test
    fun nonPositiveAmount_isNotBroadcast() = runBlocking {
        val source = readySource()
        for (bad in listOf(Dash.ZERO, Dash(-1))) {
            val result = service(source).sendToAddress(validAddress, bad, emptyWallet = false)
            assertTrue(result is SdkWriteResult.NotBroadcast)
        }
        assertEquals(0, source.sendCalls)
    }

    @Test
    fun malformedOrWrongNetworkAddress_isNotBroadcast() = runBlocking {
        val source = readySource()
        val svc = service(source)
        for (bad in listOf("", "   ", "not-an-address", "XdgeCkC9YbSxAmJ9dtxKcnnJVzUvarE3wq")) {
            val result = svc.sendToAddress(bad, amount, emptyWallet = false)
            assertTrue("'$bad' must be rejected app-side", result is SdkWriteResult.NotBroadcast)
        }
        assertEquals(0, source.sendCalls)
    }

    @Test
    fun addressValidatorThrow_isContained_andNotBroadcast() = runBlocking {
        val source = readySource()
        val svc = service(source, addressValid = { throw IllegalStateException("no params") })
        val result = svc.sendToAddress(validAddress, amount, emptyWallet = false)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.sendCalls)
    }

    @Test
    fun walletNotBound_isNotBroadcast() = runBlocking {
        val source = FakeSource(boundWalletId = { null })
        val result = service(source).sendToAddress(validAddress, amount, emptyWallet = false)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.sendCalls)
    }

    @Test
    fun bindLookupFailure_isNotBroadcast() = runBlocking {
        val source = FakeSource(boundWalletId = { throw IllegalStateException("bootstrap failed") })
        val result = service(source).sendToAddress(validAddress, amount, emptyWallet = false)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.sendCalls)
    }

    // ── The evidence gate (reuses evaluateWalletFundingGate) ─────────────

    @Test
    fun gateClosed_noParityMeasurement_isNotBroadcast() = runBlocking {
        val source = readySource()
        val result = service(source, parity = { null })
            .sendToAddress(validAddress, amount, emptyWallet = false)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertTrue((result as SdkWriteResult.NotBroadcast).reason.contains("gate closed"))
        assertEquals(0, source.sendCalls)
    }

    @Test
    fun gateClosed_staleParity_isNotBroadcast() = runBlocking {
        val source = readySource()
        val stale = matchingParity(
            timestampMs = now - ShieldedBalanceServiceImpl.PARITY_MAX_AGE_MS - 1
        )
        val result = service(source, parity = { stale })
            .sendToAddress(validAddress, amount, emptyWallet = false)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.sendCalls)
    }

    @Test
    fun gateClosed_notSynced_isNotBroadcast() = runBlocking {
        val source = readySource()
        val presync = matchingParity().copy(sdkSynced = false)
        val result = service(source, parity = { presync })
            .sendToAddress(validAddress, amount, emptyWallet = false)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.sendCalls)
    }

    @Test
    fun gateClosed_balanceMismatch_onEitherVariant_isNotBroadcast() = runBlocking {
        val source = readySource()
        val estimatedMismatch = matchingParity().copy(balancesMatch = false)
        val confirmedMismatch = matchingParity().copy(confirmedBalancesMatch = false)
        for (report in listOf(estimatedMismatch, confirmedMismatch)) {
            val result = service(source, parity = { report })
                .sendToAddress(validAddress, amount, emptyWallet = false)
            assertTrue(result is SdkWriteResult.NotBroadcast)
        }
        assertEquals(0, source.sendCalls)
    }

    @Test
    fun gateOpen_txCountDelta_doesNotBlock() = runBlocking {
        // Same rule as shieldFromWallet: tx-count parity is a diagnostic,
        // not a funds gate (matchingParity() has sdkTx=10 vs dashjTx=12).
        val source = readySource()
        assertFalse(matchingParity().txCountsMatch)
        val result = service(source).sendToAddress(validAddress, amount, emptyWallet = false)
        assertTrue(result is SdkWriteResult.Broadcast)
    }

    @Test
    fun parityReadFailure_keepsGateClosed() = runBlocking {
        val source = readySource()
        val result = service(source, parity = { throw IllegalStateException("shadow broke") })
            .sendToAddress(validAddress, amount, emptyWallet = false)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.sendCalls)
    }

    // ── The send itself ───────────────────────────────────────────────────

    @Test
    fun successfulSend_returnsTxid_andRecordsSelfSpend() = runBlocking {
        val source = readySource()
        val result = service(source).sendToAddress(validAddress, amount, emptyWallet = false)
        assertEquals(SdkWriteResult.Broadcast(txid), result)
        assertEquals(1, source.sendCalls)
        assertEquals(walletId, source.lastWalletId)
        assertEquals(validAddress, source.lastAddress)
        assertEquals(amount.duffs, source.lastAmountDuffs)
        assertEquals(1, selfSpendMarks)
    }

    @Test
    fun addressIsTrimmed_beforeValidationAndSend() = runBlocking {
        val source = readySource()
        val result = service(source).sendToAddress("  $validAddress  ", amount, emptyWallet = false)
        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals(validAddress, source.lastAddress)
    }

    @Test
    fun failedSend_isClassified_andNeverRetried_andNoSelfSpendMark() = runBlocking {
        val source = FakeSource(
            boundWalletId = { walletId },
            onSend = { _, _, _ -> throw DashSdkError.NetworkError("connection reset mid-send") }
        )
        val result = service(source).sendToAddress(validAddress, amount, emptyWallet = false)
        assertTrue(result is SdkWriteResult.Ambiguous)
        // ONE broadcast attempt, no internal retry, marker untouched.
        assertEquals(1, source.sendCalls)
        assertEquals(0, selfSpendMarks)
    }

    @Test
    fun preBroadcastSdkRejection_mapsToNotBroadcast() = runBlocking {
        val source = FakeSource(
            boundWalletId = { walletId },
            onSend = { _, _, _ ->
                throw DashSdkError.PlatformWallet.WalletOperation(
                    "transaction build failed: no spendable inputs"
                )
            }
        )
        val result = service(source).sendToAddress(validAddress, amount, emptyWallet = false)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(1, source.sendCalls)
        assertEquals(0, selfSpendMarks)
    }

    // ── The 5c.2 bridge hook (fire-and-forget consumer) ──────────────────

    @Test
    fun successfulSend_invokesTheBridgeHook_withTheBroadcastTxid() = runBlocking {
        val result = service(readySource()).sendToAddress(validAddress, amount, emptyWallet = false)
        assertEquals(SdkWriteResult.Broadcast(txid), result)
        assertEquals(listOf(txid), bridgedTxids)
    }

    @Test
    fun bridgeHookFailure_doesNotAffectTheBroadcastResult() = runBlocking {
        // The bridge launch is fire-and-forget: a throw from the hook must
        // never demote an already-decided Broadcast.
        val result = service(
            readySource(),
            bridgeAfterBroadcast = { throw IllegalStateException("factory unavailable") }
        ).sendToAddress(validAddress, amount, emptyWallet = false)
        assertEquals(SdkWriteResult.Broadcast(txid), result)
    }

    @Test
    fun failedOrSkippedSends_neverInvokeTheBridgeHook() = runBlocking {
        // Flag off (NotBroadcast, nothing attempted).
        service(readySource(), enabled = false).sendToAddress(validAddress, amount, emptyWallet = false)
        // Ambiguous broadcast failure.
        val failing = FakeSource(
            boundWalletId = { walletId },
            onSend = { _, _, _ -> throw DashSdkError.NetworkError("connection reset") }
        )
        service(failing).sendToAddress(validAddress, amount, emptyWallet = false)
        // Provably pre-broadcast rejection.
        val rejected = FakeSource(
            boundWalletId = { walletId },
            onSend = { _, _, _ ->
                throw DashSdkError.PlatformWallet.WalletOperation("transaction build failed: no inputs")
            }
        )
        service(rejected).sendToAddress(validAddress, amount, emptyWallet = false)

        assertTrue("only a real Broadcast may bridge", bridgedTxids.isEmpty())
    }

    @Test
    fun selfSpendMarkerFailure_doesNotAffectTheResult() = runBlocking {
        val source = readySource()
        val svc = SdkL1SendService(
            source = source,
            dashPayConfig = config(true),
            isValidAddress = { true },
            l1Parity = { matchingParity() },
            onSelfSpendBroadcast = { throw IllegalStateException("shadow not running") },
            nowMs = { now }
        )
        val result = svc.sendToAddress(validAddress, amount, emptyWallet = false)
        assertEquals(SdkWriteResult.Broadcast(txid), result)
    }

    // ── beforeBroadcast (the call site's dashj-equivalent conditions) ────

    @Test
    fun beforeBroadcast_runsOnlyAfterAllPreflightsPass() = runBlocking {
        var invoked = 0
        // Gate closed → the hook must NOT run.
        val closed = service(readySource(), parity = { null })
        closed.sendToAddress(validAddress, amount, emptyWallet = false) { invoked++ }
        assertEquals(0, invoked)
        // Flag off → the hook must NOT run.
        val off = service(readySource(), enabled = false)
        off.sendToAddress(validAddress, amount, emptyWallet = false) { invoked++ }
        assertEquals(0, invoked)
        // Everything green → it runs exactly once, before the broadcast.
        val source = readySource()
        val ok = service(source)
        ok.sendToAddress(validAddress, amount, emptyWallet = false) {
            invoked++
            assertEquals("must run BEFORE the broadcast attempt", 0, source.sendCalls)
        }
        assertEquals(1, invoked)
        assertEquals(1, source.sendCalls)
    }

    @Test
    fun beforeBroadcastThrow_propagatesUnclassified_andNothingIsBroadcast() = runBlocking {
        val source = readySource()
        val boom = IllegalStateException("leftover balance")
        try {
            service(source).sendToAddress(validAddress, amount, emptyWallet = false) { throw boom }
            fail("expected the beforeBroadcast throw to propagate")
        } catch (e: IllegalStateException) {
            assertSame(boom, e)
        }
        assertEquals(0, source.sendCalls)
        assertEquals(0, selfSpendMarks)
    }

    // ── NotBroadcast reasons carry no txid ───────────────────────────────

    @Test
    fun notBroadcastResults_haveNoValueToPropagate() = runBlocking {
        val result = service(readySource(), enabled = false)
            .sendToAddress(validAddress, amount, emptyWallet = false)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertNull((result as SdkWriteResult.NotBroadcast).cause)
    }
}
