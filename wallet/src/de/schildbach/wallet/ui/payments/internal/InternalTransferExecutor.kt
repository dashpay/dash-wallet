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

import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.service.platform.sdk.ExternalSendResult
import de.schildbach.wallet.service.platform.sdk.InternalTransferService
import de.schildbach.wallet.service.platform.sdk.NotSentReason
import de.schildbach.wallet.service.platform.sdk.SendBalance
import de.schildbach.wallet.service.platform.sdk.safeErrorSummary
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
import java.util.concurrent.atomic.AtomicLong
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
 *
 * Reset Wallet: this singleton outlives the wallet, so a wipe listener ([forgetWalletState])
 * returns it to Idle and moves it to a new wallet generation. A transfer of the wiped wallet that
 * completes afterwards publishes nothing and touches no marker: the next wallet never shows the
 * old one's outcome, nor an interruption the old one left behind.
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
        walletData: WalletData,
        applicationScope: CoroutineScope
    ) : this(
        service,
        DashPayConfigInFlightMarker(dashPayConfig, DashPayConfig.INTERNAL_TRANSFER_IN_FLIGHT),
        applicationScope
    ) {
        walletData.attachOnWalletWipedListener { forgetWalletState() }
    }

    companion object {
        private val log = LoggerFactory.getLogger(InternalTransferExecutor::class.java)
    }

    private val _state = MutableStateFlow<InternalTransferExecution>(InternalTransferExecution.Restoring)
    val state: StateFlow<InternalTransferExecution> = _state.asStateFlow()

    /**
     * The wallet generation: bumped by every wipe ([forgetWalletState]). A transfer captures it
     * when submitted and, once the wallet it was for is gone, neither publishes its outcome nor
     * touches the in-flight marker (which may by then be the next wallet's).
     */
    private val generation = AtomicLong(0)

    init {
        applicationScope.launch {
            val restoredIn = generation.get()
            val interrupted = try {
                marker.isSet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("internal transfer in-flight marker unreadable; assuming a transfer may have gone through ({})", safeErrorSummary(e))
                true
            }
            if (interrupted) log.warn("internal transfer: a previous process may have left a transfer in flight")
            _state.update {
                // A wipe while the marker was read already returned the state to Idle (and the
                // marker it read was the wiped wallet's): the read is not applied.
                if (it is InternalTransferExecution.Restoring && generation.get() == restoredIn) {
                    if (interrupted) InternalTransferExecution.InterruptedByRestart else InternalTransferExecution.Idle
                } else {
                    it
                }
            }
        }
    }

    /** Start [request]; refused (false) unless Idle. A terminal result is never silently replaced. */
    fun submit(request: InternalTransferRequest): Boolean {
        // Read BEFORE the state claim: a wipe that resets the state from here on is seen by
        // [isCurrent], so this transfer can never publish into the next wallet's state.
        val submittedIn = generation.get()

        fun isCurrent() = generation.get() == submittedIn
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
                if (!isCurrent()) {
                    // The wallet was wiped before anything was handed to the service.
                    result = ExternalSendResult.NotSent(NotSentReason.GENERIC)
                    return@launch
                }
                try {
                    marker.set()
                    marked = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nothing handed to the service: provably nothing spent.
                    log.warn("internal transfer: could not record the in-flight marker; not sent ({})", safeErrorSummary(e))
                    result = ExternalSendResult.NotSent(NotSentReason.GENERIC)
                }
                if (marked && !isCurrent()) {
                    // A wipe ran while the marker was written, possibly after it cleared the
                    // marker: this one belongs to the wiped wallet. Nothing is sent.
                    withContext(NonCancellable) { clearMarker() }
                    marked = false
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
                log.error("internal transfer threw; outcome unknown ({})", safeErrorSummary(e))
            } finally {
                log.info("internal transfer {} → {} finished: {}", request.from, request.to, kindOf(result))
                if (!isCurrent()) {
                    // The wallet it was for was wiped: its outcome is not the next wallet's to
                    // see, and the marker (cleared by the wipe) may already be the next one's.
                    log.warn("internal transfer: finished after a wallet wipe; outcome dropped")
                } else {
                    // Cleared BEFORE the outcome is published, and only on Not sent.
                    if (marked && result is ExternalSendResult.NotSent) {
                        withContext(NonCancellable) { clearMarker() }
                    }
                    // Checked again in the update (after the clear's suspension): a wipe that
                    // bumped the generation, or already reset the state, wins.
                    val outcome = result
                    _state.update { if (isCurrent()) InternalTransferExecution.Finished(request, outcome) else it }
                }
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
        val acknowledgedIn = generation.get()
        if (!_state.compareAndSet(current, InternalTransferExecution.Acknowledging)) return
        applicationScope.launch {
            // After a wipe the marker is the wipe's (cleared) or the next wallet's: left alone.
            if (generation.get() == acknowledgedIn) clearMarker()
            _state.compareAndSet(InternalTransferExecution.Acknowledging, InternalTransferExecution.Idle)
        }
    }

    /**
     * Reset Wallet (a wallet-wipe listener, run after the wipe drained every payment hold and
     * cleared the configs): forgets every outcome of the wiped wallet, an interruption by restart
     * included. Bumps [generation] first, so a transfer of that wallet still finishing (or a
     * restore still reading the marker) publishes nothing, then clears the in-flight marker
     * (again: [DashPayConfig] is cleared by the wipe too, but a transfer may have written it
     * after that) and returns to Idle.
     */
    internal suspend fun forgetWalletState() {
        generation.incrementAndGet()
        // Cleared before Idle, so no transfer of the next wallet can have set it yet.
        clearMarker()
        _state.value = InternalTransferExecution.Idle
        log.info("internal transfer: state reset for a wallet wipe")
    }

    private suspend fun clearMarker() {
        try {
            marker.clear()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Left set: the next process warns once more (conservative).
            log.warn("internal transfer: could not clear the in-flight marker ({})", safeErrorSummary(e))
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
