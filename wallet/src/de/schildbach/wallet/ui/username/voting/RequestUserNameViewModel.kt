/*
 * Copyright 2023 Dash Core Group.
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

    private val _requestedUserNameLink = MutableStateFlow<String?>(null)
    val requestedUserNameLink: StateFlow<String?> = _requestedUserNameLink.asStateFlow()

    var requestedUserName: String? = null
    suspend fun isUserNameRequested(): Boolean =
        dashPayConfig.get(DashPayConfig.REQUESTED_USERNAME).isNullOrEmpty().not()


    init {
        viewModelScope.launch {
            _requestedUserNameLink.value =
                dashPayConfig.get(DashPayConfig.REQUESTED_USERNAME_LINK)
        }
    }

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
    fun setRequestedUserNameLink(link: String) {
        _requestedUserNameLink.value = link
    }
    private fun updateUiForApiSuccess() {
        viewModelScope.launch {
            requestedUserName?.let { name ->
                dashPayConfig.set(DashPayConfig.REQUESTED_USERNAME, name)
            }
            _requestedUserNameLink.value.let { link ->
                dashPayConfig.set(DashPayConfig.REQUESTED_USERNAME_LINK, link ?: "")
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
        viewModelScope.launch {
            dashPayConfig.set(DashPayConfig.REQUESTED_USERNAME_LINK, _requestedUserNameLink.value ?: "")
            _uiState.update {
                it.copy(
                    usernameVerified = true
                )
            }
        }
    }

    fun cancelRequest() {
        viewModelScope.launch {
            dashPayConfig.set(DashPayConfig.REQUESTED_USERNAME, "")
            dashPayConfig.set(DashPayConfig.REQUESTED_USERNAME_LINK, "")
        }
    }
}
