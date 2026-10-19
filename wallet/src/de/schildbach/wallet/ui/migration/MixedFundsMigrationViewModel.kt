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
package de.schildbach.wallet.ui.migration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.service.platform.sdk.CoinJoinFundsMigrationService
import de.schildbach.wallet.service.platform.sdk.InFlightMixedFundsMigration
import de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationAction
import de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationOutcome
import de.schildbach.wallet.service.platform.sdk.MixedFundsRoutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.dash.wallet.common.money.Dash
import org.slf4j.LoggerFactory
import javax.inject.Inject

/**
 * Single UI state of the post-upgrade mixed-funds sheet. The outcome fields
 * ([inProgress] … [resultLanded]) are always [reduce]d from
 * [MixedFundsMigrationFacts], never written directly.
 *
 * @param amount the mixed-funds balance being offered
 *   ([CoinJoinFundsMigrationService.mixedFundsToMigrate] — the same source
 *   the prompt gate uses), or null while it is being read or when there is
 *   nothing to migrate.
 * @param amountChecked the latest [amount] read has completed. With a null
 *   [amount] in choice mode the sheet has nothing to offer and closes itself
 *   instead of showing disabled buttons.
 * @param migrationPending the latest read found an in-flight/persisted
 *   migration — the sheet presents its progress (via the marker observer)
 *   and must not close for lack of an amount, even before that observer's
 *   first emission lands.
 * @param inProgress an option is running (pre-broadcast); both actions are
 *   disabled.
 * @param outcome the finished result, or null while nothing has run.
 * @param attemptedAction which migration the [outcome] belongs to. Drives the
 *   per-scenario failure presentation: a failed SHIELD offers "Restart" plus
 *   the combine fallback, a failed COMBINE offers "Restart" only.
 * @param awaitingResult a migration BROADCAST (this session or a torn-down
 *   earlier one — the persisted in-flight marker is the source) and its
 *   result is not user-visible yet: show the post-choice PROCESSING
 *   presentation (spinner + "on the way" message, choices hidden, sheet
 *   dismissible — the marker keeps the state, not the sheet).
 * @param resultTimedOut the in-flight marker outlived its honesty window
 *   without the result landing: show the "could not confirm" guidance
 *   instead of spinning forever; closing acknowledges it (the marker is
 *   kept, only the automatic re-presentation stops).
 * @param resultLanded the result became user-visible while this sheet was
 *   observing (marker cleared): swap to the success presentation and
 *   auto-dismiss.
 * @param shieldOperable / [combineOperable] that route's spend gate is open
 *   ([CoinJoinFundsMigrationService.routeAvailability], read with [amount]);
 *   an inoperable route is never offered, in the choice or as a fallback.
 */
data class MixedFundsMigrationUIState(
    val amount: Coin? = null,
    val amountChecked: Boolean = false,
    val migrationPending: Boolean = false,
    val inProgress: Boolean = false,
    val outcome: MixedFundsMigrationOutcome? = null,
    val attemptedAction: MixedFundsMigrationAction? = null,
    val awaitingResult: Boolean = false,
    val resultTimedOut: Boolean = false,
    val resultLanded: Boolean = false,
    val shieldOperable: Boolean = false,
    val combineOperable: Boolean = false
)

/** Which parts of the sheet are shown — a pure function of [MixedFundsMigrationUIState]. */
internal data class MixedFundsSheetPresentation(
    /** The attempt provably spent nothing (NOT_ATTEMPTED). */
    val failed: Boolean,
    /** "Could not confirm": an UNCONFIRMED outcome or the honesty window lapsed. */
    val terminalGuidance: Boolean,
    /** The destination choice (Shield / Keep spendable / Later) is on offer. */
    val choiceMode: Boolean,
    /** Spinner; Close is hidden and the sheet cannot be dismissed. */
    val processing: Boolean,
    /** The Shield / Combine choice buttons (shown disabled while the routes are read). */
    val showShieldChoice: Boolean,
    val showCombineChoice: Boolean,
    val showRestart: Boolean,
    val showTransferUnmixed: Boolean,
    val showClose: Boolean,
    val cancelable: Boolean
)

