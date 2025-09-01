/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.more

import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.CoinJoinService
import de.schildbach.wallet.service.MixingStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bitcoinj.core.Coin
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.toBigDecimal
import java.text.DecimalFormat
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val walletUIConfig: WalletUIConfig,
    private val coinJoinConfig: CoinJoinConfig,
    private val coinJoinService: CoinJoinService,
    walletDataProvider: WalletDataProvider,
    private val analytics: AnalyticsService,
) : ViewModel() {
    val hideBalance = walletUIConfig.observe(WalletUIConfig.AUTO_HIDE_BALANCE).filterNotNull()
    private val powerManager: PowerManager = walletApplication.getSystemService(PowerManager::class.java)

    private val _ignoringBatteryOptimizations = MutableStateFlow(isIgnoringBatteryOptimizations())
    val ignoringBatteryOptimizations = _ignoringBatteryOptimizations.asStateFlow()

    private fun isIgnoringBatteryOptimizations() = powerManager.isIgnoringBatteryOptimizations(walletApplication.packageName)
    fun updateIgnoringBatteryOptimizations() {
        _ignoringBatteryOptimizations.value = isIgnoringBatteryOptimizations()
    }
    val coinJoinMixingMode: Flow<CoinJoinMode>
        get() = coinJoinConfig.observeMode()
    private val _mixingProgress = MutableStateFlow(0.0)
    val mixingProgress = _mixingProgress.asStateFlow()

    private val _localCurrencySymbol = MutableStateFlow(Constants.USD_CURRENCY)
    val localCurrencySymbol = _localCurrencySymbol.asStateFlow()

    private val _coinJoinMixingStatus = MutableStateFlow(MixingStatus.NOT_STARTED)
    val coinJoinMixingStatus = _coinJoinMixingStatus.asStateFlow()
    //private var decimalFormat: DecimalFormat = DecimalFormat("0.000")
    private val _totalBalance = MutableStateFlow(Coin.ZERO)
    val totalBalance = _totalBalance.asStateFlow()
    //get() = decimalFormat.format(walletDataProvider.getWalletBalance().toBigDecimal())

    private val _mixedBalance = MutableStateFlow(Coin.ZERO)
    val mixedBalance =  _mixedBalance.asStateFlow()
    //get() = decimalFormat.format(walletDataProvider.getMixedBalance().toBigDecimal())

    init {
        coinJoinService.observeMixingState()
            .onEach { _coinJoinMixingStatus.value = it }
            .launchIn(viewModelScope)
        coinJoinService.observeMixingProgress()
            .onEach { _mixingProgress.value = it }
            .launchIn(viewModelScope)
        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .onEach { _localCurrencySymbol.value = it }
            .launchIn(viewModelScope)
        walletDataProvider.observeMixedBalance()
            .filterNotNull()
            .onEach { _mixedBalance.value = it }
            .launchIn(viewModelScope)
        walletDataProvider.observeTotalBalance()
            .filterNotNull()
            .onEach { _totalBalance.value = it }
            .launchIn(viewModelScope)
    }


    suspend fun shouldShowCoinJoinInfo(): Boolean {
        return coinJoinConfig.get(CoinJoinConfig.FIRST_TIME_INFO_SHOWN) != true
    }

    suspend fun setCoinJoinInfoShown() {
        coinJoinConfig.set(CoinJoinConfig.FIRST_TIME_INFO_SHOWN, true)
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }
}
