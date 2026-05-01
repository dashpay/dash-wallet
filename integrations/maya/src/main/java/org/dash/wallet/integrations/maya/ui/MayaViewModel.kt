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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.isCurrencyFirst
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.common.util.toFiat
import org.dash.wallet.integrations.maya.api.FiatExchangeRateProvider
import org.dash.wallet.integrations.maya.api.MayaApi
import org.dash.wallet.integrations.maya.model.InboundAddress
import org.dash.wallet.integrations.maya.model.PoolInfo
import org.dash.wallet.integrations.maya.payments.MayaCurrencyList
import org.dash.wallet.integrations.maya.utils.MayaConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
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
        private val log: Logger = LoggerFactory.getLogger(MayaViewModel::class.java)
    }

    private var fiatFormat: MonetaryFormat = MonetaryFormat()
        .minDecimals(GenericUtils.getCurrencyDigits())
        .withLocale(Locale.getDefault())
        .noCode()

    val networkError = SingleLiveEvent<Unit>()

    //private var dashExchangeRate: org.bitcoinj.utils.ExchangeRate? = null
    private var fiatExchangeRate: Fiat? = null

    private val _uiState = MutableStateFlow(MayaPortalUIState())
    val uiState: StateFlow<MayaPortalUIState> = _uiState.asStateFlow()

    val dashFormat: MonetaryFormat
        get() = globalConfig.format.noCode()

    val poolList = MutableStateFlow<List<PoolInfo>>(listOf())
    private val _inboundAddresses = MutableStateFlow<List<InboundAddress>>(emptyList())
    val inboundAddresses: StateFlow<List<InboundAddress>> = _inboundAddresses.asStateFlow()
    val _exchangeRates = MutableStateFlow<List<ExchangeRate>>(listOf())
    val exchangeRates = _exchangeRates.asStateFlow()
    val hasHaltedCoins: StateFlow<Boolean> = inboundAddresses.map { addresses ->
        addresses.any { it.halted }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val paymentParsers = MayaCurrencyList.getPaymentProcessors()

    init {
        // TODO: is this really needed? we don't support DASH swaps
        exchangeRatesProvider.observeExchangeRates()
            .onEach {
                _exchangeRates.value = it
            }.launchIn(viewModelScope)

//        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
//            .filterNotNull()
//            .flatMapLatest(exchangeRatesProvider::observeExchangeRate)
//            .onEach { rate ->
//                dashExchangeRate = rate?.let {
//                    org.bitcoinj.utils.ExchangeRate(Coin.COIN, rate.fiat)
//                }
//                _uiState.update { it.copy() }
//            }
//            .launchIn(viewModelScope)

        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .onEach { log.info("exchange rate selected currency: {}", it) }
            .flatMapLatest(exchangeRatesProvider::observeExchangeRate)
            .filterNotNull()
            .onEach { fiatRate ->
                fiatFormat = fiatFormat.minDecimals(GenericUtils.getCurrencyDigits(fiatRate.currencyCode))
                fiatExchangeRate = fiatRate.fiat
                log.info("exchange rate: {}", fiatRate)
            }
            .flatMapLatest { fiatRate ->
                mayaApi.observePoolList(fiatRate.fiat).mapLatest { pools ->
                    pools to fiatRate.fiat
                }
            }
            .onEach { (newPoolList, dashFiat) ->
                applyPoolPrices(newPoolList, dashFiat)
                log.info(
                    "exchange rate Pool List: {}",
                    newPoolList.map { pool -> "${pool.asset}=${pool.assetPriceFiat.toFriendlyString()}" }
                )
                poolList.value = newPoolList
            }
            .launchIn(viewModelScope)

        updateInboundAddresses()
    }

    private suspend fun applyPoolPrices(pools: List<PoolInfo>, dashFiat: Fiat) {
        // Quote 10 DASH instead of 1 to amortize the fixed outbound fee — at 1 DASH
        // input the network fee can eat several percent of the output and inflate
        // the implied asset price.
        val quoteDashCount = 10L
        val quoteAtomicInput = Coin.COIN.value * quoteDashCount
        val quoteInputBd = BigDecimal(quoteAtomicInput)
        pools.forEach { pool ->
            if (pool.asset == "DASH.DASH") {
                pool.assetPriceFiat = dashFiat
                return@forEach
            }
            val quote = mayaApi.getDefaultSwapQuote(pool.asset, quoteAtomicInput)
                ?.takeIf { it.error == null }
            val expectedOut = quote?.expectedAmountOut?.toBigDecimalOrNull()
            if (quote == null || expectedOut == null || expectedOut.signum() <= 0) {
                log.info("no quote for {}: {}", pool.asset, quote?.error)
                return@forEach
            }
            // expectedAmountOut is post-fees; add fees.total back to recover the
            // gross swap rate so the implied price tracks the mid-market rate.
            val totalFees = quote.fees.total.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val grossOut = expectedOut + totalFees
            // quoteAtomicInput atoms of DASH -> grossOut atoms of asset
            // 1 asset = (quoteAtomicInput / grossOut) DASH; price in fiat = that ratio * dashFiat
            pool.assetPriceFiat = quoteInputBd
                .divide(grossOut, 8, RoundingMode.HALF_UP)
                .multiply(dashFiat.toBigDecimal())
                .toFiat(dashFiat.currencyCode)
        }
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

    fun getPoolInfo(currency: String): PoolInfo? {
        if (poolList.value.isNotEmpty()) {
            return poolList.value.find { it.currencyCode == currency }
        }
        return null
    }

    private fun updateInboundAddresses() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshInboundAddresses()
        }
    }

    suspend fun refreshInboundAddresses() {
        _inboundAddresses.value = mayaApi.getInboundAddresses()
    }

    fun getInboundAddress(asset: String): InboundAddress? {
        return if (inboundAddresses.value.isNotEmpty()) {
            val chain = asset.let { it.substring(0, it.indexOf('.')) }
            inboundAddresses.value.find { it.chain == chain }
        } else { null }
    }
}