internal fun MixedFundsMigrationUIState.presentation(): MixedFundsSheetPresentation {
    val failed = outcome == MixedFundsMigrationOutcome.NOT_ATTEMPTED
    // Terminal guidance is closable on its own: the recovery marker and any
    // still-suspended native call outlive the sheet, so closing cancels
    // nothing and the marker re-presents the migration later.
    val terminalGuidance = !resultLanded &&
        (resultTimedOut || outcome == MixedFundsMigrationOutcome.UNCONFIRMED)
    val awaiting = !inProgress && !resultLanded && !terminalGuidance &&
        (awaitingResult || outcome == MixedFundsMigrationOutcome.STARTED)
    val processing = (inProgress && !terminalGuidance) || awaiting
    val choiceMode = !failed && !processing && !terminalGuidance && !resultLanded
    // A failed move's Try again re-runs that move: offered only while its route can run.
    val failedRouteOperable = when (attemptedAction) {
        MixedFundsMigrationAction.SHIELD -> shieldOperable
        MixedFundsMigrationAction.COMBINE -> combineOperable
        null -> false
    }
    return MixedFundsSheetPresentation(
        failed = failed,
        terminalGuidance = terminalGuidance,
        choiceMode = choiceMode,
        processing = processing,
        showShieldChoice = choiceMode && (!amountChecked || shieldOperable),
        showCombineChoice = choiceMode && (!amountChecked || combineOperable),
        // Nothing to retry while a call is still running. Guidance's Try again only resumes.
        showRestart = (terminalGuidance || (failed && failedRouteOperable)) && !inProgress,
        // After a DEFINITIVE shield failure only — an uncertain one must not
        // offer a second, fresh spend — and only while Combine can run.
        showTransferUnmixed = failed && attemptedAction == MixedFundsMigrationAction.SHIELD && !inProgress &&
            combineOperable,
        showClose = !processing,
        cancelable = !processing && !choiceMode
    )
}

/**
 * Does Try again start a FRESH spend (re-running a definitively failed
 * move), as opposed to resuming the transaction already in flight? Fresh
 * spends need the user's authentication; resuming does not.
 */
internal fun MixedFundsMigrationUIState.retryStartsFreshSpend(): Boolean =
    !(resultTimedOut || outcome == MixedFundsMigrationOutcome.UNCONFIRMED)

/** The latest observed persisted in-flight marker, reduced to what the sheet needs. */
internal enum class MarkerPhase {
    /** No marker (never set, landed, released or reconciled). */
    ABSENT,

    /** Broadcast; the result is not user-visible yet and the window is open. */
    PENDING,

    /** Broadcast; the honesty window lapsed without a visible result. */
    EXPIRED,

    /** A consolidated migration failed DEFINITIVELY (nothing spent, nothing uncertain). */
    FAILED
}

/**
 * Everything the sheet's outcome depends on, recorded as independent facts
 * so the order in which they arrive does not matter — see [reduce].
 *
 * @param marker the latest observed persisted marker — the AUTHORITY.
 * @param action the migration the facts belong to: the latest call's move,
 *   or the latest observed marker's action, whichever came last.
 * @param callRunning a shield/combine/retry call is suspended.
 * @param callResult the latest completed call's return value (null: none
 *   since the last reset).
 * @param markerSeenSinceCall a SET marker was observed after the latest
 *   call started, so a cleared marker is no longer "not written yet".
 * @param landed the watcher VERIFIED the result landed (marker set → cleared
 *   with [CoinJoinFundsMigrationService.verifiedLandings] advanced) since the
 *   latest call started / marker was set.
 */
internal data class MixedFundsMigrationFacts(
    val marker: MarkerPhase = MarkerPhase.ABSENT,
    val action: MixedFundsMigrationAction? = null,
    val callRunning: Boolean = false,
    val callResult: MixedFundsMigrationOutcome? = null,
    val markerSeenSinceCall: Boolean = false,
    val landed: Boolean = false
)

