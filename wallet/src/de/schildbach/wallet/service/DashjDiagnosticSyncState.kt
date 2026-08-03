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

import de.schildbach.wallet.service.platform.sdk.ParityReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DIAGNOSTIC-ONLY holder for the dashj L1 engine's own sync progress and the
 * SDK-vs-dashj parity verdict, isolated from the shared `blockchain_state`
 * Room row.
 *
 * It exists so the Tools "dashj sync (diagnostic)" row can show dashj's real
 * sync percentage — and, once dashj is caught up, whether the dashj wallet
 * agrees with the Kotlin SDK's L1 view — AFTER the Phase 5d cutover, WITHOUT
 * the (un-held) dashj peergroup overwriting the shared row that every other
 * sync-header consumer reads from the SDK post-cutover (Option B isolation).
 *
 * Fed exclusively by [BlockchainServiceImpl] when
 * [de.schildbach.wallet.ui.dashpay.utils.DashPayConfig.DASHJ_SYNC_DIAGNOSTIC]
 * is on; a plain in-memory [StateFlow] (no persistence, no schema) that reads
 * as its inert [Snapshot] default whenever the diagnostic is off. Observed
 * only by the Tools screen.
 */
@Singleton
class DashjDiagnosticSyncState @Inject constructor() {

    /**
     * The SDK-vs-dashj parity verdict, meaningful only once dashj has caught
     * up (percent == 100). While dashj is still syncing it stays [UNKNOWN].
     *
     * [BALANCE_MATCH] is the in-between verdict: both balance comparisons
     * (estimated AND confirmed, sdk == dashj) agree exactly but the two
     * stacks' transaction counts differ — usually a bookkeeping difference
     * (e.g. self-transfers counted differently), not missing funds.
     */
    enum class Parity { UNKNOWN, MATCH, BALANCE_MATCH, MISMATCH }

    /**
     * One entry of the recent parity history kept for the support-log bundle:
     * a new [ParityReport] from the shadow harness or a verdict transition.
     *
     * @property recordedAtMs when this entry was recorded (wall clock).
     * @property percent dashj's diagnostic sync percentage at the time.
     * @property verdict the verdict derived from [report] (or [Parity.UNKNOWN]).
     * @property report the parity numbers, or null when none existed yet.
     */
    data class ParityHistoryEntry(
        val recordedAtMs: Long,
        val percent: Int,
        val verdict: Parity,
        val report: ParityReport?
    )

    /**
     * @property percent dashj's own sync percentage, 0..100.
     * @property parity SDK-vs-dashj parity once caught up ([UNKNOWN] while syncing).
     * @property stageName dashj's neutral sync-stage name, for the readout/log.
     * @property verifying dashj itself has caught up but the fresh
     *   post-catch-up parity report has not landed yet — the readout shows a
     *   neutral "Verifying" instead of a percentage during this window.
     * @property active whether the diagnostic engine is currently feeding this
     *   holder (dashj un-held by the toggle); false = inert default.
     */
    data class Snapshot(
        val percent: Int = 0,
        val parity: Parity = Parity.UNKNOWN,
        val stageName: String? = null,
        val verifying: Boolean = false,
        val active: Boolean = false
    )

    private val _state = MutableStateFlow(Snapshot())

    /** Live diagnostic snapshot; the inert default until the diagnostic runs. */
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** Push one dashj progress sample (from [BlockchainServiceImpl]). */
    fun update(percent: Int, parity: Parity, stageName: String?, verifying: Boolean = false) {
        _state.value = Snapshot(
            percent = percent.coerceIn(0, 100),
            parity = parity,
            stageName = stageName,
            verifying = verifying,
            active = true
        )
    }

    /** Back to the inert default (diagnostic turned off / not running). */
    fun reset() {
        _state.value = Snapshot()
    }

    // ── Recent parity history (for the support-log bundle) ────────────

    private val history = ArrayDeque<ParityHistoryEntry>()
    private var lastRecordedReportTimestampMs: Long? = null
    private var lastRecordedVerdict: Parity? = null

    /**
     * Record one parity sample (from [BlockchainServiceImpl]) into the small
     * in-memory ring buffer the support-log's `dashJ-kotlin-parity-log.txt`
     * is built from. Deduplicated: an entry lands only when the harness
     * produced a NEW [ParityReport] (by its own timestamp) or the derived
     * verdict changed, so the per-progress-sample call rate never floods it.
     */
    @Synchronized
    fun recordParity(percent: Int, verdict: Parity, report: ParityReport?) {
        val newReport = report != null && report.timestampMs != lastRecordedReportTimestampMs
        val newVerdict = verdict != lastRecordedVerdict
        if (!newReport && !newVerdict) return
        if (report != null) {
            lastRecordedReportTimestampMs = report.timestampMs
        }
        lastRecordedVerdict = verdict
        history.addLast(ParityHistoryEntry(System.currentTimeMillis(), percent, verdict, report))
        while (history.size > HISTORY_CAPACITY) {
            history.removeFirst()
        }
    }

    /** The recorded parity history, oldest first. Empty when the diagnostic never ran. */
    @Synchronized
    fun parityHistory(): List<ParityHistoryEntry> = history.toList()

    companion object {
        /** Ring-buffer capacity for [parityHistory]. */
        private const val HISTORY_CAPACITY = 50
    }
}
