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
import de.schildbach.wallet.service.platform.sdk.MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS
import de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationAction
import de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationOutcome
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
 * Single UI state of the post-upgrade mixed-funds sheet.
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
 *   instead of spinning forever; dismissal acknowledges (clears) it.
 * @param resultLanded the result became user-visible while this sheet was
 *   observing (marker cleared): swap to the success presentation and
 *   auto-dismiss.
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
    val resultLanded: Boolean = false
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
    return MixedFundsSheetPresentation(
        failed = failed,
        terminalGuidance = terminalGuidance,
        choiceMode = choiceMode,
        processing = processing,
        // Nothing to retry while a call is still running.
        showRestart = (failed || terminalGuidance) && !inProgress,
        // After a DEFINITIVE shield failure only — an uncertain one must not
        // offer a second, fresh spend.
        showTransferUnmixed = failed && attemptedAction == MixedFundsMigrationAction.SHIELD && !inProgress,
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

/**
 * Drives the two one-time mixed-funds choices. Both are single-source-account
 * transactions on the DIP-9 CoinJoin account — see
 * [CoinJoinFundsMigrationService] for the privacy invariant.
 */
@HiltViewModel
class MixedFundsMigrationViewModel @Inject constructor(
    private val migrationService: CoinJoinFundsMigrationService
) : ViewModel() {

    private val _uiState = MutableStateFlow(MixedFundsMigrationUIState())
    val uiState: StateFlow<MixedFundsMigrationUIState> = _uiState.asStateFlow()

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

    /** A verified landing observed while a call was still running. */
    private var landedWhileRunning = false

    /** Re-evaluates [MixedFundsMigrationUIState.resultTimedOut] when the honesty window lapses while the sheet is up. */
    private var expiryJob: Job? = null

    /** The in-progress [refreshAmount] read; a newer refresh supersedes it. */
    private var amountJob: Job? = null

    init {
        refreshAmount()
        // The persisted in-flight marker drives the post-broadcast window:
        // set (this session's broadcast or a resumed one) → processing
        // presentation; expired → the "could not confirm" guidance; cleared
        // after being seen set → the result is user-visible, success.
        viewModelScope.launch {
            migrationService.observeInFlightMigration().collect { marker ->
                expiryJob?.cancel()
                if (marker == null) {
                    val landed = markerSet && migrationService.verifiedLandings > landingsAtObservation
                    markerSet = false
                    val running = _uiState.value.inProgress
                    if (running && landed) landedWhileRunning = true
                    _uiState.value = _uiState.value.copy(
                        awaitingResult = false,
                        resultTimedOut = false,
                        resultLanded = _uiState.value.resultLanded || (landed && !running)
                    )
                } else {
                    if (!markerSet) landingsAtObservation = migrationService.verifiedLandings
                    markerSet = true
                    val expired = marker.isExpired()
                    _uiState.value = _uiState.value.copy(
                        awaitingResult = !expired,
                        resultTimedOut = expired,
                        resultLanded = false,
                        attemptedAction = marker.action
                    )
                    if (!expired) {
                        // The marker itself does not re-emit at expiry, so a
                        // sheet kept open across the window needs its own
                        // tick to stop spinning honestly.
                        expiryJob = viewModelScope.launch {
                            delay(marker.startedAtMillis + MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS - System.currentTimeMillis() + 50L)
                            if (migrationService.inFlightMigration()?.isExpired() == true) {
                                _uiState.value = _uiState.value.copy(awaitingResult = false, resultTimedOut = true)
                            }
                        }
                    }
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
            _uiState.value = _uiState.value.copy(amount = amount, amountChecked = true, migrationPending = pending)
        }
    }

    /**
     * The user dismissed the TIMED-OUT presentation — the guidance was seen,
     * so the marker is dropped and the sheet stops re-appearing. Called from
     * the fragment's onDismiss only in that state.
     */
    fun acknowledgeTimedOutResult() {
        viewModelScope.launch {
            migrationService.clearInFlightMigration()
        }
    }

    suspend fun deferMigration() {
        if (_uiState.value.inProgress || _uiState.value.awaitingResult) return
        _uiState.value.amount?.let { migrationService.deferMigration(it) }
    }

    fun retryMigration() {
        val state = _uiState.value
        if (state.inProgress) return
        // A pending marker blocks a retry unless its terminal guidance is up
        // (an UNCONFIRMED outcome keeps the marker): that retry only resumes.
        if (state.awaitingResult && !state.presentation().terminalGuidance) return
        if (!state.retryStartsFreshSpend()) {
            val landingsBefore = migrationService.verifiedLandings
            _uiState.value = state.copy(inProgress = true, resultTimedOut = false, outcome = null)
            viewModelScope.launch {
                val outcome = try {
                    migrationService.retryPendingMigration()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    log.error("pending CoinJoin shield retry failed", t)
                    MixedFundsMigrationOutcome.UNCONFIRMED
                }
                val landed = outcome == MixedFundsMigrationOutcome.STARTED &&
                    migrationService.inFlightMigration() == null &&
                    migrationService.verifiedLandings > landingsBefore
                _uiState.value = _uiState.value.copy(
                    inProgress = false, outcome = outcome, resultLanded = landed,
                    awaitingResult = outcome == MixedFundsMigrationOutcome.STARTED && !landed
                )
            }
        } else when (_uiState.value.attemptedAction) {
            MixedFundsMigrationAction.SHIELD -> shieldMixedFunds()
            MixedFundsMigrationAction.COMBINE -> combineIntoUnmixedBalance()
            null -> Unit
        }
    }

    /**
     * The sheet was closed. A DEFINITIVE failure (nothing spent) returns the
     * activity-scoped state to the destination choice, so reopening from
     * Tools offers both moves again instead of the same failed one.
     * Uncertain or in-flight states are kept.
     */
    fun onSheetClosed() {
        val state = _uiState.value
        if (!state.inProgress && state.outcome == MixedFundsMigrationOutcome.NOT_ATTEMPTED) {
            _uiState.value = state.copy(outcome = null, attemptedAction = null)
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
            _uiState.value = _uiState.value.copy(
                outcome = MixedFundsMigrationOutcome.NOT_ATTEMPTED,
                attemptedAction = action
            )
            return
        }
        landedWhileRunning = false
        _uiState.value = _uiState.value.copy(inProgress = true, outcome = null, attemptedAction = action)
        viewModelScope.launch {
            val outcome = try {
                block(amount)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // An unexpected throw is NOT proof that nothing was spent —
                // the services classify every known case, so anything landing
                // here is treated as unconfirmed rather than retryable.
                log.error("mixed-funds migration ({}) threw unexpectedly", action, t)
                MixedFundsMigrationOutcome.UNCONFIRMED
            }
            _uiState.value = _uiState.value.copy(
                inProgress = false,
                outcome = outcome,
                resultLanded = outcome == MixedFundsMigrationOutcome.STARTED && landedWhileRunning &&
                    !_uiState.value.awaitingResult && !_uiState.value.resultTimedOut
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(MixedFundsMigrationViewModel::class.java)
    }
}
