/*
 * Copyright 2024 Dash Core Group.
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

package org.dash.wallet.integrations.maya.ui.convert_currency

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.common.util.toCoin
import org.dash.wallet.integrations.maya.api.SwapProvider
import org.dash.wallet.integrations.maya.model.AccountDataUIModel
import org.dash.wallet.integrations.maya.model.Amount
import org.dash.wallet.integrations.maya.model.CurrencyInputType
import org.dash.wallet.integrations.maya.ui.convert_currency.model.MayaTransactionParams
import org.dash.wallet.integrations.maya.ui.convert_currency.model.SwapRequest
import org.dash.wallet.integrations.maya.ui.convert_currency.model.SwapValueErrorType
import org.dash.wallet.integrations.maya.utils.MaxSendable
import org.dash.wallet.integrations.maya.utils.MayaConstants
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConvertViewViewModel @Inject constructor(
    var exchangeRates: ExchangeRatesProvider,
    private val walletUIConfig: WalletUIConfig,
    private val walletDataProvider: WalletDataProvider,
    private val analyticsService: AnalyticsService,
    private val swapProvider: SwapProvider,
    // Sole source of the "max sendable" figure — see [maxSendableAmount].
    private val sendPaymentService: SendPaymentService,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(ConvertViewViewModel::class.java)
        private const val KEY_AMOUNT = "amount"
        private const val KEY_PENDING_RESULT = "pending_conversion_result"
        private const val KEY_MAX_SELECTED = "max_amount_selected"
    }

    /**
     * True when the entered amount came from the Max button — the user asked to convert the whole
     * wallet, which the swap backends carry out as a sweep ([SwapRequest.maximum]).
     *
     * Held as explicit intent rather than inferred by comparing the entered amount with the
     * balance: with the picker on fiat or crypto, [amount] is anchored on that currency, and the
     * trip back to DASH loses precision twice — [GenericUtils.toScaledBigDecimal] rounds the
     * entered value to 8 decimals, and [toCoin] then *truncates* the recomputed DASH value. The
     * result lands a satoshi or more below the balance, so an equality test quietly downgraded a
     * fiat-entered Max to a partial swap. Persisted so it survives a configuration change.
     *
     * Set it through [selectMaxAmount], which also pins the DASH amount; assign it directly only
     * to clear it when the entry stops being a Max (an edit, a currency or direction change).
     */
    var maxAmountSelected: Boolean
        get() = savedStateHandle[KEY_MAX_SELECTED] ?: false
        set(value) {
            savedStateHandle[KEY_MAX_SELECTED] = value
        }

    /**
     * The parameters of a conversion-result sheet the user hasn't acknowledged yet. The lock
     * screen auto-dismisses all dialogs, so the sheet is re-shown from these when the lock screen
     * goes away; persisted via [savedStateHandle] (this ViewModel is nav-graph scoped) so it also
     * survives the OS killing the process. Cleared when the user acts on the result.
     */
    var pendingConversionResult: MayaTransactionParams?
        get() = savedStateHandle[KEY_PENDING_RESULT]
        set(value) {
            savedStateHandle[KEY_PENDING_RESULT] = value
        }

    /**
     * Drops everything this flow persisted in [savedStateHandle] (entered amount, pending
     * result). Called when the user closes a successful conversion — the flow is finished, so
     * nothing should be restored if its screens are ever recreated.
     */
    fun clearSavedState() {
        savedStateHandle.remove<Amount>(KEY_AMOUNT)
        savedStateHandle.remove<MayaTransactionParams>(KEY_PENDING_RESULT)
        savedStateHandle.remove<Boolean>(KEY_MAX_SELECTED)
    }
    var destinationCurrency: String? = null
    var destinationAddress: String? = null
    lateinit var account: AccountDataUIModel
    val amount = Amount()
    val cryptoFormat: DecimalFormat = DecimalFormat(
        "0.########",
        DecimalFormatSymbols(GenericUtils.getDeviceLocale())
    ).apply {
        isDecimalSeparatorAlwaysShown = false
        isGroupingUsed = false
    }
    val fiatFormat: DecimalFormat = DecimalFormat(
        "0.##",
        DecimalFormatSymbols(GenericUtils.getDeviceLocale())
    ).apply {
        isDecimalSeparatorAlwaysShown = false
        isGroupingUsed = false
    }

    private val _dashToCrypto = MutableLiveData<Boolean>()
    val dashToCrypto: LiveData<Boolean>
        get() = this._dashToCrypto

    var enteredConvertAmount = "0"
    var maxCoinBaseAccountAmount: String = "0"

    var minAllowedSwapAmount: String = MayaConstants.MIN_USD_AMOUNT

    val onContinueEvent = SingleLiveEvent<SwapRequest>()

    /**
     * Last successfully computed [maxSendableAmount], readable without suspending so the screen
     * can name the real limit in its "more than max" message. [Coin.ZERO] until the first
     * estimate lands.
     */
    @Volatile
    var lastMaxSendableAmount: Coin = Coin.ZERO
        private set

    // Sweep estimate paired with the balance it was computed from; invalidated by any other
    // balance turning up. Guarded by [maxSendableMutex], which also keeps two callers from
    // paying for the same estimate concurrently.
    private var maxSendableCache: Pair<Coin, Coin>? = null
    private val maxSendableMutex = Mutex()

    private var minAllowedSwapDashCoin: Coin = Coin.ZERO
    private var maxForDashCoinBaseAccount: Coin = Coin.ZERO

    private val _selectedCryptoCurrencyAccount = MutableLiveData<AccountDataUIModel?>()
    val selectedCryptoCurrencyAccount: LiveData<AccountDataUIModel?>
        get() = this._selectedCryptoCurrencyAccount

    /**
     * Which of the three currencies the amount picker is on — the one the typed digits are
     * denominated in. Held as a [CurrencyInputType] rather than a currency code so it can't drift
     * out of step with what the screen shows: matching codes by string meant a late
     * SELECTED_CURRENCY read (or a currency change mid-screen) could leave this pointing at fiat
     * while the picker displayed DASH, and the typed amount was then read as fiat and converted
     * down to a fraction of a DASH — quoted as an amount far below the route minimum.
     */
    var selectedPickerCurrency: CurrencyInputType = CurrencyInputType.Dash

    private val _enteredAmount = MutableLiveData<String>("0")
    val enteredAmount: LiveData<String>
        get() = _enteredAmount

    private val _enteredConvertDashAmount = MutableLiveData<Coin>()
    val enteredConvertDashAmount: LiveData<Coin>
        get() = _enteredConvertDashAmount

    private val _enteredConvertFiatAmount = MutableLiveData<Fiat>()
    val enteredConvertFiatAmount: LiveData<Fiat>
        get() = _enteredConvertFiatAmount

    private val _enteredConvertCryptoAmount = MutableLiveData<Pair<String, String>>()
    val enteredConvertCryptoAmount: LiveData<Pair<String, String>>
        get() = _enteredConvertCryptoAmount

    var selectedLocalCurrencyCode: String = Constants.USD_CURRENCY
        private set

    private val _selectedLocalExchangeRate = MutableLiveData<ExchangeRate?>()
    val selectedLocalExchangeRate: LiveData<ExchangeRate?>
        get() = _selectedLocalExchangeRate

    val userDashAccountEmptyError = SingleLiveEvent<Unit>()

    // Persistent companion to the one-shot [userDashAccountEmptyError] event: the fragment's
    // Get-quote gate is derived from this so it survives a configuration change, while the
    // event itself only drives the toast.
    var userDashAccountEmpty = false
        private set

    val validSwapValue = SingleLiveEvent<String>()

    init {
        // do we need this?
        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            // Mirror the code into [amount] as well: it is read back as
            // [Amount.anchoredCurrencyCode] and drives the fiat label in the picker, and the
            // config read is async — leaving amount.fiatCode at its "USD" default while the
            // screen shows the real currency made the two disagree.
            .onEach {
                selectedLocalCurrencyCode = it
                amount.fiatCode = it
            }
            .flatMapLatest(exchangeRates::observeExchangeRate)
            .onEach(_selectedLocalExchangeRate::postValue)
            .launchIn(viewModelScope)

        savedStateHandle.get<Amount>(KEY_AMOUNT)?.let { savedAmount ->
            when (savedAmount.anchoredType) {
                CurrencyInputType.Dash -> amount.dash = savedAmount.dash
                CurrencyInputType.Fiat -> amount.fiat = savedAmount.fiat
                CurrencyInputType.Crypto -> amount.crypto = savedAmount.crypto
            }
        }
    }

    fun setSelectedAsset(asset: String) {
        viewModelScope.launch {
            val quote = swapProvider.getDefaultSwapQuote(asset)
            val minAmount = amount.copy()
            if (quote != null && quote.error == null) {
                minAmount.dash = quote.recommendedMinAmountIn.toBigDecimal()
                    .setScale(8, RoundingMode.HALF_UP)
                    .div(BigDecimal(1_0000_0000))
                minAllowedSwapAmount =
                    minAmount.fiat.setScale(GenericUtils.getCurrencyDigits(), RoundingMode.HALF_UP).toString()
                minAllowedSwapDashCoin = minAmount.dash.toCoin()
            }
        }
    }
    fun setSelectedCryptoCurrency(account: AccountDataUIModel) {
        // A different target crypto means whatever was entered — including a Max — no longer
        // applies. Only a real change clears it: this is called again with the same currency
        // every time the enter-amount screen's view is (re)created, and a Max entry has to
        // survive that (see [maxAmountSelected]).
        val previousCurrency = if (this::account.isInitialized) this.account.coinbaseAccount.currency else null
        if (previousCurrency != null && previousCurrency != account.coinbaseAccount.currency) {
            maxAmountSelected = false
        }
        amount.cryptoCode = account.coinbaseAccount.currency
        amount.fiatCode = selectedLocalCurrencyCode
        this.account = account
        maxCoinBaseAccountAmount = account.coinbaseAccount.availableBalance.value

        this._selectedLocalExchangeRate.value = selectedLocalExchangeRate.value?.currencyCode?.let {
            val cleanedValue = 1.toBigDecimal() / account.currencyToDashExchangeRate
            val bd = cleanedValue.setScale(8, RoundingMode.HALF_UP)
            ExchangeRate(it, bd.toString())
        }
        this._selectedCryptoCurrencyAccount.value = account

        // To check if the user has different fiat than usd the min is 2 usd
        val minFaitValue = MayaConstants.MIN_USD_AMOUNT.toBigDecimal() /
            account.currencyToUSDExchangeRate
        val cleanedValue: BigDecimal = minFaitValue * account.currencyToDashExchangeRate

        minAllowedSwapAmount = minFaitValue.toString()
        val bd = cleanedValue.setScale(8, RoundingMode.HALF_UP)

        val coin = try {
            Coin.parseCoin(bd.toString())
        } catch (x: Exception) {
            Coin.ZERO
        }

        minAllowedSwapDashCoin = coin

        val value =
            (maxCoinBaseAccountAmount.toBigDecimal() * account.getCryptoToDashExchangeRate())
                .setScale(8, RoundingMode.HALF_UP)

        val maxCoinValue = try {
            Coin.parseCoin(value.toString())
        } catch (x: Exception) {
            Coin.ZERO
        }

        maxForDashCoinBaseAccount = maxCoinValue

        setExchangeRates(
            BigDecimal.ONE.setScale(16, RoundingMode.HALF_UP) / account.currencyToDashExchangeRate,
            BigDecimal.ONE.setScale(16, RoundingMode.HALF_UP) / account.currencyToCryptoCurrencyExchangeRate
        )
    }

    fun updateAmounts() {
        val dashValue = try {
            Coin.parseCoin(amount.dash.toString())
        } catch (e: Exception) {
            Coin.ZERO
        }
        _enteredConvertDashAmount.value = dashValue

        _selectedCryptoCurrencyAccount.value?.let {
            val cryptoCurrency = amount.crypto.setScale(8, RoundingMode.HALF_UP).toString()
            _enteredConvertCryptoAmount.value = Pair(cryptoCurrency, it.coinbaseAccount.currency)
        }
        val fiatValue = Fiat.parseFiat(
            selectedLocalCurrencyCode,
            amount.fiat.setScale(2, RoundingMode.HALF_UP).toString()
        )
        _enteredConvertFiatAmount.value = fiatValue

        if (dashValue.isZero) {
            resetSwapValueError()
        }
    }

    fun resetSwapValueError() {
        validSwapValue.call()
    }

    /**
     * Bounds the entry by what can actually be sent, not by the gross balance: an amount above
     * [maxSendableAmount] can't be delivered, and bounding by the balance let the user carry it
     * all the way to the preview before finding out.
     */
    suspend fun checkEnteredAmountValue(checkSendingConditions: Boolean): SwapValueErrorType {
        val coin = if (dashToCrypto.value == true) {
            maxSendableAmount()
        } else {
            maxForDashCoinBaseAccount
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
        userDashAccountEmpty = dashToCrypto && walletDataProvider.getWalletBalance().isZero
        if (userDashAccountEmpty) {
            userDashAccountEmptyError.call()
            return
        }
        // Flipping the direction changes which balance "max" refers to; as with the target
        // currency, only a real change clears it (this is re-asserted on every view creation).
        if (_dashToCrypto.value != null && _dashToCrypto.value != dashToCrypto) {
            maxAmountSelected = false
        }
        _dashToCrypto.value = dashToCrypto

        if (dashToCrypto) {
            // A non-zero balance isn't the same as having something to sell — the fee may eat all
            // of it. Warm the estimate now so Max is instant, and let it close the gate if what
            // is left after the sweep fee is nothing.
            refreshMaxSendable()
        }
    }

    fun clear() {
        _selectedCryptoCurrencyAccount.value = null
        _dashToCrypto.value = false
        _enteredConvertDashAmount.value = Coin.ZERO
        _enteredConvertCryptoAmount.value = Pair("", "")
        maxAmountSelected = false
        savedStateHandle.remove<Amount>(KEY_AMOUNT)
    }

    fun continueSwap() {
        viewModelScope.launch {
            analyticsService.logEvent(AnalyticsConstants.Coinbase.CONVERT_CONTINUE, mapOf())
            // What the user typed in is exactly what [amount] is anchored on.
            logEnteredAmountCurrency(amount.anchoredType)
            // A sweep is what the Max button asked for, so take it from that intent
            // ([maxAmountSelected]) rather than inferring it: a fiat- or crypto-anchored Max
            // doesn't survive the round trip back to DASH as an exact match. The comparison
            // stays as a fallback for a max the user typed in by hand — measured against the
            // sendable figure, since that is what Max itself now enters, and evaluated only
            // when the flag is unset so the estimate is skipped on the common path.
            val isMaxSwap = maxAmountSelected ||
                MaxSendable.isTypedMax(amount.dash.toCoin(), maxSendableAmount())
            onContinueEvent.value = selectedCryptoCurrencyAccount.value?.coinbaseAccount?.let {
                destinationAddress?.let { address ->
                    SwapRequest(
                        amount,
                        isMaxSwap,
                        address,
                        it.currency,
                        it.asset,
                        selectedLocalCurrencyCode
                    )
                }
            }
        }
    }

    private fun getFiatAmount(currencyInputType: CurrencyInputType): Pair<Fiat?, Coin?> {
        selectedCryptoCurrencyAccount.value?.let { account ->
            val fiatAmount = selectedLocalExchangeRate.value?.let { rate ->
                when (currencyInputType) {
                    CurrencyInputType.Crypto -> {
                        val cleanedValue = enteredConvertAmount.toBigDecimal() /
                            account.currencyToCryptoCurrencyExchangeRate
                        val bd = cleanedValue.setScale(8, RoundingMode.HALF_UP)

                        Fiat.parseFiat(rate.fiat.currencyCode, bd.toString())
                    }

                    CurrencyInputType.Fiat -> {
                        Fiat.parseFiat(rate.fiat.currencyCode, enteredConvertAmount)
                    }

                    else -> {
                        val cleanedValue = enteredConvertAmount.toBigDecimal() /
                            account.currencyToDashExchangeRate
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

    /** convert a value in a string (Fiat or Crypto) to DASH using exchange rates in #[userAccountData]*/
    fun toDashValue(
        valueToBind: String,
        userAccountData: AccountDataUIModel,
        fromCrypto: Boolean = false
    ): BigDecimal {
        val convertedValue = if (fromCrypto) {
            valueToBind.toBigDecimal() * userAccountData.getCryptoToDashExchangeRate()
        } else {
            valueToBind.toBigDecimal() * userAccountData.currencyToDashExchangeRate
        }.setScale(8, RoundingMode.HALF_UP)
        return convertedValue
    }

    /**
     * The largest DASH amount this wallet can actually deliver in a max sell, and the single
     * figure every Max path is driven from — the button's entered value, the entry bound in
     * [checkEnteredAmountValue] and the hand-typed-max fallback in [continueSwap].
     *
     * A max sell is a sweep and the miner fee comes out of the sweep's single output, so the
     * deliverable amount is `balance − fee`, not the balance. This is deliberately the same call
     * `SwapKitApiAggregator.getSwapInfo` makes to quote a maximum, so entry, quote and deposit all
     * agree on one number; reading the gross `Wallet.BalanceType.ESTIMATED` here instead is what
     * made Max show an amount the swap would never use (MO-997).
     *
     * Estimation is not cheap — it builds and signs a candidate sweep, which derives the wallet
     * key — so the result is cached against the balance it was computed from and recomputed only
     * once that balance moves. Serialized so a pre-warm and a Max tap can't both pay for it.
     */
    suspend fun maxSendableAmount(): Coin = estimateMaxSendable() ?: Coin.ZERO

    /**
     * [maxSendableAmount], but distinguishing "this wallet can send nothing" ([Coin.ZERO]) from
     * "the estimate failed" (null), which callers must not act on: a transient failure would
     * otherwise read as an empty wallet and wipe a pinned Max.
     */
    private suspend fun estimateMaxSendable(): Coin? = withContext(Dispatchers.IO) {
        // Off the main thread deliberately: unlike its neighbours in SendCoinsTaskRunner,
        // estimateNetworkFee runs on the caller's dispatcher, and it derives the wallet key and
        // completes a candidate transaction — every entry point here is a Main-dispatched
        // viewModelScope/lifecycleScope coroutine, which would make that an ANR.
        maxSendableMutex.withLock {
            val wallet = walletDataProvider.wallet ?: return@withLock null
            val balance = wallet.getBalance(Wallet.BalanceType.ESTIMATED)
            maxSendableCache?.let { (cachedForBalance, cachedSendable) ->
                if (cachedForBalance == balance) {
                    return@withLock cachedSendable
                }
            }

            val sendable = try {
                MaxSendable.coerceSendable(
                    sendPaymentService.estimateNetworkFee(
                        walletDataProvider.currentReceiveAddress(),
                        balance,
                        emptyWallet = true
                    ).amountToSend
                )
            } catch (e: Exception) {
                // Insufficient funds for the fee lands here too, but so does a genuine failure,
                // and the two aren't worth telling apart: leave the cache alone so the next Max
                // tap retries rather than pinning a wrong figure until the balance happens to
                // move.
                log.warn("max sell: sweep estimate failed for balance {}", balance.toFriendlyString(), e)
                return@withLock null
            }

            log.info(
                "max sell: sendable {} of balance {}",
                sendable.toFriendlyString(),
                balance.toFriendlyString()
            )
            maxSendableCache = balance to sendable
            lastMaxSendableAmount = sendable
            sendable
        }
    }

    /**
     * Computes [maxSendableAmount] ahead of the user reaching for Max, so the button doesn't have
     * to wait on the estimate, and closes the Get-quote gate on a wallet whose whole balance is
     * eaten by the fee — for this flow that is as empty as a zero balance.
     */
    private fun refreshMaxSendable() {
        viewModelScope.launch {
            val sendable = estimateMaxSendable() ?: return@launch
            if (_dashToCrypto.value == true && !sendable.isPositive && !userDashAccountEmpty) {
                userDashAccountEmpty = true
                userDashAccountEmptyError.call()
            }
        }
    }

    /** The Max entry in every currency, or null when there is nothing this wallet can send. */
    suspend fun getMaxAmount(): Amount? {
        val sendable = maxSendableAmount()
        if (!sendable.isPositive) {
            return null
        }
        return amount.copy().apply { dash = sendable.toBigDecimal() }
    }

    /**
     * Records that the entered amount is a max ([maxAmountSelected]) and re-pins [amount]'s DASH
     * component to the exact [maxSendableAmount].
     *
     * Call it right after the Max value has been entered in [displayType]: entering it re-anchors
     * [amount] on the picker's currency, and for fiat or crypto the DASH value is then a rounded
     * back-conversion rather than the sendable amount (see [maxAmountSelected]) — which the quote
     * and the amount checks both read. Assigning [Amount.dash] recomputes fiat and crypto from the
     * exact figure; the anchor is restored to [displayType] afterwards (that setter doesn't
     * recompute) so the picker's currency still drives what's displayed and logged.
     */
    suspend fun selectMaxAmount(displayType: CurrencyInputType) {
        // A failed estimate leaves the entry exactly as it was; only a real zero — the fee has
        // caught up with the balance — retracts the Max, since there is no longer a max to pin.
        val sendable = estimateMaxSendable() ?: return
        if (!sendable.isPositive) {
            maxAmountSelected = false
            return
        }
        amount.dash = sendable.toBigDecimal()
        amount.anchoredType = displayType
        savedStateHandle[KEY_AMOUNT] = amount.copy()
        maxAmountSelected = true
        updateAmounts()
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

    private fun setExchangeRates(dashRate: BigDecimal, cryptoRate: BigDecimal) {
        amount.dashFiatExchangeRate = dashRate
        amount.cryptoFiatExchangeRate = cryptoRate
    }
    private fun setAmount(valueToBind: String, currency: CurrencyInputType, isLocalized: Boolean) {
        val value = GenericUtils.toScaledBigDecimal(valueToBind, localized = isLocalized)
        // Assigning re-anchors [amount] on that currency and recomputes the other two.
        when (currency) {
            CurrencyInputType.Dash -> amount.dash = value
            CurrencyInputType.Fiat -> amount.fiat = value
            CurrencyInputType.Crypto -> amount.crypto = value
        }
    }

    fun setEnteredAmount(amount: String, isLocalized: Boolean) {
        _enteredAmount.value = amount
        setAmount(amount, selectedPickerCurrency, isLocalized)
        savedStateHandle[KEY_AMOUNT] = this.amount.copy()
        log.info("setting amount: {} {}: {}", amount, selectedPickerCurrency, this.amount)
    }

    fun getAmountValue(currency: CurrencyInputType): String = amount.getValue(currency).toString()

    fun reset() {
        amount.dash = BigDecimal.ZERO
        maxAmountSelected = false
    }
}
