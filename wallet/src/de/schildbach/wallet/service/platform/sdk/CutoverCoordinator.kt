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
     *   boundary: the launch before this one ran a PRE-11.10 build
     *   (`0 < previousVersionCode < ` [FIRST_CUTOVER_VERSION_CODE]). The
     *   product requirement is "explain the one-time resync only to users
     *   coming from below 11.10". A previous code of 0 means the app was
     *   never run before (fresh install — belt to the fresh-setup latch's
     *   suspenders), and a previous code already at/above 11.10 means an
     *   11.10.x → 11.10.y update, whose user has already lived through (or
     *   never needed) the explainer.
     *
     * So: an app UPGRADE from a pre-11.10 build arriving in a pre-commit
     * state flips here and arms; an 11.10.x → 11.10.y update never arms;
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
                        "not a pre-{} upgrade (0 = fresh install, >= {} = already on 11.10+) and " +
                        "no boundary crossing was ever latched — leaving the state alone for the " +
                        "readiness-gated auto-commit",
                    previousVersionCode, FIRST_CUTOVER_VERSION_CODE, FIRST_CUTOVER_VERSION_CODE
                )
                return@launch
            }

            // MO-995 GATE 2 — never hand L1 to an SDK that has never proved it
            // can bind on this install. Committing holds the dashj engine, so
            // committing while the bind is broken leaves the wallet with NO L1
            // engine: no sync, no incoming transactions, "setup is incomplete".
            // walletC/D show the commit today runs 3-7 seconds BEFORE the first
            // bind is even attempted, so success there was luck, not design.
            //
            // Deliberately NOT a deferred/awaited commit: BlockchainServiceImpl
            // resolves its engine gate once at service onCreate and
            // `onCutoverStateChanged` is un-hold-only, so a commit landing
            // mid-launch cannot stop a live dashj peergroup. Declining outright
            // keeps dashj as the single engine for this launch; once a bind
            // succeeds, the auto-commit observer earns CUT_OVER through the
            // normal readiness policy.
            val bindEverSucceeded =
                runCatching { dashPayConfig.get(DashPayConfig.SDK_BIND_EVER_SUCCEEDED) == true }
                    .getOrDefault(false)
            if (!bindEverSucceeded) {
                log.warn(
                    "upgrade seam declining to commit the cutover: the SDK wallet bind has never " +
                        "succeeded on this install, so handing L1 to the SDK would hold dashj and " +
                        "leave no L1 engine — staying on dashj until a bind succeeds"
                )
                return@launch
            }

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
            // this line already means a genuine pre-cutover upgrade. Arming is
            // unconditional from here.
            runCatching { dashPayConfig.set(DashPayConfig.CUTOVER_UPGRADE_NOTICE_PENDING, true) }
                .onSuccess { log.info("upgrade cutover: one-time sync explainer armed") }
                .onFailure {
                    if (it is CancellationException) throw it
                    // Non-fatal: the cutover itself already committed; the
                    // user simply does not get the explainer.
                    log.warn("failed to arm the upgrade sync explainer", it)
                }
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
         * The first versionCode of the 11.10 line — the release that ships the
         * SDK cutover. The store versionCode scheme is MMmmppbb
         * (`wallet/build.gradle`: 11.8.2 = 11080201), so EVERY pre-11.10 build
         * (11.9.x = 1109xxxx, 11.8.x = 1108xxxx, …) is numerically below
         * 11.10.0 = 11100000, and every 11.10+ build (including the
         * monotonic-decoupled QA codes, all >= 11100001) is at/above it. The
         * one-time upgrade sync explainer arms only for upgrades crossing
         * this boundary.
         */
        const val FIRST_CUTOVER_VERSION_CODE = 11100000

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
 * i.e. the app was last run on a pre-11.10 build.
 *
 * `0` means the app was never run before (fresh install), and anything at or
 * above [CutoverCoordinator.FIRST_CUTOVER_VERSION_CODE] means the previous
 * launch was already on 11.10+ — including a relaunch of the SAME build,
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