/**
 * The outcome-related part of [MixedFundsMigrationUIState], as a PURE
 * function of the facts — no field is ever overwritten by whichever event
 * happens to arrive last. Precedence:
 *
 * 1. A running call shows its progress (spinner, no actions); a marker
 *    failure observed meanwhile is held, not lost.
 * 2. A FAILED marker is definitive and beats any call result (a retry or
 *    consolidating call returns STARTED before the watcher fails it):
 *    failure, Try again and — after a shield — the wallet-balance fallback.
 * 3. A VERIFIED landing is the only success.
 * 4. A PENDING/EXPIRED marker means something is (or may be) out:
 *    processing / "could not confirm"; a call's NOT_ATTEMPTED cannot
 *    overrule it (only a stale, about-to-clear marker could disagree).
 * 5. No marker: the call result — except a STARTED whose marker was seen
 *    and then cleared WITHOUT a verified landing, which is not success and
 *    leaves nothing pending.
 */
internal fun MixedFundsMigrationFacts.reduce(): MixedFundsMigrationOutcomeView {
    if (callRunning) {
        return MixedFundsMigrationOutcomeView(
            inProgress = true,
            outcome = null,
            attemptedAction = action,
            awaitingResult = marker == MarkerPhase.PENDING,
            resultTimedOut = marker == MarkerPhase.EXPIRED,
            resultLanded = false
        )
    }
    val uncertainOrStarted = callResult.takeIf { it != MixedFundsMigrationOutcome.NOT_ATTEMPTED }
    return when {
        marker == MarkerPhase.FAILED -> MixedFundsMigrationOutcomeView(
            outcome = MixedFundsMigrationOutcome.NOT_ATTEMPTED,
            attemptedAction = action
        )
        landed -> MixedFundsMigrationOutcomeView(
            outcome = callResult,
            attemptedAction = action,
            resultLanded = true
        )
        marker == MarkerPhase.PENDING -> MixedFundsMigrationOutcomeView(
            outcome = uncertainOrStarted,
            attemptedAction = action,
            awaitingResult = true
        )
        marker == MarkerPhase.EXPIRED -> MixedFundsMigrationOutcomeView(
            outcome = uncertainOrStarted,
            attemptedAction = action,
            resultTimedOut = true
        )
        callResult == MixedFundsMigrationOutcome.STARTED && markerSeenSinceCall ->
            MixedFundsMigrationOutcomeView(outcome = null, attemptedAction = action)
        else -> MixedFundsMigrationOutcomeView(outcome = callResult, attemptedAction = action)
    }
}

/** The outcome fields of [MixedFundsMigrationUIState] produced by [reduce]. */
internal data class MixedFundsMigrationOutcomeView(
    val inProgress: Boolean = false,
    val outcome: MixedFundsMigrationOutcome? = null,
    val attemptedAction: MixedFundsMigrationAction? = null,
    val awaitingResult: Boolean = false,
    val resultTimedOut: Boolean = false,
    val resultLanded: Boolean = false
)

/**
 * Drives the two one-time mixed-funds choices. Both are single-source-account
 * transactions on the DIP-9 CoinJoin account — see
 * [CoinJoinFundsMigrationService] for the privacy invariant.
 *
 * Event handlers only record [MixedFundsMigrationFacts]; the UI outcome is
 * always [reduce]d from them, so a marker emission and a call completion
 * yield the same presentation in either order.
 */
