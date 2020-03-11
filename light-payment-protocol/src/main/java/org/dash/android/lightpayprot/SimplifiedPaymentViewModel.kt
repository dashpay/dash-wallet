/*
 * Copyright 2020 Dash Core Group
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

package org.dash.android.lightpayprot

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.lifecycle.liveData
import kotlinx.coroutines.Dispatchers
import org.dash.android.lightpayprot.data.SimplifiedPayment
import org.dash.android.lightpayprot.data.SimplifiedPaymentRequest

class SimplifiedPaymentViewModel : ViewModel() {

    private val paymentRequestUrl = MutableLiveData<String>()

    var lightPaymentRepo: LightPaymentRepo = LightPaymentRepo()

    var paymentRequestData: SimplifiedPaymentRequest? = null

//    fun paymentRequest(requestId: String) = liveData(Dispatchers.IO) {
//        emit(Resource.loading(null))
//        emit(paymentRepository.getPaymentRequest(requestId))
//    }

    var paymentRequest = Transformations.switchMap(paymentRequestUrl) { url ->
        liveData(Dispatchers.IO) {
            emit(Resource.loading(null))
            emit(lightPaymentRepo.getPaymentRequest(url))
        }
    }

    fun getPaymentRequest(requestUrl: String) {
        paymentRequestUrl.value = requestUrl
    }

    fun postPayment(paymentUrl: String, payment: SimplifiedPayment) = liveData(Dispatchers.IO) {
        emit(Resource.loading(null))
        emit(lightPaymentRepo.postPayment(paymentUrl, payment))
    }

}