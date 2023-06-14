package org.dash.wallet.integration.coinbase_integration.viewmodels

import androidx.annotation.StringRes
import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.utils.Fiat
import org.bitcoinj.wallet.Wallet.CouldNotAdjustDownwards
import org.bitcoinj.wallet.Wallet.DustySendRequested
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.R
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.coinbase_integration.CoinbaseConstants
import org.dash.wallet.integration.coinbase_integration.model.CoinbaseToDashExchangeRateUIModel
import org.dash.wallet.integration.coinbase_integration.model.CoinbaseTransactionParams
import org.dash.wallet.integration.coinbase_integration.model.SendTransactionToWalletParams
import org.dash.wallet.integration.coinbase_integration.model.TransactionType
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.services.*
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import org.dash.wallet.integration.coinbase_integration.ui.convert_currency.model.SwapValueErrorType
import org.dash.wallet.integration.coinbase_integration.ui.dialogs.CoinBaseResultDialog
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*
import javax.inject.Inject

@ExperimentalCoroutinesApi
@HiltViewModel
class TransferDashViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    val config: Configuration,
    private val walletDataProvider: WalletDataProvider,
    private val sendPaymentService: SendPaymentService,
    var exchangeRates: ExchangeRatesProvider,
    networkState: NetworkStateInt,
    private val analyticsService: AnalyticsService,
    private val transactionMetadataProvider: TransactionMetadataProvider
) : ViewModel() {

    private val _loadingState: MutableLiveData<Boolean> = MutableLiveData()
    val observeLoadingState: LiveData<Boolean>
        get() = _loadingState

    private val _dashBalanceInWalletState = MutableLiveData(walletDataProvider.getWalletBalance())
    val dashBalanceInWalletState: LiveData<Coin>
        get() = _dashBalanceInWalletState


    private var withdrawalLimitCurrency = MutableStateFlow(config.exchangeCurrencyCode)
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

    private val _sendDashToCoinbaseError = MutableLiveData<NetworkFeeExceptionState>()
    val sendDashToCoinbaseError: LiveData<NetworkFeeExceptionState>
        get() = _sendDashToCoinbaseError

    val isDeviceConnectedToInternet: LiveData<Boolean> = networkState.isConnected.asLiveData()

    var minAllowedSwapDashCoin: Coin = Coin.ZERO
    var minFaitAmount:Fiat = Fiat.valueOf(config.exchangeCurrencyCode, 0)

    private var maxForDashCoinBaseAccount: Coin = Coin.ZERO

    init {
        getWithdrawalLimitOnCoinbase()
        getUserAccountAddress()
        walletDataProvider.observeBalance()
            .onEach(_dashBalanceInWalletState::postValue)
            .launchIn(viewModelScope)

        withdrawalLimitCurrency
            .filterNotNull()
            .flatMapLatest { code ->
                exchangeRates.observeExchangeRate(code)
            }
            .onEach { exchangeRate = it }
            .launchIn(viewModelScope)
    }

    private fun getWithdrawalLimitOnCoinbase() = viewModelScope.launch(Dispatchers.Main){
        when (val response = coinBaseRepository.getWithdrawalLimit()){
            is ResponseResource.Success -> {
                withdrawalLimitCurrency.value = response.value.currency
                getUserData()
            }
            is ResponseResource.Failure -> {
                // todo: still lacking the use-case when withdrawal limit could not be fetched
                _loadingState.value = false
            }
        }
    }

    private fun getUserAccountAddress() = viewModelScope.launch(Dispatchers.Main){
        when (val response = coinBaseRepository.getUserAccountAddress()){
            is ResponseResource.Success -> {
                observeCoinbaseUserAccountAddress.value = response.value ?: ""
            }
            is ResponseResource.Failure -> {
            }
        }
    }

    private val withdrawalLimitInDash: Double
        get() {
            return if (config.coinbaseUserWithdrawalLimitAmount.isNullOrEmpty()) {
                0.0
            } else {
                val formattedAmount = GenericUtils.formatFiatWithoutComma(config.coinbaseUserWithdrawalLimitAmount)
                val fiatAmount = try {
                    Fiat.parseFiat(config.coinbaseSendLimitCurrency, formattedAmount)
                } catch (x: Exception) {
                    Fiat.valueOf(config.coinbaseSendLimitCurrency, 0)
                }

                exchangeRate?.fiat?.let { fiat ->
                    val newRate = org.bitcoinj.utils.ExchangeRate(Coin.COIN, fiat)
                    val amountInDash = newRate.fiatToCoin(fiatAmount)
                    amountInDash.toPlainString().toDoubleOrZero
                } ?: 0.0
            }
        }

    private fun calculateCoinbaseMinAllowedValue(account:CoinbaseToDashExchangeRateUIModel){
        val minFaitValue = CoinbaseConstants.MIN_USD_COINBASE_AMOUNT.toBigDecimal() / account.currencyToUSDExchangeRate.toBigDecimal()

        val cleanedValue: BigDecimal =
            minFaitValue * account.currencyToDashExchangeRate.toBigDecimal()

        val bd = cleanedValue.setScale(8, RoundingMode.HALF_UP)

        val coin = try {
            Coin.parseCoin(bd.toString())
        } catch (x: Exception) {
            Coin.ZERO
        }

        minAllowedSwapDashCoin = coin

        val formattedAmount = GenericUtils.formatFiatWithoutComma(minFaitValue.toString())
        minFaitAmount = try {
            Fiat.parseFiat(config.exchangeCurrencyCode, formattedAmount)
        } catch (x: Exception) {
            Fiat.valueOf(config.exchangeCurrencyCode, 0)
        }
    }

    private fun calculateCoinbaseMaxAllowedValue(account:CoinbaseToDashExchangeRateUIModel){
        val maxCoinValue = try {
            Coin.parseCoin(account.coinBaseUserAccountData.balance?.amount)
        } catch (x: Exception) {
            Coin.ZERO
        }
        maxForDashCoinBaseAccount = maxCoinValue
    }


    private fun isInputGreaterThanCoinbaseWithdrawalLimit(amountInDash: Coin): Boolean {
        return amountInDash.toPlainString().toDoubleOrZero.compareTo(withdrawalLimitInDash) > 0
    }

    fun checkEnteredAmountValue(amountInDash: Coin): SwapValueErrorType {
        return when {
                (amountInDash == minAllowedSwapDashCoin || amountInDash.isGreaterThan(minAllowedSwapDashCoin)) &&
                        maxForDashCoinBaseAccount.isLessThan(minAllowedSwapDashCoin) -> SwapValueErrorType.NotEnoughBalance
                amountInDash.isLessThan(minAllowedSwapDashCoin) -> SwapValueErrorType.LessThanMin
                amountInDash.isGreaterThan(maxForDashCoinBaseAccount) -> SwapValueErrorType.MoreThanMax.apply {
                    amount = userAccountOnCoinbaseState.value?.coinBaseUserAccountData?.balance?.amount
                }
                isInputGreaterThanCoinbaseWithdrawalLimit(amountInDash)-> {
                    SwapValueErrorType.UnAuthorizedValue
                }
                else -> SwapValueErrorType.NOError
            }
    }

    fun isInputGreaterThanWalletBalance(input: Coin, balanceInWallet: Coin): Boolean {
        return input.isGreaterThan(balanceInWallet)
    }

    fun isUserAuthorized(): Boolean {
        return config.spendingConfirmationEnabled
    }

    fun createAddressForAccount() = viewModelScope.launch {
        _loadingState.value = true
        when(val result = coinBaseRepository.createAddress()){
            is ResponseResource.Success -> {
                if (result.value.isEmpty()){
                    onAddressCreationFailedCallback.call()
                } else {
                    result.value?.let{
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

    suspend fun sendDash(dashValue: Coin, isEmptyWallet: Boolean, checkConditions: Boolean) {
        _sendDashToCoinbaseState.value = checkTransaction(dashValue, isEmptyWallet, checkConditions)
    }

    suspend fun estimateNetworkFee(value: Coin, emptyWallet: Boolean): SendPaymentService.TransactionDetails? {
         try {
             return sendPaymentService.estimateNetworkFee(dashAddress, value, emptyWallet)
        } catch (exception: Exception) {

             when (exception) {
                 is DustySendRequested -> {
                     _sendDashToCoinbaseError.value = NetworkFeeExceptionState(R.string.send_coins_error_dusty_send)
                 }
                 is InsufficientMoneyException -> {
                     _sendDashToCoinbaseError.value  =NetworkFeeExceptionState( R.string.send_coins_error_insufficient_money)
                 }
                 is CouldNotAdjustDownwards -> {
                     _sendDashToCoinbaseError.value  =NetworkFeeExceptionState( R.string.send_coins_error_dusty_send)

                 }
                 else -> {
                     _sendDashToCoinbaseError.value  =NetworkFeeExceptionState( exceptionMessage =exception.toString())
                 }
             }
             return null
        }
    }

    private suspend fun checkTransaction(
        coin: Coin,
        isEmptyWallet: Boolean,
        checkConditions: Boolean
    ): SendDashResponseState{
        return try {
            val transaction = sendPaymentService.sendCoins(
                dashAddress, coin,
                emptyWallet = isEmptyWallet,
                checkBalanceConditions = checkConditions
            )
            transactionMetadataProvider.markAddressAsTransferOutAsync(
                dashAddress.toBase58(),
                ServiceName.Coinbase
            )
            SendDashResponseState.SuccessState(transaction.isPending)
        } catch(e: LeftoverBalanceException) {
            throw e
        } catch (e: InsufficientMoneyException) {
            e.printStackTrace()
            SendDashResponseState.InsufficientMoneyState
        } catch (e: Exception){
            e.printStackTrace()
            e.message?.let {
                SendDashResponseState.FailureState(it)
            } ?: SendDashResponseState.UnknownFailureState
        }
    }

    fun reviewTransfer(dashValue: String) {
        val sendTransactionToWalletParams = SendTransactionToWalletParams(
            dashValue,
            Constants.DASH_CURRENCY,
            UUID.randomUUID().toString(),
            walletDataProvider.freshReceiveAddress().toBase58(),
            CoinbaseConstants.TRANSACTION_TYPE_SEND
        )

        onBuildTransactionParamsCallback.value = CoinbaseTransactionParams(
            sendTransactionToWalletParams,
            TransactionType.TransferDash
        )
        transactionMetadataProvider.markAddressAsTransferInAsync(sendTransactionToWalletParams.to!!, ServiceName.Coinbase)
    }

    fun logTransfer(isFiatSelected: Boolean) {
        analyticsService.logEvent(AnalyticsConstants.Coinbase.TRANSFER_CONTINUE, mapOf())
        analyticsService.logEvent(if (isFiatSelected) {
            AnalyticsConstants.Coinbase.TRANSFER_ENTER_FIAT
        } else {
            AnalyticsConstants.Coinbase.TRANSFER_ENTER_DASH
        }, mapOf())
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

    private fun getUserData(){
        viewModelScope.launch {
            when(val response = coinBaseRepository.getExchangeRateFromCoinbase()){
                is ResponseResource.Success -> {
                    val userData = response.value
                    if (userData == CoinbaseToDashExchangeRateUIModel.EMPTY){
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
    val dashAddress: Address
        get() = Address.fromString(walletDataProvider.networkParameters, (observeCoinbaseAddressState.value ?: observeCoinbaseUserAccountAddress.value?:"").trim { it <= ' ' })
}

sealed class SendDashResponseState{
    data class SuccessState(val isTransactionPending: Boolean): SendDashResponseState()
    object InsufficientMoneyState: SendDashResponseState()
    data class FailureState(val failureMessage: String): SendDashResponseState()
    object UnknownFailureState: SendDashResponseState()
}

data class NetworkFeeExceptionState(
    @StringRes val exceptionMessageResource: Int? = null,
    val exceptionMessage: String? = null
)