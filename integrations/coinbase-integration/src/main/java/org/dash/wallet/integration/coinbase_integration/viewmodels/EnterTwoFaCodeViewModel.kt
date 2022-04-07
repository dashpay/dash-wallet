package org.dash.wallet.integration.coinbase_integration.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.integration.coinbase_integration.ERROR_ID_INVALID_REQUEST
import org.dash.wallet.integration.coinbase_integration.ERROR_MSG_INVALID_REQUEST
import org.dash.wallet.integration.coinbase_integration.model.CoinbaseErrorResponse
import org.dash.wallet.integration.coinbase_integration.model.SendTransactionToWalletParams
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepositoryInt
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class EnterTwoFaCodeViewModel @Inject constructor(
    private val coinBaseRepository: CoinBaseRepositoryInt
) : ViewModel() {

    private val _loadingState: MutableLiveData<Boolean> = MutableLiveData()
    val loadingState: LiveData<Boolean>
        get() = _loadingState

    private val _transactionState: MutableLiveData<TransactionState> = MutableLiveData()
    val transactionState: LiveData<TransactionState>
        get() = _transactionState

    val twoFaErrorState = SingleLiveEvent<Unit>()

    fun verifyUserAndCompleteTransaction(
        params: SendTransactionToWalletParams?,
        twoFaCode: String
    ) = viewModelScope.launch(Dispatchers.Main) {
        _loadingState.value = true

        params?.let {
            when (val result = coinBaseRepository.sendFundsToWallet(params, twoFaCode)) {
                is ResponseResource.Success -> {
                    _loadingState.value = false
                    if (result.value == null) {
                        _transactionState.value = TransactionState(false)
                    } else {
                        _transactionState.value = TransactionState(true)
                    }
                }

                is ResponseResource.Failure -> {
                    _loadingState.value = false
                    try {
                        val error = result.errorBody?.string()
                        if (result.errorCode == 400 || result.errorCode == 402) {
                            error?.let { errorMsg ->
                                val errorContent = CoinbaseErrorResponse.getErrorMessage(errorMsg)
                                if (errorContent?.id.equals(ERROR_ID_INVALID_REQUEST, true)
                                    && errorContent?.message?.contains(ERROR_MSG_INVALID_REQUEST) == true){
                                    twoFaErrorState.call()
                                } else {
                                    _transactionState.value = TransactionState(false, errorContent?.message)
                                }
                            }
                        }else {
                            _transactionState.value = TransactionState(false, null)
                        }
                    } catch (e: IOException) {
                        _transactionState.value = TransactionState(false, null)
                    }
                }
            }
        }
    }
}



data class TransactionState(
    val isTransactionSuccessful: Boolean,
    val responseMessage: String? = null
)