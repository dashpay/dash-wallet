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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject


data class RequestUserNameUIState(
    val usernameVerified: Boolean = false,
    val usernameSubmittedSuccess: Boolean = false,
    val usernameSubmittedError: Boolean = false
)

@HiltViewModel
class RequestUserNameViewModel @Inject constructor(
    val dashPayConfig: DashPayConfig
) : ViewModel() {
    private val _uiState = MutableStateFlow(RequestUserNameUIState())
    val uiState: StateFlow<RequestUserNameUIState> = _uiState.asStateFlow()

    var username: String? = null

    fun submit() {
        // Reset ui state for retry if needed
        // resetUiForRetrySubmit()

        // TODO("Change to submit USERNAME")
        // if call success
        updateUiForApiSuccess()
        // else if call failed
       // updateUiForApiError()
    }

    private fun resetUiForRetrySubmit() {
        _uiState.update {
            it.copy(
                usernameVerified = false,
                usernameSubmittedSuccess = false,
                usernameSubmittedError = false
            )
        }
    }
    private fun updateUiForApiSuccess() {
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
    }
    private fun updateUiForApiError() {
        _uiState.update { it ->
            it.copy(
                usernameSubmittedSuccess = false,
                usernameSubmittedError = true
            )
        }
    }

    fun verify() {
        _uiState.update {
            it.copy(
                usernameVerified = true
            )
        }
    }
}
