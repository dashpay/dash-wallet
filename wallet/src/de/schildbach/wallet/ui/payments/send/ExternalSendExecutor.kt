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

import androidx.datastore.preferences.core.Preferences
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.service.platform.sdk.ExternalSendResult
import de.schildbach.wallet.service.platform.sdk.ExternalSendService
import de.schildbach.wallet.service.platform.sdk.NotSentReason
import de.schildbach.wallet.service.platform.sdk.SendBalance
import de.schildbach.wallet.service.platform.sdk.SendDestination
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exactly what the user confirmed on the confirm sheet: captured BEFORE authentication and
 * passed to [ExternalSendService.send] unchanged. [expectedWalletIdHex] is the SDK wallet bound
 * when the confirm sheet opened ([ExternalSendService.currentWalletIdOrNull]); the send is
 * refused unless it is still the bound one. [toString] is redacted.
 */
data class ExternalSendRequest(
    val source: SendBalance,
    val destination: SendDestination,
    val amount: Dash,
    val isMax: Boolean,
    val expectedWalletIdHex: String?
) {
    override fun toString(): String = "ExternalSendRequest($source → $destination, <redacted>, isMax=$isMax)"
}

/** The one external send the app runs at a time, as [ExternalSendExecutor] reports it. */
sealed class ExternalSendExecution {
    /** The in-flight marker of a previous process is still being read; nothing can be sent yet. */
    object Restoring : ExternalSendExecution()

    object Idle : ExternalSendExecution()

    data class Sending(val request: ExternalSendRequest) : ExternalSendExecution()

    /** Terminal until the screen [ExternalSendExecutor.acknowledge]s it. */
    data class Finished(val request: ExternalSendRequest, val result: ExternalSendResult) : ExternalSendExecution()

    /**
     * A previous process died with an external send possibly submitted (or its unknown outcome
     * unacknowledged), or the marker could not be read: "may have gone through — check your
     * history". Terminal until acknowledged.
     */
    object InterruptedByRestart : ExternalSendExecution()

    /**
     * An outcome was acknowledged and its marker is being cleared: claimed atomically so a
     * second acknowledgement does nothing, and not Idle so nothing new can be submitted (and
     * have its own marker wiped) before the clear finishes. Becomes Idle after the clear.
     */
    object Acknowledging : ExternalSendExecution()
}

/**
 * The persisted "an external send may be in flight" flag ([DashPayConfig.EXTERNAL_SEND_IN_FLIGHT]).
 * Every call throws when the store cannot be read or written.
 */
internal interface ExternalSendInFlightMarker {
    suspend fun isSet(): Boolean
    suspend fun set()
    suspend fun clear()
}

/**
 * [ExternalSendInFlightMarker] over the [DashPayConfig] flag [key] (error-preserving reads):
 * [DashPayConfig.EXTERNAL_SEND_IN_FLIGHT] by default, [DashPayConfig.INTERNAL_TRANSFER_IN_FLIGHT]
 * for the internal transfer.
 */
internal class DashPayConfigInFlightMarker(
    private val config: DashPayConfig,
    private val key: Preferences.Key<Boolean> = DashPayConfig.EXTERNAL_SEND_IN_FLIGHT
) : ExternalSendInFlightMarker {
    override suspend fun isSet(): Boolean = config.getPreservingErrors(key) == true
    override suspend fun set() = config.set(key, true)
    override suspend fun clear() = config.set(key, false)
}

