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

/**
 * Host-JVM tests for the [CutoverCoordinator] transitions added for the
 * automatic + restore/new-wallet cutover: the immediate fresh-wallet
 * commit (self-gated on the SDK L1 flag), the per-wallet wipe reset, and
 * the combined [CutoverCoordinator.autoAdvanceToCutover] path the
 * auto-commit observer drives.
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
        assertTrue(coordinator.dashjEngineMayStart())
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
        assertTrue(coordinator.dashjEngineMayStart())
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
        assertTrue(coordinator.dashjEngineMayStart())
    }

    @Test
    fun autoAdvanceToCutover_isIdempotentOnceCommitted() = runBlocking {
        val (coordinator, stored) = coordinator(stored = CutoverState.CUT_OVER.name)
        val status = coordinator.autoAdvanceToCutover()
        assertEquals(CutoverState.CUT_OVER, status.state)
        assertEquals(CutoverState.CUT_OVER.name, stored())
    }

    // ── Engine-start gate hardening (FIX 3) ───────────────────────────

    @Test
    fun dashjEngineMayStart_true_whileDualRunning() = runBlocking {
        val (coordinator, _) = coordinator(stored = null, flag = true)
        assertTrue(coordinator.dashjEngineMayStart())
    }

    @Test
    fun dashjEngineMayStart_false_whenCommittedAndSdkL1EngineEnabled() = runBlocking {
        val (coordinator, _) = coordinator(stored = CutoverState.CUT_OVER.name, flag = true)
        assertFalse(coordinator.dashjEngineMayStart())
    }

    @Test
    fun dashjEngineMayStart_true_whenCommittedButSdkL1EngineDisabled() = runBlocking {
        // The latent brick: committed state persisted, but the shadow flag was
        // toggled off on a later launch → the SDK will not own L1, so dashj MUST
        // still be allowed to start (otherwise the wallet has no L1 engine).
        val (coordinator, _) = coordinator(stored = CutoverState.CUT_OVER.name, flag = false)
        assertTrue(coordinator.dashjEngineMayStart())
    }

    @Test
    fun dashjEngineMayStart_true_whenSettledButSdkL1EngineDisabled() = runBlocking {
        val (coordinator, _) = coordinator(stored = CutoverState.SETTLED.name, flag = false)
        assertTrue(coordinator.dashjEngineMayStart())
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
        boundaryAlreadyLatched: Boolean = false
    ): Triple<CutoverCoordinator, () -> String?, () -> Boolean> {
        var current = stored
        var noticeArmed = false
        var boundaryLatched = boundaryAlreadyLatched
        val config = mockk<DashPayConfig>()
        coEvery { config.get(DashPayConfig.CUTOVER_STATE) } answers { current }
        coEvery { config.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) } returns flag
        coEvery { config.get(DashPayConfig.SDK_BIND_EVER_SUCCEEDED) } returns bindEverSucceeded
        coEvery { config.get(DashPayConfig.CUTOVER_UPGRADE_BOUNDARY_CROSSED) } answers { boundaryLatched }
        coEvery { config.set(DashPayConfig.CUTOVER_UPGRADE_BOUNDARY_CROSSED, any<Boolean>()) } answers {
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
        val collector = mockk<CutoverEvidenceCollector>()
        val coordinator = CutoverCoordinator(config, collector, CoroutineScope(Dispatchers.Unconfined))
        return Triple(coordinator, { current }, { noticeArmed })
    }

    /** A previous-launch versionCode from BELOW the 11.10 cutover line (v11.9.0). */
    private val pre1110VersionCode = 11090002

    /** A previous-launch versionCode already ON the 11.10 line (11.10.1). */
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

    @Test
    fun upgradeNotice_armed_onAGenuineUpgradeFromPre1110ThatFlipsTheState() = runBlocking {
        val (coordinator, stored, armed) = noticeCoordinator(stored = null)
        coordinator.commitForUpgradedWalletAsync(pre1110VersionCode)
        assertEquals(CutoverState.CUT_OVER.name, stored())
        assertTrue("an upgrade from below 11.10 arriving pre-commit is exactly the case worth explaining", armed())
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
        // MO-995 GATE 1 (behaviour CHANGE): the version-code test now gates the
        // COMMIT too, not just the explainer. An 11.10+ previous code means this
        // launch did not cross the cutover boundary, so the upgrade seam must
        // leave the state alone — walletB committed here on a same-version
        // relaunch and was left with no L1 engine when its bind then failed.
        assertNull("a non-boundary-crossing launch must not commit", stored())
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
        // GATE 1 again: a fresh install did not cross the boundary either, and
        // the fresh-wallet seam owns that commit.
        assertNull("a fresh install must not commit through the UPGRADE seam", stored())
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
        assertTrue(coordinator.dashjEngineMayStart())
    }

    @Test
    fun rollbackForFailedBind_isANoOpFromDualRunning() = runBlocking {
        val (coordinator, stored) = coordinator(stored = CutoverState.DUAL_RUNNING.name)
        val status = coordinator.rollbackForFailedBind(consecutiveFailures = 5)
        assertEquals(CutoverState.DUAL_RUNNING, status.state)
        assertEquals(CutoverState.DUAL_RUNNING.name, stored())
        assertTrue(coordinator.dashjEngineMayStart())
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

    // ── MO-995: the UPGRADE seam's commit gates ───────────────────────

    @Test
    fun upgradeSeam_doesNotCommit_whenTheSdkBindHasNeverSucceeded() = runBlocking {
        // walletB, exactly: a genuine pre-11.10 upgrade, but this install's
        // Keystore keeps denying the lock-bound master alias so the SDK has
        // never bound. Committing would hold dashj and leave NO L1 engine —
        // no sync, no incoming transactions, "setup is incomplete".
        val (coordinator, stored, armed) = noticeCoordinator(stored = null, bindEverSucceeded = false)
        coordinator.commitForUpgradedWalletAsync(pre1110VersionCode)
        assertNull("a never-bound SDK must not be handed L1", stored())
        assertFalse("nothing committed, so nothing to explain", armed())
        assertTrue("dashj must stay available as the only engine", coordinator.dashjEngineMayStart())
    }

    @Test
    fun upgradeSeam_doesNotCommit_whenTheBindMarkerIsAbsent() = runBlocking {
        // An absent key reads as "never bound" — fail safe, not fail open.
        val (coordinator, stored, _) = noticeCoordinator(stored = null, bindEverSucceeded = null)
        coordinator.commitForUpgradedWalletAsync(pre1110VersionCode)
        assertNull("an absent bind marker must be treated as never-bound", stored())
    }

    @Test
    fun upgradeSeam_commits_onAGenuineUpgradeOnceTheBindHasSucceeded() = runBlocking {
        // walletC/D: same seam, same version-code path, but the bind works.
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
    fun upgradeSeam_latchesTheBoundary_thenCommitsOnALaterLaunchOnceTheBindWorks() = runBlocking {
        // The two-launch sequence a healthy upgrade actually takes.
        //
        // LAUNCH 1: previous launch ran 11.9.0, so the boundary is crossed —
        // but the bind has not run yet (this seam is in finalizeInitialization;
        // the binder starts with platform sync). Latch, do not commit.
        val (l1, storedL1, armedL1) = noticeCoordinator(stored = null, bindEverSucceeded = false)
        l1.commitForUpgradedWalletAsync(pre1110VersionCode)
        assertNull("launch 1 must not commit — no bind yet", storedL1())
        assertFalse(armedL1())

        // LAUNCH 2: lastVersionCode now reads THIS build, so the live version
        // test fails; only the latch keeps the seam alive. The bind succeeded
        // during launch 1, so it commits and explains.
        val (l2, storedL2, armedL2) = noticeCoordinator(
            stored = null,
            bindEverSucceeded = true,
            boundaryAlreadyLatched = true
        )
        l2.commitForUpgradedWalletAsync(sameBuildVersionCode)
        assertEquals("the latch must carry the crossing past launch 1", CutoverState.CUT_OVER.name, storedL2())
        assertTrue("the one-time explainer must survive the deferral", armedL2())
    }

    @Test
    fun upgradeSeam_neverCommits_whenTheBindNeverWorks_evenWithTheBoundaryLatched() = runBlocking {
        // walletB: latched on its upgrade launch, but 16 keystore denials later
        // the bind has still never succeeded. It must stay on dashj forever
        // rather than be handed an L1 it cannot serve.
        val (coordinator, stored, _) = noticeCoordinator(
            stored = null,
            bindEverSucceeded = false,
            boundaryAlreadyLatched = true
        )
        coordinator.commitForUpgradedWalletAsync(sameBuildVersionCode)
        assertNull("a latched boundary must not override a broken bind", stored())
        assertTrue(coordinator.dashjEngineMayStart())
    }

    @Test
    fun upgradeSeam_doesNotLatch_whenNoBoundaryWasCrossed() = runBlocking {
        // A same-build relaunch that never had a crossing must not invent one.
        val (coordinator, stored, _) = noticeCoordinator(stored = null, bindEverSucceeded = true)
        coordinator.commitForUpgradedWalletAsync(sameBuildVersionCode)
        assertNull("no crossing ever seen — the seam stays out of it", stored())
    }
}
