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

import de.schildbach.wallet.database.dao.DashPayContactRequestDao
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────
// Persisted records
// ─────────────────────────────────────────────────────────────────────────

/**
 * A backfill that PROVABLY finished: the scan was rewound to [floor] and
 * has since climbed back to [completedThroughHeight], covering the whole
 * re-scanned range, while the app's contact set was [contactFingerprint].
 */
data class BackfillCoverage(
    val floor: Long,
    val completedThroughHeight: Long,
    val contactFingerprint: String
)

/**
 * A backfill OBSERVED to be in flight: the SDK lowered the durable synced
 * height to [floor], down from [targetHeight]. Completion is exactly
 * "synced height back at or above [targetHeight]" — every block in
 * `[floor, targetHeight]` has then been re-matched against the contact
 * addresses that were registered when the rewind fired.
 */
data class BackfillInProgress(
    val floor: Long,
    val targetHeight: Long,
    val contactFingerprint: String
)

/**
 * What one gate consultation could see. Any null is "unknown", and every
 * unknown steers the decision toward RE-RUNNING the backfill — slow, never
 * lossy.
 */
data class BackfillObservation(
    /** Durable `WalletEntity.syncedHeight`; see [DashPayBackfillSignals.syncedHeight]. */
    val syncedHeight: Long?,
    /** The app's own contact-request set identity; null when it could not be read. */
    val contactFingerprint: String?,
    /** Diagnostic `min(coreHeightCreatedAt)`; see [DashPayBackfillSignals.contactCoreHeightFloor]. */
    val sdkContactFloor: Long?,
    /** How many contact requests the SDK has persisted for us (diagnostics only). */
    val sdkContactCount: Int = 0
) {
    companion object {
        val UNKNOWN = BackfillObservation(null, null, null, 0)
    }
}

/** What the gate decided, and the bookkeeping the caller must persist. */
data class BackfillDecision(
    /** Whether the provisioning pass (and with it the SDK's rewind) may run. */
    val shouldRun: Boolean,
    /** Human-readable justification — goes verbatim into the launch log line. */
    val reason: String,
    val coverageToWrite: BackfillCoverage? = null,
    val clearCoverage: Boolean = false,
    val inProgressToWrite: BackfillInProgress? = null,
    val clearInProgress: Boolean = false
)

/** The bookkeeping a completed provisioning pass produced, if any. */
data class BackfillPassOutcome(
    val reason: String,
    val coverageToWrite: BackfillCoverage? = null,
    val inProgressToWrite: BackfillInProgress? = null
)

// ─────────────────────────────────────────────────────────────────────────
// Pure decision core (host-testable, no Android / SDK / IO)
// ─────────────────────────────────────────────────────────────────────────

/**
 * Decide whether the DIP-15 contact-account provisioning pass — and with
 * it the SDK's `synced_height` rewind — may run on this trigger.
 *
 * Evaluation order matters; each rule below is the reason it exists:
 *
 * 1. **Nothing observable → RUN.** Without a synced height or a contact
 *    fingerprint there is no evidence a backfill finished, and suppressing
 *    on no evidence is the one failure mode that loses payments.
 * 2. **A backfill is in flight → WATCH.** Re-running provisioning here is
 *    the whole defect: it re-lowers `synced_height` to the floor, so the
 *    scan never climbs and the backfill never completes. Skipping lets the
 *    durable watermark advance across launches until it passes the target.
 *    - The contact set changing mid-backfill abandons the watch and
 *      re-runs: the new contact's receival addresses were not in the filter
 *      match set for the range already re-scanned.
 *    - A watermark BELOW the recorded floor (a further rewind, or SDK
 *      storage loss) only ever widens the covered range downward.
 * 3. **Coverage recorded → SKIP**, unless the contact set changed (a new
 *    contact always needs its own backfill) or the watermark has fallen
 *    below the covered floor (the SDK's wallet state was reset, so the
 *    evidence the coverage rests on is gone).
 * 4. Otherwise **RUN** — no marker has ever been written.
 */
