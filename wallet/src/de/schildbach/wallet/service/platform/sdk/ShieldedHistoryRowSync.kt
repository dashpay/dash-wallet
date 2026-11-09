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

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet.database.dao.TxDisplayCacheDao
import de.schildbach.wallet.service.DisplayCacheRefreshBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the Home history's shielded-activity rows (`tx_display_cache` rows with
 * `source = SOURCE_SHIELDED`, see [planShieldedHistoryRow]) in step with the SDK's
 * `shielded_activities` table for the ACTIVE wallet
 * ([ShieldedBalanceService.observeShieldedActivityOrNotReady] follows the ready
 * wallet id). Each snapshot is planned and applied with
 * [TxDisplayCacheDao.replaceShieldedRows] — L1 rows are never touched — and the
 * pager is told through [DisplayCacheRefreshBus.signalChanged].
 *
 * Fail closed:
 * - "not ready" (`null`, e.g. before bring-up or after a wipe reset) changes
 *   nothing: unknown is not empty, so existing rows stay. Only a READY wallet's
 *   real (possibly empty) list replaces them.
 * - a failed read or write logs (no values) and keeps the rows; the feed is
 *   re-subscribed after [retryDelayMs], which re-emits the current snapshot and
 *   so retries the write.
 *
 * Lifecycle: [start] is idempotent and once-per-process (called beside
 * [CutoverUiDataService.start]). The wallet wipe calls [stopForWalletWipe]
 * BEFORE the display cache is cleared — it cancels AND joins the collector, so
 * no write planned from the wiped wallet can land after the clear — and
 * [resumeAfterWalletWipe] once it is done.
 */
@Singleton
class ShieldedHistoryRowSync internal constructor(
    private val shieldedBalanceService: ShieldedBalanceService,
    private val txDisplayCacheDao: TxDisplayCacheDao,
    private val displayCacheRefreshBus: DisplayCacheRefreshBus,
    private val resolveString: (Int) -> String,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val retryDelayMs: Long
) {
    @Inject
    constructor(
        shieldedBalanceService: ShieldedBalanceService,
        txDisplayCacheDao: TxDisplayCacheDao,
        displayCacheRefreshBus: DisplayCacheRefreshBus,
        @ApplicationContext context: Context
    ) : this(
        shieldedBalanceService,
        txDisplayCacheDao,
        displayCacheRefreshBus,
        { res -> context.getString(res) },
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        Dispatchers.IO,
        DEFAULT_RETRY_DELAY_MS
    )

    companion object {
        private val log = LoggerFactory.getLogger(ShieldedHistoryRowSync::class.java)
        private const val DEFAULT_RETRY_DELAY_MS = 30_000L
    }

    private val lock = Any()

    /** The collector; written under [lock]. */
    private var job: Job? = null

    /** [start] was called (so [resumeAfterWalletWipe] restarts). Written under [lock]. */
    private var startRequested = false

    /** Set by [stopForWalletWipe] until [resumeAfterWalletWipe]. Written under [lock]. */
    private var wipeFenced = false

    /** The wallet of the last non-null snapshot the collector saw (never logged). */
    @Volatile
    private var lastSeenWalletIdHex: String? = null

    /**
     * The wiped wallet, held off after [resumeAfterWalletWipe]: the shielded
     * service's wipe reset does not clear its ready wallet id, so the resumed
     * feed can still serve the wiped wallet's activity. Its snapshots are
     * ignored until the feed reports "not ready" (the runtime was torn down —
     * a same-seed restore may then legitimately bring the same id back) or a
     * different wallet. Null = nothing held.
     */
    @Volatile
    private var heldWalletIdHex: String? = null

    /** Idempotent: launches the collector unless it runs or a wipe holds it stopped. */
    fun start() {
        synchronized(lock) {
            startRequested = true
            if (wipeFenced || job?.isActive == true) return
            job = scope.launch(ioDispatcher) { collectLoop() }
        }
    }

    /**
     * Stop the collector for a wallet wipe and WAIT until it is gone, so no
     * snapshot of the wiped wallet is written after the cache clear. Stays
     * stopped until [resumeAfterWalletWipe].
     */
    suspend fun stopForWalletWipe() {
        val running = synchronized(lock) {
            wipeFenced = true
            job.also { job = null }
        }
        running?.cancelAndJoin()
        // After the join: the collector can no longer move lastSeenWalletIdHex.
        heldWalletIdHex = lastSeenWalletIdHex
        log.info("shielded history rows: sync stopped for the wallet wipe")
    }

    /**
     * Lift [stopForWalletWipe]'s fence and restart (when [start] ran before).
     * Non-suspending so the wipe can call it from a `finally`. The restarted
     * collector writes nothing for the wiped wallet (see [heldWalletIdHex]).
     */
    fun resumeAfterWalletWipe() {
        val restart = synchronized(lock) {
            wipeFenced = false
            startRequested
        }
        if (restart) start()
    }

    private suspend fun collectLoop() {
        while (true) {
            try {
                shieldedBalanceService.observeShieldedActivityOrNotReady().collect { snapshot ->
                    applySnapshot(snapshot)
                }
                // The feed is not expected to complete; resubscribe after a pause if it does.
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // A failed feed OR a failed write: rows stay as they are, and the
                // resubscription after the pause re-emits the current snapshot, so a
                // write that failed while the feed is quiet is retried. No values:
                // the exception class only.
                log.warn("shielded history rows: sync pass failed ({}); keeping rows, retrying", t.javaClass.simpleName)
            }
            delay(retryDelayMs)
        }
    }

    /**
     * Apply one activity snapshot: `null` (not ready) is a no-op; a list
     * (possibly empty) replaces the shielded rows, each entry planned with its
     * counterparty ownership resolved against the snapshot's own wallet.
     * Returns true when the rows were written. A failed write THROWS (after
     * leaving the existing rows untouched — the replace is one transaction) so
     * [collectLoop] retries it.
     */
    internal suspend fun applySnapshot(snapshot: ShieldedActivitySnapshot?): Boolean {
        if (snapshot == null) {
            heldWalletIdHex = null
            return false
        }
        val held = heldWalletIdHex
        if (held != null) {
            if (held.equals(snapshot.walletIdHex, ignoreCase = true)) return false
            heldWalletIdHex = null
        }
        lastSeenWalletIdHex = snapshot.walletIdHex
        val ownership = HashMap<String, CounterpartyOwnership>()
        for (entry in snapshot.entries) {
            ownership[shieldedHistoryRowId(entry.accountIndex, entry.id)] =
                shieldedBalanceService.counterpartyOwnership(snapshot.walletIdHex, entry)
        }
        val plan = planShieldedHistoryRows(
            snapshot.entries,
            { ownership[shieldedHistoryRowId(it.accountIndex, it.id)] ?: CounterpartyOwnership.UNKNOWN },
            resolveString
        )
        if (plan.skipped.isNotEmpty()) {
            // Counts only — never ids, amounts or memos.
            log.info("shielded history rows: {} planned, skipped {}", plan.rows.size, plan.skipped)
        }
        currentCoroutineContext().ensureActive()
        txDisplayCacheDao.replaceShieldedRows(plan.rows, plan.rows.mapTo(HashSet()) { it.rowId })
        displayCacheRefreshBus.signalChanged()
        return true
    }
}
