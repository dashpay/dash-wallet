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
    val contactFingerprint: String,
    /**
     * Whether [floor] was DIRECTLY OBSERVED as the durable synced height
     * after the SDK's rewind, rather than ASSUMED from a pass that produced
     * no rewind at all.
     *
     * An observed floor is authoritative — the SDK picked it, so a contact
     * core height below it merely reflects the SDK subsetting by direction
     * and establishment, and the record is never second-guessed. An assumed
     * floor is only ever "the tip when we concluded there was nothing to
     * do", so it stays open to being invalidated by later evidence that a
     * rewind was in fact owed.
     */
    val observedFloor: Boolean = true
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
 * A provisioning pass ARMED but not yet accounted for: [evaluate] decided
 * shouldRun=true and recorded the pre-pass durable synced height as
 * [targetHeight] BEFORE the pass ran, while the app's contact set was
 * [contactFingerprint].
 *
 * This exists because the rewind's two observation channels both fail on
 * the field wallet:
 *
 * 1. [DashPayBackfillGateImpl.recordPassOutcome]'s one-shot after-read
 *    races the SDK's ASYNC watermark persist — the rewind fires in SDK
 *    memory synchronously during the sweep, but the durable
 *    `WalletEntity.syncedHeight` only drops via a filter-loop tick +
 *    changeset batch ~9–60 s later, so "no rewind observed" is logged even
 *    though the rewind happened.
 * 2. The old settledness precondition (`pendingBefore == 0`) is
 *    UNSATISFIABLE on a wallet whose contact builds are re-enqueued every
 *    launch (SDK FFI persistence gap), so even a caught rewind was never
 *    latched.
 *
 * The armed marker turns the observation inside out: instead of trying to
 * SEE the persisted drop in a razor-thin window, remember what the height
 * WAS before provisioning, and let any LATER consultation — same process
 * or next launch — conclude "the rewind fired" from the durable height
 * sitting BELOW [targetHeight]. That comparison cannot be raced: the
 * persist lands eventually, and until the scan climbs back past
 * [targetHeight] the height stays below it.
 */
data class BackfillArmed(
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
    val sdkContactCount: Int = 0,
    /**
     * `min(coreHeightCreatedAt)` over RECEIVED requests only; see
     * [DashPayBackfillSignals.receivedContactCoreHeightFloor]. Consulted
     * only to WITHHOLD a "nothing to backfill" conclusion.
     */
    val sdkReceivedContactFloor: Long? = null,
    /**
     * How many contact requests the APP's own Room table holds — the same
     * read [contactFingerprint] is built from. Null when it could not be
     * read. Exists so the gate can tell "the SDK looked and there is
     * genuinely nothing" from "the SDK has not ingested the app's contacts
     * yet", which look identical in [sdkContactCount] alone.
     */
    val appContactCount: Int? = null
) {
    companion object {
        val UNKNOWN = BackfillObservation(null, null, null, 0, null, null)
    }
}

/**
 * The gate's persisted bookkeeping, as consumers OUTSIDE the gate need to
 * read it — "is the DIP-15 contact backfill settled, or is money still
 * missing from the ledger?".
 *
 * ## Two predicates, because the two consumers ask different questions
 *
 * [armed] is BOOKKEEPING, not a statement about the money. It means "a
 * provisioning pass ran and we could not PROVE what it did", and on a healthy
 * wallet it is PERMANENT: [decideDashPayBackfillPassOutcome] can only clear it
 * by observing a rewind or by recording coverage, and recording coverage is
 * refused by [noRewindConclusionBlockedBecause] whenever any RECEIVED contact
 * request predates the height — which is true of essentially every wallet that
 * has contacts. A wallet whose coverage is already correct therefore stays
 * armed forever, and it is perfectly healthy (observed on the S21, 11.10.87:
 * "no rewind observed, but the pass is not conclusive … recording nothing",
 * repeating with pendingBuilds=0, syncErrors=0, drainScheduled=false).
 *
 * So:
 * - [ledgerIncomplete] — "money is provably missing from the ledger RIGHT
 *   NOW" — is what a USER-FACING "still syncing" indicator must use. It
 *   excludes [armed] for exactly the reason above: a permanent bookkeeping
 *   state would pin the indicator on forever, and an indicator that never
 *   clears is worse than one that clears slightly early.
 * - [settled] — "nothing is owed, in flight, or unproven" — is the stricter
 *   test the DURABLE last-known-balance seed uses, where the cost of being
 *   wrong is a persisted figure that poisons every later launch. It keeps
 *   [armed], but the gate reports [armed] under a deadline (see
 *   [BACKFILL_ARMED_HOLD_MS]) so this cannot freeze the seed forever either.
 *
 * All flags false = settled, which is also what a wallet with no DashPay
 * contacts always reads: the gate has never written anything for it, and a
 * mechanism that does not apply must never hold it back.
 *
 * @property armed a provisioning pass is armed and unaccounted for: a rewind
 *   MAY be owed. See above — on its own this is not evidence of missing money.
 * @property replaying a rewind was OBSERVED and the scan is climbing back —
 *   the balance is mid-replay and passes through arbitrary partial values.
 * @property registrationOutstanding receiving accounts were REGISTERED since
 *   the last sweep, so their payments are not matched yet and the total is
 *   short by exactly them. Positive evidence of missing money, and short-lived
 *   (the next sweep consumes it).
 */
