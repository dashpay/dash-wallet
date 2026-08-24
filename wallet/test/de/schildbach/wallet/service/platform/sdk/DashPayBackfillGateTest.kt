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

import androidx.datastore.preferences.core.Preferences
import de.schildbach.wallet.database.dao.DashPayContactRequestDao
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the DIP-15 coreHeight-backfill gate — the app-side
 * mitigation for the SDK's in-memory-only `rescan_triggered` guard, which
 * otherwise re-fires a ~210,000-block rewind on every launch and keeps a
 * contact-heavy mainnet wallet from ever finishing its initial sync.
 *
 * The property under test throughout is the asymmetry that makes the gate
 * safe: skipping the rewind requires POSITIVE evidence that the scan
 * climbed back past the height it was rewound from; every unknown,
 * failure, or half-finished state must fall back to running it again.
 * Running it needlessly is slow. Skipping it wrongly loses payments
 * permanently.
 */
class DashPayBackfillGateTest {

    private val walletId = "cd".repeat(32)
    private val userId = "9YourIdentityBase58"
    private val identityId = ByteArray(32) { 7 }

    private val fingerprintA = contactSetFingerprint(145, 140, 1_700_000_000_000L, 1_699_000_000_000L)
    private val fingerprintB = contactSetFingerprint(146, 140, 1_700_500_000_000L, 1_699_000_000_000L)

    private fun settledReport(
        bound: Boolean = true,
        syncErrors: Int = 0,
        pendingBefore: Int = 0,
        drainScheduled: Boolean = false
    ) = DashPayContactProvisionReport(
        bound = bound,
        syncSuccess = 1,
        syncErrors = syncErrors,
        pendingBefore = pendingBefore,
        drainScheduled = drainScheduled
    )

    // ── the pure decision core ───────────────────────────────────────────

