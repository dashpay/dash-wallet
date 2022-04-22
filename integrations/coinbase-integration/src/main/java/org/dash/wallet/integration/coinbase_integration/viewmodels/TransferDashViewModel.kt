package org.dash.wallet.integration.coinbase_integration.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.coinbase_integration.model.CoinbaseToDashExchangeRateUIModel
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import javax.inject.Inject

@HiltViewModel
class TransferDashViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    val config: Configuration,
    private val walletDataProvider: WalletDataProvider,
    private val sendPaymentService: SendPaymentService,
    var exchangeRates: ExchangeRatesProvider
) : ViewModel() {

    private val _loadingState: MutableLiveData<Boolean> = MutableLiveData()
    val observeLoadingState: LiveData<Boolean>
        get() = _loadingState

    private val _dashBalanceInWalletState = MutableLiveData(walletDataProvider.getWalletBalance())
    val dashBalanceInWalletState: LiveData<Coin>
        get() = _dashBalanceInWalletState

    private var coinbaseUserAccount: CoinbaseToDashExchangeRateUIModel = CoinbaseToDashExchangeRateUIModel.EMPTY
    private var exchangeRate: ExchangeRate? = null

    init {
        getWithdrawalLimitOnCoinbase()
        getUserAccountDataOnCoinbase()
    }

    private fun getUserAccountDataOnCoinbase() = viewModelScope.launch(Dispatchers.Main){
        _loadingState.value = true
        when(val response = coinBaseRepository.getExchangeRateFromCoinbase()){
            is ResponseResource.Success -> {
                _loadingState.value = false
                coinbaseUserAccount = response.value
            }

            is ResponseResource.Failure -> {
                _loadingState.value = false
            }
        }
    }

    private fun getWithdrawalLimitOnCoinbase() = viewModelScope.launch(Dispatchers.Main){
        when (val response = coinBaseRepository.getWithdrawalLimit()){
            is ResponseResource.Success -> {
                val withdrawalLimit = response.value
                exchangeRate = getCurrencyExchangeRate(withdrawalLimit.currency)
            }
            is ResponseResource.Failure -> {
                // todo: still lacking the use-case when withdrawal limit could not be fetched
            }
        }
    }

    private suspend fun getCurrencyExchangeRate(currency: String): ExchangeRate {
        return exchangeRates.observeExchangeRate(currency).first()
    }

    private val formatCoinbaseWithdrawalLimit: Double
        get() {
            return if (config.coinbaseUserWithdrawalLimitAmount.isNullOrEmpty()){
                0.0
            } else {
                val formattedAmount = GenericUtils.formatFiatWithoutComma(config.coinbaseUserWithdrawalLimitAmount)
                val fiatAmount = try {
                    Fiat.parseFiat(config.coinbaseSendLimitCurrency, formattedAmount)
                }catch (x: Exception) {
                    Fiat.valueOf(config.coinbaseSendLimitCurrency, 0)
                }
                exchangeRate?.let {
                    val newRate = org.bitcoinj.utils.ExchangeRate(Coin.COIN, it.fiat)
                    val amountInDash = newRate.fiatToCoin(fiatAmount)
                    amountInDash.toPlainString().toDoubleOrZero
                } ?: 0.0

            }
        }

    fun isInputGreaterThanCoinbaseLimit(amountInDash: Coin): Boolean {
        return amountInDash.toPlainString().toDoubleOrZero.compareTo(formatCoinbaseWithdrawalLimit) > 0
    }

    fun isFiatInputGreaterThanLimit(amountInFiat: Fiat): Boolean{
        return amountInFiat.toPlainString().toDoubleOrZero.compareTo(formatCoinbaseWithdrawalLimit) > 0
    }

    fun isInputGreaterThanWalletBalance(input: String, balanceInWallet: String): Boolean {
        return input.toDoubleOrZero.compareTo(balanceInWallet.toDoubleOrZero) > 0
    }
}