@HiltViewModel
class MixedFundsMigrationViewModel @Inject constructor(
    private val migrationService: CoinJoinFundsMigrationService
) : ViewModel() {

    private val _uiState = MutableStateFlow(MixedFundsMigrationUIState())
    val uiState: StateFlow<MixedFundsMigrationUIState> = _uiState.asStateFlow()

    private var facts = MixedFundsMigrationFacts()

    /**
     * Whether the latest marker emission was SET. Only a set → cleared
     * transition can mean "landed"; repeated null emissions (the preference
     * flow re-emits on unrelated writes) never do.
     */
    private var markerSet = false

    /**
     * [CoinJoinFundsMigrationService.verifiedLandings] when the marker was
     * seen set: a cleared marker is success only if the watcher VERIFIED a
     * landing since — a definitive rejection or a reconciled interrupted
     * attempt also clears it.
     */
    private var landingsAtObservation = 0

    /** Re-evaluates the marker's expiry when the honesty window lapses while the sheet is up. */
    private var expiryJob: Job? = null

    /** The in-progress [refreshAmount] read; a newer refresh supersedes it. */
    private var amountJob: Job? = null

    init {
        refreshAmount()
        // The persisted in-flight marker is the authority on the
        // post-broadcast window: set → processing; expired → the "could not
        // confirm" guidance; failed → the failure with its fallback; cleared
        // after being seen set with a verified landing → success.
        viewModelScope.launch {
            migrationService.observeInFlightMigration().collect { marker ->
                expiryJob?.cancel()
                onMarker(marker)
            }
        }
    }

    private fun updateFacts(transform: (MixedFundsMigrationFacts) -> MixedFundsMigrationFacts) {
        facts = transform(facts)
        val view = facts.reduce()
        _uiState.value = _uiState.value.copy(
            inProgress = view.inProgress,
            outcome = view.outcome,
            attemptedAction = view.attemptedAction,
            awaitingResult = view.awaitingResult,
            resultTimedOut = view.resultTimedOut,
            resultLanded = view.resultLanded
        )
    }

    private fun onMarker(marker: InFlightMixedFundsMigration?) {
        if (marker == null) {
            val landed = markerSet && migrationService.verifiedLandings > landingsAtObservation
            // Cleared: the choice (or a released failure's fallback) is offered again on current routes.
            if (markerSet) refreshRoutes()
            markerSet = false
            updateFacts { it.copy(marker = MarkerPhase.ABSENT, landed = it.landed || landed) }
            return
        }
        if (!markerSet) landingsAtObservation = migrationService.verifiedLandings
        markerSet = true
        val phase = when {
            marker.failed -> MarkerPhase.FAILED
            marker.isExpired() -> MarkerPhase.EXPIRED
            else -> MarkerPhase.PENDING
        }
        // A failure offers Try again and the fallback: on current routes, not the sheet's first read.
        if (phase == MarkerPhase.FAILED && facts.marker != MarkerPhase.FAILED) refreshRoutes()
        updateFacts {
            it.copy(marker = phase, action = marker.action, markerSeenSinceCall = true, landed = false)
        }
        if (phase == MarkerPhase.PENDING) {
            // The marker itself does not re-emit at expiry, so a sheet kept
            // open across the window needs its own tick to stop spinning
            // honestly. Cancelled by every later emission.
            expiryJob = viewModelScope.launch {
                delay(marker.remainingMillis() + 50L)
                val current = migrationService.inFlightMigration()
                if (current != null && !current.failed && current.isExpired() &&
                    facts.marker == MarkerPhase.PENDING
                ) {
                    updateFacts { it.copy(marker = MarkerPhase.EXPIRED) }
                }
            }
        }
    }

    /**
     * Re-read the offered amount. This ViewModel is ACTIVITY-scoped (it must
     * outlive the sheet across auto-lock), so its init runs when MainActivity
     * is created — typically long before the L1 view is synced, when
     * [CoinJoinFundsMigrationService.mixedFundsToMigrate] still answers null.
     * Reading only then left a sheet raised later by the (post-sync) prompt
     * gate with no amount and both choices disabled; the sheet therefore
     * calls this every time it is shown, so the offered amount and the
     * prompt decision come from the same source at the same time.
     */
    fun refreshAmount() {
        amountJob?.cancel()
        _uiState.value = _uiState.value.copy(amountChecked = false)
        amountJob = viewModelScope.launch {
            val pending = migrationService.restorePendingShieldMigration()
            val amount = migrationService.mixedFundsToMigrate()
            val routes = readRoutes()
            // One write: the sheet must never see the amount checked without its routes.
            _uiState.value = _uiState.value.copy(amount = amount, amountChecked = true, migrationPending = pending)
                .withRoutes(routes)
        }
    }

    private fun refreshRoutes() {
        viewModelScope.launch { applyRoutes(readRoutes()) }
    }

    private fun applyRoutes(routes: MixedFundsRoutes) {
        _uiState.value = _uiState.value.withRoutes(routes)
    }

    private fun MixedFundsMigrationUIState.withRoutes(routes: MixedFundsRoutes) =
        copy(shieldOperable = routes.shieldOperable, combineOperable = routes.combineOperable)

    /** [CoinJoinFundsMigrationService.routeAvailability]; both closed when it cannot be read. */
    private suspend fun readRoutes(): MixedFundsRoutes = try {
        migrationService.routeAvailability()
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.warn("failed to read the mixed-funds routes; offering neither", t)
        MixedFundsRoutes(shieldOperable = false, combineOperable = false)
    }

    /**
     * The user closed the "could not confirm" guidance — it was seen, so the
     * automatic triggers stop re-presenting it. The marker (and any
     * consolidation stage) is KEPT: the migration may still be out, so it
     * keeps blocking a fresh spend and stays resumable from Tools.
     */
    fun acknowledgeTimedOutResult() {
        viewModelScope.launch {
            migrationService.acknowledgeUnconfirmedMigration()
        }
    }

    /**
     * Should the unlock path bring the sheet back for THIS ViewModel's state
     * (a call running in this process, or a prompt deferred by the lock
     * screen)? Yes — except when the sheet would show "could not confirm"
     * guidance the user already closed for the current marker window
     * ([CoinJoinFundsMigrationService.isUnconfirmedMigrationAcknowledged]),
     * which the automatic paths never re-present, call running or not. A
     * processing (not yet expired) migration and a failure always come back.
     */
    suspend fun shouldRepresentAfterUnlock(): Boolean {
        if (!_uiState.value.presentation().terminalGuidance) return true
        return !migrationService.isUnconfirmedMigrationAcknowledged()
    }

    suspend fun deferMigration() {
        if (_uiState.value.inProgress || _uiState.value.awaitingResult) return
        _uiState.value.amount?.let { migrationService.deferMigration(it) }
    }

    /**
     * Try again, in the mode the sheet decided once and confirmed: [authenticatedMove]
     * null resumes; non-null is the fresh spend of that move, already authenticated.
     * If the state now calls for anything else, nothing runs.
     */
    fun retryMigration(authenticatedMove: MixedFundsMigrationAction?) {
        val state = _uiState.value
        if (state.inProgress) return
        // A pending marker blocks a retry unless its terminal guidance is up
        // (an UNCONFIRMED outcome keeps the marker): that retry only resumes.
        if (state.awaitingResult && !state.presentation().terminalGuidance) return
        val fresh = state.retryStartsFreshSpend()
        if (authenticatedMove != (if (fresh) state.attemptedAction else null)) {
            log.warn("retry mode changed under the confirmation; not retrying")
            return
        }
        if (!fresh) {
            startCall(action = null) {
                try {
                    migrationService.retryPendingMigration()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    log.error("pending CoinJoin shield retry failed", t)
                    MixedFundsMigrationOutcome.UNCONFIRMED
                }
            }
        } else when (state.attemptedAction) {
            MixedFundsMigrationAction.SHIELD -> shieldMixedFunds()
            MixedFundsMigrationAction.COMBINE -> combineIntoUnmixedBalance()
            null -> Unit
        }
    }

    /**
     * The sheet was closed by the user (Close or a cancel gesture — not a
     * lock-screen teardown). A DEFINITIVE failure (nothing spent) returns the
     * activity-scoped state to the destination choice, so reopening from
     * Tools offers both moves again instead of the same failed one; a
     * persisted definitive failure is released the same way (kept — and so
     * presented again — if it cannot be proven safe). Closed "could not
     * confirm" guidance is acknowledged so it stops re-appearing on every
     * resume — EVEN while the native call is still suspended: that guidance
     * is closable precisely because the call outlives the sheet, and the
     * acknowledgement keeps the marker (spend block and recovery intact).
     * Uncertain or in-flight states are kept.
     */
    fun onSheetClosed() {
        val state = _uiState.value
        if (state.presentation().terminalGuidance) {
            acknowledgeTimedOutResult()
            return
        }
        if (state.inProgress) return
        if (state.outcome != MixedFundsMigrationOutcome.NOT_ATTEMPTED) return
        updateFacts { it.copy(callResult = null, action = if (it.marker == MarkerPhase.FAILED) it.action else null) }
        viewModelScope.launch {
            val released = try {
                migrationService.releaseFailedMigration()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("failed to release a failed mixed-funds migration", t)
                false
            }
            // The service's answer is authoritative about what it persisted:
            // released → the failed record is gone (its cleared emission may
            // still be on its way).
            if (released && facts.marker == MarkerPhase.FAILED && !facts.callRunning) {
                markerSet = false
                updateFacts { it.copy(marker = MarkerPhase.ABSENT, action = null) }
            }
        }
    }

    /** OPTION A — shield the mixed funds (keeps them private). */
    fun shieldMixedFunds() = run(MixedFundsMigrationAction.SHIELD) { amount ->
        // This is a conservative UI estimate. The SDK drains the account
        // directly and computes the actual lock value after the L1 fee.
        val lockable = amount.value - CoinJoinFundsMigrationService.L1_FEE_RESERVE_DUFFS
        if (lockable <= 0L) {
            log.warn("mixed-funds balance {} does not cover the L1 fee reserve", amount)
            MixedFundsMigrationOutcome.NOT_ATTEMPTED
        } else {
            migrationService.shieldMixedFunds(Dash(lockable))
        }
    }

    /**
     * OPTION B — combine into the unmixed balance (DE-MIXES the funds). Also
     * the "Transfer to un-mixed balance" fallback offered after a failed
     * shield attempt.
     */
    fun combineIntoUnmixedBalance() = run(MixedFundsMigrationAction.COMBINE) {
        migrationService.combineIntoUnmixedBalance()
    }

    private fun run(
        action: MixedFundsMigrationAction,
        block: suspend (Coin) -> MixedFundsMigrationOutcome
    ) {
        if (_uiState.value.inProgress || _uiState.value.awaitingResult || _uiState.value.resultTimedOut) return
        val amount = _uiState.value.amount
        if (amount == null || !amount.isPositive) {
            updateFacts { it.copy(callResult = MixedFundsMigrationOutcome.NOT_ATTEMPTED, action = action) }
            return
        }
        startCall(action) {
            try {
                block(amount)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // An unexpected throw is NOT proof that nothing was spent —
                // the services classify every known case, so anything landing
                // here is treated as unconfirmed rather than retryable.
                log.error("mixed-funds migration ({}) threw unexpectedly", action, t)
                MixedFundsMigrationOutcome.UNCONFIRMED
            }
        }
    }

    /**
     * Record a call's lifecycle as facts. [action] is the fresh move, or null
     * for a resume (which keeps the migration's own action).
     */
    private fun startCall(action: MixedFundsMigrationAction?, call: suspend () -> MixedFundsMigrationOutcome) {
        updateFacts {
            it.copy(
                callRunning = true,
                callResult = null,
                action = action ?: it.action,
                markerSeenSinceCall = false,
                landed = false
            )
        }
        viewModelScope.launch {
            val outcome = call()
            updateFacts { it.copy(callRunning = false, callResult = outcome) }
            // A failure may follow a gate closing: its fallback views use fresh route availability.
            applyRoutes(readRoutes())
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(MixedFundsMigrationViewModel::class.java)
    }
}
