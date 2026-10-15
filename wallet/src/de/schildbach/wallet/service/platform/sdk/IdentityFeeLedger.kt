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

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/** The Platform write a [IdentityFeeLedger] entry attributes credits to. */
enum class IdentityFeeStep {
    /** IdentityCreate from the asset lock: credited − post-create balance. */
    IDENTITY_CREATE,

    /** DPNS preorder + domain (one SDK call, so one combined figure). */
    DPNS,

    /** DashPay `contactRequest` document create. */
    CONTACT_REQUEST,

    /** DashPay `profile` document create/update. */
    PROFILE
}

/**
 * Best-effort, LOG-ONLY Platform fee ledger: credit-balance snapshots taken
 * around each identity write so QA can split what a username creation (and
 * the DashPay writes after it) cost per step. Nothing here ever feeds back
 * into a write's result, the creation state or retry behaviour — every read
 * failure or timeout becomes `null` and the entry logs `fee=unknown`.
 *
 * One greppable line per step:
 * ```
 * identity fee ledger: step=IDENTITY_CREATE identity=5zZxxQEw… credited=3000000000 (assetLock) balanceAfter=2693390540 fee=306609460 credits (≈0.0030660946 DASH)
 * identity fee ledger: step=DPNS identity=5zZxxQEw… label=alice status=REGISTERED balanceBefore=… balanceAfter=… fee=… credits (≈… DASH) note=preorder+domain combined
 * ```
 *
 * STALENESS: Platform is read through whichever DAPI node answers, and a node
 * can lag the block that applied the transition. A read right after a write
 * may therefore still show the pre-write balance — that step logs `fee=0`
 * (flagged `possiblyStale`) and its cost lands on the NEXT step's figure; the
 * running total stays right. No waits/polling are added to avoid it.
 */
internal object IdentityFeeLedger {
    private val log = LoggerFactory.getLogger(IdentityFeeLedger::class.java)

    /** Per-read wall-clock budget; a balance read is diagnostics, never worth more. */
    const val BALANCE_READ_TIMEOUT_MS = 5_000L

    /** Credits an asset lock of [lockedDuffs] credits the new identity with (before fees). */
    fun creditedCredits(lockedDuffs: Long): Long = Math.multiplyExact(lockedDuffs, CREDITS_PER_DUFF)

    /** Credits spent between two balance snapshots; null when either is unknown. */
    fun feeCredits(before: Long?, after: Long?): Long? =
        if (before != null && after != null) before - after else null

    /** [credits] as a plain DASH string (1 DASH = 1e11 credits), sign kept. */
    fun creditsToDashString(credits: Long): String =
        BigDecimal.valueOf(credits, 11).stripTrailingZeros().toPlainString()

    /**
     * The ledger line. [startName] names the [start] figure — `credited` for
     * [IdentityFeeStep.IDENTITY_CREATE], `balanceBefore` otherwise; [startSource]
     * qualifies it (e.g. `assetLock` / `requested`). Identity ids are truncated
     * to 8 chars like the surrounding logs.
     */
    fun format(
        step: IdentityFeeStep,
        identityIdBase58: String,
        start: Long?,
        after: Long?,
        startName: String = if (step == IdentityFeeStep.IDENTITY_CREATE) "credited" else "balanceBefore",
        startSource: String? = null,
        label: String? = null,
        status: String? = null,
        note: String? = null
    ): String = buildString {
        append("identity fee ledger: step=").append(step.name)
        append(" identity=").append(identityIdBase58.take(8)).append('…')
        label?.let { append(" label=").append(it) }
        status?.let { append(" status=").append(it) }
        append(' ').append(startName).append('=').append(start ?: "unknown")
        startSource?.let { append(" (").append(it).append(')') }
        append(" balanceAfter=").append(after ?: "unknown")
        val fee = feeCredits(start, after)
        if (fee == null) {
            append(" fee=unknown")
        } else {
            append(" fee=").append(fee).append(" credits (≈").append(creditsToDashString(fee)).append(" DASH)")
            if (fee <= 0) append(" possiblyStale")
        }
        note?.let { append(" note=").append(it) }
    }

    /** Emit [format]'s line. Never throws. */
    fun record(
        step: IdentityFeeStep,
        identityIdBase58: String,
        start: Long?,
        after: Long?,
        startSource: String? = null,
        label: String? = null,
        status: String? = null,
        note: String? = null
    ) {
        try {
            log.info(
                format(
                    step = step,
                    identityIdBase58 = identityIdBase58,
                    start = start,
                    after = after,
                    startSource = startSource,
                    label = label,
                    status = status,
                    note = note
                )
            )
        } catch (e: Exception) {
            log.debug("identity fee ledger: failed to format the {} entry", step, e)
        }
    }

    /**
     * One bounded, best-effort credit-balance read for the ledger. Any failure,
     * an absent identity (a `null` read — e.g. a node that has not yet seen a
     * fresh IdentityCreate) or a timeout past [timeoutMs] returns null and
     * logs at debug; the caller's own cancellation is rethrown. The read runs
     * through [boundedSdkPlatformQuery] so a blocking native query on a bad
     * DAPI node really is abandoned after [timeoutMs] rather than holding up
     * the user-visible flow.
     */
    suspend fun readBalanceOrNull(
        step: IdentityFeeStep,
        identityIdBase58: String,
        timeoutMs: Long = BALANCE_READ_TIMEOUT_MS,
        read: suspend () -> Long?
    ): Long? = try {
        val boxed = boundedSdkPlatformQuery("identity fee ledger $step balance", timeoutMs) { read() }
        if (boxed == null) {
            log.debug("identity fee ledger: {} balance read for {}… timed out", step, identityIdBase58.take(8))
        }
        boxed?.orElse(null)
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.debug("identity fee ledger: {} balance read for {}… failed", step, identityIdBase58.take(8), t)
        null
    }
}
