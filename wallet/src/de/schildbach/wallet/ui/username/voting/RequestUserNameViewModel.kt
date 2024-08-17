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
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.CreateIdentityService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.dash.wallet.common.WalletDataProvider
import javax.inject.Inject

data class RequestUserNameUIState(
    val usernameVerified: Boolean = false,
    val usernameRequestSubmitting: Boolean = false,
    val usernameRequestSubmitted: Boolean = false,
    val usernameCheckSuccess: Boolean = false,
    val usernameSubmittedError: Boolean = false,
    val usernameLengthValid: Boolean = false,
    val usernameCharactersValid: Boolean = false,
    val usernameTooShort: Boolean = true,  // default zero length username
    val usernameContestable: Boolean = false,
    val usernameContested: Boolean = false,
    val usernameExists: Boolean = false,
    val usernameBlocked: Boolean = false,
    val enoughBalance: Boolean = false,
    val votingPeriodStart: Long = System.currentTimeMillis()
)

@HiltViewModel
class RequestUserNameViewModel @Inject constructor(
    val walletApplication: WalletApplication,
    val identityConfig: BlockchainIdentityConfig,
    val walletData: WalletDataProvider,
    val platformRepo: PlatformRepo
) : ViewModel() {
    private val _uiState = MutableStateFlow(RequestUserNameUIState())
    val uiState: StateFlow<RequestUserNameUIState> = _uiState.asStateFlow()

    private val _requestedUserNameLink = MutableStateFlow<String?>(null)
    val requestedUserNameLink: StateFlow<String?> = _requestedUserNameLink.asStateFlow()

    var requestedUserName: String? = null

    val walletBalance: Coin
        get() = walletData.getWalletBalance()
    suspend fun isUserNameRequested(): Boolean =
        identityConfig.get(BlockchainIdentityConfig.REQUESTED_USERNAME).isNullOrEmpty().not()
    suspend fun hasUserCancelledVerification(): Boolean =
        identityConfig.get(BlockchainIdentityConfig.CANCELED_REQUESTED_USERNAME_LINK) ?: false

    fun canAffordNonContestedUsername(): Boolean = walletBalance >= Constants.DASH_PAY_FEE
    fun canAffordContestedUsername(): Boolean = walletBalance >= Constants.DASH_PAY_FEE_CONTESTED

    init {

        viewModelScope.launch {
            _requestedUserNameLink.value = identityConfig.get(BlockchainIdentityConfig.REQUESTED_USERNAME_LINK)
        }
    }

    private fun triggerIdentityCreation(reuseTransaction: Boolean) {
        val username = requestedUserName!!
        if (reuseTransaction) {
            walletApplication.startService(CreateIdentityService.createIntentForNewUsername(walletApplication, username))
        } else {
            walletApplication.startService(CreateIdentityService.createIntent(walletApplication, username))
        }
    }

    fun submit() {
        // Reset ui state for retry if needed
        resetUiForRetrySubmit()

        // send the request / create username
        triggerIdentityCreation(false)
        // if call success
        updateUiForApiSuccess()
        // else if call failed
        // updateUiForApiError()
    }

    private fun resetUiForRetrySubmit() {
        _uiState.update {
            it.copy(
                usernameSubmittedError = false,
                usernameRequestSubmitted = false,
                usernameRequestSubmitting = false
            )
        }
    }
    fun setRequestedUserNameLink(link: String) {
        _requestedUserNameLink.value = link
    }

    private suspend fun updateConfig() {
        requestedUserName?.let { name ->
            identityConfig.set(BlockchainIdentityConfig.REQUESTED_USERNAME, name)
        }
        _requestedUserNameLink.value.let { link ->
            identityConfig.set(BlockchainIdentityConfig.REQUESTED_USERNAME_LINK, link ?: "")
        }
    }

        _uiState.update {
            it.copy(
                usernameCheckSuccess = true,
                usernameRequestSubmitting = false,
                usernameRequestSubmitted = true,
                usernameSubmittedError = false
            )
        }
    }
    private fun updateUiForApiError() {
        _uiState.update { it ->
            it.copy(
                usernameCheckSuccess = false,
                usernameSubmittedError = true
            )
        }
    }

    fun checkUsername(requestedUserName: String?) {
        viewModelScope.launch {
            requestedUserName?.let { username ->
                val usernameSearchResult = platformRepo.getUsername(username)
                val usernameExists = when (usernameSearchResult.status) {
                    Status.SUCCESS -> {
                        usernameSearchResult.data != null
                    }
                    else -> false
                }
                val usernameContested = false // TODO: make the call
                val usernameBlocked = false // TODO: make the call
                _uiState.update {
                    it.copy(
                        usernameVerified = true,
                        usernameCheckSuccess = true,
                        usernameSubmittedError = false,
                        usernameContested = usernameContested,
                        usernameExists = usernameExists,
                        usernameBlocked = usernameBlocked
                    )
                }
            }
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
            identityConfig.set(BlockchainIdentityConfig.REQUESTED_USERNAME, "")
            identityConfig.set(BlockchainIdentityConfig.REQUESTED_USERNAME_LINK, "")
            identityConfig.set(BlockchainIdentityConfig.CANCELED_REQUESTED_USERNAME_LINK, true)
        }
    }

    private fun isUsernameContestable(username: String): Boolean {
        val regex = Regex("^[a-zA-Z01-]{3,19}$")
        return regex.matches(username)
    }

    private fun validateUsernameSize(uname: String): Boolean {
        return uname.length in Constants.USERNAME_MIN_LENGTH..Constants.USERNAME_MAX_LENGTH
    }

    private fun validateUsernameCharacters(uname: String): Pair<Boolean, Boolean> {
        val alphaNumHyphenValid = !Regex("[^a-zA-Z0-9\\-]").containsMatchIn(uname)
        val startOrEndWithHyphen = uname.startsWith("-") || uname.endsWith("-")
        //val containsHyphen = uname.contains("-")

        return Pair(alphaNumHyphenValid, startOrEndWithHyphen)
    }

    fun checkUsernameValid(username: String): Boolean {
        val validLength = validateUsernameSize(username)
        val (validCharacters, startOrEndWithHyphen) = validateUsernameCharacters(username)
        val contestable = isUsernameContestable(username)
        val enoughBalance = if (contestable) {
            walletBalance >= Constants.DASH_PAY_FEE_CONTESTED
        } else {
            walletBalance >= Constants.DASH_PAY_FEE
        }
        _uiState.update {
            it.copy(
                usernameLengthValid = validLength,
                usernameCharactersValid = validCharacters && !startOrEndWithHyphen,
                usernameContestable = contestable,
                enoughBalance = enoughBalance,
                usernameTooShort = username.isEmpty(),
                usernameSubmittedError = false,
                usernameCheckSuccess = false,
                usernameVerified = false
            )
        }
        return validCharacters && validLength
    }
}
