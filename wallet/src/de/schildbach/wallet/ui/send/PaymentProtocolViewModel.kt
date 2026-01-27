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
package de.schildbach.wallet.ui.send

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.payments.SendCoinsTaskRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.SendRequest
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class PaymentProtocolViewModel @Inject constructor(
    walletData: WalletDataProvider,
    configuration: Configuration,
    exchangeRates: ExchangeRatesProvider,
    private val sendCoinsTaskRunner: SendCoinsTaskRunner,
    walletUIConfig: WalletUIConfig
) : SendCoinsBaseViewModel(walletData, configuration) {

    companion object {
        val FAKE_FEE_FOR_EXCEPTIONS: Coin =
            org.dash.wallet.common.util.Constants.ECONOMIC_FEE.multiply(261).divide(1000)
    }

    private val log = LoggerFactory.getLogger(PaymentProtocolFragment::class.java)

    var baseSendRequest: SendRequest? = null
    var finalPaymentIntent: PaymentIntent? = null

    private val _sendRequestLiveData = MutableLiveData<Resource<SendRequest?>>()
    val sendRequestLiveData: LiveData<Resource<SendRequest?>>
        get() = _sendRequestLiveData

    private val _exchangeRateData = MutableLiveData<ExchangeRate?>()
    val exchangeRateData: LiveData<ExchangeRate?>
        get() = _exchangeRateData

    val directPaymentAckLiveData = MutableLiveData<Resource<Transaction>>()

    val exchangeRate: org.bitcoinj.utils.ExchangeRate?
        get() = exchangeRateData.value?.run {
            org.bitcoinj.utils.ExchangeRate(Coin.COIN, fiat)
        }

    init {
        @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .flatMapConcat(exchangeRates::observeExchangeRate)
            .distinctUntilChanged()
            .onEach(_exchangeRateData::postValue)
            .launchIn(viewModelScope)
    }

    override suspend fun initPaymentIntent(paymentIntent: PaymentIntent) {
        super.initPaymentIntent(paymentIntent)

        if (!paymentIntent.hasPaymentRequestUrl()) {
            throw UnsupportedOperationException(
                PaymentProtocolFragment::class.java.simpleName +
                    "class should be used to handle Payment requests (BIP70 and BIP270)"
            )
        }

        when {
            paymentIntent.isHttpPaymentRequestUrl -> requestPaymentRequest(paymentIntent)
            paymentIntent.isBluetoothPaymentRequestUrl -> {
                log.warn("PaymentRequest via Bluetooth is not supported anymore")
                throw UnsupportedOperationException(
                    SendCoinsFragment::class.java.simpleName +
                        "class should be used to handle this type of payment $paymentIntent"
                )
            }
            else -> {
                log.warn("Incorrect payment type $paymentIntent")
                throw UnsupportedOperationException(
                    SendCoinsFragment::class.java.simpleName +
                        "class should be used to handle this type of payment $paymentIntent"
                )
            }
        }
    }

    /**
     * Requests a BIP70/BIP270 payment request from the payment URL.
     * Updates [sendRequestLiveData] with loading, success, or error states.
     */
    fun requestPaymentRequest(basePaymentIntent: PaymentIntent) {
        _sendRequestLiveData.value = Resource.loading(null)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val paymentIntent = sendCoinsTaskRunner.fetchPaymentRequest(basePaymentIntent)

                if (basePaymentIntent.isExtendedBy(paymentIntent, true)) {
                    finalPaymentIntent = paymentIntent
                    createBaseSendRequest(paymentIntent)
                } else {
                    finalPaymentIntent = null
                    _sendRequestLiveData.postValue(Resource.error("isn't extension of basePaymentIntent"))
                    log.info("BIP72 trust check failed")
                }
            } catch (ex: Exception) {
                finalPaymentIntent = null
                _sendRequestLiveData.postValue(Resource.error(ex, ex.message ?: "Failed to fetch payment request"))
                log.error("Failed to fetch payment request", ex)
            }
        }
    }

    /**
     * Creates a base send request for the given payment intent.
     * This is a dry-run to show the user the transaction details before sending.
     */
    private suspend fun createBaseSendRequest(paymentIntent: PaymentIntent) {
        withContext(Dispatchers.IO) {
            Context.propagate(wallet.context)
            try {
                val sendRequest = sendCoinsTaskRunner.createSendRequest(
                    false,
                    paymentIntent,
                    signInputs = false,
                    forceEnsureMinRequiredFee = false
                )

                wallet.completeTx(sendRequest)

                baseSendRequest = sendRequest
                _sendRequestLiveData.postValue(Resource.success(sendRequest))
            } catch (x: Exception) {
                baseSendRequest = null
                _sendRequestLiveData.postValue(Resource.error(x))
            }
        }
    }

    /**
     * Sends the payment via BIP70/BIP270 direct payment protocol.
     * Updates [directPaymentAckLiveData] with the result.
     */
    fun sendPayment() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                directPaymentAckLiveData.value = Resource.loading(null)

                val sendRequest = sendCoinsTaskRunner.createSendRequest(
                    basePaymentIntent.mayEditAmount(),
                    finalPaymentIntent!!,
                    true,
                    baseSendRequest!!.ensureMinRequiredFee
                )

                val transaction = sendCoinsTaskRunner.sendDirectPayment(
                    sendRequest,
                    finalPaymentIntent!!
                )

                directPaymentAckLiveData.postValue(Resource.success(transaction))
            } catch (ex: Exception) {
                log.error("Failed to send direct payment", ex)
                directPaymentAckLiveData.postValue(Resource.error(ex, ex.message ?: "Payment failed"))
            }
        }
    }

    /**
     * Commits and broadcasts a transaction that has already been acknowledged.
     */
    suspend fun commitAndBroadcast(sendRequest: SendRequest): Transaction {
        return sendCoinsTaskRunner.sendCoins(
            sendRequest,
            txCompleted = true,
            checkBalanceConditions = true
        )
    }
}