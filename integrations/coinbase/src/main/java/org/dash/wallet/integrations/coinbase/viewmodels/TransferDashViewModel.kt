package org.dash.wallet.integrations.coinbase.viewmodels

import androidx.annotation.StringRes
import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.R
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.freshReceiveAddressStringOffMain
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.getDashBalance
import org.dash.wallet.common.isTransactionPending
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.money.FiatValue
import org.dash.wallet.common.observeTotalDashBalance
import org.dash.wallet.common.services.*
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integrations.coinbase.CoinbaseConstants
import org.dash.wallet.integrations.coinbase.model.CoinbaseToDashExchangeRateUIModel
import org.dash.wallet.integrations.coinbase.model.CoinbaseTransactionParams
import org.dash.wallet.integrations.coinbase.model.SendTransactionToWalletParams
import org.dash.wallet.integrations.coinbase.model.TransactionType
import org.dash.wallet.integrations.coinbase.repository.CoinBaseRepositoryInt
import org.dash.wallet.integrations.coinbase.ui.convert_currency.model.SwapValueErrorType
import org.dash.wallet.integrations.coinbase.ui.dialogs.CoinBaseResultDialog
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*
import javax.inject.Inject

@HiltViewModel
class TransferDashViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    val config: Configuration,
    private val walletDataProvider: WalletDataProvider,
    private val sendPaymentService: SendPaymentService,
    var exchangeRates: ExchangeRatesProvider,
    networkState: NetworkStateInt,
    private val analyticsService: AnalyticsService,
    private val transactionMetadataProvider: TransactionMetadataProvider,
    private val walletUIConfig: WalletUIConfig
) : ViewModel() {

    val minimumFee: Dash = Dash.valueOf(226)
    private val _loadingState: MutableLiveData<Boolean> = MutableLiveData()
    val observeLoadingState: LiveData<Boolean>
        get() = _loadingState

    private val _dashBalanceInWalletState = MutableLiveData(walletDataProvider.getDashBalance())
    val dashBalanceInWalletState: LiveData<Dash>
        get() = _dashBalanceInWalletState

    private var withdrawalLimitCurrency = MutableStateFlow<String?>(null)
    private var exchangeRate: ExchangeRate? = null

    val onAddressCreationFailedCallback = SingleLiveEvent<Unit>()

    val observeCoinbaseAddressState = SingleLiveEvent<String>()

    val observeCoinbaseUserAccountAddress = SingleLiveEvent<String>()

    val onBuildTransactionParamsCallback = SingleLiveEvent<CoinbaseTransactionParams>()

    private val _sendDashToCoinbaseState = MutableLiveData<SendDashResponseState>()
    val observeSendDashToCoinbaseState: LiveData<SendDashResponseState>
        get() = _sendDashToCoinbaseState

    private val _userAccountDataWithExchangeRate = MutableLiveData<CoinbaseToDashExchangeRateUIModel>()
    val userAccountOnCoinbaseState: LiveData<CoinbaseToDashExchangeRateUIModel>
        get() = _userAccountDataWithExchangeRate

    val onFetchUserDataOnCoinbaseFailedCallback = SingleLiveEvent<Unit>()

    val onAuthenticationErrorCallback = SingleLiveEvent<Unit>()

    private val _sendDashToCoinbaseError = MutableLiveData<NetworkFeeExceptionState>()
    val sendDashToCoinbaseError: LiveData<NetworkFeeExceptionState>
        get() = _sendDashToCoinbaseError

    val isDeviceConnectedToInternet: LiveData<Boolean> = networkState.isConnected.asLiveData()

    var minAllowedSwapDashCoin: Dash = Dash.ZERO
    var minFiatAmount: FiatValue = FiatValue.valueOf(Constants.USD_CURRENCY, 0)

    var maxForDashCoinBaseAccount: Dash = Dash.ZERO
        private set

    init {
        getUserAccountAddress()
        getUserData()
        walletDataProvider.observeTotalDashBalance()
            .onEach(_dashBalanceInWalletState::postValue)
            .launchIn(viewModelScope)

        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .onEach { minFiatAmount = FiatValue.valueOf(it, minFiatAmount.value) }
            .launchIn(viewModelScope)

        withdrawalLimitCurrency
            .filterNotNull()
            .flatMapLatest { code ->
                exchangeRates.observeExchangeRate(code)
            }
            .onEach { exchangeRate = it }
            .launchIn(viewModelScope)
    }

    private fun getUserAccountAddress() = viewModelScope.launch(Dispatchers.Main) {
        when (val response = coinBaseRepository.getUserAccountAddress()) {
            is ResponseResource.Success -> {
                observeCoinbaseUserAccountAddress.value = response.value ?: ""
            }
            is ResponseResource.Failure -> {
            }
        }
    }

    private fun calculateCoinbaseMinAllowedValue(account:CoinbaseToDashExchangeRateUIModel) {
        val minFaitValue = CoinbaseConstants.MIN_USD_COINBASE_AMOUNT.toBigDecimal() / account.currencyToUSDExchangeRate
        val cleanedValue: BigDecimal = minFaitValue * account.currencyToDashExchangeRate

        val bd = cleanedValue.setScale(8, RoundingMode.HALF_UP)

        val coin = try {
            Dash.parse(bd.toString())
        } catch (x: Exception) {
            Dash.ZERO
        }

        minAllowedSwapDashCoin = coin

        val formattedAmount = GenericUtils.formatFiatWithoutComma(minFaitValue.toString())
        minFiatAmount = try {
            FiatValue.parseFiat(minFiatAmount.currencyCode, formattedAmount)
        } catch (x: Exception) {
            FiatValue.valueOf(minFiatAmount.currencyCode, 0)
        }
    }

    private fun calculateCoinbaseMaxAllowedValue(account:CoinbaseToDashExchangeRateUIModel) {
        maxForDashCoinBaseAccount = account.coinbaseAccount.coinBalance()
    }

    private suspend fun isInputGreaterThanLimit(amountInDash: Dash): Boolean {
        return coinBaseRepository.isInputGreaterThanLimit(amountInDash)
    }

    suspend fun checkEnteredAmountValue(amountInDash: Dash): SwapValueErrorType {
        return when {
            (amountInDash == minAllowedSwapDashCoin || amountInDash.isGreaterThan(minAllowedSwapDashCoin)) &&
                maxForDashCoinBaseAccount.isLessThan(minAllowedSwapDashCoin) -> SwapValueErrorType.NotEnoughBalance
            amountInDash.isLessThan(minAllowedSwapDashCoin) -> SwapValueErrorType.LessThanMin
            amountInDash.isGreaterThan(maxForDashCoinBaseAccount) -> SwapValueErrorType.MoreThanMax.apply {
                amount = userAccountOnCoinbaseState.value?.coinbaseAccount?.availableBalance?.value
            }
            isInputGreaterThanLimit(amountInDash) -> {
                SwapValueErrorType.UnAuthorizedValue
            }
            else -> SwapValueErrorType.NOError
        }
    }

    fun isInputGreaterThanWalletBalance(input: Dash, balanceInWallet: Dash): Boolean {
        return input.isGreaterThan(balanceInWallet)
    }

    fun isUserAuthorized(): Boolean {
        return config.spendingConfirmationEnabled
    }

    fun createAddressForAccount() = viewModelScope.launch {
        _loadingState.value = true
        when (val result = coinBaseRepository.createAddress()) {
            is ResponseResource.Success -> {
                if (result.value.isEmpty()) {
                    onAddressCreationFailedCallback.call()
                } else {
                    result.value?.let {
                        observeCoinbaseAddressState.value = it
                    }
                }
                _loadingState.value = false
            }
            is ResponseResource.Failure -> {
                _loadingState.value = false
                onAddressCreationFailedCallback.call()
            }
        }
    }

    suspend fun sendDash(dashValue: Dash, isEmptyWallet: Boolean, checkConditions: Boolean) {
        _sendDashToCoinbaseState.value = checkTransaction(dashValue, isEmptyWallet, checkConditions)
    }

    suspend fun estimateNetworkFee(value: Dash, emptyWallet: Boolean): SendPaymentService.TransactionEstimate? {
        try {
            return sendPaymentService.estimateNetworkFee(dashAddress, value, emptyWallet)
        } catch (exception: Exception) {
            when {
                exception.isDustySend -> {
                    _sendDashToCoinbaseError.value = NetworkFeeExceptionState(R.string.send_coins_error_dusty_send)
                }
                exception.isInsufficientMoney -> {
                    _sendDashToCoinbaseError.value = NetworkFeeExceptionState(
                        R.string.send_coins_error_insufficient_money
                    )
                }
                else -> {
                    _sendDashToCoinbaseError.value = NetworkFeeExceptionState(exceptionMessage = exception.toString())
                }
            }
            return null
        }
    }

    private suspend fun checkTransaction(
        coin: Dash,
        isEmptyWallet: Boolean,
        checkConditions: Boolean
    ): SendDashResponseState {
        return try {
            val txId = sendPaymentService.sendCoins(
                dashAddress,
                coin,
                emptyWallet = isEmptyWallet,
                checkBalanceConditions = checkConditions
            )
            transactionMetadataProvider.markAddressAsTransferOutAsync(
                dashAddress,
                ServiceName.Coinbase
            )
            SendDashResponseState.SuccessState(walletDataProvider.isTransactionPending(txId))
        } catch (e: Exception) {
            when {
                e.isLeftoverBalanceWarning -> throw e
                e.isInsufficientMoney -> {
                    e.printStackTrace()
                    SendDashResponseState.InsufficientMoneyState
                }
                else -> {
                    e.printStackTrace()
                    e.message?.let {
                        SendDashResponseState.FailureState(it)
                    } ?: SendDashResponseState.UnknownFailureState
                }
            }
        }
    }

    fun reviewTransfer(dashValue: String) = viewModelScope.launch {
        // Off-main: reviewTransfer is called from a Main-thread observer, and
        // the underlying freshReceiveAddress() forces a synchronous
        // full-wallet save; the callback below still fires on Main.
        val sendTransactionToWalletParams = SendTransactionToWalletParams(
            dashValue,
            Constants.DASH_CURRENCY,
            UUID.randomUUID().toString(),
            walletDataProvider.freshReceiveAddressStringOffMain(),
            CoinbaseConstants.TRANSACTION_TYPE_SEND
        )

        onBuildTransactionParamsCallback.value = CoinbaseTransactionParams(
            sendTransactionToWalletParams,
            TransactionType.TransferDash
        )
        transactionMetadataProvider.markAddressAsTransferInAsync(
            sendTransactionToWalletParams.to!!,
            ServiceName.Coinbase
        )
    }

    fun logTransfer(isFiatSelected: Boolean) {
        analyticsService.logEvent(AnalyticsConstants.Coinbase.TRANSFER_CONTINUE, mapOf())
        analyticsService.logEvent(
            if (isFiatSelected) {
                AnalyticsConstants.Coinbase.TRANSFER_ENTER_FIAT
            } else {
                AnalyticsConstants.Coinbase.TRANSFER_ENTER_DASH
            },
            mapOf()
        )
    }

    fun logEvent(eventName: String) {
        analyticsService.logEvent(eventName, mapOf())
    }

    fun logRetry() {
        analyticsService.logEvent(AnalyticsConstants.Coinbase.TRANSFER_ERROR_RETRY, mapOf())
    }

    fun logClose(type: CoinBaseResultDialog.Type) {
        when (type) {
            CoinBaseResultDialog.Type.TRANSFER_DASH_SUCCESS -> {
                analyticsService.logEvent(AnalyticsConstants.Coinbase.TRANSFER_SUCCESS_CLOSE, mapOf())
            }
            CoinBaseResultDialog.Type.TRANSFER_DASH_ERROR -> {
                analyticsService.logEvent(AnalyticsConstants.Coinbase.TRANSFER_ERROR_CLOSE, mapOf())
            }
            else -> {}
        }
    }

    private fun getUserData() {
        viewModelScope.launch {
            when (val response = coinBaseRepository.getExchangeRateFromCoinbase()) {
                is ResponseResource.Success -> {
                    val userData = response.value
                    if (userData == CoinbaseToDashExchangeRateUIModel.EMPTY) {
                        onFetchUserDataOnCoinbaseFailedCallback.call()
                    } else {
                        _userAccountDataWithExchangeRate.value = userData
                        calculateCoinbaseMinAllowedValue(userData)
                        calculateCoinbaseMaxAllowedValue(userData)
                    }
                    _loadingState.value = false
                }

                is ResponseResource.Failure -> {
                    _loadingState.value = false
                    onFetchUserDataOnCoinbaseFailedCallback.call()
                }
            }
        }
    }
    private val dashAddress: String
        get() = (observeCoinbaseAddressState.value ?: observeCoinbaseUserAccountAddress.value ?: "").trim {
            it <= ' '
        }
}

sealed class SendDashResponseState {
    data class SuccessState(val isTransactionPending: Boolean): SendDashResponseState()
    object InsufficientMoneyState: SendDashResponseState()
    data class FailureState(val failureMessage: String): SendDashResponseState()
    object UnknownFailureState: SendDashResponseState()
}

data class NetworkFeeExceptionState(
    @StringRes val exceptionMessageResource: Int? = null,
    val exceptionMessage: String? = null
)
