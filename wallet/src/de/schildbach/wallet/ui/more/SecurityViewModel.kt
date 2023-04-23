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

package de.schildbach.wallet.ui.more

import androidx.core.os.bundleOf
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.security.BiometricHelper
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bitcoinj.core.Coin
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.toFormattedString
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val exchangeRates: ExchangeRatesProvider,
    private val configuration: Configuration,
    private val walletData: WalletDataProvider,
    private val analytics: AnalyticsService,
    private val walletApplication: WalletApplication,
    val biometricHelper: BiometricHelper
): ViewModel() {
    private var selectedExchangeRate: ExchangeRate? = null

    val currencyCode
        get() = configuration.exchangeCurrencyCode ?: Constants.DEFAULT_EXCHANGE_CURRENCY

    val needPassphraseBackUp
        get() = configuration.remindBackupSeed

    val balance: Coin
        get() = walletData.wallet?.getBalance(Wallet.BalanceType.ESTIMATED) ?: Coin.ZERO

    private var _hideBalance = MutableLiveData(configuration.hideBalance)
    val hideBalance: LiveData<Boolean>
        get() = _hideBalance

    private var _fingerprintIsAvailable = MutableLiveData(false)
    val fingerprintIsAvailable: LiveData<Boolean>
        get() = _fingerprintIsAvailable

    private var _fingerprintIsEnabled = MutableLiveData(false)
    val fingerprintIsEnabled: LiveData<Boolean>
        get() = _fingerprintIsEnabled

    fun init() {
        exchangeRates.observeExchangeRate(currencyCode)
            .onEach { selectedExchangeRate = it }
            .launchIn(viewModelScope)

        _fingerprintIsAvailable.value = biometricHelper.isAvailable
        _fingerprintIsEnabled.value = biometricHelper.isEnabled
        configuration.enableFingerprint = biometricHelper.isEnabled
    }

    fun getBalanceInLocalFormat(): String {
        selectedExchangeRate?.fiat?.let {
            val exchangeRate = org.bitcoinj.utils.ExchangeRate(Coin.COIN, it)
            return exchangeRate.coinToFiat(balance).toFormattedString()
        }

        return ""
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }

    fun triggerWipe() {
        walletApplication.triggerWipe()
    }

    fun setEnableFingerprint(enable: Boolean) {
        val isEnabled = enable && biometricHelper.isEnabled
        _fingerprintIsEnabled.value = isEnabled

        if (configuration.enableFingerprint != isEnabled) {
            analytics.logEvent(
                if (isEnabled) {
                    AnalyticsConstants.Security.FINGERPRINT_ON
                } else {
                    AnalyticsConstants.Security.FINGERPRINT_OFF
                }, mapOf()
            )

            configuration.enableFingerprint = isEnabled

            if (!isEnabled) {
                biometricHelper.clearBiometricInfo()
            }
        }
    }

    fun setHideBalanceOnLaunch(hide: Boolean) {
        _hideBalance.value = hide

        if (configuration.hideBalance != hide) {
            analytics.logEvent(
                if (hide) {
                    AnalyticsConstants.Security.AUTOHIDE_BALANCE_ON
                } else {
                    AnalyticsConstants.Security.AUTOHIDE_BALANCE_OFF
                }, mapOf()
            )
            configuration.hideBalance = hide
        }
    }
}