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

package de.schildbach.wallet.service.platform

import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded-retry decision state for [PlatformSyncService.updateContactRequests].
 *
 * Why this exists (observed live, S21 build 11.10.45, 2026-08-02): a full
 * contact update (`updateContactRequests(true)`) failed with the legacy CBOR
 * identity-cache error and was simply never re-run — the 15s ticker is scoped
 * to the blockchain service (which tears down within minutes post-cutover and
 * does not reliably re-arm it), so discovery of an iPhone contact-request
 * ACCEPTANCE waited ~16 minutes for an unrelated home-screen-resume trigger.
 * A failed run must schedule its own bounded retry instead of going silent.
 *
 * This class is only the pure, unit-testable decision seam — "should this
 * failure schedule a retry, and is a pending retry still worth running?" —
 * with the scheduling itself (scope, delay) kept in the service:
 *
 *  - each FAILED run increments the consecutive-failure streak; a retry is
 *    warranted while the streak is within [maxConsecutiveRetries], so a
 *    persistently broken environment (platform down, no network) retries a few
 *    times and then goes quiet until the next external trigger
 *    (ticker/resume/contacts screen) — which also resets nothing by itself:
 *    only a SUCCESSFUL run resets the budget;
 *  - a SUCCESSFUL run resets the streak, which both restores the retry budget
 *    for future failures and marks any still-pending retry as no longer
 *    warranted (a later regular run already did the work).
 */
internal class ContactUpdateRetryPolicy(
    private val maxConsecutiveRetries: Int = DEFAULT_MAX_CONSECUTIVE_RETRIES
) {
    companion object {
        const val DEFAULT_MAX_CONSECUTIVE_RETRIES = 3
    }

    private val consecutiveFailures = AtomicInteger(0)

    /** Consecutive failed runs since the last successful one. */
    val failureCount: Int
        get() = consecutiveFailures.get()

    /**
     * True while a scheduled retry is still worth executing — i.e. no run has
     * succeeded since the failure that scheduled it. Checked by the delayed
     * retry right before it re-invokes the update, so a success that lands
     * while the retry is pending turns the retry into a no-op instead of a
     * redundant network pass.
     */
    val retryStillWarranted: Boolean
        get() = consecutiveFailures.get() > 0

    /** Record a successful run: reset the streak and the retry budget. */
    fun onSuccess() {
        consecutiveFailures.set(0)
    }

    /**
     * Record a failed run. Returns true when a retry should be scheduled;
     * false once the consecutive-failure budget ([maxConsecutiveRetries]) is
     * exhausted, after which only an external trigger runs the update again
     * (and only its success restores the budget).
     */
    fun onFailureShouldRetry(): Boolean {
        return consecutiveFailures.incrementAndGet() <= maxConsecutiveRetries
    }
}
