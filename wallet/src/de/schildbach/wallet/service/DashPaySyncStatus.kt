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

package de.schildbach.wallet.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The three DashPay-side terms of "is this wallet actually finished
 * syncing?", and whether they have all settled. Pure — host-testable.
 *
 * @property applicable whether DashPay applies to this wallet at all. FALSE
 *   until the app has seen an identity, which is what makes the whole signal
 *   fail-open: a wallet with no identity — and any wallet before platform
 *   sync has said otherwise — is trivially settled and can never be held in
 *   "syncing" by a mechanism that does not concern it.
 * @property contactSyncSettled a contact-request sync pass has RUN TO
 *   COMPLETION at least once this process and none is in flight. A FAILED
 *   pass counts as completed — it ends, and the ticker retries — so a broken
 *   Platform connection cannot pin the indicator on forever.
 * @property accountBuildsSettled the SDK's deferred DashPay account-build
 *   queue is drained, or provably stuck ([de.schildbach.wallet.service.platform
 *   .sdk.deferredBuildsSettled]). Until it settles, contacts' receiving
 *   addresses are not in the watched script set and their payments are
 *   unmatched.
 * @property backfillSettled no coreHeight backfill is owed or replaying
 *   ([de.schildbach.wallet.service.platform.sdk.DashPayBackfillStatus]).
 */
data class DashPaySyncTerms(
    val applicable: Boolean = false,
    val contactSyncSettled: Boolean = false,
    val accountBuildsSettled: Boolean = true,
    val backfillSettled: Boolean = true
) {
    val settled: Boolean
        get() = !applicable || (contactSyncSettled && accountBuildsSettled && backfillSettled)
}

/**
 * Whether the DashPay half of a sync has finished — the term the user-facing
 * "still syncing" state needs and the L1 signals cannot see.
 *
 * ## Why this exists
 *
 * The L1 pipeline reporting "caught up" is not the same thing as the wallet
 * being ready. On 11.10.86 the UI reported synced at 17:12:39
 * (`L1Shadow phase=SYNCED 100.0%`), and only THEN did contact sync run
 * (17:13:57 → 17:20:09, "updating contacts and profiles took 6.185 min"),
 * DashPay receiving accounts register (17:20:11–17:20:31), and the correct
 * balance appear (17:25:00). The user was told "done" roughly twelve minutes
 * before the wallet was complete — the residue of the original complaint that
 * the earlier fix only closed for the L1 block/tx pipeline.
 *
 * ## Shape
 *
 * A LEAF singleton with no dependencies: the producers (platform sync, the
 * cutover UI pipeline) push into it and the consumer
 * ([L1SyncStatusService]) reads it, which is what keeps it out of the
 * dependency cycle those components already form with each other.
 *
 * ## Fail-open by construction
 *
 * [DashPaySyncTerms.applicable] starts FALSE and only becomes true when
 * platform sync reports a live identity, so no wallet is ever held in
 * "syncing" by a subsystem that does not apply to it — the hard requirement
 * on this signal. Every individual term is also bounded (see the property
 * docs): none of them can stay false forever on a wallet where DashPay does
 * apply.
 */
@Singleton
class DashPaySyncStatus @Inject constructor() {

    private val _terms = MutableStateFlow(DashPaySyncTerms())

    /** The individual terms — exposed for diagnostics and the detail readout. */
    val terms: StateFlow<DashPaySyncTerms> = _terms.asStateFlow()

    /**
     * Whether DashPay applies: the app has (or is restoring) an identity.
     * Called by platform sync, which is the only component that knows.
     */
    fun setApplicable(applicable: Boolean) = update { it.copy(applicable = applicable) }

    /** A contact-request sync pass has started — the wallet is not settled. */
    fun contactSyncStarted() = update { it.copy(contactSyncSettled = false) }

    /**
     * A contact-request sync pass has ENDED — successfully or not. Failure
     * still counts: the pass is over, the ticker retries, and treating only
     * success as settled would pin the indicator on a wallet whose Platform
     * connection is down.
     */
    fun contactSyncFinished() = update { it.copy(contactSyncSettled = true) }

    fun setAccountBuildsSettled(settled: Boolean) = update { it.copy(accountBuildsSettled = settled) }

    fun setBackfillSettled(settled: Boolean) = update { it.copy(backfillSettled = settled) }

    private inline fun update(transform: (DashPaySyncTerms) -> DashPaySyncTerms) {
        val before = _terms.value
        _terms.update(transform)
        val after = _terms.value
        if (before.settled != after.settled) {
            log.info(
                "DashPay sync {}: applicable={} contactSync={} accountBuilds={} backfill={}",
                if (after.settled) "SETTLED" else "still in progress",
                after.applicable, after.contactSyncSettled,
                after.accountBuildsSettled, after.backfillSettled
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DashPaySyncStatus::class.java)
    }
}