internal fun decideDashPayBackfill(
    observation: BackfillObservation,
    coverage: BackfillCoverage?,
    inProgress: BackfillInProgress?
): BackfillDecision {
    val syncedHeight = observation.syncedHeight
    val fingerprint = observation.contactFingerprint
    if (syncedHeight == null || fingerprint == null) {
        return BackfillDecision(
            shouldRun = true,
            reason = "backfill state is not observable (syncedHeight=$syncedHeight, " +
                "contactFingerprint=$fingerprint); forcing the rewind rather than " +
                "risking an unfinished backfill being treated as done"
        )
    }

    if (inProgress != null) {
        if (inProgress.contactFingerprint != fingerprint) {
            return BackfillDecision(
                shouldRun = true,
                reason = "contact set changed while a backfill was in flight " +
                    "(was ${inProgress.contactFingerprint}, now $fingerprint); the in-flight " +
                    "coverage cannot include the new contact's history — restarting the backfill",
                clearInProgress = true
            )
        }
        if (syncedHeight >= inProgress.targetHeight) {
            return BackfillDecision(
                shouldRun = false,
                reason = "backfill COMPLETE: scan climbed from floor ${inProgress.floor} " +
                    "back to $syncedHeight (target ${inProgress.targetHeight}); " +
                    "recording coverage and no longer forcing the rewind",
                coverageToWrite = BackfillCoverage(
                    floor = inProgress.floor,
                    completedThroughHeight = syncedHeight,
                    contactFingerprint = fingerprint
                ),
                clearInProgress = true
            )
        }
        if (syncedHeight < inProgress.floor) {
            return BackfillDecision(
                shouldRun = false,
                reason = "backfill still running and the floor moved DOWN " +
                    "(${inProgress.floor} -> $syncedHeight, target ${inProgress.targetHeight}); " +
                    "widening the covered range and continuing to watch",
                inProgressToWrite = inProgress.copy(floor = syncedHeight)
            )
        }
        return BackfillDecision(
            shouldRun = false,
            reason = "backfill IN PROGRESS: synced height $syncedHeight of target " +
                "${inProgress.targetHeight} (floor ${inProgress.floor}, " +
                "${inProgress.targetHeight - syncedHeight} blocks to go); " +
                "NOT re-triggering the rewind so the scan can finish"
        )
    }

    if (coverage != null) {
        if (coverage.contactFingerprint != fingerprint) {
            val olderThanCovered = observation.sdkContactFloor != null &&
                observation.sdkContactFloor < coverage.floor
            return BackfillDecision(
                shouldRun = true,
                reason = "contact set changed since the backfill completed " +
                    "(was ${coverage.contactFingerprint}, now $fingerprint" +
                    (if (olderThanCovered) {
                        "; the lowest contact core height ${observation.sdkContactFloor} is BELOW " +
                            "the covered floor ${coverage.floor}"
                    } else {
                        ""
                    }) +
                    "); a newly established contact's receival addresses were not watched " +
                    "during the covered scan, so its history needs a fresh backfill",
                clearCoverage = true
            )
        }
        if (syncedHeight < coverage.floor) {
            return BackfillDecision(
                shouldRun = true,
                reason = "synced height $syncedHeight fell below the covered floor " +
                    "${coverage.floor}; the SDK wallet state behind the recorded coverage " +
                    "is gone — discarding it and backfilling again",
                clearCoverage = true
            )
        }
        return BackfillDecision(
            shouldRun = false,
            reason = "backfill already covered: floor ${coverage.floor}, completed through " +
                "${coverage.completedThroughHeight}, synced height now $syncedHeight, " +
                "contact set unchanged ($fingerprint)"
        )
    }

    return BackfillDecision(
        shouldRun = true,
        reason = "no backfill coverage recorded for this wallet yet"
    )
}

