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

package de.schildbach.wallet.ui.payments.send

import de.schildbach.wallet.service.platform.sdk.ExternalSendResult
import de.schildbach.wallet.service.platform.sdk.ExternalSendService
import de.schildbach.wallet.service.platform.sdk.SendBalance
import de.schildbach.wallet.service.platform.sdk.SendDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.dash.wallet.common.money.Dash
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exactly what the user confirmed on the confirm sheet: captured BEFORE authentication and
 * passed to [ExternalSendService.send] unchanged. [toString] is redacted.
 */
data class ExternalSendRequest(
    val source: SendBalance,
    val destination: SendDestination,
    val amount: Dash,
    val isMax: Boolean
) {
    override fun toString(): String = "ExternalSendRequest($source → $destination, <redacted>, isMax=$isMax)"
}

/** The one external send the app runs at a time, as [ExternalSendExecutor] reports it. */
sealed class ExternalSendExecution {
    object Idle : ExternalSendExecution()

    data class Sending(val request: ExternalSendRequest) : ExternalSendExecution()

    /** Terminal until the screen [ExternalSendExecutor.acknowledge]s it. */
    data class Finished(val request: ExternalSendRequest, val result: ExternalSendResult) : ExternalSendExecution()
}

/**
 * App-lifetime owner of the Advanced-mode external send (MO-1121). A shielded leg blocks for a
 * ~30 s proof, so the send runs on the application scope: leaving the screen, rotating or
 * backgrounding the app never cancels it. [state] is the single source of truth; the
 * [ExternalSendViewModel] of any later visit mirrors it, so a user who left during the send
 * sees the outcome when they open the external send again.
 *
 * Deliberately simpler than the shielded [de.schildbach.wallet.ui.shielded.ShieldedTransferExecutor]:
 * no system notification and no stall watchdog. The service is itself single-flight.
 */
@Singleton
class ExternalSendExecutor @Inject constructor(
    private val service: ExternalSendService,
    private val applicationScope: CoroutineScope
) {
    companion object {
        private val log = LoggerFactory.getLogger(ExternalSendExecutor::class.java)
    }

    private val _state = MutableStateFlow<ExternalSendExecution>(ExternalSendExecution.Idle)
    val state: StateFlow<ExternalSendExecution> = _state.asStateFlow()

    /**
     * Start sending [request]. Refused (false) while another send is running or an earlier
     * outcome has not been acknowledged: one attempt per confirmation, and a terminal result
     * is never silently replaced.
     */
    fun submit(request: ExternalSendRequest): Boolean {
        var accepted = false
        _state.update { current ->
            if (current is ExternalSendExecution.Idle) {
                accepted = true
                ExternalSendExecution.Sending(request)
            } else {
                accepted = false
                current
            }
        }
        if (!accepted) {
            log.warn("external send refused: a previous send is still running or unacknowledged")
            return false
        }
        applicationScope.launch {
            var result: ExternalSendResult = ExternalSendResult.MayHaveGoneThrough
            try {
                result = service.send(request.source, request.destination, request.amount, request.isMax)
            } catch (e: CancellationException) {
                // The application scope is never cancelled in practice; if it is, the outcome
                // is unknown and stays "may have gone through".
                throw e
            } catch (e: Exception) {
                log.error("external send threw; outcome unknown", e)
            } finally {
                log.info("external send {} → {} finished: {}", request.source, kindOf(request.destination), kindOf(result))
                _state.value = ExternalSendExecution.Finished(request, result)
            }
        }
        return true
    }

    /** The screen showed [ExternalSendExecution.Finished]; the executor is free again. */
    fun acknowledge() {
        _state.update { if (it is ExternalSendExecution.Finished) ExternalSendExecution.Idle else it }
    }

    private fun kindOf(destination: SendDestination): String = destination::class.java.simpleName

    private fun kindOf(result: ExternalSendResult): String = when (result) {
        is ExternalSendResult.Sent -> "Sent"
        is ExternalSendResult.NotSent -> "NotSent(${result.reason})"
        ExternalSendResult.MayHaveGoneThrough -> "MayHaveGoneThrough"
        ExternalSendResult.InterruptedFundsReturnToShielded -> "InterruptedFundsReturnToShielded"
    }
}
