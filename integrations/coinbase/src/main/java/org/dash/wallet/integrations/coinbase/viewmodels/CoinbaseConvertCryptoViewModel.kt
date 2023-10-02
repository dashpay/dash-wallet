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
package org.dash.wallet.integrations.coinbase.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.toFormattedStringNoCode
import org.dash.wallet.integrations.coinbase.model.BaseIdForUSDData
import org.dash.wallet.integrations.coinbase.model.CoinBaseUserAccountDataUIModel
import org.dash.wallet.integrations.coinbase.model.CoinbaseErrorResponse
import org.dash.wallet.integrations.coinbase.model.SwapTradeResponse
import org.dash.wallet.integrations.coinbase.model.SwapTradeUIModel
import org.dash.wallet.integrations.coinbase.model.TradesRequest
import org.dash.wallet.integrations.coinbase.repository.CoinBaseRepositoryInt
import org.dash.wallet.integrations.coinbase.utils.CoinbaseConfig
import javax.inject.Inject

@HiltViewModel
class CoinbaseConvertCryptoViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    private val config: CoinbaseConfig,
    private val walletUIConfig: WalletUIConfig,
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

    init {
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
                    it.base == selectedCoinBaseAccount.coinbaseAccount.currency
                }?.base_id ?: ""
            }
        val targetAsset = if (dashToCrypt) {
            _baseIdForFaitModelCoinBase.value?.firstOrNull {
                it.base == selectedCoinBaseAccount.coinbaseAccount.currency
            }?.base_id ?: ""
        } else {
            _baseIdForFaitModelCoinBase.value?.firstOrNull { it.base == Constants.DASH_CURRENCY }?.base_id ?: ""
        }

        val tradesRequest = TradesRequest(
            valueToConvert.toFormattedStringNoCode(),
            walletUIConfig.get(WalletUIConfig.SELECTED_CURRENCY) ?: Constants.DEFAULT_EXCHANGE_CURRENCY,
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
                            selectedCoinBaseAccount.coinbaseAccount.currency
                        }
                        this.outputCurrencyName = if (dashToCrypt) {
                            selectedCoinBaseAccount.coinbaseAccount.currency
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

    suspend fun getUserWalletAccounts(dashToCrypto: Boolean): List<CoinBaseUserAccountDataUIModel> {
        analyticsService.logEvent(AnalyticsConstants.Coinbase.CONVERT_SELECT_COIN, mapOf())

        return try {
            coinBaseRepository.getUserAccounts(walletUIConfig.getExchangeCurrencyCode())
        } catch (ex: Exception) {
            listOf()
        }.filter {
            if (dashToCrypto) {
                isValidCoinBaseAccount(it)
            } else {
                isValidCoinBaseAccount(it) && it.coinbaseAccount.availableBalance.value.toDouble() != 0.0
            }
        }.sortedBy { item -> item.coinbaseAccount.currency }
    }

    fun logEvent(eventName: String) {
        analyticsService.logEvent(eventName, mapOf())
    }

    suspend fun getLastBalance(): Coin {
        return Coin.valueOf(config.get(CoinbaseConfig.LAST_BALANCE) ?: 0)
    }

    private fun isValidCoinBaseAccount(it: CoinBaseUserAccountDataUIModel) = (
        !it.coinbaseAccount.availableBalance.value.toDouble().isNaN() &&
            it.coinbaseAccount.type != "fiat" &&
            it.coinbaseAccount.currency != Constants.DASH_CURRENCY
        )

    private fun setDashWalletBalance() {
        _dashWalletBalance.value = walletDataProvider.getWalletBalance()
    }

    suspend fun isInputGreaterThanLimit(amountInDash: Coin): Boolean {
        val withdrawalLimitInDash = coinBaseRepository.getWithdrawalLimitInDash()
        return amountInDash.toPlainString().toDoubleOrZero.compareTo(withdrawalLimitInDash) > 0
    }
}
