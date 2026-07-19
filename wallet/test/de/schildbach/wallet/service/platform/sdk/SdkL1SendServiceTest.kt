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
import io.mockk.every
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
        var onSend: (String, String, Long) -> String = { _, _, _ -> "00".repeat(32) },
        var onSpendable: () -> Long = { throw IllegalStateException("spendable not stubbed") },
        var onSendAll: (String, String, Long) -> String = { _, _, _ ->
            throw IllegalStateException("send-all not stubbed")
        }
    ) : SdkL1SendSource {
        var boundCalls = 0
        var sendCalls = 0
        var spendableCalls = 0
        var sendAllCalls = 0
        var lockAcquisitions = 0
        var insideLock = false
        var sendAllCallsInsideLock = 0
        var lastWalletId: String? = null
        var lastAddress: String? = null
        var lastAmountDuffs: Long? = null
        val sendAllFloors = mutableListOf<Long>()

        override suspend fun boundWalletIdOrNull(): String? {
            boundCalls++
            return boundWalletId()
        }

        override suspend fun <T> withCoreSendLock(walletIdHex: String, block: suspend () -> T): T {
            check(!insideLock) { "core-send lock is NOT reentrant — nested acquisition would deadlock" }
            lockAcquisitions++
            insideLock = true
            try {
                return block()
            } finally {
                insideLock = false
            }
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

        override suspend fun spendableBalanceDuffs(walletIdHex: String): Long {
            spendableCalls++
            return onSpendable()
        }

        override suspend fun sendAllToAddress(
            walletIdHex: String,
            addressBase58: String,
            floorDuffs: Long
        ): String {
            sendAllCalls++
            if (insideLock) sendAllCallsInsideLock++
            lastWalletId = walletIdHex
            lastAddress = addressBase58
            sendAllFloors += floorDuffs
            return onSendAll(walletIdHex, addressBase58, floorDuffs)
        }
    }

    private fun config(enabled: Boolean?, cutoverState: String? = null): DashPayConfig = mockk {
        if (enabled == null) {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_L1_SEND) } throws
                IllegalStateException("datastore unavailable")
            coEvery { get(DashPayConfig.CUTOVER_STATE) } throws
                IllegalStateException("datastore unavailable")
        } else {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_L1_SEND) } returns enabled
            coEvery { get(DashPayConfig.CUTOVER_STATE) } returns cutoverState
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
        cutoverState: String? = null,
        parity: () -> ParityReport? = { matchingParity() },
        addressValid: (String) -> Boolean = { it == validAddress },
        bridgeAfterBroadcast: (String) -> Unit = { bridgedTxids += it },
        // Tests default to NO app-locked outputs so the drain paths are
        // exercisable; the production wiring (and the constructor default)
        // is fail-closed — covered by dedicated tests below.
        hasAppLockedOutputs: () -> Boolean = { false },
        // Fresh empty registry by default: no seam locks, drain paths
        // exercisable. Seam-lock refusal is covered by dedicated tests.
        seamRegistry: SeamOutputLockRegistry = SeamOutputLockRegistry()
    ) = SdkL1SendService(
        source = source,
        dashPayConfig = config(enabled, cutoverState),
        isValidAddress = addressValid,
        l1Parity = parity,
        hasAppLockedSpendableOutputs = hasAppLockedOutputs,
        seamOutputLockRegistry = seamRegistry,
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
    fun emptyWallet_preCutover_isNotBroadcast_beforeAnySdkCall() = runBlocking {
        // Pre-cutover (soak flag on, no committed cutover) dashj keeps
        // owning send-all — the drain is a post-cutover route only.
        val source = readySource()
        val result = service(source).sendToAddress(validAddress, amount, emptyWallet = true)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.boundCalls + source.sendCalls + source.sendAllCalls + source.spendableCalls)
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
    fun probeSendGate_mirrorsTheSendGate_andTouchesNoSdkSurface() {
        // The debug settings status line reads THIS accessor — it must be
        // the send predicate itself, and read-only.
        val source = readySource()
        assertTrue(service(source).probeSendGate().allowed)
        assertFalse(service(source, parity = { null }).probeSendGate().allowed)
        assertFalse(
            service(source, parity = { matchingParity().copy(sdkSynced = false) })
                .probeSendGate().allowed
        )
        assertFalse(
            service(
                source,
                parity = { matchingParity(timestampMs = now - ShieldedBalanceServiceImpl.PARITY_MAX_AGE_MS - 1) }
            ).probeSendGate().allowed
        )
        // Contained like a real send's gate read: a parity throw = closed.
        assertFalse(
            service(source, parity = { throw IllegalStateException("shadow broke") })
                .probeSendGate().allowed
        )
        assertEquals(0, source.boundCalls + source.sendCalls)
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

    // ── Phase 5d: cutover-committed gate ─────────────────────────────────

    @Test
    fun cutoverCommitted_followsThePersistedState_andFailsSafeToFalse() = runBlocking {
        // Unset / pre-flip states → not committed (today's behavior).
        assertFalse(service(readySource(), enabled = false).cutoverCommitted())
        assertFalse(service(readySource(), enabled = false, cutoverState = "DUAL_RUNNING").cutoverCommitted())
        assertFalse(service(readySource(), enabled = false, cutoverState = "READY_OBSERVED").cutoverCommitted())
        // Flipped states → committed.
        assertTrue(service(readySource(), enabled = false, cutoverState = "CUT_OVER").cutoverCommitted())
        assertTrue(service(readySource(), enabled = false, cutoverState = "SETTLED").cutoverCommitted())
        // Garbage parses as DUAL_RUNNING; a config read failure is contained.
        assertFalse(service(readySource(), enabled = false, cutoverState = "garbage").cutoverCommitted())
        assertFalse(service(readySource(), enabled = null).cutoverCommitted())
    }

    @Test
    fun committedCutover_enablesTheSendPath_withTheSoakFlagOff() = runBlocking {
        val source = readySource()
        val result = service(source, enabled = false, cutoverState = "CUT_OVER")
            .sendToAddress(validAddress, amount, emptyWallet = false)
        // With the soak flag OFF, pre-cutover this send would be
        // NotBroadcast("flag off") — the committed cutover alone must open
        // the path all the way to a broadcast.
        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals(1, source.sendCalls)
    }

    // ── Step B: send-all (drain) — the pure pieces ───────────────────────

    @Test
    fun sendAllFloor_isSpendableMinusReserve_clampedToOne() {
        assertEquals(4_990_000L, sendAllFloorDuffs(5_000_000L))
        assertEquals(1L, sendAllFloorDuffs(SEND_ALL_FEE_RESERVE_DUFFS))
        assertEquals(1L, sendAllFloorDuffs(SEND_ALL_FEE_RESERVE_DUFFS - 1))
        assertEquals(1L, sendAllFloorDuffs(0L))
        // The JNI boundary rejects a non-positive output amount — the clamp
        // keeps even a dust-level wallet's attempt boundary-legal (the
        // engine then decides via its own dust/fee checks).
        assertEquals(1L, sendAllFloorDuffs(-5L))
        // Custom reserve.
        assertEquals(900L, sendAllFloorDuffs(1_000L, reserveDuffs = 100L))
    }

    @Test
    fun sendAllShortfall_matchesOnlyTheEngineInsufficientAtFeeBuildFailure() {
        // The retryable signature: WalletOperation + FFI build prefix +
        // key-wallet's InsufficientFunds Display text.
        assertTrue(
            isSendAllShortfall(
                DashSdkError.PlatformWallet.WalletOperation(
                    "transaction build failed: Insufficient funds: available 5000000, required 5001480"
                )
            )
        )
        assertTrue(
            isSendAllShortfall(
                DashSdkError.PlatformWallet.WalletOperation(
                    "transaction build failed: Coin selection error: Insufficient funds: available 1, required 2"
                )
            )
        )
        // No-UTXOs is NOT a shortfall (retrying with a lower floor can't help).
        assertFalse(
            isSendAllShortfall(
                DashSdkError.PlatformWallet.WalletOperation(
                    "transaction build failed: Coin selection error: No UTXOs available for selection"
                )
            )
        )
        // Funding failures, other types, broadcast failures: never retryable.
        assertFalse(
            isSendAllShortfall(DashSdkError.PlatformWallet.WalletOperation("set_funding failed: wallet not found"))
        )
        assertFalse(isSendAllShortfall(DashSdkError.NetworkError("Insufficient funds")))
        assertFalse(isSendAllShortfall(RuntimeException("transaction build failed: Insufficient funds")))
        // Every retryable shortfall MUST classify NotBroadcast (the no-
        // double-broadcast proof the single retry rests on).
        val shortfall = DashSdkError.PlatformWallet.WalletOperation(
            "transaction build failed: Insufficient funds: available 1, required 2"
        )
        assertTrue(classifyCoreSendFailure(shortfall) is SdkWriteResult.NotBroadcast)
    }

    // ── Step B: send-all (drain) — post-cutover orchestration ────────────

    /** A drain-ready source: bound, spendable stubbed, drain succeeds. */
    private fun drainReadySource(spendable: Long = 5_000_000L) = FakeSource(
        boundWalletId = { walletId },
        onSpendable = { spendable },
        onSendAll = { _, _, _ -> txid }
    )

    @Test
    fun sendAll_postCutover_drainsViaTheSdk_withTheFloor() = runBlocking {
        val source = drainReadySource(spendable = 5_000_000L)
        val result = service(source, enabled = false, cutoverState = "CUT_OVER")
            .sendToAddress(validAddress, amount, emptyWallet = true)
        assertEquals(SdkWriteResult.Broadcast(txid), result)
        // The drain ran INSTEAD of the plain send, floored at
        // spendable − reserve (the iOS-validated max pattern).
        assertEquals(0, source.sendCalls)
        assertEquals(1, source.sendAllCalls)
        assertEquals(listOf(5_000_000L - SEND_ALL_FEE_RESERVE_DUFFS), source.sendAllFloors)
        assertEquals(walletId, source.lastWalletId)
        assertEquals(validAddress, source.lastAddress)
        // The attempt ran under the core-send lock.
        assertEquals(1, source.lockAcquisitions)
        assertEquals(1, source.sendAllCallsInsideLock)
        // Post-broadcast hooks fire exactly like a plain send.
        assertEquals(1, selfSpendMarks)
        assertEquals(listOf(txid), bridgedTxids)
    }

    // ── Step B fix round: the app-locked-output drain guard ──────────────

    @Test
    fun sendAll_withAppLockedOutputs_isBlocked_beforeAnyBuildOrBalanceRead() = runBlocking {
        // FUNDS-CRITICAL: the SDK drain selects EVERY spendable UTXO and the
        // FFI has no exclusion API, so app-locked outputs (CrowdNode's
        // account-address locks, still tracked by the held dashj wallet)
        // WOULD be spent. The guard must refuse pre-build: no drain attempt,
        // no balance read, nothing broadcast.
        val source = drainReadySource()
        val result = service(
            source,
            enabled = false,
            cutoverState = "CUT_OVER",
            hasAppLockedOutputs = { true }
        ).sendToAddress(validAddress, amount, emptyWallet = true)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertTrue(
            "reason must name the lock guard",
            (result as SdkWriteResult.NotBroadcast).reason.contains("app-locked outputs")
        )
        assertEquals(0, source.sendAllCalls)
        assertEquals(0, source.spendableCalls)
        assertEquals(0, source.lockAcquisitions)
        assertEquals(0, selfSpendMarks)
        assertTrue(bridgedTxids.isEmpty())
    }

    @Test
    fun sendAll_lockedOutputCheckFailure_blocksTheDrain_failClosed() = runBlocking {
        // A check that cannot PROVE the absence of locks blocks the drain.
        val source = drainReadySource()
        val result = service(
            source,
            enabled = false,
            cutoverState = "CUT_OVER",
            hasAppLockedOutputs = { throw IllegalStateException("wallet unavailable") }
        ).sendToAddress(validAddress, amount, emptyWallet = true)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.sendAllCalls + source.spendableCalls)
    }

    @Test
    fun sendAll_constructorDefault_isFailClosed_untilTheCheckIsWired() = runBlocking {
        // A construction that does NOT provide the locked-output check must
        // assume locks exist — the drain stays blocked (fail closed), while
        // the plain send path is unaffected.
        val source = drainReadySource()
        val svc = SdkL1SendService(
            source = source,
            dashPayConfig = config(false, "CUT_OVER"),
            isValidAddress = { it == validAddress },
            l1Parity = { matchingParity() },
            nowMs = { now }
        )
        val drained = svc.sendToAddress(validAddress, amount, emptyWallet = true)
        assertTrue(drained is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.sendAllCalls)
        val plain = svc.sendToAddress(validAddress, amount, emptyWallet = false)
        assertTrue(plain is SdkWriteResult.Broadcast)
    }

    @Test
    fun sendAll_withSeamRegisteredLocks_isBlocked_evenWhenDashjSeesNone() = runBlocking {
        // B7 union (FUNDS-CRITICAL): post-cutover CrowdNode's API-response
        // txs exist only in the SDK store, so their account-address locks
        // live in the SeamOutputLockRegistry — the dashj wallet check
        // reports NO locks for them. The drain guard must OR the registry
        // in: seam locks alone refuse the drain, pre-build, pre-balance.
        val source = drainReadySource()
        val registry = SeamOutputLockRegistry()
        registry.lockOutput("ab".repeat(32), 0)
        val result = service(
            source,
            enabled = false,
            cutoverState = "CUT_OVER",
            hasAppLockedOutputs = { false },
            seamRegistry = registry
        ).sendToAddress(validAddress, amount, emptyWallet = true)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertTrue(
            "reason must name the lock guard",
            (result as SdkWriteResult.NotBroadcast).reason.contains("app-locked outputs")
        )
        assertEquals(0, source.sendAllCalls)
        assertEquals(0, source.spendableCalls)
        assertEquals(0, source.lockAcquisitions)
        assertEquals(0, selfSpendMarks)
        assertTrue(bridgedTxids.isEmpty())
    }

    @Test
    fun sendAll_seamRegistryReadFailure_blocksTheDrain_failClosed() = runBlocking {
        // A registry that cannot PROVE the absence of seam locks blocks the
        // drain, mirroring the dashj-side check's fail-closed contract.
        val source = drainReadySource()
        val registry = mockk<SeamOutputLockRegistry> {
            every { hasAnyLocks() } throws IllegalStateException("registry unavailable")
        }
        val result = service(
            source,
            enabled = false,
            cutoverState = "CUT_OVER",
            hasAppLockedOutputs = { false },
            seamRegistry = registry
        ).sendToAddress(validAddress, amount, emptyWallet = true)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.sendAllCalls + source.spendableCalls)
    }

    @Test
    fun plainSend_neverConsultsTheLockedOutputGuard() = runBlocking {
        var lockedChecks = 0
        val source = readySource()
        val result = service(
            source,
            hasAppLockedOutputs = { lockedChecks++; true }
        ).sendToAddress(validAddress, amount, emptyWallet = false)
        // A plain send is untouched by the drain guard — the SDK's own coin
        // selection handles it (the residual locked-output exposure of PLAIN
        // sends is pre-existing and tracked separately).
        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals(0, lockedChecks)
    }

    @Test
    fun sendAll_shortfall_adjustsDownOnce_engineAuthoritative() = runBlocking {
        val source = drainReadySource(spendable = 5_000_000L)
        source.onSendAll = { _, _, floor ->
            if (floor > 1L) {
                throw DashSdkError.PlatformWallet.WalletOperation(
                    "transaction build failed: Insufficient funds: available 5000000, required 5001480"
                )
            }
            txid
        }
        val result = service(source, enabled = false, cutoverState = "CUT_OVER")
            .sendToAddress(validAddress, amount, emptyWallet = true)
        assertEquals(SdkWriteResult.Broadcast(txid), result)
        // Exactly two attempts: the floor, then the engine-authoritative
        // retry (floor 1 → deliverable = total − fee).
        assertEquals(listOf(5_000_000L - SEND_ALL_FEE_RESERVE_DUFFS, 1L), source.sendAllFloors)
        assertEquals(1, selfSpendMarks)
        // BOTH attempts ran under ONE core-send-lock acquisition, so a
        // concurrent plain send cannot interleave between them and change
        // the drained balance.
        assertEquals(1, source.lockAcquisitions)
        assertEquals(2, source.sendAllCallsInsideLock)
    }

    @Test
    fun sendAll_shortfallTwice_isNotBroadcast_neverAThirdAttempt() = runBlocking {
        val source = drainReadySource()
        source.onSendAll = { _, _, _ ->
            throw DashSdkError.PlatformWallet.WalletOperation(
                "transaction build failed: Insufficient funds: available 1000, required 2000"
            )
        }
        val result = service(source, enabled = false, cutoverState = "CUT_OVER")
            .sendToAddress(validAddress, amount, emptyWallet = true)
        // A genuine can't-pay-the-fee wallet: classified pre-broadcast, ONE
        // adjust-down retry, never a third attempt, no hooks — and still a
        // single lock acquisition around both attempts.
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(2, source.sendAllCalls)
        assertEquals(1, source.lockAcquisitions)
        assertEquals(0, selfSpendMarks)
        assertTrue(bridgedTxids.isEmpty())
    }

    @Test
    fun sendAll_nonShortfallFailure_isClassified_withoutRetry() = runBlocking {
        val source = drainReadySource()
        source.onSendAll = { _, _, _ -> throw DashSdkError.NetworkError("connection reset mid-send") }
        val result = service(source, enabled = false, cutoverState = "CUT_OVER")
            .sendToAddress(validAddress, amount, emptyWallet = true)
        // An unprovable failure stays Ambiguous and is NEVER retried — a
        // drain retry after a maybe-sent drain is a potential double pay.
        assertTrue(result is SdkWriteResult.Ambiguous)
        assertEquals(1, source.sendAllCalls)
        assertEquals(0, selfSpendMarks)
    }

    @Test
    fun sendAll_spendableReadFailure_isNotBroadcast_beforeAnyAttempt() = runBlocking {
        val source = FakeSource(
            boundWalletId = { walletId },
            onSpendable = { throw IllegalStateException("balance read failed") }
        )
        val result = service(source, enabled = false, cutoverState = "CUT_OVER")
            .sendToAddress(validAddress, amount, emptyWallet = true)
        // Preflight by construction: NotBroadcast, no drain attempt.
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.sendAllCalls)
    }

    @Test
    fun sendAll_gateClosed_isNotBroadcast_beforeAnyBalanceReadOrAttempt() = runBlocking {
        val source = drainReadySource()
        val result = service(source, enabled = false, cutoverState = "CUT_OVER", parity = { null })
            .sendToAddress(validAddress, amount, emptyWallet = true)
        // The same funding-evidence gate guards the drain: the SDK spends
        // from its own SPV view, send-all most of all.
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertTrue((result as SdkWriteResult.NotBroadcast).reason.contains("gate closed"))
        assertEquals(0, source.spendableCalls + source.sendAllCalls)
    }

    @Test
    fun sendAll_beforeBroadcastThrow_propagates_andNothingIsDrained() = runBlocking {
        val source = drainReadySource()
        val boom = IllegalStateException("leftover balance")
        try {
            service(source, enabled = false, cutoverState = "CUT_OVER")
                .sendToAddress(validAddress, amount, emptyWallet = true) { throw boom }
            fail("expected the beforeBroadcast throw to propagate")
        } catch (e: IllegalStateException) {
            assertSame(boom, e)
        }
        assertEquals(0, source.sendAllCalls)
        assertEquals(0, selfSpendMarks)
    }

    @Test
    fun plainSend_neverTouchesTheDrainSurface() = runBlocking {
        val source = readySource()
        val result = service(source).sendToAddress(validAddress, amount, emptyWallet = false)
        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals(0, source.spendableCalls + source.sendAllCalls)
        // …including the drain's explicit lock wrapper: a plain send takes
        // the mutex inside the SDK's own sendToAddresses, never via
        // withCoreSendLock — so the two can never nest (no deadlock).
        assertEquals(0, source.lockAcquisitions)
    }

    @Test
    fun pinnedSdk_exposesTheDrainBindings_theJavaShimAndMutexRelyOn() {
        // AAR-bump canary (pin-don't-track): the drain wrapper reaches the
        // SDK's internal builder through JVM-level surface of the PINNED
        // dash-sdk-android binary. If a future AAR renames the module
        // (mangling suffix) or the synthetic mutex accessor, the Java shim
        // fails to COMPILE — and THIS test fails for the reflection-only
        // piece (DashSdkL1SendSource.coreSendMutexOf), keeping the break
        // loud at build/test time instead of first-send time.
        val accessor = org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet::class.java
            .getMethod(
                "access\$getCoreSendMutex\$p",
                org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet::class.java
            )
        assertTrue(java.lang.reflect.Modifier.isStatic(accessor.modifiers))
        assertEquals(kotlinx.coroutines.sync.Mutex::class.java, accessor.returnType)
        // The strategy knob's Kotlin-side FFI value must stay 5 = All
        // (CoreSelectionStrategyFFI::All in rs-platform-wallet-ffi).
        assertEquals(
            5,
            org.dashfoundation.dashsdk.wallet.CoreTransactionBuilder.SelectionStrategy.ALL.ffiValue
        )
    }
}
