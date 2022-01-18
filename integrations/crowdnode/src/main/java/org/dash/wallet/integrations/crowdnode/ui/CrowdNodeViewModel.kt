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
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.TestNet3Params
import org.dash.wallet.common.BuildConfig
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.services.SendPaymentService
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.system.measureTimeMillis

enum class NavigationRequest {
    BackupPassphrase, RestoreWallet
}

@HiltViewModel
class CrowdNodeViewModel @Inject constructor(
    private val config: Configuration,
    private val walletDataProvider: WalletDataProvider,
    private val paymentsService: SendPaymentService
) : ViewModel() {
    companion object {
        val MINIMUM_REQUIRED_DASH: Coin = Coin.valueOf(100000)
        val OFFSET: Coin = Coin.valueOf(546)
        val SIGNUP_REQUEST: Coin = Coin.valueOf(2048)

        val CROWD_NODE_ADDRESS = if (BuildConfig.DEBUG) { // TODO: network, not build type
            "yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy"
        } else {
            "XjbaGWaGnvEtuQAUoBgDxJWe8ZNv45upG2"
        }
    }

    val navigationCallback = SingleLiveEvent<NavigationRequest>()

    private val params = TestNet3Params.get() // TODO
    private val crowdNodeAddress = Address.fromBase58(params, CROWD_NODE_ADDRESS)
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

    init {
        walletDataProvider.observeBalance()
            .distinctUntilChanged()
            .onEach {
                _dashBalance.postValue(it)
                _hasEnoughBalance.postValue(it >= MINIMUM_REQUIRED_DASH)
            }
            .launchIn(viewModelScope)
    }
    
    fun backupPassphrase() {
        navigationCallback.postValue(NavigationRequest.BackupPassphrase)
    }

    fun restoreWallet() {
        navigationCallback.postValue(NavigationRequest.RestoreWallet)
    }

    fun signUp() {
        // TODO: Move to crowdnode API, viewModel shouldn't care about sending coins
        // and tracking transaction
        viewModelScope.launch {
            Log.i("CROWDNODE", "sending to address: ${crowdNodeAddress.toBase58()}")
            Log.i("CROWDNODE", "sending from address: ${accountAddress.toBase58()}")
//            paymentsService.sendCoins(accountAddress, MINIMUM_REQUIRED_DASH)
            paymentsService.sendCoins(crowdNodeAddress, OFFSET + SIGNUP_REQUEST, accountAddress)
        }
    }

    private fun getOrCreateAccountAddress(): Address {
        val savedAddress = config.crowdNodeAccountAddress
        Log.i("CROWDNODE", "crowdnode savedAddress: ${savedAddress}")

        return if (savedAddress.isNullOrEmpty()) {
            val address = walletDataProvider.freshReceiveAddress()
            config.crowdNodeAccountAddress = address.toBase58()
            return address
        } else {
            Address.fromString(params, savedAddress)
        }
    }
}