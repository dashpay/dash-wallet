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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.integration.coinbase_integration.CoinbaseConstants
import org.dash.wallet.integration.coinbase_integration.model.CoinBaseUserAccountDataUIModel
import org.dash.wallet.integration.coinbase_integration.ui.convert_currency.model.SwapRequest
import org.dash.wallet.integration.coinbase_integration.ui.convert_currency.model.SwapValueErrorType
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

enum class CurrencyInputType {
    Dash,
    Crypto,
    Fiat
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConvertViewViewModel @Inject constructor(
    var exchangeRates: ExchangeRatesProvider,
    private val walletUIConfig: WalletUIConfig,
    private val walletDataProvider: WalletDataProvider,
    private val analyticsService: AnalyticsService
) : ViewModel() {

    private val dashFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(6).optionalDecimals()

    private val _dashToCrypto = MutableLiveData<Boolean>()
    val dashToCrypto: LiveData<Boolean>
        get() = this._dashToCrypto

    var enteredConvertAmount = "0"
    var maxCoinBaseAccountAmount: String = "0"

    var minAllowedSwapAmount: String = CoinbaseConstants.MIN_USD_COINBASE_AMOUNT

    var maxForDashWalletAmount: String = "0"
    val onContinueEvent = SingleLiveEvent<SwapRequest>()

    var minAllowedSwapDashCoin: Coin = Coin.ZERO
    private var maxForDashCoinBaseAccount: Coin = Coin.ZERO

    private val _selectedCryptoCurrencyAccount = MutableLiveData<CoinBaseUserAccountDataUIModel?>()
    val selectedCryptoCurrencyAccount: LiveData<CoinBaseUserAccountDataUIModel?>
        get() = this._selectedCryptoCurrencyAccount

    var selectedPickerCurrencyCode: String = Constants.USD_CURRENCY

    private val _enteredConvertDashAmount = MutableLiveData<Coin>()
    val enteredConvertDashAmount: LiveData<Coin>
        get() = _enteredConvertDashAmount

    private val _enteredConvertCryptoAmount = MutableLiveData<Pair<String, String>>()
    val enteredConvertCryptoAmount: LiveData<Pair<String, String>>
        get() = _enteredConvertCryptoAmount

    var selectedLocalCurrencyCode: String = Constants.USD_CURRENCY
        private set

    private val _selectedLocalExchangeRate = MutableLiveData<ExchangeRate?>()
    val selectedLocalExchangeRate: LiveData<ExchangeRate?>
        get() = _selectedLocalExchangeRate

    val userDashAccountEmptyError = SingleLiveEvent<Unit>()

    val validSwapValue = SingleLiveEvent<String>()

    init {
        setDashWalletBalance()
        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .onEach { selectedLocalCurrencyCode = it }
            .flatMapLatest(exchangeRates::observeExchangeRate)
            .onEach(_selectedLocalExchangeRate::postValue)
            .launchIn(viewModelScope)

        viewModelScope.launch {
            walletUIConfig.get(WalletUIConfig.SELECTED_CURRENCY)?.let {
                selectedPickerCurrencyCode = it
            }
        }
    }

    fun setSelectedCryptoCurrency(account: CoinBaseUserAccountDataUIModel) {
        maxCoinBaseAccountAmount = account.coinBaseUserAccountData.balance?.amount ?: "0"

        this._selectedLocalExchangeRate.value = selectedLocalExchangeRate.value?.currencyCode?.let {
            val cleanedValue =
                1.toBigDecimal() /
                    account.currencyToDashExchangeRate.toBigDecimal()
            val bd = cleanedValue.setScale(8, RoundingMode.HALF_UP)
            ExchangeRate(
                it,
                bd.toString()
            )
        }
        this._selectedCryptoCurrencyAccount.value = account

        // To check if the user has different fiat than usd the min is 2 usd
        val minFaitValue = CoinbaseConstants.MIN_USD_COINBASE_AMOUNT.toBigDecimal() /
            account.currencyToUSDExchangeRate.toBigDecimal()

        val cleanedValue: BigDecimal =
            minFaitValue * account.currencyToDashExchangeRate.toBigDecimal()

        minAllowedSwapAmount = minFaitValue.toString()
        val bd = cleanedValue.setScale(8, RoundingMode.HALF_UP)

        val coin = try {
            Coin.parseCoin(bd.toString())
        } catch (x: Exception) {
            Coin.ZERO
        }

        minAllowedSwapDashCoin = coin

        val value =
            (maxCoinBaseAccountAmount.toBigDecimal() * account.cryptoCurrencyToDashExchangeRate.toBigDecimal())
                .setScale(8, RoundingMode.HALF_UP)

        val maxCoinValue = try {
            Coin.parseCoin(value.toString())
        } catch (x: Exception) {
            Coin.ZERO
        }

        maxForDashCoinBaseAccount = maxCoinValue
    }

    fun setEnteredConvertDashAmount(value: Coin) {
        _enteredConvertDashAmount.value = value
        if (!value.isZero) {
            _selectedCryptoCurrencyAccount.value?.let {
                val cryptoCurrency = (value.toBigDecimal() / it.cryptoCurrencyToDashExchangeRate.toBigDecimal())
                    .setScale(8, RoundingMode.HALF_UP).toString()

                _enteredConvertCryptoAmount.value =
                    Pair(cryptoCurrency, it.coinBaseUserAccountData.currency?.code.toString())
            }
        }

        if (value.isZero) {
            resetSwapValueError()
        }
    }

    fun resetSwapValueError() {
        validSwapValue.call()
    }

    fun checkEnteredAmountValue(checkSendingConditions: Boolean): SwapValueErrorType {
        val coin = try {
            if (dashToCrypto.value == true) {
                Coin.parseCoin(maxForDashWalletAmount)
            } else {
                maxForDashCoinBaseAccount
            }
        } catch (x: Exception) {
            Coin.ZERO
        }

        _enteredConvertDashAmount.value?.let {
            return when {
                it.isZero -> SwapValueErrorType.NOError
                (it == minAllowedSwapDashCoin || it.isGreaterThan(minAllowedSwapDashCoin)) &&
                    coin.isLessThan(minAllowedSwapDashCoin) -> SwapValueErrorType.NotEnoughBalance
                it.isLessThan(minAllowedSwapDashCoin) -> SwapValueErrorType.LessThanMin
                it.isGreaterThan(coin) -> SwapValueErrorType.MoreThanMax.apply {
                    amount = maxCoinBaseAccountAmount
                }
                checkSendingConditions && !doesMeetSendingConditions(it) -> {
                    SwapValueErrorType.SendingConditionsUnmet
                }
                else -> SwapValueErrorType.NOError
            }
        }
        return SwapValueErrorType.NOError
    }

    fun setOnSwapDashFromToCryptoClicked(dashToCrypto: Boolean) {
        if (dashToCrypto) {
            if (walletDataProvider.getWalletBalance().isZero) {
                userDashAccountEmptyError.call()
                return
            }
        }
        _dashToCrypto.value = dashToCrypto
    }

    fun clear() {
        _selectedCryptoCurrencyAccount.value = null
        _dashToCrypto.value = false
        _enteredConvertDashAmount.value = Coin.ZERO
        _enteredConvertCryptoAmount.value = Pair("", "")
    }

    fun continueSwap(pickedCurrencyOption: String) {
        viewModelScope.launch {
            analyticsService.logEvent(AnalyticsConstants.Coinbase.CONVERT_CONTINUE, mapOf())
            val currencyInputType = getCurrencyInputType(pickedCurrencyOption)
            val amount = getFiatAmount(currencyInputType)
            logEnteredAmountCurrency(currencyInputType)
            onContinueEvent.value = SwapRequest(
                dashToCrypto.value ?: false, // dash -> coinbase,
                amount.second,
                amount.first
            )
        }
    }

    private fun getFiatAmount(currencyInputType: CurrencyInputType): Pair<Fiat?, Coin?> {
        selectedCryptoCurrencyAccount.value?.let { account ->
            val fiatAmount = selectedLocalExchangeRate.value?.let { rate ->
                when (currencyInputType) {
                    CurrencyInputType.Crypto -> {
                        val cleanedValue = enteredConvertAmount.toBigDecimal() /
                            account.currencyToCryptoCurrencyExchangeRate.toBigDecimal()
                        val bd = cleanedValue.setScale(8, RoundingMode.HALF_UP)

                        Fiat.parseFiat(rate.fiat.currencyCode, bd.toString())
                    }

                    CurrencyInputType.Fiat -> {
                        Fiat.parseFiat(rate.fiat.currencyCode, enteredConvertAmount)
                    }

                    else -> {
                        val cleanedValue = enteredConvertAmount.toBigDecimal() /
                            account.currencyToDashExchangeRate.toBigDecimal()
                        val bd = cleanedValue.setScale(8, RoundingMode.HALF_UP)

                        Fiat.parseFiat(rate.fiat.currencyCode, bd.toString())
                    }
                }
            }

            val bd = toDashValue(enteredConvertAmount, account)
            val coin = try {
                Coin.parseCoin(bd.toString())
            } catch (x: Exception) {
                Coin.ZERO
            }
            return Pair(fiatAmount, coin)
        }

        return Pair(null, null)
    }

    fun toDashValue(
        valueToBind: String,
        userAccountData: CoinBaseUserAccountDataUIModel,
        fromCrypto: Boolean = false
    ): BigDecimal {
        val convertedValue = if (fromCrypto) {
            valueToBind.toBigDecimal() *
                userAccountData.cryptoCurrencyToDashExchangeRate.toBigDecimal()
        } else {
            valueToBind.toBigDecimal() *
                userAccountData.currencyToDashExchangeRate.toBigDecimal()
        }.setScale(8, RoundingMode.HALF_UP)
        return convertedValue
    }

    private fun setDashWalletBalance() {
        val balance = walletDataProvider.getWalletBalance()
        maxForDashWalletAmount = dashFormat.minDecimals(0)
            .optionalDecimals(0, 8).format(balance).toString()
    }

    private fun doesMeetSendingConditions(value: Coin): Boolean {
        if (dashToCrypto.value != true) {
            // No need to check
            return true
        }

        return try {
            walletDataProvider.checkSendingConditions(null, value)
            true
        } catch (ex: LeftoverBalanceException) {
            false
        }
    }

    private suspend fun getCurrencyInputType(currencyCode: String): CurrencyInputType {
        val code = currencyCode.lowercase()
        val account = selectedCryptoCurrencyAccount.value
        val currency = account?.coinBaseUserAccountData?.balance?.currency?.lowercase()

        return when {
            currency == Constants.DASH_CURRENCY.lowercase() -> CurrencyInputType.Dash
            currency == code -> CurrencyInputType.Crypto
            (walletUIConfig.get(WalletUIConfig.SELECTED_CURRENCY) ?: Constants.USD_CURRENCY)
                .lowercase() == code -> CurrencyInputType.Fiat
            else -> CurrencyInputType.Dash
        }
    }

    private fun logEnteredAmountCurrency(inputType: CurrencyInputType) {
        analyticsService.logEvent(
            when (inputType) {
                CurrencyInputType.Crypto -> AnalyticsConstants.Coinbase.CONVERT_ENTER_CRYPTO
                CurrencyInputType.Fiat -> AnalyticsConstants.Coinbase.CONVERT_ENTER_FIAT
                else -> AnalyticsConstants.Coinbase.CONVERT_ENTER_DASH
            },
            mapOf()
        )
    }

    override fun onCleared() {
        super.onCleared()
        _selectedCryptoCurrencyAccount.value = null
    }
}
