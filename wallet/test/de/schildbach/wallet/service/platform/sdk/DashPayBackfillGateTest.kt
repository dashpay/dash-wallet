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
            inProgress = null
        )
        assertTrue(decision.shouldRun)
        assertNull(decision.coverageToWrite)
        assertNull(decision.inProgressToWrite)
    }

    @Test
    fun completedRun_markerPresentAndContactsUnchanged_skipsTheBackfill() {
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(1_010_000L, fingerprintA, 790_000L, 145),
            coverage = BackfillCoverage(790_000L, 1_000_000L, fingerprintA),
            inProgress = null
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
            inProgress = null
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
            inProgress = null
        )
        assertTrue(decision.shouldRun)
        assertNull(decision.coverageToWrite)
    }

    @Test
    fun unknownContactFingerprint_runsTheBackfill() {
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(1_010_000L, null, 790_000L, 145),
            coverage = BackfillCoverage(790_000L, 1_000_000L, fingerprintA),
            inProgress = null
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
            inProgress = BackfillInProgress(790_000L, 1_000_000L, fingerprintA)
        )
        assertFalse(decision.shouldRun)
        assertNull(decision.coverageToWrite) // NOT complete yet — nothing latched
    }

    @Test
    fun backfillInProgress_reachedTarget_recordsCoverage() {
        val decision = decideDashPayBackfill(
            observation = BackfillObservation(1_000_000L, fingerprintA, 790_000L, 145),
            coverage = null,
            inProgress = BackfillInProgress(790_000L, 1_000_000L, fingerprintA)
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
            inProgress = BackfillInProgress(790_000L, 1_000_000L, fingerprintA)
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
            inProgress = BackfillInProgress(790_000L, 1_000_000L, fingerprintA)
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
            inProgress = null
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
    fun passOutcome_rewindObservedWhileAccountsStillRegistering_latchesNothing() {
        val outcome = decideDashPayBackfillPassOutcome(
            before = BackfillObservation(1_000_000L, fingerprintA, 790_000L, 145),
            syncedHeightAfter = 790_000L,
            report = settledReport(pendingBefore = 12, drainScheduled = true),
            firstPassInProcess = true
        )
        assertNull(outcome.inProgressToWrite)
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
            BackfillCoverage(1_000_000L, 1_000_000L, fingerprintA),
            outcome.coverageToWrite
        )
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
        gate.recordPassOutcome(walletId, identityId, settledReport())

        // A watch was latched; completion was NOT recorded.
        assertEquals(790_000L, store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_FLOOR])
        assertEquals(1_000_000L, store.values[DashPayConfig.DASHPAY_BACKFILL_PENDING_TARGET])
        assertNull(store.values[DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR])
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
}
