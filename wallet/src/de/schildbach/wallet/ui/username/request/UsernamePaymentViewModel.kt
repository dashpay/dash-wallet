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
package de.schildbach.wallet.ui.username.request

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import de.schildbach.wallet.service.platform.sdk.ShieldedSyncStatus
import de.schildbach.wallet.service.platform.sdk.shieldedIdentityFundingRequirement
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.dash.wallet.common.money.Dash
import org.slf4j.LoggerFactory
import javax.inject.Inject

/**
 * Where the username fee is paid from — the choice the "Select your payment
 * option" sheet (Figma 1856:1805) records. Replaces the removed CoinJoin
 * mixed/unmixed funding split: the private option is now the SHIELDED
 * (Orchard) balance.
 */
enum class UsernamePaymentSource { DASH_BALANCE, SHIELDED_BALANCE }

/**
 * Which sheet the create-username flow shows after the "Welcome to Dash Pay"
 * continue (the decision point on the Figma flow canvas 555:811):
 *
 * - [NONE]: shielded balances are unavailable (flag off / platform
 *   unsupported / invite pays the fee) — continue straight on, exactly the
 *   pre-design behavior. Never blocks the flow.
 * - [SELECT_PAYMENT_OPTION]: the user has a trustworthy shielded balance
 *   that covers the username fee — ask which balance pays (Figma 1856:1805).
 * - [MAKE_USERNAME_PRIVATE]: shielded is available but there are no usable
 *   shielded funds — recommend shielding first (Figma 1856:1519).
 */
enum class UsernamePaymentPrompt { NONE, SELECT_PAYMENT_OPTION, MAKE_USERNAME_PRIVATE }

/**
 * UI state for the shielded-funds payment step of the create-username flow.
 *
 * The balance/sync pair follows the More-screen balance-card rule: a
 * shielded balance is only trusted when the sync status is
 * [ShieldedSyncStatus.READY] — `Dash.ZERO` mid-sync is a placeholder, not
 * evidence of an empty pool, so it must never unlock the "pay from
 * shielded" path by accident (it can't: zero covers no fee) nor be shown
 * as a real amount.
 */
data class UsernamePaymentUIState(
    /** `USE_KOTLIN_SDK_SHIELDED` on (callers additionally gate on `Constants.SUPPORTS_PLATFORM`). */
    val shieldedEnabled: Boolean = false,
    val syncStatus: ShieldedSyncStatus = ShieldedSyncStatus.NOT_READY,
    val shieldedBalance: Dash = Dash.ZERO,
    /** Cost of a non-contested username (the flow's minimum requirement). */
    val usernameFee: Dash = Dash.ZERO,
    val selectedSource: UsernamePaymentSource? = null
) {
    /** True only when the shielded runtime has finished a sync pass — see class KDoc. */
    val shieldedBalanceTrustworthy: Boolean
        get() = shieldedEnabled && syncStatus == ShieldedSyncStatus.READY

    /**
     * The shielded pool balance actually required to fund the username:
     * the smallest fixed Type-20 exit denomination (0.1/0.3/0.5/1.0 DASH)
     * covering [usernameFee] — NOT the bare fee. The identity is created
     * by spending a whole denomination from the pool (fee metered out of
     * it, change back to the pool), so a pool holding more than the fee
     * but less than the denomination cannot fund the creation. Null when
     * no denomination covers the fee.
     */
    val shieldedFundingRequirement: Dash?
        get() = shieldedIdentityFundingRequirement(usernameFee)

    /** The shielded pool can fund a (non-contested) username right now. */
    val canPayFeeFromShielded: Boolean
        get() = shieldedBalanceTrustworthy &&
            shieldedFundingRequirement?.let { shieldedBalance >= it } == true

    /**
     * The sheet to show at the welcome-screen decision point. While the
     * pool is still syncing we cannot prove funds exist, so the flow takes
     * the [UsernamePaymentPrompt.MAKE_USERNAME_PRIVATE] arm — it only
     * recommends shielding and always offers "Continue without privacy",
     * so an unproven balance never blocks anyone.
     */
    val prompt: UsernamePaymentPrompt
        get() = when {
            !shieldedEnabled -> UsernamePaymentPrompt.NONE
            canPayFeeFromShielded -> UsernamePaymentPrompt.SELECT_PAYMENT_OPTION
            else -> UsernamePaymentPrompt.MAKE_USERNAME_PRIVATE
        }

    /** "Continue" on the select sheet is enabled only after an option is picked (Figma 1855:11870 → 1856:1476). */
    val canContinue: Boolean
        get() = selectedSource != null
}

/**
 * Balance/requirement source for the shielded-funds create-username designs
 * (Figma 555:811 / 1855:11660 / 1856:1805 / 1856:1519), replacing the
 * removed CoinJoin mixed-balance sourcing.
 *
 * Degrades gracefully: with `USE_KOTLIN_SDK_SHIELDED` off the state stays
 * at its inert defaults ([UsernamePaymentUIState.prompt] == NONE), the
 * [ShieldedBalanceService] is never brought up, and nothing can throw out
 * of the init path.
 */
@HiltViewModel
class UsernamePaymentViewModel @Inject constructor(
    private val dashPayConfig: DashPayConfig,
    private val shieldedBalanceService: ShieldedBalanceService
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(UsernamePaymentViewModel::class.java)
    }

    private val _uiState = MutableStateFlow(
        UsernamePaymentUIState(usernameFee = Dash(Constants.DASH_PAY_FEE.value))
    )
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val enabled = runCatching {
                dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) == true
            }.getOrDefault(false)
            _uiState.update { it.copy(shieldedEnabled = enabled) }

            if (!enabled) {
                return@launch
            }

            // Bring the runtime up so the balance loads and the status can
            // advance past NOT_READY (idempotent + single-flight; the sync
            // loop itself runs on the app scope). Never crashes the flow.
            launch {
                runCatching { shieldedBalanceService.ensureShieldedReady() }
                    .onFailure { log.warn("shielded bring-up failed", it) }
            }
            launch {
                shieldedBalanceService.observeShieldedBalance()
                    .catch { log.warn("shielded balance flow failed", it) }
                    .collect { balance -> _uiState.update { it.copy(shieldedBalance = balance) } }
            }
            launch {
                shieldedBalanceService.shieldedSyncStatus
                    .catch { log.warn("shielded status flow failed", it) }
                    .collect { status -> _uiState.update { it.copy(syncStatus = status) } }
            }
        }
    }

    fun selectSource(source: UsernamePaymentSource) {
        _uiState.update { it.copy(selectedSource = source) }
    }
}
