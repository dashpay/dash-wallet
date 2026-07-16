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
package de.schildbach.wallet.ui.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import de.schildbach.wallet.service.platform.sdk.ShieldedSyncStatus
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
 * Which sheet the create-invitation flow shows before the fee/confirm step —
 * the shielded-funding decision point, mirroring the create-username flow's
 * "Make your username private" arm (Figma flow 555:811, sheet 1856:1519;
 * invite variant 25163:53221):
 *
 * - [NONE]: shielded balances are unavailable (flag off / platform
 *   unsupported) — continue straight to the fee dialog, exactly the
 *   pre-design behavior. Never blocks the flow.
 * - [MAKE_INVITE_PRIVATE]: shielded is available — inform the user their
 *   funds can be shielded, show the shielded contested/non-contested cost,
 *   and offer "Shield your funds first" or "Continue without privacy".
 *
 * Unlike the username flow there is NO shielded-funded invite creation path
 * (an invitation is always an L1 asset lock), so the SELECT_PAYMENT_OPTION
 * arm does not apply here — the only decision is shield-first vs continue.
 */
enum class InviteShieldedFundingPrompt { NONE, MAKE_INVITE_PRIVATE }

/**
 * UI state for the shielded-funding decision step of the create-invitation
 * flow.
 *
 * The shielded contested/non-contested "amount to shield" figures come
 * from the same fund-minimum constants the create-username flow shows
 * ([Constants.SHIELDED_USERNAME_FUND_MIN] 0.15 DASH / 
 * [Constants.SHIELDED_USERNAME_FUND_MIN_CONTESTED] 0.35 DASH) — the 0.1 /
 * 0.3 Type-20 exit denomination padded for the shielded-spend fee.
 *
 * The balance/sync pair follows the More-screen balance-card rule: a
 * shielded balance is only trusted when the sync status is
 * [ShieldedSyncStatus.READY] — `Dash.ZERO` mid-sync is a placeholder, not
 * evidence of an empty pool.
 */
data class InviteShieldedFundingUIState(
    /** `USE_KOTLIN_SDK_SHIELDED` on (callers additionally gate on `Constants.SUPPORTS_PLATFORM`). */
    val shieldedEnabled: Boolean = false,
    val syncStatus: ShieldedSyncStatus = ShieldedSyncStatus.NOT_READY,
    val shieldedBalance: Dash = Dash.ZERO,
    /** The L1 wallet balance — what "Shield your funds first" would shield FROM. */
    val walletBalance: Dash = Dash.ZERO,
    /** L1 cost of a non-contested invitation (`DASH_PAY_FEE`). */
    val nonContestedFee: Dash = Dash.ZERO,
    /** L1 cost of a contested invitation (`DASH_PAY_FEE_CONTESTED`). */
    val contestedFee: Dash = Dash.ZERO,
    /** True once the resolving `shieldedEnabled` read has completed (see [prompt]). */
    val resolved: Boolean = false
) {
    /**
     * The shielded balance a NON-contested invitation requires the user to
     * hold: [Constants.SHIELDED_USERNAME_FUND_MIN] (0.15 DASH) — the 0.1
     * Type-20 exit denomination padded for the shielded-spend fee. This is
     * the "amount to shield" the sheet asks for, NOT the bare exit
     * denomination (0.1), which is only what finally leaves the pool.
     */
    val nonContestedShieldedCost: Dash = Dash(Constants.SHIELDED_USERNAME_FUND_MIN.value)

    /** The shielded balance a CONTESTED invitation requires (0.35 DASH). */
    val contestedShieldedCost: Dash = Dash(Constants.SHIELDED_USERNAME_FUND_MIN_CONTESTED.value)

    /**
     * The sheet to show at the invite decision point. Until the flag read
     * resolves the prompt is [InviteShieldedFundingPrompt.NONE] so an
     * undecided state never renders a sheet — the fragment waits for
     * [resolved] before it forwards a NONE straight to the fee dialog.
     */
    val prompt: InviteShieldedFundingPrompt
        get() = when {
            !shieldedEnabled -> InviteShieldedFundingPrompt.NONE
            else -> InviteShieldedFundingPrompt.MAKE_INVITE_PRIVATE
        }

    /**
     * "Shield your funds first" is only useful when the wallet holds at
     * least the SHIELD-guidance amount ([Constants.SHIELDED_USERNAME_FUND_MIN],
     * 0.15 DASH — the 0.1 pool denomination padded for the Shield
     * operation's fee) — below it the sheet disables the button and offers
     * only "Continue without privacy".
     */
    val canShieldMinimum: Boolean
        get() = walletBalance >= Dash(Constants.SHIELDED_USERNAME_FUND_MIN.value)
}

/**
 * Balance/requirement source for the create-invitation shielded-funding
 * decision sheet (Figma 25163:53221), mirroring
 * [de.schildbach.wallet.ui.username.request.UsernamePaymentViewModel].
 *
 * Degrades gracefully: with `USE_KOTLIN_SDK_SHIELDED` off the state stays
 * at its inert defaults ([InviteShieldedFundingUIState.prompt] == NONE), the
 * [ShieldedBalanceService] is never brought up, and nothing can throw out
 * of the init path.
 */
@HiltViewModel
class InviteShieldedFundingViewModel @Inject constructor(
    private val dashPayConfig: DashPayConfig,
    private val shieldedBalanceService: ShieldedBalanceService,
    private val walletData: org.dash.wallet.common.WalletDataProvider
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(InviteShieldedFundingViewModel::class.java)
    }

    private val _uiState = MutableStateFlow(
        InviteShieldedFundingUIState(
            nonContestedFee = Dash(Constants.DASH_PAY_FEE.value),
            contestedFee = Dash(Constants.DASH_PAY_FEE_CONTESTED.value)
        )
    )
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val enabled = runCatching {
                dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) == true
            }.getOrDefault(false)
            _uiState.update { it.copy(shieldedEnabled = enabled, resolved = true) }

            if (!enabled) {
                return@launch
            }

            // Bring the runtime up so the balance loads and the status can
            // advance past NOT_READY (idempotent + single-flight). Never
            // crashes the flow.
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
            launch {
                walletData.observeTotalBalance()
                    .catch { log.warn("wallet balance flow failed", it) }
                    .collect { balance ->
                        _uiState.update { it.copy(walletBalance = Dash(balance.value)) }
                    }
            }
        }
    }
}
