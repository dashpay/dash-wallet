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

package org.dash.wallet.features.exploredash.ui.ctxspend

import android.content.Intent
import androidx.lifecycle.*
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.services.*
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.features.exploredash.data.ctxspend.model.DenominationType
import org.dash.wallet.features.exploredash.data.ctxspend.model.GetMerchantResponse
import org.dash.wallet.features.exploredash.data.ctxspend.model.GiftCardResponse
import org.dash.wallet.features.exploredash.data.explore.GiftCardDao
import org.dash.wallet.features.exploredash.data.explore.MerchantDao
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.repository.CTXSpendException
import org.dash.wallet.features.exploredash.repository.CTXSpendRepositoryInt
import org.dash.wallet.features.exploredash.utils.CTXSpendConstants
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class CTXSpendViewModel @Inject constructor(
    private val walletDataProvider: WalletDataProvider,
    exchangeRates: ExchangeRatesProvider,
    var configuration: Configuration,
    private val sendPaymentService: SendPaymentService,
    private val repository: CTXSpendRepositoryInt,
    private val transactionMetadata: TransactionMetadataProvider,
    private val giftCardDao: GiftCardDao,
    networkState: NetworkStateInt,
    private val analytics: AnalyticsService,
    private val savedStateHandle: SavedStateHandle,
    private val exploreDao: MerchantDao
) : ViewModel() {

    companion object {
        private val log = LoggerFactory.getLogger(CTXSpendViewModel::class.java)
        private const val MERCHANT_ID_KEY = "merchant_id"
        private const val BALANCE_KEY = "balance"
    }

    val dashFormat: MonetaryFormat
        get() = configuration.format

    private val _balance = MutableLiveData<Coin>().apply {
        savedStateHandle.get<Long>(BALANCE_KEY)?.let {
            value = Coin.valueOf(it)
        }
    }
    val balance: LiveData<Coin>
        get() = _balance

    val balanceWithDiscount: Coin?
        get() = _balance.value?.let { balance ->
            giftCardMerchant?.let { merchant ->
                val d = merchant.savingsFraction
                return Coin.valueOf((balance.value / (1.0 - d)).toLong()).minus(Transaction.DEFAULT_TX_FEE.multiply(20))
            }
        }

    val userEmail = repository.userEmail.asLiveData()

    private val _exchangeRate: MutableLiveData<ExchangeRate> = MutableLiveData()
    val usdExchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    private val _isFixedDenomination = MutableStateFlow<Boolean?>(null)
    val isFixedDenomination: StateFlow<Boolean?> = _isFixedDenomination.asStateFlow()

    private val _giftCardPaymentValue = MutableStateFlow<Fiat>(Fiat.valueOf(Constants.USD_CURRENCY, 0))
    val giftCardPaymentValue: StateFlow<Fiat> = _giftCardPaymentValue.asStateFlow()

    val isNetworkAvailable = networkState.isConnected.asLiveData()

    var giftCardMerchant: Merchant? = null
        set(value) {
            field = value
            // Save merchant ID to state handle for restoration
            savedStateHandle[MERCHANT_ID_KEY] = value?.merchantId
        }

    var minCardPurchaseCoin: Coin = Coin.ZERO
    var minCardPurchaseFiat: Fiat = Fiat.valueOf(Constants.USD_CURRENCY, 0)
    var maxCardPurchaseCoin: Coin = Coin.ZERO
    var maxCardPurchaseFiat: Fiat = Fiat.valueOf(Constants.USD_CURRENCY, 0)

    var openedCTXSpendTermsAndConditions = false

    init {
        exchangeRates
            .observeExchangeRate(Constants.USD_CURRENCY)
            .onEach(_exchangeRate::postValue)
            .launchIn(viewModelScope)

        walletDataProvider
            .observeSpendableBalance()
            .distinctUntilChanged()
            .onEach(_balance::postValue)
            .launchIn(viewModelScope)

        // Save balance changes to SavedStateHandle
        _balance.observeForever { coin ->
            savedStateHandle[BALANCE_KEY] = coin?.value
        }
    }

    suspend fun purchaseGiftCard(): GiftCardResponse {
        giftCardMerchant?.merchantId?.let {
            val amountValue = giftCardPaymentValue.value

            val response = try {
                repository.purchaseGiftCard(
                    merchantId = it,
                    fiatAmount = MonetaryFormat.FIAT.noCode().format(amountValue).toString(),
                    fiatCurrency = "USD",
                    cryptoCurrency = Constants.DASH_CURRENCY
                )
            } catch (ex: Exception) {
                log.error("purchaseGiftCard network error", ex)
                throw CTXSpendException(
                    "network-connection-error",
                    null,
                    ex.message,
                    ex
                )
            }

            when (response) {
                is ResponseResource.Success -> {
                    return response.value!!
                }
                is ResponseResource.Failure -> {
                    log.error("purchaseGiftCard error ${response.errorCode}: ${response.errorBody}")
                    throw CTXSpendException(
                        "purchaseGiftCard error ${response.errorCode}: ${response.errorBody}",
                        response.errorCode,
                        response.errorBody
                    )
                }
                // else -> {}
            }
        }
        throw CTXSpendException("purchaseGiftCard error")
    }

    suspend fun createSendingRequestFromDashUri(paymentUri: String): Sha256Hash {
        val transaction = sendPaymentService.payWithDashUrl(paymentUri)
        log.info("ctx spend transaction: ${transaction.txId}")
        transactionMetadata.markGiftCardTransaction(transaction.txId, giftCardMerchant?.logoLocation)

        return transaction.txId
    }

    suspend fun updateMerchantDetails(merchant: Merchant) = withContext(Dispatchers.IO) {
        // previously this API call would only be made if we didn't have the min and max card values,
        // but now we need to call this every time to get an updated savings percentage and to see if
        // the merchant is enabled
        val response = try {
            getMerchant(merchant.merchantId!!)
        } catch (ex: Exception) {
            log.error("failed to get merchant ${merchant.merchantId}", ex)
            null
        }

        try {
            response?.apply {
                merchant.savingsPercentage = this.savingsPercentage
                merchant.minCardPurchase = this.minimumCardPurchase
                merchant.maxCardPurchase = this.maximumCardPurchase
                // TODO: re-enable fixed denoms
                merchant.active = this.enabled || this.denominationType == DenominationType.Fixed
                merchant.fixedDenomination = this.denominationType == DenominationType.Fixed
                merchant.denominations = this.denominations.map { it.toInt() }
            }
        } catch (e: Exception) {
            log.warn("updated merchant details contains unexpected data:", e)
        }
    }

    suspend fun getMerchant(merchantId: String): GetMerchantResponse? {
        repository.getCTXSpendEmail()?.let { email ->
            return repository.getMerchant(merchantId)
        }
        return null
    }

    fun refreshMinMaxCardPurchaseValues() {
        giftCardMerchant?.let { merchant ->
            val minCardPurchase = merchant.minCardPurchase ?: 0.0
            val maximumCardPurchase = merchant.maxCardPurchase ?: 0.0
            minCardPurchaseFiat = Fiat.parseFiat(Constants.USD_CURRENCY, minCardPurchase.toString())
            maxCardPurchaseFiat = Fiat.parseFiat(Constants.USD_CURRENCY, maximumCardPurchase.toString())
            updatePurchaseLimits()
        }
    }

    fun withinLimits(purchaseAmount: Coin): Boolean {
        return giftCardMerchant?.let { merchant ->
            if (merchant.fixedDenomination) {
                true
            } else {
                !purchaseAmount.isLessThan(minCardPurchaseCoin) &&
                    !purchaseAmount.isGreaterThan(maxCardPurchaseCoin)
            }
        } ?: false
    }

    fun withinLimits(purchaseAmount: Fiat): Boolean {
        return giftCardMerchant?.let { merchant ->
            if (merchant.fixedDenomination) {
                true
            } else {
                !purchaseAmount.isLessThan(minCardPurchaseFiat) &&
                    !purchaseAmount.isGreaterThan(maxCardPurchaseFiat)
            }
        } ?: false
    }

    private fun updatePurchaseLimits() {
        _exchangeRate.value?.let {
            val myRate = org.bitcoinj.utils.ExchangeRate(it.fiat)
            minCardPurchaseCoin = myRate.fiatToCoin(minCardPurchaseFiat)
            maxCardPurchaseCoin = myRate.fiatToCoin(maxCardPurchaseFiat)
        }
    }

    suspend fun isUserSignedInCTXSpend() = repository.isUserSignedIn()

    suspend fun signInToCTXSpend(email: String) = repository.login(email)

    suspend fun verifyEmail(code: String) = repository.verifyEmail(code)

    suspend fun logout() = repository.logout()

    fun saveGiftCardDummy(txId: Sha256Hash, giftCardId: String) {
        val giftCard = GiftCard(
            txId = txId,
            merchantName = giftCardMerchant?.name ?: "",
            price = giftCardPaymentValue.value.toBigDecimal().toDouble(),
            merchantUrl = giftCardMerchant?.website,
            note = giftCardId
        )
        viewModelScope.launch {
            giftCardDao.insertGiftCard(giftCard)
        }
    }

    fun needsCrowdNodeWarning(dashAmount: String): Boolean {
        val outputAmount = Coin.parseCoin(dashAmount)
        return try {
            walletDataProvider.checkSendingConditions(null, outputAmount)
            false
        } catch (_: LeftoverBalanceException) {
            true
        }
    }

    fun setIsFixedDenomination(isFixed: Boolean) {
        _isFixedDenomination.value = isFixed
    }

    fun setGiftCardPaymentValue(fiat: Fiat) {
        _giftCardPaymentValue.value = fiat
    }

    fun setGiftCardPaymentValue(coin: Coin) {
        _exchangeRate.value?.let {
            val myRate = org.bitcoinj.utils.ExchangeRate(it.fiat)
            _giftCardPaymentValue.value = myRate.coinToFiat(coin)
        }
    }

    fun resetSelectedDenomination() {
        _giftCardPaymentValue.value = Fiat.valueOf(Constants.USD_CURRENCY, 0)
    }

    fun logEvent(eventName: String) {
        analytics.logEvent(eventName, mapOf())
    }

    suspend fun checkToken(): Boolean {
        return try {
            !repository.isUserSignedIn() || repository.refreshToken()
        } catch (ex: Exception) {
            false
        }
    }

    fun createEmailIntent(
        subject: String,
        ex: CTXSpendException
    ) = Intent(Intent.ACTION_SEND).apply {
        setType("message/rfc822")
        putExtra(Intent.EXTRA_EMAIL, arrayOf(CTXSpendConstants.REPORT_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, createReportEmail(ex))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun getSavedMerchantId(): String? {
        return savedStateHandle.get<String>(MERCHANT_ID_KEY)
    }

    private fun createReportEmail(ex: CTXSpendException): String {
        val report = StringBuilder()
        report.append("CTX Issue Report").append("\n")
        giftCardMerchant?.let { merchant ->
            report.append("Merchant details").append("\n")
                .append("name: ").append(merchant.name).append("\n")
                .append("id: ").append(merchant.merchantId).append("\n")
                .append("min: ").append(merchant.minCardPurchase).append("\n")
                .append("max: ").append(merchant.maxCardPurchase).append("\n")
                .append("discount: ").append(merchant.savingsFraction).append("\n")
                .append("denominations type: ").append(merchant.denominationsType).append("\n")
                .append("denominations: ").append(merchant.denominations).append("\n")
                .append("\n")
        } ?: run {
            report.append("No merchant selected").append("\n")
        }
        report.append("\n")
        report.append("Purchase Details").append("\n")
        report.append("amount: ").append(giftCardPaymentValue.value.toFriendlyString()).append("\n")
        report.append("\n")
        ex.errorCode?.let {
            report.append("code: ").append(it).append("\n")
        }
        ex.errorBody?.let {
            report.append("body:\n").append(it).append("\n")
        }
        return report.toString()
    }

    suspend fun getMerchantById(merchantId: String): Merchant? = withContext(Dispatchers.IO) {
        exploreDao.getMerchantById(merchantId)
    }
}