    @Test
    fun firstRun_noMarker_runsTheBackfill() {
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(1_000_000L, fingerprintA, 790_000L, 145),
            coverage = null,
            inProgress = null,
            armed = null,
            hasProvisionedInProcess = false
        )
        assertTrue(decision.shouldRun)
        assertNull(decision.coverageToWrite)
        assertNull(decision.inProgressToWrite)
        // The pass about to run is put on record BEFORE it runs, so a later
        // consultation can detect its rewind from the durable height alone.
        assertEquals(BackfillArmed(1_000_000L, fingerprintA), decision.armedToWrite)
    }

    // ── the armed marker (the cross-launch rewind detector) ──────────────

    @Test
    fun armedMarker_heightBelowTarget_latchesTheWatchAndSkips() {
        // Launch A armed at 2377092 and provisioned; the rewind persisted
        // AFTER the process died. Launch B's observation alone proves it.
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(2_302_092L, fingerprintA, 2_167_000L, 145),
            coverage = null,
            inProgress = null,
            armed = BackfillArmed(2_377_092L, fingerprintA),
            hasProvisionedInProcess = false
        )
        assertFalse(decision.shouldRun)
        assertEquals(
            BackfillInProgress(2_302_092L, 2_377_092L, fingerprintA),
            decision.inProgressToWrite
        )
        assertTrue(decision.clearArmed)
        assertNull(decision.coverageToWrite) // latch is a watch, never coverage
    }

    @Test
    fun armedMarker_contactSetChanged_abandonsTheMarkerAndRearmsForTheNewSet() {
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(2_400_000L, fingerprintB, 2_167_000L, 146),
            coverage = null,
            inProgress = null,
            armed = BackfillArmed(2_377_092L, fingerprintA),
            hasProvisionedInProcess = false
        )
        assertTrue(decision.shouldRun)
        assertNull(decision.inProgressToWrite)
        assertEquals(BackfillArmed(2_400_000L, fingerprintB), decision.armedToWrite)
    }

    @Test
    fun armedMarker_heightAtOrAboveTarget_freshProcess_failsTowardReRunning() {
        // "Persist never landed", "nothing to rewind" and "completed inside
        // the arming session" are indistinguishable here, and coverage may
        // not be recorded without an observed climb from an observed floor —
        // so the first pass of a fresh process re-runs and re-arms.
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(2_380_000L, fingerprintA, 2_167_000L, 145),
            coverage = null,
            inProgress = null,
            armed = BackfillArmed(2_377_092L, fingerprintA),
            hasProvisionedInProcess = false
        )
        assertTrue(decision.shouldRun)
        assertNull(decision.coverageToWrite) // NEVER coverage without a climb
        assertEquals(BackfillArmed(2_380_000L, fingerprintA), decision.armedToWrite)
    }

    @Test
    fun armedMarker_heightAtOrAboveTarget_alreadyProvisionedThisProcess_waitsWithoutReRunning() {
        // The SDK's in-memory rescan guard makes a same-process re-run prove
        // nothing, and the armed pass's persisted drop may still be in
        // flight (~9-60s): keep the marker, skip, and let a later trigger
        // (or the next launch) observe the drop.
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(2_377_100L, fingerprintA, 2_167_000L, 145),
            coverage = null,
            inProgress = null,
            armed = BackfillArmed(2_377_092L, fingerprintA),
            hasProvisionedInProcess = true
        )
        assertFalse(decision.shouldRun)
        assertNull(decision.coverageToWrite)
        assertNull(decision.armedToWrite)
        assertFalse(decision.clearArmed) // the marker must survive the wait
    }

    @Test
    fun armedMarker_unknownObservation_runsButLeavesTheMarkerAlone() {
        // Unknown must not be mistaken for "resolved": run (never lossy),
        // write nothing, and let a later readable launch honour the marker.
        val decision = decideDashPayBackfill(
            observation = BackfillObservation.UNKNOWN,
            coverage = null,
            inProgress = null,
            armed = BackfillArmed(2_377_092L, fingerprintA),
            hasProvisionedInProcess = false
        )
        assertTrue(decision.shouldRun)
        assertNull(decision.armedToWrite)
        assertFalse(decision.clearArmed)
        assertNull(decision.coverageToWrite)
    }

    @Test
    fun armedMarker_survivingNextToAWatch_watchWinsAndCompletionClearsBoth() {
        // Crash between the latch's two writes can leave marker + watch
        // together; the watch takes precedence, and completion sweeps the
        // stale marker out.
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(2_400_000L, fingerprintA, 2_167_000L, 145),
            coverage = null,
            inProgress = BackfillInProgress(2_302_092L, 2_377_092L, fingerprintA),
            armed = BackfillArmed(2_377_092L, fingerprintA),
            hasProvisionedInProcess = false
        )
        assertFalse(decision.shouldRun)
        assertEquals(
            BackfillCoverage(2_302_092L, 2_400_000L, fingerprintA),
            decision.coverageToWrite
        )
        assertTrue(decision.clearInProgress)
        assertTrue(decision.clearArmed)
    }

    @Test
    fun completedRun_markerPresentAndContactsUnchanged_skipsTheBackfill() {
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(1_010_000L, fingerprintA, 790_000L, 145),
            coverage = BackfillCoverage(790_000L, 1_000_000L, fingerprintA),
            inProgress = null,
            armed = null,
            hasProvisionedInProcess = false
        )
        assertFalse(decision.shouldRun)
        assertFalse(decision.clearCoverage)
    }

    @Test
    fun newContactAppears_invalidatesCoverageAndRunsAgain() {
        // A contact established after the covered scan: its receival
        // addresses were not in the filter match set while that scan ran, so
        // its history needs its own backfill regardless of core height.
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(1_010_000L, fingerprintB, 700_000L, 146),
            coverage = BackfillCoverage(790_000L, 1_000_000L, fingerprintA),
            inProgress = null,
            armed = null,
            hasProvisionedInProcess = false
        )
        assertTrue(decision.shouldRun)
        assertTrue(decision.clearCoverage)
        // The log must call out that the newcomer predates the covered floor.
        assertTrue(decision.reason.contains("BELOW the covered floor 790000"))
    }

    @Test
    fun unknownSyncedHeight_runsTheBackfillAndRecordsNothing() {
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(null, fingerprintA, 790_000L, 145),
            coverage = BackfillCoverage(790_000L, 1_000_000L, fingerprintA),
            inProgress = null,
            armed = null,
            hasProvisionedInProcess = false
        )
        assertTrue(decision.shouldRun)
        assertNull(decision.coverageToWrite)
    }

    @Test
    fun unknownContactFingerprint_runsTheBackfill() {
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(1_010_000L, null, 790_000L, 145),
            coverage = BackfillCoverage(790_000L, 1_000_000L, fingerprintA),
            inProgress = null,
            armed = null,
            hasProvisionedInProcess = false
        )
        assertTrue(decision.shouldRun)
    }

    @Test
    fun backfillInProgress_belowTarget_skipsSoTheScanCanFinish() {
        // The heart of the fix: re-running here re-lowers synced_height and
        // the scan can never climb out.
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(850_000L, fingerprintA, 790_000L, 145),
            coverage = null,
            inProgress = BackfillInProgress(790_000L, 1_000_000L, fingerprintA),
            armed = null,
            hasProvisionedInProcess = false
        )
        assertFalse(decision.shouldRun)
        assertNull(decision.coverageToWrite) // NOT complete yet — nothing latched
    }

    @Test
    fun backfillInProgress_reachedTarget_recordsCoverage() {
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(1_000_000L, fingerprintA, 790_000L, 145),
            coverage = null,
            inProgress = BackfillInProgress(790_000L, 1_000_000L, fingerprintA),
            armed = null,
            hasProvisionedInProcess = false
        )
        assertFalse(decision.shouldRun)
        assertEquals(
            BackfillCoverage(790_000L, 1_000_000L, fingerprintA),
            decision.coverageToWrite
        )
        assertTrue(decision.clearInProgress)
    }

    @Test
    fun backfillInProgress_contactSetChanged_restartsInsteadOfCompleting() {
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(1_000_000L, fingerprintB, 700_000L, 146),
            coverage = null,
            inProgress = BackfillInProgress(790_000L, 1_000_000L, fingerprintA),
            armed = null,
            hasProvisionedInProcess = false
        )
        assertTrue(decision.shouldRun)
        assertTrue(decision.clearInProgress)
        assertNull(decision.coverageToWrite)
    }

    @Test
    fun backfillInProgress_floorMovedDown_widensCoverageAndKeepsWatching() {
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(700_000L, fingerprintA, 700_000L, 145),
            coverage = null,
            inProgress = BackfillInProgress(790_000L, 1_000_000L, fingerprintA),
            armed = null,
            hasProvisionedInProcess = false
        )
        assertFalse(decision.shouldRun)
        assertEquals(
            BackfillInProgress(700_000L, 1_000_000L, fingerprintA),
            decision.inProgressToWrite
        )
    }

    @Test
    fun coverageWithSyncedHeightBelowFloor_discardsItAndRunsAgain() {
        // SDK wallet state was reset under us: the evidence is gone.
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(10_000L, fingerprintA, 790_000L, 145),
            coverage = BackfillCoverage(790_000L, 1_000_000L, fingerprintA),
            inProgress = null,
            armed = null,
            hasProvisionedInProcess = false
        )
        assertTrue(decision.shouldRun)
        assertTrue(decision.clearCoverage)
    }

    // ── pass-outcome interpretation ──────────────────────────────────────

    @Test
    fun passOutcome_rewindObserved_startsAWatchAndNEVERMarksComplete() {
        val outcome = decideDashPayBackfillPassOutcome(
            before = BackfillObservation(1_000_000L, fingerprintA, 790_000L, 145),
            syncedHeightAfter = 790_000L,
            report = settledReport(),
            firstPassInProcess = true
        )
        assertEquals(
            BackfillInProgress(790_000L, 1_000_000L, fingerprintA),
            outcome.inProgressToWrite
        )
        // The whole safety property: a triggered backfill is not a done one.
        assertNull(outcome.coverageToWrite)
    }

    @Test
    fun passOutcome_rewindObservedWhileAccountsStillRegistering_STILLLatches() {
        // The settledness precondition was removed as field-proven wrong: the
        // SDK re-enqueues every contact's account build on every launch (FFI
        // persistence gap), so on the wallet this gate exists for
        // pendingBefore is structurally pinned at the full contact count —
        // the old gate made latching unreachable, and every launch re-rewound
        // the scan ~210k blocks forever. A floor that drops further after
        // latching is absorbed by the watch's floor-widening rule.
        val outcome = decideDashPayBackfillPassOutcome(
            before = BackfillObservation(1_000_000L, fingerprintA, 790_000L, 145),
            syncedHeightAfter = 790_000L,
            report = settledReport(pendingBefore = 182, drainScheduled = true),
            firstPassInProcess = true
        )
        assertEquals(
            BackfillInProgress(790_000L, 1_000_000L, fingerprintA),
            outcome.inProgressToWrite
        )
        assertTrue(outcome.clearArmed)
        assertNull(outcome.coverageToWrite)
    }

    @Test
    fun passOutcome_noRewindOnFirstPassOfProcess_marksNothingToBackfill() {
        val outcome = decideDashPayBackfillPassOutcome(
            before = BackfillObservation(1_000_000L, fingerprintA, null, 0),
            syncedHeightAfter = 1_000_000L,
            report = settledReport(),
            firstPassInProcess = true
        )
        assertEquals(
            // ASSUMED, not observed: no rewind was ever seen, so the record
            // stays open to being refuted by later evidence.
            BackfillCoverage(1_000_000L, 1_000_000L, fingerprintA, observedFloor = false),
            outcome.coverageToWrite
        )
    }

    @Test
    fun passOutcome_noRewindButAReceivedContactPredatesTheHeight_recordsNOTHING() {
        // The S22 field case. A received contact request created at core
        // height 2_382_103 means a rewind was OWED; a quiet pass therefore
        // proves the rewind was suppressed or has not persisted, NOT that
        // there was nothing to backfill. Recording coverage at the tip here
        // claims 136k blocks as scanned that never were.
        val outcome = decideDashPayBackfillPassOutcome(
            before = BackfillObservation(
                syncedHeight = 2_518_637L,
                contactFingerprint = fingerprintA,
                sdkContactFloor = 2_382_103L,
                sdkContactCount = 6,
                sdkReceivedContactFloor = 2_382_103L,
                appContactCount = 6
            ),
            syncedHeightAfter = 2_518_637L,
            report = settledReport(),
            firstPassInProcess = true
        )
        assertNull(outcome.coverageToWrite)
        // The armed marker must survive so the next trigger provisions again.
        assertFalse(outcome.clearArmed)
    }

    @Test
    fun passOutcome_noRewindWhileTheSdkHasNotIngestedTheAppsContactsYet_recordsNOTHING() {
        // The other half of the S22 case: the gate ran 16 minutes before the
        // SDK's contact store was populated, so provisioning had nothing to
        // act on and no rewind could possibly fire. The app's own table
        // already held the contacts — that disagreement is the tell.
        val outcome = decideDashPayBackfillPassOutcome(
            before = BackfillObservation(
                syncedHeight = 2_518_637L,
                contactFingerprint = fingerprintA,
                sdkContactFloor = null,
                sdkContactCount = 0,
                sdkReceivedContactFloor = null,
                appContactCount = 2
            ),
            syncedHeightAfter = 2_518_637L,
            report = settledReport(),
            firstPassInProcess = true
        )
        assertNull(outcome.coverageToWrite)
        assertFalse(outcome.clearArmed)
    }

    @Test
    fun passOutcome_noRewindAndTheSdkAgreesThereAreNoContacts_stillRecordsCoverage() {
        // The genuinely-quiet wallet must still reach the covered steady
        // state, or the gate re-provisions on every launch forever.
        val outcome = decideDashPayBackfillPassOutcome(
            before = BackfillObservation(
                syncedHeight = 1_000_000L,
                contactFingerprint = fingerprintA,
                sdkContactFloor = null,
                sdkContactCount = 0,
                sdkReceivedContactFloor = null,
                appContactCount = 0
            ),
            syncedHeightAfter = 1_000_000L,
            report = settledReport(),
            firstPassInProcess = true
        )
        assertEquals(
            BackfillCoverage(1_000_000L, 1_000_000L, fingerprintA, observedFloor = false),
            outcome.coverageToWrite
        )
    }

    @Test
    fun passOutcome_noRewindWithOnlyOUTGOINGContactsBelowTheHeight_stillRecordsCoverage() {
        // Outgoing requests yield no receival chain, so nothing is owed a
        // rewind and withholding here would strand the wallet re-provisioning
        // forever.
        val outcome = decideDashPayBackfillPassOutcome(
            before = BackfillObservation(
                syncedHeight = 1_000_000L,
                contactFingerprint = fingerprintA,
                sdkContactFloor = 790_000L,
                sdkContactCount = 3,
                sdkReceivedContactFloor = null,
                appContactCount = 3
            ),
            syncedHeightAfter = 1_000_000L,
            report = settledReport(),
            firstPassInProcess = true
        )
        assertEquals(
            BackfillCoverage(1_000_000L, 1_000_000L, fingerprintA, observedFloor = false),
            outcome.coverageToWrite
        )
    }

    // ── an ASSUMED floor stays open to being refuted ─────────────────────

    @Test
    fun assumedCoverage_refutedByAReceivedContactBelowTheFloor_discardsAndReRuns() {
        // The exact suppression seen on S22 at 22:25:17: coverage floor
        // 2_518_645 with the SDK reporting a contact core height of
        // 2_382_103, contact set unchanged — and the old gate SKIPPED.
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(
                syncedHeight = 2_518_646L,
                contactFingerprint = fingerprintA,
                sdkContactFloor = 2_382_103L,
                sdkContactCount = 5,
                sdkReceivedContactFloor = 2_382_103L,
                appContactCount = 5
            ),
            coverage = BackfillCoverage(
                2_518_645L, 2_518_645L, fingerprintA, observedFloor = false
            ),
            inProgress = null,
            armed = null,
            hasProvisionedInProcess = false
        )
        assertTrue(decision.shouldRun)
        assertTrue(decision.clearCoverage)
        assertEquals(BackfillArmed(2_518_646L, fingerprintA), decision.armedToWrite)
    }

    @Test
    fun observedCoverage_isNEVERSecondGuessedByTheContactFloor() {
        // An observed floor is the height the SDK itself rewound to; a
        // contact below it just reflects the SDK subsetting by direction and
        // establishment. Re-running on that would rewind every launch forever
        // — the livelock this whole gate exists to stop.
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(
                syncedHeight = 2_518_646L,
                contactFingerprint = fingerprintA,
                sdkContactFloor = 2_167_000L,
                sdkContactCount = 182,
                sdkReceivedContactFloor = 2_167_000L,
                appContactCount = 182
            ),
            coverage = BackfillCoverage(
                2_302_092L, 2_400_000L, fingerprintA, observedFloor = true
            ),
            inProgress = null,
            armed = null,
            hasProvisionedInProcess = false
        )
        assertFalse(decision.shouldRun)
        assertFalse(decision.clearCoverage)
    }

    @Test
    fun passOutcome_noRewindOnLaterPass_provesNothing_becauseTheSdkGuardIsPerProcess() {
        // The SDK's `rescan_triggered` is in-memory: from the second pass of
        // a process onward, a quiet pass means "the guard suppressed it",
        // NOT "there was nothing to do".
        val outcome = decideDashPayBackfillPassOutcome(
            before = BackfillObservation(1_000_000L, fingerprintA, null, 0),
            syncedHeightAfter = 1_000_000L,
            report = settledReport(),
            firstPassInProcess = false
        )
        assertNull(outcome.coverageToWrite)
        assertNull(outcome.inProgressToWrite)
    }

    @Test
    fun passOutcome_noRewindButSweepErrored_recordsNothing() {
        val outcome = decideDashPayBackfillPassOutcome(
            before = BackfillObservation(1_000_000L, fingerprintA, null, 0),
            syncedHeightAfter = 1_000_000L,
            report = settledReport(syncErrors = 3),
            firstPassInProcess = true
        )
        assertNull(outcome.coverageToWrite)
    }

    @Test
    fun passOutcome_heightUnreadable_recordsNothing() {
        val outcome = decideDashPayBackfillPassOutcome(
            before = BackfillObservation(1_000_000L, fingerprintA, null, 0),
            syncedHeightAfter = null,
            report = settledReport(),
            firstPassInProcess = true
        )
        assertNull(outcome.coverageToWrite)
        assertNull(outcome.inProgressToWrite)
    }

    @Test
    fun passOutcome_walletNotBound_recordsNothing() {
        val outcome = decideDashPayBackfillPassOutcome(
            before = BackfillObservation(1_000_000L, fingerprintA, null, 0),
            syncedHeightAfter = 1_000_000L,
            report = settledReport(bound = false),
            firstPassInProcess = true
        )
        assertNull(outcome.coverageToWrite)
    }

    // ── the wired gate, end to end over a fake store ─────────────────────

    /** In-memory stand-in for the DataStore-backed [DashPayConfig]. */
    private class FakeStore {
        val values = mutableMapOf<Preferences.Key<*>, Any?>()

        @Suppress("UNCHECKED_CAST")
        fun config(): DashPayConfig = mockk {
            coEvery { get(any<Preferences.Key<Any>>()) } answers {
                values[firstArg<Preferences.Key<Any>>()]
            }
            coEvery { set(any<Preferences.Key<Any>>(), any()) } answers {
                values[firstArg<Preferences.Key<Any>>()] = secondArg<Any>()
            }
            coEvery { remove(any<Preferences.Key<Any>>()) } answers {
                values.remove(firstArg<Preferences.Key<Any>>())
                Unit
            }
        }
    }

    private fun dao(
        toUs: Int,
        fromUs: Int,
        latestToUs: Long = 1_700_000_000_000L,
        latestFromUs: Long = 1_699_000_000_000L
    ): DashPayContactRequestDao = mockk {
        coEvery { countAllRequestsToUser(any()) } returns toUs
        coEvery { countAllRequestsFromUser(any()) } returns fromUs
        coEvery { getLastTimestampToUser(any()) } returns latestToUs
        coEvery { getLastTimestampFromUser(any()) } returns latestFromUs
    }

    /** SDK fake that serves a scripted sequence of watermark readings. */
    private fun sdk(vararg signals: DashPayBackfillSignals): DashSdkService {
        val queue = ArrayDeque(signals.toList())
        var last = signals.lastOrNull() ?: DashPayBackfillSignals.UNKNOWN
        return mockk {
            coEvery { readDashPayBackfillSignals(any(), any()) } answers {
                if (queue.isNotEmpty()) queue.removeFirst().also { last = it } else last
            }
        }
    }

    @Test
    fun gate_firstLaunch_runsThenLatchesAWatchOnTheObservedRewind() = runBlocking {
        val store = FakeStore()
        val gate = DashPayBackfillGateImpl(
            sdkService = sdk(
                DashPayBackfillSignals(1_000_000L, 790_000L, 145), // evaluate: pre-pass
                DashPayBackfillSignals(790_000L, 790_000L, 145)    // record: post-rewind
            ),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140)
        )

        assertTrue(gate.evaluate(walletId, identityId, userId).shouldRun)
        // The pass was put on record BEFORE it ran.
        assertEquals(1_000_000L, store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET])

        gate.recordPassOutcome(walletId, identityId, settledReport())

        // A watch was latched and the marker spent; completion was NOT recorded.
        assertEquals(790_000L, store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_FLOOR])
        assertEquals(1_000_000L, store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_TARGET])
        assertNull(store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET])
        assertNull(store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR])
    }

    @Test
    fun gate_persistRaceAcrossLaunches_armedMarkerLatchesWhatTheOutcomeReadMissed() = runBlocking {
        // The exact field sequence that defeated the direct observation: the
        // rewind fires in SDK memory during the sweep, but the durable
        // watermark only drops ~9-60s later, so the pass outcome's one-shot
        // after-read sees NO drop and the old gate re-rewound every launch.
        val store = FakeStore()

        // ── Launch A: arms at 2377092, provisions; the after-read still
        // reads the PRE-rewind height (persist in flight), then the process
        // dies before the drop is ever observed in-process.
        val launchA = DashPayBackfillGateImpl(
            sdkService = sdk(
                DashPayBackfillSignals(2_377_092L, 2_167_000L, 182), // evaluate
                DashPayBackfillSignals(2_377_092L, 2_167_000L, 182)  // record: raced
            ),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140)
        )
        assertTrue(launchA.evaluate(walletId, identityId, userId).shouldRun)
        launchA.recordPassOutcome(
            walletId, identityId, settledReport(pendingBefore = 182, drainScheduled = true)
        )
        // Nothing latched (no drop seen), but the armed marker survives.
        assertNull(store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_FLOOR])
        assertEquals(2_377_092L, store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET])

        // ── Launch B: the persisted watermark now shows the rewind. The
        // armed marker turns that into a latch, and provisioning is skipped
        // so the scan can climb.
        val launchB = DashPayBackfillGateImpl(
            sdkService = sdk(DashPayBackfillSignals(2_302_092L, 2_167_000L, 182)),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140)
        )
        assertFalse(launchB.evaluate(walletId, identityId, userId).shouldRun)
        assertEquals(2_302_092L, store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_FLOOR])
        assertEquals(2_377_092L, store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_TARGET])
        assertNull(store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET])
        assertNull(store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR])

        // ── Launch C: the scan climbed back past the armed pre-rewind
        // height — NOW completion may be recorded, and the rewind stays off.
        val launchC = DashPayBackfillGateImpl(
            sdkService = sdk(DashPayBackfillSignals(2_400_000L, 2_167_000L, 182)),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140)
        )
        assertFalse(launchC.evaluate(walletId, identityId, userId).shouldRun)
        assertEquals(2_302_092L, store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR])
        assertEquals(2_400_000L, store.values[DashPayConfig.DASHPAY_BACKFILL_COMPLETED_THROUGH])
        assertNull(store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_FLOOR])
        assertNull(store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET])
    }

    @Test
    fun gate_sameProcessSecondTrigger_latchesOncePersistLands() = runBlocking {
        // The drop can also land WITHIN the arming session (~9-60s): the
        // next in-process trigger must latch without re-running.
        val store = FakeStore()
        val gate = DashPayBackfillGateImpl(
            sdkService = sdk(
                DashPayBackfillSignals(2_377_092L, 2_167_000L, 182), // evaluate #1
                DashPayBackfillSignals(2_377_092L, 2_167_000L, 182), // record: raced
                DashPayBackfillSignals(2_302_092L, 2_167_000L, 182)  // evaluate #2
            ),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140)
        )
        assertTrue(gate.evaluate(walletId, identityId, userId).shouldRun)
        gate.recordPassOutcome(
            walletId, identityId, settledReport(pendingBefore = 182, drainScheduled = true)
        )
        assertFalse(gate.evaluate(walletId, identityId, userId).shouldRun)
        assertEquals(2_302_092L, store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_FLOOR])
        assertEquals(2_377_092L, store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_TARGET])
        assertNull(store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET])
    }

    @Test
    fun gate_armedMarkerFromDeadProcess_contactSetChanged_abandonsAndRearms() = runBlocking {
        val store = FakeStore()
        store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET] = 2_377_092L
        store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_FINGERPRINT] = fingerprintA

        val gate = DashPayBackfillGateImpl(
            sdkService = sdk(DashPayBackfillSignals(2_302_092L, 2_167_000L, 183)),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 146, fromUs = 140, latestToUs = 1_700_500_000_000L)
        )

        // Even though the height is below the old target, the marker belongs
        // to a DIFFERENT contact set: no latch — run, re-armed for the new set.
        assertTrue(gate.evaluate(walletId, identityId, userId).shouldRun)
        assertNull(store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_FLOOR])
        assertEquals(2_302_092L, store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET])
        assertEquals(fingerprintB, store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_FINGERPRINT])
    }

    @Test
    fun gate_interruptedBackfill_keepsSkippingAndWritesNoCompletionMarker() = runBlocking {
        val store = FakeStore()
        store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_FLOOR] = 790_000L
        store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_TARGET] = 1_000_000L
        store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_FINGERPRINT] = fingerprintA

        val gate = DashPayBackfillGateImpl(
            sdkService = sdk(DashPayBackfillSignals(850_000L, 790_000L, 145)),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140)
        )

        // Session ended mid-backfill: skip so the durable watermark keeps
        // climbing next launch, and record NO completion.
        assertFalse(gate.evaluate(walletId, identityId, userId).shouldRun)
        assertNull(store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR])
        assertEquals(790_000L, store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_FLOOR])
    }

    @Test
    fun gate_scanClimbedPastTheTarget_recordsCompletionAndThenSkips() = runBlocking {
        val store = FakeStore()
        store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_FLOOR] = 790_000L
        store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_TARGET] = 1_000_000L
        store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_FINGERPRINT] = fingerprintA

        val gate = DashPayBackfillGateImpl(
            sdkService = sdk(DashPayBackfillSignals(1_000_042L, 790_000L, 145)),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140)
        )

        assertFalse(gate.evaluate(walletId, identityId, userId).shouldRun)
        assertEquals(790_000L, store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR])
        assertEquals(1_000_042L, store.values[DashPayConfig.DASHPAY_BACKFILL_COMPLETED_THROUGH])
        assertNull(store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_FLOOR])

        // A later launch with the same contacts stays skipped — the rewind
        // no longer re-fires, which is the whole point.
        assertFalse(gate.evaluate(walletId, identityId, userId).shouldRun)
    }

    @Test
    fun gate_newOlderContactAfterCompletion_dropsTheMarkerAndBackfillsAgain() = runBlocking {
        val store = FakeStore()
        store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR] = 790_000L
        store.values[DashPayConfig.DASHPAY_BACKFILL_COMPLETED_THROUGH] = 1_000_000L
        store.values[DashPayConfig.DASHPAY_BACKFILL_CONTACT_FINGERPRINT] = fingerprintA

        val gate = DashPayBackfillGateImpl(
            // 146 contacts now, and the lowest core height is BELOW the
            // covered floor: exactly the case that must re-backfill.
            sdkService = sdk(DashPayBackfillSignals(1_010_000L, 700_000L, 146)),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 146, fromUs = 140, latestToUs = 1_700_500_000_000L)
        )

        val decision = gate.evaluate(walletId, identityId, userId)
        assertTrue(decision.shouldRun)
        assertNull(store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR])
        assertTrue(decision.reason.contains("BELOW the covered floor 790000"))
    }

    @Test
    fun gate_signalsUnreadable_runsTheBackfillAndLeavesTheMarkerAlone() = runBlocking {
        val store = FakeStore()
        store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR] = 790_000L
        store.values[DashPayConfig.DASHPAY_BACKFILL_COMPLETED_THROUGH] = 1_000_000L
        store.values[DashPayConfig.DASHPAY_BACKFILL_CONTACT_FINGERPRINT] = fingerprintA

        val gate = DashPayBackfillGateImpl(
            sdkService = sdk(DashPayBackfillSignals.UNKNOWN), // SDK not started
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140)
        )

        assertTrue(gate.evaluate(walletId, identityId, userId).shouldRun)
        // Unknown must not be mistaken for "changed" — the marker survives
        // so a later readable launch can still honour it.
        assertNotNull(store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR])
    }

    @Test
    fun gate_sdkReadThrows_failsTowardRunningTheBackfill() = runBlocking {
        val store = FakeStore()
        val sdkService: DashSdkService = mockk {
            coEvery { readDashPayBackfillSignals(any(), any()) } throws
                IllegalStateException("SDK down")
        }
        val gate = DashPayBackfillGateImpl(
            sdkService = sdkService,
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140)
        )

        assertTrue(gate.evaluate(walletId, identityId, userId).shouldRun)
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun gate_contactDaoThrows_runsTheBackfillRatherThanGuessing() = runBlocking {
        val store = FakeStore()
        val brokenDao: DashPayContactRequestDao = mockk {
            coEvery { countAllRequestsToUser(any()) } throws IllegalStateException("db locked")
        }
        val gate = DashPayBackfillGateImpl(
            sdkService = sdk(DashPayBackfillSignals(1_010_000L, 790_000L, 145)),
            dashPayConfig = store.config(),
            contactRequestDao = brokenDao
        )

        assertTrue(gate.evaluate(walletId, identityId, userId).shouldRun)
    }

    @Test
    fun gate_concludeNoRewind_refusesWhileAReceivedContactPredatesTheHeight() = runBlocking {
        // S22, 22:24:42: the watch window expired with nothing accounted for
        // and the gate concluded "nothing needed backfilling", recording a
        // floor of 2_518_645 over a wallet whose oldest received contact
        // dates from 2_382_103. That record then suppressed the rewind.
        val store = FakeStore()
        store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET] = 2_518_643L
        store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_FINGERPRINT] = fingerprintA

        val gate = DashPayBackfillGateImpl(
            sdkService = sdk(DashPayBackfillSignals(2_518_645L, 2_382_103L, 5, 2_382_103L)),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140)
        )

        assertFalse(gate.concludeNoRewindObserved(walletId, identityId, userId))
        assertNull(store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR])
        // The marker survives, so the next launch provisions again.
        assertEquals(2_518_643L, store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET])
    }

    @Test
    fun gate_concludeNoRewind_recordsCoverageWhenNothingWasOwedARewind() = runBlocking {
        // No RECEIVED requests means no receival chain and so nothing to
        // backfill; this wallet must still reach the covered steady state.
        val outgoingOnly = contactSetFingerprint(0, 140, 1_700_000_000_000L, 1_699_000_000_000L)
        val store = FakeStore()
        store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET] = 1_000_000L
        store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_FINGERPRINT] = outgoingOnly

        val gate = DashPayBackfillGateImpl(
            sdkService = sdk(DashPayBackfillSignals(1_000_042L, 790_000L, 140, null)),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 0, fromUs = 140)
        )

        assertTrue(gate.concludeNoRewindObserved(walletId, identityId, userId))
        assertEquals(1_000_042L, store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR])
        // Recorded as ASSUMED, so later evidence can still refute it.
        assertEquals(false, store.values[DashPayConfig.DASHPAY_BACKFILL_COVERAGE_OBSERVED])
        assertNull(store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET])
    }

    // ── registrations after the sweep (the "restart to see your money" bug) ──

    @Test
    fun armedMarker_accountsRegisteredSinceTheSweep_reProvisionsInTheSameProcess() {
        // Field 11.10.86 (splawik): the sweep ran at 17:20:08 against ZERO
        // receival accounts; the drain registered 29 of them 17:20:11–17:20:31.
        // Every later consultation in that process took the "already
        // provisioned" branch and skipped, so the 0.11095834 of contact funds
        // stayed invisible until a manual restart at 17:24.
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(2_521_270L, fingerprintA, 2_167_130L, 61),
            coverage = null,
            inProgress = null,
            armed = BackfillArmed(2_521_270L, fingerprintA),
            hasProvisionedInProcess = true,
            accountsRegisteredSincePass = true
        )
        assertTrue(decision.shouldRun)
        assertEquals(BackfillArmed(2_521_270L, fingerprintA), decision.armedToWrite)
    }

    @Test
    fun armedMarker_noRegistrations_stillWaitsRatherThanReRunning() {
        // The pre-existing contract is untouched when nothing was registered:
        // a re-run really would prove nothing there.
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(2_521_270L, fingerprintA, 2_167_130L, 61),
            coverage = null,
            inProgress = null,
            armed = BackfillArmed(2_521_270L, fingerprintA),
            hasProvisionedInProcess = true,
            accountsRegisteredSincePass = false
        )
        assertFalse(decision.shouldRun)
    }

    @Test
    fun armedMarker_rewindEvidenceStillWinsOverAPendingRegistration() {
        // Ordering matters: a durable height BELOW the armed target is proof
        // the rewind fired, and latching the watch must not be pre-empted by
        // a registration flag — re-running would re-lower the floor and the
        // scan would never climb.
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(2_237_130L, fingerprintA, 2_167_130L, 61),
            coverage = null,
            inProgress = null,
            armed = BackfillArmed(2_521_270L, fingerprintA),
            hasProvisionedInProcess = true,
            accountsRegisteredSincePass = true
        )
        assertFalse(decision.shouldRun)
        assertEquals(
            BackfillInProgress(2_237_130L, 2_521_270L, fingerprintA),
            decision.inProgressToWrite
        )
    }

    @Test
    fun coverage_accountsRegisteredSinceTheSweep_discardsItAndBackfillsAgain() {
        // The app-side contact fingerprint cannot see this: the contact
        // requests were already counted; only the SDK-side ACCOUNT is new.
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(2_521_270L, fingerprintA, 2_167_130L, 61),
            coverage = BackfillCoverage(2_237_130L, 2_521_270L, fingerprintA),
            inProgress = null,
            armed = null,
            hasProvisionedInProcess = true,
            accountsRegisteredSincePass = true
        )
        assertTrue(decision.shouldRun)
        assertTrue(decision.clearCoverage)
        assertEquals(BackfillArmed(2_521_270L, fingerprintA), decision.armedToWrite)
    }

    @Test
    fun inFlightBackfill_isNeverInterruptedByARegistration() {
        // A watch in flight still wins: re-lowering the floor mid-climb is
        // the livelock the whole gate exists to prevent, and the watch's own
        // floor-widening rule absorbs a further SDK rewind.
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(2_300_000L, fingerprintA, 2_167_130L, 61),
            coverage = null,
            inProgress = BackfillInProgress(2_237_130L, 2_521_270L, fingerprintA),
            armed = null,
            hasProvisionedInProcess = true,
            accountsRegisteredSincePass = true
        )
        assertFalse(decision.shouldRun)
    }

    @Test
    fun gate_registrationFlagIsConsumedByThePassItCauses() = runBlocking {
        val store = FakeStore()
        store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET] = 2_521_270L
        store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_FINGERPRINT] = fingerprintA
        val gate = DashPayBackfillGateImpl(
            sdkService = sdk(
                DashPayBackfillSignals(2_521_270L, 2_167_130L, 61, 2_167_130L), // evaluate #1
                DashPayBackfillSignals(2_521_270L, 2_167_130L, 61, 2_167_130L), // record #1: quiet
                DashPayBackfillSignals(2_521_270L, 2_167_130L, 61, 2_167_130L), // evaluate #2
                DashPayBackfillSignals(2_237_130L, 2_167_130L, 61, 2_167_130L)  // record #2: rewound
            ),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140)
        )
        // This process has already provisioned, and its sweep saw no rewind…
        gate.evaluate(walletId, identityId, userId)
        gate.recordPassOutcome(walletId, identityId, settledReport(pendingBefore = 0))
        // …then the drain registered accounts the sweep could not have seen.
        gate.noteAccountBuildsRegistered(26)

        assertTrue(gate.evaluate(walletId, identityId, userId).shouldRun)

        // …and the pass that runs consumes the signal, so a steady wallet
        // settles instead of sweeping on every trigger.
        gate.recordPassOutcome(
            walletId,
            identityId,
            DashPayContactProvisionReport(
                bound = true, syncSuccess = 1, syncErrors = 0,
                pendingBefore = 0, drainScheduled = false, pendingAfter = 0
            )
        )
        assertEquals(2_237_130L, store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_FLOOR])
    }

    @Test
    fun gate_aPassThatBuildsAccountsReArmsItself() = runBlocking {
        // The registrations landed DURING the pass, after its sweep had
        // already reconciled — so the pass must leave the signal SET.
        val store = FakeStore()
        val gate = DashPayBackfillGateImpl(
            sdkService = sdk(DashPayBackfillSignals(2_521_270L, 2_167_130L, 61, 2_167_130L)),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140)
        )
        gate.evaluate(walletId, identityId, userId)
        gate.recordPassOutcome(
            walletId,
            identityId,
            DashPayContactProvisionReport(
                bound = true, syncSuccess = 1, syncErrors = 0,
                pendingBefore = 58, drainScheduled = true, pendingAfter = 32
            )
        )

        assertTrue(gate.evaluate(walletId, identityId, userId).shouldRun)
    }

    @Test
    fun gate_backfillStatus_reportsArmedThenReplayingThenSettled() = runBlocking {
        val store = FakeStore()
        val gate = DashPayBackfillGateImpl(
            sdkService = sdk(DashPayBackfillSignals(2_521_270L, 2_167_130L, 61, 2_167_130L)),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140)
        )
        // Nothing written yet: a wallet with no DashPay backfill history is
        // settled, so the balance persist can never deadlock on it.
        assertTrue(gate.readBackfillStatus().settled)

        store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET] = 2_521_270L
        store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_FINGERPRINT] = fingerprintA
        assertTrue(gate.readBackfillStatus().armed)
        assertFalse(gate.readBackfillStatus().settled)

        store.values.clear()
        store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_FLOOR] = 2_237_130L
        store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_TARGET] = 2_521_270L
        store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_FINGERPRINT] = fingerprintA
        assertTrue(gate.readBackfillStatus().replaying)

        store.values.clear()
        store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR] = 2_237_130L
        store.values[DashPayConfig.DASHPAY_BACKFILL_COMPLETED_THROUGH] = 2_521_270L
        store.values[DashPayConfig.DASHPAY_BACKFILL_CONTACT_FINGERPRINT] = fingerprintA
        assertTrue(gate.readBackfillStatus().settled)
    }

    @Test
    fun gate_armedStatus_expiresSoTheLaunchSeedCanNeverFreeze() = runBlocking {
        // S21 (11.10.87): a wallet whose coverage is already correct keeps its
        // armed marker forever — the gate can only clear it by OBSERVING a
        // rewind, and refuses to record coverage while a received contact
        // predates the height. Reporting that as "unsettled" indefinitely
        // froze the last-known-balance seed for the wallet's whole life.
        val store = FakeStore()
        store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET] = 2_521_270L
        store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_FINGERPRINT] = fingerprintA
        val gate = DashPayBackfillGateImpl(
            sdkService = sdk(DashPayBackfillSignals(2_521_270L, 2_167_130L, 61, 2_167_130L)),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140)
        )
        var now = 0L
        gate.nowElapsedMs = { now }

        // Inside the window the marker still holds the durable seed — this is
        // the 11.10.86 field incident, where the rewind landed 13 s later.
        assertTrue(gate.readBackfillStatus().armed)
        assertFalse(gate.readBackfillStatus().settled)
        // …but it is NOT evidence of missing money, so the indicator is free.
        assertFalse(gate.readBackfillStatus().ledgerIncomplete)

        now = DashPayBackfillGateImpl.BACKFILL_ARMED_HOLD_MS
        assertFalse("an unproven marker must not hold the seed forever", gate.readBackfillStatus().armed)
        assertTrue(gate.readBackfillStatus().settled)
    }

    @Test
    fun gate_armedStatus_restartsItsDeadlineOnceTheMarkerClears() = runBlocking {
        val store = FakeStore()
        store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET] = 2_521_270L
        store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_FINGERPRINT] = fingerprintA
        val gate = DashPayBackfillGateImpl(
            sdkService = sdk(DashPayBackfillSignals(2_521_270L, 2_167_130L, 61, 2_167_130L)),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140)
        )
        var now = 0L
        gate.nowElapsedMs = { now }
        assertTrue(gate.readBackfillStatus().armed)
        now = DashPayBackfillGateImpl.BACKFILL_ARMED_HOLD_MS
        assertFalse(gate.readBackfillStatus().armed)

        // A LATER pass arms again (FIX A: a registration buys a re-sweep).
        // That is a fresh unproven state and gets its own full window.
        store.values.clear()
        assertFalse(gate.readBackfillStatus().armed)
        store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET] = 2_600_000L
        store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_FINGERPRINT] = fingerprintA
        assertTrue(gate.readBackfillStatus().armed)
    }

    @Test
    fun gate_registrationOutstanding_isReportedAsMissingMoney() = runBlocking {
        // FIX A's guarantee, unchanged: a registered receiving account means
        // the total is short by exactly its payments, so BOTH the durable seed
        // and the user-facing indicator must hold.
        val store = FakeStore()
        val gate = DashPayBackfillGateImpl(
            sdkService = sdk(DashPayBackfillSignals(2_521_270L, 2_167_130L, 61, 2_167_130L)),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140)
        )
        assertTrue(gate.readBackfillStatus().settled)

        gate.noteAccountBuildsRegistered(26)

        assertTrue(gate.readBackfillStatus().registrationOutstanding)
        assertFalse(gate.readBackfillStatus().settled)
        assertTrue(gate.readBackfillStatus().ledgerIncomplete)
    }

    @Test
    fun gate_preFlagCoverageRecord_isTreatedAsAssumedAndReValidated() = runBlocking {
        // A wallet upgrading with a bad floor already persisted (no OBSERVED
        // flag, because the flag did not exist when it was written) must
        // re-validate rather than stay stuck on it forever.
        val store = FakeStore()
        store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR] = 2_518_645L
        store.values[DashPayConfig.DASHPAY_BACKFILL_COMPLETED_THROUGH] = 2_518_645L
        store.values[DashPayConfig.DASHPAY_BACKFILL_CONTACT_FINGERPRINT] = fingerprintA

        val gate = DashPayBackfillGateImpl(
            sdkService = sdk(DashPayBackfillSignals(2_518_646L, 2_382_103L, 5, 2_382_103L)),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140)
        )

        assertTrue(gate.evaluate(walletId, identityId, userId).shouldRun)
        assertNull(store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR])
        assertEquals(2_518_646L, store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET])
    }

    // ── durable coverage invalidation on new registrations (the crash-safe
    //    half of the post-drain follow-up sweep) ──────────────────────────

    @Test
    fun noteAccountBuildsRegistered_clearsPersistedCoverageDurably_andReportsAccepted() = runBlocking {
        // The in-memory re-provision signal dies with the process; the
        // persisted coverage does not. Accepted registrations must therefore
        // clear the coverage IN THE STORE — the same four-key removal the
        // address-window heal performs — before the note returns.
        val store = FakeStore()
        store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR] = 2_237_130L
        store.values[DashPayConfig.DASHPAY_BACKFILL_COMPLETED_THROUGH] = 2_521_270L
        store.values[DashPayConfig.DASHPAY_BACKFILL_CONTACT_FINGERPRINT] = fingerprintA
        store.values[DashPayConfig.DASHPAY_BACKFILL_COVERAGE_OBSERVED] = true
        val gate = DashPayBackfillGateImpl(
            sdkService = sdk(DashPayBackfillSignals(2_521_270L, 2_167_130L, 61, 2_167_130L)),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140)
        )

        assertTrue(gate.noteAccountBuildsRegistered(29))

        assertNull(store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR])
        assertNull(store.values[DashPayConfig.DASHPAY_BACKFILL_COMPLETED_THROUGH])
        assertNull(store.values[DashPayConfig.DASHPAY_BACKFILL_CONTACT_FINGERPRINT])
        assertNull(store.values[DashPayConfig.DASHPAY_BACKFILL_COVERAGE_OBSERVED])
    }

    @Test
    fun noteThenProcessDeath_nextLaunchProvisionsAgain() = runBlocking {
        // The crash-between-drain-and-sweep case the durable half exists for:
        // launch A's drain registers accounts, the process dies before the
        // follow-up sweep, and the in-memory signal is gone. Without the
        // durable invalidation, the untouched coverage + unchanged contact
        // set would skip the sweep on every later launch — those contacts'
        // historical payments would never be backfilled.
        val store = FakeStore()
        store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR] = 2_237_130L
        store.values[DashPayConfig.DASHPAY_BACKFILL_COMPLETED_THROUGH] = 2_521_270L
        store.values[DashPayConfig.DASHPAY_BACKFILL_CONTACT_FINGERPRINT] = fingerprintA
        store.values[DashPayConfig.DASHPAY_BACKFILL_COVERAGE_OBSERVED] = true

        val launchA = DashPayBackfillGateImpl(
            sdkService = sdk(DashPayBackfillSignals(2_521_270L, 2_167_130L, 61, 2_167_130L)),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140)
        )
        launchA.noteAccountBuildsRegistered(29)
        // …process death before the follow-up sweep…

        val launchB = DashPayBackfillGateImpl(
            sdkService = sdk(DashPayBackfillSignals(2_521_270L, 2_167_130L, 61, 2_167_130L)),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140) // contact set UNCHANGED
        )
        assertTrue(launchB.evaluate(walletId, identityId, userId).shouldRun)
    }

    @Test
    fun noteOfZeroBuilds_keepsCoverageAndReportsNothingOwed() = runBlocking {
        val store = FakeStore()
        store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR] = 2_237_130L
        store.values[DashPayConfig.DASHPAY_BACKFILL_COMPLETED_THROUGH] = 2_521_270L
        store.values[DashPayConfig.DASHPAY_BACKFILL_CONTACT_FINGERPRINT] = fingerprintA
        store.values[DashPayConfig.DASHPAY_BACKFILL_COVERAGE_OBSERVED] = true
        val gate = DashPayBackfillGateImpl(
            sdkService = sdk(DashPayBackfillSignals(2_521_270L, 2_167_130L, 61, 2_167_130L)),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140)
        )

        assertFalse(gate.noteAccountBuildsRegistered(0))

        assertEquals(2_237_130L, store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR])
    }

    @Test
    fun noteMutedByTheLoopGuard_keepsCoverageAndReportsNothingOwed() = runBlocking {
        // Re-builds of already-swept accounts (the S22 loop) must invalidate
        // nothing: clearing coverage for them would re-scan on every launch.
        val store = FakeStore()
        val gate = DashPayBackfillGateImpl(
            sdkService = sdk(DashPayBackfillSignals(2_521_270L, 2_167_130L, 61, 2_167_130L)),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 145, fromUs = 140)
        )
        // Set the cap (an evaluate records the observation: 61 SDK contact
        // requests) and exhaust it with the first, genuinely-new batch.
        gate.evaluate(walletId, identityId, userId)
        assertTrue(gate.noteAccountBuildsRegistered(61))

        // Coverage recorded afterwards…
        store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR] = 2_237_130L
        store.values[DashPayConfig.DASHPAY_BACKFILL_COMPLETED_THROUGH] = 2_521_270L
        store.values[DashPayConfig.DASHPAY_BACKFILL_CONTACT_FINGERPRINT] = fingerprintA
        store.values[DashPayConfig.DASHPAY_BACKFILL_COVERAGE_OBSERVED] = true

        // …survives the capped re-build note.
        assertFalse(gate.noteAccountBuildsRegistered(5))
        assertEquals(2_237_130L, store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR])
    }

    @Test
    fun gate_concludeNoRewind_refusesWhileARegistrationIsOutstanding() = runBlocking {
        // The quiet-window conclusion says "nothing needed backfilling" — but
        // a receival account registered since the sweep is POSITIVE evidence
        // a sweep is still owed, and coverage recorded under it would
        // suppress exactly the backfill that account needs (forever, after a
        // crash that loses the in-memory signal).
        val outgoingOnly = contactSetFingerprint(0, 140, 1_700_000_000_000L, 1_699_000_000_000L)
        val store = FakeStore()
        store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET] = 1_000_000L
        store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_FINGERPRINT] = outgoingOnly

        val gate = DashPayBackfillGateImpl(
            sdkService = sdk(DashPayBackfillSignals(1_000_042L, 790_000L, 140, null)),
            dashPayConfig = store.config(),
            contactRequestDao = dao(toUs = 0, fromUs = 140)
        )
        gate.noteAccountBuildsRegistered(3)

        assertFalse(gate.concludeNoRewindObserved(walletId, identityId, userId))
        assertNull(store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR])
        // The marker survives, so the next launch provisions again.
        assertEquals(1_000_000L, store.values[DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET])
    }
}
