/*
 * Copyright 2021 Dash Core Group.
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
package org.dash.wallet.integration.coinbase_integration.viewmodels

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.toFormattedStringNoCode
import org.dash.wallet.integration.coinbase_integration.model.*
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import org.dash.wallet.integration.coinbase_integration.utils.CoinbaseConfig
import javax.inject.Inject

@HiltViewModel
class CoinbaseConvertCryptoViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    private val userPreference: Configuration,
    private val config: CoinbaseConfig,
    private val walletDataProvider: WalletDataProvider,
    var exchangeRates: ExchangeRatesProvider,
    networkState: NetworkStateInt,
    private val analyticsService: AnalyticsService
) : ViewModel() {
    private val _showLoading: MutableLiveData<Boolean> = MutableLiveData()
    val showLoading: LiveData<Boolean>
        get() = _showLoading

    private val _baseIdForFaitModelCoinBase: MutableLiveData<List<BaseIdForUSDData>> = MutableLiveData()

    val swapTradeOrder = SingleLiveEvent<SwapTradeUIModel>()

    val swapTradeFailedCallback = SingleLiveEvent<String?>()

    private val _dashWalletBalance = MutableLiveData<Coin>()
    val dashWalletBalance: LiveData<Coin>
        get() = this._dashWalletBalance

    val isDeviceConnectedToInternet: LiveData<Boolean> = networkState.isConnected.asLiveData()

    var exchangeRate: ExchangeRate? = null
        private set

    init {
        getWithdrawalLimit()
        setDashWalletBalance()
    }

    fun setBaseIdForFaitModelCoinBase(list:List<BaseIdForUSDData>) {
        _baseIdForFaitModelCoinBase.value = list
    }

    fun swapTrade(
        valueToConvert: Fiat,
        selectedCoinBaseAccount: CoinBaseUserAccountDataUIModel,
        dashToCrypt: Boolean
    ) = viewModelScope.launch {
        _showLoading.value = true

        val sourceAsset =
            if (dashToCrypt) {
                _baseIdForFaitModelCoinBase.value?.firstOrNull { it.base == Constants.DASH_CURRENCY }?.base_id ?: ""
            } else {
                _baseIdForFaitModelCoinBase.value?.firstOrNull {
                    it.base == selectedCoinBaseAccount.coinBaseUserAccountData.currency?.code
                }?.base_id ?: ""
            }
        val targetAsset = if (dashToCrypt) {
            _baseIdForFaitModelCoinBase.value?.firstOrNull {
                it.base == selectedCoinBaseAccount.coinBaseUserAccountData.currency?.code
            }?.base_id ?: ""
        } else {
            _baseIdForFaitModelCoinBase.value?.firstOrNull { it.base == Constants.DASH_CURRENCY }?.base_id ?: ""
        }

        val tradesRequest = TradesRequest(
            valueToConvert.toFormattedStringNoCode(),
            userPreference.exchangeCurrencyCode!!,
            source_asset = sourceAsset,
            target_asset = targetAsset
        )

        when (val result = coinBaseRepository.swapTrade(tradesRequest)) {
            is ResponseResource.Success -> {
                if (result.value == SwapTradeResponse.EMPTY_SWAP_TRADE) {
                    _showLoading.value = false
                    swapTradeFailedCallback.call()
                } else {
                    _showLoading.value = false

                    result.value.apply {
                        this.assetsBaseID = Pair(sourceAsset, targetAsset)
                        this.inputCurrencyName = if (dashToCrypt) {
                            "Dash"
                        } else {
                            selectedCoinBaseAccount.coinBaseUserAccountData.currency?.name ?: ""
                        }
                        this.outputCurrencyName = if (dashToCrypt) {
                            selectedCoinBaseAccount.coinBaseUserAccountData.currency?.name ?: ""
                        } else {
                            "Dash"
                        }
                        swapTradeOrder.value = this
                    }
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false

                val error = result.errorBody
                if (error.isNullOrEmpty()) {
                    swapTradeFailedCallback.call()
                } else {
                    val message = CoinbaseErrorResponse.getErrorMessage(error)?.message
                    if (message.isNullOrEmpty()) {
                        swapTradeFailedCallback.call()
                    } else {
                        swapTradeFailedCallback.value = message
                    }
                }
            }
        }
    }

    suspend fun getUserWalletAccounts(dashToCrypt: Boolean): List<CoinBaseUserAccountDataUIModel> {
        analyticsService.logEvent(AnalyticsConstants.Coinbase.CONVERT_SELECT_COIN, mapOf())

        return when (
            val response = coinBaseRepository.getUserAccounts(userPreference.exchangeCurrencyCode!!)
        ) {
            is ResponseResource.Success -> response.value
            else -> listOf()
        }.filter {
            if (dashToCrypt) {
                isValidCoinBaseAccount(it)
            } else {
                isValidCoinBaseAccount(it) && it.coinBaseUserAccountData.balance?.amount?.toDouble() != 0.0
            }
        }.sortedBy { item -> item.coinBaseUserAccountData.currency?.code }
    }

    fun logEvent(eventName: String) {
        analyticsService.logEvent(eventName, mapOf())
    }

    suspend fun getLastBalance(): Coin {
        return Coin.valueOf(config.getPreference(CoinbaseConfig.LAST_BALANCE) ?: 0)
    }

    private fun isValidCoinBaseAccount(
        it: CoinBaseUserAccountDataUIModel
    ) = (
        it.coinBaseUserAccountData.balance?.amount?.toDouble() != null &&
            !it.coinBaseUserAccountData.balance.amount.toDouble().isNaN() &&
            it.coinBaseUserAccountData.type != "fiat" &&
            it.coinBaseUserAccountData.balance.currency != Constants.DASH_CURRENCY
        )

    private fun setDashWalletBalance() {
        _dashWalletBalance.value = walletDataProvider.getWalletBalance()
    }

    private fun getWithdrawalLimit() = viewModelScope.launch(Dispatchers.Main) {
        when (val response = coinBaseRepository.getWithdrawalLimit()) {
            is ResponseResource.Success -> {
                val withdrawalLimit = response.value
                exchangeRate = getCurrencyExchangeRate(withdrawalLimit.currency)
            }
            is ResponseResource.Failure -> {
                // todo use case when limit is not fetched
            }
        }
    }

    private suspend fun getCurrencyExchangeRate(currency: String): ExchangeRate? {
        return exchangeRates.observeExchangeRate(currency).first()
    }

    private val withdrawalLimitInDash: Double
        get() {
            return if (userPreference.coinbaseUserWithdrawalLimitAmount.isNullOrEmpty()) {
                0.0
            } else {
                val formattedAmount = GenericUtils.formatFiatWithoutComma(
                    userPreference.coinbaseUserWithdrawalLimitAmount
                )
                val fiatAmount = try {
                    Fiat.parseFiat(userPreference.coinbaseSendLimitCurrency, formattedAmount)
                } catch (x: Exception) {
                    Fiat.valueOf(userPreference.coinbaseSendLimitCurrency, 0)
                }
                exchangeRate?.fiat?.let {
                    val newRate = org.bitcoinj.utils.ExchangeRate(Coin.COIN, it)
                    val amountInDash = newRate.fiatToCoin(fiatAmount)
                    amountInDash.toPlainString().toDoubleOrZero
                } ?: 0.0
            }
        }

    fun isInputGreaterThanLimit(amountInDash: Coin): Boolean {
        return amountInDash.toPlainString().toDoubleOrZero.compareTo(withdrawalLimitInDash) > 0
    }
}
