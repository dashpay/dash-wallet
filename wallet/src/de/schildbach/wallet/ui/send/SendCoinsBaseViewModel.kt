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
package de.schildbach.wallet.ui.send

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.data.PaymentIntent
import org.bitcoinj.utils.MonetaryFormat
import org.bitcoinj.wallet.SendRequest
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.util.Constants
import javax.inject.Inject

@HiltViewModel
open class SendCoinsBaseViewModel @Inject constructor(
    walletData: WalletDataProvider,
    private val configuration: Configuration
) : ViewModel() {
    val wallet = walletData.wallet!!
    val dashFormat: MonetaryFormat
        get() = configuration.format

    lateinit var basePaymentIntent: PaymentIntent
        private set

    val isInitialized: Boolean
        get() = ::basePaymentIntent.isInitialized

    private val _address = MutableLiveData("")
    val address: LiveData<String>
        get() = _address

    open suspend fun initPaymentIntent(paymentIntent: PaymentIntent) {
        basePaymentIntent = paymentIntent

        if (paymentIntent.hasAddress()) { // avoid the exception for a missing address in a BIP70 payment request
            _address.postValue(paymentIntent.address.toBase58())
        }
    }

    protected fun checkDust(req: SendRequest): Boolean {
        if (req.tx != null) {
            for (output in req.tx.outputs) {
                if (output.isDust) return true
            }
        }
        return false
    }
}
