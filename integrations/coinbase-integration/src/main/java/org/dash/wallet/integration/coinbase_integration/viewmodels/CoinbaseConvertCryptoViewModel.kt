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

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.coinbase_integration.model.*
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepository
import javax.inject.Inject

@HiltViewModel
class CoinbaseConvertCryptoViewModel @Inject constructor(
    application: Application,
    private val exchangeRatesProvider: ExchangeRatesProvider,
    private val coinBaseRepository: CoinBaseRepository,
    val config: Configuration
) : AndroidViewModel(application) {
    private val _userAccountsInfo: MutableLiveData<List<CoinBaseUserAccountDataUIModel>> = MutableLiveData()
    val userAccountsInfo: LiveData<List<CoinBaseUserAccountDataUIModel>>
        get() = _userAccountsInfo

    private val _showLoading: MutableLiveData<Boolean> = MutableLiveData()
    val showLoading: LiveData<Boolean>
        get() = _showLoading

    private val _baseIdForUSDModelCoinBase: MutableLiveData<List<BaseIdForUSDData>> = MutableLiveData()
    val baseIdForUSDModelCoinBase: LiveData<List<BaseIdForUSDData>>
        get() = _baseIdForUSDModelCoinBase

    private val _swapTradeOrder: MutableLiveData<SwapTradeUIModel> = MutableLiveData()
    val swapTradeOrder: LiveData<SwapTradeUIModel>
        get() = _swapTradeOrder

    val swapTradeFailedCallback = SingleLiveEvent<String>()

    init {
        getUserAccountInfo()
        getBaseIdForUSDModel()
    }
    private fun getBaseIdForUSDModel() = viewModelScope.launch(Dispatchers.Main) {
        when (val response = coinBaseRepository.getBaseIdForUSDModel(config.exchangeCurrencyCode)) {
            is ResponseResource.Success -> {
                response.value?.data?.let {
                    _baseIdForUSDModelCoinBase.value = it
                }
            }

            is ResponseResource.Failure -> {
            }
        }
    }

    private fun getUserAccountInfo() = viewModelScope.launch(Dispatchers.Main) {
        _showLoading.value = true
        when (val response = coinBaseRepository.getUserAccounts(config.exchangeCurrencyCode)) {
            is ResponseResource.Success -> {
                _showLoading.value = false
                _userAccountsInfo.value = response.value
            }

            is ResponseResource.Failure -> {
                _showLoading.value = false
            }
        }
    }

    fun swapTrade(valueToConvert: Fiat, selectedCoinBaseAccount: CoinBaseUserAccountDataUIModel) = viewModelScope.launch(Dispatchers.Main) {
        _showLoading.value = true

        val source_asset =
            _baseIdForUSDModelCoinBase.value?.firstOrNull { it.base == selectedCoinBaseAccount.coinBaseUserAccountData.currency?.code }?.base_id ?: ""
        val target_asset =
            _baseIdForUSDModelCoinBase.value?.firstOrNull { it.base == "DASH" }?.base_id ?: ""

        val tradesRequest = TradesRequest(
            GenericUtils.fiatToStringWithoutCurrencyCode(valueToConvert),
            config.exchangeCurrencyCode,
            source_asset = source_asset!!,
            target_asset = target_asset!!
        )

        when (val result = coinBaseRepository.swapTrade(tradesRequest)) {
            is ResponseResource.Success -> {

                if (result.value == SwapTradeResponse.EMPTY_SWAP_TRADE) {
                    _showLoading.value = false
                    swapTradeFailedCallback.call()
                } else {
                    _showLoading.value = false

                    result.value.apply {
                        this.assetsBaseID = Pair(source_asset, target_asset)
                        this.inputCurrencyName =
                            selectedCoinBaseAccount.coinBaseUserAccountData.currency?.name ?: ""
                        this.outputCurrencyName = "Dash"
                        _swapTradeOrder.value = this
                    }
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false

                val error = result.errorBody?.string()
                if (error.isNullOrEmpty()) {
                    swapTradeFailedCallback.call()
                } else {
                    val message = CoinbaseErrorResponse.getErrorMessage(error)
                    if (message.isNullOrEmpty()) {
                        swapTradeFailedCallback.call()
                    } else {
                        swapTradeFailedCallback.value = message
                    }
                }
            }
        }
    }
}
