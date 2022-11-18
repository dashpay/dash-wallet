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

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.offline.DirectPaymentTask
import de.schildbach.wallet.offline.DirectPaymentTask.HttpPaymentTask
import de.schildbach.wallet.payments.RequestPaymentRequestTask
import de.schildbach.wallet.payments.RequestPaymentRequestTask.HttpRequestTask
import de.schildbach.wallet.rates.ExchangeRatesRepository
import de.schildbach.wallet_test.BuildConfig
import de.schildbach.wallet_test.R
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.protocols.payments.PaymentProtocol
import org.bitcoinj.wallet.KeyChain.KeyPurpose
import org.bitcoinj.wallet.SendRequest
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.ExchangeRate
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class PaymentProtocolViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    configuration: Configuration
) : SendCoinsBaseViewModel(walletApplication, configuration) {

    companion object {
        val FAKE_FEE_FOR_EXCEPTIONS: Coin =
            org.dash.wallet.common.Constants.ECONOMIC_FEE.multiply(261).divide(1000)
    }

    private val log = LoggerFactory.getLogger(PaymentProtocolFragment::class.java)

    var finalPaymentIntent: PaymentIntent? = null
    var baseSendRequest: SendRequest? = null

    val sendRequestLiveData = MutableLiveData<Resource<SendRequest>>()

    val exchangeRateData: LiveData<ExchangeRate>
    val directPaymentAckLiveData = MutableLiveData<Resource<Pair<SendRequest, Boolean>>>()

    val exchangeRate: org.bitcoinj.utils.ExchangeRate?
        get() = exchangeRateData.value?.run {
            org.bitcoinj.utils.ExchangeRate(Coin.COIN, fiat)
        }

    init {
        val currencyCode = configuration.exchangeCurrencyCode
        exchangeRateData = ExchangeRatesRepository.instance.getRate(currencyCode)
    }

    fun requestPaymentRequest(basePaymentIntent: PaymentIntent) {

        sendRequestLiveData.value = Resource.loading(null)

        val requestCallback: RequestPaymentRequestTask.ResultCallback = object : RequestPaymentRequestTask.ResultCallback {

            override fun onPaymentIntent(paymentIntent: PaymentIntent) {
                if (basePaymentIntent.isExtendedBy(paymentIntent, true)) {
                    finalPaymentIntent = paymentIntent
                    createBaseSendRequest(paymentIntent)
                } else {
                    finalPaymentIntent = null
                    sendRequestLiveData.value = Resource.error("isn't extension of basePaymentIntent")
                    log.info("BIP72 trust check failed")
                }
            }

            override fun onFail(ex: Exception?, messageResId: Int, vararg messageArgs: Any) {
                finalPaymentIntent = null
                if (ex != null) {
                    val errorMessage =
                            if (messageResId > 0) walletApplication.getString(messageResId, *messageArgs)
                            else ex.message!!
                    sendRequestLiveData.value = Resource.error(ex, errorMessage)
                } else {
                    val errorMessage = walletApplication.getString(messageResId, *messageArgs)
                    sendRequestLiveData.value = Resource.error(errorMessage)
                }
            }
        }

        HttpRequestTask(backgroundHandler, requestCallback, walletApplication.httpUserAgent())
                .requestPaymentRequest(basePaymentIntent.paymentRequestUrl)
    }

    fun createBaseSendRequest(paymentIntent: PaymentIntent) {

        backgroundHandler.post {
            Context.propagate(Constants.CONTEXT)
            try {
                var sendRequest = createSendRequest(wallet, false, paymentIntent, signInputs = false, forceEnsureMinRequiredFee = false)

                wallet.completeTx(sendRequest)
                if (checkDust(sendRequest)) {
                    sendRequest = createSendRequest(wallet, false, paymentIntent, signInputs = false, forceEnsureMinRequiredFee = true)
                    wallet.completeTx(sendRequest)
                }
                callbackHandler?.post {
                    baseSendRequest = sendRequest
                    sendRequestLiveData.value = Resource.success(sendRequest)
                }
            } catch (x: Exception) {
                callbackHandler?.post {
                    baseSendRequest = null
                    sendRequestLiveData.value = Resource.error(x)
                }
            }
        }
    }

    fun signAndSendPayment() {
        signAndSendPayment(finalPaymentIntent!!, baseSendRequest!!.ensureMinRequiredFee)
    }

    override fun signAndSendPayment(
        sendRequest: SendRequest,
        txAlreadyCompleted: Boolean,
        checkBalanceConditions: Boolean
    ) {
        if (finalPaymentIntent!!.hasPaymentUrl()) {
            wallet.completeTx(sendRequest)
            directPay(sendRequest)
        } else {
            super.signAndSendPayment(sendRequest, txAlreadyCompleted, checkBalanceConditions)
        }
    }

    fun directPay(sendRequest: SendRequest) {
        val refundAddress = wallet.freshAddress(KeyPurpose.REFUND)
        val payment = PaymentProtocol.createPaymentMessage(listOf(sendRequest.tx), finalPaymentIntent!!.amount, refundAddress, null, finalPaymentIntent!!.payeeData)

        val callback: DirectPaymentTask.ResultCallback = object : DirectPaymentTask.ResultCallback {
            override fun onResult(ack: Boolean) {
                directPaymentAckLiveData.value = Resource.success(Pair(sendRequest, ack))
            }

            override fun onFail(messageResId: Int, vararg messageArgs: Any) {
                val message = StringBuilder().apply {
                    if (BuildConfig.DEBUG && messageArgs[0] == 415) {
                        val host = Uri.parse(finalPaymentIntent!!.paymentUrl).host
                        appendLine(host)
                        appendLine(walletApplication.getString(messageResId, *messageArgs))
                        appendLine(PaymentProtocol.MIMETYPE_PAYMENT)
                        appendLine()
                    }
                    appendLine(walletApplication.getString(R.string.payment_request_problem_message))
                }

                directPaymentAckLiveData.value = Resource.error(message.toString(), Pair(sendRequest, false))
            }
        }

        HttpPaymentTask(backgroundHandler, callback, finalPaymentIntent!!.paymentUrl, walletApplication.httpUserAgent())
                .send(payment)
    }

    fun commitAndBroadcast(sendRequest: SendRequest) {
        super.signAndSendPayment(sendRequest,
            txAlreadyCompleted = true,
            checkBalanceConditions = true
        )
    }
}