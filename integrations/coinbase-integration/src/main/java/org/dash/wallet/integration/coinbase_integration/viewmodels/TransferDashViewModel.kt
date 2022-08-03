package org.dash.wallet.integration.coinbase_integration.viewmodels

import androidx.core.os.bundleOf
import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.livedata.NetworkStateInt
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.ConnectivityViewModel
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.coinbase_integration.DASH_CURRENCY
import org.dash.wallet.integration.coinbase_integration.TRANSACTION_TYPE_SEND
import org.dash.wallet.integration.coinbase_integration.model.CoinbaseToDashExchangeRateUIModel
import org.dash.wallet.integration.coinbase_integration.model.CoinbaseTransactionParams
import org.dash.wallet.integration.coinbase_integration.model.SendTransactionToWalletParams
import org.dash.wallet.integration.coinbase_integration.model.TransactionType
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import org.dash.wallet.integration.coinbase_integration.ui.dialogs.CoinBaseResultDialog
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
    var networkState: NetworkStateInt,
    private val analyticsService: AnalyticsService
) : ConnectivityViewModel(networkState) {

    private val _loadingState: MutableLiveData<Boolean> = MutableLiveData()
    val observeLoadingState: LiveData<Boolean>
        get() = _loadingState

    private val _dashBalanceInWalletState = MutableLiveData(walletDataProvider.getWalletBalance())
    val dashBalanceInWalletState: LiveData<Coin>
        get() = _dashBalanceInWalletState

    private var exchangeRate: ExchangeRate? = null

    val onAddressCreationFailedCallback = SingleLiveEvent<Unit>()

    val observeCoinbaseAddressState = SingleLiveEvent<String>()

    val onBuildTransactionParamsCallback = SingleLiveEvent<CoinbaseTransactionParams>()

    private val _sendDashToCoinbaseState = MutableLiveData<SendDashResponseState>()
    val observeSendDashToCoinbaseState: LiveData<SendDashResponseState>
        get() = _sendDashToCoinbaseState

    private val _userAccountDataWithExchangeRate = MutableLiveData<CoinbaseToDashExchangeRateUIModel>()
    val userAccountOnCoinbaseState: LiveData<CoinbaseToDashExchangeRateUIModel>
        get() = _userAccountDataWithExchangeRate

    val onFetchUserDataOnCoinbaseFailedCallback = SingleLiveEvent<Unit>()

    init {
        getWithdrawalLimitOnCoinbase()
        walletDataProvider.observeBalance()
            .onEach(_dashBalanceInWalletState::postValue)
            .launchIn(viewModelScope)
    }

    private fun getWithdrawalLimitOnCoinbase() = viewModelScope.launch(Dispatchers.Main){
        when (val response = coinBaseRepository.getWithdrawalLimit()){
            is ResponseResource.Success -> {
                val withdrawalLimit = response.value
                exchangeRate = getCurrencyExchangeRate(withdrawalLimit.currency)
                getUserData()
            }
            is ResponseResource.Failure -> {
                // todo: still lacking the use-case when withdrawal limit could not be fetched
                _loadingState.value = false
            }
        }
    }

    private suspend fun getCurrencyExchangeRate(currency: String): ExchangeRate {
        return exchangeRates.observeExchangeRate(currency).first()
    }

    private val withdrawalLimitAsFiat: Fiat
        get() {
            return if (config.coinbaseUserWithdrawalLimitAmount.isNullOrEmpty()){
               Fiat.valueOf(config.coinbaseSendLimitCurrency, 0)
            } else {
                val formattedAmount = GenericUtils.formatFiatWithoutComma(config.coinbaseUserWithdrawalLimitAmount)
                return try {
                    Fiat.parseFiat(config.coinbaseSendLimitCurrency, formattedAmount)
                }catch (x: Exception) {
                    Fiat.valueOf(config.coinbaseSendLimitCurrency, 0)
                }
            }
        }

    fun isInputGreaterThanCoinbaseWithdrawalLimit(amountInDash: Fiat): Boolean {
        return amountInDash.isGreaterThan(withdrawalLimitAsFiat)
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

    suspend fun estimateNetworkFee(value: Coin, emptyWallet: Boolean): SendPaymentService.TransactionDetails {
        return sendPaymentService.estimateNetworkFee(dashAddress, value, emptyWallet)
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
            DASH_CURRENCY,
            UUID.randomUUID().toString(),
            walletDataProvider.freshReceiveAddress().toBase58(),
            TRANSACTION_TYPE_SEND
        )

        onBuildTransactionParamsCallback.value = CoinbaseTransactionParams(
            sendTransactionToWalletParams,
            TransactionType.TransferDash
        )
    }

    fun logTransfer(isFiatSelected: Boolean) {
        analyticsService.logEvent(AnalyticsConstants.Coinbase.TRANSFER_CONTINUE, bundleOf())
        analyticsService.logEvent(if (isFiatSelected) {
            AnalyticsConstants.Coinbase.TRANSFER_ENTER_FIAT
        } else {
            AnalyticsConstants.Coinbase.TRANSFER_ENTER_DASH
        }, bundleOf())
    }

    fun logEvent(eventName: String) {
        analyticsService.logEvent(eventName, bundleOf())
    }

    fun logRetry() {
        analyticsService.logEvent(AnalyticsConstants.Coinbase.TRANSFER_ERROR_RETRY, bundleOf())
    }

    fun logClose(type: CoinBaseResultDialog.Type) {
        when (type) {
            CoinBaseResultDialog.Type.TRANSFER_DASH_SUCCESS -> {
                analyticsService.logEvent(AnalyticsConstants.Coinbase.TRANSFER_SUCCESS_CLOSE, bundleOf())
            }
            CoinBaseResultDialog.Type.TRANSFER_DASH_ERROR -> {
                analyticsService.logEvent(AnalyticsConstants.Coinbase.TRANSFER_ERROR_CLOSE, bundleOf())
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
        get() = Address.fromString(walletDataProvider.networkParameters, (observeCoinbaseAddressState.value ?: "").trim { it <= ' ' })
}

sealed class SendDashResponseState{
    data class SuccessState(val isTransactionPending: Boolean): SendDashResponseState()
    object InsufficientMoneyState: SendDashResponseState()
    data class FailureState(val failureMessage: String): SendDashResponseState()
    object UnknownFailureState: SendDashResponseState()
}