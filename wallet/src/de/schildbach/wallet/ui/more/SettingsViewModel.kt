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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.util.toBigDecimal
import java.text.DecimalFormat
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val walletUIConfig: WalletUIConfig,
    private val coinJoinConfig: CoinJoinConfig,
    private val coinJoinService: CoinJoinService,
    private val walletDataProvider: WalletDataProvider
) : ViewModel() {
    private val powerManager: PowerManager = walletApplication.getSystemService(PowerManager::class.java)

    val isIgnoringBatteryOptimizations: Boolean
        get() = powerManager.isIgnoringBatteryOptimizations(walletApplication.packageName)
    val voteDashPayIsEnabled = walletUIConfig.observe(WalletUIConfig.VOTE_DASH_PAY_ENABLED)
    val coinJoinMixingMode: Flow<CoinJoinMode>
        get() = coinJoinConfig.observeMode()

    var coinJoinMixingStatus: MixingStatus = MixingStatus.NOT_STARTED
    init {
        coinJoinService.observeMixingState()
            .onEach { coinJoinMixingStatus = it }
            .launchIn(viewModelScope)
    }

    var decimalFormat: DecimalFormat = DecimalFormat("0.000")
    val walletBalance: String
        get() = decimalFormat.format(walletDataProvider.wallet!!.getBalance(Wallet.BalanceType.ESTIMATED).toBigDecimal())

    val mixedBalance: String
        get() = decimalFormat.format((walletDataProvider.wallet as WalletEx).coinJoinBalance.toBigDecimal())

    fun setVoteDashPay(isEnabled: Boolean) {
        viewModelScope.launch {
            walletUIConfig.set(WalletUIConfig.VOTE_DASH_PAY_ENABLED, isEnabled)
        }
    }

    suspend fun shouldShowCoinJoinInfo(): Boolean {
        return coinJoinConfig.get(CoinJoinConfig.FIRST_TIME_INFO_SHOWN) != true
    }

    suspend fun setCoinJoinInfoShown() {
        coinJoinConfig.set(CoinJoinConfig.FIRST_TIME_INFO_SHOWN, true)
    }
}
