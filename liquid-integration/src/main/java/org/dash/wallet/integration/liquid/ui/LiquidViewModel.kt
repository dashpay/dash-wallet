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
        set(value) = prefs.edit().putString(Configuration.PREFS_KEY_LAST_LIQUID_BALANCE, value).apply()

    private val liquidClient = LiquidClient.getInstance()!!

    val liquidBalanceLiveData = Transformations.switchMap(triggerLiquidBalanceUpdate) {
        liveData {
            emit(Resource.loading())
            val result = suspendCoroutine<Resource<String>> { continuation ->
                liquidClient.getUserAccountBalance(liquidClient.storedSessionId!!, object : LiquidClient.Callback<String> {
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