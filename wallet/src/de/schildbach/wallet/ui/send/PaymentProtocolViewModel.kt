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
import kotlinx.coroutines.NonCancellable
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
import org.dash.wallet.common.services.PaymentSubmissionPendingException
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

    var finalPaymentIntent: PaymentIntent? = null

    /**
     * Post-cutover preview (issue #1520 Phase 1B item 1): the SDK-built,
     * signed, inputs-RESERVED payment. Its [SdkDeferredPayment.feeDuffs]
     * is the EXACT fee of the tx that will be submitted — the fee preview
     * IS the payment. Consumed by [sendPayment]; released in [onCleared]
     * when abandoned (idempotent engine-side, TTL backstop besides).
     */
    private val deferredPaymentRef =
        java.util.concurrent.atomic.AtomicReference<de.schildbach.wallet.service.platform.sdk.SdkDeferredPayment?>(null)
    val deferredPayment: de.schildbach.wallet.service.platform.sdk.SdkDeferredPayment?
        get() = deferredPaymentRef.get()

    /**
     * In-flight guard for [sendPayment]: a second confirm while one is
     * running is dropped instead of racing it. Covers BOTH paths — on the
     * deferred path a duplicate could double-submit/rebuild the
     * reservation; on the dashj path it would build a genuinely second
     * transaction (a pre-existing double-pay hazard this guard closes).
     */
    private val sendingPayment = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Set when this invoice was paid before and that payment (T1) was released without being seen:
     * the confirm action resubmits T1 itself rather than building a new payment, which the payee
     * could broadcast alongside T1 (review 9fa90c30ccf1).
     */
    private val resubmissionRef =
        java.util.concurrent.atomic.AtomicReference<de.schildbach.wallet.data.PendingDirectPayment?>(null)
    private var resubmissionFee: Coin? = null

    /** True when confirming resends the earlier payment for this invoice. */
    val isResubmission: Boolean
        get() = resubmissionRef.get() != null

    /** The exact fee of the payment the preview shows (the reserved one, or the resubmitted one). */
    val previewFee: Coin?
        get() = deferredPayment?.let { Coin.valueOf(it.feeDuffs) } ?: resubmissionFee.takeIf { isResubmission }

    /** True when a confirmed send can actually run (the preview is armed). */
    val canSendPayment: Boolean
        get() = deferredPayment != null || isResubmission

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
                // An expired invoice can no longer be paid, but one this wallet already paid has an
                // outcome to report: the same pending/unknown outcome a re-confirmation would get.
                // The parser wraps the typed expiry (PaymentIntentParserException), so look through
                // the causes for it.
                val expired = generateSequence<Throwable>(ex) { it.cause }.take(8)
                    .filterIsInstance<org.dash.wallet.common.payments.bip70.PaymentProtocolException.Expired>()
                    .firstOrNull()
                val earlier = expired?.paymentRequestHash
                    ?.let { runCatching { sendCoinsTaskRunner.findUnresolvedSubmission(it) }.getOrNull() }
                if (earlier != null) {
                    _sendRequestLiveData.postValue(
                        Resource.error(PaymentSubmissionPendingException(earlier.toString(), ex))
                    )
                } else if (expired != null) {
                    // the typed expiry, so the screen shows its "expired" message
                    _sendRequestLiveData.postValue(Resource.error(expired, expired.message ?: "Payment request expired"))
                } else {
                    _sendRequestLiveData.postValue(Resource.error(ex, ex.message ?: "Failed to fetch payment request"))
                }
                log.error("Failed to fetch payment request", ex)
            }
        }
    }

    /**
     * Creates the payment preview: the SDK builds + signs the REAL
     * payment with its inputs reserved ([deferredPayment]) — the
     * displayed fee is exact and the preview IS the payment. (The dashj
     * `completeTx` dry-run was deleted per the replace-then-delete
     * policy; dashj remains foundation-only until Phase 3.)
     */
    private suspend fun createBaseSendRequest(paymentIntent: PaymentIntent) {
        withContext(Dispatchers.IO) {
            try {
                // A re-preview (retry after a failed send) must not
                // leak the previous reservation. getAndSet keeps the
                // take-then-release atomic against a concurrent send.
                deferredPaymentRef.getAndSet(null)?.let {
                    sendCoinsTaskRunner.releaseDeferredPayment(it)
                }
                // Paid before and released unseen: arm a resubmission of that exact payment and
                // build nothing new. A store that cannot be read builds the preview as usual; the
                // confirm then reads it strictly and refuses.
                val earlier = try {
                    sendCoinsTaskRunner.findAbandonedSubmission(paymentIntent.paymentRequestHash, wallet)
                } catch (e: Exception) {
                    log.warn("could not look for an earlier payment of this request", e)
                    null
                }
                if (earlier != null) {
                    resubmissionFee = sendCoinsTaskRunner.knownFeeOf(earlier.txBytes, wallet)
                    resubmissionRef.set(earlier)
                    _sendRequestLiveData.postValue(Resource.success(null))
                    return@withContext
                }
                // Built on, and later submitted for, the wallet this screen was opened on
                // ([wallet]): a wipe in between must not have the replacement adopt the payment.
                deferredPaymentRef.set(sendCoinsTaskRunner.buildDeferredBip70Payment(paymentIntent, wallet))
                _sendRequestLiveData.postValue(Resource.success(null))
            } catch (x: Exception) {
                deferredPaymentRef.set(null)
                _sendRequestLiveData.postValue(Resource.error(x))
            }
        }
    }

    /**
     * Sends the payment via BIP70/BIP270 direct payment protocol.
     * Updates [directPaymentAckLiveData] with the result.
     */
    fun sendPayment() {
        // Single-flight: a confirm while a send is already running is
        // dropped, not queued — a duplicate would race the atomic take
        // below and, on the dashj path, build a second transaction.
        if (!sendingPayment.compareAndSet(false, true)) {
            log.warn("sendPayment ignored: a payment submission is already in flight")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (isResubmission) {
                resubmitEarlierPayment()
                return@launch
            }
            try {
                directPaymentAckLiveData.postValue(Resource.loading(null))

                // Atomic take: whoever wins this getAndSet owns the
                // reservation; any concurrent reader sees null.
                val prebuilt = deferredPaymentRef.getAndSet(null)
                    // No armed reservation: the preview failed (the
                    // fragment gates on canSendPayment, so this is a
                    // backstop, not a fallback — the dashj leg is gone).
                    ?: error("no prepared payment to submit — the preview must build first")

                // Before submitting anything, ask whether this invoice already has a submission
                // out. Restored input locks only keep a new transaction off the old one's inputs;
                // a wallet with other funds happily builds a second payment for the same invoice
                // from different ones, and the payee can broadcast both. This screen keeps its own
                // record of having sent in a fragment-scoped view model, which is gone after
                // process death and was never there when the invoice is simply opened again, so
                // the stored record is the only thing left that remembers.
                //
                // From the take above until the runner is handed the reservation, this coroutine
                // is its only owner: deferredPaymentRef no longer holds it, so onCleared() cannot
                // give it back. Every exit before the hand-off (an outstanding submission, a
                // failed read of the store, cancellation) releases it here, outside cancellation,
                // so its inputs and their mirrored wallet locks are not stranded.
                var handedOff = false
                val transaction = try {
                    sendCoinsTaskRunner.findUnresolvedSubmission(finalPaymentIntent!!.paymentRequestHash)?.let { txId ->
                        log.info("not submitting again, {} is still unresolved for this payment request", txId)
                        throw PaymentSubmissionPendingException(txId.toString(), null)
                    }

                    // Submit the EXACT tx the preview showed. The reservation is consumed (ack →
                    // broadcast), held by the pending-payment watch (outcome unknown, a nack
                    // included) or released (the request never left) inside the runner — this
                    // reference is dead after the call either way.
                    //
                    // NEVER re-arm a fresh reservation after a failure here: only a request that
                    // never left the device is definitively unsent, and the runner already freed
                    // those inputs. Anything else may have reached the payee, and a rebuilt retry may
                    // select DIFFERENT inputs and pay the merchant twice if the server broadcasts
                    // after all. Observed in the field test (2026-08-03): the rebuild produced a
                    // different tx (ed08074b) for an invoice whose original (2c3be7d8) was already
                    // on-chain. The user must restart the payment.
                    //
                    // From here the runner owns the reservation: it consumes, holds or releases it.
                    handedOff = true
                    sendCoinsTaskRunner.sendPrebuiltDirectPayment(prebuilt, finalPaymentIntent!!, wallet)
                } finally {
                    if (!handedOff) {
                        withContext(NonCancellable) { sendCoinsTaskRunner.releaseDeferredPayment(prebuilt) }
                    }
                }

                directPaymentAckLiveData.postValue(Resource.success(transaction))
            } catch (ex: Exception) {
                log.error("Failed to send direct payment", ex)
                directPaymentAckLiveData.postValue(Resource.error(ex, ex.message ?: "Payment failed"))
            } finally {
                sendingPayment.set(false)
            }
        }
    }

    /**
     * Resends the earlier payment of this invoice, exactly as it was signed. No reservation is
     * involved: T1's inputs were released when it went unseen. Runs inside [sendPayment]'s
     * single-flight guard.
     */
    private suspend fun resubmitEarlierPayment() {
        try {
            directPaymentAckLiveData.postValue(Resource.loading(null))
            val transaction = sendCoinsTaskRunner.resubmitAbandonedPayment(finalPaymentIntent!!, wallet)
            directPaymentAckLiveData.postValue(Resource.success(transaction))
        } catch (ex: Exception) {
            log.error("Failed to resubmit the earlier payment", ex)
            directPaymentAckLiveData.postValue(Resource.error(ex, ex.message ?: "Payment failed"))
        } finally {
            sendingPayment.set(false)
        }
    }

    override fun onCleared() {
        // Abandoned preview (user backed out before confirming): free the
        // reserved inputs. Fire-and-forget on a detached scope — the
        // ViewModel scope is already dead here, the release is idempotent,
        // and the engine's reservation TTL is the backstop if the process
        // dies first.
        deferredPaymentRef.getAndSet(null)?.let { payment ->
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                sendCoinsTaskRunner.releaseDeferredPayment(payment)
            }
        }
        super.onCleared()
    }
}