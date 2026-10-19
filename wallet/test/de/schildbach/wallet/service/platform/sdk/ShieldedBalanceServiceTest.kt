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
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.money.Dash
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashfoundation.dashsdk.persistence.entities.ShieldedActivityEntity
import org.dashfoundation.dashsdk.persistence.entities.ShieldedNoteEntity
import org.dashfoundation.dashsdk.persistence.entities.ShieldedOutgoingNoteEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Host-JVM tests for the Phase 4 shielded-balances service layer: the
 * flag/lifecycle decision table (inert-when-off, single-flight bring-up,
 * latch/retry), the write orchestration under the [SdkWriteResult]
 * no-double-broadcast contract, and the pure credits/activity/address
 * mapping with fixture data. No native calls; the SDK shielded surface is
 * faked via [ShieldedSource].
 */
class ShieldedBalanceServiceTest {

    private val walletIdHex = "ab".repeat(32)
    private val walletIdBytes = ByteArray(32) { 0xab.toByte() }
    private val hrp = "tdash"
    private val dbPath = "/data/files/shielded_tree_testnet.sqlite"

    /** A valid Orchard recipient for transfer tests. */
    private val recipientRaw43 = ByteArray(43) { (it + 1).toByte() }
    private val recipientAddress: String = encodeOrchardAddress(recipientRaw43, hrp)!!

    private class FakeSource(
        var hasSupport: Boolean = true,
        var boundWalletId: () -> String? = { null },
        var syncRunning: Boolean = false
    ) : ShieldedSource {
        val events = mutableListOf<String>()
        var supportCalls = 0
        var configureCalls = 0
        var bindCalls = 0
        var startCalls = 0
        var stopCalls = 0
        var warmUpCalls = 0
        var broadcastCalls = 0
        var lastDbPath: String? = null
        var lastBoundWallet: ByteArray? = null
        var lastBoundAccounts: List<Int>? = null
        var lastAmountCredits: Long? = null
        var lastRecipientRaw43: ByteArray? = null
        var lastMemo: String? = null
        var lastPlatformTarget: String? = null
        var lastCoreAddress: String? = null
        var lastCoreFeePerByte: Int? = null

        var onConfigure: (String) -> Unit = {}
        var onBind: () -> Unit = {}
        /** Suspending bind hook: a bring-up parked inside the native bind (plan §37). */
        var onBindSuspend: suspend () -> Unit = {}
        var onStart: () -> Unit = {}
        var onShield: () -> Unit = {}
        var onTransfer: () -> Unit = {}
        var onUnshield: () -> Unit = {}
        var onWithdraw: () -> Unit = {}

        var defaultAddress: ByteArray? = ByteArray(43) { 7 }
        var platformAddress: () -> String? = { "tdash1ownplatformaddress" }

        val notesFlow = MutableStateFlow<List<ShieldedNoteEntity>>(emptyList())
        val activityFlow = MutableStateFlow<List<ShieldedActivityEntity>>(emptyList())

        fun interactions() = supportCalls + configureCalls + bindCalls + startCalls +
            stopCalls + warmUpCalls + broadcastCalls

        /** Suspending hook inside the support probe — the SDK bootstrap interval (review 2026-09-23). */
        var onSupportSuspend: suspend () -> Unit = {}

        override suspend fun hasShieldedSupport(): Boolean {
            supportCalls++
            events += "support"
            onSupportSuspend()
            return hasSupport
        }

        override suspend fun boundWalletIdOrNull(): String? {
            events += "bound"
            return boundWalletId()
        }

        override suspend fun configureShielded(dbPath: String) {
            configureCalls++
            lastDbPath = dbPath
            events += "configure"
            onConfigure(dbPath)
        }

        override suspend fun bindShielded(walletId: ByteArray, accounts: List<Int>) {
            bindCalls++
            lastBoundWallet = walletId
            lastBoundAccounts = accounts
            events += "bind"
            onBind()
            onBindSuspend()
        }

        override suspend fun isShieldedSyncRunning(): Boolean {
            events += "isRunning"
            return syncRunning
        }

        /** Pass-in-flight signal for the sync-status poller (loop-independent). */
        var syncing = false
        override suspend fun isShieldedSyncing(): Boolean = syncing

        /** Suspending hook inside the native sync-loop start (review 2026-09-23). */
        var onStartSuspend: suspend () -> Unit = {}

        /** Whether the simulated native loop is running: set when a start begins, cleared when a stop COMPLETES. */
        var nativeLoopRunning = false

        override suspend fun startShieldedSync() {
            startCalls++
            events += "start"
            nativeLoopRunning = true
            onStart()
            onStartSuspend()
        }

        /** Suspending hook inside the native loop stop — an owed-stop drain that takes a while (review 2026-09-28). */
        var onStopSuspend: suspend () -> Unit = {}

        override suspend fun stopShieldedSync() {
            stopCalls++
            events += "stop"
            onStopSuspend()
            nativeLoopRunning = false
        }

        var syncNowCalls = 0

        /** Suspending hook inside syncShieldedNow — the real one blocks for a whole network pass. */
        var onSyncNowSuspend: suspend () -> Unit = {}

        override suspend fun syncShieldedNow() {
            syncNowCalls++
            events += "syncNow"
            onSyncNowSuspend()
        }

        /** Completed-pass results the test emits (the SDK's WalletSyncEvent.ShieldedResult). */
        val passResults = MutableSharedFlow<ShieldedPassResult>(extraBufferCapacity = 64)

        override fun shieldedSyncResults(): Flow<ShieldedPassResult> = passResults

        /** Suspending hook inside the prover warm-up — the last suspend before ready (review 2026-09-27). */
        var onWarmUpSuspend: suspend () -> Unit = {}

        override suspend fun warmUpProver() {
            warmUpCalls++
            events += "warmUp"
            onWarmUpSuspend()
        }

        override fun observeUnspentNotes(walletId: ByteArray): Flow<List<ShieldedNoteEntity>> = notesFlow

        /** Anchored unspent notes returned by the funding-note anchor gate read. */
        var anchoredNotes: List<ShieldedNoteEntity> = emptyList()
        override suspend fun unspentAnchoredNotes(walletId: ByteArray): List<ShieldedNoteEntity> = anchoredNotes

        override suspend fun minUnspentAnchoredBlockHeight(walletId: ByteArray): Long? =
            anchoredNotes.filter { it.blockHeight > 0 }.minOfOrNull { it.blockHeight }

        /** When set, reading the activity store throws it (an unreadable Room store). */
        @Volatile
        var activityFailure: Throwable? = null

        /** Spent notes still in Room (`isSpent = true`); with [notesFlow] they make up every note. */
        val spentNotesFlow = MutableStateFlow<List<ShieldedNoteEntity>>(emptyList())
        val outgoingFlow = MutableStateFlow<List<ShieldedOutgoingNoteEntity>>(emptyList())

        /** When set, reading the outgoing-note store throws it. */
        @Volatile
        var outgoingFailure: Throwable? = null

        override fun observeAllNotes(walletId: ByteArray): Flow<List<ShieldedNoteEntity>> =
            combine(notesFlow, spentNotesFlow) { unspent, spent -> unspent + spent }

        override fun observeOutgoingNotes(walletId: ByteArray): Flow<List<ShieldedOutgoingNoteEntity>> =
            outgoingFlow.onStart { outgoingFailure?.let { throw it } }

        override fun observeActivity(walletId: ByteArray): Flow<List<ShieldedActivityEntity>> =
            activityFlow.onStart { activityFailure?.let { throw it } }

        override suspend fun shieldedDefaultAddress(walletId: ByteArray): ByteArray? = defaultAddress

        override suspend fun ownPlatformAddressOrNull(walletId: ByteArray): String? = platformAddress()

        override suspend fun shield(walletId: ByteArray, amountCredits: Long) {
            broadcastCalls++
            lastAmountCredits = amountCredits
            onShield()
        }

        override suspend fun transfer(
            walletId: ByteArray,
            recipientRaw43: ByteArray,
            amountCredits: Long,
            memo: String?
        ) {
            broadcastCalls++
            lastRecipientRaw43 = recipientRaw43
            lastAmountCredits = amountCredits
            lastMemo = memo
            onTransfer()
        }

        override suspend fun unshield(walletId: ByteArray, toPlatformAddress: String, amountCredits: Long) {
            broadcastCalls++
            lastPlatformTarget = toPlatformAddress
            lastAmountCredits = amountCredits
            onUnshield()
        }

        override suspend fun withdraw(
            walletId: ByteArray,
            toCoreAddress: String,
            amountCredits: Long,
            coreFeePerByte: Int
        ) {
            broadcastCalls++
            lastCoreAddress = toCoreAddress
            lastAmountCredits = amountCredits
            lastCoreFeePerByte = coreFeePerByte
            onWithdraw()
        }

        // ── shieldFromWallet / staged-retry surface ────────────────────

        var fundCalls = 0
        var resumeCalls = 0
        var lockQueries = 0
        var lastFundRecipient: ByteArray? = null
        var lastFundAmountDuffs: Long? = null
        val resumedOutPoints = mutableListOf<Pair<ByteArray, Int>>()
        var lastResumeRecipient: ByteArray? = null
        var onFund: () -> Unit = {}
        var onResume: (ByteArray, Int) -> Unit = { _, _ -> }
        var shieldLocks: () -> List<PendingWalletShieldLock> = { emptyList() }

        /** 10k duffs of flat shielded fee by default. */
        var feeCredits: () -> Long = { 10_000_000L }

        override suspend fun fundFromAssetLock(
            walletId: ByteArray,
            recipientRaw43: ByteArray,
            amountDuffs: Long
        ) {
            fundCalls++
            broadcastCalls++
            lastFundRecipient = recipientRaw43
            lastFundAmountDuffs = amountDuffs
            onFund()
        }

        var accountFundCalls = 0

        override suspend fun fundFromAssetLockFromAccount(
            walletId: ByteArray,
            recipientRaw43: ByteArray,
            amountDuffs: Long,
            fundingPath: String
        ) {
            accountFundCalls++
            broadcastCalls++
            lastFundRecipient = recipientRaw43
            lastFundAmountDuffs = amountDuffs
            onFund()
        }

        override suspend fun resumeFundFromAssetLock(
            walletId: ByteArray,
            outPointTxid: ByteArray,
            outPointVout: Int,
            recipientRaw43: ByteArray
        ) {
            resumeCalls++
            broadcastCalls++
            resumedOutPoints += outPointTxid to outPointVout
            lastResumeRecipient = recipientRaw43
            onResume(outPointTxid, outPointVout)
        }

        /** Suspending hook inside the sweep's lock query — a sweep in flight (review 2026-09-28). */
        var onShieldLocksSuspend: suspend () -> Unit = {}

        override suspend fun walletShieldLocks(walletId: ByteArray): List<PendingWalletShieldLock> {
            lockQueries++
            events += "locks"
            onShieldLocksSuspend()
            return shieldLocks()
        }

        override suspend fun estimateShieldFeeCredits(): Long = feeCredits()
    }