/**
 * Interpret a provisioning pass that actually ran, by comparing the durable
 * synced height either side of it.
 *
 * - A **regression** (`after < before`) is the rewind itself, directly
 *   observed. That is the only way the app learns the real floor — it
 *   cannot compute one, because DashPay contact requests carry no core
 *   height on the app side. `before` becomes the completion target: once
 *   the scan returns to it, every block that was previously scanned WITHOUT
 *   the contact addresses has been re-scanned WITH them.
 *   Recorded only when the account set is settled (`pendingBefore == 0` and
 *   no drain scheduled) — while registrations are still draining the floor
 *   can still drop further, so latching a watch there would freeze a
 *   half-provisioned account set.
 * - **No regression** means nothing needed backfilling. That is only
 *   trustworthy on [firstPassInProcess]: the SDK's `rescan_triggered` guard
 *   is in-memory and per-process, so from the second pass onward a quiet
 *   pass proves nothing — the guard, not the absence of work, is what
 *   suppressed the rewind. It also requires a clean, settled sweep.
 * - Anything else writes nothing, so the next trigger re-runs.
 */
internal fun decideDashPayBackfillPassOutcome(
    before: BackfillObservation,
    syncedHeightAfter: Long?,
    report: DashPayContactProvisionReport,
    firstPassInProcess: Boolean
): BackfillPassOutcome {
    val syncedHeightBefore = before.syncedHeight
    val fingerprint = before.contactFingerprint
    if (syncedHeightBefore == null || syncedHeightAfter == null || fingerprint == null) {
        return BackfillPassOutcome(
            reason = "pass outcome not observable (before=$syncedHeightBefore, " +
                "after=$syncedHeightAfter, fingerprint=$fingerprint); recording nothing " +
                "so the next trigger re-runs the backfill"
        )
    }
    if (!report.bound) {
        return BackfillPassOutcome(reason = "SDK wallet not loaded; nothing attempted")
    }

    if (syncedHeightAfter < syncedHeightBefore) {
        if (report.pendingBefore > 0 || report.drainScheduled) {
            return BackfillPassOutcome(
                reason = "rewind observed ($syncedHeightBefore -> $syncedHeightAfter) but " +
                    "${report.pendingBefore} contact account build(s) are still registering " +
                    "(drainScheduled=${report.drainScheduled}); the floor is not settled, so " +
                    "no watch is latched and the next trigger provisions again"
            )
        }
        return BackfillPassOutcome(
            reason = "rewind observed: synced height dropped $syncedHeightBefore -> " +
                "$syncedHeightAfter; watching for the scan to climb back to " +
                "$syncedHeightBefore before recording completion",
            inProgressToWrite = BackfillInProgress(
                floor = syncedHeightAfter,
                targetHeight = syncedHeightBefore,
                contactFingerprint = fingerprint
            )
        )
    }

    val settledAndClean = report.syncErrors == 0 &&
        report.pendingBefore == 0 &&
        !report.drainScheduled
    if (firstPassInProcess && settledAndClean) {
        return BackfillPassOutcome(
            reason = "no rewind on this process's FIRST provisioning pass and the sweep was " +
                "clean and settled; there is nothing to backfill — recording coverage at " +
                "height $syncedHeightAfter",
            coverageToWrite = BackfillCoverage(
                floor = syncedHeightAfter,
                completedThroughHeight = syncedHeightAfter,
                contactFingerprint = fingerprint
            )
        )
    }
    return BackfillPassOutcome(
        reason = "no rewind observed, but the pass is not conclusive " +
            "(firstPassInProcess=$firstPassInProcess, syncErrors=${report.syncErrors}, " +
            "pendingBuilds=${report.pendingBefore}, drainScheduled=${report.drainScheduled}); " +
            "recording nothing"
    )
}

/**
 * Stable identity of the app's OWN DashPay contact-request set.
 *
 * Deliberately sourced from the app's Room table rather than the SDK's:
 * the SDK's contact table is populated by the very sweep this gate
 * suppresses, so gating on it would make new contacts invisible forever.
 * The app's table is filled independently by
 * [de.schildbach.wallet.service.platform.PlatformSyncService.updateContactRequests]
 * over dashj-platform, so it keeps moving while provisioning is skipped.
 *
 * Counts plus per-direction latest timestamps: any newly established
 * contact changes a count, which is all the gate needs (it re-runs the
 * backfill on ANY change — a strict superset of "a contact with an older
 * core height appeared", and the safe side of that approximation).
 */
