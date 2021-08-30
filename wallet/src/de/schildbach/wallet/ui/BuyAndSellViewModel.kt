/*
 * Copyright 2021 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
import org.dash.wallet.integration.uphold.data.UpholdClient
import java.math.BigDecimal
import kotlin.coroutines.suspendCoroutine

/**
 * @author Eric Britten
 */

class BuyAndSellViewModel(application: Application) : AndroidViewModel(application) {

    //TODO: move this into UpholdViewModel
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
                        continuation.resumeWith(Result.success(Resource.error(e!!)))
                    }
                })
            }
            emit(result)
        }
    }
}