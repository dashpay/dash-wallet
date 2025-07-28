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

package org.dash.wallet.features.exploredash.ui.dashspend

import android.content.Intent
import androidx.lifecycle.*
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import org.bitcoinj.uri.BitcoinURI
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.services.*
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProvider
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.DenominationType
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.GetMerchantResponse
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderType
import org.dash.wallet.features.exploredash.data.dashspend.model.GiftCardInfo
import org.dash.wallet.features.exploredash.data.explore.GiftCardDao
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderDao
import org.dash.wallet.features.exploredash.data.dashspend.model.GiftCardStatus
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.repository.CTXSpendException
import org.dash.wallet.features.exploredash.repository.CTXSpendRepository
import org.dash.wallet.features.exploredash.repository.DashSpendRepository
import org.dash.wallet.features.exploredash.repository.DashSpendRepositoryFactory
import org.dash.wallet.features.exploredash.repository.PiggyCardsRepository
import org.dash.wallet.features.exploredash.utils.CTXSpendConstants
import org.slf4j.LoggerFactory
import javax.inject.Inject

data class DashSpendState(
    val email: String? = null,
    val isLoggedIn: Boolean = false
)

@HiltViewModel
class DashSpendViewModel @Inject constructor(
    private val walletDataProvider: WalletDataProvider,
    exchangeRates: ExchangeRatesProvider,
    var configuration: Configuration,
    private val sendPaymentService: SendPaymentService,
    private val repositoryFactory: DashSpendRepositoryFactory,
    private val ctxSpendRepository: CTXSpendRepository,
    private val piggyCardsRepository: PiggyCardsRepository,
    private val transactionMetadata: TransactionMetadataProvider,
    private val giftCardDao: GiftCardDao,
    private val giftCardProviderDao: GiftCardProviderDao,
    networkState: NetworkStateInt,
    private val analytics: AnalyticsService
) : ViewModel() {

    companion object {
        private val log = LoggerFactory.getLogger(DashSpendViewModel::class.java)
    }

    var selectedProvider: GiftCardProviderType? = null
    private var stateTrackingJob: Job? = null

    private val providers: Map<GiftCardProviderType, DashSpendRepository> by lazy {
        mapOf(
            GiftCardProviderType.CTX to repositoryFactory.create(GiftCardProviderType.CTX),
            GiftCardProviderType.PiggyCards to repositoryFactory.create(GiftCardProviderType.PiggyCards)
        )
    }

    val dashFormat: MonetaryFormat
        get() = configuration.format

    private val _balance = MutableLiveData<Coin>()
    val balance: LiveData<Coin>
        get() = _balance

    val balanceWithDiscount: Coin?
        get() = _balance.value?.let {
            val d = giftCardMerchant.savingsFraction
            return Coin.valueOf((it.value / (1.0 - d)).toLong()).minus(Transaction.DEFAULT_TX_FEE.multiply(20))
        }

    private val _dashSpendState = MutableStateFlow(DashSpendState())
    val dashSpendState: StateFlow<DashSpendState>
        get() = _dashSpendState.asStateFlow()

    private val _exchangeRate: MutableLiveData<ExchangeRate> = MutableLiveData()
    val usdExchangeRate: LiveData<ExchangeRate>
        get() = _exchangeRate

    private val _isFixedDenomination = MutableStateFlow<Boolean?>(null)
    val isFixedDenomination: StateFlow<Boolean?> = _isFixedDenomination.asStateFlow()

    private val _giftCardPaymentValue = MutableStateFlow<Fiat>(Fiat.valueOf(Constants.USD_CURRENCY, 0))
    val giftCardPaymentValue: StateFlow<Fiat> = _giftCardPaymentValue.asStateFlow()

    val isNetworkAvailable = networkState.isConnected.asLiveData()

    lateinit var giftCardMerchant: Merchant

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
    }

    suspend fun purchaseGiftCard(): GiftCardInfo = withContext(Dispatchers.IO) {
        giftCardMerchant.merchantId?.let {
            val amountValue = giftCardPaymentValue.value
            val provider = giftCardProviderDao.getProviderByMerchantId(it, selectedProvider!!.name)
            when (selectedProvider) {
                GiftCardProviderType.CTX -> {
                    try {
                        val response = ctxSpendRepository.purchaseGiftCard(
                            merchantId = provider!!.sourceId,
                            fiatAmount = MonetaryFormat.FIAT.noCode().format(amountValue).toString(),
                            fiatCurrency = "USD",
                            cryptoCurrency = Constants.DASH_CURRENCY
                        )
                        // return value
                        GiftCardInfo(
                            response.id,
                            status = GiftCardStatus.valueOf(response.status.uppercase()),
                            cryptoAmount = response.cryptoAmount,
                            cryptoCurrency = response.cryptoCurrency,
                            paymentCryptoNetwork = response.paymentCryptoNetwork,
                            paymentId = response.paymentId,
                            percentDiscount = response.percentDiscount,
                            rate = response.rate,
                            redeemUrl = response.redeemUrl,
                            fiatAmount = response.fiatAmount,
                            fiatCurrency = response.fiatCurrency,
                            paymentUrls = response.paymentUrls,
                        )
                    } catch (e: Exception) {
                        log.error("purchaseGiftCard error", e)
                        throw CTXSpendException("purchaseGiftCard error: ${e.message}", null, null)
                    }
                }
                GiftCardProviderType.PiggyCards -> {
                    try {
                        val orderResponse = piggyCardsRepository.purchaseGiftCard(
                            merchantId = provider!!.sourceId,
                            fiatAmount = MonetaryFormat.FIAT.noCode().format(amountValue).toString(),
                            fiatCurrency = Constants.USD_CURRENCY
                        )
                        delay(250)
                        val response = piggyCardsRepository.getGiftCardById(orderResponse.id)
                        if (response == null) {
                            throw Exception("invalid order number ${orderResponse.id}")
                        }
                        //val giftCardData = response.data
                        // val firstCardData = response.data.cards.first()
                        val uri = BitcoinURI(orderResponse.payTo)
                        // return value
                        GiftCardInfo(
                            id = orderResponse.id,
                            status = response.status,
                            cryptoAmount = uri.amount.toPlainString(),
                            cryptoCurrency = "DASH", // need a constant
                            paymentCryptoNetwork = "DASH",
                            // percentDiscount = giftCardMerchant.savingsPercentage.toString(),
                            rate = usdExchangeRate.value?.rate.toString(),
                            fiatAmount = giftCardPaymentValue.value.toPlainString(),
                            fiatCurrency = "USD",
                            paymentUrls = hashMapOf("DASH.DASH" to orderResponse.payTo)
                        )
                    } catch (e: Exception) {
                        log.error("purchaseGiftCard error", e)
                        throw CTXSpendException("purchaseGiftCard error: ${e.message}", null, null)
                    }
                }

                null -> {
                    throw IllegalArgumentException("no giftcard provider")
                }
            }
        }
        throw CTXSpendException("purchaseGiftCard error: merchantId is null")
    }

    suspend fun createSendingRequestFromDashUri(paymentUri: String): Sha256Hash = withContext(Dispatchers.IO) {
        val transaction = sendPaymentService.payWithDashUrl(paymentUri)
        log.info("ctx spend transaction: ${transaction.txId}")
        transactionMetadata.markGiftCardTransaction(transaction.txId, selectedProvider!!.serviceName, giftCardMerchant.logoLocation)
//        transaction.memo?.let { memo ->
//            transactionMetadata.setTransactionMemo(transaction.txId, memo)
//        }
        BitcoinURI(paymentUri).message?.let { memo ->
            if (memo.isNotBlank()) {
                transactionMetadata.setTransactionMemo(transaction.txId, memo)
            }
        }
        transaction.txId
    }

    // needs to get all data, but each provider may have different limites
    suspend fun updateMerchantDetails(merchant: Merchant) {
        // previously this API call would only be made if we didn't have the min and max card values,
        // but now we need to call this every time to get an updated savings percentage and to see if
        // the merchant is enabled
        val response = try {
            getMerchant(merchant, selectedProvider!!.name)
        } catch (ex: Exception) {
            log.error("failed to get merchant ${merchant.merchantId}", ex)
            null
        }

        try {
            response?.apply {
                merchant.savingsPercentage = this.savingsPercentage // differs between providers
                merchant.minCardPurchase = this.minimumCardPurchase // might differ between providers
                merchant.maxCardPurchase = this.maximumCardPurchase // might differ between providers
                // TODO: re-enable fixed denoms
                merchant.active = this.enabled || this.denominationType == DenominationType.Fixed // might differ between providers
                merchant.fixedDenomination = this.denominationType == DenominationType.Fixed // might differ between providers
                merchant.denominations = this.denominations.map { it.toInt() } // might differ between providers
                merchant.giftCardProviders = merchant.giftCardProviders.mapIndexed { index, giftCardProvider ->
                    if (index == 0 && selectedProvider?.name == "CTX") {
                        giftCardProvider.copy(
                            savingsPercentage = this.savingsPercentage,
                            active = this.enabled
                        )
                    } else if (index == 0 && selectedProvider?.name == "PiggyCards") {
                        giftCardProvider.copy(
                            savingsPercentage = this.savingsPercentage,
                            active = this.enabled
                        )
                    } else {
                        giftCardProvider
                    }

                }
            }
        } catch (e: Exception) {
            log.warn("updated merchant details contains unexpected data:", e)
        }
    }

    // needs to get all data, but each provider may have different limites
    suspend fun updateMerchantDetailsForAllProviders(merchant: Merchant) {
        // previously this API call would only be made if we didn't have the min and max card values,
        // but now we need to call this every time to get an updated savings percentage and to see if
        // the merchant is enabled
        val response = try {
            getMerchants(merchant)
        } catch (ex: Exception) {
            log.error("failed to get merchant ${merchant.merchantId}", ex)
            null
        }

        try {
            response?.apply {
                merchant.savingsPercentage = this.first.maxOf { it.savingsPercentage } // differs between providers
                // TODO: re-enable fixed denoms
                merchant.active = this.first.any { it.enabled }
                merchant.giftCardProviders = this.second.mapIndexed { index, giftCardProvider ->
                    giftCardProvider.copy(
                        savingsPercentage = this.first[index].savingsPercentage,
                        active = this.first[index].enabled
                    )
                }

            }
        } catch (e: Exception) {
            log.warn("updated merchant details contains unexpected data:", e)
        }
    }

    private suspend fun getMerchant(merchant: Merchant, provider: String): GetMerchantResponse? {
        val giftCardProvider = giftCardProviderDao.getProviderByMerchantId(merchant.merchantId!!, provider)
        return if (giftCardProvider != null ) {
            when (giftCardProvider.provider) {
                "CTX" -> {
                    if (ctxSpendRepository.isUserSignedIn()) {
                        ctxSpendRepository.getMerchant(giftCardProvider.sourceId)
                    } else {
                        null
                    }
                }

                "PiggyCards" -> {
                    if (piggyCardsRepository.isUserSignedIn()) {
                        piggyCardsRepository.getMerchant(giftCardProvider.sourceId)
                    } else {
                        null
                    }
                }

                else -> {
                    null
                }
            }
        } else {
            null
        }
    }

    private suspend fun getMerchants(merchant: Merchant): Pair<List<GetMerchantResponse>, List<GiftCardProvider>> {
        val providers = giftCardProviderDao.getProvidersByMerchantId(merchant.merchantId!!)
        val merchantResponseList = arrayListOf<GetMerchantResponse>()
        val providerResponseList = arrayListOf<GiftCardProvider>()
        providers.forEach { provider ->
            when (provider.provider) {
                "CTX" -> {
                    if (ctxSpendRepository.isUserSignedIn()) {
                        ctxSpendRepository.getMerchant(provider.sourceId)?.let {
                            merchantResponseList.add(it)
                            providerResponseList.add(
                                provider.copy(
                                    savingsPercentage = it.savingsPercentage,
                                )
                            )
                        }
                    }
                }

                "PiggyCards" -> {
                    if (piggyCardsRepository.isUserSignedIn()) {
                        piggyCardsRepository.getMerchant(provider.sourceId)?.let {
                            merchantResponseList.add(it)
                            providerResponseList.add(
                                provider.copy(
                                    savingsPercentage = it.savingsPercentage,
                                )
                            )
                        }
                    }
                }

                else -> {

                }
            }
        }
        return Pair(merchantResponseList, providerResponseList)
    }

    fun refreshMinMaxCardPurchaseValues() {
        val minCardPurchase = giftCardMerchant.minCardPurchase ?: 0.0
        val maximumCardPurchase = giftCardMerchant.maxCardPurchase ?: 0.0
        minCardPurchaseFiat = Fiat.parseFiat(Constants.USD_CURRENCY, minCardPurchase.toString())
        maxCardPurchaseFiat = Fiat.parseFiat(Constants.USD_CURRENCY, maximumCardPurchase.toString())
        updatePurchaseLimits()
    }

    fun withinLimits(purchaseAmount: Coin): Boolean {
        if (giftCardMerchant.fixedDenomination) {
            return true
        }

        return !purchaseAmount.isLessThan(minCardPurchaseCoin) &&
            !purchaseAmount.isGreaterThan(maxCardPurchaseCoin)
    }

    fun withinLimits(purchaseAmount: Fiat): Boolean {
        if (giftCardMerchant.fixedDenomination) {
            return true
        }

        return !purchaseAmount.isLessThan(minCardPurchaseFiat) &&
            !purchaseAmount.isGreaterThan(maxCardPurchaseFiat)
    }

    fun observeDashSpendState(provider: GiftCardProviderType?) {
        val serviceRepository = providers[provider]
        stateTrackingJob?.cancel()

        if (provider == null || serviceRepository == null) {
            return
        }

        stateTrackingJob = serviceRepository.userEmail
            .distinctUntilChanged()
            .onEach { email ->
                val isLoggedIn = serviceRepository.isUserSignedIn()
                _dashSpendState.value = _dashSpendState.value.copy(
                    email = email,
                    isLoggedIn = isLoggedIn
                )
            }.launchIn(viewModelScope)
    }

    suspend fun isUserSignedInService(provider: GiftCardProviderType): Boolean {
        return providers[provider]?.isUserSignedIn() == true
    }

    suspend fun signUp(provider: GiftCardProviderType, email: String): Boolean {
        return providers[provider]?.signup(email) == true
    }

    suspend fun signIn(provider: GiftCardProviderType, email: String): Boolean {
        return providers[provider]?.login(email) == true
    }

    suspend fun verifyEmail(provider: GiftCardProviderType, code: String): Boolean {
        return providers[provider]?.verifyEmail(code) == true
    }

    suspend fun logout(provider: GiftCardProviderType) {
        providers[provider]?.logout()
    }

    fun saveGiftCardDummy(txId: Sha256Hash, giftCardId: String) {
        val giftCard = GiftCard(
            txId = txId,
            merchantName = giftCardMerchant.name ?: "",
            price = giftCardPaymentValue.value.toBigDecimal().toDouble(),
            merchantUrl = giftCardMerchant.website,
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
            !ctxSpendRepository.isUserSignedIn() || ctxSpendRepository.refreshToken()
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

    fun createReportEmail(ex: CTXSpendException): String {
        val report = StringBuilder()
        report.append("CTX Issue Report").append("\n")
        if (this::giftCardMerchant.isInitialized) {
            report.append("Merchant details").append("\n")
                .append("name: ").append(giftCardMerchant.name).append("\n")
                .append("id: ").append(giftCardMerchant.merchantId).append("\n")
                .append("min: ").append(giftCardMerchant.minCardPurchase).append("\n")
                .append("max: ").append(giftCardMerchant.maxCardPurchase).append("\n")
                .append("discount: ").append(giftCardMerchant.savingsFraction).append("\n")
                .append("denominations type: ").append(giftCardMerchant.denominationsType).append("\n")
                .append("denominations: ").append(giftCardMerchant.denominations).append("\n")
                .append("\n")
        } else {
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

    private fun updatePurchaseLimits() {
        _exchangeRate.value?.let {
            val myRate = org.bitcoinj.utils.ExchangeRate(it.fiat)
            minCardPurchaseCoin = myRate.fiatToCoin(minCardPurchaseFiat)
            maxCardPurchaseCoin = myRate.fiatToCoin(maxCardPurchaseFiat)
        }
    }
}
