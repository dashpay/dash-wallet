package de.schildbach.wallet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.liveData
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.data.BlockchainState
import kotlinx.coroutines.Dispatchers
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.liquid.data.LiquidClient
import org.dash.wallet.integration.uphold.data.UpholdClient
import java.math.BigDecimal
import kotlin.coroutines.suspendCoroutine


class BuyAndSellViewModel(application: Application) : AndroidViewModel(application) {

    val blockChainStateLiveData = AppDatabase.getAppDatabase().blockchainStateDao().load()

    val networkOnlineLiveData = Transformations.switchMap(blockChainStateLiveData) {
        liveData(Dispatchers.IO) {
            if (it != null) {
                emit(it.impediments.contains(BlockchainState.Impediment.NETWORK))
            } else {
                emit(false)
            }
        }
    }

    private val triggerUploadBalanceUpdate = MutableLiveData<Unit>()

    fun updateUpholdBalance() {
        triggerUploadBalanceUpdate.value = Unit
    }

    private val upholdClient = UpholdClient.getInstance()

    val upholdBalanceLiveData = Transformations.switchMap(triggerUploadBalanceUpdate) {
        liveData {
            emit(Resource.loading())
            val result = suspendCoroutine<Resource<BigDecimal>> { continuation ->
                upholdClient.getDashBalance(object : UpholdClient.Callback<BigDecimal> {
                    override fun onSuccess(data: BigDecimal) {
                        continuation.resumeWith(Result.success(Resource.success(data)))
                    }

                    override fun onError(e: java.lang.Exception, otpRequired: Boolean) {
                        continuation.resumeWith(Result.failure(e))
                    }
                })
            }
            emit(result)
        }
    }

    private val triggerLiquidBalanceUpdate = MutableLiveData<Unit>()

    fun updateLiquidBalance() {
        triggerLiquidBalanceUpdate.value = Unit
    }

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
                        continuation.resumeWith(Result.failure(e!!))
                    }
                })
            }
            emit(result)
        }
    }

}