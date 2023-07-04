/*
 * Copyright 2021 Dash Core Group
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

package de.schildbach.wallet.ui.buy_sell

import androidx.core.os.bundleOf
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.data.BuyAndSellDashServicesModel
import de.schildbach.wallet.data.ServiceStatus
import de.schildbach.wallet.data.ServiceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.livedata.NetworkStateInt
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.ConnectivityViewModel
import org.dash.wallet.common.util.Constants
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepository
import org.dash.wallet.integration.coinbase_integration.utils.CoinbaseConfig
import org.dash.wallet.integration.uphold.api.TopperClient
import org.dash.wallet.integration.uphold.api.UpholdClient
import org.dash.wallet.integration.uphold.api.getDashBalance
import org.dash.wallet.integration.uphold.api.hasValidCredentials
import org.dash.wallet.integration.uphold.api.isAuthenticated
import javax.inject.Inject

/**
 * @author Eric Britten
 */
@HiltViewModel
class BuyAndSellViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepository,
    val config: Configuration,
    private val coinbaseConfig: CoinbaseConfig,
    val analytics: AnalyticsService,
    private val upholdClient: UpholdClient,
    private val topperClient: TopperClient,
    networkState: NetworkStateInt,
    exchangeRates: ExchangeRatesProvider,
    private val walletData: WalletDataProvider
): ConnectivityViewModel(networkState) {

    companion object {
        private const val ZERO_BALANCE = "0.0"
    }

    private var currentExchangeRate: org.dash.wallet.common.data.ExchangeRate? = null

    private val _servicesList = MutableStateFlow(BuyAndSellDashServicesModel.getBuyAndSellDashServicesList())
    val servicesList: StateFlow<List<BuyAndSellDashServicesModel>> = _servicesList.asStateFlow()

    val isUpholdAuthenticated: Boolean
        get() = upholdClient.isAuthenticated

    val isCoinbaseAuthenticated: Boolean
        get() = coinBaseRepository.isAuthenticated

    val hasValidCredentials: Boolean
        get() = upholdClient.hasValidCredentials

    init {
        exchangeRates.observeExchangeRate(config.exchangeCurrencyCode!!)
            .onEach { exchangeRate ->
                currentExchangeRate = exchangeRate

                showRowBalance(ServiceType.UPHOLD, (config.lastUpholdBalance ?: "").ifEmpty { ZERO_BALANCE })
                showRowBalance(ServiceType.COINBASE, coinbaseBalanceString())
            }
            .launchIn(viewModelScope)

        isDeviceConnectedToInternet.observeForever {
            updateServicesStatus()
            updateBalances()
        }
    }

    private fun setDashServiceList(list: List<BuyAndSellDashServicesModel>) {
        _servicesList.value = list.sortedBy { it.serviceStatus }
    }

    fun updateServicesStatus() {
        setDashServiceList(
            _servicesList.value.map { model ->
                val serviceStatus = getItemStatus(model.serviceType)
                if (serviceStatus != model.serviceStatus) {
                    model.copy(serviceStatus = serviceStatus)
                } else {
                    model
                }
            }
        )
    }

    fun updateBalances() {
        if (isDeviceConnectedToInternet.value == true) {
            if (upholdClient.isAuthenticated) {
                updateUpholdBalance()
            }

            if (coinBaseRepository.isUserConnected()) {
                updateCoinbaseBalance()
            }
        }
    }

    private fun getItemStatus(service: ServiceType): ServiceStatus {
        var isAuthenticated = false
        var hasValidCredentials = false

        when (service) {
            ServiceType.TOPPER -> {
                hasValidCredentials = upholdClient.hasValidCredentials // TODO
                isAuthenticated = false
            }
            ServiceType.UPHOLD -> {
                hasValidCredentials = upholdClient.hasValidCredentials
                isAuthenticated = upholdClient.isAuthenticated
            }
            ServiceType.COINBASE -> {
                hasValidCredentials = coinBaseRepository.hasValidCredentials
                isAuthenticated = coinBaseRepository.isAuthenticated
            }
        }

        if (!hasValidCredentials) {
            return ServiceStatus.IDLE_DISCONNECTED
        }

        val hasNetwork = isDeviceConnectedToInternet.value == true

        return if (isAuthenticated) {
            if (hasNetwork) ServiceStatus.CONNECTED else ServiceStatus.DISCONNECTED
        } else {
            if (hasNetwork) ServiceStatus.IDLE else ServiceStatus.IDLE_DISCONNECTED
        }
    }

    private fun showRowBalance(serviceType: ServiceType, amount: String) {
        val list = _servicesList.value.map { model ->
            if (model.serviceType == serviceType) {
                val balance = try {
                    Coin.parseCoin(amount)
                } catch (x: Exception) {
                    Coin.ZERO
                }

                val currentRate = currentExchangeRate

                if (currentRate == null) {
                    model.copy(balance = balance)
                } else {
                    val exchangeRate = ExchangeRate(Coin.COIN, currentRate.fiat)
                    val localValue = exchangeRate.coinToFiat(balance)
                    model.copy(balance = balance, localBalance = localValue)
                }
            } else {
                model
            }
        }
        setDashServiceList(list)
    }

    private fun updateCoinbaseBalance() {
        viewModelScope.launch {
            when (val response = coinBaseRepository.getUserAccount()) {
                is ResponseResource.Success -> {
                    response.value?.balance?.amount?.let {
                        coinbaseConfig.setPreference(CoinbaseConfig.LAST_BALANCE, Coin.parseCoin(it).value)
                    }
                    showRowBalance(ServiceType.COINBASE, response.value?.balance?.amount ?: coinbaseBalanceString())
                }
                is ResponseResource.Failure -> {
                    showRowBalance(ServiceType.COINBASE, coinbaseBalanceString())
                }
            }
        }
    }

    private fun updateUpholdBalance() {
        viewModelScope.launch {
            try {
                val balance = upholdClient.getDashBalance()
                config.lastUpholdBalance = balance.toString()
                showRowBalance(
                    ServiceType.UPHOLD,
                    balance.toString()
                )
            } catch (ex: Exception) {
                showRowBalance(ServiceType.UPHOLD, (config.lastUpholdBalance ?: "").ifEmpty { "0.0" })
            }
        }
    }

    fun logEnterUphold() {
        analytics.logEvent(
            if (upholdClient.isAuthenticated) {
                AnalyticsConstants.Uphold.ENTER_CONNECTED
            } else {
                AnalyticsConstants.Uphold.ENTER_DISCONNECTED
            },
            bundleOf()
        )
    }

    fun logEnterCoinbase() {
        analytics.logEvent(
            if (coinBaseRepository.isUserConnected()) {
                AnalyticsConstants.Coinbase.ENTER_CONNECTED
            } else {
                AnalyticsConstants.Coinbase.ENTER_DISCONNECTED
            },
            bundleOf()
        )
    }

    fun topperBuyUrl(walletName: String): String {
        return topperClient.getOnRampUrl(
            Constants.USD_CURRENCY,
            10.0,
            walletData.freshReceiveAddress(),
            walletName
        )
    }

    private suspend fun coinbaseBalanceString(): String =
        Coin.valueOf(coinbaseConfig.getPreference(CoinbaseConfig.LAST_BALANCE) ?: 0).toPlainString()
}
