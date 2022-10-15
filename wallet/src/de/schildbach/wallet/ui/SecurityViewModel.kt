/*
 * Copyright 2022 Dash Core Group.
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


package de.schildbach.wallet.ui

import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.security.FingerprintHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bitcoinj.core.Coin
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.GenericUtils
import javax.inject.Inject

@ExperimentalCoroutinesApi
@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val exchangeRates: ExchangeRatesProvider,
    private val configuration: Configuration,
    private val walletData: WalletDataProvider,
    private val analytics: AnalyticsService,
    private val walletApplication: WalletApplication,
    val fingerprintHelper: FingerprintHelper
): ViewModel() {
    private var selectedExchangeRate: ExchangeRate? = null

    val currencyCode
        get() = configuration.exchangeCurrencyCode ?: Constants.DEFAULT_EXCHANGE_CURRENCY

    val needPassphraseBackUp
        get() = configuration.remindBackupSeed

    val balance: Coin
        get() = walletData.wallet?.getBalance(Wallet.BalanceType.ESTIMATED) ?: Coin.ZERO

    fun init() {
        exchangeRates.observeExchangeRate(currencyCode)
            .onEach { selectedExchangeRate = it }
            .launchIn(viewModelScope)
    }

    fun getBalanceInLocalFormat(): String {
        selectedExchangeRate?.fiat?.let {
            val exchangeRate = org.bitcoinj.utils.ExchangeRate(Coin.COIN, it)
            return GenericUtils.fiatToString(exchangeRate.coinToFiat(balance))
        }

        return ""
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, bundleOf())
    }

    fun triggerWipe() {
        walletApplication.triggerWipe()
    }
}