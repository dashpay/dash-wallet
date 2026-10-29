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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.dash.wallet.common.money.Dash
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        /** Runs after [onSend] submitted, before the native call returns: may suspend. */
        var afterSendSubmitted: suspend () -> Unit = {},
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

        // ── CoinJoin consolidation (mirrors DashSdkL1SendSource: a throwing
        // `prepared` abandons the finalized build, releasing its inputs) ──
        var consolidationAbandons = 0
        var consolidationBroadcasts = 0
        val consolidationWallets = mutableListOf<String>()

        /** The persisted address table's answer: which own account holds the destination. */
        var owner: (String, String) -> OwnDestinationAccount? = { _, _ -> OwnDestinationAccount.BIP44 }
        val ownershipChecks = mutableListOf<Pair<String, String>>()
        var ownershipCheckedInsideLock = false

        override suspend fun destinationOwner(walletIdHex: String, address: String): OwnDestinationAccount? {
            ownershipChecks += walletIdHex to address
            ownershipCheckedInsideLock = insideLock
            return owner(walletIdHex, address)
        }

        override suspend fun consolidateCoinJoin(
            walletIdHex: String,
            destination: String,
            amount: Long,
            drain: Boolean,
            prepared: suspend (ByteArray) -> Unit
        ): String {
            consolidationWallets += walletIdHex
            try {
                prepared(byteArrayOf(1, 2, 3))
            } catch (t: Throwable) {
                consolidationAbandons++
                throw t
            }
            consolidationBroadcasts++
            return "11".repeat(32)
        }

        /** Runs before the core-send lock is acquired: stands in for waiting on a held lock. */
        var waitForCoreSendLock: suspend () -> Unit = {}

        /** The faked CoinJoin-account drain (the interface default throws). */
        var onDrainCoinJoin: (String, String, Long) -> String = { _, _, _ ->
            throw UnsupportedOperationException("drain not stubbed")
        }
        var drainCoinJoinCalls = 0

        override suspend fun drainCoinJoinToAddress(walletIdHex: String, addressBase58: String, floorDuffs: Long): String {
            drainCoinJoinCalls++
            return onDrainCoinJoin(walletIdHex, addressBase58, floorDuffs)
        }

        override suspend fun <T> withCoreSendLock(walletIdHex: String, block: suspend () -> T): T {
            waitForCoreSendLock()
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
            val sent = onSend(walletIdHex, addressBase58, amountDuffs)
            afterSendSubmitted()
            return sent
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
            releasedOnWallets += walletIdHex
        }

        // ── BIP70 deferred build / broadcast ──────────────────────────────
        val releasedOnWallets = mutableListOf<String>()
        var deferredBuildCalls = 0
        val broadcastOnWallets = mutableListOf<String>()

        override suspend fun buildDeferredPayment(
            walletIdHex: String,
            recipients: List<Pair<String, Long>>
        ): SdkDeferredPayment {
            deferredBuildCalls++
            return SdkDeferredPayment(txidHex = "dd".repeat(32), rawTxBytes = ByteArray(0), feeDuffs = 226L, native = null)
        }

        /** Runs inside the faked native broadcast; a blocking one stands in for a native call. */
        var onDeferredBroadcast: () -> Unit = {}

        override suspend fun broadcastDeferredPayment(walletIdHex: String, payment: SdkDeferredPayment): String {
            onDeferredBroadcast()
            broadcastOnWallets += walletIdHex
            return payment.txidHex
        }

        override suspend fun unusedExternalAddress(walletIdHex: String): String? = externalAddress

        /** Null = the interface default (no raw-bytes broadcast in the pinned SDK). */
        var onRawBroadcast: ((ByteArray) -> String)? = null
        val rawBroadcasts = mutableListOf<ByteArray>()

        override suspend fun broadcastRawTransaction(rawTx: ByteArray): String {
            rawBroadcasts += rawTx
            return onRawBroadcast?.invoke(rawTx) ?: super.broadcastRawTransaction(rawTx)
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
        // The SDK's three-phase mean; 1.0 is honest for a fixture at the tip.
        // Since 2026-09-21 the gate READS it (the iOS aggregate rule), so a
        // fixture that is behind on heights must lower this too — see
        // gateClosed_scanNotCaughtUp_isNotBroadcast.
        overallPercent = 1.0,
        headerHeight = 1_500_000,
        headerTarget = 1_500_000,
        filterHeight = 1_500_000,
        filterTarget = 1_500_000,
        // A known wallet cursor at the tip: an unknown one closes the gate.
        walletSyncedHeight = 1_500_000
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
        seamRegistry: SeamOutputLockRegistry = SeamOutputLockRegistry(),
        unprotectedPendingPayment: suspend () -> Boolean = { false },
        fundingSelection: de.schildbach.wallet.payments.FundingSelectionLock =
            de.schildbach.wallet.payments.FundingSelectionLock()
    ) = SdkL1SendService(
        source = source,
        dashPayConfig = config(enabled, cutoverState),
        isValidAddress = addressValid,
        l1Progress = progress,
        hasAppLockedSpendableOutputs = hasAppLockedOutputs,
        spendableUtxoCount = utxoCount,
        seamOutputLockRegistry = seamRegistry,
        onSelfSpendBroadcast = { selfSpendMarks++ },
        bridgeAfterBroadcast = bridgeAfterBroadcast,
        hasUnprotectedPendingPayment = unprotectedPendingPayment,
        fundingSelection = fundingSelection
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

    private fun consolidationService(
        source: FakeSource,
        seamRegistry: SeamOutputLockRegistry = SeamOutputLockRegistry(),
        unprotectedPendingPayment: suspend () -> Boolean = { false },
        fundingSelection: de.schildbach.wallet.payments.FundingSelectionLock =
            de.schildbach.wallet.payments.FundingSelectionLock()
    ) = SdkL1SendService(
        source = source,
        dashPayConfig = config(true),
        isValidAddress = { true },
        l1Progress = { caughtUpProgress() },
        hasAppLockedCoinJoinOutputs = { false },
        seamOutputLockRegistry = seamRegistry,
        hasUnprotectedPendingPayment = unprotectedPendingPayment,
        fundingSelection = fundingSelection
    )

    /** A consolidation selects fresh CoinJoin inputs: refused, never built, while a pending payment is exposed. */
    @Test
    fun consolidateCoinJoin_pendingPaymentExposed_isRefusedAndNeverBuilds() = runBlocking {
        val source = readySource()
        val svc = consolidationService(source, unprotectedPendingPayment = { true })
        var prepared = false
        val result = svc.consolidateCoinJoin(walletId, validAddress, 1L, drain = true) { prepared = true }
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertTrue(isPaymentStillConfirmingReason((result as SdkWriteResult.NotBroadcast).reason))
        assertFalse(prepared)
        assertTrue(source.consolidationWallets.isEmpty())
    }

    /** The gate is asked again under the shared hold: a payment exposed after the preflight still refuses. */
    @Test
    fun consolidateCoinJoin_pendingPaymentExposedUnderTheHold_isRefused() = runBlocking {
        val source = readySource()
        var asks = 0
        val svc = consolidationService(source, unprotectedPendingPayment = { ++asks > 1 })
        val result = svc.consolidateCoinJoin(walletId, validAddress, 1L, drain = true) { }
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertTrue(isPaymentStillConfirmingReason((result as SdkWriteResult.NotBroadcast).reason))
        assertTrue(source.consolidationWallets.isEmpty())
    }

    /** A rebind between the preflight and the hold refuses: the SDK builds on whichever wallet is bound. */
    @Test
    fun consolidateCoinJoin_reboundUnderTheHold_isRefusedAndNeverBuilds() = runBlocking {
        var lookups = 0
        val source = FakeSource(boundWalletId = { if (++lookups == 1) walletId else "02".repeat(32) })
        val svc = consolidationService(source)
        val result = svc.consolidateCoinJoin(walletId, validAddress, 1L, drain = true) { }
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertTrue(source.consolidationWallets.isEmpty())
        assertEquals(0, source.lockAcquisitions)
    }

    /** The build and broadcast run inside the shared funding hold: a reactivation cannot take it meanwhile. */
    @Test
    fun consolidateCoinJoin_buildsInsideTheSharedHold() = runBlocking {
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        val source = readySource()
        val svc = consolidationService(source, fundingSelection = lock)
        var exclusiveDuringBuild: Unit? = Unit
        val result = svc.consolidateCoinJoin(walletId, validAddress, 1L, drain = true) {
            exclusiveDuringBuild = lock.tryExclusive { }
        }
        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals(null, exclusiveDuringBuild)
    }

    /** Cancelled once the txid checkpoint is being written: the broadcast still follows, nothing is abandoned. */
    @Test
    fun consolidateCoinJoin_cancelledAtTheCheckpoint_stillBroadcasts() = runBlocking {
        val source = readySource()
        val svc = consolidationService(source)
        var result: SdkWriteResult<String>? = null
        lateinit var call: kotlinx.coroutines.Job
        call = launch {
            result = svc.consolidateCoinJoin(walletId, validAddress, 1L, drain = true) {
                call.cancel()
                kotlinx.coroutines.delay(10) // the checkpoint write, suspended when cancelled
            }
        }
        call.join()
        assertEquals(1, source.consolidationBroadcasts)
        assertEquals(0, source.consolidationAbandons)
        assertTrue(result is SdkWriteResult.Broadcast)
    }

    /**
     * The CoinJoin drains use the send-all union: an SDK-only output locked in
     * the seam registry (invisible to the dashj check) refuses consolidation
     * and the migration's shield guard, and so does a registry that cannot be read.
     */
    @Test
    fun consolidateCoinJoin_seamLockedOrUnreadableRegistry_isNotBroadcastAndNeverBuilds() = runBlocking {
        val unreadable = mockk<SeamOutputLockRegistry> {
            every { hasAnyLocks() } throws IllegalStateException("registry unavailable")
        }
        val locked = SeamOutputLockRegistry().apply { lockOutput("ee".repeat(32), 0) }
        for (registry in listOf(locked, unreadable)) {
            val source = readySource()
            val svc = consolidationService(source, registry)
            assertTrue(svc.coinJoinOutputsLocked())
            var prepared = false
            val result = svc.consolidateCoinJoin(walletId, validAddress, 1L, drain = true) { prepared = true }
            assertEquals(SdkWriteResult.NotBroadcast("CoinJoin outputs are locked"), result)
            assertFalse(prepared)
            assertTrue(source.consolidationWallets.isEmpty())
        }
        assertFalse(consolidationService(readySource()).coinJoinOutputsLocked())
    }

    /** A seam lock of a wiped wallet never blocks the next wallet's CoinJoin drain (registry is process-wide). */
    @Test
    fun consolidateCoinJoin_seamLockOfAWipedWallet_noLongerBlocks() = runBlocking {
        val registry = SeamOutputLockRegistry().apply { lockOutput("ee".repeat(32), 0) }
        val svc = consolidationService(readySource(), registry)
        assertTrue(svc.coinJoinOutputsLocked())
        registry.clearForWalletWipe()
        assertFalse(svc.coinJoinOutputsLocked())
    }

    @Test
    fun consolidateCoinJoin_bindLookupFailure_isNotBroadcast() = runBlocking {
        val failure = IllegalStateException("bootstrap failed")
        val source = FakeSource(boundWalletId = { throw failure })
        val svc = consolidationService(source)
        val result = svc.consolidateCoinJoin(walletId, validAddress, 1L, drain = true) { }
        assertEquals(SdkWriteResult.NotBroadcast("SDK bootstrap/bind lookup failed", failure), result)
        assertEquals(1, source.boundCalls)
        assertEquals(0, source.lockAcquisitions)
    }

    @Test
    fun consolidateCoinJoin_walletNotBound_isNotBroadcast() = runBlocking {
        val source = FakeSource(boundWalletId = { null })
        val svc = consolidationService(source)
        val result = svc.consolidateCoinJoin(walletId, validAddress, 1L, drain = true) { }
        assertEquals(SdkWriteResult.NotBroadcast("wallet not bound"), result)
        assertEquals(0, source.lockAcquisitions)
    }

    @Test
    fun consolidateCoinJoin_rejectedSignedDrain_isAbandonedAndNotBroadcast() = runBlocking {
        // The migration rejects a partial drain inside `prepared`
        // (validateCoinJoinConsolidation): nothing is broadcast, the build is
        // abandoned (inputs released) and the result is a definitive
        // pre-broadcast failure, never Ambiguous.
        val source = readySource()
        val rejection = IllegalArgumentException("Drain must spend every snapshot coin (spends 2 of 3)")
        val svc = consolidationService(source)
        val result = svc.consolidateCoinJoin(walletId, validAddress, 1L, drain = true) { throw rejection }
        // Compared by message: crossing withContext, coroutine debug mode may rethrow a copy.
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals("consolidation preparation failed", (result as SdkWriteResult.NotBroadcast).reason)
        assertEquals(rejection.message, result.cause?.message)
        assertEquals(1, source.consolidationAbandons)
        assertEquals(0, source.consolidationBroadcasts)
    }

    @Test
    fun consolidateCoinJoin_differentBoundWallet_isNotBroadcastAndNeverBuilds() = runBlocking {
        // A rebind since the migration's snapshot: never build toward its destination.
        val source = FakeSource(boundWalletId = { "02".repeat(32) })
        val svc = consolidationService(source)
        var prepared = false
        val result = svc.consolidateCoinJoin(walletId, validAddress, 1L, drain = true) { prepared = true }
        assertEquals(SdkWriteResult.NotBroadcast("bound wallet is not the migration's wallet"), result)
        assertTrue(source.consolidationWallets.isEmpty())
        assertEquals(0, source.lockAcquisitions)
        assertFalse(prepared)
    }

    @Test
    fun consolidateCoinJoin_buildsOnTheExpectedWalletById() = runBlocking {
        val source = readySource()
        val svc = consolidationService(source)
        val result = svc.consolidateCoinJoin(walletId, validAddress, 1L, drain = true) { }
        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals(listOf(walletId), source.consolidationWallets)
        // The destination was bound to that same wallet, under the build's lock.
        assertEquals(listOf(walletId to validAddress), source.ownershipChecks)
        assertTrue(source.ownershipCheckedInsideLock)
    }

    @Test
    fun consolidateCoinJoin_destinationNotOwnedByTheWallet_isNotBroadcastAndNeverBuilds() = runBlocking {
        // A valid address of ANOTHER wallet (or none in the persisted table): refuse before building.
        val source = readySource().apply { owner = { _, _ -> null } }
        val svc = consolidationService(source)
        var prepared = false
        val result = svc.consolidateCoinJoin(walletId, validAddress, 1L, drain = true) { prepared = true }
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(DESTINATION_NOT_OWNED, (result as SdkWriteResult.NotBroadcast).cause?.message)
        assertTrue(source.consolidationWallets.isEmpty())
        assertFalse(prepared)
    }

    @Test
    fun consolidateCoinJoin_unreadableOwnership_isNotBroadcastAndNeverBuilds() = runBlocking {
        val source = readySource().apply { owner = { _, _ -> throw java.io.IOException("db closed") } }
        val svc = consolidationService(source)
        val result = svc.consolidateCoinJoin(walletId, validAddress, 1L, drain = true) { }
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertTrue(source.consolidationWallets.isEmpty())
    }

    // ── CoinJoin consolidation replay (exact persisted bytes) ────────────

    private val replayBytes = byteArrayOf(1, 2, 3, 4)

    @Test
    fun rebroadcastConsolidation_submitsTheExactBytes() = runBlocking {
        val source = readySource().apply { onRawBroadcast = { txid } }
        val result = service(source).rebroadcastCoinJoinConsolidation(walletId, replayBytes, txid)
        assertEquals(ConsolidationReplay.SUBMITTED, result)
        assertEquals(1, source.rawBroadcasts.size)
        assertTrue(source.rawBroadcasts.single().contentEquals(replayBytes))
        assertEquals(listOf(txid), bridgedTxids)
        assertEquals(0, source.sendCalls)
        assertEquals(0, source.sendAllCalls)
    }

    @Test
    fun rebroadcastConsolidation_anotherBoundWallet_neverSubmits() = runBlocking {
        val source = FakeSource(boundWalletId = { "02".repeat(32) }).apply { onRawBroadcast = { txid } }
        assertEquals(ConsolidationReplay.FAILED, service(source).rebroadcastCoinJoinConsolidation(walletId, replayBytes, txid))
        assertEquals(0, source.rawBroadcasts.size)
    }

    @Test
    fun rebroadcastConsolidation_submitsInsideTheSharedHold() = runBlocking {
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        var exclusiveDuringBroadcast: Unit? = Unit
        val source = readySource().apply {
            onRawBroadcast = {
                exclusiveDuringBroadcast = kotlinx.coroutines.runBlocking { lock.tryExclusive { } }
                txid
            }
        }
        assertEquals(
            ConsolidationReplay.SUBMITTED,
            service(source, fundingSelection = lock).rebroadcastCoinJoinConsolidation(walletId, replayBytes, txid)
        )
        assertEquals(null, exclusiveDuringBroadcast)
    }

    @Test
    fun rebroadcastConsolidation_alreadyKnownCountsAsSubmitted() = runBlocking {
        for (answer in listOf("txn-already-known", "txn-already-in-mempool", "Transaction already in block chain")) {
            val source = readySource().apply { onRawBroadcast = { throw IllegalStateException("rejected: $answer") } }
            assertEquals(answer, ConsolidationReplay.SUBMITTED,
                service(source).rebroadcastCoinJoinConsolidation(walletId, replayBytes, txid))
        }
    }

    @Test
    fun rebroadcastConsolidation_conflictIsTerminal() = runBlocking {
        val source = readySource().apply {
            onRawBroadcast = { throw RuntimeException("wrapped", IllegalStateException("txn-mempool-conflict")) }
        }
        assertEquals(ConsolidationReplay.CONFLICT, service(source).rebroadcastCoinJoinConsolidation(walletId, replayBytes, txid))
        assertEquals(1, source.rawBroadcasts.size)
    }

    @Test
    fun rebroadcastConsolidation_withoutARawBroadcastApiIsUnavailable() = runBlocking {
        val source = readySource()
        assertEquals(ConsolidationReplay.UNAVAILABLE, service(source).rebroadcastCoinJoinConsolidation(walletId, replayBytes, txid))
        assertTrue(bridgedTxids.isEmpty())
    }

    @Test
    fun rebroadcastConsolidation_otherFailuresAndClosedGateAreRetryable() = runBlocking {
        val failing = readySource().apply { onRawBroadcast = { throw IllegalStateException("bad-txns-inputs-missingorspent") } }
        assertEquals(ConsolidationReplay.FAILED, service(failing).rebroadcastCoinJoinConsolidation(walletId, replayBytes, txid))
        val gated = readySource().apply { onRawBroadcast = { txid } }
        assertEquals(ConsolidationReplay.FAILED,
            service(gated, progress = { ShadowSyncProgress.IDLE }).rebroadcastCoinJoinConsolidation(walletId, replayBytes, txid))
        assertEquals(0, gated.rawBroadcasts.size)
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
    fun gateClosed_waitingForPeers_withThePreviousSessionsHeights_isNotBroadcast() = runBlocking {
        // PastaClaw on #1632: WAITING_FOR_CONNECTIONS maps to CONNECTING while
        // the snapshot keeps heights that read caught up.
        val source = readySource()
        val connecting = caughtUpProgress().copy(phase = ShadowSyncPhase.CONNECTING)
        val result = service(source, progress = { connecting })
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
            // 100k filters behind: the aggregate has to be honest too —
            // (100 + 100 + 93.3) / 3 — or the iOS rule would call it caught up.
            caughtUpProgress().copy(filterHeight = 1_400_000, overallPercent = 0.978),
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
    fun gateClosed_pipelineLagging_isNotBroadcast() = runBlocking {
        // 2026-09-21: the DISPLAY predicate dropped the pipeline-lag veto (iOS
        // rule). The SEND gate must not follow it. Filters at the tip and the
        // aggregate at 100%, but the wallet cursor 300k blocks back = matched
        // blocks still being processed = this wallet's own spends not yet
        // applied to the ledger. A send built on that ledger can pick a UTXO
        // already spent — the double-spend shape of the 48.86 DASH incident.
        val source = readySource()
        val lagging = caughtUpProgress().copy(walletSyncedHeight = 1_200_000)
        assertTrue("fixture must read caught up for the display", lagging.scanCaughtUpToTip)
        assertTrue("…and provably lagging", lagging.blockPipelineLagging)

        val result = service(source, progress = { lagging })
            .sendToAddress(validAddress, amount, emptyWallet = false)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        val reason = (result as SdkWriteResult.NotBroadcast).reason
        assertTrue(
            "the reason must name the pipeline, not the scan: $reason",
            reason.contains("processing matched blocks")
        )
        assertEquals(0, source.sendCalls)
    }

    @Test
    fun gateClosed_unknownWalletCursor_atTheTip_isNotBroadcast() = runBlocking {
        // PastaClaw on #1632: filters at the tip, but no evidence the matched
        // blocks were applied — the UTXO view may still miss a spend.
        val source = readySource()
        val unknownCursor = caughtUpProgress().copy(walletSyncedHeight = 0)
        val result = service(source, progress = { unknownCursor })
            .sendToAddress(validAddress, amount, emptyWallet = false)
        assertTrue(result is SdkWriteResult.NotBroadcast)
        val reason = (result as SdkWriteResult.NotBroadcast).reason
        assertTrue(reason, reason.contains("cursor is not known"))
        assertEquals(0, source.sendCalls)
    }

    @Test
    fun gateClosed_aggregateRuleOnly_withUnknownWalletCursor_isNotBroadcast() = runBlocking {
        // The §34 shape: filters 2,167 short of the tip, the aggregate at
        // 99.954% (the DISPLAY's iOS rule calls that synced), and the wallet
        // cursor unknown, so the pipeline veto has no evidence to act on. The
        // unscanned blocks can hold a spend of a UTXO this wallet still reads
        // unspent; the send gate must keep the height rule.
        val source = readySource()
        val aggregateOnly = caughtUpProgress().copy(
            headerHeight = 1_558_166, headerTarget = 1_558_166,
            filterHeight = 1_555_999, filterTarget = 1_558_166, overallPercent = 0.99954,
            walletSyncedHeight = 0
        )
        assertTrue("fixture must read caught up for the display", aggregateOnly.scanCaughtUpToTip)
        assertFalse("…with no pipeline evidence", aggregateOnly.blockPipelineLagging)

        val result = service(source, progress = { aggregateOnly })
            .sendToAddress(validAddress, amount, emptyWallet = false)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        val reason = (result as SdkWriteResult.NotBroadcast).reason
        assertTrue(reason, reason.contains("short of the chain tip"))
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
            // 100k behind with an honest aggregate, or the iOS rule opens it.
            service(source, progress = { caughtUpProgress().copy(filterHeight = 1_400_000, overallPercent = 0.978) })
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
            onSelfSpendBroadcast = { throw IllegalStateException("shadow not running") },
            fundingSelection = de.schildbach.wallet.payments.FundingSelectionLock()
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
            // the caller's own exception (debug-mode stack-trace recovery may hand back a copy of it)
            assertTrue(e === boom || e.cause === boom)
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

    /**
     * A config whose [DashPayConfig.CUTOVER_STATE] observations behave as
     * [subscriptions] says, by subscription number. `observe` is modelled as
     * the real `BaseConfig` serves it: a failed read is rescued as an absent
     * key (`null`) and the flow then ends.
     */
    private fun observedConfig(subscriptions: (Int) -> kotlinx.coroutines.flow.Flow<String?>): DashPayConfig {
        val count = java.util.concurrent.atomic.AtomicInteger()
        return mockk {
            coEvery { get(DashPayConfig.CUTOVER_STATE) } returns "CUT_OVER"
            every { observePreservingErrors(DashPayConfig.CUTOVER_STATE) } answers {
                subscriptions(count.getAndIncrement())
            }
            every { observe(DashPayConfig.CUTOVER_STATE) } answers {
                subscriptions(count.getAndIncrement())
                    .catch { e -> if (e is java.io.IOException) emit(null) else throw e }
            }
        }
    }

    private fun cutoverFlowService(config: DashPayConfig) =
        SdkL1SendService(source = readySource(), dashPayConfig = config, isValidAddress = { true }, fundingSelection = de.schildbach.wallet.payments.FundingSelectionLock())

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cutoverCommittedFlow_aFailedObservation_isUnknown_notNotCommitted_andIsRetried() = runTest {
        // PR #1608 review: the first observation of a CUT_OVER install fails.
        // It used to read as "not committed" and end the flow — a send screen
        // then kept that fabricated false and refused every SDK dry run.
        val config = observedConfig { n ->
            if (n == 0) flow { throw java.io.IOException("preferences unreadable") } else flowOf("CUT_OVER")
        }
        val values = cutoverFlowService(config).cutoverCommittedFlow().toList()

        assertEquals("unknown, then the persisted committed state", listOf(null, true), values)
        assertEquals("retried after the first backoff", 1_000L, currentTime)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cutoverCommittedFlow_backsOff_andResetsTheBackoffAfterASuccessfulRead() = runTest {
        // Three failures (1 s, 2 s, 4 s), a success that later fails (the
        // backoff restarts at 1 s), then a success.
        val config = observedConfig { n ->
            when (n) {
                0, 1, 2 -> flow { throw java.io.IOException("preferences unreadable") }
                3 -> flow {
                    emit("CUT_OVER")
                    throw java.io.IOException("preferences unreadable")
                }
                else -> flowOf("CUT_OVER")
            }
        }
        val values = cutoverFlowService(config).cutoverCommittedFlow().toList()

        assertEquals(listOf(null, true, null, true), values)
        assertEquals(1_000L + 2_000L + 4_000L + 1_000L, currentTime)
        assertFalse("a failed read never reads as not committed", values.contains(false))
    }

    @Test
    fun cutoverCommittedOrThrow_anUnreadableState_throws_anAbsentOneIsNotCommitted() = runBlocking {
        val unreadable = cutoverFlowService(observedConfig { flow { throw java.io.IOException("unreadable") } })
        try {
            unreadable.cutoverCommittedOrThrow()
            fail("an unreadable state must not read as an answer")
        } catch (expected: java.io.IOException) {
        }
        assertTrue(cutoverFlowService(observedConfig { flowOf("CUT_OVER") }).cutoverCommittedOrThrow())
        assertTrue(cutoverFlowService(observedConfig { flowOf("SETTLED") }).cutoverCommittedOrThrow())
        assertFalse(cutoverFlowService(observedConfig { flowOf(null) }).cutoverCommittedOrThrow())
        assertFalse(cutoverFlowService(observedConfig { flowOf("DUAL_RUNNING") }).cutoverCommittedOrThrow())
    }

    @Test
    fun cutoverObserveRetryDelay_doublesFromOneSecond_cappedAtThirty() {
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
            listOf(0, 1, 2, 3, 4, 5, 6, 1_000).map { SdkL1SendService.cutoverObserveRetryDelayMs(it) }
        )
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
            l1Progress = { caughtUpProgress() },
            fundingSelection = de.schildbach.wallet.payments.FundingSelectionLock()
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
            // the caller's own exception (debug-mode stack-trace recovery may hand back a copy of it)
            assertTrue(e === boom || e.cause === boom)
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
            svc.buildDeferredMayaDeposit(validAddress, 50_000L, ByteArray(40), isMaxDeposit = true, stillOwned = { true })
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
                .buildDeferredMayaDeposit(validAddress, 50_000L, ByteArray(40), isMaxDeposit = true, stillOwned = { true })
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
        svc.buildDeferredMayaDeposit(validAddress, 50_000L, ByteArray(40), isMaxDeposit = false, stillOwned = { true })
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

    // ── fresh funding vs. a resubmission's reactivation (review 4309551218) ──

    @Test
    fun aFreshSendAuthorizedBeforeAReactivation_neverSelectsTheReactivatedInputs() = runBlocking {
        // The send passed the gate while T1 was still released; a resubmission then reactivated
        // T1 (the gate now refuses). The send must not go on to select T1's inputs.
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        var t1Active = false
        val source = readySource()
        val svc = service(source, unprotectedPendingPayment = { t1Active }, fundingSelection = lock)

        val reactivating = CompletableDeferred<Unit>()
        val reactivated = CompletableDeferred<Unit>()
        val reactivation = async(Dispatchers.Default) {
            lock.tryExclusive {
                reactivating.complete(Unit)
                reactivated.await()
                t1Active = true // the record is active again: fresh funding must be refused
            }
        }
        reactivating.await()
        val send = async(Dispatchers.Default) { svc.sendToAddress(validAddress, Dash(10_000L), emptyWallet = false) }
        delay(200)
        reactivated.complete(Unit)
        reactivation.await()

        val result = send.await()
        assertTrue("refused, not sent: $result", result is SdkWriteResult.NotBroadcast)
        assertEquals("never reached coin selection", 0, source.sendCalls)
    }

    @Test
    fun aSendIsNotBlockedByALongRunningRegistration() = runBlocking {
        // a username registration funds under the same lock and can wait minutes for its asset
        // lock's IS/ChainLock; fresh fundings share the lock and never wait for each other
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        val source = readySource()
        val svc = service(source, fundingSelection = lock)
        val registering = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val registration = async(Dispatchers.Default) {
            lock.withShared {
                registering.complete(Unit)
                finish.await()
            }
        }
        registering.await()

        val result = kotlinx.coroutines.withTimeout(2_000) {
            svc.sendToAddress(validAddress, Dash(10_000L), emptyWallet = false)
        }

        assertTrue("sent while the registration runs: $result", result is SdkWriteResult.Broadcast)
        finish.complete(Unit)
        registration.await()
    }

    // ── a deferred broadcast across a wipe ────────────────────────────────
    //
    // The SDK broadcasts from whichever wallet is bound. A reservation built on one wallet must
    // never be consumed on another, and its broadcast must be serialized with a wipe.

    // ── an immediate send across a wipe: refused, not sent, or sent-or-unknown ──

    @Test
    fun sendToAddress_aWipeUnderWayBeforeTheHold_isNotBroadcast_andNeverReachesTheSdk() = runBlocking {
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        val source = readySource()
        val svc = service(source, fundingSelection = lock)
        lock.raiseWipeBarrier()
        try {
            val result = svc.sendToAddress(validAddress, amount, emptyWallet = false)

            assertTrue("refused before admission: $result", result is SdkWriteResult.NotBroadcast)
            assertEquals(0, source.sendCalls)
        } finally {
            lock.lowerWipeBarrier()
        }
    }

    @Test
    fun sendToAddress_aWipeBeforeTheNativeCall_isNotBroadcast() = runBlocking {
        // cancelled in the call site's pre-send hook, before the SDK was asked to send
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        val source = readySource()
        val svc = service(source, fundingSelection = lock)
        try {
            val result = svc.sendToAddress(validAddress, amount, emptyWallet = false) {
                lock.raiseWipeBarrier()
                kotlinx.coroutines.yield() // the cancellation lands here
            }

            assertTrue("nothing was submitted: $result", result is SdkWriteResult.NotBroadcast)
            assertEquals("never reached the SDK send", 0, source.sendCalls)
        } finally {
            lock.lowerWipeBarrier()
        }
    }

    @Test
    fun sendAll_aWipeWhileWaitingForTheCoreSendLock_isNotBroadcast() = runBlocking {
        // The core-send lock wait is cancellable and can be long: a wipe that cancels it has sent
        // nothing, so it must not be reported as sent-or-unknown.
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        val source = drainReadySource().apply {
            waitForCoreSendLock = {
                lock.raiseWipeBarrier()
                kotlinx.coroutines.awaitCancellation() // still waiting when the wipe cancels the hold
            }
        }
        val svc = service(source, enabled = false, cutoverState = "CUT_OVER", fundingSelection = lock)
        try {
            val result = kotlinx.coroutines.withTimeout(5_000) {
                svc.sendToAddress(validAddress, amount, emptyWallet = true)
            }

            assertTrue("nothing was submitted: $result", result is SdkWriteResult.NotBroadcast)
            assertEquals("never reached the SDK drain", 0, source.sendAllCalls)
        } finally {
            lock.lowerWipeBarrier()
        }
    }

    @Test
    fun coinJoinDrain_aWipeWhileWaitingForTheCoreSendLock_isNotBroadcast() = runBlocking {
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        val source = readySource().apply { onDrainCoinJoin = { _, _, _ -> txid } }
        val svc = consolidationService(source, fundingSelection = lock)
        // Without a wipe the drain goes out: the refusal below is the wipe's, not a gate's.
        assertEquals(SdkWriteResult.Broadcast(txid), svc.drainCoinJoinAccountTo(validAddress))
        source.drainCoinJoinCalls = 0
        source.waitForCoreSendLock = {
            lock.raiseWipeBarrier()
            kotlinx.coroutines.awaitCancellation()
        }
        try {
            val result = kotlinx.coroutines.withTimeout(5_000) { svc.drainCoinJoinAccountTo(validAddress) }

            assertTrue("nothing was submitted: $result", result is SdkWriteResult.NotBroadcast)
            assertEquals("never reached the SDK drain", 0, source.drainCoinJoinCalls)
        } finally {
            lock.lowerWipeBarrier()
        }
    }

    @Test
    fun sendToAddress_aWipeDuringTheNativeCall_isSentOrUnknown_neverACancellation() = runBlocking {
        // the native call submitted, then the wipe cancelled the hold before the txid came back:
        // the payment may be on the network, so it is Ambiguous with a pending (non-retryable)
        // cause, never a CancellationException and never NotBroadcast
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        val source = readySource().apply {
            afterSendSubmitted = {
                lock.raiseWipeBarrier()
                kotlinx.coroutines.yield() // the cancellation lands before the call returns
            }
        }
        val svc = service(source, fundingSelection = lock)
        try {
            val result = svc.sendToAddress(validAddress, amount, emptyWallet = false)

            assertTrue("sent-or-unknown: $result", result is SdkWriteResult.Ambiguous)
            val cause = (result as SdkWriteResult.Ambiguous).cause
            assertTrue(
                "a pending payment, never a cancellation: $cause",
                cause is de.schildbach.wallet.payments.WalletWipedDuringSubmissionException &&
                    cause is org.dash.wallet.common.services.PaymentSubmissionPendingException &&
                    cause !is kotlinx.coroutines.CancellationException
            )
            assertEquals(1, source.sendCalls)
            assertTrue("no bridge for a send the wipe cut short", bridgedTxids.isEmpty())
        } finally {
            lock.lowerWipeBarrier()
        }
    }

    @Test
    fun sendToAddress_aWipeAfterTheNativeCallReturned_keepsTheBroadcast() = runBlocking {
        // the txid came back before the cancellation was noticed: the result stands
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        val source = readySource().apply { afterSendSubmitted = { lock.raiseWipeBarrier() } }
        val svc = service(source, fundingSelection = lock)
        try {
            val result = svc.sendToAddress(validAddress, amount, emptyWallet = false)

            assertEquals(SdkWriteResult.Broadcast(txid), result)
        } finally {
            lock.lowerWipeBarrier()
        }
    }

    @Test
    fun sendToAddress_nestedInACallersHold_passesTheWipeUpWithTheSubmissionMarked() = runBlocking {
        // under a caller's own submission hold the service does not report the wipe itself: the
        // caller, the only one still running afterwards, does, and sees that the send went out
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        val source = readySource().apply {
            afterSendSubmitted = {
                lock.raiseWipeBarrier()
                kotlinx.coroutines.yield()
            }
        }
        val svc = service(source, fundingSelection = lock)
        try {
            val outcome = lock.withSharedSubmission(
                refused = { "refused" },
                cancelled = { _, submitted -> if (submitted) "sent-or-unknown" else "not sent" }
            ) {
                svc.sendToAddress(validAddress, amount, emptyWallet = false)
                "completed"
            }

            assertEquals("sent-or-unknown", outcome)
        } finally {
            lock.lowerWipeBarrier()
        }
    }

    @Test
    fun deferredBroadcast_refusesWhenTheBoundWalletIsNotTheOneItWasBuiltOn() = runBlocking {
        var bound = walletId
        val source = FakeSource(boundWalletId = { bound })
        val svc = service(source)
        val payment = svc.buildDeferredPayment(listOf(validAddress to 10_000L), stillOwned = { true })

        bound = "ef".repeat(32) // a wipe replaced the wallet and bound the new one
        val result = svc.broadcastDeferredPayment(payment) { true }

        assertTrue("broadcast onto another wallet: $result", result is SdkWriteResult.NotBroadcast)
        assertTrue("reached the SDK broadcast", source.broadcastOnWallets.isEmpty())
    }

    @Test
    fun deferredBroadcast_waitsForAWipeUnderWay_andIsThenRefused() = runBlocking {
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        val source = FakeSource(boundWalletId = { walletId })
        val svc = service(source, fundingSelection = lock)
        val payment = svc.buildDeferredPayment(listOf(validAddress to 10_000L), stillOwned = { true })

        val wiping = CompletableDeferred<Unit>()
        val finishWipe = CompletableDeferred<Unit>()
        val wipe = async(Dispatchers.Default) {
            lock.exclusiveForWipe {
                wiping.complete(Unit)
                finishWipe.await()
            }
        }
        wiping.await()
        val broadcast = async(Dispatchers.Default) { svc.broadcastDeferredPayment(payment) { true } }
        delay(200)
        assertTrue("broadcast while the wipe cleared the wallet", source.broadcastOnWallets.isEmpty())
        finishWipe.complete(Unit)
        wipe.await()

        val result = kotlinx.coroutines.withTimeout(2_000) { broadcast.await() }
        assertTrue("a broadcast that waited through a wipe went on: $result", result is SdkWriteResult.NotBroadcast)
        assertTrue(source.broadcastOnWallets.isEmpty())
    }

    @Test
    fun deferredBroadcast_aWipeStartedDuringTheNativeBroadcastTearsNothingDownUntilItReturns() = runBlocking {
        // Reset Wallet as production runs it (WalletWipeSequence.finish with the barrier raised
        // first), started while a native broadcast, deaf to cancellation, is under way: neither the
        // wallet detach nor the SDK teardown nor the verifier's clear may run until it returns.
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        val inNative = java.util.concurrent.CountDownLatch(1)
        val releaseNative = java.util.concurrent.CountDownLatch(1)
        val nativeReturned = java.util.concurrent.atomic.AtomicBoolean(false)
        val source = FakeSource(boundWalletId = { walletId })
        val svc = service(source, fundingSelection = lock)
        val payment = svc.buildDeferredPayment(listOf(validAddress to 10_000L), stillOwned = { true })
        source.onDeferredBroadcast = {
            inNative.countDown()
            releaseNative.await()
            nativeReturned.set(true)
        }
        val broadcast = async(Dispatchers.Default) { svc.broadcastDeferredPayment(payment) { true } }
        assertTrue(inNative.await(5, java.util.concurrent.TimeUnit.SECONDS))

        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        lock.raiseWipeBarrier() // the user confirmed Reset Wallet
        val wipe = async(Dispatchers.Default) {
            de.schildbach.wallet.util.WalletWipeSequence.finish(
                pending = { de.schildbach.wallet.util.WalletWipeState.Pending.PENDING },
                fundingBarrier = lock,
                detachWallet = { events += "detach after native=${nativeReturned.get()}" },
                destroy = {
                    events += "sdk teardown"
                    lock.exclusiveForWipe { events += "verifier clear" }
                },
                markComplete = { events += "complete" }
            )
        }
        // a broadcast asked for now is refused at once, its owner never asked
        var askedOwner = false
        val late = kotlinx.coroutines.withTimeout(2_000) {
            svc.broadcastDeferredPayment(payment) { askedOwner = true; true }
        }
        assertTrue("a broadcast was admitted during the wipe: $late", late is SdkWriteResult.NotBroadcast)
        assertFalse(askedOwner)
        delay(300)
        val tornDownEarly = events.toList()
        releaseNative.countDown() // never left blocked, whatever the assertion below says
        assertTrue("the wipe tore down while the native broadcast ran: $tornDownEarly", tornDownEarly.isEmpty())

        val result = kotlinx.coroutines.withTimeout(5_000) { broadcast.await() }
        assertTrue("the broadcast that went out is reported as such: $result", result is SdkWriteResult.Broadcast)
        assertTrue(kotlinx.coroutines.withTimeout(5_000) { wipe.await() })
        assertEquals(
            listOf("detach after native=true", "sdk teardown", "verifier clear", "complete"),
            events
        )
        assertEquals("one broadcast, the admitted one", listOf(walletId), source.broadcastOnWallets)
        assertFalse(lock.isWipeBarrierRaised)
    }

    @Test
    fun deferredBroadcast_asksTheOwnerUnderTheHold_andRefusesAWipedOne() = runBlocking {
        val source = FakeSource(boundWalletId = { walletId })
        val svc = service(source)
        val payment = svc.buildDeferredPayment(listOf(validAddress to 10_000L), stillOwned = { true })

        val result = svc.broadcastDeferredPayment(payment) { false }

        assertTrue("broadcast for a wiped wallet: $result", result is SdkWriteResult.NotBroadcast)
        assertTrue(source.broadcastOnWallets.isEmpty())
    }

    @Test
    fun deferredBroadcast_broadcastsOnTheWalletItWasBuiltOn() = runBlocking {
        val source = FakeSource(boundWalletId = { walletId })
        val svc = service(source)
        val payment = svc.buildDeferredPayment(listOf(validAddress to 10_000L), stillOwned = { true })

        val result = svc.broadcastDeferredPayment(payment) { true }

        assertTrue("$result", result is SdkWriteResult.Broadcast)
        assertEquals(listOf(walletId), source.broadcastOnWallets)
    }

    @Test
    fun deferredBroadcast_neverBroadcastsAPaymentWithNoRecordedWallet() = runBlocking {
        val source = FakeSource(boundWalletId = { walletId })
        val payment = SdkDeferredPayment("dd".repeat(32), ByteArray(0), 226L, null)

        val result = service(source).broadcastDeferredPayment(payment) { true }

        assertTrue("$result", result is SdkWriteResult.NotBroadcast)
        assertTrue(source.broadcastOnWallets.isEmpty())
    }

    @Test
    fun deferredBroadcast_insideAHeldLock_passesThrough_evenWithAWipeWaiting() = runBlocking {
        // a caller that already holds the lock (shared) must neither deadlock nor be counted twice
        val lock = de.schildbach.wallet.payments.FundingSelectionLock()
        val source = FakeSource(boundWalletId = { walletId })
        val svc = service(source, fundingSelection = lock)
        val payment = svc.buildDeferredPayment(listOf(validAddress to 10_000L), stillOwned = { true })

        val result = kotlinx.coroutines.withTimeout(2_000) {
            lock.withShared { svc.broadcastDeferredPayment(payment) { true } }
        }
        assertTrue("$result", result is SdkWriteResult.Broadcast)
        // released once: a wipe now runs at once
        assertEquals("wiped", kotlinx.coroutines.withTimeout(2_000) { lock.exclusiveForWipe { "wiped" } })
    }

    @Test
    fun deferredBuild_asksTheOwnerUnderTheHold_andBuildsNothingForAWipedOne() = runBlocking {
        val source = FakeSource(boundWalletId = { walletId })
        try {
            service(source).buildDeferredPayment(listOf(validAddress to 10_000L), stillOwned = { false })
            fail("built for a wiped wallet")
        } catch (expected: IllegalStateException) {
            // refused
        }
        assertEquals(0, source.deferredBuildCalls)
    }

    @Test
    fun sendToAddress_refusesWhenTheBoundWalletChangedBeforeTheHold() = runBlocking {
        var bound = walletId
        val source = readySource().apply { boundWalletId = { bound } }
        val svc = service(source)

        // the wallet is swapped after the preflight read the bound wallet, before the send
        val result = svc.sendToAddress(validAddress, amount, emptyWallet = false) { bound = "ef".repeat(32) }

        assertTrue("sent from another wallet: $result", result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.sendCalls)
    }

    @Test
    fun deferredRelease_neverReleasesOnAnotherWallet() = runBlocking {
        var bound = walletId
        val source = FakeSource(boundWalletId = { bound })
        val svc = service(source)
        val payment = svc.buildDeferredPayment(listOf(validAddress to 10_000L), stillOwned = { true })

        bound = "ef".repeat(32)
        svc.releaseDeferredPayment(payment)

        assertTrue("released on another wallet: ${source.releasedOnWallets}", source.releasedOnWallets.isEmpty())
    }

    // ── unprotected pending payment (review 4245734665) ────────────────────
    //
    // After a restart an uncertain BIP70 payment's inputs are locked only on the dashj side, and
    // SDK coin selection cannot see those locks. Every SDK-funded build must refuse while such a
    // payment is outstanding.

    @Test
    fun sendToAddress_refusesWhileAPendingPaymentIsUnprotected() = runBlocking {
        val source = readySource()
        val svc = service(source, unprotectedPendingPayment = { true })

        val result = svc.sendToAddress(validAddress, amount, emptyWallet = false)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertTrue(
            (result as SdkWriteResult.NotBroadcast).reason.contains(L1_PENDING_PAYMENT_UNPROTECTED_REASON)
        )
        assertEquals("nothing may be built while the old inputs are exposed", 0, source.sendCalls)
    }

    @Test
    fun sendToAddress_proceedsOnceNoPendingPaymentIsUnprotected() = runBlocking {
        val source = readySource()
        val svc = service(source, unprotectedPendingPayment = { false })

        val result = svc.sendToAddress(validAddress, amount, emptyWallet = false)

        assertTrue(result is SdkWriteResult.Broadcast)
    }

    @Test
    fun buildDeferredPayment_refusesWhileAPendingPaymentIsUnprotected() = runBlocking {
        val svc = service(readySource(), unprotectedPendingPayment = { true })

        try {
            svc.buildDeferredPayment(listOf(validAddress to 10_000L), stillOwned = { true })
            fail("a deferred build went ahead while a pending payment's inputs were exposed")
        } catch (e: org.dash.wallet.common.services.PaymentStillConfirmingException) {
            assertTrue(e.message!!.isNotEmpty())
        }
        Unit
    }

    @Test
    fun aRefusedSendSurfacesAsPaymentStillConfirming() {
        val e = de.schildbach.wallet.payments.sdkSendNotAttemptedException(
            "sendToAddress: $L1_PENDING_PAYMENT_UNPROTECTED_REASON"
        )
        assertTrue(e is org.dash.wallet.common.services.PaymentStillConfirmingException)
    }

    @Test
    fun aDeferredBuildRecordsALowerBoundOfTheHeightItWasReservedAt() = runBlocking {
        // The pending-payment gate measures the reservation's remaining life in blocks from
        // here, so it must never be above the engine's processed height (review 2a8b1c7c057e).
        val source = mayaSource(spendable = 1_000_000L, feeDuffs = 500L)
        // within the funding gate's tolerance, with the cursors a little apart
        val behind = caughtUpProgress().copy(filterHeight = 1_499_999, walletSyncedHeight = 1_499_998)
        val payment = service(source, progress = { behind })
            .buildDeferredMayaDeposit(validAddress, 50_000L, ByteArray(40), stillOwned = { true })

        assertEquals("the committed wallet cursor", 1_499_998L, payment.reservedAtHeight)
    }

    @Test
    fun aDeferredBuildWithNoCommittedCursor_isRefusedBeforeReserving() = runBlocking {
        // Review 28775c57d545: header and filter cursors at the tip say nothing about how far the
        // engine has processed (that can be 100 blocks behind), so they must never stand in for
        // the reservation height. Since #1632 an unknown cursor does not get that far: the funding
        // gate refuses it ("wallet scan cursor is not known yet") before anything is reserved, so
        // no reservation is ever stamped from an unknown cursor. reservationHeightLowerBound()'s
        // null stays as the defence behind the gate.
        val source = mayaSource(spendable = 1_000_000L, feeDuffs = 500L)
        val noCommittedCursor = caughtUpProgress().copy(walletSyncedHeight = 0)
        val refused = try {
            service(source, progress = { noCommittedCursor })
                .buildDeferredMayaDeposit(validAddress, 50_000L, ByteArray(40), stillOwned = { true })
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertNotNull("the gate refuses an unknown cursor", refused)
        assertTrue(refused!!.message, refused.message!!.contains("cursor is not known"))
        assertEquals("nothing was reserved", 0, source.mayaBuildCalls)
    }

    @Test
    fun aDeferredBuildWithNoKnownHeightRecordsNone() = runBlocking {
        val source = mayaSource(spendable = 1_000_000L, feeDuffs = 500L)
        val unknown = caughtUpProgress().copy(headerHeight = 0, filterHeight = 0, walletSyncedHeight = 0)
        val payment = try {
            service(source, progress = { unknown }).buildDeferredMayaDeposit(validAddress, 50_000L, ByteArray(40), stillOwned = { true })
        } catch (e: IllegalStateException) {
            // the funding gate may refuse an engine with no heights at all, which is fine too
            return@runBlocking
        }
        assertNull(payment.reservedAtHeight)
    }

    @Test
    fun coinJoinDrain_refusesWhileAPendingPaymentIsUnprotected() = runBlocking {
        // The drain selects fresh transparent inputs too (review 1b15e950af15). The FakeSource
        // has no drain; reaching it would throw UnsupportedOperationException, which this
        // refusal must come before.
        val svc = service(readySource(), unprotectedPendingPayment = { true })

        val result = svc.drainCoinJoinAccountTo(validAddress)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertTrue(isPaymentStillConfirmingReason((result as SdkWriteResult.NotBroadcast).reason))
    }
}
