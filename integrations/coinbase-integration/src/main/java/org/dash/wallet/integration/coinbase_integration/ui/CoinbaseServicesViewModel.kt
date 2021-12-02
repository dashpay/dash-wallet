package org.dash.wallet.integration.coinbase_integration.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.dash.wallet.integration.coinbase_integration.model.CoinBaseUserAccountData
import org.dash.wallet.integration.coinbase_integration.network.ResponseResource
import org.dash.wallet.integration.coinbase_integration.repository.CoinBaseRepository
import javax.inject.Inject

@HiltViewModel
class CoinbaseServicesViewModel @Inject constructor(
    application: Application,
    private val coinBaseRepository: CoinBaseRepository,
) : AndroidViewModel(application) {

    private val _user: MutableLiveData<CoinBaseUserAccountData> = MutableLiveData()
    val user: LiveData<CoinBaseUserAccountData>
        get() = _user

    private val _showLoading: MutableLiveData<Boolean> = MutableLiveData()
    val showLoading: LiveData<Boolean>
        get() = _showLoading

    private val _userAccountError: MutableLiveData<Boolean> = MutableLiveData()
    val userAccountError: LiveData<Boolean>
        get() = _userAccountError

    private fun getUserAccountInfo() = viewModelScope.launch {

        when (val response = coinBaseRepository.getUserAccount()) {
            is ResponseResource.Success -> {
                _showLoading.value = false
                val userAccountData = response.value.body()?.data?.firstOrNull {
                    it.balance?.currency?.equals("DASH") ?: false
                }

                if (userAccountData == null) {
                    _userAccountError.value = true
                } else {
                    _user.value = userAccountData
                }
            }
            is ResponseResource.Loading -> {
                _showLoading.value = true
            }
            is ResponseResource.Failure -> {
                _showLoading.value = false
            }
        }
    }

    fun disconnectCoinbaseAccount() {
        viewModelScope.launch {
            coinBaseRepository.disconnectCoinbaseAccount()
        }
    }

    init {
        getUserAccountInfo()
    }
}
