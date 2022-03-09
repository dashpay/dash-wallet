/*
 * Copyright 2020 Dash Core Group.
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

package org.dash.wallet.integration.liquid.ui

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.liveData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.livedata.NetworkState
import org.dash.wallet.common.ui.ConnectivityViewModel
import org.dash.wallet.integration.liquid.data.LiquidClient
import javax.inject.Inject
import kotlin.coroutines.suspendCoroutine

@ExperimentalCoroutinesApi
@HiltViewModel
class LiquidViewModel @Inject constructor(
    private val config: Configuration,
    walletDataProvider: WalletDataProvider,
    networkState: NetworkState
) : ConnectivityViewModel(networkState) {

    val defaultCurrency = walletDataProvider.defaultCurrencyCode()

    private val triggerLiquidBalanceUpdate = MutableLiveData<Unit>()

    fun updateLiquidBalance() {
        triggerLiquidBalanceUpdate.value = Unit
    }

    var lastLiquidBalance: String?
        get() = config.lastLiquidBalance
        set(value) {
            config.lastLiquidBalance = value
        }

    private val liquidClient = LiquidClient.getInstance()!!

    val liquidBalanceLiveData = Transformations.switchMap(triggerLiquidBalanceUpdate) {
        liveData {
            emit(Resource.loading())
            val result = suspendCoroutine<Resource<String>> { continuation ->
                liquidClient.getUserAccountBalance(
                    liquidClient.storedSessionId!!,
                    object : LiquidClient.Callback<String> {
                        override fun onSuccess(data: String) {
                            continuation.resumeWith(Result.success(Resource.success(data)))
                        }

                        override fun onError(e: Exception?) {
                            continuation.resumeWith(Result.success(Resource.error(e!!)))
                        }
                    }
                )
            }
            emit(result)
        }
    }
}
