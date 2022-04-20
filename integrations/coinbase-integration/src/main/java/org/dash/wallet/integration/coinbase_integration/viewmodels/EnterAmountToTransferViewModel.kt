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

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.Constants
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.coinbase_integration.VALUE_ZERO
import org.dash.wallet.integration.coinbase_integration.model.CoinbaseToDashExchangeRateUIModel
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import javax.inject.Inject

@ExperimentalCoroutinesApi
@HiltViewModel
class EnterAmountToTransferViewModel@Inject constructor(
    var exchangeRates: ExchangeRatesProvider,
    var configuration: Configuration,
    private val walletDataProvider: WalletDataProvider,
    private val coinBaseRepository: CoinBaseRepositoryInt
) : ViewModel() {

    var maxAmountInDashWallet = VALUE_ZERO
    private val dashFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(6).optionalDecimals()
    val decimalSeparator =
        DecimalFormatSymbols.getInstance(GenericUtils.getDeviceLocale()).decimalSeparator
    private val format = Constants.SEND_PAYMENT_LOCAL_FORMAT.noCode()
    var fiatAmount: Fiat? = null
    var fiatBalance: String = ""
    var inputValue: String = VALUE_ZERO
    var isMaxAmountSelected: Boolean = false
    var formattedValue: String = ""
    val onContinueTransferEvent = SingleLiveEvent<Pair<Boolean, Pair<Fiat?, Coin?>>>()
    var isFiatSelected: Boolean = true
        set(value) {
            if (field != value){
                field = value
            }
        }

    private val _localCurrencyCode = MutableStateFlow(configuration.exchangeCurrencyCode)
    var localCurrencyCode: String
        get() = _localCurrencyCode.value
        set(value) {
            _localCurrencyCode.value = value
        }

    private val _isTransferFromWalletToCoinbase = MutableLiveData<Boolean>()
    val transferDirectionState: LiveData<Boolean>
        get() = _isTransferFromWalletToCoinbase

    private val _localCurrencyExchangeRate = MutableLiveData<ExchangeRate>()
    val localCurrencyExchangeRate: LiveData<ExchangeRate>
        get() = _localCurrencyExchangeRate

    private val _enteredConvertDashAmount = MutableLiveData<Coin>()
    val enteredConvertDashAmount: LiveData<Coin>
        get() = _enteredConvertDashAmount

    private val _userAccountDataWithExchangeRate = MutableLiveData<CoinbaseToDashExchangeRateUIModel>()
    val userAccountOnCoinbaseState: LiveData<CoinbaseToDashExchangeRateUIModel>
        get() = _userAccountDataWithExchangeRate

    fun setEnteredConvertDashAmount(value: Coin){
        _enteredConvertDashAmount.value = value
    }

    init {
        setDashWalletBalance()
        getUserData()
        _localCurrencyCode.flatMapLatest { code ->
            exchangeRates.observeExchangeRate(code)
        }.onEach(_localCurrencyExchangeRate::postValue)
            .launchIn(viewModelScope)
    }

    private fun getUserData() = viewModelScope.launch(Dispatchers.Main){
        when(val response = coinBaseRepository.getExchangeRateFromCoinbase()){
            is ResponseResource.Success -> {
                _userAccountDataWithExchangeRate.value = response.value!!
            }

            is ResponseResource.Failure -> {

            }
        }
    }

    private fun setDashWalletBalance() {
        maxAmountInDashWallet = dashFormat.minDecimals(0)
            .optionalDecimals(0, 8).format(walletBalance).toString()
    }

    fun applyNewValue(value: String, monetaryCode: String): String {
        inputValue = value.ifEmpty { VALUE_ZERO }
        val isFraction = inputValue.indexOf(decimalSeparator) > -1
        val lengthOfDecimalPart = inputValue.length - inputValue.indexOf(decimalSeparator)

        return if (localCurrencyCode == monetaryCode){
            val cleanedValue = GenericUtils.formatFiatWithoutComma(inputValue)
            fiatAmount = Fiat.parseFiat(localCurrencyCode, cleanedValue)
            val localCurrencySymbol = GenericUtils.getLocalCurrencySymbol(localCurrencyCode)

            fiatBalance = if (isFraction && lengthOfDecimalPart > 2) {
                format.format(fiatAmount).toString()
            } else {
                inputValue
            }
            if (GenericUtils.isCurrencyFirst(fiatAmount)) {
                "$localCurrencySymbol $fiatBalance"
            } else {
                "$fiatBalance $localCurrencySymbol"
            }
        } else {
            formattedValue = if (inputValue.contains("E")) {
                DecimalFormat("########.########").format(inputValue.toDouble())
            } else {
                inputValue
            }
            "$formattedValue $monetaryCode"
        }
    }

    val hasBalance: Boolean
        get() = inputValue.isNotEmpty() &&
                    (inputValue.toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO

    fun convertToDash() {
        val dashAmount = if (isFiatSelected){
            userAccountOnCoinbaseState.value?.let {
                val cleanedValue = inputValue.toBigDecimal() * it.currencyToDashExchangeRate.toBigDecimal()
                val plainValue = cleanedValue.setScale(8, RoundingMode.HALF_UP).toPlainString()
                try {
                    Coin.parseCoin(plainValue)
                } catch (x: Exception) {
                    Coin.ZERO
                }
            } ?: Coin.ZERO
        } else {
            val formattedValue = GenericUtils.formatFiatWithoutComma(inputValue)
            try {
                Coin.parseCoin(formattedValue)
            } catch (x: Exception) {
                Coin.ZERO
            }
        }
        setEnteredConvertDashAmount(dashAmount)
    }

    private val walletBalance = walletDataProvider.getWalletBalance()

    val maxValue: String
        get() {
            return if (transferDirectionState.value == true) {
                userAccountOnCoinbaseState.value?.let { uiModel ->
                    val cleanedValue = maxAmountInDashWallet.toBigDecimal() / uiModel.currencyToDashExchangeRate.toBigDecimal()
                    cleanedValue.setScale(8, RoundingMode.HALF_UP).toPlainString()
                } ?: VALUE_ZERO
            } else maxAmountCoinbaseAccount
        }

    val maxAmountCoinbaseAccount : String
        get() {
            return userAccountOnCoinbaseState.value?.let {
                it.coinBaseUserAccountData.balance?.amount ?: VALUE_ZERO
            } ?: VALUE_ZERO
        }

    val isInputNull: Boolean
        get() = inputValue.toBigDecimalOrNull() ?: BigDecimal.ZERO > BigDecimal.ZERO

    val applyCoinbaseExchangeRateToFiat: String
        get() {
            return userAccountOnCoinbaseState.value?.let {
                val cleanedValue = inputValue.toBigDecimal() * it.currencyToDashExchangeRate.toBigDecimal()
                val plainValue = cleanedValue.setScale(8, RoundingMode.HALF_UP).toPlainString()
                val dashAmount = try {
                    Coin.parseCoin(plainValue)
                } catch (x: Exception) {
                    Coin.ZERO
                }

                return if (dashAmount.isZero) VALUE_ZERO.toBigDecimal().toPlainString() else plainValue
            } ?: VALUE_ZERO.toBigDecimal().toPlainString()
        }

    val applyCoinbaseExchangeRateToDash: String
        get() {
            return userAccountOnCoinbaseState.value?.let { uiModel ->
                val cleanedValue = inputValue.toBigDecimal() / uiModel.currencyToDashExchangeRate.toBigDecimal()
                cleanedValue.setScale(8, RoundingMode.HALF_UP).toPlainString()
            } ?: VALUE_ZERO.toBigDecimal().toPlainString()
        }


    val formatInput: String
        get() {
            return if (isInputNull){
                inputValue
            } else {
                if (isFiatSelected) {
                    applyCoinbaseExchangeRateToFiat
                } else {
                    applyCoinbaseExchangeRateToDash
                }
            }
        }


}