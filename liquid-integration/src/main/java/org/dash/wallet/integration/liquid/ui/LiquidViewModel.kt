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

import android.app.Application
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.ui.ConnectivityViewModel
import androidx.lifecycle.liveData
import androidx.preference.PreferenceManager
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.integration.liquid.data.LiquidClient
import kotlin.coroutines.suspendCoroutine

class LiquidViewModel(application: Application) : ConnectivityViewModel(application) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(application)
    private val walletDataProvider = application as WalletDataProvider
    val defaultCurrency = walletDataProvider.defaultCurrencyCode()

    private val triggerLiquidBalanceUpdate = MutableLiveData<Unit>()

    fun updateLiquidBalance() {
        triggerLiquidBalanceUpdate.value = Unit
    }

    var lastLiquidBalance: String
        get() = prefs.getString(Configuration.PREFS_KEY_LAST_LIQUID_BALANCE, "0.00")!!
        set(value) = prefs.edit().putString(Configuration.PREFS_KEY_LAST_LIQUID_BALANCE, value)
            .apply()

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
                    })
            }
            emit(result)
        }
    }
}
