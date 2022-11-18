/*
 * Copyright 2019 Dash Core Group
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

import androidx.lifecycle.MutableLiveData
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.security.BiometricHelper
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.wallet.SendRequest
import org.dash.wallet.common.Configuration
import javax.inject.Inject

@HiltViewModel
class SendCoinsViewModel @Inject constructor(
    walletApplication: WalletApplication,
    configuration: Configuration,
    val biometricHelper: BiometricHelper
) : SendCoinsBaseViewModel(walletApplication, configuration) {

    enum class State {
        INPUT,  // asks for confirmation
        DECRYPTING, SIGNING, SENDING, SENT, FAILED // sending states
    }

    @JvmField
    val state = MutableLiveData<State>()

    @JvmField
    var finalPaymentIntent: PaymentIntent? = null

    @JvmField
    var dryrunSendRequest: SendRequest? = null

    @JvmField
    var dryrunException: Exception? = null

    fun createSendRequest(finalPaymentIntent: PaymentIntent, signInputs: Boolean, forceEnsureMinRequiredFee: Boolean): SendRequest {
        return createSendRequest(wallet, basePaymentIntentValue.mayEditAmount(), finalPaymentIntent, signInputs, forceEnsureMinRequiredFee)
    }

    fun signAndSendPayment(editedAmount: Coin, exchangeRate: ExchangeRate?) {
        state.value = State.DECRYPTING
        finalPaymentIntent = basePaymentIntentValue.mergeWithEditedValues(editedAmount, null)
        super.signAndSendPayment(finalPaymentIntent!!, dryrunSendRequest!!.ensureMinRequiredFee, exchangeRate, basePaymentIntentValue.memo)
    }

    override fun signAndSendPayment(
        sendRequest: SendRequest,
        txAlreadyCompleted: Boolean,
        checkBalanceConditions: Boolean
    ) {
        state.value = State.SIGNING
        super.signAndSendPayment(sendRequest, false, checkBalanceConditions)
    }
}