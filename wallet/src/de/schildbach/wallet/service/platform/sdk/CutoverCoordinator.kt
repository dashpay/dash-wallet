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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/** What a cutover-advance attempt did, for the debug readout and logs. */
data class CutoverStatus(
    val state: CutoverState,
    val verdict: CutoverVerdict
) {
    val ready: Boolean get() = verdict.ready
}

/**
 * Phase 5d: the thin, persisted wrapper over the pure [nextCutoverState]
 * machine and the [CutoverEvidenceCollector] → [evaluateCutoverReadiness]
 * pipeline. Owns NO policy (the evaluator does) and NO transition rules
 * (the state machine does) — only the persistence, the single-flight
 * serialization, and the atomic config write that IS the flip.
 *
 * Everything here is reversible and inert by default: with no explicit
 * COMMIT the state never leaves DUAL_RUNNING/READY_OBSERVED, and
 * [dashjEngineMayStart] stays true — so wiring this up changes nothing
 * observable until a deliberate [commitCutover] (a debug action first).
 */
@Singleton
class CutoverCoordinator @Inject constructor(
    private val dashPayConfig: DashPayConfig,
    private val evidenceCollector: CutoverEvidenceCollector,
    // The default keeps host tests (which construct with two args) working;
    // Dagger injects the application scope in production.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val mutex = Mutex()

    suspend fun currentState(): CutoverState =
        CutoverState.fromStored(runCatching { dashPayConfig.get(DashPayConfig.CUTOVER_STATE) }.getOrNull())

    /**
     * Whether the dashj L1 engine may start this launch (engine-start sites
     * consult this). True in every non-committed state, AND — the hardening
     * guard — also true when the state IS committed (CUT_OVER/SETTLED) but the
     * SDK L1 engine is disabled ([DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW] off).
     *
     * WHY: the stored cutover state is per-install-persisted while the shadow
     * flag can be toggled off on a LATER launch. Without this guard a committed
     * install whose flag was turned off would hold dashj (state committed) AND
     * never start the SDK shadow (start gated on the flag) — leaving the wallet
     * with NO L1 engine at all. The immediate/auto commits both self-gate on the
     * flag so this cannot arise same-launch, but the flag is external state; this
     * makes the engine-start decision robust to a later toggle-off. Never hold
     * dashj unless the SDK will actually own L1.
     */
    suspend fun dashjEngineMayStart(): Boolean {
        val state = currentState()
        if (dashjEngineMayStart(state)) return true
        if (!sdkL1EngineEnabled()) {
            log.warn(
                "cutover state is {} but USE_KOTLIN_SDK_L1_SHADOW is off — the SDK L1 engine will " +
                    "not start, so allowing dashj to run (never hold dashj without an SDK L1 owner)",
                state
            )
            return true
        }
        return false
    }

    /**
     * REACTIVE mirror of the suspend [dashjEngineMayStart] ownership decision:
     * emits whether the Dash Kotlin SDK owns L1 right now, and RE-EMITS when
     * the cutover commits (or the SDK L1 flag flips) mid-launch — so UI that
     * observes it (the About screen's L1-engine row) updates live when the
     * new SDK-primary auto-commit takes over ~15-30s after launch without a
     * relaunch, or immediately after a restore.
     *
     * SDK owns L1 exactly when the state is committed (CUT_OVER/SETTLED, i.e.
     * the pure [dashjEngineMayStart] gate says dashj must NOT start) AND the
     * SDK L1 engine is actually enabled ([DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW])
     * — the identical condition the suspend [dashjEngineMayStart] fail-safe
     * encodes, kept in lockstep by reusing the same pure predicate rather than
     * duplicating the gate. Observes the CUTOVER_STATE and shadow-flag
     * DataStore keys, so any persisted transition flows through.
     */
    fun sdkOwnsL1Flow(): Flow<Boolean> =
        combine(
            dashPayConfig.observe(DashPayConfig.CUTOVER_STATE),
            dashPayConfig.observe(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW)
        ) { storedState, shadowEnabled ->
            val state = CutoverState.fromStored(storedState)
            !dashjEngineMayStart(state) && shadowEnabled == true
        }.distinctUntilChanged()

    /**
     * Recompute readiness and apply the ADVISORY edge
     * (DUAL_RUNNING ⇄ READY_OBSERVED) — safe to call on every parity
     * probe. Never commits or rolls back. Returns the resulting status.
     */
    suspend fun observeReadiness(): CutoverStatus =
        transition(CutoverAction.OBSERVE_READINESS)

    /**
     * Commit the flip — the single atomic write making the SDK the L1
     * source of truth. Legal only from READY_OBSERVED with a still-Ready
     * verdict (the guard re-checks readiness under the lock, so a race
     * that lost readiness after observation cannot flip). No-op otherwise.
     */
    suspend fun commitCutover(): CutoverStatus =
        transition(CutoverAction.COMMIT_CUTOVER)

    /** Undo a flip while still legal (CUT_OVER → DUAL_RUNNING). */
    suspend fun rollback(): CutoverStatus =
        transition(CutoverAction.ROLLBACK)

    /**
     * Drive the full advisory→commit path in one call — the AUTOMATIC
     * cutover trigger ([CutoverAutoCommitObserver]) does exactly what a
     * manual CHECK_CUTOVER + COMMIT_CUTOVER would: recompute the advisory
     * readiness edge (DUAL_RUNNING → READY_OBSERVED if Ready), then commit
     * (READY_OBSERVED → CUT_OVER if STILL Ready under the lock). Both legs
     * re-check readiness, so this is fail-safe by construction: if any
     * blocker holds, the state never leaves DUAL_RUNNING/READY_OBSERVED and
     * [dashjEngineMayStart] stays true — no timeout, no forced commit.
     * Idempotent: a no-op once already CUT_OVER/SETTLED.
     */
    suspend fun autoAdvanceToCutover(): CutoverStatus {
        val advisory = observeReadiness()
        // Only READY_OBSERVED can legally commit; anything else (still
        // DUAL_RUNNING because a blocker holds, or already past the flip)
        // is returned unchanged without attempting the commit leg.
        if (advisory.state != CutoverState.READY_OBSERVED) return advisory
        return commitCutover()
    }

    /**
     * Restore/new-wallet path: make the SDK the L1 source of truth
     * IMMEDIATELY at wallet-setup time, bypassing the dual-run readiness
     * gate. A freshly created or restored wallet has NO already-synced
     * dashj balance to protect, so there is nothing to be "ready" about —
     * letting dashj run its full (multi-day for large CoinJoin wallets)
     * block sync first would only add a pointless wait. Committing here
     * holds dashj from the start and lets the SDK do the fast initial sync
     * (a post-restore "syncing" wait is the expected, correct UX).
     *
     * Self-gated on [DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW]: holding dashj
     * while the SDK L1 engine is OFF (release/prod, or the flag toggled
     * off) would leave the wallet with NO L1 engine at all — so when the
     * SDK path is inactive this is a deliberate no-op and the wallet stays
     * on dashj. Only advances a pre-commit state; never clobbers
     * CUT_OVER/SETTLED. Never throws.
     *
     * MO-995 escape hatch: this commit lands BEFORE the first SDK wallet
     * bind runs (it has to — see [rollbackForFailedBind] for why deferring
     * it is not possible), so a bind that then fails persistently
     * (keystore denial) would hold dashj with nothing to replace it. The
     * bind-failure rollback ([rollbackForFailedBind], driven by
     * [SdkBindRetryService]) undoes this commit in that case, restoring
     * the dashj fallback engine.
     */
    /**
     * Fire-and-forget [commitForFreshWalletSetup] for the Java `setWallet`
     * seam. `setWallet` is called on the MAIN thread by the restore-from-FILE
     * path (`RestoreWalletFromFileViewModel.restoreWallet`), so it must NEVER
     * block on DataStore I/O (first-access init can take hundreds of ms). The
     * home screen reads the cutover state reactively, so a brief
     * dual→committed transition on a fresh restore is harmless, and the
     * engine-start gate ([dashjEngineMayStart]) fails safe (dashj runs) if the
     * commit has not landed by the time the blockchain service starts.
     */
    fun commitForFreshWalletSetupAsync() {
        // Set SYNCHRONOUSLY, before any coroutine is dispatched: `setWallet`
        // (the only caller) happens-before `finalizeInitialization` on the
        // same launch, so the upgrade seam is guaranteed to observe this even
        // though the commit itself is fire-and-forget. See
        // [freshWalletSetupThisLaunch].
        freshWalletSetupThisLaunch = true
        scope.launch { commitForFreshWalletSetup() }
    }

    /**
     * Whether a FRESH wallet setup (create or restore) happened on this
     * launch — i.e. [commitForFreshWalletSetupAsync] was called.
     *
     * This exists because "did the state move to CUT_OVER on this launch?"
     * is NOT a usable test for "is this an upgrade". A fresh create/restore
     * reaches BOTH seams: `WalletApplication.setWallet` fires the fresh
     * commit, and the onboarding PIN step then calls
     * `saveWalletAndFinalizeInitialization` → `finalizeInitialization` →
     * [commitForUpgradedWalletAsync]. Whichever of the two lands the write
     * first, the other sees a DUAL_RUNNING → CUT_OVER transition and would
     * arm the one-time UPGRADE explainer for a user who just restored.
     * Ordering alone cannot fix that (both commits are async and either can
     * win), so the fresh path SUPPRESSES the notice positively.
     *
     * Process-scoped by design: it only has to survive from `setWallet` to
     * `finalizeInitialization` within one onboarding. A later launch loads
     * from the protobuf, never calls `setWallet`, and by then the state is
     * already CUT_OVER — so the transition test correctly reports "no move"
     * and nothing is armed.
     */
    @Volatile
    private var freshWalletSetupThisLaunch = false

    /**
     * The UPGRADE seam's counterpart to [commitForFreshWalletSetupAsync]:
     * same commit, but it additionally arms the one-time sync explainer
     * ([DashPayConfig.CUTOVER_UPGRADE_NOTICE_PENDING]) when — and only when —
     * the state ACTUALLY moves to CUT_OVER on this launch.
     *
     * THREE conditions must hold, and all are required:
     * - the state ACTUALLY moved to CUT_OVER on this launch — read and
     *   decided INSIDE [mutex] alongside the write itself ([commitLocked]),
     *   so a concurrent commit cannot slip between the "before" read and the
     *   write and make an already-committed install look like it just
     *   flipped;
     * - no FRESH wallet setup happened on this launch
     *   ([freshWalletSetupThisLaunch]) — a create/restore reaches this seam
     *   too (via `finalizeInitialization`), and it must keep its own
     *   already-expected post-restore sync wait rather than being told its
     *   wallet was "upgraded"; and
     * - [previousVersionCode] says the app REALLY upgraded across the cutover
     *   boundary: the launch before this one ran a PRE-CUTOVER build
     *   (`0 < previousVersionCode < ` [FIRST_CUTOVER_VERSION_CODE]). The
     *   product requirement is "explain the one-time resync only to users
     *   coming from below the cutover release". A previous code of 0 means the
     *   app was never run before (fresh install — belt to the fresh-setup
     *   latch's suspenders), and a previous code already at/above the boundary
     *   means a within-cutover-line update, whose user has already lived
     *   through (or never needed) the explainer.
     *
     * Version numbers are deliberately NOT spelled out here — the boundary
     * already moved once (11.10 → 12.0) and prose that names a release goes
     * stale silently. [FIRST_CUTOVER_VERSION_CODE] is the only statement of it.
     *
     * So: an app UPGRADE from a pre-cutover build arriving in a pre-commit
     * state flips here and arms; a within-line update never arms;
     * every later launch of the same install is already CUT_OVER and no-ops;
     * a fresh create/restore is positively suppressed twice over (version
     * code 0 AND the latch).
     *
     * @param previousVersionCode the versionCode recorded by the PREVIOUS
     *   launch (`Configuration.lastVersionCode` — a final field captured at
     *   construction, so still the pre-upgrade value even after this launch
     *   persists its own code), or 0 if the app never ran before.
     *
     * Fire-and-forget on the injected scope for the same reason as
     * [commitForFreshWalletSetupAsync] — the caller is on the main thread and
     * must not block on DataStore I/O. Never throws.
     */
    fun commitForUpgradedWalletAsync(previousVersionCode: Int) {
        scope.launch {
            // MO-995 GATE 1 — this must be a REAL upgrade across the cutover
            // boundary. The same `previousVersionCode` test below used to gate
            // only the explainer, while the commit itself ran unconditionally.
            // walletB reached here on a SAME-VERSION relaunch (previous code
            // 12000001 == this build): the seam committed, dashj was held, the
            // SDK bind then failed on the keystore, and the wallet was left
            // with no L1 engine at all. If this launch did not cross the
            // boundary, the upgrade seam has no business committing — the
            // readiness-gated auto-commit observer owns that decision.
            // `previousVersionCode` is the version the PREVIOUS LAUNCH ran, and
            // `Configuration.updateLastVersionCode` overwrites it every startup
            // — so the boundary crossing is visible for exactly one launch, and
            // that is the one launch on which GATE 2 below cannot yet be
            // satisfied (the bind runs after this seam). Latch it durably so a
            // later launch with a working bind can still commit.
            val crossedNow = isPreCutoverUpgrade(previousVersionCode)
            if (crossedNow) {
                runCatching {
                    dashPayConfig.set(DashPayConfig.CUTOVER_UPGRADE_BOUNDARY_CROSSED, true)
                }.onFailure {
                    if (it is CancellationException) throw it
                    log.warn("failed to latch the cutover boundary crossing", it)
                }
            }
            val crossedEver = crossedNow || runCatching {
                dashPayConfig.get(DashPayConfig.CUTOVER_UPGRADE_BOUNDARY_CROSSED) == true
            }.getOrDefault(false)
            if (!crossedEver) {
                log.info(
                    "upgrade seam declining to commit the cutover: previous version code {} is " +
                        "not a pre-{} upgrade (0 = fresh install, >= {} = already cut over) and " +
                        "no boundary crossing was ever latched — leaving the state alone for the " +
                        "readiness-gated auto-commit",
                    previousVersionCode, FIRST_CUTOVER_VERSION_CODE, FIRST_CUTOVER_VERSION_CODE
                )
                return@launch
            }

            // GATE 2 (bind evidence) now lives in commitLocked /
            // refusesCutOverWithoutBindEvidence, so EVERY commit path inherits
            // it — the seam, the fresh-wallet commit, and the readiness-driven
            // auto-commit that used to bypass it entirely.
            val (_, justCutOver) = mutex.withLock { commitLocked("upgraded-wallet launch") }
            if (!justCutOver) return@launch
            if (freshWalletSetupThisLaunch) {
                log.info(
                    "upgrade seam committed the cutover, but a fresh wallet setup ran on this " +
                        "launch — suppressing the one-time UPGRADE sync explainer (a restore's " +
                        "sync wait is already expected)"
                )
                return@launch
            }
            // The version-code condition that used to gate ONLY the explainer
            // here is now GATE 1 above and gates the commit itself, so reaching
            // this line already means a genuine pre-cutover upgrade. The one
            // remaining condition is the once-per-install latch inside
            // armUpgradeNoticeOnce() — GATE 1's durable boundary latch makes
            // this line reachable on more than one launch (rollback → commit),
            // and the explainer promises it happens only once.
            armUpgradeNoticeOnce()
        }
    }

    /**
     * Arm the one-time upgrade sync explainer, at most ONCE per install.
     *
     * MO-995 (Andrei, comment 91138 #2). The explainer's own copy promises
     * "This happens only once, after this update", but its marker
     * ([DashPayConfig.CUTOVER_UPGRADE_NOTICE_PENDING]) is a *pending* flag that
     * the sheet clears on acknowledgment — after which an arming site cannot
     * tell "already shown and dismissed" from "never armed". Field log
     * (2026-09-02, prod, 11.9.1 -> 12.0.0-sync): acknowledged on the 07:31:54
     * upgrade launch, then armed again by the deferred commit at 17:31:54, ten
     * hours later. So the arming needs a marker that is never cleared, which is
     * [DashPayConfig.CUTOVER_UPGRADE_NOTICE_EVER_ARMED].
     *
     * WHY THE COMMIT IS THAT LATE, since it is what makes a second arming
     * reachable at all: the bind-evidence gate
     * ([refusesCutOverWithoutBindEvidence]) cannot pass on the upgrade launch
     * — the bind runs after this seam — so the boundary is latched and the
     * commit lands on a later process start, whenever that happens to be. The
     * readiness-driven [CutoverAutoCommitObserver] is the in-launch path that
     * would close the gap, and in that same log it ran for 8.5 hours without
     * committing. Bounding the deferral means changing which engine owns L1
     * mid-launch, which the "never two live SPV engines" invariant forbids
     * (see [rollbackForFailedBind]) — so the deferral stays, and this makes it
     * harmless to the user.
     *
     * Ordering note: the latch is written BEFORE the pending flag. A crash
     * between the two costs the user the explainer; the reverse order would
     * re-arm forever, which is the bug being fixed. Never throws.
     */
    private suspend fun armUpgradeNoticeOnce() {
        val everArmed = runCatching {
            dashPayConfig.get(DashPayConfig.CUTOVER_UPGRADE_NOTICE_EVER_ARMED) == true
        }.getOrElse {
            if (it is CancellationException) throw it
            // Unreadable latch: assume it WAS armed. Suppressing an explainer
            // the user may already have seen beats re-showing a "happens only
            // once" sheet on every commit.
            log.warn("failed to read the upgrade sync-explainer latch; suppressing the explainer", it)
            true
        }
        if (everArmed) {
            log.info(
                "upgrade cutover committed, but the one-time sync explainer was already armed " +
                    "once on this install — not arming it again"
            )
            return
        }
        runCatching {
            dashPayConfig.set(DashPayConfig.CUTOVER_UPGRADE_NOTICE_EVER_ARMED, true)
            dashPayConfig.set(DashPayConfig.CUTOVER_UPGRADE_NOTICE_PENDING, true)
        }
            .onSuccess { log.info("upgrade cutover: one-time sync explainer armed") }
            .onFailure {
                if (it is CancellationException) throw it
                // Non-fatal: the cutover itself already committed; the
                // user simply does not get the explainer.
                log.warn("failed to arm the upgrade sync explainer", it)
            }
    }

    suspend fun commitForFreshWalletSetup(): CutoverStatus = mutex.withLock {
        commitLocked("fresh-wallet setup (restore/new)").first
    }

    /**
     * MO-995 bind-failure fallback: roll a committed cutover back to
     * DUAL_RUNNING because the SDK wallet bind keeps failing — after this,
     * [dashjEngineMayStart] is true again and the user syncs on the dashj
     * fallback engine instead of being stranded with NO engine at all.
     *
     * WHY a rollback and not a deferred commit: the fresh-wallet commit
     * ([commitForFreshWalletSetupAsync]) cannot wait for the first
     * successful bind, because the commit IS what routes the fresh-wallet
     * launch — [de.schildbach.wallet.service.BlockchainServiceImpl]
     * resolves the engine gate once at service onCreate (right after
     * `setWallet`), while the first bind pass only runs when platform sync
     * starts. A deferred commit would let the dashj peergroup start on
     * EVERY fresh wallet and then land mid-launch, leaving both SPV
     * engines live for the rest of the session (the "never two live SPV
     * engines" invariant). So the commit stays immediate and THIS is the
     * escape hatch: [SdkBindRetryService] calls it once
     * [SdkWalletBinder.consecutiveBindFailures] passes its threshold
     * (skipping it while the device is provably locked — a locked-device
     * keystore denial heals on unlock and must not flip engines).
     *
     * Legal only from CUT_OVER (mirrors the state machine's ROLLBACK edge —
     * SETTLED is past the migration horizon and never regresses); a no-op
     * from any other state. The live engine un-hold is
     * BlockchainServiceImpl's job: it observes CUTOVER_STATE and starts the
     * dashj peergroup when a rollback lands mid-launch. Recovery is
     * symmetric — once a later bind pass succeeds, the auto-commit observer
     * re-earns CUT_OVER through the normal readiness policy. Never throws.
     */
    suspend fun rollbackForFailedBind(consecutiveFailures: Int): CutoverStatus = mutex.withLock {
        val current = currentState()
        if (current != CutoverState.CUT_OVER) {
            return@withLock CutoverStatus(current, READY_VERDICT)
        }
        writeState(
            current,
            CutoverState.DUAL_RUNNING,
            "SDK wallet bind failed $consecutiveFailures consecutive passes — " +
                "falling back to the dashj engine so the wallet is never left with no L1 engine"
        )
    }

    /**
     * The immediate (non-readiness) commit, plus whether THIS call is the
     * one that moved the state to CUT_OVER. Both halves are computed under
     * [mutex] from a single state read, so the transition verdict cannot be
     * corrupted by a racing commit — the property the one-time upgrade
     * explainer depends on. Must be called under [mutex].
     */
    /**
     * Whether the SDK has ever proved, on THIS install, that it can bind the
     * app wallet — i.e. that its Keystore-backed master alias is usable. Set by
     * [de.schildbach.wallet.service.platform.sdk.SdkWalletBinder] on the first
     * successful pass. Absent reads as false: fail safe, not fail open.
     */
    private suspend fun sdkBindEverSucceeded(): Boolean =
        runCatching { dashPayConfig.get(DashPayConfig.SDK_BIND_EVER_SUCCEEDED) == true }
            .getOrDefault(false)

    /**
     * MO-995: refuse ANY transition into CUT_OVER while the SDK has never bound.
     *
     * Committing HOLDS the dashj engine, so committing onto an SDK that cannot
     * bind leaves the wallet with NO L1 engine — no sync, no incoming
     * transactions, "setup is incomplete" (walletB, HONOR PTP-N49, 16
     * consecutive `KeystoreDeviceLockedException` denials on the lock-bound
     * master alias).
     *
     * WHY HERE AND NOT ONLY AT THE SEAM: the gate first lived in
     * [commitForUpgradedWalletAsync], which left the readiness-driven path
     * wide open. On the emulator, with every bind failing,
     * [CutoverAutoCommitObserver] still committed FOUR times —
     * `READY_OBSERVED -> CUT_OVER on COMMIT_CUTOVER (ready=true)` followed by
     * "SDK is now L1-primary (dashj held)" — reaching walletB's end state
     * through a different door. The readiness evaluator has no notion of
     * whether the wallet is bound, so this has to be checked where the write
     * happens: [commitLocked] AND [transition] both consult it.
     *
     * Only CUT_OVER is guarded. ROLLBACK and the wipe reset move AWAY from a
     * committed state and must never be blocked — that is the escape hatch.
     */
    private suspend fun refusesCutOverWithoutBindEvidence(to: CutoverState): Boolean {
        if (to != CutoverState.CUT_OVER) return false
        if (sdkBindEverSucceeded()) return false
        log.warn(
            "declining to commit the cutover: the SDK wallet bind has never succeeded on this " +
                "install, so handing L1 to the SDK would hold dashj and leave no L1 engine — " +
                "staying on dashj until a bind succeeds"
        )
        return true
    }

    private suspend fun commitLocked(reason: String): Pair<CutoverStatus, Boolean> {
        val current = currentState()
        if (current == CutoverState.CUT_OVER || current == CutoverState.SETTLED) {
            return CutoverStatus(current, READY_VERDICT) to false
        }
        if (!sdkL1EngineEnabled()) {
            log.info(
                "fresh-wallet cutover skipped: USE_KOTLIN_SDK_L1_SHADOW is off — the SDK L1 " +
                    "engine is inactive, so dashj must keep owning L1 (staying {})",
                current
            )
            return CutoverStatus(current, READY_VERDICT) to false
        }
        if (refusesCutOverWithoutBindEvidence(CutoverState.CUT_OVER)) {
            return CutoverStatus(current, READY_VERDICT) to false
        }
        val status = writeState(current, CutoverState.CUT_OVER, reason)
        // A failed persist reports the OLD state (writeState's contract), so
        // this is false — never a phantom "just cut over".
        return status to (status.state == CutoverState.CUT_OVER)
    }

    /**
     * Per-wallet reset: on a wallet WIPE, put the cutover state back to
     * DUAL_RUNNING so a newly restored/created wallet re-runs the flow from
     * scratch (immediate-commit for a fresh restore, or dual-run →
     * caught-up → auto-commit for whatever comes next) instead of
     * inheriting the WIPED wallet's committed state. Critical for
     * correctness: without this, a Reset-then-restore would start already
     * CUT_OVER and hold dashj while the SDK has not yet synced the new
     * wallet. Unconditional write (independent of the SDK flag — a stored
     * CUT_OVER must be cleared even if the flag is momentarily off). Never
     * throws.
     */
    suspend fun resetForWalletWipe(): CutoverStatus = mutex.withLock {
        val current = currentState()
        if (current == CutoverState.DUAL_RUNNING) {
            return@withLock CutoverStatus(current, READY_VERDICT)
        }
        writeState(current, CutoverState.DUAL_RUNNING, "wallet wipe")
    }

    /** Whether the SDK L1 engine (shadow) is enabled — the fresh-commit gate. */
    private suspend fun sdkL1EngineEnabled(): Boolean =
        runCatching { dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) == true }
            .getOrDefault(false)

    /**
     * The atomic config write shared by the direct (non-readiness) state
     * moves ([commitForFreshWalletSetup]/[resetForWalletWipe]). A failed
     * persist keeps the old state (reported unchanged), never a phantom
     * flip. Must be called under [mutex].
     */
    private suspend fun writeState(from: CutoverState, to: CutoverState, reason: String): CutoverStatus {
        if (from == to) return CutoverStatus(from, READY_VERDICT)
        return runCatching { dashPayConfig.set(DashPayConfig.CUTOVER_STATE, to.name) }
            .fold(
                onSuccess = {
                    log.info("cutover state {} -> {} ({})", from, to, reason)
                    CutoverStatus(to, READY_VERDICT)
                },
                onFailure = {
                    if (it is CancellationException) throw it
                    log.warn("cutover state persist failed ({} -> {}, {}); keeping {}", from, to, reason, from, it)
                    CutoverStatus(from, READY_VERDICT)
                }
            )
    }

    private suspend fun transition(action: CutoverAction): CutoverStatus = mutex.withLock {
        val current = currentState()
        val verdict = try {
            evaluateCutoverReadiness(evidenceCollector.collect())
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Evidence unavailable = not provably ready. Block by verdict,
            // never advance; report the current state unchanged.
            log.warn("cutover readiness evaluation failed; treating as NOT ready", t)
            return@withLock CutoverStatus(current, CutoverVerdict(setOf(CutoverBlocker.PARITY_EVIDENCE_STALE)))
        }
        val next = nextCutoverState(current, action, verdict.ready)
        if (next != current && refusesCutOverWithoutBindEvidence(next)) {
            // Readiness said yes, but the SDK cannot own L1. Report the state
            // unchanged so the observer keeps observing instead of standing down.
            return@withLock CutoverStatus(current, verdict)
        }
        if (next != current) {
            runCatching { dashPayConfig.set(DashPayConfig.CUTOVER_STATE, next.name) }
                .onFailure {
                    if (it is CancellationException) throw it
                    log.warn("cutover state persist failed ({} -> {}); keeping {}", current, next, current, it)
                    return@withLock CutoverStatus(current, verdict)
                }
            log.info("cutover state {} -> {} on {} (ready={})", current, next, action, verdict.ready)
        }
        CutoverStatus(next, verdict)
    }

    companion object {
        private val log = LoggerFactory.getLogger(CutoverCoordinator::class.java)

        /**
         * The first versionCode of the release line that ships the SDK
         * cutover — currently 12.0.0. THE single statement of the boundary:
         * the emulator harness reads it from here too
         * (`scripts/cutover-emulator-test.sh`), and the tests derive their
         * fixtures from it rather than hardcoding a value.
         *
         * The store versionCode scheme is MMmmppbb (`wallet/build.gradle`:
         * 11.8.2 = 11080201), so every pre-12.0 build (11.9.x = 1109xxxx,
         * 11.25.x = 1125xxxx, …) is numerically below 12.0.0 = 12000000, and
         * every 12.0+ build (including the monotonic-decoupled QA codes, all
         * >= 12000001) is at/above it. The one-time upgrade sync explainer
         * arms only for upgrades crossing this boundary.
         *
         * THIS MOVED from 11100000 when the cutover slipped from 11.10 to
         * 12.0, and moving it inverts the meaning of every hardcoded
         * counterpart — one test asserted the opposite of its own name until
         * it was rederived from here. Keep it the only literal.
         */
        const val FIRST_CUTOVER_VERSION_CODE = 12000000

        /**
         * The verdict reported by the DIRECT (non-readiness) state moves
         * ([commitForFreshWalletSetup]/[resetForWalletWipe]): those bypass
         * the evaluator by design, so there are no blockers to report; the
         * meaningful result is the resulting [CutoverStatus.state].
         */
        private val READY_VERDICT = CutoverVerdict(emptySet())
    }
}

/**
 * Whether [previousVersionCode] — the versionCode recorded by the launch
 * BEFORE this one — means this launch genuinely crossed the cutover boundary,
 * i.e. the app was last run on a pre-cutover build.
 *
 * `0` means the app was never run before (fresh install), and anything at or
 * above [CutoverCoordinator.FIRST_CUTOVER_VERSION_CODE] means the previous
 * launch was already cut over — including a relaunch of the SAME build,
 * which is what walletB hit (previous code 12000001 on a 12000001 build).
 * Neither is an upgrade across the boundary, so neither may drive the
 * UPGRADE seam's commit.
 *
 * Pure and host-testable: this is the predicate that decides whether the
 * upgrade seam is allowed to hand L1 to the SDK at all.
 */
internal fun isPreCutoverUpgrade(previousVersionCode: Int): Boolean =
    previousVersionCode > 0 &&
        previousVersionCode < CutoverCoordinator.FIRST_CUTOVER_VERSION_CODE