/**
 * App-lifetime owner of the Advanced-mode external send (MO-1121). A shielded leg blocks for a
 * ~30 s proof, so the send runs on the application scope: leaving the screen, rotating or
 * backgrounding the app never cancels it. [state] is the single source of truth; the
 * [ExternalSendViewModel] of any later visit mirrors it, so a user who left during the send
 * sees the outcome when they open the external send again.
 *
 * Process death: before the request is handed to the service the in-flight marker is written
 * (a failed write sends nothing). It is cleared right away only on Not sent (nothing spent);
 * on Sent and on an unknown outcome only when the user acknowledges it, so a process that dies
 * before the user saw a Sent still warns on the next launch instead of offering the form again.
 * A fresh process that finds it set (or cannot read it) starts in
 * [ExternalSendExecution.InterruptedByRestart].
 *
 * Reset Wallet: this singleton outlives the wallet, so a wipe listener ([forgetWalletState])
 * returns it to Idle and moves it to a new wallet generation. A send of the wiped wallet that
 * completes afterwards publishes nothing and touches no marker: the next wallet never shows,
 * or opens the transaction of, the old one's outcome.
 *
 * Deliberately simpler than the shielded [de.schildbach.wallet.ui.shielded.ShieldedTransferExecutor]:
 * no system notification and no stall watchdog. The service is itself single-flight.
 */
