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
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Host-JVM tests for the [CutoverCoordinator]: the immediate fresh-wallet
 * and upgrade commits (both unconditional under the no-fallback policy,
 * docs/upgrade-memory-and-sync-plan.md §12), the per-wallet wipe reset,
 * the one-time upgrade explainer's arming rules, and the engine gate —
 * which now answers "dashj may not start on its own" in every state.
 */
class CutoverCoordinatorTest {

    private val probeIntervalMs = L1ShadowSyncService.PARITY_INTERVAL_MS

    /** A fully drained, caught-up, parity-proven, backed-up evidence set → Ready. */
    private fun readyEvidence(): CutoverEvidence {
        // MIN_PARITY_STREAK consecutive caught-up MATCH probes at the ~10s
        // production cadence — spans well over the short parity-window floor.
        val end = CutoverPolicy.MIN_PARITY_STREAK * probeIntervalMs
        return CutoverEvidence(
            parityObservations = (CutoverPolicy.MIN_PARITY_STREAK - 1 downTo 0).map { back ->
                ParityObservation(caughtUp = true, match = true, atElapsedMillis = end - back * probeIntervalMs)
            },
            unconfirmedSelfAuthoredTxs = 0,
            identityOperationInFlight = false,
            pendingShieldedLocks = 0,
            shieldedEnabled = true,
            shieldedReady = true,
            walletBackupExists = true,
            nowElapsedMillis = end + probeIntervalMs
        )
    }

