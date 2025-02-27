/*
 * Copyright 2023 Dash Core Group.
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

package org.dash.wallet.features.exploredash.ui.dashdirect.dialogs

import android.graphics.Bitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.data.unwrap
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.*
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.dashdirect.GiftCardDao
import org.dash.wallet.features.exploredash.data.dashdirect.model.Barcode
import org.dash.wallet.features.exploredash.data.dashdirect.model.giftcard.GetGiftCardResponse
import org.dash.wallet.features.exploredash.data.dashdirect.model.paymentstatus.PaymentStatusResponse
import org.dash.wallet.features.exploredash.repository.DashDirectException
import org.dash.wallet.features.exploredash.repository.DashDirectRepository
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class GiftCardDetailsViewModel @Inject constructor(
    private val giftCardDao: GiftCardDao,
    private val metadataProvider: TransactionMetadataProvider,
    private val analyticsService: AnalyticsService,
    private val repository: DashDirectRepository,
    private val walletData: WalletDataProvider
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(GiftCardDetailsViewModel::class.java)
    }

    lateinit var transactionId: Sha256Hash
        private set
    private var tickerJob: Job? = null

    private var exchangeRate: ExchangeRate? = null
    private var retries = 3

    private val _giftCard: MutableLiveData<GiftCard?> = MutableLiveData()
    val giftCard: LiveData<GiftCard?>
        get() = _giftCard

    private val _icon: MutableLiveData<Bitmap?> = MutableLiveData()
    val icon: LiveData<Bitmap?>
        get() = _icon

    private val _barcode: MutableLiveData<Barcode?> = MutableLiveData()
    val barcode: LiveData<Barcode?>
        get() = _barcode

    private val _barcodeUrl: MutableLiveData<String?> = MutableLiveData()
    val barcodeUrl: LiveData<String?>
        get() = _barcodeUrl

    private val _date: MutableLiveData<LocalDateTime> = MutableLiveData()
    val date: LiveData<LocalDateTime>
        get() = _date

    val error: SingleLiveEvent<Exception?> = SingleLiveEvent()

    fun init(transactionId: Sha256Hash) {
        this.transactionId = transactionId

        metadataProvider.observeTransactionMetadata(transactionId)
            .filterNotNull()
            .onEach { metadata ->
                if (!metadata.currencyCode.isNullOrEmpty() && !metadata.rate.isNullOrEmpty()) {
                    exchangeRate = ExchangeRate(Fiat.parseFiat(metadata.currencyCode, metadata.rate))
                }

                _date.value = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(metadata.timestamp),
                    ZoneId.systemDefault()
                )
                _icon.value = metadata.customIconId?.let { metadataProvider.getIcon(it) }
            }
            .launchIn(viewModelScope)

        giftCardDao.observeCardForTransaction(transactionId)
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { giftCard ->
                _giftCard.value = giftCard

                giftCard.barcodeValue?.let { value ->
                    if (_barcode.value?.value != value) {
                        _barcode.value = Barcode(value, giftCard.barcodeFormat!!)
                    }
                }

                if (giftCard.number == null && giftCard.note != null) {
                    val (orderId, paymentId) = giftCard.note!!.split('+')
                    tickerJob = TickerFlow(period = 1.5.seconds, initialDelay = 1.seconds)
                        .cancellable()
                        .onEach { fetchGiftCardInfo(orderId, paymentId) }
                        .launchIn(viewModelScope)
                } else {
                    cancelTicker()
                }
            }
            .launchIn(viewModelScope)
    }

    private suspend fun fetchGiftCardInfo(orderId: String, paymentId: String) {
        val email = repository.getDashDirectEmail()

        if (!repository.isUserSignIn() || email.isNullOrEmpty()) {
            error.postValue(DashDirectException(ResourceString(R.string.log_in_to_dashdirect_account)))
            cancelTicker()
            return
        }

        try {
            val paymentStatus = getPaymentStatus(email, paymentId, orderId)

            if (paymentStatus?.data?.status == "paid" && paymentStatus.data.giftCardId != null) {
                val giftCardData = getGiftCardDetails(email, paymentStatus.data.giftCardId)?.data

                if (giftCardData != null && !giftCardData.cardNumber.isNullOrEmpty()) {
                    cancelTicker()
                    updateGiftCard(giftCardData.cardNumber, giftCardData.cardPin)

                    if (!giftCardData.barcodeUrl.isNullOrEmpty()) {
                        saveBarcode(giftCardData.barcodeUrl)
                    }
                }
            } else if (paymentStatus?.errorMessage?.isNotEmpty() == true ||
                paymentStatus?.data?.errors?.any { !it.isNullOrEmpty() } == true
            ) {
                if (retries > 0) {
                    retries--
                    return
                }

                cancelTicker()
                val message = if (!paymentStatus.errorMessage.isNullOrEmpty()) {
                    paymentStatus.errorMessage
                } else {
                    paymentStatus.data?.errors?.firstOrNull() ?: ""
                }
                log.error("DashDirect returned error: $message")
                error.postValue(DashDirectException(message))
            }
        } catch (ex: Exception) {
            cancelTicker()
            log.error("Failed to fetch gift card info", ex)
            error.postValue(ex)
        }
    }

    private suspend fun getPaymentStatus(email: String, paymentId: String, orderId: String): PaymentStatusResponse? {
        return repository.getPaymentStatus(userEmail = email, paymentId = paymentId, orderId = orderId).unwrap()
    }

    private suspend fun getGiftCardDetails(email: String, giftCardId: Long): GetGiftCardResponse? {
        return repository.getGiftCardDetails(userEmail = email, giftCardId = giftCardId).unwrap()
    }

    private fun updateGiftCard(number: String, pinCode: String?) {
        val giftCard = giftCard.value ?: return

        viewModelScope.launch {
            metadataProvider.updateGiftCardMetadata(
                giftCard.copy(
                    number = number,
                    pin = pinCode,
                    note = null
                )
            )
        }

        logOnPurchaseEvents(giftCard)
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun saveBarcode(barcodeUrl: String) {
        _barcodeUrl.value = barcodeUrl

        // We don't want this cancelled when the viewModel is destroyed
        GlobalScope.launch {
            try {
                val result = Constants.HTTP_CLIENT.get(barcodeUrl)
                require(result.isSuccessful && result.body != null) { "call is not successful" }
                val bitmap = result.body!!.decodeBitmap()
                val decodeResult = Qr.scanBarcode(bitmap)

                if (decodeResult != null) {
                    metadataProvider.updateGiftCardBarcode(transactionId, decodeResult.first, decodeResult.second)
                } else {
                    log.error("ScanBarcode returned null: $barcodeUrl")
                }
            } catch (ex: Exception) {
                log.error("Failed to resize and decode barcode: $barcodeUrl", ex)
            }
        }
    }

    fun logEvent(event: String) {
        analyticsService.logEvent(event, mapOf())
    }

    private fun logOnPurchaseEvents(giftCard: GiftCard) {
        analyticsService.logEvent(AnalyticsConstants.DashSpend.SUCCESSFUL_PURCHASE, mapOf())
        analyticsService.logEvent(
            AnalyticsConstants.DashSpend.MERCHANT_NAME,
            mapOf(AnalyticsConstants.Parameter.VALUE to giftCard.merchantName)
        )

        exchangeRate?.let {
            val transaction = walletData.getTransaction(transactionId)
            val fiatValue = it.coinToFiat(transaction?.getValue(walletData.transactionBag) ?: Coin.ZERO)

            analyticsService.logEvent(
                AnalyticsConstants.DashSpend.PURCHASE_AMOUNT,
                mapOf(AnalyticsConstants.Parameter.VALUE to giftCard.price)
            )

            analyticsService.logEvent(
                AnalyticsConstants.DashSpend.DISCOUNT_AMOUNT,
                mapOf(AnalyticsConstants.Parameter.VALUE to fiatValue.toFriendlyString())
            )
        }
    }

    private fun cancelTicker() {
        tickerJob?.cancel()
        tickerJob = null
        retries = 0
    }
}