@Singleton
class ExternalSendExecutor internal constructor(
    private val service: ExternalSendService,
    private val marker: ExternalSendInFlightMarker,
    private val applicationScope: CoroutineScope
) {
    @Inject
    constructor(
        service: ExternalSendService,
        dashPayConfig: DashPayConfig,
        walletData: WalletData,
        applicationScope: CoroutineScope
    ) : this(service, DashPayConfigInFlightMarker(dashPayConfig), applicationScope) {
        walletData.attachOnWalletWipedListener { forgetWalletState() }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExternalSendExecutor::class.java)
    }

    private val _state = MutableStateFlow<ExternalSendExecution>(ExternalSendExecution.Restoring)
    val state: StateFlow<ExternalSendExecution> = _state.asStateFlow()

    /**
     * The wallet generation: bumped by every wipe ([forgetWalletState]). A send captures it when
     * submitted and, once the wallet it was for is gone, neither publishes its outcome nor
     * touches the in-flight marker (which may by then be the next wallet's).
     */
    private val generation = AtomicLong(0)

    /** The running [resumePendingPlatformFunding] sweep (declared before `init`, which starts one). */
    private val platformFundingSweep = AtomicReference<Job?>(null)

    init {
        applicationScope.launch {
            val interrupted = try {
                marker.isSet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("external send in-flight marker unreadable; assuming a send may have gone through", e)
                true
            }
            if (interrupted) log.warn("external send: a previous process may have left a send in flight")
            _state.update {
                if (it is ExternalSendExecution.Restoring) {
                    if (interrupted) ExternalSendExecution.InterruptedByRestart else ExternalSendExecution.Idle
                } else {
                    it
                }
            }
        }
        resumePendingPlatformFunding()
    }

    /**
     * Finish an interrupted Core → Platform send in the background
     * ([ExternalSendService.resumePendingPlatformFunding]): run when the executor starts, on
     * every SDK engine kick after its bind pass (app start and each blockchain-service start,
     * `PlatformSynchronizationService.kickSdkEngines`), and each time the external send
     * screen opens. One sweep at a time; cheap (one store read)
     * when nothing is pending. It shares the service's single-flight with sends: it skips
     * while a send runs, and a send submitted while it resumes a lock is refused as in
     * progress (Not sent, nothing spent).
     */
    fun resumePendingPlatformFunding() {
        val running = platformFundingSweep.get()
        if (running?.isActive == true) return
        val job = applicationScope.launch(start = CoroutineStart.LAZY) {
            val completed = try {
                service.resumePendingPlatformFunding()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("external send: pending Core → Platform sweep failed", e)
                0
            }
            if (completed > 0) log.info("external send: an interrupted Core → Platform send was completed")
        }
        if (platformFundingSweep.compareAndSet(running, job)) job.start() else job.cancel()
    }

    /**
     * Start sending [request]. Refused (false) while another send is running, an earlier
     * outcome has not been acknowledged, or the previous process's marker is not yet read: one
     * attempt per confirmation, and a terminal result is never silently replaced.
     */
    fun submit(request: ExternalSendRequest): Boolean {
        // Read BEFORE the state claim: a wipe that resets the state from here on is seen by
        // [isCurrent], so this send can never publish into the next wallet's state.
        val submittedIn = generation.get()

        fun isCurrent() = generation.get() == submittedIn
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
                    log.warn("external send: could not record the in-flight marker; not sent", e)
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
                    result = service.send(
                        request.source,
                        request.destination,
                        request.amount,
                        request.isMax,
                        request.expectedWalletIdHex
                    )
                }
            } catch (e: CancellationException) {
                // The application scope is never cancelled in practice; if it is, the outcome
                // is unknown and stays "may have gone through".
                throw e
            } catch (e: Exception) {
                log.error("external send threw; outcome unknown", e)
            } finally {
                log.info("external send {} → {} finished: {}", request.source, kindOf(request.destination), kindOf(result))
                if (!isCurrent()) {
                    // The wallet it was for was wiped: its outcome is not the next wallet's to
                    // see, and the marker (cleared by the wipe) may already be the next one's.
                    log.warn("external send: finished after a wallet wipe; outcome dropped")
                } else {
                    // Only Not sent (nothing spent) clears here; Sent keeps the restart
                    // protection until acknowledged. Cleared BEFORE the outcome is published: a
                    // later submit (only after this one is acknowledged) can never have its own
                    // marker cleared by this one.
                    if (marked && result is ExternalSendResult.NotSent) {
                        withContext(NonCancellable) { clearMarker() }
                    }
                    // Checked again in the update (after the clear's suspension): a wipe that
                    // bumped the generation, or already reset the state, wins.
                    val outcome = result
                    _state.update { if (isCurrent()) ExternalSendExecution.Finished(request, outcome) else it }
                }
            }
        }
        return true
    }

    /**
     * The screen showed the outcome; the executor is free again. A Not sent outcome (marker
     * already cleared) becomes Idle at once. Any other outcome is first CLAIMED — atomically
     * moved to [ExternalSendExecution.Acknowledging] — so only one acknowledgement clears the
     * marker; it becomes Idle only after the clear, so the clear can never race a new send's
     * marker. A losing (duplicate) acknowledgement does nothing.
     */
    fun acknowledge() {
        val current = _state.value
        val needsClear = when (current) {
            is ExternalSendExecution.Finished -> current.result !is ExternalSendResult.NotSent
            ExternalSendExecution.InterruptedByRestart -> true
            else -> return
        }
        if (!needsClear) {
            _state.compareAndSet(current, ExternalSendExecution.Idle)
            return
        }
        val acknowledgedIn = generation.get()
        if (!_state.compareAndSet(current, ExternalSendExecution.Acknowledging)) return
        applicationScope.launch {
            // After a wipe the marker is the wipe's (cleared) or the next wallet's: left alone.
            if (generation.get() == acknowledgedIn) clearMarker()
            _state.compareAndSet(ExternalSendExecution.Acknowledging, ExternalSendExecution.Idle)
        }
    }

    /**
     * Reset Wallet (a wallet-wipe listener, run after the wipe drained every payment hold and
     * cleared the configs): forgets every outcome of the wiped wallet. Bumps [generation] first,
     * so a send of that wallet still finishing publishes nothing, then clears the in-flight
     * marker (again: [DashPayConfig] is cleared by the wipe too, but a send may have written it
     * after that) and returns to Idle.
     */
    internal suspend fun forgetWalletState() {
        generation.incrementAndGet()
        // Cleared before Idle, so no send of the next wallet can have set it yet.
        clearMarker()
        _state.value = ExternalSendExecution.Idle
        log.info("external send: state reset for a wallet wipe")
    }

    private suspend fun clearMarker() {
        try {
            marker.clear()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Left set: the next process warns once more (conservative).
            log.warn("external send: could not clear the in-flight marker", e)
        }
    }

    private fun kindOf(destination: SendDestination): String = destination::class.java.simpleName

    private fun kindOf(result: ExternalSendResult): String = when (result) {
        is ExternalSendResult.Sent -> "Sent"
        is ExternalSendResult.NotSent -> "NotSent(${result.reason})"
        ExternalSendResult.MayHaveGoneThrough -> "MayHaveGoneThrough"
        ExternalSendResult.InterruptedFundsReturnToShielded -> "InterruptedFundsReturnToShielded"
        ExternalSendResult.CompletesAutomatically -> "CompletesAutomatically"
    }
}
