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

package org.dash.wallet.integrations.crowdnode.ui

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeApi
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants
import javax.inject.Inject

enum class NavigationRequest {
    BackupPassphrase, RestoreWallet, BuyDash
}

@HiltViewModel
class CrowdNodeViewModel @Inject constructor(
    private val config: Configuration,
    private val walletDataProvider: WalletDataProvider,
    private val crowdNodeApi: CrowdNodeApi
) : ViewModel() {
    val navigationCallback = SingleLiveEvent<NavigationRequest>()

    private val accountAddress = getOrCreateAccountAddress()

    val dashAccountAddress: String = accountAddress.toBase58()
    val needPassphraseBackUp
        get() = config.remindBackupSeed

    private val _hasEnoughBalance = MutableLiveData<Boolean>()
    val hasEnoughBalance: LiveData<Boolean>
        get() = _hasEnoughBalance

    private val _dashBalance = MutableLiveData<Coin>()
    val dashBalance: LiveData<Coin>
        get() = _dashBalance

    private val _crowdNodeAccountFound = MutableLiveData<Boolean>(true)
    val crowdNodeAccountFound: LiveData<Boolean>
        get() = _crowdNodeAccountFound

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

    fun signUp() {
        viewModelScope.launch {
            crowdNodeApi.signUp(accountAddress)
        }
    }

    private fun getOrCreateAccountAddress(): Address {
//        val savedAddress = config.crowdNodeAccountAddress

//        return if (savedAddress.isNullOrEmpty()) {
            val address = walletDataProvider.freshReceiveAddress()
        Log.i("CROWDNODE", "crowdnode savedAddress: ${address}")
            config.crowdNodeAccountAddress = address.toBase58()
            return address
//        } else {
//            Address.fromString(Constants.NETWORK_PARAMETERS, savedAddress)
//        }
    }
}