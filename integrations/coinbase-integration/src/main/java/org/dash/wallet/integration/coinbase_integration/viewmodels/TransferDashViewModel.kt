package org.dash.wallet.integration.coinbase_integration.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.integration.coinbase_integration.model.CoinbaseToDashExchangeRateUIModel
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import javax.inject.Inject

@HiltViewModel
class TransferDashViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt,
    val config: Configuration,
    private val walletDataProvider: WalletDataProvider
) : ViewModel() {

    private val _loadingState: MutableLiveData<Boolean> = MutableLiveData()
    val observeLoadingState: LiveData<Boolean>
        get() = _loadingState

    private val _dashBalanceInWalletState = MutableLiveData(walletDataProvider.getWalletBalance())
    val dashBalanceInWalletState: LiveData<Coin>
        get() = _dashBalanceInWalletState

    private var coinbaseUserAccount: CoinbaseToDashExchangeRateUIModel = CoinbaseToDashExchangeRateUIModel.EMPTY

    init {
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
}