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
import de.schildbach.wallet.payments.MaxOutputAmountCoinSelector
import org.bitcoinj.utils.MonetaryFormat
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.ZeroConfCoinSelector
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.WalletDataProvider
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

    private val _address = MutableLiveData("")
    val address: LiveData<String>
        get() = _address

    open fun initPaymentIntent(paymentIntent: PaymentIntent) {
        basePaymentIntent = paymentIntent

        if (paymentIntent.hasAddress()) { // avoid the exception for a missing address in a BIP70 payment request
            _address.value = paymentIntent.address.toBase58()
        }
    }

    protected fun createSendRequest(
        mayEditAmount: Boolean,
        paymentIntent: PaymentIntent,
        signInputs: Boolean,
        forceEnsureMinRequiredFee: Boolean
    ): SendRequest {
        paymentIntent.setInstantX(false) // to make sure the correct instance of Transaction class is used in toSendRequest() method
        val sendRequest = paymentIntent.toSendRequest()
        sendRequest.coinSelector = ZeroConfCoinSelector.get()
        sendRequest.useInstantSend = false
        sendRequest.feePerKb = Constants.ECONOMIC_FEE
        sendRequest.ensureMinRequiredFee = forceEnsureMinRequiredFee
        sendRequest.signInputs = signInputs

        val walletBalance = wallet.getBalance(MaxOutputAmountCoinSelector())
        sendRequest.emptyWallet = mayEditAmount && walletBalance == paymentIntent.amount

        return sendRequest
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
