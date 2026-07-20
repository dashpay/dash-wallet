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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.dashfoundation.dashsdk.wallet.SpvSubProgress
import org.dashfoundation.dashsdk.wallet.SpvSyncProgressData
import org.dashfoundation.dashsdk.wallet.SpvSyncState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Host-JVM tests for the Phase 5a L1 shadow-sync parity harness: the
 * flag/lifecycle decision table (inert-when-off, requires a bound wallet,
 * single-flight start, stop teardown), the pure SPV-progress mapping, and
 * the pure parity comparison + log-line semantics. No native calls; both
 * sides of the comparison are faked via [L1ShadowSource].
 */
class L1ShadowSyncServiceTest {

    private val walletIdHex = "cd".repeat(32)

    /** Unconfined so launched loops run to their first suspension synchronously. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val dataDir = Files.createTempDirectory("l1-shadow-test").toFile()

    @After
    fun tearDown() {
        scope.cancel()
        dataDir.deleteRecursively()
    }

    private class FakeSource(
        var boundWalletId: String? = null,
        var spvRunning: Boolean = false
    ) : L1ShadowSource {
        var boundCalls = 0
        var isRunningCalls = 0
        var startCalls = 0
        var stopCalls = 0
        var lastDataDir: String? = null
        var onStart: () -> Unit = {}
        var onClearRows: () -> Unit = {}
        var onProbe: suspend () -> Unit = {}

        var sdkConfirmed = 0L
        var sdkUnconfirmed = 0L
        var sdkTxs = 0
        var dashjBalances: Pair<Long, Long>? = 0L to 0L
        var dashjTxs: Int? = 0
        var dashjChainHead: Int? = null

        var sdkUtxos: List<L1Utxo> = emptyList()
        var dashjUtxos: List<L1Utxo>? = emptyList()
        var sdkUtxoFetches = 0
        var clearSpvStorageCalls = 0
        var clearL1RowsCalls = 0

        val progressFlow = MutableStateFlow(SpvSyncProgressData.EMPTY)
        val eventStrings = MutableSharedFlow<String>()

        fun interactions() = boundCalls + isRunningCalls + startCalls + stopCalls

        override suspend fun boundWalletIdOrNull(): String? {
            boundCalls++
            return boundWalletId
        }

        override suspend fun isSpvRunning(): Boolean {
            isRunningCalls++
            return spvRunning
        }

        override suspend fun startSpv(dataDir: String) {
            startCalls++
            lastDataDir = dataDir
            onStart()
        }

        override suspend fun stopSpv() {
            stopCalls++
        }

        override fun spvProgress(): Flow<SpvSyncProgressData> = progressFlow

        override fun walletEventStrings(): Flow<String> = eventStrings

        override suspend fun sdkBalanceDuffs(walletIdHex: String): Pair<Long, Long> {
            onProbe()
            return sdkConfirmed to sdkUnconfirmed
        }

        override suspend fun sdkTxCount(walletIdHex: String): Int = sdkTxs

        override suspend fun dashjBalanceDuffs(): Pair<Long, Long>? = dashjBalances

        override suspend fun dashjTxCount(): Int? = dashjTxs

        override suspend fun dashjChainHeadHeight(): Int? = dashjChainHead

        override suspend fun sdkUnspentUtxos(walletIdHex: String): List<L1Utxo> {
            sdkUtxoFetches++
            return sdkUtxos
        }

        override suspend fun dashjUnspentUtxos(): List<L1Utxo>? = dashjUtxos

        override suspend fun clearSpvStorage() {
            clearSpvStorageCalls++
        }

        override suspend fun clearSdkL1Rows(walletIdHex: String) {
            clearL1RowsCalls++
            onClearRows()
        }
    }

    /**
     * DashPayConfig fake: the flag plus a persisted reset marker whose
     * reads see the writes ([markerWrites] doubles as the assertion
     * surface and the simulated DataStore).
     */
    private fun config(
        flag: Boolean?,
        lastResetMs: Long? = null,
        markerWrites: MutableList<Long> = mutableListOf()
    ): DashPayConfig = mockk<DashPayConfig>().also {
        coEvery { it.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) } returns flag
        coEvery { it.get(DashPayConfig.L1_SHADOW_LAST_RESET) } answers {
            markerWrites.lastOrNull() ?: lastResetMs
        }
        coEvery { it.set(DashPayConfig.L1_SHADOW_LAST_RESET, any()) } answers {
            markerWrites.add(secondArg())
            Unit
        }
    }

    /**
     * [ShadowWalletRecreator] fake: records the recovery orchestration
     * as an ordered event log (the sequencing assertion surface) and
     * exposes a controllable bind [Job] so tests can verify the shadow
     * restart WAITS for the rebind pass.
     */
    private class FakeRecreator : ShadowWalletRecreator {
        val events = mutableListOf<String>()
        val removedWalletIds = mutableListOf<String>()

        /** The job [rebindInBackground] returns; completed by default. */
        var bindJob: kotlinx.coroutines.CompletableJob =
            kotlinx.coroutines.Job().apply { complete() }
        var onRemove: () -> Unit = {}
        var onBinderReset: () -> Unit = {}

        override suspend fun stopShieldedSync() {
            events += "stopShielded"
        }

        override suspend fun removeSdkWallet(walletIdHex: String) {
            events += "removeWallet"
            removedWalletIds += walletIdHex
            onRemove()
        }

        override suspend fun resetBinderLatch() {
            events += "resetBinderLatch"
            onBinderReset()
        }

        override fun rebindInBackground(): kotlinx.coroutines.Job {
            events += "rebind"
            return bindJob
        }
    }

    private fun service(
        source: FakeSource,
        flag: Boolean? = true,
        nowMs: () -> Long = { 1_000_000L },
        lastResetMs: Long? = null,
        markerWrites: MutableList<Long> = mutableListOf(),
        parityIntervalMs: Long = L1ShadowSyncService.PARITY_INTERVAL_MS,
        watchdogIntervalMs: Long = L1ShadowSyncService.WATCHDOG_INTERVAL_MS,
        probeStallThresholdMs: Long = L1ShadowSyncService.PROBE_STALL_THRESHOLD_MS,
        recreator: ShadowWalletRecreator? = null
    ) = L1ShadowSyncService(
        source = source,
        dashPayConfig = config(flag, lastResetMs, markerWrites),
        scope = scope,
        spvDataDirPath = { dataDir.resolve("spv").absolutePath },
        nowMs = nowMs,
        parityIntervalMs = parityIntervalMs,
        watchdogIntervalMs = watchdogIntervalMs,
        probeStallThresholdMs = probeStallThresholdMs,
        recreator = recreator
    )

    // ── Lifecycle / inertness ─────────────────────────────────────────

    @Test
    fun flagOff_isProvablyInert() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        for (flag in listOf(false, null)) {
            val service = service(source, flag = flag)
            assertFalse(service.startIfEnabled())
            assertEquals(0, source.interactions())
            assertNull(service.latestParity.value)
            assertEquals(ShadowSyncProgress.IDLE, service.progress.value)
        }
    }

    @Test
    fun flagReadFailure_treatedAsOff() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        val config = mockk<DashPayConfig>()
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) } throws IllegalStateException("datastore")
        val service = L1ShadowSyncService(
            source = source,
            dashPayConfig = config,
            scope = scope,
            spvDataDirPath = { dataDir.resolve("spv").absolutePath }
        )
        assertFalse(service.startIfEnabled())
        assertEquals(0, source.interactions())
    }

    @Test
    fun unboundWallet_doesNotStartSpv_andRetriesNextTrigger() = runBlocking {
        val source = FakeSource(boundWalletId = null)
        val service = service(source)
        assertFalse(service.startIfEnabled())
        assertEquals(0, source.startCalls)

        // The wallet gets bound later; the next trigger starts the shadow.
        source.boundWalletId = walletIdHex
        assertTrue(service.startIfEnabled())
        assertEquals(1, source.startCalls)
    }

    @Test
    fun start_launchesSpvWithTheDedicatedDataDir_andLatches() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        val service = service(source)

        assertTrue(service.startIfEnabled())
        assertEquals(1, source.startCalls)
        assertEquals(dataDir.resolve("spv").absolutePath, source.lastDataDir)
        assertTrue(dataDir.resolve("spv").isDirectory) // created before startSpv

        // Single-flight latch: a second start is a cheap no-op.
        assertTrue(service.startIfEnabled())
        assertEquals(1, source.startCalls)
    }

    @Test
    fun start_skipsStartSpvWhenTheRustClientIsAlreadyRunning() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex, spvRunning = true)
        val service = service(source)
        assertTrue(service.startIfEnabled())
        assertEquals(0, source.startCalls) // reused, still latched
        assertTrue(service.startIfEnabled())
    }

    @Test
    fun startFailure_isSwallowed_notLatched_andRetryable() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        source.onStart = { throw IllegalStateException("native start failed") }
        val service = service(source)

        assertFalse(service.startIfEnabled())
        assertEquals(1, source.startCalls)

        source.onStart = {}
        assertTrue(service.startIfEnabled())
        assertEquals(2, source.startCalls)
    }

    @Test
    fun stop_stopsSpvAndResetsProgress_andIsIdempotent() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        val service = service(source)
        assertTrue(service.startIfEnabled())

        // Progress mirrored from the source while running.
        source.progressFlow.value = syncing(headers = sub(SpvSyncState.SYNCING, 10, 100))
        assertEquals(ShadowSyncPhase.HEADERS, service.progress.value.phase)

        service.stop()
        assertEquals(1, source.stopCalls)
        assertEquals(ShadowSyncProgress.IDLE, service.progress.value)

        // Stopping again is a no-op.
        service.stop()
        assertEquals(1, source.stopCalls)

        // And a fresh start works after a stop.
        assertTrue(service.startIfEnabled())
        assertEquals(2, source.startCalls)
    }

    // ── ensureSpvRunning: the shield-from-wallet broadcast guard ───────

    @Test
    fun ensureSpvRunning_startsTheSpvWhenTheFlagIsOnAndItIsNotRunning() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        source.onStart = { source.spvRunning = true } // a real start makes it live
        val service = service(source)

        assertTrue(service.ensureSpvRunning())
        assertEquals(1, source.startCalls) // brought up exactly once
        assertTrue(service.isShadowSpvRunning())
    }

    @Test
    fun ensureSpvRunning_noOpsWhenTheSpvIsAlreadyRunning() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex, spvRunning = true)
        source.onStart = { source.spvRunning = true }
        val service = service(source)

        assertTrue(service.ensureSpvRunning())
        assertEquals(0, source.startCalls) // reused, never (re)started
    }

    @Test
    fun ensureSpvRunning_restartsTheClientInPlaceWhenItWasStoppedUnderARunningLatch() = runBlocking {
        // The race the fix targets: the shadow is latched-running, but the
        // Rust SPV client was stopped out from under it (a reset window or an
        // external teardown that left the latch). A shield's ensure must
        // restart the client IN PLACE — no reset — so the broadcast has a
        // live SPV.
        val source = FakeSource(boundWalletId = walletIdHex, spvRunning = true)
        source.onStart = { source.spvRunning = true }
        val service = service(source)
        assertTrue(service.startIfEnabled())
        assertEquals(0, source.startCalls) // already running when latched

        source.spvRunning = false // stopped underneath, latch survives
        assertTrue(service.ensureSpvRunning())
        assertEquals(1, source.startCalls) // restarted in place
        assertEquals(0, source.clearL1RowsCalls) // NOT a reset
        assertTrue(service.isShadowSpvRunning())
    }

    @Test
    fun ensureSpvRunning_isInertWhileTheFlagIsOff() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        for (flag in listOf(false, null)) {
            val service = service(source, flag = flag)
            assertFalse(service.ensureSpvRunning())
        }
        assertEquals(0, source.interactions()) // nothing touched while off
    }

    @Test
    fun ensureSpvRunning_isFalseWhenNoWalletIsBound() = runBlocking {
        val source = FakeSource(boundWalletId = null)
        val service = service(source)
        assertFalse(service.ensureSpvRunning())
        assertEquals(0, source.startCalls)
    }

    // ── Parity probe ──────────────────────────────────────────────────

    @Test
    fun probeParity_publishesTheLatestReport() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        source.sdkConfirmed = 90_000
        source.sdkUnconfirmed = 10_000
        source.sdkTxs = 7
        source.dashjBalances = 100_000L to 90_000L
        source.dashjTxs = 7
        val service = service(source, nowMs = { 42_000L })

        val report = service.probeParity(walletIdHex)!!
        assertEquals(report, service.latestParity.value)
        assertTrue(report.balancesMatch)
        assertTrue(report.confirmedBalancesMatch)
        assertTrue(report.txCountsMatch)
        assertTrue(report.fullMatch)
        assertEquals(100_000, report.sdkDuffs)
        assertEquals(100_000, report.dashjDuffs)
        assertEquals(90_000, report.sdkConfirmedDuffs)
        assertEquals(90_000, report.dashjAvailableDuffs)
        assertEquals(7, report.sdkTxCount)
        assertEquals(7, report.dashjTxCount)
        assertFalse(report.sdkSynced) // shadow chain not synced in this test
        assertEquals(42_000L, report.timestampMs)
    }

    @Test
    fun probeParity_skipsWhenTheDashjWalletIsUnavailable() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        source.dashjBalances = null
        val service = service(source)
        assertNull(service.probeParity(walletIdHex))
        assertNull(service.latestParity.value)
    }

    @Test
    fun probeParity_marksSdkSyncedOnceTheShadowChainIs() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        val service = service(source)
        assertTrue(service.startIfEnabled())
        source.progressFlow.value = SpvSyncProgressData(
            overallState = SpvSyncState.SYNCED,
            overallPercentage = 100.0,
            headers = null, filterHeaders = null, filters = null, masternodes = null
        )
        assertTrue(service.probeParity(walletIdHex)!!.sdkSynced)
    }

    // ── Pure comparison + log-line semantics ──────────────────────────

    @Test
    fun buildParityReport_comparesBothBalanceVariantsIndependently() {
        // Estimated matches (pending included), confirmed-only does not:
        // the "one side saw the mempool first" signature.
        val report = buildParityReport(
            sdkConfirmedDuffs = 80_000, sdkUnconfirmedDuffs = 20_000,
            dashjEstimatedDuffs = 100_000, dashjAvailableDuffs = 100_000,
            sdkTxCount = 3, dashjTxCount = 3,
            sdkSynced = true, timestampMs = 1L
        )
        assertTrue(report.balancesMatch)
        assertFalse(report.confirmedBalancesMatch)
        assertFalse(report.fullMatch)
    }

    @Test
    fun parityLogLine_verdictsAndValues() {
        val match = buildParityReport(100, 0, 100, 100, 2, 2, sdkSynced = true, timestampMs = 1L)
        assertTrue(parityLogLine(match).startsWith("L1Parity MATCH "))

        // A mismatch before the shadow chain is synced is expected (the
        // wallet was bound with birthHeight=0 and is still scanning).
        val preSync = buildParityReport(0, 0, 100_000, 100_000, 0, 5, sdkSynced = false, timestampMs = 1L)
        assertTrue(parityLogLine(preSync).startsWith("L1Parity MISMATCH-PRESYNC "))

        // The same numbers AFTER sync are the real thing — e.g. missing
        // CoinJoin-account funds on the SDK side.
        val real = buildParityReport(0, 0, 100_000, 100_000, 0, 5, sdkSynced = true, timestampMs = 1L)
        val line = parityLogLine(real)
        assertTrue(line.startsWith("L1Parity MISMATCH "))
        assertTrue(line.contains("estimated sdk=0 dashj=100000"))
        assertTrue(line.contains("confirmed sdk=0 dashj=100000"))
        assertTrue(line.contains("tx sdk=0 dashj=5"))
    }

    @Test
    fun distinctTxCount_dedupsAcrossFundingAndSpending() {
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        val c = ByteArray(32) { 3 }
        // Two TXOs funded by a; one spent by b, one by c; b also funds a TXO.
        assertEquals(
            3,
            distinctTxCount(
                txids = listOf(a, a.copyOf(), b, null),
                spendingTxids = listOf(b.copyOf(), c, null, null)
            )
        )
        assertEquals(0, distinctTxCount(emptyList(), emptyList()))
    }

    // ── Outpoint-level diff (pure) ────────────────────────────────────

    private fun utxo(txidByte: Int, vout: Int, value: Long) =
        L1Utxo("%02x".format(txidByte).repeat(32), vout, value)

    @Test
    fun computeL1OutpointDiff_symmetricDifferenceAndTotals() {
        val shared = utxo(1, 0, 100)
        val sdkExtra = utxo(2, 1, 40)
        val dashjExtra = utxo(3, 0, 25)
        val diff = computeL1OutpointDiff(
            sdk = listOf(shared, sdkExtra),
            dashj = listOf(shared, dashjExtra)
        )
        assertEquals(2, diff.sdkCount)
        assertEquals(2, diff.dashjCount)
        assertEquals(140, diff.sdkTotalDuffs)
        assertEquals(125, diff.dashjTotalDuffs)
        assertEquals(listOf(sdkExtra), diff.sdkOnly)
        assertEquals(listOf(dashjExtra), diff.dashjOnly)
        assertTrue(diff.valueMismatched.isEmpty())
        assertTrue(diff.duplicateSdkOutpoints.isEmpty())
    }

    @Test
    fun computeL1OutpointDiff_flagsDuplicateSdkRows() {
        // The suspected corruption: the same outpoint appearing twice on
        // the SDK side. It must be flagged AND surface as an inflated
        // value mismatch against dashj's single copy.
        val original = utxo(7, 0, 100)
        val diff = computeL1OutpointDiff(
            sdk = listOf(original, original.copy()),
            dashj = listOf(original)
        )
        assertEquals(listOf("${original.outpoint} x2"), diff.duplicateSdkOutpoints)
        assertEquals(200, diff.sdkTotalDuffs)
        assertEquals(100, diff.dashjTotalDuffs)
        assertEquals(listOf(Triple(original.outpoint, 200L, 100L)), diff.valueMismatched)
        assertTrue(diff.sdkOnly.isEmpty())
        assertTrue(diff.dashjOnly.isEmpty())
    }

    @Test
    fun computeL1OutpointDiff_reportsValueMismatchOnSharedOutpoints() {
        val diff = computeL1OutpointDiff(
            sdk = listOf(utxo(1, 0, 150)),
            dashj = listOf(utxo(1, 0, 100))
        )
        assertEquals(listOf(Triple(utxo(1, 0, 0).outpoint, 150L, 100L)), diff.valueMismatched)
        assertTrue(diff.sdkOnly.isEmpty() && diff.dashjOnly.isEmpty())
    }

    @Test
    fun l1OutpointDiffLog_capsEntriesButKeepsFullTotals() {
        val sdk = (0 until 60).map { utxo(1, it, 10) }
        val diff = computeL1OutpointDiff(sdk, emptyList())
        val logged = l1OutpointDiffLog(diff, maxEntries = 50)
        assertTrue(logged.contains("sdk=60 (600 duffs)"))
        assertTrue(logged.contains("sdk-only (60)"))
        assertTrue(logged.contains("(+10 more)"))
        assertTrue(logged.contains("DUPLICATE sdk rows: none"))
        assertTrue(logged.contains("delta=600"))
    }

    // ── Auto-reset decision table (pure) ──────────────────────────────

    private fun inflated(synced: Boolean = true) = buildParityReport(
        sdkConfirmedDuffs = 200, sdkUnconfirmedDuffs = 0,
        dashjEstimatedDuffs = 100, dashjAvailableDuffs = 100,
        sdkTxCount = 1, dashjTxCount = 1, sdkSynced = synced, timestampMs = 1L
    )

    private fun deficit() = buildParityReport(
        sdkConfirmedDuffs = 50, sdkUnconfirmedDuffs = 0,
        dashjEstimatedDuffs = 100, dashjAvailableDuffs = 100,
        sdkTxCount = 1, dashjTxCount = 1, sdkSynced = true, timestampMs = 1L
    )

    private fun matching() = buildParityReport(
        sdkConfirmedDuffs = 100, sdkUnconfirmedDuffs = 0,
        dashjEstimatedDuffs = 100, dashjAvailableDuffs = 100,
        sdkTxCount = 1, dashjTxCount = 1, sdkSynced = true, timestampMs = 1L
    )

    @Test
    fun resetDecider_neverActsPreSyncOrOnMatchOrOnDeficit() {
        val decider = ShadowResetDecider()
        repeat(5) {
            assertEquals(ShadowResetDecider.Decision.NONE, decider.onProbe(inflated(synced = false)))
            assertEquals(ShadowResetDecider.Decision.NONE, decider.onProbe(matching()))
            // A deficit (sdk < dashj) is exactly the bug class the harness
            // must surface, never erase — no reset, ever.
            assertEquals(ShadowResetDecider.Decision.NONE, decider.onProbe(deficit()))
        }
    }

    @Test
    fun resetDecider_requiresThreeConsecutiveInflatedProbes() {
        val decider = ShadowResetDecider()
        assertEquals(ShadowResetDecider.Decision.NONE, decider.onProbe(inflated()))
        assertEquals(ShadowResetDecider.Decision.NONE, decider.onProbe(inflated()))
        // The inflated ledger discrepancy triggers a ONE-TIME full SDK-wallet rebuild self-heal.
        assertEquals(ShadowResetDecider.Decision.REBUILD_WALLET, decider.onProbe(inflated()))
    }

    @Test
    fun resetDecider_anyNonInflatedProbeBreaksTheStreak() {
        val decider = ShadowResetDecider()
        decider.onProbe(inflated())
        decider.onProbe(inflated())
        assertEquals(ShadowResetDecider.Decision.NONE, decider.onProbe(matching())) // streak reset
        decider.onProbe(inflated())
        assertEquals(ShadowResetDecider.Decision.NONE, decider.onProbe(inflated()))
        assertEquals(ShadowResetDecider.Decision.REBUILD_WALLET, decider.onProbe(inflated()))
    }

    @Test
    fun resetDecider_recentSelfSpend_suppressesInflatedStreaks() {
        // A Phase 5b SDK self-spend legitimately inflates the SDK view until
        // the tx is mined and filter-scanned (dashj drops its balance at
        // mempool time, the compact-filter scan only at the next block) —
        // marked probes must never feed the rebuild streak.
        val decider = ShadowResetDecider()
        repeat(5) {
            assertEquals(
                ShadowResetDecider.Decision.NONE,
                decider.onProbe(inflated(), recentSelfSpendMarker = true)
            )
        }
        // The marker also ZEROES the streak: two unmarked + one marked +
        // two unmarked never reaches the three-consecutive threshold…
        repeat(2) { assertEquals(ShadowResetDecider.Decision.NONE, decider.onProbe(inflated())) }
        assertEquals(
            ShadowResetDecider.Decision.NONE,
            decider.onProbe(inflated(), recentSelfSpendMarker = true)
        )
        repeat(2) { assertEquals(ShadowResetDecider.Decision.NONE, decider.onProbe(inflated())) }
        // …and a genuine post-grace inflation self-heals (REBUILD) after three.
        assertEquals(ShadowResetDecider.Decision.REBUILD_WALLET, decider.onProbe(inflated()))
    }

    @Test
    fun dashjChainCaughtUp_gateTruthTable() {
        // Caught up: at the tip, ahead of it, or within the tolerance.
        assertTrue(isDashjChainCaughtUp(1_511_575, 1_511_575L))
        assertTrue(isDashjChainCaughtUp(1_511_580, 1_511_575L))
        assertTrue(isDashjChainCaughtUp(1_511_573, 1_511_575L)) // tip - tolerance
        // Behind by more than the tolerance: mid-sync/replay — not caught up.
        assertFalse(isDashjChainCaughtUp(1_511_572, 1_511_575L))
        assertFalse(isDashjChainCaughtUp(900_000, 1_511_575L)) // the live replay shape
        // Missing evidence is conservative (suppresses the reset, never fires it).
        assertFalse(isDashjChainCaughtUp(null, 1_511_575L)) // dashj wallet not loaded
        assertFalse(isDashjChainCaughtUp(1_511_575, 0L)) // SDK snapshot carries no heights

        // The SDK tip reference: target while syncing, max of both once synced.
        assertEquals(0L, ShadowSyncProgress.IDLE.bestKnownTipHeight)
        val progress = ShadowSyncProgress(ShadowSyncPhase.SYNCED, 100.0, 1_511_575, 1_511_570, 0, 0)
        assertEquals(1_511_575L, progress.bestKnownTipHeight)
        assertEquals(1_511_575L, progress.copy(headerHeight = 1_511_570, headerTarget = 1_511_575).bestKnownTipHeight)
    }

    @Test
    fun resetDecider_dashjNotCaughtUp_suppressesAndZeroesInflatedStreaks() {
        // The live 02:38–02:42 incident: the SDK finished its scan (synced,
        // full balance) while dashj was still replaying its chain download —
        // sdk > dashj on every probe, but the inflated rule must not fire
        // until dashj's chain head reaches the tip.
        val decider = ShadowResetDecider()
        repeat(5) {
            assertEquals(
                ShadowResetDecider.Decision.NONE,
                decider.onProbe(inflated(), dashjChainCaughtUp = false)
            )
        }
        // Not-caught-up probes also ZERO the streak (the stability
        // requirement): the condition must hold for the FULL window with
        // dashj genuinely synced throughout.
        repeat(2) { assertEquals(ShadowResetDecider.Decision.NONE, decider.onProbe(inflated())) }
        assertEquals(
            ShadowResetDecider.Decision.NONE,
            decider.onProbe(inflated(), dashjChainCaughtUp = false)
        )
        repeat(2) { assertEquals(ShadowResetDecider.Decision.NONE, decider.onProbe(inflated())) }
        // A genuinely inflated view with BOTH engines synced self-heals (REBUILD).
        assertEquals(ShadowResetDecider.Decision.REBUILD_WALLET, decider.onProbe(inflated()))
    }

    @Test
    fun resetDecider_dashjNotCaughtUp_doesNotDisturbTheDeficitRows() {
        // The deficit direction is deliberately ungated (its empty-deficit
        // signature is SDK-side): the caught-up bit must not change deficit
        // handling either way.
        val decider = ShadowResetDecider()
        repeat(3) {
            assertEquals(
                ShadowResetDecider.Decision.NONE,
                decider.onProbe(deficit(), dashjChainCaughtUp = false)
            )
        }
        repeat(2) {
            assertEquals(
                ShadowResetDecider.Decision.NONE,
                decider.onProbe(
                    emptyDeficit(), scanLooksComplete = true, dashjChainCaughtUp = false
                )
            )
        }
        // A persistent empty deficit self-heals with the one-time full rebuild too.
        assertEquals(
            ShadowResetDecider.Decision.REBUILD_WALLET,
            decider.onProbe(
                emptyDeficit(), scanLooksComplete = true, dashjChainCaughtUp = false
            )
        )
    }

    @Test
    fun resetDecider_selfSpendMarker_doesNotDisturbTheDeficitRows() {
        // The deficit direction needs no self-spend guard (a post-send
        // wallet always has sdkTxCount > 0, so the empty-deficit signature
        // cannot form); the marker must not change deficit handling.
        val decider = ShadowResetDecider()
        repeat(5) {
            assertEquals(
                ShadowResetDecider.Decision.NONE,
                decider.onProbe(deficit(), recentSelfSpendMarker = true)
            )
            assertEquals(ShadowResetDecider.Decision.NONE, decider.onProbe(deficit()))
        }
    }

    @Test
    fun resetDecider_rebuildsOncePerProcess_thenStandsDownOnce_thenSilent() {
        val decider = ShadowResetDecider()
        repeat(2) { decider.onProbe(inflated()) }
        assertEquals(ShadowResetDecider.Decision.REBUILD_WALLET, decider.onProbe(inflated()))

        // The rebuild stops the probe loop and re-scans: un-synced probes
        // zero the streak.
        assertEquals(ShadowResetDecider.Decision.NONE, decider.onProbe(inflated(synced = false)))

        // A FULL post-rebuild resync still inflated → deterministic SDK bug:
        // STAND_DOWN exactly once, then silence (no churn — never rebuilds twice).
        repeat(2) {
            assertEquals(ShadowResetDecider.Decision.NONE, decider.onProbe(inflated()))
        }
        assertEquals(ShadowResetDecider.Decision.STAND_DOWN, decider.onProbe(inflated()))
        repeat(4) {
            assertEquals(ShadowResetDecider.Decision.NONE, decider.onProbe(inflated()))
        }
    }

    @Test
    fun resetDecider_rebuildLatchIsSharedAcrossDirections() {
        // One full wallet rebuild fixes the ledger regardless of direction;
        // a mismatch that flips direction after the rebuild must NOT rebuild
        // again (no churn) — it stands down.
        val decider = ShadowResetDecider()
        repeat(2) { decider.onProbe(inflated()) }
        assertEquals(ShadowResetDecider.Decision.REBUILD_WALLET, decider.onProbe(inflated()))
        // Post-rebuild resync now shows the OTHER direction (empty deficit):
        // the shared latch means stand down, not a second rebuild.
        repeat(2) {
            assertEquals(
                ShadowResetDecider.Decision.NONE,
                decider.onProbe(emptyDeficit(), scanLooksComplete = true)
            )
        }
        assertEquals(
            ShadowResetDecider.Decision.STAND_DOWN,
            decider.onProbe(emptyDeficit(), scanLooksComplete = true)
        )
    }

    // ── Empty-deficit stand-down (pure decision table) ────────────────

    /** The stranded-scan signature: sdk=0 duffs AND 0 txs vs a real dashj balance. */
    private fun emptyDeficit() = buildParityReport(
        sdkConfirmedDuffs = 0, sdkUnconfirmedDuffs = 0,
        dashjEstimatedDuffs = 154_427_919, dashjAvailableDuffs = 154_427_919,
        sdkTxCount = 0, dashjTxCount = 12, sdkSynced = true, timestampMs = 1L
    )

    @Test
    fun resetDecider_emptyDeficit_rebuildsOnce_thenStandsDownOnce_thenSilent() {
        val decider = ShadowResetDecider()
        // Three consecutive qualifying probes required, like the inflated
        // path — the stranded-scan state self-heals on the THIRD synced probe.
        repeat(2) {
            assertEquals(
                ShadowResetDecider.Decision.NONE,
                decider.onProbe(emptyDeficit(), scanLooksComplete = true)
            )
        }
        assertEquals(
            ShadowResetDecider.Decision.REBUILD_WALLET,
            decider.onProbe(emptyDeficit(), scanLooksComplete = true)
        )
        // The rebuild is once-per-process: if the rebuilt wallet's rescan
        // STILL comes back empty, stand down (once), then silence.
        repeat(2) {
            assertEquals(
                ShadowResetDecider.Decision.NONE,
                decider.onProbe(emptyDeficit(), scanLooksComplete = true)
            )
        }
        assertEquals(
            ShadowResetDecider.Decision.STAND_DOWN,
            decider.onProbe(emptyDeficit(), scanLooksComplete = true)
        )
        repeat(4) {
            assertEquals(
                ShadowResetDecider.Decision.NONE,
                decider.onProbe(emptyDeficit(), scanLooksComplete = true)
            )
        }
    }

    @Test
    fun resetDecider_deficitWithTxsOrOpenScan_neverQualifiesForRebuild() {
        val decider = ShadowResetDecider()
        repeat(5) {
            // A deficit with SDK transactions present is a partial scan gap
            // (the CoinJoin-derivation bug class) — MISMATCH log only.
            assertEquals(
                ShadowResetDecider.Decision.NONE,
                decider.onProbe(deficit(), scanLooksComplete = true)
            )
            // And an empty deficit while the filter scan is still open is
            // just a scan in progress.
            assertEquals(
                ShadowResetDecider.Decision.NONE,
                decider.onProbe(emptyDeficit(), scanLooksComplete = false)
            )
        }
    }

    @Test
    fun resetDecider_emptyDeficitStreakIsBrokenByAnyOtherProbe() {
        val decider = ShadowResetDecider()
        repeat(2) {
            decider.onProbe(emptyDeficit(), scanLooksComplete = true)
        }
        assertEquals(ShadowResetDecider.Decision.NONE, decider.onProbe(matching())) // streak reset
        repeat(2) {
            assertEquals(
                ShadowResetDecider.Decision.NONE,
                decider.onProbe(emptyDeficit(), scanLooksComplete = true)
            )
        }
        assertEquals(
            ShadowResetDecider.Decision.REBUILD_WALLET,
            decider.onProbe(emptyDeficit(), scanLooksComplete = true)
        )
    }

    @Test
    fun scanLooksComplete_requiresNonZeroTargetsReachedOnBothAxes() {
        assertFalse(ShadowSyncProgress.IDLE.scanLooksComplete) // all-zero SYNCED-shaped snapshots don't count
        val complete = ShadowSyncProgress(ShadowSyncPhase.SYNCED, 100.0, 100, 100, 100, 100)
        assertTrue(complete.scanLooksComplete)
        assertFalse(complete.copy(filterHeight = 99).scanLooksComplete)
        assertFalse(complete.copy(headerTarget = 0, headerHeight = 0).scanLooksComplete)
    }

    // ── Service-level reset + diff orchestration ──────────────────────

    private val synced = SpvSyncProgressData(
        overallState = SpvSyncState.SYNCED,
        overallPercentage = 100.0,
        headers = null, filterHeaders = null, filters = null, masternodes = null
    )

    /**
     * An inflated synced mismatch: sdk sees 200k duffs, dashj 100k — with
     * dashj's chain head AT the network tip, so the mismatch is genuinely
     * inflated (not a dashj mid-sync artifact the caught-up gate suppresses).
     */
    private fun inflatedSource() = FakeSource(boundWalletId = walletIdHex).apply {
        sdkConfirmed = 200_000
        dashjBalances = 100_000L to 100_000L
        dashjChainHead = 1_511_575
    }

    @Test
    fun probeParity_inflatedMismatch_selfHealsWithOneFullWalletRebuild() = runBlocking {
        // Self-heal: a persistent inflated mismatch triggers a ONE-TIME full
        // SDK-wallet REBUILD (the SPV-only reset is gone — device evidence
        // showed the +0.01 inflation survives it, so it lives in the wallet
        // ledger, not the scan data). The rebuild runs the removeWallet
        // cascade + rebind — NOT the legacy L1-row purge or SDK clear.
        val source = inflatedSource()
        val recreator = FakeRecreator()
        val markerWrites = mutableListOf<Long>()
        val service = service(source, markerWrites = markerWrites, recreator = recreator)
        assertTrue(service.startIfEnabled())
        source.emitWithoutEdgeProbe(syncedComplete) // count the streak manually

        repeat(2) { service.probeParity(walletIdHex) }
        assertTrue(recreator.events.isEmpty()) // below the threshold

        service.probeParity(walletIdHex) // third consecutive → ONE full rebuild
        assertEquals(
            listOf("stopShielded", "removeWallet", "resetBinderLatch", "rebind"),
            recreator.events
        )
        assertEquals(listOf(walletIdHex), recreator.removedWalletIds)
        // The rebuild uses the removeWallet cascade — NOT the legacy SPV-only
        // row purge or the broken SDK clearSpvStorage call.
        assertEquals(0, source.clearL1RowsCalls)
        assertEquals(0, source.clearSpvStorageCalls)
        assertEquals(2, source.startCalls) // initial + fresh post-rebind restart
        assertEquals(listOf(1_000_000L), markerWrites) // recovery stamped the marker
    }

    @Test
    fun probeParity_dashjMidReplay_neverHardResetsACorrectSdkView() = runBlocking {
        // The live 02:38–02:42 incident: after a restore-from-seed the SDK
        // discovered the full 12.08713251 DASH / 1044+ txs in ~3 minutes
        // while dashj was still replaying its chain download (balance
        // climbing from ~0, chain head far below the tip). The old rule
        // read this as "inflated for 3 synced probes" and hard-reset the
        // CORRECT SDK state; the caught-up gate must suppress that.
        val source = FakeSource(boundWalletId = walletIdHex).apply {
            sdkConfirmed = 1_208_713_251 // the SDK view is CORRECT
            sdkTxs = 1044
            dashjBalances = 0L to 0L // dashj replay just started
            dashjTxs = 0
            dashjChainHead = 900_000 // replay position, tip is 1_511_575
        }
        val service = service(source)
        assertTrue(service.startIfEnabled())
        source.progressFlow.value = syncedComplete

        repeat(3) { service.probeParity(walletIdHex) }
        // dashj replay progresses: balance partially discovered, still behind.
        source.dashjBalances = 500_000_000L to 500_000_000L
        source.dashjChainHead = 1_200_000
        repeat(3) { service.probeParity(walletIdHex) }
        assertEquals(0, source.clearL1RowsCalls) // NO hard reset — SDK state intact
        assertEquals(1, source.startCalls)

        // dashj finishes its replay and agrees with the SDK: parity verified.
        source.dashjBalances = 1_208_713_251L to 1_208_713_251L
        source.dashjTxs = 1044
        source.dashjChainHead = 1_511_574 // within tolerance of the tip
        service.probeParity(walletIdHex)
        assertEquals(0, source.clearL1RowsCalls)
        assertEquals(L1VerificationStatus.VERIFIED, service.verificationStatus.value)
    }

    @Test
    fun probeParity_inflationSurvivingTheRebuild_standsDownAsFailed_neverRebuildsTwice() = runBlocking {
        val source = inflatedSource()
        val recreator = FakeRecreator()
        val service = service(source, recreator = recreator)
        assertTrue(service.startIfEnabled())
        source.progressFlow.value = syncedComplete
        repeat(3) { service.probeParity(walletIdHex) } // → the one rebuild
        assertEquals(1, recreator.removedWalletIds.size)

        // The rebuilt wallet's rescan completes but the inflation SURVIVES:
        // a deterministic SDK ledger bug → stand down FAILED, no 2nd rebuild.
        source.progressFlow.value = SpvSyncProgressData.EMPTY
        source.progressFlow.value = syncedComplete
        repeat(6) { service.probeParity(walletIdHex) }
        assertEquals(1, recreator.removedWalletIds.size) // never rebuilds twice — no churn
        assertEquals(L1VerificationStatus.FAILED, service.verificationStatus.value)
    }

    @Test
    fun probeParity_recentSelfSpendBroadcast_deferstheRebuildUntilPastTheGraceWindow() = runBlocking {
        // Phase 5b wiring: SdkL1SendService calls noteSelfSpendBroadcast()
        // after a successful SDK L1 send. The legitimate inflation window
        // (mempool → mined → filter-scanned) must NOT trigger the rebuild;
        // only a genuine post-grace inflation self-heals.
        var now = 1_000_000L
        val source = inflatedSource()
        val recreator = FakeRecreator()
        val service = service(source, nowMs = { now }, recreator = recreator)
        assertTrue(service.startIfEnabled())
        source.progressFlow.value = syncedComplete

        service.noteSelfSpendBroadcast()
        repeat(5) {
            now += 60_000 // probe cadence, still inside the grace window
            service.probeParity(walletIdHex)
        }
        assertTrue(recreator.events.isEmpty()) // suppressed — no rebuild during the grace window

        // Past the grace window the inflation is real → one full rebuild.
        now += L1ShadowSyncService.SELF_SPEND_GRACE_MS + 1
        repeat(3) { service.probeParity(walletIdHex) }
        assertEquals(listOf(walletIdHex), recreator.removedWalletIds)
    }

    @Test
    fun probeParity_neverResetsOnADeficitMismatch() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex).apply {
            sdkConfirmed = 50_000 // sdk BELOW dashj — a real scan-gap candidate
            dashjBalances = 100_000L to 100_000L
        }
        val service = service(source)
        assertTrue(service.startIfEnabled())
        source.progressFlow.value = synced
        repeat(5) { service.probeParity(walletIdHex) }
        assertEquals(0, source.clearL1RowsCalls)
        assertEquals(0, source.clearSpvStorageCalls)
        assertEquals(1, source.startCalls)
    }

    @Test
    fun resetShadowState_isDirectlyCallable_andRequiresARunningShadow() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        val service = service(source)

        assertFalse(service.resetShadowState()) // not running yet
        assertEquals(0, source.clearL1RowsCalls)

        assertTrue(service.startIfEnabled())
        assertTrue(service.resetShadowState()) // debug-broadcast / debug-screen entry point
        assertEquals(1, source.stopCalls)
        assertEquals(1, source.clearL1RowsCalls)
        assertEquals(0, source.clearSpvStorageCalls) // hard by default
        assertEquals(2, source.startCalls)
    }

    @Test
    fun resetShadowState_hard_deletesDataDirBeforeRowsBeforeRestart_andStampsTheMarker() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        val markerWrites = mutableListOf<Long>()
        val service = service(source, nowMs = { 777_000L }, markerWrites = markerWrites)
        val spvDir = dataDir.resolve("spv")
        assertTrue(service.startIfEnabled())

        // Simulate the persisted Rust scan state a stopped-client
        // clearSpvStorage can never remove: header store + watermark files.
        spvDir.resolve("headers").mkdirs()
        spvDir.resolve("headers/block_headers.dat").writeText("stale header store")
        spvDir.resolve("metadata.dat").writeText("scan watermark")

        var dirExistedWhenRowsCleared: Boolean? = null
        source.onClearRows = { dirExistedWhenRowsCleared = spvDir.exists() }
        var dirExistedWhenRestarted: Boolean? = null
        var rowsClearedBeforeRestart = -1
        source.onStart = {
            dirExistedWhenRestarted = spvDir.exists()
            rowsClearedBeforeRestart = source.clearL1RowsCalls
        }

        assertTrue(service.resetShadowState(hard = true))
        assertEquals(1, source.stopCalls)
        // Sequencing: fs delete BEFORE the Room row delete...
        assertEquals(false, dirExistedWhenRowsCleared)
        // ...row delete BEFORE the restart, dir recreated for the restart.
        assertEquals(1, rowsClearedBeforeRestart)
        assertEquals(true, dirExistedWhenRestarted)
        assertTrue(spvDir.isDirectory) // fresh empty dir for the rescan
        assertNull(spvDir.resolve("metadata.dat").takeIf { it.exists() })
        assertEquals(0, source.clearSpvStorageCalls) // the broken SDK call is not used
        assertEquals(2, source.startCalls)
        assertEquals(listOf(777_000L), markerWrites) // reset marker persisted
    }

    @Test
    fun resetShadowState_soft_usesTheLegacySdkClearCall() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        val service = service(source)
        assertTrue(service.startIfEnabled())
        assertTrue(service.resetShadowState(hard = false))
        assertEquals(1, source.clearSpvStorageCalls)
        assertEquals(1, source.clearL1RowsCalls)
        assertEquals(2, source.startCalls)
    }

    // ── Reset-aftermath deficit recovery (service-level) ──────────────

    /**
     * A SYNCED snapshot whose header AND filter scans report a reached
     * non-zero target — the live incident's "instant SYNCED from surviving
     * scan state" shape (headers=filters=1511575/1511575).
     */
    private val syncedComplete = SpvSyncProgressData(
        overallState = SpvSyncState.SYNCED,
        overallPercentage = 100.0,
        headers = sub(SpvSyncState.SYNCED, 1_511_575, 1_511_575),
        filterHeaders = sub(SpvSyncState.SYNCED, 1_511_575, 1_511_575),
        filters = sub(SpvSyncState.SYNCED, 1_511_575, 1_511_575),
        masternodes = sub(SpvSyncState.SYNCED, 1_511_575, 1_511_575)
    )

    /**
     * Emit a progress snapshot while the SYNCED-edge auto-probe is parked
     * (dashj side unavailable → the edge probe skips without publishing):
     * for tests that drive every DECIDER-feeding probe manually and count
     * the decision streak — the edge probe (a production feature, see
     * [L1ShadowSyncService.parityLoop]) would otherwise consume part of
     * it. Deterministic on the Unconfined scope: the skip completes
     * inside the assignment.
     */
    private fun FakeSource.emitWithoutEdgeProbe(snapshot: SpvSyncProgressData) {
        val saved = dashjBalances
        dashjBalances = null
        progressFlow.value = snapshot
        dashjBalances = saved
    }

    /** The stuck post-broken-reset state: SDK sees NOTHING, dashj holds funds. */
    private fun emptyDeficitSource() = FakeSource(boundWalletId = walletIdHex).apply {
        sdkConfirmed = 0
        sdkTxs = 0
        dashjBalances = 154_427_919L to 154_427_919L
        dashjTxs = 12
    }

    @Test
    fun probeParity_emptyDeficit_selfHealsWithOneFullWalletRebuild_thenStandsDown() = runBlocking {
        val source = emptyDeficitSource()
        val recreator = FakeRecreator()
        val markerWrites = mutableListOf<Long>()
        // The stranded-scan state (empty deficit + complete scan) self-heals
        // with the SAME one-time full SDK-wallet rebuild as the inflated path.
        val service = service(
            source, markerWrites = markerWrites, recreator = recreator
        )
        assertTrue(service.startIfEnabled())
        source.emitWithoutEdgeProbe(syncedComplete) // this test counts the streak manually

        repeat(2) { service.probeParity(walletIdHex) }
        assertTrue(recreator.events.isEmpty()) // below the threshold

        service.probeParity(walletIdHex) // third consecutive → ONE full rebuild
        assertEquals(
            listOf("stopShielded", "removeWallet", "resetBinderLatch", "rebind"),
            recreator.events
        )
        assertEquals(listOf(walletIdHex), recreator.removedWalletIds)
        // removeWallet's cascade replaces row deletion — no legacy row purge,
        // no broken SDK clearSpvStorage call.
        assertEquals(0, source.clearL1RowsCalls)
        assertEquals(0, source.clearSpvStorageCalls)
        assertEquals(2, source.startCalls) // initial + fresh post-rebind restart
        assertEquals(listOf(1_000_000L), markerWrites) // recovery stamped the marker

        // The rebuilt wallet's rescan STILL comes back empty: stand down
        // FAILED — no second rebuild this process.
        source.progressFlow.value = SpvSyncProgressData.EMPTY
        source.progressFlow.value = syncedComplete
        repeat(6) { service.probeParity(walletIdHex) }
        assertEquals(1, recreator.removedWalletIds.size)
        assertEquals(2, source.startCalls)
        assertEquals(L1VerificationStatus.FAILED, service.verificationStatus.value)
    }

    @Test
    fun probeParity_incompleteScanDeficit_isNotTreatedAsStrandedScan() = runBlocking {
        val source = emptyDeficitSource()
        val recreator = FakeRecreator()
        val service = service(source, lastResetMs = 900_000L, recreator = recreator)
        assertTrue(service.startIfEnabled())
        // SYNCED overall but WITHOUT provable header+filter completion
        // (no sub-progress blocks) — must not qualify.
        source.progressFlow.value = synced
        repeat(6) { service.probeParity(walletIdHex) }
        assertEquals(0, source.clearL1RowsCalls)
        assertEquals(1, source.startCalls)
        assertTrue(recreator.events.isEmpty())
    }

    // ── User-observable verification status ───────────────────────────

    @Test
    fun verificationStatus_scanningThenProbingThenVerified() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex).apply {
            sdkConfirmed = 100_000
            dashjBalances = 100_000L to 100_000L
        }
        val service = service(source)
        assertEquals(L1VerificationStatus.UNKNOWN, service.verificationStatus.value)

        assertTrue(service.startIfEnabled())
        source.progressFlow.value = syncing(headers = sub(SpvSyncState.SYNCING, 10, 100))
        assertEquals(L1VerificationStatus.SCANNING, service.verificationStatus.value)

        // Chain synced: parity still needs confirming. (The edge auto-probe
        // is parked — this test confirms parity with the manual probe below.)
        source.emitWithoutEdgeProbe(synced)
        assertEquals(L1VerificationStatus.PROBING, service.verificationStatus.value)

        // A synced probe matching on BOTH balance variants → VERIFIED…
        service.probeParity(walletIdHex)
        assertEquals(L1VerificationStatus.VERIFIED, service.verificationStatus.value)

        // …and later synced progress ticks must not downgrade it.
        source.progressFlow.value = SpvSyncProgressData(
            overallState = SpvSyncState.SYNCED,
            overallPercentage = 100.0,
            headers = null, filterHeaders = null, filters = null, masternodes = null
        )
        assertEquals(L1VerificationStatus.VERIFIED, service.verificationStatus.value)

        // A later mismatching synced probe closes the gate again → PROBING.
        source.sdkConfirmed = 50_000
        service.probeParity(walletIdHex)
        assertEquals(L1VerificationStatus.PROBING, service.verificationStatus.value)
    }

    @Test
    fun verificationStatus_mismatchSurvivingTheRebuild_isTerminalFailure() = runBlocking {
        val source = emptyDeficitSource()
        // First a rebuild (probe 3), then — the mismatch surviving it — a
        // stand-down (probe 6) → FAILED. (No recreator wired: the rebuild
        // self-heal is a no-op here, but the decider latch still advances to
        // the stand-down, which is the terminal-FAILED path under test.)
        val service = service(source)
        assertTrue(service.startIfEnabled())
        source.progressFlow.value = syncedComplete
        repeat(6) { service.probeParity(walletIdHex) }
        assertEquals(L1VerificationStatus.FAILED, service.verificationStatus.value)

        // Terminal per process: even a later fully-matching probe (or a
        // progress tick) cannot clear it.
        source.sdkConfirmed = 154_427_919
        source.sdkTxs = 12
        service.probeParity(walletIdHex)
        assertEquals(L1VerificationStatus.FAILED, service.verificationStatus.value)
        source.progressFlow.value = SpvSyncProgressData.EMPTY
        assertEquals(L1VerificationStatus.FAILED, service.verificationStatus.value)
    }

    // ── Wallet re-creation recovery (orchestration; also debug-trigger) ─

    @Test
    fun recoverByRecreatingWallet_runsTheFullSequenceInOrder() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        val recreator = FakeRecreator()
        val markerWrites = mutableListOf<Long>()
        val service = service(
            source, nowMs = { 888_000L }, markerWrites = markerWrites, recreator = recreator
        )
        val spvDir = dataDir.resolve("spv")
        assertTrue(service.startIfEnabled())

        // Simulate surviving chain data; the WATERMARK itself lives in the
        // wallet's Room row (WalletEntity.syncedHeight) — destroyed by the
        // removeWallet step, which is the whole point of this recovery.
        spvDir.resolve("headers").mkdirs()
        spvDir.resolve("headers/block_headers.dat").writeText("chain data")

        // The restart must WAIT for the rebind: hold the bind job open.
        recreator.bindJob = kotlinx.coroutines.Job()

        var spvStoppedWhenRemoved = -1
        var dirExistedWhenRemoved: Boolean? = null
        recreator.onRemove = {
            spvStoppedWhenRemoved = source.stopCalls
            dirExistedWhenRemoved = spvDir.resolve("headers/block_headers.dat").exists()
        }
        var dirExistedAtBinderReset: Boolean? = null
        var markerStampedAtBinderReset: Int? = null
        recreator.onBinderReset = {
            dirExistedAtBinderReset = spvDir.exists()
            markerStampedAtBinderReset = markerWrites.size
        }

        assertTrue(service.recoverByRecreatingWallet())

        // 1. shadow SPV + shielded stopped BEFORE the destructive steps…
        assertEquals(1, spvStoppedWhenRemoved)
        assertEquals(
            listOf("stopShielded", "removeWallet", "resetBinderLatch", "rebind"),
            recreator.events
        )
        assertEquals(listOf(walletIdHex), recreator.removedWalletIds)
        // 2. …removeWallet ran with the dataDir still present, then the
        //    dataDir was deleted and the marker stamped BEFORE the binder
        //    latch cleared (so a rebind can never see half-torn-down state).
        assertEquals(true, dirExistedWhenRemoved)
        assertEquals(false, dirExistedAtBinderReset)
        assertEquals(1, markerStampedAtBinderReset)
        assertEquals(listOf(888_000L), markerWrites)
        // 3. the legacy row purge / SDK storage clear are NOT part of this
        //    path (the cascade already removed every row).
        assertEquals(0, source.clearL1RowsCalls)
        assertEquals(0, source.clearSpvStorageCalls)
        // 4. the shadow does NOT restart until the bind pass finishes…
        assertEquals(1, source.startCalls)
        assertEquals(ShadowSyncProgress.IDLE, service.progress.value)

        // 5. …and restarts fresh once it does.
        recreator.bindJob.complete()
        withTimeout(5_000) {
            while (source.startCalls < 2) delay(5)
        }
        assertEquals(2, source.startCalls)
    }

    @Test
    fun recoverByRecreatingWallet_worksWhenTheShadowIsNotRunning() = runBlocking {
        // The debug-broadcast case: shadow never started this process, but
        // the SDK holds a bound wallet — recovery still runs (the wallet id
        // comes from the source, and stop() is a no-op).
        val source = FakeSource(boundWalletId = walletIdHex)
        val recreator = FakeRecreator()
        val service = service(source, recreator = recreator)

        assertTrue(service.recoverByRecreatingWallet())
        assertEquals(
            listOf("stopShielded", "removeWallet", "resetBinderLatch", "rebind"),
            recreator.events
        )
        assertEquals(0, source.stopCalls) // nothing was running to stop
        // Bind job is completed by default → restart attempt happens and
        // succeeds (the fake still reports a bound wallet).
        assertEquals(1, source.startCalls)
    }

    @Test
    fun recoverByRecreatingWallet_skipsWhenNoWalletIsBound() = runBlocking {
        val source = FakeSource(boundWalletId = null)
        val recreator = FakeRecreator()
        val service = service(source, recreator = recreator)

        assertFalse(service.recoverByRecreatingWallet())
        assertTrue(recreator.events.isEmpty())
        assertEquals(0, source.startCalls)
    }

    @Test
    fun recoverByRecreatingWallet_skipsWithoutARecreatorWired() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        val service = service(source) // recreator = null (test default)
        assertTrue(service.startIfEnabled())
        assertFalse(service.recoverByRecreatingWallet())
        assertEquals(0, source.stopCalls) // nothing torn down
    }

    @Test
    fun clearForWalletWipe_stopsRemovesAndResetsTheBinder_butNEVERRebinds() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        val recreator = FakeRecreator()
        val service = service(source, recreator = recreator)
        assertTrue(service.startIfEnabled())
        val startsBefore = source.startCalls

        service.clearForWalletWipe()

        // Same destructive teardown as recovery, but the rebind must NOT run —
        // after a wipe there is no seed to bind until the next wallet's setup.
        assertEquals(
            listOf("stopShielded", "removeWallet", "resetBinderLatch"),
            recreator.events
        )
        assertEquals(listOf(walletIdHex), recreator.removedWalletIds)
        assertTrue(source.stopCalls > 0) // the shadow SPV was stopped
        assertEquals(startsBefore, source.startCalls) // and never restarted
    }

    @Test
    fun clearForWalletWipe_withNoWalletBound_skipsRemoveButStillResetsTheBinder() = runBlocking {
        val source = FakeSource(boundWalletId = null)
        val recreator = FakeRecreator()
        val service = service(source, recreator = recreator)

        service.clearForWalletWipe()

        // Nothing to remove, but the latch must still clear so the next wallet
        // binds fresh; still no rebind.
        assertEquals(listOf("stopShielded", "resetBinderLatch"), recreator.events)
        assertTrue(recreator.removedWalletIds.isEmpty())
        assertEquals(0, source.startCalls)
    }

    @Test
    fun clearForWalletWipe_swallowsARemoveFailure_andStillResetsTheBinder() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        val recreator = FakeRecreator()
        recreator.onRemove = { throw IllegalStateException("native removeWallet failed") }
        val service = service(source, recreator = recreator)

        service.clearForWalletWipe()

        // A failing removal is contained — the binder latch still clears (a
        // partial clear that skipped the latch is exactly the resurrection bug).
        assertTrue(recreator.events.contains("resetBinderLatch"))
        assertEquals(0, source.startCalls)
    }

    @Test
    fun clearForWalletWipe_skipsWithoutARecreatorWired() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        val service = service(source) // recreator = null (test default)
        service.clearForWalletWipe() // must be a no-op, never throw
        assertEquals(0, source.stopCalls)
    }

    @Test
    fun recoverByRecreatingWallet_swallowsARemoveFailure_andSkipsTheRebind() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        val recreator = FakeRecreator()
        recreator.onRemove = { throw IllegalStateException("native removeWallet failed") }
        val service = service(source, recreator = recreator)
        assertTrue(service.startIfEnabled())

        assertFalse(service.recoverByRecreatingWallet())
        // The teardown ran, but neither the binder latch nor the rebind may
        // fire against a wallet whose removal state is unknown.
        assertEquals(listOf("stopShielded", "removeWallet"), recreator.events)
        assertEquals(1, source.stopCalls)
        assertEquals(1, source.startCalls) // no restart launched
    }

    // ── SYNCED-edge probe (funding-gate freshness) ────────────────────

    @Test
    fun parityLoop_probesImmediatelyOnTheSyncedEdge_notAFullIntervalLater() = runBlocking {
        // The live gap: every dashj idle-cycle restarts the shadow, which
        // re-syncs in seconds — but the next 60s parity tick was up to a
        // minute away, and the funding gate (which requires a FRESH
        // report) stayed closed for the whole wait (measured 53s per
        // cycle, recurring).
        val source = FakeSource(boundWalletId = walletIdHex)
        var probes = 0
        source.onProbe = { probes++ }
        val service = service(source) // default 60s interval: no tick during the test
        assertTrue(service.startIfEnabled())
        val startupProbes = probes // the loop's immediate first probe

        source.progressFlow.value = synced // the edge into SYNCED
        withTimeout(5_000) {
            while (probes < startupProbes + 1) delay(5)
        }
        assertEquals(startupProbes + 1, probes)
        service.stop()
    }

    @Test
    fun parityLoop_syncedEdgeFlapping_firesAtMostOneProbePerEdge() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        var probes = 0
        source.onProbe = { probes++ }
        val service = service(source)
        assertTrue(service.startIfEnabled())
        val startupProbes = probes

        source.progressFlow.value = synced // edge 1
        source.progressFlow.value = syncing(filters = sub(SpvSyncState.SYNCING, 50, 100)) // leaves SYNCED
        source.progressFlow.value = synced // edge 2
        withTimeout(5_000) {
            while (probes < startupProbes + 1) delay(5)
        }
        delay(300) // let any over-delivery surface
        assertTrue("probes=$probes", probes <= startupProbes + 2)

        // No storm afterwards: the interval pacing is otherwise intact.
        val settled = probes
        delay(300)
        assertEquals(settled, probes)
        service.stop()
    }

    // ── Probe watchdog ────────────────────────────────────────────────

    @Test
    fun probeWatchdogDecider_restartsOnce_reportsExhaustionOnce_thenStaysSilent() {
        val decider = ProbeWatchdogDecider(stallThresholdMs = 100)
        // Fresh heartbeat: nothing to do.
        assertEquals(ProbeWatchdogDecider.Decision.NONE, decider.onCheck(nowMs = 50, lastHeartbeatMs = 0))
        // Stale heartbeat: restart, exactly once per process.
        assertEquals(ProbeWatchdogDecider.Decision.RESTART, decider.onCheck(nowMs = 150, lastHeartbeatMs = 0))
        // The restarted loop heartbeats again: healthy.
        assertEquals(ProbeWatchdogDecider.Decision.NONE, decider.onCheck(nowMs = 200, lastHeartbeatMs = 160))
        // It stalls AGAIN: the one restart is spent — exhausted (once)...
        assertEquals(ProbeWatchdogDecider.Decision.EXHAUSTED, decider.onCheck(nowMs = 400, lastHeartbeatMs = 160))
        // ...then silence.
        repeat(3) {
            assertEquals(ProbeWatchdogDecider.Decision.NONE, decider.onCheck(nowMs = 600, lastHeartbeatMs = 160))
        }
    }

    @Test
    fun watchdog_restartsAStuckProbeLoop() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        var hangProbes = true
        // The first probe hangs forever inside the source — the field
        // failure shape (loop alive but silent, or dead scope).
        source.onProbe = { if (hangProbes) awaitCancellation() }
        val service = service(
            source,
            nowMs = System::currentTimeMillis,
            parityIntervalMs = 20,
            watchdogIntervalMs = 10,
            probeStallThresholdMs = 80
        )
        assertTrue(service.startIfEnabled())
        assertNull(service.latestParity.value) // stuck inside the first probe

        hangProbes = false // the restarted loop's probes succeed
        withTimeout(5_000) {
            while (service.latestParity.value == null) delay(10)
        }
        // The watchdog cancelled the stuck loop and relaunched it exactly
        // once; the relaunched loop published a report.
        assertNotNull(service.latestParity.value)
        service.stop()
    }

    @Test
    fun outpointDiff_isLoggedOncePerDistinctMismatchState() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex).apply {
            sdkConfirmed = 50_000 // deficit → diff logging without auto-reset
            dashjBalances = 100_000L to 100_000L
            sdkUtxos = listOf(L1Utxo("aa".repeat(32), 0, 50_000))
            dashjUtxos = listOf(L1Utxo("bb".repeat(32), 0, 100_000))
        }
        val service = service(source)
        assertTrue(service.startIfEnabled())
        source.progressFlow.value = synced

        repeat(3) { service.probeParity(walletIdHex) }
        assertEquals(1, source.sdkUtxoFetches) // same state → one diff dump

        source.sdkConfirmed = 60_000 // the mismatch changed shape → dump again
        service.probeParity(walletIdHex)
        assertEquals(2, source.sdkUtxoFetches)
    }

    @Test
    fun outpointDiff_notComputedPreSyncOrOnMatch() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex).apply {
            sdkConfirmed = 50_000
            dashjBalances = 100_000L to 100_000L
        }
        val service = service(source)
        assertTrue(service.startIfEnabled())

        service.probeParity(walletIdHex) // pre-sync mismatch: expected, no diff
        assertEquals(0, source.sdkUtxoFetches)

        // Balances match BEFORE the SYNCED edge, so the edge auto-probe is
        // a synced MATCH too — this test is about match/pre-sync probes
        // never computing the diff.
        source.sdkConfirmed = 100_000
        source.progressFlow.value = synced
        service.probeParity(walletIdHex) // synced MATCH: no diff
        assertEquals(0, source.sdkUtxoFetches)
    }

    @Test
    fun outpointDiff_retriesWhenDashjIsUnavailableForTheDiff() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex).apply {
            sdkConfirmed = 50_000
            dashjBalances = 100_000L to 100_000L
            dashjUtxos = null // balance probe works, UTXO listing does not (yet)
        }
        val service = service(source)
        assertTrue(service.startIfEnabled())
        source.progressFlow.value = synced

        service.probeParity(walletIdHex)
        assertEquals(0, source.sdkUtxoFetches)

        source.dashjUtxos = emptyList() // becomes available → same state retried
        service.probeParity(walletIdHex)
        assertEquals(1, source.sdkUtxoFetches)
    }

    // ── Pure SPV-progress mapping ─────────────────────────────────────

    private fun sub(state: SpvSyncState, current: Long, target: Long) =
        SpvSubProgress(state = state, currentHeight = current, targetHeight = target, percentage = 0.0)

    private fun syncing(
        headers: SpvSubProgress? = sub(SpvSyncState.SYNCED, 100, 100),
        filterHeaders: SpvSubProgress? = sub(SpvSyncState.SYNCED, 100, 100),
        masternodes: SpvSubProgress? = sub(SpvSyncState.SYNCED, 100, 100),
        filters: SpvSubProgress? = sub(SpvSyncState.SYNCED, 100, 100)
    ) = SpvSyncProgressData(
        overallState = SpvSyncState.SYNCING,
        overallPercentage = 50.0,
        headers = headers,
        filterHeaders = filterHeaders,
        filters = filters,
        masternodes = masternodes
    )

    @Test
    fun toShadowSyncProgress_mapsOverallStates() {
        assertEquals(ShadowSyncPhase.IDLE, toShadowSyncProgress(SpvSyncProgressData.EMPTY).phase)

        val connecting = SpvSyncProgressData.EMPTY.copy(overallState = SpvSyncState.WAITING_FOR_CONNECTIONS)
        assertEquals(ShadowSyncPhase.CONNECTING, toShadowSyncProgress(connecting).phase)

        val synced = SpvSyncProgressData.EMPTY.copy(overallState = SpvSyncState.SYNCED)
        assertEquals(ShadowSyncPhase.SYNCED, toShadowSyncProgress(synced).phase)
        assertTrue(toShadowSyncProgress(synced).synced)

        val error = SpvSyncProgressData.EMPTY.copy(overallState = SpvSyncState.ERROR)
        assertEquals(ShadowSyncPhase.ERROR, toShadowSyncProgress(error).phase)
    }

    @Test
    fun toShadowSyncProgress_picksTheFirstPendingPipelineStage() {
        assertEquals(
            ShadowSyncPhase.HEADERS,
            toShadowSyncProgress(syncing(headers = sub(SpvSyncState.SYNCING, 10, 100))).phase
        )
        assertEquals(
            ShadowSyncPhase.FILTER_HEADERS,
            toShadowSyncProgress(syncing(filterHeaders = sub(SpvSyncState.SYNCING, 10, 100))).phase
        )
        assertEquals(
            ShadowSyncPhase.MASTERNODES,
            toShadowSyncProgress(syncing(masternodes = sub(SpvSyncState.WAIT_FOR_EVENTS, 0, 0))).phase
        )
        // Everything else synced → the filter scan is the bottleneck.
        assertEquals(
            ShadowSyncPhase.FILTERS,
            toShadowSyncProgress(syncing(filters = sub(SpvSyncState.SYNCING, 50, 100))).phase
        )
    }

    @Test
    fun toShadowSyncProgress_carriesHeightsAndPercent() {
        val mapped = toShadowSyncProgress(
            syncing(
                headers = sub(SpvSyncState.SYNCED, 200, 200),
                filters = sub(SpvSyncState.SYNCING, 50, 200)
            )
        )
        assertEquals(50.0, mapped.overallPercent, 0.0)
        assertEquals(200, mapped.headerHeight)
        assertEquals(200, mapped.headerTarget)
        assertEquals(50, mapped.filterHeight)
        assertEquals(200, mapped.filterTarget)
        assertEquals(
            "L1Shadow phase=FILTERS 50.0% headers 200/200 filters 50/200",
            shadowProgressLine(mapped)
        )
    }

    // ── kotlinSyncLabel (home-screen debug indicator) ─────────────────

    @Test
    fun kotlinSyncLabel_idleHides_percentCombinesHeadersAndFilters_syncedIs100() {
        assertNull(kotlinSyncLabel(ShadowSyncProgress.IDLE, L1VerificationStatus.UNKNOWN))
        // Combined percent: (1260660 + 24000) / (1514660 + 1514660) = 42%.
        assertEquals(
            "Kotlin 42%",
            kotlinSyncLabel(
                ShadowSyncProgress(ShadowSyncPhase.HEADERS, 0.3, 1_260_660, 1_514_660, 24_000, 1_514_660),
                L1VerificationStatus.SCANNING
            )
        )
        // The SDK's own overallPercent said 1.0% here — the label must combine the raw counts:
        // (1514660 + 1402000) / (1514660 + 1514660) = 96%.
        assertEquals(
            "Kotlin 96%",
            kotlinSyncLabel(
                ShadowSyncProgress(ShadowSyncPhase.FILTERS, 1.0, 1_514_660, 1_514_660, 1_402_000, 1_514_660),
                L1VerificationStatus.SCANNING
            )
        )
        // Only SYNCED may claim 100% — a still-scanning phase at target caps at 99%.
        assertEquals(
            "Kotlin 99%",
            kotlinSyncLabel(
                ShadowSyncProgress(ShadowSyncPhase.FILTERS, 1.0, 100, 100, 100, 100),
                L1VerificationStatus.SCANNING
            )
        )
        assertEquals(
            "Kotlin 100%",
            kotlinSyncLabel(
                ShadowSyncProgress(ShadowSyncPhase.SYNCED, 100.0, 100, 100, 100, 100),
                L1VerificationStatus.PROBING
            )
        )
        assertEquals(
            "Kotlin 100% \u2713",
            kotlinSyncLabel(
                ShadowSyncProgress(ShadowSyncPhase.SYNCED, 100.0, 100, 100, 100, 100),
                L1VerificationStatus.VERIFIED
            )
        )
        assertEquals(
            "Kotlin 100% (verification failed)",
            kotlinSyncLabel(
                ShadowSyncProgress(ShadowSyncPhase.SYNCED, 100.0, 100, 100, 100, 100),
                L1VerificationStatus.FAILED
            )
        )
        // Unknown targets must not divide by zero.
        assertEquals(
            "Kotlin 0%",
            kotlinSyncLabel(
                ShadowSyncProgress(ShadowSyncPhase.FILTERS, 0.0, 0, 0, 0, 0),
                L1VerificationStatus.SCANNING
            )
        )
    }

    // ── shadowSyncPercent (post-cutover home "Syncing N%" source) ─────

    @Test
    fun shadowSyncPercent_idleAndConnectingAreZero_scanningCombinesAndCapsAt99_syncedIs100() {
        // Idle/connecting: 0% (nothing to show yet).
        assertEquals(0, shadowSyncPercent(ShadowSyncProgress.IDLE))
        assertEquals(0, shadowSyncPercent(ShadowSyncProgress(ShadowSyncPhase.CONNECTING, 0.0, 0, 0, 0, 0)))
        // Same combined header+filter metric as kotlinSyncLabel:
        // (1260660 + 24000) / (1514660 + 1514660) = 42%.
        assertEquals(
            42,
            shadowSyncPercent(
                ShadowSyncProgress(ShadowSyncPhase.HEADERS, 0.3, 1_260_660, 1_514_660, 24_000, 1_514_660)
            )
        )
        // Only SYNCED may claim 100% — a still-scanning phase at target caps at 99%.
        assertEquals(
            99,
            shadowSyncPercent(ShadowSyncProgress(ShadowSyncPhase.FILTERS, 1.0, 100, 100, 100, 100))
        )
        assertEquals(
            100,
            shadowSyncPercent(ShadowSyncProgress(ShadowSyncPhase.SYNCED, 100.0, 100, 100, 100, 100))
        )
        // Unknown targets must not divide by zero.
        assertEquals(0, shadowSyncPercent(ShadowSyncProgress(ShadowSyncPhase.FILTERS, 0.0, 0, 0, 0, 0)))
        // Error phase reads as 0 (nothing trustworthy to show).
        assertEquals(0, shadowSyncPercent(ShadowSyncProgress(ShadowSyncPhase.ERROR, 0.0, 100, 100, 100, 100)))
    }

    // ── parseL1TxEvent (the engine wallet-event Debug-string parser) ──

    /** Display-order txid of the RECORD (the one the parser must pick). */
    private val recordTxid = "00".repeat(31) + "ab"

    /** A DIFFERENT txid appearing as OutPoint noise inside the record's Transaction. */
    private val inputTxid = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    /** Realistic `WalletEvent::TransactionDetected` Debug string (mempool incoming). */
    private fun detectedDebug(
        context: String = "Mempool",
        direction: String = "Incoming",
        netAmount: Long = 1_000_000L,
        fee: String = "None"
    ): String =
        "TransactionDetected { wallet_id: WalletId([205, 205, 205]), record: TransactionRecord { " +
            "transaction: Transaction { version: 2, lock_time: 0, input: [TxIn { " +
            "previous_output: OutPoint { txid: 0x$inputTxid, vout: 1 }, " +
            "script_sig: Script(OP_DUP OP_HASH160), sequence: 4294967295, " +
            "witness: Witness { content: [], witness_elements: 0, indices_start: 0 } }], " +
            "output: [TxOut { value: 1000000, script_pubkey: Script(OP_DUP OP_HASH160) }], " +
            "special_transaction_payload: None }, " +
            "txid: 0x$recordTxid, " +
            "account_type: Standard { index: 0, standard_account_type: BIP44 }, " +
            "context: $context, transaction_type: Standard, direction: $direction, " +
            "input_details: [], " +
            "output_details: [OutputDetail { index: 0, role: Received, address: Some(Address..), value: 1000000 }], " +
            "net_amount: $netAmount, fee: $fee, label: \"\" }, " +
            "balance: WalletCoreBalance { confirmed: 0, unconfirmed: 1000000, immature: 0, locked: 0 }, " +
            "account_balances: {}, addresses_derived: [] }"

    /** Realistic `WalletEvent::TransactionInstantLocked` Debug string. */
    private fun instantLockedDebug(): String =
        "TransactionInstantLocked { wallet_id: WalletId([205, 205, 205]), " +
            "txid: 0x$recordTxid, " +
            "instant_lock: InstantLock { version: 1, " +
            "inputs: [OutPoint { txid: 0x$inputTxid, vout: 1 }], " +
            "txid: 0x$recordTxid, cyclehash: 0x${"11".repeat(32)}, signature: [1, 2, 3] }, " +
            "balance: WalletCoreBalance { confirmed: 0, unconfirmed: 1000000, immature: 0, locked: 0 }, " +
            "account_balances: {} }"

    @Test
    fun parseTxEvent_detectedMempoolIncoming() {
        val event = parseL1TxEvent(detectedDebug())
        assertEquals(
            L1TxEvent.Detected(
                txidHex = recordTxid,
                netAmountDuffs = 1_000_000L,
                feeDuffs = null,
                contextCode = 0,
                directionCode = 0
            ),
            event
        )
    }

    @Test
    fun parseTxEvent_recordTxidNotConfusedWithInputOutpointTxid() {
        val detected = parseL1TxEvent(detectedDebug()) as L1TxEvent.Detected
        assertEquals(recordTxid, detected.txidHex)
        assertFalse(detected.txidHex == inputTxid)
    }

    @Test
    fun parseTxEvent_detectedInstantSendFirstOutgoingWithFee() {
        // A record born with an IS lock: context is InstantSend(InstantLock { … }),
        // whose inner txids must not confuse any anchor.
        val debug = detectedDebug(
            context = "InstantSend(InstantLock { version: 1, inputs: [OutPoint { txid: 0x$inputTxid, " +
                "vout: 0 }], txid: 0x$recordTxid, cyclehash: 0x${"22".repeat(32)}, signature: [9] })",
            direction = "Outgoing",
            netAmount = -1_000_146L,
            fee = "Some(146)"
        )
        assertEquals(
            L1TxEvent.Detected(
                txidHex = recordTxid,
                netAmountDuffs = -1_000_146L,
                feeDuffs = 146L,
                contextCode = 1,
                directionCode = 1
            ),
            parseL1TxEvent(debug)
        )
    }

    @Test
    fun parseTxEvent_instantLocked() {
        assertEquals(L1TxEvent.InstantLocked(recordTxid), parseL1TxEvent(instantLockedDebug()))
    }

    @Test
    fun parseTxEvent_otherEventKindsAndGarbageAreNull() {
        // Block/height/chainlock events flow through the same string channel
        // and must be ignored — even though BlockProcessed CONTAINS records
        // whose fields would match the anchors.
        assertNull(
            parseL1TxEvent(
                "BlockProcessed { wallet_id: WalletId([1]), height: 1200000, chain_lock: None, " +
                    "inserted: [TransactionRecord { txid: 0x$recordTxid, account_type: Standard { " +
                    "index: 0 }, context: InBlock(BlockInfo { height: 1200000 }), direction: Incoming, " +
                    "net_amount: 5, fee: None, label: \"\" }], updated: [], matured: [], " +
                    "balance: WalletCoreBalance { confirmed: 5 }, account_balances: {}, addresses_derived: [] }"
            )
        )
        assertNull(parseL1TxEvent("SyncHeightAdvanced { wallet_id: WalletId([1]), height: 1200001 }"))
        assertNull(parseL1TxEvent("ChainLockProcessed { wallet_id: WalletId([1]) }"))
        assertNull(parseL1TxEvent("not an event at all"))
        // A Detected string missing a required field degrades to null, not a wrong row.
        assertNull(parseL1TxEvent("TransactionDetected { wallet_id: WalletId([1]), record: broken }"))
    }

    // ── The wallet-event tap (start/stop lifecycle + parse-and-forward) ──

    @Test
    fun eventTap_forwardsParsedTxEventsWhileRunning_andStopsWithService() = runBlocking {
        val source = FakeSource(boundWalletId = walletIdHex)
        val service = service(source)
        assertTrue(service.startIfEnabled())

        val received = mutableListOf<L1TxEvent>()
        val collector = scope.launch { service.txEvents.collect { received += it } }
        try {
            source.eventStrings.emit(detectedDebug())
            source.eventStrings.emit("SyncHeightAdvanced { wallet_id: WalletId([1]), height: 7 }")
            source.eventStrings.emit(instantLockedDebug())

            assertEquals(2, received.size)
            assertTrue(received[0] is L1TxEvent.Detected)
            assertEquals(L1TxEvent.InstantLocked(recordTxid), received[1])

            // stop() cancels the tap: later engine events no longer surface.
            service.stop()
            assertEquals(0, source.eventStrings.subscriptionCount.value)
        } finally {
            collector.cancel()
        }
    }
}
