/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.integrations.crowdnode.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.core.os.bundleOf
import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.Status
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeApi
import org.dash.wallet.integrations.crowdnode.model.MessageStatusException
import org.dash.wallet.integrations.crowdnode.model.OnlineAccountStatus
import org.dash.wallet.integrations.crowdnode.model.SignUpStatus
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConfig
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants
import java.io.IOException
import javax.inject.Inject

enum class NavigationRequest {
    BackupPassphrase, RestoreWallet, BuyDash, SendReport
}

@HiltViewModel
class CrowdNodeViewModel @Inject constructor(
    private val globalConfig: Configuration,
    private val config: CrowdNodeConfig,
    private val walletDataProvider: WalletDataProvider,
    private val crowdNodeApi: CrowdNodeApi,
    private val clipboardManager: ClipboardManager,
    exchangeRatesProvider: ExchangeRatesProvider,
    val analytics: AnalyticsService
) : ViewModel() {
    companion object {
        const val URL_ARG = "url"
        const val EMAIL_ARG = "email"
    }

    private var emailForAccount = ""

    val navigationCallback = SingleLiveEvent<NavigationRequest>()
    val networkError = SingleLiveEvent<Unit>()
    val onlineAccountRequest = SingleLiveEvent<Map<String, String>>()

    private val _accountAddress = MutableLiveData<Address>()
    val accountAddress: LiveData<Address>
        get() = _accountAddress

    val primaryDashAddress
        get() = crowdNodeApi.primaryAddress

    val needPassphraseBackUp
        get() = globalConfig.remindBackupSeed

    val termsAccepted = MutableLiveData(false)

    private val _hasEnoughBalance = MutableLiveData<Boolean>()
    val hasEnoughBalance: LiveData<Boolean>
        get() = _hasEnoughBalance

    private val _dashBalance = MutableLiveData<Coin>()
    val dashBalance: LiveData<Coin>
        get() = _dashBalance

    val signUpStatus: SignUpStatus
        get() = crowdNodeApi.signUpStatus.value

    val onlineAccountStatus: OnlineAccountStatus
        get() = crowdNodeApi.onlineAccountStatus.value

    val crowdNodeError: Exception?
        get() = crowdNodeApi.apiError.value

    private val _exchangeRate: MutableLiveData<ExchangeRate> = MutableLiveData()
    val exchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    private val _crowdNodeBalance: MutableLiveData<Coin> = MutableLiveData()
    val crowdNodeBalance: LiveData<Coin>
        get() = _crowdNodeBalance

    private val _isBalanceLoading: MutableLiveData<Boolean> = MutableLiveData()
    val isBalanceLoading: LiveData<Boolean>
        get() = _isBalanceLoading

    val dashFormat: MonetaryFormat
        get() = globalConfig.format.noCode()

    val networkParameters: NetworkParameters
        get() = walletDataProvider.networkParameters

    val shouldShowFirstDepositBanner: Boolean
        get() = !crowdNodeApi.hasAnyDeposits() &&
                (crowdNodeBalance.value?.isLessThan(CrowdNodeConstants.MINIMUM_DASH_DEPOSIT) ?: true)

    init {
        walletDataProvider.observeBalance()
            .distinctUntilChanged()
            .onEach {
                _dashBalance.postValue(it)
                _hasEnoughBalance.postValue(it >= CrowdNodeConstants.MINIMUM_REQUIRED_DASH)
            }
            .launchIn(viewModelScope)

        exchangeRatesProvider
            .observeExchangeRate(globalConfig.exchangeCurrencyCode!!)
            .onEach(_exchangeRate::postValue)
            .launchIn(viewModelScope)

        crowdNodeApi.balance
            .onEach {
                when (it.status) {
                    Status.LOADING -> {
                        _isBalanceLoading.postValue(true)
                        _crowdNodeBalance.postValue(it.data ?: Coin.ZERO)
                    }
                    Status.SUCCESS -> {
                        _isBalanceLoading.postValue(false)
                        _crowdNodeBalance.postValue(it.data ?: Coin.ZERO)
                    }
                    Status.ERROR -> {
                        _isBalanceLoading.postValue(false)
                        networkError.call()
                    }
                    else -> _isBalanceLoading.postValue(false)
                }
            }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            _accountAddress.value = getOrCreateAccountAddress()
            crowdNodeApi.refreshBalance()
        }
    }
    
    fun backupPassphrase() {
        navigationCallback.postValue(NavigationRequest.BackupPassphrase)
    }

    fun restoreWallet() {
        navigationCallback.postValue(NavigationRequest.RestoreWallet)
    }

    fun buyDash() {
        navigationCallback.postValue(NavigationRequest.BuyDash)
    }

    fun sendReport() {
        navigationCallback.postValue(NavigationRequest.SendReport)
    }

    fun signUp() {
        crowdNodeApi.persistentSignUp(_accountAddress.value!!)
    }

    fun linkOnlineAccount() {
        val address = _accountAddress.value!!
        val apiLinkUrl = CrowdNodeConstants.getApiLinkUrl(address)
        crowdNodeApi.trackLinkingAccount(address)
        onlineAccountRequest.postValue(mapOf(
            URL_ARG to apiLinkUrl
        ))
    }

    fun cancelLinkingOnlineAccount() {
        crowdNodeApi.stopTrackingLinked()
    }

    fun resetSignUp() {
        viewModelScope.launch {
            resetAddressAndApi()
        }
    }

    fun clearError() {
        if (crowdNodeApi.apiError.value is MessageStatusException) {
            viewModelScope.launch {
                config.setPreference(CrowdNodeConfig.SIGNED_EMAIL_MESSAGE_ID, -1)
            }
        }

        crowdNodeApi.apiError.value = null
    }

    fun retrySignup() {
        viewModelScope.launch {
            resetAddressAndApi()
            signUp()
        }
    }

    fun resetAddress() {
        viewModelScope.launch {
            resetAddressAndApi()
        }
    }

    fun changeNotifyWhenDone(toNotify: Boolean) {
        crowdNodeApi.showNotificationOnResult = toNotify
    }

    fun setNotificationIntent(intent: Intent?) {
        crowdNodeApi.notificationIntent = intent
    }

    suspend fun getIsInfoShown(): Boolean {
        return config.getPreference(CrowdNodeConfig.INFO_SHOWN) ?: false
    }

    fun setInfoShown(isShown: Boolean) {
        viewModelScope.launch {
            config.setPreference(CrowdNodeConfig.INFO_SHOWN, isShown)
        }
    }

    suspend fun getShouldShowConfirmationDialog(): Boolean {
        return crowdNodeApi.onlineAccountStatus.value == OnlineAccountStatus.Confirming &&
               !(config.getPreference(CrowdNodeConfig.CONFIRMATION_DIALOG_SHOWN) ?: false)
    }

    fun setConfirmationDialogShown(isShown: Boolean) {
        viewModelScope.launch {
            config.setPreference(CrowdNodeConfig.CONFIRMATION_DIALOG_SHOWN, isShown)
        }
    }

    suspend fun getShouldShowOnlineInfo(): Boolean {
        return signUpStatus != SignUpStatus.LinkedOnline &&
                !(config.getPreference(CrowdNodeConfig.ONLINE_INFO_SHOWN) ?: false)
    }

    fun setOnlineInfoShown(isShown: Boolean) {
        viewModelScope.launch {
            config.setPreference(CrowdNodeConfig.ONLINE_INFO_SHOWN, isShown)
        }
    }

    suspend fun deposit(value: Coin, checkBalanceConditions: Boolean): Boolean {
        val emptyWallet = value >= dashBalance.value
        return crowdNodeApi.deposit(value, emptyWallet, checkBalanceConditions)
    }

    suspend fun withdraw(value: Coin): Boolean {
        return crowdNodeApi.withdraw(value)
    }

    fun copyPrimaryAddress() {
        clipboardManager.setPrimaryClip(
            ClipData.newPlainText(
                "primary dash address",
                primaryDashAddress.toString()
            )
        )
    }

    fun copyAccountAddress() {
        clipboardManager.setPrimaryClip(
            ClipData.newPlainText(
                "dash address",
                accountAddress.value.toString()
            )
        )
    }

    fun observeSignUpStatus(): LiveData<SignUpStatus> {
        return crowdNodeApi.signUpStatus.asLiveData()
    }

    fun observeOnlineAccountStatus(): LiveData<OnlineAccountStatus> {
        return crowdNodeApi.onlineAccountStatus.asLiveData()
    }

    fun observeCrowdNodeError(): LiveData<Exception?> {
        return crowdNodeApi.apiError.asLiveData()
    }

    fun signAndSendEmail(email: String) {
        viewModelScope.launch {
            emailForAccount = email

            try {
                crowdNodeApi.registerEmailForAccount(email)
            } catch (ex: IOException) {
                networkError.postCall()
            }
        }
    }

    fun initiateOnlineSignUp() {
        val signupUrl = CrowdNodeConstants.getProfileUrl(networkParameters)
        onlineAccountRequest.postValue(mapOf(
            URL_ARG to signupUrl,
            EMAIL_ARG to emailForAccount
        ))
    }

    fun getAccountUrl(): String {
        return CrowdNodeConstants.getFundsOpenUrl(_accountAddress.value!!)
    }

    fun finishSignUpToOnlineAccount() {
        crowdNodeApi.setOnlineAccountCreated()
    }

    fun logEvent(eventName: String) {
        analytics.logEvent(eventName, bundleOf())
    }

    private fun getOrCreateAccountAddress(): Address {
        return crowdNodeApi.accountAddress ?: createNewAccountAddress()
    }

    private fun createNewAccountAddress(): Address {
        val address = walletDataProvider.freshReceiveAddress()
        globalConfig.crowdNodeAccountAddress = address.toBase58()

        return address
    }

    private suspend fun resetAddressAndApi() {
        _accountAddress.value = createNewAccountAddress()
        crowdNodeApi.reset()
    }
}