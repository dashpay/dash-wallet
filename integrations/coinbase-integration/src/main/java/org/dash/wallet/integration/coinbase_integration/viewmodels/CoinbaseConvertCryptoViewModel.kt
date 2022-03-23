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
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.livedata.Event
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.coinbase_integration.DASH_CURRENCY
import org.dash.wallet.integration.coinbase_integration.model.*
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import java.util.*
import javax.inject.Inject

@HiltViewModel
class CoinbaseConvertCryptoViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    val config: Configuration,
    private val walletDataProvider: WalletDataProvider,
    private val sendPaymentService: SendPaymentService
) : ViewModel() {
    private val _userAccountsInfo: MutableLiveData<List<CoinBaseUserAccountDataUIModel>> = MutableLiveData()
    val userAccountsInfo: LiveData<List<CoinBaseUserAccountDataUIModel>>
        get() = _userAccountsInfo

    private val _showLoading: MutableLiveData<Boolean> = MutableLiveData()
    val showLoading: LiveData<Boolean>
        get() = _showLoading

    private val _baseIdForUSDModelCoinBase: MutableLiveData<List<BaseIdForUSDData>> = MutableLiveData()
    val baseIdForUSDModelCoinBase: LiveData<List<BaseIdForUSDData>>
        get() = _baseIdForUSDModelCoinBase

    private val _swapTradeOrder: MutableLiveData<Event<SwapTradeUIModel>> = MutableLiveData()
    val swapTradeOrder: LiveData<Event<SwapTradeUIModel>>
        get() = _swapTradeOrder

    val swapTradeFailedCallback = SingleLiveEvent<String>()

    private val _userAccountsWithBalance: MutableLiveData<Event<List<CoinBaseUserAccountDataUIModel>>> = MutableLiveData()
    val userAccountsWithBalance: LiveData<Event<List<CoinBaseUserAccountDataUIModel>>>
        get() = _userAccountsWithBalance

    private val _userAccountError: SingleLiveEvent<Boolean> = SingleLiveEvent()
    val userAccountError: LiveData<Boolean>
        get() = _userAccountError

    private val _dashWalletBalance = MutableLiveData<Coin>()
    val dashWalletBalance: LiveData<Coin>
        get() = this._dashWalletBalance

    val getUserAccountAddressFailedCallback = SingleLiveEvent<Unit>()
    val onFailure = SingleLiveEvent<String>()
    val onInsufficientMoneyCallback = SingleLiveEvent<Unit>()
    val sendDashToCoinBaseFailed = SingleLiveEvent<Unit>()
    init {
        setDashWalletBalance()
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


    fun sellDashToCoinBase(coin: Coin, valueToConvert: Fiat, selectedCoinBaseAccount: CoinBaseUserAccountDataUIModel) = viewModelScope.launch(Dispatchers.Main) {
        _showLoading.value = true


        when (val result = coinBaseRepository.createAddress()) {
            is ResponseResource.Success -> {
                if (result.value?.isEmpty() == true) {
                    _showLoading.value = false
                    getUserAccountAddressFailedCallback.call()
                } else {
                    result.value?.let {
                        if (sendDashToCoinbase(coin, result.value)) {
                            swapTrade(valueToConvert, selectedCoinBaseAccount, true)
                        } else {
                            _showLoading.value = false
                            sendDashToCoinBaseFailed.call()
                        }
                    }
                    _showLoading.value = false
                }
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
                getUserAccountAddressFailedCallback.call()
            }
        }
    }

    private suspend fun sendDashToCoinbase(coin: Coin, addressInfo: String): Boolean {
        val address = walletDataProvider.createSentDashAddress(addressInfo)
        try {
            val transaction = sendPaymentService.sendCoins(address, coin)
            return transaction.isPending
        } catch (x: InsufficientMoneyException) {
            onInsufficientMoneyCallback.call()
            x.printStackTrace()
            return false
        } catch (ex: Exception) {
            onFailure.value = ex.message
            ex.printStackTrace()
            return false
        }
    }

    fun swapTrade(valueToConvert: Fiat, selectedCoinBaseAccount: CoinBaseUserAccountDataUIModel, dashToCrypt: Boolean) = viewModelScope.launch(Dispatchers.Main) {
        _showLoading.value = true

        val source_asset =
            if (dashToCrypt)_baseIdForUSDModelCoinBase.value?.firstOrNull { it.base == DASH_CURRENCY }?.base_id ?: ""
            else _baseIdForUSDModelCoinBase.value?.firstOrNull { it.base == selectedCoinBaseAccount.coinBaseUserAccountData.currency?.code }?.base_id ?: ""
        val target_asset = if (dashToCrypt)_baseIdForUSDModelCoinBase.value?.firstOrNull { it.base == selectedCoinBaseAccount.coinBaseUserAccountData.currency?.code }?.base_id ?: ""
        else
            _baseIdForUSDModelCoinBase.value?.firstOrNull { it.base == DASH_CURRENCY }?.base_id ?: ""

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
                        this.inputCurrencyName = if (dashToCrypt)"Dash"
                        else
                            selectedCoinBaseAccount.coinBaseUserAccountData.currency?.name ?: ""
                        this.outputCurrencyName = if (dashToCrypt) selectedCoinBaseAccount.coinBaseUserAccountData.currency?.name ?: ""
                        else
                            "Dash"
                        _swapTradeOrder.value = Event(this)
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
                        swapTradeFailedCallback.value = message!!
                    }
                }
            }
        }
    }

    fun getUserWalletAccounts(dashToCrypt: Boolean) {
        val userAccountsWithBalanceList =
            if (dashToCrypt) {
                _userAccountsInfo.value?.filter {
                    isValidCoinBaseAccount(it)
                }
            } else {
                _userAccountsInfo.value?.filter {
                    isValidCoinBaseAccount(it) && it.coinBaseUserAccountData.balance?.amount?.toDouble() != 0.0
                }
            }

        if (userAccountsWithBalanceList.isNullOrEmpty()) {
            _userAccountError.value = true
        } else {
            _userAccountsWithBalance.value = Event(userAccountsWithBalanceList)
        }
    }

    private fun isValidCoinBaseAccount(
        it: CoinBaseUserAccountDataUIModel
    ) = (
        it.coinBaseUserAccountData.balance?.amount?.toDouble() != null &&
            !it.coinBaseUserAccountData.balance.amount.toDouble().isNaN() &&
            it.coinBaseUserAccountData.type != "fiat" &&
            it.coinBaseUserAccountData.balance.currency != DASH_CURRENCY
        )

    private fun setDashWalletBalance() {
        _dashWalletBalance.value = walletDataProvider.getWalletBalance()
    }
}
