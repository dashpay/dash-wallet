package org.dash.wallet.common.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.dash.wallet.common.livedata.NetworkState
import javax.inject.Inject

@ExperimentalCoroutinesApi
@HiltViewModel
open class ConnectivityViewModel @Inject constructor(private val networkStateProvider: NetworkState)
    : ViewModel(){

    private var _isDeviceConnectedToInternet: MutableStateFlow<Boolean> = MutableStateFlow(true)
    val isDeviceConnectedToInternet = _isDeviceConnectedToInternet.asLiveData()

    fun monitorNetworkStateChange(){
        viewModelScope.launch(Dispatchers.Main) {
            networkStateProvider.observeNetworkChangeState().collect {
                _isDeviceConnectedToInternet.value = it
            }
        }
    }
}