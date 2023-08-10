/*
 * Copyright 2020 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.username.voting

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.NetworkStateInt
import javax.inject.Inject


data class RequestUserNameUIState(
    val usernameVerified: Boolean = false,
    val usernameSubmittedSuccess: Boolean = false,
    val usernameSubmittedError: Boolean = false
)

@HiltViewModel
class RequestUserNameViewModel @Inject constructor(
    private val networkState: NetworkStateInt,
    val dashPayConfig: DashPayConfig
) : ViewModel() {
    private val _uiState = MutableStateFlow(RequestUserNameUIState())
    val uiState: StateFlow<RequestUserNameUIState> = _uiState.asStateFlow()

    var username: String? = null
    private val _isNetworkAvailable = MutableLiveData<Boolean>()

    init {
        networkState.isConnected.filterNotNull()
            .onEach(_isNetworkAvailable::postValue)
            .launchIn(viewModelScope)
    }
    fun submit() {
        // TODO("Change to submit USERNAME")
        _uiState.update {
            it.copy(
                usernameVerified = false,
                usernameSubmittedSuccess = false,
                usernameSubmittedError = false
            )
        }

        if (_isNetworkAvailable.value == true) {
            viewModelScope.launch {
                username?.let { name ->
                    dashPayConfig.set(DashPayConfig.REQUESTED_USERNAME, name)
                }
            }

            _uiState.update {
                it.copy(
                    usernameSubmittedSuccess = true,
                    usernameSubmittedError = false
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    usernameSubmittedSuccess = false,
                    usernameSubmittedError = true
                )
            }
        }
    }

    fun verfiy() {
        _uiState.update {
            it.copy(
                usernameVerified = true
            )
        }
    }
}
