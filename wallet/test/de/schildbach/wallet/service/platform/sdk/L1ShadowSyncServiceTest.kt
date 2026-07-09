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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.dashfoundation.dashsdk.wallet.SpvSubProgress
import org.dashfoundation.dashsdk.wallet.SpvSyncProgressData
import org.dashfoundation.dashsdk.wallet.SpvSyncState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        var sdkConfirmed = 0L
        var sdkUnconfirmed = 0L
        var sdkTxs = 0
        var dashjBalances: Pair<Long, Long>? = 0L to 0L
        var dashjTxs: Int? = 0

        var sdkUtxos: List<L1Utxo> = emptyList()
        var dashjUtxos: List<L1Utxo>? = emptyList()
        var sdkUtxoFetches = 0
        var clearSpvStorageCalls = 0
        var clearL1RowsCalls = 0

        val progressFlow = MutableStateFlow(SpvSyncProgressData.EMPTY)

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

        override suspend fun sdkBalanceDuffs(walletIdHex: String): Pair<Long, Long> =
            sdkConfirmed to sdkUnconfirmed

        override suspend fun sdkTxCount(walletIdHex: String): Int = sdkTxs

        override suspend fun dashjBalanceDuffs(): Pair<Long, Long>? = dashjBalances

        override suspend fun dashjTxCount(): Int? = dashjTxs

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
        }
    }

    private fun config(flag: Boolean?): DashPayConfig = mockk<DashPayConfig>().also {
        coEvery { it.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) } returns flag
    }

    private fun service(
        source: FakeSource,
        flag: Boolean? = true,
        nowMs: () -> Long = { 1_000_000L }
    ) = L1ShadowSyncService(
        source = source,
        dashPayConfig = config(flag),
        scope = scope,
        spvDataDirPath = { dataDir.resolve("spv").absolutePath },
        nowMs = nowMs
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
        assertEquals(ShadowResetDecider.Decision.RESET, decider.onProbe(inflated()))
    }

    @Test
    fun resetDecider_anyNonInflatedProbeBreaksTheStreak() {
        val decider = ShadowResetDecider()
        decider.onProbe(inflated())
        decider.onProbe(inflated())
        assertEquals(ShadowResetDecider.Decision.NONE, decider.onProbe(matching())) // streak reset
        decider.onProbe(inflated())
        assertEquals(ShadowResetDecider.Decision.NONE, decider.onProbe(inflated()))
        assertEquals(ShadowResetDecider.Decision.RESET, decider.onProbe(inflated()))
    }

    @Test
    fun resetDecider_resetsOncePerProcess_thenReportsCorruptOnce_thenStandsDown() {
        val decider = ShadowResetDecider()
        repeat(2) { decider.onProbe(inflated()) }
        assertEquals(ShadowResetDecider.Decision.RESET, decider.onProbe(inflated()))

        // Post-reset rescan: un-synced probes zero the streak.
        assertEquals(ShadowResetDecider.Decision.NONE, decider.onProbe(inflated(synced = false)))

        // A FULL post-reset resync still inflated → corrupt, exactly once.
        repeat(2) {
            assertEquals(ShadowResetDecider.Decision.NONE, decider.onProbe(inflated()))
        }
        assertEquals(ShadowResetDecider.Decision.CORRUPT_AFTER_RESET, decider.onProbe(inflated()))
        repeat(4) {
            assertEquals(ShadowResetDecider.Decision.NONE, decider.onProbe(inflated()))
        }
    }

    // ── Service-level reset + diff orchestration ──────────────────────

    private val synced = SpvSyncProgressData(
        overallState = SpvSyncState.SYNCED,
        overallPercentage = 100.0,
        headers = null, filterHeaders = null, filters = null, masternodes = null
    )

    /** An inflated synced mismatch: sdk sees 200k duffs, dashj 100k. */
    private fun inflatedSource() = FakeSource(boundWalletId = walletIdHex).apply {
        sdkConfirmed = 200_000
        dashjBalances = 100_000L to 100_000L
    }

    @Test
    fun probeParity_autoResetsAfterThreeConsecutiveInflatedSyncedProbes() = runBlocking {
        val source = inflatedSource()
        val service = service(source)
        assertTrue(service.startIfEnabled())
        source.progressFlow.value = synced

        repeat(2) { service.probeParity(walletIdHex) }
        assertEquals(0, source.clearL1RowsCalls) // below the threshold

        service.probeParity(walletIdHex) // third consecutive → reset
        assertEquals(1, source.stopCalls)
        assertEquals(1, source.clearL1RowsCalls)
        assertEquals(1, source.clearSpvStorageCalls)
        assertEquals(2, source.startCalls) // initial + post-reset restart

        // The reset marks the progress un-synced until the rescan reports in.
        assertEquals(ShadowSyncPhase.IDLE, service.progress.value.phase)
    }

    @Test
    fun probeParity_neverResetsTwicePerProcess_evenIfTheMismatchSurvivesResync() = runBlocking {
        val source = inflatedSource()
        val service = service(source)
        assertTrue(service.startIfEnabled())
        source.progressFlow.value = synced
        repeat(3) { service.probeParity(walletIdHex) } // → the one reset

        // Simulate the post-reset resync completing (flow change re-emits).
        source.progressFlow.value = SpvSyncProgressData.EMPTY
        source.progressFlow.value = synced

        // Mismatch persists through a full resync: ERROR + stand down, no 2nd reset.
        repeat(6) { service.probeParity(walletIdHex) }
        assertEquals(1, source.clearL1RowsCalls)
        assertEquals(1, source.clearSpvStorageCalls)
        assertEquals(2, source.startCalls)
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
        assertTrue(service.resetShadowState()) // future debug-screen entry point
        assertEquals(1, source.stopCalls)
        assertEquals(1, source.clearL1RowsCalls)
        assertEquals(1, source.clearSpvStorageCalls)
        assertEquals(2, source.startCalls)
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

        source.progressFlow.value = synced
        source.sdkConfirmed = 100_000
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
}
