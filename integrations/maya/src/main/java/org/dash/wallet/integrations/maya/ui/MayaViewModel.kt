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

package org.dash.wallet.integrations.maya.ui

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.isCurrencyFirst
import org.dash.wallet.integrations.maya.api.FiatExchangeRateProvider
import org.dash.wallet.integrations.maya.api.MayaApi
import org.dash.wallet.integrations.maya.model.PoolInfo
import org.dash.wallet.integrations.maya.utils.MayaConfig
import org.slf4j.LoggerFactory
import java.util.Locale
import javax.inject.Inject

data class MayaPortalUIState(
    val errorCode: Int? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MayaViewModel @Inject constructor(
    private val globalConfig: Configuration,
    private val config: MayaConfig,
    private val mayaApi: MayaApi,
    private val fiatExchangeRateProvider: FiatExchangeRateProvider,
    exchangeRatesProvider: ExchangeRatesProvider,
    val analytics: AnalyticsService,
    walletUIConfig: WalletUIConfig
) : ViewModel() {
    companion object {
        val log = LoggerFactory.getLogger(MayaViewModel::class.java)
    }

    val fiatFormat = MonetaryFormat().minDecimals(2).withLocale(Locale.getDefault()).noCode()

    val networkError = SingleLiveEvent<Unit>()

    private var dashExchangeRate: org.bitcoinj.utils.ExchangeRate? = null
    private var fiatExchangeRate: Fiat? = null

    // val currencyList = MutableStateFlow<List<CryptoCurrencyItem>>(listOf<CryptoCurrencyItem>())

    private val _uiState = MutableStateFlow(MayaPortalUIState())
    val uiState: StateFlow<MayaPortalUIState> = _uiState.asStateFlow()

    val dashFormat: MonetaryFormat
        get() = globalConfig.format.noCode()

    val poolList = MutableStateFlow<List<PoolInfo>>(listOf())

    init {
        // TODO: is this really needed? we don't support DASH swaps
        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .flatMapLatest(exchangeRatesProvider::observeExchangeRate)
            .onEach { rate ->
                dashExchangeRate = rate?.let { org.bitcoinj.utils.ExchangeRate(Coin.COIN, rate.fiat) }
                _uiState.update { it.copy() }
            }
            .launchIn(viewModelScope)

        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .onEach { log.info("selected currency: {}", it) }
            .flatMapLatest(fiatExchangeRateProvider::observeFiatRate)
            .onEach {
                it?.let { fiatRate -> fiatExchangeRate = fiatRate.fiat }
                log.info("exchange rate: $it")
            }
            .flatMapLatest { mayaApi.observePoolList(it!!.fiat) }
            .onEach {
                log.info("exchange rate in view model: {}", fiatExchangeRate?.toFriendlyString())
                log.info("Pool List: {}", it)
                log.info("Pool List: {}", it.map { pool -> pool.assetPriceFiat })
                it.forEach { pool ->
                    pool.setAssetPrice(fiatExchangeRate!!)
                }
                poolList.value = it
            }
            .launchIn(viewModelScope)
    }

    fun formatFiat(fiatAmount: Fiat): String {
        val localCurrencySymbol = GenericUtils.getLocalCurrencySymbol(fiatAmount.currencyCode)

        val fiatBalance = fiatFormat.format(fiatAmount).toString()

        return if (fiatAmount.isCurrencyFirst()) {
            "$localCurrencySymbol $fiatBalance"
        } else {
            "$fiatBalance $localCurrencySymbol"
        }
    }
    fun errorHandled() {
        _uiState.update { it.copy(errorCode = null) }
    }

    fun logEvent(eventName: String) {
        analytics.logEvent(eventName, mapOf())
    }
}
