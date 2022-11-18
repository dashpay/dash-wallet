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

package org.dash.wallet.common.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.dash.wallet.common.livedata.NetworkStateInt
import javax.inject.Inject

@HiltViewModel
open class ConnectivityViewModel @Inject constructor(private val networkStateProvider: NetworkStateInt)
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