    private fun config(
        enabled: Boolean?,
        l1ShadowEnabled: Boolean = true,
        lastBalanceDuffs: Long? = null,
        consumed: ConsumedStore = ConsumedStore()
    ): DashPayConfig = mockk {
        if (enabled == null) {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) } throws
                IllegalStateException("datastore unavailable")
        } else {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) } returns enabled
        }
        coEvery { get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) } returns l1ShadowEnabled
        coEvery { getLastShieldedBalanceDuffs() } returns lastBalanceDuffs
        coEvery { markShieldedSpendReconcilePending(any()) } just Runs
        coEvery { shieldedSpendReconcilePendingSinceMs() } returns null
        coEvery { clearShieldedSpendReconcilePending() } just Runs
        coEvery { setLastShieldedBalanceDuffsUnlessReconciling(any(), any()) } returns true
        every { lastShieldedBalanceCacheEpoch() } returns 0L
        coEvery { getPreservingErrors(DashPayConfig.SHIELD_LOCKS_PLATFORM_CONSUMED) } answers {
            consumed.readFailure?.let { throw it }
            consumed.value
        }
        coEvery { set(DashPayConfig.SHIELD_LOCKS_PLATFORM_CONSUMED, any()) } answers { consumed.value = secondArg() }
    }

    /** Durable stand-in for [DashPayConfig.SHIELD_LOCKS_PLATFORM_CONSUMED]; share one to model a restart. */
    private class ConsumedStore {
        var value: Set<String>? = null
        var readFailure: Throwable? = null
    }

    /** A source in the fully-ready state: shielded build, wallet bound. */
    private fun readySource() = FakeSource(
        hasSupport = true,
        boundWalletId = { walletIdHex }
    )

    /**
     * The open L1 funding gate: the SDK filter scan has caught up to the
     * chain tip. Phase is deliberately NOT SYNCED — this is the device's
     * stuck-but-fundable state (a live shadow perpetually chasing the tip
     * never latches SYNCED, see the never-SYNCED root cause), proving the
     * gate opens on caught-up alone, without phase==SYNCED and without any
     * dashj parity.
     */
    private fun caughtUpProgress() = ShadowSyncProgress(
        phase = ShadowSyncPhase.FILTERS,
        // The SDK's three-phase mean; 1.0 is honest for a fixture at the tip.
        // Since 2026-09-21 the gate READS it (the iOS aggregate rule), so a
        // fixture that is behind on heights must lower this too.
        overallPercent = 1.0,
        headerHeight = 1_500_000,
        headerTarget = 1_500_000,
        filterHeight = 1_500_000,
        filterTarget = 1_500_000
    )

    private fun service(
        source: FakeSource,
        enabled: Boolean? = true,
        l1ShadowEnabled: Boolean = true,
        lastBalanceDuffs: Long? = null,
        consumed: ConsumedStore = ConsumedStore(),
        progress: () -> ShadowSyncProgress = { ShadowSyncProgress.IDLE },
        progressFlow: Flow<ShadowSyncProgress> = flowOf(progress()),
        ensureL1SpvRunning: suspend () -> Boolean = { true },
        noteSelfSpendBroadcast: () -> Unit = {},
        hasAppLockedCoinJoinOutputs: () -> Boolean = { false },
        stopLockTimeoutMs: Long = ShieldedBalanceServiceImpl.STOP_LOCK_TIMEOUT_MS,
        afterReadyCommitted: suspend () -> Unit = {},
        afterStopFallbackBump: suspend () -> Unit = {}
    ) = ShieldedBalanceServiceImpl(
        source = source,
        dashPayConfig = config(enabled, l1ShadowEnabled, lastBalanceDuffs, consumed),
        shieldedDbPath = { dbPath },
        displayHrp = { hrp },
        l1Progress = progress,
        l1ProgressFlow = { progressFlow },
        ensureL1SpvRunning = ensureL1SpvRunning,
        noteSelfSpendBroadcast = noteSelfSpendBroadcast,
        hasAppLockedCoinJoinOutputs = hasAppLockedCoinJoinOutputs,
        stopLockTimeoutMs = stopLockTimeoutMs,
        afterReadyCommitted = afterReadyCommitted,
        afterStopFallbackBump = afterStopFallbackBump
    )

    // ── Plan §37: stop() must not wait forever on a bring-up parked in the native bind ──

    /**
     * Andrei, 2026-09-22 (SM-A536B, `12000017`): after a blockchain reset the
     * next bring-up entered `bindShielded` and the SDK parked it behind a
     * resumed wallet shield waiting indefinitely for a ChainLock. `stop()` then
     * waited on the bring-up lock forever, the blockchain service's onDestroy
     * never finished, and every later start refused itself for four hours.
     *
     * Pins the bound: with the bind parked, stop() returns within its timeout,
     * tears the Kotlin side down, and does NOT try to stop a sync loop that was
     * never started. When the SDK finally returns the bind, the abandoned
     * bring-up reports NOT ready and starts nothing; a fresh bring-up then
     * binds again and succeeds.
     */
    @Test
    fun stop_returnsWithinItsBound_whenTheBringUpIsParkedInTheNativeBind() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val source = readySource().apply { onBindSuspend = { gate.await() } }
        val service = service(source, stopLockTimeoutMs = 200)

        val bringUp = async(Dispatchers.Default) { service.ensureShieldedReady() }
        withTimeout(5_000) { while (source.bindCalls == 0) delay(10) }
        assertTrue("the bring-up is parked inside the bind", bringUp.isActive)

        val startedNs = System.nanoTime()
        withTimeout(5_000) { service.stop() }
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        assertTrue("stop() returned in ${elapsedMs}ms, well inside the 5 s the service gives a cleanup", elapsedMs < 4_000)
        assertEquals("nothing to stop natively: the loop is only started after the bind", 0, source.stopCalls)
        assertEquals(ShieldedSyncStatus.NOT_READY, service.shieldedSyncStatus.value)

        // The SDK returns the bind after the stop: the bring-up must be inert.
        gate.complete(Unit)
        assertFalse("an abandoned bring-up does not report ready", bringUp.await())
        assertEquals("…and does not start the sync loop", 0, source.startCalls)

        // The lock is free again; a fresh bring-up succeeds end to end.
        assertTrue(service.ensureShieldedReady())
        assertEquals(2, source.bindCalls)
        assertEquals(1, source.startCalls)
    }

    /**
     * Review, 2026-09-23: the generation guard covered configure/bind only. A
     * bring-up past that check could be parked in the native sync-loop start
     * when stop() timed out and tore the Kotlin side down; on return it
     * published ready and started the collectors over the teardown. The
     * fence now re-checks after the loop start and, when superseded, stops
     * the loop it started and declines.
     */
    @Test
    fun stop_supersedesABringUpParkedInTheNativeLoopStart_whichThenStopsTheLoopItStarted() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val source = readySource().apply { onStartSuspend = { gate.await() } }
        val service = service(source, stopLockTimeoutMs = 200)

        val bringUp = async(Dispatchers.Default) { service.ensureShieldedReady() }
        withTimeout(5_000) { while (source.startCalls == 0) delay(10) }
        assertEquals(1, source.bindCalls)

        withTimeout(5_000) { service.stop() } // times out on the lock, tears the Kotlin side down
        assertEquals(ShieldedSyncStatus.NOT_READY, service.shieldedSyncStatus.value)

        gate.complete(Unit) // the native start returns into a superseded bring-up
        assertFalse("superseded: not ready", bringUp.await())
        assertEquals("…and the loop it started is stopped", 1, source.stopCalls)

        // Fresh bring-up afterwards is whole again.
        assertTrue(service.ensureShieldedReady())
        assertEquals(2, source.bindCalls)
        assertEquals(2, source.startCalls)
    }

    /**
     * Review, 2026-09-23: hasShieldedSupport()/boundWalletIdOrNull() call
     * ensureStarted(), which can bootstrap the SDK. A stop() in that interval
     * used to be absorbed by a generation snapshot taken later; the snapshot
     * is now taken first, so the bring-up aborts before it ever binds.
     */
    @Test
    fun stop_duringTheSdkBootstrapInterval_abortsTheBringUpBeforeItBinds() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val source = readySource().apply { onSupportSuspend = { gate.await() } }
        val service = service(source, stopLockTimeoutMs = 200)

        val bringUp = async(Dispatchers.Default) { service.ensureShieldedReady() }
        withTimeout(5_000) { while (source.supportCalls == 0) delay(10) }

        withTimeout(5_000) { service.stop() } // times out; the bring-up holds the lock inside the probe
        gate.complete(Unit)

        assertFalse(bringUp.await())
        assertEquals("never reached the bind", 0, source.bindCalls)
        assertEquals(0, source.startCalls)
        assertEquals(0, source.stopCalls)
    }

    /**
     * Review, 2026-09-24: the two-bring-up handoff. Bring-up #1 holds the lock
     * inside the native bind; #2 is queued on the lock; stop() bumps the
     * generation and starts polling. #1 returns superseded and releases; the
     * queued #2 wins the handoff over stop's tryLock and snapshots the
     * generation stop already produced; #2 then parks in ITS native bind and
     * stop times out. Without a second bump in the fallback, #2's snapshot
     * still matched and it went on to start the loop and publish ready over
     * the teardown.
     */
    @Test
    fun stop_fallbackAlsoSupersedesASecondBringUpThatTookTheLockDuringItsPoll() = runBlocking {
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        var binds = 0
        val source = readySource().apply { onBindSuspend = { if (++binds == 1) gate1.await() else gate2.await() } }
        // stop() must still be POLLING when gate1 opens (~150 ms in). If it
        // timed out first, #2 would sample the generation after both bumps and
        // legitimately start — a scheduler-timing failure, not a bug. 2 s gives
        // a slow CI runner an order of magnitude of margin (review, 2026-09-28).
        val service = service(source, stopLockTimeoutMs = 2_000)

        val first = async(Dispatchers.Default) { service.ensureShieldedReady() }
        withTimeout(5_000) { while (source.bindCalls == 0) delay(10) } // #1 inside its bind
        val second = async(Dispatchers.Default) { service.ensureShieldedReady() } // #2 queued on the lock
        delay(50)
        val stop = async(Dispatchers.Default) { service.stop() } // polling for the lock
        delay(100)

        gate1.complete(Unit) // #1 returns superseded; the queued #2 takes the lock…
        withTimeout(5_000) { while (source.bindCalls < 2) delay(10) } // …and parks in its own bind
        assertFalse(first.await())
        withTimeout(5_000) { stop.await() } // times out on #2; fallback tears down
        assertEquals(ShieldedSyncStatus.NOT_READY, service.shieldedSyncStatus.value)

        gate2.complete(Unit) // #2's native bind returns
        assertFalse("#2 snapshotted a generation the fallback has since moved past", second.await())
        assertEquals("no loop was ever started", 0, source.startCalls)

        // A genuinely new bring-up after the stop is whole.
        assertTrue(service.ensureShieldedReady())
        assertEquals(3, source.bindCalls)
        assertEquals(1, source.startCalls)
    }

    /**
     * Review, 2026-09-27: warmUpProver() is a suspend call AFTER the last
     * generation check. A stop() that timed out on the lock while it was
     * outstanding tore the Kotlin side down, and when warm-up returned the
     * bring-up published ready anyway, leaving the native loop running and
     * letting ensureShieldedReady() restart the collectors and the sweep over
     * the teardown. The fence now re-checks after warm-up: not ready, and the
     * loop it started is stopped.
     */
    @Test
    fun stop_supersedesABringUpParkedInTheProverWarmUp_whichThenStopsTheLoopAndDeclines() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var warmUps = 0
        val source = readySource().apply { onWarmUpSuspend = { if (++warmUps == 1) gate.await() } }
        val service = service(source, stopLockTimeoutMs = 200)

        val bringUp = async(Dispatchers.Default) { service.ensureShieldedReady() }
        withTimeout(5_000) { while (source.warmUpCalls == 0) delay(10) }
        assertEquals("the loop was started before the warm-up", 1, source.startCalls)
        assertTrue("the bring-up is parked inside the warm-up", bringUp.isActive)

        // Times out on the lock and runs its fallback to completion BEFORE warm-up returns.
        withTimeout(5_000) { service.stop() }
        assertEquals("the fallback cannot reach the loop", 0, source.stopCalls)
        assertEquals(ShieldedSyncStatus.NOT_READY, service.shieldedSyncStatus.value)

        gate.complete(Unit) // warm-up returns into a superseded bring-up
        assertFalse("superseded during warm-up: not ready", bringUp.await())
        assertEquals("…and the loop it started is stopped", 1, source.stopCalls)
        assertEquals(ShieldedSyncStatus.NOT_READY, service.shieldedSyncStatus.value)

        // A later stop has nothing ready and touches the SDK no further.
        service.stop()
        assertEquals(1, source.stopCalls)

        // A fresh bring-up afterwards is whole again.
        assertTrue(service.ensureShieldedReady())
        assertEquals(2, source.bindCalls)
        assertEquals(2, source.startCalls)
        assertEquals(2, source.warmUpCalls)
    }

    /**
     * Review, 2026-09-27 (second pass): no generation check can close the
     * window AFTER the last one. A bring-up that published ready and passed
     * its final verify can still stall holding the lock (a slow log write);
     * a stop() that times out then clears the latch but cannot reach the
     * native loop, and before the fix nothing owed that loop a stop — later
     * stops skipped native cleanup because the latch was already null. The
     * fallback now records the stop as owed and the holder pays it on release.
     */
    @Test
    fun stop_fallbackAfterTheFinalCheck_leavesTheNativeStopOwed_andTheReleasingBringUpPaysIt() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var parked = false
        var commits = 0
        val source = readySource()
        val service = service(source, stopLockTimeoutMs = 200, afterReadyCommitted = {
            if (++commits == 1) {
                parked = true
                gate.await()
            }
        })

        val bringUp = async(Dispatchers.Default) { service.ensureShieldedReady() }
        withTimeout(5_000) { while (!parked) delay(10) }
        assertEquals("the loop is running before the stall", 1, source.startCalls)

        // Times out on the lock and completes BEFORE the bring-up resumes.
        withTimeout(5_000) { service.stop() }
        assertEquals("the fallback cannot reach the loop itself", 0, source.stopCalls)
        assertEquals(ShieldedSyncStatus.NOT_READY, service.shieldedSyncStatus.value)

        gate.complete(Unit) // the stalled bring-up resumes past every check
        assertFalse("a bring-up whose stop was paid on release does not report ready", bringUp.await())
        assertEquals("…and the native loop it started is stopped", 1, source.stopCalls)
        assertEquals(ShieldedSyncStatus.NOT_READY, service.shieldedSyncStatus.value)

        // The debt is paid once: a later stop has nothing to do.
        service.stop()
        assertEquals(1, source.stopCalls)

        // A fresh bring-up afterwards is whole and stays up.
        assertTrue(service.ensureShieldedReady())
        assertEquals(2, source.startCalls)
        assertEquals(1, source.stopCalls)
        service.stop()
        assertEquals("an uncontended stop still stops the loop", 2, source.stopCalls)
    }

    /**
     * Review, 2026-09-28: a queued bring-up sampled its generation only AFTER
     * paying an owed stop, and that payment suspends in the native stop. A
     * second stop that timed out during the drain was absorbed: the bring-up
     * sampled its bumped generation, bound, started a replacement loop,
     * published ready, paid the second debt on release — stopping that loop —
     * and still returned true over the teardown. The sample now precedes the
     * drain, so the pass sees it was superseded and never binds.
     */
    @Test
    fun stop_timingOutDuringAQueuedBringUpsOwedStopDrain_supersedesThatBringUp() = runBlocking {
        val commitGate = CompletableDeferred<Unit>()
        val stopGate = CompletableDeferred<Unit>()
        var parked = false
        var commits = 0
        var stops = 0
        val source = readySource().apply { onStopSuspend = { if (++stops == 1) stopGate.await() } }
        val service = service(source, stopLockTimeoutMs = 200, afterReadyCommitted = {
            if (++commits == 1) {
                parked = true
                commitGate.await()
            }
        })

        // #1 holds the lock past its last check; stop A times out and leaves #1's loop stop owed.
        val first = async(Dispatchers.Default) { service.ensureShieldedReady() }
        withTimeout(5_000) { while (!parked) delay(10) }
        withTimeout(5_000) { service.stop() }
        assertEquals(0, source.stopCalls)

        // #2 queues on the lock. UNDISPATCHED runs it synchronously up to its
        // first suspension, which is lock.lock() — so it is provably queued
        // before #1 releases, and Mutex.unlock() hands the lock straight to it.
        val second = async(start = CoroutineStart.UNDISPATCHED) { service.ensureShieldedReady() }
        assertTrue(second.isActive)

        commitGate.complete(Unit) // #1 resumes; its release hands the lock to #2, which pays A's debt…
        withTimeout(5_000) { while (source.stopCalls == 0) delay(10) } // …and suspends in that native stop
        assertFalse("#1 was superseded by stop A", first.await())

        withTimeout(5_000) { service.stop() } // stop B times out against #2's drain
        stopGate.complete(Unit)

        assertFalse("#2 sampled before the drain, so stop B supersedes it", second.await())
        assertEquals("#2 never bound", 1, source.bindCalls)
        assertEquals("…never started a replacement loop", 1, source.startCalls)
        assertEquals("only #1's loop was stopped", 1, source.stopCalls)
        assertEquals(ShieldedSyncStatus.NOT_READY, service.shieldedSyncStatus.value)

        // A fresh bring-up afterwards is whole.
        assertTrue(service.ensureShieldedReady())
        assertEquals(2, source.startCalls)
    }

    /**
     * Review, 2026-09-28 (second pass): the fallback's Kotlin teardown used no
     * ownership check. If it paused after its final bump, a bring-up that
     * sampled after that bump could bind, start and publish; the teardown
     * then cleared that NEWER runtime's latch while the generation-scoped
     * debt rightly spared its loop — a loop running with no readiness. The
     * teardown is now scoped to runtimes older than the stop, atomically with
     * publication.
     */
    @Test
    fun stop_fallbackPausedAfterItsBump_leavesANewerBringUpsReadinessAndLoopIntact() = runBlocking {
        val bindGate = CompletableDeferred<Unit>()
        val fallbackGate = CompletableDeferred<Unit>()
        var binds = 0
        var fallbackParked = false
        val source = readySource().apply { onBindSuspend = { if (++binds == 1) bindGate.await() } }
        val service = service(source, stopLockTimeoutMs = 200, afterStopFallbackBump = {
            fallbackParked = true
            fallbackGate.await()
        })

        val first = async(Dispatchers.Default) { service.ensureShieldedReady() }
        withTimeout(5_000) { while (source.bindCalls == 0) delay(10) } // #1 parked in its bind
        val stop = async(Dispatchers.Default) { service.stop() }
        withTimeout(5_000) { while (!fallbackParked) delay(10) } // timed out, bumped, paused before teardown

        bindGate.complete(Unit)
        assertFalse("#1 is older than the stop", first.await())

        // A bring-up that sampled after the stop's final bump completes in the pause.
        assertTrue(service.ensureShieldedReady())
        assertEquals(2, source.bindCalls)
        assertEquals(1, source.startCalls)

        fallbackGate.complete(Unit) // the paused fallback resumes its teardown
        withTimeout(5_000) { stop.await() }

        // The newer runtime is untouched: still ready (no re-bind), loop not stopped.
        assertTrue(service.ensureShieldedReady())
        assertEquals("still ready: no re-bind", 2, source.bindCalls)
        assertEquals("its loop was not stopped", 0, source.stopCalls)

        // An ordinary stop still takes it down.
        service.stop()
        assertEquals(1, source.stopCalls)
    }

    /**
     * Review, 2026-09-28 (third pass): a queued bring-up pays an owed stop in
     * its CALLER's context. Cancelled there before the native stop ran — the
     * SDK dispatches it through withContext(Dispatchers.IO), which a
     * cancelled caller skips — the debt and the loop ownership were both
     * already cleared, so every later stop() returned early with the native
     * loop still running. The payment is now non-cancellable and the markers
     * are cleared only after it.
     */
    @Test
    fun cancellingAQueuedBringUpDuringItsOwedStopDrain_stillStopsTheNativeLoop() = runBlocking {
        val commitGate = CompletableDeferred<Unit>()
        val stopGate = CompletableDeferred<Unit>()
        var parked = false
        var commits = 0
        var stops = 0
        val source = readySource().apply {
            onStopSuspend = { if (++stops == 1) stopGate.await() }
            // The real SDK calls suspend on IO and observe cancellation; the fake's must too.
            onSupportSuspend = { yield() }
        }
        val service = service(source, stopLockTimeoutMs = 200, afterReadyCommitted = {
            if (++commits == 1) {
                parked = true
                commitGate.await()
            }
        })

        // #1 is past its last check with the loop running; stop A times out and records the debt.
        val first = async(Dispatchers.Default) { service.ensureShieldedReady() }
        withTimeout(5_000) { while (!parked) delay(10) }
        withTimeout(5_000) { service.stop() }
        assertTrue(source.nativeLoopRunning)

        // #2 is queued on the lock (UNDISPATCHED: provably before #1 releases) and so pays the debt.
        val second = async(start = CoroutineStart.UNDISPATCHED) { service.ensureShieldedReady() }
        commitGate.complete(Unit)
        withTimeout(5_000) { while (source.stopCalls == 0) delay(10) } // #2 is inside the native stop
        first.await()

        second.cancel() // the caller's scope goes away mid-drain
        stopGate.complete(Unit)
        second.join()

        assertFalse("the native loop was stopped despite the cancellation", source.nativeLoopRunning)
        assertEquals(1, source.stopCalls)

        // Nothing left owed or owned: a later stop has nothing to do, and a fresh bring-up is whole.
        service.stop()
        assertEquals(1, source.stopCalls)
        assertTrue(service.ensureShieldedReady())
        assertTrue(source.nativeLoopRunning)
    }

    /**
     * Review, 2026-09-28 (adversarial pass): a queued bring-up cancelled AFTER
     * the lock was handed to it. Mutex's prompt-cancellation path releases the
     * lock itself, skipping releaseLock(), and the previous holder's handoff
     * had already lost its tryLock to that handoff — so a debt recorded by a
     * timed-out stop sat unpaid with the lock free and the old loop running.
     */
    @Test
    fun aQueuedBringUpCancelledAfterTheLockHandoff_stillPaysTheOwedStop() = runBlocking {
        val commitGate = CompletableDeferred<Unit>()
        var parked = false
        var commits = 0
        val source = readySource()
        val service = service(source, stopLockTimeoutMs = 200, afterReadyCommitted = {
            if (++commits == 1) {
                parked = true
                commitGate.await()
            }
        })

        val first = async(Dispatchers.Default) { service.ensureShieldedReady() }
        withTimeout(5_000) { while (!parked) delay(10) }
        withTimeout(5_000) { service.stop() } // times out: the debt against #1's loop
        assertTrue(source.nativeLoopRunning)

        // #2 queues on the lock; it resumes on THIS thread's event loop, which
        // stays blocked below, so the handoff lands but #2 cannot run yet.
        val second = async(start = CoroutineStart.UNDISPATCHED) { service.ensureShieldedReady() }
        commitGate.complete(Unit)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!first.isCompleted && System.nanoTime() < deadline) Thread.sleep(5) // blocking on purpose
        assertTrue("#1 released: the lock is now #2's", first.isCompleted)

        second.cancel()
        second.join()

        assertFalse("the owed stop was paid on the cancelled acquisition", source.nativeLoopRunning)
        assertEquals(1, source.stopCalls)
    }

    /**
     * Review, 2026-09-28 (adversarial pass): the sweep was launched after the
     * lock was released and never tracked, so a sweep in flight when a stop
     * ran went on to resume wallet shields after that stop. Teardown now
     * cancels it.
     */
    @Test
    fun stop_cancelsAPendingWalletShieldSweepInFlight() = runBlocking {
        val sweepGate = CompletableDeferred<Unit>()
        val sweepFinished = CompletableDeferred<Unit>()
        var sweepEntered = false
        var sweepContinuedAfterStop = false
        val source = readySource().apply {
            onShieldLocksSuspend = {
                sweepEntered = true
                try {
                    sweepGate.await()
                    sweepContinuedAfterStop = true
                } finally {
                    // Signalled on BOTH outcomes, so the assertion below waits
                    // for the sweep to end instead of for a fixed delay.
                    sweepFinished.complete(Unit)
                }
            }
        }
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = ShieldedBalanceServiceImpl(
                source = source,
                dashPayConfig = config(true),
                shieldedDbPath = { dbPath },
                displayHrp = { hrp },
                sweepScope = sweepScope
            )
            assertTrue(service.ensureShieldedReady())
            withTimeout(5_000) { while (!sweepEntered) delay(10) }

            service.stop()
            sweepGate.complete(Unit)
            withTimeout(5_000) { sweepFinished.await() }

            assertFalse("the sweep did not outlive the stop", sweepContinuedAfterStop)
        } finally {
            sweepScope.cancel()
        }
    }

    /** A failed native stop keeps the loop's ownership, so the next stop() retries instead of returning early. */
    @Test
    fun aFailedNativeStop_isRetriedByTheNextStop() = runBlocking {
        var failNext = true
        val source = readySource().apply {
            onStopSuspend = { if (failNext) { failNext = false; throw IllegalStateException("sdk stop failed") } }
        }
        val service = service(source)
        assertTrue(service.ensureShieldedReady())

        service.stop()
        assertTrue("the failed stop left the loop running", source.nativeLoopRunning)

        service.stop()
        assertEquals("retried", 2, source.stopCalls)
        assertFalse(source.nativeLoopRunning)
    }

    /**
     * A loop the service started must be stopped by the next stop() even when
     * the ready latch was never published. Here the native start throws after
     * the loop is already up; before the ownership flag, stop() saw a null
     * latch and returned without touching the SDK, leaving the loop orphaned.
     */
    @Test
    fun stop_stopsALoopFromAFailedBringUp_evenWithTheLatchNeverPublished() = runBlocking {
        val source = readySource().apply { onStart = { throw IllegalStateException("start failed after the loop began") } }
        val service = service(source)

        assertFalse(service.ensureShieldedReady())
        assertEquals(1, source.startCalls)
        assertEquals(0, source.stopCalls)

        service.stop()
        assertEquals("the orphaned loop is stopped", 1, source.stopCalls)
        service.stop()
        assertEquals("…once", 1, source.stopCalls)
    }

    /** The uncontended path is unchanged: stop under the lock, and the native loop IS stopped. */
    @Test
    fun stop_uncontended_stillStopsTheNativeLoopUnderTheLock() = runBlocking {
        val source = readySource()
        val service = service(source, stopLockTimeoutMs = 200)
        assertTrue(service.ensureShieldedReady())
        assertEquals(1, source.startCalls)

        service.stop()

        assertEquals(1, source.stopCalls)
        assertEquals(ShieldedSyncStatus.NOT_READY, service.shieldedSyncStatus.value)
        // Idempotent: a second stop with nothing ready touches the SDK no further.
        service.stop()
        assertEquals(1, source.stopCalls)
    }

    // ── Inertness: flag off means NOTHING touches the SDK ─────────────────

    @Test
    fun flagOff_everyEntryPoint_isInert() = runBlocking {
        val source = readySource()
        val service = service(source, enabled = false)

        assertFalse(service.ensureShieldedReady())
        assertEquals(Dash.ZERO, service.observeShieldedBalance().first())
        assertTrue(service.observeShieldedActivity().first().isEmpty())
        assertNull(service.shieldedReceiveAddress())
        assertTrue(service.shieldFromCredits(Dash.COIN) is SdkWriteResult.NotBroadcast)
        assertTrue(
            service.transferShielded(recipientAddress, Dash.COIN, null) is SdkWriteResult.NotBroadcast
        )
        assertTrue(service.unshieldToCredits(Dash.COIN) is SdkWriteResult.NotBroadcast)
        assertTrue(service.withdrawToCore("XyZ", Dash.COIN) is SdkWriteResult.NotBroadcast)
        assertTrue(service.shieldFromWallet(Dash.COIN) is SdkWriteResult.NotBroadcast)
        assertFalse(service.isWalletShieldingAvailable())
        assertEquals(0, service.resumePendingWalletShields())
        service.stop()

        assertEquals(0, source.interactions())
        assertTrue(source.events.isEmpty())
    }

    @Test
    fun flagReadFailure_isTreatedAsOff() = runBlocking {
        val source = readySource()
        val service = service(source, enabled = null)

        assertFalse(service.ensureShieldedReady())
        assertTrue(service.shieldFromCredits(Dash.COIN) is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.interactions())
    }

    // ── Lifecycle decision table ──────────────────────────────────────────

    @Test
    fun ensureReady_noShieldedSupport_isFalse_withoutConfigureOrBind() = runBlocking {
        val source = FakeSource(hasSupport = false, boundWalletId = { walletIdHex })
        assertFalse(service(source).ensureShieldedReady())
        assertEquals(0, source.configureCalls + source.bindCalls + source.startCalls)
    }

    @Test
    fun ensureReady_walletNotBound_isFalse_withoutConfigureOrBind() = runBlocking {
        val source = FakeSource(hasSupport = true, boundWalletId = { null })
        assertFalse(service(source).ensureShieldedReady())
        assertEquals(0, source.configureCalls + source.bindCalls + source.startCalls)
    }

    @Test
    fun ensureReady_happyPath_configuresBindsStartsWarms_inOrder() = runBlocking {
        val source = readySource()
        assertTrue(service(source).ensureShieldedReady())

        assertEquals(
            listOf("support", "bound", "configure", "bind", "isRunning", "start", "warmUp"),
            source.events
        )
        assertEquals(dbPath, source.lastDbPath)
        assertArrayEquals(walletIdBytes, source.lastBoundWallet)
        assertEquals(listOf(0), source.lastBoundAccounts)
    }

    @Test
    fun ensureReady_syncAlreadyRunning_doesNotStartAgain() = runBlocking {
        val source = readySource().apply { syncRunning = true }
        assertTrue(service(source).ensureShieldedReady())
        assertEquals(0, source.startCalls)
    }

    @Test
    fun ensureReady_secondCall_latches_withoutRepeatingBringUp() = runBlocking {
        val source = readySource()
        val service = service(source)

        assertTrue(service.ensureShieldedReady())
        assertTrue(service.ensureShieldedReady())

        assertEquals(1, source.configureCalls)
        assertEquals(1, source.bindCalls)
        assertEquals(1, source.startCalls)
    }

    @Test
    fun ensureReady_configureFailure_isFalse_andRetrySucceeds() = runBlocking {
        val source = readySource()
        var failNext = true
        source.onConfigure = { if (failNext) throw DashSdkError.InvalidState("db path locked") }
        val service = service(source)

        assertFalse(service.ensureShieldedReady())
        assertEquals(0, source.bindCalls)

        failNext = false
        assertTrue(service.ensureShieldedReady())
        assertEquals(2, source.configureCalls)
        assertEquals(1, source.bindCalls)
        assertEquals(1, source.startCalls)
    }

    @Test
    fun ensureReady_warmUpFailure_doesNotBlockReadiness() = runBlocking {
        val source = readySource()
        var warmUpFailed = false
        val service = ShieldedBalanceServiceImpl(
            source = object : ShieldedSource by source {
                override suspend fun warmUpProver() {
                    warmUpFailed = true
                    throw DashSdkError.InternalError("prover init failed")
                }
            },
            dashPayConfig = config(true),
            shieldedDbPath = { dbPath },
            displayHrp = { hrp }
        )
        assertTrue(service.ensureShieldedReady())
        assertTrue(warmUpFailed)
    }

    @Test
    fun stop_stopsSyncAndDropsLatch_thenRebindWorks() = runBlocking {
        val source = readySource()
        val service = service(source)

        assertTrue(service.ensureShieldedReady())
        service.stop()
        assertEquals(1, source.stopCalls)
        assertEquals(Dash.ZERO, service.observeShieldedBalance().first())

        assertTrue(service.ensureShieldedReady())
        assertEquals(2, source.configureCalls)
        assertEquals(2, source.bindCalls)
    }

    @Test
    fun stop_whenNeverReady_isNoOp() = runBlocking {
        val source = readySource()
        service(source).stop()
        assertEquals(0, source.stopCalls)
    }

    // ── Reads: balance + activity flows ───────────────────────────────────

    @Test
    fun balanceFlow_zeroBeforeReady_thenSumsUnspentNotesFloored() = runBlocking {
        val source = readySource()
        val service = service(source)

        assertEquals(Dash.ZERO, service.observeShieldedBalance().first())

        source.notesFlow.value = listOf(
            note(value = 150_000_000_000L), // 1.5 DASH in credits
            note(value = 1_999L) // sub-duff dust: floors to 1 duff
        )
        assertTrue(service.ensureShieldedReady())

        // 150_000_001_999 credits / 1000 = 150_000_001 duffs.
        assertEquals(Dash(150_000_001L), service.observeShieldedBalance().first())
    }

    @Test
    fun activityFlow_emptyBeforeReady_thenMapsSortsAndFilters() = runBlocking {
        val source = readySource()
        val service = service(source)

        assertTrue(service.observeShieldedActivity().first().isEmpty())

        source.activityFlow.value = listOf(
            activity(entryId = 1, direction = 1, status = 1, amount = 2_000L, createdAtMs = 100L),
            activity(
                entryId = 2, direction = 0, status = 0, amount = 5_000L, createdAtMs = 300L,
                memo = textMemo("thanks!")
            ),
            activity(entryId = 3, direction = 2, status = 1, amount = 1_000L, createdAtMs = 200L),
            // Filtered: failed status.
            activity(entryId = 4, direction = 0, status = 2, amount = 9_000L, createdAtMs = 400L),
            // Filtered: unknown direction tag.
            activity(entryId = 5, direction = 9, status = 1, amount = 9_000L, createdAtMs = 500L)
        )
        assertTrue(service.ensureShieldedReady())

        val entries = service.observeShieldedActivity().first()
        assertEquals(3, entries.size)
        // Newest first.
        assertEquals(listOf(300L, 200L, 100L), entries.map { it.timestampMs })

        val received = entries[0]
        assertEquals(ShieldedActivityDirection.IN, received.direction)
        assertEquals(Dash(5L), received.amount)
        assertEquals("thanks!", received.memo)
        assertTrue(received.pending)
        assertEquals("02" + "00".repeat(31), received.id)

        val internal = entries[1]
        assertEquals(ShieldedActivityDirection.INTERNAL, internal.direction)
        assertFalse(internal.pending)
        assertNull(internal.memo)

        assertEquals(ShieldedActivityDirection.OUT, entries[2].direction)
    }

    // ── Receive address ───────────────────────────────────────────────────

    @Test
    fun receiveAddress_encodesDefaultOrchardAddress_roundTrip() = runBlocking {
        val source = readySource().apply { defaultAddress = recipientRaw43 }
        val address = service(source).shieldedReceiveAddress()

        assertNotNull(address)
        assertTrue(address!!.startsWith("tdash1"))
        assertArrayEquals(recipientRaw43, decodeOrchardAddress(address, hrp))
    }

    @Test
    fun receiveAddress_nullWhenNoBoundShieldedSubWallet() = runBlocking {
        val source = readySource().apply { defaultAddress = null }
        assertNull(service(source).shieldedReceiveAddress())
    }

    @Test
    fun receiveAddress_nullWhenNotReady() = runBlocking {
        val source = FakeSource(hasSupport = true, boundWalletId = { null })
        assertNull(service(source).shieldedReceiveAddress())
    }

    // ── Writes: preflights are NotBroadcast without a broadcast attempt ───

    @Test
    fun writes_nonPositiveAmount_isNotBroadcast_withoutSourceCalls() = runBlocking {
        val source = readySource()
        val service = service(source)

        assertTrue(service.shieldFromCredits(Dash.ZERO) is SdkWriteResult.NotBroadcast)
        assertTrue(service.unshieldToCredits(Dash(-1)) is SdkWriteResult.NotBroadcast)
        assertTrue(
            service.transferShielded(recipientAddress, Dash.ZERO, null) is SdkWriteResult.NotBroadcast
        )
        assertEquals(0, source.interactions())
    }

    @Test
    fun writes_runtimeNotReady_isNotBroadcast_withoutBroadcastAttempt() = runBlocking {
        val source = FakeSource(hasSupport = true, boundWalletId = { null })
        val result = service(source).shieldFromCredits(Dash.COIN)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.broadcastCalls)
    }

    @Test
    fun write_autoRunsBringUp_whenNotReadyYet() = runBlocking {
        val source = readySource()
        val result = service(source).shieldFromCredits(Dash.COIN)

        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals(1, source.configureCalls)
        assertEquals(1, source.bindCalls)
        // 1 DASH = 1e8 duffs = 1e11 credits.
        assertEquals(100_000_000_000L, source.lastAmountCredits)
    }

    @Test
    fun transfer_malformedOrWrongNetworkAddress_isNotBroadcast_withoutSourceCalls() = runBlocking {
        val source = readySource()
        val service = service(source)

        // Garbage, a wrong-HRP (mainnet) encoding, and a platform-style
        // payload under the right HRP must all be rejected app-side.
        val mainnetAddress = encodeOrchardAddress(recipientRaw43, "dash")!!
        val platformPayload = Bech32m.encode(hrp, ByteArray(21) { 0xb0.toByte() })!!

        for (bad in listOf("not-an-address", mainnetAddress, platformPayload)) {
            assertTrue(
                service.transferShielded(bad, Dash.COIN, null) is SdkWriteResult.NotBroadcast
            )
        }
        assertEquals(0, source.interactions())
    }

    @Test
    fun transfer_memoOverLimit_isNotBroadcast_withoutSourceCalls() = runBlocking {
        val source = readySource()
        val result = service(source)
            .transferShielded(recipientAddress, Dash.COIN, "x".repeat(MAX_SHIELDED_MEMO_BYTES + 1))

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.interactions())
    }

    @Test
    fun transfer_success_mapsAddressAmountAndMemo() = runBlocking {
        val source = readySource()
        val memo = "m".repeat(MAX_SHIELDED_MEMO_BYTES) // exactly at the limit
        val result = service(source).transferShielded(recipientAddress, Dash(250), " $memo ")

        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals(1, source.broadcastCalls)
        assertArrayEquals(recipientRaw43, source.lastRecipientRaw43)
        assertEquals(250_000L, source.lastAmountCredits)
        assertEquals(memo, source.lastMemo)
    }

    @Test
    fun transfer_blankMemo_isSentAsNull() = runBlocking {
        val source = readySource()
        assertTrue(
            service(source).transferShielded(recipientAddress, Dash.COIN, "  ")
                is SdkWriteResult.Broadcast
        )
        assertNull(source.lastMemo)
    }

    @Test
    fun unshield_targetsOwnPlatformAddress() = runBlocking {
        val source = readySource().apply { platformAddress = { "tdash1qown" } }
        val result = service(source).unshieldToCredits(Dash(7))

        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals("tdash1qown", source.lastPlatformTarget)
        assertEquals(7_000L, source.lastAmountCredits)
    }

    @Test
    fun unshield_noPlatformAddress_isNotBroadcast_withoutBroadcastAttempt() = runBlocking {
        val source = readySource().apply { platformAddress = { null } }
        val result = service(source).unshieldToCredits(Dash.COIN)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.broadcastCalls)
    }

    @Test
    fun unshield_platformAddressLookupFailure_isNotBroadcast() = runBlocking {
        val source = readySource().apply {
            platformAddress = { throw DashSdkError.InternalError("room read failed") }
        }
        val result = service(source).unshieldToCredits(Dash.COIN)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.broadcastCalls)
    }

    @Test
    fun withdraw_emptyAddress_isNotBroadcast_withoutSourceCalls() = runBlocking {
        val source = readySource()
        val result = service(source).withdrawToCore("   ", Dash.COIN)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.interactions())
    }

    @Test
    fun withdraw_success_pinsFibonacciCoreFee() = runBlocking {
        val source = readySource()
        val result = service(source).withdrawToCore(" yWithdrawTarget ", Dash(3))

        assertTrue(result is SdkWriteResult.Broadcast)
        assertEquals("yWithdrawTarget", source.lastCoreAddress)
        assertEquals(3_000L, source.lastAmountCredits)
        assertEquals(1, source.lastCoreFeePerByte)
    }

    // ── Writes: broadcast-failure classification ──────────────────────────

    @Test
    fun spendUnconfirmed_isAmbiguous_neverRetried() = runBlocking {
        val source = readySource().apply {
            onTransfer = { throw DashSdkError.PlatformWallet.ShieldedSpendUnconfirmed("unconfirmed") }
        }
        val cfg = config(true)
        val service = ShieldedBalanceServiceImpl(
            source = source,
            dashPayConfig = cfg,
            shieldedDbPath = { dbPath },
            displayHrp = { hrp }
        )
        val result = service.transferShielded(recipientAddress, Dash.COIN, null)

        assertTrue(result is SdkWriteResult.Ambiguous)
        assertEquals(1, source.broadcastCalls)
        // The spend MAY have landed while its reserved notes still count as
        // unspent: reconcile like a broadcast spend, durably.
        assertTrue(service.shieldedBalanceMaybeStale.value)
        coVerify(exactly = 1) { cfg.markShieldedSpendReconcilePending(any()) }
    }

    /**
     * Cancelled once the SDK spend call started (thepastaclaw, c99eefff0): it
     * may have broadcast, so the reconciliation is recorded durably before the
     * cancellation propagates — a restart must not trust the pre-spend cache.
     */
    @Test
    fun aSpendCancelledMidCall_isReconciledDurably() = runBlocking {
        val source = readySource().apply {
            onTransfer = { throw CancellationException("cancelled mid-proof") }
        }
        val cfg = config(true)
        val service = ShieldedBalanceServiceImpl(
            source = source,
            dashPayConfig = cfg,
            shieldedDbPath = { dbPath },
            displayHrp = { hrp }
        )
        try {
            service.transferShielded(recipientAddress, Dash.COIN, null)
            fail("the cancellation must propagate")
        } catch (expected: CancellationException) {
            // propagated
        }

        assertTrue(service.shieldedBalanceMaybeStale.value)
        coVerify(exactly = 1) { cfg.markShieldedSpendReconcilePending(any()) }
    }

    @Test
    fun definitiveNotBroadcast_isNotMarkedStale() = runBlocking {
        val source = readySource().apply {
            onShield = { throw DashSdkError.PlatformWallet.ShieldedBroadcastFailed("relay rejected") }
        }
        val cfg = config(true)
        val service = ShieldedBalanceServiceImpl(
            source = source,
            dashPayConfig = cfg,
            shieldedDbPath = { dbPath },
            displayHrp = { hrp }
        )
        assertTrue(service.shieldFromCredits(Dash.COIN) is SdkWriteResult.NotBroadcast)
        assertFalse(service.shieldedBalanceMaybeStale.value)
        coVerify(exactly = 0) { cfg.markShieldedSpendReconcilePending(any()) }
    }

    @Test
    fun definitiveNonExecutionCodes_areNotBroadcast() = runBlocking {
        val outcomes = listOf<() -> Unit>(
            { throw DashSdkError.PlatformWallet.ShieldedBroadcastFailed("relay rejected") },
            { throw DashSdkError.PlatformWallet.ShieldedNoRecordedAnchor("tree mid-block") },
            { throw DashSdkError.InvalidParameter("bad amount") }
        )
        for (outcome in outcomes) {
            val source = readySource().apply { onShield = outcome }
            val result = service(source).shieldFromCredits(Dash.COIN)
            assertTrue(result is SdkWriteResult.NotBroadcast)
            assertEquals(1, source.broadcastCalls)
        }
    }

    @Test
    fun unprovableFailures_areAmbiguous() = runBlocking {
        val outcomes = listOf<() -> Unit>(
            { throw DashSdkError.NetworkError("conn dropped mid-submit") },
            { throw DashSdkError.Timeout("dapi timeout") },
            { throw RuntimeException("JNI blew up") }
        )
        for (outcome in outcomes) {
            val source = readySource().apply { onWithdraw = outcome }
            val result = service(source).withdrawToCore("yTarget", Dash.COIN)
            assertTrue(result is SdkWriteResult.Ambiguous)
            assertEquals(1, source.broadcastCalls)
        }
    }

    @Test
    fun everyOutcome_makesAtMostOneBroadcastAttempt() = runBlocking {
        val outcomes = listOf<() -> Unit>(
            { },
            { throw DashSdkError.InvalidParameter("x") },
            { throw DashSdkError.PlatformWallet.ShieldedSpendUnconfirmed("x") },
            { throw RuntimeException("x") }
        )
        for (outcome in outcomes) {
            val source = readySource().apply { onShield = outcome }
            service(source).shieldFromCredits(Dash.COIN)
            assertEquals(1, source.broadcastCalls)
        }
    }

    // ── Pure mapping: sync status ─────────────────────────────────────────

    @Test
    fun syncStatus_mapping_decisionTable() {
        // Not ready dominates regardless of the other inputs.
        assertEquals(
            ShieldedSyncStatus.NOT_READY,
            mapShieldedSyncStatus(ready = false, firstPassCompleted = false, passInFlight = false)
        )
        assertEquals(
            ShieldedSyncStatus.NOT_READY,
            mapShieldedSyncStatus(ready = false, firstPassCompleted = true, passInFlight = true)
        )
        // Ready but the first pass has not finished → SYNCING, so the
        // placeholder zero is never shown as a real balance.
        assertEquals(
            ShieldedSyncStatus.SYNCING,
            mapShieldedSyncStatus(ready = true, firstPassCompleted = false, passInFlight = false)
        )
        assertEquals(
            ShieldedSyncStatus.SYNCING,
            mapShieldedSyncStatus(ready = true, firstPassCompleted = false, passInFlight = true)
        )
        // A ROUTINE pass in flight after the first completed does NOT demote
        // READY: the persisted note store stays trustworthy through it, and
        // the old SYNCING arm made every 60s background pass flicker every
        // READY-gated surface (the username button, live on the S21).
        assertEquals(
            ShieldedSyncStatus.READY,
            mapShieldedSyncStatus(ready = true, firstPassCompleted = true, passInFlight = true)
        )
        // Ready, first pass done, nothing in flight → the balance is trustworthy.
        assertEquals(
            ShieldedSyncStatus.READY,
            mapShieldedSyncStatus(ready = true, firstPassCompleted = true, passInFlight = false)
        )
    }

    // ── Last-known balance cache + staleness signal ───────────────────────

    @Test
    fun lastKnownShieldedBalance_readsPersistedDuffs_orNullWhenUnset() = runBlocking {
        assertNull(service(readySource(), lastBalanceDuffs = null).lastKnownShieldedBalance())
        assertEquals(
            Dash(150_000_000L),
            service(readySource(), lastBalanceDuffs = 150_000_000L).lastKnownShieldedBalance()
        )
    }

    @Test
    fun lastKnownShieldedBalance_configReadFailure_isNull() = runBlocking {
        val cfg: DashPayConfig = mockk {
            coEvery { getLastShieldedBalanceDuffs() } throws IllegalStateException("datastore down")
        }
        val service = ShieldedBalanceServiceImpl(
            source = readySource(),
            dashPayConfig = cfg,
            shieldedDbPath = { dbPath },
            displayHrp = { hrp }
        )
        assertNull(service.lastKnownShieldedBalance())
    }

    @Test
    fun shieldedBalanceMaybeStale_startsFalse_andIsSetByASuccessfulSpend() = runBlocking {
        val source = readySource()
        val service = service(source)

        assertFalse(service.shieldedBalanceMaybeStale.value)
        assertTrue(service.transferShielded(recipientAddress, Dash.COIN, null) is SdkWriteResult.Broadcast)
        // A successful local spend marks the last-known balance stale so the
        // card shows "Syncing…" rather than the now-stale amount until the
        // next completed pass (the poller, which runs only with a sweep scope).
        assertTrue(service.shieldedBalanceMaybeStale.value)
    }

    @Test
    fun successfulSpend_durablyInvalidatesTheLastKnownBalance() = runBlocking {
        // spend → runtime stop / process restart: the in-memory stale flag is
        // gone, so the persisted pre-spend balance must already be cleared —
        // a new process then reads no cache (unknown) until a settled READY
        // balance is persisted again.
        val cfg = config(true, lastBalanceDuffs = 1_000_000_000L)
        val source = readySource()
        val service = ShieldedBalanceServiceImpl(
            source = source,
            dashPayConfig = cfg,
            shieldedDbPath = { dbPath },
            displayHrp = { hrp }
        )
        val before = System.currentTimeMillis()
        var calledAt = 0L
        source.onTransfer = { calledAt = System.currentTimeMillis() }
        assertTrue(service.transferShielded(recipientAddress, Dash.COIN, null) is SdkWriteResult.Broadcast)
        // the since was taken before the SDK spend call (less the skew allowance)
        val skew = ShieldedBalanceServiceImpl.SPEND_WINDOW_SKEW_MS
        coVerify(exactly = 1) { cfg.markShieldedSpendReconcilePending(match { it in (before - skew)..(calledAt - skew) }) }
    }

    /**
     * spend → restart → first READY: the persisted reconcile marker restores
     * the settlement guard before READY is published, so the first READY pass
     * is neither trusted (stale stays set) nor persisted as the last-known
     * balance — the settlement still needs two stable completed passes.
     */
    @Test
    fun afterRestart_aPendingReconcile_keepsTheFirstReadyStale_andUnpersisted() = runBlocking {
        val cfg = config(true)
        coEvery { cfg.shieldedSpendReconcilePendingSinceMs() } returns 0L
        coEvery { cfg.setLastShieldedBalanceDuffsUnlessReconciling(any(), any()) } returns true
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            // a fresh service = a new process: the in-memory flag starts false
            val service = ShieldedBalanceServiceImpl(
                source = readySource(),
                dashPayConfig = cfg,
                shieldedDbPath = { dbPath },
                displayHrp = { hrp },
                sweepScope = sweepScope
            )
            assertFalse(service.shieldedBalanceMaybeStale.value)
            assertTrue(service.ensureShieldedReady())

            withTimeout(10_000) {
                while (service.shieldedSyncStatus.value != ShieldedSyncStatus.READY) delay(50)
            }
            assertTrue("first READY after restart is still reconciling", service.shieldedBalanceMaybeStale.value)
            coVerify(exactly = 0) { cfg.setLastShieldedBalanceDuffsUnlessReconciling(any(), any()) }
            coVerify(exactly = 0) { cfg.clearShieldedSpendReconcilePending() }
        } finally {
            sweepScope.cancel()
        }
    }

    /**
     * Settlement of an earlier reconciliation pauses while clearing its durable
     * marker; a new spend arrives in that window. The older settlement must not
     * clear the newer spend's flag or marker: after both finish the newer
     * reconciliation is still pending, and its marker was written AFTER the
     * older clear.
     */
    @Test
    fun anOlderSettlement_doesNotClearANewerSpendsReconciliation() = runBlocking {
        val cfg = config(true)
        // the earlier reconciliation (restored at bring-up, as after a restart)
        coEvery { cfg.shieldedSpendReconcilePendingSinceMs() } returns 0L
        coEvery { cfg.setLastShieldedBalanceDuffsUnlessReconciling(any(), any()) } returns true
        val clearEntered = CompletableDeferred<Unit>()
        val clearGate = CompletableDeferred<Unit>()
        coEvery { cfg.clearShieldedSpendReconcilePending() } coAnswers {
            clearEntered.complete(Unit)
            clearGate.await()
        }
        val source = readySource()
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = ShieldedBalanceServiceImpl(
                source = source,
                dashPayConfig = cfg,
                shieldedDbPath = { dbPath },
                displayHrp = { hrp },
                sweepScope = sweepScope
            )
            assertTrue(service.ensureShieldedReady())
            withTimeout(5_000) { while (!service.shieldedBalanceMaybeStale.value) delay(20) }

            // Two successful completed passes with an unchanged balance: the
            // older settlement reaches its marker clear and pauses there.
            val pollMs = ShieldedBalanceServiceImpl.SYNC_STATUS_POLL_INTERVAL_MS
            withTimeout(10_000) {
                while (!clearEntered.isCompleted) {
                    source.passResults.emit(passOk())
                    delay(50)
                }
            }

            // A newer spend lands while the older settlement is paused.
            val spend = async(Dispatchers.Default) {
                service.transferShielded(recipientAddress, Dash.COIN, null)
            }
            delay(pollMs)
            clearGate.complete(Unit)
            assertTrue(withTimeout(5_000) { spend.await() } is SdkWriteResult.Broadcast)

            assertTrue("the newer spend is still reconciling", service.shieldedBalanceMaybeStale.value)
            coVerifyOrder {
                cfg.clearShieldedSpendReconcilePending()
                cfg.markShieldedSpendReconcilePending(any())
            }
        } finally {
            source.syncing = false
            sweepScope.cancel()
        }
    }

    private fun boundWalletId(): ByteArray = checkNotNull(walletIdFromHex(walletIdHex))

    private fun passOk(credits: Long = 0L, walletId: ByteArray = boundWalletId()) =
        ShieldedPassResult(walletId, ShieldedPassResult.Outcome.SUCCEEDED, credits)

    private fun passFailed() =
        ShieldedPassResult(boundWalletId(), ShieldedPassResult.Outcome.FAILED, 0L)

    private fun passSkipped(credits: Long = 0L) =
        ShieldedPassResult(boundWalletId(), ShieldedPassResult.Outcome.SKIPPED, credits)

    /**
     * A scoped service with a restored pending reconciliation (stale = true),
     * returned once its settlement collector is subscribed to the pass results.
     * [sinceMs] is the restored since (0 = every activity row counts).
     */
    private suspend fun reconcilingService(
        source: FakeSource,
        cfg: DashPayConfig,
        sweepScope: CoroutineScope,
        sinceMs: Long = 0L,
        clock: () -> Long = System::currentTimeMillis
    ): ShieldedBalanceServiceImpl {
        coEvery { cfg.shieldedSpendReconcilePendingSinceMs() } returns sinceMs
        val service = scopedService(source, cfg, sweepScope, clock)
        assertTrue(service.ensureShieldedReady())
        withTimeout(5_000) {
            while (!service.shieldedBalanceMaybeStale.value || source.passResults.subscriptionCount.value == 0) {
                delay(10)
            }
        }
        return service
    }

    private fun scopedService(
        source: FakeSource,
        cfg: DashPayConfig,
        sweepScope: CoroutineScope,
        clock: () -> Long = System::currentTimeMillis
    ) = ShieldedBalanceServiceImpl(
        source = source,
        dashPayConfig = cfg,
        shieldedDbPath = { dbPath },
        displayHrp = { hrp },
        sweepScope = sweepScope,
        clock = clock
    )

    /** A ready, scoped service whose settlement collector is subscribed; not reconciling yet. */
    private suspend fun subscribedService(
        source: FakeSource,
        cfg: DashPayConfig,
        sweepScope: CoroutineScope
    ): ShieldedBalanceServiceImpl {
        val service = scopedService(source, cfg, sweepScope)
        assertTrue(service.ensureShieldedReady())
        withTimeout(5_000) { while (source.passResults.subscriptionCount.value == 0) delay(10) }
        assertFalse(service.shieldedBalanceMaybeStale.value)
        return service
    }

    /** Emit [results] one by one, letting the collector process each, then give it time to settle. */
    private suspend fun emitPasses(source: FakeSource, vararg results: ShieldedPassResult) {
        for (result in results) {
            source.passResults.emit(result)
            delay(30)
        }
        delay(200)
    }

    /** Emit two equal successful results and wait for the reconciliation to settle. */
    private suspend fun settleWith(service: ShieldedBalanceServiceImpl, source: FakeSource, credits: Long) {
        source.passResults.emit(passOk(credits))
        delay(30)
        source.passResults.emit(passOk(credits))
        withTimeout(5_000) { while (service.shieldedBalanceMaybeStale.value) delay(10) }
    }

    // Pool fixture: two notes before the spend; the spend consumes [inputNote]
    // and returns [changeNote]; [recipientCmx] is its send to another wallet.
    private val inputNote = note(value = 600_000_000_000L, nullifierSeed = 1)
    private val keptNote = note(value = 400_000_000_000L, nullifierSeed = 2)
    private val changeNote = note(value = 150_000_000_000L, nullifierSeed = 3)
    private val recipientCmx = ByteArray(32) { 0x55 }
    private val preSpendCredits = inputNote.value + keptNote.value

    /** The spend's row as the SDK records it before broadcast: inputs + visible outputs. */
    private fun spendRow(status: Int, createdAtMs: Long = System.currentTimeMillis(), withChange: Boolean = true) =
        activity(
            entryId = 1, direction = 1, status = status,
            createdAtMs = createdAtMs,
            spentNullifiers = inputNote.nullifier,
            noteCmxs = if (withChange) recipientCmx + changeNote.cmx else recipientCmx
        )

    /** What a scan pass stores once it has processed the spend's block. */
    private fun FakeSource.scanTheSpendsOutputs(withChange: Boolean = true) {
        if (withChange) notesFlow.value = notesFlow.value + changeNote
        outgoingFlow.value = outgoingFlow.value + outgoing(recipientCmx)
    }

    /** The spend's inputs marked spent in Room (the SDK's finalize, or a later scan). */
    private fun FakeSource.markInputSpent() {
        notesFlow.value = notesFlow.value.filterNot { it.nullifier.contentEquals(inputNote.nullifier) }
        spentNotesFlow.value = spentNotesFlow.value + inputNote.copy(isSpent = true)
    }

    /**
     * Regression (thepastaclaw review 2026-10-18): results from passes that ran
     * BEFORE a spend can still be buffered when it is marked (no pass id; the
     * SDK buffers 256 events). While the store still shows the spend's input
     * note unspent, two such results equal the store — and used to settle. The
     * spend's activity row (written by the SDK during the spend call) keeps the
     * gate closed until its nullifier is spent in the store; from then on the
     * store excludes the input, so the same stale results can never match it.
     */
    private suspend fun assertBufferedPreSpendResultsCannotSettle(rowStatus: Int) {
        val cfg = config(true)
        val source = readySource()
        source.notesFlow.value = listOf(inputNote, keptNote)
        // The SDK writes the row during the spend call; the best-effort isSpent
        // write has not landed yet.
        source.onTransfer = { source.activityFlow.value = listOf(spendRow(rowStatus)) }
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = subscribedService(source, cfg, sweepScope)
            assertTrue(service.transferShielded(recipientAddress, Dash.COIN, null) is SdkWriteResult.Broadcast)
            assertTrue(service.shieldedBalanceMaybeStale.value)

            // Two identical pre-spend results, equal to the store, consumed only now.
            emitPasses(source, passOk(preSpendCredits), passOk(preSpendCredits))
            assertTrue("pre-spend results must not settle", service.shieldedBalanceMaybeStale.value)

            // The input is spent in the store: the same stale results still
            // count it, so they can never equal the store again.
            source.markInputSpent()
            emitPasses(source, passOk(preSpendCredits), passOk(preSpendCredits))
            assertTrue("stale results must not match the post-spend store", service.shieldedBalanceMaybeStale.value)
            coVerify(exactly = 0) { cfg.clearShieldedSpendReconcilePending() }

            // A scan stores the outputs; post-spend passes match the store.
            source.scanTheSpendsOutputs()
            settleWith(service, source, keptNote.value + changeNote.value)
            coVerify(exactly = 1) { cfg.clearShieldedSpendReconcilePending() }
        } finally {
            sweepScope.cancel()
        }
    }

    @Test
    fun bufferedPreSpendResults_cannotSettle_whileTheConfirmedSpendsInputIsUnspent() = runBlocking {
        assertBufferedPreSpendResultsCannotSettle(rowStatus = 1)
    }

    @Test
    fun bufferedPreSpendResults_cannotSettle_whileThePendingSpendsInputIsUnspent() = runBlocking {
        assertBufferedPreSpendResultsCannotSettle(rowStatus = 0)
    }

    /**
     * Regression (review 2026-10-19): a pass that finishes DURING the ~30 s
     * proof reports a balance that excludes the reserved inputs (B0 − inputs).
     * After the spend marks its inputs spent, the store reads that same value
     * until a scan finds the change — so two such buffered results would match
     * the store and settle without the change. The gate waits for the spend's
     * output cmxs to be stored.
     */
    @Test
    fun midProofResults_excludingTheReservedInputs_cannotSettleBeforeTheChangeIsStored() = runBlocking {
        val cfg = config(true)
        val source = readySource()
        source.notesFlow.value = listOf(inputNote, keptNote)
        // SDK success path: row Confirmed and the input marked spent before the call returns.
        source.onTransfer = {
            source.activityFlow.value = listOf(spendRow(status = 1))
            source.markInputSpent()
        }
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = subscribedService(source, cfg, sweepScope)
            assertTrue(service.transferShielded(recipientAddress, Dash.COIN, null) is SdkWriteResult.Broadcast)

            val reservationExcluded = preSpendCredits - inputNote.value
            assertEquals("the fixture's store reads B0 − inputs", reservationExcluded, keptNote.value)
            emitPasses(source, passOk(reservationExcluded), passOk(reservationExcluded), passOk(reservationExcluded))
            assertTrue("must not settle without the change note", service.shieldedBalanceMaybeStale.value)
            coVerify(exactly = 0) { cfg.clearShieldedSpendReconcilePending() }

            source.scanTheSpendsOutputs()
            // The same buffered value no longer equals the store.
            emitPasses(source, passOk(reservationExcluded), passOk(reservationExcluded))
            assertTrue(service.shieldedBalanceMaybeStale.value)
            settleWith(service, source, keptNote.value + changeNote.value)
        } finally {
            sweepScope.cancel()
        }
    }

    /**
     * An exact-amount transfer has NO change output (the SDK's single-transfer
     * builder omits a zero change), so its only visible cmx is the recipient's,
     * which the scan stores as an outgoing note. B0 − inputs is then the final
     * balance; the gate opens once the scan has processed the spend.
     */
    @Test
    fun anExactAmountTransfer_settlesOnceItsSendIsScanned() = runBlocking {
        val cfg = config(true)
        val source = readySource()
        source.notesFlow.value = listOf(inputNote, keptNote)
        source.onTransfer = {
            source.activityFlow.value = listOf(spendRow(status = 1, withChange = false))
            source.markInputSpent()
        }
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = subscribedService(source, cfg, sweepScope)
            assertTrue(service.transferShielded(recipientAddress, Dash.COIN, null) is SdkWriteResult.Broadcast)
            emitPasses(source, passOk(keptNote.value), passOk(keptNote.value))
            assertTrue("the send is not scanned yet", service.shieldedBalanceMaybeStale.value)

            source.scanTheSpendsOutputs(withChange = false)
            settleWith(service, source, keptNote.value)
        } finally {
            sweepScope.cancel()
        }
    }

    /**
     * A spend that consumes no notes (Type-15 shield from a platform address):
     * no nullifiers, but its row names the new note's cmx, so pre-shield
     * results can't settle at the pre-shield amount before the note is stored.
     */
    @Test
    fun aNoteLessShield_cannotSettleAtThePreShieldAmount() = runBlocking {
        val cfg = config(true)
        val source = readySource()
        source.notesFlow.value = listOf(keptNote)
        val newNote = note(value = 200_000_000_000L, nullifierSeed = 7)
        source.onShield = {
            source.activityFlow.value = listOf(
                activity(
                    entryId = 2, direction = 0, status = 1,
                    createdAtMs = System.currentTimeMillis(),
                    noteCmxs = newNote.cmx
                )
            )
        }
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = subscribedService(source, cfg, sweepScope)
            assertTrue(service.shieldFromCredits(Dash.COIN) is SdkWriteResult.Broadcast)
            emitPasses(source, passOk(keptNote.value), passOk(keptNote.value), passOk(keptNote.value))
            assertTrue("the new note is not stored yet", service.shieldedBalanceMaybeStale.value)

            source.notesFlow.value = listOf(keptNote, newNote)
            settleWith(service, source, keptNote.value + newNote.value)
        } finally {
            sweepScope.cancel()
        }
    }

    /**
     * An ambiguous spend (row Pending, input still unspent) keeps the balance
     * stale however many stable passes arrive; once the SDK resolves the row as
     * Failed, the unspent input is legitimately back and settlement proceeds.
     */
    @Test
    fun anAmbiguousSpend_staysStaleUntilItsRowResolves() = runBlocking {
        val cfg = config(true)
        val source = readySource()
        source.notesFlow.value = listOf(inputNote, keptNote)
        val row = spendRow(status = 0)
        source.onTransfer = {
            source.activityFlow.value = listOf(row)
            throw DashSdkError.PlatformWallet.ShieldedSpendUnconfirmed("unconfirmed")
        }
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = subscribedService(source, cfg, sweepScope)
            assertTrue(service.transferShielded(recipientAddress, Dash.COIN, null) is SdkWriteResult.Ambiguous)
            assertTrue(service.shieldedBalanceMaybeStale.value)

            emitPasses(source, *Array(6) { passOk(preSpendCredits) })
            assertTrue("an unresolved spend must keep the balance stale", service.shieldedBalanceMaybeStale.value)
            coVerify(exactly = 0) { cfg.clearShieldedSpendReconcilePending() }

            source.activityFlow.value = listOf(row.copy(status = 2))
            source.passResults.emit(passOk(preSpendCredits))
            withTimeout(5_000) { while (service.shieldedBalanceMaybeStale.value) delay(10) }
        } finally {
            sweepScope.cancel()
        }
    }

    /**
     * Fail closed (thepastaclaw, c99eefff0): a row's age never releases it. A
     * Pending row whose inputs are still unspent blocks however old it is, and
     * stops blocking only once the SDK resolves it from protocol evidence —
     * here, Failed after its recorded anchor was pruned.
     */
    @Test
    fun anUnresolvedPendingRow_blocksWhateverItsAge_untilTheSdkFailsIt() = runBlocking {
        val cfg = config(true)
        val source = readySource()
        val start = 1_800_000_000_000L
        val now = java.util.concurrent.atomic.AtomicLong(start)
        source.notesFlow.value = listOf(inputNote, keptNote)
        source.activityFlow.value = listOf(spendRow(status = 0, createdAtMs = start))
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = reconcilingService(source, cfg, sweepScope, sinceMs = start - 1_000L, clock = { now.get() })
            now.set(start + 365L * 24 * 60 * 60 * 1000)
            emitPasses(source, passOk(preSpendCredits), passOk(preSpendCredits), passOk(preSpendCredits))
            assertTrue("an unresolved row blocks at any age", service.shieldedBalanceMaybeStale.value)

            // Unknown (an abandoned identity debit that may still execute) blocks too
            source.activityFlow.value = listOf(spendRow(status = 3, createdAtMs = start))
            emitPasses(source, passOk(preSpendCredits), passOk(preSpendCredits))
            assertTrue("an Unknown row blocks", service.shieldedBalanceMaybeStale.value)

            source.activityFlow.value = listOf(spendRow(status = 2, createdAtMs = start))
            source.passResults.emit(passOk(preSpendCredits))
            withTimeout(5_000) { while (service.shieldedBalanceMaybeStale.value) delay(10) }
        } finally {
            sweepScope.cancel()
        }
    }

    /** A row older than the spend (a long-stuck Pending op) does not block its settlement. */
    @Test
    fun activityRowsOlderThanTheSpend_areIgnored() = runBlocking {
        val cfg = config(true)
        val source = readySource()
        source.notesFlow.value = listOf(inputNote, keptNote)
        val stuck = activity(
            entryId = 9, direction = 1, status = 0,
            createdAtMs = System.currentTimeMillis() - 3_600_000L,
            spentNullifiers = keptNote.nullifier,
            noteCmxs = ByteArray(32) { 0x66 }
        )
        source.activityFlow.value = listOf(stuck)
        source.onTransfer = {
            // SDK success path: the row is Confirmed and the input marked spent.
            source.activityFlow.value = listOf(stuck, spendRow(status = 1))
            source.markInputSpent()
        }
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = subscribedService(source, cfg, sweepScope)
            assertTrue(service.transferShielded(recipientAddress, Dash.COIN, null) is SdkWriteResult.Broadcast)
            source.scanTheSpendsOutputs()
            settleWith(service, source, keptNote.value + changeNote.value)
        } finally {
            sweepScope.cancel()
        }
    }

    /**
     * spend → restart: the restored since still selects the spend's row (and
     * only rows from then on), so an unresolved spend from before the restart
     * keeps the balance stale.
     */
    @Test
    fun afterRestart_theRestoredSince_stillGatesSettlement() = runBlocking {
        val cfg = config(true)
        val source = readySource()
        val now = System.currentTimeMillis()
        source.notesFlow.value = listOf(inputNote, keptNote)
        val pendingSpend = spendRow(status = 0, createdAtMs = now - 300_000L)
        val olderStuck = activity(
            entryId = 9, direction = 1, status = 0,
            createdAtMs = now - 3_600_000L,
            spentNullifiers = keptNote.nullifier
        )
        source.activityFlow.value = listOf(olderStuck, pendingSpend)
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = reconcilingService(source, cfg, sweepScope, sinceMs = now - 600_000L)
            emitPasses(source, passOk(preSpendCredits), passOk(preSpendCredits), passOk(preSpendCredits))
            assertTrue("the restored spend is unresolved", service.shieldedBalanceMaybeStale.value)

            source.activityFlow.value = listOf(olderStuck, pendingSpend.copy(status = 2))
            source.passResults.emit(passOk(preSpendCredits))
            withTimeout(5_000) { while (service.shieldedBalanceMaybeStale.value) delay(10) }
        } finally {
            sweepScope.cancel()
        }
    }

    /** An unreadable activity or outgoing-note store keeps the gate closed (fail closed). */
    @Test
    fun anUnreadableActivityOrOutgoingStore_keepsTheReconciliationPending() = runBlocking {
        val cfg = config(true)
        val source = readySource()
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            source.activityFailure = IllegalStateException("room closed")
            val service = reconcilingService(source, cfg, sweepScope)
            emitPasses(source, passOk(), passOk(), passOk())
            assertTrue(service.shieldedBalanceMaybeStale.value)

            source.activityFailure = null
            source.outgoingFailure = IllegalStateException("room closed")
            emitPasses(source, passOk(), passOk(), passOk())
            assertTrue(service.shieldedBalanceMaybeStale.value)
            coVerify(exactly = 0) { cfg.clearShieldedSpendReconcilePending() }

            source.outgoingFailure = null
            source.passResults.emit(passOk())
            withTimeout(5_000) { while (service.shieldedBalanceMaybeStale.value) delay(10) }
        } finally {
            sweepScope.cancel()
        }
    }

    /**
     * Regression (codex review 2026-10-18): if the durable marker can't be
     * cleared, the reconciliation stays pending — clearing only the flag would
     * show a balance the persister refuses to cache. The next stable pass
     * retries the clear.
     */
    @Test
    fun settlement_keepsTheFlag_whenClearingTheMarkerFails() = runBlocking {
        val cfg = config(true)
        var clearFails = true
        coEvery { cfg.clearShieldedSpendReconcilePending() } coAnswers {
            if (clearFails) throw IllegalStateException("datastore write failed")
        }
        val source = readySource()
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = reconcilingService(source, cfg, sweepScope)
            source.passResults.emit(passOk())
            delay(30)
            source.passResults.emit(passOk())
            withTimeout(5_000) {
                while (true) {
                    try {
                        coVerify(atLeast = 1) { cfg.clearShieldedSpendReconcilePending() }
                        break
                    } catch (e: AssertionError) {
                        delay(10)
                    }
                }
            }
            delay(100)
            assertTrue("marker still set: must stay stale", service.shieldedBalanceMaybeStale.value)

            clearFails = false
            source.passResults.emit(passOk())
            withTimeout(5_000) { while (service.shieldedBalanceMaybeStale.value) delay(10) }
        } finally {
            sweepScope.cancel()
        }
    }

    /**
     * Regression (codex review 2026-10-18): stopping the runtime cancels the
     * settlement driver — it must not keep forcing passes against a stopped
     * loop (or a wiped wallet). The durable marker restarts it on bring-up.
     */
    @Test
    fun stop_cancelsTheSettlementDriver() = runBlocking {
        val cfg = config(true)
        val source = readySource()
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = ShieldedBalanceServiceImpl(
                source = source,
                dashPayConfig = cfg,
                shieldedDbPath = { dbPath },
                displayHrp = { hrp },
                sweepScope = sweepScope
            )
            assertTrue(service.ensureShieldedReady())
            withTimeout(5_000) { while (source.passResults.subscriptionCount.value == 0) delay(10) }

            // Every forced pass fails, so the driver would retry indefinitely.
            source.onSyncNowSuspend = { throw IllegalStateException("not ready") }
            withTimeout(2_000) { service.noteExternalShieldedSpendBroadcast(System.currentTimeMillis()) }
            withTimeout(5_000) { while (source.syncNowCalls == 0) delay(10) }

            withTimeout(5_000) { service.stop() }
            val callsAtStop = source.syncNowCalls
            delay(ShieldedBalanceServiceImpl.SETTLEMENT_RETRY_BASE_MS * 4 + 200)
            assertEquals("no forced pass after stop", callsAtStop, source.syncNowCalls)
        } finally {
            sweepScope.cancel()
        }
    }

    /**
     * Regression (thepastaclaw review 2026-10-18): an unreadable reconcile
     * marker on restart counts as PENDING — fail closed — not as "no spend".
     */
    @Test
    fun afterRestart_anUnreadableMarker_isTreatedAsPending() = runBlocking {
        val cfg = config(true)
        coEvery { cfg.shieldedSpendReconcilePendingSinceMs() } throws java.io.IOException("unreadable")
        val source = readySource()
        // An hour-old unresolved spend: with the since unknown (0), it counts.
        source.notesFlow.value = listOf(inputNote)
        source.activityFlow.value = listOf(
            activity(
                entryId = 1, direction = 1, status = 0,
                createdAtMs = System.currentTimeMillis() - 3_600_000L,
                spentNullifiers = inputNote.nullifier
            )
        )
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = ShieldedBalanceServiceImpl(
                source = source,
                dashPayConfig = cfg,
                shieldedDbPath = { dbPath },
                displayHrp = { hrp },
                sweepScope = sweepScope
            )
            assertTrue(service.ensureShieldedReady())
            withTimeout(10_000) {
                while (service.shieldedSyncStatus.value != ShieldedSyncStatus.READY) delay(50)
            }
            assertTrue("an unknown marker must keep the balance stale", service.shieldedBalanceMaybeStale.value)
            withTimeout(5_000) { while (source.passResults.subscriptionCount.value == 0) delay(10) }
            emitPasses(source, passOk(inputNote.value), passOk(inputNote.value))
            assertTrue("since = 0: every unresolved row gates", service.shieldedBalanceMaybeStale.value)
        } finally {
            sweepScope.cancel()
        }
    }

    /**
     * Smoke test (thepastaclaw review 2026-10-18): two overlapping spends still
     * end in a settled reconciliation. Each driver starts under the reconcile
     * mutex, so an older start can't cancel a newer driver; that has no window
     * left to force deterministically, so this guards the outcome. The
     * reconciliation keeps the EARLIER of the two spends' since.
     */
    @Test
    fun overlappingSpends_stillSettle_andKeepTheEarliestSince() = runBlocking {
        val cfg = config(true)
        val markGate = CompletableDeferred<Unit>()
        var marks = 0
        coEvery { cfg.markShieldedSpendReconcilePending(any()) } coAnswers {
            if (++marks == 1) markGate.await() // the first spend pauses inside its mark
        }
        val source = readySource()
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = ShieldedBalanceServiceImpl(
                source = source,
                dashPayConfig = cfg,
                shieldedDbPath = { dbPath },
                displayHrp = { hrp },
                sweepScope = sweepScope
            )
            assertTrue(service.ensureShieldedReady())
            withTimeout(5_000) { while (source.passResults.subscriptionCount.value == 0) delay(10) }

            val now = System.currentTimeMillis()
            val skew = ShieldedBalanceServiceImpl.SPEND_WINDOW_SKEW_MS
            // An old (but still in-window) row for the FIRST spend, unresolved.
            val firstSpendRow = activity(
                entryId = 1, direction = 1, status = 0,
                createdAtMs = now - 60_000L,
                spentNullifiers = ByteArray(32) { 1 }
            )
            source.notesFlow.value = listOf(note(value = 0L, nullifierSeed = 1))
            source.activityFlow.value = listOf(firstSpendRow)

            val first = async(Dispatchers.Default) { service.noteExternalShieldedSpendBroadcast(now - 60_000L) }
            withTimeout(5_000) { while (marks == 0) delay(10) }
            val second = async(Dispatchers.Default) { service.noteExternalShieldedSpendBroadcast(now) }
            delay(50)
            markGate.complete(Unit)
            withTimeout(5_000) { first.await(); second.await() }
            assertEquals(2, marks)
            coVerify { cfg.markShieldedSpendReconcilePending(now - 60_000L - skew) }
            coVerify { cfg.markShieldedSpendReconcilePending(now - skew) }

            // The second spend's since alone would ignore the first spend's row.
            emitPasses(source, passOk(), passOk(), passOk())
            assertTrue("the first spend's row is still gated", service.shieldedBalanceMaybeStale.value)

            source.activityFlow.value = listOf(firstSpendRow.copy(status = 2))
            withTimeout(5_000) {
                while (service.shieldedBalanceMaybeStale.value) {
                    source.passResults.emit(passOk())
                    delay(30)
                }
            }
        } finally {
            markGate.complete(Unit)
            sweepScope.cancel()
        }
    }

    /** Failed / skipped passes are no settlement evidence, however many of them complete. */
    @Test
    fun failedOrSkippedPasses_neverSettleAReconciliation() = runBlocking {
        val cfg = config(true)
        val source = readySource()
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = reconcilingService(source, cfg, sweepScope)
            repeat(3) {
                source.passResults.emit(passFailed())
                delay(30)
                source.passResults.emit(passSkipped())
                delay(30)
            }
            delay(200)
            assertTrue(service.shieldedBalanceMaybeStale.value)
            coVerify(exactly = 0) { cfg.clearShieldedSpendReconcilePending() }
        } finally {
            sweepScope.cancel()
        }
    }

    /**
     * Regression: two successful results with DIFFERENT pass balances queued
     * back-to-back while the store already reads the later one. Re-reading the
     * store for each made them look stable; comparing the balances the passes
     * themselves observed keeps the reconciliation pending.
     */
    @Test
    fun bufferedPassesWithDifferentBalances_doNotSettle() = runBlocking {
        val cfg = config(true)
        val source = readySource()
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = reconcilingService(source, cfg, sweepScope)
            source.notesFlow.value = listOf(note(value = 600_000_000_000L)) // store already at B
            source.passResults.emit(passOk(credits = 1_000_000_000_000L)) // pass A
            source.passResults.emit(passOk(credits = 600_000_000_000L)) // pass B, queued right behind
            delay(300)
            assertTrue("A != B: not two stable passes", service.shieldedBalanceMaybeStale.value)
            coVerify(exactly = 0) { cfg.clearShieldedSpendReconcilePending() }

            source.passResults.emit(passOk(credits = 600_000_000_000L)) // B again: now stable
            withTimeout(5_000) { while (service.shieldedBalanceMaybeStale.value) delay(10) }
        } finally {
            sweepScope.cancel()
        }
    }

    /** A cooldown-skipped pass between two successes neither counts nor breaks the streak. */
    @Test
    fun aSkippedPassBetweenSuccesses_doesNotBreakTheStreak() = runBlocking {
        val cfg = config(true)
        val source = readySource()
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = reconcilingService(source, cfg, sweepScope)
            source.passResults.emit(passOk())
            delay(30)
            source.passResults.emit(passSkipped())
            delay(30)
            source.passResults.emit(passOk())
            withTimeout(5_000) { while (service.shieldedBalanceMaybeStale.value) delay(10) }
        } finally {
            sweepScope.cancel()
        }
    }

    /**
     * Stable pass balances, but the note store the surfaces display has not
     * caught up yet: stay pending (the header would show the old figure), and
     * settle on the next successful pass once it has.
     */
    @Test
    fun stablePasses_waitForTheDisplayedStoreToCatchUp() = runBlocking {
        val cfg = config(true)
        val source = readySource()
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = reconcilingService(source, cfg, sweepScope)
            source.notesFlow.value = listOf(note(value = 900_000_000_007L)) // store still pre-settlement (distinct low byte: the fixture's nullifier is value.toByte())
            source.passResults.emit(passOk(credits = 500_000_000_003L))
            delay(30)
            source.passResults.emit(passOk(credits = 500_000_000_003L))
            delay(300)
            assertTrue("store not caught up", service.shieldedBalanceMaybeStale.value)

            source.notesFlow.value = listOf(note(value = 500_000_000_003L))
            source.passResults.emit(passOk(credits = 500_000_000_003L))
            withTimeout(5_000) { while (service.shieldedBalanceMaybeStale.value) delay(10) }
        } finally {
            sweepScope.cancel()
        }
    }

    /**
     * Two consecutive SUCCESSFUL passes with a stable balance settle it — with
     * no in-flight flag ever observed by the poller (a pass shorter than the
     * poll interval), which the old busy→idle sampling could never see.
     */
    @Test
    fun twoSuccessfulPasses_settle_evenIfNeverObservedInFlight() = runBlocking {
        val cfg = config(true)
        val source = readySource()
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = reconcilingService(source, cfg, sweepScope)
            assertFalse(source.syncing)
            source.passResults.emit(passOk())
            delay(30)
            source.passResults.emit(passOk())
            withTimeout(5_000) { while (service.shieldedBalanceMaybeStale.value) delay(10) }
            coVerify(exactly = 1) { cfg.clearShieldedSpendReconcilePending() }
        } finally {
            sweepScope.cancel()
        }
    }

    /** success → FAILED → success is not two consecutive successes: still reconciling. */
    @Test
    fun aFailedPassBetweenSuccesses_resetsTheStreak() = runBlocking {
        val cfg = config(true)
        val source = readySource()
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = reconcilingService(source, cfg, sweepScope)
            source.passResults.emit(passOk())
            delay(30)
            source.passResults.emit(passFailed())
            delay(30)
            source.passResults.emit(passOk())
            delay(200)
            assertTrue(service.shieldedBalanceMaybeStale.value)

            source.passResults.emit(passOk())
            withTimeout(5_000) { while (service.shieldedBalanceMaybeStale.value) delay(10) }
        } finally {
            sweepScope.cancel()
        }
    }

    /** Another wallet's successful passes are not evidence for the bound wallet. */
    @Test
    fun anotherWalletsPasses_areIgnored() = runBlocking {
        val cfg = config(true)
        val source = readySource()
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = reconcilingService(source, cfg, sweepScope)
            val other = checkNotNull(walletIdFromHex("cd".repeat(32)))
            repeat(4) {
                source.passResults.emit(passOk(walletId = other))
                delay(30)
            }
            delay(200)
            assertTrue(service.shieldedBalanceMaybeStale.value)
            coVerify(exactly = 0) { cfg.clearShieldedSpendReconcilePending() }
        } finally {
            sweepScope.cancel()
        }
    }

    /**
     * External spend (invite / username): the durable invalidation happens
     * before the call returns, but the network refresh does not hold the caller
     * — the invite flow still has to persist its one-time key afterwards.
     */
    @Test
    fun noteExternalSpend_marksDurably_withoutWaitingForTheSyncPass() = runBlocking {
        val cfg = config(true)
        val syncGate = CompletableDeferred<Unit>()
        val source = readySource().apply { onSyncNowSuspend = { syncGate.await() } }
        val sweepScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = ShieldedBalanceServiceImpl(
                source = source,
                dashPayConfig = cfg,
                shieldedDbPath = { dbPath },
                displayHrp = { hrp },
                sweepScope = sweepScope
            )
            assertTrue(service.ensureShieldedReady())
            // returns although syncShieldedNow is blocked
            withTimeout(2_000) { service.noteExternalShieldedSpendBroadcast(1_700_000_000_000L) }
            assertTrue(service.shieldedBalanceMaybeStale.value)
            // the since is the caller's start time less the skew allowance
            coVerify(exactly = 1) {
                cfg.markShieldedSpendReconcilePending(
                    1_700_000_000_000L - ShieldedBalanceServiceImpl.SPEND_WINDOW_SKEW_MS
                )
            }
        } finally {
            syncGate.complete(Unit)
            sweepScope.cancel()
        }
    }

    @Test
    fun successfulSpend_staysBroadcast_whenInvalidatingTheCacheFails() = runBlocking {
        val cfg = config(true, lastBalanceDuffs = 1_000_000_000L)
        coEvery { cfg.markShieldedSpendReconcilePending(any()) } throws IllegalStateException("datastore down")
        val service = ShieldedBalanceServiceImpl(
            source = readySource(),
            dashPayConfig = cfg,
            shieldedDbPath = { dbPath },
            displayHrp = { hrp }
        )
        assertTrue(service.transferShielded(recipientAddress, Dash.COIN, null) is SdkWriteResult.Broadcast)
        assertTrue(service.shieldedBalanceMaybeStale.value)
    }

    @Test
    fun shieldedBalanceMaybeStale_notSetByAPreflightRejectedSpend() = runBlocking {
        val source = readySource()
        val service = service(source)
        // Non-positive amount is rejected before any broadcast — nothing was
        // spent, so the cache is not stale.
        assertTrue(service.shieldFromCredits(Dash.ZERO) is SdkWriteResult.NotBroadcast)
        assertFalse(service.shieldedBalanceMaybeStale.value)
    }

    @Test
    fun syncStatus_startsNotReady_andIsInertWhileFlagOff() = runBlocking {
        // No poller runs without a sweep scope (the test service), so the
        // status stays at its constructed NOT_READY and the pass-in-flight
        // signal is never touched.
        val source = readySource()
        val service = service(source, enabled = false)
        assertEquals(ShieldedSyncStatus.NOT_READY, service.shieldedSyncStatus.value)
        service.ensureShieldedReady()
        assertEquals(ShieldedSyncStatus.NOT_READY, service.shieldedSyncStatus.value)
    }

    // ── Pure mapping: credits ↔ Dash ──────────────────────────────────────

    @Test
    fun creditsToDash_floors() {
        assertEquals(Dash.ZERO, creditsToDash(0))
        assertEquals(Dash.ZERO, creditsToDash(999))
        assertEquals(Dash(1), creditsToDash(1_000))
        assertEquals(Dash(1), creditsToDash(1_999))
        assertEquals(Dash.COIN, creditsToDash(100_000_000_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun creditsToDash_rejectsNegative() {
        creditsToDash(-1)
    }

    @Test
    fun dashToCredits_isExact() {
        assertEquals(1_000L, dashToCredits(Dash(1)))
        assertEquals(100_000_000_000L, dashToCredits(Dash.COIN))
    }

    @Test(expected = ArithmeticException::class)
    fun dashToCredits_throwsOnOverflow() {
        dashToCredits(Dash(Long.MAX_VALUE / 10))
    }

    // ── Pure mapping: memo decoding ───────────────────────────────────────

    @Test
    fun memo_textKind_decodesAndTrimsPadding() {
        assertEquals("hello", decodeShieldedMemo(textMemo("hello")))
        val full = "y".repeat(32)
        assertEquals(full, decodeShieldedMemo(textMemo(full)))
    }

    @Test
    fun memo_nonTextOrMalformed_decodesToNull() {
        assertNull(decodeShieldedMemo(ByteArray(0))) // absent
        assertNull(decodeShieldedMemo(ByteArray(36))) // kind 0 = empty
        assertNull(decodeShieldedMemo(ByteArray(35))) // wrong length
        assertNull(decodeShieldedMemo(textMemo("").also { it[0] = 1 })) // text kind, empty payload
        // Unknown kind round-trips as "no readable memo".
        val unknownKind = textMemo("hidden").also { it[0] = 2 }
        assertNull(decodeShieldedMemo(unknownKind))
        // Text kind with invalid UTF-8.
        val invalid = textMemo("").also { it[0] = 1; it[4] = 0xff.toByte() }
        assertNull(decodeShieldedMemo(invalid))
    }

    // ── Pure mapping: activity entity ─────────────────────────────────────

    @Test
    fun activityEntity_failedOrUnknownDirection_isNotSurfaced() {
        assertNull(toShieldedActivityEntry(activity(entryId = 1, direction = 0, status = 2)))
        assertNull(toShieldedActivityEntry(activity(entryId = 1, direction = 3, status = 1)))
        assertNull(toShieldedActivityEntry(activity(entryId = 1, direction = -1, status = 1)))
    }

    @Test
    fun activityEntity_mapsAllFields() {
        val entry = toShieldedActivityEntry(
            activity(
                entryId = 0x1f, direction = 1, status = 1,
                amount = 42_000L, createdAtMs = 1_234L, memo = textMemo("note")
            )
        )
        assertNotNull(entry)
        assertEquals("1f" + "00".repeat(31), entry!!.id)
        assertEquals(ShieldedActivityDirection.OUT, entry.direction)
        assertEquals(Dash(42), entry.amount)
        assertEquals(1_234L, entry.timestampMs)
        assertEquals("note", entry.memo)
        assertFalse(entry.pending)
    }

    // ── Pure mapping: Orchard address codec ───────────────────────────────

    @Test
    fun orchardAddress_roundTripsThroughBech32m() {
        val encoded = encodeOrchardAddress(recipientRaw43, "dash")
        assertNotNull(encoded)
        assertArrayEquals(recipientRaw43, decodeOrchardAddress(encoded!!, "dash"))
        // Wrong network HRP does not decode.
        assertNull(decodeOrchardAddress(encoded, "tdash"))
    }

    @Test
    fun orchardAddress_rejectsWrongShapes() {
        assertNull(encodeOrchardAddress(ByteArray(42), hrp))
        assertNull(decodeOrchardAddress("garbage", hrp))
        // Right HRP but a 21-byte platform payload.
        assertNull(decodeOrchardAddress(Bech32m.encode(hrp, ByteArray(21) { 0xb0.toByte() })!!, hrp))
        // Right HRP and length but a non-Orchard type byte.
        val wrongType = Bech32m.encode(hrp, ByteArray(44) { if (it == 0) 0x11 else 1 })!!
        assertNull(decodeOrchardAddress(wrongType, hrp))
    }

    // ── shieldFromWallet: the L1 funding gate ─────────────────────────────

    @Test
    fun fundingGate_pure_decisionTable() {
        // The fix: the gate OPENS on caught-up alone — phase is FILTERS,
        // NOT SYNCED (the never-SYNCED root cause: a live shadow chasing
        // the tip never latches SYNCED), and NO dashj parity is consulted.
        val caughtUp = caughtUpProgress()
        assertFalse(caughtUp.synced) // proves we are not relying on SYNCED
        assertTrue(evaluateWalletFundingGate(caughtUp).allowed)

        // Backward-compatible: a genuine SYNCED-with-complete-scan also opens.
        assertTrue(
            evaluateWalletFundingGate(caughtUp.copy(phase = ShadowSyncPhase.SYNCED)).allowed
        )

        // Engine not running (the progress feed resets to IDLE on stop).
        assertFalse(evaluateWalletFundingGate(ShadowSyncProgress.IDLE).allowed)
        // Engine errored — closed even with a complete scan snapshot.
        assertFalse(evaluateWalletFundingGate(caughtUp.copy(phase = ShadowSyncPhase.ERROR)).allowed)

        // Genuinely mid-scan: headers at the tip but the filter scan far
        // behind (the real "still scanning" state) stays closed. The aggregate
        // must be honest too — (100 + 100 + 93.3) / 3 — or the iOS rule would
        // call this caught up.
        assertFalse(
            evaluateWalletFundingGate(caughtUp.copy(filterHeight = 1_400_000, overallPercent = 0.978)).allowed
        )
        // All-zero snapshot (no real sub-progress — the post-reset watermark
        // signature) cannot prove caught-up: closed even if phase were SYNCED.
        assertFalse(
            evaluateWalletFundingGate(
                caughtUp.copy(
                    phase = ShadowSyncPhase.SYNCED,
                    headerHeight = 0, headerTarget = 0, filterHeight = 0, filterTarget = 0
                )
            ).allowed
        )
        // Headers not yet at the tip → closed even if filters look full.
        assertFalse(evaluateWalletFundingGate(caughtUp.copy(headerHeight = 1_499_000)).allowed)

        // Tolerance boundary, HEIGHT rule (aggregate unknown = 0.0): within
        // SCAN_TIP_TOLERANCE_BLOCKS of the tip is caught up; one block further
        // is not.
        val noAggregate = caughtUp.copy(overallPercent = 0.0)
        assertTrue(
            evaluateWalletFundingGate(
                noAggregate.copy(filterHeight = 1_500_000 - ShadowSyncProgress.SCAN_TIP_TOLERANCE_BLOCKS)
            ).allowed
        )
        assertFalse(
            evaluateWalletFundingGate(
                noAggregate.copy(filterHeight = 1_500_000 - ShadowSyncProgress.SCAN_TIP_TOLERANCE_BLOCKS - 1)
            ).allowed
        )
        // The same one-block-past-tolerance gap WITH an honest aggregate OPENS:
        // 3 short of 1.5M is 99.9998%, and since 2026-09-21 the gate applies
        // the iOS rule (>= 0.999). This is the change, stated as a test: the
        // §34 stall (2,167 short, 99.954%) opens too.
        assertTrue(
            evaluateWalletFundingGate(
                caughtUp.copy(filterHeight = 1_500_000 - ShadowSyncProgress.SCAN_TIP_TOLERANCE_BLOCKS - 1)
            ).allowed
        )
        assertTrue(
            evaluateWalletFundingGate(
                caughtUp.copy(
                    headerHeight = 1_558_166, headerTarget = 1_558_166,
                    filterHeight = 1_555_999, filterTarget = 1_558_166, overallPercent = 0.99954
                )
            ).allowed
        )

        // THE VETO THE GATE KEEPS. A caught-up scan with the block pipeline
        // provably lagging — this wallet's own spends not yet applied to the
        // ledger — is closed, with a reason that names the pipeline. The
        // display predicate dropped this veto; a spend must not.
        val lagging = evaluateWalletFundingGate(caughtUp.copy(walletSyncedHeight = 1_200_000))
        assertFalse(lagging.allowed)
        assertTrue(lagging.reason, lagging.reason.contains("processing matched blocks"))
        // Unknown cursor (0) is not lag: the gate cannot wedge a wallet at the tip.
        assertTrue(evaluateWalletFundingGate(caughtUp.copy(walletSyncedHeight = 0)).allowed)
    }

    @Test
    fun shieldFromWallet_gateClosed_isNotBroadcast_beforeAnySpend() = runBlocking {
        val source = readySource()
        // Engine not running (IDLE progress) — the funds-safe default.
        val service = service(source, progress = { ShadowSyncProgress.IDLE })

        val result = service.shieldFromWallet(Dash.COIN)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.fundCalls)
        assertFalse(service.isWalletShieldingAvailable())
    }

    @Test
    fun shieldFromWallet_scanNotCaughtUp_isNotBroadcast() = runBlocking {
        val source = readySource()
        // Headers at the tip but the filter scan far behind (mid-scan), with
        // an honest aggregate — or the iOS rule would open the gate.
        val service = service(
            source,
            progress = { caughtUpProgress().copy(filterHeight = 1_400_000, overallPercent = 0.978) }
        )

        assertTrue(service.shieldFromWallet(Dash.COIN) is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.fundCalls)
        assertFalse(service.isWalletShieldingAvailable())
    }

    @Test
    fun shieldFromWallet_l1FlagOff_isNotBroadcast() = runBlocking {
        val source = readySource()
        val service = service(source, l1ShadowEnabled = false, progress = { caughtUpProgress() })

        assertTrue(service.shieldFromWallet(Dash.COIN) is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.fundCalls)
        assertFalse(service.isWalletShieldingAvailable())
    }

    @Test
    fun shieldFromWallet_belowPoolFee_isNotBroadcast() = runBlocking {
        val source = readySource()
        val service = service(source, progress = { caughtUpProgress() })

        // Fee floor: 10k duffs shielded fee + 50k duffs asset-lock base cost.
        assertTrue(service.shieldFromWallet(Dash(60_000)) is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.fundCalls)

        // Just above the floor goes through.
        assertTrue(service.shieldFromWallet(Dash(60_001)) is SdkWriteResult.Broadcast)
        assertEquals(1, source.fundCalls)
    }

    @Test
    fun shieldFromWallet_happyPath_fundsWithDuffsAndOwnAddress() = runBlocking {
        val source = readySource()
        val service = service(source, progress = { caughtUpProgress() })

        val result = service.shieldFromWallet(Dash.COIN)

        assertEquals(
            SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED),
            result
        )
        assertEquals(1, source.fundCalls)
        // L1 duffs, NOT credits — the SDK converts internally.
        assertEquals(Dash.COIN.duffs, source.lastFundAmountDuffs)
        // Shield-to-self: the wallet's own default Orchard address.
        assertArrayEquals(source.defaultAddress, source.lastFundRecipient)
    }

    @Test
    fun mixedFundsShield_existingLockReturnsRecoveryWithoutNewFunding() = runBlocking {
        val source = readySource().apply {
            shieldLocks = { listOf(PendingWalletShieldLock("${"ab".repeat(32)}:0", 1, 206751800L)) }
        }
        val service = service(source, progress = { caughtUpProgress() })
        val result = service.shieldMixedFundsFromWallet(Dash.COIN, "m/9'/1'/4'/0'")
        assertEquals(SdkWriteResult.Broadcast(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY), result)
        assertEquals(0, source.fundCalls)
        assertEquals(0, source.broadcastCalls)
    }

    @Test
    fun mixedFundsShield_coinJoinFundedLockBlocksANewDrain() = runBlocking {
        val source = readySource().apply {
            shieldLocks = {
                listOf(PendingWalletShieldLock("${"ab".repeat(32)}:0", 2, 206751800L, coinJoinFunded = true))
            }
        }
        val service = service(source, progress = { caughtUpProgress() })
        val result = service.shieldMixedFundsFromWallet(Dash.COIN, "m/9'/1'/4'/0'")
        assertEquals(SdkWriteResult.Broadcast(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY), result)
        assertEquals(0, source.accountFundCalls)
        assertEquals(1, service.pendingCoinJoinShieldLockCount())
    }

    @Test
    fun mixedFundsShield_submissionCheckpointRunsOnlyRightBeforeTheFundingCall() = runBlocking {
        val source = readySource()
        val service = service(source, progress = { caughtUpProgress() })
        var fundCallsAtCheckpoint = -1
        val result = service.shieldMixedFundsFromWallet(Dash.COIN, "m/9'/1'/4'/0'") {
            fundCallsAtCheckpoint = source.accountFundCalls
        }
        assertEquals(SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED), result)
        assertEquals(0, fundCallsAtCheckpoint)
        assertEquals(1, source.accountFundCalls)

        // An existing CoinJoin lock answers without reaching the native call.
        source.shieldLocks = {
            listOf(PendingWalletShieldLock("${"ab".repeat(32)}:0", 2, 206751800L, coinJoinFunded = true))
        }
        var checkpointed = false
        service.shieldMixedFundsFromWallet(Dash.COIN, "m/9'/1'/4'/0'") { checkpointed = true }
        assertFalse(checkpointed)
        assertEquals(1, source.accountFundCalls)
    }

    @Test
    fun mixedFundsShield_failedSubmissionCheckpointSubmitsNothing() = runBlocking {
        val source = readySource()
        val service = service(source, progress = { caughtUpProgress() })
        val result = service.shieldMixedFundsFromWallet(Dash.COIN, "m/9'/1'/4'/0'") {
            throw java.io.IOException("disk full")
        }
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.accountFundCalls)
    }

    @Test
    fun mixedFundsShield_unrelatedBip44LockDoesNotBlockTheDrain() = runBlocking {
        val source = readySource().apply {
            shieldLocks = {
                listOf(
                    PendingWalletShieldLock("${"ab".repeat(32)}:0", 1, 5_000_000L, coinJoinFunded = false),
                    // A consumed CoinJoin lock is history, not pending work.
                    PendingWalletShieldLock("${"cd".repeat(32)}:0", 4, 5_000_000L, coinJoinFunded = true)
                )
            }
        }
        val service = service(source, progress = { caughtUpProgress() })

        val result = service.shieldMixedFundsFromWallet(Dash.COIN, "m/9'/1'/4'/0'")

        assertEquals(SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED), result)
        assertEquals(0, service.pendingCoinJoinShieldLockCount())
        assertEquals(1, service.pendingWalletShieldLockCount())
        assertEquals(1, source.accountFundCalls)
        assertEquals(0, source.fundCalls)
    }

    /**
     * The native CoinJoin drain spends the whole account and cannot exclude
     * an input: an app-locked (e.g. CrowdNode) CoinJoin output — or a check
     * that cannot answer — refuses it before the submission checkpoint.
     */
    @Test
    fun mixedFundsShield_appLockedCoinJoinOutputRefusesTheDrain() = runBlocking {
        for (guard in listOf<() -> Boolean>({ true }, { throw IllegalStateException("wallet unavailable") })) {
            val source = readySource()
            val service = service(source, progress = { caughtUpProgress() }, hasAppLockedCoinJoinOutputs = guard)
            var checkpointed = false
            val result = service.shieldMixedFundsFromWallet(Dash.COIN, "m/9'/1'/4'/0'") { checkpointed = true }
            assertTrue(result is SdkWriteResult.NotBroadcast)
            assertFalse(checkpointed)
            assertEquals(0, source.accountFundCalls)
            // The BIP44 shield never touches the CoinJoin account: unaffected.
            assertTrue(service.shieldFromWallet(Dash.COIN) is SdkWriteResult.Broadcast)
            assertEquals(1, source.fundCalls)
        }

        // A lock taken after the first check is caught right before the native call.
        val source = readySource()
        var checks = 0
        val service = service(source, progress = { caughtUpProgress() }, hasAppLockedCoinJoinOutputs = { ++checks > 1 })
        var checkpointed = false
        val result = service.shieldMixedFundsFromWallet(Dash.COIN, "m/9'/1'/4'/0'") { checkpointed = true }
        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals(2, checks)
        assertFalse(checkpointed)
        assertEquals(0, source.accountFundCalls)

        // A pending CoinJoin lock still answers as recovery (never a NotBroadcast that drops the record).
        val pending = readySource().apply {
            shieldLocks = { listOf(PendingWalletShieldLock("ab".repeat(32) + ":0", 3, 1L, coinJoinFunded = true)) }
        }
        assertEquals(
            SdkWriteResult.Broadcast(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY),
            service(pending, progress = { caughtUpProgress() }, hasAppLockedCoinJoinOutputs = { true })
                .shieldMixedFundsFromWallet(Dash.COIN, "m/9'/1'/4'/0'")
        )
    }

    /**
     * Regression guard for the bricked-shields bug: the gate must NOT
     * consult ANY dashj comparison. A caught-up engine shields regardless
     * of how far the frozen held dashj wallet has diverged post-migration
     * (parity would otherwise close the gate forever after the first
     * external receive). The fixture is not even SYNCED — caught-up alone
     * funds the shield.
     */
    @Test
    fun shieldFromWallet_noDashjParityRequired() = runBlocking {
        val source = readySource()
        val service = service(source, progress = { caughtUpProgress() })

        assertTrue(service.isWalletShieldingAvailable())
        assertTrue(service.shieldFromWallet(Dash.COIN) is SdkWriteResult.Broadcast)
        assertEquals(1, source.fundCalls)
    }

    // ── shieldFromWallet: the SDK-SPV ensure-running preflight ────────────

    @Test
    fun shieldFromWallet_l1SpvNotRunning_isNotBroadcast_beforeAnySpend() = runBlocking {
        val source = readySource()
        // The gate passes (fresh MATCH) but the SDK's SPV — the L1 asset-lock
        // broadcaster — cannot be brought up: refuse to spend, cleanly and
        // retryably, before fundFromAssetLock is ever reached.
        val service = service(source, progress = { caughtUpProgress() }, ensureL1SpvRunning = { false })

        val result = service.shieldFromWallet(Dash.COIN)

        assertTrue(result is SdkWriteResult.NotBroadcast)
        assertEquals("L1 sync not running", (result as SdkWriteResult.NotBroadcast).reason)
        assertEquals(0, source.fundCalls)
    }

    @Test
    fun shieldFromWallet_ensuresSpvRunning_afterTheGate_beforeBroadcast() = runBlocking {
        val source = readySource()
        val order = mutableListOf<String>()
        source.onFund = { order += "fund" }
        val service = service(
            source,
            progress = { caughtUpProgress() },
            ensureL1SpvRunning = { order += "ensureSpv"; true }
        )

        val result = service.shieldFromWallet(Dash.COIN)

        assertTrue(result is SdkWriteResult.Broadcast)
        // The SPV ensure runs (after the funding gate passed) and strictly
        // BEFORE the asset-lock build/broadcast.
        assertEquals(listOf("ensureSpv", "fund"), order)
        assertEquals(1, source.fundCalls)
    }

    @Test
    fun shieldFromWallet_armsSelfSpendGrace_beforeBroadcast() = runBlocking {
        val source = readySource()
        val order = mutableListOf<String>()
        source.onFund = { order += "fund" }
        val service = service(
            source,
            progress = { caughtUpProgress() },
            noteSelfSpendBroadcast = { order += "grace" }
        )

        assertTrue(service.shieldFromWallet(Dash.COIN) is SdkWriteResult.Broadcast)
        // The asset lock is a self-spend whose inputs the SDK doesn't debit
        // until it confirms; the parity grace must be armed strictly BEFORE
        // the broadcast or the inflated-balance decider can hard-reset the
        // shadow state right after a clean shield.
        assertEquals(listOf("grace", "fund"), order)
    }

    @Test
    fun shieldFromWallet_gateClosed_doesNotArmTheGrace() = runBlocking {
        val source = readySource()
        var armed = false
        val service = service(source, progress = { ShadowSyncProgress.IDLE }, noteSelfSpendBroadcast = { armed = true })

        assertTrue(service.shieldFromWallet(Dash.COIN) is SdkWriteResult.NotBroadcast)
        assertFalse(armed)
    }

    // ── observeWalletShieldingAvailable: the LIVE funding gate ────────────

    @Test
    fun observeWalletShieldingAvailable_reDerivesGate_perProgressEmission() = runBlocking {
        val source = readySource()
        // A progress feed that starts closed (IDLE → engine not running),
        // then emits a caught-up scan — the SDK filter scan reaching the
        // tip while a screen is already open.
        val progressFeed = MutableStateFlow(ShadowSyncProgress.IDLE)
        val service = service(source, progress = { progressFeed.value }, progressFlow = progressFeed)

        // Subscribe first (UNDISPATCHED runs the collector to its first
        // suspension, so it has already emitted the initial closed state
        // before we move the feed) — no re-subscription happens after.
        val seen = mutableListOf<Boolean>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            service.observeWalletShieldingAvailable().take(2).collect { seen.add(it) }
        }

        // The SDK filter scan catches up to the tip while collection is live.
        progressFeed.value = caughtUpProgress()
        job.join()

        // Closed first (funds-safe default), open once caught-up lands —
        // proving the gate is observed per emission, not snapshotted once.
        assertEquals(listOf(false, true), seen)
    }

    @Test
    fun observeWalletShieldingAvailable_flagOff_staysClosed() = runBlocking {
        val source = readySource()
        val service = service(
            source, enabled = false, progressFlow = flowOf(caughtUpProgress())
        )
        // Even with a caught-up scan, the shielded flag being off keeps
        // the observed gate closed (inert).
        assertFalse(service.observeWalletShieldingAvailable().first())
    }

    // ── shieldFromWallet: staged-failure classification ───────────────────

    @Test
    fun shieldFromWallet_failureWithNewTrackedLock_isLockPendingRetry() = runBlocking {
        val source = readySource()
        val service = service(source, progress = { caughtUpProgress() })

        // Before the attempt: no locks. After: the lock the failed attempt
        // broadcast (the Rust side tracks it before broadcasting).
        var funded = false
        source.shieldLocks = {
            if (funded) listOf(PendingWalletShieldLock("aa".repeat(32) + ":0", 1, 100_000)) else emptyList()
        }
        source.onFund = { funded = true; throw RuntimeException("transition timed out") }

        val result = service.shieldFromWallet(Dash.COIN)

        assertEquals(
            SdkWriteResult.Broadcast(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY),
            result
        )
    }

    @Test
    fun shieldFromWallet_definitiveRejection_noNewLock_isNotBroadcast() = runBlocking {
        val source = readySource()
        val service = service(source, progress = { caughtUpProgress() })
        source.onFund = { throw DashSdkError.InvalidParameter("insufficient funds") }

        assertTrue(service.shieldFromWallet(Dash.COIN) is SdkWriteResult.NotBroadcast)
    }

    @Test
    fun shieldFromWallet_unknownFailure_noNewLock_isAmbiguous() = runBlocking {
        val source = readySource()
        val service = service(source, progress = { caughtUpProgress() })
        source.onFund = { throw RuntimeException("connection reset") }

        // The persistence bridge is async: no row is NOT proof of no
        // broadcast — must stay Ambiguous, never NotBroadcast.
        assertTrue(service.shieldFromWallet(Dash.COIN) is SdkWriteResult.Ambiguous)
    }

    @Test
    fun shieldFromWallet_preexistingLock_doesNotMaskDefinitiveRejection() = runBlocking {
        val source = readySource()
        val service = service(source, progress = { caughtUpProgress() })

        // A lock from an EARLIER interrupted attempt exists before this
        // call — it must not be misread as this call's evidence.
        source.shieldLocks = { listOf(PendingWalletShieldLock("bb".repeat(32) + ":1", 2, 5_000)) }
        source.onFund = { throw DashSdkError.InvalidParameter("bad params") }

        assertTrue(service.shieldFromWallet(Dash.COIN) is SdkWriteResult.NotBroadcast)
    }

    @Test
    fun shieldFromWallet_unreadableLockStateBefore_refusesToSpend() = runBlocking {
        val source = readySource()
        val service = service(source, progress = { caughtUpProgress() })
        source.shieldLocks = { throw RuntimeException("db closed") }

        assertTrue(service.shieldFromWallet(Dash.COIN) is SdkWriteResult.NotBroadcast)
        assertEquals(0, source.fundCalls)
    }

    // ── The staged-retry sweep ────────────────────────────────────────────

    @Test
    fun resumeSweep_resumesOnlyUnconsumedLocks_withReversedTxid() = runBlocking {
        val source = readySource()
        val service = service(source, progress = { caughtUpProgress() })

        val txidDisplayHex = (1..32).joinToString("") { "%02x".format(it) }
        source.shieldLocks = {
            listOf(
                PendingWalletShieldLock("$txidDisplayHex:2", 1, 100_000), // Broadcast → resumable
                PendingWalletShieldLock("cc".repeat(32) + ":0", 4, 100_000) // Consumed → skipped
            )
        }

        assertEquals(1, service.resumePendingWalletShields())
        assertEquals(1, source.resumeCalls)

        val (txid, vout) = source.resumedOutPoints.single()
        assertEquals(2, vout)
        // Display hex is byte-reversed from the raw wire-order txid.
        assertArrayEquals(ByteArray(32) { (32 - it).toByte() }, txid)
        assertArrayEquals(source.defaultAddress, source.lastResumeRecipient)
    }

    @Test
    fun resumeSweep_isolatesPerLockFailures() = runBlocking {
        val source = readySource()
        val service = service(source, progress = { caughtUpProgress() })
        source.shieldLocks = {
            listOf(
                PendingWalletShieldLock("aa".repeat(32) + ":0", 1, 1),
                PendingWalletShieldLock("bb".repeat(32) + ":0", 2, 1)
            )
        }
        source.onResume = { txid, _ ->
            if (txid[0] == 0xaa.toByte()) throw RuntimeException("still no chainlock")
        }

        // The first lock fails, the second still resumes.
        assertEquals(1, service.resumePendingWalletShields())
        assertEquals(2, source.resumeCalls)
    }

    @Test
    fun resumeSweep_capsAttemptsPerOutpointPerProcess() = runBlocking {
        val source = readySource()
        val service = service(source, progress = { caughtUpProgress() })
        source.shieldLocks = { listOf(PendingWalletShieldLock("aa".repeat(32) + ":0", 1, 1)) }
        source.onResume = { _, _ -> throw RuntimeException("permanently stuck") }

        repeat(5) { assertEquals(0, service.resumePendingWalletShields()) }

        // 3 attempts max per process, later sweeps skip the outpoint.
        assertEquals(3, source.resumeCalls)
    }

    @Test
    fun resumeSweep_userRequestGetsOneAttemptPastTheBudget() = runBlocking {
        val source = readySource()
        val service = service(source, progress = { caughtUpProgress() })
        source.shieldLocks = { listOf(PendingWalletShieldLock("aa".repeat(32) + ":0", 1, 1)) }
        source.onResume = { _, _ -> throw RuntimeException("no chainlock yet") }
        repeat(4) { assertEquals(0, service.resumePendingWalletShields()) }
        assertEquals(3, source.resumeCalls)

        // Connectivity is back and the user taps Try again: one real attempt.
        source.onResume = { _, _ -> }
        assertEquals(1, service.resumePendingWalletShieldsOnUserRequest())
        assertEquals(4, source.resumeCalls)
        // Unattended sweeps stay capped.
        service.resumePendingWalletShields()
        assertEquals(4, source.resumeCalls)
    }

    /**
     * SDK `AssetLockStatus` discriminants (AssetLockEntity.statusRaw): every
     * status but Consumed (4) is still recoverable. RecoveredFromChain (5)
     * is what the chainlock promotion writes for a ChainLocked lock nothing
     * live is completing — it must stay pending, not drop out of `0..3`.
     */
    @Test
    fun pendingWalletShieldLock_everyStatusButConsumedIsResumable() {
        for (status in listOf(0, 1, 2, 3, 5)) {
            val lock = PendingWalletShieldLock("aa".repeat(32) + ":0", status, 1, coinJoinFunded = true)
            assertTrue("status $status", lock.resumable)
            assertTrue("status $status", lock.pendingCoinJoinDrain)
        }
        val consumed = PendingWalletShieldLock("aa".repeat(32) + ":0", 4, 1, coinJoinFunded = true)
        assertFalse(consumed.resumable)
        assertFalse(consumed.pendingCoinJoinDrain)
        // Platform reported a FINAL lock consumed: no longer pending.
        for (status in listOf(2, 3, 5)) {
            val reported = PendingWalletShieldLock("aa".repeat(32) + ":0", status, 1, true, platformConsumed = true)
            assertFalse("status $status", reported.resumable)
            assertFalse("status $status", reported.pendingCoinJoinDrain)
        }
        // A not-yet-final lock stays pending whatever was reported.
        assertTrue(PendingWalletShieldLock("aa".repeat(32) + ":0", 1, 1, true, platformConsumed = true).resumable)
        // Cutover evidence keeps the 0..3 semantics: 5 never blocks it.
        assertEquals(
            listOf(true, true, true, true, false, false),
            (0..5).map { PendingWalletShieldLock("aa".repeat(32) + ":0", it, 1).blocksCutover }
        )
    }

    /**
     * Field failure (build 41): CoinJoin lock A is IS/Chain-locked (later
     * RecoveredFromChain) but its Platform transition keeps failing, the
     * per-process resume budget is spent, and new CoinJoin coins arrive. A
     * second shield must not drain them: A stays a pending CoinJoin lock,
     * blocks the drain, and only the user's Try again resumes A once more.
     */
    @Test
    fun mixedFundsShield_unresolvedCoinJoinLockPastTheResumeBudgetBlocksASecondDrain() = runBlocking {
        for (status in listOf(2, 3, 5)) {
            val source = readySource()
            val service = service(source, progress = { caughtUpProgress() })
            val lockA = "02".repeat(32) + ":0"
            source.shieldLocks = { listOf(PendingWalletShieldLock(lockA, status, 84_997_634L, coinJoinFunded = true)) }
            source.onResume = { _, _ -> throw RuntimeException("SDK error: cannot decode consensus error") }
            repeat(4) { assertEquals("status $status", 0, service.resumePendingWalletShields()) }
            assertEquals("status $status: capped at 3 attempts", 3, source.resumeCalls)

            assertEquals("status $status", 1, service.pendingCoinJoinShieldLockCount())
            // Cutover evidence: 0..3 only (a historical 5 must never block a cutover).
            assertEquals("status $status", if (status == 5) 0 else 1, service.pendingWalletShieldLockCount())
            assertEquals(
                "status $status",
                SdkWriteResult.Broadcast(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY),
                service.shieldMixedFundsFromWallet(Dash.COIN, "m/9'/1'/4'/0'")
            )
            assertEquals("status $status: no second drain", 0, source.accountFundCalls)
            assertEquals("status $status", 0, source.fundCalls)

            // Try again: lock A only, one attempt past the budget.
            assertEquals("status $status", 0, service.resumePendingWalletShieldsOnUserRequest())
            assertEquals("status $status", 4, source.resumeCalls)
            assertTrue("status $status", source.resumedOutPoints.all { it.first.all { b -> b == 0x02.toByte() } })
            assertEquals("status $status", 0, source.accountFundCalls)
        }
    }

    /**
     * Platform answers a status-5 CoinJoin lock's resume with the typed
     * already-consumed signal (the SDK keeps it at 5, non-terminal): that one
     * round trip records it durably, and it stops blocking a fresh drain and
     * the sweep — in this process and after a restart.
     */
    @Test
    fun resumeSweep_alreadyConsumedStatus5LockStopsBlockingTheDrainDurably() = runBlocking {
        val failures = listOf<(String) -> Throwable>(
            { DashSdkError.PlatformWallet.AssetLockAlreadyConsumed(it) },
            // Wrapped by the JNI: matched down the cause chain.
            { RuntimeException("resume failed", DashSdkError.PlatformWallet.AssetLockAlreadyConsumed(it)) }
        )
        for ((i, failure) in failures.withIndex()) {
            for (userRequested in listOf(false, true)) {
                val label = "failure $i, userRequested $userRequested"
                val consumed = ConsumedStore()
                val source = readySource()
                val lockA = "02".repeat(32) + ":0"
                source.shieldLocks = { listOf(PendingWalletShieldLock(lockA, 5, 84_997_634L, coinJoinFunded = true)) }
                source.onResume = { _, _ -> throw failure("asset lock $lockA already consumed") }
                val service = service(source, consumed = consumed, progress = { caughtUpProgress() })
                assertTrue(label, service.ensureShieldedReady())
                assertEquals(label, 1, service.pendingCoinJoinShieldLockCount())

                val resumed = if (userRequested) {
                    service.resumePendingWalletShieldsOnUserRequest()
                } else {
                    service.resumePendingWalletShields()
                }
                assertEquals(label, 0, resumed)
                assertEquals(label, setOf(lockA), consumed.value)
                assertEquals(label, 0, service.pendingCoinJoinShieldLockCount())
                // Not proven again on later sweeps.
                service.resumePendingWalletShields()
                assertEquals(label, 1, source.resumeCalls)

                // A new process (fresh service, same store) reads the record.
                val restarted = service(source, consumed = consumed, progress = { caughtUpProgress() })
                assertTrue(label, restarted.ensureShieldedReady())
                assertEquals(label, 0, restarted.pendingCoinJoinShieldLockCount())
                assertEquals(label, 0, restarted.resumePendingWalletShields())
                assertEquals(label, 1, source.resumeCalls)
                // The user's next shield drains the new coins instead of waiting on A.
                assertEquals(
                    label,
                    SdkWriteResult.Broadcast(ShieldFromWalletOutcome.COMPLETED),
                    restarted.shieldMixedFundsFromWallet(Dash.COIN, "m/9'/1'/4'/0'")
                )
                assertEquals(label, 1, source.accountFundCalls)
            }
        }
    }

    /**
     * Any other resume failure — including the untyped "already completely
     * used" wording — proves nothing: the status-5 CoinJoin lock (e.g. live
     * lock A, whose transition never reached Platform) keeps blocking.
     */
    @Test
    fun resumeSweep_otherFailureKeepsAStatus5CoinJoinLockBlocking() = runBlocking {
        val failures = listOf(
            RuntimeException("SDK error: cannot decode consensus error"),
            RuntimeException("asset lock output 0 already completely used"),
            DashSdkError.PlatformWallet.AssetLockNotTracked("not tracked")
        )
        for (failure in failures) {
            val consumed = ConsumedStore()
            val source = readySource()
            source.shieldLocks = {
                listOf(PendingWalletShieldLock("02".repeat(32) + ":0", 5, 84_997_634L, coinJoinFunded = true))
            }
            source.onResume = { _, _ -> throw failure }
            val service = service(source, consumed = consumed, progress = { caughtUpProgress() })
            assertTrue(service.ensureShieldedReady())
            assertEquals(failure.message, 0, service.resumePendingWalletShieldsOnUserRequest())
            assertNull(failure.message, consumed.value)
            assertEquals(failure.message, 1, service.pendingCoinJoinShieldLockCount())
            assertEquals(
                failure.message,
                SdkWriteResult.Broadcast(ShieldFromWalletOutcome.SHIELD_PENDING_RETRY),
                service.shieldMixedFundsFromWallet(Dash.COIN, "m/9'/1'/4'/0'")
            )
            assertEquals(failure.message, 0, source.accountFundCalls)
        }
        // An unreadable record never unblocks a lock it may list.
        val consumed = ConsumedStore().apply {
            value = setOf("02".repeat(32) + ":0")
            readFailure = java.io.IOException("unreadable preferences")
        }
        val source = readySource().apply {
            shieldLocks = { listOf(PendingWalletShieldLock("02".repeat(32) + ":0", 5, 1L, coinJoinFunded = true)) }
        }
        val unreadable = service(source, consumed = consumed)
        assertTrue(unreadable.ensureShieldedReady())
        assertEquals(1, unreadable.pendingCoinJoinShieldLockCount())
    }

    /**
     * Restore/scan rebuilds every historical chain-locked shield top-up as
     * RecoveredFromChain (5). Those must never hold up the cutover — not even
     * before any resume could record them consumed — while live 0..3 locks
     * still do.
     */
    @Test
    fun cutoverEvidence_historicalStatus5LocksNeverBlock() = runBlocking {
        val source = readySource()
        val service = service(source, progress = { caughtUpProgress() })
        assertTrue(service.ensureShieldedReady())
        source.shieldLocks = {
            listOf(
                PendingWalletShieldLock("aa".repeat(32) + ":0", 5, 1L, coinJoinFunded = false),
                PendingWalletShieldLock("bb".repeat(32) + ":1", 5, 1L),
                PendingWalletShieldLock("cc".repeat(32) + ":0", 4, 1L)
            )
        }
        assertEquals(0, service.pendingWalletShieldLockCount())
        val historical = source.shieldLocks()
        source.shieldLocks = { historical + PendingWalletShieldLock("dd".repeat(32) + ":0", 3, 1L) }
        assertEquals(1, service.pendingWalletShieldLockCount())
    }

    @Test
    fun resumeSweep_nothingPending_isCheap() = runBlocking {
        val source = readySource()
        val service = service(source, progress = { caughtUpProgress() })

        assertEquals(0, service.resumePendingWalletShields())
        assertEquals(1, source.lockQueries)
        assertEquals(0, source.resumeCalls)
    }

    // ── Pure helpers ──────────────────────────────────────────────────────

    @Test
    fun parseOutPointHex_parsesAndReversesToWireOrder() {
        val displayHex = (1..32).joinToString("") { "%02x".format(it) }
        val parsed = parseOutPointHex("$displayHex:7")
        assertNotNull(parsed)
        assertEquals(7, parsed!!.second)
        assertArrayEquals(ByteArray(32) { (32 - it).toByte() }, parsed.first)
    }

    @Test
    fun parseOutPointHex_rejectsMalformedInput() {
        assertNull(parseOutPointHex(""))
        assertNull(parseOutPointHex("aa:0"))
        assertNull(parseOutPointHex("zz".repeat(32) + ":0")) // not hex
        assertNull(parseOutPointHex("aa".repeat(32))) // no vout
        assertNull(parseOutPointHex("aa".repeat(32) + ":x"))
        assertNull(parseOutPointHex("aa".repeat(32) + ":-1"))
    }

    @Test
    fun ceilCreditsToDuffs_roundsUp() {
        assertEquals(0L, ceilCreditsToDuffs(0))
        assertEquals(1L, ceilCreditsToDuffs(1))
        assertEquals(1L, ceilCreditsToDuffs(1_000))
        assertEquals(2L, ceilCreditsToDuffs(1_001))
    }

    // ── Pure settlement gate ──────────────────────────────────────────────

    @Test
    fun spendsReflected_decisionTable() {
        val a = note(value = 1L, nullifierSeed = 1)
        val b = note(value = 2L, nullifierSeed = 2)
        val change = note(value = 3L, nullifierSeed = 3)
        val sent = ByteArray(32) { 0x55 }
        val now = 1_800_000_000_000L
        fun row(status: Int, createdAtMs: Long = now, nullifiers: ByteArray = ByteArray(0), cmxs: ByteArray = ByteArray(0)) =
            activity(
                entryId = 1, direction = 1, status = status, createdAtMs = createdAtMs,
                spentNullifiers = nullifiers, noteCmxs = cmxs
            )
        fun reflected(rows: List<ShieldedActivityEntity>, notes: List<ShieldedNoteEntity>, outgoing: List<ByteArray> = emptyList(), since: Long = 0L) =
            shieldedSpendsReflected(rows, notes, outgoing, since)
        val aSpent = a.copy(isSpent = true)

        assertTrue(reflected(emptyList(), listOf(a, b)))
        // input still unspent: Pending and Confirmed both close the gate; Failed doesn't
        assertFalse(reflected(listOf(row(0, nullifiers = a.nullifier)), listOf(a, b)))
        assertFalse(reflected(listOf(row(1, nullifiers = a.nullifier)), listOf(a, b)))
        assertTrue(reflected(listOf(row(2, nullifiers = a.nullifier, cmxs = change.cmx)), listOf(a, b)))
        // input spent, but the change output not stored yet: closed; stored: open
        assertFalse(reflected(listOf(row(1, nullifiers = a.nullifier, cmxs = change.cmx)), listOf(aSpent, b)))
        assertTrue(reflected(listOf(row(1, nullifiers = a.nullifier, cmxs = change.cmx)), listOf(aSpent, b, change)))
        // ... even when the change was spent again by a later spend
        assertTrue(reflected(listOf(row(1, nullifiers = a.nullifier, cmxs = change.cmx)), listOf(aSpent, b, change.copy(isSpent = true))))
        // every visible cmx must be known: a send counts once the scan recovers it as outgoing
        val transfer = row(1, nullifiers = a.nullifier, cmxs = sent + change.cmx)
        assertFalse(reflected(listOf(transfer), listOf(aSpent, change)))
        assertTrue(reflected(listOf(transfer), listOf(aSpent, change), outgoing = listOf(sent)))
        // a note-less shield: gated on its new note
        assertFalse(reflected(listOf(row(1, cmxs = change.cmx)), listOf(b)))
        assertTrue(reflected(listOf(row(1, cmxs = change.cmx)), listOf(b, change)))
        // concatenated nullifiers: any one still unspent closes it
        assertFalse(reflected(listOf(row(1, nullifiers = a.nullifier + b.nullifier)), listOf(b)))
        // rows before the since are ignored; at the since they count
        assertTrue(reflected(listOf(row(0, createdAtMs = now - 10, nullifiers = a.nullifier)), listOf(a), since = now - 9))
        assertFalse(reflected(listOf(row(0, createdAtMs = now - 9, nullifiers = a.nullifier)), listOf(a), since = now - 9))
        // Unknown (may still execute) closes it like Pending
        assertFalse(reflected(listOf(row(3, nullifiers = a.nullifier)), listOf(a)))
        // malformed lists are unreadable: closed
        assertFalse(reflected(listOf(row(1, nullifiers = ByteArray(33))), emptyList()))
        assertFalse(reflected(listOf(row(1, cmxs = ByteArray(31))), emptyList()))
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private fun note(value: Long, isSpent: Boolean = false, nullifierSeed: Int = value.toInt()) = ShieldedNoteEntity(
        nullifier = ByteArray(32) { nullifierSeed.toByte() },
        walletId = walletIdBytes,
        accountIndex = 0,
        position = 0L,
        cmx = ByteArray(32) { (nullifierSeed + 100).toByte() },
        blockHeight = 1L,
        isSpent = isSpent,
        value = value,
        noteData = ByteArray(0)
    )

    private fun activity(
        entryId: Int,
        direction: Int,
        status: Int,
        amount: Long = 1_000L,
        createdAtMs: Long = 0L,
        memo: ByteArray = ByteArray(0),
        spentNullifiers: ByteArray = ByteArray(0),
        noteCmxs: ByteArray = ByteArray(0)
    ) = ShieldedActivityEntity(
        walletId = walletIdBytes,
        accountIndex = 0,
        entryId = ByteArray(32).also { it[0] = entryId.toByte() },
        kindTag = 3,
        direction = direction,
        status = status,
        amount = amount,
        fee = 0L,
        hasFee = false,
        blockHeight = 0L,
        hasBlockHeight = false,
        createdAtMs = createdAtMs,
        memo = memo,
        noteCmxs = noteCmxs,
        spentNullifiers = spentNullifiers
    )

    private fun outgoing(cmx: ByteArray) = ShieldedOutgoingNoteEntity(
        walletId = walletIdBytes,
        accountIndex = 0,
        cmx = cmx,
        recipient = ByteArray(43),
        value = 1L,
        memo = ByteArray(0),
        blockHeight = 1L
    )

    /** Build a 36-byte on-chain text memo: LE u32 kind 1 + zero-padded UTF-8. */
    private fun textMemo(text: String): ByteArray {
        val out = ByteArray(36)
        out[0] = 1
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 32)
        bytes.copyInto(out, destinationOffset = 4)
        return out
    }
}
