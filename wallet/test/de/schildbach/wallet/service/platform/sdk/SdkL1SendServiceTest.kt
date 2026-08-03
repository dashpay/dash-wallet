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
        },
        var onSweepReceival: () -> Long = { 0L }
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
        var sweepReceivalCalls = 0
        var spendableCallsAtSweep = -1
        var sweepInsideLock = false

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

        override suspend fun sweepReceivalAccountsForSendAll(walletIdHex: String): Long {
            sweepReceivalCalls++
            spendableCallsAtSweep = spendableCalls
            sweepInsideLock = insideLock
            return onSweepReceival()
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

    /**
     * The open-gate evidence: the SDK filter scan has caught up to the
     * chain tip. Phase is deliberately NOT SYNCED — the device's
     * stuck-but-fundable state (a live shadow chasing the tip never latches
     * SYNCED), proving the gate opens on caught-up alone, without
     * phase==SYNCED and without any dashj parity.
     */
    private fun caughtUpProgress() = ShadowSyncProgress(
        phase = ShadowSyncPhase.FILTERS,
        overallPercent = 1.0, // the SDK under-reports the percent; the gate ignores it
        headerHeight = 1_500_000,
        headerTarget = 1_500_000,
        filterHeight = 1_500_000,
        filterTarget = 1_500_000
    )

    private var selfSpendMarks = 0
    private val bridgedTxids = mutableListOf<String>()

    private fun service(
        source: FakeSource,
        enabled: Boolean? = true,
        cutoverState: String? = null,
        progress: () -> ShadowSyncProgress = { caughtUpProgress() },
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
        l1Progress = progress,
        hasAppLockedSpendableOutputs = hasAppLockedOutputs,
        seamOutputLockRegistry = seamRegistry,
        onSelfSpendBroadcast = { selfSpendMarks++ },
        bridgeAfterBroadcast = bridgeAfterBroadcast
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
    fun gateClosed_engineNotRunning_isNotBroadcast() = runBlocking {
        // IDLE progress = the engine is not running (the feed resets to
        // IDLE on stop) — the gate must fail closed with no send attempt.
        val source = readySource()
        val result = service(source, progress = { ShadowSyncProgress.IDLE })
            .sendToAddress(validAddress, amount, emptyWallet = false)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertTrue((result as SdkWriteResult.NotBroadcast).reason.contains("gate closed"))
        assertEquals(0, source.sendCalls)
    }

    @Test
    fun gateClosed_engineError_isNotBroadcast() = runBlocking {
        val source = readySource()
        val errored = caughtUpProgress().copy(phase = ShadowSyncPhase.ERROR)
        val result = service(source, progress = { errored })
            .sendToAddress(validAddress, amount, emptyWallet = false)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.sendCalls)
    }

    @Test
    fun gateClosed_scanNotCaughtUp_isNotBroadcast() = runBlocking {
        val source = readySource()
        // Every genuinely-not-caught-up shape closes the gate: headers still
        // climbing, a filter scan far behind the header tip, and an all-zero
        // snapshot (the post-reset watermark signature, even if it claimed
        // SYNCED). Note the gate does NOT key on the phase label — it opens
        // on caught-up alone — so these fixtures are behind on the HEIGHTS.
        val closedProgressions = listOf(
            caughtUpProgress().copy(phase = ShadowSyncPhase.HEADERS, headerHeight = 1_400_000, filterHeight = 0),
            caughtUpProgress().copy(filterHeight = 1_400_000),
            caughtUpProgress().copy(
                phase = ShadowSyncPhase.SYNCED,
                headerHeight = 0, headerTarget = 0, filterHeight = 0, filterTarget = 0
            )
        )
        for (progress in closedProgressions) {
            val result = service(source, progress = { progress })
                .sendToAddress(validAddress, amount, emptyWallet = false)
            assertTrue("$progress must close the gate", result is SdkWriteResult.NotBroadcast)
        }
        assertEquals(0, source.sendCalls)
    }

    @Test
    fun gateOpen_noDashjParityRequired() = runBlocking {
        // Regression guard for the bricked-sends bug: the gate must NOT
        // consult any dashj comparison — a caught-up engine broadcasts,
        // full stop, and WITHOUT phase==SYNCED (the default fixture is
        // FILTERS). Post-migration the held dashj wallet diverges
        // permanently, so any parity requirement would close the gate
        // forever after the first external receive.
        val source = readySource()
        assertFalse(caughtUpProgress().synced)
        val result = service(source).sendToAddress(validAddress, amount, emptyWallet = false)
        assertTrue(result is SdkWriteResult.Broadcast)
    }

    @Test
    fun probeSendGate_mirrorsTheSendGate_andTouchesNoSdkSurface() {
        // The debug settings status line reads THIS accessor — it must be
        // the send predicate itself, and read-only.
        val source = readySource()
        assertTrue(service(source).probeSendGate().allowed)
        assertFalse(service(source, progress = { ShadowSyncProgress.IDLE }).probeSendGate().allowed)
        assertFalse(
            service(source, progress = { caughtUpProgress().copy(phase = ShadowSyncPhase.ERROR) })
                .probeSendGate().allowed
        )
        assertFalse(
            service(source, progress = { caughtUpProgress().copy(filterHeight = 1_400_000) })
                .probeSendGate().allowed
        )
        // Contained like a real send's gate read: a progress throw = closed.
        assertFalse(
            service(source, progress = { throw IllegalStateException("shadow broke") })
                .probeSendGate().allowed
        )
        assertEquals(0, source.boundCalls + source.sendCalls)
    }

    @Test
    fun progressReadFailure_keepsGateClosed() = runBlocking {
        val source = readySource()
        val result = service(source, progress = { throw IllegalStateException("shadow broke") })
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
            l1Progress = { caughtUpProgress() },
            onSelfSpendBroadcast = { throw IllegalStateException("shadow not running") }
        )
        val result = svc.sendToAddress(validAddress, amount, emptyWallet = false)
        assertEquals(SdkWriteResult.Broadcast(txid), result)
    }

    // ── beforeBroadcast (the call site's dashj-equivalent conditions) ────

    @Test
    fun beforeBroadcast_runsOnlyAfterAllPreflightsPass() = runBlocking {
        var invoked = 0
        // Gate closed → the hook must NOT run.
        val closed = service(readySource(), progress = { ShadowSyncProgress.IDLE })
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
            l1Progress = { caughtUpProgress() }
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
        val result = service(source, enabled = false, cutoverState = "CUT_OVER", progress = { ShadowSyncProgress.IDLE })
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

    // ── MAX-send completeness: receival sweep-all before the drain ───────

    @Test
    fun sendAll_sweepsReceivalAccounts_beforeTheFloorRead_thenDrains() = runBlocking {
        // The on-device bug: the BIP44 drain cannot see DashPay
        // receival-account funds and self-limits WITHOUT failing, so a MAX
        // send quietly left the contact-received funds behind. The sweep-all
        // pass must run before the floor's balance read (so the floor covers
        // the swept funds) and outside the core-send lock (its own txs are
        // self-sends; only the drain serializes).
        val source = drainReadySource(spendable = 7_000_000L)
        source.onSweepReceival = { 2_000_000L }
        val result = service(source, enabled = false, cutoverState = "CUT_OVER")
            .sendToAddress(validAddress, amount, emptyWallet = true)
        assertEquals(SdkWriteResult.Broadcast(txid), result)
        assertEquals(1, source.sweepReceivalCalls)
        assertEquals("sweep must precede the floor's balance read", 0, source.spendableCallsAtSweep)
        assertFalse(source.sweepInsideLock)
        assertEquals(listOf(7_000_000L - SEND_ALL_FEE_RESERVE_DUFFS), source.sendAllFloors)
        assertEquals(1, source.lockAcquisitions)
        assertEquals(1, selfSpendMarks)
    }

    @Test
    fun sendAll_sweepFailure_isNotBroadcast_andTheDrainNeverRuns() = runBlocking {
        // Draining anyway after a failed sweep would repeat the
        // silent-shortchange bug (deliver less than the quoted max), so a
        // sweep failure fails the send closed: NotBroadcast (the payment was
        // never built; broadcast sweeps are self-sends — funds safe), no
        // balance read, no drain attempt, no lock, no hooks.
        val source = drainReadySource()
        source.onSweepReceival = { throw IllegalStateException("sweep broadcast failed") }
        val result = service(source, enabled = false, cutoverState = "CUT_OVER")
            .sendToAddress(validAddress, amount, emptyWallet = true)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertTrue(
            "reason must say the funds are safe",
            (result as SdkWriteResult.NotBroadcast).reason.contains("funds are safe")
        )
        assertEquals(0, source.spendableCalls)
        assertEquals(0, source.sendAllCalls)
        assertEquals(0, source.lockAcquisitions)
        assertEquals(0, selfSpendMarks)
        assertTrue(bridgedTxids.isEmpty())
    }

    @Test
    fun plainSend_neverRunsTheSendAllSweep() = runBlocking {
        // The plain path's receival handling is the SHORTFALL fallback inside
        // the source (single-account, then consolidation) — never the
        // send-all sweep pass.
        val source = readySource()
        val result = service(source).sendToAddress(validAddress, amount, emptyWallet = false)
        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals(0, source.sweepReceivalCalls)
    }

    @Test
    fun sendAll_beforeBroadcastThrow_alsoSkipsTheSweeps() = runBlocking {
        // The call-site pre-send conditions may throw to ask the user
        // (LeftoverBalanceException → confirm dialog); NOTHING may have been
        // broadcast by then — the receival self-sweeps included, or a
        // cancelled confirm would already have de-linked the receival funds.
        val source = drainReadySource()
        source.onSweepReceival = { 1_000_000L }
        val boom = IllegalStateException("leftover balance")
        try {
            service(source, enabled = false, cutoverState = "CUT_OVER")
                .sendToAddress(validAddress, amount, emptyWallet = true) { throw boom }
            fail("expected the beforeBroadcast throw to propagate")
        } catch (e: IllegalStateException) {
            assertSame(boom, e)
        }
        assertEquals(0, source.sweepReceivalCalls)
        assertEquals(0, source.sendAllCalls)
    }

    @Test
    fun sendAll_lockGuardAndGate_blockBeforeAnySweep() = runBlocking {
        // The fail-closed drain guards run before the sweeps: a blocked
        // drain must not have consolidated the receival accounts first.
        val locked = drainReadySource()
        service(locked, enabled = false, cutoverState = "CUT_OVER", hasAppLockedOutputs = { true })
            .sendToAddress(validAddress, amount, emptyWallet = true)
        assertEquals(0, locked.sweepReceivalCalls)

        val gateClosed = drainReadySource()
        service(gateClosed, enabled = false, cutoverState = "CUT_OVER", progress = { ShadowSyncProgress.IDLE })
            .sendToAddress(validAddress, amount, emptyWallet = true)
        assertEquals(0, gateClosed.sweepReceivalCalls)
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
        // AAR-bump canary (pin-don't-track), asserting the v41int11 shape:
        // the drain shim (CoreSendAllNative, Java on purpose) reaches the
        // SDK's internal builder through the JVM-level $sdk_release mangling
        // of the PINNED dash-sdk-android binary. javac catches a rename only
        // when the compile classpath and the packaged AAR agree — this
        // reflection probe keeps the break loud at TEST time even when they
        // drift, instead of at first-send time.
        val mpw = org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet::class.java
        val builder = org.dashfoundation.dashsdk.wallet.CoreTransactionBuilder::class.java
        val accountType = org.dashfoundation.dashsdk.wallet.CoreTransactionBuilder.AccountType::class.java
        val strategy = org.dashfoundation.dashsdk.wallet.CoreTransactionBuilder.SelectionStrategy::class.java
        val coreTx = org.dashfoundation.dashsdk.wallet.CoreTransaction::class.java

        // The exact members CoreSendAllNative.buildSignBroadcastDrain links.
        builder.getConstructor(org.dashfoundation.dashsdk.Network::class.java)
        builder.getMethod("addOutput\$sdk_release", String::class.java, Long::class.javaPrimitiveType)
        builder.getMethod("setSelectionStrategy\$sdk_release", strategy)
        builder.getMethod("setFunding\$sdk_release", mpw, accountType, Int::class.javaPrimitiveType)
        assertEquals(
            coreTx,
            builder.getMethod(
                "buildSigned\$sdk_release",
                mpw, accountType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType
            ).returnType
        )
        // …and the broadcast leg.
        val coreWallet = mpw.getMethod("coreWallet").returnType
        assertEquals(org.dashfoundation.dashsdk.wallet.ManagedCoreWallet::class.java, coreWallet)
        coreWallet.getMethod("broadcastTransaction", coreTx)

        // The strategy knob's Kotlin-side FFI value must stay 5 = All
        // (CoreSelectionStrategyFFI::All in rs-platform-wallet-ffi).
        assertEquals(
            5,
            org.dashfoundation.dashsdk.wallet.CoreTransactionBuilder.SelectionStrategy.ALL.ffiValue
        )

        // Drain serialization is APP-owned (SdkL1SendService.coreSendMutex /
        // withCoreSendLock): since v41int11 dropped the v41int9-era synthetic
        // accessor, the pinned AAR must expose NO SDK-owned coreSendMutex.
        // If a future AAR reintroduces one, the SDK is re-owning
        // serialization and the withCoreSendLock nesting contract (plain
        // send locks inside sendToAddresses, drain locks app-side) needs a
        // re-audit before bumping the pin.
        try {
            mpw.getMethod("access\$getCoreSendMutex\$p", mpw)
            fail(
                "pinned AAR exposes an SDK-owned coreSendMutex again — " +
                    "re-audit the app-owned withCoreSendLock contract before bumping the pin"
            )
        } catch (expected: NoSuchMethodException) {
            // v41int11 shape: no SDK-side mutex accessor.
        }

        // The funding-path plain-send surface the Kotlin side links
        // (SdkL1SendService → ManagedPlatformWallet.buildSignedPaymentWithToken,
        // suspend → mangled with a trailing Continuation at the JVM level)…
        mpw.getMethod(
            "buildSignedPaymentWithToken",
            java.util.List::class.java,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            String::class.java,
            kotlin.coroutines.Continuation::class.java
        )
        // …and its JNI binding, whose NAME is the runtime link contract
        // (a rename only surfaces as UnsatisfiedLinkError at first send).
        val jni = Class.forName("org.dashfoundation.dashsdk.ffi.WalletManagerNative")
            .getDeclaredMethod(
                "coreWalletBuildSignedPaymentWithToken",
                Long::class.javaPrimitiveType,
                ByteArray::class.java,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                String::class.java
            )
        assertTrue(java.lang.reflect.Modifier.isNative(jni.modifiers))
        assertEquals(ByteArray::class.java, jni.returnType)
    }
}