internal fun contactSetFingerprint(
    requestsToUs: Int,
    requestsFromUs: Int,
    latestToUsMillis: Long,
    latestFromUsMillis: Long
): String = "to=$requestsToUs/from=$requestsFromUs@$latestToUsMillis/$latestFromUsMillis"

// ─────────────────────────────────────────────────────────────────────────
// Wiring
// ─────────────────────────────────────────────────────────────────────────

/**
 * App-side gate over the DIP-15 §12.6 coreHeight backfill.
 *
 * ## The defect this works around
 *
 * When DashPay receival contact accounts are provisioned, the SDK lowers
 * the SPV `synced_height` to the minimum `core_height_created_at` across
 * established receival contacts, so payments that landed before those
 * addresses were watched still get matched. That rewind is CORRECT and
 * NECESSARY — receival addresses enter the filter match set forward-only.
 *
 * What is broken is the "already did this" guard. `rescan_triggered`
 * (`rs-platform-wallet/src/wallet/identity/state/managed_identity/dashpay.rs`)
 * lives in memory only — it appears in no changeset, no FFI surface and no
 * persistence path — so it resets on every process start and
 * `reconcile_dashpay_rescan()`
 * (`rs-platform-wallet/src/wallet/identity/network/payments.rs`) calls the
 * unconditional regressing setter `update_synced_height(floor)` again;
 * `dash-spv/src/sync/filters/sync_manager.rs` then sees
 * `synced_height < committed_height` and calls `reset_for_rescan()`.
 *
 * For a tester with ~145 contacts the floor sits ~210,000 blocks below the
 * tip: ~25 minutes of re-matching per launch, against ~19-minute sessions.
 * The initial sync therefore never completes and the rewind repeats forever.
 *
 * ## What this gate does instead
 *
 * It remembers, ACROSS LAUNCHES, that a backfill finished, and stops
 * forcing the rewind once it genuinely has. The single rule that makes it
 * safe: **completion is recorded only after the scan is observed to have
 * climbed back past the height it was rewound from** — never when a
 * backfill is merely triggered. Marking on trigger would record an
 * interrupted backfill as done and miss those historical payments
 * permanently.
 *
 * Every unknown resolves toward re-running (slow), never toward skipping
 * (lossy). This is an INTERIM mitigation; the proper fix is to persist
 * backfill completion SDK-side so every consumer, including iOS, benefits
 * and this gate can be removed.
 */
interface DashPayBackfillGate {
    /**
     * Decide whether provisioning may run now, persisting any bookkeeping
     * the decision implies, and logging the per-launch decision line.
     * Never throws — any failure resolves to "run".
     *
     * @param ownerIdentityId our 32-byte platform identity id (SDK side).
     * @param ownerUserId the same identity as base58 — the key the app's
     *   own `dashpay_contact_request` table is indexed by.
     */
    suspend fun evaluate(
        walletIdHex: String,
        ownerIdentityId: ByteArray,
        ownerUserId: String
    ): BackfillDecision

    /**
     * Interpret the provisioning pass that just ran (against the
     * observation the matching [evaluate] took) and persist what it
     * proved. Never throws — a failure records nothing, so the next
     * trigger provisions again.
     */
    suspend fun recordPassOutcome(
        walletIdHex: String,
        ownerIdentityId: ByteArray,
        report: DashPayContactProvisionReport
    )

    companion object {
        /**
         * The pre-feature behaviour: always provision, never record. The
         * binder's default, so tests and call sites that are not about the
         * backfill are provably unaffected by it.
         */
        val ALWAYS_RUN: DashPayBackfillGate = object : DashPayBackfillGate {
            override suspend fun evaluate(
                walletIdHex: String,
                ownerIdentityId: ByteArray,
                ownerUserId: String
            ) = BackfillDecision(shouldRun = true, reason = "backfill gate disabled")

            override suspend fun recordPassOutcome(
                walletIdHex: String,
                ownerIdentityId: ByteArray,
                report: DashPayContactProvisionReport
            ) = Unit
        }
    }
}

