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
import kotlinx.coroutines.flow.*
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.util.*
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.common.util.toFiat
import org.dash.wallet.integration.coinbase_integration.CoinbaseConstants
import org.dash.wallet.integration.coinbase_integration.model.CoinbaseToDashExchangeRateUIModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import javax.inject.Inject

@ExperimentalCoroutinesApi
@HiltViewModel
class EnterAmountToTransferViewModel @Inject constructor(
    var exchangeRates: ExchangeRatesProvider,
    var configuration: Configuration,
    walletDataProvider: WalletDataProvider,
    var blockchainStateProvider: BlockchainStateProvider
) : ViewModel() {

    var coinbaseExchangeRate: CoinbaseToDashExchangeRateUIModel? = null
    private var maxAmountInDashWalletFormatted: String = CoinbaseConstants.VALUE_ZERO
    private val dashFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(6).optionalDecimals()
    val decimalSeparator =
        DecimalFormatSymbols.getInstance(GenericUtils.getDeviceLocale()).decimalSeparator
    private val format = Constants.SEND_PAYMENT_LOCAL_FORMAT.noCode()

    var fiatAmount: Fiat? = null
    var fiatBalance: String = ""
    var inputValue: String = CoinbaseConstants.VALUE_ZERO
    var isMaxAmountSelected: Boolean = false
    var formattedValue: String = ""
    val onContinueTransferEvent = SingleLiveEvent<Pair<Fiat, Coin>>()
    var isFiatSelected: Boolean = false
        set(value) {
            if (field != value) {
                field = value
            }
        }

    private val _localCurrencyCode = MutableStateFlow(configuration.exchangeCurrencyCode!!)
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

    private val _localCurrencyExchangeRate = MutableLiveData<ExchangeRate?>()
    val localCurrencyExchangeRate: LiveData<ExchangeRate?>
        get() = _localCurrencyExchangeRate

    private val _enteredConvertDashAmount = MutableLiveData<Pair<Fiat, Coin>>()
    val enteredConvertDashAmount: LiveData<Pair<Fiat, Coin>>
        get() = _enteredConvertDashAmount

    private val _isBlockchainSynced = MutableLiveData<Boolean>()
    val isBlockchainSynced: LiveData<Boolean>
        get() = _isBlockchainSynced

    private val _isBlockchainSyncFailed = MutableLiveData<Boolean>()
    val isBlockchainSyncFailed: LiveData<Boolean>
        get() = _isBlockchainSyncFailed

    val dashWalletEmptyCallback = SingleLiveEvent<Unit>()
    val removeBannerCallback = SingleLiveEvent<Unit>()
    val keyboardStateCallback = MutableLiveData<Boolean>()

    init {
        setDashWalletBalance()
        _localCurrencyCode.flatMapLatest { code ->
            exchangeRates.observeExchangeRate(code)
        }.onEach(_localCurrencyExchangeRate::postValue)
            .launchIn(viewModelScope)

        blockchainStateProvider.observeState()
            .filterNotNull()
            .onEach { state ->
                updateSyncStatus(state)
            }
            .launchIn(viewModelScope)
    }

    private fun updateSyncStatus(state: BlockchainState) {
        if (_isBlockchainSyncFailed.value != state.isSynced()) {
            _isBlockchainSynced.postValue(state.isSynced())
        }

        _isBlockchainSyncFailed.postValue(state.syncFailed())
    }

    private fun setDashWalletBalance() {
        maxAmountInDashWalletFormatted = dashFormat.minDecimals(0)
            .optionalDecimals(0, 8).format(dashBalanceInWalletState.value).toString()
    }

    fun applyNewValue(value: String, monetaryCode: String): String {
        inputValue = value.ifEmpty { CoinbaseConstants.VALUE_ZERO }
        val isFraction = inputValue.indexOf(decimalSeparator) > -1
        val lengthOfDecimalPart = inputValue.length - inputValue.indexOf(decimalSeparator)

        return if (localCurrencyCode == monetaryCode) {
            val cleanedValue = GenericUtils.formatFiatWithoutComma(inputValue)
            fiatAmount = Fiat.parseFiat(localCurrencyCode, cleanedValue)
            val localCurrencySymbol = GenericUtils.getLocalCurrencySymbol(localCurrencyCode)

            fiatBalance = if (isFraction && lengthOfDecimalPart > 2) {
                format.format(fiatAmount).toString()
            } else {
                inputValue
            }
            if (fiatAmount!!.isCurrencyFirst()) {
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

    fun setOnTransferDirectionListener(walletToCoinbase: Boolean) {
        // there must be a non-zero balance
        if (!walletToCoinbase && dashBalanceInWalletState.value.isZero) {
            dashWalletEmptyCallback.call()
            return
        }
        _isTransferFromWalletToCoinbase.value = !walletToCoinbase
    }

    val maxValue: String
        get() {
            val amount = if (_isTransferFromWalletToCoinbase.value) {
                maxAmountInDashWalletFormatted
            } else {
                maxAmountCoinbaseAccount
            }
            val cleanedValue = if (isFiatSelected) {
                applyCoinbaseExchangeRate(amount)
            } else {
                amount
            }

            return cleanedValue
        }

    private val maxAmountCoinbaseAccount: String
        get() {
            return coinbaseExchangeRate?.let {
                it.coinBaseUserAccountData.balance?.amount
            } ?: CoinbaseConstants.VALUE_ZERO
        }

    private fun applyCoinbaseExchangeRate(amount: String): String {
        return coinbaseExchangeRate?.let { uiModel ->
            val cleanedValue = amount
                .replace(',', '.') // TODO: the amount sometimes comes here with a comma as decimal separator.
                // TODO: it's better to identify the root of this and replace in there to prevent this problem from appearing anywhere else.
                .toBigDecimal() / uiModel.currencyToDashExchangeRate.toBigDecimal()
            cleanedValue.setScale(8, RoundingMode.HALF_UP).toPlainString()
        } ?: CoinbaseConstants.VALUE_ZERO
    }

    fun applyExchangeRateToFiat(fiatValue: Fiat): Coin {
        return coinbaseExchangeRate?.let {
            val cleanedValue =
                fiatValue.toBigDecimal() * it.currencyToDashExchangeRate.toBigDecimal()
            val plainValue = cleanedValue.setScale(8, RoundingMode.HALF_UP).toPlainString()
            try {
                Coin.parseCoin(plainValue)
            } catch (x: Exception) {
                Coin.ZERO
            }
        } ?: Coin.ZERO
    }

    private val applyCoinbaseExchangeRateToFiat: String
        get() {
            val cleanedValue = scaleValue(inputValue)
            val dashAmount = toCoin(cleanedValue)

            return if (dashAmount.isZero) {
                CoinbaseConstants.VALUE_ZERO.toBigDecimal()
                    .toPlainString()
            } else {
                cleanedValue
            }
        }

    private val amountInDash: Coin
        get() {
            val scaledValue = scaleValue(inputValue)
            return if (scaledValue.isEmpty()) {
                Coin.ZERO
            } else {
                toCoin(scaledValue)
            }
        }

    private val coinbaseExchangeRateAppliedOnInput: String
        get() = applyCoinbaseExchangeRate(inputValue)

    val formatInput: String
        get() {
            return if ((inputValue.toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO) {
                if (isFiatSelected) {
                    coinbaseExchangeRateAppliedOnInput
                } else {
                    applyCoinbaseExchangeRateToFiat
                }
            } else {
                inputValue
            }
        }

    fun setBalanceForWallet() {
        if (hasBalance) {
            val dashAmt = if (isFiatSelected) {
                amountInDash
            } else {
                toCoin(inputValue)
            }
            val formatDash = dashFormat.format(dashAmt).toString()
            val rateApplied = applyCoinbaseExchangeRate(formatDash)
            val fiatAmt = Fiat.parseFiat(localCurrencyCode, rateApplied)

            _enteredConvertDashAmount.value = Pair(fiatAmt, dashAmt)
        } else {
            _enteredConvertDashAmount.value =
                Pair(Fiat.parseFiat(localCurrencyCode, CoinbaseConstants.VALUE_ZERO), Coin.ZERO)
        }
    }

    fun getCoinbaseBalanceInFiatFormat(dashAmt: String): String = getFiat(dashAmt).toFormattedString()

    fun getExchangeRate(): org.bitcoinj.utils.ExchangeRate? {
        return coinbaseExchangeRate?.let {
            val rate = BigDecimal.ONE.divide(it.currencyToDashExchangeRate.toBigDecimal(), 10, RoundingMode.HALF_UP)
            org.bitcoinj.utils.ExchangeRate(rate.toFiat(localCurrencyCode))
        }
    }

    private fun scaleValue(valueToScale: String): String {
        return coinbaseExchangeRate?.let {
            val cleanedValue = valueToScale.toBigDecimal() * it.currencyToDashExchangeRate.toBigDecimal()
            cleanedValue.setScale(8, RoundingMode.HALF_UP).toPlainString()
        } ?: ""
    }

    private fun toCoin(inputVal: String) : Coin {
        val formattedValue = GenericUtils.formatFiatWithoutComma(inputVal)
        return try {
            Coin.parseCoin(formattedValue)
        } catch (x: Exception) {
            Coin.ZERO
        }
    }

    private fun getFiat(dashValue: String): Fiat {
        val rateApplied = applyCoinbaseExchangeRate(dashValue)
        val formattedValue = GenericUtils.formatFiatWithoutComma(rateApplied)
        return Fiat.parseFiat(localCurrencyCode, formattedValue)
    }
}
