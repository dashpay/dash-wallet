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

package de.schildbach.wallet.ui.shielded

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.bitcoinj.utils.Fiat
import javax.inject.Inject

/**
 * Single UI state of the shielded "Payments" hub (Figma 1693:15911):
 * balance cards + Receive / Internal / Send tabs.
 */
data class ShieldedHomeUIState(
    /** True once [ShieldedBalanceService.ensureShieldedReady] succeeded. */
    val shieldedReady: Boolean = false,
    /** Bring-up attempt finished (success or not) — gates the "syncing" toast. */
    val readyCheckDone: Boolean = false,
    val shieldedBalance: Dash = Dash.ZERO,
    val shieldedBalanceFiat: String? = null,
    val walletBalance: Dash = Dash.ZERO,
    val walletBalanceFiat: String? = null,
    /** bech32m shielded receive address, null until the runtime is ready. */
    val shieldedAddress: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ShieldedHomeViewModel @Inject constructor(
    private val shieldedBalanceService: ShieldedBalanceService,
    walletDataProvider: WalletDataProvider,
    walletUIConfig: WalletUIConfig,
    exchangeRates: ExchangeRatesProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShieldedHomeUIState())
    val uiState: StateFlow<ShieldedHomeUIState> = _uiState.asStateFlow()

    private var rate: Fiat? = null

    init {
        viewModelScope.launch {
            val ready = shieldedBalanceService.ensureShieldedReady()
            val address = if (ready) shieldedBalanceService.shieldedReceiveAddress() else null
            _uiState.value = _uiState.value.copy(
                shieldedReady = ready,
                readyCheckDone = true,
                shieldedAddress = address
            )
        }

        shieldedBalanceService.observeShieldedBalance()
            .onEach { balance ->
                _uiState.value = _uiState.value.copy(
                    shieldedBalance = balance,
                    shieldedBalanceFiat = balance.toFiatAt(rate)?.toDisplay()
                )
            }
            .launchIn(viewModelScope)

        walletDataProvider.observeTotalBalance()
            .onEach { balance ->
                val dash = Dash(balance.value)
                _uiState.value = _uiState.value.copy(
                    walletBalance = dash,
                    walletBalanceFiat = dash.toFiatAt(rate)?.toDisplay()
                )
            }
            .launchIn(viewModelScope)

        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .flatMapLatest(exchangeRates::observeExchangeRate)
            .onEach { exchangeRate ->
                rate = exchangeRate?.fiat
                val state = _uiState.value
                _uiState.value = state.copy(
                    shieldedBalanceFiat = state.shieldedBalance.toFiatAt(rate)?.toDisplay(),
                    walletBalanceFiat = state.walletBalance.toFiatAt(rate)?.toDisplay()
                )
            }
            .launchIn(viewModelScope)
    }

    /** Retry the bring-up pass (e.g. after the wallet finished binding to the SDK). */
    fun retryReady() {
        viewModelScope.launch {
            val ready = shieldedBalanceService.ensureShieldedReady()
            val address = if (ready) shieldedBalanceService.shieldedReceiveAddress() else null
            _uiState.value = _uiState.value.copy(
                shieldedReady = ready,
                readyCheckDone = true,
                shieldedAddress = address ?: _uiState.value.shieldedAddress
            )
        }
    }
}