    /**
     * A [CutoverCoordinator] over a STATEFUL in-memory CUTOVER_STATE (reads
     * see writes, so the two-step advisory→commit path works), the SDK L1
     * flag, and a fixed evidence set. Returns the coordinator plus a live
     * view of the stored state.
     */
    private fun coordinator(
        stored: String? = null,
        flag: Boolean? = true,
        evidence: CutoverEvidence = readyEvidence(),
        // MO-995 GATE 2: the UPGRADE seam refuses to commit until the SDK bind
        // has succeeded at least once on this install. Defaults to true so the
        // pre-existing cases keep exercising what they were written for; the
        // gate itself has its own tests below.
        bindEverSucceeded: Boolean? = true
    ): Pair<CutoverCoordinator, () -> String?> {
        var current = stored
        val config = mockk<DashPayConfig>()
        coEvery { config.get(DashPayConfig.CUTOVER_STATE) } answers { current }
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) } returns flag
        coEvery { config.get(DashPayConfig.SDK_BIND_EVER_SUCCEEDED) } returns bindEverSucceeded
        coEvery { config.get(DashPayConfig.CUTOVER_UPGRADE_BOUNDARY_CROSSED) } returns false
        coEvery { config.set(DashPayConfig.CUTOVER_UPGRADE_BOUNDARY_CROSSED, any<Boolean>()) } just Runs
        coEvery { config.set(DashPayConfig.CUTOVER_STATE, any<String>()) } answers {
            current = secondArg()
            Unit
        }
        val collector = mockk<CutoverEvidenceCollector>()
        coEvery { collector.collect() } returns evidence
        return CutoverCoordinator(config, collector) to { current }
    }

    // ── Restore/new-wallet immediate commit ───────────────────────────

    @Test
    fun commitForFreshWalletSetup_committsImmediately_whenFlagOn() = runBlocking {
        val (coordinator, stored) = coordinator(stored = null, flag = true)
        val status = coordinator.commitForFreshWalletSetup()
        assertEquals(CutoverState.CUT_OVER, status.state)
        assertEquals(CutoverState.CUT_OVER.name, stored())
        // No dashj L1 engine post-commit: the SDK owns L1 from the start.
        assertFalse(coordinator.dashjEngineMayStart())
    }

    @Test
    fun commitForFreshWalletSetup_isNoOp_whenSdkL1FlagOff() = runBlocking {
        // Flag off = SDK L1 engine inactive; holding dashj would leave NO L1
        // engine, so the fresh wallet must stay dual-running on dashj.
        val (coordinator, stored) = coordinator(stored = null, flag = false)
        val status = coordinator.commitForFreshWalletSetup()
        assertEquals(CutoverState.DUAL_RUNNING, status.state)
        assertEquals(null, stored())
        // …and even then dashj does not start on its own (no-fallback policy).
        assertFalse(coordinator.dashjEngineMayStart())
    }

    @Test
    fun commitForFreshWalletSetup_doesNotClobberSettled() = runBlocking {
        val (coordinator, stored) = coordinator(stored = CutoverState.SETTLED.name, flag = true)
        val status = coordinator.commitForFreshWalletSetup()
        assertEquals(CutoverState.SETTLED, status.state)
        assertEquals(CutoverState.SETTLED.name, stored())
    }

    @Test
    fun commitForFreshWalletSetup_doesNotReadinessGate() = runBlocking {
        // Evidence is NOT ready (no parity), yet a fresh wallet commits anyway:
        // there is no synced balance to protect.
        val notReady = readyEvidence().copy(parityObservations = emptyList())
        val (coordinator, stored) = coordinator(stored = null, flag = true, evidence = notReady)
        assertEquals(CutoverState.CUT_OVER, coordinator.commitForFreshWalletSetup().state)
        assertEquals(CutoverState.CUT_OVER.name, stored())
    }

    // ── Per-wallet wipe reset ─────────────────────────────────────────

    @Test
    fun resetForWalletWipe_putsCommittedStateBackToDualRunning() = runBlocking {
        val (coordinator, stored) = coordinator(stored = CutoverState.CUT_OVER.name)
        val status = coordinator.resetForWalletWipe()
        assertEquals(CutoverState.DUAL_RUNNING, status.state)
        assertEquals(CutoverState.DUAL_RUNNING.name, stored())
        // The reset exists so the NEXT wallet's commit re-runs from scratch; it
        // does not hand L1 back to dashj.
        assertFalse(coordinator.dashjEngineMayStart())
    }

    @Test
    fun resetForWalletWipe_isNoOp_whenAlreadyDualRunning() = runBlocking {
        val config = mockk<DashPayConfig>()
        coEvery { config.get(DashPayConfig.CUTOVER_STATE) } returns null
        val setSlot: CapturingSlot<String> = slot()
        coEvery { config.set(DashPayConfig.CUTOVER_STATE, capture(setSlot)) } just Runs
        val collector = mockk<CutoverEvidenceCollector>()
        val coordinator = CutoverCoordinator(config, collector)

        assertEquals(CutoverState.DUAL_RUNNING, coordinator.resetForWalletWipe().state)
        // No write when there is nothing to reset.
        coVerify(exactly = 0) { config.set(DashPayConfig.CUTOVER_STATE, any<String>()) }
    }

    // ── Automatic advisory→commit path ────────────────────────────────

    @Test
    fun autoAdvanceToCutover_commits_whenFullyReady() = runBlocking {
        val (coordinator, stored) = coordinator(stored = null, evidence = readyEvidence())
        val status = coordinator.autoAdvanceToCutover()
        assertEquals(CutoverState.CUT_OVER, status.state)
        assertEquals(CutoverState.CUT_OVER.name, stored())
    }

    @Test
    fun autoAdvanceToCutover_staysDualRunning_whenBlocked() = runBlocking {
        // A single blocker (pending shielded lock) → never leaves DUAL_RUNNING.
        val blocked = readyEvidence().copy(pendingShieldedLocks = 1)
        val (coordinator, stored) = coordinator(stored = null, evidence = blocked)
        val status = coordinator.autoAdvanceToCutover()
        assertEquals(CutoverState.DUAL_RUNNING, status.state)
        assertEquals(null, stored())
        assertFalse(coordinator.dashjEngineMayStart())
    }

    @Test
    fun autoAdvanceToCutover_isIdempotentOnceCommitted() = runBlocking {
        val (coordinator, stored) = coordinator(stored = CutoverState.CUT_OVER.name)
        val status = coordinator.autoAdvanceToCutover()
        assertEquals(CutoverState.CUT_OVER, status.state)
        assertEquals(CutoverState.CUT_OVER.name, stored())
    }

    // ── Engine-start gate: dashj never starts on its own ──────────────
    //
    // Policy (docs/upgrade-memory-and-sync-plan.md §12): the SDK owns L1 on
    // every install, and the only thing that starts a dashj peergroup is the
    // Tools › dashj sync diagnostic, applied by the blockchain service on top
    // of this gate. So the coordinator's answer is false in EVERY state and
    // regardless of the SDK L1 flag — a flag-off build has no L1 engine at
    // all (logged as a WARN) rather than a silent dashj fallback. The
    // reference install's OOM was two SPV engines in one heap; the fallback
    // path is what put them there.

    @Test
    fun dashjEngineMayStart_false_whileDualRunning() = runBlocking {
        val (coordinator, _) = coordinator(stored = null, flag = true)
        assertFalse(coordinator.dashjEngineMayStart())
    }

    @Test
    fun dashjEngineMayStart_false_whenCommittedAndSdkL1EngineEnabled() = runBlocking {
        val (coordinator, _) = coordinator(stored = CutoverState.CUT_OVER.name, flag = true)
        assertFalse(coordinator.dashjEngineMayStart())
    }

    @Test
    fun dashjEngineMayStart_false_evenWhenTheSdkL1FlagIsOff() = runBlocking {
        // No fallback: a committed install whose SDK L1 flag is off has no L1
        // engine. That is a configuration error to fix, not a reason to start
        // dashj beside (or instead of) the SDK.
        val (committed, _) = coordinator(stored = CutoverState.CUT_OVER.name, flag = false)
        assertFalse(committed.dashjEngineMayStart())
        val (settled, _) = coordinator(stored = CutoverState.SETTLED.name, flag = false)
        assertFalse(settled.dashjEngineMayStart())
        val (dual, _) = coordinator(stored = null, flag = false)
        assertFalse(dual.dashjEngineMayStart())
    }

    // ── Reactive ownership flow (About-screen L1-engine row) ──────────

    /**
     * A [CutoverCoordinator] wired to observable CUTOVER_STATE and SDK-L1-flag
     * DataStore keys, returned with live handles so a test can flip either key
     * and watch [CutoverCoordinator.sdkOwnsL1Flow] re-emit.
     */
    private fun observableCoordinator(
        stored: String? = null,
        flag: Boolean? = true
    ): Triple<CutoverCoordinator, MutableStateFlow<String?>, MutableStateFlow<Boolean?>> {
        val stateFlow = MutableStateFlow(stored)
        val shadowFlow = MutableStateFlow(flag)
        val config = mockk<DashPayConfig>()
        every { config.observe(DashPayConfig.CUTOVER_STATE) } returns stateFlow
        every { config.observe(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) } returns shadowFlow
        val collector = mockk<CutoverEvidenceCollector>()
        return Triple(CutoverCoordinator(config, collector), stateFlow, shadowFlow)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sdkOwnsL1Flow_flipsToTrue_whenTheCutoverCommitsMidObservation() = runTest {
        val (coordinator, stateFlow, _) = observableCoordinator(stored = null, flag = true)
        val emissions = mutableListOf<Boolean>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.sdkOwnsL1Flow().collect { emissions.add(it) }
        }

        // Pre-cutover (DUAL_RUNNING) → dashj owns L1 → false.
        assertEquals(listOf(false), emissions)

        // The auto-commit flips the persisted state mid-launch → SDK owns → true.
        stateFlow.value = CutoverState.CUT_OVER.name
        assertEquals(listOf(false, true), emissions)

        job.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sdkOwnsL1Flow_staysFalse_whenCommittedButSdkL1EngineDisabled() = runTest {
        // Committed state but the SDK L1 flag is off → dashj still owns L1, so
        // the About row must NOT claim the SDK owns L1 (mirrors the suspend gate).
        val (coordinator, _, shadowFlow) = observableCoordinator(
            stored = CutoverState.CUT_OVER.name,
            flag = false
        )
        val emissions = mutableListOf<Boolean>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.sdkOwnsL1Flow().collect { emissions.add(it) }
        }

        assertEquals(listOf(false), emissions)

        // Enabling the SDK L1 engine now makes it the true L1 owner → flips true.
        shadowFlow.value = true
        assertEquals(listOf(false, true), emissions)

        job.cancel()
    }

    // ── Fire-and-forget fresh-wallet commit (FIX 1, non-blocking) ─────

    @Test
    fun commitForFreshWalletSetupAsync_commitsOnTheInjectedScope() = runBlocking {
        var current: String? = null
        val config = mockk<DashPayConfig>()
        coEvery { config.get(DashPayConfig.CUTOVER_STATE) } answers { current }
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) } returns true
        // This test is about WHICH SCOPE the commit runs on, not about gating —
        // give it the bind evidence every commit path now requires.
        coEvery { config.get(DashPayConfig.SDK_BIND_EVER_SUCCEEDED) } returns true
        coEvery { config.get(DashPayConfig.CUTOVER_UPGRADE_BOUNDARY_CROSSED) } returns true
        coEvery { config.set(DashPayConfig.CUTOVER_STATE, any<String>()) } answers {
            current = secondArg()
            Unit
        }
        val collector = mockk<CutoverEvidenceCollector>()
        // Unconfined runs the launched commit inline, so the effect is observable
        // synchronously here — the production seam is fire-and-forget (non-blocking).
        val coordinator = CutoverCoordinator(config, collector, CoroutineScope(Dispatchers.Unconfined))

        coordinator.commitForFreshWalletSetupAsync()

        assertEquals(CutoverState.CUT_OVER.name, current)
    }

    // ── One-time UPGRADE sync explainer (armed only on a real upgrade) ─

    /**
     * A coordinator over stateful CUTOVER_STATE + a captured
     * CUTOVER_UPGRADE_NOTICE_PENDING write, on an Unconfined scope so the
     * fire-and-forget seams run inline. Third element reports whether the
     * explainer was armed.
     */
    private fun noticeCoordinator(
        stored: String? = null,
        flag: Boolean? = true,
        bindEverSucceeded: Boolean? = true,
        boundaryAlreadyLatched: Boolean = false,
        noticeAlreadyArmedEver: Boolean = false,
        // The first N attempts to persist the boundary latch throw, the way a
        // DataStore write does on a full or corrupt store. Int.MAX_VALUE makes
        // the key permanently unwritable.
        boundaryWriteFailures: Int = 0
    ): Triple<CutoverCoordinator, () -> String?, () -> Boolean> {
        var current = stored
        var noticeArmed = false
        var boundaryLatched = boundaryAlreadyLatched
        var noticeEverArmed = noticeAlreadyArmedEver
        var boundaryWritesLeftToFail = boundaryWriteFailures
        val config = mockk<DashPayConfig>()
        coEvery { config.get(DashPayConfig.CUTOVER_STATE) } answers { current }
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) } returns flag
        coEvery { config.get(DashPayConfig.SDK_BIND_EVER_SUCCEEDED) } returns bindEverSucceeded
        coEvery { config.get(DashPayConfig.CUTOVER_UPGRADE_BOUNDARY_CROSSED) } answers { boundaryLatched }
        coEvery { config.set(DashPayConfig.CUTOVER_UPGRADE_BOUNDARY_CROSSED, any<Boolean>()) } answers {
            if (boundaryWritesLeftToFail > 0) {
                boundaryWritesLeftToFail--
                throw IOException("datastore write failed")
            }
            boundaryLatched = secondArg()
            Unit
        }
        coEvery { config.set(DashPayConfig.CUTOVER_STATE, any<String>()) } answers {
            current = secondArg()
            Unit
        }
        coEvery { config.set(DashPayConfig.CUTOVER_UPGRADE_NOTICE_PENDING, any<Boolean>()) } answers {
            noticeArmed = secondArg()
            Unit
        }
        coEvery { config.get(DashPayConfig.CUTOVER_UPGRADE_NOTICE_EVER_ARMED) } answers { noticeEverArmed }
        coEvery { config.set(DashPayConfig.CUTOVER_UPGRADE_NOTICE_EVER_ARMED, any<Boolean>()) } answers {
            noticeEverArmed = secondArg()
            Unit
        }
        val collector = mockk<CutoverEvidenceCollector>()
        // Ready evidence, so the READINESS auto-commit path can be driven
        // through this helper too — that is the path that actually commits on
        // a real upgrade (MO-1022), and it must be able to arm the explainer.
        coEvery { collector.collect() } returns readyEvidence()
        val coordinator = CutoverCoordinator(config, collector, CoroutineScope(Dispatchers.Unconfined))
        return Triple(coordinator, { current }, { noticeArmed })
    }

    /** A previous-launch versionCode from BELOW the cutover line (v11.9.0). */
    private val pre1110VersionCode = 11090002

    /**
     * A previous-launch versionCode AT/ABOVE the cutover line — i.e. the
     * previous launch already had the SDK cutover, so this launch did not cross
     * the boundary. DERIVED from the constant on purpose: this was hardcoded to
     * 11100100 and silently became a *pre*-cutover value when the boundary moved
     * from 11100000 to 12000000, inverting what the test asserted.
     */
    private val onOrAfterCutoverVersionCode = CutoverCoordinator.FIRST_CUTOVER_VERSION_CODE + 100

    /**
     * The versionCode of THIS build — what `lastVersionCode` reads on every
     * launch after the first one. walletB reached the seam with exactly this
     * shape (previous code 12000001 on a 12000001 build).
     */
    private val sameBuildVersionCode = CutoverCoordinator.FIRST_CUTOVER_VERSION_CODE + 1

    /**
     * A DROPPED boundary-latch write must not cost the user the explainer.
     *
     * The crossing is computable on exactly one launch —
     * `Configuration.lastVersionCode` is overwritten at every startup — so if
     * GATE 1's `set(CUTOVER_UPGRADE_BOUNDARY_CROSSED, true)` throws and nothing
     * else remembers it, the explainer is lost for good: this launch's
     * `armUpgradeNoticeIfUpgraded` reads the store and sees `false`, and every
     * later launch computes `crossedNow == false` with nothing on disk to
     * recover from. Before the fix that is exactly what happened.
     *
     * Here the write fails once and the retry at arm time succeeds, so both the
     * explainer AND the durable latch come out right.
     */
    @Test
    fun upgradeNotice_survivesADroppedBoundaryLatchWrite_andRepersistsIt() = runBlocking {
        val (coordinator, stored, armed) = noticeCoordinator(
            stored = null,
            boundaryWriteFailures = 1
        )
        coordinator.commitForUpgradedWalletAsync(pre1110VersionCode)
        assertEquals(CutoverState.CUT_OVER.name, stored())
        assertTrue(
            "a dropped latch write must not cost the user the one-time explainer",
            armed()
        )
    }

    /**
     * The same dropped write, but the store NEVER accepts the key. The
     * in-memory record still carries this launch, which is all that can be
     * salvaged: if the store is permanently unwritable then the explainer's own
     * flags cannot be written either, so there is no durable outcome to assert.
     * What must NOT happen is silently skipping the explainer while the cutover
     * commits anyway.
     */
    @Test
    fun upgradeNotice_armedFromMemory_whenTheBoundaryLatchNeverPersists() = runBlocking {
        val (coordinator, stored, armed) = noticeCoordinator(
            stored = null,
            boundaryWriteFailures = Int.MAX_VALUE
        )
        coordinator.commitForUpgradedWalletAsync(pre1110VersionCode)
        assertEquals(CutoverState.CUT_OVER.name, stored())
        assertTrue("the launch that observed the crossing must still arm", armed())
    }

    @Test
    fun upgradeNotice_armed_onAGenuineUpgradeFromPre1110ThatFlipsTheState() = runBlocking {
        val (coordinator, stored, armed) = noticeCoordinator(stored = null)
        coordinator.commitForUpgradedWalletAsync(pre1110VersionCode)
        assertEquals(CutoverState.CUT_OVER.name, stored())
        assertTrue("an upgrade from below 11.10 arriving pre-commit is exactly the case worth explaining", armed())
    }

    /**
     * MO-1022 — THE BUG QA ACTUALLY HIT: on a real upgrade the explainer was
     * never armed AT ALL. Not late; never.
     *
     * Two facts combine. The upgrade seam cannot commit on the upgrade launch
     * (GATE 2 wants bind evidence, and the bind lands seconds later), so it
     * returns before any arming. The commit then happens on the READINESS
     * auto-commit path — which had no arming logic whatsoever.
     *
     * Field log (2026-09-04, prod 12000004, SM-A536B), the whole story:
     *
     *     17:10:59  declining to commit (upgraded-wallet launch): bind has never succeeded
     *     17:11:02  app wallet bound to new SDK wallet a60ed232…
     *     18:06:02  cutover state DUAL_RUNNING -> READY_OBSERVED on OBSERVE_READINESS
     *     18:06:02  cutover state READY_OBSERVED -> CUT_OVER on COMMIT_CUTOVER
     *     18:06:02  cutover auto-commit: SDK is now L1-primary (dashj held)
     *
     * — committed, and no explainer. So the arming belongs to the COMMIT, not
     * to one particular caller.
     */
    @Test
    fun upgradeNotice_isArmed_whenTheReadinessAutoCommitIsWhatCommits() = runBlocking {
        // The upgrade launch already latched the boundary; this is the later
        // commit, and it does NOT come through the seam.
        val (coordinator, stored, armed) = noticeCoordinator(
            stored = null,
            boundaryAlreadyLatched = true
        )

        coordinator.autoAdvanceToCutover()

        assertEquals(CutoverState.CUT_OVER.name, stored())
        assertTrue("the path that actually commits must arm the explainer", armed())
    }

    /**
     * The counterpart: a FRESH install that never crossed the boundary must
     * not be told its wallet was upgraded, no matter which path commits. The
     * boundary latch is what separates the two, so pin it here.
     */
    @Test
    fun upgradeNotice_isNotArmed_byAnAutoCommitOnAnInstallThatNeverUpgraded() = runBlocking {
        val (coordinator, stored, armed) = noticeCoordinator(
            stored = null,
            boundaryAlreadyLatched = false
        )

        coordinator.autoAdvanceToCutover()

        assertEquals("the commit itself still happens", CutoverState.CUT_OVER.name, stored())
        assertFalse("a fresh install has no upgrade to explain", armed())
    }

    /**
     * MO-995 (Andrei, comment 91138 #2) — THE REPORTED BUG.
     *
     * The explainer's own copy says "This happens only once, after this
     * update", but CUTOVER_UPGRADE_NOTICE_PENDING is a *pending* flag that the
     * sheet sets back to false on acknowledgment — indistinguishable from
     * never-armed. Field log, 2026-09-02 prod, 11.9.1 -> 12.0.0-sync: the
     * notice was pending and acknowledged on the 07:31:54 upgrade launch, then
     * the seam committed at 17:31:54 (`cutover state DUAL_RUNNING -> CUT_OVER
     * (upgraded-wallet launch)`) and armed the same explainer a second time,
     * ten hours later.
     *
     * Two launches, both reaching the seam legitimately: the second one passes
     * GATE 1 on the durable boundary latch. So the arming — not the commit —
     * has to be the thing that is idempotent.
     */
    @Test
    fun upgradeNotice_notArmedASecondTime_afterTheUserAlreadyAcknowledgedIt() = runBlocking {
        // Launch 1: the genuine upgrade. Arms, and latches "ever armed".
        val (first, _, armedFirst) = noticeCoordinator(stored = null)
        first.commitForUpgradedWalletAsync(pre1110VersionCode)
        assertTrue("the upgrade launch must arm the explainer", armedFirst())

        // Launch 2: same install, state back at DUAL_RUNNING (a bind-failure
        // rollback), boundary latched, notice already armed once and
        // acknowledged (PENDING is back to false — which is why it cannot be
        // the guard).
        val (second, stored, armedSecond) = noticeCoordinator(
            stored = CutoverState.DUAL_RUNNING.name,
            boundaryAlreadyLatched = true,
            noticeAlreadyArmedEver = true
        )
        second.commitForUpgradedWalletAsync(sameBuildVersionCode)

        assertEquals(
            "the commit itself must still happen — only the explainer is suppressed",
            CutoverState.CUT_OVER.name,
            stored()
        )
        assertFalse("a \"happens only once\" sheet must not be armed twice", armedSecond())
    }

    /**
     * The latch is written BEFORE the pending flag, so a first arming leaves
     * BOTH set. Pins that ordering: without the latch write, the guard above
     * has nothing to read on the next launch.
     */
    @Test
    fun upgradeNotice_firstArming_alsoLatchesTheOnceEverMarker() = runBlocking {
        var everArmed: Boolean? = null
        var pending: Boolean? = null
        var current: String? = null
        val config = mockk<DashPayConfig>()
        coEvery { config.get(DashPayConfig.CUTOVER_STATE) } answers { current }
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) } returns true
        coEvery { config.get(DashPayConfig.SDK_BIND_EVER_SUCCEEDED) } returns true
        // Stateful: GATE 1 writes this latch BEFORE attempting the commit, and
        // the arming re-reads it. A constant-false stub models neither.
        var boundaryLatched = false
        coEvery { config.get(DashPayConfig.CUTOVER_UPGRADE_BOUNDARY_CROSSED) } answers { boundaryLatched }
        coEvery { config.set(DashPayConfig.CUTOVER_UPGRADE_BOUNDARY_CROSSED, any<Boolean>()) } answers {
            boundaryLatched = secondArg()
            Unit
        }
        coEvery { config.set(DashPayConfig.CUTOVER_STATE, any<String>()) } answers {
            current = secondArg(); Unit
        }
        coEvery { config.get(DashPayConfig.CUTOVER_UPGRADE_NOTICE_EVER_ARMED) } answers { everArmed }
        coEvery { config.set(DashPayConfig.CUTOVER_UPGRADE_NOTICE_EVER_ARMED, any<Boolean>()) } answers {
            everArmed = secondArg(); Unit
        }
        coEvery { config.set(DashPayConfig.CUTOVER_UPGRADE_NOTICE_PENDING, any<Boolean>()) } answers {
            // Reading the latch here proves it was written FIRST.
            assertEquals("the once-ever latch must be set before the pending flag", true, everArmed)
            pending = secondArg(); Unit
        }
        val coordinator = CutoverCoordinator(
            config, mockk<CutoverEvidenceCollector>(), CoroutineScope(Dispatchers.Unconfined)
        )

        coordinator.commitForUpgradedWalletAsync(pre1110VersionCode)

        assertEquals(true, pending)
        assertEquals(true, everArmed)
    }

    /**
     * An unreadable latch must SUPPRESS, not arm. Re-showing a "happens only
     * once" sheet is the user-visible defect; a missed explainer is not.
     */
    @Test
    fun upgradeNotice_notArmed_whenTheOnceEverLatchCannotBeRead() = runBlocking {
        var pending: Boolean? = null
        var current: String? = null
        val config = mockk<DashPayConfig>()
        coEvery { config.get(DashPayConfig.CUTOVER_STATE) } answers { current }
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) } returns true
        coEvery { config.get(DashPayConfig.SDK_BIND_EVER_SUCCEEDED) } returns true
        // Stateful: GATE 1 writes this latch BEFORE attempting the commit, and
        // the arming re-reads it. A constant-false stub models neither.
        var boundaryLatched = false
        coEvery { config.get(DashPayConfig.CUTOVER_UPGRADE_BOUNDARY_CROSSED) } answers { boundaryLatched }
        coEvery { config.set(DashPayConfig.CUTOVER_UPGRADE_BOUNDARY_CROSSED, any<Boolean>()) } answers {
            boundaryLatched = secondArg()
            Unit
        }
        coEvery { config.set(DashPayConfig.CUTOVER_STATE, any<String>()) } answers {
            current = secondArg(); Unit
        }
        coEvery { config.get(DashPayConfig.CUTOVER_UPGRADE_NOTICE_EVER_ARMED) } throws
            IllegalStateException("DataStore read failed")
        coEvery { config.set(DashPayConfig.CUTOVER_UPGRADE_NOTICE_PENDING, any<Boolean>()) } answers {
            pending = secondArg(); Unit
        }
        val coordinator = CutoverCoordinator(
            config, mockk<CutoverEvidenceCollector>(), CoroutineScope(Dispatchers.Unconfined)
        )

        coordinator.commitForUpgradedWalletAsync(pre1110VersionCode)

        assertEquals("the commit must not be blocked by an unreadable latch", CutoverState.CUT_OVER.name, current)
        assertNull("an unreadable latch must suppress the explainer", pending)
    }

    @Test
    fun upgradeNotice_notArmed_onALaterLaunchOfAnAlreadyCommittedInstall() = runBlocking {
        val (coordinator, _, armed) = noticeCoordinator(stored = CutoverState.CUT_OVER.name)
        coordinator.commitForUpgradedWalletAsync(pre1110VersionCode)
        assertFalse("nothing flipped, so there is nothing to explain", armed())
    }

    @Test
    fun upgradeNotice_notArmed_whenPreviousVersionIsAlreadyAtOrAfterCutover() = runBlocking {
        // An update from a build that ALREADY had the cutover: the user is not
        // crossing the pre-cutover boundary, so neither the commit nor the
        // explainer may fire.
        val (coordinator, stored, armed) = noticeCoordinator(stored = null)
        coordinator.commitForUpgradedWalletAsync(onOrAfterCutoverVersionCode)
        // The commit is unconditional (every install is CUT_OVER from its first
        // launch); only the EXPLAINER depends on the boundary crossing.
        assertEquals("the seam commits regardless of the previous version", CutoverState.CUT_OVER.name, stored())
        assertFalse("an already-cut-over update must not re-explain the resync", armed())
    }

    @Test
    fun upgradeNotice_notArmed_onAFreshInstallWithNoPreviousVersion() = runBlocking {
        // lastVersionCode == 0 means the app never ran before (fresh install).
        // The fresh-setup latch also suppresses this case when setWallet ran,
        // but the version gate must hold on its own (belt AND suspenders —
        // e.g. any future path reaching this seam without setWallet).
        val (coordinator, stored, armed) = noticeCoordinator(stored = null)
        coordinator.commitForUpgradedWalletAsync(0)
        assertEquals("the seam commits for a fresh install too (idempotent with setWallet's)",
            CutoverState.CUT_OVER.name, stored())
        assertFalse("a fresh install has nothing to explain", armed())
    }

    @Test
    fun upgradeNotice_armed_atTheLastPreCutoverCode_andNotAtTheBoundaryItself() = runBlocking {
        // Boundary pin: FIRST_CUTOVER_VERSION_CODE is the first code that does
        // NOT arm; one below it still does. Expressed relative to the constant
        // so moving the cutover release cannot invert the assertion.
        val below = CutoverCoordinator.FIRST_CUTOVER_VERSION_CODE - 1
        val (armedCoordinator, _, armedBelow) = noticeCoordinator(stored = null)
        armedCoordinator.commitForUpgradedWalletAsync(below)
        assertTrue("previous code $below is pre-11.10 — must arm", armedBelow())

        val (boundaryCoordinator, _, armedAt) = noticeCoordinator(stored = null)
        boundaryCoordinator.commitForUpgradedWalletAsync(CutoverCoordinator.FIRST_CUTOVER_VERSION_CODE)
        assertFalse("the boundary code itself is already cut over — must not arm", armedAt())
    }

    @Test
    fun upgradeNotice_notArmed_whenAFreshWalletSetupRanOnThisLaunch() = runBlocking {
        // FIX-pin: a fresh create/restore reaches BOTH seams — setWallet fires
        // commitForFreshWalletSetupAsync, and onboarding's PIN step then calls
        // finalizeInitialization -> commitForUpgradedWalletAsync. Whoever writes
        // first, the other observes a DUAL_RUNNING -> CUT_OVER move and used to
        // arm the UPGRADE explainer for a user who had just restored. Passing a
        // pre-11.10 previous code on purpose: the LATCH must suppress even when
        // the version gate alone would arm (a restore onto a device that
        // previously ran a pre-11.10 install).
        val (coordinator, stored, armed) = noticeCoordinator(stored = null)

        coordinator.commitForFreshWalletSetupAsync()
        coordinator.commitForUpgradedWalletAsync(pre1110VersionCode)

        assertEquals(CutoverState.CUT_OVER.name, stored())
        assertFalse("a restore's sync wait is already expected — do not explain it", armed())
    }

    @Test
    fun upgradeNotice_notArmed_whenTheUPGRADESeamIsTheOneThatFlipsTheState() = runBlocking {
        // The ORDERING-INDEPENDENT half of the same fix. Both commits are
        // fire-and-forget, so on a fresh restore either can land first, and the
        // loser sees a DUAL_RUNNING -> CUT_OVER move. Here the FRESH commit
        // no-ops (SDK L1 flag momentarily off) and the UPGRADE seam performs
        // the flip — the case a "did the state move?" test alone gets wrong.
        // The latch is set SYNCHRONOUSLY by commitForFreshWalletSetupAsync, so
        // suppression does not depend on which write won.
        var current: String? = null
        var noticeArmed = false
        var sdkL1Enabled = false
        val config = mockk<DashPayConfig>()
        coEvery { config.get(DashPayConfig.CUTOVER_STATE) } answers { current }
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) } answers { sdkL1Enabled }
        coEvery { config.get(DashPayConfig.SDK_BIND_EVER_SUCCEEDED) } returns true
        coEvery { config.get(DashPayConfig.CUTOVER_UPGRADE_BOUNDARY_CROSSED) } returns true
        coEvery { config.set(DashPayConfig.CUTOVER_UPGRADE_BOUNDARY_CROSSED, any<Boolean>()) } just Runs
        coEvery { config.set(DashPayConfig.CUTOVER_STATE, any<String>()) } answers {
            current = secondArg()
            Unit
        }
        coEvery { config.set(DashPayConfig.CUTOVER_UPGRADE_NOTICE_PENDING, any<Boolean>()) } answers {
            noticeArmed = secondArg()
            Unit
        }
        val coordinator = CutoverCoordinator(config, mockk(), CoroutineScope(Dispatchers.Unconfined))

        coordinator.commitForFreshWalletSetupAsync()
        assertEquals("the fresh commit no-opped, as intended for this case", null, current)

        sdkL1Enabled = true
        coordinator.commitForUpgradedWalletAsync(11090002)

        assertEquals("the UPGRADE seam is the one that wrote CUT_OVER", CutoverState.CUT_OVER.name, current)
        assertFalse("…but a fresh setup ran this launch, so no upgrade explainer", noticeArmed)
    }

    // ── MO-995: the bind-failure rollback ─────────────────────────────

    @Test
    fun rollbackForFailedBind_rollsACommittedCutoverBackToDualRunning() = runBlocking {
        // The Andrei outage end-state guard: the fresh-wallet commit held
        // dashj, the SDK bind kept failing — the rollback must restore
        // dashjEngineMayStart so the wallet is never left with NO engine.
        val (coordinator, stored) = coordinator(stored = CutoverState.CUT_OVER.name)
        assertFalse(coordinator.dashjEngineMayStart())
        val status = coordinator.rollbackForFailedBind(consecutiveFailures = 5)
        assertEquals(CutoverState.DUAL_RUNNING, status.state)
        assertEquals(CutoverState.DUAL_RUNNING.name, stored())
        // The state moved, but the engine gate no longer follows it.
        assertFalse(coordinator.dashjEngineMayStart())
    }

    @Test
    fun rollbackForFailedBind_isANoOpFromDualRunning() = runBlocking {
        val (coordinator, stored) = coordinator(stored = CutoverState.DUAL_RUNNING.name)
        val status = coordinator.rollbackForFailedBind(consecutiveFailures = 5)
        assertEquals(CutoverState.DUAL_RUNNING, status.state)
        assertEquals(CutoverState.DUAL_RUNNING.name, stored())
        assertFalse(coordinator.dashjEngineMayStart())
    }

    @Test
    fun rollbackForFailedBind_neverRegressesSettled() = runBlocking {
        // SETTLED is past the migration horizon (mirrors the state
        // machine's ROLLBACK edge): the direct rollback must not regress
        // it either.
        val (coordinator, stored) = coordinator(stored = CutoverState.SETTLED.name)
        val status = coordinator.rollbackForFailedBind(consecutiveFailures = 5)
        assertEquals(CutoverState.SETTLED, status.state)
        assertEquals(CutoverState.SETTLED.name, stored())
    }

    // ── The UPGRADE seam commits unconditionally ──────────────────────
    //
    // No-fallback policy (docs/upgrade-memory-and-sync-plan.md §12). Two gates
    // used to sit in front of this commit: a boundary-crossing test (GATE 1)
    // and a persisted "the SDK has bound once on this install" marker
    // (GATE 2). GATE 2 could not pass on the upgrade launch — the bind runs
    // after the seam — so every real upgrade committed one launch late with
    // dashj running in between, and the bind succeeding mid-launch then put
    // the SDK engine beside it (the reference install's OOM). Both gates are
    // gone from the commit; the boundary test now decides only the explainer.

    @Test
    fun upgradeSeam_commits_evenWhenTheSdkBindHasNeverSucceeded() = runBlocking {
        // walletB's shape: a genuine upgrade whose keystore denies the bind.
        // The commit still lands; the bind is retried in place until the
        // device is unlocked, and dashj does not start meanwhile.
        val (coordinator, stored, armed) = noticeCoordinator(stored = null, bindEverSucceeded = false)
        coordinator.commitForUpgradedWalletAsync(pre1110VersionCode)
        assertEquals(CutoverState.CUT_OVER.name, stored())
        assertTrue("a genuine boundary-crossing upgrade explains the resync", armed())
        assertFalse("dashj must not start as a fallback", coordinator.dashjEngineMayStart())
    }

    @Test
    fun upgradeSeam_commits_whenTheBindMarkerIsAbsent() = runBlocking {
        val (coordinator, stored, _) = noticeCoordinator(stored = null, bindEverSucceeded = null)
        coordinator.commitForUpgradedWalletAsync(pre1110VersionCode)
        assertEquals(CutoverState.CUT_OVER.name, stored())
    }

    @Test
    fun upgradeSeam_commits_onAGenuineUpgradeOnceTheBindHasSucceeded() = runBlocking {
        // walletC/D: same seam, same version-code path, bind works — same result.
        val (coordinator, stored, armed) = noticeCoordinator(stored = null, bindEverSucceeded = true)
        coordinator.commitForUpgradedWalletAsync(pre1110VersionCode)
        assertEquals(CutoverState.CUT_OVER.name, stored())
        assertTrue("a genuine boundary-crossing upgrade still explains the resync", armed())
    }

    @Test
    fun isPreCutoverUpgrade_pinsTheBoundary() {
        assertFalse("0 = fresh install, never ran before", isPreCutoverUpgrade(0))
        assertFalse("negative is nonsense — fail safe", isPreCutoverUpgrade(-1))
        assertTrue("a pre-cutover release crossed the boundary", isPreCutoverUpgrade(11090000))
        assertTrue(
            "one below the line still crosses it",
            isPreCutoverUpgrade(CutoverCoordinator.FIRST_CUTOVER_VERSION_CODE - 1)
        )
        assertFalse(
            "the line itself is already 11.10",
            isPreCutoverUpgrade(CutoverCoordinator.FIRST_CUTOVER_VERSION_CODE)
        )
        assertFalse(
            "walletB: a relaunch of the SAME build is not an upgrade",
            isPreCutoverUpgrade(CutoverCoordinator.FIRST_CUTOVER_VERSION_CODE + 1)
        )
    }

    @Test
    fun upgradeSeam_commitsOnTheUpgradeLaunchItself_andLatchesTheBoundaryForTheExplainer() = runBlocking {
        // The reference install (2026-09-14): previous launch ran 11.9.1, the
        // bind has not run yet. Under the old gates this launch latched and
        // declined, and the commit landed 22 hours and two crashes later. Now
        // the same launch commits AND arms the explainer.
        val (l1, storedL1, armedL1) = noticeCoordinator(stored = null, bindEverSucceeded = false)
        l1.commitForUpgradedWalletAsync(pre1110VersionCode)
        assertEquals("the upgrade launch commits", CutoverState.CUT_OVER.name, storedL1())
        assertTrue("…and explains the one-time resync", armedL1())

        // The next launch reads THIS build's code. Already committed: no-op,
        // and the explainer (already armed once) is not armed again.
        val (l2, storedL2, armedL2) = noticeCoordinator(
            stored = CutoverState.CUT_OVER.name,
            bindEverSucceeded = true,
            boundaryAlreadyLatched = true,
            noticeAlreadyArmedEver = true
        )
        l2.commitForUpgradedWalletAsync(sameBuildVersionCode)
        assertEquals(CutoverState.CUT_OVER.name, storedL2())
        assertFalse(armedL2())
    }

    @Test
    fun upgradeSeam_commitsASameBuildRelaunchThatArrivedPreCommit_withoutInventingACrossing() = runBlocking {
        // A same-build relaunch that finds DUAL_RUNNING (a failed persist, a
        // wipe reset) is corrected here — but it never crossed the boundary,
        // so nothing is explained.
        val (coordinator, stored, armed) = noticeCoordinator(stored = null, bindEverSucceeded = true)
        coordinator.commitForUpgradedWalletAsync(sameBuildVersionCode)
        assertEquals(CutoverState.CUT_OVER.name, stored())
        assertFalse("no crossing ever seen — nothing to explain", armed())
    }

    @Test
    fun autoAdvance_commits_evenWhenTheSdkBindHasNeverSucceeded() = runBlocking {
        // The readiness path no longer consults the bind marker either. (The
        // observer that drives this path is retired separately; the debug
        // readout still reaches it.)
        val (coordinator, stored) = coordinator(stored = null, bindEverSucceeded = false)
        coordinator.autoAdvanceToCutover()
        assertEquals(CutoverState.CUT_OVER.name, stored())
    }

    @Test
    fun freshWalletCommit_commitsWithoutBindEvidence_becauseTheBindRunsAfterIt() = runBlocking {
        // Field log (2026-09-03, prod, brand-new wallet) from when a bind gate
        // was briefly applied here: "declining to commit … bind has never
        // succeeded" at 09:29:06, "app wallet bound to new SDK wallet" at
        // 09:29:09. QA reported it as "one time sync not started".
        val (coordinator, stored) = coordinator(stored = null, bindEverSucceeded = false)

        val status = coordinator.commitForFreshWalletSetup()

        assertEquals(CutoverState.CUT_OVER, status.state)
        assertEquals(CutoverState.CUT_OVER.name, stored())
        assertFalse("the SDK owns L1 from the start on a fresh wallet", coordinator.dashjEngineMayStart())
    }
}
