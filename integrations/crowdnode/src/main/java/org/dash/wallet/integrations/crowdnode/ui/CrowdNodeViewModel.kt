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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeApi
import org.dash.wallet.integrations.crowdnode.api.SignUpStatus
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants
import javax.inject.Inject

enum class NavigationRequest {
    BackupPassphrase, RestoreWallet, BuyDash, SendReport
}

@HiltViewModel
class CrowdNodeViewModel @Inject constructor(
    private val config: Configuration,
    private val walletDataProvider: WalletDataProvider,
    private val crowdNodeApi: CrowdNodeApi
) : ViewModel() {
    val navigationCallback = SingleLiveEvent<NavigationRequest>()

    private val _accountAddress = MutableLiveData(getOrCreateAccountAddress())
    var accountAddress: LiveData<String> = MediatorLiveData<String>().apply {
        addSource(_accountAddress) {
            value = it.toBase58()
        }
    }

    val needPassphraseBackUp
        get() = config.remindBackupSeed

    private val _hasEnoughBalance = MutableLiveData<Boolean>()
    val hasEnoughBalance: LiveData<Boolean>
        get() = _hasEnoughBalance

    private val _dashBalance = MutableLiveData<Coin>()
    val dashBalance: LiveData<Coin>
        get() = _dashBalance

    var crowdNodeSignUpStatus: LiveData<SignUpStatus> = MediatorLiveData<SignUpStatus>().apply {
        addSource(crowdNodeApi.signUpStatus.asLiveData(), this::setValue)
        value = crowdNodeApi.signUpStatus.value
    }

    val crowdNodeError: Exception?
        get() = crowdNodeApi.apiError

    val termsAccepted = MutableLiveData(false)

    init {
        walletDataProvider.observeBalance()
            .distinctUntilChanged()
            .onEach {
                _dashBalance.postValue(it)
                _hasEnoughBalance.postValue(it >= CrowdNodeConstants.MINIMUM_REQUIRED_DASH)
            }
            .launchIn(viewModelScope)
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

    fun signUpInBackground() {
        crowdNodeApi.persistentSignUp(_accountAddress.value!!)
    }

    fun reset() {
        _accountAddress.value = createNewAccountAddress()
        crowdNodeApi.reset()
    }

    fun retry() {
        reset()
        signUpInBackground()
    }

    fun changeNotifyWhenDone(toNotify: Boolean) {
        crowdNodeApi.showNotificationOnResult = toNotify
    }

    fun setNotificationIntent(intent: Intent?) {
        crowdNodeApi.notificationIntent = intent
    }

    private fun getOrCreateAccountAddress(): Address {
        val existingAddress = crowdNodeApi.existingAccountAddress

        if (existingAddress != null) {
            config.crowdNodeAccountAddress = existingAddress.toBase58()
            return existingAddress
        }

        val savedAddress = config.crowdNodeAccountAddress

        return if (savedAddress.isNullOrEmpty()) {
            return createNewAccountAddress()
        } else {
            Address.fromString(walletDataProvider.networkParameters, savedAddress)
        }
    }

    private fun createNewAccountAddress(): Address {
        val address = walletDataProvider.freshReceiveAddress()
        config.crowdNodeAccountAddress = address.toBase58()
        return address
    }
}