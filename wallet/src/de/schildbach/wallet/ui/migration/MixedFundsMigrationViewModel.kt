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
import de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationOutcome
import kotlinx.coroutines.CancellationException
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
 * @param amount the CoinJoin-keychain balance being offered, or null while it
 *   is still being read.
 * @param inProgress an option is running; both actions are disabled.
 * @param outcome the finished result, or null while nothing has run.
 */
data class MixedFundsMigrationUIState(
    val amount: Coin? = null,
    val inProgress: Boolean = false,
    val outcome: MixedFundsMigrationOutcome? = null
)

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

    init {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(amount = migrationService.mixedFundsToMigrate())
        }
    }

    /** OPTION A — shield the mixed funds (keeps them private). */
    fun shieldMixedFunds() = run("shield") { amount ->
        // Leave headroom for the L1 asset-lock fee. The engine's selection
        // still has to reach every CoinJoin UTXO to cover this, so the
        // account empties; whatever the fee leaves over returns as BIP44
        // change rather than being stranded on the mixed keychain.
        val lockable = amount.value - CoinJoinFundsMigrationService.L1_FEE_RESERVE_DUFFS
        if (lockable <= 0L) {
            log.warn("mixed-funds balance {} does not cover the L1 fee reserve", amount)
            MixedFundsMigrationOutcome.NOT_ATTEMPTED
        } else {
            migrationService.shieldMixedFunds(Dash(lockable))
        }
    }

    /** OPTION B — combine into the unmixed balance (DE-MIXES the funds). */
    fun combineIntoUnmixedBalance() = run("combine") {
        migrationService.combineIntoUnmixedBalance()
    }

    /** "Not now" — suppress the prompt permanently without spending anything. */
    fun dismissForever() {
        viewModelScope.launch {
            try {
                migrationService.dismissForever()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("failed to record the mixed-funds dismissal", t)
            }
        }
    }

    private fun run(
        what: String,
        block: suspend (Coin) -> MixedFundsMigrationOutcome
    ) {
        if (_uiState.value.inProgress) return
        val amount = _uiState.value.amount
        if (amount == null || !amount.isPositive) {
            _uiState.value = _uiState.value.copy(outcome = MixedFundsMigrationOutcome.NOT_ATTEMPTED)
            return
        }
        _uiState.value = _uiState.value.copy(inProgress = true, outcome = null)
        viewModelScope.launch {
            val outcome = try {
                block(amount)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // An unexpected throw is NOT proof that nothing was spent —
                // the services classify every known case, so anything landing
                // here is treated as unconfirmed rather than retryable.
                log.error("mixed-funds migration ({}) threw unexpectedly", what, t)
                MixedFundsMigrationOutcome.UNCONFIRMED
            }
            _uiState.value = _uiState.value.copy(inProgress = false, outcome = outcome)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(MixedFundsMigrationViewModel::class.java)
    }
}
