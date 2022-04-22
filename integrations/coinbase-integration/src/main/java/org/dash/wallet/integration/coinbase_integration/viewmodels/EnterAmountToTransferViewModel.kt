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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
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

    private var maxAmountInDashWalletFormatted : String = VALUE_ZERO
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
    val onContinueTransferEvent = SingleLiveEvent<Pair<Fiat, Coin>>()
    var isFiatSelected: Boolean = false
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

    private val _isTransferFromWalletToCoinbase = MutableStateFlow(false)
    val transferDirectionState: LiveData<Boolean>
        get() = _isTransferFromWalletToCoinbase.asLiveData()

    private val _dashBalanceInWallet = MutableStateFlow(walletDataProvider.getWalletBalance())
    val dashBalanceInWalletState: StateFlow<Coin>
        get() = _dashBalanceInWallet

    private val _localCurrencyExchangeRate = MutableLiveData<ExchangeRate>()
    val localCurrencyExchangeRate: LiveData<ExchangeRate>
        get() = _localCurrencyExchangeRate

    private val _enteredConvertDashAmount = MutableLiveData<Coin>()
    val enteredConvertDashAmount: LiveData<Coin>
        get() = _enteredConvertDashAmount

    private val _userAccountDataWithExchangeRate = MutableLiveData<CoinbaseToDashExchangeRateUIModel>()
    val userAccountOnCoinbaseState: LiveData<CoinbaseToDashExchangeRateUIModel>
        get() = _userAccountDataWithExchangeRate

    val dashWalletEmptyCallback = SingleLiveEvent<Unit>()

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
        maxAmountInDashWalletFormatted = dashFormat.minDecimals(0)
            .optionalDecimals(0, 8).format(dashBalanceInWalletState.value).toString()
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

    fun setOnTransferDirectionListener(walletToCoinbase: Boolean) {
        if (walletToCoinbase && dashBalanceInWalletState.value.isZero){
            dashWalletEmptyCallback.call()
            return
        }
        _isTransferFromWalletToCoinbase.value = walletToCoinbase
    }


    val maxValue: String
        get() {
            val amount = if (_isTransferFromWalletToCoinbase.value) {
                maxAmountInDashWalletFormatted
            } else {
                maxAmountCoinbaseAccount
            }
            val cleanedValue = if (isFiatSelected){
                applyCoinbaseExchangeRate(amount)
            } else amount

            return cleanedValue
        }

    val maxAmountCoinbaseAccount : String
        get() {
            return userAccountOnCoinbaseState.value?.let {
                it.coinBaseUserAccountData.balance?.amount ?: VALUE_ZERO
            } ?: VALUE_ZERO
        }

    fun applyCoinbaseExchangeRate(amount: String): String {
        return userAccountOnCoinbaseState.value?.let { uiModel ->
            val cleanedValue = amount.toBigDecimal() / uiModel.currencyToDashExchangeRate.toBigDecimal()
            cleanedValue.setScale(8, RoundingMode.HALF_UP).toPlainString()
        } ?: VALUE_ZERO
    }

    fun applyCoinbaseExchangeRateOnDash(amount: String): String {
        return userAccountOnCoinbaseState.value?.let { uiModel ->
            val cleanedValue = amount.toBigDecimal() * uiModel.currencyToDashExchangeRate.toBigDecimal()
            cleanedValue.setScale(8, RoundingMode.HALF_UP).toPlainString()
        } ?: VALUE_ZERO
    }


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

    val coinbaseExchangeRateAppliedOnInput: String
        get() = applyCoinbaseExchangeRate(inputValue)

    val coinbaseExchangeRateAppliedOnWalletBalance: String
        get() = applyCoinbaseExchangeRate(maxAmountInDashWalletFormatted)

    val formatInput: String
        get() {
            return if ((inputValue.toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO){
                if (isFiatSelected){
                    coinbaseExchangeRateAppliedOnInput
                } else {
                    applyCoinbaseExchangeRateToFiat
                }
            } else inputValue
        }


}