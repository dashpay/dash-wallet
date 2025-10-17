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

package org.dash.wallet.features.exploredash.ui.dashspend.dialogs

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.BuildConfig
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.*
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderType
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.Barcode
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.GiftCardResponse
import org.dash.wallet.features.exploredash.data.dashspend.model.GiftCardStatus
import org.dash.wallet.features.exploredash.data.explore.GiftCardDao
import org.dash.wallet.features.exploredash.repository.CTXSpendException
import org.dash.wallet.features.exploredash.repository.CTXSpendRepository
import org.dash.wallet.features.exploredash.repository.PiggyCardsRepository
import org.dash.wallet.features.exploredash.utils.CTXSpendConfig
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

data class GiftCardUIState(
    val giftCard: GiftCard? = null,
    val icon: Bitmap? = null,
    val barcode: Barcode? = null,
    val date: LocalDateTime? = null,
    val error: Exception? = null,
    val serviceName: String? = null,
    val status: GiftCardStatus? = null,
    val queries: Int = 0
)

@HiltViewModel
class GiftCardDetailsViewModel @Inject constructor(
    private val applicationScope: CoroutineScope,
    private val giftCardDao: GiftCardDao,
    private val metadataProvider: TransactionMetadataProvider,
    private val analyticsService: AnalyticsService,
    private val ctxSpendRepository: CTXSpendRepository,
    private val piggyCardsRepository: PiggyCardsRepository,
    private val walletData: WalletDataProvider,
    private val ctxSpendConfig: CTXSpendConfig
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(GiftCardDetailsViewModel::class.java)
    }

    lateinit var transactionId: Sha256Hash
        private set
    private var tickerJob: Job? = null

    private var exchangeRate: ExchangeRate? = null
    private var retries = 3

    private val _uiState = MutableStateFlow(GiftCardUIState())
    val uiState: StateFlow<GiftCardUIState> = _uiState.asStateFlow()

    fun init(transactionId: Sha256Hash) {
        this.transactionId = transactionId

        metadataProvider.observeTransactionMetadata(transactionId)
            .filterNotNull()
            .onEach { metadata ->
                if (!metadata.currencyCode.isNullOrEmpty() && !metadata.rate.isNullOrEmpty()) {
                    exchangeRate = ExchangeRate(Fiat.parseFiat(metadata.currencyCode, metadata.rate))
                }

                _uiState.update { currentState ->
                    currentState.copy(
                        date = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(metadata.timestamp),
                            ZoneId.systemDefault()
                        ),
                        icon = metadata.customIconId?.let { metadataProvider.getIcon(it) },
                        serviceName = metadata.service
                    )
                }
            }
            .launchIn(viewModelScope)

        giftCardDao.observeCardForTransaction(transactionId)
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { giftCard ->
                _uiState.update { currentState ->
                    val barcodeValue = giftCard.barcodeValue
                    currentState.copy(
                        giftCard = giftCard,
                        barcode = barcodeValue?.let { value ->
                            if (currentState.barcode?.value != value) {
                                Barcode(value, giftCard.barcodeFormat!!)
                            } else {
                                currentState.barcode
                            }
                        }
                    )
                }

                if (giftCard.number == null && giftCard.merchantUrl.isNullOrEmpty() && giftCard.note != null) {
                    tickerJob = TickerFlow(period = 0.5.seconds, initialDelay = 1.seconds)
                        .cancellable()
                        .onEach { fetchGiftCardInfo(giftCard.txId) }
                        .launchIn(viewModelScope)
                } else {
                    cancelTicker()
                }
            }
            .launchIn(viewModelScope)
    }

    private suspend fun fetchGiftCardInfo(txid: Sha256Hash) {
        val metadata = metadataProvider.getTransactionMetadata(txid)
        when (metadata?.service) {
            ServiceName.CTXSpend -> {
                val email = ctxSpendRepository.getCTXSpendEmail()

                if (!ctxSpendRepository.isUserSignedIn() || email.isNullOrEmpty()) {
                    log.error("not logged in to DashSpend (CTX) while attempting to fetch gift card info")
                    _uiState.update { it.copy(error = CTXSpendException(ResourceString(R.string.log_in_to_ctxspend_account))) }
                    cancelTicker()
                    return
                }

                try {
                    val giftCard = ctxSpendRepository.getGiftCard(txid.toStringBase58())
                    
                    // Single state update with all changes
                    val newState = if (giftCard != null) {
                        when (giftCard.status) {
                            GiftCardStatus.UNPAID -> {
                                uiState.value.copy(
                                    status = giftCard.status,
                                    queries = uiState.value.queries + 1,
                                    error = CTXSpendException("gift card status unpaid, but transaction sent", giftCard, txid.toStringBase58())
                                )
                            }

                            GiftCardStatus.PAID -> {
                                uiState.value.copy(
                                    status = giftCard.status,
                                    queries = uiState.value.queries + 1,
                                    error = CTXSpendException("gift card status paid, not fulfilled", giftCard, txid.toStringBase58())
                                )
                            }

                            GiftCardStatus.FULFILLED -> {
                                if (!giftCard.cardNumber.isNullOrEmpty()) {
                                    updateGiftCard(giftCard.cardNumber, giftCard.cardPin)
                                    log.info("CTXSpend: saving barcode for: ${giftCard.barcodeUrl}")
                                    saveBarcode(giftCard.cardNumber)
                                    val startPurchaseTime = ctxSpendConfig.get(CTXSpendConfig.PREFS_LAST_PURCHASE_START) ?: -1
                                    if (startPurchaseTime != -1L) {
                                        if (BuildConfig.DEBUG) {
                                            log.info("event:process_gift_card_purchase: {} s", (System.currentTimeMillis() - startPurchaseTime).toDouble() / 1000)
                                        }
                                        analyticsService.logEvent(
                                            AnalyticsConstants.Process.PROCESS_GIFT_CARD_PURCHASE,
                                            hashMapOf(
                                                AnalyticsConstants.Parameter.TIME to (System.currentTimeMillis() - startPurchaseTime).toDouble() / 1000,
                                                AnalyticsConstants.Parameter.ARG1 to "CTX",
                                                AnalyticsConstants.Parameter.ARG2 to (giftCard.merchantName ?: "unknown merchant")
                                            )
                                        )
                                        ctxSpendConfig.set(CTXSpendConfig.PREFS_LAST_PURCHASE_START, -1L)
                                    }
                                    val state = uiState.value.copy(
                                        status = giftCard.status,
                                        queries = uiState.value.queries + 1,
                                        error = null
                                    )
                                    cancelTicker()
                                    state
                                } else if (giftCard.redeemUrl?.isNotEmpty() == true) {
                                    log.error("CTXSpend returned a redeem url card: not supported")
                                    val state = uiState.value.copy(
                                        status = giftCard.status,
                                        queries = uiState.value.queries + 1,
                                        error = CTXSpendException(
                                            ResourceString(
                                                R.string.gift_card_redeem_url_not_supported,
                                                listOf(GiftCardProviderType.CTX.name, giftCard.id, giftCard.paymentId ?: "", txid)
                                            ),
                                            giftCard
                                        )
                                    )
                                    cancelTicker()
                                    state
                                } else {
                                    uiState.value.copy(
                                        status = giftCard.status,
                                        queries = uiState.value.queries + 1,
                                        error = null
                                    )
                                }
                            }

                            GiftCardStatus.REJECTED -> {
                                log.error("CTXSpend returned error: rejected")
                                analyticsService.logError(
                                    CTXSpendException("CTXSpend returned error: rejected", giftCard, ""),
                                    "CTX returned error: rejected ${
                                        giftCard.merchantName
                                    } for ${giftCard.fiatAmount} ${giftCard.fiatCurrency}"
                                )
                                val state = uiState.value.copy(
                                    status = giftCard.status,
                                    queries = uiState.value.queries + 1,
                                    error = CTXSpendException(
                                        ResourceString(
                                            R.string.gift_card_rejected,
                                            listOf(GiftCardProviderType.CTX.name, giftCard.id, giftCard.paymentId ?: "", txid)
                                        ),
                                        giftCard
                                    )
                                )
                                cancelTicker()
                                state
                            }

                            else -> {
                                uiState.value.copy(
                                    status = giftCard.status,
                                    queries = uiState.value.queries + 1,
                                    error = null
                                )
                            }
                        }
                    } else {
                        if (retries > 0) {
                            retries--
                            return
                        }
                        cancelTicker()
                        log.error("CTXSpend returned null gift card")
                        uiState.value.copy(
                            queries = uiState.value.queries + 1,
                            error = CTXSpendException(
                                ResourceString(
                                    R.string.gift_card_unknown_error,
                                    listOf(txid)
                                )
                            )
                        )
                    }
                    
                    _uiState.update { newState }
                    
                } catch (ex: Exception) {
                    if (retries > 0) {
                        retries--
                        return
                    }
                    cancelTicker()
                    log.error("Failed to fetch gift card info", ex)
                    _uiState.update { it.copy(error = ex) }
                }
            }
            ServiceName.PiggyCards -> {
                val email = piggyCardsRepository.getAccountEmail()

                if (!piggyCardsRepository.isUserSignedIn() || email.isNullOrEmpty()) {
                    log.error("not logged in to DashSpend (PiggyCards) while attempting to fetch gift card info")
                    _uiState.update { it.copy(error = CTXSpendException(ResourceString(R.string.log_in_to_ctxspend_account))) }
                    cancelTicker()
                    return
                }

                val orderId = giftCardDao.getCardForTransaction(txid)?.note
                if (orderId == null) {
                    log.error("piggycards order # is missing for $txid")
                    return
                }
                log.info("piggycard tx: {} and order: {}",
                    walletData.getTransaction(txid)?.toStringHex(), orderId
                )

                try {
                    val giftCard = piggyCardsRepository.getGiftCard(orderId)
                    
                    // Single state update with all changes
                    val newState = if (giftCard != null) {
                        when (giftCard.status) {
                            GiftCardStatus.UNPAID -> {
                                uiState.value.copy(
                                    status = giftCard.status,
                                    queries = uiState.value.queries + 1,
                                    error = CTXSpendException("gift card status unpaid, but transaction sent", giftCard, txid.toString())
                                )
                            }

                            GiftCardStatus.PAID -> {
                                uiState.value.copy(
                                    status = giftCard.status,
                                    queries = uiState.value.queries + 1,
                                    error = CTXSpendException("gift card status paid, not fulfilled", giftCard, txid.toString())
                                )
                            }

                            GiftCardStatus.FULFILLED -> {
                                val startPurchaseTime = ctxSpendConfig.get(CTXSpendConfig.PREFS_LAST_PURCHASE_START) ?: -1
                                if (startPurchaseTime != -1L) {
                                    if (BuildConfig.DEBUG) {
                                        log.info("event:process_gift_card_purchase: {} s", (System.currentTimeMillis() - startPurchaseTime).toDouble() / 1000)
                                    }
                                    analyticsService.logEvent(
                                        AnalyticsConstants.Process.PROCESS_GIFT_CARD_PURCHASE,
                                        hashMapOf(
                                            AnalyticsConstants.Parameter.TIME to (System.currentTimeMillis() - startPurchaseTime).toDouble() / 1000,
                                            AnalyticsConstants.Parameter.ARG1 to "PiggyCards",
                                            AnalyticsConstants.Parameter.ARG2 to (giftCard.merchantName ?: "unknown merchant")
                                        )
                                    )
                                    ctxSpendConfig.set(CTXSpendConfig.PREFS_LAST_PURCHASE_START, -1L)
                                }
                                if (!giftCard.cardNumber.isNullOrEmpty()) {
                                    updateGiftCard(giftCard.cardNumber, giftCard.cardPin)
                                    log.info("PiggyCards: saving barcode for: ${giftCard.barcodeUrl}")
                                    giftCard.barcodeUrl?.let {
                                        saveBarcodeUrl(it)
                                    }
                                    val newState = uiState.value.copy(
                                        status = giftCard.status,
                                        queries = uiState.value.queries + 1,
                                        error = null
                                    )
                                    cancelTicker()
                                    newState
                                } else if (giftCard.redeemUrl?.isNotEmpty() == true) {
                                    updateGiftCard(giftCard.redeemUrl)
                                    val newState = uiState.value.copy(
                                        status = giftCard.status,
                                        queries = uiState.value.queries + 1,
                                        error = null
                                    )
                                    cancelTicker()
                                    newState
                                } else {
                                    uiState.value.copy(
                                        status = giftCard.status,
                                        queries = uiState.value.queries + 1,
                                        error = null
                                    )
                                }
                            }

                            GiftCardStatus.REJECTED -> {
                                log.error("PiggyCards returned error: rejected")
                                analyticsService.logError(
                                    CTXSpendException("PiggyCards returned error: rejected", giftCard, ""),
                                    "PiggyCards returned error: rejected ${giftCard.merchantName} for ${giftCard.fiatAmount} ${giftCard.fiatCurrency}"
                                )
                                val newState = uiState.value.copy(
                                    status = giftCard.status,
                                    queries = uiState.value.queries + 1,
                                    error = CTXSpendException(
                                        ResourceString(
                                            R.string.gift_card_rejected,
                                            listOf(GiftCardProviderType.PiggyCards.name, giftCard.id, giftCard.paymentId ?: "", txid)
                                        ),
                                        giftCard
                                    )
                                )
                                cancelTicker()
                                newState
                            }

                            else -> {
                                uiState.value.copy(
                                    status = giftCard.status,
                                    queries = uiState.value.queries + 1,
                                    error = null
                                )
                            }
                        }
                    } else {
                        if (retries > 0) {
                            retries--
                            return
                        }
                        cancelTicker()
                        log.error("PiggyCards returned null gift card")
                        uiState.value.copy(
                            queries = uiState.value.queries + 1,
                            error = CTXSpendException(
                                ResourceString(
                                    R.string.gift_card_unknown_error,
                                    listOf(txid)
                                )
                            )
                        )
                    }
                    
                    _uiState.update { newState }
                    
                } catch (ex: Exception) {
                    if (retries > 0) {
                        retries--
                        return
                    }
                    cancelTicker()
                    log.error("Failed to fetch gift card info", ex)
                    _uiState.update { it.copy(error = ex) }
                }
            }
            else -> {

            }
        }
    }

    private fun updateGiftCard(number: String, pinCode: String?) {
        val giftCard = uiState.value.giftCard ?: return

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

    private fun updateGiftCard(merchantUrl: String) {
        val giftCard = uiState.value.giftCard ?: return

        viewModelScope.launch {
            metadataProvider.updateGiftCardMetadata(
                giftCard.copy(
                    merchantUrl = merchantUrl,
                    note = null
                )
            )
        }

        logOnPurchaseEvents(giftCard)
    }

    private fun saveBarcode(giftCardNumber: String) {
        applicationScope.launch {
            try {
                metadataProvider.updateGiftCardBarcode(
                    transactionId,
                    giftCardNumber.replace(" ", "").replace("-", ""),
                    BarcodeFormat.CODE_128 // Assuming CTX barcodes are all CODE_128
                )
            } catch (ex: Exception) {
                log.error("Failed to save barcode for $giftCardNumber", ex)
            }
        }
    }

    private fun saveBarcode(giftCardNumber: String, cardFormat: BarcodeFormat) {
        applicationScope.launch {
            try {
                metadataProvider.updateGiftCardBarcode(
                    transactionId,
                    giftCardNumber.replace(" ", "").replace("-", ""),
                    cardFormat
                )
            } catch (ex: Exception) {
                log.error("Failed to save barcode for $giftCardNumber", ex)
            }
        }
    }

    private fun saveBarcodeUrl(barcodeUrl: String) {
        applicationScope.launch {
            try {
                val result = Constants.HTTP_CLIENT.get(barcodeUrl)
                require(result.isSuccessful && result.body != null) { "call is not successful" }
                val bitmap = result.body!!.decodeBitmap()
                val decodeResult = Qr.scanBarcode(bitmap)

                if (decodeResult != null) {
                    metadataProvider.updateGiftCardBarcode(
                        transactionId,
                        decodeResult.first.replace(" ", "").replace("-", ""),
                        decodeResult.second
                    )
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
