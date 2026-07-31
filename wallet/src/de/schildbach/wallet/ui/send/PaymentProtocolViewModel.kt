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
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import org.dash.wallet.common.data.PaymentIntent
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
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.slf4j.LoggerFactory
import javax.inject.Inject
import de.schildbach.wallet.util.toDashjFiat

@HiltViewModel
class PaymentProtocolViewModel @Inject constructor(
    walletData: WalletData,
    configuration: Configuration,
    exchangeRates: ExchangeRatesProvider,
    private val sendCoinsTaskRunner: SendCoinsTaskRunner,
    walletUIConfig: WalletUIConfig
) : SendCoinsBaseViewModel(walletData, configuration) {

    companion object {
        val FAKE_FEE_FOR_EXCEPTIONS: Coin =
            Coin.valueOf(org.dash.wallet.common.util.Constants.ECONOMIC_FEE.multiply(261).divide(1000).value)
    }

    private val log = LoggerFactory.getLogger(PaymentProtocolFragment::class.java)

    var baseSendRequest: SendRequest? = null
    var finalPaymentIntent: PaymentIntent? = null

    /**
     * Post-cutover preview (issue #1520 Phase 1B item 1): the SDK-built,
     * signed, inputs-RESERVED payment. Its [SdkDeferredPayment.feeDuffs]
     * is the EXACT fee of the tx that will be submitted — the fee preview
     * IS the payment. Consumed by [sendPayment]; released in [onCleared]
     * when abandoned (idempotent engine-side, TTL backstop besides).
     * Null pre-cutover — the dashj [baseSendRequest] dry-run then owns
     * the preview exactly as before.
     */
    var deferredPayment: de.schildbach.wallet.service.platform.sdk.SdkDeferredPayment? = null
        private set

    /** The preview fee to display, from whichever path built the preview. */
    val previewFee: Coin?
        get() = deferredPayment?.let { Coin.valueOf(it.feeDuffs) } ?: baseSendRequest?.tx?.fee

    /** True when a confirmed send can actually run (either path is armed). */
    val canSendPayment: Boolean
        get() = baseSendRequest != null || deferredPayment != null

    private val _sendRequestLiveData = MutableLiveData<Resource<SendRequest?>>()
    val sendRequestLiveData: LiveData<Resource<SendRequest?>>
        get() = _sendRequestLiveData

    private val _exchangeRateData = MutableLiveData<ExchangeRate?>()
    val exchangeRateData: LiveData<ExchangeRate?>
        get() = _exchangeRateData

    val directPaymentAckLiveData = MutableLiveData<Resource<Transaction>>()

    val exchangeRate: org.bitcoinj.utils.ExchangeRate?
        get() = exchangeRateData.value?.run {
            org.bitcoinj.utils.ExchangeRate(Coin.COIN, fiat.toDashjFiat())
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

                if (basePaymentIntent.isExtendedBy(paymentIntent, true, Constants.ADDRESS_NETWORK)) {
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
     * Creates the payment preview for the given payment intent.
     *
     * Post-cutover: the SDK builds + signs the REAL payment with its
     * inputs reserved ([deferredPayment]) — the displayed fee is exact
     * and no dashj `completeTx` dry-run runs. Pre-cutover: the dashj
     * dry-run [baseSendRequest], byte-identical to before.
     */
    private suspend fun createBaseSendRequest(paymentIntent: PaymentIntent) {
        withContext(Dispatchers.IO) {
            if (sendCoinsTaskRunner.isCutoverCommitted()) {
                try {
                    // A re-preview (retry after a failed send) must not
                    // leak the previous reservation.
                    deferredPayment?.let { sendCoinsTaskRunner.releaseDeferredPayment(it) }
                    deferredPayment = sendCoinsTaskRunner.buildDeferredBip70Payment(paymentIntent)
                    baseSendRequest = null
                    _sendRequestLiveData.postValue(Resource.success(null))
                } catch (x: Exception) {
                    deferredPayment = null
                    baseSendRequest = null
                    _sendRequestLiveData.postValue(Resource.error(x))
                }
                return@withContext
            }
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
                directPaymentAckLiveData.postValue(Resource.loading(null))

                val prebuilt = deferredPayment
                val transaction = if (prebuilt != null) {
                    // Post-cutover: submit the EXACT tx the preview showed.
                    // The reservation is consumed (ack → broadcast) or
                    // released (pre-ack failure) inside the runner either
                    // way — this reference is dead after the call.
                    deferredPayment = null
                    try {
                        sendCoinsTaskRunner.sendPrebuiltDirectPayment(prebuilt, finalPaymentIntent!!)
                    } catch (ex: Exception) {
                        // Pre-ack failures are retryable — rebuild the
                        // preview so a retry submits a fresh reservation.
                        // NEVER rebuild after Bip70AckedDisplayException:
                        // the merchant holds the acked tx and a rebuilt
                        // retry could double-pay.
                        if (ex !is SendCoinsTaskRunner.Bip70AckedDisplayException) {
                            runCatching { createBaseSendRequest(finalPaymentIntent!!) }
                        }
                        throw ex
                    }
                } else {
                    val sendRequest = sendCoinsTaskRunner.createSendRequest(
                        basePaymentIntent.mayEditAmount(),
                        finalPaymentIntent!!,
                        true,
                        baseSendRequest!!.ensureMinRequiredFee
                    )

                    sendCoinsTaskRunner.sendDirectPayment(
                        sendRequest,
                        finalPaymentIntent!!
                    )
                }

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

    override fun onCleared() {
        // Abandoned preview (user backed out before confirming): free the
        // reserved inputs. Fire-and-forget on a detached scope — the
        // ViewModel scope is already dead here, the release is idempotent,
        // and the engine's reservation TTL is the backstop if the process
        // dies first.
        deferredPayment?.let { payment ->
            deferredPayment = null
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                sendCoinsTaskRunner.releaseDeferredPayment(payment)
            }
        }
        super.onCleared()
    }
}