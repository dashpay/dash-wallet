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
 * POLICY (2026-09-16, docs/upgrade-memory-and-sync-plan.md §12): every
 * install cuts over to the SDK on its first launch of a cutover build —
 * fresh, restored and UPGRADED wallets alike — and the dashj L1 engine never
 * starts on its own. The only thing that starts a dashj peergroup is the
 * Tools › dashj sync diagnostic toggle, which the blockchain service applies
 * on top of [dashjEngineMayStart]. A failed SDK bind does NOT fall back to
 * dashj; it is retried until the device keystore is usable (see
 * [SdkBindRetryService]).
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
     * Whether the dashj L1 engine may start ON ITS OWN this launch. Always
     * false: the SDK owns L1 on every install from its first launch, and the
     * dashj peergroup runs only when the user turns on the Tools › dashj sync
     * diagnostic, which [de.schildbach.wallet.service.BlockchainServiceImpl]
     * OR-s onto this gate itself.
     *
     * The persisted [CutoverState] is no longer consulted here. It still drives
     * the UI seams' "SDK owns L1" reads through the pure [dashjEngineMayStart]
     * predicate and [sdkOwnsL1Flow], and the upgrade seam still writes it
     * (unconditionally now) so those seams settle on CUT_OVER within the first
     * launch.
     *
     * Field history for why this used to be state-dependent, and why the
     * fallback it enabled is gone: the reference install (Pixel 8a, 62 MB
     * wallet) upgraded 11.9.1 → 12.0.0 on 2026-09-14, the seam declined to
     * commit (no bind evidence yet), the bind succeeded seconds later, and on
     * the next foreground launch the dashj peergroup and the SDK engine ran
     * side by side in a 512 MB heap already 94% full of the dashj wallet —
     * OutOfMemoryError 90 s in. The "fall back to dashj" path this gate
     * implemented is what put two SPV engines in one process.
     *
     * Logs a WARN when [DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW] is off, because
     * in that configuration NO L1 engine starts. The flag is seeded on for every
     * variant; a flag-off build is a configuration error, not a reason to start
     * dashj.
     */
    suspend fun dashjEngineMayStart(): Boolean {
        if (!sdkL1EngineEnabled()) {
            log.warn(
                "USE_KOTLIN_SDK_L1_SHADOW is off — the SDK L1 engine will not start, and dashj " +
                    "no longer starts on its own (cutover state {}). No L1 engine will run " +
                    "unless the Tools › dashj sync diagnostic is on.",
                currentState()
            )
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

    // No rollback and no readiness-driven auto-commit any more. The
    // ROLLBACK edge of the pure state machine is unused; the debug readout's
    // ROLLBACK_CUTOVER action and CutoverAutoCommitObserver were removed with
    // the dashj fallback (docs/upgrade-memory-and-sync-plan.md, Phase 1a
    // item 2). commitCutover()/observeReadiness() remain for the debug readout.

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
     * This commit lands BEFORE the first SDK wallet bind runs (the bind
     * starts with platform sync). A bind that then fails is retried in
     * place by [SdkBindRetryService] until the device keystore is usable;
     * it never rolls the cutover back.
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
     * Set when this launch observed the cutover boundary crossing but could
     * NOT persist [DashPayConfig.CUTOVER_UPGRADE_BOUNDARY_CROSSED].
     *
     * The crossing is visible for exactly one launch — `Configuration
     * .lastVersionCode` is overwritten on every startup — so a dropped write
     * would otherwise lose the upgrade explainer permanently: this launch's
     * [armUpgradeNoticeIfUpgraded] reads the store and sees `false`, and every
     * later launch computes `crossedNow == false` with nothing on disk to
     * recover it from.
     *
     * This keeps the fact in memory so the current launch can still arm, and
     * so the persist can be retried at arm time. It is NOT a substitute for
     * the latch: if the store is permanently unwritable the explainer's own
     * flags cannot be written either, so there is nothing left to salvage.
     * The case it does cover is the transient one.
     */
    @Volatile
    private var boundaryCrossingUnpersisted = false

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
     * @param onCutOverForExistingWallet runs when THIS call moved an existing
     *   (not freshly created/restored) wallet to CUT_OVER — the moment the SDK
     *   takes over a wallet that dashj had synced. `WalletApplication` uses
     *   it to mark the replay as started (Phase 1b item 8) so the service
     *   stays alive and the home screen reads "syncing" before the SDK's
     *   first progress update lands. Invoked at most once per install (the
     *   state only flips once); failures are logged, never propagated.
     *
     * Fire-and-forget on the injected scope for the same reason as
     * [commitForFreshWalletSetupAsync] — the caller is on the main thread and
     * must not block on DataStore I/O. Never throws.
     */
    fun commitForUpgradedWalletAsync(
        previousVersionCode: Int,
        onCutOverForExistingWallet: () -> Unit = {}
    ) {
        scope.launch {
            // The boundary test decides ONLY whether this install is owed the
            // one-time sync explainer. It used to gate the commit as well
            // (MO-995 GATE 1), together with a bind-evidence gate (GATE 2) that
            // by construction could not pass on the upgrade launch — the bind
            // runs after this seam — so a real upgrade always committed one
            // launch late, with dashj running in between. Under the no-fallback
            // policy the commit is unconditional (below) and the boundary
            // crossing is latched here purely so the explainer survives the
            // one launch on which `previousVersionCode` reveals it.
            val crossedNow = isPreCutoverUpgrade(previousVersionCode)
            if (crossedNow) {
                runCatching {
                    dashPayConfig.set(DashPayConfig.CUTOVER_UPGRADE_BOUNDARY_CROSSED, true)
                }.onFailure {
                    if (it is CancellationException) throw it
                    // Remember it in memory: the crossing is observable on this
                    // launch only, so dropping it here costs the explainer for
                    // good. Retried by persistBoundaryCrossingIfPending, which
                    // runs on every readiness evaluation and on the decline
                    // path below — NOT only when a commit succeeds.
                    boundaryCrossingUnpersisted = true
                    log.warn(
                        "failed to latch the cutover boundary crossing — holding it in memory " +
                            "for this launch and retrying on every later opportunity",
                        it
                    )
                }
            }
            // Unconditional: no bind evidence, no boundary test, no readiness.
            // Every install is CUT_OVER from its first launch of a cutover build,
            // and a same-version relaunch that somehow arrives pre-commit (a
            // failed persist, a wipe reset that lost the race) is corrected here
            // rather than left to a readiness observer. The SDK bind that follows
            // this seam is retried until it works ([SdkBindRetryService]); a
            // failing bind no longer changes which engine owns L1.
            val (_, justCutOver) = mutex.withLock {
                commitLocked("upgraded-wallet launch")
            }
            if (!justCutOver) {
                // The seam DECLINING is the normal case on a real upgrade — the
                // bind runs after it, so GATE 2 cannot pass yet. That makes this
                // the last code to run on the one launch that can observe the
                // crossing, so a pending latch must be retried HERE; leaving it
                // to a commit that will not happen loses it when the process
                // ends.
                persistBoundaryCrossingIfPending()
                return@launch
            }
            if (freshWalletSetupThisLaunch) {
                log.info(
                    "upgrade seam committed the cutover, but a fresh wallet setup ran on this " +
                        "launch — suppressing the one-time UPGRADE sync explainer (a restore's " +
                        "sync wait is already expected)"
                )
                return@launch
            }
            // An existing wallet just changed engines: the SDK scan from birth
            // is a replay, and the row must say so before the SDK does.
            runCatching { onCutOverForExistingWallet() }
                .onSuccess { log.info("upgrade cutover: replay marked as started for the SDK takeover") }
                .onFailure {
                    if (it is CancellationException) throw it
                    log.warn("upgrade cutover: the post-commit hook failed", it)
                }
            // NB: the explainer is NOT armed here any more. It is armed from
            // [armUpgradeNoticeIfUpgraded], which every commit path calls —
            // because on a REAL upgrade this seam is not the path that
            // commits. See that function for the field evidence.
        }
    }

    /**
     * Retry a boundary latch that failed to persist, if one is pending.
     *
     * The crossing is computable on exactly one launch, so a dropped write has
     * to be re-attempted from somewhere that actually runs on THAT launch. The
     * commit path is not such a place: on a real upgrade the seam declines
     * (the bind lands after it), so the arming site is never reached. This is
     * called from the decline path and from every readiness evaluation, so the
     * retry gets as many chances as the process has left.
     *
     * Never throws; safe to call when nothing is pending.
     */
    private suspend fun persistBoundaryCrossingIfPending() {
        if (!boundaryCrossingUnpersisted) return
        runCatching {
            dashPayConfig.set(DashPayConfig.CUTOVER_UPGRADE_BOUNDARY_CROSSED, true)
        }.onSuccess {
            boundaryCrossingUnpersisted = false
            log.info("re-latched the cutover boundary crossing that failed to persist earlier")
        }.onFailure {
            if (it is CancellationException) throw it
            log.warn("failed to re-latch the cutover boundary crossing; will retry again", it)
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
     * The commit is no longer deferred (the upgrade seam commits on the
     * upgrade launch itself), but the once-ever latch stays: a wipe reset
     * followed by a re-commit, or a failed state persist retried on the next
     * launch, would otherwise re-arm a sheet that promises to appear once.
     *
     * Ordering note: the latch is written BEFORE the pending flag. A crash
     * between the two costs the user the explainer; the reverse order would
     * re-arm forever, which is the bug being fixed. Never throws.
     */
    private suspend fun armUpgradeNoticeIfUpgraded(committedBy: String) {
        // Only an install that genuinely crossed the cutover boundary is owed
        // the explainer. GATE 1 in commitForUpgradedWalletAsync latches this
        // BEFORE it attempts its commit, so it is normally already persisted by
        // the time a later auto-commit reads it on the same launch.
        val persisted = runCatching {
            dashPayConfig.get(DashPayConfig.CUTOVER_UPGRADE_BOUNDARY_CROSSED) == true
        }.getOrDefault(false)

        // ...normally. If GATE 1's write failed, the store says `false` even
        // though this launch genuinely crossed the boundary. Fall back to the
        // in-memory record and retry the persist, so a transient DataStore
        // failure costs the user nothing: without this, the explainer is lost
        // permanently, because no later launch can recompute the crossing.
        val upgraded = if (persisted) {
            true
        } else if (boundaryCrossingUnpersisted) {
            // One more attempt, then arm from memory regardless: if the store
            // is this broken the explainer's own flags will not write either,
            // and armUpgradeNoticeOnce logs that failure.
            persistBoundaryCrossingIfPending()
            true
        } else {
            false
        }
        if (!upgraded) return
        if (freshWalletSetupThisLaunch) {
            log.info(
                "cutover committed ({}), but a fresh wallet setup ran on this launch — " +
                    "suppressing the one-time UPGRADE sync explainer (a restore's sync wait " +
                    "is already expected)",
                committedBy
            )
            return
        }
        armUpgradeNoticeOnce(committedBy)
    }

    /**
     * Arms the one-time sync explainer, at most once per install.
     *
     * Split out of [armUpgradeNoticeIfUpgraded] because the eligibility tests
     * (did this install cross the boundary, did a fresh wallet setup run) and
     * the once-only guard answer different questions: eligibility is per
     * INSTALL and re-evaluated on every commit, while this latch is the thing
     * that stops a second commit — the seam and the readiness auto-commit can
     * both land on the same install — re-showing a sheet that says it happens
     * only once.
     *
     * Write order matters: [DashPayConfig.CUTOVER_UPGRADE_NOTICE_EVER_ARMED]
     * lands BEFORE [DashPayConfig.CUTOVER_UPGRADE_NOTICE_PENDING], so a crash
     * between the two costs the user the explainer rather than re-arming it
     * forever. An UNREADABLE latch is treated as "already armed" for the same
     * reason: suppressing a sheet the user may have seen beats repeating it.
     *
     * Never throws.
     */
    private suspend fun armUpgradeNoticeOnce(committedBy: String) {
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
                "cutover committed ({}), but the one-time sync explainer was already armed " +
                    "once on this install — not arming it again",
                committedBy
            )
            return
        }
        runCatching {
            dashPayConfig.set(DashPayConfig.CUTOVER_UPGRADE_NOTICE_EVER_ARMED, true)
            dashPayConfig.set(DashPayConfig.CUTOVER_UPGRADE_NOTICE_PENDING, true)
        }
            .onSuccess { log.info("upgrade cutover: one-time sync explainer armed ({})", committedBy) }
            .onFailure {
                if (it is CancellationException) throw it
                // Non-fatal: the cutover itself already committed; the
                // user simply does not get the explainer.
                log.warn("failed to arm the upgrade sync explainer", it)
            }
    }

    /**
     * The fresh-wallet commit. Like the upgrade seam it commits without bind
     * evidence: on a fresh install there is none BY CONSTRUCTION — the commit
     * is what routes the launch, and the first bind pass only runs once
     * platform sync starts, after it. Field log (2026-09-03, prod 12000003,
     * brand-new wallet) from when a bind-evidence guard was briefly applied
     * here: "declining to commit … bind has never succeeded" at 09:29:06,
     * "app wallet bound to new SDK wallet" at 09:29:09. QA reported it as
     * "one time sync not started".
     */
    suspend fun commitForFreshWalletSetup(): CutoverStatus = mutex.withLock {
        commitLocked("fresh-wallet setup (restore/new)").first
    }

    /**
     * The immediate (non-readiness) commit, plus whether THIS call is the
     * one that moved the state to CUT_OVER. Both halves are computed under
     * [mutex] from a single state read, so the transition verdict cannot be
     * corrupted by a racing commit — the property the one-time upgrade
     * explainer depends on. Must be called under [mutex].
     *
     * No bind-evidence gate. One lived here (MO-995 GATE 2, `36792ccd1`) and
     * refused every path into CUT_OVER until the SDK had bound once on the
     * install. It could not pass on the launch that needed it — the bind runs
     * after both seams — so upgrades committed one launch late with dashj
     * running in between, and on the reference install that produced two SPV
     * engines in one 512 MB heap. A bind that fails is now retried in place
     * ([SdkBindRetryService]); it never decides engine ownership.
     */
    private suspend fun commitLocked(reason: String): Pair<CutoverStatus, Boolean> {
        val current = currentState()
        if (current == CutoverState.CUT_OVER || current == CutoverState.SETTLED) {
            return CutoverStatus(current, READY_VERDICT) to false
        }
        if (!sdkL1EngineEnabled()) {
            log.info(
                "cutover skipped ({}): USE_KOTLIN_SDK_L1_SHADOW is off — the SDK L1 " +
                    "engine is inactive, so dashj must keep owning L1 (staying {})",
                reason, current
            )
            return CutoverStatus(current, READY_VERDICT) to false
        }
        val status = writeState(current, CutoverState.CUT_OVER, reason)
        // A failed persist reports the OLD state (writeState's contract), so
        // this is false — never a phantom "just cut over".
        val justCutOver = status.state == CutoverState.CUT_OVER
        if (justCutOver) armUpgradeNoticeIfUpgraded(reason)
        return status to justCutOver
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
        // The readiness observer drives this repeatedly for as long as the
        // process lives, which makes it the best available retry clock for a
        // boundary latch whose first write failed.
        persistBoundaryCrossingIfPending()
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
        if (next != current) {
            runCatching { dashPayConfig.set(DashPayConfig.CUTOVER_STATE, next.name) }
                .onFailure {
                    if (it is CancellationException) throw it
                    log.warn("cutover state persist failed ({} -> {}); keeping {}", current, next, current, it)
                    return@withLock CutoverStatus(current, verdict)
                }
            log.info("cutover state {} -> {} on {} (ready={})", current, next, action, verdict.ready)
            if (next == CutoverState.CUT_OVER) armUpgradeNoticeIfUpgraded("readiness auto-commit")
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
