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
     */
    enum class Parity { UNKNOWN, MATCH, MISMATCH }

    /**
     * @property percent dashj's own sync percentage, 0..100.
     * @property parity SDK-vs-dashj parity once caught up ([UNKNOWN] while syncing).
     * @property stageName dashj's neutral sync-stage name, for the readout/log.
     * @property active whether the diagnostic engine is currently feeding this
     *   holder (dashj un-held by the toggle); false = inert default.
     */
    data class Snapshot(
        val percent: Int = 0,
        val parity: Parity = Parity.UNKNOWN,
        val stageName: String? = null,
        val active: Boolean = false
    )

    private val _state = MutableStateFlow(Snapshot())

    /** Live diagnostic snapshot; the inert default until the diagnostic runs. */
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** Push one dashj progress sample (from [BlockchainServiceImpl]). */
    fun update(percent: Int, parity: Parity, stageName: String?) {
        _state.value = Snapshot(
            percent = percent.coerceIn(0, 100),
            parity = parity,
            stageName = stageName,
            active = true
        )
    }

    /** Back to the inert default (diagnostic turned off / not running). */
    fun reset() {
        _state.value = Snapshot()
    }
}