data class DashPayBackfillStatus(
    val armed: Boolean,
    val replaying: Boolean,
    val registrationOutstanding: Boolean = false
) {
    /** Nothing owed, in flight or unproven — the strict test for the durable seed. */
    val settled: Boolean get() = !armed && !replaying && !registrationOutstanding

    /**
     * Money is provably missing from the ledger right now — the test a
     * user-facing "still syncing" signal uses. Deliberately excludes [armed];
     * see the class KDoc.
     */
    val ledgerIncomplete: Boolean get() = replaying || registrationOutstanding

    companion object {
        val SETTLED = DashPayBackfillStatus(armed = false, replaying = false)
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
    val clearInProgress: Boolean = false,
    /**
     * Armed marker to persist BEFORE provisioning runs — set on every
     * shouldRun=true decision whose observation is readable, so a later
     * consultation can detect the rewind from the durable height alone.
     */
    val armedToWrite: BackfillArmed? = null,
    val clearArmed: Boolean = false
)

/** The bookkeeping a completed provisioning pass produced, if any. */
data class BackfillPassOutcome(
    val reason: String,
    val coverageToWrite: BackfillCoverage? = null,
    val inProgressToWrite: BackfillInProgress? = null,
    /** The armed marker is spent once the pass it armed is accounted for. */
    val clearArmed: Boolean = false
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
 *    on no evidence is the one failure mode that loses payments. No armed
 *    marker is written (there is no height to arm with) and any EXISTING
 *    armed marker is deliberately left alone — unknown must not be
 *    mistaken for "resolved".
 * 2. **A backfill is in flight → WATCH.** Re-running provisioning here is
 *    the whole defect: it re-lowers `synced_height` to the floor, so the
 *    scan never climbs and the backfill never completes. Skipping lets the
 *    durable watermark advance across launches until it passes the target.
 *    - The contact set changing mid-backfill abandons the watch and
 *      re-runs: the new contact's receival addresses were not in the filter
 *      match set for the range already re-scanned.
 *    - A watermark BELOW the recorded floor (a further rewind, or SDK
 *      storage loss) only ever widens the covered range downward.
 * 3. **An armed marker exists → the last provisioning pass is not yet
 *    accounted for** (see [BackfillArmed] for why the pass outcome alone
 *    cannot be trusted to account for it):
 *    - Contact set changed → the marker belongs to a different contact set;
 *      abandon it and re-run (re-arming under the new fingerprint).
 *    - Height BELOW the armed target → direct evidence the armed pass's
 *      rewind fired and has now persisted; latch the in-progress watch
 *      (floor = the height observed NOW, target = the armed pre-pass
 *      height) and skip so the scan can climb.
 *    - Height at/above the armed target → ambiguous: the rewind's persist
 *      may never have landed (process died first — no durable rewind
 *      happened, so nothing was re-scanned), OR nothing needed rewinding,
 *      OR the rewind fired and the scan already climbed back within the
 *      arming session. The marker alone cannot distinguish these, and the
 *      invariant forbids recording coverage without an OBSERVED climb from
 *      an observed floor — so fail toward re-running: on this process's
 *      FIRST pass, provision again (re-arming at the fresh height; a fresh
 *      process re-fires the SDK rewind, so this time it will be observed).
 *      After this process has already provisioned, re-running proves
 *      nothing (the SDK's in-memory `rescan_triggered` guard suppresses
 *      the rewind) — keep the marker and skip, waiting for the async
 *      persist (~9–60 s) to surface the drop on a later trigger.
 * 4. **Coverage recorded → SKIP**, unless the contact set changed (a new
 *    contact always needs its own backfill) or the watermark has fallen
 *    below the covered floor (the SDK's wallet state was reset, so the
 *    evidence the coverage rests on is gone).
 * 5. Otherwise **RUN** — no marker has ever been written.
 *
 * Every shouldRun=true decision over a READABLE observation also writes an
 * armed marker (the pre-pass height + fingerprint), so the pass it permits
 * can be accounted for later regardless of when the SDK's watermark
 * persist lands.
 */
/**
 * Why concluding "there was nothing to backfill" is NOT admissible over
 * [observation] at [heightToRecord] — or null when it is.
 *
 * "No rewind was observed" is only evidence of "no rewind was needed" when
 * nothing was owed one. Two field-observed states make it worthless, and
 * both were visible in the same tester log:
 *
 * 1. **A rewind was OWED.** The SDK holds a RECEIVED contact request whose
 *    `coreHeightCreatedAt` is below the height about to be recorded. Its
 *    receival addresses enter the filter match set forward-only, so the
 *    range between them was scanned WITHOUT those addresses. Recording
 *    coverage from [heightToRecord] would claim that range as covered and
 *    permanently suppress the backfill that would have found those
 *    payments. Not seeing the rewind means it was suppressed (the SDK's
 *    `rescan_triggered` guard is in-memory and per-process) or has not
 *    persisted yet (~9–60 s) — never that it was unnecessary.
 * 2. **The SDK has not ingested the app's contacts yet.** The SDK's contact
 *    store is filled by its own sync, minutes AFTER the app's Room table is
 *    filled independently over dashj-platform. A gate consultation landing
 *    in that window sees an empty SDK store, provisions nothing, observes no
 *    rewind — and would conclude a wallet with contacts has nothing to
 *    backfill. The app's own count is the tell: SDK silence next to a
 *    non-empty app table is a not-yet, not a nothing.
 *
 * Withholding costs a re-scan on the next trigger. Concluding wrongly loses
 * the payments the backfill exists to find, so this only ever refuses.
 */
internal fun noRewindConclusionBlockedBecause(
    observation: BackfillObservation,
    heightToRecord: Long
): String? {
    val receivedFloor = observation.sdkReceivedContactFloor
    if (receivedFloor != null && receivedFloor < heightToRecord) {
        return "the SDK holds a RECEIVED contact request created at core height " +
            "$receivedFloor, below the $heightToRecord about to be recorded as covered — a " +
            "rewind was OWED, so a pass that produced none proves it was suppressed or has " +
            "not persisted, not that it was unnecessary"
    }
    val appCount = observation.appContactCount
    if (observation.sdkContactCount == 0 && appCount != null && appCount > 0) {
        return "the SDK holds no contact requests while the app's own table holds $appCount; " +
            "the SDK has not ingested them yet, so provisioning had nothing to act on and its " +
            "silence is not evidence that nothing needs backfilling"
    }
    return null
}

/**
 * Why a pass that already ran IN THIS PROCESS must be run AGAIN when the
 * drain has registered receival accounts since it — the reason a field
 * wallet needed a manual restart before its friendship funds appeared.
 *
 * The SDK's sweep (`dashPaySyncNow` → `reconcile_dashpay_rescan`) lowers
 * `synced_height` for the receival accounts that exist AT THAT MOMENT. The
 * accounts themselves are registered by the signer-backed DRAIN, which runs
 * AFTER the sweep in the same pass and continues asynchronously beyond it.
 * So a first pass on a wallet whose accounts are not yet built reconciles
 * against ZERO receival accounts, rewinds nothing, and every later
 * consultation in that process then hit the "this process already
 * provisioned; re-running proves nothing" branch and skipped — leaving the
 * balance short of every contact payment until the user restarted the app
 * (field 11.10.86: 29 accounts registered 17:20:11–17:20:31, sweep at
 * 17:20:08, no rewind until the manual restart at 17:24:01).
 *
 * "Re-running proves nothing" is only true for contacts the SDK has ALREADY
 * marked: `rescan_triggered`
 * (`rs-platform-wallet/.../network/payments.rs`) is a `BTreeSet<Identifier>`
 * of contacts, populated only for contacts that had a receival account when
 * a sweep ran. A contact whose account was registered afterwards is NOT in
 * it, so the next sweep does fire the rewind for it — which is exactly what
 * the manual restart demonstrated.
 */
internal fun decideDashPayBackfill(
    observation: BackfillObservation,
    coverage: BackfillCoverage?,
    inProgress: BackfillInProgress?,
    armed: BackfillArmed?,
    hasProvisionedInProcess: Boolean,
    /**
     * Whether the SDK has REGISTERED DashPay account builds since the last
     * sweep this app observed (see the KDoc above). Over-reporting costs one
     * extra sweep — the SDK's per-contact guard makes a redundant one a
     * no-op — and the flag is cleared by the pass it causes, so it cannot
     * loop.
     */
    accountsRegisteredSincePass: Boolean = false
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
                clearInProgress = true,
                armedToWrite = BackfillArmed(syncedHeight, fingerprint)
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
                clearInProgress = true,
                // A marker that somehow survived alongside the watch (a crash
                // between the latch's two writes) is superseded by the
                // completed coverage.
                clearArmed = true
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

    if (armed != null) {
        if (armed.contactFingerprint != fingerprint) {
            return BackfillDecision(
                shouldRun = true,
                reason = "contact set changed since a provisioning pass was armed " +
                    "(was ${armed.contactFingerprint}, now $fingerprint); abandoning the " +
                    "armed marker and provisioning for the new contact set",
                armedToWrite = BackfillArmed(syncedHeight, fingerprint)
            )
        }
        if (syncedHeight < armed.targetHeight) {
            // The armed pass's rewind has PERSISTED: the durable watermark
            // sits below the pre-pass height recorded at arming. This is the
            // observation recordPassOutcome's one-shot after-read races and
            // usually loses (the SDK persists the rewound height ~9–60 s
            // after the in-memory rewind) — here it cannot be raced, because
            // the height stays below the target until the scan climbs back.
            return BackfillDecision(
                shouldRun = false,
                reason = "armed pass's rewind detected: synced height $syncedHeight is BELOW " +
                    "the pre-pass height ${armed.targetHeight} recorded at arming; latching " +
                    "the watch (floor=$syncedHeight, target=${armed.targetHeight}) and " +
                    "NOT re-triggering the rewind so the scan can climb",
                inProgressToWrite = BackfillInProgress(
                    floor = syncedHeight,
                    targetHeight = armed.targetHeight,
                    contactFingerprint = fingerprint
                ),
                clearArmed = true
            )
        }
        if (accountsRegisteredSincePass) {
            // Receival accounts appeared AFTER the armed pass's sweep
            // reconciled, so that sweep could not have rewound for them and
            // the SDK's per-contact guard has not marked them. Provision
            // again NOW — in this same process — instead of waiting for a
            // relaunch. Re-arms at the current height so the new pass's
            // rewind is detectable exactly like any other.
            return BackfillDecision(
                shouldRun = true,
                reason = "DashPay account builds were REGISTERED since the last sweep (armed " +
                    "target ${armed.targetHeight}, synced height $syncedHeight); the sweep " +
                    "reconciled before those accounts existed, so it could not have rewound " +
                    "for them — provisioning again in this process and re-arming at $syncedHeight",
                armedToWrite = BackfillArmed(syncedHeight, fingerprint)
            )
        }
        if (!hasProvisionedInProcess) {
            // Height at/above the armed target and this process has not
            // provisioned yet: the marker is from an earlier process, and
            // "the rewind's persist never landed", "nothing needed
            // rewinding" and "it already completed inside the arming
            // session" are indistinguishable from here. Coverage may NOT be
            // recorded without an observed climb from an observed floor, so
            // fail toward re-running: this process's first pass re-fires the
            // SDK rewind (its in-memory guard is fresh), which the re-armed
            // marker will account for.
            return BackfillDecision(
                shouldRun = true,
                reason = "armed pass unaccounted for (synced height $syncedHeight >= armed " +
                    "target ${armed.targetHeight}) and this process has not provisioned yet; " +
                    "cannot prove the backfill completed, so provisioning again and " +
                    "re-arming at $syncedHeight",
                armedToWrite = BackfillArmed(syncedHeight, fingerprint)
            )
        }
        // This process already provisioned: its rewind (if any) is
        // suppressed by the SDK's in-memory guard on a re-run, so re-running
        // proves nothing — and the armed pass's persisted drop may simply
        // not have landed yet. Keep the marker and wait.
        return BackfillDecision(
            shouldRun = false,
            reason = "armed pass (target ${armed.targetHeight}) not yet accounted for, but " +
                "this process already provisioned; waiting for the SDK's async watermark " +
                "persist rather than re-running a pass that can prove nothing"
        )
    }

    if (coverage != null) {
        if (accountsRegisteredSincePass) {
            // A recorded coverage describes a scan run with the receival
            // accounts that existed then. An account registered since watches
            // addresses that range was never matched against, so its history
            // needs its own rewind — the fingerprint (an app-side contact-set
            // comparison) cannot see this, because the contact requests were
            // already there; only the SDK-side ACCOUNT is new.
            return BackfillDecision(
                shouldRun = true,
                reason = "DashPay account builds were REGISTERED since the last sweep, after " +
                    "coverage floor ${coverage.floor}/completedThrough " +
                    "${coverage.completedThroughHeight} was recorded; those accounts' " +
                    "addresses were not watched during the covered scan — discarding the " +
                    "coverage and backfilling again",
                clearCoverage = true,
                armedToWrite = BackfillArmed(syncedHeight, fingerprint)
            )
        }
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
                clearCoverage = true,
                armedToWrite = BackfillArmed(syncedHeight, fingerprint)
            )
        }
        if (syncedHeight < coverage.floor) {
            return BackfillDecision(
                shouldRun = true,
                reason = "synced height $syncedHeight fell below the covered floor " +
                    "${coverage.floor}; the SDK wallet state behind the recorded coverage " +
                    "is gone — discarding it and backfilling again",
                clearCoverage = true,
                armedToWrite = BackfillArmed(syncedHeight, fingerprint)
            )
        }
        // An ASSUMED floor rests on "nothing needed backfilling". The SDK's
        // contact store can arrive minutes later and refute that outright, and
        // when it does the record is claiming a range it never scanned with
        // the contact addresses watched. Re-validate it against the evidence
        // that exists NOW, which the fingerprint (a same-set comparison)
        // cannot see. An OBSERVED floor is never re-validated: the SDK chose
        // it, so a contact below it is the expected result of the SDK
        // subsetting by direction and establishment, and re-running on that
        // would rewind on every launch forever.
        if (!coverage.observedFloor) {
            val blocked = noRewindConclusionBlockedBecause(observation, coverage.floor)
            if (blocked != null) {
                return BackfillDecision(
                    shouldRun = true,
                    reason = "the recorded coverage ASSUMED floor ${coverage.floor} (it was " +
                        "never observed as a rewind) and later evidence refutes it: $blocked; " +
                        "discarding the coverage and backfilling again",
                    clearCoverage = true,
                    armedToWrite = BackfillArmed(syncedHeight, fingerprint)
                )
            }
        }
        return BackfillDecision(
            shouldRun = false,
            reason = "backfill already covered: floor ${coverage.floor} " +
                "(${if (coverage.observedFloor) "observed" else "assumed"}), completed through " +
                "${coverage.completedThroughHeight}, synced height now $syncedHeight, " +
                "contact set unchanged ($fingerprint)"
        )
    }

    return BackfillDecision(
        shouldRun = true,
        reason = "no backfill coverage recorded for this wallet yet",
        armedToWrite = BackfillArmed(syncedHeight, fingerprint)
    )
}