/** See [DashPayBackfillGate]. */
@Singleton
class DashPayBackfillGateImpl @Inject constructor(
    private val sdkService: DashSdkService,
    private val dashPayConfig: DashPayConfig,
    private val contactRequestDao: DashPayContactRequestDao
) : DashPayBackfillGate {

    /**
     * The observation [evaluate] reasoned over, handed to
     * [recordPassOutcome] so the before/after comparison uses the exact
     * pre-pass watermark rather than re-reading a value the pass has
     * already moved.
     */
    @Volatile
    private var lastObservation: BackfillObservation = BackfillObservation.UNKNOWN

    /**
     * Whether this PROCESS has provisioned yet. The SDK's `rescan_triggered`
     * guard is per-process, so only the first pass of a process can prove
     * "no rewind was needed"; see [decideDashPayBackfillPassOutcome].
     */
    private val hasProvisionedInProcess = AtomicBoolean(false)

    override suspend fun evaluate(
        walletIdHex: String,
        ownerIdentityId: ByteArray,
        ownerUserId: String
    ): BackfillDecision {
        return try {
            val signals = sdkService.readDashPayBackfillSignals(walletIdHex, ownerIdentityId)
            val observation = BackfillObservation(
                syncedHeight = signals.syncedHeight,
                contactFingerprint = readContactFingerprint(ownerUserId),
                sdkContactFloor = signals.contactCoreHeightFloor,
                sdkContactCount = signals.contactRequestCount
            )
            lastObservation = observation

            val coverage = readCoverage()
            val inProgress = readInProgress()
            val decision = decideDashPayBackfill(observation, coverage, inProgress)
            applyDecision(decision)

            // The per-launch line the next tester log has to be unambiguous
            // about: the floor, what was persisted, and what we did about it.
            log.info(
                "DashPay coreHeight backfill gate: floor(min coreHeightCreatedAt over {} SDK " +
                    "contact request(s))={}, syncedHeight={}, persistedCoverage={}, " +
                    "persistedInProgress={}, appContactSet={} -> {} ({})",
                observation.sdkContactCount,
                observation.sdkContactFloor ?: "unknown",
                observation.syncedHeight ?: "unknown",
                coverage?.let {
                    "floor=${it.floor}/completedThrough=${it.completedThroughHeight}"
                } ?: "none",
                inProgress?.let { "floor=${it.floor}/target=${it.targetHeight}" } ?: "none",
                observation.contactFingerprint ?: "unknown",
                if (decision.shouldRun) "FORCING REWIND" else "SKIPPING REWIND",
                decision.reason
            )
            decision
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fail toward re-running: slow, never lossy.
            lastObservation = BackfillObservation.UNKNOWN
            log.warn("DashPay coreHeight backfill gate failed; forcing the rewind for safety", e)
            BackfillDecision(shouldRun = true, reason = "gate evaluation failed: ${e.message}")
        }
    }

    override suspend fun recordPassOutcome(
        walletIdHex: String,
        ownerIdentityId: ByteArray,
        report: DashPayContactProvisionReport
    ) {
        try {
            val before = lastObservation
            val firstPass = hasProvisionedInProcess.compareAndSet(false, true)
            val after = sdkService
                .readDashPayBackfillSignals(walletIdHex, ownerIdentityId)
                .syncedHeight
            val outcome = decideDashPayBackfillPassOutcome(before, after, report, firstPass)

            outcome.inProgressToWrite?.let { writeInProgress(it) }
            outcome.coverageToWrite?.let {
                writeCoverage(it)
                clearInProgress()
            }
            log.info("DashPay coreHeight backfill pass outcome: {}", outcome.reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Writing nothing means the next trigger re-runs — the safe side.
            log.warn(
                "failed to record the DashPay coreHeight backfill pass outcome; " +
                    "the next trigger will provision again", e
            )
        }
    }

    /** Null when the app's contact table cannot be read — treated as unknown. */
    private suspend fun readContactFingerprint(ownerUserId: String): String? = try {
        contactSetFingerprint(
            requestsToUs = contactRequestDao.countAllRequestsToUser(ownerUserId),
            requestsFromUs = contactRequestDao.countAllRequestsFromUser(ownerUserId),
            latestToUsMillis = contactRequestDao.getLastTimestampToUser(ownerUserId),
            latestFromUsMillis = contactRequestDao.getLastTimestampFromUser(ownerUserId)
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("failed to fingerprint the app's DashPay contact set; treating as unknown", e)
        null
    }

    private suspend fun applyDecision(decision: BackfillDecision) {
        if (decision.clearCoverage) clearCoverage()
        if (decision.clearInProgress) clearInProgress()
        decision.coverageToWrite?.let { writeCoverage(it) }
        decision.inProgressToWrite?.let { writeInProgress(it) }
    }

    private suspend fun readCoverage(): BackfillCoverage? {
        val floor = dashPayConfig.get(DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR) ?: return null
        val through =
            dashPayConfig.get(DashPayConfig.DASHPAY_BACKFILL_COMPLETED_THROUGH) ?: return null
        val fingerprint =
            dashPayConfig.get(DashPayConfig.DASHPAY_BACKFILL_CONTACT_FINGERPRINT) ?: return null
        return BackfillCoverage(floor, through, fingerprint)
    }

    private suspend fun writeCoverage(coverage: BackfillCoverage) {
        dashPayConfig.set(DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR, coverage.floor)
        dashPayConfig.set(
            DashPayConfig.DASHPAY_BACKFILL_COMPLETED_THROUGH, coverage.completedThroughHeight
        )
        dashPayConfig.set(
            DashPayConfig.DASHPAY_BACKFILL_CONTACT_FINGERPRINT, coverage.contactFingerprint
        )
    }

    private suspend fun clearCoverage() {
        dashPayConfig.remove(DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR)
        dashPayConfig.remove(DashPayConfig.DASHPAY_BACKFILL_COMPLETED_THROUGH)
        dashPayConfig.remove(DashPayConfig.DASHPAY_BACKFILL_CONTACT_FINGERPRINT)
    }

    private suspend fun readInProgress(): BackfillInProgress? {
        val floor = dashPayConfig.get(DashPayConfig.DASHPAY_BACKFILL_PENDING_FLOOR) ?: return null
        val target = dashPayConfig.get(DashPayConfig.DASHPAY_BACKFILL_PENDING_TARGET) ?: return null
        val fingerprint =
            dashPayConfig.get(DashPayConfig.DASHPAY_BACKFILL_PENDING_FINGERPRINT) ?: return null
        return BackfillInProgress(floor, target, fingerprint)
    }

    private suspend fun writeInProgress(inProgress: BackfillInProgress) {
        dashPayConfig.set(DashPayConfig.DASHPAY_BACKFILL_PENDING_FLOOR, inProgress.floor)
        dashPayConfig.set(DashPayConfig.DASHPAY_BACKFILL_PENDING_TARGET, inProgress.targetHeight)
        dashPayConfig.set(
            DashPayConfig.DASHPAY_BACKFILL_PENDING_FINGERPRINT, inProgress.contactFingerprint
        )
    }

    private suspend fun clearInProgress() {
        dashPayConfig.remove(DashPayConfig.DASHPAY_BACKFILL_PENDING_FLOOR)
        dashPayConfig.remove(DashPayConfig.DASHPAY_BACKFILL_PENDING_TARGET)
        dashPayConfig.remove(DashPayConfig.DASHPAY_BACKFILL_PENDING_FINGERPRINT)
    }

    companion object {
        private val log = LoggerFactory.getLogger(DashPayBackfillGateImpl::class.java)
    }
}
