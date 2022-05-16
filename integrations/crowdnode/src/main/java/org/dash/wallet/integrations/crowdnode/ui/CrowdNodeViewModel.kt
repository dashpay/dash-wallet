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

import android.content.Intent
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
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeApi
import org.dash.wallet.integrations.crowdnode.model.OnlineAccountStatus
import org.dash.wallet.integrations.crowdnode.model.SignUpStatus
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConfig
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
    exchangeRatesProvider: ExchangeRatesProvider
) : ViewModel() {
    val navigationCallback = SingleLiveEvent<NavigationRequest>()
    val networkError = SingleLiveEvent<Unit>()

    private val _accountAddress = MutableLiveData<Address>()
    val accountAddress: LiveData<Address>
        get() = _accountAddress

    val needPassphraseBackUp
        get() = globalConfig.remindBackupSeed

    val termsAccepted = MutableLiveData(false)

    private val _hasEnoughBalance = MutableLiveData<Boolean>()
    val hasEnoughBalance: LiveData<Boolean>
        get() = _hasEnoughBalance

    private val _dashBalance = MutableLiveData<Coin>()
    val dashBalance: LiveData<Coin>
        get() = _dashBalance

    var signUpStatus: LiveData<SignUpStatus> = MediatorLiveData<SignUpStatus>().apply {
        addSource(crowdNodeApi.signUpStatus.asLiveData(), this::setValue)
        value = SignUpStatus.NotStarted//crowdNodeApi.signUpStatus.value
    }

    var onlineAccountStatus: LiveData<OnlineAccountStatus> = MediatorLiveData<OnlineAccountStatus>().apply {
        addSource(crowdNodeApi.onlineAccountStatus.asLiveData(), this::setValue)
        value = crowdNodeApi.onlineAccountStatus.value
    }

    var crowdNodeError: LiveData<Exception?> = MediatorLiveData<Exception?>().apply {
        addSource(crowdNodeApi.apiError.asLiveData(), this::setValue)
        value = crowdNodeApi.apiError.value
    }

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

    val isFirstDeposit: Boolean
        get() = !crowdNodeApi.hasAnyDeposits()

    init {
        walletDataProvider.observeBalance()
            .distinctUntilChanged()
            .onEach {
                _dashBalance.postValue(it)
                _hasEnoughBalance.postValue(it >= CrowdNodeConstants.MINIMUM_REQUIRED_DASH)
            }
            .launchIn(viewModelScope)

        exchangeRatesProvider.observeExchangeRate(globalConfig.exchangeCurrencyCode)
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
        crowdNodeApi.startTrackingLinked(_accountAddress.value!!)
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
        crowdNodeApi.apiError.value = null
    }

    fun retrySignup() {
        viewModelScope.launch {
            resetAddressAndApi()
            signUp()
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

    suspend fun deposit(value: Coin): Boolean {
        return crowdNodeApi.deposit(value, emptyWallet = value >= dashBalance.value)
    }

    suspend fun withdraw(value: Coin): Boolean {
        return crowdNodeApi.withdraw(value)
    }

    private suspend fun getOrCreateAccountAddress(): Address {
        return crowdNodeApi.accountAddress ?: createNewAccountAddress()
    }

    private suspend fun createNewAccountAddress(): Address {
        val address = walletDataProvider.freshReceiveAddress()
        config.setPreference(CrowdNodeConfig.ACCOUNT_ADDRESS, address.toBase58())

        return address
    }

    private suspend fun resetAddressAndApi() {
        _accountAddress.value = createNewAccountAddress()
        crowdNodeApi.reset()
    }
}