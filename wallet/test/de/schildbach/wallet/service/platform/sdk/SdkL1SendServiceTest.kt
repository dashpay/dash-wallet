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
        /** Fee the faked engine reports for a Maya deposit build. */
        var onMayaDepositFee: (Long, ByteArray) -> Long = { _, _ -> 0L },
        var externalAddress: String? = null,
        // Interface default: enumeration unavailable → the service uses the
        // flat SEND_ALL_FEE_RESERVE_DUFFS fallback reserve.
        var onPooledUtxoCount: () -> Int? = { null }
    ) : SdkL1SendSource {
        var boundCalls = 0
        var sendCalls = 0
        var spendableCalls = 0
        var pooledUtxoCountCalls = 0
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

        override suspend fun pooledSpendableUtxoCount(walletIdHex: String): Int? {
            pooledUtxoCountCalls++
            return onPooledUtxoCount()
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

        // ── Maya deposit build / release (fee-probe surface) ──────────────
        var mayaBuildCalls = 0
        var mayaReleaseCalls = 0
        val mayaBuiltAmounts = mutableListOf<Long>()
        val mayaBuiltMemoSizes = mutableListOf<Int>()
        var mayaBuiltVault: String? = null

        var mayaBuiltDrains = mutableListOf<Boolean>()

        /**
         * Under [drain] the engine computes the deliverable amount, so the fake
         * mirrors that: it reports [drainDeliverable] rather than echoing the
         * caller's (ignored) [vaultDuffs].
         */
        var drainDeliverable: Long = 0

        /** When set, the build throws it — the engine refusing an unfundable drain. */
        var failMayaBuildWith: Throwable? = null

        override suspend fun buildDeferredMayaDeposit(
            walletIdHex: String,
            vaultAddressBase58: String,
            vaultDuffs: Long,
            memo: ByteArray
        ): SdkDeferredPayment {
            mayaBuildCalls++
            failMayaBuildWith?.let { throw it }
            mayaBuiltAmounts += vaultDuffs
            mayaBuiltMemoSizes += memo.size
            mayaBuiltVault = vaultAddressBase58
            return SdkDeferredPayment(
                txidHex = "bb".repeat(32),
                rawTxBytes = ByteArray(0),
                feeDuffs = onMayaDepositFee(vaultDuffs, memo),
                native = null
            )
        }

        override suspend fun releaseDeferredPayment(walletIdHex: String, payment: SdkDeferredPayment) {
            mayaReleaseCalls++
        }

        override suspend fun unusedExternalAddress(walletIdHex: String): String? = externalAddress
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
        utxoCount: suspend (String) -> Int = { 1 },
        // Fresh empty registry by default: no seam locks, drain paths
        // exercisable. Seam-lock refusal is covered by dedicated tests.
        seamRegistry: SeamOutputLockRegistry = SeamOutputLockRegistry()
    ) = SdkL1SendService(
        source = source,
        dashPayConfig = config(enabled, cutoverState),
        isValidAddress = addressValid,
        l1Progress = progress,
        hasAppLockedSpendableOutputs = hasAppLockedOutputs,
        spendableUtxoCount = utxoCount,
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
    fun classify_typedTransactionBuild_isNotBroadcast() {
        // v4.2-dev dropped the typed code-32 arm (#4310/#4311 closed
        // unmerged); a builder rejection now classifies via the
        // WalletOperation "transaction build failed" prefix fallback — still
        // pre-broadcast, so the safe fallback is preserved.
        val error = DashSdkError.PlatformWallet.WalletOperation(
            "transaction build failed: funding path m/44'/1'/0' matches no spendable funds account"
        )
        val result = classifyCoreSendFailure(error)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertSame(error, (result as SdkWriteResult.NotBroadcast).cause)
    }

    @Test
    fun classify_typedTransactionSigning_isNotBroadcast_keyedRetryableAfterUnlock() {
        // The merged line surfaces a signing failure as SigningKeyUnavailable
        // (code 31): request valid, tx assembled, but the signer holds no
        // usable key (Keystore locked). Must be NotBroadcast AND carry the
        // signer-locked reason key so the UI surfaces "unlock to continue".
        val error = DashSdkError.PlatformWallet.SigningKeyUnavailable(
            "mnemonic unavailable: keystore locked"
        )
        val result = classifyCoreSendFailure(error)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        result as SdkWriteResult.NotBroadcast
        assertSame(error, result.cause)
        assertTrue(
            "signing refusals must be keyed retryable-after-unlock, got '${result.reason}'",
            result.reason.contains(L1_SIGNER_LOCKED_REASON)
        )
    }

    @Test
    fun classify_typedBroadcastRejected_isNotBroadcast_unlikeUnconfirmed() {
        // v41int14: code 26 is the DEFINITIVE counterpart of code 20 — Core
        // provably rejected the tx and the reservation was released. It must
        // classify NotBroadcast while its ambiguous sibling (20) stays
        // Ambiguous.
        val rejected = DashSdkError.PlatformWallet.TransactionBroadcastRejected(
            "rejected by core: dust output"
        )
        val result = classifyCoreSendFailure(rejected)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertSame(rejected, (result as SdkWriteResult.NotBroadcast).cause)
        // The ambiguous sibling keeps its classification — the pair must
        // never collapse into one rule.
        assertTrue(
            classifyCoreSendFailure(
                DashSdkError.PlatformWallet.TransactionBroadcastUnconfirmed("timeout after send")
            ) is SdkWriteResult.Ambiguous
        )
    }

    @Test
    fun classify_staleReservationToken_isNotBroadcast_onTheAtomicSendPath() {
        // dashpay/platform#4309 age-guards the ATOMIC finalize → broadcast
        // path (plain send and drain) against the same reservation bound as
        // the deferred surface, reusing native code 34. The guard refuses
        // BEFORE touching the broadcaster and releases the reservation
        // owner-guarded, so this is definitively pre-network — Ambiguous here
        // would show a payment that never left the device as "may be on the
        // network" and block the dashj fallback.
        val stale = DashSdkError.PlatformWallet.StaleReservationToken(
            "finalized transaction reservation has outlived its lifetime; rebuild the payment"
        )
        val result = classifyCoreSendFailure(stale)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertSame(stale, (result as SdkWriteResult.NotBroadcast).cause)
        // The ambiguous broadcast sibling must not be dragged along: only the
        // pre-network refusal downgrades.
        assertTrue(
            classifyCoreSendFailure(
                DashSdkError.PlatformWallet.TransactionBroadcastUnconfirmed("timeout after send")
            ) is SdkWriteResult.Ambiguous
        )
    }

    @Test
    fun classify_typedArms_reachTheDeferredTableToo() {
        // classifyDeferredBroadcastFailure defers to classifyCoreSendFailure,
        // so the typed trio must classify identically on the BIP70 deferred
        // broadcast path.
        for (
            error in listOf<Throwable>(
                DashSdkError.PlatformWallet.WalletOperation("transaction build failed: bad recipients blob"),
                DashSdkError.PlatformWallet.SigningKeyUnavailable("keystore locked"),
                DashSdkError.PlatformWallet.TransactionBroadcastRejected("rejected by core"),
                DashSdkError.PlatformWallet.StaleReservationToken("reservation aged out")
            )
        ) {
            assertTrue(
                "${error.javaClass.simpleName} must be NotBroadcast on the deferred path",
                classifyDeferredBroadcastFailure(error) is SdkWriteResult.NotBroadcast
            )
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
        // TYPED arm (v41int19, the pooled atomic finalize's selector
        // shortfall — FFI code 22): the message carries no key-wallet
        // "Insufficient funds" Display text, so without the typed arm the
        // adjust-down retry would silently never fire on new AARs.
        assertTrue(
            isSendAllShortfall(
                DashSdkError.PlatformWallet.CoreInsufficientFunds(
                    "insufficient unreserved Core funds: available 5000000, required 5001480"
                )
            )
        )
        // LEGACY arm: WalletOperation + FFI build prefix + key-wallet's
        // InsufficientFunds Display text (the v1 split surface's wrapping).
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
        // A typed build refusal that is NOT a shortfall must not retry either.
        assertFalse(
            isSendAllShortfall(DashSdkError.PlatformWallet.WalletOperation("transaction build failed: recipient below dust"))
        )
        // Every retryable shortfall MUST classify NotBroadcast (the no-
        // double-broadcast proof the single retry rests on).
        val shortfall = DashSdkError.PlatformWallet.WalletOperation(
            "transaction build failed: Insufficient funds: available 1, required 2"
        )
        assertTrue(classifyCoreSendFailure(shortfall) is SdkWriteResult.NotBroadcast)
        val typedShortfall = DashSdkError.PlatformWallet.CoreInsufficientFunds(
            "insufficient unreserved Core funds: available 1, required 2"
        )
        assertTrue(classifyCoreSendFailure(typedShortfall) is SdkWriteResult.NotBroadcast)
    }

    // ── Send-all fee reserve: the iOS size-based model ───────────────────

    @Test
    fun sendAllFeeReserve_isSizeBased_withTheFlatFallback() {
        // Model: (10 + n·148 + 68) bytes at 1 duff/byte, +50%.
        assertEquals((10L + 0L * 148L + 68L) * 3L / 2L, sendAllFeeReserveDuffs(0))
        assertEquals((10L + 1L * 148L + 68L) * 3L / 2L, sendAllFeeReserveDuffs(1))
        assertEquals((10L + 10L * 148L + 68L) * 3L / 2L, sendAllFeeReserveDuffs(10))
        // A 200-input wallet needs far more than the old flat reserve —
        // the exact case dashwallet-ios#928 fixed.
        assertEquals((10L + 200L * 148L + 68L) * 3L / 2L, sendAllFeeReserveDuffs(200))
        assertTrue(sendAllFeeReserveDuffs(200) > SEND_ALL_FEE_RESERVE_DUFFS)
        // Enumeration unavailable → the flat fallback, never a failure.
        assertEquals(SEND_ALL_FEE_RESERVE_DUFFS, sendAllFeeReserveDuffs(null))
        assertEquals(SEND_ALL_FEE_RESERVE_DUFFS, sendAllFeeReserveDuffs(-1))
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
        // spendable − reserve (the iOS-validated max pattern); with the
        // UTXO enumeration unavailable (the fake's default) the reserve is
        // the flat fallback constant.
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

    @Test
    fun sendAll_withAPooledUtxoCount_usesTheSizeBasedReserve() = runBlocking {
        // The iOS #928 model: the reserve is sized from the pooled UNSPENT
        // output count, so a many-input wallet's floor leaves room for the
        // real fee instead of failing the first attempt on the flat reserve.
        val source = drainReadySource(spendable = 100_000_000L)
        source.onPooledUtxoCount = { 200 }
        val result = service(source, enabled = false, cutoverState = "CUT_OVER")
            .sendToAddress(validAddress, amount, emptyWallet = true)
        assertEquals(SdkWriteResult.Broadcast(txid), result)
        assertEquals(1, source.pooledUtxoCountCalls)
        assertEquals(
            listOf(100_000_000L - sendAllFeeReserveDuffs(200)),
            source.sendAllFloors
        )
    }

    @Test
    fun sendAll_pooledUtxoCountThrow_fallsBackToTheFlatReserve() = runBlocking {
        // The count read is advisory: a throwing source must not fail the
        // send — the service treats it like an unavailable enumeration and
        // uses the flat reserve. (The production source already contains its
        // own failures to null; this covers a source that leaks the throw.)
        val source = drainReadySource(spendable = 5_000_000L)
        source.onPooledUtxoCount = { throw IllegalStateException("room unavailable") }
        val result = service(source, enabled = false, cutoverState = "CUT_OVER")
            .sendToAddress(validAddress, amount, emptyWallet = true)
        assertEquals(SdkWriteResult.Broadcast(txid), result)
        assertEquals(listOf(5_000_000L - SEND_ALL_FEE_RESERVE_DUFFS), source.sendAllFloors)
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
        // AAR-bump canary (pin-don't-track), asserting the v41int19 shape:
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
        val finalizedTx = org.dashfoundation.dashsdk.wallet.FinalizedCoreTransaction::class.java

        // The exact members CoreSendAllNative.buildSignBroadcastDrain links:
        // new → addOutput → setSelectionStrategy → finalizeAtomic (the
        // concurrency-safe v2 surface — #4323 removed the setFunding +
        // buildSigned split from the supported path).
        builder.getConstructor(org.dashfoundation.dashsdk.Network::class.java)
        builder.getMethod("addOutput\$sdk_release", String::class.java, Long::class.javaPrimitiveType)
        builder.getMethod("setSelectionStrategy\$sdk_release", strategy)
        assertEquals(
            finalizedTx,
            builder.getMethod(
                "finalizeAtomic\$sdk_release",
                mpw, accountType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType
            ).returnType
        )
        // …and the broadcast leg (public, no mangling).
        val coreWallet = mpw.getMethod("coreWallet").returnType
        assertEquals(org.dashfoundation.dashsdk.wallet.ManagedCoreWallet::class.java, coreWallet)
        coreWallet.getMethod("broadcastTransaction", finalizedTx)

        // The strategy knob's Kotlin-side FFI value must stay 5 = All
        // (CoreSelectionStrategyFFI::All in rs-platform-wallet-ffi).
        assertEquals(
            5,
            org.dashfoundation.dashsdk.wallet.CoreTransactionBuilder.SelectionStrategy.ALL.ffiValue
        )

        // The POOLED funding selector (dashpay/platform#4329): the user-facing
        // send-all drains ALL_SPENDABLE (FFI value 3 =
        // CoreAccountTypeFFI::AllSpendable), and the public send surface
        // defaults to the pooled set too — both enums must keep it.
        assertEquals(
            3,
            org.dashfoundation.dashsdk.wallet.CoreTransactionBuilder.AccountType.ALL_SPENDABLE.ffiValue
        )
        assertEquals(
            3,
            org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet.AccountType.ALL_SPENDABLE.ffiValue
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
            // v41int11+ shape: no SDK-side mutex accessor.
        }
    }

    // ── Maya max-deposit measurement ──────────────────────────────────────
    // The figure quoted for a MAX sell. It must NEVER come in under the real
    // fee: a quote derived from a too-small reserve makes the deposit pay the
    // vault less than quoted, and NEAR Intents refuses under-delivery.

    private fun mayaSource(spendable: Long, feeDuffs: Long, deliverable: Long = 0L) = FakeSource(
        boundWalletId = { walletId },
        onSpendable = { spendable },
        onMayaDepositFee = { _, _ -> feeDuffs },
        externalAddress = validAddress
    ).apply { drainDeliverable = deliverable }

    @Test
    fun maxMayaDepositIsSpendableMinusTheFeeReserve() = runBlocking {
        // The whole model: quote = spendable - reserve, an amount the app owns.
        // No probe build is performed, so nothing is reserved to compute it.
        val source = mayaSource(spendable = 1_000_000L, feeDuffs = 500L)
        val expected = 1_000_000L - mayaMaxFeeReserveDuffs(1, SdkL1SendService.MAX_MAYA_MEMO_BYTES)
        assertEquals(expected, service(source).maxMayaDepositDuffs())
        assertEquals("quoting must not build anything", 0, source.mayaBuildCalls)
        assertEquals("and must not reserve anything", 0, source.mayaReleaseCalls)
    }

    @Test
    fun maxMayaDepositReserveGrowsWithTheInputCount() = runBlocking {
        // More inputs means a bigger transaction means a bigger fee, so the
        // reserve must scale with the UTXO count -- under-reserving is the
        // direction that fails the build.
        val source = mayaSource(spendable = 10_000_000L, feeDuffs = 500L)
        val few = service(source, utxoCount = { 1 }).maxMayaDepositDuffs()
        val many = service(source, utxoCount = { 40 }).maxMayaDepositDuffs()
        assertTrue("a 40-input wallet must reserve more than a 1-input one", many < few)
    }

    @Test
    fun maxMayaDepositReservesForTheWorstCaseMemoByDefault() = runBlocking {
        // The default sizes the data carrier at the 80-byte ceiling, so a
        // shorter real memo only over-reserves -- the safe direction. Needs a
        // wallet big enough that the 1000-duff floor is not what decides the
        // reserve; see the floor test below.
        val source = mayaSource(spendable = 10_000_000L, feeDuffs = 500L)
        val worstCase = service(source, utxoCount = { 40 }).maxMayaDepositDuffs()
        val shortMemo = service(source, utxoCount = { 40 }).maxMayaDepositDuffs(memoSizeBytes = 10)
        assertTrue("a 10-byte memo leaves more depositable", shortMemo > worstCase)
    }

    @Test
    fun theReserveFloorDominatesASmallWallet() = runBlocking {
        // Sized purely by bytes, a one-input deposit would reserve only a few
        // hundred duffs, so the 1000-duff floor is what actually applies -- and
        // it makes the memo size irrelevant at that scale. Pinned so the floor
        // is not mistaken for a bug when a small wallet quotes identically for
        // any memo length.
        assertEquals(1000L, mayaMaxFeeReserveDuffs(1, SdkL1SendService.MAX_MAYA_MEMO_BYTES))
        assertEquals(1000L, mayaMaxFeeReserveDuffs(1, 10))
        assertTrue(mayaMaxFeeReserveDuffs(40, SdkL1SendService.MAX_MAYA_MEMO_BYTES) > 1000L)
    }

    @Test
    fun maxMayaDepositNeverGoesNegative() = runBlocking {
        // A reserve larger than the balance must read as "nothing depositable",
        // never as a negative quote.
        val source = mayaSource(spendable = 100L, feeDuffs = 25_000L)
        assertEquals(0L, service(source, utxoCount = { 40 }).maxMayaDepositDuffs())
    }

    @Test
    fun maxMayaDepositRefusesWhileAppLockedOutputsExist() = runBlocking {
        // A max deposit drains the account, so selection would reach
        // CrowdNode-locked outputs — the same fail-closed refusal the send-all
        // drain applies. Nothing may be built or measured.
        val source = mayaSource(spendable = 1_000_000L, feeDuffs = 500L)
        try {
            service(source, hasAppLockedOutputs = { true }).maxMayaDepositDuffs()
            fail("expected the max deposit to be refused while app-locked outputs exist")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("app-locked"))
        }
        assertEquals(0, source.mayaBuildCalls)
    }

    @Test
    fun maxMayaDepositRefusesOnSeamRegisteredLocks() = runBlocking {
        // Locks on SDK-only txs are invisible to the dashj check; they block too.
        val source = mayaSource(spendable = 1_000_000L, feeDuffs = 500L)
        val registry = SeamOutputLockRegistry().apply { lockOutput("ee".repeat(32), 0) }
        try {
            service(source, seamRegistry = registry).maxMayaDepositDuffs()
            fail("expected the max deposit to be refused while seam locks exist")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("app-locked"))
        }
        assertEquals(0, source.mayaBuildCalls)
    }

    @Test
    fun maxMayaDepositFailsClosedWhenTheLockCheckThrows() = runBlocking {
        val source = mayaSource(spendable = 1_000_000L, feeDuffs = 500L)
        val svc = service(source, hasAppLockedOutputs = { throw IllegalStateException("wallet unavailable") })
        try {
            svc.maxMayaDepositDuffs()
            fail("expected a failed lock check to block the max deposit")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("app-locked"))
        }
        assertEquals(0, source.mayaBuildCalls)
    }

    @Test
    fun maxDepositRefusesAppLockedOutputsWithoutAnyPriorMeasurement() = runBlocking {
        // The guard belongs to the PRIMITIVE, not to the call-site convention
        // of measuring first. A caller that goes straight to a max build —
        // which no current caller does, but which one refactor could — must
        // still be refused, with nothing reserved.
        val source = mayaSource(spendable = 1_000_000L, feeDuffs = 500L)
        val svc = service(source, hasAppLockedOutputs = { true })
        try {
            svc.buildDeferredMayaDeposit(validAddress, 50_000L, ByteArray(40), isMaxDeposit = true)
            fail("expected a direct max build to be refused while app-locked outputs exist")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("app-locked"))
        }
        assertEquals(0, source.mayaBuildCalls)
    }

    @Test
    fun maxDepositRefusesSeamRegisteredLocksWithoutAnyPriorMeasurement() = runBlocking {
        val source = mayaSource(spendable = 1_000_000L, feeDuffs = 500L)
        val registry = SeamOutputLockRegistry().apply { lockOutput("ee".repeat(32), 0) }
        try {
            service(source, seamRegistry = registry)
                .buildDeferredMayaDeposit(validAddress, 50_000L, ByteArray(40), isMaxDeposit = true)
            fail("expected a direct max build to be refused while seam locks exist")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("app-locked"))
        }
        assertEquals(0, source.mayaBuildCalls)
    }

    @Test
    fun partialDepositIsNotBlockedByAppLockedOutputs() = runBlocking {
        // Only a DRAIN is guarded. A partial deposit keeps the ordinary send's
        // exposure — guarding it too would block ordinary swaps for anyone
        // holding a CrowdNode balance.
        val source = mayaSource(spendable = 1_000_000L, feeDuffs = 500L)
        val svc = service(source, hasAppLockedOutputs = { true })
        svc.buildDeferredMayaDeposit(validAddress, 50_000L, ByteArray(40), isMaxDeposit = false)
        assertEquals(1, source.mayaBuildCalls)
    }

    @Test
    fun maxMayaDepositRejectsAnOversizeMemo() = runBlocking {
        val source = mayaSource(spendable = 1_000_000L, feeDuffs = 500L)
        try {
            service(source).maxMayaDepositDuffs(memoSizeBytes = SdkL1SendService.MAX_MAYA_MEMO_BYTES + 1)
            fail("expected an oversize memo to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("memoSizeBytes"))
        }
        Unit
    }
}
