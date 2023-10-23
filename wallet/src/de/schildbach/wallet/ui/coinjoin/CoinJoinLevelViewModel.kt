/*
 * Copyright 2023 Dash Core Group.
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

package de.schildbach.wallet.ui.coinjoin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.CoinJoinService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Inject

@HiltViewModel
open class CoinJoinLevelViewModel @Inject constructor(
    private val analytics: AnalyticsService,
    private val coinJoinService: CoinJoinService,
    private val coinJoinConfig: CoinJoinConfig,
    private var networkState: NetworkStateInt
) : ViewModel() {

    val isMixing: Boolean
        get() = _mixingMode.value != CoinJoinMode.NONE
    //    get() = coinJoinService.mixingStatus == MixingStatus.MIXING ||
    //        coinJoinService.mixingStatus == MixingStatus.PAUSED

    val _mixingMode = MutableStateFlow<CoinJoinMode>(CoinJoinMode.NONE)

    val mixingMode: Flow<CoinJoinMode>
        get() = _mixingMode

    init {
        coinJoinConfig.observeMode()
            .filterNotNull()
            .onEach { _mixingMode.value = it }
            .launchIn(viewModelScope)
    }

    fun isWifiConnected(): Boolean {
        return networkState.isWifiConnected()
    }

    suspend fun setMode(mode: CoinJoinMode) {
        coinJoinConfig.setMode(mode)
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }
}
