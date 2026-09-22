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
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.util.*
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.common.util.toFiat
import org.dash.wallet.integrations.coinbase.CoinbaseConstants
import org.dash.wallet.integrations.coinbase.model.CoinbaseToDashExchangeRateUIModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EnterAmountToTransferViewModel @Inject constructor(
    var exchangeRates: ExchangeRatesProvider,
    var configuration: Configuration,
    walletDataProvider: WalletDataProvider,
    blockchainStateProvider: BlockchainStateProvider,
    walletUIConfig: WalletUIConfig
) : ViewModel() {

    var coinbaseExchangeRate: CoinbaseToDashExchangeRateUIModel? = null
    private var maxAmountInDashWalletFormatted: String = CoinbaseConstants.VALUE_ZERO
    // Not locale-aware: every value this produces is an INPUT -- to maxValue, to
    // applyCoinbaseExchangeRate, to BigDecimal -- never something shown to the user.
    // withLocale() gave it the device's decimal mark, so on a comma-decimal locale MAX
    // handed "1,5" to a pipeline that can only read "1.5": the DASH branch left it
    // unparseable and the transfer button never enabled.
    private val dashFormat = MonetaryFormat()
        .noCode().minDecimals(6).optionalDecimals()
    val decimalSeparator =
        DecimalFormatSymbols.getInstance(GenericUtils.getDeviceLocale()).decimalSeparator
    private val format = Constants.SEND_PAYMENT_LOCAL_FORMAT.noCode()

    var fiatAmount: Fiat? = null
    var inputValue: String = CoinbaseConstants.VALUE_ZERO
    var isMaxAmountSelected: Boolean = false
    val onContinueTransferEvent = SingleLiveEvent<Pair<Fiat, Coin>>()

    /**
     * The digits-only slice of the text [applyNewValue] last returned -- without the
     * currency symbol or code. Single source of truth for the span bounds the view styles
     * and for the keypad's decimal gate, so neither can be paired with text from the other
     * branch (MO-995).
     */
    var amountPart: String = CoinbaseConstants.VALUE_ZERO
        private set

    /** The mode [applyNewValue] last formatted in. */
    internal var amountMode: AmountMode = AmountMode.DASH
        private set

    /** Bounds of the currency label in the text [applyNewValue] last returned. */
    internal var currencySpan: AmountCurrencySpan? = null
        private set

    /** Decimal places the keypad may accept for the text now on screen. */
    val maxDecimals: Int
        get() = amountMode.maxDecimals

    var isFiatSelected: Boolean = false
        set(value) {
            if (field != value) {
                field = value
            }
        }

    private val _isTransferFromWalletToCoinbase = MutableStateFlow(false)
    val transferDirectionState: LiveData<Boolean>
        get() = _isTransferFromWalletToCoinbase.asLiveData()

    private val _dashBalanceInWallet = MutableStateFlow(walletDataProvider.getWalletBalance())
    val dashBalanceInWalletState: StateFlow<Coin>
        get() = _dashBalanceInWallet

    private val _localCurrencyCode = MutableStateFlow(Constants.USD_CURRENCY)

    /** Selected local currency, for synchronous formatting inside this ViewModel. */
    val localCurrencyCode: String
        get() = _localCurrencyCode.value

    /**
     * Emits the selected local currency: seeded with the default, then re-emitted once the
     * real value loads from [WalletUIConfig]. The view must rebuild its currency picker on
     * every emission -- reading the code once in onViewCreated left the fiat option
     * labelled USD for the life of the view (MO-995 C).
     */
    val localCurrencyCodeState: LiveData<String> = _localCurrencyCode.asLiveData()

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
        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .onEach { _localCurrencyCode.value = it }
            .flatMapLatest(exchangeRates::observeExchangeRate)
            .onEach(_localCurrencyExchangeRate::postValue)
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

    /**
     * Formats [value] for display and republishes everything derived from it -- the digits
     * slice ([amountPart]), the currency-label bounds ([currencySpan]) and the keypad's
     * decimal limit ([maxDecimals]) -- so no caller can pair the returned text with bounds
     * or a limit belonging to the other branch.
     *
     * MO-995: this used to take the currency picker's visible LABEL and branch on
     * `localCurrencyCode == monetaryCode`. The label is the selected currency code, which
     * loads asynchronously, so a stale "USD" against a real "BYN" failed to match and sent
     * a fiat amount down the DASH branch -- 8 decimals, a "USD" label on a BYN figure, a
     * keypad gated at 2 places that then refused every digit, and span bounds taken from
     * the other branch's field, which crashed `Spannable.setSpan`. It now branches on the
     * boolean the picker itself sets, and labels with its own [localCurrencyCode].
     */
    fun applyNewValue(value: String, isFiat: Boolean): String {
        val amount = transferAmount(
            value = value,
            isFiat = isFiat,
            localCurrencyCode = localCurrencyCode,
            decimalSeparator = decimalSeparator,
            fiatFormat = format
        )
        amountMode = amount.mode
        inputValue = amount.inputValue
        fiatAmount = amount.fiatAmount
        amountPart = amount.amountPart
        currencySpan = amount.currencySpan
        return amount.text
    }

    val hasBalance: Boolean
        get() {
            if (inputValue.isEmpty()) return false
            if (inputValue == CoinbaseConstants.VALUE_ZERO) return false
            
            // Allow incomplete decimal entries like "0." or "0.0" to keep UI responsive
            if (inputValue.endsWith(".") || inputValue.matches(Regex("0\\.0+"))) {
                return false
            }
            
            return (inputValue.toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO
        }

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
        get() = coinbaseExchangeRate?.coinbaseAccount?.availableBalance?.value ?: CoinbaseConstants.VALUE_ZERO

    private fun applyCoinbaseExchangeRate(amount: String): String {
        return coinbaseExchangeRate?.let { uiModel ->
            val cleanedValue = GenericUtils.formatFiatWithoutComma(amount)
                .toBigDecimal() / uiModel.currencyToDashExchangeRate
            cleanedValue.setScale(8, RoundingMode.HALF_UP).toPlainString()
        } ?: CoinbaseConstants.VALUE_ZERO
    }

    fun applyExchangeRateToFiat(fiatValue: Fiat): Coin {
        return coinbaseExchangeRate?.let {
            val cleanedValue = fiatValue.toBigDecimal() * it.currencyToDashExchangeRate
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
            val rate = BigDecimal.ONE.divide(it.currencyToDashExchangeRate, 10, RoundingMode.HALF_UP)
            org.bitcoinj.utils.ExchangeRate(rate.toFiat(localCurrencyCode))
        }
    }

    private fun scaleValue(valueToScale: String): String {
        return coinbaseExchangeRate?.let {
            val cleanedValue = valueToScale.toBigDecimal() * it.currencyToDashExchangeRate
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
