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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Single UI state of the shielded receive screen. */
data class ShieldedReceiveUIState(
    /** True once [ShieldedBalanceService.ensureShieldedReady] succeeded. */
    val shieldedReady: Boolean = false,
    /** Bring-up attempt finished (success or not) — gates the "syncing" toast. */
    val readyCheckDone: Boolean = false,
    /** bech32m shielded receive address, null until the runtime is ready. */
    val shieldedAddress: String? = null
)

@HiltViewModel
class ShieldedReceiveViewModel @Inject constructor(
    private val shieldedBalanceService: ShieldedBalanceService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShieldedReceiveUIState())
    val uiState: StateFlow<ShieldedReceiveUIState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** (Re-)run the bring-up pass and fetch the receive address. */
    fun refresh() {
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
