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

package de.schildbach.wallet.ui.payments.internal

import de.schildbach.wallet.service.platform.sdk.ExternalSendResult
import de.schildbach.wallet.service.platform.sdk.InternalTransferService
import de.schildbach.wallet.service.platform.sdk.NotSentReason
import de.schildbach.wallet.service.platform.sdk.SendBalance
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.payments.send.DashPayConfigInFlightMarker
import de.schildbach.wallet.ui.payments.send.ExternalSendInFlightMarker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dash.wallet.common.money.Dash
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exactly what the user confirmed: captured BEFORE authentication and passed to
 * [InternalTransferService.transfer] unchanged. There is no destination: the service resolves
 * the wallet's own address for [to] at send time. [toString] is redacted.
 */
data class InternalTransferRequest(
    val from: SendBalance,
    val to: SendBalance,
    val amount: Dash,
    val isMax: Boolean,
    val expectedWalletIdHex: String?
) {
    override fun toString(): String = "InternalTransferRequest($from → $to, <redacted>, isMax=$isMax)"
}

/** The one internal transfer the app runs at a time, as [InternalTransferExecutor] reports it. */
sealed class InternalTransferExecution {
    /** The in-flight marker of a previous process is still being read; nothing can be sent yet. */
    object Restoring : InternalTransferExecution()

    object Idle : InternalTransferExecution()

    data class Sending(val request: InternalTransferRequest) : InternalTransferExecution()

    /** Terminal until the screen [InternalTransferExecutor.acknowledge]s it. */
    data class Finished(val request: InternalTransferRequest, val result: ExternalSendResult) :
        InternalTransferExecution()

    /** A previous process died with a transfer possibly submitted, or the marker is unreadable. */
    object InterruptedByRestart : InternalTransferExecution()

    /** An outcome was acknowledged and its marker is being cleared (see the External Send executor). */
    object Acknowledging : InternalTransferExecution()
}

/**
 * App-lifetime owner of the Advanced-mode internal transfer (MO-1125): the same contract as
 * [de.schildbach.wallet.ui.payments.send.ExternalSendExecutor] — application scope (a shielded
 * leg's ~30 s proof survives the screen), one transfer per confirmation, a durable in-flight
 * marker ([DashPayConfig.INTERNAL_TRANSFER_IN_FLIGHT]) written before the request reaches the
 * service and cleared right away only on Not sent, otherwise on acknowledgement — over
 * [InternalTransferService] and its own request type.
 *
 * A sibling rather than a generalized External Send executor: that executor's state type is
 * public API of the External Send screen and its tests, and sharing one instance would let one
 * flow's screen show (and acknowledge) the other's outcome.
 */
@Singleton
class InternalTransferExecutor internal constructor(
    private val service: InternalTransferService,
    private val marker: ExternalSendInFlightMarker,
    private val applicationScope: CoroutineScope
) {
    @Inject
    constructor(
        service: InternalTransferService,
        dashPayConfig: DashPayConfig,
        applicationScope: CoroutineScope
    ) : this(
        service,
        DashPayConfigInFlightMarker(dashPayConfig, DashPayConfig.INTERNAL_TRANSFER_IN_FLIGHT),
        applicationScope
    )

    companion object {
        private val log = LoggerFactory.getLogger(InternalTransferExecutor::class.java)
    }

    private val _state = MutableStateFlow<InternalTransferExecution>(InternalTransferExecution.Restoring)
    val state: StateFlow<InternalTransferExecution> = _state.asStateFlow()

    init {
        applicationScope.launch {
            val interrupted = try {
                marker.isSet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("internal transfer in-flight marker unreadable; assuming a transfer may have gone through", e)
                true
            }
            if (interrupted) log.warn("internal transfer: a previous process may have left a transfer in flight")
            _state.update {
                if (it is InternalTransferExecution.Restoring) {
                    if (interrupted) InternalTransferExecution.InterruptedByRestart else InternalTransferExecution.Idle
                } else {
                    it
                }
            }
        }
    }

    /** Start [request]; refused (false) unless Idle. A terminal result is never silently replaced. */
    fun submit(request: InternalTransferRequest): Boolean {
        var accepted = false
        _state.update { current ->
            if (current is InternalTransferExecution.Idle) {
                accepted = true
                InternalTransferExecution.Sending(request)
            } else {
                accepted = false
                current
            }
        }
        if (!accepted) {
            log.warn("internal transfer refused: a previous transfer is still running or unacknowledged")
            return false
        }
        applicationScope.launch {
            var result: ExternalSendResult = ExternalSendResult.MayHaveGoneThrough
            var marked = false
            try {
                try {
                    marker.set()
                    marked = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nothing handed to the service: provably nothing spent.
                    log.warn("internal transfer: could not record the in-flight marker; not sent", e)
                    result = ExternalSendResult.NotSent(NotSentReason.GENERIC)
                }
                if (marked) {
                    result = service.transfer(
                        request.from,
                        request.to,
                        request.amount,
                        request.isMax,
                        request.expectedWalletIdHex
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("internal transfer threw; outcome unknown", e)
            } finally {
                log.info("internal transfer {} → {} finished: {}", request.from, request.to, kindOf(result))
                // Cleared BEFORE the outcome is published, and only on Not sent.
                if (marked && result is ExternalSendResult.NotSent) {
                    withContext(NonCancellable) { clearMarker() }
                }
                _state.value = InternalTransferExecution.Finished(request, result)
            }
        }
        return true
    }

    /** The screen showed the outcome (see `ExternalSendExecutor.acknowledge`). */
    fun acknowledge() {
        val current = _state.value
        val needsClear = when (current) {
            is InternalTransferExecution.Finished -> current.result !is ExternalSendResult.NotSent
            InternalTransferExecution.InterruptedByRestart -> true
            else -> return
        }
        if (!needsClear) {
            _state.compareAndSet(current, InternalTransferExecution.Idle)
            return
        }
        if (!_state.compareAndSet(current, InternalTransferExecution.Acknowledging)) return
        applicationScope.launch {
            clearMarker()
            _state.compareAndSet(InternalTransferExecution.Acknowledging, InternalTransferExecution.Idle)
        }
    }

    private suspend fun clearMarker() {
        try {
            marker.clear()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Left set: the next process warns once more (conservative).
            log.warn("internal transfer: could not clear the in-flight marker", e)
        }
    }

    private fun kindOf(result: ExternalSendResult): String = when (result) {
        is ExternalSendResult.Sent -> "Sent"
        is ExternalSendResult.NotSent -> "NotSent(${result.reason})"
        ExternalSendResult.MayHaveGoneThrough -> "MayHaveGoneThrough"
        ExternalSendResult.InterruptedFundsReturnToShielded -> "InterruptedFundsReturnToShielded"
        ExternalSendResult.CompletesAutomatically -> "CompletesAutomatically"
    }
}