/**
 * Interpret a provisioning pass that actually ran, by comparing the durable
 * synced height either side of it.
 *
 * NOTE this after-read RACES the SDK's async watermark persist: the rewind
 * fires synchronously in SDK memory during the sweep, but the durable
 * height only drops via a filter-loop tick + changeset batch ~9–60 s
 * later, so "no regression" here is routinely a false negative. The armed
 * marker written by [decideDashPayBackfill] is the race-free channel; a
 * direct observation here is merely a faster path to the same latch.
 *
 * - A **regression** (`after < before`) is the rewind itself, directly
 *   observed. That is the only way the app learns the real floor — it
 *   cannot compute one, because DashPay contact requests carry no core
 *   height on the app side. `before` becomes the completion target: once
 *   the scan returns to it, every block that was previously scanned WITHOUT
 *   the contact addresses has been re-scanned WITH them.
 *   Latched REGARDLESS of `pendingBefore`/`drainScheduled`: the old
 *   settledness precondition is unsatisfiable in the field, because the
 *   SDK re-enqueues every contact's account build on every launch (FFI
 *   persistence gap), pinning `pendingBefore` at the full contact count
 *   forever. A floor that drops further after latching is already handled
 *   by the watch's floor-widening rule, so waiting for settledness bought
 *   nothing and cost everything.
 * - **No regression** is only trustworthy on [firstPassInProcess]: the
 *   SDK's `rescan_triggered` guard is in-memory and per-process, so from
 *   the second pass onward a quiet pass proves nothing — the guard, not
 *   the absence of work, is what suppressed the rewind. It also requires a
 *   clean, settled sweep. The armed marker is cleared along with the
 *   coverage write (a genuinely quiet wallet must reach the covered
 *   steady state, not re-provision every launch); if this was the
 *   persist-race false negative, the late-landing drop pushes the height
 *   BELOW the just-written coverage floor, [decideDashPayBackfill]'s
 *   floor rule discards the coverage, and the next process's first pass
 *   re-fires and re-arms — slow, never lossy.
 * - Anything else writes nothing — and deliberately leaves any armed
 *   marker in place — so a later consultation re-runs or latches.
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
        // NO settledness precondition (`pendingBefore == 0 && !drainScheduled`)
        // here anymore: `pendingBefore` is field-proven meaningless — the SDK
        // re-enqueues EVERY contact's account build on EVERY launch (FFI
        // persistence gap), so on the wallet this gate exists for it is
        // structurally pinned at the full contact count and the old gate made
        // latching unreachable. If further registrations do lower the floor
        // again, the watch's floor-widening rule absorbs it.
        return BackfillPassOutcome(
            reason = "rewind observed: synced height dropped $syncedHeightBefore -> " +
                "$syncedHeightAfter (pendingBuilds=${report.pendingBefore}, " +
                "drainScheduled=${report.drainScheduled} — not load-bearing); watching for " +
                "the scan to climb back to $syncedHeightBefore before recording completion",
            inProgressToWrite = BackfillInProgress(
                floor = syncedHeightAfter,
                targetHeight = syncedHeightBefore,
                contactFingerprint = fingerprint
            ),
            clearArmed = true
        )
    }

    val settledAndClean = report.syncErrors == 0 &&
        report.pendingBefore == 0 &&
        !report.drainScheduled
    if (firstPassInProcess && settledAndClean) {
        val blocked = noRewindConclusionBlockedBecause(before, syncedHeightAfter)
        if (blocked != null) {
            // Leaves the armed marker in place, so the next trigger re-runs.
            return BackfillPassOutcome(
                reason = "no rewind on this process's FIRST provisioning pass and the sweep " +
                    "was clean and settled, but recording coverage would be unsound: " +
                    "$blocked; recording nothing so the next trigger provisions again"
            )
        }
        return BackfillPassOutcome(
            reason = "no rewind on this process's FIRST provisioning pass and the sweep was " +
                "clean and settled; there is nothing to backfill — recording coverage at " +
                "height $syncedHeightAfter",
            coverageToWrite = BackfillCoverage(
                floor = syncedHeightAfter,
                completedThroughHeight = syncedHeightAfter,
                contactFingerprint = fingerprint,
                // ASSUMED, not observed: no rewind was ever seen.
                observedFloor = false
            ),
            clearArmed = true
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
 * The rewind is DETECTED via an ARMED marker rather than a before/after
 * read around the pass: [evaluate] persists the pre-pass durable height
 * before letting provisioning run, and any LATER consultation that finds
 * the durable height BELOW that marker has race-free proof the rewind
 * fired (see [BackfillArmed] for the two field-proven reasons the direct
 * observation fails: the SDK's ~9–60 s async watermark persist, and a
 * settledness precondition the re-enqueued contact builds made
 * unsatisfiable).
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

    /**
     * Whether an armed rewind has already been accounted for — a watch is
     * latched or coverage is recorded — so there is nothing left for a
     * post-arm poll to observe. Never throws; a read failure answers false,
     * which only costs another poll.
     *
     * Exists because the ONLY race-free evidence that the armed rewind
     * happened is the durable synced height sitting BELOW the armed target,
     * and that is true for a bounded window (the scan climbs back out of it).
     * Missing the window strands the marker and the next launch re-runs the
     * whole rewind — the livelock seen in the field. See the poll in
     * `SdkWalletBinder.watchArmedBackfillRewind`.
     */
    suspend fun isRewindAccountedFor(): Boolean

    /**
     * Record that the SDK REGISTERED [built] DashPay account builds — the
     * signer-backed drain turning queued entries into real receival/external
     * accounts. Any positive count means the last sweep reconciled the rewind
     * before those accounts existed, so another sweep is owed; see
     * [decideDashPayBackfill]. Zero is ignored. Never throws.
     */
    fun noteAccountBuildsRegistered(built: Int)

    /**
     * The persisted bookkeeping, for consumers that must not treat a
     * mid-backfill balance as whole (see [DashPayBackfillStatus]). Never
     * throws; a read failure answers "armed", which only holds a stale
     * launch seed rather than persisting a short one.
     */
    suspend fun readBackfillStatus(): DashPayBackfillStatus

    /**
     * Conclude that the armed pass needed NO rewind, and record coverage.
     *
     * Only safe to call after a sustained observation window in which nothing
     * was accounted for. "No rewind observed" is worthless at pass-outcome
     * time — the durable height drops 9–60 s after the in-memory rewind (102 s
     * on the wallet this was diagnosed against), so an immediate after-read
     * cannot tell "nothing needed rewinding" from "it has not landed yet", and
     * concluding there would record coverage over a range about to be rewound.
     *
     * The watch is what makes this decision sound: it exits the moment
     * anything IS accounted for, so still being unaccounted-for after several
     * minutes of polling is itself the evidence that no rewind is coming.
     * Re-checks the height and fingerprint itself and refuses if a rewind did
     * land. Returns whether coverage was recorded. Never throws.
     */
    suspend fun concludeNoRewindObserved(
        walletIdHex: String,
        ownerIdentityId: ByteArray,
        ownerUserId: String
    ): Boolean

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

            // Nothing is ever armed, so there is never anything to watch for.
            override suspend fun isRewindAccountedFor() = true

            override fun noteAccountBuildsRegistered(built: Int) = Unit

            // Nothing is ever recorded, so nothing is ever owed.
            override suspend fun readBackfillStatus() = DashPayBackfillStatus.SETTLED

            override suspend fun concludeNoRewindObserved(
                walletIdHex: String,
                ownerIdentityId: ByteArray,
                ownerUserId: String
            ) = false
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
     * Monotonic milliseconds for [BACKFILL_ARMED_HOLD_MS]. Deliberately NOT
     * wall-clock (a clock change must not release or extend the hold) and not
     * `SystemClock` (which is unavailable on the host JVM these tests run on).
     * Overridable so the deadline is testable without waiting minutes.
     */
    internal var nowElapsedMs: () -> Long = { System.nanoTime() / 1_000_000 }

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

    /**
     * Whether the drain has registered account builds since the last sweep
     * this gate accounted for — see [decideDashPayBackfill]'s KDoc. SET by
     * [noteAccountBuildsRegistered] (and by [recordPassOutcome] from the
     * pass's own drain), CLEARED at the top of [recordPassOutcome] because
     * the sweep that pass just ran is what consumes it. Deliberately NOT
     * cleared by [evaluate]: the watch loop calls evaluate for its side
     * effects and discards the decision, and a read-and-clear there would
     * swallow the signal before any pass acted on it.
     */
    private val accountsRegisteredSincePass = AtomicBoolean(false)

    override fun noteAccountBuildsRegistered(built: Int) {
        if (built <= 0) return
        if (accountsRegisteredSincePass.compareAndSet(false, true)) {
            log.info(
                "DashPay coreHeight backfill: {} account build(s) registered since the last " +
                    "sweep; the next gate consultation will provision again so the SDK can " +
                    "rewind for them (their receival addresses were not watched when the " +
                    "current range was scanned)",
                built
            )
        }
    }

    /**
     * Monotonic reading at which THIS PROCESS first saw an armed marker, null
     * when none has been seen — the clock behind [BACKFILL_ARMED_HOLD_MS].
     * Nullable rather than a 0 sentinel: 0 is a legitimate reading of a
     * monotonic clock, and conflating the two re-stamps the deadline on every
     * read, which is the freeze this exists to end.
     *
     * Process-scoped on purpose: the marker itself is durable and (see
     * [DashPayBackfillStatus]) permanent on a healthy wallet, so a persisted
     * timestamp would just make the freeze survive relaunches too.
     */
    @Volatile
    private var armedFirstSeenAtMs: Long? = null

    override suspend fun readBackfillStatus(): DashPayBackfillStatus = try {
        val armedMarker = readArmed() != null
        val firstSeen = if (!armedMarker) {
            armedFirstSeenAtMs = null
            null
        } else {
            armedFirstSeenAtMs ?: nowElapsedMs().also { armedFirstSeenAtMs = it }
        }
        // The armed marker is UNPROVEN, not wrong, and on a wallet whose
        // coverage is already correct it never clears (see the class KDoc).
        // Report it only while a rewind could still plausibly be on its way —
        // past that, the absence of one IS the answer, and continuing to
        // report it would freeze the last-known-balance seed for the wallet's
        // whole life. The rewind's own evidence (a latched watch) is not
        // time-limited here; only the unproven state is.
        val armedWithinDeadline = firstSeen != null &&
            nowElapsedMs() - firstSeen < BACKFILL_ARMED_HOLD_MS
        DashPayBackfillStatus(
            armed = armedWithinDeadline,
            replaying = readInProgress() != null,
            registrationOutstanding = accountsRegisteredSincePass.get()
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Unknown ⇒ treat as owed: holding a stale launch seed is recoverable,
        // persisting a figure short of every contact payment is not.
        log.warn("failed to read the DashPay backfill status; treating it as unsettled", e)
        DashPayBackfillStatus(armed = true, replaying = false)
    }

    override suspend fun evaluate(
        walletIdHex: String,
        ownerIdentityId: ByteArray,
        ownerUserId: String
    ): BackfillDecision {
        return try {
            val signals = sdkService.readDashPayBackfillSignals(walletIdHex, ownerIdentityId)
            val appContacts = readAppContactSet(ownerUserId)
            val observation = BackfillObservation(
                syncedHeight = signals.syncedHeight,
                contactFingerprint = appContacts?.fingerprint,
                sdkContactFloor = signals.contactCoreHeightFloor,
                sdkContactCount = signals.contactRequestCount,
                sdkReceivedContactFloor = signals.receivedContactCoreHeightFloor,
                appContactCount = appContacts?.count
            )
            lastObservation = observation

            val coverage = readCoverage()
            val inProgress = readInProgress()
            val armed = readArmed()
            val decision = decideDashPayBackfill(
                observation, coverage, inProgress, armed, hasProvisionedInProcess.get(),
                accountsRegisteredSincePass.get()
            )
            // Persist the bookkeeping — including the armed marker on a
            // shouldRun=true decision — BEFORE returning, so the pass this
            // decision permits is on record even if the process dies mid-pass.
            applyDecision(decision)

            // The per-launch line the next tester log has to be unambiguous
            // about: the floor, what was persisted, and what we did about it.
            log.info(
                "DashPay coreHeight backfill gate: floor(min coreHeightCreatedAt over {} SDK " +
                    "contact request(s))={} (received-only floor={}), syncedHeight={}, " +
                    "persistedCoverage={}, persistedInProgress={}, persistedArmed={}, " +
                    "appContactSet={} -> {} ({})",
                observation.sdkContactCount,
                observation.sdkContactFloor ?: "unknown",
                observation.sdkReceivedContactFloor ?: "unknown",
                observation.syncedHeight ?: "unknown",
                coverage?.let {
                    "floor=${it.floor}(${if (it.observedFloor) "observed" else "assumed"})" +
                        "/completedThrough=${it.completedThroughHeight}"
                } ?: "none",
                inProgress?.let { "floor=${it.floor}/target=${it.targetHeight}" } ?: "none",
                armed?.let { "target=${it.targetHeight}" } ?: "none",
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
            // The sweep this pass just ran is what the pending-registration
            // signal was asking for; consume it BEFORE re-reading the pass's
            // own drain below, so registrations that landed DURING this pass
            // (after its sweep reconciled) still ask for the next one.
            accountsRegisteredSincePass.set(false)
            noteAccountBuildsRegistered(report.built)
            val after = sdkService
                .readDashPayBackfillSignals(walletIdHex, ownerIdentityId)
                .syncedHeight
            val outcome = decideDashPayBackfillPassOutcome(before, after, report, firstPass)

            outcome.inProgressToWrite?.let { writeInProgress(it) }
            outcome.coverageToWrite?.let {
                writeCoverage(it)
                clearInProgress()
            }
            // AFTER the latch/coverage write: a crash in between leaves both
            // the marker and the watch behind, which the decision core
            // resolves toward re-running — never toward a silent skip.
            if (outcome.clearArmed) clearArmed()
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

    override suspend fun concludeNoRewindObserved(
        walletIdHex: String,
        ownerIdentityId: ByteArray,
        ownerUserId: String
    ): Boolean = try {
        val armed = readArmed()
        when {
            armed == null -> false
            // Something already accounted for the pass; leave it alone.
            readInProgress() != null || readCoverage() != null -> false
            else -> {
                val signals = sdkService.readDashPayBackfillSignals(walletIdHex, ownerIdentityId)
                val height = signals.syncedHeight
                val appContacts = readAppContactSet(ownerUserId)
                val fingerprint = appContacts?.fingerprint
                when {
                    height == null || fingerprint == null -> false
                    // A changed contact set needs its own backfill, so this
                    // pass proves nothing about the new one.
                    armed.contactFingerprint != fingerprint -> false
                    // The rewind DID land after all — that is the latch's
                    // evidence, not ours. Leave it to the next consultation.
                    height < armed.targetHeight -> false
                    else -> {
                        // A quiet observation window is only evidence when
                        // nothing was owed a rewind. Refusing here leaves the
                        // armed marker, so the next launch provisions again.
                        val blocked = noRewindConclusionBlockedBecause(
                            BackfillObservation(
                                syncedHeight = height,
                                contactFingerprint = fingerprint,
                                sdkContactFloor = signals.contactCoreHeightFloor,
                                sdkContactCount = signals.contactRequestCount,
                                sdkReceivedContactFloor = signals.receivedContactCoreHeightFloor,
                                appContactCount = appContacts.count
                            ),
                            height
                        )
                        if (blocked != null) {
                            log.info(
                                "DashPay coreHeight backfill: armed pass (target {}) produced " +
                                    "no rewind across the whole observation window, but NOT " +
                                    "concluding the backfill was unnecessary — {}; leaving the " +
                                    "armed marker so the next launch provisions again",
                                armed.targetHeight,
                                blocked
                            )
                            false
                        } else {
                            writeCoverage(
                                BackfillCoverage(
                                    floor = height,
                                    completedThroughHeight = height,
                                    contactFingerprint = fingerprint,
                                    // ASSUMED: no rewind was ever observed.
                                    observedFloor = false
                                )
                            )
                            clearArmed()
                            log.info(
                                "DashPay coreHeight backfill: armed pass (target {}) produced " +
                                    "no rewind across the whole observation window and the " +
                                    "contact set is unchanged — nothing needed backfilling; " +
                                    "recording coverage at height {} so later launches stop " +
                                    "re-provisioning",
                                armed.targetHeight,
                                height
                            )
                            true
                        }
                    }
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("failed to conclude the no-rewind outcome; the next launch will re-provision", e)
        false
    }

    override suspend fun isRewindAccountedFor(): Boolean = try {
        readInProgress() != null || readCoverage() != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Answering false only costs another poll; answering true could end
        // the watch while the evidence is still unread.
        false
    }

    /** The app's own contact-request set: its fingerprint and its size. */
    private data class AppContactSet(val fingerprint: String, val count: Int)

    /** Null when the app's contact table cannot be read — treated as unknown. */
    private suspend fun readAppContactSet(ownerUserId: String): AppContactSet? = try {
        val toUs = contactRequestDao.countAllRequestsToUser(ownerUserId)
        val fromUs = contactRequestDao.countAllRequestsFromUser(ownerUserId)
        AppContactSet(
            fingerprint = contactSetFingerprint(
                requestsToUs = toUs,
                requestsFromUs = fromUs,
                latestToUsMillis = contactRequestDao.getLastTimestampToUser(ownerUserId),
                latestFromUsMillis = contactRequestDao.getLastTimestampFromUser(ownerUserId)
            ),
            count = toUs + fromUs
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
        // Writes before clears: a crash in between leaves BOTH markers
        // behind, and the decision core resolves that toward re-running.
        decision.armedToWrite?.let { writeArmed(it) }
        if (decision.clearArmed && decision.armedToWrite == null) clearArmed()
    }

    private suspend fun readCoverage(): BackfillCoverage? {
        val floor = dashPayConfig.get(DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR) ?: return null
        val through =
            dashPayConfig.get(DashPayConfig.DASHPAY_BACKFILL_COMPLETED_THROUGH) ?: return null
        val fingerprint =
            dashPayConfig.get(DashPayConfig.DASHPAY_BACKFILL_CONTACT_FINGERPRINT) ?: return null
        // Absent on records written before the flag existed. Those were
        // written by the code path that could assume a floor, so treat them
        // as ASSUMED: a wallet already carrying a bad floor then re-validates
        // once instead of staying stuck on it forever.
        val observed = dashPayConfig.get(DashPayConfig.DASHPAY_BACKFILL_COVERAGE_OBSERVED) ?: false
        return BackfillCoverage(floor, through, fingerprint, observed)
    }

    private suspend fun writeCoverage(coverage: BackfillCoverage) {
        dashPayConfig.set(DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR, coverage.floor)
        dashPayConfig.set(
            DashPayConfig.DASHPAY_BACKFILL_COMPLETED_THROUGH, coverage.completedThroughHeight
        )
        dashPayConfig.set(
            DashPayConfig.DASHPAY_BACKFILL_CONTACT_FINGERPRINT, coverage.contactFingerprint
        )
        dashPayConfig.set(
            DashPayConfig.DASHPAY_BACKFILL_COVERAGE_OBSERVED, coverage.observedFloor
        )
    }

    private suspend fun clearCoverage() {
        dashPayConfig.remove(DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR)
        dashPayConfig.remove(DashPayConfig.DASHPAY_BACKFILL_COMPLETED_THROUGH)
        dashPayConfig.remove(DashPayConfig.DASHPAY_BACKFILL_CONTACT_FINGERPRINT)
        dashPayConfig.remove(DashPayConfig.DASHPAY_BACKFILL_COVERAGE_OBSERVED)
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

    private suspend fun readArmed(): BackfillArmed? {
        val target = dashPayConfig.get(DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET) ?: return null
        val fingerprint =
            dashPayConfig.get(DashPayConfig.DASHPAY_BACKFILL_ARMED_FINGERPRINT) ?: return null
        return BackfillArmed(target, fingerprint)
    }

    private suspend fun writeArmed(armed: BackfillArmed) {
        dashPayConfig.set(DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET, armed.targetHeight)
        dashPayConfig.set(
            DashPayConfig.DASHPAY_BACKFILL_ARMED_FINGERPRINT, armed.contactFingerprint
        )
    }

    private suspend fun clearArmed() {
        dashPayConfig.remove(DashPayConfig.DASHPAY_BACKFILL_ARMED_TARGET)
        dashPayConfig.remove(DashPayConfig.DASHPAY_BACKFILL_ARMED_FINGERPRINT)
    }

    companion object {
        /**
         * How long an UNPROVEN armed marker is reported as [DashPayBackfillStatus.armed].
         *
         * Sized to the same question the gate's own no-rewind conclusion asks
         * ([SdkWalletBinder.BACKFILL_NO_REWIND_CONCLUSION_POLLS] — five
         * one-minute polls): how long must a quiet observation window be
         * before "no rewind is coming" is the honest reading? The rewind's
         * durable drop lands 9-60 s after the in-memory rewind (102 s at
         * worst, measured), so five minutes clears every observed case with
         * wide margin — the field rewind this guards showed up 13 s after the
         * process's first sweep (17:23:58 armed -> 17:24:11 observed).
         *
         * Over-running costs a delayed refresh of the launch seed.
         * Under-running was the S21 regression: reported forever, so the seed
         * could never refresh and the sync indicator never cleared.
         */
        internal const val BACKFILL_ARMED_HOLD_MS = 5 * 60_000L

        private val log = LoggerFactory.getLogger(DashPayBackfillGateImpl::class.java)
    }
}
